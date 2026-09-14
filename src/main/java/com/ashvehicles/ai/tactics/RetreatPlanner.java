package com.ashvehicles.ai.tactics;

import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.ai.battlefield.HeightField;
import com.ashvehicles.ai.battlefield.TacticalCell;
import com.ashvehicles.ai.battlefield.TacticalMap;
import com.ashvehicles.ai.battlefield.ThreatMap;
import com.ashvehicles.ai.decision.ParameterSet;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 下がる先を探す。
 *
 * <p><b>単純な後退方向ではない。</b> 真後ろへ下がった先が開けた平地なら、撃たれながら遠ざかるだけだ。候補は
 * 「そこに行けば安全になる理由がある」場所に限る——自陣が握っている拠点、自陣の旗、味方の固まり、脅威から隠れ
 * られる遮蔽、周りより高い所。その中から、脅威から遠ざかる向きで、危険が小さく、味方が近く、遠すぎない物を選ぶ。
 *
 * <p><b>敵の方へは下がらない。</b> 脅威の重心へ向かう候補は、よほど近くない限り捨てる。
 */
public final class RetreatPlanner {
    /** 脅威へ向かう度合いがこれより強い候補は捨てる（向きの内積）。 */
    private static final double TOWARDS_ENEMY = -0.2;

    /** 敵の方でも、この距離の内なら選んでよい（ブロック）。すぐ隣の遮蔽は向きを問わない。 */
    private static final double NEAR_ENOUGH = 24.0;

    /** 高所を探す距離（ブロック）。 */
    private static final double HIGH_REACH = 40.0;

    /**
     * 探す条件。
     *
     * @param from           今の位置
     * @param threatCentroid 脅威の重心。無ければ向きは問わない
     * @param friendlyPoints 自陣が握っていて取られかけていない拠点の中心
     * @param bases          自陣の旗
     * @param allies         味方の位置
     * @param threats        隠れたい相手
     * @param vehicleHeight  車体の高さ
     * @param climb          登れる段差
     */
    public record Request(Vec3 from, @Nullable Vec3 threatCentroid, List<Vec3> friendlyPoints, List<Vec3> bases,
            List<Vec3> allies, List<CoverFinder.Threat> threats, double vehicleHeight, double climb) {
    }

    /** 点数の重み。版のパラメータ（{@code tactics.retreat.*}）から読む。 */
    public record Weights(double safety, double support, double away, double distance, double point, double base,
            double ally, double cover, double high) {
        public static Weights of(ParameterSet parameters) {
            return new Weights(
                    parameters.get("tactics.retreat.safety", 1.0),
                    parameters.get("tactics.retreat.support", 0.5),
                    parameters.get("tactics.retreat.away", 0.6),
                    parameters.get("tactics.retreat.distance", 0.4),
                    parameters.get("tactics.retreat.point", 0.25),
                    parameters.get("tactics.retreat.base", 0.15),
                    parameters.get("tactics.retreat.ally", 0.2),
                    parameters.get("tactics.retreat.cover", 0.3),
                    parameters.get("tactics.retreat.high", 0.15));
        }
    }

    private RetreatPlanner() {
    }

    @Nullable
    public static TacticalPosition find(Level level, TacticalMap map, ThreatMap threats, Request request,
            Weights weights, CoverFinder.Weights coverWeights, @Nullable Entity self) {
        HeightField field = HeightField.of(level);
        Vec3 away = awayFrom(request);
        Best best = new Best();

        for (Vec3 point : request.friendlyPoints()) {
            best.offer(request, threats, weights, point, weights.point(), 0.0);
        }

        for (Vec3 base : request.bases()) {
            best.offer(request, threats, weights, base, weights.base(), 0.0);
        }

        if (!request.allies().isEmpty()) {
            double x = 0.0;
            double y = 0.0;
            double z = 0.0;

            for (Vec3 ally : request.allies()) {
                x += ally.x;
                y += ally.y;
                z += ally.z;
            }

            int count = request.allies().size();

            best.offer(request, threats, weights, new Vec3(x / count, y / count, z / count), weights.ally(), 0.0);
        }

        if (!request.threats().isEmpty()) {
            Vec3 searchFrom = away == null ? request.from() : request.from().add(away.scale(32.0));
            TacticalPosition cover = CoverFinder.find(level, map, threats, new CoverFinder.Request(searchFrom,
                    request.threats(), null, null, 0.0, 40.0, request.vehicleHeight(), request.climb(), false),
                    coverWeights, self);

            if (cover != null) {
                best.offer(request, threats, weights, cover.pos(), weights.cover() * cover.protection(),
                        cover.protection());
            }
        }

        // 高所。脅威から遠ざかる側の半分だけ見る。
        if (away != null) {
            for (int at = -3; at <= 3; at++) {
                double angle = Math.atan2(away.z, away.x) + at * Math.PI / 8.0;
                double x = request.from().x + Math.cos(angle) * HIGH_REACH;
                double z = request.from().z + Math.sin(angle) * HIGH_REACH;
                TacticalCell cell = map.cellAt(x, z);

                if (cell.known() && cell.walkable() && cell.heightValue() >= 0.4) {
                    double ground = field.ground(x, z);

                    if (!Double.isNaN(ground)) {
                        best.offer(request, threats, weights, new Vec3(x, ground, z), weights.high(), 0.0);
                    }
                }
            }
        }

        return best.position;
    }

    @Nullable
    private static Vec3 awayFrom(Request request) {
        if (request.threatCentroid() == null) {
            return null;
        }

        Vec3 away = request.from().subtract(request.threatCentroid());
        Vec3 flat = new Vec3(away.x, 0.0, away.z);

        return flat.lengthSqr() < 1.0E-4 ? null : flat.normalize();
    }

    /** 一番良い候補を持ち回る。 */
    private static final class Best {
        @Nullable
        TacticalPosition position;
        double score = Double.NEGATIVE_INFINITY;

        void offer(Request request, ThreatMap threats, Weights weights, Vec3 candidate, double bonus,
                double protection) {
            Vec3 step = candidate.subtract(request.from());
            double distance = Math.sqrt(step.x * step.x + step.z * step.z);
            double away = 0.5;

            if (request.threatCentroid() != null && distance > 1.0E-3) {
                Vec3 fromThreat = request.from().subtract(request.threatCentroid());
                double length = Math.sqrt(fromThreat.x * fromThreat.x + fromThreat.z * fromThreat.z);

                away = length < 1.0E-3 ? 0.5 : (step.x * fromThreat.x + step.z * fromThreat.z) / (distance * length);
            }

            if (away < TOWARDS_ENEMY && distance > NEAR_ENOUGH) {
                return;
            }

            double safety = 1.0 - Mth.clamp(threats.danger(candidate.x, candidate.z)
                    + 0.5 * threats.exposure(candidate.x, candidate.z), 0.0, 1.0);
            int near = 0;

            for (Vec3 ally : request.allies()) {
                if (ally.distanceToSqr(candidate) <= 48.0 * 48.0) {
                    near++;
                }
            }

            double score = weights.safety() * safety + weights.support() * Math.min(near / 3.0, 1.0)
                    + weights.away() * away - weights.distance() * distance / 300.0 + bonus;

            if (score > this.score) {
                this.score = score;
                this.position = new TacticalPosition(candidate, TacticalPosition.Kind.RETREAT, score, protection,
                        0.0);
            }
        }
    }
}
