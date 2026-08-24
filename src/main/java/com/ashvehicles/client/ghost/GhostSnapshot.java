package com.ashvehicles.client.ghost;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaternionf;

/**
 * Everything the ghost renderer needs to know about an entity, copied out of it in one go.
 *
 * <p>Taken on the game thread once a tick and handed to the render thread as a whole. Being
 * immutable is the point: the renderer reads a snapshot that cannot change under it, and the next
 * tick replaces the reference rather than the contents. Positions are interpolated between the
 * previous snapshot and this one at render time, exactly as the game interpolates an entity between
 * its last tick and this.
 *
 * @param uuid the entity's UUID, which is the ghost's identity; entity ids are reused, UUIDs are not
 * @param entityId the entity's network id, for finding it again while it still exists
 * @param type what kind of entity it is
 * @param position where it was at the tick the snapshot was taken
 * @param velocity how it was moving, in blocks per tick
 * @param yaw heading in degrees, the game's convention
 * @param pitch pitch in degrees
 * @param bodyYaw body heading in degrees; the same as {@code yaw} for anything without a separate body
 * @param attitude the full orientation as a rotation, for things whose orientation is more than two
 *        angles; {@code null} when yaw and pitch tell the whole story
 * @param scale model scale
 * @param shade how much of its own colour the thing keeps, 1 for all of it. The one thing about how
 *        a ghost looks that is the entity's business rather than the distance's: a machine that has
 *        been written off is charred wherever it is being drawn from, and a ghost drawn in the
 *        aeroplane's colours would have a wreck come back to life at the hand-over distance
 * @param model the geometry file to draw the ghost from, or {@code null} if it has none
 * @param texture the texture to draw it with, or {@code null}
 * @param animation the animation file whose cycles the ghost plays, or {@code null} for a ghost
 *        with nothing to play
 * @param billboard the flat icon for the furthest tier, or {@code null} for none
 * @param bounds the box the entity is drawn within, relative to {@code position}: what the ghost
 *        is culled against, what the occlusion trace is aimed at and what a billboard is sized by.
 *        Deliberately not the box the entity collides with, which for these machines is kept
 *        smaller than they are
 * @param useGeckoLib whether {@code model} is a GeckoLib geometry file
 * @param gameTime the game tick the snapshot was taken on
 * @param payload anything else the adapter that took this snapshot wants back when it draws it
 */
public record GhostSnapshot(
        UUID uuid,
        int entityId,
        EntityType<?> type,
        Vec3 position,
        Vec3 velocity,
        float yaw,
        float pitch,
        float bodyYaw,
        @Nullable Quaternionf attitude,
        float scale,
        float shade,
        @Nullable ResourceLocation model,
        @Nullable ResourceLocation texture,
        @Nullable ResourceLocation animation,
        @Nullable ResourceLocation billboard,
        AABB bounds,
        boolean useGeckoLib,
        long gameTime,
        @Nullable Object payload) {

    /** The box this ghost occupies in the world, for culling and for the debug outline. */
    public AABB worldBounds() {
        return this.bounds.move(this.position);
    }

    /** The middle of the entity rather than its feet, which is what occlusion is aimed at first. */
    public Vec3 centre() {
        return this.position.add(0.0, (this.bounds.minY + this.bounds.maxY) * 0.5, 0.0);
    }

    /**
     * The top of the entity, which occlusion falls back to: something on the ground, seen from an
     * eye at about its own height, has a line to its middle that skims the surface all the way.
     */
    public Vec3 top() {
        return this.position.add(0.0, this.bounds.maxY, 0.0);
    }
}
