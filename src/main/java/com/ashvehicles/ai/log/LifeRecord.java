package com.ashvehicles.ai.log;

import java.util.EnumMap;
import java.util.UUID;

import javax.annotation.Nullable;

import com.ashvehicles.ai.BotPilot;
import com.ashvehicles.ai.decision.TacticalAction;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.VehicleEntityBase;
import com.google.gson.JsonObject;

/**
 * AI の車両1両の、出撃から撃破（か戦闘の終わり）までの記録。
 *
 * <p><b>1両の一生で1行。</b> 倒されて出直した車両は別のエンティティで、別の行になる——同じ AI が何度出たかではなく、
 * 1回の出撃で何をしたかを数える。統計（{@code learning/BattleStatistics}）はこれを版ごとに足す。
 */
public final class LifeRecord {
    final UUID uuid;
    final int entityId;
    final String team;
    final String version;
    final String vehicle;
    final String role;
    final long spawnTick;

    long endTick = -1L;
    boolean destroyed;
    int kills;
    float damageDealt;
    float damageTaken;
    int captures;
    int recaptures;
    int defenses;
    double reward;
    int decisions;
    final EnumMap<TacticalAction, Integer> actions = new EnumMap<>(TacticalAction.class);

    /** 報酬を受け取っている最中の判断。次の判断が来るか、一生が終われば書かれる。 */
    @Nullable
    BattleDecisionLog open;

    LifeRecord(BotPilot pilot, long now) {
        this.uuid = pilot.vehicle().getUUID();
        this.entityId = pilot.vehicle().getId();
        this.team = pilot.team();
        this.version = pilot.version();
        this.vehicle = vehicleName(pilot.vehicle());
        this.role = pilot.role().id();
        this.spawnTick = now;
    }

    /** 記録に書く車両の名前。地上車両は定義の ID（名前空間付き）、航空機はエンティティの型の短い名前（{@code jas_39}）。 */
    static String vehicleName(VehicleEntityBase vehicle) {
        return vehicle instanceof GroundVehicleEntity ground ? ground.getVehicleId().toString()
                : vehicle.getType().toShortString();
    }

    public String team() {
        return this.team;
    }

    public String version() {
        return this.version;
    }

    public boolean destroyed() {
        return this.destroyed;
    }

    public int kills() {
        return this.kills;
    }

    public float damageDealt() {
        return this.damageDealt;
    }

    public int captures() {
        return this.captures;
    }

    public int recaptures() {
        return this.recaptures;
    }

    public int defenses() {
        return this.defenses;
    }

    public double reward() {
        return this.reward;
    }

    /** 生きていた長さ（tick）。まだ終わっていなければ今まで。 */
    public long survivalTicks(long now) {
        return Math.max(0L, (this.endTick >= 0L ? this.endTick : now) - this.spawnTick);
    }

    public boolean ended() {
        return this.endTick >= 0L;
    }

    JsonObject toJson(String battleId, long now, @Nullable String winner) {
        JsonObject json = new JsonObject();

        json.addProperty("battle", battleId);
        json.addProperty("bot", this.uuid.toString());
        json.addProperty("entity", this.entityId);
        json.addProperty("team", this.team);
        json.addProperty("version", this.version);
        json.addProperty("vehicle", this.vehicle);
        json.addProperty("role", this.role);
        json.addProperty("spawn_tick", this.spawnTick);
        json.addProperty("end_tick", this.endTick >= 0L ? this.endTick : now);
        json.addProperty("survival_seconds", BattleDecisionLog.round(this.survivalTicks(now) / 20.0));
        json.addProperty("destroyed", this.destroyed);
        json.addProperty("kills", this.kills);
        json.addProperty("deaths", this.destroyed ? 1 : 0);
        json.addProperty("damage_dealt", BattleDecisionLog.round(this.damageDealt));
        json.addProperty("damage_taken", BattleDecisionLog.round(this.damageTaken));
        json.addProperty("captures", this.captures);
        json.addProperty("recaptures", this.recaptures);
        json.addProperty("defenses", this.defenses);
        json.addProperty("reward", BattleDecisionLog.round(this.reward));
        json.addProperty("decisions", this.decisions);
        json.addProperty("victory", winner == null ? null : winner.equals(this.team));

        JsonObject counted = new JsonObject();

        for (var entry : this.actions.entrySet()) {
            counted.addProperty(entry.getKey().name(), entry.getValue());
        }

        json.add("actions", counted);

        return json;
    }
}
