package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.AircraftInput;
import com.ashvehicles.network.AircraftInputPayload;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Turns the pilot's controls into aircraft input once a tick.
 *
 * <p>The three rotation axes are flown directly, one pair of keys each: W and S pitch, A and D roll,
 * Q and E yaw. Shift and control are the throttle. The mouse is not part of flying at all; it only
 * looks around.
 *
 * <p>The aircraft is simulated on this client (see {@link AircraftEntity}), so the resulting input is
 * applied locally and sent to the server, which mirrors the attitude to everyone else.
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class AircraftInputHandler {
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;

        if (player == null || minecraft.isPaused()) {
            return;
        }

        // Drained every tick, in or out of a cockpit, so a press made on the ground cannot queue up
        // and fire the moment the player climbs in.
        boolean toggleGear = ModKeyMappings.TOGGLE_GEAR.consumeClick();
        boolean toggleFlaps = ModKeyMappings.TOGGLE_FLAPS.consumeClick();
        boolean cycleWeapon = ModKeyMappings.CYCLE_WEAPON.consumeClick();
        AircraftEntity aircraft = pilotedAircraft(player);

        if (aircraft == null) {
            return;
        }

        // Q, E and F are the rudder and the flaps up here. Vanilla reads them later in the same tick,
        // so emptying the click queues now stops the pilot dropping their sword out of the cockpit.
        while (minecraft.options.keyDrop.consumeClick()) {
        }

        while (minecraft.options.keySwapOffhand.consumeClick()) {
        }

        // And the trigger is the attack button, which vanilla would otherwise spend swinging at the
        // inside of the canopy. Held rather than clicked: a gun fires for as long as it is pressed.
        while (minecraft.options.keyAttack.consumeClick()) {
        }

        AircraftInput input = new AircraftInput(
                // Stick forward (the walk-forwards key) drops the nose, as in any flight sim.
                -player.input.forwardImpulse,
                // Vanilla's left impulse is positive to the left; rolling is positive to the right.
                -player.input.leftImpulse,
                axis(ModKeyMappings.YAW_RIGHT, ModKeyMappings.YAW_LEFT),
                axis(ModKeyMappings.THROTTLE_UP, ModKeyMappings.THROTTLE_DOWN),
                ModKeyMappings.AIR_BRAKE.isDown(),
                minecraft.options.keyAttack.isDown());

        aircraft.setInput(input);
        // The velocity goes with it because the server cannot see it: an aircraft flown from here is
        // moved on the server by packets that land between its ticks, so from over there it never
        // appears to move at all. Without this, everything fired from it left with no speed of its
        // own. See AircraftEntity.getVelocity.
        PacketDistributor.sendToServer(new AircraftInputPayload(
                input, aircraft.getThrottle(), aircraft.getAttitude(), aircraft.getVelocity(),
                aircraft.isCrashing(), toggleGear, toggleFlaps, cycleWeapon));
    }

    /**
     * Stops the attack button doing anything vanilla would do with it while the player is flying: no
     * swinging, and no mining whatever the cockpit happens to be resting against. It is the trigger
     * now, and the trigger is read in {@link #onClientTick}.
     */
    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (event.isAttack() && Minecraft.getInstance().player instanceof LocalPlayer player
                && pilotedAircraft(player) != null) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }

    /**
     * Moves "get out" from shift to alt, since shift is now the throttle.
     *
     * <p>Dismounting is decided on the server, from the shift state the client reports in its input
     * packet. This fires the moment the input has been read and before that packet goes out, so
     * rewriting the flag here is all it takes: hold alt and the server hears what it expects to hear,
     * hold shift and it hears nothing.
     */
    @SubscribeEvent
    public static void onMovementInputUpdate(MovementInputUpdateEvent event) {
        if (event.getEntity() instanceof LocalPlayer player && pilotedAircraft(player) != null) {
            event.getInput().shiftKeyDown = ModKeyMappings.DISMOUNT.isDown();
        }
    }

    private static AircraftEntity pilotedAircraft(LocalPlayer player) {
        return player.getVehicle() instanceof AircraftEntity aircraft && aircraft.getControllingPassenger() == player
                ? aircraft
                : null;
    }

    private static float axis(KeyMapping positive, KeyMapping negative) {
        return (positive.isDown() ? 1.0F : 0.0F) - (negative.isDown() ? 1.0F : 0.0F);
    }

    private AircraftInputHandler() {
    }
}
