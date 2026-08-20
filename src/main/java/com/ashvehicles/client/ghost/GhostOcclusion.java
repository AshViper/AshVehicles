package com.ashvehicles.client.ghost;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.ghost.dh.DHIntegration;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
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
 * <p><b>Two points are aimed at, not one.</b> A line to the middle of something standing on the
 * ground, from an eye at about the same height, skims the surface for its whole length, and any
 * rise of a single block along the way reports the thing as hidden when it is in plain sight. So
 * the middle is tried first and the top of it second, and it counts as hidden only when both are
 * blocked — which is also the honest answer to the question being asked, since a shape with its
 * fin showing over a ridge is visible. The line is stopped a little short of the point it is aimed
 * at, so that the ground a thing rests on is never counted as standing in front of it.
 *
 * <p>Each ghost is asked about every few ticks, not every frame, and the manager spreads the
 * asking out so that no one tick pays for all of them. The worker's queue is bounded; a check that
 * does not fit is dropped and the ghost asked again next time round.
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
     */
    static void check(ClientLevel level, Vec3 eye, EntityGhost ghost, long now) {
        ghost.beginOcclusion(now);

        GhostSnapshot snapshot = ghost.current();
        // Only ground this client actually has can be asked about; beyond it every block reads as
        // air, and following the line out there costs a great deal and finds nothing.
        double loaded = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        Vec3 target = null;

        // The middle first, then the top: the first point the game's own blocks do not hide is the
        // one worth asking Distant Horizons about, and if neither survives there is nothing to ask.
        for (Vec3 candidate : new Vec3[] { snapshot.centre(), snapshot.top() }) {
            if (!blockedByWorld(level, eye, candidate, loaded)) {
                target = candidate;
                break;
            }
        }

        if (target == null) {
            ghost.finishOcclusion(true);
            return;
        }

        if (target.distanceTo(eye) <= loaded || !DHIntegration.isActive()) {
            ghost.finishOcclusion(false);
            return;
        }

        Vec3 asked = target;

        try {
            worker().execute(() -> {
                boolean hidden;

                try {
                    hidden = DHIntegration.isOccluded(level, eye, asked, loaded);
                } catch (RuntimeException e) {
                    hidden = false;
                }

                ghost.finishOcclusion(hidden);
            });
        } catch (RejectedExecutionException e) {
            // The queue is full; keep the last answer and ask again next time.
            ghost.finishOcclusion(ghost.isOccluded());
        }
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
