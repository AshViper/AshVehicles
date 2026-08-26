package com.ashvehicles.client.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.ashvehicles.vehicle.Ride;
import com.ashvehicles.vehicle.VehicleChassis;

import net.minecraft.util.Mth;
import software.bernie.geckolib.animation.state.BoneSnapshot;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;

/**
 * Builds a tracked vehicle's run of track out of one link, and lays it round the wheels the vehicle
 * already names.
 *
 * <p>Nothing about a track is drawn in the geometry beyond the one link. The band is the taut band
 * round the wheels — the shape a belt takes when it is pulled tight round a set of pulleys, which is
 * what a track is — and the wheels are read out of the baked model with the size and the place their
 * own geometry gives them. So a wheel moved in Blockbench, or swapped for a bigger one, or a whole
 * different tank, needs nothing changed here or in the vehicle's file.
 *
 * <p>The band is worked out in the plane the vehicle drives in: the run is described by where the
 * wheels are fore and aft and how high off the ground they sit, and the only thing the third axis
 * decides is which side of the hull a wheel is on. Wheels sort themselves into two groups by that,
 * and each group gets its own band at its own wheels' distance out, which is why one link bone is
 * enough for a vehicle rather than one per side.
 *
 * <p><b>What the artist has to do.</b> Build one link, in its own bone, lying flat and running along
 * the bone's Z. It is drawn from wherever it was left to wherever the band wants it, so where it is
 * built does not matter; how big it is does, because that is what the run's pitch is taken from.
 *
 * <p><b>Where the work goes.</b> The shape is settled by the geometry and nothing but, so it is
 * worked out once per model and kept against the baked model itself — which means a resource reload,
 * which bakes a new one, gets a new shape without anything having to be told to throw the old one
 * away. What is left per frame is one point along the band per link and the matrix to put it there.
 */
public final class TrackBelt {
    /**
     * Which way the run travels for a wheel angle that is winding on, for a link bone the model has
     * not turned round.
     *
     * <p>Whether this model has turned it round is {@link Shape#travelSign} — the band is laid out
     * in the link's parent's axes, and half these models hang everything off a root bone with a half
     * turn on it, which reverses those axes against the vehicle's. The wheels themselves are already
     * made to roll the same way on both kinds; see {@link VehicleGeoModel#turnAboutX}. Without the
     * pair the track would run one way and the wheels under it the other.
     *
     * <p>Flip this if the track runs backwards on <em>every</em> vehicle at once. One vehicle's
     * track running backwards is not this.
     */
    private static final float TRAVEL_SIGN = 1.0F;

    /**
     * How many points each wheel is described by while the band is pulled round it.
     *
     * <p>The band is the convex hull of the wheels, and the cheapest honest way to take the hull of
     * a set of circles is to take the hull of points sampled round them. Twenty-four leaves the
     * corners of the run about a percent inside the true circle, which is a fraction of a pixel on a
     * road wheel and nothing at all against the thickness of a link.
     */
    private static final int ARC_STEPS = 24;

    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    /** How a single link is drawn, once it has been put where it belongs. */
    public interface LinkDrawer {
        void draw(GeoBone link);
    }

    /**
     * The shape of every band on one model, and where the link sits in its own bone.
     *
     * @param belts one per side of the hull, or one in total for a vehicle whose wheels are all in a
     *              line
     * @param linkCentre the middle of the link's own geometry, in the link bone's own axes and in
     *                   blocks. What gets put on the band is this point rather than the bone's pivot:
     *                   a link built off to one side of its pivot would otherwise hang off the run
     * @param travelSign whether the axes the band was laid out in run with the vehicle or against
     *                   it, which is minus one for a model whose root bone is turned round. See
     *                   {@link #TRAVEL_SIGN}
     */
    private record Shape(List<Belt> belts, Vector3f linkCentre, float travelSign) {
        static final Shape NONE = new Shape(List.of(), new Vector3f(), 1.0F);
    }

    /**
     * One closed band, as a ring of points with the distance to each along it.
     *
     * @param x how far out from the middle of the vehicle this side's run sits
     * @param z the run's points fore and aft, in blocks, in the link bone's parent's axes
     * @param y the same points' heights
     * @param run the distance along the band to each point, ending with the whole way round
     * @param links how many links go round it. See {@link #links}
     * @param pitch the distance from one link to the next, which is the way round divided by the
     *              number of them, so that the last link meets the first exactly
     * @param rollRadius the radius the wheels turn at, which is what a wheel angle is turned into a
     *                   distance travelled with. See {@link #rollRadius}
     */
    private record Belt(float x, float[] z, float[] y, float[] run, int links, float pitch,
            float rollRadius) {
        /**
         * Where the band has got to a given distance along it, wrapped, into {@code into} as
         * {@code (z, y)}.
         */
        void pointAt(float distance, Vector3f into) {
            float length = this.run[this.run.length - 1];
            float along = distance % length;

            if (along < 0.0F) {
                along += length;
            }

            int low = 0;
            int high = this.run.length - 1;

            while (low + 1 < high) {
                int mid = (low + high) >>> 1;

                if (this.run[mid] <= along) {
                    low = mid;
                } else {
                    high = mid;
                }
            }

            float span = this.run[low + 1] - this.run[low];
            float t = span <= 0.0F ? 0.0F : (along - this.run[low]) / span;
            int next = (low + 1) % this.z.length;

            into.set(this.z[low] + (this.z[next] - this.z[low]) * t,
                    this.y[low] + (this.y[next] - this.y[low]) * t, 0.0F);
        }
    }

    /**
     * The shapes worked out so far, against the baked model they were worked out from.
     *
     * <p>Weakly, and keyed by the model object itself, because that is what makes a reload correct
     * for free: GeckoLib bakes a new model out of the reloaded geometry, the new model is not this
     * one, and the shape is worked out again from the geometry that is actually being drawn. Held
     * without locking because every caller is the render thread.
     */
    private static final Map<BakedGeoModel, Map<VehicleChassis.Track, Shape>> SHAPES = new WeakHashMap<>();

    private TrackBelt() {
    }

    /** Whether this bone is the one link the vehicle's run of track is built out of. */
    public static boolean isLink(VehicleChassis.Model setup, GeoBone bone) {
        VehicleChassis.Track track = setup.track().orElse(null);

        return track != null && track.link().equals(bone.getName());
    }

    /**
     * Draws the whole of a vehicle's track, by moving the one link bone round each band and handing
     * it to {@code drawer} where each link goes.
     *
     * <p>The bone is left where the geometry put it afterwards. It is one bone shared by every
     * vehicle of the kind on the screen and by every pass over each of them, and a bone left out on
     * the run would be the next pass's starting point.
     *
     * <p>The run stays on the ground while the body moves above it. The whole model is rocked on the
     * pose stack by whatever the suspension is doing — see {@link Ride} — and the band is a child of
     * the model like everything else, so left alone it would be carried up and down with the hull
     * and the tracks would lift clear of the ground every time the vehicle crossed a bump. Each
     * point of the run is therefore put back down by exactly what the body's movement lifted it, the
     * same as each road wheel is; the band then flexes along its length, which is what a real run of
     * track does over wheels moving on their torsion bars.
     *
     * @param wheelAngle how far the road wheels have gone round, in degrees. The run is scrolled by
     *                   the distance those wheels have rolled through, so that a link is never seen
     *                   to slip on a wheel. It starts again every revolution, which the run survives
     *                   only because of the rounding in {@link #rollRadius}
     * @param ride how far the body has moved on its springs
     * @param wheelTravel how far a road wheel is allowed to move, in blocks, which is as far as the
     *                    run is put back down by before it is against the stops with them
     * @return whether there was a run to draw. False leaves the caller to draw the bone as it was
     *         built, which is the honest answer for a model with no wheels to lay a band round
     */
    public static boolean draw(BakedGeoModel model, VehicleChassis.Model setup, GeoBone link,
            float wheelAngle, Ride ride, float wheelTravel, LinkDrawer drawer) {
        Shape shape = shapeOf(model, setup, link);

        if (shape.belts().isEmpty()) {
            return false;
        }

        Vector3f here = new Vector3f();
        Vector3f next = new Vector3f();
        Vector3f pivot = new Vector3f();
        Vector3f fromPivot = new Vector3f();
        Matrix4f turn = new Matrix4f();
        boolean sprung = !ride.isLevel() && wheelTravel > 0.0F;

        for (Belt belt : shape.belts()) {
            float travel = TRAVEL_SIGN * shape.travelSign() * wheelAngle * DEG_TO_RAD * belt.rollRadius();

            for (int i = 0; i < belt.links(); i++) {
                float along = travel + i * belt.pitch();

                belt.pointAt(along, here);
                belt.pointAt(along + belt.pitch(), next);

                if (sprung) {
                    here.y -= plant(shape, belt, here.x(), ride, setup.scale(), wheelTravel);
                    next.y -= plant(shape, belt, next.x(), ride, setup.scale(), wheelTravel);
                }

                place(link, shape.linkCentre(), belt.x(), here, next, pivot, fromPivot, turn);
                drawer.draw(link);
            }
        }

        restore(link);

        return true;
    }

    /**
     * How far the body's movement has lifted one point of the run, in the model's blocks, so that
     * the caller can put it back down by the same amount.
     *
     * <p>The band is laid out in the link bone's parent's axes rather than the model's, and half
     * these models hang everything off a root bone turned half round — which is exactly what
     * {@link Shape#travelSign} already measures for the run's direction of travel. The same figure
     * carries a point of the band back into the axes {@link Ride#liftOf} works in.
     *
     * @param z where along the run the point is, in the band's own axes
     */
    private static float plant(Shape shape, Belt belt, float z, Ride ride, float scale, float wheelTravel) {
        float lift = ride.liftOf(shape.travelSign() * belt.x(), shape.travelSign() * z, scale);
        float stop = wheelTravel / Math.max(scale, 0.01F);

        return Mth.clamp(lift, -stop, stop);
    }

    /**
     * Puts the link between two points on the band: turned to lie along the run between them, and
     * moved so that the middle of its own geometry is the middle of that stretch.
     *
     * <p>Turned about X and nothing else. A track link banks along the run and does nothing else,
     * and a run that is a shape in one plane is a run every link of which lies in that plane.
     */
    private static void place(GeoBone link, Vector3f centre, float x, Vector3f here, Vector3f next,
            Vector3f pivot, Vector3f fromPivot, Matrix4f turn) {
        BoneSnapshot rest = BakedGeometry.rest(link);
        float dz = next.x() - here.x();
        float dy = next.y() - here.y();

        // A turn about X of a takes the bone's own +Z to (0, −sin a, cos a), so the turn that lays
        // the link along the stretch is the one whose sine is minus the rise.
        float rotX = rest.getRotX() + (float) Math.atan2(-dy, dz);

        link.updateRotation(rotX, rest.getRotY(), rest.getRotZ());

        pivot.set(link.getPivotX(), link.getPivotY(), link.getPivotZ()).div(BakedGeometry.UNITS);
        fromPivot.set(centre).sub(pivot);
        turn.identity().rotateZ(rest.getRotZ()).rotateY(rest.getRotY()).rotateX(rotX)
                .transformPosition(fromPivot);

        // Where the bone ends up is its offset, plus its pivot, plus the turned distance from that
        // pivot to the link itself; so the offset is what is left of the target once those are taken
        // off it. The X of an offset is applied negated — see RenderUtil.translateMatrixToBone —
        // which is the whole of why that one term is the other way round.
        float wantZ = (here.x() + next.x()) * 0.5F;
        float wantY = (here.y() + next.y()) * 0.5F;

        link.updatePosition(
                -(x - pivot.x() - fromPivot.x()) * BakedGeometry.UNITS,
                (wantY - pivot.y() - fromPivot.y()) * BakedGeometry.UNITS,
                (wantZ - pivot.z() - fromPivot.z()) * BakedGeometry.UNITS);
    }

    /** Puts the link bone back exactly where the geometry file left it. */
    private static void restore(GeoBone link) {
        BoneSnapshot rest = BakedGeometry.rest(link);

        link.updateRotation(rest.getRotX(), rest.getRotY(), rest.getRotZ());
        link.updatePosition(rest.getOffsetX(), rest.getOffsetY(), rest.getOffsetZ());
    }

    // ------------------------------------------------------------------
    // Working the shape out
    // ------------------------------------------------------------------

    private static Shape shapeOf(BakedGeoModel model, VehicleChassis.Model setup, GeoBone link) {
        VehicleChassis.Track track = setup.track().orElse(null);

        if (track == null) {
            return Shape.NONE;
        }

        return SHAPES.computeIfAbsent(model, ignored -> new HashMap<>())
                .computeIfAbsent(track, ignored -> build(model, setup, track, link));
    }

    /**
     * Works out, once, where every link on a model goes.
     *
     * <p>Read off the model as it was built rather than as it is being drawn. A road wheel that is
     * turning is a road wheel in the same place, and a run that was re-derived from a spinning wheel
     * every frame would cost the same work twenty times a second to arrive at the same answer.
     */
    private static Shape build(BakedGeoModel model, VehicleChassis.Model setup,
            VehicleChassis.Track track, GeoBone link) {
        BakedGeometry.Bounds linkBox = BakedGeometry.bounds(link, new Matrix4f());

        if (linkBox == null) {
            return Shape.NONE;
        }

        // The link's own size, seen the way round the geometry file leaves it: how long it is along
        // the run, which is the pitch, and how thick it is, which is how far off the wheel the band
        // has to stand for the inside face of a link to touch the rim.
        BakedGeometry.Bounds asBuilt = BakedGeometry.bounds(link, BakedGeometry.restTransform(link));
        float pitch = (track.pitch() > 0.0F ? track.pitch() : asBuilt.sizeZ()) * track.spacing();
        float outset = track.outset().orElse(asBuilt.sizeY() * 0.5F);

        if (pitch < 0.001F) {
            return Shape.NONE;
        }

        // The axes the whole band is described in: the link bone's parent's, so that a link can be
        // put on the band without further conversion. Which way round they are against the vehicle
        // is what a run travelling backwards hangs on, so it is read off here with them.
        Matrix4f intoLink = BakedGeometry.toRoot(link.getParent()).invert();
        float travelSign = handedness(intoLink);
        List<Wheel> wheels = wheels(model, track.wheelsOr(setup.roadWheels()), intoLink);

        if (wheels.size() < 2) {
            return Shape.NONE;
        }

        List<Belt> belts = new ArrayList<>(2);

        for (List<Wheel> side : sides(wheels)) {
            Belt belt = belt(side, outset, pitch, track.maxLinks());

            if (belt != null) {
                belts.add(belt);
            }
        }

        return new Shape(belts, linkBox.centre(), travelSign);
    }

    /**
     * Whether an axis of the band's frame points the way the vehicle does or the other way, which is
     * the other way for a model hung off a root bone with a half turn on it.
     */
    private static float handedness(Matrix4f intoLink) {
        return intoLink.transformDirection(new Vector3f(1.0F, 0.0F, 0.0F)).x() < 0.0F ? -1.0F : 1.0F;
    }

    /**
     * One wheel the band is pulled round: where its middle is and how big it is, in the link bone's
     * parent's axes so that a link can be put on the band without further conversion.
     */
    private record Wheel(float x, float y, float z, float radius) {
    }

    private static List<Wheel> wheels(BakedGeoModel model, List<String> names, Matrix4f intoLink) {
        List<Wheel> found = new ArrayList<>(names.size());

        for (String name : names) {
            GeoBone bone = model.getBone(name).orElse(null);

            if (bone == null) {
                continue;
            }

            BakedGeometry.Bounds box =
                    BakedGeometry.bounds(bone, new Matrix4f(intoLink).mul(BakedGeometry.toRoot(bone)));

            if (box == null) {
                continue;
            }

            // A road wheel is a disc, so how big it is is however far it reaches in the plane the
            // vehicle drives in; which of the two that is depends on how the wheel was built and
            // stood up, and taking the larger asks nobody to care.
            float radius = Math.max(box.sizeY(), box.sizeZ()) * 0.5F;

            if (radius > 0.0F) {
                found.add(new Wheel(box.centre().x(), box.centre().y(), box.centre().z(), radius));
            }
        }

        return found;
    }

    /**
     * Sorts the wheels into the hull's two sides, by which side of the middle of the lot of them
     * each sits.
     *
     * <p>A vehicle whose wheels are all one side of that — a model with one side's wheels named, or
     * a single line of them — comes back as one run rather than as one run and one empty one.
     */
    private static List<List<Wheel>> sides(List<Wheel> wheels) {
        float middle = 0.0F;

        for (Wheel wheel : wheels) {
            middle += wheel.x();
        }

        middle /= wheels.size();

        List<Wheel> left = new ArrayList<>();
        List<Wheel> right = new ArrayList<>();

        for (Wheel wheel : wheels) {
            (wheel.x() < middle ? left : right).add(wheel);
        }

        if (left.isEmpty() || right.isEmpty()) {
            return List.of(wheels);
        }

        return List.of(left, right);
    }

    /** Pulls one band tight round one side's wheels and works out how many links go round it. */
    private static Belt belt(List<Wheel> side, float outset, float pitch, int maxLinks) {
        if (side.size() < 2) {
            return null;
        }

        float[] pz = new float[side.size() * ARC_STEPS];
        float[] py = new float[pz.length];
        int at = 0;
        float x = 0.0F;

        for (Wheel wheel : side) {
            float radius = wheel.radius() + outset;
            x += wheel.x();

            for (int step = 0; step < ARC_STEPS; step++) {
                double angle = 2.0 * Math.PI * step / ARC_STEPS;

                pz[at] = wheel.z() + radius * (float) Math.cos(angle);
                py[at] = wheel.y() + radius * (float) Math.sin(angle);
                at++;
            }
        }

        int[] ring = hull(pz, py);

        if (ring.length < 3) {
            return null;
        }

        float[] z = new float[ring.length];
        float[] y = new float[ring.length];
        float[] run = new float[ring.length + 1];

        for (int i = 0; i < ring.length; i++) {
            z[i] = pz[ring[i]];
            y[i] = py[ring[i]];
        }

        for (int i = 0; i < ring.length; i++) {
            int next = (i + 1) % ring.length;

            run[i + 1] = run[i] + (float) Math.hypot(z[next] - z[i], y[next] - y[i]);
        }

        float length = run[ring.length];
        int links = links(length, pitch, Math.max(maxLinks, 3), radius(side));
        float spaced = length / links;

        return new Belt(x / side.size(), z, y, run, links, spaced, rollRadius(side, spaced));
    }

    /**
     * How many links go round the band.
     *
     * <p>As many as fit, give or take a few — and the give and take is the whole point.
     *
     * <p>Two things want to divide evenly by the distance between one link and the next. The band
     * does, because the last link round has to meet the first one: that one is not negotiable, so
     * the spacing is the way round divided by however many links are being drawn. And a wheel's
     * circumference does, because the vehicle counts how far it has come only as far as one turn of
     * a road wheel before starting again — which is all a spinning wheel needs, a wheel a whole turn
     * on being a wheel where it was, but which for a run of track means the whole run jumping
     * backwards by whatever the odd fraction of a link was, eight or nine times for every time the
     * track goes round.
     *
     * <p>No number of links satisfies both exactly. But moving the count by one or two barely
     * changes how the run looks — the links are drawn at the size they were built, so all that
     * changes is whether they sit flush or overlap by a percent or two — while it swings the
     * fraction of a link left over at the wheel through a whole cycle. So: try the counts within a
     * twentieth of the honest one, and take whichever leaves the least over. On the Leopard that
     * turns a five per cent slip against the wheels into half of one.
     */
    private static int links(float length, float pitch, int maxLinks, float radius) {
        int ideal = Math.min(Math.max(Math.round(length / pitch), 3), maxLinks);
        int span = Math.max(Math.round(ideal * 0.05F), 1);
        float turn = (float) (2.0 * Math.PI) * radius;
        int best = ideal;
        float least = Float.MAX_VALUE;

        for (int count = Math.max(ideal - span, 3); count <= Math.min(ideal + span, maxLinks); count++) {
            float spaced = length / count;
            float over = Math.abs(Math.max(Math.round(turn / spaced), 1) * spaced - turn);

            if (over < least) {
                least = over;
                best = count;
            }
        }

        return best;
    }

    /**
     * The radius the run is scrolled at, which is the middling wheel's rounded to a whole number of
     * links round — see {@link #links}, which has already chosen a count that makes the rounding
     * nearly nothing. What is left of it the run gives away as a slow creep against the wheels
     * rather than as a jump.
     */
    private static float rollRadius(List<Wheel> side, float pitch) {
        float turn = (float) (2.0 * Math.PI) * radius(side);
        int links = Math.max(Math.round(turn / pitch), 1);

        return links * pitch / (float) (2.0 * Math.PI);
    }

    /** The middling wheel's radius, so that one odd-sized idler does not speak for the whole side. */
    private static float radius(List<Wheel> side) {
        float[] radii = new float[side.size()];

        for (int i = 0; i < radii.length; i++) {
            radii[i] = side.get(i).radius();
        }

        Arrays.sort(radii);

        return radii[radii.length / 2];
    }

    /**
     * The convex hull of the sampled points, anticlockwise, by the monotone chain — which is the
     * band, because a belt pulled tight round a set of pulleys is exactly the hull of them.
     *
     * @return the indices of the hull's points in order
     */
    private static int[] hull(float[] px, float[] py) {
        Integer[] order = new Integer[px.length];

        for (int i = 0; i < px.length; i++) {
            order[i] = i;
        }

        Arrays.sort(order, (a, b) -> px[a] != px[b]
                ? Float.compare(px[a], px[b])
                : Float.compare(py[a], py[b]));

        int[] chain = new int[px.length * 2];
        int size = 0;

        // The underside of the run, left to right.
        for (int i = 0; i < order.length; i++) {
            while (size >= 2 && cross(px, py, chain[size - 2], chain[size - 1], order[i]) <= 0.0F) {
                size--;
            }

            chain[size++] = order[i];
        }

        // And back along the top, which may not eat into the underside: hence the floor.
        int floor = size + 1;

        for (int i = order.length - 2; i >= 0; i--) {
            while (size >= floor && cross(px, py, chain[size - 2], chain[size - 1], order[i]) <= 0.0F) {
                size--;
            }

            chain[size++] = order[i];
        }

        // The last point round is the first one again.
        return Arrays.copyOf(chain, Math.max(size - 1, 0));
    }

    private static float cross(float[] px, float[] py, int origin, int a, int b) {
        return (px[a] - px[origin]) * (py[b] - py[origin]) - (py[a] - py[origin]) * (px[b] - px[origin]);
    }

}
