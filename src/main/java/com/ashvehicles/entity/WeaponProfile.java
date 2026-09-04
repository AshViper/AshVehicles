package com.ashvehicles.entity;

import com.ashvehicles.AshVehicles;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 一時的。飛翔体がサーバーの1tickをどれだけ使っているかを、tick の中の段階ごとに数えてログに出す。
 *
 * <p><b>これが在る理由。</b> 「撃つと重い」は測らずに直せない。バニラの {@code /debug} が出すのは TPS の
 * 数字1つだけで内訳が無い。最初の版は「弾に何 %」までしか答えず、その答えが 95% だったので、次に要るのは
 * 「弾の tick の<em>どこ</em>で」になった。段階はそのために分けてある。
 *
 * <p>読み方は、割合の大きい段階が犯人。ほぼ全部が1つの段階に乗るはずで、乗らなければ「どこでもなく全体
 * が遅い」——つまりスレッドの外側の問題になる。
 *
 * <p><b>普段は切っておくこと。</b> {@link #ENABLED} が偽の間、{@link #open} は 0 を返し他は何もしない。
 * 調査が終わったらこのクラスごと消してよい。
 */
public final class WeaponProfile {
    /** 調査中だけ真にすること。 */
    private static final boolean ENABLED = true;

    /** 何 tick ごとに1行出すか。20 = 毎秒1行。 */
    private static final int EVERY = 20;

    private static final long MILLIS = 1_000_000L;

    /** tick の中の段階。{@link VehicleProjectile#tick} が順に通る。 */
    public static final int GATE = 0;
    public static final int BASE = 1;
    public static final int STEER = 2;
    public static final int FUSE = 3;
    public static final int HIT = 4;
    public static final int MOVE = 5;

    private static final String[] NAMES = {"門", "基底", "誘導", "信管", "命中", "移動"};

    /** 段階ごとの累計ナノ秒と、直前に区切った時刻。 */
    private static final long[] PHASES = new long[NAMES.length];
    private static long lapAt;

    /** この集計期間に飛翔体の tick が使ったナノ秒。 */
    private static long spent;
    /** 同じ期間に tick された飛翔体の延べ数と、そのうち世界へ問い合わせた数。 */
    private static int shots;
    private static int asking;
    /** 集計期間のサーバー tick 数と、その開始時刻。 */
    private static int ticks;
    private static long since;
    /** 最後に数えたサーバー tick。ワールドは3つあるので、同じ tick を3度数えないための記憶。 */
    private static int countedAt = -1;

    private WeaponProfile() {
    }

    /**
     * 計測中の tick を回しているスレッド。null なら誰も計測していない。
     *
     * <p>{@link #lap} を黙らせるために要る。あちらは {@code flightTick} の中から呼ばれるので、
     * サーバーだけでなくクライアントの弾も通る——そしてクライアントでは {@link #open} が0を返して
     * {@link #lapAt} を進めないので、区切りは「サーバーが最後に区切ってから今まで」を測ってしまう。
     * 段階の数字が合計を何十倍も超えていたのはこれで、門の 400ms は存在しない時間だった。
     */
    private static volatile Thread measuring;

    /** 飛翔体1発の tick が始まる。開始時刻、または計測しないなら0。 */
    public static long open(VehicleProjectile shot) {
        if (!ENABLED || shot.level().isClientSide) {
            return 0L;
        }

        measuring = Thread.currentThread();
        lapAt = System.nanoTime();

        return lapAt;
    }

    /**
     * ここまでを1つの段階として区切る。
     *
     * <p>途中で return する経路では、その先の段階が記録されないだけで害は無い——合計は
     * {@link #close} が別に取っているので、段階の和と合計の差が「早く抜けた分」になる。
     */
    public static void lap(int phase) {
        // 計測している当のスレッドの、計測している最中だけ。クライアントの弾も、サーバーの tick と tick
        // の合間も、ここを通ってはいけない。measuring 参照。
        if (!ENABLED || Thread.currentThread() != measuring) {
            return;
        }

        long now = System.nanoTime();

        PHASES[phase] += now - lapAt;
        lapAt = now;
    }

    /**
     * その tick が終わる。
     *
     * @param from {@link #open} が返した値。0 なら何もしない
     * @param asked この tick にこの弾が世界へ問い合わせたか（演算範囲の内側にいたか）
     */
    public static void close(long from, boolean asked) {
        if (from == 0L) {
            return;
        }

        spent += System.nanoTime() - from;
        measuring = null;
        shots++;

        if (asked) {
            asking++;
        }
    }

    /** ワールドの1tickが終わった。周期が来ていれば1行出して数え直す。{@link WeaponTicker} から。 */
    public static void report(ServerLevel level) {
        if (!ENABLED) {
            return;
        }

        MinecraftServer server = level.getServer();

        // ワールドの数だけ呼ばれるので、同じサーバー tick は1度しか数えない。数え違えると「1tickあたり」
        // がワールドの数だけ小さく出る。
        if (server.getTickCount() == countedAt) {
            return;
        }

        countedAt = server.getTickCount();

        long now = System.nanoTime();

        if (since == 0L) {
            since = now;

            return;
        }

        if (++ticks < EVERY) {
            return;
        }

        long span = now - since;

        // 何も飛んでいない間は黙っている。撃っていない時間のログでファイルを埋めても読む物が無い。
        if (shots > 0) {
            StringBuilder split = new StringBuilder();

            for (int phase = 0; phase < PHASES.length; phase++) {
                split.append(' ').append(NAMES[phase]).append('=').append(PHASES[phase] / MILLIS);
            }

            AshVehicles.LOGGER.info(
                    "[shots] {} tick / {} ms (1tick {} ms)  弾 {} 発延べ・うち判定 {}  弾に {} ms = {}% |{} (ms)",
                    ticks, span / MILLIS, span / MILLIS / ticks, shots, asking, spent / MILLIS,
                    span <= 0L ? 0 : Math.round(spent * 100.0 / span), split);
        }

        ticks = 0;
        shots = 0;
        asking = 0;
        spent = 0L;
        since = now;

        java.util.Arrays.fill(PHASES, 0L);
    }
}
