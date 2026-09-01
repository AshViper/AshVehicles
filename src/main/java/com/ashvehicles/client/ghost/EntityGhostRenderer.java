package com.ashvehicles.client.ghost;

import javax.annotation.Nullable;

import com.ashvehicles.client.ghost.geo.GhostAnimatable;
import com.ashvehicles.client.ghost.geo.GhostGeoRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

/**
 * アダプタが頼れる描画処理。スナップショットからのモデル、スナップショットからの平坦アイコン、そしてその前段
 * となる姿勢設定。
 *
 * <p>アダプタは好きに描いてよい——機体アダプタは機体の完全な姿勢でモデルを回し、兵装を吊る——が、ゴーストに
 * 必要な物の大半は対象が何であれ同じであり、それがここにある。ここでエンティティを見る処理は無い。
 */
public final class EntityGhostRenderer {
    private EntityGhostRenderer() {
    }

    /**
     * pose stack をスナップショットの姿勢へ回す。スナップショットが完全な姿勢を持つならそれを、無ければゲーム
     * 流儀の方位とピッチを使う——ゲーム自身のエンティティレンダラーが適用するのと同じ回転だ。
     */
    public static void orient(PoseStack poseStack, GhostSnapshot snapshot) {
        if (snapshot.attitude() != null) {
            poseStack.mulPose(snapshot.attitude());
            return;
        }

        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - snapshot.bodyYaw()));
        poseStack.mulPose(Axis.XP.rotationDegrees(snapshot.pitch()));
    }

    /**
     * スナップショットの GeckoLib モデルを、pose stack の現在の原点と姿勢で描く。
     *
     * @param poser ボーンのポーズ付け方法。作成時のポーズのままなら {@code null}
     */
    public static void drawModel(EntityGhost ghost, GhostSnapshot snapshot, GhostRenderContext context,
            @Nullable GhostAnimatable.GhostPoser poser) {
        GhostGeoRenderer.draw(ghost, snapshot, context, poser);
    }

    /**
     * スナップショットのビルボードを描く。エンティティと同じ大きさの、カメラを向いた平坦なアイコンを、
     * エンティティの中心に置く。有効時の最遠階層。
     *
     * @return 何か描かれたか。スナップショットにビルボードテクスチャが無ければ false
     */
    public static boolean drawBillboard(GhostSnapshot snapshot, GhostRenderContext context) {
        ResourceLocation texture = snapshot.billboard();

        if (texture == null) {
            return false;
        }

        AABB bounds = snapshot.bounds();
        float size = (float) Math.max(bounds.getXsize(), Math.max(bounds.getYsize(), bounds.getZsize()));
        float half = size * 0.5F;
        PoseStack poseStack = context.poseStack();
        // モデルと同じ理由で自発光の透過型は使わない——あれは深度を書かないので、重なったビルボード同士が
        // 前後関係を失って互いに透ける（GhostGeoRenderer#renderType 参照）。ただしこちらは裏表を捨てる型でもない。
        // カメラを向く板1枚に裏面は無く、向きは頂点の並び順次第なので、捨てさせれば消えかねない方に賭けるだけだ。
        RenderType type = RenderType.entityTranslucent(texture);
        VertexConsumer buffer = context.buffers().getBuffer(type);
        // モデルと同じ式で。DH の霧の濃さの分だけ薄れる。
        float opacity = (context.ghostStyle() ? GhostGeoRenderer.GHOST_ALPHA : 1.0F)
                * (1.0F - context.fog());
        int alpha = (int) (255.0F * Mth.clamp(opacity, 0.0F, 1.0F));
        // 残骸の代役アイコンもモデルと同様に暗くする。最遠階層が黙って塗り直してしまわないように。
        int level = (int) (255.0F * Mth.clamp(snapshot.shade(), 0.0F, 1.0F));
        int light = context.packedLight();

        poseStack.pushPose();
        poseStack.translate(0.0, (bounds.minY + bounds.maxY) * 0.5, 0.0);
        // パーティクルとまったく同じようにカメラを向く。
        poseStack.mulPose(context.camera().rotation());
        PoseStack.Pose pose = poseStack.last();

        vertex(buffer, pose, -half, -half, 0.0F, 1.0F, level, alpha, light);
        vertex(buffer, pose, half, -half, 1.0F, 1.0F, level, alpha, light);
        vertex(buffer, pose, half, half, 1.0F, 0.0F, level, alpha, light);
        vertex(buffer, pose, -half, half, 0.0F, 0.0F, level, alpha, light);
        poseStack.popPose();

        return true;
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float u, float v,
            int level, int alpha, int light) {
        buffer.addVertex(pose, x, y, 0.0F)
                .setColor(level, level, level, alpha)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, 0.0F, 0.0F, 1.0F);
    }
}
