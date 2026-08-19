package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.aircraft.Attitude;
import com.ashvehicles.entity.AircraftEntity;

import org.joml.Quaternionf;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Parks the third-person camera where the aircraft's data pack file asks for it, and keeps the whole
 * aeroplane in frame while it does.
 *
 * <p>Vanilla only knows how to sit four blocks behind the player's eyes, which for a fifteen-block
 * aeroplane is somewhere inside the fuselage. The camera is placed outright instead, at
 * {@code camera.pos} from the middle of the aircraft.
 *
 * <p>That offset is measured along the <em>viewing</em> axes, not the aircraft's: x to the right of
 * the view, y straight up, z along the line of sight with negative meaning behind. Hanging it off
 * the aircraft's own heading instead is the obvious thing to do and it is wrong, because the mouse
 * aims by looking: pull the nose up and the view climbs while a heading-locked camera stays level,
 * dropping the aircraft out of the bottom of the screen exactly when the pilot most wants to see it.
 * Measured along the view, the aircraft sits still in frame whatever it is doing.
 *
 * <p>First person is the opposite case and gets the opposite treatment: the camera is bolted into
 * the cockpit at {@code camera.cockpit}, in the aircraft's own axes, and rolls with the wings. The
 * view direction is still the pilot's to choose, since that is what aims the aircraft.
 *
 * <p>The two halves arrive by different routes. Bank goes through NeoForge's camera event, which
 * fires at the top of {@code Camera.setup} and can only set angles; the position has to wait until
 * setup has finished writing its own, which is what {@link com.ashvehicles.mixin.CameraMixin} is
 * for. Setting the position from the event looks like it works and is silently undone.
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class AircraftCameraHandler {
    /** Kept between the camera and whatever block it would otherwise be pressed against. */
    private static final double BLOCK_CLEARANCE = 0.25;

    @SubscribeEvent
    public static void onCalculateCameraDistance(CalculateDetachedCameraDistanceEvent event) {
        if (riddenAircraft(event.getCamera()) != null) {
            // The camera is placed outright below, so vanilla should leave it on the pilot's eyes.
            event.setDistance(0.0F);
        }
    }

    /**
     * Tips the horizon over with the wings in first person. Only the bank is taken from the
     * aircraft: pitch and heading stay the pilot's, because with mouse aim those two are the
     * controls. Third person is left upright, where a rolling horizon only makes the aircraft
     * harder to watch.
     */
    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Camera camera = event.getCamera();
        AircraftEntity aircraft = riddenAircraft(camera);

        CockpitView.follow(camera.isDetached() ? null : aircraft);

        if (aircraft == null || camera.isDetached()) {
            return;
        }

        // Three angles that come to the same rotation the pilot's head is at. Heading and elevation
        // alone cannot describe it; with the roll as well, any orientation goes across intact.
        Quaternionf view = CockpitView.world((float) event.getPartialTick());
        event.setYaw(Attitude.heading(view));
        event.setPitch(Attitude.elevation(view));
        event.setRoll(Attitude.bank(view));
    }

    /**
     * Called from {@link com.ashvehicles.mixin.CameraMixin} once vanilla has finished placing the
     * camera, which is the first moment a new position will actually stick.
     */
    public static void placeCamera(Camera camera, Entity viewer, boolean detached, float partialTick) {
        if (!(viewer.getVehicle() instanceof AircraftEntity aircraft)) {
            return;
        }

        if (!detached) {
            camera.setPosition(aircraft.toWorld(aircraft.getStats().camera().cockpit(), partialTick));

            return;
        }

        Vec3 offset = aircraft.getStats().camera().pos();

        // Following the line of sight rather than the aircraft's heading is what keeps the aeroplane
        // centred through climbs, dives and rolls; it also gets the reversed third-person view right
        // for free, since there the line of sight is flipped and the camera therefore lands in front
        // of the aircraft looking back at it.
        Vec3 sight = Vec3.directionFromRotation(camera.getXRot(), camera.getYRot());
        Vec3 right = sight.cross(new Vec3(0.0, 1.0, 0.0));
        right = right.lengthSqr() < 1.0E-6 ? new Vec3(1.0, 0.0, 0.0) : right.normalize();

        // Middle of the airframe rather than its origin, which sits down at the wheels.
        Vec3 middle = aircraft.getPosition(partialTick).add(0.0, aircraft.getBbHeight() * 0.5, 0.0);
        Vec3 target = middle
                .add(right.scale(offset.x))
                .add(0.0, offset.y, 0.0)
                .add(sight.scale(offset.z));

        camera.setPosition(clipToBlocks(aircraft, middle, target));
    }

    /**
     * Pulls the camera in if the line from the aircraft to it passes through the scenery. Vanilla
     * does the same for its own four blocks; without it the camera happily ends up inside a hill.
     */
    private static Vec3 clipToBlocks(Entity aircraft, Vec3 anchor, Vec3 target) {
        Level level = aircraft.level();
        HitResult hit = level.clip(
                new ClipContext(anchor, target, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, aircraft));

        if (hit.getType() == HitResult.Type.MISS) {
            return target;
        }

        Vec3 blocked = hit.getLocation();
        Vec3 back = blocked.subtract(anchor);

        return back.lengthSqr() <= BLOCK_CLEARANCE * BLOCK_CLEARANCE
                ? anchor
                : blocked.subtract(back.normalize().scale(BLOCK_CLEARANCE));
    }

    /** The aircraft the camera's owner is riding, or null if they are not flying one. */
    private static AircraftEntity riddenAircraft(Camera camera) {
        Entity viewer = camera.getEntity();

        return viewer != null && viewer.getVehicle() instanceof AircraftEntity aircraft ? aircraft : null;
    }

    private AircraftCameraHandler() {
    }
}
