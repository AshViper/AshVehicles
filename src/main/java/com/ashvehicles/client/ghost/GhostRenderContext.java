package com.ashvehicles.client.ghost;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;

/**
 * ゴースト描画を依頼されたアダプタへ渡される情報。加えて、ゲーム自身のレンダラーがゴーストパスについて知る
 * 必要のある唯一の情報——今その内側にいるかどうか。
 *
 * <p>static 部分は、エンティティループとゴーストパスの両方に仕える レンダラーのためにある。
 * {@code AircraftRenderer} はどちらでも機体を描くが、ゴーストは半透明で何にも照らされないので、どちらを描いて
 * いるか教える必要がある。ディスパッチャが描画ごとに設定し、読むのはレンダースレッドのみ。
 */
public final class GhostRenderContext {
    private static boolean drawingGhost;
    private static boolean translucent;
    private static float fogThickness;

    private final PoseStack poseStack;
    private final MultiBufferSource buffers;
    private final Camera camera;
    private final Vec3 fromCamera;
    private final float partialTick;
    private final int packedLight;
    private final boolean ghostStyle;
    private final float fog;
    private final double distanceSq;

    GhostRenderContext(PoseStack poseStack, MultiBufferSource buffers, Camera camera, Vec3 fromCamera,
            float partialTick, int packedLight, boolean ghostStyle, float fog, double distanceSq) {
        this.poseStack = poseStack;
        this.buffers = buffers;
        this.camera = camera;
        this.fromCamera = fromCamera;
        this.partialTick = partialTick;
        this.packedLight = packedLight;
        this.ghostStyle = ghostStyle;
        this.fog = fog;
        this.distanceSq = distanceSq;
    }

    /** ゴーストの原点、ワールド軸、遠方面への引き寄せ適用済み。 */
    public PoseStack poseStack() {
        return this.poseStack;
    }

    public MultiBufferSource buffers() {
        return this.buffers;
    }

    public Camera camera() {
        return this.camera;
    }

    /**
     * 視点から見たゴースト。実際の位置からカメラ位置を引いた値。
     *
     * <p>遠方面への引き寄せ後ではなく前の値。有用なのはそちらであり、どちらでも方向は同じだ——引き寄せられた
     * ゴーストはまさにこの線に沿って滑るので、変わるのは長さだけ。必要とするのは視聴者の方へ向ける必要がある物
     * 全て。{@code Tracer.streak} 参照。
     */
    public Vec3 fromCamera() {
        return this.fromCamera;
    }

    public float partialTick() {
        return this.partialTick;
    }

    /** 常に最大輝度。あの距離では世界は「不明な光」ではなく「光なし」を報告するからだ。 */
    public int packedLight() {
        return this.packedLight;
    }

    /**
     * ゴーストを実体としてではなくゴーストとして——半透明の、空を背にした点として——描くべきか。背後に描画済み
     * 地形が無いとき true。
     */
    public boolean ghostStyle() {
        return this.ghostStyle;
    }

    /**
     * このゴーストの位置での Distant Horizons の霧の濃さ。0が素通し、1が霧に沈み切った状態。DH が霧を
     * 描いていなければ常に0。アダプタはこの分だけ描く物を透明へ寄せる——背景は既に霧の色をしているので、
     * それが「霧に混ざる」ことと同じ画になる。{@link com.ashvehicles.client.ghost.dh.DHFog} 参照。
     */
    public float fog() {
        return this.fog;
    }

    public double distanceSq() {
        return this.distanceSq;
    }

    // ------------------------------------------------------------------
    // ゲーム自身のレンダラー向け
    // ------------------------------------------------------------------

    /** 今ゴーストパスが描画中か。レンダースレッド限定。 */
    public static boolean isDrawingGhost() {
        return drawingGhost;
    }

    /** ゴーストパスが描いている物を半透明にすべきか。レンダースレッド限定。 */
    public static boolean isTranslucent() {
        return drawingGhost && translucent;
    }

    /**
     * 今描いている物の位置の DH の霧の濃さ。ゴーストパスの外では常に0。レンダースレッド限定。
     *
     * <p>static で持つのは {@code isTranslucent()} と同じ理由——GeckoLib の {@code getRenderColor} には
     * コンテキストを渡す隙間が無い。
     */
    public static float fogFactor() {
        return drawingGhost ? fogThickness : 0.0F;
    }

    static void enter(boolean translucentStyle, float fog) {
        drawingGhost = true;
        translucent = translucentStyle;
        fogThickness = fog;
    }

    static void exit() {
        drawingGhost = false;
        translucent = false;
        fogThickness = 0.0F;
    }
}
