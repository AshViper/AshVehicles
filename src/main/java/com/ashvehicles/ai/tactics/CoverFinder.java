package com.ashvehicles.ai.tactics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.ai.battlefield.HeightField;
import com.ashvehicles.ai.battlefield.HeightfieldSight;
import com.ashvehicles.ai.battlefield.TacticalCell;
import com.ashvehicles.ai.battlefield.TacticalMap;
import com.ashvehicles.ai.battlefield.ThreatMap;
import com.ashvehicles.ai.core.AiBudget;
import com.ashvehicles.ai.decision.ParameterSet;
import com.ashvehicles.ai.perception.LineOfSight;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 遮蔽と射撃位置を探す。
 *
 * <pre>
 *           敵
 *            ↓
 *        ███████
 *  AI ──→ ███████
 *            ↑
 *        この裏の点
 * </pre>
 *
 * <p><b>欲しいのは「隠れられて、撃てる」点。</b> 車体は脅威から見えず（脅威の視点から車体の中ほどへの線が
 * 遮られる）、砲塔の高さからは目標が見える——戦車が稜線の裏で車体を隠して砲塔だけ出す形であり、建物の角から
 * 覗く形でもある。撃てなくても隠れられる点は遮蔽（{@link TacticalPosition.Kind#COVER}）、両方揃えば射撃位置
 * （{@link TacticalPosition.Kind#FIRING}）。
 *
 * <p><b>候補は輪の上に並べ、高さ地図で測る。</b> 百点近い候補の1点ずつに脅威の数だけ見通しを引くので、ブロックを
 * 歩く射線（{@code Level.clip}）では払えない——{@link HeightfieldSight} で見積もり、<b>決める1点だけを本物の
 * 射線で確かめる</b>。高さ地図は窓や柵の隙間を塞がっていると読むので、確かめて見えていたら次の候補へ移る。
 *
 * <p><b>知らない土地は候補にしない。</b> ロードされていない所は隠れられるか分からない——分からない場所へ
 * 「隠れに」行かせない。
 *
 * <p>1回の探索はサーバー全体の予算（{@link AiBudget.Kind#SEARCH}）の内で呼ぶこと。呼び手（{@link Tactics}）が
 * 数える。
 */
public final class CoverFinder {
    /** 探す輪の半径（ブロック）。 */
    private static final double[] RINGS = {8.0, 16.0, 24.0, 32.0, 40.0, 48.0, 56.0, 64.0};

    /** 輪の上の候補の間隔（ブロック）。 */
    private static final double SPACING = 8.0;

    /** これより隠れられない点は遮蔽ではない。 */
    private static final double LEAST_PROTECTION = 0.34;

    /** 本物の射線で確かめる候補の数。 */
    private static final int VERIFY = 2;

    /**
     * 脅威1つ。
     *
     * @param sight  脅威の視点
     * @param weight 重み（脅威の度合い）
     */
    public record Threat(Vec3 sight, double weight) {
    }

    /**
     * 探す条件。
     *
     * @param from          今の位置
     * @param threats       隠れたい相手
     * @param target        撃ちたい相手の視点。無ければ撃てるかは問わない
     * @param anchor        この円の中から探す（拠点を守るとき）。無ければ自分の周り
     * @param anchorRadius  その円の半径
     * @param searchRadius  自分の周りを探す半径
     * @param vehicleHeight 隠したい車体の高さ
     * @param climb         登れる段差。候補の足元がこれより急なら捨てる
     * @param wantFire      撃てることを重く見るか
     */
    public record Request(Vec3 from, List<Threat> threats, @Nullable Vec3 target, @Nullable Vec3 anchor,
            double anchorRadius, double searchRadius, double vehicleHeight, double climb, boolean wantFire) {
    }

    /** 点数の重み。版のパラメータ（{@code tactics.cover.*}）から読む。 */
    public record Weights(double protection, double fire, double danger, double exposure, double distance,
            double approach, double height) {
        public static Weights of(ParameterSet parameters) {
            return new Weights(
                    parameters.get("tactics.cover.protection", 1.2),
                    parameters.get("tactics.cover.fire", 0.8),
                    parameters.get("tactics.cover.danger", 0.6),
                    parameters.get("tactics.cover.exposure", 0.4),
                    parameters.get("tactics.cover.distance", 0.35),
                    parameters.get("tactics.cover.approach", 0.3),
                    parameters.get("tactics.cover.height", 0.2));
        }
    }

    private record Scored(Vec3 pos, Vec3 hull, double score, double protection, double fire) {
    }

    private CoverFinder() {
    }

    /** 一番良い遮蔽か射撃位置。隠れられる点が無ければ null。 */
    @Nullable
    public static TacticalPosition find(Level level, TacticalMap map, ThreatMap threats, Request request,
            Weights weights, @Nullable Entity self) {
        if (request.threats().isEmpty()) {
            return null;
        }

        HeightField field = HeightField.of(level);
        List<Scored> found = new ArrayList<>();

        if (request.anchor() != null) {
            Vec3 anchor = request.anchor();

            consider(found, field, map, threats, request, weights, anchor.x, anchor.z);

            for (double fraction = 0.25; fraction <= 1.0; fraction += 0.25) {
                ring(found, field, map, threats, request, weights, anchor, request.anchorRadius() * fraction);
            }
        } else {
            for (double radius : RINGS) {
                if (radius > request.searchRadius()) {
                    break;
                }

                ring(found, field, map, threats, request, weights, request.from(), radius);
            }
        }

        found.sort(Comparator.comparingDouble(Scored::score).reversed());

        MinecraftServer server = level.getServer();
        Threat strongest = request.threats().stream().max(Comparator.comparingDouble(Threat::weight)).orElse(null);

        for (int at = 0; at < found.size(); at++) {
            Scored candidate = found.get(at);

            // 高さ地図の見積もりを、上位の数点だけ本物の射線で確かめる。見えていたら、その点は遮蔽ではない。
            if (at < VERIFY && strongest != null && server != null && AiBudget.spend(server, AiBudget.Kind.RAY)
                    && LineOfSight.trace(level, strongest.sight(), candidate.hull(), self).clear()) {
                continue;
            }

            TacticalPosition.Kind kind = candidate.fire() >= 0.6 && request.wantFire()
                    ? TacticalPosition.Kind.FIRING : TacticalPosition.Kind.COVER;

            return new TacticalPosition(candidate.pos(), kind, candidate.score(), candidate.protection(),
                    candidate.fire());
        }

        return null;
    }

    private static void ring(List<Scored> found, HeightField field, TacticalMap map, ThreatMap threats,
            Request request, Weights weights, Vec3 centre, double radius) {
        int count = Math.max(6, Math.min(32, (int) Math.round(Math.PI * 2.0 * radius / SPACING)));

        for (int at = 0; at < count; at++) {
            double angle = Math.PI * 2.0 * at / count;

            consider(found, field, map, threats, request, weights, centre.x + Math.cos(angle) * radius,
                    centre.z + Math.sin(angle) * radius);
        }
    }

    private static void consider(List<Scored> found, HeightField field, TacticalMap map, ThreatMap threats,
            Request request, Weights weights, double x, double z) {
        double ground = field.ground(x, z);

        if (Double.isNaN(ground)) {
            return;
        }

        TacticalCell cell = map.cellAt(x, z);

        if (!cell.known() || !cell.walkable()) {
            return;
        }

        // 崖の縁と急な土手は捨てる。隠れた先で車体が滑り落ちては意味が無い。
        for (int corner = 0; corner < 4; corner++) {
            double around = field.ground(x + ((corner & 1) == 0 ? -2.0 : 2.0), z + ((corner & 2) == 0 ? -2.0 : 2.0));

            if (!Double.isNaN(around) && Math.abs(around - ground) > request.climb() + 0.5) {
                return;
            }
        }

        Vec3 hull = new Vec3(x, ground + request.vehicleHeight() * 0.45, z);
        Vec3 eye = new Vec3(x, ground + request.vehicleHeight() * 0.85, z);
        double protection = 0.0;
        double total = 0.0;

        for (Threat threat : request.threats()) {
            total += threat.weight();
            protection += switch (HeightfieldSight.trace(field, threat.sight(), hull)) {
                case BLOCKED -> threat.weight();
                case UNKNOWN -> threat.weight() * 0.3;
                case CLEAR -> 0.0;
            };
        }

        protection = total > 0.0 ? protection / total : 0.0;

        if (protection < LEAST_PROTECTION) {
            return;
        }

        double fire = request.target() == null ? 0.5 : switch (HeightfieldSight.trace(field, eye, request.target())) {
            case CLEAR -> 1.0;
            case UNKNOWN -> 0.3;
            case BLOCKED -> 0.0;
        };
        Vec3 pos = new Vec3(x, ground, z);
        double distance = Math.sqrt((x - request.from().x) * (x - request.from().x)
                + (z - request.from().z) * (z - request.from().z)) / Math.max(request.searchRadius(), 1.0);
        double score = weights.protection() * protection
                + (request.wantFire() ? weights.fire() : weights.fire() * 0.25) * fire
                - weights.danger() * threats.danger(x, z)
                - weights.exposure() * threats.exposure(x, z)
                - weights.distance() * distance
                - weights.approach() * threats.riskAlong(request.from(), pos, 4)
                + weights.height() * cell.heightValue();

        found.add(new Scored(pos, hull, score, protection, fire));
    }
}
