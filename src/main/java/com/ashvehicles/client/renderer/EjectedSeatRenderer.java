package com.ashvehicles.client.renderer;

import com.ashvehicles.entity.EjectedSeatEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * 射出座席を箱3つ（座面・背もたれ・頭当て）で描く。模型ファイルを持たないのは、数秒で視界から落ちていく
 * 物だから。燃え尽きた後はゆっくり転がる。
 */
public class EjectedSeatRenderer extends EntityRenderer<EjectedSeatEntity> {
    /** 無地の白。頂点色で塗る。 */
    static final ResourceLocation WHITE = ResourceLocation.withDefaultNamespace("textures/block/white_concrete.png");

    private static final int FRAME = 0xFF3A3D40;
    private static final int CUSHION = 0xFF5B5130;
    private static final int STRIPE = 0xFFE0C020;

    public EjectedSeatRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(EjectedSeatEntity seat, float yaw, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource, int packedLight) {
        VertexConsumer buffer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(WHITE));
        int light = LightTexture.FULL_SKY;
        float tumble = seat.burning() ? 0.0F : (seat.tickCount + partialTick) * 9.0F;

        poseStack.pushPose();
        poseStack.translate(0.0, 0.6, 0.0);
        poseStack.mulPose(Axis.YP.rotationDegrees(-Mth.lerp(partialTick, seat.yRotO, seat.getYRot())));
        poseStack.mulPose(Axis.XP.rotationDegrees(tumble));
        poseStack.mulPose(Axis.ZP.rotationDegrees(tumble * 0.37F));
        poseStack.translate(0.0, -0.6, 0.0);

        // 座面、背もたれ、頭当て、黄黒の射出ハンドル
        Boxes.box(poseStack, buffer, light, -0.35F, 0.0F, -0.35F, 0.35F, 0.25F, 0.35F, FRAME);
        Boxes.box(poseStack, buffer, light, -0.3F, 0.25F, -0.3F, 0.3F, 0.35F, 0.3F, CUSHION);
        Boxes.box(poseStack, buffer, light, -0.35F, 0.25F, 0.3F, 0.35F, 1.1F, 0.45F, FRAME);
        Boxes.box(poseStack, buffer, light, -0.25F, 1.1F, 0.28F, 0.25F, 1.35F, 0.45F, CUSHION);
        Boxes.box(poseStack, buffer, light, -0.12F, 0.15F, -0.42F, 0.12F, 0.22F, -0.35F, STRIPE);

        poseStack.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(EjectedSeatEntity seat) {
        return WHITE;
    }

    /** 塗りつぶしの直方体。 */
    static final class Boxes {
        private Boxes() {
        }

        static void box(PoseStack poseStack, VertexConsumer buffer, int light, float x0, float y0, float z0,
                float x1, float y1, float z1, int colour) {
            PoseStack.Pose pose = poseStack.last();

            quad(pose, buffer, light, colour, 0, -1, 0, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
            quad(pose, buffer, light, colour, 0, 1, 0, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0);
            quad(pose, buffer, light, colour, 0, 0, -1, x0, y1, z0, x1, y1, z0, x1, y0, z0, x0, y0, z0);
            quad(pose, buffer, light, colour, 0, 0, 1, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
            quad(pose, buffer, light, colour, -1, 0, 0, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
            quad(pose, buffer, light, colour, 1, 0, 0, x1, y1, z0, x1, y1, z1, x1, y0, z1, x1, y0, z0);
        }

        static void quad(PoseStack.Pose pose, VertexConsumer buffer, int light, int colour, float nx, float ny,
                float nz, float ax, float ay, float az, float bx, float by, float bz, float cx, float cy,
                float cz, float dx, float dy, float dz) {
            vertex(pose, buffer, light, colour, nx, ny, nz, ax, ay, az, 0.0F, 0.0F);
            vertex(pose, buffer, light, colour, nx, ny, nz, bx, by, bz, 1.0F, 0.0F);
            vertex(pose, buffer, light, colour, nx, ny, nz, cx, cy, cz, 1.0F, 1.0F);
            vertex(pose, buffer, light, colour, nx, ny, nz, dx, dy, dz, 0.0F, 1.0F);
        }

        static void vertex(PoseStack.Pose pose, VertexConsumer buffer, int light, int colour, float nx, float ny,
                float nz, float x, float y, float z, float u, float v) {
            buffer.addVertex(pose, x, y, z)
                    .setColor(colour)
                    .setUv(u, v)
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(light)
                    .setNormal(pose, nx, ny, nz);
        }
    }
}
