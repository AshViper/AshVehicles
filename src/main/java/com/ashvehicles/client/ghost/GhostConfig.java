package com.ashvehicles.client.ghost;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-side settings for the ghost system: where each tier of drawing begins and ends, how many
 * ghosts are worth drawing, and which of the optional parts are switched on.
 *
 * <p>Distances are in blocks and compared squared everywhere else; the squared values are cached
 * here when the config loads or changes, so that the render loop never squares anything itself.
 *
 * <p>Registered as a {@code CLIENT} config from {@link com.ashvehicles.AshVehiclesClient}: nothing
 * in it means anything to a server.
 */
public final class GhostConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue GHOST_ENABLED = BUILDER
            .comment("Whether entities beyond the normal drawing range are drawn as ghosts at all.")
            .define("ghostEnabled", true);

    public static final ModConfigSpec.DoubleValue GHOST_START_DISTANCE = BUILDER
            .comment("Distance in blocks at which the normal entity renderer hands over to the ghost renderer.",
                    "Keep this inside the vanilla render distance. Distant Horizons fades vanilla rendering out",
                    "towards its own terrain near that edge, and anything the game's own entity loop draws out",
                    "there is dissolved into the LOD colour with it; the ghost pass draws after that fade and is",
                    "not touched by it. Inside the built world a ghost is drawn with the world's own light and fog,",
                    "so handing over early costs nothing in appearance.")
            .defineInRange("ghostStartDistance", 128.0, 16.0, 8192.0);

    public static final ModConfigSpec.DoubleValue GHOST_SIMPLIFIED_DISTANCE = BUILDER
            .comment("Distance in blocks beyond which a ghost is drawn simplified: a static model with no animation.")
            .defineInRange("ghostSimplifiedDistance", 512.0, 16.0, 16384.0);

    public static final ModConfigSpec.DoubleValue GHOST_END_DISTANCE = BUILDER
            .comment("Distance in blocks beyond which nothing is drawn at all.")
            .defineInRange("ghostEndDistance", 2048.0, 16.0, 65536.0);

    public static final ModConfigSpec.DoubleValue BILLBOARD_DISTANCE = BUILDER
            .comment("Distance in blocks beyond which a simplified ghost becomes a flat billboard (only if enableBillboardLOD is on).")
            .defineInRange("billboardDistance", 1024.0, 16.0, 65536.0);

    public static final ModConfigSpec.IntValue MAX_GHOST_ENTITIES = BUILDER
            .comment("Most ghosts drawn in one frame. The nearest are drawn first.")
            .defineInRange("maxGhostEntities", 256, 1, 4096);

    public static final ModConfigSpec.BooleanValue ENABLE_GECKOLIB_GHOST = BUILDER
            .comment("Whether ghosts of GeckoLib entities are drawn from their GeckoLib model. Off, they are drawn as billboards only.")
            .define("enableGeckoLibGhost", true);

    public static final ModConfigSpec.BooleanValue ENABLE_ANIMATION = BUILDER
            .comment("Whether ghosts between ghostStartDistance and ghostSimplifiedDistance carry a simplified pose",
                    "(undercarriage, flaps, control surfaces taken from the last snapshot). No animation controllers run either way.")
            .define("enableAnimation", true);

    public static final ModConfigSpec.BooleanValue ENABLE_BILLBOARD_LOD = BUILDER
            .comment("Whether the furthest tier is drawn as a flat camera-facing icon instead of a model.")
            .define("enableBillboardLOD", false);

    public static final ModConfigSpec.BooleanValue USE_DH_BOX_LOD = BUILDER
            .comment("With Distant Horizons present: draw simplified ghosts as box groups inside Distant Horizons' own",
                    "render pass, so they are depth-tested, fogged and lit against its terrain rather than ours.")
            .define("useDistantHorizonsBoxLod", true);

    public static final ModConfigSpec.IntValue GHOST_TIMEOUT_TICKS = BUILDER
            .comment("How long, in ticks, a ghost outlives an entity the client stopped receiving without it having died.")
            .defineInRange("ghostTimeoutTicks", 200, 0, 72000);

    public static final ModConfigSpec.IntValue OCCLUSION_INTERVAL_TICKS = BUILDER
            .comment("How often, in ticks, each ghost re-checks whether terrain stands between it and the camera.")
            .defineInRange("occlusionIntervalTicks", 10, 1, 200);

    public static final ModConfigSpec.IntValue MAX_OCCLUSION_RAYS_PER_TICK = BUILDER
            .comment("Most occlusion checks run in one tick. Checks beyond this wait for the next tick.")
            .defineInRange("maxOcclusionRaysPerTick", 8, 1, 512);

    public static final ModConfigSpec.BooleanValue DEBUG_OVERLAY = BUILDER
            .comment("Adds ghost statistics to the F3 screen.")
            .define("debugOverlay", false);

    public static final ModConfigSpec.BooleanValue DEBUG_BOXES = BUILDER
            .comment("Draws a red box around every ghost.")
            .define("debugBoxes", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    // Cached squared distances, refreshed by refresh() whenever the config is (re)loaded.
    private static double startSq = 128.0 * 128.0;
    private static double simplifiedSq = 512.0 * 512.0;
    private static double endSq = 2048.0 * 2048.0;
    private static double billboardSq = 1024.0 * 1024.0;
    private static boolean loaded;

    private GhostConfig() {
    }

    /** Re-reads the distances. Called from the config load and reload events. */
    public static void refresh() {
        double start = GHOST_START_DISTANCE.get();
        // Tiers must nest: a simplified distance inside the start distance is a configuration
        // mistake, and the tidy answer is to push it out rather than to hide a tier entirely.
        double simplified = Math.max(start, GHOST_SIMPLIFIED_DISTANCE.get());
        double end = Math.max(simplified, GHOST_END_DISTANCE.get());
        double billboard = Math.max(simplified, BILLBOARD_DISTANCE.get());

        startSq = start * start;
        simplifiedSq = simplified * simplified;
        endSq = end * end;
        billboardSq = billboard * billboard;
        loaded = true;
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static boolean enabled() {
        return loaded && GHOST_ENABLED.get();
    }

    public static double startDistance() {
        return Math.sqrt(startSq);
    }

    public static double startSq() {
        return startSq;
    }

    public static double simplifiedSq() {
        return simplifiedSq;
    }

    public static double endSq() {
        return endSq;
    }

    public static double billboardSq() {
        return billboardSq;
    }

    public static int maxGhosts() {
        return loaded ? MAX_GHOST_ENTITIES.get() : 256;
    }

    public static boolean geckoLibGhosts() {
        return loaded && ENABLE_GECKOLIB_GHOST.get();
    }

    public static boolean animation() {
        return loaded && ENABLE_ANIMATION.get();
    }

    public static boolean billboards() {
        return loaded && ENABLE_BILLBOARD_LOD.get();
    }

    public static boolean dhBoxLod() {
        return loaded && USE_DH_BOX_LOD.get();
    }

    public static int timeoutTicks() {
        return loaded ? GHOST_TIMEOUT_TICKS.get() : 200;
    }

    public static int occlusionInterval() {
        return loaded ? OCCLUSION_INTERVAL_TICKS.get() : 10;
    }

    public static int maxOcclusionRays() {
        return loaded ? MAX_OCCLUSION_RAYS_PER_TICK.get() : 8;
    }

    public static boolean debugOverlay() {
        return loaded && DEBUG_OVERLAY.get();
    }

    public static boolean debugBoxes() {
        return loaded && DEBUG_BOXES.get();
    }
}
