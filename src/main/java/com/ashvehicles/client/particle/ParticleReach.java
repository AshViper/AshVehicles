package com.ashvehicles.client.particle;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.ghost.GhostRenderDispatcher;
import com.ashvehicles.client.ghost.dh.DHFog;
import com.ashvehicles.client.ghost.dh.DHIntegration;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * この MOD のパーティクルが、ロード済みの世界より遠くでも描かれるための取り決め。ゴーストがそこに立てる理由と
 * 同じ3つで、同じ計算を使う。
 *
 * <p><b>なぜ要るのか。</b>パーティクル自体は既にどんな距離にでも生まれる（{@code TintedParticleType} が
 * limiter を上書きしている）し、下にチャンクが無くても正しく光る（{@link WeaponParticle} の
 * {@code getLightColor}）。それでも遠くの機体は煙を引かなかった。残っていたのは描画側の3つで、どれも
 * ゴーストパスが既に解いた物だ。
 *
 * <ul>
 *   <li><b>視錐台が遠方面で捨てる。</b>{@code ParticleEngine.render} は粒ごとに
 *       {@code frustum.isVisible(getRenderBoundingBox(...))} を問う。視錐台は投影と同じ遠方面
 *       （描画距離×64ブロック）を持つので、それより遠い粒は1頂点も書かれずに落ちる。</li>
 *   <li><b>遠方面が頂点を切る。</b>捨てられなかったとしても、投影が同じ場所でクリップする。</li>
 *   <li><b>霧が塗り潰す。</b>粒のシェーダーは {@code FogStart}/{@code FogEnd} で色を霧へ寄せる。帯の外は
 *       完全に霧の色なので、描かれてはいるが見えない。</li>
 * </ul>
 *
 * <p><b>やり方はゴーストと同じ。</b>遠方面より遠い粒は、視点からの線に沿って手前へ滑らせ、動かしたのと
 * ちょうど同じだけ縮める。同じ画素を覆うので見た目は変わらず、遠方面には決して届かない。写像は
 * {@link GhostRenderDispatcher#pull} そのもの——<b>共有していることが要点だ</b>。機体を引き寄せる比率と
 * 排気を引き寄せる比率が違えば、煙が機体から離れて漂う。
 *
 * <p>そして霧。引き寄せた粒に自動霧を掛けると、シェーダーは<em>引き寄せ後</em>の頂点距離で濃さを決めるので、
 * 3km 先の煙が 900m の霧で描かれる。だから帯はバッチの間だけ押し広げ、本当の距離での濃さ——DH の遠方霧と、
 * ゲームが今フレーム立てた霧の帯の両方——を自分で求めてアルファへ畳み込む。ゴーストパスの第2フェーズと同じ
 * 合成則で、同じ理由による。背景は既に霧の色をしているので、透けさせることが霧に混ざることと同じ画になる。
 *
 * <p><b>近い粒には何も起きない。</b>遠方面の {@code 0.85} 倍より近ければ引き寄せ率は1で、頂点は以前と1ビット
 * も変わらない。霧の濃さもそこでは0だ。変わるのは、そもそも今まで見えていなかった距離の粒だけである。
 *
 * <h2>帯を戻す場所</h2>
 *
 * <p>{@code ParticleRenderType} に終了フックは無いので、押し広げた帯は外から戻す。
 * {@code RenderLevelStageEvent.Stage.AFTER_PARTICLES} は {@code ParticleEngine.render} の直後に必ず
 * ディスパッチされる——{@code LevelRenderer} の2つの経路（描画優先とそれ以外）のどちらでもそうだ——ので、
 * そこで最優先に戻す。<b>ゴーストパスより先でなければならない。</b>あちらは自分の霧の濃さを求めるために
 * まさにこの帯を読むし、既定の優先度で同じステージに来る。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class ParticleReach {
    /** 今バッチが霧の帯を押し広げているか。 */
    private static boolean pushed;
    /** 押し広げる前の帯。戻す先であり、濃さを自分で求めるときの基準でもある。 */
    private static float fogStart;
    private static float fogEnd;
    /** そのフレームの DH の遠方霧。粒ごとに設定を読み直す理由は無い。 */
    @Nullable
    private static DHFog distantFog;

    private ParticleReach() {
    }

    /**
     * バッチの先頭。{@link WeaponParticle} の描画型の {@code begin} から呼ぶ。
     *
     * <p>控えるのは、読めた値が自分の置いた物でないときだけ。{@link #close} が呼ばれ損ねたまま次のフレームへ
     * 入っても、レベルは毎フレーム霧を立て直す（{@code FogRenderer.setupFog}）ので、ここで読める値は本物へ
     * 戻っている——さもないと一度取りこぼしただけで、帯が二度と戻らなくなる。
     */
    public static void open() {
        float start = RenderSystem.getShaderFogStart();

        if (start != Float.MAX_VALUE) {
            fogStart = start;
            fogEnd = RenderSystem.getShaderFogEnd();
        }

        pushed = true;
        distantFog = DHIntegration.fog();

        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
    }

    /** 帯を見つけた状態へ戻す。押し広げていなければ何もしない。 */
    public static void close() {
        if (!pushed) {
            return;
        }

        pushed = false;
        distantFog = null;

        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            close();
        }
    }

    /** 視点。粒は自分の位置しか知らないので、距離はここから測る。 */
    public static Vec3 eye() {
        return Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
    }

    /**
     * この距離にある物の拡大率かつ移動係数。1なら手を触れない。ゴーストが使うのと同じ写像・同じ遠方面。
     *
     * @param away 視点からの本当の距離（ブロック）
     */
    public static double pull(double away) {
        return GhostRenderDispatcher.pull(away, Minecraft.getInstance().gameRenderer.getDepthFar());
    }

    /**
     * この位置の霧の濃さ。0が素通し、1が霧の色そのもの。
     *
     * <p>2つの霧源のどちらか濃い方ではなく、両方が同時に晴れて初めて素通しになる。DH の霧は水平距離で測る
     * （円筒形）、ゲーム自身の霧は3D距離で測る。どちらも<em>本当の</em>距離で測る——引き寄せ後の距離で測れば
     * 遠い物ほど霧が薄くなってしまう。
     *
     * @param dx 視点から見た位置のX成分
     * @param dz 同Z成分
     * @param away 視点からの本当の距離
     */
    public static float fog(double dx, double dz, double away) {
        DHFog curve = distantFog;
        float distant = curve == null ? 0.0F : curve.thickness(dx, dz);
        // 押し広げる前に控えた帯。押し広げた後の値を読めば、答えは常に0になる。
        float start = pushed ? fogStart : RenderSystem.getShaderFogStart();
        float end = pushed ? fogEnd : RenderSystem.getShaderFogEnd();
        float near = GhostRenderDispatcher.vanillaFogThickness(away, start, end);

        return 1.0F - (1.0F - distant) * (1.0F - near);
    }
}
