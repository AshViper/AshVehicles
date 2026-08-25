package com.ashvehicles.client;

import javax.annotation.Nullable;

import com.ashvehicles.vehicle.GroundVehicleDefinition;
import com.ashvehicles.vehicle.VehicleShape;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Where a machine's boxes are lying in its own axes, for the instruments that draw it flat.
 *
 * <p>The mod already has this arithmetic twice over — {@code GroundVehicleEntity} does it every tick
 * to put the real boxes in the world, and {@code VehicleShapeRenderer} does it again to outline them
 * — but both of those want the answer <em>in the world</em>, with the hull's attitude on the end of
 * it. A picture of a tank drawn on the screen wants the step before that: where the turret has
 * carried a box to within the hull, with the hull itself held still. So it is here once, and the two
 * instruments that draw a machine — the plan view in the corner and the readout that says where a
 * round landed — share it rather than each keeping a copy of the signs.
 *
 * <p><b>The axes are the file's own</b>: x to the right, y up, z over the bow, exactly as a
 * {@code hitbox} block is written. That is not the frame the rotations work in — inside a quaternion
 * +X runs out to the <em>left</em>, which is why {@link com.ashvehicles.vehicle.Attitude#toWorld}
 * negates it — so {@link #turn} is the one place that crosses between the two, and everything else
 * here stays in the axes the file uses.
 */
final class Silhouette {
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);
    /**
     * How steeply a machine may be looked at, in radians.
     *
     * <p>A round that came down almost vertically would otherwise be drawn against a plan view whose
     * "up" is whichever way the arithmetic happened to fall, and a picture that spins on the sign of
     * a rounding is worse than one drawn from slightly the wrong angle.
     */
    private static final double STEEPEST = Math.toRadians(55.0);

    private Silhouette() {
    }

    /**
     * Where a box sits in the machine's own axes, with the turret where it is now.
     *
     * <p>The same three cases {@code GroundVehicleEntity.mountOffset} has, and for the same reason: a
     * box on the hull is where the file says it is, one on the turret is swung about the ring, and
     * one on the gun is rocked about the trunnion first and then swung round with the turret.
     * {@code stats} is null for an aircraft, which has neither and whose every box is on the hull.
     */
    static Vec3 centre(VehicleShape.Box box, @Nullable GroundVehicleDefinition stats,
            float traverse, float pitch) {
        if (stats == null || box.mount() == VehicleShape.Mount.HULL) {
            return box.offset();
        }

        Vec3 at = box.mount() == VehicleShape.Mount.GUN
                ? onGun(box.offset(), stats.armament().trunnion(), pitch)
                : box.offset();

        return onTurret(at, stats.turret().ring(), traverse);
    }

    /** A point swung about the turret ring by however far the turret is traversed. */
    private static Vec3 onTurret(Vec3 offset, Vec3 ring, float traverse) {
        Vec3 local = offset.subtract(ring);
        float radians = traverse * DEG_TO_RAD;
        double sin = Mth.sin(radians);
        double cos = Mth.cos(radians);

        return ring.add(new Vec3(
                local.x * cos + local.z * sin,
                local.y,
                -local.x * sin + local.z * cos));
    }

    /** A point rocked about the trunnion by however far the gun is laid up or down. */
    private static Vec3 onGun(Vec3 offset, Vec3 trunnion, float pitch) {
        Vec3 local = offset.subtract(trunnion);
        float radians = -pitch * DEG_TO_RAD;
        double sin = Mth.sin(radians);
        double cos = Mth.cos(radians);

        return trunnion.add(new Vec3(
                local.x,
                local.y * cos - local.z * sin,
                local.y * sin + local.z * cos));
    }

    /**
     * How a box is lying within the machine: the turret's traverse, the gun's elevation if it rides
     * the barrel, and then the box's own angle inside whatever carries it.
     *
     * <p>What comes back turns vectors in the quaternions' own frame. Hand it to {@link #turn}
     * rather than to {@code transform} and the crossing is done for you.
     */
    static Quaternionf rotation(VehicleShape.Box box, @Nullable GroundVehicleDefinition stats,
            float traverse, float pitch) {
        Quaternionf rotation = new Quaternionf();

        if (stats != null && box.mount() != VehicleShape.Mount.HULL) {
            rotation.rotateY(-traverse * DEG_TO_RAD);
        }

        if (stats != null && box.mount() == VehicleShape.Mount.GUN) {
            rotation.rotateX(-pitch * DEG_TO_RAD);
        }

        return rotation.mul(box.orientation());
    }

    /**
     * A vector turned by one of those rotations, with both ends of it in the file's axes.
     *
     * <p>The x is negated on the way in and again on the way out because the quaternions work in a
     * frame whose +X is the left-hand side, and everything else here counts x to the right.
     */
    static Vec3 turn(Quaternionf rotation, Vec3 offset) {
        Vector3f turned = rotation.transform(
                new Vector3f((float) -offset.x, (float) offset.y, (float) offset.z));

        return new Vec3(-turned.x, turned.y, turned.z);
    }

    /**
     * Which way is right and which way is up, for somebody looking along a line.
     *
     * <p>Both in the machine's own axes, so a point on it is put on the screen by asking how far it
     * lies along each of them. That is the whole of the projection: no perspective, because an
     * instrument the size of a postage stamp gains nothing from it and a silhouette drawn flat is
     * the one everybody already knows how to read.
     */
    record View(Vec3 right, Vec3 up) {
        /**
         * The view of somebody standing behind a line and looking along it — which, for the line a
         * round came in on, is the machine as the gunner saw it.
         */
        static View along(Vec3 line) {
            double flat = Math.sqrt(line.x * line.x + line.z * line.z);
            Vec3 look;

            if (flat < 1.0E-4) {
                // Straight up or straight down: there is no bearing to take, so it is drawn from
                // dead astern rather than from an angle picked out of the rounding.
                look = new Vec3(0.0, 0.0, 1.0);
            } else {
                double climb = Mth.clamp(Math.atan2(line.y, flat), -STEEPEST, STEEPEST);
                double along = Math.cos(climb);

                look = new Vec3(line.x / flat * along, Math.sin(climb), line.z / flat * along);
            }

            Vec3 right = new Vec3(0.0, 1.0, 0.0).cross(look).normalize();

            return new View(right, look.cross(right).normalize());
        }

        /**
         * The line being looked along, back out of the two axes that were built from it.
         *
         * <p>Kept this way round rather than held as a third field because it is the clamped line
         * that matters — the one the picture was actually built on — and reading it back off the two
         * axes cannot disagree with them.
         */
        Vec3 look() {
            return this.right.cross(this.up);
        }

        /** How far a point lies to the right of the middle of the picture. */
        double across(Vec3 point) {
            return point.dot(this.right);
        }

        /** How far up it. */
        double aloft(Vec3 point) {
            return point.dot(this.up);
        }
    }
}
