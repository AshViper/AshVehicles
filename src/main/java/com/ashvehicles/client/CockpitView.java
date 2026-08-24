package com.ashvehicles.client;

import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.entity.AircraftEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Where the pilot is looking, held as a turn of their head within the cockpit.
 *
 * <p>Minecraft stores a view as a compass bearing and an elevation against the world, which is
 * enough for someone standing on the ground and not enough for someone strapped into an aeroplane.
 * Bank the aircraft and the mouse no longer matches the screen; put the nose near the vertical and
 * the bearing stops meaning anything, so the view slews or locks up.
 *
 * <p>The answer is not to give the head a free rotation, which brings troubles of its own, but to
 * keep the same two angles a head actually has and measure them <em>against the aircraft</em>
 * instead of against the world. Sideways is then always sideways however the aircraft is lying,
 * there is no attitude at which the two angles run into each other, and the head cannot do anything
 * a head cannot do.
 *
 * <p>That last part matters more than it sounds. Held as a free rotation, turned about its own axes
 * by each mouse movement, the head has two ways to go wrong. It can be pitched past the vertical,
 * where it carries on over the top and leaves the pilot hanging upside down facing backwards with
 * no way back; and because turning about one's own axes does not commute, circling the mouse winds
 * roll into it a few degrees at a time until the horizon sits permanently askew. Two angles and a
 * limit on each have neither problem.
 *
 * <p>Where the pilot is looking in the world is this turn applied after the aircraft's own attitude,
 * and the camera is handed the three angles that reproduce it.
 *
 * <p>The player's own bearing and elevation are still kept up to date from the result, because
 * everything else in the game - what the crosshair is over, which way the body faces - reads those.
 */
public final class CockpitView {
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);
    /**
     * As far up and down as the pilot can look, in degrees. The same limit vanilla puts on every
     * other view in the game, and for the same reason: straight up is as far as looking up goes, and
     * a view that carries on over the top has turned itself upside down rather than looked higher.
     */
    private static final float PITCH_LIMIT = 90.0F;
    /**
     * As far round as the pilot can look, in degrees. Matches the limit the aircraft already puts on
     * a rider's body in {@code AircraftEntity.clampRotation}, so the view and the crosshair do not
     * end up pointing at different things.
     */
    private static final float YAW_LIMIT = 135.0F;

    /** The head's turn within the cockpit: right positive. Zero looks straight down the nose. */
    private static float yaw;
    /** The head's elevation within the cockpit, counted as Minecraft counts it: down positive. */
    private static float pitch;
    private static AircraftEntity aircraft;

    private CockpitView() {
    }

    /** True while the local player is aboard an aircraft and looking out of it. */
    public static boolean isActive() {
        return aircraft != null && !aircraft.isRemoved();
    }

    /**
     * Called every frame from the camera. Takes note of whether the player is aboard, and starts the
     * head looking straight ahead whenever they climb in or out.
     */
    public static void follow(AircraftEntity riding) {
        if (riding != aircraft) {
            aircraft = riding;
            yaw = 0.0F;
            pitch = 0.0F;
        }
    }

    /**
     * Turns the head by a mouse movement, in degrees.
     *
     * <p>Both angles are measured against the aircraft, so left is whatever is left on the screen at
     * that moment whichever way up the aeroplane is; and both stop where a head stops, so the mouse
     * cannot push the view over the top or wind it round.
     */
    public static void turn(double deltaYaw, double deltaPitch) {
        yaw = Mth.clamp((float) (yaw + deltaYaw), -YAW_LIMIT, YAW_LIMIT);
        pitch = Mth.clamp((float) (pitch + deltaPitch), -PITCH_LIMIT, PITCH_LIMIT);
    }

    /**
     * Points the head at a direction in the world, as far as a head can be pointed at it.
     *
     * <p>What the mouse moves while flying by pointing is the mark in the sky, not the head; the head
     * then follows the mark, so the pilot is always looking at what they are asking the aeroplane to
     * fly at. The two angles are worked out afresh from the direction rather than accumulated, so
     * nothing drifts, and they are clamped exactly as a mouse movement would be — a mark that has
     * gone further round than a head can turn simply leaves the head at the end of its travel.
     */
    public static void lookAlong(Vec3 direction) {
        if (!isActive() || direction.lengthSqr() < 1.0E-8) {
            return;
        }

        Quaternionf attitude = aircraft.getAttitude(1.0F);
        double ahead = direction.dot(Attitude.nose(attitude));
        double across = direction.dot(Attitude.right(attitude));
        double above = direction.dot(Attitude.up(attitude));

        yaw = Mth.clamp((float) Math.toDegrees(Mth.atan2(across, ahead)), -YAW_LIMIT, YAW_LIMIT);
        // The head's elevation is counted down-positive, the way Minecraft counts every other one.
        pitch = Mth.clamp((float) -Math.toDegrees(Math.asin(Mth.clamp(above, -1.0, 1.0))),
                -PITCH_LIMIT, PITCH_LIMIT);
    }

    /** Where the pilot is looking in the world: the aircraft's attitude, then the head's turn. */
    public static Quaternionf world(float partialTick) {
        if (!isActive()) {
            return new Quaternionf();
        }

        return new Quaternionf(aircraft.getAttitude(partialTick))
                .rotateY(-yaw * DEG_TO_RAD)
                .rotateX(pitch * DEG_TO_RAD)
                .normalize();
    }

    /**
     * Keeps the player's own bearing and elevation pointing where the pilot is looking. The roll is
     * not theirs to carry - Minecraft has nowhere to put it - so it stays on the camera, but the
     * direction has to agree or the crosshair would be over one thing and the view over another.
     */
    public static void applyToPlayer() {
        LocalPlayer player = Minecraft.getInstance().player;

        if (player == null || !isActive()) {
            return;
        }

        Quaternionf world = world(1.0F);
        player.setYRot(Attitude.heading(world));
        player.setXRot(Attitude.elevation(world));
        player.setYHeadRot(player.getYRot());
    }

    /** Where the pilot is looking, as a direction. */
    public static Vec3 lookVector(float partialTick) {
        return Attitude.nose(world(partialTick));
    }
}
