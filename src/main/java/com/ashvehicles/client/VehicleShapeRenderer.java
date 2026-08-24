package com.ashvehicles.client;

import java.util.List;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.AshVehicles;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.vehicle.VehicleShape;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

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
 * Draws a vehicle's collision boxes as they are written, tilted with the vehicle, turned with its
 * turret, and with whatever angle each box carries.
 *
 * <p>Minecraft's own hitbox overlay cannot show this. What the game collides against is an upright
 * box drawn around each tilted one, so the overlay shows a wing as a tall slab the moment the
 * aircraft banks and never shows the wing itself — and a tank's gun as a box very nearly square the
 * moment the turret comes off the bow. That is honest about what the game will collide with and
 * useless for checking whether the shape fits the model, which is what these are for.
 *
 * <p>Shown whenever the hitbox overlay is, so F3+B turns it on. It is not an aid to reading the
 * game's own outline beside it — it is the shape itself. Nothing Minecraft draws is what a machine
 * is hit or collided with any more; these boxes are.
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class VehicleShapeRenderer {
    private static final float RED = 0.25F;
    private static final float GREEN = 1.0F;
    private static final float BLUE = 0.45F;
    private static final float ALPHA = 0.9F;
    /** Pylons are drawn red, so that where a weapon hangs is never mistaken for a piece of airframe. */
    private static final float PYLON_RED = 1.0F;
    private static final float PYLON_GREEN = 0.2F;
    private static final float PYLON_BLUE = 0.2F;
    /**
     * Boxes carried by a turret are drawn amber, because the one thing worth checking about them is
     * whether they follow the turret round — and a box that has quietly stayed on the hull looks
     * exactly like a correct one until the gun is laid abeam.
     */
    private static final float TURRET_RED = 1.0F;
    private static final float TURRET_GREEN = 0.75F;
    private static final float TURRET_BLUE = 0.2F;
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
            if (!entity.position().closerThan(eye, RANGE)) {
                continue;
            }

            if (entity instanceof AircraftEntity aircraft) {
                draw(poseStack, lines, aircraft, eye, partialTick);
            } else if (entity instanceof GroundVehicleEntity vehicle) {
                draw(poseStack, lines, vehicle, eye, partialTick);
            }
        }

        buffers.endBatch(RenderType.lines());
    }

    private static void draw(PoseStack poseStack, VertexConsumer lines, AircraftEntity aircraft,
            Vec3 eye, float partialTick) {
        VehicleShape shape = Definitions.shape(aircraft.getAircraftId());
        List<AircraftDefinition.Hardpoint> hardpoints = aircraft.getStats().hardpoints();

        if (shape.boxes().isEmpty() && hardpoints.isEmpty()) {
            return;
        }

        Vec3 position = aircraft.getPosition(partialTick);

        poseStack.pushPose();
        poseStack.translate(position.x - eye.x, position.y - eye.y, position.z - eye.z);
        poseStack.mulPose(aircraft.getAttitude(partialTick));

        for (VehicleShape.Box box : shape.boxes()) {
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

    /**
     * The same for a ground vehicle, with the turret's boxes swung about its ring.
     *
     * <p>Built the same way the vehicle builds them — into the ring, round by the traverse, then out
     * to the box — so what is drawn is what is really being shot at rather than a second opinion
     * about it. Inside the vehicle's own frame +X points left, so an offset to the right is negative,
     * and the traverse is a negative turn about Y for the same reason.
     */
    private static void draw(PoseStack poseStack, VertexConsumer lines, GroundVehicleEntity vehicle,
            Vec3 eye, float partialTick) {
        VehicleShape shape = Definitions.shape(vehicle.getVehicleId());

        if (shape.boxes().isEmpty()) {
            return;
        }

        Vec3 position = vehicle.getPosition(partialTick);
        Vec3 ring = vehicle.getStats().turret().ring();
        float traverse = vehicle.getTurretYaw(partialTick);

        poseStack.pushPose();
        poseStack.translate(position.x - eye.x, position.y - eye.y, position.z - eye.z);
        poseStack.mulPose(vehicle.getAttitude(partialTick));

        for (VehicleShape.Box box : shape.boxes()) {
            boolean onTurret = box.mount() == VehicleShape.Mount.TURRET;
            Vec3 offset = onTurret ? box.offset().subtract(ring) : box.offset();

            poseStack.pushPose();

            if (onTurret) {
                poseStack.translate(-ring.x, ring.y, ring.z);
                poseStack.mulPose(Axis.YP.rotationDegrees(-traverse));
            }

            poseStack.translate(-offset.x, offset.y, offset.z);
            poseStack.mulPose(box.orientation());

            Vec3 half = box.size().scale(0.5);
            LevelRenderer.renderLineBox(poseStack, lines,
                    -half.x, -half.y, -half.z, half.x, half.y, half.z,
                    onTurret ? TURRET_RED : RED,
                    onTurret ? TURRET_GREEN : GREEN,
                    onTurret ? TURRET_BLUE : BLUE,
                    ALPHA);

            poseStack.popPose();
        }

        poseStack.popPose();
    }

    private VehicleShapeRenderer() {
    }
}
