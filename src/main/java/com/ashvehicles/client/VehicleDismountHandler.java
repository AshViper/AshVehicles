package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.VehicleEntityBase;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;

/**
 * One key gets a crew member out of anything in the mod, and it is alt.
 *
 * <p>Shift cannot be it. In a cockpit shift is the throttle, and a pilot climbing away from the
 * ground with the throttle open would step out of the aeroplane doing it. That was moved to alt for
 * the pilot alone to begin with, which left everybody else — the passengers sitting behind the
 * pilot, and the whole crew of a tank — getting out on a key the cockpit had quietly taken away.
 * One key for every seat of every machine is the whole point of this.
 *
 * <p>Getting out is decided on the server, from the shift state the client reports in its input
 * packet. This fires the moment the input has been read and before that packet goes out, so
 * rewriting the flag here is all it takes: hold alt and the server hears what it expects to hear,
 * hold shift and it hears nothing.
 *
 * <p>The line vanilla writes across the screen on climbing in names the key <em>it</em> thinks does
 * this, which is shift and is now wrong. {@code MountHintMixin} asks here for the one that works.
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class VehicleDismountHandler {
    @SubscribeEvent
    public static void onMovementInputUpdate(MovementInputUpdateEvent event) {
        if (isAboard(event.getEntity())) {
            event.getInput().shiftKeyDown = ModKeyMappings.DISMOUNT.isDown();
        }
    }

    /** Whether this rider is sitting in one of ours, in any seat, flying or driving or neither. */
    public static boolean isAboard(Entity rider) {
        return rider != null && rider.getVehicle() instanceof VehicleEntityBase;
    }

    /** The key vanilla's "press this to get out" line ought to be naming. */
    public static Component dismountKeyName() {
        return ModKeyMappings.DISMOUNT.getTranslatedKeyMessage();
    }

    private VehicleDismountHandler() {
    }
}
