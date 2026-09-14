package com.ashvehicles.ai.objective;

import com.ashvehicles.ai.decision.ParameterSet;
import com.ashvehicles.ai.role.TacticalProfile;
import com.ashvehicles.ai.role.VehicleRole;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 持ち場の点数。
 *
 * <pre>
 * objectiveScore = captureValue + strategicValue + recaptureUrgency + allySupport - enemyThreat - routeRisk
 * </pre>
 *
 * <p><b>2段に分けてある。</b> 前の5項は陣営全体で同じ値（{@link #teamScore}）で、陣営の頭が周期ごとに1回だけ
 * 出す。最後の {@code routeRisk} と距離は車両ごとに違う（{@link #botScore}）。40両がそれぞれ全拠点の点数を
 * 毎回出し直す必要は無い。
 *
 * <p><b>固定の優先順で決めない。</b> 「奪還 &gt; 防衛 &gt; 制圧」はここでは重みの大小として現れるだけで、奪還の
 * 必要な拠点でも敵が群がっていれば点数は下がり、近くの中立の拠点が上に来ることがある。重みは全部版のパラメータ
 * （{@code objective.*}）で変えられる。
 */
public final class ObjectiveScoring {
    private ObjectiveScoring() {
    }

    /** 重み。{@link #of} が版のパラメータから読む。 */
    public record Weights(double enemyPoint, double neutralPoint, double contestedPoint, double friendlyPoint,
            double enemyBase, double strategic, double recapture, double recaptureRecencyTicks,
            double defendUrgency, double allySupport, double enemyThreat, double distance, double routeRisk,
            double sticky, double scoutFar, double artillery, double antiAirHold, double tankFight,
            double lightCapture, double tankCapture) {

        public static Weights of(ParameterSet parameters) {
            return new Weights(
                    parameters.get("objective.capture.enemy", 1.0),
                    parameters.get("objective.capture.neutral", 0.85),
                    parameters.get("objective.capture.contested", 0.95),
                    parameters.get("objective.capture.friendly", 0.2),
                    parameters.get("objective.capture.base", 0.6),
                    parameters.get("objective.strategic", 0.3),
                    parameters.get("objective.recapture", 1.2),
                    parameters.get("objective.recapture_recency_ticks", 1200.0),
                    parameters.get("objective.defend_urgency", 1.0),
                    parameters.get("objective.ally_support", 0.3),
                    parameters.get("objective.enemy_threat", 0.5),
                    parameters.get("objective.distance", 0.6),
                    parameters.get("objective.route_risk", 0.4),
                    parameters.get("objective.sticky", 0.25),
                    parameters.get("objective.role.scout_far", 0.25),
                    parameters.get("objective.role.artillery", -0.3),
                    parameters.get("objective.role.anti_air_hold", 0.2),
                    parameters.get("objective.role.tank_fight", 0.4),
                    parameters.get("objective.role.light_capture", 0.3),
                    parameters.get("objective.role.tank_capture", 0.3));
        }
    }

    /** 取る価値そのもの。状態で決まる。 */
    public static double captureValue(ObjectiveState state, Weights weights) {
        return switch (state.state()) {
            case ENEMY -> state.base() ? weights.enemyBase() : weights.enemyPoint();
            case NEUTRAL -> weights.neutralPoint();
            case CONTESTED -> weights.contestedPoint();
            case FRIENDLY -> weights.friendlyPoint();
        };
    }

    /**
     * 急ぐ理由。奪われた拠点（失ってからの時間が短いほど急ぐ）と、今取られかけている自陣の拠点（進んでいるほど
     * 急ぐ）。
     */
    public static double recaptureUrgency(ObjectiveState state, Weights weights, long now) {
        double urgency = 0.0;

        if (state.recaptureRequired()) {
            double recency = state.lostTick() == Long.MIN_VALUE ? 0.0
                    : Math.max(0.0, 1.0 - (now - state.lostTick()) / Math.max(weights.recaptureRecencyTicks(), 1.0));

            urgency += weights.recapture() * (1.0 + 0.5 * recency);
        }

        if (state.beingTaken()) {
            urgency += weights.defendUrgency() * (0.5 + state.progress());
        }

        return urgency;
    }

    /** 陣営としての点数。距離と経路の危険はまだ引かない。 */
    public static double teamScore(ObjectiveState state, Weights weights, long now) {
        double strategic = weights.strategic() * state.strategic();
        double support = weights.allySupport() * Math.min(state.alliesNear() / 3.0, 1.0);
        double threat = weights.enemyThreat() * Mth.clamp(state.threat() + state.enemiesNear() * 0.15, 0.0, 1.0);

        return captureValue(state, weights) + strategic + recaptureUrgency(state, weights, now) + support - threat;
    }

    /**
     * 車両1両にとっての点数。陣営の点数から、そこまでの距離と直線で行った場合の危険を引き、役割の癖を足す。
     *
     * @param routeRisk 直線で行った場合の危なさ（0〜1）。{@code battlefield/ThreatMap#riskAlong}
     * @param arena     会場の広さの目安（ブロック）。距離をこれで割って揃える
     */
    public static double botScore(ObjectiveState state, Vec3 from, double routeRisk, double arena,
            Weights weights, VehicleRole role, TacticalProfile profile) {
        double dx = state.centre().x - from.x;
        double dz = state.centre().z - from.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        double far = Mth.clamp(distance / Math.max(arena, 100.0), 0.0, 1.5);
        double distancePenalty = weights.distance() * far * (role == VehicleRole.SCOUT ? 0.5 : 1.0);
        // 役割の分担: 戦車と軽装甲はまだ取れていない拠点へ（戦車は敵のいる拠点ほど。2026-09-14 の指示「戦車とIFVは拠点を取りに
        // 行くように」）、偵察は遠くの取るべき拠点も、砲兵は下がる、防空は自陣に残る。
        double bias = switch (role) {
            case SCOUT -> state.wantsTaking() ? weights.scoutFar() * Math.min(far, 1.0) + weights.lightCapture() : 0.0;
            case IFV, APC -> state.wantsTaking() ? weights.lightCapture() : 0.0;
            case TANK -> state.wantsTaking() ? weights.tankCapture()
                    + weights.tankFight() * Mth.clamp(state.enemiesNear() / 3.0 + state.threat(), 0.0, 1.0) : 0.0;
            case ARTILLERY -> weights.artillery();
            case AA -> state.state() == CaptureState.FRIENDLY ? weights.antiAirHold() : 0.0;
            default -> 0.0;
        };

        return state.score() * (0.5 + 0.5 * profile.objectiveBias()) - distancePenalty
                - weights.routeRisk() * routeRisk + bias;
    }
}
