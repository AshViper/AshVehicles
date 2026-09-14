package com.ashvehicles.ai.tactics;

import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.ai.battlefield.HeightField;
import com.ashvehicles.ai.battlefield.HeightfieldSight;
import com.ashvehicles.ai.battlefield.TacticalCell;
import com.ashvehicles.ai.battlefield.TacticalMap;
import com.ashvehicles.ai.battlefield.ThreatMap;
import com.ashvehicles.ai.decision.ParameterSet;
import com.ashvehicles.ai.perception.LineOfSight;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 側面へ回る点を探す。
 *
 * <pre>
 *         敵 → 正面
 *          ↑
 *      ███████
 *  AI ──────┘
 *     ↘
 *       ─────→ 側面
 * </pre>
 *
 * <p><b>正面を避ける。</b> 装甲は正面が一番厚く、車体の向きに付いている——だから回る先は相手の車体の向きから
 * 測る。側面は正面より弱く、後ろは一番弱い（{@code weapon/Ricochet} は板の法線からの角で弾くかを決め、車体は
 * 撃つ側へ正面を向けて戦う物だ）。
 *
 * <p><b>回る途中も撃たれてはいけない。</b> 回る先への直線の上で、相手の正面の扇（±40度）を横切る所と、脅威マップ
 * の危険な所を嫌う。道そのものは経路探索（{@code navigation/RoutePlanner}）が危険を強く嫌う重みで引き直すので、
 * ここで決めるのは「どこへ回るか」だけ。
 *
 * <p>回った先から撃てない点は選ばない（高さ地図の見通し）。
 */
public final class FlankPlanner {
    /** 相手の正面から測った候補の角（度）。 */
    private static final double[] ANGLES = {70.0, -70.0, 100.0, -100.0, 130.0, -130.0, 160.0, -160.0, 180.0};

    /** 撃ち合う距離に対する候補の距離の倍率。 */
    private static final double[] DISTANCES = {0.8, 1.0, 1.25};

    /** 正面の扇の半角の余弦。 */
    private static final double FRONT_ARC = Math.cos(Math.toRadians(40.0));

    /**
     * 探す条件。
     *
     * @param from          今の位置
     * @param target        回り込む相手
     * @param facing        相手の車体の向き（水平の単位ベクトル）
     * @param range         撃ち合う距離
     * @param others        相手以外の脅威
     * @param vehicleHeight 自分の車体の高さ
     */
    public record Request(Vec3 from, Entity target, Vec3 facing, double range, List<CoverFinder.Threat> others,
            double vehicleHeight) {
    }

    /** 点数の重み。版のパラメータ（{@code tactics.flank.*}）から読む。 */
    public record Weights(double weakness, double fire, double protection, double approach, double travel,
            double danger) {
        public static Weights of(ParameterSet parameters) {
            return new Weights(
                    parameters.get("tactics.flank.weakness", 1.0),
                    parameters.get("tactics.flank.fire", 0.7),
                    parameters.get("tactics.flank.protection", 0.4),
                    parameters.get("tactics.flank.approach", 0.8),
                    parameters.get("tactics.flank.travel", 0.5),
                    parameters.get("tactics.flank.danger", 0.4));
        }
    }

    private FlankPlanner() {
    }

    /** 相手の車体の向き（水平）。車両は車体の向き、それ以外は視線。 */
    public static Vec3 facingOf(Entity target) {
        Vec3 forward = Vec3.directionFromRotation(0.0F, target.getYRot());

        return new Vec3(forward.x, 0.0, forward.z).normalize();
    }

    @Nullable
    public static TacticalPosition find(Level level, TacticalMap map, ThreatMap threats, Request request,
            Weights weights) {
        HeightField field = HeightField.of(level);
        Vec3 centre = request.target().position();
        Vec3 targetEye = LineOfSight.sightOf(request.target());
        Vec3 facing = request.facing();
        TacticalPosition best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (double angle : ANGLES) {
            double radians = Math.toRadians(angle);
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            double dirX = facing.x * cos - facing.z * sin;
            double dirZ = facing.x * sin + facing.z * cos;
            // 正面から45度までは弱点ではない。90度（真横）で 1/3、180度（真後ろ）で1。
            double weakness = Mth.clamp((Math.abs(angle) - 45.0) / 135.0, 0.0, 1.0);

            for (double distance : DISTANCES) {
                double x = centre.x + dirX * request.range() * distance;
                double z = centre.z + dirZ * request.range() * distance;
                double ground = field.ground(x, z);

                if (Double.isNaN(ground)) {
                    continue;
                }

                TacticalCell cell = map.cellAt(x, z);

                if (!cell.known() || !cell.walkable()) {
                    continue;
                }

                Vec3 pos = new Vec3(x, ground, z);
                Vec3 eye = new Vec3(x, ground + request.vehicleHeight() * 0.85, z);
                double fire = switch (HeightfieldSight.trace(field, eye, targetEye)) {
                    case CLEAR -> 1.0;
                    case UNKNOWN -> 0.3;
                    case BLOCKED -> 0.0;
                };

                if (fire <= 0.0) {
                    continue;
                }

                double protection = protection(field, request.others(), pos, request.vehicleHeight());
                double approach = approach(threats, request.from(), pos, centre, facing, request.range());
                double travel = pos.distanceTo(request.from()) / Math.max(request.range() * 3.0, 1.0);
                double score = weights.weakness() * weakness + weights.fire() * fire
                        + weights.protection() * protection - weights.approach() * approach
                        - weights.travel() * travel - weights.danger() * threats.danger(x, z);

                if (score > bestScore) {
                    bestScore = score;
                    best = new TacticalPosition(pos, TacticalPosition.Kind.FLANK, score, protection, fire);
                }
            }
        }

        return best;
    }

    /** 回る先への直線の危なさ。相手の正面の扇を横切る所と、脅威マップの危険。 */
    private static double approach(ThreatMap threats, Vec3 from, Vec3 to, Vec3 target, Vec3 facing, double range) {
        double total = 0.0;
        int samples = 6;

        for (int at = 1; at <= samples; at++) {
            double along = (double) at / samples;
            double x = Mth.lerp(along, from.x, to.x);
            double z = Mth.lerp(along, from.z, to.z);
            double dx = x - target.x;
            double dz = z - target.z;
            double length = Math.sqrt(dx * dx + dz * dz);
            double risk = threats.danger(x, z);

            if (length > 1.0E-3 && length < range * 1.5 && (dx * facing.x + dz * facing.z) / length > FRONT_ARC) {
                risk += 0.5;
            }

            total += Math.min(risk, 1.0);
        }

        return total / samples;
    }

    private static double protection(HeightField field, List<CoverFinder.Threat> others, Vec3 pos, double height) {
        if (others.isEmpty()) {
            return 0.0;
        }

        Vec3 hull = pos.add(0.0, height * 0.45, 0.0);
        double blocked = 0.0;
        double total = 0.0;

        for (CoverFinder.Threat threat : others) {
            total += threat.weight();

            if (HeightfieldSight.trace(field, threat.sight(), hull) == HeightfieldSight.Visibility.BLOCKED) {
                blocked += threat.weight();
            }
        }

        return total > 0.0 ? blocked / total : 0.0;
    }
}
