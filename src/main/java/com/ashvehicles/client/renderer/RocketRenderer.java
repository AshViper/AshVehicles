package com.ashvehicles.client.renderer;

import com.ashvehicles.client.ghost.GhostRenderDispatcher;
import com.ashvehicles.client.model.WeaponModel;
import com.ashvehicles.entity.RocketEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * ロケットやミサイルを、兵器のジオメトリファイルから進行方向に沿って寝かせて描く。
 *
 * <p>機関砲弾と違い、これらは筋ではなく物体だ。眺められる程度に遅く、見える程度に大きく、どちらを向いているかを
 * 知る価値がある。ミサイルが旋回を成功させるかどうかの全てがそこにあるからだ。
 *
 * <p>だからこそ、ロード範囲の外へ出た後も描き続ける必要がある。ミサイルは定義上遠くの物を狙うし、飛翔の面白い部分
 * はその外で起きる。このレンダラーが受け持つのは近距離だけ——{@code ghostStartDistance} までで降板し、以降は
 * ゴーストパスで {@code RocketGhostAdapter} がスナップショットから描く。両者は同じファイルの同じモデルを同じ向き
 * で描くので、引き継ぎを跨いでもミサイルは何も変わらない。
 */
public class RocketRenderer extends GeoEntityRenderer<RocketEntity> {
    public RocketRenderer(EntityRendererProvider.Context context) {
        super(context, new Model());
    }

    /**
     * ゴースト開始距離を超えたら降板し、ゴーストパスへ引き継ぐ。判定はパスが同じカメラで行う物と同一なので、
     * ミサイルは常にどちらか一方の担当であり両方になることはない。
     */
    @Override
    public boolean shouldRender(RocketEntity rocket, Frustum frustum, double camX, double camY, double camZ) {
        if (GhostRenderDispatcher.claims(rocket, camX, camY, camZ)) {
            return false;
        }

        return super.shouldRender(rocket, frustum, camX, camY, camZ);
    }

    /**
     * モデルを飛行経路に沿わせて回す。基底実装は意図的に呼ばない。あちらは生きたエンティティの体の回転から取った
     * 自前の方位を適用するが、ミサイルはそれではない。
     *
     * <p>その後の半回転はモデル由来だ。ジオメトリは北——つまり -Z——を向いて作られるが、ここで求める方位は飛行経路
     * 方向を +Z とする。
     */
    @Override
    protected void applyRotations(RocketEntity animatable, PoseStack poseStack, float ageInTicks,
            float rotationYaw, float partialTick, float nativeScale) {
        Vec3 travel = animatable.travel(partialTick);

        if (travel.lengthSqr() > 1.0E-8) {
            double flat = Math.sqrt(travel.x * travel.x + travel.z * travel.z);
            poseStack.mulPose(Axis.YP.rotationDegrees((float) Math.toDegrees(Math.atan2(travel.x, travel.z))));
            poseStack.mulPose(Axis.XP.rotationDegrees((float) -Math.toDegrees(Math.atan2(travel.y, flat))));
        }

        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
    }

    /** ミサイルの上に浮かぶ名前タグを描くのは妙な話だ。 */
    @Override
    public boolean shouldShowName(RocketEntity animatable) {
        return false;
    }

    /** 兵装モデル。どの兵装を描くかはミサイル自身が伝える。 */
    private static class Model extends WeaponModel<RocketEntity> {
        @Override
        protected ResourceLocation weaponId(RocketEntity animatable) {
            return animatable.getWeaponId();
        }
    }
}
