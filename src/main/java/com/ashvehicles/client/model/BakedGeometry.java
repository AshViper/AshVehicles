package com.ashvehicles.client.model;

import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import software.bernie.geckolib.animation.state.BoneSnapshot;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;

/**
 * Reading a baked model as the artist left it: where a bone's geometry actually is, and the matrix
 * that takes its own axes into the model's.
 *
 * <p>Everything here is about the model's <em>rest</em> pose rather than the frame being drawn. A
 * road wheel that is turning is a road wheel in the same place; a turret that has been slewed is
 * still a turret at the same height. What the callers want to know is where the artist put a part,
 * and that does not change from frame to frame — so it is worked out from the initial snapshots and
 * not from whatever the animation has the bone doing.
 *
 * <p>The transforms are laid out exactly as GeckoLib lays them out when it draws a bone — see
 * {@code RenderUtil.prepMatrixForBone} — so a point worked out here is the point the pose stack will
 * put that bone's geometry at. That equivalence is the whole value of this class: the run of track
 * and the suspension both have to place things in the same space the model is drawn in, and
 * rederiving the order from the geometry files by hand is how the two would end up disagreeing.
 */
public final class BakedGeometry {
    /** Model units to the block, which is what everything in a geometry file is written in. */
    public static final float UNITS = 16.0F;

    /**
     * How far each bone's own geometry reaches in the model's axes, and where it turns, against the
     * bone they were worked out from. Absent from {@link #REACHES} means a bone with no geometry of
     * its own, which is a real answer and is kept as one.
     *
     *
     * <p>Weakly and by the bone object itself, which is what makes a resource reload correct for
     * free: GeckoLib bakes new bones out of the reloaded geometry, the new bone is not this one, and
     * the answer is worked out again from the geometry actually being drawn. Held without locking
     * because every caller is the render thread.
     */
    private static final Map<GeoBone, Optional<Bounds>> REACHES = new WeakHashMap<>();
    private static final Map<GeoBone, Vector3f> PIVOTS = new WeakHashMap<>();

    private BakedGeometry() {
    }

    /**
     * Where the middle of a bone's own geometry sits in the model's axes, in blocks.
     *
     * <p>The geometry rather than the pivot. The two are usually close on a road wheel, whose pivot
     * is its axle, and they are not on a bone built off to one side of the point it turns about — and
     * what a caller placing something against a part wants is the part.
     *
     * <p>A bone with no cubes of its own falls back to its pivot, which is the only thing such a
     * bone has to say about where it is.
     */
    public static Vector3f centreOf(GeoBone bone) {
        return reachOf(bone).map(Bounds::centre).orElseGet(() -> new Vector3f(pivotOf(bone)));
    }

    /**
     * How far a bone's own geometry reaches, in the model's axes and in blocks, or empty for a bone
     * that has none — a bare parent hung there to carry its children.
     *
     * <p>Its own and not its children's. What a caller wants to know from this is where the part
     * itself is, and a turret's box is the turret rather than the turret plus a barrel out over the
     * bow.
     */
    public static Optional<Bounds> reachOf(GeoBone bone) {
        return REACHES.computeIfAbsent(bone, found -> Optional.ofNullable(bounds(found, toRoot(found))));
    }

    /**
     * The point a bone turns about, in the model's axes and in blocks.
     *
     * <p>The bone's pivot is where it is in its <em>own</em> axes as well as in its parent's — the
     * rotation and the scale are both applied about it, so both leave it where it was — which is why
     * carrying it up with {@link #toRoot} is the whole of the answer.
     */
    public static Vector3f pivotOf(GeoBone bone) {
        return PIVOTS.computeIfAbsent(bone, found -> toRoot(found).transformPosition(
                new Vector3f(found.getPivotX(), found.getPivotY(), found.getPivotZ()).div(UNITS)));
    }

    /** How far a bone's geometry reaches, in blocks, once {@code into} has been applied to it. */
    public record Bounds(Vector3f min, Vector3f max) {
        public Vector3f centre() {
            return new Vector3f(this.min).add(this.max).mul(0.5F);
        }

        public float sizeY() {
            return this.max.y() - this.min.y();
        }

        public float sizeZ() {
            return this.max.z() - this.min.z();
        }
    }

    /**
     * The box a bone's own cubes fill, put through {@code into}.
     *
     * <p>Taken from the corners the cubes are actually drawn with rather than from the sizes written
     * in the file, because a cube is turned about its own pivot before it is drawn and a wheel is
     * usually built lying down and stood up. The box of the turned corners is the box that is on the
     * screen.
     *
     * @return null for a bone with no geometry of its own
     */
    public static Bounds bounds(GeoBone bone, Matrix4f into) {
        Vector3f min = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        boolean any = false;

        for (GeoCube cube : bone.getCubes()) {
            Matrix4f matrix = cubeTransform(cube, into);

            for (GeoQuad quad : cube.quads()) {
                if (quad == null) {
                    continue;
                }

                for (GeoVertex vertex : quad.vertices()) {
                    Vector3f corner = matrix.transformPosition(new Vector3f(vertex.position()));

                    min.min(corner);
                    max.max(corner);
                    any = true;
                }
            }
        }

        return any ? new Bounds(min, max) : null;
    }

    /** A cube is turned about its own pivot before it is drawn, and this is that turn. */
    private static Matrix4f cubeTransform(GeoCube cube, Matrix4f into) {
        return new Matrix4f(into)
                .translate((float) cube.pivot().x() / UNITS, (float) cube.pivot().y() / UNITS,
                        (float) cube.pivot().z() / UNITS)
                .mul(rotation((float) cube.rotation().x(), (float) cube.rotation().y(),
                        (float) cube.rotation().z()))
                .translate((float) -cube.pivot().x() / UNITS, (float) -cube.pivot().y() / UNITS,
                        (float) -cube.pivot().z() / UNITS);
    }

    /**
     * The matrix taking a bone's own axes into the model's, from the pose the geometry file settles
     * on rather than the one an animation has the bone in this frame.
     */
    public static Matrix4f toRoot(GeoBone bone) {
        if (bone == null) {
            return new Matrix4f();
        }

        return toRoot(bone.getParent()).mul(restTransform(bone));
    }

    /**
     * One bone's own step of that, laid out exactly as GeckoLib lays it out when it draws the bone —
     * offset, out to the pivot, turn, scale, back off the pivot. See {@code RenderUtil}.
     */
    public static Matrix4f restTransform(GeoBone bone) {
        BoneSnapshot rest = rest(bone);
        float pivotX = bone.getPivotX() / UNITS;
        float pivotY = bone.getPivotY() / UNITS;
        float pivotZ = bone.getPivotZ() / UNITS;

        return new Matrix4f()
                .translate(-rest.getOffsetX() / UNITS, rest.getOffsetY() / UNITS, rest.getOffsetZ() / UNITS)
                .translate(pivotX, pivotY, pivotZ)
                .mul(rotation(rest.getRotX(), rest.getRotY(), rest.getRotZ()))
                .scale(rest.getScaleX(), rest.getScaleY(), rest.getScaleZ())
                .translate(-pivotX, -pivotY, -pivotZ);
    }

    /** Z, then Y, then X, which is the order GeckoLib turns both a bone and a cube in. */
    public static Matrix4f rotation(float x, float y, float z) {
        return new Matrix4f().rotateZ(z).rotateY(y).rotateX(x);
    }

    /**
     * Where the geometry file left a bone. A bone GeckoLib has not taken its snapshot of yet is
     * asked to take it, which for a bone nothing has moved is the same answer.
     */
    public static BoneSnapshot rest(GeoBone bone) {
        BoneSnapshot rest = bone.getInitialSnapshot();

        if (rest == null) {
            bone.saveInitialSnapshot();
            rest = bone.getInitialSnapshot();
        }

        return rest;
    }
}
