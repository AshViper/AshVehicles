package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.VehicleEntityBase;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * The sight brought up to the eye: a narrower field of view for as long as the aim key is held.
 *
 * <p>A gunsight is a small mark on a distant target, and at the ordinary field of view a target
 * worth shooting at is a few pixels wide by the time the round can reach it. Pull the view in and
 * it is something that can be laid on, which is the whole of what a sight is for. Whoever is at
 * the controls is still laying the gun onto it — nothing here aims anything; see {@link GunSight}
 * for what an aircraft's marks mean and {@link GroundVehicleHud} for a tank's — and the mouse is
 * slowed by the same amount the view is narrowed, so a movement on the screen is the same movement
 * on the screen whether the sight is up or not.
 *
 * <p>One sight for every machine in the mod, read from one key, for the reason the way out of
 * them is one key: a crew member who has to remember which seat they are in before they can look
 * down a sight has been given the wrong key. Only whoever is at the controls gets it, because the
 * key it sits on is vanilla's use key, and only for the crew at the controls is that swallowed —
 * a passenger's right click still does what it always did, and should not zoom as well.
 *
 * <p>Eased over a few ticks rather than snapped, because a view that jumps is a view the eye has
 * to find its place in again, and the moment the sight comes up is exactly the moment the crew
 * are trying not to lose the target.
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class AimZoom {
    /** How much closer the sight brings things. */
    public static final float ZOOM = 2.5F;
    /** How far the view travels in a tick, as a fraction of the whole way in or out. */
    private static final float RATE = 0.3F;

    private static float progress;
    private static float progressO;
    private static boolean aiming;

    private AimZoom() {
    }

    /**
     * Once a tick: whether the sight is wanted up, which is the aim key held by whoever is at the
     * controls of anything. Ticked whether or not anyone is, so the view comes back out after the
     * crew have climbed down with the key still held.
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;

        if (player == null || minecraft.isPaused()) {
            return;
        }

        boolean atControls = player.getVehicle() instanceof VehicleEntityBase vehicle
                && vehicle.getControllingPassenger() == player;

        tick(atControls && ModKeyMappings.AIM.isDown());
    }

    private static void tick(boolean wanted) {
        aiming = wanted;
        progressO = progress;
        progress = wanted ? Math.min(progress + RATE, 1.0F) : Math.max(progress - RATE, 0.0F);
    }

    /** Whether the aim key is held. */
    public static boolean isAiming() {
        return aiming;
    }

    /** How much narrower than usual the view is at this moment: one for not at all. */
    public static float factor(float partialTick) {
        return 1.0F + (ZOOM - 1.0F) * Mth.lerp(partialTick, progressO, progress);
    }

    /** The same, for this frame. */
    public static float factor() {
        return factor(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true));
    }

    /**
     * Narrows the world's field of view while the sight is up. Only the world's: the hands are
     * drawn at a field of view of their own, and a spyglass-sized pair of hands is not wanted.
     */
    @SubscribeEvent
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (!event.usedConfiguredFov()) {
            return;
        }

        float factor = factor((float) event.getPartialTick());

        if (factor > 1.0001F) {
            event.setFOV(event.getFOV() / factor);
        }
    }
}
