package com.ashvehicles.client.ghost.dh;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.ghost.EntityGhost;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiCustomRenderObjectFactory;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiCustomRenderRegister;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.data.DhApiRaycastResult;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3d;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The only class that names a Distant Horizons type. Loaded only through {@link DHIntegration},
 * and only when the mod is present.
 *
 * <h2>What was found in Distant Horizons 3.2.0-b, and what is used</h2>
 *
 * <p><b>There is no entity API.</b> Distant Horizons has no notion of entity LODs, and nothing
 * that would let a model be pushed through its pipeline. What its public API
 * ({@code com.seibel.distanthorizons.api}) does offer, and what this class uses:
 * <ul>
 *   <li>{@link DhApi.Delayed#configs} → {@code graphics().renderingEnabled()} and
 *       {@code chunkRenderDistance()}: whether and how far it draws. {@code configs} is null until
 *       the mod has initialised, so every read is null-checked.</li>
 *   <li>{@link DhApi.Delayed#worldProxy} → {@link IDhApiWorldProxy#getAllLoadedLevelWrappers()}:
 *       the {@link IDhApiLevelWrapper} for the client level, matched through
 *       {@code getWrappedMcObject()}. Every other call wants one.</li>
 *   <li>{@link DhApi.Delayed#terrainRepo} → {@link IDhApiTerrainDataRepo#getColumnDataAtBlockPos}:
 *       the LOD column at a block position, as {@link DhApiTerrainDataPoint}s with absolute
 *       {@code bottomYBlockPos}/{@code topYBlockPos} (the repo's own {@code raycast} compares world
 *       Y against them directly). This is how a ghost behind one of its mountains is found:
 *       Distant Horizons draws its terrain into its own framebuffer and merges only colour into
 *       the game's — its {@code shared/gl/apply.frag} writes no {@code gl_FragDepth}, and none of
 *       its shaders do — so the game's depth buffer knows nothing of its terrain and a depth test
 *       alone would draw a ghost straight through a hill. Its {@code raycast()} walks one block at a
 *       time through those columns, and blocks while the data loads, so it is called from a
 *       worker thread and rationed by the manager.</li>
 *   <li>{@link DhApi.Delayed#customRenderObjectFactory} and
 *       {@link IDhApiLevelWrapper#getRenderRegister()}: box groups drawn inside its own pass, with
 *       its depth, its fog and its lighting. The simplified tier uses these when allowed: a
 *       handful of boxes is all an aeroplane is at a kilometre, and drawn there it sits behind the
 *       hill it should sit behind for free. A group's origin is moved with
 *       {@code setOriginBlockPos}; its boxes are mutable and re-uploaded after
 *       {@code triggerBoxChange()}, which only marks them dirty.</li>
 * </ul>
 *
 * <p><b>Where it draws, and why ours draws where it does.</b> Its NeoForge
 * {@code MixinLevelRenderer} injects at the head of {@code LevelRenderer.renderSectionLayer} and
 * renders its terrain when the solid layer comes round — before the game's terrain, before any
 * entity — and then, at the head of the translucent and tripwire layers, runs its <em>vanilla
 * fade</em> ({@code renderFadeOpaque}/{@code renderFadeTransparent}), which repaints whatever the
 * game drew beyond its fade distance with its own terrain wherever it has any. The ghost pass
 * therefore runs at {@code RenderLevelStageEvent.Stage.AFTER_PARTICLES}, after both: by then the
 * game's depth buffer holds its own terrain and entities (against which the ghosts depth-test),
 * its colour buffer holds Distant Horizons' finished terrain (over which they are composited, with
 * the raycast above standing in for the depth it did not leave), and nothing of Distant Horizons'
 * runs afterwards to paint over them. Its {@code MixinGameRenderer} is empty: it does not move the
 * projection's far plane, which is why the far-plane pull in the ghost renderer is still needed
 * with it installed.
 *
 * <p>Nothing here is a mixin and nothing is reflection: the public API is enough. Version
 * dependence is on the {@code DhApi.Delayed} fields and the interfaces above, all of which are
 * part of the published API jar this mod compiles against.
 */
final class DHRendererBridge {
    /** Name every box group is registered under; the ghost's UUID is appended. */
    private static final String GROUP_NAMESPACE = AshVehicles.MODID;

    /** Blocks left off the end of an occlusion ray, so the ground under a target is not "in front" of it. */
    private static final double TARGET_MARGIN = 2.5;

    /** Re-resolve the level wrapper no more often than this, in ticks, when there is none. */
    private static final long WRAPPER_RETRY_TICKS = 20L;

    @Nullable
    private static ClientLevel wrappedLevel;
    @Nullable
    private static IDhApiLevelWrapper wrapper;
    private static long wrapperAskedAt = Long.MIN_VALUE / 2;
    @Nullable
    private static IDhApiTerrainDataCache cache;

    private DHRendererBridge() {
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    static boolean isActive() {
        IDhApiConfig configs = DhApi.Delayed.configs;

        if (configs == null) {
            return false;
        }

        try {
            return Boolean.TRUE.equals(configs.graphics().renderingEnabled().getValue());
        } catch (RuntimeException e) {
            return false;
        }
    }

    static double drawnRadius() {
        IDhApiConfig configs = DhApi.Delayed.configs;

        if (configs == null) {
            return 0.0;
        }

        try {
            if (!Boolean.TRUE.equals(configs.graphics().renderingEnabled().getValue())) {
                return 0.0;
            }

            Integer chunks = configs.graphics().chunkRenderDistance().getValue();

            return chunks == null ? 0.0 : chunks * 16.0;
        } catch (RuntimeException e) {
            return 0.0;
        }
    }

    static void reset() {
        wrappedLevel = null;
        wrapper = null;
        cache = null;
        wrapperAskedAt = Long.MIN_VALUE / 2;
    }

    /** The DH level standing for the client level, or {@code null} if it has none (yet). */
    @Nullable
    private static IDhApiLevelWrapper wrapperFor(ClientLevel level) {
        if (wrapper != null && wrappedLevel == level) {
            return wrapper;
        }

        long now = level.getGameTime();

        if (wrappedLevel == level && now - wrapperAskedAt < WRAPPER_RETRY_TICKS) {
            return null;
        }

        wrappedLevel = level;
        wrapperAskedAt = now;
        wrapper = null;
        cache = null;

        IDhApiWorldProxy world = DhApi.Delayed.worldProxy;

        if (world == null) {
            return null;
        }

        try {
            if (!world.worldLoaded()) {
                return null;
            }

            // In multiplayer the wrapper wraps the client level itself. In singleplayer Distant
            // Horizons runs one level for both sides and hands out the *server* level's wrapper, so
            // the match falls back to the dimension; either wrapper serves the same DH level, and
            // the terrain data and render register are that level's.
            IDhApiLevelWrapper sameDimension = null;

            for (IDhApiLevelWrapper candidate : world.getAllLoadedLevelWrappers()) {
                Object wrapped = candidate.getWrappedMcObject();

                if (wrapped == level) {
                    wrapper = candidate;
                    break;
                }

                if (sameDimension == null && wrapped instanceof Level mcLevel
                        && mcLevel.dimension().equals(level.dimension())) {
                    sameDimension = candidate;
                }
            }

            if (wrapper == null) {
                wrapper = sameDimension;
            }
        } catch (IllegalStateException e) {
            // "No world loaded": asked between worlds. Try again shortly.
            return null;
        }

        return wrapper;
    }

    // ------------------------------------------------------------------
    // Occlusion
    // ------------------------------------------------------------------

    /**
     * Casts the line between two points through Distant Horizons' terrain, with its own
     * {@link IDhApiTerrainDataRepo#raycast}.
     *
     * <p>Block by block, which is what makes it trustworthy — a one-block wall is found as surely
     * as a mountain — and also what makes it unfit for the game thread: a few thousand column
     * lookups, the first of each loaded by Distant Horizons on its own threads while the caller
     * blocks. {@link com.ashvehicles.client.ghost.GhostOcclusion} calls this from a worker for
     * exactly that reason. The part of the line inside the loaded world has already been checked
     * against the game's own blocks and is skipped; the last couple of blocks are left off so that
     * the ground the target stands on does not count as hiding it.
     */
    static boolean isOccluded(ClientLevel level, Vec3 from, Vec3 to, double skip) {
        IDhApiTerrainDataRepo repo = DhApi.Delayed.terrainRepo;

        if (repo == null) {
            return false;
        }

        IDhApiLevelWrapper dhLevel = wrapperFor(level);

        if (dhLevel == null) {
            return false;
        }

        Vec3 gap = to.subtract(from);
        double away = gap.length();
        double length = away - skip - TARGET_MARGIN;

        if (length <= 1.0) {
            return false;
        }

        IDhApiTerrainDataCache dataCache = cache;

        if (dataCache == null) {
            dataCache = repo.createSoftCache();
            cache = dataCache;
        }

        Vec3 direction = gap.scale(1.0 / away);
        Vec3 start = from.add(direction.scale(skip));

        try {
            DhApiResult<DhApiRaycastResult> result = repo.raycast(dhLevel,
                    start.x, start.y, start.z,
                    (float) direction.x, (float) direction.y, (float) direction.z,
                    (int) Math.ceil(length), dataCache);

            return result != null && result.success && result.payload != null;
        } catch (RuntimeException e) {
            // The repo can be asked between level loads; a failed answer is "not hidden".
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Box groups
    // ------------------------------------------------------------------

    /** A word or two more than "active", for the debug overlay: what, if anything, is missing. */
    static String detail(ClientLevel level) {
        if (!isActive()) {
            return "inactive";
        }

        if (wrapperFor(level) == null) {
            return "no level wrapper";
        }

        return boxesAvailable(level) ? "level + boxes" : "level, no boxes";
    }

    static boolean boxesAvailable(ClientLevel level) {
        IDhApiConfig configs = DhApi.Delayed.configs;

        if (configs == null || DhApi.Delayed.customRenderObjectFactory == null) {
            return false;
        }

        try {
            if (!Boolean.TRUE.equals(configs.graphics().renderingEnabled().getValue())
                    || !Boolean.TRUE.equals(configs.graphics().genericRendering().renderingEnabled().getValue())) {
                return false;
            }
        } catch (RuntimeException e) {
            return false;
        }

        return wrapperFor(level) != null;
    }

    static void updateBoxes(ClientLevel level, EntityGhost ghost, List<AABB> boxes, int argb) {
        Handle handle = (Handle) ghost.dhHandle();

        if (boxes.isEmpty()) {
            if (handle != null) {
                handle.group.setActive(false);
            }

            return;
        }

        IDhApiLevelWrapper dhLevel = wrapperFor(level);
        IDhApiCustomRenderObjectFactory factory = DhApi.Delayed.customRenderObjectFactory;

        if (dhLevel == null || factory == null) {
            return;
        }

        // The first box's centre is as good an origin as any; what matters is that every box is
        // near it, so that the floats the group is uploaded as stay precise.
        AABB first = boxes.get(0);
        double ox = (first.minX + first.maxX) * 0.5;
        double oy = (first.minY + first.maxY) * 0.5;
        double oz = (first.minZ + first.maxZ) * 0.5;

        try {
            if (handle != null && (handle.level != dhLevel || handle.group.size() != boxes.size())) {
                remove(handle);
                handle = null;
            }

            if (handle == null) {
                List<DhApiRenderableBox> dhBoxes = new ArrayList<>(boxes.size());
                Color colour = new Color(argb, true);

                for (AABB box : boxes) {
                    dhBoxes.add(new DhApiRenderableBox(
                            new DhApiVec3d(box.minX - ox, box.minY - oy, box.minZ - oz),
                            new DhApiVec3d(box.maxX - ox, box.maxY - oy, box.maxZ - oz),
                            colour, EDhApiBlockMaterial.METAL));
                }

                // Null until the DH level behind the wrapper exists; try again next tick.
                IDhApiCustomRenderRegister register = dhLevel.getRenderRegister();

                if (register == null) {
                    return;
                }

                IDhApiRenderableBoxGroup group = factory.createRelativePositionedGroup(
                        GROUP_NAMESPACE + ":ghost/" + ghost.uuid(), new DhApiVec3d(ox, oy, oz), dhBoxes);
                group.setSkyLight(15);
                group.setBlockLight(0);
                group.setSsaoEnabled(false);
                register.add(group);
                handle = new Handle(dhLevel, group);
                ghost.setDhHandle(handle);
            } else {
                IDhApiRenderableBoxGroup group = handle.group;

                for (int i = 0; i < boxes.size(); i++) {
                    AABB box = boxes.get(i);
                    DhApiRenderableBox dhBox = group.get(i);
                    dhBox.minPos.x = box.minX - ox;
                    dhBox.minPos.y = box.minY - oy;
                    dhBox.minPos.z = box.minZ - oz;
                    dhBox.maxPos.x = box.maxX - ox;
                    dhBox.maxPos.y = box.maxY - oy;
                    dhBox.maxPos.z = box.maxZ - oz;
                }

                group.setOriginBlockPos(new DhApiVec3d(ox, oy, oz));
                group.triggerBoxChange();
            }

            handle.group.setActive(true);
        } catch (RuntimeException e) {
            // Between levels, or a register that no longer exists: drop the handle and try afresh.
            ghost.setDhHandle(null);
        }
    }

    static void removeBoxes(EntityGhost ghost) {
        Handle handle = (Handle) ghost.dhHandle();

        if (handle == null) {
            return;
        }

        ghost.setDhHandle(null);
        remove(handle);
    }

    private static void remove(Handle handle) {
        try {
            handle.group.setActive(false);
            IDhApiCustomRenderRegister register = handle.level.getRenderRegister();

            if (register != null) {
                register.remove(handle.group.getId());
            }
        } catch (RuntimeException e) {
            // The level it was registered in is gone, and the register with it. Nothing to do.
        }
    }

    /** A group and the level it was registered in; the level matters for taking it out again. */
    private record Handle(IDhApiLevelWrapper level, IDhApiRenderableBoxGroup group) {
    }
}
