package com.ashvehicles.ai.log;

import com.ashvehicles.ai.BotPilot;
import com.ashvehicles.ai.decision.TacticalAction;
import com.ashvehicles.entity.VehicleEntityBase;
import com.google.gson.JsonObject;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 航空機の AI の飛び方の1行（{@code battles/<戦闘>/flights.jsonl}）。{@link BattleEvents#tick} が書く。
 *
 * <p><b>判断の行だけでは空が見えない。</b> 地上の車両は判断が変わるたびと5秒ごとに位置が残る（{@link BattleDecisionLog}）が、
 * 航空機は方針を通らないので判断の行を書かない——そして5秒おきでは、1 tick に数ブロック進む機体の跡は数百ブロックの直線に
 * なり、航過も旋回も見えない。だから航空機だけ {@value #EVERY} tick おきに、位置・速さ・向き・何をしているか
 * （{@link BotPilot#describeFlight}）・狙っている相手・向かっている点を1行書く。<b>毎 tick は書かない。</b>
 *
 * <p>読むのは {@code tool/ai/viewer}（跡を地形の上に描き、段階で色を分け、時刻で再生する）。
 */
final class FlightLog {
    /** 書く間隔（tick）。 */
    static final int EVERY = 20;

    private FlightLog() {
    }

    static JsonObject row(String battleId, long now, BotPilot pilot) {
        VehicleEntityBase vehicle = pilot.vehicle();
        Vec3 motion = vehicle.getDeltaMovement();
        double horizontal = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        JsonObject json = new JsonObject();

        json.addProperty("battle", battleId);
        json.addProperty("t", now);
        json.addProperty("bot", vehicle.getStringUUID());
        json.addProperty("entity", vehicle.getId());
        json.addProperty("team", pilot.team());
        json.addProperty("version", pilot.version());
        json.addProperty("vehicle", LifeRecord.vehicleName(vehicle));
        json.addProperty("x", tenth(vehicle.getX()));
        json.addProperty("y", tenth(vehicle.getY()));
        json.addProperty("z", tenth(vehicle.getZ()));
        // 速さはブロック/tick（×72 で km/h）。向きは yRot と同じ取り方（南が0、西が90）で、止まっていれば機首の向き。
        json.addProperty("speed", BattleDecisionLog.round(motion.length()));
        json.addProperty("heading", horizontal < 1.0E-4 ? Math.round(vehicle.getYRot())
                : Math.round(Math.toDegrees(Math.atan2(-motion.x, motion.z))));
        json.addProperty("climb", BattleDecisionLog.round(motion.y));
        json.addProperty("health", BattleDecisionLog.round(vehicle.getHealthFraction()));

        TacticalAction action = pilot.action();

        json.addProperty("action", action == null ? null : action.name());

        Entity target = pilot.target();

        if (target != null) {
            json.addProperty("target", PilotRecorder.describe(target));
            json.addProperty("target_x", tenth(target.getX()));
            json.addProperty("target_y", tenth(target.getY()));
            json.addProperty("target_z", tenth(target.getZ()));
        }

        Vec3 steering = pilot.snapshot().destination();

        if (steering != null) {
            json.addProperty("to_x", tenth(steering.x));
            json.addProperty("to_y", tenth(steering.y));
            json.addProperty("to_z", tenth(steering.z));
        }

        pilot.describeFlight(json);

        return json;
    }

    private static double tenth(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
