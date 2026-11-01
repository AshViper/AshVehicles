package com.ashvehicles.client.ghost;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.MatchMarks;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.pipeline.RenderTarget;

import org.lwjgl.opengl.GL30;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 味方の輪郭（陣営に入っていない者には全陣営の輪郭）を、模型が見えている限りどの距離でも引く。
 *
 * <p><b>輪郭が引き継ぎ距離で消えていた。</b> 味方の発光はバニラのアウトラインパスそのもの（{@link MatchMarks}）
 * で、あのパスが縁取るのはゲーム自身のエンティティループが描いた物だけだ。ところが機体はゴースト開始距離
 * （既定 128）でゲームのレンダラーから降板し、以降はゴーストパスが模型を描く。模型は数キロ先まで見えている
 * のに、輪郭は 128 ブロックで切れていた。
 *
 * <p><b>ゴーストもアウトラインバッファへ描く。</b> 味方のゴーストは {@link OutlineBufferSource} を通して
 * 描かれ、ゲームがエンティティに対してやるのと同じく、色だけの影がアウトライン用のターゲットへ落ちる。
 * 色は {@code Entity.getTeamColor} ——つまり {@code MatchGlowColourMixin} の答えで、近くと遠くで色が違う
 * ことは無い。
 *
 * <p><b>バニラのポスト処理を後ろへずらす。</b> 輪郭を実際に引く（影の縁を検出する）のは
 * {@code LevelRenderer.renderLevel} の途中、エンティティの直後の {@code entityEffect.process} 1回で、
 * ゴーストパスはそれより後（AFTER_PARTICLES、Iris 使用中は AFTER_LEVEL）に走る。後から影を足しても
 * 処理は済んでいるし、もう1回処理すると既に引いた輪郭の縁をまた検出して線が二重になる。だから
 * {@code GhostOutlineMixin} がバニラの1回を止めて「保留」を立て、ゴーストパスの後でここが1回だけ走らせる。
 * 合成（{@code doEntityOutline}）は {@code renderLevel} の外なので、AFTER_LEVEL までに処理すれば間に合う。
 *
 * <p>遮蔽の扱いはバニラと同じだ——輪郭の描画型は深度を見ないので、尾根の手前側の半分だけが見えている
 * 機体でも輪郭は全身ぶん出る。ゴーストが丸ごと隠れた（OCCLUDED・霧・視錐台外）ときは模型ごと描かれない
 * ので、輪郭も出ない。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class GhostOutline {
    /** バニラが輪郭を処理しようとしたか、ゴーストが影を足したか。このフレームのどこかで1回処理する。 */
    private static boolean pending;

    private static float pendingTicks;

    private GhostOutline() {
    }

    /**
     * バニラの輪郭処理を保留にする。{@code GhostOutlineMixin} から。呼び出し元は処理を飛ばす。
     */
    public static void deferVanilla(float ticks) {
        pending = true;
        pendingTicks = ticks;
    }

    /**
     * そのゴーストを縁取るなら、アウトラインを添えたバッファを返す。縁取らないなら {@code null}。
     * エンティティを失ったゴーストは陣営が分からない（乗っている者で決めるので）ので縁取らない。
     */
    @Nullable
    static MultiBufferSource outlined(Minecraft minecraft, @Nullable Entity entity) {
        if (entity == null || !minecraft.levelRenderer.shouldShowEntityOutlines() || !MatchMarks.glows(entity)) {
            return null;
        }

        OutlineBufferSource outline = minecraft.renderBuffers().outlineBufferSource();
        int colour = entity.getTeamColor();

        outline.setColor(colour >> 16 & 0xFF, colour >> 8 & 0xFF, colour & 0xFF, 255);
        pending = true;

        return outline;
    }

    /**
     * ゴーストパスの後で。ゴーストパス自身は NORMAL で走るので、LOWEST ならこちらが必ず後になる。パスが
     * 早くに帰ったフレーム（ゴーストが無い・一時停止）でも、保留したバニラの分はここで処理される。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onRenderLevel(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();

        if (stage != RenderLevelStageEvent.Stage.AFTER_PARTICLES && stage != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }

        // Iris 使用中はゴーストパスが AFTER_LEVEL へ移るので、その前に処理してはならない。
        if (stage == RenderLevelStageEvent.Stage.AFTER_PARTICLES && GhostRenderDispatcher.passStage() != stage) {
            return;
        }

        flush();
    }

    /**
     * 溜めた影を輪郭用のターゲットへ流す。ゴーストパスが、自分の行列が立っているうちに呼ぶ。
     *
     * <p>描画型は流し終えるとメインのターゲットを結び直す。Fabulous の AFTER_PARTICLES ではパーティクルの
     * ターゲットが、Iris の下ではパック自身のものが結ばれているので、見つけた物へ戻す。
     */
    static void endBatch(Minecraft minecraft) {
        restoringFramebuffer(minecraft, () -> minecraft.renderBuffers().outlineBufferSource().endOutlineBatch());
    }

    private static void flush() {
        Minecraft minecraft = Minecraft.getInstance();

        if (!pending) {
            return;
        }

        pending = false;

        PostChain effect = GhostOutlineAccess.effect(minecraft.levelRenderer);

        if (effect == null || !minecraft.levelRenderer.shouldShowEntityOutlines()) {
            return;
        }

        // バニラは処理の後にメインを結び直すが、ここでは endBatch と同じく見つけた物へ戻す。
        restoringFramebuffer(minecraft, () -> effect.process(pendingTicks));
    }

    private static void restoringFramebuffer(Minecraft minecraft, Runnable work) {
        int bound = GlStateManager._getInteger(GL30.GL_FRAMEBUFFER_BINDING);

        work.run();

        RenderTarget main = minecraft.getMainRenderTarget();

        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, bound);
        GlStateManager._viewport(0, 0, main.viewWidth, main.viewHeight);
    }

    /** {@code LevelRenderer.entityEffect} への窓。ミキシンが実装する。 */
    public interface GhostOutlineAccess {
        @Nullable
        PostChain ashvehicles$entityEffect();

        @Nullable
        static PostChain effect(Object levelRenderer) {
            return ((GhostOutlineAccess) levelRenderer).ashvehicles$entityEffect();
        }
    }
}
