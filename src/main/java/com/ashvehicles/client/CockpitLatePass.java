package com.ashvehicles.client;

import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.ghost.GhostRenderDispatcher;
import com.ashvehicles.entity.AircraftEntity;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Iris シェーダーパックの下で、MFD と HUD のガラス板を {@code AFTER_LEVEL} から描く。
 *
 * <p>通常は模型と一緒に描く（{@link CockpitDisplays#draw}）。パックはエンティティの段で渡した物を自分の
 * バッファと合成パスへ通すので、全光量のはずの画面がパックの照明と影で暗く潰れ、文字が読めず、HUD の半透明の
 * 板は消えていた。{@code AFTER_LEVEL} はフレームの組み立ての後で、そこで描いた物は完成した画像へ直接入り、
 * パックが残すシーン深度で模型に隠れる。
 *
 * <p>その段の行列と深度書き込みは信用できない。整え方はゴーストのパスと同じで、理由もそちらに書いてある
 * （{@link GhostRenderDispatcher} の「描画型が言わない2つの状態」）。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class CockpitLatePass {
    private CockpitLatePass() {
    }

    @SubscribeEvent
    static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || !GhostRenderDispatcher.shadersInUse()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null || !(minecraft.player.getVehicle() instanceof AircraftEntity aircraft)
                || !minecraft.options.getCameraType().isFirstPerson()) {
            return;
        }

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 eye = event.getCamera().getPosition();
        Vec3 at = aircraft.getPosition(partialTick);

        Matrix4f projection = RenderSystem.getProjectionMatrix();
        VertexSorting sorting = RenderSystem.getVertexSorting();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        boolean depthWrite = GlStateManager._getInteger(GL11.GL_DEPTH_WRITEMASK) != 0;

        RenderSystem.setProjectionMatrix(event.getProjectionMatrix(), VertexSorting.DISTANCE_TO_ORIGIN);
        modelView.pushMatrix();
        modelView.identity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.depthMask(depthWrite);
        RenderSystem.depthMask(true);

        PoseStack poseStack = new PoseStack();
        poseStack.mulPose(event.getModelViewMatrix());
        poseStack.translate(at.x - eye.x, at.y - eye.y, at.z - eye.z);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();

        CockpitDisplays.late = true;

        try {
            CockpitDisplays.draw(aircraft, partialTick, poseStack, buffers);
            CockpitDisplays.drawHud(aircraft, partialTick, poseStack, buffers);
            buffers.endBatch();
        } finally {
            CockpitDisplays.late = false;

            if (!depthWrite) {
                RenderSystem.depthMask(false);
            }

            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection, sorting);
        }
    }
}
