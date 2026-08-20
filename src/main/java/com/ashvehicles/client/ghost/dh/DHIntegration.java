package com.ashvehicles.client.ghost.dh;

import java.util.List;

import com.ashvehicles.client.ghost.EntityGhost;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/**
 * The one door to Distant Horizons, and the only class outside this package that the rest of the
 * ghost system talks to about it.
 *
 * <p>Every method here first asks whether the mod is present and answers "no" or "nothing" when it
 * is not. Only behind that check is {@link DHRendererBridge} touched, and that class is the only one
 * that names a {@code com.seibel} type — so it is never loaded, let alone resolved, in a game that
 * does not have Distant Horizons, and the ghost system carries on drawing without it.
 *
 * <p>What Distant Horizons actually provides, as found in 3.2.0-b (see {@link DHRendererBridge}):
 * <ul>
 *   <li>how far it is drawing terrain, which decides whether a ghost has ground behind it;</li>
 *   <li>its terrain data, column by column, which is the only way to know that one of its
 *       mountains stands between the camera and a ghost — it leaves no depth in the game's depth
 *       buffer;</li>
 *   <li>a register of box groups drawn inside its own pass, which is what the simplified tier
 *       uses when allowed, so that those ghosts are depth-tested, fogged and lit by it.</li>
 * </ul>
 */
public final class DHIntegration {
    private static final boolean LOADED = ModList.get().isLoaded("distanthorizons");

    private DHIntegration() {
    }

    /** Whether the mod is in the mod list at all. */
    public static boolean isLoaded() {
        return LOADED;
    }

    /** Whether it is loaded, initialised, and drawing terrain right now. */
    public static boolean isActive() {
        return LOADED && DHRendererBridge.isActive();
    }

    /** How far it is drawing terrain, in blocks; zero when it is not. */
    public static double drawnRadius() {
        return LOADED ? DHRendererBridge.drawnRadius() : 0.0;
    }

    /**
     * Whether its terrain stands between two points.
     *
     * @param level the client level, to find the matching DH level
     * @param from where the eye is
     * @param to the point looked at
     * @param skip how far along the line, from {@code from}, has already been checked against the
     *        game's own blocks and need not be asked about again
     */
    public static boolean isOccluded(ClientLevel level, Vec3 from, Vec3 to, double skip) {
        return LOADED && DHRendererBridge.isOccluded(level, from, to, skip);
    }

    /** Whether the simplified tier may be drawn as box groups in Distant Horizons' own pass. */
    public static boolean boxesAvailable(ClientLevel level) {
        return LOADED && DHRendererBridge.boxesAvailable(level);
    }

    /**
     * Shows, moves or hides a ghost's box group.
     *
     * @param boxes the boxes, in world coordinates; empty hides the group
     */
    public static void updateBoxes(ClientLevel level, EntityGhost ghost, List<AABB> boxes, int argb) {
        if (LOADED) {
            DHRendererBridge.updateBoxes(level, ghost, boxes, argb);
        }
    }

    /** Removes a ghost's box group, if it has one. Safe to call for any ghost. */
    public static void removeBoxes(EntityGhost ghost) {
        if (LOADED) {
            DHRendererBridge.removeBoxes(ghost);
        }
    }

    /** Forgets everything tied to the old level. */
    public static void onLevelChanged() {
        if (LOADED) {
            DHRendererBridge.reset();
        }
    }

    /** One word for the debug overlay. */
    public static String status() {
        if (!LOADED) {
            return "ABSENT";
        }

        return DHRendererBridge.isActive() ? "ACTIVE" : "INACTIVE";
    }

    /** A little more than {@link #status()}: what the bridge has and has not managed to reach. */
    public static String detail(ClientLevel level) {
        return LOADED ? DHRendererBridge.detail(level) : "absent";
    }
}
