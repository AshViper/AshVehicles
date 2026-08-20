package com.ashvehicles.client.ghost;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * One entity as the ghost system knows it: the last two snapshots taken of it, and the bookkeeping
 * that decides whether, and how, it is drawn this frame.
 *
 * <p>This is a client-side drawing record and nothing more. It has no AI, no physics and no
 * animation controllers; it does not tick. The only thing that changes it is the manager replacing
 * its snapshots on the game thread, and the only thing that reads it is the render pass.
 *
 * <p>The snapshot references are volatile so the render thread always sees a whole snapshot, never
 * half of one — cheap insurance in a game where render and tick share a thread today and may not
 * tomorrow.
 */
public final class EntityGhost {
    private final UUID uuid;
    private final GhostAdapter<?> adapter;

    private volatile GhostSnapshot current;
    private volatile GhostSnapshot previous;

    /** The live entity, while the client still has one. Cleared the moment it leaves the level. */
    @Nullable
    private Entity entity;

    /** Game tick this ghost was last refreshed from a live entity. */
    private long lastSeenTick;
    /** Game tick the live entity went away, or -1 while it is still here. */
    private long orphanedAt = -1L;

    // Occlusion: begun on the game thread every few ticks, sometimes finished on a worker thread
    // (see GhostOcclusion), read by the render pass. Volatile for that reason.
    private volatile boolean occluded;
    private volatile boolean occlusionPending;
    /** Far enough back that the first check is due at once, without {@code now - this} overflowing. */
    private long occlusionCheckedAt = Long.MIN_VALUE / 2;

    // What the last frame decided, kept for the debug overlay and for the Distant Horizons bridge.
    private GhostLOD lod = GhostLOD.HIDDEN;
    private double distanceSq = Double.MAX_VALUE;
    private boolean drawnLastFrame;
    private int lastLight;
    private boolean lastInWorld;

    /** Whatever the Distant Horizons bridge attaches to this ghost. Untyped so that this class never loads a DH class. */
    @Nullable
    private Object dhHandle;
    private volatile boolean dhDrawn;

    EntityGhost(UUID uuid, GhostAdapter<?> adapter, Entity entity, GhostSnapshot first) {
        this.uuid = uuid;
        this.adapter = adapter;
        this.entity = entity;
        this.current = first;
        this.previous = first;
        this.lastSeenTick = first.gameTime();
    }

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    public UUID uuid() {
        return this.uuid;
    }

    public GhostAdapter<?> adapter() {
        return this.adapter;
    }

    // ------------------------------------------------------------------
    // Snapshots
    // ------------------------------------------------------------------

    public GhostSnapshot current() {
        return this.current;
    }

    public GhostSnapshot previous() {
        return this.previous;
    }

    /** Replaces the snapshots: what was current becomes previous. Game thread only. */
    void update(GhostSnapshot next) {
        this.previous = this.current;
        this.current = next;
        this.lastSeenTick = next.gameTime();
    }

    /** Where the ghost is drawn this frame, between the last two ticks. */
    public Vec3 position(float partialTick) {
        GhostSnapshot now = this.current;
        GhostSnapshot then = this.previous;

        if (then == now) {
            return now.position();
        }

        return new Vec3(
                Mth.lerp(partialTick, then.position().x, now.position().x),
                Mth.lerp(partialTick, then.position().y, now.position().y),
                Mth.lerp(partialTick, then.position().z, now.position().z));
    }

    // ------------------------------------------------------------------
    // The live entity
    // ------------------------------------------------------------------

    /** The entity this ghost stands for, or {@code null} once the client no longer has it. */
    @Nullable
    public Entity entity() {
        return this.entity;
    }

    void attach(Entity entity) {
        this.entity = entity;
        this.orphanedAt = -1L;
    }

    void orphan(long now) {
        this.entity = null;
        this.orphanedAt = now;
    }

    public boolean isOrphaned() {
        return this.entity == null;
    }

    public long orphanedAt() {
        return this.orphanedAt;
    }

    public long lastSeenTick() {
        return this.lastSeenTick;
    }

    // ------------------------------------------------------------------
    // Occlusion
    // ------------------------------------------------------------------

    public boolean isOccluded() {
        return this.occluded;
    }

    /** Whether a check has been begun and not yet answered. */
    boolean isOcclusionPending() {
        return this.occlusionPending;
    }

    /** Marks a check begun this tick. Game thread. */
    void beginOcclusion(long now) {
        this.occlusionCheckedAt = now;
        this.occlusionPending = true;
    }

    /** Records the answer. Game thread or worker thread. */
    void finishOcclusion(boolean occluded) {
        this.occluded = occluded;
        this.occlusionPending = false;
    }

    long occlusionCheckedAt() {
        return this.occlusionCheckedAt;
    }

    // ------------------------------------------------------------------
    // Last frame's verdict
    // ------------------------------------------------------------------

    public GhostLOD lod() {
        return this.lod;
    }

    public double distanceSq() {
        return this.distanceSq;
    }

    public boolean wasDrawnLastFrame() {
        return this.drawnLastFrame;
    }

    void record(GhostLOD lod, double distanceSq, boolean drawn) {
        this.lod = lod;
        this.distanceSq = distanceSq;
        this.drawnLastFrame = drawn;

        if (!drawn) {
            // Nothing was drawn, so the light of the last draw is somebody else's frame. Reported
            // as it stands it reads as fact, which is worse than reporting nothing.
            this.lastLight = 0;
            this.lastInWorld = false;
        }
    }

    /** The packed light the last draw used, and whether it came from the world or from nowhere. */
    public int lastLight() {
        return this.lastLight;
    }

    public boolean wasInWorld() {
        return this.lastInWorld;
    }

    void recordLight(int light, boolean inWorld) {
        this.lastLight = light;
        this.lastInWorld = inWorld;
    }

    // ------------------------------------------------------------------
    // Distant Horizons
    // ------------------------------------------------------------------

    @Nullable
    public Object dhHandle() {
        return this.dhHandle;
    }

    public void setDhHandle(@Nullable Object handle) {
        this.dhHandle = handle;
    }

    /** Whether Distant Horizons is drawing this ghost this tick, so that our pass must not. */
    public boolean isDhDrawn() {
        return this.dhDrawn;
    }

    public void setDhDrawn(boolean dhDrawn) {
        this.dhDrawn = dhDrawn;
    }
}
