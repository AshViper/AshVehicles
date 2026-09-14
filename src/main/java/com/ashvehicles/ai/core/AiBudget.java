package com.ashvehicles.ai.core;

import java.util.Arrays;

import com.ashvehicles.ai.AiConfig;

import net.minecraft.server.MinecraftServer;

/**
 * サーバー全体で、1 tick のうちに使ってよい重い処理の数。
 *
 * <p><b>周期のずらし（{@link Cadence}）は平均を均すが、山は均さない。</b> 拠点が取られた瞬間には陣営の全員が
 * 同じ tick に考え直し、道を引き直し、遮蔽を探し始める——20両ぶんの経路探索が1 tick に集まるのは、まさに
 * 「その瞬間だけサーバーが止まる」形の負荷だ。だから種類ごとに1 tick の上限を置き、溢れた依頼は次の tick へ
 * 回す。断られた側は前の答え（古い道・古い射線）を使い続けるだけで、壊れる物は無い。
 *
 * <p>数え直しは tick が変わって最初の問い合わせで行う。サーバーの tick イベントに頼らないので、誰が先に
 * 呼ぶかに依存しない。<b>サーバースレッドだけから呼ぶこと。</b>
 */
public final class AiBudget {
    /** 予算の種類。 */
    public enum Kind {
        /** ブロックを歩く視線1本（{@code Level.clip}）。 */
        RAY,
        /** 経路探索1回。 */
        PLAN,
        /** 遮蔽・側面・撤退先の探索1回。 */
        SEARCH
    }

    private static final Kind[] KINDS = Kind.values();

    private static int tick = Integer.MIN_VALUE;
    private static final int[] SPENT = new int[KINDS.length];

    /** 断った数の累計。{@code /tdm ai perf} が「予算が足りているか」を示すために読む。 */
    private static final long[] DENIED = new long[KINDS.length];

    private AiBudget() {
    }

    /** その種類を1つ使ってよいか。よければ数に入れて true。 */
    public static boolean spend(MinecraftServer server, Kind kind) {
        return spend(server.getTickCount(), kind);
    }

    /** {@link #spend(MinecraftServer, Kind)} の、tick を直接渡す形。 */
    public static boolean spend(int now, Kind kind) {
        if (now != tick) {
            tick = now;
            Arrays.fill(SPENT, 0);
        }

        int at = kind.ordinal();

        if (SPENT[at] >= limit(kind)) {
            DENIED[at]++;

            return false;
        }

        SPENT[at]++;

        return true;
    }

    /** この tick に使った数。 */
    public static int spent(Kind kind) {
        return SPENT[kind.ordinal()];
    }

    /** これまでに断った数の累計。 */
    public static long denied(Kind kind) {
        return DENIED[kind.ordinal()];
    }

    /** サーバーが止まったときに。1つの JVM で次のワールドを開いても、前の数を持ち越さない。 */
    public static void clear() {
        tick = Integer.MIN_VALUE;
        Arrays.fill(SPENT, 0);
        Arrays.fill(DENIED, 0L);
    }

    private static int limit(Kind kind) {
        AiConfig.Budget budget = AiConfig.get().budget();

        return switch (kind) {
            case RAY -> budget.raysPerTick();
            case PLAN -> budget.plansPerTick();
            case SEARCH -> budget.searchesPerTick();
        };
    }
}
