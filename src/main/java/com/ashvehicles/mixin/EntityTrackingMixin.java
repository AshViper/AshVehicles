package com.ashvehicles.mixin;

import java.util.Set;

import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.entity.DesignationEntity;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.entity.VehicleProjectile;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 機体と、その機体が撃った物を、周囲の世界の送信が止まったずっと後までプレイヤーへ送り続ける。
 *
 * <p>Minecraft はエンティティを誰に知らせるかを二重に決める。エンティティ型の追跡距離（プレイヤーの描画
 * 距離で頭打ち）と、そのプレイヤーがエンティティの chunk をロードしているかどうか。牛にはどちらも正解
 * で、高高度の機体にはどちらも不正解だ。機体は真下の地面よりずっと遠くから見えるのに、このままではロード
 * 済み範囲の縁を越えた瞬間に空から消える。バニラの描画距離を低くして Distant Horizons を使っている人ほど
 * 顕著で、そういう人こそ遠くの機体を眺めたい人でもある。
 *
 * <p>そこで機体については両方の制限を外し、代わりに自分の {@code ghost_range} を使う。その距離で届くの
 * は位置も向きも普通に付いた本物のエンティティで、プレイヤーが実際に見えている世界の外に出たら
 * {@link com.ashvehicles.client.renderer.AircraftRenderer} がゴーストとして描く。
 *
 * <p>弾やミサイルも同じ理由で同じ扱いにするが、距離はエンティティ型に登録された値のまま。あちらで効いて
 * いるのは描画距離による頭打ちの方だ。ミサイルは300ブロック先の相手を狙い着弾まで見る価値があるのに、
 * 描画距離8チャンクのクライアントでは120ブロックで見失う——撃った機体はまだはっきり見えているのに。
 *
 * <p>そして照準ポッドが保持している光点。これが最も極端な例で、マークが置かれるのは望遠鏡越しに見ている
 * 地面、つまり誰もロードしておらず、しばしば1km以上先の地面だ。バニラのままではパイロットは指示した直後
 * に「何も保持していない」と告げられる。計器も、ポッドカメラも、マークを手放すキーも、まず機体に何を
 * 保持しているか訊き、次に世界へその位置を訊くが、こちら側の世界はそれを知らされないまま。描画物は無い
 * ので、コストは目標を持っている間の毎秒1個の位置パケットだけ。
 *
 * <p>それ以外は一切そのまま。エンティティ側がそのプレイヤーへの送信を望んでいる必要はあるし、ゲーム中の
 * 他のエンティティは全部バニラの経路を通る。
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class EntityTrackingMixin {
    // 実フィールドと同じアクセス修飾子で宣言する。mixin はそこを照合する。
    @Shadow
    @Final
    ServerEntity serverEntity;
    @Shadow
    @Final
    Entity entity;
    @Shadow
    @Final
    private Set<ServerPlayerConnection> seenBy;

    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
    private void ashvehicles$trackBeyondTheWorld(ServerPlayer player, CallbackInfo callback) {
        if (player == this.entity) {
            return;
        }

        if (this.entity instanceof VehicleEntityBase machine) {
            callback.cancel();
            this.ashvehicles$report(player, withinGhostRange(machine, player));
        } else if (this.entity instanceof VehicleProjectile || this.entity instanceof DesignationEntity) {
            callback.cancel();
            // エンティティ型に登録された距離をブロック単位にしただけ。外すべきはプレイヤーの描画距離
            // による頭打ちであって、距離そのものではない。
            this.ashvehicles$report(player,
                    this.ashvehicles$within(player, this.entity.getType().clientTrackingRange() * 16.0));
        }
    }

    /** 見えるかどうかを自前で決めた後の、バニラ本来の登録処理。 */
    private void ashvehicles$report(ServerPlayer player, boolean inRange) {
        if (inRange && this.entity.broadcastToPlayer(player)) {
            if (this.seenBy.add(player.connection)) {
                this.serverEntity.addPairing(player);
            }
        } else if (this.seenBy.remove(player.connection)) {
            this.serverEntity.removePairing(player);
        }
    }

    /**
     * このプレイヤーが、その機体の情報を受け取り続けるだけ近くにいるか。
     *
     * <p>ファイルに上限を書いていない機体は常に「十分近い」。同じワールドにいる限りどこにいても送られる。
     * これはワールドについての判断ではなく数種類のエンティティ型についての判断で、機体の数は元々多く
     * ないので、コストはワールドの広さに比例する何かではなく1機あたり毎tick 1個の位置パケット。
     *
     * <p>戦車がここにいる理由は機体と同じ。ただし少し分かりにくい。戦車は飛ばないが、戦車が乗っている
     * 地面は戦車と同じ距離から見えるし、2km 先の谷を渡る車列こそ Distant Horizons を入れた人が眺めている
     * 物だ。バニラに任せればロード済み chunk の縁で消える。
     */
    private boolean withinGhostRange(VehicleEntityBase machine, ServerPlayer player) {
        VehicleChassis.Hitbox hitbox = machine.hitbox();

        return !hitbox.hasGhostLimit() || this.ashvehicles$within(player, hitbox.ghostRange());
    }

    /** バニラの追跡判定と同じ水平距離。高さは関係しない。 */
    private boolean ashvehicles$within(ServerPlayer player, double range) {
        double dx = player.getX() - this.entity.getX();
        double dz = player.getZ() - this.entity.getZ();

        return dx * dx + dz * dz <= range * range;
    }
}
