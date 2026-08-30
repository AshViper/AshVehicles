package com.ashvehicles.client.ghost;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * ゴーストシステムのクライアント側設定。各描画階層の開始・終了距離、描く価値のあるゴースト数、有効化する
 * オプション部分。
 *
 * <p>距離はブロック単位で、他の場所では2乗して比較する。2乗値は設定のロード時・変更時にここでキャッシュする
 * ので、描画ループが自分で2乗することは無い。
 *
 * <p>{@link com.ashvehicles.AshVehiclesClient} から {@code CLIENT} 設定として登録する。中身はサーバーにとって
 * 何の意味も持たない。
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

    public static final ModConfigSpec.DoubleValue GHOST_END_DISTANCE = BUILDER
            .comment("Distance in blocks beyond which nothing is drawn at all. 0 means no limit:",
                    "everything the server reports is drawn, however far away it is. The renderer is",
                    "built for either answer -- a ghost past the projection's far plane is drawn",
                    "pulled-in and shrunk by the same measure, so distance costs nothing extra to",
                    "draw, and what bounds the work is maxGhostEntities rather than the range.")
            .defineInRange("ghostEndDistance", 0.0, 0.0, 65536.0);

    public static final ModConfigSpec.DoubleValue BILLBOARD_DISTANCE = BUILDER
            .comment("Distance in blocks beyond which a ghost becomes a flat billboard (only if enableBillboardLOD is on).")
            .defineInRange("billboardDistance", 1024.0, 16.0, 65536.0);

    public static final ModConfigSpec.IntValue MAX_GHOST_ENTITIES = BUILDER
            .comment("Most ghosts drawn in one frame. The nearest are drawn first.")
            .defineInRange("maxGhostEntities", 256, 1, 4096);

    public static final ModConfigSpec.BooleanValue ENABLE_GECKOLIB_GHOST = BUILDER
            .comment("Whether ghosts of GeckoLib entities are drawn from their GeckoLib model. Off, they are drawn as billboards only.")
            .define("enableGeckoLibGhost", true);

    public static final ModConfigSpec.BooleanValue ENABLE_ANIMATION = BUILDER
            .comment("Whether ghosts move as the aircraft they stand for does: control surfaces, flaps, nozzle and",
                    "rotors posed from the last snapshot, and the undercarriage cycle played from the animation file",
                    "exactly as the aircraft's own renderer plays it. Off, the model is drawn in the pose it was",
                    "authored in.")
            .define("enableAnimation", true);

    public static final ModConfigSpec.BooleanValue ENABLE_BILLBOARD_LOD = BUILDER
            .comment("Whether the furthest tier is drawn as a flat camera-facing icon instead of a model.")
            .define("enableBillboardLOD", false);

    public static final ModConfigSpec.IntValue GHOST_TIMEOUT_TICKS = BUILDER
            .comment("How long, in ticks, a ghost outlives an entity the client stopped receiving without it having died.")
            .defineInRange("ghostTimeoutTicks", 200, 0, 72000);

    public static final ModConfigSpec.IntValue MACHINE_GHOST_TIMEOUT_TICKS = BUILDER
            .comment("How long, in ticks, a stationary machine's ghost outlives the entity after its chunk",
                    "unloads. A parked aircraft or tank far from every player is unloaded with its ground, not",
                    "destroyed - and ground nobody has loaded cannot change, so its last known image stays true",
                    "until somebody goes there. 0 keeps the ghost for the whole session.")
            .defineInRange("machineGhostTimeoutTicks", 0, 0, 1728000);

    public static final ModConfigSpec.IntValue OCCLUSION_INTERVAL_TICKS = BUILDER
            .comment("How often, in ticks, each ghost re-checks whether terrain stands between it and the camera.")
            .defineInRange("occlusionIntervalTicks", 10, 1, 200);

    public static final ModConfigSpec.BooleanValue OCCLUDE_BEHIND_DH = BUILDER
            .comment("Whether Distant Horizons' terrain hides a ghost standing behind it.",
                    "Its terrain leaves no depth for the game to test against, so the line has to be traced through",
                    "its data instead — and that data is an average of the real ground which gets coarser the further",
                    "out it is drawn. Where that matters more than a hill drawn through, turn this off: ghosts are",
                    "then hidden only by ground the client itself has.")
            .define("occludeBehindDistantHorizons", true);

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

    // 2乗距離のキャッシュ。設定が(再)ロードされるたび refresh() が更新する。初期値は上の既定値に合わせてある。
    private static double startSq = 128.0 * 128.0;
    private static double endSq = Double.POSITIVE_INFINITY;
    private static double billboardSq = 1024.0 * 1024.0;
    private static boolean loaded;

    private GhostConfig() {
    }

    /** 距離を読み直す。設定のロード・リロードイベントから呼ばれる。 */
    public static void refresh() {
        double start = GHOST_START_DISTANCE.get();
        double limit = GHOST_END_DISTANCE.get();
        // 階層は入れ子でなければならない。開始距離の内側にある終了距離は設定ミスであり、階層を丸ごと隠すより
        // 外へ押し出すのが綺麗な対処だ。0は「終端無し」を意味する。無限大は2乗しても無限大で、全ての距離が
        // その下に収まる。
        double end = limit <= 0.0 ? Double.POSITIVE_INFINITY : Math.max(start, limit);
        double billboard = Math.max(start, BILLBOARD_DISTANCE.get());

        startSq = start * start;
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

    public static int timeoutTicks() {
        return loaded ? GHOST_TIMEOUT_TICKS.get() : 200;
    }

    /** 静止した機体・車両の孤児ゴーストの寿命。0(既定)は「セッションの間ずっと」で、上限なしとして返す。 */
    public static int machineTimeoutTicks() {
        int ticks = loaded ? MACHINE_GHOST_TIMEOUT_TICKS.get() : 0;

        return ticks <= 0 ? Integer.MAX_VALUE : ticks;
    }

    public static int occlusionInterval() {
        return loaded ? OCCLUSION_INTERVAL_TICKS.get() : 10;
    }

    public static boolean occludeBehindDh() {
        return loaded && OCCLUDE_BEHIND_DH.get();
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
