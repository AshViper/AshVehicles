package com.ashvehicles.client.ghost;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.ghost.dh.DHIntegration;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Whether the world stands between the camera and a ghost.
 *
 * <p>A ghost is drawn without fog and lit by nothing, so that it reads against the sky — and it
 * would read just as well straight through a mountain, which is worse than not drawing it. The
 * game's depth buffer catches part of this for free: ghosts depth-test against whatever the game
 * has drawn, so anything behind rendered terrain is already hidden. What escapes it is Distant
 * Horizons' terrain, which leaves no depth in the game's buffer at all; and, as a belt to those
 * braces, the game's own blocks are traced too.
 *
 * <p>So the line is traced twice: through the game's own blocks as far as the loaded world
 * reaches, on the game thread, which is cheap; and past that through Distant Horizons' LOD
 * columns, <em>on a worker thread</em>. That last part is not optional: the Distant Horizons data
 * repo loads what it is asked for on its own threads and blocks the caller until it arrives, and
 * those threads can in turn be waiting on the game thread — asked from the game thread it has
 * deadlocked the client outright. The worker waits instead; the ghost keeps its last answer until
 * the new one lands.
 *
 * <p><b>Two points are aimed at, not one, in both halves.</b> A line to the middle of something
 * standing on the ground, from an eye at about the same height, skims the surface for its whole
 * length, and any rise of a single block along the way reports the thing as hidden when it is in
 * plain sight. So the middle is tried and the top of it as well, and it counts as hidden only when
 * both are blocked — which is also the honest answer to the question being asked, since a shape
 * with its fin showing over a ridge is visible. The line is stopped short of the point it is aimed
 * at, so that the ground a thing rests on is never counted as standing in front of it.
 *
 * <p>The second point matters more for Distant Horizons than for the game's own blocks, not less:
 * its terrain is an average of the real thing and gets coarser the further out it is drawn, so a
 * line that passes a few blocks over a distant ridge in the world can go straight through the
 * ridge as Distant Horizons has it. Asking about one point only — which is what this did until
 * 2026-08-21 — hides aeroplanes that are plainly in the air over the hill. The rays are still at
 * most two: the top is asked about only when the middle came back blocked.
 *
 * <p><b>None of it applies to a ghost standing inside the world the client has built.</b> The game
 * has drawn the terrain around it, the ghost is drawn at its real position with that terrain's own
 * light and fog, and the depth buffer therefore hides it exactly — per pixel, by the ground that is
 * actually in the way. Tracing a line as well can only overrule that, and it overrules it in one
 * direction: two points is a coarse way to describe a machine, and a sightline to something
 * <em>standing on the ground</em> grazes that ground for its whole length, so a single rise
 * anywhere along it hides a tank that is in plain view. Ground vehicles were being lost at the
 * hand-over distance for exactly that reason. Inside the built world the answer is always "not
 * hidden", and the depth buffer settles it.
 *
 * <p>Each remaining ghost is asked about every few ticks, not every frame, and the manager spreads
 * the asking out so that no one tick pays for all of them. The worker's queue is bounded; a check
 * that does not fit is dropped and the ghost asked again next time round.
 */
final class GhostOcclusion {
    /** How many checks may wait for the worker at once. */
    private static final int QUEUE_LIMIT = 64;

    /** Blocks left off the end of a ray, so the ground a thing rests on is not "in front of" it. */
    private static final double TARGET_MARGIN = 1.5;

    private static ThreadPoolExecutor worker;

    private GhostOcclusion() {
    }

    /**
     * Begins a check: answers at once if the game's own blocks decide it, or hands the rest to the
     * worker. Game thread.
     *
     * @param eye where the camera is
     * @param now the game tick, recorded as when the check began
     * @return whether the check actually cost a ray. One the depth buffer already answers for costs
     *         none, and the caller's ray budget should go to a ghost that needs it
     */
    static boolean check(ClientLevel level, Vec3 eye, EntityGhost ghost, long now) {
        ghost.beginOcclusion(now);

        GhostSnapshot snapshot = ghost.current();

        // Standing in the built world: not this method's business. See the note on the class.
        if (GhostRenderDispatcher.isBuilt(BlockPos.containing(snapshot.position()))) {
            ghost.finishOcclusion(false);

            return false;
        }
        // Only ground this client actually has can be asked about; beyond it every block reads as
        // air, and following the line out there costs a great deal and finds nothing.
        double loaded = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        List<Vec3> candidates = new ArrayList<>(2);

        // Whatever the game's own blocks do not already hide is what Distant Horizons is asked
        // about; if neither point survives them there is nothing left to ask.
        for (Vec3 candidate : new Vec3[] { snapshot.centre(), snapshot.top() }) {
            if (!blockedByWorld(level, eye, candidate, loaded)) {
                candidates.add(candidate);
            }
        }

        if (candidates.isEmpty()) {
            ghost.finishOcclusion(true);

            return true;
        }

        // A point inside the loaded world that the blocks did not hide is a point in plain sight,
        // and there is no Distant Horizons terrain between here and there to hide it either.
        for (Vec3 candidate : candidates) {
            if (candidate.distanceTo(eye) <= loaded) {
                ghost.finishOcclusion(false);

                return true;
            }
        }

        if (!GhostConfig.occludeBehindDh() || !DHIntegration.isActive()) {
            ghost.finishOcclusion(false);

            return true;
        }

        List<Vec3> asked = List.copyOf(candidates);

        try {
            worker().execute(() -> {
                boolean hidden = true;

                try {
                    for (Vec3 point : asked) {
                        if (!DHIntegration.isOccluded(level, eye, point, loaded)) {
                            hidden = false;
                            break;
                        }
                    }
                } catch (RuntimeException e) {
                    hidden = false;
                }

                ghost.finishOcclusion(hidden);
            });
        } catch (RejectedExecutionException e) {
            // The queue is full; keep the last answer and ask again next time.
            ghost.finishOcclusion(ghost.isOccluded());
        }

        return true;
    }

    /** Whether the game's own blocks, as far as the client has them, stand in the way of a point. */
    private static boolean blockedByWorld(ClientLevel level, Vec3 eye, Vec3 target, double loaded) {
        Vec3 gap = target.subtract(eye);
        double away = gap.length();

        if (away < 1.0E-4) {
            return false;
        }

        double reach = Math.min(loaded, away - TARGET_MARGIN);

        if (reach <= 0.0) {
            return false;
        }

        Vec3 end = eye.add(gap.scale(reach / away));
        // What the eye can see, so glass and foliage are not walls.
        HitResult hit = level.clip(new ClipContext(eye, end,
                ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));

        return hit.getType() != HitResult.Type.MISS;
    }

    /** Drops whatever is queued. Level change. */
    static synchronized void reset() {
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
    }

    private static synchronized ThreadPoolExecutor worker() {
        if (worker == null) {
            worker = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(QUEUE_LIMIT), runnable -> {
                        Thread thread = new Thread(runnable, AshVehicles.MODID + "-ghost-occlusion");
                        thread.setDaemon(true);
                        return thread;
                    });
            worker.allowCoreThreadTimeOut(true);
        }

        return worker;
    }
}
