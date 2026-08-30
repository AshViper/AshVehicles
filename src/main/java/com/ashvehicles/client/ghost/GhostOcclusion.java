package com.ashvehicles.client.ghost;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.ghost.dh.DHIntegration;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * カメラとゴーストの間に世界が立ち塞がっているか。
 *
 * <p>ゴーストは霧無し・照明無しで描かれ、空を背に読める。だが山を突き抜けても同じように読めてしまい、それは描か
 * ないより悪い。ゲームの深度バッファがその一部は無料で拾ってくれる。ゴーストはゲームが描いた物に対して深度テスト
 * するので、描画済み地形の背後にある物は既に隠れる。逃れるのは Distant Horizons の地形だ。あれはゲームのバッファ
 * に深度をまったく残さない。加えて念のため、ゲーム自身のブロックもトレースする。
 *
 * <p>そこで線は2度トレースする。ロード範囲まではゲーム自身のブロックを、ゲームスレッドで——これは安い。その先は
 * Distant Horizons の LOD 列を、<em>ワーカースレッドで</em>。最後の点は選択の余地が無い。Distant Horizons のデータ
 * リポジトリは要求された物を自前のスレッドでロードし、届くまで呼び出し元をブロックする。そしてそれらのスレッドが
 * 今度はゲームスレッドを待っていることがある——ゲームスレッドから問い合わせてクライアントを完全にデッドロックさせ
 * た実績がある。代わりにワーカーが待ち、ゴーストは新しい答えが着くまで前回の答えを保つ。
 *
 * <p><b>どちらの半分でも、狙う点は1つではなく2つだ。</b>地上に立つ物の中心へ、ほぼ同じ高さの視点から引いた線は
 * 全長にわたって地表を掠めるので、途中に1ブロックの起伏があるだけで、丸見えの物を「隠れている」と報告する。だから
 * 中心と上端の両方を試し、両方が塞がれたときだけ隠れていると数える——それが問いへの正直な答えでもある。尾翼が
 * 尾根の上に見えている形は見えているのだから。線は狙う点の手前で止めるので、物が乗っている地面がその物の前に立つ
 * 物として数えられることはない。
 *
 * <p>2つ目の点は、ゲーム自身のブロックより Distant Horizons にとってこそ重要だ。あちらの地形は実物の平均であり、
 * 遠くに描かれるほど粗くなるので、世界では遠い尾根の数ブロック上を通る線が、Distant Horizons の尾根では真っ直ぐ
 * 貫通しうる。点を1つしか問わないと——2026-08-21 まではそうだった——丘の上空に明らかにいる機体が隠れてしまう。
 * レイは今も最大2本だ。上端を問うのは中心が塞がれて戻ってきたときだけ。
 *
 * <p><b>クライアントが構築済みの世界の内側に立つゴーストには、この一切が適用されない。</b>ゲームはその周りの地形を
 * 描いており、ゴーストは実位置にその地形自身の光と霧で描かれるので、深度バッファが正確に——ピクセル単位で、実際に
 * 遮っている地面によって——隠してくれる。加えて線をトレースしてもそれを覆すだけであり、覆す向きは一方向だ。2点は
 * 機体を表すには粗すぎるし、<em>地上に立つ</em>物への視線は全長にわたってその地面を擦るので、線上のどこか1つの
 * 起伏が丸見えの戦車を隠す。地上車両が引き継ぎ距離で見失われていた理由はまさにそれだ。構築済み世界の内側では答え
 * は常に「隠れていない」であり、深度バッファが決着させる。
 *
 * <p>残る各ゴーストへの問い合わせは毎フレームではなく数tickごとで、マネージャが問い合わせを分散させるので、1つの
 * tickが全員分を払うことは無い。ワーカーのキューには上限がある。入り切らなかった判定は捨て、そのゴーストは次の巡回
 * で改めて問う。
 */
final class GhostOcclusion {
    /** ワーカー待ちにできる判定の同時上限数。 */
    private static final int QUEUE_LIMIT = 64;

    /** レイの終端で差し引くブロック数。物が乗っている地面をその物の「手前」と数えないため。 */
    private static final double TARGET_MARGIN = 1.5;

    private static ThreadPoolExecutor worker;

    private GhostOcclusion() {
    }

    /**
     * 判定を開始する。ゲーム自身のブロックで決着するなら即答し、そうでなければ残りをワーカーへ渡す。ゲーム
     * スレッド。
     *
     * @param eye カメラ位置
     * @param now ゲームtick。判定開始時刻として記録する
     * @return 実際にレイを消費したか。深度バッファが既に答えている物はレイを消費しないので、呼び出し元のレイ予算
     *         は必要とするゴーストへ回すべきだ
     */
    static boolean check(ClientLevel level, Vec3 eye, EntityGhost ghost, long now) {
        ghost.beginOcclusion(now);

        GhostSnapshot snapshot = ghost.current();

        // 構築済み世界の内側に立っている。このメソッドの管轄外だ。クラスの注記参照。
        if (GhostRenderDispatcher.isBuilt(BlockPos.containing(snapshot.position()))) {
            ghost.finishOcclusion(false);

            return false;
        }
        // 問い合わせられるのはこのクライアントが実際に持つ地面だけ。その外では全ブロックが空気と読まれるので、
        // 線を追ってもコストばかりかかって何も見つからない。
        double loaded = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        List<Vec3> candidates = new ArrayList<>(2);

        // ゲーム自身のブロックが隠していない分だけを Distant Horizons へ問う。どちらの点も生き残らなければ、
        // 問うべき物は残っていない。
        for (Vec3 candidate : new Vec3[] { snapshot.centre(), snapshot.top() }) {
            if (!blockedByWorld(level, eye, candidate, loaded)) {
                candidates.add(candidate);
            }
        }

        if (candidates.isEmpty()) {
            ghost.finishOcclusion(true);

            return true;
        }

        // ロード範囲内にあってブロックに隠されなかった点は丸見えの点だ。ここからそこまでの間に、それを隠す
        // Distant Horizons の地形も存在しない。
        for (Vec3 candidate : candidates) {
            if (candidate.distanceTo(eye) <= loaded) {
                ghost.finishOcclusion(false);

                return true;
            }
        }

        if (!GhostConfig.occludeBehindDh() || !DHIntegration.isActive()) {
            ghost.finishOcclusion(false);

            return true;
        }

        List<Vec3> asked = List.copyOf(candidates);

        try {
            worker().execute(() -> {
                boolean hidden = true;

                try {
                    for (Vec3 point : asked) {
                        if (!DHIntegration.isOccluded(level, eye, point, loaded)) {
                            hidden = false;
                            break;
                        }
                    }
                } catch (RuntimeException e) {
                    hidden = false;
                }

                ghost.finishOcclusion(hidden);
            });
        } catch (RejectedExecutionException e) {
            // キューが一杯。前回の答えを保ち、次回また問う。
            ghost.finishOcclusion(ghost.isOccluded());
        }

        return true;
    }

    /** クライアントが持つ範囲のゲーム自身のブロックが、ある点への行く手を塞いでいるか。 */
    private static boolean blockedByWorld(ClientLevel level, Vec3 eye, Vec3 target, double loaded) {
        Vec3 gap = target.subtract(eye);
        double away = gap.length();

        if (away < 1.0E-4) {
            return false;
        }

        double reach = Math.min(loaded, away - TARGET_MARGIN);

        if (reach <= 0.0) {
            return false;
        }

        Vec3 end = eye.add(gap.scale(reach / away));
        // 目に見えるかを基準にする。ガラスや葉は壁ではない。
        HitResult hit = level.clip(new ClipContext(eye, end,
                ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));

        return hit.getType() != HitResult.Type.MISS;
    }

    /** キューの中身を捨てる。レベル変更時。 */
    static synchronized void reset() {
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
    }

    private static synchronized ThreadPoolExecutor worker() {
        if (worker == null) {
            worker = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(QUEUE_LIMIT), runnable -> {
                        Thread thread = new Thread(runnable, AshVehicles.MODID + "-ghost-occlusion");
                        thread.setDaemon(true);
                        return thread;
                    });
            worker.allowCoreThreadTimeOut(true);
        }

        return worker;
    }
}
