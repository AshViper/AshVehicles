package com.ashvehicles.client;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.ghost.dh.DHIntegration;

import net.minecraft.client.multiplayer.ClientLevel;

/**
 * ロード範囲の外の地面を、Distant Horizons が持っている粗い地形（LOD）から読む。
 *
 * <p>{@link Terrain} が答えられるのはクライアントが chunk を持つ範囲までで、その外は「知らない」だ——それが
 * 正直な答えである限りは。だが DH を入れている環境では知らないわけではない。何十チャンクも先の地形を、粗い
 * ながら実際に持って描いている。この MOD がその地形を<em>見る</em>ことは既にしている
 * （{@code GhostOcclusion} が遠方の機体の遮蔽を問う）ので、同じデータを<em>読む</em>ことも自然に続く。
 *
 * <p><b>ゲームスレッドからは絶対に問わない。</b>DH の地形問い合わせはデータを読み込む future を待ち、その
 * 待ちの向こうで DH のスレッドがメインスレッドを待っていることがある。ゲームスレッドから呼べばクライアントは
 * その場で固まる（jstack で確認済み）。だからここは専用のデーモンワーカーを1本持ち、問い合わせは全部そこへ
 * 送る。答えは書き込み先の配列へ<em>届いた順に</em>入っていくので、地図は埋まっていく途中を描ける。
 *
 * <p><b>1回の依頼は格子1枚。</b>依頼した側は配列を持ち、ワーカーはそこへ直接書く。要素ごとの競合はあり得るが
 * 害にならない——1フレーム古い高さが1マスに出るだけで、次のフレームには新しい値が乗っている。取り消しは旗1つ
 * で、視野が動いた瞬間に前の依頼を降ろす。
 */
public final class LodTerrain {
    /** 待たせておける依頼の数。地図は1枚しか出ないので、溜まるのは連打した分だけ。 */
    private static final int QUEUE_LIMIT = 4;

    private static ThreadPoolExecutor worker;

    private LodTerrain() {
    }

    /** 走っている依頼1つ。依頼した側はこれを持って、要らなくなったら降ろす。 */
    public static final class Fill {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile int done;

        /** この依頼はもう要らない。ワーカーは次のマスで手を止める。 */
        public void cancel() {
            this.cancelled.set(true);
        }

        /** 何マス埋まったか。地図が「読み込み中」を出すために読む。 */
        public int done() {
            return this.done;
        }
    }

    /**
     * 格子1枚分の高さを DH から埋める。
     *
     * <p>ワールドが分かるのは呼んだ側なので、原点と刻みはブロックで受け取る。書き込み先は呼んだ側の配列で、
     * ここは確保も交換もしない——地図は毎フレームその配列を読んでおり、差し替えれば描いている最中の物が消える。
     *
     * @param height 高さの書き込み先。長さは {@code cells * cells}。埋まらなかったマスは触らない
     * @param liquid そのマスが水面かどうかの書き込み先。同じ長さ
     * @param cells 1辺のマス数
     * @param originX 格子の左上（-X 側・-Z 側）の隅のワールド座標
     * @param step 1マスの1辺（ブロック）
     * @return 走らせた依頼。ワーカーが受け取れなければ null
     */
    @javax.annotation.Nullable
    public static Fill request(ClientLevel level, double[] height, boolean[] liquid, int cells,
            double originX, double originZ, double step) {
        if (!DHIntegration.isActive()) {
            return null;
        }

        Fill fill = new Fill();

        try {
            worker().execute(() -> run(level, fill, height, liquid, cells, originX, originZ, step));
        } catch (RejectedExecutionException e) {
            // キューが一杯。地図は次の視野変更でまた頼む。
            return null;
        }

        return fill;
    }

    private static void run(ClientLevel level, Fill fill, double[] height, boolean[] liquid, int cells,
            double originX, double originZ, double step) {
        // 中心から外へ埋める。乗員が最初に見るのは自分の周りで、端が最後になるのは正しい順序だ。
        for (int ring = 0; ring <= cells / 2 && !fill.cancelled.get(); ring++) {
            for (int row = cells / 2 - ring; row <= cells / 2 + ring; row++) {
                for (int column = cells / 2 - ring; column <= cells / 2 + ring; column++) {
                    if (fill.cancelled.get()) {
                        return;
                    }

                    // 環の内側は前の周で済んでいる。縁だけを歩く。
                    if (row < 0 || row >= cells || column < 0 || column >= cells
                            || (Math.abs(row - cells / 2) != ring && Math.abs(column - cells / 2) != ring)) {
                        continue;
                    }

                    int at = row * cells + column;

                    if (!Double.isNaN(height[at])) {
                        continue;
                    }

                    double[] top;

                    try {
                        top = DHIntegration.columnTop(level,
                                (int) Math.floor(originX + (column + 0.5) * step),
                                (int) Math.floor(originZ + (row + 0.5) * step));
                    } catch (RuntimeException e) {
                        continue;
                    }

                    if (top == null) {
                        continue;
                    }

                    liquid[at] = top[1] > 0.5;
                    // 高さを最後に入れる。読む側はこれで埋まったかを判定するので、逆順だと液体旗が
                    // 入る前の1フレームだけ水面が陸に見える。
                    height[at] = top[0];
                    fill.done++;
                }
            }
        }
    }

    /** キューの中身を捨てる。レベル変更時。 */
    public static synchronized void reset() {
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
    }

    private static synchronized ThreadPoolExecutor worker() {
        if (worker == null) {
            worker = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(QUEUE_LIMIT), runnable -> {
                        Thread thread = new Thread(runnable, AshVehicles.MODID + "-lod-terrain");
                        thread.setDaemon(true);
                        return thread;
                    });
            worker.allowCoreThreadTimeOut(true);
        }

        return worker;
    }
}
