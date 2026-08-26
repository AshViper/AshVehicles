package com.ashvehicles.client.model;

import org.joml.Vector3f;

import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.vehicle.GroundVehicleDefinition;
import com.ashvehicles.vehicle.Ride;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

/**
 * Draws any ground vehicle. Nothing here is specific to one: the geometry, texture and animation
 * files are found by the vehicle's own name, and which bone is a turret or a road wheel comes from
 * its file.
 *
 * <p>Everything that moves is driven from code in {@link #setCustomAnimations}, which GeckoLib calls
 * once per frame after the animation controllers have run. There is nothing here that is a sequence
 * rather than an angle: a turret is somewhere, a gun is at some elevation, and a wheel has gone
 * round so far. Hatches, which are a sequence, will belong in the vehicle's animation file.
 *
 * <p>A bone the vehicle does not name, or names wrongly, is skipped rather than crashing; it just
 * sits rigid.
 */
public class GroundVehicleModel extends VehicleGeoModel<GroundVehicleEntity> {
    /**
     * Which way round each part is turned, in the <em>vehicle's</em> axes.
     *
     * <p>Which way round that is in any one model's own axes is nobody's business here:
     * {@link #turnAboutX}, {@link #turnAboutY} and {@link #slideAlongZ} work that out per bone from
     * the geometry, so the same figure drives a hull built facing forwards and one built facing
     * backwards and turned round on its root bone. See {@link VehicleGeoModel#turnAboutX} for how,
     * and for why a per-vehicle flag would not have been enough.
     *
     * <p>What is left to settle is only the frame those three work in, which is GeckoLib's: the
     * model faces its own −Z and its +X is the vehicle's <em>left</em>. So bringing the turret right
     * means turning the nose from −Z towards −X, a positive turn about Y, so negative here; a road
     * wheel rolling the vehicle forwards carries its top towards −Z, a negative turn about X, so
     * negative here; and the barrel runs back towards +Z, so positive here. Raising the gun lifts
     * the muzzle from −Z towards +Y, a positive turn about X.
     *
     * <p>Flip one of these if a part goes the wrong way <em>on every vehicle at once</em>. One part
     * wrong on one vehicle is not this; that is a bone the geometry has square to the axis it is
     * being turned about, and it is fixed in Blockbench.
     */
    private static final float TURRET_SIGN = -1.0F;
    private static final float GUN_SIGN = 1.0F;
    private static final float WHEEL_SIGN = -1.0F;
    /**
     * Which way a steered wheel turns. The turret's sign, and for the turret's reason: both are a
     * turn about the vehicle's vertical, and a wheel pointing right is the same rotation as a gun
     * laid right.
     */
    private static final float STEER_SIGN = -1.0F;
    private static final float RECOIL_SIGN = 1.0F;

    @Override
    protected ResourceLocation idOf(GroundVehicleEntity animatable) {
        return animatable.getVehicleId();
    }

    @Override
    public void setCustomAnimations(GroundVehicleEntity animatable, long instanceId,
            AnimationState<GroundVehicleEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);

        applyPose(this, Setup.of(animatable.getStats()),
                Pose.of(animatable, animationState.getPartialTick()));
    }

    /**
     * The figures out of a vehicle's file that decide how its model is posed, taken from the file
     * once and carried about together.
     *
     * <p>They come from four different blocks of the file and are wanted in one place, and a ghost —
     * which has no vehicle left to ask — has to carry the lot along with its photograph. Gathered
     * here rather than passed one by one, which is how {@link #applyPose} came to be taking four
     * loose floats behind the thing they all describe.
     *
     * @param recoilTravel how far the barrel runs back when the gun fires, in blocks
     * @param wheelTravel how far a road wheel moves on its springs, in blocks. Nothing pins the
     *                    running gear to the body, which is a vehicle with no suspension to draw
     */
    public record Setup(VehicleChassis.Model model, float recoilTravel, float wheelTravel) {
        public static Setup of(GroundVehicleDefinition stats) {
            return new Setup(stats.model(), stats.armament().recoil(), stats.suspension().travel());
        }
    }

    /**
     * Everything a ground vehicle does with itself, taken from the vehicle once and then applied to
     * any model of it.
     *
     * @param turretYaw where the turret is pointing, in degrees from dead ahead of the hull
     * @param gunPitch how far the gun is elevated above the turret roof line, in degrees
     * @param wheelAngle how far the road wheels have gone round, in degrees
     * @param steerAngle how far the steered wheels are turned, in degrees, positive to the right
     * @param recoil how far the barrel has run back, from nothing to one
     * @param ride how far the body has moved on its springs, which the running gear is put back down
     *             by so that it stays on the ground while the hull above it moves
     */
    public record Pose(float turretYaw, float gunPitch, float wheelAngle, float steerAngle, float recoil,
            Ride ride) {
        public static Pose of(GroundVehicleEntity vehicle, float partialTick) {
            return new Pose(
                    vehicle.getTurretYaw(partialTick),
                    vehicle.getGunPitch(partialTick),
                    vehicle.getWheelAngle(partialTick),
                    vehicle.getSteerAngle(partialTick),
                    vehicle.getRecoil(partialTick),
                    vehicle.getRide(partialTick));
        }

        /**
         * The pose between two snapshots, for a ghost, which has no vehicle to ask.
         *
         * <p>Between two rather than from the newest, because that is what the game draws for the
         * tank standing next to the ghost: what is on the screen at any moment is the tick before
         * last blended into the last one. A ghost posed from the newest alone would run a tick ahead
         * of it and jump once a tick besides.
         *
         * <p>The turret is wound the short way round. It is an angle that wraps, and a turret
         * crossing dead astern would otherwise spin the long way home once a tick. The road wheels
         * are not wrapped: the figure is how far the tracks have run in total, so it grows without
         * bound and the plain blend between two of them is the distance covered in between.
         *
         * @param previous the pose at the end of the tick before last
         * @param now the pose at the end of the last tick
         */
        public static Pose between(Pose previous, Pose now, float partialTick) {
            return new Pose(
                    Mth.rotLerp(partialTick, previous.turretYaw(), now.turretYaw()),
                    Mth.lerp(partialTick, previous.gunPitch(), now.gunPitch()),
                    Mth.lerp(partialTick, previous.wheelAngle(), now.wheelAngle()),
                    Mth.lerp(partialTick, previous.steerAngle(), now.steerAngle()),
                    Mth.lerp(partialTick, previous.recoil(), now.recoil()),
                    Ride.between(previous.ride(), now.ride(), partialTick));
        }
    }

    /** Poses any model of a ground vehicle. */
    public static void applyPose(GeoModel<?> model, Setup figures, Pose pose) {
        VehicleChassis.Model setup = figures.model();
        String turretBone = setup.bone(GroundVehicleDefinition.Bone.TURRET);

        turnAboutY(model, turretBone, TURRET_SIGN * pose.turretYaw());

        // The gun and the mantlet elevate together, and both are children of the turret in the
        // geometry, so neither of them knows or needs to know which way the turret is pointing.
        turnAboutX(model, setup.bone(GroundVehicleDefinition.Bone.GUN), GUN_SIGN * pose.gunPitch());
        turnAboutX(model, setup.bone(GroundVehicleDefinition.Bone.MANTLET), GUN_SIGN * pose.gunPitch());
        // And the machine gun with them, which is what makes it a coaxial: a barrel clamped to the
        // gun looks where the gun looks. Every model here hangs it off the turret rather than off
        // the gun bone, so it is elevated here rather than coming round for nothing — and it has to
        // be, or the rounds would leave a barrel lying level while going where the cannon points.
        // See GroundVehicleDefinition.Coaxial, which is where they come from.
        turnAboutX(model, setup.bone(GroundVehicleDefinition.Bone.MG), GUN_SIGN * pose.gunPitch());

        // The barrel runs back. Slid rather than rotated, and slid in the turret's axes rather than
        // the gun's, since a bone's offset is applied before its own elevation. The travel is
        // written in blocks like everything else in these files and the bone wants model units,
        // which are sixteen to the block before the vehicle's own scale is applied.
        float travel = RECOIL_SIGN * pose.recoil() * figures.recoilTravel() * BakedGeometry.UNITS
                / Math.max(setup.scale(), 0.01F);
        slideAlongZ(model, setup.bone(GroundVehicleDefinition.Bone.GUN), travel);

        // Any further mounts laid on the same target as the main one — a warship's second turret,
        // slaved to the same fire control. Each is swung about its own ring, so a pair of guns set
        // apart along the deck comes round together to the one aim rather than about a shared pivot.
        for (String turret : setup.slavedTurrets()) {
            turnAboutY(model, turret, TURRET_SIGN * pose.turretYaw());
            turnAboutX(model, turret, GUN_SIGN * pose.gunPitch());
        }

        // All of them at once, at the same angle. Which wheel is which does not matter: they are the
        // same size and they are all driven by the same track. Which way round each one was built
        // does not matter either, and on at least one of these vehicles it differs from wheel to
        // wheel down the same side.
        for (String wheel : setup.roadWheels()) {
            turnAboutX(model, wheel, WHEEL_SIGN * pose.wheelAngle());
            plant(model, wheel, pose.ride(), setup.scale(), figures.wheelTravel());
        }

        // And the ones that steer, turned about their own upright. A wheel is usually in both lists,
        // and both rotations land on the one bone -- which is exactly right and only because of the
        // order GeckoLib applies them in. It builds a bone matrix as Z, then Y, then X, so the X
        // comes out innermost: the wheel rolls about its axle first and the whole of it is then swung
        // about the kingpin. The other way round the wheel would roll about a vertical it no longer
        // has, and a steered wheel at speed would wobble instead of turning.
        for (String wheel : setup.steeredWheels()) {
            turnAboutY(model, wheel, STEER_SIGN * pose.steerAngle());
        }
    }


    /**
     * Puts one road wheel back on the ground after the body above it has moved.
     *
     * <p>The suspension is drawn by rocking the <em>whole</em> model on the pose stack — see
     * {@code GroundVehicleRenderer.applyRotations} — which is the only way to do it on models that
     * have no one bone for the hull: on the vehicles here the hull, the turret, the stowage and the
     * wheels are all children of the same root, so there is nothing to move that is not everything.
     * That carries the running gear up and down with the body, which is precisely backwards. So each
     * wheel is moved down by exactly what the body's movement lifted it, and the two cancel: the
     * wheels stay where they were on the ground and the hull moves above them, which is what a
     * suspension looks like.
     *
     * <p>Held inside the wheel's own travel. Past that the wheel is against its bump stops and the
     * whole vehicle really does move — which is right, and is what makes a hard landing throw a tank
     * about rather than being swallowed silently by its springs.
     *
     * <p>Written on every frame, including the frames where the body has not moved. A bone is one
     * object shared by every vehicle of the kind on the screen and by every pass over each of them,
     * so a wheel left displaced because there was nothing to do this frame is a wheel displaced for
     * the next vehicle drawn — which is the same reason the run of track puts its link back.
     */
    private static void plant(GeoModel<?> model, String bone, Ride ride, float scale, float travel) {
        if (bone.isEmpty()) {
            return;
        }

        model.getBone(bone).ifPresent(found -> {
            float lift = 0.0F;

            if (travel > 0.0F && !ride.isLevel()) {
                Vector3f centre = BakedGeometry.centreOf(found);
                float stop = travel / Math.max(scale, 0.01F);

                lift = Mth.clamp(ride.liftOf(centre.x(), centre.z(), scale), -stop, stop);
            }

            slideAlongY(found, -lift * BakedGeometry.UNITS);
        });
    }
}
