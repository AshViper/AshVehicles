package com.ashvehicles.vehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.joml.Matrix3f;
import org.joml.Quaternionf;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A box lying at whatever angle its machine is lying at, and every question anyone can ask about
 * one, answered here.
 *
 * <p>Nothing of Minecraft's own shapes is used to describe it. It is not an {@code AABB}, it is not
 * a {@code VoxelShape}, and it is not built out of either: it is a middle, three half-lengths and
 * three axes, and the tests below are written against that. That is the whole point of it. Every
 * shape the game has is aligned to the world, so a shape of the game's own kind can only describe a
 * tilted box by drawing an upright one round it — which is a hull leant onto a hillside filling the
 * wedge of air in front of its own tracks — or by stacking small upright ones up the slope, which is
 * a staircase and is exact nowhere.
 *
 * <p>The one {@code AABB} here is {@link #reach}, and it is not the shape. It is the patch of world
 * worth looking in before any of the real tests are run, which is the one thing an upright box is
 * genuinely good for.
 *
 * <p>Three tests, and everything the mod does with a hitbox is one of them:
 *
 * <ul>
 * <li>{@link #clip} — where a line first enters the box. Shots, the player's crosshair, anything
 *     aimed. The slab method in the box's own axes: three pairs of parallel faces, each pair giving
 *     the stretch of the line that is between them, and the line is inside the box over whatever
 *     stretch all three agree on.</li>
 * <li>{@link #sweep} — how far a moving upright box gets before it touches this one. What stops a
 *     player walking into a hull, holds them up on a sloping deck, and stops a machine's own boxes
 *     against the world. The separating axis theorem, run as an interval in time rather than a yes
 *     or no: two convex shapes are apart exactly while some axis has daylight between their shadows,
 *     so the moment they touch is the moment the last axis runs out of it.</li>
 * <li>{@link #contains} — whether a point is inside. The same projection as the first, without the
 *     line.</li>
 * </ul>
 */
public final class Hitbox {
    /**
     * Motion below this along an axis is treated as none at all, and an axis shorter than this is
     * treated as no axis. Squared lengths are compared against its square where that is what is to
     * hand.
     */
    private static final double NOTHING = 1.0E-9;

    /**
     * How far inside the box something may already be before it counts as having been there at the
     * outset rather than as having arrived during the move.
     *
     * <p>Only a rounding's worth. Something resting on a box is inside it by whatever was left over
     * from the move that put it there, and that has to still be a floor.
     */
    private static final double SETTLED = 1.0E-7;

    /** The world's own three axes, which is what an upright box is aligned to. */
    private static final Vec3[] WORLD = {
            new Vec3(1.0, 0.0, 0.0), new Vec3(0.0, 1.0, 0.0), new Vec3(0.0, 0.0, 1.0)
    };

    private final Vec3 centre;
    private final Vec3 half;
    /** The box's own three axes as unit vectors in the world. Orthonormal, so their own inverse. */
    private final Vec3[] axes;
    /** The fifteen directions {@link #sweep} tries, worked out once because they never change. */
    private final Vec3[] directions;
    private final AABB reach;

    public Hitbox(Vec3 centre, Vec3 size, Quaternionf rotation) {
        Matrix3f matrix = rotation.get(new Matrix3f());

        this.centre = centre;
        this.half = size.scale(0.5);
        this.axes = new Vec3[] {
                column(matrix, 0), column(matrix, 1), column(matrix, 2)
        };
        this.directions = directions(WORLD, this.axes);

        // How far the box reaches along each of the world's axes: each of its own axes contributes
        // its half-length times how much of that axis points that way.
        double x = this.span(this.half, 0);
        double y = this.span(this.half, 1);
        double z = this.span(this.half, 2);

        this.reach = new AABB(centre.x - x, centre.y - y, centre.z - z,
                centre.x + x, centre.y + y, centre.z + z);
    }

    private Hitbox(Vec3 centre, Vec3 half, Vec3[] axes, Vec3[] directions, AABB reach) {
        this.centre = centre;
        this.half = half;
        this.axes = axes;
        this.directions = directions;
        this.reach = reach;
    }

    public Vec3 centre() {
        return this.centre;
    }

    /**
     * The upright box this one fits inside.
     *
     * <p>For looking things up and nothing else — never for deciding anything. It is exactly the air
     * this class exists to stop treating as machine, and the only reason it is here at all is that
     * the world is filed by upright boxes and something has to be handed to it to search with.
     */
    public AABB reach() {
        return this.reach;
    }

    /** The same box with a margin all round, which is how a test that allows a graze asks for one. */
    public Hitbox grow(double margin) {
        if (margin == 0.0) {
            return this;
        }

        // Never past nothing: a box asked to shrink by more than it has left is a box with no room
        // to spare, not a box turned inside out.
        Vec3 half = new Vec3(Math.max(this.half.x + margin, NOTHING),
                Math.max(this.half.y + margin, NOTHING), Math.max(this.half.z + margin, NOTHING));

        return new Hitbox(this.centre, half, this.axes, this.directions,
                new AABB(this.centre.x - this.span(half, 0), this.centre.y - this.span(half, 1),
                        this.centre.z - this.span(half, 2), this.centre.x + this.span(half, 0),
                        this.centre.y + this.span(half, 1), this.centre.z + this.span(half, 2)));
    }

    /**
     * The same box, shifted. Keeps its axes, which is the expensive part of one and is exactly what
     * moving it does not change.
     */
    public Hitbox move(Vec3 offset) {
        return new Hitbox(this.centre.add(offset), this.half, this.axes, this.directions,
                this.reach.move(offset));
    }

    /** Whether a point is inside the box, measured in the box's own axes. */
    public boolean contains(Vec3 point) {
        Vec3 from = point.subtract(this.centre);

        return Math.abs(from.dot(this.axes[0])) <= this.half.x
                && Math.abs(from.dot(this.axes[1])) <= this.half.y
                && Math.abs(from.dot(this.axes[2])) <= this.half.z;
    }

    /**
     * Whereabouts in the box a point lies, as a fraction of each of its own half-lengths: nought at
     * the middle, one at a face, more than one outside it.
     *
     * <p>The same three projections {@link #contains} makes, kept rather than compared. What it is
     * for is telling somebody where a round landed on a machine: a point in the world would be stale
     * the moment the machine moved, but a fraction of the box it went into stays true however far the
     * hull drives off or the turret traverses afterwards.
     *
     * <p>The axes are the box's own, in the order they were built in, so x runs along whichever way
     * the box's own width does — which inside a machine's frame is out to its left.
     */
    public Vec3 within(Vec3 point) {
        Vec3 from = point.subtract(this.centre);

        return new Vec3(
                from.dot(this.axes[0]) / Math.max(this.half.x, NOTHING),
                from.dot(this.axes[1]) / Math.max(this.half.y, NOTHING),
                from.dot(this.axes[2]) / Math.max(this.half.z, NOTHING));
    }

    /**
     * Where a line first enters the box, or empty if it misses.
     *
     * <p>The slab method, in the box's own axes. Each pair of opposite faces cuts the line down to
     * the stretch of it that is between them; three pairs leave the stretch that is inside the box,
     * and if at any point there is no stretch left the line missed. A line that starts inside comes
     * back with where it started, which is what everything asking this expects.
     */
    public Optional<Vec3> clip(Vec3 from, Vec3 to) {
        Vec3 along = to.subtract(from);
        Vec3 start = from.subtract(this.centre);
        double first = 0.0;
        double last = 1.0;

        for (int axis = 0; axis < 3; axis++) {
            double offset = start.dot(this.axes[axis]);
            double speed = along.dot(this.axes[axis]);
            double half = this.half(axis);

            if (Math.abs(speed) < NOTHING) {
                // Running along the face rather than towards it: either it is between the pair for
                // the whole of its length or it never is.
                if (offset < -half || offset > half) {
                    return Optional.empty();
                }

                continue;
            }

            double near = (-half - offset) / speed;
            double far = (half - offset) / speed;

            if (near > far) {
                double swap = near;
                near = far;
                far = swap;
            }

            first = Math.max(first, near);
            last = Math.min(last, far);

            if (first > last) {
                return Optional.empty();
            }
        }

        return Optional.of(from.add(along.scale(first)));
    }

    /**
     * Which way the box's surface faces at a point on it: the outward unit normal of the face that
     * point is lying on.
     *
     * <p>Found by which of the three pairs of faces the point is nearest to, measured in the box's
     * own axes, which is the only sense in which a tilted box has faces at all. A point on a face is
     * its whole half-length out along that axis and less than it along the other two, so the
     * smallest of the three margins names the face and the sign of the offset says which of the pair.
     *
     * <p>Asked of a point that came back from {@link #clip}, this is the plate a shot arrived at,
     * and the angle between it and the shot's path is the whole of what decides whether the shot
     * bites or is thrown off. See {@code Ricochet}.
     *
     * <p>The answer is only as square as the point is: a point well inside the box, or one out past
     * a corner, still gets the nearest face, which is the sensible answer to a question that has no
     * exact one. Ask it of the same box the point was clipped against — a grown one included — or
     * the margin will name a different face near an edge.
     */
    public Vec3 normalAt(Vec3 point) {
        Vec3 from = point.subtract(this.centre);
        Vec3 face = this.axes[0];
        double nearest = Double.MAX_VALUE;
        double side = 1.0;

        for (int axis = 0; axis < 3; axis++) {
            double offset = from.dot(this.axes[axis]);
            double margin = Math.abs(this.half(axis) - Math.abs(offset));

            if (margin < nearest) {
                nearest = margin;
                face = this.axes[axis];
                side = offset < 0.0 ? -1.0 : 1.0;
            }
        }

        return face.scale(side);
    }

    /**
     * How much of a move an upright box may make before it touches this one, as a fraction of the
     * move: one for a move that never touches it, nought for one that is stopped at the outset.
     *
     * <p>The separating axis theorem swept through time. Two convex shapes are apart exactly when
     * some direction can be found along which their shadows do not overlap; for a box against a box
     * it is enough to try fifteen — the three the world is aligned to, the three this box is aligned
     * to, and the nine each pair of those makes between them, which are the directions an edge of
     * one meets an edge of the other along.
     *
     * <p>What makes it a sweep rather than a test is that the shadows are moving. Along each
     * direction the two shadows overlap over some stretch of the move and are apart either side of
     * it; the shapes touch over whatever stretch <em>every</em> direction is overlapping at once, and
     * the moment that stretch begins is the moment of contact. A direction whose shadows never
     * overlap ends it there and then: they do not touch at all.
     *
     * @param box the upright box that is moving
     * @param motion where it is trying to get to, as an offset
     */
    public double sweep(AABB box, Vec3 motion) {
        Vec3 half = new Vec3(box.getXsize() * 0.5, box.getYsize() * 0.5, box.getZsize() * 0.5);

        return this.sweep(box.getCenter(), half, WORLD, this.directions, motion);
    }

    /**
     * The same for another box lying at an angle of its own: how much of a move it may make before
     * it touches this one.
     *
     * <p>This is what an aeroplane setting down on a deck is: two shapes, neither of them upright,
     * neither of them describable to the game. Nothing about it is harder than the upright case —
     * only the list of directions worth trying is longer, because both boxes now have three of their
     * own to contribute rather than one of them borrowing the world's.
     *
     * @param other the box that is moving. This one stands still
     */
    public double sweep(Hitbox other, Vec3 motion) {
        return this.sweep(other.centre, other.half, other.axes,
                directions(this.axes, other.axes), motion);
    }

    /**
     * How much of a move something may make before it touches this box.
     *
     * <p>The separating axis theorem swept through time. Two convex shapes are apart exactly when
     * some direction can be found along which their shadows do not overlap; for a box against a box
     * it is enough to try the three each of them is aligned to and the nine each pair of those makes
     * between them, which are the directions an edge of one meets an edge of the other along.
     *
     * <p>What makes it a sweep rather than a test is that the shadows are moving. Along each
     * direction the two shadows overlap over some stretch of the move and are apart either side of
     * it; the shapes touch over whatever stretch <em>every</em> direction is overlapping at once, and
     * the moment that stretch begins is the moment of contact. A direction whose shadows never
     * overlap ends it there and then: they do not touch at all.
     */
    private double sweep(Vec3 theirCentre, Vec3 theirHalf, Vec3[] theirAxes, Vec3[] directions,
            Vec3 motion) {
        Vec3 between = this.centre.subtract(theirCentre);
        double first = Double.NEGATIVE_INFINITY;
        double last = Double.POSITIVE_INFINITY;

        for (Vec3 direction : directions) {
            // How far the two shadows reach along this direction, either side of their middles.
            double spread = spread(direction, theirAxes, theirHalf)
                    + spread(direction, this.axes, this.half);
            double apart = direction.dot(between);
            double closing = direction.dot(motion);

            if (Math.abs(closing) < NOTHING) {
                // Neither closing nor opening along this one. Either it already separates them for
                // the whole move or it has nothing to say about it.
                if (Math.abs(apart) > spread) {
                    return 1.0;
                }

                continue;
            }

            double near = (apart - spread) / closing;
            double far = (apart + spread) / closing;

            if (near > far) {
                double swap = near;
                near = far;
                far = swap;
            }

            first = Math.max(first, near);
            last = Math.min(last, far);

            if (first > last) {
                return 1.0;
            }
        }

        if (first > 1.0 || last < 0.0) {
            return 1.0;
        }

        // Already inside at the outset — the box has moved onto it rather than it onto the box,
        // which is what a hull settling onto a hillside does to whoever is standing on it twenty
        // times a second. It may go anywhere that does not take it deeper in.
        if (first < -SETTLED) {
            return this.deeper(between, theirHalf, theirAxes, directions, motion) ? 0.0 : 1.0;
        }

        return Math.max(first, 0.0);
    }

    /**
     * Whether a move made from inside the box would take it further in.
     *
     * <p>The one question worth asking of something that is already inside, and it has to be asked
     * rather than waved through. Waved through, a box is not a floor to anything it has ever caught
     * up with: a hull rises a hair onto a slope, everybody standing on the deck is a hair inside it,
     * and from that moment they fall through the tank. Refused outright it is worse — anything the
     * machine turns into is pinned there for good, every direction being a direction that starts
     * inside.
     *
     * <p>So: out of all the directions that separate the two, the one they are least far into is the
     * way out, and a move is allowed if it is not against it. Straight up out of a deck, and along
     * it, and anything in between; down into it, no. It is the direction anything sunk into anything
     * else has to be pushed to free it, and the reason the answer is stable from tick to tick is that
     * shallowly inside a face, that direction is the face's own.
     */
    private boolean deeper(Vec3 between, Vec3 theirHalf, Vec3[] theirAxes, Vec3[] directions,
            Vec3 motion) {
        Vec3 out = null;
        double shallowest = Double.MAX_VALUE;

        for (Vec3 direction : directions) {
            double spread = spread(direction, theirAxes, theirHalf)
                    + spread(direction, this.axes, this.half);
            double apart = direction.dot(between);
            double depth = spread - Math.abs(apart);

            if (depth < shallowest) {
                shallowest = depth;
                // Away from this box's middle. `between` runs from the moving shape to this one, so
                // a direction that agrees with it points into this box and the way out is the other.
                out = apart > 0.0 ? direction.reverse() : direction;
            }
        }

        return out != null && motion.dot(out) < 0.0;
    }

    /** How far a box of the given axes and half-lengths reaches along a direction, either way. */
    private static double spread(Vec3 direction, Vec3[] axes, Vec3 half) {
        return Math.abs(direction.dot(axes[0])) * half.x
                + Math.abs(direction.dot(axes[1])) * half.y
                + Math.abs(direction.dot(axes[2])) * half.z;
    }

    /**
     * Whether an upright box is touching this one where both of them stand. The same directions as
     * {@link #sweep}, asked once rather than through time: one direction with daylight along it is
     * enough to say they are apart.
     */
    public boolean overlaps(AABB box) {
        Vec3 half = new Vec3(box.getXsize() * 0.5, box.getYsize() * 0.5, box.getZsize() * 0.5);
        Vec3 between = this.centre.subtract(box.getCenter());

        for (Vec3 direction : this.directions) {
            double spread = spread(direction, WORLD, half) + spread(direction, this.axes, this.half);

            if (Math.abs(direction.dot(between)) > spread) {
                return false;
            }
        }

        return true;
    }

    /**
     * The directions worth trying between two boxes: the three each of them is aligned to, and the
     * nine each pair of those makes between them.
     *
     * <p>One of the nine is dropped whenever the pair it came from point the same way, because two
     * axes lying along one another make no direction at all — and there is nothing lost, since both
     * of them are in the list already. A box standing square against an upright one drops all nine.
     */
    private static Vec3[] directions(Vec3[] theirs, Vec3[] mine) {
        List<Vec3> found = new ArrayList<>(15);

        for (Vec3 axis : theirs) {
            found.add(axis);
        }

        for (Vec3 axis : mine) {
            found.add(axis);
        }

        for (Vec3 one : theirs) {
            for (Vec3 two : mine) {
                Vec3 crossed = one.cross(two);

                if (crossed.lengthSqr() > NOTHING) {
                    found.add(crossed.normalize());
                }
            }
        }

        return found.toArray(new Vec3[0]);
    }

    private double half(int axis) {
        return axis == 0 ? this.half.x : axis == 1 ? this.half.y : this.half.z;
    }

    /** How far a box of the given half-lengths reaches along one of the world's axes. */
    private double span(Vec3 half, int world) {
        return Math.abs(component(this.axes[0], world)) * half.x
                + Math.abs(component(this.axes[1], world)) * half.y
                + Math.abs(component(this.axes[2], world)) * half.z;
    }

    private static double component(Vec3 of, int axis) {
        return axis == 0 ? of.x : axis == 1 ? of.y : of.z;
    }

    /** One column of a rotation, which is one of the turned axes seen from the world. */
    private static Vec3 column(Matrix3f matrix, int axis) {
        return new Vec3(matrix.get(axis, 0), matrix.get(axis, 1), matrix.get(axis, 2));
    }
}
