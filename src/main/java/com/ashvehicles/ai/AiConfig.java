package com.ashvehicles.ai;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.ai.role.VehicleRole;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 戦闘 AI の設定。ワールドごとの {@code serverconfig/ashvehicles-ai-server.toml}。
 *
 * <p><b>ここに置くのは「エンジンの値」だけ。</b> 処理の周期、1 tick あたりの予算、記録の出し方、報酬の点数、
 * 自己対戦の進め方——どれも AI の強さではなく、サーバーの負荷と実験の段取りを決める物だ。<b>AI が何を優先するか
 * （方針の重み・拠点の点数・経路で危険をどれだけ嫌うか）はここに無い。</b>あれは版ファイル
 * （{@code learning/AiVersions}）が持つ。版は自己対戦で比べて入れ替える物で、1つのワールド設定に焼き付けると
 * 比べられなくなる。
 *
 * <p><b>SERVER 型にしてある。</b> AI はサーバーにしか居ないし、自己対戦の実験はワールドごとに条件を変えたい。
 *
 * <p>値は読み込み時に {@link Settings} へ写す。{@code Config} と同じ理由で、設定オブジェクトを毎回引かない——
 * 40両が毎 tick 何度も訊く値で、しかも読み込み前に訊かれると例外を投げる。読み込み前と降ろした後は既定値を返す。
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class AiConfig {
    /** 設定ファイルの名前。 */
    public static final String FILE = "ashvehicles-ai-server.toml";

    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.IntValue PERCEPTION_TICKS;
    private static final ModConfigSpec.IntValue DECISION_TICKS;
    private static final ModConfigSpec.IntValue TARGET_TICKS;
    private static final ModConfigSpec.IntValue OBJECTIVE_TICKS;
    private static final ModConfigSpec.IntValue THREAT_MAP_TICKS;
    private static final ModConfigSpec.IntValue TACTICS_TICKS;
    private static final ModConfigSpec.IntValue REPLAN_TICKS;

    private static final ModConfigSpec.IntValue RAYS_PER_TICK;
    private static final ModConfigSpec.IntValue PLANS_PER_TICK;
    private static final ModConfigSpec.IntValue SEARCHES_PER_TICK;
    private static final ModConfigSpec.IntValue PLAN_EXPANSIONS;

    private static final ModConfigSpec.DoubleValue SIGHT_RANGE;
    private static final ModConfigSpec.IntValue RAYS_PER_PASS;
    private static final ModConfigSpec.IntValue LOS_CACHE_TICKS;
    private static final ModConfigSpec.IntValue MEMORY_TICKS;
    private static final ModConfigSpec.IntValue ATTACKED_WINDOW_TICKS;

    private static final ModConfigSpec.IntValue THREAT_SOURCES;
    private static final ModConfigSpec.DoubleValue THREAT_RADIUS;
    private static final ModConfigSpec.DoubleValue THREAT_DECAY;
    private static final ModConfigSpec.DoubleValue UNKNOWN_EXPOSURE;
    private static final ModConfigSpec.IntValue TERRAIN_CACHE_TICKS;

    private static final ModConfigSpec.BooleanValue LOG_BATTLES;
    private static final ModConfigSpec.BooleanValue LOG_DECISIONS;
    private static final ModConfigSpec.ConfigValue<String> OUTPUT_DIRECTORY;

    private static final ModConfigSpec.DoubleValue ENEMY_DESTROYED;
    private static final ModConfigSpec.DoubleValue OBJECTIVE_CAPTURED;
    private static final ModConfigSpec.DoubleValue OBJECTIVE_RECAPTURED;
    private static final ModConfigSpec.DoubleValue OBJECTIVE_DEFENDED;
    private static final ModConfigSpec.DoubleValue ALLY_SUPPORTED;
    private static final ModConfigSpec.DoubleValue GOOD_FLANK;
    private static final ModConfigSpec.DoubleValue VEHICLE_DESTROYED;
    private static final ModConfigSpec.DoubleValue OBJECTIVE_LOST;
    private static final ModConfigSpec.DoubleValue UNNECESSARY_DEATH;
    private static final ModConfigSpec.DoubleValue LONG_EXPOSURE;
    private static final ModConfigSpec.IntValue LONG_EXPOSURE_TICKS;
    private static final ModConfigSpec.DoubleValue WATER_ENTERED;
    private static final ModConfigSpec.DoubleValue UNDERWATER;
    private static final ModConfigSpec.IntValue UNDERWATER_TICKS;

    private static final ModConfigSpec.BooleanValue MAP_MEMORY;
    private static final ModConfigSpec.DoubleValue MAP_KEEP;

    private static final ModConfigSpec.ConfigValue<String> DEFAULT_VERSION;
    private static final ModConfigSpec.IntValue PROMOTE_MIN_BATTLES;
    private static final ModConfigSpec.DoubleValue PROMOTE_WIN_RATE;
    private static final ModConfigSpec.IntValue SELF_PLAY_COOLDOWN;
    private static final ModConfigSpec.BooleanValue SELF_PLAY_ALTERNATE;

    private static final ModConfigSpec.ConfigValue<List<? extends String>> ROLE_OVERRIDES;

    private static final ModConfigSpec.DoubleValue DEBUG_RANGE;
    private static final ModConfigSpec.IntValue DEBUG_MAX_MACHINES;

    /** 周期（tick）。 */
    public record Timing(int perception, int decision, int target, int objective, int threatMap, int tactics,
            int replan) {
    }

    /** サーバー全体の 1 tick あたりの上限。 */
    public record Budget(int raysPerTick, int plansPerTick, int searchesPerTick, int planExpansions) {
    }

    /** 観測。 */
    public record Sight(double range, int raysPerPass, int losCacheTicks, int memoryTicks, int attackedWindowTicks) {
    }

    /** 脅威マップと地形解析。 */
    public record Threats(int sources, double radius, double decay, double unknownExposure, int terrainCacheTicks) {
    }

    /** 記録。 */
    public record Logging(boolean battles, boolean decisions, String directory) {
    }

    /** 報酬の点数。 */
    public record Rewards(double enemyDestroyed, double objectiveCaptured, double objectiveRecaptured,
            double objectiveDefended, double allySupported, double goodFlank, double vehicleDestroyed,
            double objectiveLost, double unnecessaryDeath, double longExposure, int longExposureTicks,
            double waterEntered, double underwater, int underwaterTicks) {
    }

    /** 試合を重ねて覚える物（{@code learning/MapMemory}）。 */
    public record Learning(boolean mapMemory, double mapKeep) {
    }

    /** 版の選び方と、自己対戦の進め方。 */
    public record Versions(String defaultVersion, int promoteMinBattles, double promoteWinRate,
            int selfPlayCooldownTicks, boolean alternateSides) {
    }

    /** 可視化。 */
    public record Debugging(double range, int maxMachines) {
    }

    /** 読み込んだ設定の写し1つ。差し替えは丸ごと1回で行うので、途中の半端な組み合わせを見ることが無い。 */
    public record Settings(Timing timing, Budget budget, Sight sight, Threats threats, Logging logging,
            Rewards rewards, Learning learning, Versions versions, Debugging debug,
            Map<ResourceLocation, VehicleRole> roleOverrides) {
    }

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("How often each part of the combat AI runs, in ticks (20 ticks = 1 second).",
                "Every machine offsets its own phase, so these are averages rather than bursts.").push("timing");
        PERCEPTION_TICKS = builder
                .comment("Re-observe enemies and allies: line of sight, threat, who is shooting at whom.")
                .defineInRange("perception", 5, 1, 40);
        DECISION_TICKS = builder
                .comment("Choose a tactical action. Events (a point lost, taking fire) bring this forward.")
                .defineInRange("decision", 20, 5, 200);
        TARGET_TICKS = builder.comment("Re-rank targets.").defineInRange("target", 10, 1, 100);
        OBJECTIVE_TICKS = builder.comment("Re-score capture points and hand them out, once per team.")
                .defineInRange("objective", 40, 10, 400);
        THREAT_MAP_TICKS = builder.comment("Update each team's threat map.").defineInRange("threatMap", 20, 5, 200);
        TACTICS_TICKS = builder
                .comment("Look again for cover, flanking and retreat positions while an action lasts.")
                .defineInRange("tactics", 60, 10, 600);
        REPLAN_TICKS = builder.comment("Re-plan a route even when nothing has changed.")
                .defineInRange("replan", 120, 20, 1200);
        builder.pop();

        builder.comment("How much of the expensive work the whole server may do in one tick. Requests over the",
                "limit wait for the next tick and keep using their previous answer meanwhile.").push("budget");
        RAYS_PER_TICK = builder.comment("Block-walking line-of-sight rays.").defineInRange("raysPerTick", 48, 4, 1024);
        PLANS_PER_TICK = builder.comment("Route searches.").defineInRange("plansPerTick", 2, 1, 32);
        SEARCHES_PER_TICK = builder.comment("Cover, flank and retreat position searches.")
                .defineInRange("searchesPerTick", 3, 1, 32);
        PLAN_EXPANSIONS = builder.comment("Grid cells one route search may expand before it settles for the",
                "closest cell it reached.").defineInRange("planExpansions", 240, 40, 4000);
        builder.pop();

        builder.push("perception");
        SIGHT_RANGE = builder.comment("How far a machine looks for enemies, in blocks.")
                .defineInRange("sightRange", 192.0, 32.0, 1024.0);
        RAYS_PER_PASS = builder.comment("Nearest enemies given a fresh line-of-sight ray per observation pass.")
                .defineInRange("raysPerPass", 6, 1, 32);
        LOS_CACHE_TICKS = builder.comment("How long one line-of-sight answer between two machines is reused.")
                .defineInRange("losCacheTicks", 10, 1, 100);
        MEMORY_TICKS = builder.comment("How long a team remembers where an enemy was after losing sight of it.")
                .defineInRange("memoryTicks", 200, 20, 2400);
        ATTACKED_WINDOW_TICKS = builder.comment("How long being hit by someone counts as them attacking you.")
                .defineInRange("attackedWindowTicks", 100, 20, 1200);
        builder.pop();

        builder.push("threat");
        THREAT_SOURCES = builder.comment("Most enemies one team's threat map is built from.")
                .defineInRange("sources", 16, 1, 64);
        THREAT_RADIUS = builder.comment("Furthest an enemy's threat is spread over the map, in blocks.")
                .defineInRange("radius", 128.0, 32.0, 512.0);
        THREAT_DECAY = builder.comment("Fraction of remembered threat kept per update. 0.85 at one update a",
                "second fades a place where an enemy was over roughly ten seconds.")
                .defineInRange("decay", 0.85, 0.0, 1.0);
        UNKNOWN_EXPOSURE = builder.comment("Exposure assumed on ground nobody has loaded. Unknown is never safe.")
                .defineInRange("unknownExposure", 0.5, 0.0, 1.0);
        TERRAIN_CACHE_TICKS = builder.comment("How long terrain and building analysis is reused.")
                .defineInRange("terrainCacheTicks", 1200, 100, 24000);
        builder.pop();

        builder.push("logging");
        LOG_BATTLES = builder.comment("Write battle events, per-machine results and statistics under the game",
                "directory.").define("battles", true);
        LOG_DECISIONS = builder.comment("Also write every change of decision with the state it was made in. This",
                "is the training data.").define("decisions", true);
        OUTPUT_DIRECTORY = builder.comment("Folder under the game directory for logs, statistics and AI versions.")
                .define("directory", "ashvehicles_ai");
        builder.pop();

        builder.comment("Rewards credited to the machine that earned them. Recorded for training and statistics;",
                "they change nothing in the match itself.").push("rewards");
        ENEMY_DESTROYED = builder.defineInRange("enemyDestroyed", 30.0, -1000.0, 1000.0);
        OBJECTIVE_CAPTURED = builder.defineInRange("objectiveCaptured", 100.0, -1000.0, 1000.0);
        OBJECTIVE_RECAPTURED = builder.defineInRange("objectiveRecaptured", 120.0, -1000.0, 1000.0);
        OBJECTIVE_DEFENDED = builder.defineInRange("objectiveDefended", 50.0, -1000.0, 1000.0);
        ALLY_SUPPORTED = builder.defineInRange("allySupported", 20.0, -1000.0, 1000.0);
        GOOD_FLANK = builder.defineInRange("goodFlank", 40.0, -1000.0, 1000.0);
        VEHICLE_DESTROYED = builder.defineInRange("vehicleDestroyed", -50.0, -1000.0, 1000.0);
        OBJECTIVE_LOST = builder.defineInRange("objectiveLost", -100.0, -1000.0, 1000.0);
        UNNECESSARY_DEATH = builder.defineInRange("unnecessaryDeath", -50.0, -1000.0, 1000.0);
        LONG_EXPOSURE = builder.defineInRange("longExposure", -10.0, -1000.0, 1000.0);
        LONG_EXPOSURE_TICKS = builder.comment("Continuous ticks in the open under threat, not fighting, before",
                "longExposure is charged (and charged again every this many ticks).")
                .defineInRange("longExposureTicks", 200, 20, 6000);
        WATER_ENTERED = builder.comment("Charged once when a machine drives into water deep enough to swallow its hull.")
                .defineInRange("waterEntered", -60.0, -1000.0, 1000.0);
        UNDERWATER = builder.comment("Charged again every underwaterTicks for as long as it stays under.")
                .defineInRange("underwater", -20.0, -1000.0, 1000.0);
        UNDERWATER_TICKS = builder.defineInRange("underwaterTicks", 40, 5, 6000);
        builder.pop();

        builder.comment("What the AI learns from battle to battle and keeps with the world.").push("learning");
        MAP_MEMORY = builder.comment("Remember, per dimension, where machines got stuck, went under water, were",
                "destroyed and drove through cleanly, and let route planning steer by it. Stored in the world folder",
                "under ashvehicles_ai/map_memory.").define("mapMemory", true);
        MAP_KEEP = builder.comment("Share of what was learned before that each finished battle keeps. Lower forgets",
                "changed terrain sooner; higher trusts older battles longer.").defineInRange("mapKeep", 0.9, 0.0, 1.0);
        builder.pop();

        builder.push("versions");
        DEFAULT_VERSION = builder.comment("AI version a team uses unless /tdm ai team gives it another.")
                .define("defaultVersion", "rule_v1");
        PROMOTE_MIN_BATTLES = builder.comment("Battles a challenger needs against the champion before it can be",
                "promoted.").defineInRange("promoteMinBattles", 20, 1, 100000);
        PROMOTE_WIN_RATE = builder.comment("Win rate against the champion a challenger needs to be promoted.")
                .defineInRange("promoteWinRate", 0.55, 0.0, 1.0);
        SELF_PLAY_COOLDOWN = builder.comment("Ticks between the end of one self-play battle and the next.")
                .defineInRange("selfPlayCooldownTicks", 200, 20, 6000);
        SELF_PLAY_ALTERNATE = builder.comment("Swap which team each version plays every battle, so that the",
                "sides of the map cancel out.").define("selfPlayAlternateSides", true);
        builder.pop();

        builder.push("roles");
        ROLE_OVERRIDES = builder.comment("Force a vehicle's role, as \"namespace:vehicle=role\".",
                "Roles: scout, tank, ifv, apc, aa, artillery, support.")
                .defineListAllowEmpty("overrides", List.of(), () -> "", AiConfig::validOverride);
        builder.pop();

        builder.push("debug");
        DEBUG_RANGE = builder.comment("How far from a watching player AI machines are drawn, in blocks.")
                .defineInRange("range", 160.0, 16.0, 1024.0);
        DEBUG_MAX_MACHINES = builder.comment("Most machines sent to one watching player.")
                .defineInRange("maxMachines", 12, 1, 64);
        builder.pop();

        SPEC = builder.build();
        settings = read(true);
    }

    private static volatile Settings settings;

    private AiConfig() {
    }

    /** 今の設定。読み込み前なら既定値。 */
    public static Settings get() {
        return settings;
    }

    public static Timing timing() {
        return settings.timing();
    }

    public static Budget budget() {
        return settings.budget();
    }

    public static Sight sight() {
        return settings.sight();
    }

    public static Threats threats() {
        return settings.threats();
    }

    @SubscribeEvent
    static void onLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) {
            settings = read(false);
        }
    }

    @SubscribeEvent
    static void onReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            settings = read(false);
        }
    }

    /** ワールドを閉じたら既定値へ戻す。次に開くワールドの設定が読まれるまで、前のワールドの値を使わない。 */
    @SubscribeEvent
    static void onUnload(ModConfigEvent.Unloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            settings = read(true);
        }
    }

    private static Settings read(boolean defaults) {
        return new Settings(
                new Timing(value(PERCEPTION_TICKS, defaults), value(DECISION_TICKS, defaults),
                        value(TARGET_TICKS, defaults), value(OBJECTIVE_TICKS, defaults),
                        value(THREAT_MAP_TICKS, defaults), value(TACTICS_TICKS, defaults),
                        value(REPLAN_TICKS, defaults)),
                new Budget(value(RAYS_PER_TICK, defaults), value(PLANS_PER_TICK, defaults),
                        value(SEARCHES_PER_TICK, defaults), value(PLAN_EXPANSIONS, defaults)),
                new Sight(value(SIGHT_RANGE, defaults), value(RAYS_PER_PASS, defaults),
                        value(LOS_CACHE_TICKS, defaults), value(MEMORY_TICKS, defaults),
                        value(ATTACKED_WINDOW_TICKS, defaults)),
                new Threats(value(THREAT_SOURCES, defaults), value(THREAT_RADIUS, defaults),
                        value(THREAT_DECAY, defaults), value(UNKNOWN_EXPOSURE, defaults),
                        value(TERRAIN_CACHE_TICKS, defaults)),
                new Logging(value(LOG_BATTLES, defaults), value(LOG_DECISIONS, defaults),
                        value(OUTPUT_DIRECTORY, defaults)),
                new Rewards(value(ENEMY_DESTROYED, defaults), value(OBJECTIVE_CAPTURED, defaults),
                        value(OBJECTIVE_RECAPTURED, defaults), value(OBJECTIVE_DEFENDED, defaults),
                        value(ALLY_SUPPORTED, defaults), value(GOOD_FLANK, defaults),
                        value(VEHICLE_DESTROYED, defaults), value(OBJECTIVE_LOST, defaults),
                        value(UNNECESSARY_DEATH, defaults), value(LONG_EXPOSURE, defaults),
                        value(LONG_EXPOSURE_TICKS, defaults), value(WATER_ENTERED, defaults),
                        value(UNDERWATER, defaults), value(UNDERWATER_TICKS, defaults)),
                new Learning(value(MAP_MEMORY, defaults), value(MAP_KEEP, defaults)),
                new Versions(value(DEFAULT_VERSION, defaults), value(PROMOTE_MIN_BATTLES, defaults),
                        value(PROMOTE_WIN_RATE, defaults), value(SELF_PLAY_COOLDOWN, defaults),
                        value(SELF_PLAY_ALTERNATE, defaults)),
                new Debugging(value(DEBUG_RANGE, defaults), value(DEBUG_MAX_MACHINES, defaults)),
                overrides(value(ROLE_OVERRIDES, defaults)));
    }

    private static <T> T value(ModConfigSpec.ConfigValue<T> config, boolean defaults) {
        return defaults ? config.getDefault() : config.get();
    }

    private static Map<ResourceLocation, VehicleRole> overrides(List<? extends String> lines) {
        Map<ResourceLocation, VehicleRole> parsed = new HashMap<>();

        for (String line : lines) {
            int split = line.indexOf('=');

            if (split <= 0) {
                continue;
            }

            ResourceLocation vehicle = ResourceLocation.tryParse(line.substring(0, split).trim());
            VehicleRole role = VehicleRole.byName(line.substring(split + 1));

            if (vehicle != null && role != null) {
                parsed.put(vehicle, role);
            }
        }

        return Map.copyOf(parsed);
    }

    private static boolean validOverride(Object entry) {
        if (!(entry instanceof String line)) {
            return false;
        }

        int split = line.indexOf('=');

        return split > 0 && ResourceLocation.tryParse(line.substring(0, split).trim()) != null
                && VehicleRole.byName(line.substring(split + 1)) != null;
    }
}
