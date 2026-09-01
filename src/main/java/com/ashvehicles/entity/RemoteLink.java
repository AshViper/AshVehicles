package com.ashvehicles.entity;

import java.util.Set;

import com.ashvehicles.AshVehicles;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 無人機への遠隔リンク。操作者を機体へ繋ぎ、切れたら基地へ帰す。
 *
 * <p><b>リンクとは搭乗である。</b>「誰がどの機体を操作しているか」の台帳は持たない。操作者は無人機の
 * 搭乗者として——見えず、傷付かず、操作もできない乗員として——実際に機体に乗っており、それがリンクだ。
 * 乗っている限り繋がっているし、降りれば切れる。切れ方が「自分で切った」でも「機体が落とされた」でも
 * 「アルトキーを押した」でも、経路は1本しかない。
 *
 * <p><b>なぜ本当に乗せるのか。</b>操作者のクライアントに機体の周りの世界を描かせるためだ。Minecraft が
 * chunk を送るのはプレイヤーの周りだけで、カメラの周りではない。基地に座ったまま3km 先の映像を出す道は
 * 無く、あるように見せるには chunk 送信そのものを作り直すしかない。機体の位置にプレイヤーを置けば、
 * 世界も、カメラも、HUD も、照準ポッドも、何一つ手を入れずに動く。
 *
 * <p><b>だから「体は基地に残っていることにする」。</b>実装上そこにいなくても、プレイヤーから見た振る舞い
 * は遠隔操作そのものだ——繋いでいる間は何もできず、傷付かず、他人からは見えず、切れば元いた場所に立って
 * いる。機体が撃ち落とされても操作者は死なない。失うのは機体だけで、それが無人機というものである。
 *
 * <p><b>帰る場所はプレイヤーが持つ。</b>機体ではなく。機体は落ちるし、チャンクごと消えるし、再起動を
 * 跨ぐ。帰投点をそこに置けば、一番失いたくない情報を一番失われやすい場所に置くことになる。
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class RemoteLink {
    /** 帰投点を書き込むタグ名。プレイヤーの永続データの中。 */
    private static final String RETURN = AshVehicles.MODID + ":drone_return";

    private RemoteLink() {
    }

    // ------------------------------------------------------------------
    // 接続と切断
    // ------------------------------------------------------------------

    /**
     * この機体へ繋ぐ。繋げたら true。
     *
     * <p>断る理由は全部「その機体は今操作できない」に集約される——無人機ではない、残骸である、既に誰かが
     * 繋いでいる、操作者が既に何かに乗っている、別のワールドにいる。距離では断らない。この MOD の機体は
     * 元々どれだけ離れても全クライアントへ届いており（{@code EntityTrackingMixin}）、そこに人工的な上限を
     * 足す理由が無い。
     */
    public static boolean connect(ServerPlayer operator, AircraftEntity drone) {
        if (!drone.isUnmanned() || drone.isWrecked() || !drone.isAlive()) {
            return false;
        }

        if (drone.getOperator() != null || operator.getVehicle() != null
                || operator.level() != drone.level()) {
            return false;
        }

        remember(operator);

        // force 付き。無人機は canAddPassenger で全員を断るので、ここだけがその判定を通り抜ける唯一の道だ。
        if (!operator.startRiding(drone, true)) {
            operator.getPersistentData().remove(RETURN);

            return false;
        }

        operator.setInvisible(true);
        operator.setInvulnerable(true);
        operator.fallDistance = 0.0F;

        return true;
    }

    /**
     * 繋いでいるものから切る。
     *
     * <p>降ろすだけで、帰投は {@link #onDismount} が受け持つ。切断・撃墜・アルトキー——どの経路も最後は
     * 降車であり、後始末を1か所に集めておけば「この降り方のときだけ基地へ戻らない」が起こり得ない。
     */
    public static void disconnect(ServerPlayer operator) {
        if (linkedDrone(operator) != null) {
            operator.stopRiding();
        }
    }

    /** この操作者が今繋いでいる無人機。繋いでいなければ null。 */
    public static AircraftEntity linkedDrone(Entity operator) {
        return operator.getVehicle() instanceof AircraftEntity drone && drone.isUnmanned() ? drone : null;
    }

    // ------------------------------------------------------------------
    // 切れたときの後始末
    // ------------------------------------------------------------------

    /**
     * 無人機から降りた。理由は問わない。
     *
     * <p>帰投は次のtickへ回す。この event はバニラの降車処理の途中——搭乗関係を解いている最中——に発火する
     * ので、その最中にプレイヤーを別のワールドへ飛ばすのは、家を解体している人の足元から床を抜くようなものだ。
     * サーバーは Executor なので、渡した処理は次のtickの安全な位置で走る。
     */
    @SubscribeEvent
    public static void onDismount(EntityMountEvent event) {
        if (!event.isDismounting() || !(event.getEntityMounting() instanceof ServerPlayer operator)) {
            return;
        }

        if (!(event.getEntityBeingMounted() instanceof AircraftEntity drone) || !drone.isUnmanned()) {
            return;
        }

        operator.getServer().execute(() -> recall(operator));
    }

    /**
     * 繋いだまま退出した。
     *
     * <p>ここで帰さないと、次に入ってくる場所が機体の最後の位置になる。しかもその機体は、留守の間に
     * 撃ち落とされているかもしれない——不可視・無敵のまま数kmの高さに現れて、そこから永遠に落ち続ける
     * プレイヤーが出来上がる。{@code PlayerLoggedOut} はセーブより先に走るので、ここで動かした位置が
     * そのまま保存される。
     */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer operator && linkedDrone(operator) != null) {
            operator.stopRiding();
            // 退出時は event を待てない。次のtickにこのプレイヤーはもういない。
            recall(operator);
        }
    }

    /**
     * 死亡や次元移動で体が作り直された。帰投点を新しい体へ持ち越す。
     *
     * <p>持ち越さなければ、リンク中に何らかの理由で死んだ操作者は帰る場所を失う。無敵にしてあるので普通は
     * 起こらないが、「普通は起こらない」を理由に情報を捨てると、起きたときに直しようが無くなる。
     */
    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        CompoundTag was = event.getOriginal().getPersistentData();

        if (was.contains(RETURN)) {
            event.getEntity().getPersistentData().put(RETURN, was.getCompound(RETURN).copy());
        }
    }

    // ------------------------------------------------------------------
    // 帰投点
    // ------------------------------------------------------------------

    /** 今立っている場所と、今の見え方・無敵状態を控えておく。 */
    private static void remember(ServerPlayer operator) {
        CompoundTag point = new CompoundTag();

        point.putString("Dimension", operator.level().dimension().location().toString());
        point.putDouble("X", operator.getX());
        point.putDouble("Y", operator.getY());
        point.putDouble("Z", operator.getZ());
        point.putFloat("Yaw", operator.getYRot());
        point.putFloat("Pitch", operator.getXRot());
        // 元の状態を控える。クリエイティブの無敵や、他の MOD が掛けた不可視を、こちらの後始末で勝手に
        // 解除しないため。戻すべきは「繋ぐ前」であって「既定値」ではない。
        point.putBoolean("WasInvisible", operator.isInvisible());
        point.putBoolean("WasInvulnerable", operator.isInvulnerable());

        operator.getPersistentData().put(RETURN, point);
    }

    /** 元の場所と元の状態へ戻す。控えが無ければ、状態だけ戻してその場に残す。 */
    private static void recall(ServerPlayer operator) {
        CompoundTag point = operator.getPersistentData().getCompound(RETURN);
        boolean known = point.contains("Dimension");

        operator.setInvisible(known && point.getBoolean("WasInvisible"));
        operator.setInvulnerable(known && point.getBoolean("WasInvulnerable"));
        // 数kmの高さから降ろされた体には、それだけの落下距離が溜まっている。
        operator.fallDistance = 0.0F;
        operator.setDeltaMovement(Vec3.ZERO);
        operator.getPersistentData().remove(RETURN);

        if (!known) {
            return;
        }

        ServerLevel home = operator.getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse(point.getString("Dimension"))));

        if (home == null) {
            return;
        }

        double x = point.getDouble("X");
        double y = point.getDouble("Y");
        double z = point.getDouble("Z");
        float yaw = point.getFloat("Yaw");
        float pitch = point.getFloat("Pitch");

        if (home == operator.level()) {
            operator.teleportTo(x, y, z);
            operator.connection.teleport(x, y, z, yaw, pitch);
        } else {
            operator.teleportTo(home, x, y, z, Set.of(), yaw, pitch);
        }
    }

    /**
     * 帰投点を控えているか。デバッグと、繋ぎ直しの判定に。
     *
     * <p>控えがあるのに乗っていない状態は、本来あり得ない——帰投が必ず消すからだ。残っていればそれは
     * 帰投が走らなかった証拠だ。
     */
    public static boolean hasReturnPoint(ServerPlayer operator) {
        return operator.getPersistentData().getCompound(RETURN).contains("Dimension");
    }
}
