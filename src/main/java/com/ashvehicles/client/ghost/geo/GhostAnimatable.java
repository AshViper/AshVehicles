package com.ashvehicles.client.ghost.geo;

import javax.annotation.Nullable;

import com.ashvehicles.client.ghost.EntityGhost;
import com.ashvehicles.client.ghost.GhostSnapshot;

import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animatable.instance.SingletonAnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.model.GeoModel;

/**
 * A ghost, as something GeckoLib can draw.
 *
 * <p>GeckoLib draws things through a renderer pointed at an animatable, and a ghost is not an
 * entity, so this is the animatable: a holder carrying which ghost is being drawn and how to pose
 * it. There are no animation controllers — a ghost plays no animations — so one instance is shared
 * by every ghost, set immediately before each draw, exactly as {@code MountedStore} is shared by
 * every pylon. Rendering is single-threaded and each draw finishes before the next begins.
 */
public final class GhostAnimatable implements GeoAnimatable {
    private static final GhostAnimatable INSTANCE = new GhostAnimatable();

    private final AnimatableInstanceCache cache = new SingletonAnimatableInstanceCache(this);

    private EntityGhost ghost;
    private GhostSnapshot snapshot;
    @Nullable
    private GhostPoser poser;

    private GhostAnimatable() {
    }

    /** The shared holder, set to draw the given ghost. */
    static GhostAnimatable of(EntityGhost ghost, GhostSnapshot snapshot, @Nullable GhostPoser poser) {
        INSTANCE.ghost = ghost;
        INSTANCE.snapshot = snapshot;
        INSTANCE.poser = poser;

        return INSTANCE;
    }

    public EntityGhost ghost() {
        return this.ghost;
    }

    public GhostSnapshot snapshot() {
        return this.snapshot;
    }

    @Nullable
    GhostPoser poser() {
        return this.poser;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    /** Ghosts play no animations, so there is no clock to report. */
    @Override
    public double getTick(Object entity) {
        return 0.0;
    }

    /**
     * Poses the bones of a ghost's model from its snapshot: a few rotations, read straight from
     * what the entity was doing when it was last seen. This is the whole of a ghost's animation —
     * no controllers, no keyframes, no clock.
     */
    @FunctionalInterface
    public interface GhostPoser {
        void pose(GeoModel<GhostAnimatable> model, GhostSnapshot snapshot);
    }
}
