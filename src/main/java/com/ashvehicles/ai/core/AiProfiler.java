package com.ashvehicles.ai.core;

/**
 * AI の各系が1 tick あたりに使った時間。{@code /tdm ai perf} が読む。
 *
 * <p><b>測れない物は直せない。</b> 「AI がサーバーを重くしていないか」は感想ではなく ms で答える問いで、
 * どの系が払っているかまで分からなければ、周期（{@link Cadence}）と予算（{@link AiBudget}）のどれを触るべき
 * かが決まらない。
 *
 * <p>測るのは {@code System.nanoTime} の差だけで、オブジェクトは作らない——40両が毎 tick 呼ぶ。
 * {@value #WINDOW} tick ごとに窓を締め、直前の窓の平均を出す。<b>サーバースレッド専用。</b>
 */
public final class AiProfiler {
    /** 計測の単位。 */
    public enum Section {
        PERCEPTION,
        DECISION,
        TACTICS,
        NAVIGATION,
        COMBAT,
        TEAM,
        DEBUG,
        LOGGING
    }

    /** 窓の長さ（tick）。10秒。 */
    public static final int WINDOW = 200;

    private static final Section[] SECTIONS = Section.values();

    private static final long[] RUNNING = new long[SECTIONS.length];
    private static final long[] SHOWN = new long[SECTIONS.length];
    private static final long[] RUNNING_CALLS = new long[SECTIONS.length];
    private static final long[] SHOWN_CALLS = new long[SECTIONS.length];

    private static int lastTick = Integer.MIN_VALUE;
    private static int windowTicks;
    private static int shownTicks;

    private AiProfiler() {
    }

    /** 測り始め。返った値を {@link #stop} へ渡す。 */
    public static long start() {
        return System.nanoTime();
    }

    public static void stop(Section section, long started) {
        RUNNING[section.ordinal()] += System.nanoTime() - started;
        RUNNING_CALLS[section.ordinal()]++;
    }

    /** サーバー tick ごとに1回。同じ tick に2度呼ばれても1度しか数えない。 */
    public static void tick(int now) {
        if (now == lastTick) {
            return;
        }

        lastTick = now;

        if (++windowTicks < WINDOW) {
            return;
        }

        System.arraycopy(RUNNING, 0, SHOWN, 0, RUNNING.length);
        System.arraycopy(RUNNING_CALLS, 0, SHOWN_CALLS, 0, RUNNING_CALLS.length);
        java.util.Arrays.fill(RUNNING, 0L);
        java.util.Arrays.fill(RUNNING_CALLS, 0L);
        shownTicks = windowTicks;
        windowTicks = 0;
    }

    /** 直前の窓での、1 tick あたりの平均（ms）。まだ窓が1つも締まっていなければ0。 */
    public static double millisPerTick(Section section) {
        return shownTicks <= 0 ? 0.0 : SHOWN[section.ordinal()] / 1.0E6 / shownTicks;
    }

    /** 直前の窓での呼び出し回数。 */
    public static long calls(Section section) {
        return SHOWN_CALLS[section.ordinal()];
    }

    /** 全系の合計（ms/tick）。 */
    public static double totalMillisPerTick() {
        double total = 0.0;

        for (Section section : SECTIONS) {
            total += millisPerTick(section);
        }

        return total;
    }

    /** 窓が締まっているか。まだ {@value #WINDOW} tick 経っていない間は数字に意味が無い。 */
    public static boolean ready() {
        return shownTicks > 0;
    }

    public static void clear() {
        java.util.Arrays.fill(RUNNING, 0L);
        java.util.Arrays.fill(SHOWN, 0L);
        java.util.Arrays.fill(RUNNING_CALLS, 0L);
        java.util.Arrays.fill(SHOWN_CALLS, 0L);
        lastTick = Integer.MIN_VALUE;
        windowTicks = 0;
        shownTicks = 0;
    }
}
