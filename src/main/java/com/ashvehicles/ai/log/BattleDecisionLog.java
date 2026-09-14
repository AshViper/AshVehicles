package com.ashvehicles.ai.log;

import java.util.Map;

import javax.annotation.Nullable;

import com.ashvehicles.ai.BotPilot;
import com.ashvehicles.ai.decision.BattleState;
import com.ashvehicles.ai.decision.TacticalAction;
import com.ashvehicles.ai.navigation.Navigator;
import com.ashvehicles.ai.objective.CaptureState;
import com.ashvehicles.ai.objective.ObjectiveState;
import com.ashvehicles.ai.perception.EnemyObservation;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.world.phys.Vec3;

/**
 * 判断1回の記録。学習の1行。
 *
 * <p><b>状態・行動・その後に起きたこと。</b> 状態は判断した瞬間の要約と特徴量（{@link BattleState#features}）、
 * 行動は選んだ1つ、そして<b>報酬は次の判断を記録するまでに受け取った分の合計</b>——判断と判断の間の tick を1行に
 * 畳むので、毎 tick 書かなくても「この判断の後に何が起きたか」が残る。
 *
 * <p>行が書かれるのは、次の判断が記録されたとき・その車両が倒れたとき・戦闘が終わったとき。それまでは報酬を
 * 足しながら持っている。
 *
 * <p><b>位置と走り方も載せる</b>（{@code x y z}・{@code navigation}）。学習の特徴量ではなく、後から「進めていたか」を
 * 読むため——拠点へ向かわない AI を記録から調べたとき、行動と拠点までの距離だけでは、詰まっているのか戦域の
 * 外にいるのかが分からなかった（2026-09-13）。
 */
public final class BattleDecisionLog {
    private final long timestamp;
    private final long wallClock;
    private final String bot;
    private final int entityId;
    private final String team;
    private final String version;
    private final String role;
    private final String vehicle;
    private final Vec3 position;
    private final TacticalAction action;

    @Nullable
    private final TacticalAction previous;

    @Nullable
    private final String objectiveId;

    private final double distanceToObjective;
    private final double enemyThreat;
    private final double allySupport;
    private final double routeRisk;
    private final boolean enemyVisible;
    private final boolean objectiveContested;
    private final double health;
    private final Navigator.Status navigation;
    private final float[] features;
    private final Map<TacticalAction, Double> utilities;

    private double reward;

    private BattleDecisionLog(long timestamp, long wallClock, String bot, int entityId, String team, String version,
            String role, String vehicle, Vec3 position, TacticalAction action, @Nullable TacticalAction previous,
            @Nullable String objectiveId, double distanceToObjective, double enemyThreat, double allySupport,
            double routeRisk, boolean enemyVisible, boolean objectiveContested, double health,
            Navigator.Status navigation, float[] features, Map<TacticalAction, Double> utilities) {
        this.timestamp = timestamp;
        this.wallClock = wallClock;
        this.bot = bot;
        this.entityId = entityId;
        this.team = team;
        this.version = version;
        this.role = role;
        this.vehicle = vehicle;
        this.position = position;
        this.action = action;
        this.previous = previous;
        this.objectiveId = objectiveId;
        this.distanceToObjective = distanceToObjective;
        this.enemyThreat = enemyThreat;
        this.allySupport = allySupport;
        this.routeRisk = routeRisk;
        this.enemyVisible = enemyVisible;
        this.objectiveContested = objectiveContested;
        this.health = health;
        this.navigation = navigation;
        this.features = features;
        this.utilities = utilities;
    }

    /** その判断の記録。 */
    public static BattleDecisionLog of(BotPilot pilot, BattleState state, @Nullable TacticalAction previous,
            TacticalAction action, Map<TacticalAction, Double> utilities, @Nullable ObjectiveState objective,
            Navigator.Status navigation) {
        boolean visible = false;

        for (EnemyObservation enemy : state.enemies()) {
            if (enemy.visible()) {
                visible = true;

                break;
            }
        }

        String vehicle = pilot.vehicle() instanceof GroundVehicleEntity ground ? ground.getVehicleId().toString()
                : pilot.vehicle().getType().toShortString();

        return new BattleDecisionLog(state.gameTime(), System.currentTimeMillis(), pilot.vehicle().getStringUUID(),
                state.entityId(), state.team(), state.version(), state.role().id(), vehicle, state.position(), action,
                previous, objective == null ? null : objective.id(), state.objectiveDistance(), state.threat(),
                Math.min(state.alliesNear() / 3.0, 1.0), state.routeRisk(), visible,
                objective != null && (objective.state() == CaptureState.CONTESTED || objective.beingTaken()),
                state.health(), navigation, state.features(), Map.copyOf(utilities));
    }

    public void addReward(double value) {
        this.reward += value;
    }

    public double reward() {
        return this.reward;
    }

    public TacticalAction action() {
        return this.action;
    }

    public long timestamp() {
        return this.timestamp;
    }

    /**
     * 記録の1行。
     *
     * @param closedAt この判断が次の判断に替わった（か、終わった）時刻
     */
    public JsonObject toJson(String battleId, long closedAt) {
        JsonObject json = new JsonObject();

        json.addProperty("battle", battleId);
        json.addProperty("t", this.timestamp);
        json.addProperty("wall", this.wallClock);
        json.addProperty("duration", Math.max(0L, closedAt - this.timestamp));
        json.addProperty("bot", this.bot);
        json.addProperty("entity", this.entityId);
        json.addProperty("team", this.team);
        json.addProperty("version", this.version);
        json.addProperty("role", this.role);
        json.addProperty("vehicle", this.vehicle);
        json.addProperty("x", tenth(this.position.x));
        json.addProperty("y", tenth(this.position.y));
        json.addProperty("z", tenth(this.position.z));
        json.addProperty("action", this.action.name());
        json.addProperty("previous", this.previous == null ? null : this.previous.name());
        json.addProperty("objective", this.objectiveId);
        json.addProperty("distance_to_objective", round(this.distanceToObjective));
        json.addProperty("enemy_threat", round(this.enemyThreat));
        json.addProperty("ally_support", round(this.allySupport));
        json.addProperty("route_risk", round(this.routeRisk));
        json.addProperty("enemy_visible", this.enemyVisible);
        json.addProperty("objective_contested", this.objectiveContested);
        json.addProperty("health", round(this.health));
        json.addProperty("reward", round(this.reward));

        JsonObject navigation = new JsonObject();

        navigation.addProperty("leashed", this.navigation.leashed());
        navigation.addProperty("lost", this.navigation.lost());
        navigation.addProperty("jams", this.navigation.jams());
        navigation.addProperty("backing", this.navigation.backing());
        navigation.addProperty("following", this.navigation.following());
        navigation.addProperty("remaining", this.navigation.remaining());
        navigation.addProperty("route_complete", this.navigation.routeComplete());
        navigation.addProperty("water", this.navigation.water());
        navigation.addProperty("escaping", this.navigation.escaping());
        json.add("navigation", navigation);

        JsonArray features = new JsonArray(this.features.length);

        for (float feature : this.features) {
            features.add(round(feature));
        }

        json.add("features", features);

        JsonObject utilities = new JsonObject();

        for (Map.Entry<TacticalAction, Double> entry : this.utilities.entrySet()) {
            utilities.addProperty(entry.getKey().name(), round(entry.getValue()));
        }

        json.add("utilities", utilities);

        return json;
    }

    static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static double tenth(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
