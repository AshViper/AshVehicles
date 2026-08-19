package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.AircraftEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import com.ashvehicles.aircraft.Attitude;

import org.joml.Quaternionf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

/**
 * Bolts anyone riding an aircraft to it, so they bank and pitch with the airframe.
 *
 * <p>Minecraft draws every entity bolt upright and interpolates each one's position on its own, so a
 * rider left alone stays level while the aeroplane around them stands on a wingtip, and drifts out
 * of the cockpit in a hard turn as the two interpolations disagree.
 *
 * <p>Rather than tilting the rider where they happen to have been drawn, the pose is rebuilt from
 * the aircraft: back to the aircraft's own origin, round by its attitude, then out to the seat. That
 * is the same origin and the same rotation the model renderer uses, so the two cannot drift apart no
 * matter what the rider's own position is doing.
 *
 * <p>The pose is pushed before the rider is drawn and popped afterwards. Cancelling the pre-event
 * skips the post-event, and a cancelled event is not delivered here, so the two stay paired.
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class PassengerTiltHandler {
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderRiderPre(RenderLivingEvent.Pre<?, ?> event) {
        Entity rider = event.getEntity();

        if (!(rider.getVehicle() instanceof AircraftEntity aircraft)) {
            return;
        }

        float partialTick = event.getPartialTick();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();

        // Step back to wherever the aircraft model is being drawn this frame.
        Vec3 correction = aircraft.getPosition(partialTick).subtract(rider.getPosition(partialTick));
        poseStack.translate(correction.x, correction.y, correction.z);

        // Turn into the aircraft's frame: +Z along the nose, +Y up, and therefore +X to the left.
        Quaternionf attitude = aircraft.getAttitude(partialTick);
        poseStack.mulPose(attitude);

        // Out to the seat, which is now measured along the axes it was authored in.
        Vec3 seat = aircraft.getSeatOffset(aircraft.getSeatIndex(rider));
        poseStack.translate(-seat.x, seat.y, seat.z);

        // Undo the heading, about the seat rather than the aircraft: the rider is tilted with the
        // airframe but still faces wherever they are looking.
        poseStack.mulPose(Axis.YP.rotationDegrees(Attitude.heading(attitude)));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderRiderPost(RenderLivingEvent.Post<?, ?> event) {
        if (event.getEntity().getVehicle() instanceof AircraftEntity) {
            event.getPoseStack().popPose();
        }
    }

    private PassengerTiltHandler() {
    }
}
