package com.ashvehicles.match;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.ai.core.AiDirector;
import com.ashvehicles.ai.log.BattleEvents;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.entity.VehiclePart;
import com.ashvehicles.entity.VehicleProjectile;
import com.ashvehicles.network.DeployOpenPayload;
import com.ashvehicles.network.MatchStatePayload;
import com.ashvehicles.registry.ModBlocks;
import com.ashvehicles.registry.ModEntities;
import com.ashvehicles.vehicle.Attitude;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.RandomSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * チームデスマッチの進行。帳簿（{@link MatchState}）に対して起きることが全部ここにある。
 *
 * <p><b>機体は自分の陣営を持ち歩く。</b> 出撃した機体の {@code getPersistentData} に陣営 ID を書く
 * （{@link #TEAM_KEY}）。乗員から陣営を引く方法では足りない——パイロットが脱出した機体、置き去りの
 * 戦車、無人機のどれも乗員を持たないまま撃たれるし、撃破の瞬間に乗員がいるとも限らない。機体自身が
 * 名乗れば、味方撃ちの判定も得点もその1つの事実から出る。
 *
 * <p><b>撃破は1つの出口で数える。</b> {@link VehicleEntityBase#destroy} と、人が倒れたときの
 * {@link LivingDeathEvent}。弾・爆風・衝突・墜落のどれで終わったかに関わらずそこを通るので、
 * 兵装ごとに数える場所を増やさない。
 *
 * <p><b>陣営のチケットと、個人の出撃ポイントは別物。</b> チケットは陣営の残機で、尽きた側が負ける。
 * ポイントは1人ずつの財布で、何を出せるかを決める（{@link Costs}）。撃破すればポイントが入り、拠点を
 * 取れば中にいた者に入る——稼いだ者がいい機体を出し、落とされ続ける者は軽い機体へ降りていく。
 *
 * <p><b>勝敗はチケットで測る。</b> 機体か人を1つ失えば、失った側が1枚減らす——撃った側は増えない。
 * 誰が倒したかは読み上げのためだけにあり、勘定は「何を失ったか」だけで閉じている。拠点を置いた試合
 * では、握られている側が撃たれなくても減っていく（{@link #bleed}）。
 *
 * <p>試合が終わったら、その試合で湧かせた機体は残さず片付ける。片付けの目印も陣営タグで、これは
 * 「運営が置いた飾りの戦車」と「試合で出撃した戦車」を見分ける唯一の手掛かりでもある。
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class Deathmatch {
    /** 機体の persistent data に書く陣営 ID のキー。 */
    public static final String TEAM_KEY = "AshVehiclesMatchTeam";

    /** 掲示板を配る間隔。残り時間は秒でしか読めないので、それより細かく送る意味が無い。 */
    private static final int SYNC_INTERVAL = 20;

    /**
     * 拠点の中を数え直す間隔。
     *
     * <p>毎 tick 数える必要は無い。制圧は20秒掛かる仕事で、その 0.5 秒の粒度は誰にも見えない——
     * 見えるのは計器のバーで、あれは毎秒しか届かない。</p>
     */
    private static final int CAPTURE_INTERVAL = 10;

    /** 旗からどれだけ離れて出撃できるか。画面を開いた場所から歩き去られては困る。 */
    private static final double DEPLOY_RANGE = 12.0;

    /**
     * 旗からどれだけ離れた所まで湧くか（ブロック）。
     *
     * <p><b>旗の真上に固定しない。</b> 出撃地点は1つで、そこから出る者は何人もいる。同じ1マスに順に
     * 湧かせると、後から出た機体は前の機体に塞がれて出られず、開けていても翼と翼が重なった状態から
     * 始まる。円の中に散らせば、飛行場1つに1本の滑走路しか無くても同時に出られる。
     */
    private static final double SCATTER = 30.0;

    /**
     * 散らす場所を何回まで試すか。
     *
     * <p>1回ごとに機体の形が丸ごと入るかを確かめる（{@code hasRoomHere}）ので、爆撃機のように大きい機体
     * ほど外れる。全部外れたら最後に旗の真上へ落とす——そこは運営が均した場所であり、最初から確実に
     * 開けている唯一の1マスだ。
     */
    private static final int SCATTER_TRIES = 24;

    /** 湧かせる機体を旗の上へ持ち上げる高さ。ブロックの上面に車輪を置く。 */
    private static final double DEPLOY_LIFT = 1.0;

    /** 置ける空間を確かめるときの許容（ブロック）。{@code VehicleItem} と同じ理由で同じ値。 */
    private static final double PLACING_MARGIN = 0.25;

    /** 候補が収まらないとき、持ち上げて試す高さ（ブロック）。 */
    private static final int PLACING_LIFTS = 2;

    /** 陣地へ送り返すとき、旗からどれだけ散らすか（ブロック）。全員が同じ1マスに重ならないように。 */
    /** 全部外れたときに旗から離す距離（ブロック）。真上に積み上げないための最小限。 */
    private static final double LAST_RESORT = 3.0;

    private static final double RETURN_SCATTER = 4.0;

    /** この距離まで旗に近ければ「もう陣地にいる」。送り返しはそこで何もしない。 */
    private static final double AT_BASE = 48.0;

    /**
     * 航空機を出す位置——旗の後方と、旗から測った高さ（ブロック）。
     *
     * <p><b>航空機は飛んでいる状態で出す。</b> 滑走路を走って上がる遊びは、試合の1回目には楽しくても
     * 20回目には移動時間でしかない。1km 後方から出すのは、出た瞬間が敵の照準の中では困るからで、
     * そのぶんを飛んでから戦線に着く。
     *
     * <p>「後ろ」は旗のブロックの向きの逆。向きはブロックを置いた者が向いていた方角なので、戦線の方を
     * 向いて旗を置けば、機体は後方から前線へ向かって出る。
     */
    private static final double LAUNCH_BEHIND = 1000.0;
    private static final double LAUNCH_HEIGHT = 500.0;

    /**
     * 空中に出す機体の初速（km/h）。
     *
     * <p>km/h で書いてあるのは、これが計器に出る値だからだ。内部の単位はブロック/tick で、
     * {@code 1 blocks/tick = 72 km/h}（20 tick/秒 × 3.6）。
     */
    private static final float LAUNCH_SPEED_KMH = 200.0F;

    /** ブロック/tick を km/h にする係数。{@code AircraftHud} が計器に出すのと同じ換算。 */
    private static final float KMH_PER_BLOCK_TICK = 20.0F * 3.6F;

    /** 空中に出した機体のスロットル。出た瞬間からエンジンが回っている。 */
    private static final float LAUNCH_THROTTLE = 0.5F;

    private Deathmatch() {
    }

    // ------------------------------------------------------------------
    // 陣営タグ
    // ------------------------------------------------------------------

    /** その機体を陣営の物にする。試合が片付けるのもこのタグが付いた物だけ。 */
    public static void tag(Entity vehicle, MatchTeam team) {
        vehicle.getPersistentData().putString(TEAM_KEY, team.id());
    }

    /** その相手が名乗っている陣営 ID。人なら所属、機体ならタグ。無所属なら null。 */
    @Nullable
    public static String teamIdOf(MatchState state, Entity entity) {
        Entity subject = entity instanceof VehiclePart part && part.getParent() != null
                ? part.getParent() : entity;

        if (subject instanceof Player player) {
            MatchTeam team = state.teamOf(player.getUUID());

            return team == null ? null : team.id();
        }

        CompoundTag data = subject.getPersistentData();
        String tagged = data.getString(TEAM_KEY);

        if (!tagged.isEmpty()) {
            return tagged;
        }

        // タグの無い機体でも、今それを操縦している者がいれば陣営は決まる。試合前から置いてあった車両に
        // 誰かが乗って撃ち合いに加わる場合がそれで、得点を数えるには十分な事実だ。
        if (subject instanceof VehicleEntityBase machine && machine.getAviator() instanceof Player pilot) {
            MatchTeam team = state.teamOf(pilot.getUUID());

            return team == null ? null : team.id();
        }

        return null;
    }

    /** その打撃の背後にいる人。いなければ null。 */
    @Nullable
    public static Player attacker(DamageSource source) {
        return attackerOf(source) instanceof Player player ? player : null;
    }

    /**
     * その打撃を与えた側。人か、撃った機体そのもの。
     *
     * <p><b>人に限ってはいけない。</b> AI の車両が撃った弾には持ち主（{@code getOwner}）がいない——引き金を
     * 引いたのは人ではないからだ。人だけを見ていた頃は、AI が倒した相手が「誰にともなく失われた」ことに
     * なり、AI 同士の味方撃ちも素通りしていた。陣営を名乗るのは元から機体の側なので
     * （{@link #teamIdOf}）、ここが機体を返せば、得点も味方撃ちの門も人の時と同じ1本の道を通る。
     *
     * <p>弾からは発射元を引く。{@code DamageSource} が持つのは「撃った人」と「飛んできた物」の2つで、
     * 前者が空でも後者は自分がどの砲から出たかを知っている（{@link VehicleProjectile#firedFrom}）。
     */
    @Nullable
    public static Entity attackerOf(DamageSource source) {
        Entity entity = source.getEntity();

        if (entity == null && source.getDirectEntity() instanceof VehicleProjectile shot) {
            entity = shot.firedFrom();
        }

        if (entity == null) {
            entity = source.getDirectEntity();
        }

        if (entity instanceof VehiclePart part && part.getParent() != null) {
            entity = part.getParent();
        }

        if (entity instanceof Player player) {
            return player;
        }

        if (entity instanceof VehicleEntityBase machine) {
            return machine.getAviator() instanceof Player pilot ? pilot : machine;
        }

        return entity;
    }

    // ------------------------------------------------------------------
    // 味方撃ち
    // ------------------------------------------------------------------

    /**
     * 同士討ちを止めるか。試合中で、撃つ側と撃たれる側が同じ陣営で、運営が味方撃ちを許していないとき。
     *
     * <p>自分自身への打撃は見ない。自分の爆弾の爆風で自分を傷つけるのは味方撃ちではなく操縦の結果だ。
     */
    public static boolean protects(Entity target, DamageSource source) {
        if (target.level().isClientSide || target.getServer() == null) {
            return false;
        }

        MatchState state = MatchState.of(target.getServer());

        if (!state.isRunning() || state.friendlyFire()) {
            return false;
        }

        Entity attacker = attackerOf(source);

        if (attacker == null) {
            return false;
        }

        String mine = teamIdOf(state, attacker);
        String theirs = teamIdOf(state, target);

        if (mine == null || theirs == null || !mine.equals(theirs)) {
            return false;
        }

        // 自分が乗っている機体は自分だ。撃墜されかけた機体の中で味方撃ちの規則に守られる理由は無い。
        return attacker != target && !attacker.hasPassenger(target) && target != attacker.getVehicle();
    }

    /**
     * 今この世界で、爆発が地形を壊さないか。
     *
     * <p><b>問うのは爆発の側1箇所だけ。</b> 砲弾も爆弾もミサイルも機体の最期も、穴を開けるときは
     * {@code particle/Effects.blast} を通る——そこで爆発の種別を {@code KEEP} に替えれば、弾種ごとに
     * 判定を足して回る必要が無い。爆風のダメージは種別に関わらず起きるので、失われるのは地面だけだ。
     */
    public static boolean protectsTerrain(Level level) {
        MinecraftServer server = level.getServer();

        if (server == null) {
            return false;
        }

        MatchState state = MatchState.of(server);

        return state.isRunning() && !state.terrainDamage();
    }

    // ------------------------------------------------------------------
    // 撃破
    // ------------------------------------------------------------------

    /**
     * 機体が1機終わったことを試合に伝える。{@link VehicleEntityBase#destroy} から、撃破の原因に関わらず
     * 必ず1回だけ呼ばれる。
     */
    public static void onVehicleDestroyed(VehicleEntityBase vehicle, DamageSource source) {
        MinecraftServer server = vehicle.getServer();

        if (server == null) {
            return;
        }

        MatchState state = MatchState.of(server);

        if (!state.isRunning()) {
            return;
        }

        String victim = teamIdOf(state, vehicle);

        if (victim == null) {
            return;
        }

        // 乗っていた者を出撃待ちに入れる。降ろされるのは撃破の直後なので、ここが最後に全員を数えられる
        // 場所になる。
        for (Entity rider : vehicle.getIndirectPassengers()) {
            if (rider instanceof ServerPlayer player) {
                hold(state, player);
            }
        }

        // 読み上げるのは、倒した者がいる撃破の全部——AI 同士の撃破も含む（2026-09-13 の指示「AI がキルしてもキルログが
        // 流れるように」。以前は人が関わった撃破だけで、AI 同士の撃ち合いは黙っていた）。黙るのは倒した者のいない AI の
        // 損失（墜落・自分の爆風）だけ。数え方（チケット1枚）はどちらも同じ。
        Entity killer = attackerOf(source);
        boolean loud = !vehicle.isBot() || killer != null;

        credit(server, state, victim, killer, vehicle.getDisplayName(), loud);
        // 陣営が次に出す車両の見立て（{@code ai/team/Reinforcements}）へ、何に倒されたかを渡す。
        Bots.noteLoss(server, victim, vehicle, killer);
        // 戦闘 AI の記録と報酬。勘定（チケット）はここより上で済んでいて、こちらは何も変えない。
        BattleEvents.vehicleDestroyed(vehicle, source, killer);
    }

    /**
     * 試合中に壊れた機体の残骸が残る時間（tick）。30秒。
     *
     * <p>試合の外の5分（{@code VehicleEntityBase.WRECK_LIFETIME}）は、レンチを持った誰かが金属を持ち帰るため
     * の時間だ。試合にその遊びは無い——燃料も弾も満載で出る以上、兵站は試合の中では消してある。戦場に要るのは
     * 撃破が目に見えて伝わるだけの時間で、それより長く立つ黒い船体は、旗の周りに積もる遮蔽物と当たり判定と
     * tick になる。
     */
    public static final int WRECK_LIFETIME = 600;

    /**
     * その機体の残骸を何 tick 残すか。今が試合中なら {@link #WRECK_LIFETIME}、そうでなければ {@code outside}。
     *
     * <p><b>壊れた瞬間に1度だけ訊き、答えは残骸が持ち歩く</b>（{@code VehicleEntityBase.wreck}）。試合中かを
     * 残骸の tick ごとに訊くと、試合が終わった瞬間に戦場の残骸が5分へ延び、試合前から転がっていた残骸が
     * 開始と同時に消える。
     *
     * <p>陣営は問わない。試合の最中に壊れた物は、試合前から置いてあった無所属の車両でも戦場の残骸だ。
     */
    public static int wreckLifetime(VehicleEntityBase vehicle, int outside) {
        MinecraftServer server = vehicle.getServer();

        return server != null && MatchState.of(server).isRunning() ? WRECK_LIFETIME : outside;
    }

    /**
     * 生き物に向かう味方撃ちを止める。機体は生き物ではないので自分の {@code hurt} で同じ判定を通す
     * （{@link VehicleEntityBase#hurt}）——生き物の側だけを見る仕組みでは、味方の戦車を撃てる。
     */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (protects(event.getEntity(), event.getSource())) {
            event.setCanceled(true);
        }
    }

    /** 人が倒れたとき。機体の外で撃ち合う歩兵も同じ勘定に入る。 */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer fallen)) {
            return;
        }

        MatchState state = MatchState.of(fallen.server);

        if (!state.isRunning()) {
            return;
        }

        String victim = teamIdOf(state, fallen);

        if (victim == null) {
            return;
        }

        // 既に出撃待ちに入っている者の死は、同じ1つの損失の続きだ。撃墜されて落ちていく機体から投げ
        // 出され、地面で死ぬ——それで2枚取られては、脱出装置を引いた者が損をする。待ちが明けて
        // （陣地へ戻って）からの死は別の損失なので、そちらは普通に数える。
        boolean already = state.readyAt(fallen.getUUID()) > 0;

        hold(state, fallen);

        if (!already) {
            Entity killer = attackerOf(event.getSource());

            credit(fallen.server, state, victim, killer, fallen.getDisplayName(), true);
            BattleEvents.playerKilled(fallen, killer);
        }
    }

    /**
     * 1つの撃破を帳簿に付ける。
     *
     * <p><b>減るのは失った側のチケットで、増えるものは何も無い。</b> 撃たれて落ちた機体も、山に突っ込んだ
     * 機体も、味方に撃たれた機体も、失われた1機であることに変わりはない。得点制なら「誰の手柄か」を
     * 決めないと数えられないが、チケットなら決めなくてよい——そして墜落と自爆を勘定に入れられる。
     *
     * <p>倒した側に付くのは記録だけ（{@code MatchTeam.kills}）。勝敗には関わらない。
     */
    private static void credit(MinecraftServer server, MatchState state, String victimTeam,
            @Nullable Entity attacker, Component victim, boolean loud) {
        MatchTeam fallen = state.team(victimTeam);

        if (fallen == null) {
            return;
        }

        fallen.spendTickets(state.lossCost());

        String killerTeam = attacker == null ? null : teamIdOf(state, attacker);
        MatchTeam scoring = killerTeam == null ? null : state.team(killerTeam);

        if (scoring == null) {
            if (loud) {
                announce(server, Component.translatable("message.ashvehicles.match.lost", fallen.display(),
                        victim, fallen.tickets()));
            }
        } else if (scoring == fallen) {
            if (loud) {
                announce(server, Component.translatable("message.ashvehicles.match.own_goal",
                        attacker.getDisplayName(), victim, fallen.tickets()));
            }
        } else {
            scoring.addKill();

            if (attacker instanceof Player paid) {
                state.award(paid.getUUID(), state.killAward());

                if (paid instanceof ServerPlayer told && state.killAward() > 0) {
                    told.displayClientMessage(Component.translatable("message.ashvehicles.match.earned",
                            state.killAward(), state.pointsOf(told.getUUID())), true);
                }
            }

            if (loud) {
                announce(server, Component.translatable("message.ashvehicles.match.kill",
                        attacker.getDisplayName(), scoring.display(), victim, fallen.display(),
                        fallen.tickets()));
            }
        }

        state.setDirty();
        sync(server, state);

        if (fallen.isOut()) {
            finish(server, state, leader(state));
        }
    }

    /**
     * その者を出撃待ちに入れ、陣地へ帰す予約を立てる。
     *
     * <p><b>待ち時間が0でも予約は立てる。</b> この印は2つの役目を持っている——次に出撃してよい時刻と、
     * 陣地へ送り返す合図（{@link #returnWaiting}）だ。0秒のときに印を置かないと、自分の機体を墜とした
     * 者が、敵陣の真ん中に立ったまま誰にも回収されない。
     */
    private static void hold(MatchState state, ServerPlayer player) {
        state.setReadyAt(player.getUUID(), player.level().getGameTime() + state.respawnTicks());
    }

    // ------------------------------------------------------------------
    // 出撃
    // ------------------------------------------------------------------

    /** 出撃の答え。断った理由はそのまま画面に出す1行になる。 */
    public record Deployed(boolean happened, Component message) {
        static Deployed no(String key, Object... args) {
            return new Deployed(false, Component.translatable(key, args));
        }
    }

    /**
     * 旗の上に1機出し、頼んだ者を乗せる。
     *
     * <p>断る理由は5つ——試合が動いていない、無所属、その旗が自陣の物でない、出撃待ちが明けていない、
     * 旗が塞がっている。どれも画面が事前に防げるものだが、届いたパケットは誰でも作れるので、ここが
     * 最後の門になる。
     */
    public static Deployed deploy(ServerPlayer player, BlockPos flag, Loadout loadout) {
        MinecraftServer server = player.server;
        MatchState state = MatchState.of(server);

        if (!state.isRunning()) {
            return Deployed.no("message.ashvehicles.match.not_running");
        }

        MatchTeam team = state.teamOf(player.getUUID());

        if (team == null) {
            return Deployed.no("message.ashvehicles.match.no_team");
        }

        // 出撃地点ブロックと、自陣が握っている拠点。どちらからでも出られる。
        if (state.deployOwner(flag) != team) {
            return Deployed.no("message.ashvehicles.match.not_your_spawn");
        }

        if (player.distanceToSqr(flag.getX() + 0.5, flag.getY() + 0.5, flag.getZ() + 0.5)
                > DEPLOY_RANGE * DEPLOY_RANGE) {
            return Deployed.no("message.ashvehicles.match.too_far");
        }

        long wait = state.readyAt(player.getUUID()) - player.level().getGameTime();

        if (wait > 0) {
            return Deployed.no("message.ashvehicles.match.waiting", (int) Math.ceil(wait / 20.0));
        }

        // 乗れない物——無人機と牽引砲——は出撃の対象ではない。画面にも出さないが、届いた注文書は誰でも
        // 作れる。
        if (!Loadout.deployable(loadout.vehicle())) {
            return Deployed.no("message.ashvehicles.match.not_deployable");
        }

        ServerLevel level = player.serverLevel();
        VehicleEntityBase vehicle = create(level, loadout.vehicle());

        if (vehicle == null) {
            return Deployed.no("message.ashvehicles.match.unknown_vehicle", loadout.vehicle().toString());
        }

        // 値段の確認だけ先に。引くのは機体が実際に置けてからで、置けなかった出撃で財布が減っては困る。
        int cost = Costs.of(loadout.vehicle());

        if (state.pointsOf(player.getUUID()) < cost) {
            return Deployed.no("message.ashvehicles.match.too_poor", cost,
                    state.pointsOf(player.getUUID()));
        }

        // 機首の向きは旗が決める。滑走路と平行に置かれた旗から出る機体は滑走路を向く——出撃のたびに
        // 自分の立ち方で向きが変わるのでは、並んで離陸できない。
        BlockState flagState = level.getBlockState(flag);
        float yaw = flagState.hasProperty(HorizontalDirectionalBlock.FACING)
                ? flagState.getValue(HorizontalDirectionalBlock.FACING).toYRot()
                : player.getYRot();

        boolean floats = vehicle instanceof GroundVehicleEntity ship && ship.getStats().isShip();

        if (!place(level, flag, vehicle, yaw, floats)) {
            // 艦が置けない理由はほぼ必ず「水が無い」だ。塞がっていると言われた運営が、開けた岸を
            // 探して回る羽目にならないように分けて言う。
            return Deployed.no(floats
                    ? "message.ashvehicles.match.needs_water"
                    : "message.ashvehicles.match.spawn_blocked");
        }

        if (vehicle instanceof AircraftEntity aircraft) {
            loadout.apply(aircraft);
        }

        vehicle.rearm();
        tag(vehicle, team);
        state.spend(player.getUUID(), cost);
        // 前の1機は消す。乗り換えは自由でも、置き去りは自由ではない。
        retire(level, state, player.getUUID());
        level.addFreshEntity(vehicle);
        state.setMachine(player.getUUID(), vehicle.getUUID());

        // 既に何かに乗っているなら降りる。前の機体を捨てて次に乗り換えるのは試合では普通の動きで、
        // 断る理由にはならない。
        player.stopRiding();
        player.startRiding(vehicle, true);

        level.playSound(null, vehicle.blockPosition(), SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS,
                0.7F, 0.8F);
        sync(server, state);

        return new Deployed(true, Component.translatable("message.ashvehicles.match.deployed",
                vehicle.getDisplayName(), cost, state.pointsOf(player.getUUID())));
    }

    /**
     * 旗のまわりのどこかへ機体を置く。置けたら true。
     *
     * <p><b>選ぶのは円の中で、面積に対して均す。</b> 半径を一様に選ぶと中心付近が混む。半径に平方根を
     * 掛けるのはそのためで、これで旗の周り30ブロックのどこも同じ確率になる。
     *
     * <p><b>地面はハイトマップに訊き、ロードされていない場所は捨てる。</b> {@code getChunkNow} は読める
     * chunk か null しか返さないので、ここで地形生成が始まることはない——tick スレッドの上でワールドを
     * 作る処理は、この MOD が一番避けてきたものだ（[[explosions-generate-chunks]] と同じ理由）。
     *
     * <p>艦は水面だけ、それ以外は水面以外だけ。海の真ん中に湧いた戦車も、砂浜に乗り上げた駆逐艦も、
     * 出撃としては成立しない。
     */
    static boolean place(ServerLevel level, BlockPos flag, VehicleEntityBase vehicle, float yaw,
            boolean floats) {
        return place(level, flag, vehicle, yaw, floats, true);
    }

    /**
     * @param onFlag 全部外れたときに旗の真上へ落としてよいか。<b>人には真、AI には偽。</b> 出撃を待って
     *               いる人を「置ける場所が無い」で追い返すわけにはいかないが、AI は2秒後にまた試せる
     *               ——そして旗の真上に落とし続けると、<b>20両が旗の上に積み上がる</b>
     */
    static boolean place(ServerLevel level, BlockPos flag, VehicleEntityBase vehicle, float yaw,
            boolean floats, boolean onFlag) {
        if (vehicle instanceof AircraftEntity aircraft) {
            return launch(level, flag, aircraft, yaw);
        }

        RandomSource random = level.getRandom();

        for (int attempt = 0; attempt <= SCATTER_TRIES; attempt++) {
            if (attempt == SCATTER_TRIES && !onFlag) {
                return false;
            }

            double x;
            double z;
            double y;

            if (attempt == SCATTER_TRIES) {
                // 最後の1回は旗のすぐ脇。ここだけはハイトマップに訊かない——格納庫の中に置かれた旗で、
                // 屋根の上に機体が湧いては困る。<b>真上ではなく数ブロックずらす</b>のは、ここへ落ちるのが
                // 1両とは限らないからだ。
                double angle = random.nextDouble() * Math.PI * 2.0;

                x = flag.getX() + 0.5 + Math.cos(angle) * LAST_RESORT;
                z = flag.getZ() + 0.5 + Math.sin(angle) * LAST_RESORT;
                y = flag.getY() + DEPLOY_LIFT;

                if (floats && !level.getFluidState(flag.above()).is(FluidTags.WATER)) {
                    return false;
                }
            } else {
                double angle = random.nextDouble() * Math.PI * 2.0;
                double radius = Math.sqrt(random.nextDouble()) * SCATTER;

                x = flag.getX() + 0.5 + Math.cos(angle) * radius;
                z = flag.getZ() + 0.5 + Math.sin(angle) * radius;

                double ground = groundAt(level, x, z, floats);

                if (Double.isNaN(ground)) {
                    continue;
                }

                y = ground;
            }

            if (settles(vehicle, x, y, z, yaw)) {
                return true;
            }
        }

        return false;
    }

    /**
     * その場所に機体が収まるか。収まるなら置いたまま true。
     *
     * <p><b>地表より下のブロックは床であって障害物ではない。</b> 車体の形は全部が履帯より上にあるわけでは
     * なく、起伏のある地面では必ずどこかが地表線を割る——素直に問えば「重なっている」と答えるので、
     * 開けた草原でも候補が全部外れる。{@code Hitboxes.clearOfBlocks} の underside がその線だ。
     *
     * <p>それでも収まらなければ1ブロックずつ持ち上げて試す。斜面や小さな段差はこれで収まる。
     */
    private static boolean settles(VehicleEntityBase vehicle, double x, double y, double z, float yaw) {
        for (int lift = 0; lift <= PLACING_LIFTS; lift++) {
            vehicle.moveTo(x, y + lift, z, yaw, 0.0F);
            point(vehicle, yaw);

            if (vehicle.hasRoomHere(PLACING_MARGIN, y)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 航空機を、旗の後方 {@value #LAUNCH_BEHIND} ブロック・高さ {@value #LAUNCH_HEIGHT} から飛行状態で
     * 出す。ヘリも同じ——飛べる物はどれも空から出る。
     *
     * <p><b>速度とスロットルを持たせる。</b> 止まった機体を高度500に置けば、それは飛行機ではなく落下
     * する物だ。{@value #LAUNCH_SPEED_KMH} km/h で前へ出し、スロットルは半分で既に回っている状態に
     * する——出た瞬間に操縦桿が効く。
     *
     * <p><b>空きの確認は chunk がロードされているときだけ。</b> 1km 先の空は、まだ誰も開いていない
     * ことの方が多い。そこで {@code hasRoomHere} を通すと、高度500の空を見るために地形を生成させる
     * ことになる（[[explosions-generate-chunks]] と同じ穴）。空は空なので、読めないなら訊かない。
     */
    private static boolean launch(ServerLevel level, BlockPos flag, AircraftEntity aircraft, float yaw) {
        Vec3 forward = Vec3.directionFromRotation(0.0F, yaw);
        Vec3 at = new Vec3(flag.getX() + 0.5, flag.getY() + LAUNCH_HEIGHT, flag.getZ() + 0.5)
                .subtract(forward.scale(LAUNCH_BEHIND));

        aircraft.moveTo(at.x, at.y, at.z, yaw, 0.0F);
        point(aircraft, yaw);

        boolean loaded = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(Mth.floor(at.x)),
                SectionPos.blockToSectionCoord(Mth.floor(at.z))) != null;

        if (loaded && !aircraft.hasRoomHere(PLACING_MARGIN)) {
            return false;
        }

        aircraft.setDeltaMovement(forward.scale(LAUNCH_SPEED_KMH / KMH_PER_BLOCK_TICK));
        aircraft.setThrottle(LAUNCH_THROTTLE);

        return true;
    }

    /**
     * その列に機体を置ける高さ。置けないなら {@link Double#NaN}。
     *
     * @param floats 水面を探しているか。真なら水の上だけ、偽なら水以外の上だけを答える
     */
    private static double groundAt(ServerLevel level, double x, double z, boolean floats) {
        int blockX = Mth.floor(x);
        int blockZ = Mth.floor(z);
        // hasChunk ではなく getChunkNow。前者はチケット水準しか見ないので、まだ生成中の chunk にも
        // 「ある」と答える（[[ticket-level-is-not-chunk-presence]]）。
        ChunkAccess chunk = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(blockX),
                SectionPos.blockToSectionCoord(blockZ));

        if (chunk == null) {
            return Double.NaN;
        }

        int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
        boolean water = level.getFluidState(new BlockPos(blockX, top - 1, blockZ)).is(FluidTags.WATER);

        return water == floats ? top : Double.NaN;
    }

    /**
     * その者が前に出した機体を世界から下げる。残骸も同じで、戦場に置き去りにしてよい理由は無い。
     *
     * <p>探すのは UUID 1つ。全エンティティを歩くのは試合の終わりだけで十分だ。
     */
    private static void retire(ServerLevel level, MatchState state, UUID player) {
        UUID previous = state.machineOf(player);

        if (previous == null) {
            return;
        }

        for (ServerLevel candidate : level.getServer().getAllLevels()) {
            Entity was = candidate.getEntity(previous);

            if (was != null) {
                was.ejectPassengers();
                was.discard();

                return;
            }
        }
    }

    /** ID から機体を1機。名前がどちらの登録にも無ければ null。 */
    @Nullable
    static VehicleEntityBase create(ServerLevel level, ResourceLocation id) {
        EntityType<AircraftEntity> aircraft = ModEntities.aircraft().containsKey(id)
                ? ModEntities.aircraft().get(id).get() : null;

        if (aircraft != null) {
            return aircraft.create(level);
        }

        EntityType<GroundVehicleEntity> ground = ModEntities.vehicles().containsKey(id)
                ? ModEntities.vehicles().get(id).get() : null;

        return ground == null ? null : ground.create(level);
    }

    /** 置いた向きへ向ける。{@code VehicleItem} が設置でやるのと同じ1行。 */
    private static void point(VehicleEntityBase vehicle, float yaw) {
        if (vehicle instanceof AircraftEntity aircraft) {
            aircraft.snapAttitude(Attitude.of(yaw, 0.0F));
        } else if (vehicle instanceof GroundVehicleEntity ground) {
            ground.snapAttitude(yaw, 0.0F, 0.0F);
        }
    }

    // ------------------------------------------------------------------
    // 試合の開始と終わり
    // ------------------------------------------------------------------

    /**
     * 試合を始める。チケットを配り直し、時計を巻き、<b>全員を自陣へ送り</b>、知らせる。
     *
     * <p>送るのは相を変えた後。{@link #sendToBase} は試合が動いていることを前提に旗を探すし、そもそも
     * 開始の合図で全員が持ち場に着いていないなら、開始した意味が無い。
     */
    public static void start(MinecraftServer server, MatchState state) {
        state.newRound();
        state.setTicksLeft(state.duration());
        state.setPhase(MatchState.Phase.RUNNING);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            MatchTeam team = state.teamOf(player.getUUID());

            if (team != null) {
                sendToBase(player, state, team);
            }
        }

        // 戦闘 AI の記録を始め、前の試合の敵情と拠点の履歴を捨てる。
        BattleEvents.matchStarted(server, state);
        announce(server, Component.translatable("message.ashvehicles.match.started")
                .withStyle(ChatFormatting.GOLD));
        sync(server, state);
    }

    /**
     * 試合を終える。結果を読み上げ、この試合で出した機体を片付ける。
     *
     * @param winner 勝った陣営。引き分けなら null
     */
    public static void finish(MinecraftServer server, MatchState state, @Nullable MatchTeam winner) {
        state.setPhase(MatchState.Phase.ENDED);
        state.setWinner(winner == null ? null : winner.id());
        state.setTicksLeft(0);

        announce(server, winner == null
                ? Component.translatable("message.ashvehicles.match.draw").withStyle(ChatFormatting.GOLD)
                : Component.translatable("message.ashvehicles.match.won", winner.display(), winner.tickets())
                        .withStyle(ChatFormatting.GOLD));

        for (MatchTeam team : state.ranked()) {
            announce(server, Component.translatable("message.ashvehicles.match.score_line",
                    team.display(), team.tickets(), team.kills()));
        }

        // 片付けより前。生き残っていた AI の一生を、消える前に締める。
        BattleEvents.matchEnded(server, state, winner);
        clear(server);
        sync(server, state);
    }

    /**
     * 試合で出撃した機体を全部消す。残骸も含む——燃えている残骸は次の試合の障害物であって、記念碑では
     * ない。
     *
     * @return 消した数
     */
    public static int clear(MinecraftServer server) {
        int removed = 0;

        for (ServerLevel level : server.getAllLevels()) {
            List<Entity> doomed = new ArrayList<>();

            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof VehicleEntityBase
                        && !entity.getPersistentData().getString(TEAM_KEY).isEmpty()) {
                    doomed.add(entity);
                }
            }

            for (Entity entity : doomed) {
                entity.ejectPassengers();
                entity.discard();
                removed++;
            }
        }

        return removed;
    }

    /** 決着を測る。残りチケットが並んでいれば引き分け。 */
    @Nullable
    public static MatchTeam leader(MatchState state) {
        List<MatchTeam> ranked = state.ranked();

        if (ranked.isEmpty()) {
            return null;
        }

        if (ranked.size() > 1 && ranked.get(0).tickets() == ranked.get(1).tickets()) {
            return null;
        }

        return ranked.get(0);
    }

    // ------------------------------------------------------------------
    // 陣地へ戻す
    // ------------------------------------------------------------------

    /**
     * その者を自陣へ送る。出撃地点ブロックが先で、無ければ自陣が握っている拠点。どちらも無ければ何も
     * しない——送り先の無い陣営を相手に、座標を勝手に決めてよい理由は無い。
     *
     * <p>旗の真上ではなく数ブロック散らす。開始の合図で10人が同じ1マスに現れると、押し合って全員が
     * 転がり落ちる。
     *
     * <p>乗っている者は降ろす。機体ごと運ぶと、旗の上に前の試合の戦車が現れることになる。
     */
    public static boolean sendToBase(ServerPlayer player, MatchState state, MatchTeam team) {
        BlockPos flag = baseOf(state, team);

        if (flag == null) {
            return false;
        }

        ServerLevel level = player.serverLevel();
        RandomSource random = level.getRandom();
        double angle = random.nextDouble() * Math.PI * 2.0;
        double radius = Math.sqrt(random.nextDouble()) * RETURN_SCATTER;
        double x = flag.getX() + 0.5 + Math.cos(angle) * radius;
        double z = flag.getZ() + 0.5 + Math.sin(angle) * radius;
        double y = groundAt(level, x, z, false);

        if (Double.isNaN(y)) {
            x = flag.getX() + 0.5;
            z = flag.getZ() + 0.5;
            y = flag.getY() + 1.0;
        }

        player.stopRiding();
        player.teleportTo(level, x, y, z, player.getYRot(), player.getXRot());
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;

        return true;
    }

    /** その陣営の陣地。出撃地点ブロックが先、無ければ握っている拠点。1つも無ければ null。 */
    @Nullable
    static BlockPos baseOf(MatchState state, MatchTeam team) {
        if (!team.spawns().isEmpty()) {
            return team.spawns().get(0);
        }

        for (MatchPoint point : state.points().values()) {
            if (team.id().equals(point.owner())) {
                return point.pos();
            }
        }

        return null;
    }

    /**
     * 死んで湧き直した者を自陣へ。
     *
     * <p>バニラのリスポーン地点はベッドかワールドスポーンで、試合とは何の関係も無い。撃墜されるたびに
     * 数百ブロック歩いて戻る遊びを誰も望んでいない。
     */
    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        MatchState state = MatchState.of(player.server);
        MatchTeam team = state.teamOf(player.getUUID());

        if (state.isRunning() && team != null) {
            sendToBase(player, state, team);
        }
    }

    /**
     * 出撃待ちが明けた者を自陣へ戻す。
     *
     * <p><b>撃破の瞬間ではなく、待ちが明けた瞬間に送る。</b> 機体が落ちている間パイロットは機内にいて
     * （{@code AircraftEntity.holdsCrewToTheGround}）、脱出装置を引く数秒がそこにある——撃墜の直後に
     * 引き抜いてしまえば、その数秒は無かったことになる。
     *
     * <p>乗っている者は送らない。乗り換えて戦っている最中に引き戻すのは、明らかに待たれていない。
     */
    private static void returnWaiting(MinecraftServer server, MatchState state) {
        long now = server.overworld().getGameTime();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            long ready = state.readyAt(player.getUUID());

            if (ready <= 0 || now < ready || player.isPassenger()) {
                continue;
            }

            MatchTeam team = state.teamOf(player.getUUID());

            state.clearReady(player.getUUID());

            if (team == null) {
                continue;
            }

            // 既に陣地に立っている者は動かさない。死んで湧き直した者はリスポーンの時点でここへ来て
            // いるので（{@link #onRespawn}）、待ちが明けた瞬間にもう一度引き戻すと、旗の前で歩いた分
            // だけ弾かれることになる。
            if (atBase(player, state, team)) {
                continue;
            }

            if (sendToBase(player, state, team)) {
                player.displayClientMessage(
                        Component.translatable("message.ashvehicles.match.returned", team.display()), true);
            }
        }
    }

    /** その者がもう自陣の旗の周りにいるか。 */
    private static boolean atBase(ServerPlayer player, MatchState state, MatchTeam team) {
        BlockPos flag = baseOf(state, team);

        return flag != null && player.distanceToSqr(flag.getX() + 0.5, player.getY(), flag.getZ() + 0.5)
                < AT_BASE * AT_BASE;
    }

    // ------------------------------------------------------------------
    // 拠点
    // ------------------------------------------------------------------

    /**
     * 拠点1つずつについて、円の中に誰がいるかを数え、制圧を進める。
     *
     * <p><b>数えるのは人であって機体ではない。</b> 上空を通過した戦闘機が旗を取れてしまえば、拠点は
     * 地上の物ではなくなる。乗っている者はその機体の位置にいるので、車両で乗り付けて中に留まる限りは
     * 数えられる——降りずに取れるが、留まらなければ取れない。
     *
     * <p><b>ディメンションはブロックに訊く。</b> 拠点は座標しか持たないので、同じ x/z のネザーに立って
     * いる者が地上の旗を取れてしまう。その者の世界のその座標に旗が<em>建っている</em>ことを確かめれば、
     * 座標だけで正しく閉じる。壊された旗が残っていても同じ判定で無効になる。
     */
    private static void capture(MinecraftServer server, MatchState state) {
        for (MatchPoint point : state.points().values()) {
            String single = null;
            boolean contested = false;
            List<ServerPlayer> inside = new ArrayList<>();

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                MatchTeam team = state.teamOf(player.getUUID());

                if (team == null || !inside(player, point)) {
                    continue;
                }

                inside.add(player);

                if (single == null) {
                    single = team.id();
                } else if (!single.equals(team.id())) {
                    contested = true;
                }
            }

            // AI の車両も数える。数えなければ、人が2人しかいない試合の拠点は<b>誰も取れない</b>——
            // 20 両が旗の上に集まっていても進みが1つも動かないのでは、AI を立てた意味が制圧戦にだけ
            // 届かないことになる。払う報酬は無い（財布を持たない）ので、数えるのは陣営だけだ。
            //
            // <b>AI の航空機は数えない。</b> 旗の上空を回っているだけで拠点を拮抗させ、敵の制圧を止められて
            // しまう。高さを見ない円（{@link #inside}）の上で拠点を地上の物に保つには、飛んでいる AI を外すしかない。
            List<VehicleEntityBase> bots = new ArrayList<>();

            for (Bots.Fighter fighter : Bots.combatants(server)) {
                if (!(fighter.entity() instanceof VehicleEntityBase machine) || !machine.isBot()
                        || machine instanceof AircraftEntity || !inside(machine, point)) {
                    continue;
                }

                bots.add(machine);

                if (single == null) {
                    single = fighter.team();
                } else if (!single.equals(fighter.team())) {
                    contested = true;
                }
            }

            point.setContested(contested);

            if (contested) {
                // 拮抗。どちらの時間も進まない。押し出すまで旗は動かない。
                continue;
            }

            if (single == null) {
                point.idle(CAPTURE_INTERVAL);

                continue;
            }

            String before = point.owner();
            String wasTaking = point.taking();
            int wasProgress = point.progress();

            if (point.advance(single, CAPTURE_INTERVAL)) {
                MatchTeam taken = state.team(single);

                announce(server, Component.translatable("message.ashvehicles.match.captured",
                        taken == null ? Component.literal(single) : taken.display(),
                        Component.literal(point.name())));

                // 払うのは旗の中に立っていた者にだけ。取った仕事に払う物であって、陣営全体への配当では
                // ない。
                for (ServerPlayer worker : inside) {
                    state.award(worker.getUUID(), state.captureAward());

                    if (state.captureAward() > 0) {
                        worker.displayClientMessage(
                                Component.translatable("message.ashvehicles.match.earned",
                                        state.captureAward(), state.pointsOf(worker.getUUID())), true);
                    }
                }

                sync(server, state);
                BattleEvents.pointCaptured(server, state, point, before, single, bots);
            } else if (single.equals(point.owner()) && wasTaking != null && !wasTaking.equals(single)
                    && wasProgress > 0 && point.progress() == 0) {
                // 取られかけていた自陣の拠点を、中にいた者が押し返し切った。戦闘 AI の記録と報酬だけ。
                BattleEvents.pointDefended(server, state, point, single, bots);
            }
        }

        state.setDirty();
    }

    /**
     * その者が拠点の円の中に立っているか。高さは見ない——旗は柱ではなく地面の上の円だ。
     *
     * <p>人でも AI の車両でも同じ判定を通す。<b>ディメンションを旗竿の実在で確かめる</b>ところが要点で、
     * そこだけは座標では閉じない。
     */
    private static boolean inside(Entity who, MatchPoint point) {
        double dx = who.getX() - (point.pos().getX() + 0.5);
        double dz = who.getZ() - (point.pos().getZ() + 0.5);

        if (dx * dx + dz * dz > point.radius() * point.radius()) {
            return false;
        }

        return who.level().getBlockState(point.pos()).is(ModBlocks.CAPTURE_POINT.get());
    }

    /**
     * 拠点を握られている側のチケットを減らす。
     *
     * <p>減る枚数は<b>拠点の差そのもの</b>。3つ全部を取られていれば1回に3枚、1つ差なら1枚。撃ち合いを
     * 避けて時間を稼ぐ側が、旗を明け渡したまま勝てないようにするための唯一の仕掛けであり、拠点制圧が
     * 撃ち合いと別の遊びになる理由でもある。
     */
    public static void bleed(MinecraftServer server, MatchState state) {
        int most = 0;

        for (MatchTeam team : state.teams().values()) {
            most = Math.max(most, state.pointsHeldBy(team.id()));
        }

        if (most <= 0) {
            return;
        }

        boolean spent = false;

        for (MatchTeam team : state.teams().values()) {
            int behind = most - state.pointsHeldBy(team.id());

            if (behind <= 0) {
                continue;
            }

            team.spendTickets(behind);
            spent = true;
        }

        if (!spent) {
            return;
        }

        state.setDirty();
        sync(server, state);

        MatchTeam out = state.exhausted();

        if (out != null) {
            finish(server, state, leader(state));
        }
    }

    /**
     * 旗（出撃地点ブロックか、自陣が握っている拠点）を触った者に出撃盤を開かせる。断る理由はその場で
     * 1行返す。
     *
     * <p>ブロック2種が同じ判断を通る。どちらから出るかで規則が違ってよい理由は無い。
     */
    public static void openDeploy(ServerPlayer player, BlockPos flag) {
        MatchState state = MatchState.of(player.server);
        MatchTeam owner = state.deployOwner(flag);

        if (owner == null) {
            player.displayClientMessage(
                    Component.translatable("message.ashvehicles.match.unclaimed_spawn"), true);

            return;
        }

        MatchTeam mine = state.teamOf(player.getUUID());

        if (mine == null) {
            player.displayClientMessage(Component.translatable("message.ashvehicles.match.no_team"), true);

            return;
        }

        if (mine != owner) {
            player.displayClientMessage(
                    Component.translatable("message.ashvehicles.match.not_your_spawn"), true);

            return;
        }

        if (!state.isRunning()) {
            player.displayClientMessage(Component.translatable("message.ashvehicles.match.not_running"), true);

            return;
        }

        DeployOpenPayload.send(player, flag);
    }

    // ------------------------------------------------------------------
    // 時計と掲示板
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        MatchState state = MatchState.of(server);

        if (state.isRunning() && state.ticksLeft() > 0) {
            state.setTicksLeft(state.ticksLeft() - 1);

            if (state.ticksLeft() <= 0) {
                finish(server, state, leader(state));

                return;
            }
        }

        if (state.isRunning() && !state.points().isEmpty()) {
            if (server.getTickCount() % CAPTURE_INTERVAL == 0) {
                capture(server, state);
            }

            if (server.getTickCount() % state.bleedInterval() == 0) {
                bleed(server, state);
            }
        }

        // 陣地と拠点を開けたままにする。AI の補充より先——湧かせたい場所の地面は、湧かせる判断より前に
        // 届いていなければならない。
        MatchAnchors.hold(server, state);
        // AI の補充。中で間引くので毎tick呼んでよい。試合が動いていない間は何もしない。
        Bots.tick(server, state);
        // 戦闘 AI の陣営単位の仕事（敵情・脅威マップ・拠点の割り当て）、可視化、自己対戦。AI 1両ずつの判断は
        // 車両自身の tick が回す。
        AiDirector.tick(server, state);

        // 陣営が1つも無い鯖では何も配らない。試合を1度も開かないワールドで毎秒パケットを撒く理由は無い。
        if (!state.teams().isEmpty() && server.getTickCount() % SYNC_INTERVAL == 0) {
            if (state.isRunning()) {
                returnWaiting(server, state);
            }

            sync(server, state);
        }
    }

    /** 入ってきた者にも今の掲示板を渡す。試合の途中で繋ぎ直した者の画面が空にならないように。 */
    @SubscribeEvent
    public static void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            MatchStatePayload.send(MatchState.of(player.server), player);
        }
    }

    /** 全員に今の掲示板を配る。中身は1人ずつ違う。 */
    public static void sync(MinecraftServer server, MatchState state) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            MatchStatePayload.send(state, player);
        }
    }

    /** 全員に1行。試合の出来事は全員の物なので、撃った本人ではなくサーバー全体へ出す。 */
    public static void announce(MinecraftServer server, Component line) {
        server.getPlayerList().broadcastSystemMessage(line, false);
    }

    /** その者を陣営へ入れ、出撃待ちを消す。 */
    public static void join(MinecraftServer server, MatchState state, ServerPlayer player, MatchTeam team) {
        state.join(player.getUUID(), team);
        state.clearReady(player.getUUID());

        // 途中から入った者にも財布を配る。0 ポイントで参加した者は、最初の1機すら出せない。
        if (state.pointsOf(player.getUUID()) <= 0) {
            state.setPoints(player.getUUID(), state.startPoints());
        }

        sync(server, state);
    }

    /** 陣営から外す。乗っている機体はそのまま——降ろすのは運営の仕事であって、名簿の仕事ではない。 */
    public static void leave(MinecraftServer server, MatchState state, UUID player) {
        state.leave(player);
        sync(server, state);
    }

    /** 人でなく UUID しか無い場面のための、乗員の陣営引き。 */
    @Nullable
    public static MatchTeam teamOf(MatchState state, LivingEntity entity) {
        return entity instanceof Player player ? state.teamOf(player.getUUID()) : null;
    }
}
