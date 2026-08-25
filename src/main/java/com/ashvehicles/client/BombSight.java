package com.ashvehicles.client;

import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.weapon.WeaponDefinition;
import com.ashvehicles.weapon.WeaponMounts;

import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Where a bomb released this instant would land.
 *
 * <p>A free-fall bomb cannot be aimed at anything. It leaves with whatever speed the aeroplane had
 * and from there gravity has it, so where it lands was decided at the moment of release by how fast,
 * how high and how level the aircraft was — and by the time the pilot can see the answer it is far
 * too late to change it. Every aeroplane that ever carried bombs therefore had something to tell the
 * pilot where they would go. This is that: the trajectory flown forward, tick by tick, until it runs
 * into the world.
 *
 * <p>The flight is worked out with the same arithmetic the bomb itself will use — position first,
 * then gravity, in that order — so what the pilot is shown is what the bomb will do, rather than an
 * approximation of it that drifts at the edges.
 *
 * <p><b>The world runs out before the bomb does.</b> From any height worth bombing from, a jet
 * throws its load hundreds of blocks ahead of itself — further ahead than the client has chunks for.
 * Out there every block reads as air, so a trace that only ever asks the blocks finds nothing, falls
 * through the ground it cannot see, and comes back with no answer at all: the mark vanished from the
 * screen exactly when the pilot climbed high enough to need it. So the fall is followed past the
 * edge of the loaded world against an assumed floor instead — the ground height of the last column
 * the client did know about, which is the nearest thing to the target anybody here can say. That
 * answer is a worked-out one rather than a seen one, and it is handed back marked as such so the
 * instrument can draw it for what it is.
 *
 * <p>Worked out once a tick rather than once a frame. It costs a walk through the world and the
 * answer does not meaningfully change in a sixtieth of a second.
 */
public final class BombSight {
    /**
     * Longest flight worth following, in ticks. Long enough to reach the ground from any height
     * these aeroplanes can climb to — a bomb is a thousand blocks down in about three hundred ticks
     * — and cheap in the tail, since past the loaded world each tick is arithmetic and a chunk
     * lookup rather than a walk through blocks.
     */
    private static final int MAX_FLIGHT = 1200;

    private static AircraftEntity cachedFor;
    private static long cachedAt = Long.MIN_VALUE;
    @Nullable
    private static Solution cachedSolution;

    private BombSight() {
    }

    /**
     * Where the bomb comes down, and how much that is worth.
     *
     * @param point where it would land
     * @param estimated whether the fall ended on the assumed floor rather than on a block the client
     *                  can actually see. True means the target is beyond the loaded world and the
     *                  height of the ground out there is a guess: right over flat country, wrong by
     *                  however much the ground rises or falls between here and there
     */
    public record Solution(Vec3 point, boolean estimated) {
    }

    /**
     * Where a bomb dropped now would hit, or null if it would not come down anywhere at all before
     * it gave up — over the void, or still falling after {@link #MAX_FLIGHT}.
     */
    @Nullable
    public static Solution solve(AircraftEntity aircraft, WeaponDefinition weapon) {
        long now = aircraft.level().getGameTime();

        if (aircraft != cachedFor || now != cachedAt) {
            cachedFor = aircraft;
            cachedAt = now;
            cachedSolution = trace(aircraft, weapon);
        }

        return cachedSolution;
    }

    @Nullable
    private static Solution trace(AircraftEntity aircraft, WeaponDefinition weapon) {
        WeaponDefinition.Projectile round = weapon.projectile();
        Level level = aircraft.level();
        Vec3 up = aircraft.getLiftVector();

        // The same release the aircraft will actually make: from the rack, with the aeroplane's
        // speed, pushed down off the belly by its own figure.
        Vec3 position = aircraft.toWorld(rackOffset(aircraft, weapon), 1.0F);
        Vec3 velocity = aircraft.getVelocity().add(up.scale(-round.speed()));
        int flight = Math.min(MAX_FLIGHT, round.lifetime());

        // Where the ground is taken to be once the chunks run out. Sea level only until the first
        // column of real terrain is read, which is the one the aeroplane is over itself.
        double floor = level.getSeaLevel();

        for (int tick = 0; tick < flight; tick++) {
            Vec3 next = position.add(velocity);
            double ground = surface(level, next);

            if (!Double.isNaN(ground)) {
                // Ground the client has: trace against the blocks themselves, which is the real
                // answer and the only one that knows about a hillside or a roof.
                HitResult hit = level.clip(new ClipContext(position, next,
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, aircraft));

                if (hit.getType() != HitResult.Type.MISS) {
                    return new Solution(hit.getLocation(), false);
                }

                floor = ground;
            } else if (next.y <= floor) {
                // Past the edge of the loaded world, where every block reads as air. The bomb has
                // reached the height the ground was last standing at, so this is where it lands.
                return new Solution(crossing(position, next, floor), true);
            }

            position = next;
            velocity = velocity.subtract(0.0, round.gravity(), 0.0);
        }

        return null;
    }

    /**
     * How high the ground stands in the column a point is over, or {@link Double#NaN} where the
     * client has no chunk to ask.
     *
     * <p>Read off the chunk's own heightmap, and the chunk is asked for without being allowed to
     * load — that is what the {@code false} means. Asking the level instead is the trap: on a
     * server-side call {@code Level#getHeight} generates whatever is not there yet, and here it
     * would hide the very thing that has to be noticed, which is that out here there is nothing to
     * ask.
     */
    private static double surface(Level level, Vec3 at) {
        int x = Mth.floor(at.x);
        int z = Mth.floor(at.z);
        ChunkAccess chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, false);

        if (chunk == null) {
            return Double.NaN;
        }

        return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
    }

    /** Where the step from one point to the next passes a given height. */
    private static Vec3 crossing(Vec3 from, Vec3 to, double height) {
        double fall = from.y - to.y;

        if (fall <= 1.0E-6) {
            return to;
        }

        return from.add(to.subtract(from).scale(Mth.clamp((from.y - height) / fall, 0.0, 1.0)));
    }

    /**
     * Where on the aircraft the bomb will leave from: the first pylon carrying this weapon, so the
     * mark moves with the load rather than being drawn from the middle of the aeroplane.
     */
    private static Vec3 rackOffset(AircraftEntity aircraft, WeaponDefinition weapon) {
        List<AircraftDefinition.Hardpoint> hardpoints = aircraft.getStats().hardpoints();
        List<WeaponMounts.Mount> mounts = aircraft.getWeapons().mounts();

        for (int slot = 0; slot < Math.min(hardpoints.size(), mounts.size()); slot++) {
            WeaponMounts.Mount mount = mounts.get(slot);

            if (!mount.isEmpty() && mount.ammo() > 0 && weapon.equals(Definitions.weapon(mount.weapon()))) {
                return hardpoints.get(slot).pos();
            }
        }

        return Vec3.ZERO;
    }
}
