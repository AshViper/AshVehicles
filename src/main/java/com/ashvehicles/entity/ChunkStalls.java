package com.ashvehicles.entity;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import com.ashvehicles.AshVehicles;

/**
 * 一時的。チャンク要求がサーバースレッドを何ミリ秒止めたかを、呼んだ場所ごとに数える。
 *
 * <p><b>これが在る理由。</b> 「機体で飛ぶと固まる」の調査で、原因の候補は何度も入れ替わった——
 * {@code Entity.setPosRaw} の強制ロード、回廊の {@code forceChunk}、当たり判定、GC、確保レート。その
 * どれもが一部は正しく、どれも決め手にならなかった。共通しているのは、全部が最後に
 * {@code ServerChunkCache.getChunk} の {@code managedBlock} へ行き着くこと。ならそこで数えれば、誰が
 * どれだけ止めているかは推論ではなく事実になる。
 *
 * <p>閾値を超えた1件ごとに、スタックからこの MOD かバニラの意味のあるフレームを1つ選んで数え、1秒に
 * 1度まとめて出す。行が積み上がっている場所が犯人。
 *
 * <p>調査が終わったらこのクラスと {@code ChunkStallMixin} を消すこと。
 */
public final class ChunkStalls {
    /** 調査中だけ真にすること。 */
    private static final boolean ENABLED = true;

    /** これ未満の要求は数えない（ミリ秒）。キャッシュに当たった要求は毎tick何百回も来る。 */
    private static final long WORTH_NAMING = 3L;

    /** まとめて出す間隔（ミリ秒）。 */
    private static final long EVERY = 1000L;

    /** 1行に出す呼び出し元の数。 */
    private static final int NAMES = 6;

    /** 1件につき名前を辿るフレーム数。1つでは配管の外に出られないことがある。 */
    private static final int CALLERS = 3;

    private static final long MILLIS = 1_000_000L;

    /** 入れ子の深さ。{@code managedBlock} は待つ間に他のチャンク処理を回すので、内側の要求も来る。 */
    private static int depth;
    private static long since;

    /** 呼び出し元ごとの、止めた合計時間（ナノ秒）と件数。 */
    private static final Map<String, long[]> BLAME = new HashMap<>();
    private static long reportedAt;

    private ChunkStalls() {
    }

    /** 要求が始まった。いちばん外側だけ時計を持つ。 */
    public static void enter() {
        if (!ENABLED) {
            return;
        }

        if (depth++ == 0) {
            since = System.nanoTime();
        }
    }

    /** 要求が終わった。長ければ誰のせいか記録し、周期が来ていれば出す。 */
    public static void leave(int chunkX, int chunkZ, boolean requireChunk) {
        if (!ENABLED || --depth != 0) {
            return;
        }

        long took = System.nanoTime() - since;

        if (took >= WORTH_NAMING * MILLIS) {
            long[] tally = BLAME.computeIfAbsent(blame(), key -> new long[2]);

            tally[0] += took;
            tally[1]++;
        }

        long now = System.currentTimeMillis();

        if (now - reportedAt < EVERY) {
            return;
        }

        reportedAt = now;
        report();
    }

    /**
     * この要求を誰のせいにするか。
     *
     * <p>スタックを外側へ辿り、チャンクシステム自身の中でない最初のフレームを採る。そこが「地面を
     * 訊いた人」だ。
     */
    private static String blame() {
        StringBuilder chain = new StringBuilder();
        String door = null;
        int named = 0;

        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String owner = frame.getClassName();

            if (plumbing(owner)) {
                // 最後に通った配管を1つだけ覚える。同じ「地面を訊いた人」でも、getChunk を直接呼んだのか
                // getBlockState 経由なのかで意味が違う。
                if (named == 0) {
                    door = shortName(owner) + "." + frame.getMethodName();
                }

                continue;
            }

            if (named > 0) {
                chain.append('<');
            }

            chain.append(shortName(owner)).append('.').append(frame.getMethodName())
                    .append(':').append(frame.getLineNumber());

            if (++named >= CALLERS) {
                break;
            }
        }

        if (named == 0) {
            return door == null ? "?" : door;
        }

        return (door == null ? "" : door + "<") + chain;
    }

    /**
     * チャンクへ至る配管か。ここを名前にしても「チャンクを読んだ」以上のことは分からないので、素通りして
     * その外側を探す。
     */
    private static boolean plumbing(String owner) {
        return owner.startsWith("com.ashvehicles.mixin.ChunkStall")
                || owner.startsWith("com.ashvehicles.entity.ChunkStalls")
                || owner.startsWith("net.minecraft.server.level.ServerChunkCache")
                || owner.startsWith("net.minecraft.world.level.chunk.")
                || owner.startsWith("net.minecraft.world.level.Level")
                || owner.equals("net.minecraft.world.level.BlockGetter")
                || owner.equals("net.minecraft.world.level.LevelReader")
                || owner.equals("net.minecraft.world.level.CollisionGetter")
                || owner.equals("net.minecraft.world.level.BlockCollisions")
                || owner.startsWith("net.minecraft.server.level.ServerLevel$")
                || owner.startsWith("java.")
                || owner.startsWith("jdk.");
    }

    private static String shortName(String owner) {
        return owner.substring(owner.lastIndexOf('.') + 1);
    }

    /** 1秒ぶんをまとめて1行。止めていない秒は黙っている。 */
    private static void report() {
        if (BLAME.isEmpty()) {
            return;
        }

        StringBuilder line = new StringBuilder();
        long total = 0L;

        for (long[] tally : BLAME.values()) {
            total += tally[0];
        }

        BLAME.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, long[]>>comparingLong(e -> e.getValue()[0]).reversed())
                .limit(NAMES)
                .forEach(e -> line.append("  ").append(e.getKey())
                        .append(" =").append(e.getValue()[0] / MILLIS)
                        .append("ms x").append(e.getValue()[1]));

        AshVehicles.LOGGER.info("[stall] 合計 {} ms /秒{}", total / MILLIS, line);
        BLAME.clear();
    }
}
