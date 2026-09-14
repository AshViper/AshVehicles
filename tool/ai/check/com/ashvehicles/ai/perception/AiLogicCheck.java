package com.ashvehicles.ai.perception;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.ashvehicles.ai.air.AirLoadout;
import com.ashvehicles.ai.air.Delivery;
import com.ashvehicles.ai.air.FlightControl;
import com.ashvehicles.ai.air.KillScore;
import com.ashvehicles.ai.battlefield.HeightField;
import com.ashvehicles.ai.battlefield.HeightfieldSight;
import com.ashvehicles.ai.battlefield.ThreatMap;
import com.ashvehicles.ai.battlefield.ThreatSource;
import com.ashvehicles.ai.core.Cadence;
import com.ashvehicles.ai.decision.BattleState;
import com.ashvehicles.ai.decision.ParameterSet;
import com.ashvehicles.ai.decision.RuleBasedPolicy;
import com.ashvehicles.ai.decision.TacticalAction;
import com.ashvehicles.ai.learning.AiVersion;
import com.ashvehicles.ai.learning.AiVersions;
import com.ashvehicles.ai.learning.BattleStatistics;
import com.ashvehicles.ai.learning.MapKnowledge;
import com.ashvehicles.ai.navigation.StallWatch;
import com.ashvehicles.ai.objective.CaptureState;
import com.ashvehicles.ai.objective.ObjectiveBoard;
import com.ashvehicles.ai.objective.ObjectiveScoring;
import com.ashvehicles.ai.objective.ObjectiveState;
import com.ashvehicles.ai.role.TacticalProfile;
import com.ashvehicles.ai.role.VehicleRole;
import com.ashvehicles.ai.tactics.Tactics;
import com.ashvehicles.ai.team.Reinforcements;
import com.ashvehicles.ai.team.SupportCalls;
import com.ashvehicles.match.Bots;
import com.ashvehicles.network.AiDebugPayload;
import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.weapon.WeaponDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * 戦闘 AI の、世界に触れない部分の合成データの試験。{@code python tool/ai/check/run_checks.py} が回す。
 *
 * <p><b>ゲームを起動しない。</b> 方針（{@code RuleBasedPolicy}）・拠点の点数・脅威マップの視界解析と記憶・
 * 高さ地図の射線・版の読み書きと既定値の集め方・統計・可視化パケットの往復・特徴量の並び・砲の据え方の幾何を、作った数字で確かめる。
 * 車両が本当に遮蔽へ入るか、道を選ぶかはゲームの中でしか分からない——ここは「判断の算術が思った通りか」まで。
 *
 * <p><b>エンティティは作れない。</b> ゲームの外ではレジストリが起動していないので、{@code Entity} のクラス初期化が
 * 落ちる。方針が読むのは観測の数字だけなので、観測（{@link EnemyObservation}）は構築子を通さずに確保し、
 * パッケージの内側の書き口で数字を置く——この試験がこのパッケージに居るのはそのため。
 */
public final class AiLogicCheck {
    private static int passed;
    private static int failed;
    private static int skipped;
    private static Map<TacticalAction, Double> utilities = Map.of();

    interface Body {
        void run() throws Throwable;
    }

    interface Surface {
        double at(int x, int z);
    }

    static final class Skip extends RuntimeException {
        Skip(String why) {
            super(why);
        }
    }

    public static void main(String[] args) {
        section("cadence", AiLogicCheck::cadence);
        section("parameters", AiLogicCheck::parameters);
        section("versions", AiLogicCheck::versions);
        section("heightfield sight", AiLogicCheck::sight);
        section("turret laying", AiLogicCheck::laying);
        section("stall detection", AiLogicCheck::stall);
        section("map knowledge", AiLogicCheck::mapKnowledge);
        section("threat map", AiLogicCheck::threatMap);
        section("objective scoring", AiLogicCheck::scoring);
        section("objective slots", AiLogicCheck::slots);
        section("policy without a target", AiLogicCheck::policyNoTarget);
        section("policy with a target", AiLogicCheck::policyTarget);
        section("policy support calls", AiLogicCheck::policySupport);
        section("support calls", AiLogicCheck::supportCalls);
        section("features", AiLogicCheck::features);
        section("statistics", AiLogicCheck::statistics);
        section("debug payload", AiLogicCheck::payload);
        section("capture anchoring", AiLogicCheck::anchoring);
        section("flight stick", AiLogicCheck::flightStick);
        section("bomb delivery", AiLogicCheck::delivery);
        section("air stores", AiLogicCheck::airStores);
        section("kill score", AiLogicCheck::killScore);
        section("reinforcements", AiLogicCheck::reinforcements);
        System.out.printf("%n%d passed, %d failed, %d skipped%n", passed, failed, skipped);
        System.exit(failed == 0 ? 0 : 1);
    }

    static void section(String name, Body body) {
        System.out.println("== " + name);

        try {
            body.run();
        } catch (Skip skip) {
            skipped++;
            System.out.println("  SKIP " + skip.getMessage());
        } catch (Throwable thrown) {
            failed++;
            System.out.println("  FAIL (threw) " + thrown);
            thrown.printStackTrace(System.out);
        }
    }

    static void check(String what, boolean ok, Object detail) {
        if (ok) {
            passed++;
        } else {
            failed++;
        }

        System.out.println((ok ? "  ok   " : "  FAIL ") + what + (detail == null ? "" : "  [" + detail + "]"));
    }

    // ------------------------------------------------------------------

    static void cadence() {
        Cadence seeded = new Cadence(3, 5);
        int firstDue = -1;

        for (int tick = 1; tick <= 12 && firstDue < 0; tick++) {
            if (seeded.tick(5)) {
                firstDue = tick;
            }
        }

        check("first due tick follows the seed", firstDue == 4, firstDue);

        Cadence forced = new Cadence(0, 20);

        forced.force();
        check("force makes the next tick due", forced.tick(20), null);
        check("then it waits a full interval", !forced.tick(20), null);
    }

    static void parameters() {
        JsonObject json = JsonParser.parseString(
                "{\"policy\":{\"survival\":1.25,\"attack\":\"x\"},\"route.threat\":3}").getAsJsonObject();
        ParameterSet set = ParameterSet.fromJson(json);

        check("nested keys are flattened", set.get("policy.survival", 0) == 1.25, set.asMap());
        check("non-numbers are dropped", !set.has("policy.attack"), null);
        check("flat dotted keys are kept", set.get("route.threat", 0) == 3.0, null);
        check("missing keys fall back", set.get("nope", 7) == 7.0, null);

        ParameterSet merged = set.overriddenBy(new ParameterSet(Map.of("route.threat", 4.0)));

        check("overrides win and the rest stays", merged.get("route.threat", 0) == 4.0
                && merged.get("policy.survival", 0) == 1.25, merged.asMap());

        ParameterSet recorder = ParameterSet.recorder();

        recorder.get("a.b", 2.5);
        check("recorder notes the defaults it was asked for", recorder.asked().equals(Map.of("a.b", 2.5)),
                recorder.asked());
    }

    static void versions() {
        Map<String, Double> defaults = AiVersions.defaults().asMap();

        check("defaults cover the policy", defaults.get("policy.survival") == 1.0
                && defaults.containsKey("policy.min_dwell"), defaults.size() + " keys");
        check("defaults cover routes, targets, tactics and objectives", defaults.containsKey("route.threat")
                && defaults.containsKey("target.attacking_me") && defaults.containsKey("tactics.cover.protection")
                && defaults.containsKey("tactics.flank.weakness") && defaults.containsKey("tactics.retreat.safety")
                && defaults.containsKey("objective.recapture"), null);
        check("defaults cover every role profile", defaults.containsKey("profile.tank.aggression")
                && defaults.containsKey("profile.artillery.retreat_health")
                && defaults.containsKey("profile.scout.scout_bias"), null);
        check("a role default is the profile's own value", defaults.get("profile.tank.aggression") == 0.90,
                defaults.get("profile.tank.aggression"));

        JsonObject json = JsonParser.parseString("{\"id\":\"rule_v2\",\"parent\":\"rule_v1\","
                + "\"parameters\":{\"policy\":{\"survival\":1.2}}}").getAsJsonObject();
        AiVersion version = AiVersion.fromJson("file", json);

        check("a version reads its id, parent, policy and parameters", version != null
                && version.id().equals("rule_v2") && "rule_v1".equals(version.parent())
                && version.policy().equals(RuleBasedPolicy.ID)
                && version.parameters().get("policy.survival", 0) == 1.2, version);
        check("bad names are refused", AiVersion.fromJson("Bad Name!", new JsonObject()) == null, null);

        AiVersion back = AiVersion.fromJson("x", version.toJson(version.parameters()));

        check("a version survives a round trip", back != null && back.id().equals(version.id())
                && back.policy().equals(version.policy()) && "rule_v1".equals(back.parent())
                && back.parameters().asMap().equals(version.parameters().asMap()), back);

        TacticalProfile tuned = TacticalProfile.tuned(VehicleRole.IFV,
                new ParameterSet(Map.of("profile.ifv.flank_bias", 0.2, "profile.ifv.aggression", 4.0)));

        check("profiles read their overrides and stay in 0..1", tuned.flankBias() == 0.2
                && tuned.aggression() == 1.0 && tuned.coverBias() == TacticalProfile.of(VehicleRole.IFV).coverBias(),
                tuned);
    }

    static HeightField field(Surface surface) {
        return new HeightField() {
            @Override
            public double sightLine(int x, int z) {
                return surface.at(x, z);
            }

            @Override
            public double ground(int x, int z) {
                return surface.at(x, z);
            }
        };
    }

    static final HeightField FLAT = field((x, z) -> 64.0);
    static final HeightField WALL = field((x, z) -> x >= 12 && x <= 20 ? 90.0 : 64.0);
    static final HeightField HALF_UNKNOWN = field((x, z) -> x >= 10 ? Double.NaN : 64.0);

    static void sight() {
        Vec3 from = new Vec3(0.5, 66.0, 0.5);
        Vec3 to = new Vec3(40.5, 66.0, 0.5);

        check("flat ground is clear", HeightfieldSight.trace(FLAT, from, to) == HeightfieldSight.Visibility.CLEAR,
                null);
        check("a wall blocks", HeightfieldSight.trace(WALL, from, to) == HeightfieldSight.Visibility.BLOCKED, null);
        check("unloaded ground is unknown, not clear",
                HeightfieldSight.trace(HALF_UNKNOWN, from, to) == HeightfieldSight.Visibility.UNKNOWN, null);
        check("a bump below the line does not block", HeightfieldSight.trace(
                field((x, z) -> x == 20 ? 65.5 : 64.0), from, to) == HeightfieldSight.Visibility.CLEAR, null);
        check("the ground under either end is not cover", HeightfieldSight.trace(
                field((x, z) -> x <= 1 ? 67.0 : 64.0), from, to) == HeightfieldSight.Visibility.CLEAR, null);
    }

    static final float DEG = (float) (Math.PI / 180.0);

    /** 車体の姿勢と架台の2つの角から、砲身の向き。{@code GroundVehicleEntity.aim} と同じ組み方。 */
    static Vec3 bore(Quaternionf hull, float yaw, float pitch) {
        return Attitude.nose(new Quaternionf(hull).rotateY(-yaw * DEG).rotateX(-pitch * DEG));
    }

    /**
     * layFrom から target へ角を測って据えた砲の、砲口から見た target とのずれ（度）。
     * {@code FireControl.onTarget} が 0.5 度と比べる角。
     */
    static double offBore(Quaternionf hull, Vec3 layFrom, Vec3 breech, double length, Vec3 target) {
        float[] angles = Attitude.mountAngles(hull, target.subtract(layFrom));
        Vec3 bore = bore(hull, angles[0], angles[1]);
        Vec3 muzzle = breech.add(bore.scale(length));

        return Math.toDegrees(Math.acos(Mth.clamp(bore.dot(target.subtract(muzzle).normalize()), -1.0, 1.0)));
    }

    static void laying() {
        Random random = new Random(7);
        double worstYaw = 0.0;
        double worstPitch = 0.0;

        for (int trial = 0; trial < 500; trial++) {
            Quaternionf hull = Attitude.rotate(Attitude.of(random.nextFloat() * 360.0F - 180.0F,
                    random.nextFloat() * 50.0F - 25.0F), random.nextFloat() * 50.0F - 25.0F, 0.0F, 0.0F);
            float yaw = random.nextFloat() * 360.0F - 180.0F;
            float pitch = random.nextFloat() * 60.0F - 30.0F;
            float[] back = Attitude.mountAngles(hull, bore(hull, yaw, pitch).scale(37.0));

            worstYaw = Math.max(worstYaw, Math.abs(Mth.wrapDegrees(back[0] - yaw)));
            worstPitch = Math.max(worstPitch, Math.abs(back[1] - pitch));
        }

        check("mount angles undo the turret's rotation on a pitched and banked hull",
                worstYaw < 0.01 && worstPitch < 0.01, worstYaw + " / " + worstPitch);

        // レオパルト 2A4: 耳軸は原点の 1.87 ブロック上、砲身 4.089。斜面に寝た車体から、斜め前の 55 ブロック先へ。
        Quaternionf hull = Attitude.rotate(Attitude.of(35.0F, -6.0F), 8.0F, 0.0F, 0.0F);
        Vec3 origin = new Vec3(100.5, 64.0, -20.5);
        Vec3 breech = origin.add(Attitude.toWorld(hull, new Vec3(-0.008, 1.87, 1.292)));
        Vec3 target = origin.add(Attitude.toWorld(hull, new Vec3(30.0, 0.0, 46.0))).add(0.0, 1.2, 0.0);
        double fromOrigin = offBore(hull, origin, breech, 4.089, target);
        double fromBreech = offBore(hull, breech, breech, 4.089, target);

        check("laid from the vehicle origin, the bore misses the trigger's 0.5 degrees", fromOrigin > 0.5,
                String.format("%.2f deg", fromOrigin));
        check("laid from the breech, the muzzle looks straight down the bore", fromBreech < 0.1,
                String.format("%.3f deg", fromBreech));
    }

    /** 止まった所から走り出す重い戦車と、壁に当たった戦車。数字はレオパルト 2A6（最高 0.944、加速 0.004、制動 0.03）。 */
    static void stall() {
        float speed = 0.0F;
        double travelled = 0.0;
        StallWatch pulling = new StallWatch();

        // 車両と同じ規則で20 tick: 加速してから、その速さで進む。
        for (int tick = 0; tick < 20; tick++) {
            pulling.command(0.9F, speed, 0.944F, 0.004F, 0.03F);
            speed = Math.min(0.9F * 0.944F, speed + 0.004F);
            travelled += speed;
        }

        check("the old yardstick (1 block in 20 ticks) called a heavy tank pulling away stuck", travelled < 1.0,
                String.format("%.2f blocks", travelled));
        check("a heavy tank pulling away is not stuck", !pulling.stalled(travelled),
                String.format("expected %.2f, moved %.2f", pulling.expected(), travelled));

        StallWatch wall = new StallWatch();

        for (int tick = 0; tick < 20; tick++) {
            wall.command(0.9F, 0.0F, 0.944F, 0.004F, 0.03F);
        }

        check("a tank pushing against a wall from a standstill is stuck", wall.stalled(0.05),
                String.format("expected %.2f", wall.expected()));

        StallWatch cruising = new StallWatch();

        // 1 tick 目で壁に着き、その後は travel() が速さを進めた距離（0）へ切り詰める。
        for (int tick = 0; tick < 20; tick++) {
            cruising.command(0.9F, tick == 0 ? 0.85F : 0.0F, 0.944F, 0.004F, 0.03F);
        }

        check("a tank that runs into a wall at speed is stuck", cruising.stalled(0.85),
                String.format("expected %.2f", cruising.expected()));

        StallWatch rocking = new StallWatch();

        for (int window = 0; window < 2; window++) {
            rocking.reset();

            for (int tick = 0; tick < 20; tick++) {
                rocking.command(0.9F, 0.02F, 0.944F, 0.004F, 0.03F);
            }
        }

        // 窓ごとに実際の速さから始め直すと、2つ目の窓の「進めたはず」は 1.24 で、0.4 進めば詰まりに見えない。
        check("a tank rocking against a wall is still stuck in the next window", rocking.stalled(0.4),
                String.format("expected %.2f", rocking.expected()));

        StallWatch sliding = new StallWatch();

        for (int tick = 0; tick < 20; tick++) {
            sliding.command(0.9F, 0.85F, 0.944F, 0.004F, 0.03F);
        }

        sliding.reset();

        for (int tick = 0; tick < 20; tick++) {
            sliding.command(0.9F, 0.08F, 0.944F, 0.004F, 0.03F);
        }

        check("a tank slowed to a crawl along a wall or up a slope is not stuck", !sliding.stalled(1.6),
                String.format("expected %.2f, moved 1.60", sliding.expected()));
        check("a heavy tank pulling away is progressing", pulling.progressing(travelled),
                String.format("expected %.2f, moved %.2f", pulling.expected(), travelled));

        StallWatch reversing = new StallWatch();
        float back = -0.04F;
        double net = 0.0;

        for (int tick = 0; tick < 20; tick++) {
            reversing.command(0.9F, back, 0.944F, 0.004F, 0.03F);
            back = Math.min(0.9F * 0.944F, back + 0.004F);
            net += back;
        }

        check("a tank pulling away out of a reverse is not stuck", !reversing.stalled(Math.abs(net)),
                String.format("expected %.2f, moved %.2f", reversing.expected(), Math.abs(net)));
        check("a vehicle told to stand still is never stuck", !new StallWatch().stalled(0.0), null);
    }

    /** 試合を重ねて覚えた地図の算術。重みは既定値（map.trap 3、map.water 6、map.death 1、map.flow 0.2）。 */
    static void mapKnowledge() {
        MapKnowledge.Weights weights = MapKnowledge.Weights.of(ParameterSet.EMPTY);
        MapKnowledge map = new MapKnowledge();

        check("a cell nobody drove through costs nothing", map.cost(0, 0, weights, 1.0) == 0.0, null);

        // (1, 0): 1両がそこで試合の間ずっと詰まっていた。(2, 0): 10両がすんなり抜けた。
        map.add(1, 0, MapKnowledge.VISITS, 1.0F);

        for (int jam = 0; jam < 2000; jam++) {
            map.add(1, 0, MapKnowledge.STALLS, 1.0F);
        }

        for (int pass = 0; pass < 10; pass++) {
            map.add(2, 0, MapKnowledge.VISITS, 1.0F);
            map.add(2, 0, MapKnowledge.PASSES, 1.0F);
        }

        double trap = map.cost(1, 0, weights, 1.0);
        double lane = map.cost(2, 0, weights, 1.0);

        check("a cell a tank got stuck in costs more than a lane tanks drive through", trap > 1.5 && lane < 0.0,
                String.format("trap %+.2f, lane %+.2f", trap, lane));
        check("one stuck tank adds a capped number of stalls in one battle",
                map.amount(1, 0, MapKnowledge.STALLS) == MapKnowledge.FRESH_CAP,
                map.amount(1, 0, MapKnowledge.STALLS));

        MapKnowledge once = new MapKnowledge();

        once.add(0, 0, MapKnowledge.VISITS, 1.0F);
        once.add(0, 0, MapKnowledge.STALLS, 1.0F);
        once.add(1, 0, MapKnowledge.VISITS, 1.0F);
        once.add(1, 0, MapKnowledge.WET, 1.0F);
        check("going under water once costs more than getting stuck once",
                once.cost(1, 0, weights, 1.0) > once.cost(0, 0, weights, 1.0),
                String.format("water %+.2f, stuck %+.2f", once.cost(1, 0, weights, 1.0),
                        once.cost(0, 0, weights, 1.0)));

        map.commit(0.9);
        check("committing counts the battle and keeps what was learned",
                map.battles() == 1 && !map.hasFresh() && Math.abs(map.cost(1, 0, weights, 1.0) - trap) < 1.0E-6,
                String.format("battles %d, trap %+.2f", map.battles(), map.cost(1, 0, weights, 1.0)));

        for (int battle = 0; battle < 10; battle++) {
            for (int pass = 0; pass < 5; pass++) {
                map.add(1, 0, MapKnowledge.VISITS, 1.0F);
                map.add(1, 0, MapKnowledge.PASSES, 1.0F);
            }

            map.commit(0.9);
        }

        double healed = map.cost(1, 0, weights, 1.0);

        check("ten battles of tanks driving through a trap wash it out", healed < trap * 0.3,
                String.format("%+.2f -> %+.2f", trap, healed));

        MapKnowledge deadly = new MapKnowledge();

        deadly.add(0, 0, MapKnowledge.VISITS, 4.0F);
        deadly.add(0, 0, MapKnowledge.DEATHS, 2.0F);
        check("a cell where machines die costs more to a machine that fears danger more",
                deadly.cost(0, 0, weights, 2.0) > deadly.cost(0, 0, weights, 1.0), null);

        MapKnowledge busy = new MapKnowledge();

        for (int battle = 0; battle < 30; battle++) {
            for (int visit = 0; visit < 40; visit++) {
                busy.add(9, 9, MapKnowledge.VISITS, 1.0F);
                busy.add(9, 9, MapKnowledge.PASSES, 1.0F);
            }

            busy.add(9, 9, MapKnowledge.STALLS, 4.0F);
            busy.commit(0.9);
        }

        check("a busy cell keeps its proportions under the cap",
                busy.amount(9, 9, MapKnowledge.VISITS) <= MapKnowledge.LEARNED_CAP + 1.0E-3
                        && Math.abs(busy.reading(9, 9).trap() - 40.0 / 442.0) < 0.02,
                String.format("visits %.1f, trap %.3f", busy.amount(9, 9, MapKnowledge.VISITS),
                        busy.reading(9, 9).trap()));

        MapKnowledge fading = new MapKnowledge();

        fading.add(5, 5, MapKnowledge.VISITS, 1.0F);
        fading.add(5, 5, MapKnowledge.DEATHS, 1.0F);

        for (int battle = 0; battle < 40; battle++) {
            fading.commit(0.9);
        }

        check("a cell nobody comes back to is forgotten", fading.size() == 0, fading.size());

        MapKnowledge back = MapKnowledge.fromJson(JsonParser.parseString(map.toJson().toString()).getAsJsonObject());

        check("the map survives being written and read",
                back.battles() == map.battles() && back.size() == map.size()
                        && Math.abs(back.cost(1, 0, weights, 1.0) - map.cost(1, 0, weights, 1.0)) < 0.01
                        && Math.abs(back.cost(2, 0, weights, 1.0) - map.cost(2, 0, weights, 1.0)) < 0.01,
                String.format("battles %d/%d, cells %d/%d", back.battles(), map.battles(), back.size(), map.size()));

        JsonObject future = map.toJson();

        future.addProperty("format", MapKnowledge.FORMAT + 1);
        check("a map written in another format is relearned instead of misread",
                MapKnowledge.fromJson(future).size() == 0, null);
    }

    static void threatMap() {
        ThreatSource source = new ThreatSource(1, new Vec3(0.5, 66.5, 0.5), 1.0, 64.0, 0L, true);
        ThreatMap open = new ThreatMap();

        open.update(FLAT, List.of(source), 0L, 200, 0.85, 0.5);

        float nearDanger = open.danger(24.5, 0.5);
        float nearExposure = open.exposure(24.5, 0.5);

        check("open ground near an enemy is dangerous", nearDanger > 0.5F, nearDanger);
        check("and exposed", nearExposure > 0.5F, nearExposure);
        check("beyond its reach is safe", open.danger(200.0, 200.0) == 0.0F && open.exposure(200.0, 200.0) == 0.0F,
                null);
        check("danger falls off with range", open.danger(56.5, 0.5) < nearDanger, open.danger(56.5, 0.5));

        ThreatMap walled = new ThreatMap();

        walled.update(WALL, List.of(source), 0L, 200, 0.85, 0.5);

        float behind = walled.danger(40.5, 0.5);

        check("behind a wall is much less dangerous", behind > 0.0F && behind < open.danger(40.5, 0.5) * 0.3F,
                behind + " vs open " + open.danger(40.5, 0.5));
        check("and not exposed", walled.exposure(40.5, 0.5) == 0.0F, walled.exposure(40.5, 0.5));
        check("the other side of the enemy stays exposed", walled.exposure(-40.5, 0.5) > 0.3F,
                walled.exposure(-40.5, 0.5));

        ThreatMap blind = new ThreatMap();

        blind.update(HALF_UNKNOWN, List.of(source), 0L, 200, 0.85, 0.5);

        float unknown = blind.exposure(40.5, 0.5);

        check("unloaded ground is treated as seen, at the unknown weight",
                Math.abs(unknown - open.exposure(40.5, 0.5) * 0.5F) < 0.02F, unknown);

        float before = open.danger(24.5, 0.5);

        open.update(FLAT, List.of(), 20L, 200, 0.5, 0.5);
        check("danger is remembered, fading, after the enemy is gone",
                Math.abs(open.danger(24.5, 0.5) - before * 0.5F) < 1.0E-4F, open.danger(24.5, 0.5));
        check("exposure is not remembered", open.exposure(24.5, 0.5) == 0.0F, null);

        ThreatMap memory = new ThreatMap();

        memory.update(FLAT, List.of(new ThreatSource(1, source.sight(), 1.0, 64.0, 0L, false)), 100L, 200, 0.85, 0.5);
        check("a lost enemy paints danger at its remaining memory", memory.danger(24.5, 0.5) > 0.0F
                && Math.abs(memory.danger(24.5, 0.5) - nearDanger * 0.5F) < 0.01F, memory.danger(24.5, 0.5));
        check("a lost enemy paints no exposure", memory.exposure(24.5, 0.5) == 0.0F, null);

        ThreatMap fresh = new ThreatMap();

        fresh.update(FLAT, List.of(source), 0L, 200, 0.85, 0.5);
        check("a straight line through the enemy is riskier than one past its reach",
                fresh.riskAlong(new Vec3(-60, 64, 0.5), new Vec3(60, 64, 0.5), 8)
                        > fresh.riskAlong(new Vec3(-60, 64, 150), new Vec3(60, 64, 150), 8), null);

        ThreatSource second = new ThreatSource(2, new Vec3(48.5, 66.5, 0.5), 1.0, 64.0, 0L, true);
        ThreatMap two = new ThreatMap();

        two.update(FLAT, List.of(source, second), 0L, 200, 0.85, 0.5);
        check("two enemies covering a spot add up, but never past 1",
                two.danger(24.5, 0.5) > nearDanger && two.danger(24.5, 0.5) <= 1.0F, two.danger(24.5, 0.5));
    }

    static ObjectiveState objective(CaptureState state, String owner, boolean beingTaken, boolean recapture,
            long lostTick, int allies, int enemies, double threat, float progress) {
        return new ObjectiveState("B", BlockPos.ZERO, new Vec3(0.5, 64.5, 0.5), 16.0, state, owner,
                beingTaken ? "blue" : null, progress, false, beingTaken, recapture, lostTick, allies, enemies, threat,
                0.5, 0.0);
    }

    static void scoring() {
        ObjectiveScoring.Weights weights = ObjectiveScoring.Weights.of(ParameterSet.EMPTY);
        long now = 10_000L;
        ObjectiveState enemy = objective(CaptureState.ENEMY, "blue", false, false, Long.MIN_VALUE, 0, 0, 0.0, 0.0F);
        ObjectiveState lost = objective(CaptureState.ENEMY, "blue", false, true, now - 100L, 0, 0, 0.0, 0.0F);
        ObjectiveState lostLongAgo = objective(CaptureState.ENEMY, "blue", false, true, now - 5000L, 0, 0, 0.0, 0.0F);
        ObjectiveState guarded = objective(CaptureState.ENEMY, "blue", false, false, Long.MIN_VALUE, 0, 3, 0.8, 0.0F);
        ObjectiveState quiet = objective(CaptureState.FRIENDLY, "red", false, false, Long.MIN_VALUE, 1, 0, 0.0, 0.0F);
        ObjectiveState taken = objective(CaptureState.FRIENDLY, "red", true, false, Long.MIN_VALUE, 1, 2, 0.3, 0.6F);
        double plain = ObjectiveScoring.teamScore(enemy, weights, now);

        check("recapture outranks a plain capture", ObjectiveScoring.teamScore(lost, weights, now) > plain,
                ObjectiveScoring.teamScore(lost, weights, now) + " vs " + plain);
        check("a well-defended point scores lower", ObjectiveScoring.teamScore(guarded, weights, now) < plain,
                ObjectiveScoring.teamScore(guarded, weights, now));
        check("a point being taken outranks a quiet friendly one",
                ObjectiveScoring.teamScore(taken, weights, now) > ObjectiveScoring.teamScore(quiet, weights, now),
                null);
        check("a fresh loss is more urgent than an old one",
                ObjectiveScoring.recaptureUrgency(lost, weights, now)
                        > ObjectiveScoring.recaptureUrgency(lostLongAgo, weights, now), null);

        ObjectiveState scored = enemy.withScore(plain);
        Vec3 near = new Vec3(0.5, 64, 60);
        Vec3 far = new Vec3(0.5, 64, 460);
        TacticalProfile tank = TacticalProfile.of(VehicleRole.TANK);
        TacticalProfile scout = TacticalProfile.of(VehicleRole.SCOUT);
        double tankNear = ObjectiveScoring.botScore(scored, near, 0.0, 500.0, weights, VehicleRole.TANK, tank);
        double tankFar = ObjectiveScoring.botScore(scored, far, 0.0, 500.0, weights, VehicleRole.TANK, tank);
        double scoutNear = ObjectiveScoring.botScore(scored, near, 0.0, 500.0, weights, VehicleRole.SCOUT, scout);
        double scoutFar = ObjectiveScoring.botScore(scored, far, 0.0, 500.0, weights, VehicleRole.SCOUT, scout);

        check("distance costs a tank", tankNear > tankFar, tankNear + " vs " + tankFar);
        check("a scout minds distance less than a tank", scoutNear - scoutFar < tankNear - tankFar,
                (scoutNear - scoutFar) + " vs " + (tankNear - tankFar));
        check("a risky route lowers the score",
                ObjectiveScoring.botScore(scored, near, 0.8, 500.0, weights, VehicleRole.TANK, tank) < tankNear, null);
        check("artillery is held back from rushing points",
                ObjectiveScoring.botScore(scored, near, 0.0, 500.0, weights, VehicleRole.ARTILLERY,
                        TacticalProfile.of(VehicleRole.ARTILLERY)) < tankNear, null);

        ObjectiveState held = objective(CaptureState.FRIENDLY, "red", false, false, Long.MIN_VALUE, 1, 0, 0.0, 0.0F)
                .withScore(plain);
        TacticalProfile ifv = TacticalProfile.of(VehicleRole.IFV);

        check("a tank is drawn to a point its team does not hold",
                tankNear > ObjectiveScoring.botScore(held, near, 0.0, 500.0, weights, VehicleRole.TANK, tank) + 0.25,
                tankNear);
        check("and more to one with enemies on it",
                ObjectiveScoring.botScore(objective(CaptureState.ENEMY, "blue", false, false, Long.MIN_VALUE, 0, 3, 0.5,
                        0.0F).withScore(plain), near, 0.0, 500.0, weights, VehicleRole.TANK, tank) > tankNear, null);
        check("an IFV is drawn to a point its team does not hold",
                ObjectiveScoring.botScore(scored, near, 0.0, 500.0, weights, VehicleRole.IFV, ifv)
                        > ObjectiveScoring.botScore(held, near, 0.0, 500.0, weights, VehicleRole.IFV, ifv) + 0.25, null);
        check("a tank weighs points as fully as light armour does", tank.objectiveBias() == ifv.objectiveBias(),
                tank.objectiveBias());
    }

    static void slots() {
        int[] seven = ObjectiveBoard.slots(new double[] {1.0, 0.5, 1.1}, new boolean[] {false, true, false}, 7);

        check("seven AI over two points to take and a quiet held one fill exactly seven places",
                seven[0] + seven[1] + seven[2] == 7 && seven[1] == 1 && seven[0] >= 3 && seven[2] >= 3,
                Arrays.toString(seven));

        int[] three = ObjectiveBoard.slots(new double[] {0.3, 0.3, 1.0}, new boolean[] {true, true, false}, 3);

        check("three AI keep one guard and send the rest to take", three[0] + three[1] == 1 && three[2] == 2,
                Arrays.toString(three));

        int[] two = ObjectiveBoard.slots(new double[] {0.3, 1.0}, new boolean[] {true, false}, 2);

        check("two AI guard nothing while a point is still to take", two[0] == 0 && two[1] == 2, Arrays.toString(two));

        int[] held = ObjectiveBoard.slots(new double[] {0.3, 0.4, 0.2}, new boolean[] {true, true, true}, 5);

        check("with every point held and quiet, each keeps one guard", held[0] == 1 && held[1] == 1 && held[2] == 1,
                Arrays.toString(held));

        int[] few = ObjectiveBoard.slots(new double[] {1.0, 1.0, 0.3}, new boolean[] {false, false, true}, 1);

        check("fewer AI than points still leaves a place at each point to take",
                few[0] == 1 && few[1] == 1 && few[2] == 0, Arrays.toString(few));
    }

    /** 好きなように組める判断の状態。 */
    static final class S {
        VehicleRole role = VehicleRole.TANK;
        double health = 1.0;
        double ammo = 1.0;
        double threat;
        double exposure;
        boolean underFire;
        int engaging;
        int allies;
        int known;
        EnemyObservation target;
        boolean frontal;
        int onTarget;
        AllyObservation ally;
        double need;
        boolean call;
        ObjectiveState objective;
        double weight;
        TacticalAction current = TacticalAction.ADVANCE;
        long ticks = 100L;
        final List<EnemyObservation> enemies = new ArrayList<>();

        BattleState build() {
            return new BattleState(0L, 1, "red", "rule_v1", this.role, TacticalProfile.of(this.role),
                    new Vec3(0, 64, 0), this.health, this.ammo, false, this.enemies, List.of(), this.threat,
                    this.exposure, this.underFire, this.engaging, this.allies, this.known, this.target, this.frontal,
                    this.onTarget, false, this.ally, this.need, this.call,
                    this.objective == null ? List.of() : List.of(this.objective),
                    this.objective, this.objective == null ? 0.0 : 40.0, false, this.weight, 0.1, null, null, false,
                    false, this.current, this.ticks, 500.0);
        }
    }

    static TacticalAction decide(S state) {
        RuleBasedPolicy policy = new RuleBasedPolicy(ParameterSet.EMPTY);
        TacticalAction action = policy.decide(state.build());

        utilities = Map.copyOf(policy.lastUtilities());

        return action;
    }

    static String top() {
        StringBuilder out = new StringBuilder();

        utilities.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).limit(4)
                .forEach(entry -> out.append(entry.getKey().name()).append('=')
                        .append(String.format("%.3f", entry.getValue())).append(' '));

        return out.toString().trim();
    }

    static final ObjectiveState ENEMY_POINT = objective(CaptureState.ENEMY, "blue", false, false, Long.MIN_VALUE, 0,
            0, 0.0, 0.0F).withScore(1.0);

    static void policyNoTarget() {
        S capture = new S();

        capture.objective = ENEMY_POINT;
        capture.weight = 1.0;
        check("an enemy point with no threat is captured", decide(capture) == TacticalAction.CAPTURE_OBJECTIVE, top());

        S defend = new S();

        defend.objective = objective(CaptureState.FRIENDLY, "red", true, false, Long.MIN_VALUE, 1, 2, 0.2, 0.5F)
                .withScore(1.5);
        defend.weight = 1.0;
        check("a friendly point being taken is defended", decide(defend) == TacticalAction.DEFEND_OBJECTIVE, top());

        S recapture = new S();

        recapture.objective = objective(CaptureState.ENEMY, "blue", false, true, 0L, 0, 1, 0.1, 0.0F).withScore(2.0);
        recapture.weight = 1.0;
        check("a lost point is recaptured", decide(recapture) == TacticalAction.RECAPTURE_OBJECTIVE, top());

        S idleTank = new S();

        check("a tank with nothing to do advances", decide(idleTank) == TacticalAction.ADVANCE, top());

        S idleScout = new S();

        idleScout.role = VehicleRole.SCOUT;
        check("a scout with nothing to do searches", decide(idleScout) == TacticalAction.SEARCH_ENEMY, top());

        S dwell = new S();

        dwell.role = VehicleRole.SCOUT;
        dwell.ticks = 5L;
        check("a slightly better action does not interrupt a fresh one", decide(dwell) == TacticalAction.ADVANCE,
                top());

        S emergency = new S();

        emergency.health = 0.15;
        emergency.threat = 0.8;
        emergency.exposure = 0.6;
        emergency.underFire = true;
        emergency.engaging = 3;
        emergency.allies = 1;
        emergency.objective = ENEMY_POINT;
        emergency.weight = 1.0;
        emergency.current = TacticalAction.CAPTURE_OBJECTIVE;
        emergency.ticks = 10L;
        check("a wounded, outnumbered tank retreats even mid-action", decide(emergency) == TacticalAction.RETREAT,
                top());
        check("and the retreat score is an emergency", utilities.get(TacticalAction.RETREAT) >= 0.8, top());

        S healthy = new S();

        healthy.threat = 0.3;
        healthy.exposure = 0.2;
        healthy.objective = ENEMY_POINT;
        healthy.weight = 1.0;
        check("a healthy tank under light threat does not retreat",
                decide(healthy) != TacticalAction.RETREAT && utilities.get(TacticalAction.RETREAT) < 0.1, top());
    }

    /**
     * 相手のエンティティを持たない観測。構築子（相手に位置を訊く）を通さずに確保する——理由はクラスの説明。
     */
    static EnemyObservation enemy(double distance, double priority, boolean armoured, double effectiveness,
            double threat, boolean attackingMe, boolean aiming) {
        EnemyObservation observation = allocate(EnemyObservation.class);

        observation.setGeometry(distance, Vec3.ZERO, Vec3.ZERO);
        observation.setSight(true, true, true, true);
        observation.setRanges(true, true);
        observation.setIntent(attackingMe, false, false, aiming);
        observation.setBody(false, armoured, 1.0F, WeaponReach.NONE);
        observation.setThreat(threat, threat, effectiveness);
        observation.setTargetPriority(priority);

        return observation;
    }

    /** 撃たれている味方の観測。構築子（エンティティを取る）を通さずに確保する。 */
    static AllyObservation ally(double health, int attacker) {
        AllyObservation observation = allocate(AllyObservation.class);

        observation.update(80.0, Vec3.ZERO, (float) health, true, true, VehicleRole.IFV,
                TacticalAction.CAPTURE_OBJECTIVE, attacker, -1, 0L);

        return observation;
    }

    /** 構築子を通さずにインスタンスを確保する（エンティティを取る構築子はゲームの外で落ちる）。 */
    static <T> T allocate(Class<T> type) {
        try {
            Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");

            theUnsafe.setAccessible(true);

            Object unsafe = theUnsafe.get(null);

            return type.cast(unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type));
        } catch (Exception exception) {
            throw new Skip("cannot allocate " + type.getSimpleName() + ": " + exception);
        }
    }

    static void policySupport() {
        S called = new S();

        called.objective = ENEMY_POINT;
        called.weight = 1.0;
        called.ally = ally(0.5, 7);
        called.need = 0.7;
        called.call = true;
        check("a support call from the team pulls a free tank off a routine capture",
                decide(called) == TacticalAction.SUPPORT_ALLY, top());

        S noticed = new S();

        noticed.objective = ENEMY_POINT;
        noticed.weight = 1.0;
        noticed.ally = ally(0.5, 7);
        noticed.need = 0.7;
        check("the same ally merely seen under fire does not", decide(noticed) == TacticalAction.CAPTURE_OBJECTIVE,
                top());

        S recapture = new S();

        recapture.objective = objective(CaptureState.ENEMY, "blue", false, true, 0L, 0, 1, 0.1, 0.0F).withScore(2.0);
        recapture.weight = 1.0;
        recapture.ally = ally(0.5, 7);
        recapture.need = 0.7;
        recapture.call = true;
        check("a lost point to retake still outranks a support call",
                decide(recapture) == TacticalAction.RECAPTURE_OBJECTIVE, top());
    }

    static void policyTarget() {
        S close = new S();

        close.objective = ENEMY_POINT;
        close.weight = 1.0;
        close.current = TacticalAction.CAPTURE_OBJECTIVE;
        close.threat = 0.6;
        close.exposure = 0.4;
        close.underFire = true;
        close.engaging = 1;
        close.target = enemy(50.0, 2.0, true, 1.0, 0.6, true, true);
        close.enemies.add(close.target);
        check("a tank shot at from close range fights", decide(close) == TacticalAction.ATTACK, top());

        S ifv = new S();

        ifv.role = VehicleRole.IFV;
        ifv.objective = ENEMY_POINT;
        ifv.weight = 0.5;
        ifv.threat = 0.4;
        ifv.exposure = 0.3;
        ifv.engaging = 1;
        ifv.frontal = true;
        ifv.onTarget = 1;
        ifv.target = enemy(100.0, 0.9, true, 0.37, 0.4, false, true);
        ifv.enemies.add(ifv.target);
        check("an IFV facing frontal armour, with a tank already on it, flanks", decide(ifv) == TacticalAction.FLANK,
                top());

        S distant = new S();

        distant.objective = ENEMY_POINT;
        distant.weight = 1.0;
        distant.current = TacticalAction.CAPTURE_OBJECTIVE;
        distant.threat = 0.1;
        distant.target = enemy(180.0, 0.4, true, 1.0, 0.1, false, false);
        distant.enemies.add(distant.target);
        check("a distant enemy doing nothing does not pull a tank off its point",
                decide(distant) == TacticalAction.CAPTURE_OBJECTIVE, top());

        S outnumbered = new S();

        outnumbered.health = 0.2;
        outnumbered.threat = 0.8;
        outnumbered.exposure = 0.6;
        outnumbered.underFire = true;
        outnumbered.engaging = 3;
        outnumbered.allies = 1;
        outnumbered.objective = ENEMY_POINT;
        outnumbered.weight = 1.0;
        outnumbered.current = TacticalAction.ATTACK;
        outnumbered.ticks = 10L;
        outnumbered.target = enemy(60.0, 2.0, true, 1.0, 0.7, true, true);
        outnumbered.enemies.add(outnumbered.target);
        check("enemies 3, allies 1, health 20%: RETREAT", decide(outnumbered) == TacticalAction.RETREAT, top());

        S tankFrontal = new S();

        tankFrontal.objective = ENEMY_POINT;
        tankFrontal.weight = 1.0;
        tankFrontal.threat = 0.4;
        tankFrontal.frontal = true;
        tankFrontal.onTarget = 1;
        tankFrontal.target = enemy(100.0, 0.9, true, 1.0, 0.4, false, true);
        tankFrontal.enemies.add(tankFrontal.target);
        check("a tank whose gun works on the target does not bother flanking",
                decide(tankFrontal) != TacticalAction.FLANK, top());

        S features = new S();

        features.target = close.target;
        features.objective = ENEMY_POINT;
        check("features with a target are the right length",
                features.build().features().length == BattleState.FEATURES.size(), null);
    }

    static void features() {
        S state = new S();

        state.objective = ENEMY_POINT;
        state.weight = 1.0;

        float[] vector = state.build().features();
        boolean sane = true;

        for (float value : vector) {
            sane &= Float.isFinite(value) && value >= 0.0F && value <= 1.5F;
        }

        check("the feature vector matches its names", vector.length == BattleState.FEATURES.size(),
                vector.length + " vs " + BattleState.FEATURES.size());
        check("features are finite and roughly unit-scaled", sane, java.util.Arrays.toString(vector));
    }

    static void statistics() {
        BattleStatistics.Tally tally = new BattleStatistics.Tally();

        tally.battles = 4;
        tally.wins = 2;
        tally.draws = 1;
        tally.losses = 1;
        tally.lives = 10;
        tally.kills = 5;
        tally.deaths = 8;
        tally.captures = 3;
        tally.recaptures = 1;
        tally.defenses = 2;
        tally.damage = 1000.0;
        tally.reward = 120.0;
        tally.survivalTicks = 10L * 20L * 60L;

        check("win rate counts a draw as half", tally.winRate() == 0.625, tally.winRate());
        check("kill and death rates are per life", tally.killRate() == 0.5 && tally.deathRate() == 0.8, null);
        check("capture rate includes recaptures, per battle", tally.captureRate() == 1.0, tally.captureRate());
        check("survival is averaged in seconds", tally.averageSurvivalSeconds() == 60.0,
                tally.averageSurvivalSeconds());
        check("an empty tally has zero rates, not NaN",
                new BattleStatistics.Tally().winRate() == 0.0 && new BattleStatistics.Tally().killRate() == 0.0,
                null);
    }

    static void payload() {
        AiDebugPayload.Machine first = new AiDebugPayload.Machine(12, "AI #12 leopard_2a6", 0xFFFF5555, "TANK",
                "FLANK", "B", "T-80U #4", 44, 0.72F, 0.31F, 0.64F, "rule_v1", new Vec3(1.25, 64.0, -3.5),
                new Vec3(10.5, 64.0, 20.5), new Vec3(8.0, 65.0, 18.0), 2,
                List.of(new Vec3(4.5, 0.0, 6.5), new Vec3(8.5, 0.0, 10.5)));
        AiDebugPayload.Machine second = new AiDebugPayload.Machine(13, "AI #13", 0xFF5555FF, "IFV", "-", "-", "-",
                -1, 0.0F, 0.0F, 1.0F, "rule_v2", new Vec3(0.0, 70.0, 0.0), null, null, -1, List.of());
        AiDebugPayload payload = new AiDebugPayload(List.of(first, second),
                List.of(new AiDebugPayload.Cell(-16, 32, 0.5F, 0.25F)), 8);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        AiDebugPayload.STREAM_CODEC.encode(buffer, payload);

        AiDebugPayload back = AiDebugPayload.STREAM_CODEC.decode(buffer);

        check("the debug payload survives the wire", back.equals(payload), back);
        check("nothing is left unread", buffer.readableBytes() == 0, buffer.readableBytes());
    }

    static void anchoring() {
        check("a tank well inside the capture circle anchors", Tactics.anchors(11.0, 16.0, false), null);
        check("one just inside the edge keeps driving in", !Tactics.anchors(12.5, 16.0, false), null);
        check("an anchored tank stays anchored near the edge", Tactics.anchors(14.5, 16.0, true), null);
        check("and lets go once it is really leaving", !Tactics.anchors(15.5, 16.0, true), null);
        check("a small circle keeps a proportionate margin",
                Tactics.anchors(5.0, 8.0, false) && !Tactics.anchors(5.5, 8.0, false), null);

        double braking = 0.03;
        double speed = Tactics.stoppingSpeed(8.0, braking);

        check("a vehicle at the stopping speed stops within the room", stopsWithin(speed, braking) <= 8.0 + braking,
                stopsWithin(speed, braking));
        check("and one a tenth faster does not", stopsWithin(speed * 1.1, braking) > 8.0,
                stopsWithin(speed * 1.1, braking));
        check("a standard circle does not slow a full-speed tank", Tactics.stoppingSpeed(16.0, braking) >= 0.944,
                Tactics.stoppingSpeed(16.0, braking));
    }

    /** 毎 tick その速さで進んでから {@code braking} だけ落とす車両が、止まるまでに走る距離。 */
    static double stopsWithin(double speed, double braking) {
        double travelled = 0.0;

        for (double left = speed; left > 0.0; left -= braking) {
            travelled += left;
        }

        return travelled;
    }

    static void flightStick() {
        Quaternionf level = Attitude.of(0.0F, 0.0F);
        Vec3 nose = Attitude.nose(level);
        Vec3 right = Attitude.right(level);

        FlightControl.Stick toRight = FlightControl.point(level, nose.add(right), 0.0F, 0.0F, 0.0F, false, 75.0F);
        check("a mark to the right banks right and kicks the rudder right",
                toRight.roll() > 0.0F && toRight.yaw() > 0.0F, toRight);

        FlightControl.Stick above = FlightControl.point(level, nose.add(0.0, 0.5, 0.0), 0.0F, 0.0F, 0.0F, false,
                75.0F);
        check("a mark above raises the nose without rolling", above.pitch() > 0.0F && Math.abs(above.roll()) < 1.0E-3F,
                above);

        Vec3 behind = FlightControl.inCone(nose, right, nose.scale(-1.0));
        check("straight behind becomes a turn to the right", behind.dot(right) > 0.5 && behind.dot(nose) > 0.8, behind);

        FlightControl.Stick turnBack = FlightControl.point(level, nose.scale(-1.0), 0.0F, 0.0F, 0.0F, false, 75.0F);
        check("so a target behind starts a turn instead of flying on", turnBack.roll() > 0.5F, turnBack);

        Quaternionf banked = Attitude.rotate(new Quaternionf(level), 60.0F, 0.0F, 0.0F);
        FlightControl.Stick rollOut = FlightControl.point(banked, Attitude.nose(banked), 0.0F, 0.0F, 0.0F, false,
                75.0F);
        check("banked right with the mark ahead, it rolls back and holds the turn",
                rollOut.roll() < 0.0F && rollOut.pitch() > 0.0F, rollOut);

        FlightControl.Stick capped = FlightControl.point(level, nose.add(right.scale(0.3)), 0.0F, 0.0F, 0.0F, false,
                10.0F);
        check("a bank limit caps the roll demand", capped.roll() <= 10.0F * 0.04F + 1.0E-6F, capped);

        Quaternionf start = Attitude.of(30.0F, -10.0F);
        Quaternionf turned = Attitude.rotate(new Quaternionf(start), 2.0F, 1.5F, -0.8F);
        float[] rates = FlightControl.bodyRates(start, turned);
        check("measured rates match the rotation the flight model applies (pitch, yaw, roll)",
                Math.abs(rates[0] - 1.5F) < 0.1F && Math.abs(rates[1] + 0.8F) < 0.1F && Math.abs(rates[2] - 2.0F) < 0.1F,
                java.util.Arrays.toString(rates));

        FlightControl.Stick forward = FlightControl.rotor(level, Vec3.ZERO, nose.scale(1.5), null, 0.0F, 0.0F, 0.0F,
                15.0F);
        check("a helicopter asked forward lowers the nose", forward.pitch() < 0.0F && Math.abs(forward.roll()) < 1.0E-3F,
                forward);

        FlightControl.Stick sideways = FlightControl.rotor(level, Vec3.ZERO, right, nose, 0.0F, 0.0F, 0.0F, 15.0F);
        check("asked to the right, it banks right and keeps its heading",
                sideways.roll() > 0.0F && Math.abs(sideways.yaw()) < 1.0E-3F, sideways);

        FlightControl.Stick stopping = FlightControl.rotor(level, nose, Vec3.ZERO, null, 0.0F, 0.0F, 0.0F, 15.0F);
        check("moving forward and asked to stop, it raises the nose", stopping.pitch() > 0.0F, stopping);

        FlightControl.Stick facing = FlightControl.rotor(level, Vec3.ZERO, Vec3.ZERO, right, 0.0F, 0.0F, 0.0F, 15.0F);
        check("the pedals turn the nose toward where it should face", facing.yaw() > 0.0F, facing);

        check("the collective climbs toward a higher hover and sinks toward a lower one",
                FlightControl.collective(100.0, 130.0) > 0.0F && FlightControl.collective(100.0, 70.0) < 0.0F, null);
        check("the throttle opens when slow and closes when fast",
                FlightControl.throttle(3.0, 5.0, 0.5F) > 0.0F && FlightControl.throttle(6.0, 5.0, 0.5F) < 0.0F, null);
        check("but never pushes through the afterburner gate", FlightControl.throttle(3.0, 5.0, 0.99F) == 0.0F, null);
    }

    static void delivery() {
        WeaponDefinition.Projectile still = bomb(0.0F);
        Delivery.Impact impact = Delivery.drop(new Vec3(0.0, 200.0, 0.0), new Vec3(5.0, 0.0, 0.0), still, 0.0);

        // 高さ200: g·n(n-1)/2 = 200 を跨ぐのは 128 tick と 129 tick の間。
        check("a bomb in still air falls for the ticks the height demands",
                impact != null && Math.abs(impact.ticks() - 128.3) < 1.0, impact);
        check("and carries the aircraft's speed forward",
                impact != null && Math.abs(impact.at().x - 5.0 * impact.ticks()) < 1.0E-6, impact);

        Delivery.Impact slowed = Delivery.drop(new Vec3(0.0, 200.0, 0.0), new Vec3(5.0, 0.0, 0.0), bomb(0.0005F), 0.0);
        check("drag shortens the throw", impact != null && slowed != null && slowed.at().x < impact.at().x - 1.0,
                slowed);

        Delivery.Miss miss = Delivery.miss(new Vec3(100.0, 0.0, 0.0), new Vec3(110.0, 0.0, 3.0),
                new Vec3(5.0, -1.0, 0.0));
        check("a target ahead of the impact reads as short, by the along-track distance",
                Math.abs(miss.along() - 10.0) < 1.0E-9 && Math.abs(Math.abs(miss.across()) - 3.0) < 1.0E-9, miss);

        Vec3 released = Delivery.release(new Vec3(5.0, 0.0, 0.0), new Vec3(0.0, 1.0, 0.0), still);
        check("the rack pushes the bomb down by its own speed",
                Math.abs(released.y + 0.15) < 1.0E-6 && released.x == 5.0, released);
    }

    static WeaponDefinition.Projectile bomb(float drag) {
        // 兵装の定義はゲームの中では外側のクラスから初期化される。弾の記録から先に触ると、互いの Codec を待ったまま
        // null を読んで落ちる。
        java.util.Objects.requireNonNull(WeaponDefinition.CODEC);

        return new WeaponDefinition.Projectile(40.0F, 0.15F, 0.0F, 0, 0, 0.0F, 0.0245F, 4000.0F, 11.0F, 0.0F, 0x7C806E,
                0.0F, 0.0F, drag, 0.12F, java.util.Optional.empty());
    }

    static void airStores() {
        check("a heat seeker shoots at aircraft (AIM-9)",
                AirLoadout.hitsAir(guidance(WeaponDefinition.Guidance.Seeker.HEAT, 3.0F)), null);
        check("so does a radar missile with a long fuse (AIM-120)",
                AirLoadout.hitsAir(guidance(WeaponDefinition.Guidance.Seeker.RADAR, 4.0F)), null);
        check("but not one that hits directly (Kh-25)",
                !AirLoadout.hitsAir(guidance(WeaponDefinition.Guidance.Seeker.RADAR, 2.0F)), null);
        check("nor a laser or a coordinate",
                !AirLoadout.hitsAir(guidance(WeaponDefinition.Guidance.Seeker.LASER, 1.5F))
                        && !AirLoadout.hitsAir(guidance(WeaponDefinition.Guidance.Seeker.POINT, 45.0F)), null);
        check("a team of two fields no aircraft", Bots.airSlots(2) == 0, Bots.airSlots(2));
        check("the default five field one", Bots.airSlots(5) == 1, Bots.airSlots(5));
        check("twenty field six", Bots.airSlots(20) == 6, Bots.airSlots(20));
    }

    static void killScore() {
        KillScore.Weights weights = KillScore.Weights.of(ParameterSet.EMPTY);
        KillScore.Flight jet = new KillScore.Flight(false, 5.0, KillScore.turnRadius(5.0), 1800.0, 900.0, 550.0, true);
        KillScore.Flight dry = new KillScore.Flight(false, 5.0, KillScore.turnRadius(5.0), 1800.0, 900.0, 550.0, false);
        KillScore.Round m61 = new KillScore.Round(AirLoadout.Store.GUN, 5.0, 0.0, 0.0, 100.0, 45.0, 0.00045, false);
        KillScore.Round browning = new KillScore.Round(AirLoadout.Store.GUN, 6.0, 0.0, 0.0, 40.0, 14.0, 0.00042, false);
        KillScore.Round fab500 = new KillScore.Round(AirLoadout.Store.BOMB, 40.0, 11.0, 0.0, 2.0, 0.0, 0.0, false);
        KillScore.Mark tank = new KillScore.Mark(false, false, true, 5.0, 1.0, 0.0);
        KillScore.Mark fighter = new KillScore.Mark(true, false, false, 0.0, 1.0, 5.0);

        check("a machine gun that glances off armour cannot hurt a tank",
                KillScore.damagePerPass(browning, tank, 900) == 0.0, KillScore.damagePerPass(browning, tank, 900));
        check("a 20mm cannon can, through the vertical faces", KillScore.damagePerPass(m61, tank, 500) > 0.0,
                KillScore.damagePerPass(m61, tank, 500));
        check("bombs do nothing to an aircraft", KillScore.damagePerPass(fab500, fighter, 4) == 0.0, null);
        check("the blast reaches twice the power and no further",
                KillScore.blast(11.0, 22.0) == 0.0 && KillScore.blast(11.0, 0.0) > 150.0, KillScore.blast(11.0, 0.0));
        check("a bomb's own blast is whole out to its power, half way on, and gone at twice",
                WeaponDefinition.Projectile.blastAt(300.0, 8.3, 8.0) == 300.0
                        && Math.abs(WeaponDefinition.Projectile.blastAt(300.0, 8.3, 12.45) - 150.0) < 1.0E-6
                        && WeaponDefinition.Projectile.blastAt(300.0, 8.3, 16.6) == 0.0,
                WeaponDefinition.Projectile.blastAt(300.0, 8.3, 12.45));
        KillScore.Round heavy = new KillScore.Round(AirLoadout.Store.BOMB, 500.0, 8.3, 300.0, 2.0, 0.0, 0.0, false);
        // 狙いのずれが芯（威力のブロック数）の内側なら、1発の見込みは爆風の300と直撃の500の間に来る。
        check("a bomb with its own blast is scored between its blast and its direct hit",
                KillScore.damagePerPass(heavy, tank, 4) >= KillScore.BOMBS_PER_PASS * 300.0
                        && KillScore.damagePerPass(heavy, tank, 4) < KillScore.BOMBS_PER_PASS * 500.0,
                KillScore.damagePerPass(heavy, tank, 4) + " vs vanilla " + KillScore.damagePerPass(fab500, tank, 4));

        KillScore.Prospect healthy = prospect(false, 2000.0, 0.0, 500.0, 4, false, 0, 0.0);
        double base = KillScore.score(healthy, jet, weights, false, false);

        check("a wounded target outscores a healthy one in the same place",
                KillScore.score(prospect(false, 2000.0, 0.0, 120.0, 4, false, 0, 0.0), jet, weights, false, false)
                        > base, base);
        check("a target behind costs the turn",
                KillScore.score(prospect(false, 2000.0, Math.PI, 500.0, 4, false, 0, 0.0), jet, weights, false, false)
                        < base, null);
        check("without the ammunition to finish it, the kill is unlikely",
                KillScore.killsPerMinute(prospect(false, 2000.0, 0.0, 500.0, 1, false, 0, 0.0), jet)
                        < KillScore.killsPerMinute(healthy, jet), null);
        check("destroying a vehicle is worth more than downing an aircraft, which is still worth something",
                weights.air() < 1.0 && weights.gunAir() > 0.0 && weights.gunAir() <= weights.air(), weights);
        check("an aircraft still scores when there is nothing else",
                KillScore.score(prospect(true, 2000.0, 0.0, 500.0, 4, false, 0, 0.0), jet, weights, false, false) > 0.0,
                null);
        check("an enemy fighter close enough to threaten me is still engaged before an idle ground target",
                KillScore.score(new KillScore.Prospect(true, 800.0, 0.0, 500.0, 230.0, 4, false, true, false, 0, 0.0,
                        0.0, 0.0, 0.0), jet, weights, false, false) > base, null);
        check("with the missiles spent, the ground comes first",
                KillScore.score(prospect(true, 2000.0, 0.0, 500.0, 4, false, 0, 0.0), dry, weights, false, false)
                        < KillScore.score(healthy, dry, weights, false, false), null);
        check("air defence around a target lowers its score",
                KillScore.score(prospect(false, 2000.0, 0.0, 500.0, 4, false, 0, 2.0), jet, weights, false, false)
                        < base, null);
        check("a wingman already on it lowers its score",
                KillScore.score(prospect(false, 2000.0, 0.0, 500.0, 4, false, 1, 0.0), jet, weights, false, false)
                        < base, null);
        check("someone shooting at me outranks an idle target of the same kind",
                KillScore.score(prospect(false, 2000.0, 0.0, 500.0, 4, true, 0, 0.0), jet, weights, false, false)
                        > base, null);
        check("an attack run under way holds against a slightly better target",
                KillScore.score(healthy, jet, weights, true, true)
                        > KillScore.score(prospect(false, 1500.0, 0.0, 500.0, 4, false, 0, 0.0), jet, weights, false,
                                false), null);

        double radius = 800.0;
        KillScore.Prospect wounded = prospect(false, 2000.0, 0.0, 120.0, 4, false, 0, 0.0);
        double centre = KillScore.score(placed(healthy, -radius, radius), jet, weights, false, false);
        double edge = KillScore.score(placed(healthy, 0.0, radius), jet, weights, false, false);
        double past = KillScore.score(placed(healthy, 200.0, radius), jet, weights, false, false);
        double far = KillScore.score(placed(healthy, 900.0, radius), jet, weights, false, false);

        check("without an airspace the place changes nothing",
                KillScore.score(placed(healthy, 5000.0, 0.0), jet, weights, false, false) == base && edge == base, edge);
        check("deeper inside the airspace scores higher", centre > edge, centre / edge);
        check("just past the edge costs little", past > 0.85 * edge && past < edge, past / edge);
        check("far outside costs most of the score", far < 0.2 * edge, far / edge);
        check("a much easier kill just past the edge still wins",
                KillScore.score(placed(wounded, 200.0, radius), jet, weights, false, false) > centre, null);
        check("far outside, even an easy kill yields to one inside",
                KillScore.score(placed(wounded, 900.0, radius), jet, weights, false, false) < centre, null);
        check("someone shooting at me counts in full wherever they are",
                Math.abs(KillScore.score(placed(prospect(false, 2000.0, 0.0, 500.0, 4, true, 0, 0.0), 900.0, radius),
                        jet, weights, false, false) - far - weights.attackingMe()) < 1e-9, null);
        check("a target shooting at an ally who called for help rises by the call",
                Math.abs(KillScore.score(supported(healthy, 1.0), jet, weights, false, false) - base - weights.support())
                        < 1e-9, null);
        check("and by less for a milder call",
                KillScore.score(supported(healthy, 0.4), jet, weights, false, false)
                        < KillScore.score(supported(healthy, 1.0), jet, weights, false, false), null);
    }

    /** 1回の航過で 230 点削れる相手の見込み。戦闘空域は無い。 */
    static KillScore.Prospect prospect(boolean airborne, double distance, double offTrack, double health, int passes,
            boolean attackingMe, int allies, double defence) {
        return new KillScore.Prospect(airborne, distance, offTrack, health, 230.0, passes, attackingMe, false, false,
                allies, defence, 0.0, 0.0, 0.0);
    }

    /** 同じ相手を、戦闘空域の縁から outside（内側は負）の場所に置いた物。 */
    static KillScore.Prospect placed(KillScore.Prospect prospect, double outside, double airspace) {
        return new KillScore.Prospect(prospect.airborne(), prospect.distance(), prospect.offTrack(), prospect.health(),
                prospect.damagePerPass(), prospect.passes(), prospect.attackingMe(), prospect.threatensMe(),
                prospect.onObjective(), prospect.allies(), prospect.defence(), outside, airspace, prospect.support());
    }

    /** 同じ相手が、支援を要請した味方を撃っている（急ぎ support）。 */
    static KillScore.Prospect supported(KillScore.Prospect prospect, double support) {
        return new KillScore.Prospect(prospect.airborne(), prospect.distance(), prospect.offTrack(), prospect.health(),
                prospect.damagePerPass(), prospect.passes(), prospect.attackingMe(), prospect.threatensMe(),
                prospect.onObjective(), prospect.allies(), prospect.defence(), prospect.outside(), prospect.airspace(),
                support);
    }

    static void supportCalls() {
        SupportCalls.Call call = new SupportCalls.Call(1, new Vec3(0, 64, 0), 1, 0.8, new int[] {90, 91}, 100L);
        List<SupportCalls.Candidate> withCaller = List.of(
                new SupportCalls.Candidate(2, new Vec3(50, 64, 0)),
                new SupportCalls.Candidate(3, new Vec3(0, 64, 120)),
                new SupportCalls.Candidate(4, new Vec3(400, 64, 0)),
                new SupportCalls.Candidate(5, new Vec3(-20, 64, 0)),
                new SupportCalls.Candidate(1, new Vec3(1, 64, 1)));
        Map<Integer, SupportCalls.Call> matched = SupportCalls.match(List.of(call), withCaller, SupportCalls.REACH,
                Map.of());

        check("one short, the nearest two answer; the caller and the one out of reach do not",
                matched.keySet().equals(Set.of(2, 5)), matched.keySet());

        List<SupportCalls.Candidate> others = List.of(
                new SupportCalls.Candidate(2, new Vec3(50, 64, 0)),
                new SupportCalls.Candidate(3, new Vec3(0, 64, 120)),
                new SupportCalls.Candidate(5, new Vec3(-20, 64, 0)),
                new SupportCalls.Candidate(7, new Vec3(0, 64, -100)));
        SupportCalls.Call worse = new SupportCalls.Call(6, new Vec3(30, 64, 0), 2, 1.0, new int[] {92}, 100L);
        Map<Integer, SupportCalls.Call> both = SupportCalls.match(List.of(call, worse), others, SupportCalls.REACH,
                Map.of());

        check("the more urgent call is served first and nobody answers twice",
                both.values().stream().filter(each -> each == worse).count() == 3
                        && both.values().stream().filter(each -> each == call).count() == 1, both.keySet());

        Map<Integer, SupportCalls.Call> kept = SupportCalls.match(List.of(call), others, SupportCalls.REACH,
                Map.of(3, 1));

        check("an ally already on its way keeps the job over a nearer one", kept.containsKey(3) && kept.size() == 2,
                kept.keySet());
        check("three short is as urgent as it gets", SupportCalls.urgency(3, 1.0, false) == 1.0,
                SupportCalls.urgency(3, 1.0, false));
        check("one short at full health is a mild call", Math.abs(SupportCalls.urgency(1, 1.0, false) - 1.0 / 3.0) < 1e-9,
                SupportCalls.urgency(1, 1.0, false));
        check("being hit by something my rounds cannot hurt raises it",
                SupportCalls.urgency(1, 1.0, true) > SupportCalls.urgency(1, 1.0, false), null);

        SupportCalls board = new SupportCalls();

        check("a new call is reported once", board.raise(call) && !board.raise(call), null);
        board.assign(others, 110L);
        check("an assigned ally finds the call", board.answerFor(5, 120L) == call, board.answerFor(5, 120L));
        check("aircraft see who is shooting at the caller",
                board.airSupport(90, 120L) == 0.8 && board.airSupport(99, 120L) == 0.0, null);

        long late = 100L + SupportCalls.LIFETIME + 1L;

        board.assign(others, late);
        check("a call not renewed runs out", board.answerFor(5, late) == null && board.airSupport(90, late) == 0.0,
                null);

        board.raise(new SupportCalls.Call(1, new Vec3(0, 64, 0), 1, 0.8, new int[] {90}, 300L));
        board.withdraw(1);
        board.assign(others, 310L);
        check("a withdrawn call sends nobody", board.answerFor(5, 310L) == null, null);
    }

    static void reinforcements() {
        Reinforcements.Weights weights = Reinforcements.Weights.of(ParameterSet.EMPTY);
        Map<Reinforcements.Kind, Double> calm = Reinforcements.needs(field(0, 0, 0, 0, 0, 0), weights);
        Map<Reinforcements.Kind, Double> raid = Reinforcements.needs(field(3, 0, 0, 0, 0, 0), weights);
        Map<Reinforcements.Kind, Double> covered = Reinforcements.needs(field(3, 0, 0, 4, 0, 0), weights);
        Map<Reinforcements.Kind, Double> flak = Reinforcements.needs(field(0, 0, 3, 0, 0, 0), weights);
        Map<Reinforcements.Kind, Double> points = Reinforcements.needs(field(0, 0, 0, 0, 3, 0), weights);
        Map<Reinforcements.Kind, Double> armour = Reinforcements.needs(field(0, 4, 0, 0, 0, 0), weights);
        Map<Reinforcements.Kind, Double> shotDown = Reinforcements.needs(field(0, 0, 0, 0, 0, 2), weights);

        check("with nothing going on, ground forces lead and the air is quiet",
                (top(calm) == Reinforcements.Kind.TANK || top(calm) == Reinforcements.Kind.LIGHT)
                        && calm.get(Reinforcements.Kind.AIR_DEFENCE) <= 0.0, calm);
        check("enemy aircraft make air defence the biggest need", top(raid) == Reinforcements.Kind.AIR_DEFENCE, raid);
        check("air defence already out there covers them",
                covered.get(Reinforcements.Kind.AIR_DEFENCE) < covered.get(Reinforcements.Kind.TANK), covered);
        check("enemy air defence keeps strike aircraft home", flak.get(Reinforcements.Kind.STRIKER) <= 0.0, flak);
        check("points to take call for light armour", top(points) == Reinforcements.Kind.LIGHT, points);
        check("enemy armour calls for tanks", top(armour) == Reinforcements.Kind.TANK, armour);
        check("losses to aircraft call for air defence even with none in sight",
                shotDown.get(Reinforcements.Kind.AIR_DEFENCE) > 0.0, shotDown);
        check("the choice keeps to what can be fielded",
                EnumSet.of(Reinforcements.Kind.TANK, Reinforcements.Kind.LIGHT).contains(Reinforcements.choose(raid,
                        EnumSet.of(Reinforcements.Kind.TANK, Reinforcements.Kind.LIGHT), 0.99)), null);

        int light = 0;

        for (int roll = 0; roll < 100; roll++) {
            if (Reinforcements.choose(points, EnumSet.allOf(Reinforcements.Kind.class), (roll + 0.5) / 100.0)
                    == Reinforcements.Kind.LIGHT) {
                light++;
            }
        }

        check("the biggest need wins most draws", light > 60, light);
        check("with no need anywhere it still fields something",
                Reinforcements.choose(Map.of(), EnumSet.of(Reinforcements.Kind.ARTILLERY), 0.3)
                        == Reinforcements.Kind.ARTILLERY, null);

        Reinforcements.clear();
        Reinforcements.lost("check", Reinforcements.Cause.AIR, 100L);
        Reinforcements.lost("check", Reinforcements.Cause.AIR, 150L);
        check("recent losses are counted by cause",
                Reinforcements.lostTo("check", Reinforcements.Cause.AIR, 200L) == 2
                        && Reinforcements.lostTo("check", Reinforcements.Cause.ARMOUR, 200L) == 0, null);
        check("and forgotten after two minutes", Reinforcements.lostTo("check", Reinforcements.Cause.AIR, 2700L) == 0,
                null);
        Reinforcements.clear();

        check("seven tenths of the tickets left is all the room there is",
                Reinforcements.spare(100, 100) == 1.0 && Reinforcements.spare(70, 100) == 1.0
                        && Reinforcements.spare(150, 100) == 1.0, Reinforcements.spare(70, 100));
        check("half left is half the room", Math.abs(Reinforcements.spare(50, 100) - 0.5) < 1e-9,
                Reinforcements.spare(50, 100));
        check("down to three tenths there is none", Reinforcements.spare(30, 100) == 0.0
                && Reinforcements.spare(5, 100) == 0.0, Reinforcements.spare(30, 100));

        Map<Reinforcements.Kind, Double> plenty = Reinforcements.needs(field(0, 0, 0, 0, 2, 0, 1.0), weights);
        Map<Reinforcements.Kind, Double> scarce = Reinforcements.needs(field(0, 0, 0, 0, 2, 0, 0.0), weights);
        Map<Reinforcements.Kind, Double> guarded = Reinforcements.needs(field(0, 0, 3, 0, 0, 0, 1.0), weights);

        check("tickets to spare call aircraft up",
                plenty.get(Reinforcements.Kind.FIGHTER) > scarce.get(Reinforcements.Kind.FIGHTER)
                        && plenty.get(Reinforcements.Kind.STRIKER) > scarce.get(Reinforcements.Kind.STRIKER), plenty);
        check("and leave the ground forces as they were",
                plenty.get(Reinforcements.Kind.LIGHT).equals(scarce.get(Reinforcements.Kind.LIGHT))
                        && plenty.get(Reinforcements.Kind.TANK).equals(scarce.get(Reinforcements.Kind.TANK)), null);
        check("with tickets to spare, a quarter or more of the draws fly", airDraws(plenty) >= 50, airDraws(plenty));
        check("without, few do", airDraws(scarce) <= 20, airDraws(scarce));
        check("even with tickets to spare, enemy air defence keeps aircraft behind the ground forces",
                guarded.get(Reinforcements.Kind.STRIKER) < guarded.get(Reinforcements.Kind.TANK)
                        && guarded.get(Reinforcements.Kind.FIGHTER) <= 0.0, guarded);
    }

    /** 200回のくじ（等間隔の目）のうち、航空機の種類が出た回数。 */
    static int airDraws(Map<Reinforcements.Kind, Double> needs) {
        int flying = 0;

        for (int roll = 0; roll < 200; roll++) {
            Reinforcements.Kind kind = Reinforcements.choose(needs, EnumSet.allOf(Reinforcements.Kind.class),
                    (roll + 0.5) / 200.0);

            if (kind != null && kind.flies()) {
                flying++;
            }
        }

        return flying;
    }

    /** 味方5両の陣営から見た戦場。チケットの余裕は無い。 */
    static Reinforcements.Situation field(double enemyAir, double enemyArmour, double enemyAirDefence,
            double ownAirDefence, double capturable, double lostToAir) {
        return field(enemyAir, enemyArmour, enemyAirDefence, ownAirDefence, capturable, lostToAir, 0.0);
    }

    static Reinforcements.Situation field(double enemyAir, double enemyArmour, double enemyAirDefence,
            double ownAirDefence, double capturable, double lostToAir, double spare) {
        return new Reinforcements.Situation(5.0, enemyAir, enemyArmour, 0.0, enemyAirDefence, 0.0, 0.0,
                ownAirDefence, 0.0, 0.0, 0.0, capturable, 0.0, lostToAir, 0.0, 0.0, spare);
    }

    static Reinforcements.Kind top(Map<Reinforcements.Kind, Double> needs) {
        Reinforcements.Kind best = null;

        for (Map.Entry<Reinforcements.Kind, Double> entry : needs.entrySet()) {
            if (best == null || entry.getValue() > needs.get(best)) {
                best = entry.getKey();
            }
        }

        return best;
    }

    static WeaponDefinition.Guidance guidance(WeaponDefinition.Guidance.Seeker seeker, float proximity) {
        return new WeaponDefinition.Guidance(3.5F, 12.0F, 3000.0F, 8, 60.0F, proximity, 3.5F, seeker, 8, 40, 0.2F);
    }
}
