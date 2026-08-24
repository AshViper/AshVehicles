package com.ashvehicles.client.ghost;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.ghost.dh.DHIntegration;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

/**
 * Keeps the ghosts: one per registered entity the client knows about, refreshed once a tick from
 * the entity while it is here and kept a little while after it has gone.
 *
 * <p>The manager lives on the game thread. It learns about entities from the join and leave
 * events rather than by searching the level — the level is never scanned, on any thread — and it
 * does its work once a tick: take a snapshot of each entity, decide which tier each ghost is in,
 * and spend a small budget of occlusion checks. The render pass reads the result; it adds nothing
 * and removes nothing.
 *
 * <p>Ghosts are keyed by UUID. Entity ids are reused and a ghost may outlive the entity whose id
 * it was given; a UUID is forever.
 *
 * <p>Nothing here is allowed to leak. A ghost is removed when its entity dies, when the client
 * stops receiving it (at once, unless its adapter asks for it to be kept, and then after
 * {@link GhostConfig#timeoutTicks()}), and altogether when the level changes.
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class EntityGhostManager {
    /** Concurrent so that the render thread may read it while the game thread writes it. */
    private static final Map<UUID, EntityGhost> GHOSTS = new ConcurrentHashMap<>();
    /** The same, as the read-only view {@link #ghosts()} hands out. Wrapped once, not once a frame. */
    private static final Collection<EntityGhost> VIEW = Collections.unmodifiableCollection(GHOSTS.values());
    /** The tick's working list, kept between ticks rather than made fresh. Game thread only. */
    private static final List<EntityGhost> ORDERED = new ArrayList<>();
    /** Nearest first: the draw budget and the Distant Horizons budget both favour the near. */
    private static final Comparator<EntityGhost> NEAREST_FIRST =
            Comparator.comparingDouble(EntityGhost::distanceSq);

    @Nullable
    private static ClientLevel level;
    private static int occlusionRaysThisTick;

    // Last tick's figures, for the debug overlay.
    private static int countGhost;
    private static int countBillboard;
    private static int countOccluded;
    private static int countOrphaned;

    private EntityGhostManager() {
    }

    // ------------------------------------------------------------------
    // What the render pass reads
    // ------------------------------------------------------------------

    public static Collection<EntityGhost> ghosts() {
        return VIEW;
    }

    @Nullable
    public static EntityGhost ghostOf(Entity entity) {
        return GHOSTS.get(entity.getUUID());
    }

    public static int size() {
        return GHOSTS.size();
    }

    // ------------------------------------------------------------------
    // Entities arriving and leaving
    // ------------------------------------------------------------------

    @SubscribeEvent
    static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ClientLevel joined)) {
            return;
        }

        Entity entity = event.getEntity();
        GhostAdapter<Entity> adapter = EntityGhostRegistry.adapterFor(entity);

        if (adapter == null) {
            return;
        }

        // A change of level is noticed here as well as in the tick, and it has to be. Entities
        // arrive as their packets are handled, which is between ticks, so the first machines of a
        // new world join before the tick that would notice the world had changed — and that tick
        // then clears them along with the old world's. Nothing ever makes those ghosts again: the
        // manager learns about entities from this event and never scans the level. The machines
        // they stood for are drawn by nobody from the hand-over distance out.
        levelChanged(joined);

        long now = joined.getGameTime();
        EntityGhost ghost = GHOSTS.get(entity.getUUID());

        if (ghost != null) {
            // The same entity, back again: carry on from where its ghost was.
            ghost.attach(entity);
            ghost.update(adapter.snapshot(entity, ghost.current(), now));
            return;
        }

        GhostSnapshot first = adapter.snapshot(entity, null, now);
        GHOSTS.put(entity.getUUID(), new EntityGhost(entity.getUUID(), adapter, entity, first));
    }

    @SubscribeEvent
    static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }

        Entity entity = event.getEntity();
        EntityGhost ghost = GHOSTS.get(entity.getUUID());

        if (ghost != null && ghost.entity() == entity) {
            entityGone(ghost, entity, event.getLevel().getGameTime());
        }
    }

    /** The client no longer has this entity: decide whether its ghost stays a while or goes now. */
    @SuppressWarnings("unchecked")
    private static void entityGone(EntityGhost ghost, Entity entity, long now) {
        GhostAdapter<Entity> adapter = (GhostAdapter<Entity>) ghost.adapter();

        if (adapter.isDead(entity) || !adapter.keepAfterLeave(entity) || GhostConfig.timeoutTicks() <= 0) {
            remove(ghost);
        } else {
            ghost.orphan(now);
        }
    }

    private static void remove(EntityGhost ghost) {
        GHOSTS.remove(ghost.uuid());
    }

    /**
     * Notices that the level has changed, and forgets the one before it. Asked from the tick and
     * from the join event, since either may be the first to see a new level. Game thread.
     */
    private static void levelChanged(@Nullable ClientLevel current) {
        if (current == level) {
            return;
        }

        clear();
        level = current;
    }

    /** Forgets everything. Level change, logout, or the system being switched off. */
    public static void clear() {
        GHOSTS.clear();
        ORDERED.clear();
        GhostOcclusion.reset();
        DHIntegration.onLevelChanged();
        countGhost = countBillboard = countOccluded = countOrphaned = 0;
    }

    // ------------------------------------------------------------------
    // The tick
    // ------------------------------------------------------------------

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel current = minecraft.level;

        levelChanged(current);

        if (current == null) {
            return;
        }

        if (!GhostConfig.enabled()) {
            if (!GHOSTS.isEmpty()) {
                clear();
            }

            return;
        }

        long now = current.getGameTime();
        Vec3 eye = minecraft.gameRenderer.getMainCamera().getPosition();
        int timeout = GhostConfig.timeoutTicks();
        int interval = GhostConfig.occlusionInterval();
        occlusionRaysThisTick = 0;

        // Refreshed from the entity, or timed out, in one pass.
        List<EntityGhost> ordered = ORDERED;
        ordered.clear();

        for (Iterator<EntityGhost> it = GHOSTS.values().iterator(); it.hasNext();) {
            EntityGhost ghost = it.next();
            Entity entity = ghost.entity();

            if (entity != null) {
                if (entity.isRemoved() || entity.level() != current) {
                    // Belt and braces: the leave event normally gets here first.
                    entityGone(ghost, entity, now);

                    if (!GHOSTS.containsKey(ghost.uuid())) {
                        continue;
                    }
                } else {
                    refresh(ghost, entity, now);
                }
            } else if (now - ghost.orphanedAt() > timeout) {
                it.remove();
                continue;
            }

            double distanceSq = ghost.current().position().distanceToSqr(eye);
            ghost.record(GhostLOD.of(distanceSq), distanceSq, ghost.verdict());
            ordered.add(ghost);
        }

        ordered.sort(NEAREST_FIRST);

        int budget = GhostConfig.maxGhosts();
        int maxRays = GhostConfig.maxOcclusionRays();
        countGhost = countBillboard = countOccluded = countOrphaned = 0;

        for (int i = 0; i < ordered.size(); i++) {
            EntityGhost ghost = ordered.get(i);
            GhostLOD lod = ghost.lod();
            boolean inBudget = i < budget;

            // A ghost whose adapter has said it is not worth the ray budget takes none of it.
            if (lod.isGhost() && inBudget && ghost.adapter().needsOcclusionCheck()
                    && !ghost.isOcclusionPending()
                    && now - ghost.occlusionCheckedAt() >= interval && occlusionRaysThisTick < maxRays) {
                // Staggered by the interval: a ghost checked this tick is not checked again for a
                // while, so the cost spreads itself across ticks without any scheduling. A ghost
                // inside the built world answers without a ray and takes none of the budget, which
                // is what leaves the whole of it for the ones out past the world that need it.
                if (GhostOcclusion.check(current, eye, ghost, now)) {
                    occlusionRaysThisTick++;
                }
            }

            switch (lod) {
                case GHOST -> countGhost++;
                case BILLBOARD -> countBillboard++;
                default -> {
                }
            }

            if (ghost.isOccluded()) {
                countOccluded++;
            }

            if (ghost.isOrphaned()) {
                countOrphaned++;
            }
        }

        // Nothing is held between ticks: a ghost kept here would outlive its own removal.
        ordered.clear();
    }

    @SuppressWarnings("unchecked")
    private static void refresh(EntityGhost ghost, Entity entity, long now) {
        GhostAdapter<Entity> adapter = (GhostAdapter<Entity>) ghost.adapter();
        ghost.update(adapter.snapshot(entity, ghost.current(), now));
    }

    // ------------------------------------------------------------------
    // Debug figures
    // ------------------------------------------------------------------

    public static int countGhost() {
        return countGhost;
    }

    public static int countBillboard() {
        return countBillboard;
    }

    public static int countOccluded() {
        return countOccluded;
    }

    public static int countOrphaned() {
        return countOrphaned;
    }

}
