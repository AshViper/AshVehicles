package com.ashvehicles.client;

import java.util.List;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.aircraft.AircraftManager;
import com.ashvehicles.aircraft.AircraftShape;
import com.ashvehicles.entity.AircraftEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Draws an aircraft's collision boxes as they are written, tilted with the aircraft and with
 * whatever angle each box carries.
 *
 * <p>Minecraft's own hitbox overlay cannot show this. What the game collides against is an upright
 * box drawn around each tilted one, so the overlay shows a wing as a tall slab the moment the
 * aircraft banks and never shows the wing itself. That is honest about what the game will collide
 * with and useless for checking whether the shape fits the model, which is what these are for.
 *
 * <p>Shown whenever the hitbox overlay is, so F3+B turns both on: the upright boxes the game uses,
 * and inside them the real ones this mod describes.
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class AircraftShapeRenderer {
    private static final float RED = 0.25F;
    private static final float GREEN = 1.0F;
    private static final float BLUE = 0.45F;
    private static final float ALPHA = 0.9F;
    /** Pylons are drawn red, so that where a weapon hangs is never mistaken for a piece of airframe. */
    private static final float PYLON_RED = 1.0F;
    private static final float PYLON_GREEN = 0.2F;
    private static final float PYLON_BLUE = 0.2F;
    /** Past this there is nothing to check and plenty to slow down. */
    private static final double RANGE = 96.0;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null || !minecraft.getEntityRenderDispatcher().shouldRenderHitBoxes()) {
            return;
        }

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 eye = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        PoseStack poseStack = event.getPoseStack();

        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof AircraftEntity aircraft && entity.position().closerThan(eye, RANGE)) {
                draw(poseStack, lines, aircraft, eye, partialTick);
            }
        }

        buffers.endBatch(RenderType.lines());
    }

    private static void draw(PoseStack poseStack, VertexConsumer lines, AircraftEntity aircraft,
            Vec3 eye, float partialTick) {
        AircraftShape shape = AircraftManager.shape(aircraft.getAircraftId());
        List<AircraftDefinition.Hardpoint> hardpoints = aircraft.getStats().hardpoints();

        if (shape.boxes().isEmpty() && hardpoints.isEmpty()) {
            return;
        }

        Vec3 position = aircraft.getPosition(partialTick);

        poseStack.pushPose();
        poseStack.translate(position.x - eye.x, position.y - eye.y, position.z - eye.z);
        poseStack.mulPose(aircraft.getAttitude(partialTick));

        for (AircraftShape.Box box : shape.boxes()) {
            poseStack.pushPose();
            // Inside the aircraft's own frame +X points left, so an offset to the right is negative.
            poseStack.translate(-box.offset().x, box.offset().y, box.offset().z);
            poseStack.mulPose(box.orientation());

            Vec3 half = box.size().scale(0.5);
            LevelRenderer.renderLineBox(poseStack, lines,
                    -half.x, -half.y, -half.z, half.x, half.y, half.z, RED, GREEN, BLUE, ALPHA);

            poseStack.popPose();
        }

        // The pylons, in red so that they read as a different kind of thing from the airframe: these
        // are places to hang something on rather than pieces of aeroplane, and while an aircraft's
        // hardpoints are being positioned by eye it matters a great deal exactly where they are.
        for (AircraftDefinition.Hardpoint hardpoint : hardpoints) {
            poseStack.pushPose();
            poseStack.translate(-hardpoint.pos().x, hardpoint.pos().y, hardpoint.pos().z);

            double half = AircraftEntity.PYLON_BOX / 2.0;
            LevelRenderer.renderLineBox(poseStack, lines,
                    -half, -half, -half, half, half, half, PYLON_RED, PYLON_GREEN, PYLON_BLUE, ALPHA);

            poseStack.popPose();
        }

        poseStack.popPose();
    }

    private AircraftShapeRenderer() {
    }
}
