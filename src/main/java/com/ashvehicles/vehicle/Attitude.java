package com.ashvehicles.vehicle;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Which way an aircraft is pointing, held as a rotation rather than as three angles.
 *
 * <p>Heading, elevation and bank are how Minecraft describes a mob's facing, and they are fine for
 * something that stays the right way up. An aeroplane does not: at the top of a loop it is inverted
 * and pointing backwards, and going over the vertical the heading jumps by a hundred and eighty
 * degrees while the elevation folds back on itself. A rotation has no such seam, so an aircraft
 * built on one can fly through the vertical and come out the other side without noticing.
 *
 * <p>The body frame is the one Minecraft uses for entities: +Z along the nose, +Y up through the
 * canopy, and therefore +X out to the left. Rotating on the right of the stored value applies the
 * rotation in that frame, which is what makes the controls act on the aircraft rather than on the
 * world.
 */
public final class Attitude {
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);
    private static final Vector3f NOSE = new Vector3f(0.0F, 0.0F, 1.0F);
    private static final Vector3f UP = new Vector3f(0.0F, 1.0F, 0.0F);
    private static final Vector3f LEFT = new Vector3f(1.0F, 0.0F, 0.0F);

    private Attitude() {
    }

    /** The rotation that points an aircraft along a Minecraft heading and elevation, wings level. */
    public static Quaternionf of(float yRot, float xRot) {
        return new Quaternionf().rotateY(-yRot * DEG_TO_RAD).rotateX(xRot * DEG_TO_RAD);
    }

    /**
     * Turns the aircraft about its own axes.
     *
     * @param rollRate degrees about the nose, positive to the right
     * @param pitchRate degrees about the wings, positive nose up
     * @param yawRate degrees about the aircraft's vertical, positive nose right
     */
    public static Quaternionf rotate(Quaternionf attitude, float rollRate, float pitchRate, float yawRate) {
        return attitude.rotateY(-yawRate * DEG_TO_RAD)
                .rotateX(-pitchRate * DEG_TO_RAD)
                .rotateZ(rollRate * DEG_TO_RAD)
                .normalize();
    }

    public static Vec3 nose(Quaternionf attitude) {
        return toVec3(attitude.transform(new Vector3f(NOSE)));
    }

    public static Vec3 up(Quaternionf attitude) {
        return toVec3(attitude.transform(new Vector3f(UP)));
    }

    /** Out along the right wing. */
    public static Vec3 right(Quaternionf attitude) {
        return toVec3(attitude.transform(new Vector3f(LEFT))).scale(-1.0);
    }

    /** The compass bearing the nose is on, in Minecraft's terms. */
    public static float heading(Quaternionf attitude) {
        Vec3 nose = nose(attitude);

        return (float) (Mth.atan2(-nose.x, nose.z) * (180.0 / Math.PI));
    }

    /** How far below the horizon the nose is, in Minecraft's terms: positive is nose down. */
    public static float elevation(Quaternionf attitude) {
        Vec3 nose = nose(attitude);

        return (float) (-Math.asin(Mth.clamp(nose.y, -1.0, 1.0)) * (180.0 / Math.PI));
    }

    /**
     * The bank angle, positive with the right wing down: how far the aircraft is rolled about its
     * own nose, measured against which way is up in the world.
     */
    public static float bank(Quaternionf attitude) {
        Vec3 nose = nose(attitude);
        Vec3 up = up(attitude);
        // Where the wings would be if the aircraft were not rolled at all.
        Vec3 levelRight = nose.cross(new Vec3(0.0, 1.0, 0.0));

        if (levelRight.lengthSqr() < 1.0E-6) {
            // Pointing straight up or down: every bank angle is the same picture, so call it level.
            return 0.0F;
        }

        levelRight = levelRight.normalize();
        Vec3 levelUp = levelRight.cross(nose);

        return (float) (Mth.atan2(up.dot(levelRight), up.dot(levelUp)) * (180.0 / Math.PI));
    }

    /**
     * The bank of the aircraft as seen by someone looking along {@code sight}: how far the horizon
     * is tipped over in their view. Used for the cockpit camera, where the pilot's head is bolted to
     * the airframe but their eyes can be pointing anywhere.
     */
    public static float bankAlong(Quaternionf attitude, Vec3 sight) {
        Vec3 up = up(attitude);
        Vec3 worldUp = new Vec3(0.0, 1.0, 0.0);
        Vec3 screenRight = sight.cross(worldUp);

        if (screenRight.lengthSqr() < 1.0E-6) {
            return bank(attitude);
        }

        screenRight = screenRight.normalize();
        Vec3 screenUp = screenRight.cross(sight).normalize();

        return (float) (Mth.atan2(up.dot(screenRight), up.dot(screenUp)) * (180.0 / Math.PI));
    }

    /**
     * A rotation written as the axis it turns about, scaled by how far it turns, in radians.
     *
     * <p>A turn <em>rate</em> has to be something that can be halved, added to, and decayed towards
     * nothing, and a quaternion is none of those things. This is the same rotation in the one form
     * that is: three numbers in the aircraft's own frame, which is exactly the body rate a pilot
     * reads off a turn indicator — roll about the nose, pitch about the wings, yaw about the fin.
     * {@link #rotationOf} takes it back the other way, and the two are exact inverses under half a
     * turn.
     *
     * <p>The short way round is always taken, so a rotation of three hundred and fifty degrees comes
     * back as ten the other way rather than as most of a turn. Anything measuring one tick of an
     * aircraft's turn is asking about the short way round by definition.
     *
     * @param rotation a normalised rotation
     */
    public static Vector3f rotationVector(Quaternionfc rotation) {
        float w = rotation.w();
        float x = rotation.x();
        float y = rotation.y();
        float z = rotation.z();

        // A quaternion and its negation are the same rotation; only one of the two is the short way.
        if (w < 0.0F) {
            w = -w;
            x = -x;
            y = -y;
            z = -z;
        }

        float sine = (float) Math.sqrt(Math.max(0.0F, 1.0F - w * w));

        // Below this the axis is lost in the rounding, and the half-angle vector is the answer to
        // every digit that survives anyway.
        if (sine < 1.0E-6F) {
            return new Vector3f(x * 2.0F, y * 2.0F, z * 2.0F);
        }

        float scale = 2.0F * (float) Math.acos(Mth.clamp(w, -1.0F, 1.0F)) / sine;

        return new Vector3f(x * scale, y * scale, z * scale);
    }

    /** The rotation that a scaled axis, as {@link #rotationVector} writes one, describes. */
    public static Quaternionf rotationOf(Vector3fc rotation) {
        float angle = rotation.length();

        if (angle < 1.0E-6F) {
            return new Quaternionf();
        }

        float half = angle * 0.5F;
        float scale = (float) Math.sin(half) / angle;

        return new Quaternionf(rotation.x() * scale, rotation.y() * scale, rotation.z() * scale,
                (float) Math.cos(half));
    }

    /**
     * Turns an offset in the aircraft's own axes (x right, y up, z nose) into a world offset.
     *
     * <p>One rotation rather than three. The three axes this used to build are the rotation applied
     * to the three unit vectors, and a rotation is linear, so rotating the whole offset at once is
     * the same answer for a third of the arithmetic and a tenth of the rubbish. That is worth having
     * here: every collision box and every pylon of every aircraft is placed through this on every
     * tick, and each call was three quaternion transforms and a dozen throwaway vectors.
     *
     * <p>The x is negated on the way in because the body frame's +X runs out along the <em>left</em>
     * wing — see {@link #LEFT} and {@link #right} — while an offset's x is measured to the right.
     */
    public static Vec3 toWorld(Quaternionf attitude, Vec3 offset) {
        Vector3f world = attitude.transform(
                new Vector3f((float) -offset.x, (float) offset.y, (float) offset.z));

        return toVec3(world);
    }

    private static Vec3 toVec3(Vector3f vector) {
        return new Vec3(vector.x(), vector.y(), vector.z());
    }
}
