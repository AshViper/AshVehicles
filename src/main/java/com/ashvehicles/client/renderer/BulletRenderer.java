package com.ashvehicles.client.renderer;

import com.ashvehicles.client.ghost.GhostRenderDispatcher;
import com.ashvehicles.entity.BulletEntity;
import com.ashvehicles.weapon.WeaponDefinition;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * 弾を曳光として描く。進行方向に沿った短い筋を、兵器ファイルが指定する色で。
 *
 * <p>弾は差し渡し数センチで毎秒100ブロック進むので、物体として描いても得る物は何も無い。砲手が実際に見るのは筋
 * であり、弾がどこへ向かっているかを示すのも筋だ。だからそれを描く。長さは1tick分の移動距離で、それがフレーム間に
 * どれだけ動くかの真実だ。
 */
public class BulletRenderer extends EntityRenderer<BulletEntity> {
    public BulletRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    /**
     * ゴースト開始距離を超えたら降板し、ゴーストパスが同じ筋をスナップショットから描く。判定はパスが同じカメラで
     * 行う物と同一なので、弾は常にどちらか一方の担当であり両方になることはない。
     */
    @Override
    public boolean shouldRender(BulletEntity bullet, Frustum frustum, double camX, double camY, double camZ) {
        if (GhostRenderDispatcher.claims(bullet, camX, camY, camZ)) {
            return false;
        }

        return super.shouldRender(bullet, frustum, camX, camY, camZ);
    }

    /**
     * 曳光は照らされない——描画タイプが光を完全に無視する——ので、ここですることは描くこと以外に無い。未ロードの
     * 地面の上でも見えること、放っておけば曳光の流れを飲み込む霧から外れることは、ゴーストパスの管轄だ。あちらは
     * 引き継ぎを過ぎた弾に対して {@code BulletGhostAdapter} から同じ筋を描く。
     */
    @Override
    public void render(BulletEntity bullet, float yaw, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource, int packedLight) {
        this.drawTracer(bullet, partialTick, poseStack, bufferSource);
    }

    private void drawTracer(BulletEntity bullet, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource) {
        WeaponDefinition.Projectile round = bullet.getWeapon().projectile();
        // 次のステップではなく描画中のステップを使う。筋が、このフレームで弾が実際に通っている線に沿うように
        // するためだ。
        Vec3 travel = bullet.travel(partialTick);
        Camera camera = this.entityRenderDispatcher.camera;

        if (travel.lengthSqr() < 1.0E-6 || camera == null) {
            return;
        }

        // 弾がどこから見られているか。筋はその方向を向く。tick位置ではなく補間後の位置を使う。pose stack が既に
        // 置かれている点がそれであり、他所から取った方向ではクアッドが弾のいない方を向いてしまう。
        Vec3 fromCamera = bullet.getPosition(partialTick).subtract(camera.getPosition());

        // ゴーストパスが描くのと同じコードで、同じカメラ距離を基準に描く。引き継ぎを跨いでも弾が何も変わらない
        // ようにするためだ。Tracer 参照。
        Tracer.streak(poseStack, bufferSource.getBuffer(RenderType.lightning()), camera, fromCamera, travel,
                this.entityRenderDispatcher.distanceToSqr(bullet), 0xFF000000 | round.tracer());
    }

    @Override
    public ResourceLocation getTextureLocation(BulletEntity bullet) {
        return net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS;
    }
}
