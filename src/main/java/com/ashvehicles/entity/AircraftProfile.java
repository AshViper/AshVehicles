package com.ashvehicles.entity;

import com.ashvehicles.AshVehicles;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 一時的。機体がサーバーの1tickをどれだけ使っているかを、段階ごとにログへ出す。{@link WeaponProfile} の機体版。
 *
 * <p><b>これが在る理由。</b> 「未生成の地形へ全速で入ると固まる」は測らずに直せない。今分かっているのは
 * 「どこかで tick スレッドが chunk 生成を待っている」までで、待たせている呼び出しは1つではない——
 * {@code Entity.setPosRaw} の NeoForge 追加行、{@code ForcedChunkManager.forceChunk} の同期取り立て、
 * {@code Entity.move} の無防備な {@code getBlockState} 群、{@code Entity.baseTick} の
 * {@code updateFluidOnEyes}、{@code checkInsideBlocks} の箱の中の全ブロック。どれも「その場・生成付き」で、
 * どれも1行では区別できない。段階はそのために分けてある。
 *
 * <p><b>弾との決定的な違いは、機体の仕事が tick の中に無いことだ。</b> パイロットが乗っている機体をサーバーで
 * 動かしているのは {@link AircraftEntity#tick} ではない。移動報告のパケットであり、それは
 * {@code ServerGamePacketListenerImpl.handleMoveVehicle} で処理される——サーバーの tick ループの
 * <em>エンティティループより後</em>、{@code MinecraftServer.tickChildren} の {@code connection} 段だ
 * （{@code getConnection().tick()}）。エンティティ tick だけを測ると、有人機については「機体はほぼ0ms」という
 * 正しくて役に立たない答えが出る。だから計測は2本ある。tick の run（{@link #open}）と、パケットの run
 * （{@link #openPacket}）。段階番号は連番で、ログでは {@code ||} の左右に分かれる。
 *
 * <p><b>chunk の待ちは段階に付ける。</b> {@link #chunkIn}/{@link #chunkOut} を
 * {@code ServerChunkCache.getChunk} から呼ぶ（{@code AircraftChunkProbeMixin} 参照）。数えるのは
 * {@code requireChunk = true} の呼び出しだけ——{@code getChunkFutureMainThread} が
 * {@code TicketType.UNKNOWN} を足し、{@code runDistanceManagerUpdates} を回し、生成タスクを積み、
 * {@code managedBlock} がそれを待つのはその旗が立っている時だけだからだ。旗が偽なら
 * {@code UNLOADED_CHUNK_FUTURE} が即返り、待ちは無い。溜めた回数と時間は次の {@link #lap} が、その時間が
 * 属する段階へそのまま付け替える。よってログの {@code 回廊=610/11} は「回廊に 610ms、うち 11 回の chunk 待ち」
 * と読める。
 *
 * <p><b>読み方。</b> 割合の大きい段階が犯人。「最悪」は集計期間中で最も重かったサーバー1tickの機体分で、
 * これが要る理由は固まりが平均に埋もれるからだ——20tickの平均が 52ms でも、その中の1tickが 800ms なら
 * 見えているのはその1tickの方である。合計と段階の和の差は、途中で return した経路の分。窓の ms と機体の ms
 * の差は、機体以外がサーバーで使った時間（chunk システム自身の更新など）。
 *
 * <p><b>普段は切っておくこと。</b> {@link #ENABLED} が偽の間、{@link #open} と {@link #openPacket} は 0 を
 * 返し他は何もしない。調査が終わったらこのクラスと {@code AircraftChunkProbeMixin} ごと消してよい。
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class AircraftProfile {
    /** 調査中だけ真にすること。 */
    private static final boolean ENABLED = true;

    /** 何 tick ごとに1行出すか。20 = 毎秒1行。 */
    private static final int EVERY = 20;

    private static final long MILLIS = 1_000_000L;

    // ---- tick の中の段階。AircraftEntity.tick() が順に通る ----

    /** 被弾時間の減算と {@code super.tick()}。{@code Entity.baseTick} の {@code updateFluidOnEyes} を含む。 */
    public static final int BASE = 0;
    /** {@code tickGear} / {@code tickVtol} / {@code tickRotor} / {@code tickLerp}。 */
    public static final int RIG = 1;
    /** {@code flightTick()} または {@code wreckTick()}。この側が飛ばしている機体だけが通る。 */
    public static final int FLIGHT = 2;
    /** {@code beyondTheWorld} + {@code insideTerrain} + {@code move()}/{@code setPos} + 衝突判定。 */
    public static final int MOVE = 3;
    /** 操縦していない側の分岐（{@code travelled} / {@code checkStructuralLoad} / {@code publishVelocity}）。 */
    public static final int REPORT = 4;
    /** {@code tickSweep} + {@code crash} + {@code tickParts}。 */
    public static final int PARTS = 5;
    /** {@code tickWeapons} + {@code getSensors().tick()} + {@code dispenser.tick()}。 */
    public static final int ARMS = 6;
    /** {@code AircraftChunkLoader.update}。tick から呼ばれた方。 */
    public static final int CORRIDOR = 7;
    /** {@code checkInsideBlocks()}。素の直方体の中の全ブロックを読む。 */
    public static final int INSIDE = 8;

    // ---- パケットの中の段階。handleMoveVehicle が順に通る ----

    /** 入口から {@code entity.move} まで。距離検査と1回目の {@code noCollision}。 */
    public static final int PKT_CHECK = 9;
    /** {@code move(MoverType.PLAYER, ...)} の {@code super.move} 部分。{@code Entity.move} の中身。 */
    public static final int PKT_MOVE = 10;
    /** {@code AircraftChunkLoader.update}。移動報告から呼ばれた方。 */
    public static final int PKT_CORRIDOR = 11;
    /** {@code absMoveTo} 以降。{@code setPosRaw} の chunk 要求、搭乗者の再配置、2回目の {@code noCollision}。 */
    public static final int PKT_SETTLE = 12;

    private static final String[] NAMES = {
            "基底", "艤装", "飛行", "移動", "報告", "部位", "兵装", "回廊", "接触",
            "検査", "報告移動", "報告回廊", "確定"};

    /** {@code ||} でログを割る位置。ここから先はパケットの段階。 */
    private static final int PACKET_FIRST = PKT_CHECK;

    /**
     * 段階ごとの累計ナノ秒、その段階に付いた chunk 待ちの回数と累計ナノ秒、そして直前に区切った時刻。
     *
     * <p>同期していないのは、書く者がサーバースレッド1本しかいないから。{@link #measuring} がそれを保証する。
     */
    private static final long[] PHASES = new long[NAMES.length];
    private static final int[] PHASE_CHUNKS = new int[NAMES.length];
    private static final long[] PHASE_CHUNK_NANOS = new long[NAMES.length];
    private static long lapAt;

    /** この集計期間に機体が使ったナノ秒と、そのうち最も重かったサーバー1tick分。 */
    private static long spent;
    private static long worst;
    /** 今のサーバー tick で機体が使ったナノ秒。{@link #report} が毎tick畳む。 */
    private static long inThisTick;

    /** 同じ期間に tick された機体の延べ数と、処理された移動報告の本数。 */
    private static int machines;
    private static int packets;

    /** 集計期間のサーバー tick 数と、その開始時刻。 */
    private static int ticks;
    private static long since;

    /**
     * 計測中の tick／パケットを回しているスレッド。null なら誰も計測していない。
     *
     * <p>{@link #lap} と {@link #chunkIn} を黙らせるために要る。{@link WeaponProfile} で同じ物を落として
     * 一度失敗している——あちらの {@code lap} はクライアントの弾からも呼ばれ、クライアントでは
     * {@code open} が0を返して {@code lapAt} を進めないので、区切りは「サーバーが最後に区切ってから今まで」
     * を測っていた。段階の数字が合計を何十倍も超え、存在しない 400ms が出た。
     *
     * <p>ここでは危険がもう1つ増える。{@link #chunkIn} は {@code ServerChunkCache.getChunk} という、
     * 機体とはまったく関係なく毎tick何百回も通る場所から呼ばれる。この判定が、そのうち「今まさに機体の
     * 段階の中で起きた物」だけを数えさせている。
     */
    private static volatile Thread measuring;

    /** まだどの段階の物か決まっていない chunk 待ち。次の {@link #lap} が段階へ付け替える。 */
    private static int pendingChunks;
    private static long pendingChunkNanos;
    /** {@code getChunk} の入れ子深さと、最も外側の呼び出しが始まった時刻。 */
    private static int chunkDepth;
    private static long chunkAt;

    private AircraftProfile() {
    }

    /**
     * 機体1機分の tick が始まる。開始時刻、または計測しないなら0。
     *
     * <p>{@link AircraftEntity#tick} の先頭へ。クライアントでは0を返すので、パイロット自身の飛行モデルは
     * 一切計測に入らない——測りたいのはサーバースレッドが止まる理由だけだ。
     */
    public static long open(AircraftEntity aircraft) {
        if (!ENABLED || aircraft.level().isClientSide) {
            return 0L;
        }

        return begin();
    }

    /**
     * 移動報告1本の処理が始まる。開始時刻、または計測しないなら0。
     *
     * <p>{@code handleMoveVehicle} の HEAD へ。乗り物が機体でなければ計測しない——ボートも trolley も
     * 同じ入口を通るし、そちらの時間を機体の欄に足しても読み違えるだけだから。
     *
     * <p><b>スレッドを確かめてから始めること。</b> {@code handleMoveVehicle} の1行目は
     * {@code PacketUtils.ensureRunningOnSameThread} で、パケットが先にネットワークスレッドで届いた時は
     * そこで {@code RunningOnDifferentThreadException} を投げ、処理をサーバースレッドのキューへ回す。
     * つまり HEAD の inject は毎回2度通り、1度目はその例外で巻き戻る——RETURN の inject は通らない。
     * 素直に始めてしまうと {@link #measuring} がネットワークスレッドを指したまま残り、その後サーバー
     * スレッドが呼ぶ {@link #lap} が全部黙る。機体の段階が丸ごと消え、しかもログは正常に見える。
     */
    public static long openPacket(Entity vehicle) {
        if (!ENABLED || !(vehicle instanceof AircraftEntity)
                || !(vehicle.level() instanceof ServerLevel level)
                || !level.getServer().isSameThread()) {
            return 0L;
        }

        packets++;

        return begin();
    }

    private static long begin() {
        measuring = Thread.currentThread();
        pendingChunks = 0;
        pendingChunkNanos = 0L;
        chunkDepth = 0;
        lapAt = System.nanoTime();

        return lapAt;
    }

    /**
     * ここまでを1つの段階として区切る。溜まっていた chunk 待ちもこの段階に付く。
     *
     * <p>途中で return する経路では、その先の段階が記録されないだけで害は無い——合計は {@link #close} が
     * 別に取っているので、段階の和と合計の差が「早く抜けた分」になる。
     */
    public static void lap(int phase) {
        // 計測している当のスレッドの、計測している最中だけ。クライアント側の機体も、tick と tick の合間も、
        // 移動報告の外側も、ここを通ってはいけない。measuring 参照。
        if (!ENABLED || Thread.currentThread() != measuring) {
            return;
        }

        long now = System.nanoTime();

        PHASES[phase] += now - lapAt;
        PHASE_CHUNKS[phase] += pendingChunks;
        PHASE_CHUNK_NANOS[phase] += pendingChunkNanos;
        pendingChunks = 0;
        pendingChunkNanos = 0L;
        lapAt = now;
    }

    /**
     * その run が終わる。
     *
     * @param from {@link #open} または {@link #openPacket} が返した値。0 なら何もしない
     * @param counted 機体1機分として数えるか。tick の run なら真、パケットの run なら偽（{@link #openPacket}
     *                が入口で既に数えている）
     */
    public static void close(long from, boolean counted) {
        if (from == 0L) {
            return;
        }

        long used = System.nanoTime() - from;

        spent += used;
        inThisTick += used;
        measuring = null;

        if (counted) {
            machines++;
        }
    }

    /**
     * {@code ServerChunkCache.getChunk} に入った。{@code AircraftChunkProbeMixin} から。
     *
     * <p>入れ子で呼ばれうる。{@code managedBlock} は待っている間 chunk 側の実行キューを回すので、その中の
     * タスクがまた {@code getChunk} を呼ぶ。回数は全部数え、時間は最も外側の1回だけ数える。二重に足せば
     * 段階の chunk 時間がその段階の総時間を超える。
     *
     * @param required {@code requireChunk}。偽なら待ちは発生しないので数えない
     */
    public static void chunkIn(boolean required) {
        if (!ENABLED || !required || Thread.currentThread() != measuring) {
            return;
        }

        pendingChunks++;

        if (chunkDepth++ == 0) {
            chunkAt = System.nanoTime();
        }
    }

    /** 同じ呼び出しから出た。 */
    public static void chunkOut(boolean required) {
        if (!ENABLED || !required || chunkDepth <= 0 || Thread.currentThread() != measuring) {
            return;
        }

        if (--chunkDepth == 0) {
            pendingChunkNanos += System.nanoTime() - chunkAt;
        }
    }

    /**
     * サーバーの1tickが終わった。周期が来ていれば1行出して数え直す。
     *
     * <p>{@code ServerTickEvent.Post} を使うのは2つの理由からで、どちらも {@link WeaponProfile} が
     * {@code LevelTickEvent.Post} を使っていたことへの訂正になる。1つは、あれがワールドの数だけ呼ばれるので
     * 同じ tick を3度数えないための細工（{@code countedAt}）が要ったこと。こちらはサーバー1tickに1度しか
     * 飛ばないので細工が要らない。もう1つが重要で、{@code LevelTickEvent.Post} は
     * {@code MinecraftServer.tickChildren} の levels 段で飛ぶ——移動報告を処理する {@code connection} 段
     * <em>より前</em>だ。そこで畳むと、この instrument が測りたい物の半分が毎回1tick隣の窓へ落ちる。
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!ENABLED) {
            return;
        }

        // 「最悪の1tick」はここでしか取れない。固まりは平均に埋もれるので、これが本命の数字になる。
        worst = Math.max(worst, inThisTick);
        inThisTick = 0L;

        // 1本の run がサーバー tick をまたぐことは無いので、ここで必ず閉じる。閉じ忘れ——例外で
        // RETURN の inject を通らずに抜けた移動報告など——を次の tick へ持ち越さないための保険。
        // 持ち越すと lapAt が古いまま次の lap が走り、存在しない数百 ms がその段階に付く。
        // WeaponProfile がまさにそれで嘘をついた。measuring 参照。
        measuring = null;

        long now = System.nanoTime();

        if (since == 0L) {
            since = now;

            return;
        }

        if (++ticks < EVERY) {
            return;
        }

        long span = now - since;

        // 何も飛んでいない間は黙っている。駐機中のログでファイルを埋めても読む物が無い。
        if (machines > 0 || packets > 0) {
            StringBuilder tickSplit = new StringBuilder();
            StringBuilder packetSplit = new StringBuilder();
            int chunks = 0;
            long chunkNanos = 0L;

            for (int phase = 0; phase < PHASES.length; phase++) {
                StringBuilder into = phase < PACKET_FIRST ? tickSplit : packetSplit;

                into.append(' ').append(NAMES[phase]).append('=').append(PHASES[phase] / MILLIS)
                        .append('/').append(PHASE_CHUNKS[phase]);

                chunks += PHASE_CHUNKS[phase];
                chunkNanos += PHASE_CHUNK_NANOS[phase];
            }

            AshVehicles.LOGGER.info(
                    "[air] {} tick / {} ms (1tick {} ms・最悪 {} ms)  機体 {} 機延べ・報告 {} 本  "
                            + "機体に {} ms = {}%  chunk待ち {} 回 {} ms |{} ||{}  (ms/chunk回数)",
                    ticks, span / MILLIS, span / MILLIS / ticks, worst / MILLIS, machines, packets,
                    spent / MILLIS, span <= 0L ? 0 : Math.round(spent * 100.0 / span),
                    chunks, chunkNanos / MILLIS, tickSplit, packetSplit);
        }

        ticks = 0;
        machines = 0;
        packets = 0;
        spent = 0L;
        worst = 0L;
        since = now;

        java.util.Arrays.fill(PHASES, 0L);
        java.util.Arrays.fill(PHASE_CHUNKS, 0);
        java.util.Arrays.fill(PHASE_CHUNK_NANOS, 0L);
    }
}
