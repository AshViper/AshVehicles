package com.ashvehicles.client.renderer;

import com.ashvehicles.client.ghost.GhostRenderDispatcher;
import com.ashvehicles.entity.VehicleEntityBase;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.util.Color;

/**
 * MOD のあらゆる機体を描く。寝ている姿勢と描画サイズは機体自身から取る。
 *
 * <p>どちらも飛ぶ物でも走る物でも同じ仕事であり、どちらも放っておけば Minecraft が間違える点だ。Minecraft は
 * モデルを、生きたエンティティの体の回転から読んだ方位で回そうとする——これらはどちらもそうではない——し、モデルを
 * 作られたままのサイズで描こうとする。そのサイズが Minecraft のスケールであることは稀だ。
 */
public abstract class VehicleRenderer<T extends VehicleEntityBase & GeoEntity> extends GeoEntityRenderer<T> {
    /**
     * 全損した機体が自身の色をどれだけ保つか。
     *
     * <p>飛行場の反対側からでも「焼け落ちた」と読める程度には低く、マーキング・パネルライン・形状が残る程度には
     * 高い。残骸は元の機体だと分かるべきだ。撤去せず残しておく理由はそれが全てなのだから。
     *
     * <p>public なのは、ゴーストパスが同じ機体をこのレンダラー経由ではなくスナップショットから描くからだ。引き継ぎ
     * 距離で生き返る残骸は、そもそも焦げていない残骸より悪い。
     */
    public static final float CHARRED = 0.28F;

    protected VehicleRenderer(EntityRendererProvider.Context context, GeoModel<T> model) {
        super(context, model);
    }

    /**
     * 残骸を焦がして描く。残骸の見た目はこれが全てだ。
     *
     * <p>意図的にこれで全てにしている。同じジオメトリ、同じテクスチャ、墜落時の同じ姿勢に、焼けを掛ける——MOD の
     * 全機体・全戦車に破損版ジオメトリをもう1セット用意するのではなく。あちらは機体ごとに1モデルであり、他は完成
     * している機体のために誰かが描かねばならなくなる。
     */
    @Override
    public Color getRenderColor(T animatable, float partialTick, int packedLight) {
        Color colour = super.getRenderColor(animatable, partialTick, packedLight);

        if (!animatable.isWrecked()) {
            return colour;
        }

        // アルファには触れない。機体の透過度は距離についての問いであり、残骸のゴーストもゴーストだ。
        // AircraftRenderer 参照。
        return Color.ofRGBA(
                (int) (colour.getRed() * CHARRED),
                (int) (colour.getGreen() * CHARRED),
                (int) (colour.getBlue() * CHARRED),
                colour.getAlpha());
    }

    /**
     * ゴースト開始距離を超えたら降板し、ゴーストパスへ引き継ぐ。
     *
     * <p>判定はパス自身が同じカメラで行う物と同一なので、機体は常にどちらか一方の担当であり両方になることはない。
     * 各レンダラーではなくここに置いてあるのは、MOD が描く物すべてで同じ引き継ぎだからだ。2km 先の戦車がゲームの
     * 手の届く範囲を外れる理由は、機体の場合とまったく同じである。
     */
    @Override
    public boolean shouldRender(T machine, Frustum frustum, double camX, double camY, double camZ) {
        if (GhostRenderDispatcher.claims(machine, camX, camY, camZ)) {
            return false;
        }

        return super.shouldRender(machine, frustum, camX, camY, camZ);
    }

    /** 機体ファイルが指定する描画スケール。描く対象ができるまで分からない。 */
    protected abstract float scaleOf(T animatable);

    /**
     * モデルを機体の姿勢に合わせて回す。姿勢は回転なので、そこから角度を取り出す必要は無い。基底実装は意図的に
     * 呼ばない。あちらは自前の方位を適用するし、その方位を生きたエンティティの体の回転から読むからだ。
     *
     * <p>その後の半回転はモデル由来だ。ジオメトリは北——エンティティの −Z——を向いて作られるが、機体の回転は正面を
     * +Z 方向として記述される。
     */
    @Override
    protected void applyRotations(T animatable, PoseStack poseStack, float ageInTicks, float rotationYaw,
            float partialTick, float nativeScale) {
        poseStack.mulPose(animatable.getAttitude(partialTick));
        this.applyBodyMotion(animatable, poseStack, partialTick);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
    }

    /**
     * 姿勢が記述する車体に対して機体の車体部が行う動きを、車体座標系——+Z が車首方向、+Y が上、したがって +X は
     * 左——で適用する。
     *
     * <p>車体が固定されている機体では何もしない。機体構造はそれに当たるが、トーションバーに載った車体は違い、
     * 地上車両はここへサスペンションを入れる。姿勢とモデルの半回転の間に置いてあるのは、ワールドではなく機体に
     * 対して測るためだ。制動で車首を沈める戦車は、乗っている斜面に対してそうする。
     */
    protected void applyBodyMotion(T animatable, PoseStack poseStack, float partialTick) {
    }

    @Override
    public void preRender(PoseStack poseStack, T animatable, BakedGeoModel model, MultiBufferSource bufferSource,
            VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay,
            int colour) {
        float scale = this.scaleOf(animatable);
        this.scaleWidth = scale;
        this.scaleHeight = scale;
        this.shadowRadius = animatable.getBbWidth() * 0.5F;

        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick,
                packedLight, packedOverlay, colour);
    }

    /** 動く機体の上に浮かぶ名前タグは、役に立つより気が散る。 */
    @Override
    public boolean shouldShowName(T animatable) {
        return false;
    }
}
