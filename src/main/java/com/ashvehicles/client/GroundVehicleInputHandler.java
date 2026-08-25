package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.GroundVehicleInput;
import com.ashvehicles.network.GroundVehicleInputPayload;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Turns the driver's controls into vehicle input once a tick.
 *
 * <p>Two axes, a brake and two triggers — the attack button for whichever armament is selected, and
 * a key of its own for the coaxial machine gun, which is never selected. The mouse is not part of
 * driving at all: it is where the crew are looking, and where the crew are looking is what the
 * turret is laid on — see
 * {@code GroundVehicleEntity.tickTurret}. That is why a tank needs so much less of this than an
 * aeroplane: the only things taken away from vanilla are the two mouse buttons, the attack button
 * being the trigger now and the use button the sight — see {@link AimZoom}, which reads it for
 * itself. Getting out is alt, the same key as in a cockpit and for the same reason it was moved
 * there — one way out of everything in the mod; see {@link VehicleDismountHandler}.
 *
 * <p>The vehicle is simulated on this client, so the resulting input is applied locally and sent to
 * the server, which mirrors the hull and the turret to everyone else.
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class GroundVehicleInputHandler {
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;

        if (player == null || minecraft.isPaused()) {
            return;
        }

        // One key steps through the weapons of everything in the mod, and a mapping hands a click to
        // whichever caller asks for it first. Both this handler and AircraftInputHandler run every
        // tick with no order between them, so the two conditions have to be exact mirrors of each
        // other or they race: this one takes the press whenever the player is aboard a ground
        // vehicle, that one whenever they are not, and exactly one of them takes it whatever order
        // they run in.
        //
        // Drained while merely riding as well as while driving — and thrown away in that case — so a
        // press made in the back cannot queue up and switch weapons the moment the crew change
        // seats.
        //
        // Draining it unconditionally, which is what this used to do, ate the press out from under a
        // pilot: in a cockpit this handler had no use for the click and took it anyway, and the
        // aircraft's own handler found nothing left. Holding the key appeared to fix it because a
        // held key repeats and makes a second click for the other handler to find, which is the
        // whole of why switching weapons wanted a long press.
        boolean cycleWeapon = player.getVehicle() instanceof GroundVehicleEntity
                && ModKeyMappings.CYCLE_WEAPON.consumeClick();

        GroundVehicleEntity vehicle = drivenVehicle(player);

        if (vehicle == null) {
            return;
        }

        // The attack button is the trigger now, and vanilla would otherwise spend it swinging at the
        // inside of the turret and mining whatever the hull is resting against. The use button is
        // the sight, and what vanilla would do with it — eat, or click the tank from the inside —
        // is not what a crew holding it down over a target meant.
        while (minecraft.options.keyAttack.consumeClick()) {
        }

        while (minecraft.options.keyUse.consumeClick()) {
        }

        GroundVehicleInput input = new GroundVehicleInput(
                axis(ModKeyMappings.DRIVE_FORWARD, ModKeyMappings.DRIVE_BACK),
                axis(ModKeyMappings.STEER_RIGHT, ModKeyMappings.STEER_LEFT),
                ModKeyMappings.VEHICLE_BRAKE.isDown(),
                minecraft.options.keyAttack.isDown(),
                ModKeyMappings.FIRE_COAXIAL.isDown());

        vehicle.setInput(input);
        // Before the vehicle is ticked, which is this event's whole reason for being Pre: the turret
        // is laid inside that tick and has to know how the view it is being laid through is sitting.
        vehicle.setSightTilt(sightTilt(minecraft, vehicle));
        // The hull, the speed and the turret go with it because the server cannot see any of them:
        // a vehicle driven from here is moved on the server by packets that land between its ticks,
        // and vanilla's movement packet carries a heading and an elevation and nothing else.
        PacketDistributor.sendToServer(new GroundVehicleInputPayload(input, vehicle.getAttitude(),
                vehicle.getSpeed(), vehicle.getTurretYaw(1.0F), vehicle.getGunPitch(1.0F), cycleWeapon));
    }

    /**
     * Stops the attack button doing anything vanilla would do with it while the player is driving:
     * no swinging, and no mining whatever the tank happens to be parked against. It is the trigger
     * now, and the trigger is read in {@link #onClientTick}. The use button goes the same way, for
     * the same reason: it is the sight, and using whatever is in the crew's hand is not what
     * holding it down meant.
     */
    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if ((event.isAttack() || event.isUseItem()) && Minecraft.getInstance().player instanceof LocalPlayer player
                && drivenVehicle(player) != null) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }

    /**
     * How far the view the crew are using is tipped below their own line of sight, in degrees.
     *
     * <p>The chase view is rotated down by the machine's {@code camera.tilt} so that there is ground
     * on the screen rather than sky; the first-person view is not rotated at all. What the gun does
     * about it is {@code GroundVehicleEntity.setSightTilt}: it is laid down by the same amount, so
     * the middle of the screen is the line of the gun in either view rather than only in one of
     * them.
     *
     * <p>Switching between the two therefore moves the gun, and it moves at the turret's own
     * elevation rate like any other lay. That is the honest thing for it to do — the two views are
     * pointing at different places, and the gun follows whichever one the crew are looking through.
     */
    private static float sightTilt(Minecraft minecraft, GroundVehicleEntity vehicle) {
        return minecraft.options.getCameraType().isFirstPerson() ? 0.0F : vehicle.getStats().camera().tilt();
    }

    private static GroundVehicleEntity drivenVehicle(LocalPlayer player) {
        return player.getVehicle() instanceof GroundVehicleEntity vehicle
                && vehicle.getControllingPassenger() == player
                ? vehicle
                : null;
    }

    private static float axis(KeyMapping positive, KeyMapping negative) {
        return (positive.isDown() ? 1.0F : 0.0F) - (negative.isDown() ? 1.0F : 0.0F);
    }

    private GroundVehicleInputHandler() {
    }
}
