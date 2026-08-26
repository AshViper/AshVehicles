package com.ashvehicles.entity;

import com.ashvehicles.AshVehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * What a vehicle drives through, and the breaking of it.
 *
 * <p>Two questions, and they have to have the same answer or the vehicle either stops in front of
 * something nothing ever clears or drives into something that stays where it is. So they are asked
 * of the same volume by the same rule: {@link #opens} is the one the movement asks before it commits
 * to a step, and {@link #crush} is the one the server asks afterwards about where the vehicle
 * actually got to.
 *
 * <p><b>Which of the two sides asks which.</b> A vehicle with a driver in it is simulated on that
 * driver's client — see {@link GroundVehicleEntity} — so {@code opens} is answered there, off block
 * states and tags the client already has, and the vehicle moves on the strength of it. Nothing is
 * broken on that side and nothing is asked of the server about it: the server sweeps the hull's own
 * volume every tick and breaks what it finds, which is the same set of blocks the client decided it
 * could drive into, arrived at without the client being trusted with anything beyond its own
 * position.
 *
 * <p><b>What counts as in the way.</b> The hull, and not the plain box round it: the footprint the
 * vehicle really has, turned to face the way it is facing, lying along the plane its own suspension
 * has lain it along, from a step above that plane up to the top of the turret. Everything under the
 * step is ground — a kerb the vehicle climbs, the hillside it is standing on — and nothing solid
 * there is ever touched, which is the whole of what keeps a tank from trenching its way up every
 * slope it meets.
 *
 * <p><b>Except what grows there.</b> Undergrowth is the one thing broken below that line as well,
 * right down to the tracks, because undergrowth at track height is the usual case and the whole
 * point: a tank crossing a meadow leaves a track through the meadow. It can only ever be the
 * {@link #CRUSHABLE} tag down there — never the resistance test, which at ground level would have
 * every vehicle in the mod ploughing a trench across the world.
 */
public final class BlockCrusher {
    /**
     * Things that go down under any vehicle whatever its crush strength: grass, crops, leaves,
     * saplings, the undergrowth of the world.
     *
     * <p>Separate from the resistance test because it is a separate idea. Resistance says how
     * stoutly a thing is built and is the right question to ask of a wall; asked of a hedge it gives
     * the right answer for the wrong reason, and asked by a light vehicle with little crush strength
     * it gives the wrong one outright. Nothing that grows should ever stop a machine on tracks, so
     * nothing that grows is asked.
     *
     * <p>A tag, so a pack can say what else counts without touching any of this.
     */
    public static final TagKey<Block> CRUSHABLE = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "crushable"));

    /**
     * How near the edge of the hull a block has to come before it counts as only touching it rather
     * than standing in it, in blocks.
     *
     * <p>A whisker, and it earns its place. A hull side falls on a block boundary far more often
     * than chance would have it — these vehicles are laid out in whole and half blocks — and the
     * column beyond that boundary touches the hull along a line and overlaps it by nothing at all.
     * Counted, it would have every vehicle clearing a path a block wider on each side than itself.
     */
    private static final double GRAZE = 1.0E-4;

    private BlockCrusher() {
    }

    /**
     * The volume a vehicle's hull occupies, in the shape a ground vehicle is really made of.
     *
     * <p>An oriented rectangle rather than a box, and a sloped one: {@code rise} and {@code tilt}
     * are how far the hull's underside climbs per block forward and per block to the right, which
     * for a vehicle lying on a hillside is the hillside's own slope. That is what makes the floor of
     * this volume follow the ground instead of cutting into it — take the slope away and a tank
     * pointing up a bank would find the bank inside itself and eat it.
     *
     * @param at the vehicle's origin, which for these models is between the tracks at ground level
     * @param forward its heading, level and of unit length
     * @param halfWidth half the width of the hull, in blocks
     * @param front how far the hull reaches ahead of the origin, and {@code back} how far behind,
     *              written as a negative number
     * @param rise how far the hull's underside climbs per block forward, and {@code tilt} the same
     *             per block to the right
     * @param belly how far above the hull's plane the body of the vehicle starts. A step, plus a
     *              little: below it is ground the vehicle drives over rather than through
     * @param roof how far above that plane the volume ends, which is the top of the vehicle
     */
    public record Body(Vec3 at, Vec3 forward, double halfWidth, double front, double back,
            double rise, double tilt, double belly, double roof) {
    }

    /**
     * Whether the vehicle can make its own way through whatever is stopping it: something is in the
     * body of it, and everything that is gives way.
     *
     * <p>Asked only of a step the movement has already refused, so both halves of that matter. If
     * nothing is in the body of the vehicle at all then whatever refused the step was not something
     * the vehicle could break — a ledge a shade too tall to climb, most often — and the refusal
     * stands. If something is there but will not give, it stands too.
     *
     * <p>Only what would actually stop the vehicle is counted. A block with no collision shape —
     * grass, a flower, a crop — is not in anybody's way whether or not it is soft enough to break,
     * and counting one would have a vehicle claiming to smash its way through a wheat field it was
     * never stopped by.
     */
    public static boolean opens(Level level, Body body, float limit) {
        // Set by the walk below, which cannot say two things at once: whether anything gave is the
        // other half of the answer to whether anything held.
        boolean[] gives = {false};

        boolean holds = walk(level, body, (pos, state, inBody) -> {
            if (!inBody || state.getCollisionShape(level, pos).isEmpty()) {
                return false;
            }

            if (!crushable(level, pos, state, limit)) {
                return true;
            }

            gives[0] = true;

            return false;
        });

        return !holds && gives[0];
    }

    /**
     * Breaks everything soft enough that is standing in the vehicle, and leaves everything else.
     *
     * <p>Through {@code destroyBlock}, so each one makes the noise and the scatter of itself that
     * anything broken does. It reads like a great many of them and is not: the volume is cleared the
     * tick the vehicle first reaches into it, and after that a vehicle under way is only ever
     * entering the thin slice of new ground a tick of its own speed carries it into.
     */
    public static void crush(Level level, Entity by, Body body, float limit, boolean drops) {
        walk(level, body, (pos, state, inBody) -> {
            boolean give = inBody
                    ? crushable(level, pos, state, limit)
                    : growing(level, pos, state);

            if (give) {
                level.destroyBlock(pos.immutable(), drops, by);
            }

            return false;
        });
    }

    /**
     * Asked of every block the vehicle reaches into, until one of them answers yes.
     *
     * <p>{@code inBody} tells the two halves of the volume apart: true for a block inside the body
     * of the vehicle, false for one under it, down at the height of the tracks, where the only thing
     * that ever gives is what grows.
     */
    @FunctionalInterface
    private interface Test {
        boolean of(BlockPos pos, BlockState state, boolean inBody);
    }

    /**
     * Walks the blocks the vehicle reaches into, and says whether any of them answered the test.
     *
     * <p>Column by column rather than as one box, because the floor of the volume follows the plane
     * the hull is lying along and that plane is at a different height over every column. The
     * horizontal pass is the plain rectangle in the vehicle's own axes: how far up the nose the
     * column is and how far out to the side, both of which fall straight out of two dot products.
     */
    private static boolean walk(Level level, Body body, Test test) {
        Vec3 forward = body.forward();
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x);
        Vec3 at = body.at();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // How far a column's middle may lie outside the hull and still be a column the hull is
        // standing in. A block is a square and the hull's axes are turned, so what has to be allowed
        // for is the block's width measured along those axes: half a block when the vehicle is
        // facing along the world, seven tenths when it is facing into the corner between two of
        // them. Allow only the half and a vehicle driven diagonally leaves blocks standing inside
        // itself. Both of the vehicle's axes want the same figure, the second being the first
        // turned a quarter turn.
        double reach = 0.5 * (Math.abs(forward.x) + Math.abs(forward.z)) - GRAZE;

        // The square of world the turned rectangle could possibly be in. Cheap to work out and
        // cheap to be wrong about: every column in it is asked the exact question below.
        double span = Math.max(Math.abs(body.front()), Math.abs(body.back())) + body.halfWidth() + reach;
        int fromX = Mth.floor(at.x - span);
        int toX = Mth.floor(at.x + span);
        int fromZ = Mth.floor(at.z - span);
        int toZ = Mth.floor(at.z + span);

        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                double dx = x + 0.5 - at.x;
                double dz = z + 0.5 - at.z;
                double along = dx * forward.x + dz * forward.z;
                double sideways = dx * right.x + dz * right.z;

                if (along > body.front() + reach || along < body.back() - reach
                        || Math.abs(sideways) > body.halfWidth() + reach) {
                    continue;
                }

                double plane = at.y + along * body.rise() + sideways * body.tilt();
                // A block owns the metre above its own coordinate, so the first one the body of the
                // vehicle reaches into is the one its floor is inside and the last is the one below
                // its ceiling. Written this way round, a body that starts exactly on a block
                // boundary leaves the block under that boundary alone — which is what lets a vehicle
                // whose step height is a block drive over a one-block kerb rather than break it.
                int floor = Mth.floor(plane);
                int belly = Mth.floor(plane + body.belly());
                int roof = Mth.ceil(plane + body.roof()) - 1;

                for (int y = floor; y <= roof; y++) {
                    pos.set(x, y, z);
                    BlockState state = level.getBlockState(pos);

                    if (!state.isAir() && test.of(pos, state, y >= belly)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /** Whether one block gives way to a vehicle of a given crush strength. */
    private static boolean crushable(Level level, BlockPos pos, BlockState state, float limit) {
        if (!breakable(level, pos, state)) {
            return false;
        }

        // The block's own figure rather than the one a blast would be told, because there is no
        // blast: the state-and-explosion form of the question expects an explosion to ask it about,
        // and there are mods that read the one they are handed.
        return state.is(CRUSHABLE) || state.getBlock().getExplosionResistance() <= limit;
    }

    /** Whether one block is the sort of thing tracks flatten however little else they can. */
    private static boolean growing(Level level, BlockPos pos, BlockState state) {
        return breakable(level, pos, state) && state.is(CRUSHABLE);
    }

    /**
     * Two things a vehicle never breaks whatever the figures say.
     *
     * <p>Anything with a block entity in it is somebody's furniture — a chest, a furnace, a hopper
     * with a machine behind it — and a vehicle that broke those would be emptying containers into
     * nothing, since what is crushed usually leaves no drops. And anything the world has marked
     * unbreakable stays unbreakable, for the mod that gives a wall a low resistance and leans on its
     * hardness to keep it standing.
     */
    private static boolean breakable(Level level, BlockPos pos, BlockState state) {
        return !state.hasBlockEntity() && state.getDestroySpeed(level, pos) >= 0.0F;
    }
}
