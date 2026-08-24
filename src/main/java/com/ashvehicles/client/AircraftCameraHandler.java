package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.entity.AircraftEntity;

import org.joml.Quaternionf;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
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
 * {@code camera.pos} from the middle of the aircraft; where that lands, and why it is measured along
 * the line of sight rather than along the airframe, is {@link ChaseCamera}'s business.
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
    @SubscribeEvent
    public static void onCalculateCameraDistance(CalculateDetachedCameraDistanceEvent event) {
        if (riddenAircraft(event.getCamera()) != null) {
            // The camera is placed outright below, so vanilla should leave it on the pilot's eyes.
            event.setDistance(0.0F);
        }
    }

    /**
     * Tips the horizon over with the wings in first person. Only the bank is taken from the
     * aircraft: pitch and heading are where the pilot is looking, which is what aims the aeroplane.
     * Third person is left upright, where a rolling horizon only makes the aircraft harder to watch.
     *
     * <p>The head itself is followed in <em>both</em> views. It used to be dropped the moment the
     * camera detached, which handed the mouse back to vanilla and with it the world-referenced
     * bearing that does not survive a bank — sideways stopped being sideways the moment the wings
     * were not level, in the view most people actually fly in. Third person still takes no roll from
     * it, but it is the same head, turned in the aircraft's own axes, and the chase camera swings
     * round with it because vanilla places a detached camera along the player's own line of sight.
     */
    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Camera camera = event.getCamera();
        AircraftEntity aircraft = riddenAircraft(camera);

        CockpitView.follow(aircraft);
        MouseAim.follow(aircraft);

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
            // The eye of the seat this one is actually in, which on a two-seater is not the pilot's:
            // an F-14's back-seater is a block and a half behind the front canopy and should be
            // looking out of their own.
            camera.setPosition(aircraft.eyeOf(viewer, partialTick));

            return;
        }

        ChaseCamera.place(camera, aircraft, aircraft.getStats().camera().pos(), partialTick);
    }

    /** The aircraft the camera's owner is riding, or null if they are not flying one. */
    private static AircraftEntity riddenAircraft(Camera camera) {
        Entity viewer = camera.getEntity();

        return viewer != null && viewer.getVehicle() instanceof AircraftEntity aircraft ? aircraft : null;
    }

    private AircraftCameraHandler() {
    }
}
