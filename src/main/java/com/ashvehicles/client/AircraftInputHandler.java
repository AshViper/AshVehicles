package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.AircraftInput;
import com.ashvehicles.network.AircraftInputPayload;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Turns the pilot's controls into aircraft input once a tick.
 *
 * <p>The three rotation axes are flown directly, one pair of keys each, and every pair is a binding
 * the pilot can change: W and S pitch, A and D roll, Q and E yaw to begin with. Shift and control are
 * the throttle. The mouse is not part of flying at all; it only looks around.
 *
 * <p>The aircraft is simulated on this client (see {@link AircraftEntity}), so the resulting input is
 * applied locally and sent to the server, which mirrors the attitude to everyone else.
 *
 * <p>Getting out is alt rather than shift, and is not decided here: it is one key for every seat
 * of every machine in the mod, and it lives in {@link VehicleDismountHandler}.
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
        boolean toggleVtol = ModKeyMappings.TOGGLE_VTOL.consumeClick();
        // One key steps through the weapons of everything in the mod, and only one handler may take
        // the press — a mapping hands a click to whoever asks for it first, and both of these run
        // every tick. So this one stands aside entirely while the player is aboard a ground vehicle
        // and lets {@link GroundVehicleInputHandler} have it; the short circuit is the whole of the
        // arrangement, since not consuming is what leaves the press there for the other to find.
        boolean cycleWeapon = !(player.getVehicle() instanceof com.ashvehicles.entity.GroundVehicleEntity)
                && ModKeyMappings.CYCLE_WEAPON.consumeClick();

        while (ModKeyMappings.TOGGLE_MOUSE_AIM.consumeClick()) {
            MouseAim.setEnabled(!MouseAim.isEnabled());
            // Said out loud, because it changes what the mouse does to the aircraft and there is
            // nothing else on the screen that would tell the pilot which way round it now is.
            player.displayClientMessage(Component.translatable(
                    MouseAim.isEnabled() ? "message.ashvehicles.mouse_aim_on"
                            : "message.ashvehicles.mouse_aim_off"), true);
        }

        AircraftEntity aircraft = pilotedAircraft(player);

        if (aircraft == null) {
            return;
        }

        // Q, E and F are the rudder and the flaps up here. Vanilla reads them later in the same tick,
        // so emptying the click queues now stops the pilot dropping their sword out of the cockpit,
        // opening their inventory over the canopy, or swapping hands mid-turn.
        while (minecraft.options.keyDrop.consumeClick()) {
        }

        // And the use button is the sight. From inside a cockpit there is nothing to use anyway,
        // and what vanilla would do with it — eat, or click the aeroplane from the inside — is
        // not what a pilot holding the button down over a target meant.
        while (minecraft.options.keyUse.consumeClick()) {
        }

        while (minecraft.options.keyInventory.consumeClick()) {
        }

        while (minecraft.options.keySwapOffhand.consumeClick()) {
        }

        // And the trigger is the attack button, which vanilla would otherwise spend swinging at the
        // inside of the canopy. Held rather than clicked: a gun fires for as long as it is pressed.
        while (minecraft.options.keyAttack.consumeClick()) {
        }

        // Read from bindings of their own rather than from the movement impulses these keys also
        // feed. It is the same two keys by default and the same stick, but it is now a stick the
        // pilot can move: bound here, pitch and roll appear in the controls screen beside the rest of
        // the aircraft, and a player who has walking on something other than WASD is no longer flying
        // from keys they gave to something else.
        float keyPitch = axis(ModKeyMappings.PITCH_UP, ModKeyMappings.PITCH_DOWN);
        float keyRoll = axis(ModKeyMappings.ROLL_RIGHT, ModKeyMappings.ROLL_LEFT);
        float keyYaw = axis(ModKeyMappings.YAW_RIGHT, ModKeyMappings.YAW_LEFT);

        // A hand on the stick takes that axis back, and only that one. Adding the two together
        // instead would leave the keys feeling dead, since the aim would be pulling the other way to
        // hold the nose where it was — and taking the whole aircraft back for one key would mean a
        // helicopter pilot could not slide sideways without losing the mouse off the nose, roll
        // being the one axis a rotorcraft leaves to the keys in the first place.
        MouseAim.Stick aim = MouseAim.stick();

        AircraftInput input = new AircraftInput(
                keyPitch != 0.0F ? keyPitch : aim.pitch(),
                keyRoll != 0.0F ? keyRoll : aim.roll(),
                keyYaw != 0.0F ? keyYaw : aim.yaw(),
                axis(ModKeyMappings.THROTTLE_UP, ModKeyMappings.THROTTLE_DOWN),
                ModKeyMappings.AIR_BRAKE.isDown(),
                minecraft.options.keyAttack.isDown(),
                // Held rather than clicked: a dispenser lets go of one at a time for as long as the
                // handle is pulled, and its own interval decides how fast that is.
                ModKeyMappings.RELEASE_FLARE.isDown(),
                ModKeyMappings.RELEASE_CHAFF.isDown());

        aircraft.setInput(input);
        // The velocity goes with it because the server cannot see it: an aircraft flown from here is
        // moved on the server by packets that land between its ticks, so from over there it never
        // appears to move at all. Without this, everything fired from it left with no speed of its
        // own. See AircraftEntity.getVelocity.
        PacketDistributor.sendToServer(new AircraftInputPayload(
                input, aircraft.getThrottle(), aircraft.getAfterburner(),
                aircraft.getAttitude(), aircraft.getVelocity(),
                aircraft.isCrashing(), toggleGear, toggleFlaps, toggleVtol, cycleWeapon));
    }

    /**
     * Stops the attack button doing anything vanilla would do with it while the player is flying: no
     * swinging, and no mining whatever the cockpit happens to be resting against. It is the trigger
     * now, and the trigger is read in {@link #onClientTick}.
     *
     * <p>The middle button goes the same way, for the same reason: it is the free-look handle from
     * inside a cockpit, and picking whatever block the aeroplane is pointing at is neither useful nor
     * what the pilot was asking for. And the right button is the sight — see {@link AimZoom} — so
     * using whatever is in the pilot's hand is not what holding it down meant either.
     */
    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if ((event.isAttack() || event.isPickBlock() || event.isUseItem())
                && Minecraft.getInstance().player instanceof LocalPlayer player
                && pilotedAircraft(player) != null) {
            event.setSwingHand(false);
            event.setCanceled(true);
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
