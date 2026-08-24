package com.ashvehicles.client.ghost.geo;

import javax.annotation.Nullable;

import com.ashvehicles.client.ghost.EntityGhost;
import com.ashvehicles.client.ghost.GhostSnapshot;

import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animatable.instance.SingletonAnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.util.RenderUtil;

/**
 * A ghost, as something GeckoLib can draw.
 *
 * <p>GeckoLib draws things through a renderer pointed at an animatable, and a ghost is not an
 * entity, so this is the animatable: a holder carrying which ghost is being drawn and how to pose
 * it. One instance is shared by every ghost and pointed at each in turn immediately before its
 * draw — rendering is single-threaded and one draw finishes before the next begins.
 *
 * <p>Sharing the holder does <em>not</em> mean sharing the animation. GeckoLib keeps an
 * {@link AnimatableManager} per instance id rather than per animatable, and
 * {@link GhostGeoRenderer#getInstanceId} gives every ghost its own; a shared animatable with a
 * keyed cache is exactly what {@link SingletonAnimatableInstanceCache} is for. Two aeroplanes with
 * their undercarriages doing different things are therefore two sets of controllers, each with its
 * own state, both reached through this one object.
 *
 * <p>Which controllers a ghost has is its adapter's business: GeckoLib asks
 * {@link #registerControllers} the first time a ghost is drawn, which happens inside that ghost's
 * own draw, so the adapter asked is the right one.
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

    /**
     * The controllers of whichever ghost is being drawn. Called by GeckoLib when it first needs an
     * animation manager for a ghost, which it does part way through that ghost's first draw.
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        if (this.ghost != null) {
            this.ghost.adapter().registerGhostControllers(controllers, this);
        }
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    /**
     * The clock a ghost's animations run on: the game's render clock, which is what GeckoLib uses
     * for anything that is not an entity, and which runs on smoothly between ticks rather than in
     * steps of one.
     */
    @Override
    public double getTick(Object entity) {
        return RenderUtil.getCurrentTick();
    }

    /**
     * Poses the bones of a ghost's model from its snapshots: the rotations that follow the flight
     * from moment to moment, worked out between the last two ticks exactly as the aircraft itself
     * works them out. Anything played from an animation file has already been played by the
     * controllers by the time this runs, and anything set here overrides it — the same order the
     * aircraft's own model uses.
     */
    @FunctionalInterface
    public interface GhostPoser {
        void pose(GeoModel<GhostAnimatable> model, EntityGhost ghost, float partialTick);
    }
}
