package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.GroundVehicleEntity;

import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Sits the chase camera back off a ground vehicle and tips it down onto it.
 *
 * <p>Vanilla's four blocks are measured from the rider's eyes, and a tank is longer than that: the
 * camera comes out somewhere over the engine deck, with the hull filling the screen and nothing of
 * the ground the crew are fighting over. The camera is placed outright instead, at {@code camera.pos}
 * from the middle of the vehicle — see {@link ChaseCamera}, which is the same placement an aircraft
 * gets and for the same reasons.
 *
 * <p>What is different here is the tip. An aeroplane looks along its own line of sight because what
 * matters is out in front of it at its own height; a tank's targets are on the ground it is standing
 * on, and a view laid flat along the barrel spends its top half on sky. {@code camera.tilt} rotates
 * the whole view down by a few degrees, which lifts the camera as it goes — the offset is measured
 * along the axes being looked down — so the vehicle is seen from above and there is ground on the
 * screen where the sky was.
 *
 * <p>It is the view that is tipped and not the crew: the turret is still laid on where the crew are
 * looking, which the mouse moves and this does not touch. What that costs is that the middle of the
 * screen stops being the line of the gun, which is why the gun's own mark is drawn out in the world
 * where the round will land rather than at the crosshair — see {@link GroundVehicleHud}.
 *
 * <p>First person is the opposite case and gets the opposite treatment, the same as an aircraft's:
 * the camera is placed outright at {@code camera.cockpit}, in the vehicle's own axes, so the file
 * decides which hatch the crew are looking out of rather than wherever vanilla happens to put a
 * rider's eyes — which for a seat written at the floor of the hull is a view of the inside of the
 * armour. The point rides the <em>turret</em>, swung about the ring with the gun, because that is
 * where a tank's hatches are: lay the gun abeam and the view comes round over the side of the hull
 * with it, as it does from a real cupola. Only the place is taken from the machine; the direction
 * is still the crew's, because that is what lays the turret in the first place.
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class GroundVehicleCameraHandler {
    /**
     * How near the vertical the tipped view may look, in degrees. The same shade short of straight
     * up that vanilla puts on every other view in the game: past it a bearing stops meaning anything
     * and the picture slews.
     */
    private static final float POLE = 89.5F;

    @SubscribeEvent
    public static void onCalculateCameraDistance(CalculateDetachedCameraDistanceEvent event) {
        if (riddenVehicle(event.getCamera()) != null) {
            // The camera is placed outright below, so vanilla should leave it on the crew's eyes.
            event.setDistance(0.0F);
        }
    }

    /**
     * Tips the chase view down by the vehicle's own {@code camera.tilt}.
     *
     * <p>This has to go through the event rather than being done alongside the placement, because
     * the event is the only thing that can set the camera's angles — and it is the right order in
     * any case: the tip is applied first, and the offset is then measured along the axes it leaves
     * behind, so the camera climbs into the tip instead of hanging below it.
     */
    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Camera camera = event.getCamera();
        GroundVehicleEntity vehicle = riddenVehicle(camera);

        if (vehicle == null || !camera.isDetached()) {
            return;
        }

        float tilt = vehicle.getStats().camera().tilt();

        if (tilt == 0.0F) {
            return;
        }

        // Vanilla turns the reversed view upside down after this event has run — it negates the
        // elevation to look back at the rider — so the tip has to go in negated to come out of that
        // still pointing down.
        boolean reversed = Minecraft.getInstance().options.getCameraType() == CameraType.THIRD_PERSON_FRONT;

        event.setPitch(Mth.clamp(event.getPitch() + (reversed ? -tilt : tilt), -POLE, POLE));
    }

    /**
     * Called from {@link com.ashvehicles.mixin.CameraMixin} once vanilla has finished placing the
     * camera, which is the first moment a new position will actually stick.
     */
    public static void placeCamera(Camera camera, Entity viewer, boolean detached, float partialTick) {
        if (!(viewer.getVehicle() instanceof GroundVehicleEntity vehicle)) {
            return;
        }

        if (!detached) {
            // The eye of the seat this one is actually in, carried by whatever that seat's eye is
            // bolted to: the commander's is in the turret roof and comes round with the gun, the
            // driver's is in the glacis and does not, and a rifleman in the back of a CV90 has no
            // business seeing out of either. Which is which is the machine's file's to say; where
            // it does not, every seat gets what the machine has always given them.
            camera.setPosition(vehicle.eyeOf(viewer, partialTick));

            return;
        }

        ChaseCamera.place(camera, vehicle, vehicle.getStats().camera().pos(), partialTick);
    }

    /** The vehicle the camera's owner is riding, or null if they are not aboard one. */
    private static GroundVehicleEntity riddenVehicle(Camera camera) {
        Entity viewer = camera.getEntity();

        return viewer != null && viewer.getVehicle() instanceof GroundVehicleEntity vehicle ? vehicle : null;
    }

    private GroundVehicleCameraHandler() {
    }
}
