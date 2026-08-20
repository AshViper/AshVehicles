package com.ashvehicles.client.ghost;

import java.util.List;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.ghost.dh.DHIntegration;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;

/**
 * Development aids: a few lines on the F3 screen, and a red box round every ghost, each behind
 * its own setting in {@link GhostConfig}.
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class GhostDebug {
    /** How often the overlay's figures also go to the log, in ticks, while the overlay is on. */
    private static final int LOG_INTERVAL = 200;
    /** At most this many ghosts are listed one by one in the log each time. */
    private static final int LOG_DETAIL_LIMIT = 8;

    private GhostDebug() {
    }

    /** The same figures to the log, now and then, for reading a session back afterwards. */
    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();

        if (!GhostConfig.debugOverlay() || minecraft.level == null
                || minecraft.level.getGameTime() % LOG_INTERVAL != 0) {
            return;
        }

        AshVehicles.LOGGER.info("[ghosts] tracked={} drawn={} culled={} GHOST={} SIMPLIFIED={} BILLBOARD={} "
                + "occluded={} orphaned={} dhDrawn={} dh={} ({}) dhRadius={}",
                EntityGhostManager.size(), GhostRenderDispatcher.drawnLastFrame(),
                GhostRenderDispatcher.culledLastFrame(),
                EntityGhostManager.countGhost(), EntityGhostManager.countSimplified(),
                EntityGhostManager.countBillboard(), EntityGhostManager.countOccluded(),
                EntityGhostManager.countOrphaned(), EntityGhostManager.countDhDrawn(),
                DHIntegration.status(), DHIntegration.detail(minecraft.level), DHIntegration.drawnRadius());

        int listed = 0;

        for (EntityGhost ghost : EntityGhostManager.ghosts()) {
            if (listed++ >= LOG_DETAIL_LIMIT) {
                break;
            }

            AshVehicles.LOGGER.info("[ghost] {} {} at {} distance={} lod={} occluded={} dhDrawn={} orphaned={} "
                    + "drawn={} inWorld={} light=sky{}/block{}",
                    ghost.uuid().toString().substring(0, 8), ghost.current().type().toShortString(),
                    ghost.current().position(), (int) Math.sqrt(ghost.distanceSq()), ghost.lod(),
                    ghost.isOccluded(), ghost.isDhDrawn(), ghost.isOrphaned(), ghost.wasDrawnLastFrame(),
                    ghost.wasInWorld(), LightTexture.sky(ghost.lastLight()), LightTexture.block(ghost.lastLight()));
        }
    }

    @SubscribeEvent
    static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        if (!GhostConfig.debugOverlay() || Minecraft.getInstance().level == null) {
            return;
        }

        List<String> left = event.getLeft();
        left.add("");
        left.add(String.format("[AshVehicles ghosts] %d tracked, %d drawn, %d culled",
                EntityGhostManager.size(), GhostRenderDispatcher.drawnLastFrame(),
                GhostRenderDispatcher.culledLastFrame()));
        left.add(String.format("GHOST: %d  SIMPLIFIED: %d  BILLBOARD: %d  occluded: %d  orphaned: %d",
                EntityGhostManager.countGhost(), EntityGhostManager.countSimplified(),
                EntityGhostManager.countBillboard(), EntityGhostManager.countOccluded(),
                EntityGhostManager.countOrphaned()));
        left.add(String.format("Distances: %.0f / %.0f / %.0f  ghost style beyond %.0f",
                Math.sqrt(GhostConfig.startSq()), Math.sqrt(GhostConfig.simplifiedSq()),
                Math.sqrt(GhostConfig.endSq()), GhostRenderDispatcher.ghostStyleRadius()));
        left.add(String.format("DH Integration: %s (%s)  drawn radius: %.0f  DH-drawn ghosts: %d",
                DHIntegration.status(), DHIntegration.detail(Minecraft.getInstance().level),
                DHIntegration.drawnRadius(), EntityGhostManager.countDhDrawn()));
    }

    /**
     * Red boxes round the ghosts, as they are drawn — pulled in where the ghost is. Called from the
     * ghost pass, with its pose stack, after the ghosts themselves.
     */
    static void drawBoxes(List<EntityGhost> ghosts, Vec3 eye, float partialTick, PoseStack poseStack,
            MultiBufferSource.BufferSource buffers, double farPlane) {
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        for (EntityGhost ghost : ghosts) {
            if (ghost.lod() == GhostLOD.HIDDEN) {
                continue;
            }

            Vec3 position = ghost.position(partialTick);
            double away = position.distanceTo(eye);
            double pull = GhostRenderDispatcher.pull(away, farPlane);
            AABB bounds = ghost.current().bounds();
            Vec3 drawnAt = eye.add(position.subtract(eye).scale(pull));
            AABB box = new AABB(
                    bounds.minX * pull, bounds.minY * pull, bounds.minZ * pull,
                    bounds.maxX * pull, bounds.maxY * pull, bounds.maxZ * pull).move(drawnAt.subtract(eye));

            // Red for a drawn ghost, dimmer for one that is occluded, blue for one Distant Horizons draws.
            float green = ghost.isOccluded() ? 0.4F : 0.0F;
            float blue = ghost.isDhDrawn() ? 1.0F : 0.0F;
            LevelRenderer.renderLineBox(poseStack, lines, box, 1.0F, green, blue, 1.0F);
        }

        buffers.endBatch(RenderType.lines());
    }
}
