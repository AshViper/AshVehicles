package com.ashvehicles.client.renderer;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.ghost.GhostRenderDispatcher;
import com.ashvehicles.client.model.WeaponModel;
import com.ashvehicles.entity.TargetDroneEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 標的ドローンを、進行方向へ向けバンクさせて描く。
 *
 * <p>{@link RocketRenderer} と同じ流儀で、体の回転ではなく速度から向きを取る。違いはバンクだけだ。
 * ミサイルはロールしないが、これは飛行機の形をして輪を回り続ける物で、翼を水平にしたまま曲がる姿は
 * 紙飛行機にも劣る。傾きはエンティティが進路の曲がりから計算しており、ここでは回すだけ。
 */
public class TargetDroneRenderer extends GeoEntityRenderer<TargetDroneEntity> {
    public TargetDroneRenderer(EntityRendererProvider.Context context) {
        super(context, new Model());
    }

    /**
     * ゴースト開始距離を超えたら降板し、ゴーストパスへ引き継ぐ。{@link RocketRenderer} と同じ1行で、
     * これが無いとドローンは LOD 階層に一度も入らないまま、描画上限距離で唐突に消える。
     */
    @Override
    public boolean shouldRender(TargetDroneEntity drone, Frustum frustum, double camX, double camY,
            double camZ) {
        if (GhostRenderDispatcher.claims(drone, camX, camY, camZ)) {
            return false;
        }

        return super.shouldRender(drone, frustum, camX, camY, camZ);
    }

    /**
     * 機首を飛行経路に、翼をバンク角に。基底実装は意図的に呼ばない——あちらは体の回転から自前の方位を
     * 適用するが、この向きの出所は速度だ。最後の半回転はモデル由来（ジオメトリは -Z を向いて作られる）。
     *
     * <p>ロールはピッチの後・半回転の前に掛ける。その時点の +Z が進行方向なので、そこで回せば
     * 「進路を軸にした傾き」になり、正の値が右翼下げになる。エンティティ側の符号と対になっている。
     */
    @Override
    protected void applyRotations(TargetDroneEntity animatable, PoseStack poseStack, float ageInTicks,
            float rotationYaw, float partialTick, float nativeScale) {
        Vec3 travel = animatable.travel(partialTick);

        if (travel.lengthSqr() > 1.0E-8) {
            double flat = Math.sqrt(travel.x * travel.x + travel.z * travel.z);
            poseStack.mulPose(Axis.YP.rotationDegrees((float) Math.toDegrees(Math.atan2(travel.x, travel.z))));
            poseStack.mulPose(Axis.XP.rotationDegrees((float) -Math.toDegrees(Math.atan2(travel.y, flat))));
        }

        poseStack.mulPose(Axis.ZP.rotationDegrees(animatable.roll(partialTick)));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
    }

    /**
     * 空の光で描く。輪の大半はロード済み chunk の外の上空で、そこの明かりを世界に訊くと0が返り、的が
     * 真昼の空を黒い切り絵で回ることになる。空にいる物に空の明るさを与えるのは、ほぼ常に正しい嘘。
     */
    @Override
    protected int getSkyLightLevel(TargetDroneEntity entity, BlockPos pos) {
        return 15;
    }

    /** 的の上に名札を浮かべる理由は無い。 */
    @Override
    public boolean shouldShowName(TargetDroneEntity animatable) {
        return false;
    }

    /**
     * 兵装と同じ場所（{@code geo/weapon/}・{@code textures/weapon/}）から名前で引く。専用ファイルが
     * 無ければ兵装の素のモデルへフォールバックし、テクスチャ欠落の市松にはならない。
     */
    private static class Model extends WeaponModel<TargetDroneEntity> {
        private static final ResourceLocation ID =
                ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "target_drone");

        @Override
        protected ResourceLocation weaponId(TargetDroneEntity animatable) {
            return ID;
        }
    }
}
