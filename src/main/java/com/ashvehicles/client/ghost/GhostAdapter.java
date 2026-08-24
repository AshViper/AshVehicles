package com.ashvehicles.client.ghost;

import javax.annotation.Nullable;

import com.ashvehicles.client.ghost.geo.GhostAnimatable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import software.bernie.geckolib.animation.AnimatableManager;

/**
 * How one kind of entity becomes a ghost and how that ghost is drawn.
 *
 * <p>This is the seam between the ghost system, which knows nothing about aircraft, and the mod,
 * which knows nothing about ghosts. One adapter is registered per entity type in
 * {@link EntityGhostRegistry}; it is asked to photograph the entity once a tick, and to draw the
 * photograph at whichever {@link GhostLOD} the camera distance calls for.
 *
 * <p>Adapters draw from the snapshot, not from the entity. A ghost may outlive the entity on the
 * client that had it, and one that needed the entity would go with it.
 *
 * @param <T> the entity this adapter handles
 */
public interface GhostAdapter<T extends Entity> {
    /**
     * Takes a snapshot of the entity. Game thread, once a tick.
     *
     * @param entity the live entity
     * @param previous the last snapshot taken of it, or {@code null} for the first
     * @param gameTime the current game tick
     */
    GhostSnapshot snapshot(T entity, @Nullable GhostSnapshot previous, long gameTime);

    /**
     * Draws the ghost. The pose stack is at the ghost's origin, facing the world's axes, with the
     * far-plane pull already applied; the adapter supplies orientation and geometry.
     */
    void render(EntityGhost ghost, GhostLOD lod, GhostRenderContext context);

    /**
     * Whether a ghost should be kept, for {@link GhostConfig#timeoutTicks()}, after the client
     * stops receiving the entity without it having died — an entity that merely went out of range
     * is probably still out there. The default says no: ghosts are cheap, but leaving one behind for
     * every mob that wandered away is not.
     */
    default boolean keepAfterLeave(T entity) {
        return false;
    }

    /**
     * Whether the entity is dead or destroyed, as opposed to merely gone from this client. A dead
     * entity's ghost is removed at once, whatever {@link #keepAfterLeave} says.
     */
    default boolean isDead(T entity) {
        if (entity instanceof LivingEntity living && living.isDeadOrDying()) {
            return true;
        }

        Entity.RemovalReason reason = entity.getRemovalReason();

        return reason == Entity.RemovalReason.KILLED;
    }

    /**
     * Whether the world should be traced between the camera and this ghost. Turning it off means
     * the ghost is only ever hidden by the depth buffer, which is the right trade for something
     * numerous and short-lived: the ray budget is small and shared.
     */
    default boolean needsOcclusionCheck() {
        return true;
    }

    /**
     * Registers the animation controllers a ghost of this kind plays, in the same way the entity
     * itself registers its own. GeckoLib asks once per ghost, the first time it is drawn, and what
     * it is given belongs to that ghost alone; a controller reads which ghost it is playing for out
     * of the animatable it is handed, since one animatable serves them all.
     *
     * <p>Most things have nothing to play — a missile is a missile all the way to the target — and
     * the default registers nothing. An aircraft has its undercarriage, and registering the same
     * controller here as the aircraft registers for itself is what keeps a ghost's legs doing what
     * the aeroplane's are doing.
     */
    default void registerGhostControllers(AnimatableManager.ControllerRegistrar controllers,
            GhostAnimatable animatable) {
    }
}
