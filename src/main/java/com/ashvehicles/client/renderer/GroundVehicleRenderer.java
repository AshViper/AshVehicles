package com.ashvehicles.client.renderer;

import javax.annotation.Nullable;

import com.ashvehicles.client.model.GroundVehicleModel;
import com.ashvehicles.client.model.TrackBelt;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.vehicle.Ride;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;

/** あらゆる地上車両を描く。描き方に関する全ては {@link VehicleRenderer} の管轄。 */
public class GroundVehicleRenderer extends VehicleRenderer<GroundVehicleEntity> {
    /**
     * 描画中のモデル。描画開始時から保持する。履帯敷設が転輪をここから引く必要がある一方、ボーンループには渡されて
     * いないからだ。
     */
    @Nullable
    private BakedGeoModel drawing;

    public GroundVehicleRenderer(EntityRendererProvider.Context context) {
        super(context, new GroundVehicleModel());
    }

    @Override
    protected float scaleOf(GroundVehicleEntity animatable) {
        return animatable.getStats().model().scale();
    }

    /**
     * バネの上で車体を揺らす。
     *
     * <p>車体ボーンではなくモデル全体へ適用する。この種のモデルには適用先の車体ボーンが無いからだ。車体・砲塔・
     * 車載品・走行装置が全て1つのルートにぶら下がっているので、「全部ではない何か」を動かす手立てが無い。その代償
     * として車輪と履帯も一緒に運ばれるので、後から1つずつ地面へ戻す——{@code GroundVehicleModel.plant} と
     * {@link TrackBelt#draw} 参照。
     *
     * <p>動くのは絵だけだ。当たり判定・砲の照準・車両の接地位置はいずれも剛体車体から求められ、これを見ることは
     * ない。{@link Ride} 参照。
     */
    @Override
    protected void applyBodyMotion(GroundVehicleEntity animatable, PoseStack poseStack, float partialTick) {
        Ride ride = animatable.getRide(partialTick);

        if (ride.isLevel()) {
            return;
        }

        // X 軸周りの回転は車首を下げるので、持ち上げるには負値が要る。Z 軸周りの回転は右側を下げるが、それは
        // 車体自身のロールが書かれている符号と同じなので、車体と地面が同じ向きで読める。この座標系の主である
        // Attitude 参照。
        poseStack.translate(0.0F, ride.heave(), 0.0F);
        poseStack.mulPose(Axis.XP.rotationDegrees(-ride.pitch()));
        poseStack.mulPose(Axis.ZP.rotationDegrees(ride.lean()));
    }

    @Override
    public void preRender(PoseStack poseStack, GroundVehicleEntity animatable, BakedGeoModel model,
            MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender, float partialTick,
            int packedLight, int packedOverlay, int colour) {
        this.drawing = model;

        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick,
                packedLight, packedOverlay, colour);
    }

    /**
     * モデルが構成元のリンク1つを持つ場所に履帯を1周分すべて描き、他はそのまま描く。
     *
     * <p>モデル描画後ではなくここで捕まえるのは、ここでは pose stack が既にリンク自身の親の座標系——履帯の周回を
     * 算出する座標系——にあるからだ。そして「リンクをどこかへ置いて描く」ことは GeckoLib がどのボーンに対しても行う
     * ことそのものだからでもある。おかげで各リンクは車両の他の部分と同じ経路・同じ照明・同じ色・同じレイヤーで
     * 描かれる。残骸の履帯は車体と一緒に焦げるし、これらの立方体が100回描かれたことを誰も知る必要が無い。
     */
    @Override
    public void renderRecursively(PoseStack poseStack, GroundVehicleEntity animatable, GeoBone bone,
            @Nullable RenderType renderType, MultiBufferSource bufferSource, @Nullable VertexConsumer buffer,
            boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
        BakedGeoModel model = this.drawing;

        if (model != null && TrackBelt.isLink(animatable.getStats().model(), bone)
                && TrackBelt.draw(model, animatable.getStats().model(), bone,
                        animatable.getWheelAngle(partialTick), animatable.getRide(partialTick),
                        animatable.getStats().suspension().travel(),
                        link -> super.renderRecursively(poseStack, animatable, link, renderType, bufferSource,
                                buffer, isReRender, partialTick, packedLight, packedOverlay, colour))) {
            return;
        }

        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender,
                partialTick, packedLight, packedOverlay, colour);
    }
}
