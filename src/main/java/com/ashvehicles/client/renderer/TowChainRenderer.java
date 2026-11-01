package com.ashvehicles.client.renderer;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.TowChain;
import com.ashvehicles.entity.VehicleEntityBase;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * 牽引の鎖を描く。リードと同じ描画型の帯を、縦と横の2枚。
 *
 * <p>端の点はサーバーが引っ張りに使うのと同じ {@link TowChain#anchor}。弛んでいる間は余った長さの分だけ垂れ、
 * 張れば真っ直ぐになる。鎖の輪に見えるよう、区間ごとに濃淡を交互にする。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class TowChainRenderer {
    private static final int SEGMENTS = 24;
    private static final float HALF_WIDTH = 0.06F;
    private static final double MIN_SAG = 0.15;

    private TowChainRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();

        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || minecraft.level == null) {
            return;
        }

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 eye = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();

        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (!(entity instanceof VehicleEntityBase puller)) {
                continue;
            }

            VehicleEntityBase towed = puller.getTowed();

            if (towed == null) {
                continue;
            }

            Vec3 a = TowChain.anchor(puller, towed.getPosition(partialTick), partialTick);
            Vec3 b = TowChain.anchor(towed, puller.getPosition(partialTick), partialTick);
            draw(event.getPoseStack(), buffers, a, b, eye, minecraft);
        }
    }

    private static void draw(PoseStack poseStack, MultiBufferSource.BufferSource buffers, Vec3 a, Vec3 b, Vec3 eye,
            Minecraft minecraft) {
        Matrix4f matrix = poseStack.last().pose();
        Vec3 span = b.subtract(a);
        double sag = Math.max(TowChain.LENGTH - span.length(), 0.0) * 0.5 + MIN_SAG;
        Vec3 flat = new Vec3(span.x, 0.0, span.z);
        // 横の帯の幅の向き。真上下に張った鎖では任意。
        Vec3 across = flat.lengthSqr() < 1.0E-6 ? new Vec3(1.0, 0.0, 0.0)
                : new Vec3(-flat.z, 0.0, flat.x).normalize();
        int light = LevelRenderer.getLightColor(minecraft.level, BlockPos.containing(a.add(b).scale(0.5)));

        // 帯は三角形の連なりなので1本ずつ描き切る。続けて描くと帯と帯の間に余計な三角形が張られる。
        strip(matrix, buffers.getBuffer(RenderType.leash()), a, span, sag, eye, new Vec3(0.0, HALF_WIDTH, 0.0), light);
        buffers.endBatch(RenderType.leash());
        strip(matrix, buffers.getBuffer(RenderType.leash()), a, span, sag, eye, across.scale(HALF_WIDTH), light);
        buffers.endBatch(RenderType.leash());
    }

    private static void strip(Matrix4f matrix, VertexConsumer buffer, Vec3 a, Vec3 span, double sag, Vec3 eye,
            Vec3 width, int light) {
        for (int i = 0; i <= SEGMENTS; i++) {
            double t = (double) i / SEGMENTS;
            Vec3 p = a.add(span.scale(t)).add(0.0, -sag * 4.0 * t * (1.0 - t), 0.0).subtract(eye);
            float shade = i % 2 == 0 ? 0.32F : 0.22F;

            vertex(matrix, buffer, p.add(width), shade, light);
            vertex(matrix, buffer, p.subtract(width), shade, light);
        }
    }

    private static void vertex(Matrix4f matrix, VertexConsumer buffer, Vec3 p, float shade, int light) {
        buffer.addVertex(matrix, (float) p.x, (float) p.y, (float) p.z)
                .setColor(shade, shade, shade * 1.05F, 1.0F)
                .setLight(light);
    }
}
