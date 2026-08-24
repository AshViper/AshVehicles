package com.ashvehicles.client.model;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.AshVehicles;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.entity.AircraftEntity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

/**
 * Draws any aircraft. Nothing here is specific to one: the geometry, texture and animation files are
 * found by the aircraft's own name, and which bone is an aileron or a gear leg comes from its file.
 *
 * <p>Everything that moves except the undercarriage is driven from code in
 * {@link #setCustomAnimations}, which GeckoLib calls once per frame after the animation controllers
 * have run, so anything set here overrides them. The gear is the other way round: it is played from
 * the aircraft's animation file, and this only touches it for an aircraft that has no such file.
 * See {@link AircraftAnimations}.
 *
 * <p>Control surfaces follow how fast the aircraft is actually rotating rather than the pilot's
 * keys: rotation is synced to every client, so other players see the surfaces move as well. A bone
 * an aircraft does not name, or names wrongly, is skipped rather than crashing; it just sits rigid.
 */
public class AircraftModel extends VehicleGeoModel<AircraftEntity> {
    /**
     * How far the nozzle swings on the model, in degrees.
     *
     * <p>Kept here rather than read from the aircraft's {@code vtol.max_angle}, which is a figure
     * about thrust rather than about geometry. What the physics does with ninety degrees is fixed;
     * what the model has to do to look like ninety degrees depends on how the nozzle was built, and
     * flipping the sign here is how a nozzle that swings the wrong way is fixed.
     */
    private static final float NOZZLE_TRAVEL = 90.0F;

    // Travel of each moving part, in degrees. Flip a sign here if a part swings the wrong way.
    private static final float ELEVATOR_TRAVEL = 20.0F;
    private static final float AILERON_TRAVEL = 20.0F;
    private static final float RUDDER_TRAVEL = 18.0F;
    private static final float FLAP_TRAVEL = 15.0F;
    private static final float GEAR_RETRACT_TRAVEL = 90.0F;
    private static final float GEAR_DOOR_TRAVEL = 85.0F;

    @Override
    protected ResourceLocation idOf(AircraftEntity animatable) {
        return animatable.getAircraftId();
    }

    /** The same file, for anything that needs to know what is in it before playing from it. */
    public static ResourceLocation animationFile(AircraftEntity animatable) {
        return animationFile(animatable.getAircraftId());
    }

    @Override
    public void setCustomAnimations(AircraftEntity animatable, long instanceId,
            AnimationState<AircraftEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);

        float partialTick = animationState.getPartialTick();
        applyPose(this, animatable.getStats().model(), Pose.of(animatable, partialTick));
    }

    /**
     * The bone rotations that make up everything that moves on an aircraft except what its
     * animation file plays. Taken from the aircraft once, then applied to any model of it — the
     * live one, or a ghost's copy drawn long after the aircraft itself has gone out of range.
     *
     * @param elevator elevator deflection, degrees
     * @param aileron aileron deflection, degrees; the right one gets the opposite sign
     * @param rudder rudder deflection, degrees
     * @param gear undercarriage, 0 up to 1 down
     * @param flaps flaps, 0 up to 1 down
     * @param rotor how far round the main rotor has turned, in degrees; 0 for anything without one
     * @param tailRotor the same for the tail rotor
     * @param rotorRate how far the main rotor turns in one tick, in degrees. Carried so that a pose
     *        taken at the end of a tick can be wound on to any moment within the next one: a rotor
     *        that turns most of a revolution per tick cannot be interpolated from two angles, and
     *        the angles are wrapped back to a circle in any case
     * @param tailRotorRate the same for the tail rotor, which turns several times faster again
     * @param sweepGear whether to swing the gear from here, for an aircraft with no gear cycle in
     *        its animation file to play it from
     */
    public record Pose(float elevator, float aileron, float rudder, float gear, float flaps, float nozzle,
            float rotor, float tailRotor, float rotorRate, float tailRotorRate, boolean sweepGear) {
        /** What the aircraft's surfaces are doing now. */
        public static Pose of(AircraftEntity aircraft, float partialTick) {
            // Pulling the nose up drops the tailplane's trailing edge, hence the negated pitch delta.
            return new Pose(
                    deflection(-aircraft.getPitchDelta(), aircraft.getPitchRate(), ELEVATOR_TRAVEL),
                    deflection(aircraft.getRollDelta(), aircraft.getRollRate(), AILERON_TRAVEL),
                    deflection(aircraft.getYawDelta(), aircraft.getYawRate(), RUDDER_TRAVEL),
                    aircraft.getGearProgress(partialTick),
                    aircraft.getFlapsProgress(partialTick),
                    aircraft.getVtolProgress(partialTick),
                    aircraft.getRotorAngle(partialTick),
                    aircraft.getTailRotorAngle(partialTick),
                    aircraft.getRotorAngle(1.0F) - aircraft.getRotorAngle(0.0F),
                    aircraft.getTailRotorAngle(1.0F) - aircraft.getTailRotorAngle(0.0F),
                    !AircraftAnimations.hasGearCycle(aircraft));
        }

        /**
         * The pose to draw at a moment between two ticks, from the pose taken at the end of each.
         *
         * <p>This is the aircraft's own interpolation, done from snapshots instead of from the
         * aircraft: what the game draws for an entity is always the tick before last blended into
         * the last one, and a ghost drawn any other way runs a tick ahead of the aeroplane beside
         * it. Each part is treated as the aircraft treats it. The undercarriage, the flaps and the
         * nozzle are travelling somewhere and are interpolated. The control surfaces follow how
         * fast the aircraft turned during a tick, which is one figure for the whole of that tick
         * and is not interpolated by the aircraft either. The rotors are wound on from the rate
         * rather than blended between two angles, because they are past a full turn a tick and the
         * angle is wrapped.
         *
         * @param previous the pose at the end of the tick before last
         * @param now the pose at the end of the last tick
         */
        public static Pose between(Pose previous, Pose now, float partialTick) {
            float wind = partialTick - 1.0F;

            return new Pose(
                    now.elevator(), now.aileron(), now.rudder(),
                    Mth.lerp(partialTick, previous.gear(), now.gear()),
                    Mth.lerp(partialTick, previous.flaps(), now.flaps()),
                    Mth.lerp(partialTick, previous.nozzle(), now.nozzle()),
                    now.rotor() + now.rotorRate() * wind,
                    now.tailRotor() + now.tailRotorRate() * wind,
                    now.rotorRate(), now.tailRotorRate(), now.sweepGear());
        }
    }

    /**
     * Poses any model of an aircraft. Control surfaces follow the rates the aircraft is turning
     * at; the undercarriage belongs to the animation file, and is only swung from here — in one
     * straight sweep, legs up and back, bay doors open whenever the legs are not stowed — for an
     * aircraft that has no gear cycle to play. It is not what an undercarriage looks like, but it
     * is a great deal better than a leg through a shut door.
     */
    public static void applyPose(GeoModel<?> model, VehicleChassis.Model setup, Pose pose) {
        rotateX(model, setup, AircraftDefinition.Bone.ELEVATOR_LEFT, pose.elevator());
        rotateX(model, setup, AircraftDefinition.Bone.ELEVATOR_RIGHT, pose.elevator());
        rotateX(model, setup, AircraftDefinition.Bone.AILERON_LEFT, pose.aileron());
        rotateX(model, setup, AircraftDefinition.Bone.AILERON_RIGHT, -pose.aileron());
        rotateY(model, setup, AircraftDefinition.Bone.RUDDER, pose.rudder());
        rotateY(model, setup, AircraftDefinition.Bone.RUDDER_LEFT, pose.rudder());
        rotateY(model, setup, AircraftDefinition.Bone.RUDDER_RIGHT, pose.rudder());

        if (pose.sweepGear()) {
            float gear = pose.gear();
            float retracted = 1.0F - gear;

            rotateX(model, setup, AircraftDefinition.Bone.NOSE_GEAR, retracted * GEAR_RETRACT_TRAVEL);
            rotateX(model, setup, AircraftDefinition.Bone.LEFT_GEAR, retracted * GEAR_RETRACT_TRAVEL);
            rotateX(model, setup, AircraftDefinition.Bone.RIGHT_GEAR, retracted * GEAR_RETRACT_TRAVEL);
            rotateZ(model, setup, AircraftDefinition.Bone.NOSE_GEAR_DOOR, gear * GEAR_DOOR_TRAVEL);
            rotateZ(model, setup, AircraftDefinition.Bone.LEFT_GEAR_DOOR, gear * GEAR_DOOR_TRAVEL);
            rotateZ(model, setup, AircraftDefinition.Bone.RIGHT_GEAR_DOOR, -gear * GEAR_DOOR_TRAVEL);
        }

        rotateX(model, setup, AircraftDefinition.Bone.FLAP_LEFT, pose.flaps() * FLAP_TRAVEL);
        rotateX(model, setup, AircraftDefinition.Bone.FLAP_RIGHT, pose.flaps() * FLAP_TRAVEL);

        // The nozzle swings the whole way down rather than a few degrees like a control surface, and
        // it is posed from here rather than animated because it is one angle rather than a sequence:
        // the aircraft already knows how far through the conversion it is, and the doors that open
        // with it are the animation file's business. See AircraftAnimations.
        rotateX(model, setup, AircraftDefinition.Bone.NOZZLE, pose.nozzle() * NOZZLE_TRAVEL);

        // The rotors, which are the same idea taken all the way round. Posed from here for the same
        // reason as the nozzle and one more: how fast a rotor turns is a number in the aircraft's
        // file, and an animation file would have to be retimed by hand every time that number moved.
        // The main rotor turns about its mast, the tail rotor about the shaft it is on, and both are
        // an angle the aircraft has already worked out.
        rotateY(model, setup, AircraftDefinition.Bone.ROTOR, pose.rotor());
        rotateX(model, setup, AircraftDefinition.Bone.TAIL_ROTOR, pose.tailRotor());
    }

    private static void rotateX(GeoModel<?> model, VehicleChassis.Model setup, String role, float degrees) {
        rotateX(model, setup.bone(role), degrees);
    }

    private static void rotateY(GeoModel<?> model, VehicleChassis.Model setup, String role, float degrees) {
        rotateY(model, setup.bone(role), degrees);
    }

    private static void rotateZ(GeoModel<?> model, VehicleChassis.Model setup, String role, float degrees) {
        rotateZ(model, setup.bone(role), degrees);
    }

    /** Maps a rotation rate onto a control surface: full travel once the aircraft turns at its limit. */
    private static float deflection(float ratePerTick, float maxRatePerTick, float travelDegrees) {
        if (maxRatePerTick <= 0.0F) {
            return 0.0F;
        }

        return Mth.clamp(ratePerTick / maxRatePerTick, -1.0F, 1.0F) * travelDegrees;
    }

}
