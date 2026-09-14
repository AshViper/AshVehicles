package com.ashvehicles.match;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.ai.BotChunkLoader;
import com.ashvehicles.ai.BotPilot;
import com.ashvehicles.ai.air.AirLoadout;
import com.ashvehicles.ai.core.AiDirector;
import com.ashvehicles.ai.learning.AiVersions;
import com.ashvehicles.ai.objective.ObjectiveState;
import com.ashvehicles.ai.role.Roles;
import com.ashvehicles.ai.role.VehicleRole;
import com.ashvehicles.ai.team.Reinforcements;
import com.ashvehicles.block.CapturePointBlock;
import com.ashvehicles.block.TeamSpawnBlock;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.registry.ModItems;
import com.ashvehicles.vehicle.GroundVehicleDefinition;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * 陣営を AI の車両で埋める。人が2人でも 20 対 20 が回るようにするための仕掛け。
 *
 * <p><b>AI は「人の代わりに出撃した1両」であって、別種の存在ではない。</b> 出撃地点から出て、陣営タグを
 * 持ち、撃たれれば陣営のチケットを1枚持っていく——人が出した機体とまったく同じ扱いを受ける
 * （[[team-deathmatch-shape]]）。試合の片付け（{@link Deathmatch#clear}）も同じタグで拾うので、AI のための
 * 後始末は1行も無い。
 *
 * <p><b>名簿はメモリにしか無い。</b> 保存するのは「陣営ごとに何両出すか」（{@link MatchTeam#bots}）だけで、
 * 今どの車両が AI かは車両自身の {@code getPersistentData} に書いてある。再起動やチャンクの再ロードで
 * 戻ってきた車両は {@link #onJoin} が拾い直すので、名簿を保存する理由が無い——保存すると、消えた車両の
 * UUID が次の試合まで居残る方の問題を抱えることになる。
 *
 * <p><b>補充は少しずつ。</b> 1回の掃除で陣営ごと1両しか出さない。20両を同じ tick に置けば、その20両ぶんの
 * 地面の確認と生成が1tickに集まる——試合が始まった瞬間にサーバーが数秒止まる、という形でだけ現れる種類の
 * 負荷だ。
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class Bots {
    /** 陣営1つあたりの既定の AI 数。 */
    public static final int DEFAULT_COUNT = 5;

    /** 1陣営に出せる AI の上限。 */
    public static final int MOST = 40;

    /** 車両が「AI が動かす物」であることの申告。 */
    public static final String BOT_KEY = "AshVehiclesBot";

    /** 名簿を掃除し、足りない分を補充する間隔（tick）。 */
    private static final int SWEEP = 40;

    /** 1回の掃除で陣営ごとに出す数。 */
    private static final int PER_SWEEP = 1;

    /**
     * AI に渡す車両の車幅の上限（ブロック）。
     *
     * <p>同梱の地上車両で一番広いのは牽引砲の5.0、戦車はどれも4以下。ここを超えるのは陸上戦艦
     * （P-1000 ラーテ、11.9）だけで、あれは AI の避け方が想定している大きさの物ではない。
     */
    private static final double WIDEST = 6.0;

    /**
     * 陣営の AI のうち、航空機に回してよい割合（{@link #airSlots}）。
     *
     * <p><b>拠点は地上でしか取れない</b>（{@link Deathmatch#capture} は AI の航空機を数えない）。機種を一様に選ぶと、
     * 同梱の一覧では6割近くが航空機になり、旗を踏む車両が足りなくなる。
     */
    private static final double AIR_SHARE = 0.3;

    /** 戦域の既定の半径（ブロック）。旗と拠点の重心から測る。 */
    public static final double DEFAULT_ARENA = 500.0;

    /**
     * 戦域が一番遠い旗・拠点から外へ持つ余裕（ブロック）。旗のまわりに散らして湧かせる円（30）と、そこから
     * 走り出して向きを変えるだけの広さ。
     */
    private static final double ARENA_MARGIN = 96.0;

    /** 生きている AI 車両。サーバーの持ち物で、保存しない。 */
    private static final Set<VehicleEntityBase> LIVE = new LinkedHashSet<>();

    /** 自爆を予約した AI 車両（{@link #scuttle}）。次のサーバーの tick の頭で消す。 */
    private static final Set<VehicleEntityBase> DOOMED = new LinkedHashSet<>();

    /** 1tickにつき1回だけ組む参加者の名簿。AI が目標を選ぶたびに組み直す物ではない。 */
    private static List<Fighter> roster = List.of();
    private static int rosterTick = -1;

    /** 戦域の中心。旗と拠点から出る値で、これも1tickに1回だけ測る。 */
    private static Vec3 centre = Vec3.ZERO;
    private static int centreTick = -1;

    /** 同じ苦情を繰り返さないための、陣営ごとの沈黙期限と、その長さ（tick）。 */
    private static final Map<String, Long> QUIET = new HashMap<>();
    private static final int COMPLAIN_EVERY = 200;

    /** 前回の掃除で数えた陣営ごとの生存数と、次に補充してよい時刻。減った事実はこの2つで分かる。 */
    private static final Map<String, Integer> COUNTED = new HashMap<>();
    private static final Map<String, Long> LOST = new HashMap<>();

    /** 試合に出ている物1つと、その陣営。人・人の乗る機体・AI の車両が同じ形で並ぶ。 */
    public record Fighter(Entity entity, String team) {
    }

    private Bots() {
    }

    // ------------------------------------------------------------------
    // 名簿
    // ------------------------------------------------------------------

    /**
     * 今この試合に出ている物の一覧。AI の観測・経路・拠点の評価が客で、1tickに1回だけ組む。
     *
     * <p><b>人は「乗っている機体」として並ぶ。</b> 戦車に乗っている者を撃つとは、その戦車を撃つことだ。
     * 降りて歩いている者だけが人として並ぶ。
     */
    public static List<Fighter> combatants(MinecraftServer server) {
        if (rosterTick == server.getTickCount()) {
            return roster;
        }

        MatchState state = MatchState.of(server);
        List<Fighter> built = new ArrayList<>(LIVE.size() + server.getPlayerList().getPlayerCount());

        prune();

        for (VehicleEntityBase bot : LIVE) {
            String team = bot.getPersistentData().getString(Deathmatch.TEAM_KEY);

            if (!team.isEmpty()) {
                built.add(new Fighter(bot, team));
            }
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            MatchTeam team = state.teamOf(player.getUUID());

            if (team == null || player.isSpectator()) {
                continue;
            }

            built.add(new Fighter(
                    player.getVehicle() instanceof VehicleEntityBase riding ? riding : player, team.id()));
        }

        roster = List.copyOf(built);
        rosterTick = server.getTickCount();

        return roster;
    }

    /**
     * 数に入らなくなった車両を名簿から落とす。
     *
     * <p><b>残骸はここで落ちる。</b> 全損した車両が消えるのは5分後（{@code VehicleEntityBase} の
     * 残骸寿命）で、それまで名簿に残していると<b>その5分間、倒された1両の代わりが出てこない</b>
     * ——「倒された AI がリスポーンしない」の正体はこれだった。燃えている車体は戦力ではないし、
     * 目標でも味方の輪郭でもない。世界からはこれまで通り、自分の時間をかけて消える。
     */
    private static void prune() {
        Iterator<VehicleEntityBase> walk = LIVE.iterator();

        while (walk.hasNext()) {
            VehicleEntityBase bot = walk.next();

            if (bot.isRemoved() || bot.isWrecked() || bot.getPilot() == null) {
                walk.remove();
            }
        }
    }

    /**
     * 戻ってきた車両に操縦者を付け直す。チャンクの再ロードでも再起動でもここを通る。
     *
     * <p>AI そのものは保存されない（{@link BotPilot} はエンティティですらない）。保存されているのは
     * 「これは AI の車両だ」という印1つで、そこから毎回作り直す。目標も走行状態も持ち越さないが、
     * それは失って困る物ではない——次の tick に選び直す。
     */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide || !(event.getEntity() instanceof VehicleEntityBase vehicle)) {
            return;
        }

        if (vehicle.getPersistentData().getBoolean(BOT_KEY)) {
            adopt(vehicle);
        }
    }

    /** その車両を AI に渡す。既に持っていれば名簿へ入れ直すだけ。 */
    public static void adopt(VehicleEntityBase vehicle) {
        if (vehicle.getPilot() == null) {
            BotPilot pilot = BotPilot.of(vehicle);

            if (pilot == null) {
                return;
            }

            vehicle.setPilot(pilot);
        }

        vehicle.getPersistentData().putBoolean(BOT_KEY, true);
        LIVE.add(vehicle);
    }

    /**
     * その陣営の生きた AI の UUID。掲示板がこれを配り、クライアントは味方の車両を光らせる。
     *
     * <p>陣営タグは {@code getPersistentData} にあってクライアントへは届かない。ここで配らなければ、
     * 20 対 20 の戦場で味方の戦車は1両も光らない。
     */
    public static List<UUID> machinesOf(String team) {
        prune();

        List<UUID> machines = new ArrayList<>();

        for (VehicleEntityBase bot : LIVE) {
            if (team.equals(bot.getPersistentData().getString(Deathmatch.TEAM_KEY))) {
                machines.add(bot.getUUID());
            }
        }

        return machines;
    }

    /**
     * その陣営から見た敵の航空機の数。人の乗る物も AI の物も、飛んでいても地上にいても。AI の航空機が出撃のたびに
     * 翼下へ空対空ミサイルを吊るかを決める（{@code ai/air/AirLoadout.equip}）。
     */
    public static int enemyAircraft(MinecraftServer server, String team) {
        Set<Entity> seen = new LinkedHashSet<>();

        for (Fighter fighter : combatants(server)) {
            if (!fighter.team().isEmpty() && !team.equals(fighter.team())
                    && fighter.entity() instanceof AircraftEntity aircraft && !aircraft.isWrecked()) {
                // 同じ機体に乗る2人は、同じ1機として並ぶ。
                seen.add(aircraft);
            }
        }

        return seen.size();
    }

    /** その陣営の生きた AI の数。 */
    public static int count(String team) {
        prune();

        int found = 0;

        for (VehicleEntityBase bot : LIVE) {
            if (team.equals(bot.getPersistentData().getString(Deathmatch.TEAM_KEY))) {
                found++;
            }
        }

        return found;
    }

    /**
     * その陣営の AI のうち、<b>実際に tick している</b>数。
     *
     * <p>「出ている数」と違う値になったら、そのぶんの車両はロードされた世界の中で止まっている
     * ——チケットが足りていないということだ（{@code ai/BotChunkLoader} の半径）。運営がそれを目で
     * 確かめられる唯一の場所なので、{@code /tdm bot} に出す。
     */
    public static int ticking(String team) {
        int moving = 0;

        for (VehicleEntityBase bot : LIVE) {
            if (!team.equals(bot.getPersistentData().getString(Deathmatch.TEAM_KEY))) {
                continue;
            }

            if (bot.level() instanceof ServerLevel level
                    && level.isPositionEntityTicking(bot.blockPosition())) {
                moving++;
            }
        }

        return moving;
    }

    /**
     * 出ている AI を全部下げる。撃破ではないのでチケットは動かない——運営が数を 0 にしたときと、試合を
     * 片付けるときに通る。
     *
     * @return 下げた数
     */
    public static int retire(@Nullable String team) {
        List<VehicleEntityBase> doomed = new ArrayList<>();

        for (VehicleEntityBase bot : LIVE) {
            if (team == null || team.equals(bot.getPersistentData().getString(Deathmatch.TEAM_KEY))) {
                doomed.add(bot);
            }
        }

        for (VehicleEntityBase bot : doomed) {
            bot.ejectPassengers();
            bot.discard();
            LIVE.remove(bot);
        }

        return doomed.size();
    }

    /**
     * 撃つ物の尽きた AI を自爆させる（2026-09-13 の指示「搭載している武装がなくなったら自滅してリスポーン」）。呼ぶのは
     * 操縦役（{@code ai/combat/Resupply}・{@code ai/AirPilot}）で、撃った弾が着くのを待ってから。
     *
     * <p><b>撃破ではないのでチケットは動かさない。</b> 弾が尽きたのは戦って使い切ったからで、倒されたからではない
     * ——以前は同じ車両がその場で自分に弾を満たしていて、それもチケットを動かさなかった。
     * {@link VehicleEntityBase#destroy} を通すと、チケットが1枚減り、戦闘 AI の記録にはそこで倒されたと残り（覚える
     * 地図がその場所を危ないと数える）、残骸が5分残る。だから見た目と音だけ爆発させて消す。消えた分は {@link #tick}
     * が数え、倒された時と同じ出撃の待ち（{@code /tdm set respawn}）の後に次の1両を出す。
     *
     * <p><b>その場では消さない。</b> 呼ばれるのは車両自身の tick の中で、tick の途中で消した車両はその tick の残り
     * （移動・チャンクの保持・兵装）を消えたまま走る。予約して、次のサーバーの tick の頭（{@link #sink}）で消す。
     */
    public static void scuttle(VehicleEntityBase bot) {
        if (bot.getPilot() != null && !bot.isRemoved()) {
            DOOMED.add(bot);
        }
    }

    /** 自爆の予約を実行する。 */
    private static void sink() {
        if (DOOMED.isEmpty()) {
            return;
        }

        for (VehicleEntityBase bot : List.copyOf(DOOMED)) {
            if (!bot.isRemoved() && bot.level() instanceof ServerLevel level) {
                Vec3 at = bot.getBoundingBox().getCenter();

                level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0);
                level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS,
                        4.0F, 0.9F);
                bot.ejectPassengers();
                bot.discard();
            }

            LIVE.remove(bot);
        }

        DOOMED.clear();
    }

    // ------------------------------------------------------------------
    // 補充
    // ------------------------------------------------------------------

    /**
     * 陣営ごとの数を合わせる。{@link Deathmatch#onServerTick} から毎tick呼ばれ、中で間引く。
     *
     * <p>試合が動いていない間は何もしない。AI は試合の持ち物であって、設営中の会場を走り回る物ではない。
     */
    public static void tick(MinecraftServer server, MatchState state) {
        sink();

        if (!state.isRunning()) {
            LOST.clear();
            COUNTED.clear();
            Reinforcements.clear();

            return;
        }

        if (server.getTickCount() % SWEEP != 0) {
            return;
        }

        prune();

        // <b>足元を開け直すのは AI 自身の tick だけに任せない。</b> チケットが切れた車両は tick を
        // 止められ、tick が止まった車両は自分では何も要求できない——一度でもそこへ落ちると、外から
        // 誰かが置き直すまで永久に戻ってこない。ここはサーバーの tick なので、凍っている車両にも届く。
        for (VehicleEntityBase bot : LIVE) {
            BotChunkLoader.hold(bot);
        }

        long now = server.overworld().getGameTime();

        for (MatchTeam team : state.teams().values()) {
            int live = count(team.id());
            Integer was = COUNTED.put(team.id(), live);

            // 減っていれば誰かが倒された。<b>次の1両はそこから数えて出す</b>——人と同じ
            // {@code /tdm set respawn} の待ちで、倒した側が撃った次の瞬間に同じ数の敵と向き合うことに
            // ならないように。試合開始の空っぽ（前の数を知らない）は待たない。
            if (was != null && live < was) {
                LOST.put(team.id(), now + state.respawnTicks());
            }

            if (live > team.bots()) {
                trim(team, live - team.bots());

                continue;
            }

            if (now < LOST.getOrDefault(team.id(), 0L)) {
                continue;
            }

            for (int spawned = 0; spawned < PER_SWEEP && live + spawned < team.bots(); spawned++) {
                if (!spawn(server, state, team)) {
                    break;
                }

                COUNTED.put(team.id(), live + spawned + 1);
                LOST.remove(team.id());
            }
        }
    }

    /** 多すぎる分を下げる。減らしたのは運営なので、チケットは動かさない。 */
    private static void trim(MatchTeam team, int surplus) {
        for (VehicleEntityBase bot : new ArrayList<>(LIVE)) {
            if (surplus <= 0) {
                break;
            }

            if (team.id().equals(bot.getPersistentData().getString(Deathmatch.TEAM_KEY))) {
                bot.ejectPassengers();
                bot.discard();
                LIVE.remove(bot);
                surplus--;
            }
        }
    }

    /**
     * AI を1両出す。出せたら true。
     *
     * <p>置き方は人の出撃とまったく同じ道を通る（{@link Deathmatch#place}）——自陣の旗の周り半径30ブロック、
     * 地面はハイトマップ、ロードされていない候補は捨てる。だから AI を出すことがワールド生成を起こすことは
     * ない。旗が無い陣営には出せない。
     */
    public static boolean spawn(MinecraftServer server, MatchState state, MatchTeam team) {
        BlockPos flag = Deathmatch.baseOf(state, team);

        if (flag == null) {
            complain(server, team, "旗が1つも無い（/tdm spawn で出撃地点ブロックを登録する）");

            return false;
        }

        ServerLevel level = levelOf(server, flag);

        if (level == null) {
            complain(server, team, "旗のある世界が分からない（/tdm start か /tdm spawn を会場で打ち直す）");

            return false;
        }

        // 旗の chunk がまだ届いていなければ、この掃除では出さない。ここで
        // {@code level.getBlockState} を引けば、待たずに読めない chunk を<em>その場で生成する</em>
        // ——試合の開始が tick スレッド上のワールド生成になる（[[explosions-generate-chunks]] と同じ穴）。
        // 開けるのは {@link MatchAnchors} の仕事で、数tick後には届く。
        if (level.getChunkSource().getChunkNow(
                SectionPos.blockToSectionCoord(flag.getX()),
                SectionPos.blockToSectionCoord(flag.getZ())) == null) {
            complain(server, team, "旗の chunk がまだ届いていない（数秒後にまた試す）");

            return false;
        }

        ResourceLocation choice = pick(server, state, team, level.getRandom());

        if (choice == null) {
            complain(server, team, "AI に渡せる車両が1つも無い（/tdm bot pool list）");

            return false;
        }

        VehicleEntityBase vehicle = Deathmatch.create(level, choice);

        if (vehicle == null) {
            complain(server, team, "車両を作れない: " + choice);

            return false;
        }

        BlockState flagState = level.getBlockState(flag);
        float yaw = flagState.hasProperty(HorizontalDirectionalBlock.FACING)
                ? flagState.getValue(HorizontalDirectionalBlock.FACING).toYRot()
                : 0.0F;

        // 旗から半径30ブロックの円内へランダムに置く（人の出撃とまったく同じ道）。全部外れたときの
        // 最後の1回——旗の脇——も<b>使う</b>。<b>1両も出ないことの方が、一時的に近くへ固まることより
        // 遥かに悪い</b>からで、出た車両は数秒で散っていく。使われたことは記録に残す（会場が狭い証拠だ）。
        if (!Deathmatch.place(level, flag, vehicle, yaw, false)) {
            complain(server, team, "旗のまわりに置ける場所が無い（周囲30ブロックが塞がっている）");

            return false;
        }

        // 人の航空機は出撃盤の注文書を吊って出る（Deathmatch.deploy）。AI には注文する者がいないので、機体ファイルから
        // 選んで吊る。
        if (vehicle instanceof AircraftEntity aircraft) {
            AirLoadout.equip(aircraft, enemyAircraft(server, team.id()));
        }

        vehicle.rearm();
        Deathmatch.tag(vehicle, team);
        vehicle.setCustomName(Component.translatable("entity.ashvehicles.bot",
                vehicle.getType().getDescription(), team.display()));
        adopt(vehicle);
        level.addFreshEntity(vehicle);
        // 湧いた場所の chunk を、その車両自身の物として確保する。<b>ここで置かないと動き出せない</b>
        // ——AI のチケットは AI の tick が置き直す物で、エンティティが tick するには自分の chunk が
        // 既にその水準にいなければならない。旗から30ブロック離れた場所に置かれた1両が、旗の周りの
        // 確保の外で永久に止まる、という形でだけ現れる。
        BotChunkLoader.hold(vehicle);

        return true;
    }

    /**
     * 出せなかった理由を記録に残す。同じ理由を撒き散らさないよう、陣営ごとに間引く。
     *
     * <p><b>出ない理由が見えないのが一番困る。</b> AI が1両も湧かないとき、運営に見えるのは「湧かない」
     * ことだけで、旗が無いのか、世界が違うのか、地面が塞がっているのかは中からしか分からない。
     */
    private static void complain(MinecraftServer server, MatchTeam team, String why) {
        long now = server.overworld().getGameTime();

        if (now < QUIET.getOrDefault(team.id(), 0L)) {
            return;
        }

        QUIET.put(team.id(), now + COMPLAIN_EVERY);
        AshVehicles.LOGGER.info("[tdm] {} の AI を出せない: {}", team.id(), why);
    }

    /**
     * その旗が実際に建っている世界。
     *
     * <p><b>旗は次元を持たない。</b> 帳簿が覚えているのは座標だけで、どの世界かは「そこにその旗竿が
     * 建っていること」で決まる（{@code Deathmatch.inside} が制圧判定で使うのと同じ考え方）。人の出撃では
     * 触った本人の世界がそれを兼ねていたが、AI には触る本人がいないので、ここで探す。
     *
     * <p>探すのは帳簿が世界を覚えていないときだけで、探し方は {@code getChunkNow}——ロードされていない
     * 世界を覗くために地形を生成してはならない（[[explosions-generate-chunks]] と同じ穴）。見付かれば
     * その場で覚えるので、探すのは1度きりになる。
     */
    @Nullable
    public static ServerLevel levelOf(MinecraftServer server, BlockPos flag) {
        // 帳簿が覚えていればそれが答え。旗を立てた者の世界であり、探す必要が無い——そして<b>探す方法は
        // 誰も居ない世界では使えない</b>（chunk がロードされていないので旗が見えない）。
        MatchState state = MatchState.of(server);
        ServerLevel remembered = state.level(server);

        if (remembered != null) {
            return remembered;
        }

        for (ServerLevel level : server.getAllLevels()) {
            if (level.getChunkSource().getChunkNow(
                    SectionPos.blockToSectionCoord(flag.getX()),
                    SectionPos.blockToSectionCoord(flag.getZ())) == null) {
                continue;
            }

            Block block = level.getBlockState(flag).getBlock();

            if (block instanceof TeamSpawnBlock || block instanceof CapturePointBlock) {
                // 見付けたついでに覚える。古い試合（世界を覚えていない帳簿）はここで1度だけ拾われ、
                // 以後は誰も見ていなくても陣地が開く。
                state.setWorld(level.dimension());

                return level;
            }
        }

        return null;
    }

    /**
     * AI に持たせる車両か機体を1つ選ぶ——陣営に今足りない種類から（2026-09-13 の指示「状況に合わせてスポーンする車両を
     * 選ぶ」、{@link Reinforcements}）。
     *
     * <p>運営が一覧を据えていればそこから、無ければ<b>AI が扱える物の全部</b>（{@link #usable}）から。撃てない物
     * ——輸送車や牽引砲——を混ぜると、戦場に何もしない的が並ぶ。艦を混ぜないのは AI が水面を走れないからだ
     * （{@link BotPilot#of}）。一覧を種類（{@link #kindOf}）で分け、陣営から見た戦場（{@link #situation}）から種類ごとの
     * 足りなさを出し、その2乗に比べたくじで種類を、種類の中は均等に選ぶ。防空の車両が一覧に無ければ、空を撃てる他の
     * 地上車両（CV90・ブラッドレー・BMPT）を防空に数える。
     *
     * <p><b>航空機は陣営の数の {@value #AIR_SHARE} まで</b>（{@link #airSlots}）。枠が埋まっていれば地上から。一覧に
     * 航空機しか無ければ、枠に関わらず航空機から。
     */
    @Nullable
    public static ResourceLocation pick(MinecraftServer server, MatchState state, MatchTeam team, RandomSource random) {
        List<ResourceLocation> pool = usable(state.botPool());

        if (pool.isEmpty()) {
            pool = usable(machines());
        }

        Map<Reinforcements.Kind, List<ResourceLocation>> byKind = new EnumMap<>(Reinforcements.Kind.class);
        List<ResourceLocation> guns = new ArrayList<>();

        for (ResourceLocation id : pool) {
            Reinforcements.Kind kind = kindOf(id);

            byKind.computeIfAbsent(kind, key -> new ArrayList<>()).add(id);

            if (kind != Reinforcements.Kind.AIR_DEFENCE && !kind.flies()
                    && Roles.defendsAir(Definitions.VEHICLES.get(id))) {
                guns.add(id);
            }
        }

        if (!byKind.containsKey(Reinforcements.Kind.AIR_DEFENCE) && !guns.isEmpty()) {
            byKind.put(Reinforcements.Kind.AIR_DEFENCE, guns);
        }

        boolean grounded = byKind.keySet().stream().anyMatch(kind -> !kind.flies());

        if (grounded && airborne(team.id()) >= airSlots(team.bots())) {
            byKind.keySet().removeIf(Reinforcements.Kind::flies);
        }

        if (byKind.isEmpty()) {
            return null;
        }

        long now = server.overworld().getGameTime();
        Reinforcements.Kind kind = Reinforcements.choose(
                Reinforcements.needs(situation(server, team.id(), now),
                        Reinforcements.Weights.of(AiVersions.forTeam(server, team.id()).parameters())),
                byKind.keySet(), random.nextDouble());
        List<ResourceLocation> from = kind == null ? null : byKind.get(kind);

        return from == null || from.isEmpty() ? null : from.get(random.nextInt(from.size()));
    }

    /** その車両か機体が、陣営の足りなさ（{@link Reinforcements}）のどの種類に数えられるか。 */
    public static Reinforcements.Kind kindOf(ResourceLocation id) {
        if (ModItems.aircraft().containsKey(id)) {
            return AirLoadout.isFighter(Definitions.AIRCRAFT.get(id))
                    ? Reinforcements.Kind.FIGHTER : Reinforcements.Kind.STRIKER;
        }

        return switch (Roles.of(id, Definitions.VEHICLES.get(id))) {
            case AA -> Reinforcements.Kind.AIR_DEFENCE;
            case TANK -> Reinforcements.Kind.TANK;
            case ARTILLERY -> Reinforcements.Kind.ARTILLERY;
            default -> Reinforcements.Kind.LIGHT;
        };
    }

    /**
     * 陣営から見た戦場（{@link Reinforcements.Situation}）。名簿の機体を種類で数え、拠点の割り当てと最近の損失とチケットの
     * 余裕を足す。空を撃てる防空以外の地上車両は防空に半分、歩いている人は軽い物に半分と数える。
     */
    static Reinforcements.Situation situation(MinecraftServer server, String team, long now) {
        double own = 0.0;
        double enemyAir = 0.0;
        double enemyArmour = 0.0;
        double enemyLight = 0.0;
        double enemyAirDefence = 0.0;
        double ownTanks = 0.0;
        double ownLight = 0.0;
        double ownAirDefence = 0.0;
        double ownArtillery = 0.0;
        double ownFighters = 0.0;
        double ownStrikers = 0.0;
        Set<Entity> seen = new LinkedHashSet<>();

        for (Fighter fighter : combatants(server)) {
            Entity entity = fighter.entity();

            // 同じ機体に乗る2人は、同じ1機として並ぶ。
            if (fighter.team().isEmpty() || !seen.add(entity)
                    || (entity instanceof VehicleEntityBase machine && machine.isWrecked())) {
                continue;
            }

            boolean ours = team.equals(fighter.team());

            if (ours) {
                own++;
            }

            if (entity instanceof AircraftEntity aircraft) {
                if (!ours) {
                    enemyAir++;
                } else if (AirLoadout.isFighter(aircraft.getStats())) {
                    ownFighters++;
                } else {
                    ownStrikers++;
                }
            } else if (entity instanceof GroundVehicleEntity ground) {
                VehicleRole role = Roles.of(ground);
                double air = role == VehicleRole.AA ? 1.0 : Roles.defendsAir(ground.getStats()) ? 0.5 : 0.0;

                if (ours) {
                    ownAirDefence += air;

                    switch (role) {
                        case AA -> {
                        }
                        case TANK -> ownTanks++;
                        case ARTILLERY -> ownArtillery++;
                        default -> ownLight++;
                    }
                } else {
                    enemyAirDefence += air;

                    switch (role) {
                        case AA, ARTILLERY -> {
                        }
                        case TANK -> enemyArmour++;
                        default -> enemyLight++;
                    }
                }
            } else if (!ours) {
                enemyLight += 0.5;
            }
        }

        double capturable = 0.0;
        double threatened = 0.0;
        MatchState state = MatchState.of(server);
        MatchTeam side = state.team(team);
        // チケットの余裕があるうちは航空機を呼ぶ（2026-09-13 の指示）。
        double spare = side == null ? 0.0 : Reinforcements.spare(side.tickets(), state.startTickets());

        for (ObjectiveState objective : AiDirector.brain(team).board().states()) {
            if (objective.wantsTaking() || objective.recaptureRequired()) {
                capturable++;
            }

            if (objective.beingTaken()) {
                threatened++;
            }
        }

        return new Reinforcements.Situation(own, enemyAir, enemyArmour, enemyLight, enemyAirDefence, ownTanks,
                ownLight, ownAirDefence, ownArtillery, ownFighters, ownStrikers, capturable, threatened,
                Reinforcements.lostTo(team, Reinforcements.Cause.AIR, now),
                Reinforcements.lostTo(team, Reinforcements.Cause.ARMOUR, now),
                Reinforcements.lostTo(team, Reinforcements.Cause.AIR_DEFENCE, now), spare);
    }

    /**
     * 陣営の1機が倒された。何に倒されたかを陣営の見立て（{@link Reinforcements#lost}）へ。
     * {@link Deathmatch#onVehicleDestroyed} から、人の機体でも AI の車両でも。
     */
    public static void noteLoss(MinecraftServer server, String team, VehicleEntityBase victim,
            @Nullable Entity killer) {
        Entity machine = killer instanceof Player player && player.getVehicle() != null ? player.getVehicle() : killer;
        Reinforcements.Cause cause = Reinforcements.Cause.OTHER;

        if (machine instanceof AircraftEntity) {
            cause = Reinforcements.Cause.AIR;
        } else if (machine instanceof GroundVehicleEntity ground) {
            VehicleRole role = Roles.of(ground);

            if (victim instanceof AircraftEntity && Roles.defendsAir(ground.getStats())) {
                cause = Reinforcements.Cause.AIR_DEFENCE;
            } else if (role == VehicleRole.TANK) {
                cause = Reinforcements.Cause.ARMOUR;
            } else if (role == VehicleRole.ARTILLERY) {
                cause = Reinforcements.Cause.ARTILLERY;
            }
        }

        Reinforcements.lost(team, cause, server.overworld().getGameTime());
    }

    /** 陣営の AI の数に対して、航空機に回してよい数。2両以下の陣営は地上車両だけ。 */
    public static int airSlots(int bots) {
        return bots < 3 ? 0 : Math.max(1, (int) Math.floor(bots * AIR_SHARE));
    }

    /** その陣営の、生きている AI の航空機の数。 */
    private static int airborne(String team) {
        int found = 0;

        for (VehicleEntityBase bot : LIVE) {
            if (bot instanceof AircraftEntity && team.equals(bot.getPersistentData().getString(Deathmatch.TEAM_KEY))) {
                found++;
            }
        }

        return found;
    }

    /** 登録されている地上車両と航空機の全部。 */
    public static List<ResourceLocation> machines() {
        List<ResourceLocation> machines = new ArrayList<>(ModItems.vehicles().keySet());

        machines.addAll(ModItems.aircraft().keySet());

        return machines;
    }

    /** その一覧のうち、AI が実際に出せる物。 */
    public static List<ResourceLocation> usable(Collection<ResourceLocation> candidates) {
        List<ResourceLocation> usable = new ArrayList<>();

        for (ResourceLocation id : candidates) {
            if (usable(id)) {
                usable.add(id);
            }
        }

        return usable;
    }

    /**
     * AI が出せる物か。地上車両は、地上を走り、乗って動かす物で、何か撃てる物。航空機は、座席を持ち、AI に渡さない
     * 機種でなく、パイロットの引き金で地上を撃てる物（{@link AirLoadout#flyable}）。
     */
    public static boolean usable(ResourceLocation id) {
        if (ModItems.aircraft().containsKey(id)) {
            return AirLoadout.flyable(id);
        }

        if (!ModItems.vehicles().containsKey(id) || !Loadout.deployable(id)) {
            return false;
        }

        GroundVehicleDefinition definition = Definitions.VEHICLES.get(id);

        if (definition == null || definition.isShip()) {
            return false;
        }

        // <b>大きすぎる車両は AI に渡さない。</b> 避け方は車幅を前提に組んであり（{@code ai/Obstacles} の
        // 触角と {@code ai/Course} の格子）、12ブロック幅の陸上戦艦——ラーテ——はその前提の外にある。
        // 建物の間を「通れる」と判断した道に入らないし、旗の周りに置けば出撃地点を塞ぐ。人が乗る分には
        // 今まで通り何も変わらない。
        if (definition.hitbox().width() > WIDEST) {
            return false;
        }

        return definition.armament().main().isPresent() || definition.coaxial().exists()
                || definition.launcher().exists();
    }

    // ------------------------------------------------------------------
    // 戦域
    // ------------------------------------------------------------------

    /**
     * 生きている AI の操縦役。陣営を問わない。
     *
     * <p><b>誰がどの拠点へ行くかは、もうここでは決めない。</b> 以前の {@code duties}（取りに行く拠点3枠・
     * 守る拠点1枠・敵陣の旗1枠の一覧を ID で引く）は、拠点の状況を点数にして枠を配る
     * {@code ai/objective/ObjectiveBoard} に置き換えた。団子にしない・ID で配る、という2つの理由は向こうへ
     * そのまま引き継いである。
     */
    public static List<BotPilot> pilots() {
        prune();

        List<BotPilot> pilots = new ArrayList<>(LIVE.size());

        for (VehicleEntityBase bot : LIVE) {
            BotPilot pilot = bot.getPilot();

            if (pilot != null) {
                pilots.add(pilot);
            }
        }

        return pilots;
    }

    /** その陣営の、生きている AI の操縦役。 */
    public static List<BotPilot> pilotsOf(String team) {
        prune();

        List<BotPilot> pilots = new ArrayList<>();

        for (VehicleEntityBase bot : LIVE) {
            BotPilot pilot = bot.getPilot();

            if (pilot != null && team.equals(bot.getPersistentData().getString(Deathmatch.TEAM_KEY))) {
                pilots.add(pilot);
            }
        }

        return pilots;
    }

    /**
     * 戦域から出ているか。
     *
     * <p><b>これが無いと AI は地平線まで追う。</b> 敵を追って会場を離れた車両は、誰も見ていない土地で
     * チャンクを開き続けることになる——20両ぶんとなれば、それは試合ではなくワールド生成の負荷そのものだ。
     */
    public static boolean beyondArena(VehicleEntityBase vehicle) {
        MinecraftServer server = vehicle.getServer();

        if (server == null) {
            return false;
        }

        MatchState state = MatchState.of(server);
        double radius = arenaRadius(server, state);

        if (radius <= 0.0) {
            return false;
        }

        // 円は地面の上の物。高さを数えると、地下に置いた旗の真上の地表にいる車両が「外」になる。
        return centre(server, state).subtract(vehicle.position()).horizontalDistance() > radius;
    }

    /**
     * 戦域の実際の半径（ブロック）。設定の半径（{@code /tdm set arena}）と、旗と拠点を全部含む半径の大きい方。
     * 設定が0以下なら無制限で、そのまま返す。
     *
     * <p><b>旗と拠点は必ず戦域の中にある。</b> 設定の半径は重心から測るので、会場が広いと自陣の旗がその外に
     * 出る——2026-09-13 の試合では旗が重心から約1000ブロック、半径は既定の500で、AI は出撃した瞬間から戦域の
     * 外にいた。外にいる AI は持ち場を捨てて会場の中心へ向かい、迷ったときの道案内も使わない。首輪は地平線まで
     * 追わないための物で、自分の陣地から出させない物ではない。
     */
    public static double arenaRadius(MinecraftServer server, MatchState state) {
        double radius = state.arenaRadius();

        if (radius <= 0.0) {
            return radius;
        }

        Vec3 middle = centre(server, state);
        double farthest = 0.0;

        for (MatchTeam team : state.teams().values()) {
            for (BlockPos spawn : team.spawns()) {
                farthest = Math.max(farthest, Vec3.atCenterOf(spawn).subtract(middle).horizontalDistance());
            }
        }

        for (MatchPoint point : state.points().values()) {
            farthest = Math.max(farthest,
                    Vec3.atCenterOf(point.pos()).subtract(middle).horizontalDistance() + point.radius());
        }

        return Math.max(radius, farthest + ARENA_MARGIN);
    }

    /** 旗と拠点の重心。会場の真ん中であり、戦域の中心。 */
    public static Vec3 centre(MinecraftServer server, MatchState state) {
        if (centreTick == server.getTickCount()) {
            return centre;
        }

        double x = 0.0;
        double y = 0.0;
        double z = 0.0;
        int counted = 0;

        for (MatchTeam team : state.teams().values()) {
            for (BlockPos spawn : team.spawns()) {
                x += spawn.getX() + 0.5;
                y += spawn.getY() + 0.5;
                z += spawn.getZ() + 0.5;
                counted++;
            }
        }

        for (MatchPoint point : state.points().values()) {
            x += point.pos().getX() + 0.5;
            y += point.pos().getY() + 0.5;
            z += point.pos().getZ() + 0.5;
            counted++;
        }

        centre = counted == 0 ? Vec3.ZERO : new Vec3(x / counted, y / counted, z / counted);
        centreTick = server.getTickCount();

        return centre;
    }
}
