package com.ashvehicles.ai.tactics;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.ai.AiConfig;
import com.ashvehicles.ai.battlefield.TeamIntel;
import com.ashvehicles.ai.battlefield.ThreatMap;
import com.ashvehicles.ai.combat.CombatController;
import com.ashvehicles.ai.core.AiBudget;
import com.ashvehicles.ai.core.Cadence;
import com.ashvehicles.ai.decision.BattleState;
import com.ashvehicles.ai.decision.ParameterSet;
import com.ashvehicles.ai.decision.TacticalAction;
import com.ashvehicles.ai.objective.CaptureState;
import com.ashvehicles.ai.objective.ObjectiveState;
import com.ashvehicles.ai.perception.AllyObservation;
import com.ashvehicles.ai.perception.EnemyObservation;
import com.ashvehicles.ai.perception.LineOfSight;
import com.ashvehicles.ai.perception.WeaponReach;
import com.ashvehicles.ai.role.TacticalProfile;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.vehicle.GroundVehicleDefinition;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 行動（{@link TacticalAction}）を、移動の意図（{@link MovementIntent}）に直す。
 *
 * <p><b>行動の意味を解くのはここ1箇所。</b> 方針（{@code decision/}）は「側面へ回る」と決めるだけで、側面が
 * どこかは知らない。経路（{@code navigation/}）は「そこへ、危険を強く嫌って」と言われるだけで、なぜかは知らない。
 * 真ん中で「側面とは相手の車体の向きから100度の、撃てて隠れられる点」と解くのがこのクラスの仕事。
 *
 * <p><b>探索は高いので、行動が変わったときと周期（{@code timing.tactics}）でだけ行う。</b> 1回の探索はサーバー
 * 全体の予算（{@link AiBudget.Kind#SEARCH}）の内で、断られたら前の点を使い、次の tick にまた頼む。
 */
public final class Tactics {
    /** 持ち場の散らし方（ブロック）。拠点の半径（既定16）より内側に収まる値。 */
    private static final double SPREAD_LEAST = 4.0;
    private static final double SPREAD_STEP = 3.0;

    /** 相手がこれだけ動いたら、側面の点を探し直す（ブロック）。 */
    private static final double RETARGET_MOVE = 24.0;

    /** 探す物が無いときに下がる距離（ブロック）。 */
    private static final double AWAY = 32.0;

    /** 撃ち合う距離の下限と上限（ブロック）。 */
    private static final double LEAST_RANGE = 40.0;
    private static final double MOST_RANGE = 140.0;

    /** 拠点へ向かう途中で、足を止めて撃ち合う距離の上限（ブロック）。 */
    private static final double CAPTURE_FIGHT = 40.0;

    /**
     * 拠点の円のこれだけ内側に入ったら、その場に居座る（ブロック）。小さな円では半径の {@value #ANCHOR_IN_SHARE} まで。
     *
     * <p><b>持ち場の点（{@link #spotIn}）まで行かせない。</b> 着いたと見なす距離（{@link MovementIntent#ARRIVED}）は
     * 車両の旋回半径より小さく、横に外れた点を目指した戦車はその点の周りを回り続けた。制圧は円の中にいる時間で
     * 進むので、円に入った時点で仕事は済んでいる。
     */
    private static final double ANCHOR_IN = 4.0;
    private static final double ANCHOR_IN_SHARE = 0.35;

    /** 居座った車両が円を出たと見なす、縁からの距離（ブロック）。入るより緩くして、縁で出入りを繰り返させない。 */
    private static final double ANCHOR_OUT = 1.0;

    /** 円へ入る手前で落とす速さの下限（スロットル）。 */
    private static final float APPROACH_LEAST = 0.3F;

    /** 遮蔽を探す半径（ブロック）。 */
    private static final double COVER_REACH = 48.0;

    /** 脅威として数える、脅威の下限と数の上限。 */
    private static final double THREAT_FLOOR = 0.05;
    private static final int MOST_THREATS = 4;

    private final GroundVehicleEntity vehicle;
    private final TacticalProfile profile;
    private final CoverFinder.Weights coverWeights;
    private final FlankPlanner.Weights flankWeights;
    private final RetreatPlanner.Weights retreatWeights;
    private final Cadence cadence;

    /**
     * この車両だけの持ち場のずれ。
     *
     * <p><b>5両が同じ1ブロックを目指すと、着いた順に押し合う。</b> 旗の上で団子になった戦車は的でしか
     * ないので、目的地を個体ごとに散らす。エンティティ ID から決めるので、同じ車両はいつも同じ持ち場へ行く。
     */
    private final Vec3 spread;

    @Nullable
    private TacticalPosition position;

    @Nullable
    private TacticalAction positionFor;

    private Vec3 positionTargetAt = Vec3.ZERO;

    /** 居座っている拠点の名前。居座っていなければ null。 */
    @Nullable
    private String anchoredAt;

    private MovementIntent intent;

    public Tactics(GroundVehicleEntity vehicle, TacticalProfile profile, ParameterSet parameters) {
        this.vehicle = vehicle;
        this.profile = profile;
        this.coverWeights = CoverFinder.Weights.of(parameters);
        this.flankWeights = FlankPlanner.Weights.of(parameters);
        this.retreatWeights = RetreatPlanner.Weights.of(parameters);
        this.cadence = new Cadence(vehicle.getId() * 17, AiConfig.timing().tactics());

        // 黄金角で回す。連番の ID が同じ方向に並ばない配り方。
        double angle = vehicle.getId() * 2.39996;
        double radius = SPREAD_LEAST + Math.floorMod(vehicle.getId(), 3) * SPREAD_STEP;

        this.spread = new Vec3(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius);
        this.intent = MovementIntent.hold(TacticalAction.ADVANCE, null, CombatController.CLOSE_FIGHT);
    }

    /** 最後に探した点。 */
    @Nullable
    public TacticalPosition position() {
        return this.position;
    }

    public MovementIntent intent() {
        return this.intent;
    }

    /**
     * 今の行動を移動の意図にする。毎 tick 呼ぶ——周期の数えと、動く目標への追従のため。
     *
     * @param bases   自陣の旗
     * @param changed この tick に行動が変わった
     */
    public MovementIntent resolve(TacticalAction action, BattleState state, TeamIntel intel, List<Vec3> bases,
            boolean changed) {
        boolean due = this.cadence.tick(AiConfig.timing().tactics());

        if (changed || this.positionFor != action) {
            this.position = null;
            this.positionFor = action;
            due = true;
        }

        this.intent = switch (action) {
            case CAPTURE_OBJECTIVE, RECAPTURE_OBJECTIVE -> this.capture(action, state);
            case DEFEND_OBJECTIVE -> this.defend(state, intel, due);
            case ATTACK -> this.attack(state);
            case FLANK -> this.flank(state, intel, due);
            case SEEK_COVER -> this.cover(state, intel, due);
            case RETREAT -> this.retreat(state, intel, bases, due);
            case ADVANCE -> this.advance(action, state, intel);
            case SEARCH_ENEMY -> this.search(state, intel);
            case SUPPORT_ALLY -> this.support(state);
        };

        return this.intent;
    }

    // ------------------------------------------------------------------
    // 行動ごとの解き方
    // ------------------------------------------------------------------

    /**
     * 拠点の円の中の自分の持ち場へ行き、居座る。すぐそばまで詰めてきた敵とだけ止まって撃ち合う。
     *
     * <p>足を止める距離は他の行動より短い（{@value #CAPTURE_FIGHT}）。拠点へ向かう車両の仕事は着くことで、
     * 遠くの敵には走りながら砲を向ける。撃たれて本当に撃ち合うべきなら、方針が攻撃へ切り替える。
     */
    private MovementIntent capture(TacticalAction action, BattleState state) {
        ObjectiveState duty = state.objective();

        if (duty == null) {
            return this.advance(action, state, null);
        }

        if (this.anchorsIn(duty)) {
            EnemyObservation aim = ground(state.target());

            return MovementIntent.anchor(action, aim == null ? null : aim.target().position(), CAPTURE_FIGHT);
        }

        return MovementIntent.move(action, this.spotIn(duty), 1.0, CAPTURE_FIGHT).withThrottle(this.approach(duty));
    }

    /** 拠点の円の中で、脅威から隠れて撃てる点に着く。見付からなければ自分の持ち場。 */
    private MovementIntent defend(BattleState state, TeamIntel intel, boolean due) {
        ObjectiveState duty = state.objective();

        if (duty == null) {
            return this.advance(TacticalAction.DEFEND_OBJECTIVE, state, intel);
        }

        List<CoverFinder.Threat> threats = threats(state);
        EnemyObservation aim = ground(state.target());

        if (due && !threats.isEmpty() && state.tacticalMap() != null && this.searchAllowed()) {
            this.position = CoverFinder.find(this.vehicle.level(), state.tacticalMap(), intel.threats(),
                    new CoverFinder.Request(this.vehicle.position(), threats,
                            aim == null ? null : LineOfSight.sightOf(aim.target()), duty.centre(),
                            duty.radius() * 0.8, COVER_REACH, this.vehicle.getBbHeight(), this.climb(), true),
                    this.coverWeights, this.vehicle);
        }

        Vec3 face = aim != null ? aim.target().position() : centroid(threats);

        // 隠れる場所が見付からなければ、円の中にいる限り動かない。持ち場の点を目指すと、制圧のときと同じく点の
        // 周りを回り続ける。
        if (this.position == null && this.anchorsIn(duty)) {
            return MovementIntent.anchor(TacticalAction.DEFEND_OBJECTIVE, face, 120.0);
        }

        Vec3 destination = this.position != null ? this.position.pos() : this.spotIn(duty);

        return MovementIntent.move(TacticalAction.DEFEND_OBJECTIVE, destination, 1.0, 120.0)
                .withPosition(this.position).withFace(face);
    }

    /**
     * 目標を撃つ。撃ち合う距離（役割の目盛り×自分の射程）まで詰め、そこで止まって撃つ。
     */
    private MovementIntent attack(BattleState state) {
        EnemyObservation aim = ground(state.target());

        if (aim == null) {
            return this.fallback(state);
        }

        Entity target = aim.target();
        double desired = this.engagementRange();
        double distance = target.position().distanceTo(this.vehicle.position());

        if (distance <= desired * 1.15 && aim.visible()) {
            return MovementIntent.hold(TacticalAction.ATTACK, target.position(), desired * 1.3).withTarget(target);
        }

        Vec3 back = this.vehicle.position().subtract(target.position());
        Vec3 flat = new Vec3(back.x, 0.0, back.z);
        Vec3 destination = flat.lengthSqr() < 1.0E-4 ? target.position()
                : target.position().add(flat.normalize().scale(desired));

        return MovementIntent.move(TacticalAction.ATTACK, destination, 1.1, desired).withTarget(target)
                .withFace(target.position());
    }

    /**
     * 車体を動かすときに相手にする敵。空の相手は砲塔が撃つだけで、追う・回り込む・隠れる・向く相手にはしない
     * （{@code decision/RuleBasedPolicy} が空の相手で攻撃を選ばないのと同じ理由）。
     */
    @Nullable
    private static EnemyObservation ground(@Nullable EnemyObservation aim) {
        return aim == null || aim.flying() ? null : aim;
    }

    /** 相手の正面を避けて、側面か後ろの撃てる点へ回る。回っている間は止まらない。 */
    private MovementIntent flank(BattleState state, TeamIntel intel, boolean due) {
        EnemyObservation aim = ground(state.target());

        if (aim == null || state.tacticalMap() == null) {
            return this.attack(state);
        }

        Entity target = aim.target();

        if ((due || target.position().distanceTo(this.positionTargetAt) > RETARGET_MOVE || this.position == null)
                && this.searchAllowed()) {
            List<CoverFinder.Threat> others = new ArrayList<>();

            for (CoverFinder.Threat threat : threats(state)) {
                if (threat.sight().distanceToSqr(LineOfSight.sightOf(target)) > 4.0) {
                    others.add(threat);
                }
            }

            this.position = FlankPlanner.find(this.vehicle.level(), state.tacticalMap(), intel.threats(),
                    new FlankPlanner.Request(this.vehicle.position(), target, FlankPlanner.facingOf(target),
                            this.engagementRange(), others, this.vehicle.getBbHeight()),
                    this.flankWeights);
            this.positionTargetAt = target.position();
        }

        if (this.position == null) {
            return this.attack(state);
        }

        return new MovementIntent(TacticalAction.FLANK, MovementIntent.Mode.MOVE, this.position.pos(),
                MovementIntent.ARRIVED, 1.1F, 1.6, 0.0, target.position(), target, this.position);
    }

    /** 脅威から隠れられる点へ入る。撃てるならそこから撃つ。入る途中では止まらない。 */
    private MovementIntent cover(BattleState state, TeamIntel intel, boolean due) {
        List<CoverFinder.Threat> threats = threats(state);
        EnemyObservation aim = ground(state.target());

        if (due && !threats.isEmpty() && state.tacticalMap() != null && this.searchAllowed()) {
            this.position = CoverFinder.find(this.vehicle.level(), state.tacticalMap(), intel.threats(),
                    new CoverFinder.Request(this.vehicle.position(), threats,
                            aim == null ? null : LineOfSight.sightOf(aim.target()), null, 0.0, COVER_REACH,
                            this.vehicle.getBbHeight(), this.climb(), this.profile.aggression() >= 0.4),
                    this.coverWeights, this.vehicle);
        }

        Vec3 face = aim != null ? aim.target().position() : centroid(threats);
        Vec3 destination = this.position != null ? this.position.pos() : this.awayPoint(threats);

        return new MovementIntent(TacticalAction.SEEK_COVER, MovementIntent.Mode.MOVE, destination,
                MovementIntent.ARRIVED, 1.1F, 1.5, 0.0, face, null, this.position);
    }

    /** 安全な場所へ下がる。撃ちながら、止まらずに、危険を一番強く嫌って。 */
    private MovementIntent retreat(BattleState state, TeamIntel intel, List<Vec3> bases, boolean due) {
        List<CoverFinder.Threat> threats = threats(state);

        if (due && state.tacticalMap() != null && this.searchAllowed()) {
            List<Vec3> points = new ArrayList<>();

            for (ObjectiveState objective : state.objectives()) {
                if (objective.state() == CaptureState.FRIENDLY && !objective.beingTaken() && !objective.base()) {
                    points.add(objective.centre());
                }
            }

            List<Vec3> allies = new ArrayList<>();

            for (AllyObservation ally : state.allies()) {
                if (ally.distance() <= 200.0 && !ally.underFire()) {
                    allies.add(ally.entity().position());
                }
            }

            this.position = RetreatPlanner.find(this.vehicle.level(), state.tacticalMap(), intel.threats(),
                    new RetreatPlanner.Request(this.vehicle.position(), centroid(threats), points, bases, allies,
                            threats, this.vehicle.getBbHeight(), this.climb()),
                    this.retreatWeights, this.coverWeights, this.vehicle);
        }

        Vec3 destination = this.position != null ? this.position.pos() : this.awayPoint(threats);

        return new MovementIntent(TacticalAction.RETREAT, MovementIntent.Mode.MOVE, destination, 8.0, 1.1F, 2.0,
                0.0, centroid(threats), null, this.position);
    }

    /**
     * 前へ出る。持ち場（無ければ敵陣の旗）へ向かう線の上を、危険が許せる所まで。
     *
     * @param intel 無ければ危険の縁を見ずに持ち場まで
     */
    private MovementIntent advance(TacticalAction action, BattleState state, @Nullable TeamIntel intel) {
        Vec3 here = this.vehicle.position();
        Vec3 goal = this.front(state);

        if (intel != null) {
            goal = frontier(intel.threats(), here, goal, 0.25 + 0.5 * this.profile.riskTolerance());
        }

        return MovementIntent.move(action, goal, 1.2, CombatController.CLOSE_FIGHT);
    }

    /** 見失った敵を探す。一番最近見られて、今は誰も見ていない敵の最後の位置へ。 */
    private MovementIntent search(BattleState state, TeamIntel intel) {
        Vec3 here = this.vehicle.position();
        TeamIntel.Spotted best = null;

        for (TeamIntel.Spotted spotted : intel.spotted()) {
            if (spotted.current(state.gameTime()) || spotted.flying()) {
                continue;
            }

            if (best == null || spotted.seenTick() > best.seenTick()
                    || (spotted.seenTick() == best.seenTick()
                            && spotted.position().distanceToSqr(here) < best.position().distanceToSqr(here))) {
                best = spotted;
            }
        }

        if (best != null && best.position().distanceToSqr(here) > MovementIntent.ARRIVED * MovementIntent.ARRIVED) {
            return MovementIntent.move(TacticalAction.SEARCH_ENEMY, best.position(), 1.0,
                    CombatController.CLOSE_FIGHT);
        }

        return this.advance(TacticalAction.SEARCH_ENEMY, state, intel);
    }

    /** 撃たれている味方のそばへ行き、撃っている相手を撃つ。味方の前に出ず、相手との間の脇へ。 */
    private MovementIntent support(BattleState state) {
        AllyObservation ally = state.allyInTrouble();

        if (ally == null) {
            return this.fallback(state);
        }

        Vec3 at = ally.entity().position();
        Entity attacker = ally.attackerId() >= 0 ? this.vehicle.level().getEntity(ally.attackerId()) : null;
        Vec3 destination = at;

        if (attacker != null) {
            Vec3 towards = attacker.position().subtract(at);
            Vec3 flat = new Vec3(towards.x, 0.0, towards.z);

            if (flat.lengthSqr() > 1.0E-4) {
                Vec3 unit = flat.normalize();
                Vec3 side = new Vec3(-unit.z, 0.0, unit.x).scale(Math.floorMod(this.vehicle.getId(), 2) == 0 ? 8.0 : -8.0);

                destination = at.add(unit.scale(12.0)).add(side);
            }
        }

        return MovementIntent.move(TacticalAction.SUPPORT_ALLY, destination, 1.1, CombatController.CLOSE_FIGHT)
                .withTarget(attacker);
    }

    /** 行動の前提（目標・持ち場）が崩れたときの受け皿。持ち場があればそこへ、無ければ前へ。 */
    private MovementIntent fallback(BattleState state) {
        ObjectiveState duty = state.objective();

        return duty != null ? this.capture(TacticalAction.CAPTURE_OBJECTIVE, state)
                : this.advance(TacticalAction.ADVANCE, state, null);
    }

    // ------------------------------------------------------------------
    // 補助
    // ------------------------------------------------------------------

    /** 探索を1回使ってよいか。断られたら次の tick にまた頼む。 */
    private boolean searchAllowed() {
        MinecraftServer server = this.vehicle.getServer();

        if (server != null && AiBudget.spend(server, AiBudget.Kind.SEARCH)) {
            return true;
        }

        this.cadence.force();

        return false;
    }

    /** 拠点の円の中の自分の持ち場。散らしが円の6割を超えないように縮める。 */
    private Vec3 spotIn(ObjectiveState duty) {
        double limit = duty.radius() * 0.6;
        double length = this.spread.length();
        Vec3 offset = length > limit && length > 1.0E-6 ? this.spread.scale(limit / length) : this.spread;

        return duty.centre().add(offset);
    }

    /** その拠点の円の中に居座るか。答えを覚えて、次の問いの物差しを決める。 */
    private boolean anchorsIn(ObjectiveState duty) {
        Vec3 here = this.vehicle.position();
        double dx = here.x - duty.centre().x;
        double dz = here.z - duty.centre().z;
        boolean anchored = anchors(Math.sqrt(dx * dx + dz * dz), duty.radius(), duty.id().equals(this.anchoredAt));

        this.anchoredAt = anchored ? duty.id() : null;

        return anchored;
    }

    /**
     * 円の中心から {@code distance} にいる車両が居座るか。入るときは縁から {@value #ANCHOR_IN}（小さな円では半径の
     * {@value #ANCHOR_IN_SHARE}）内側、居座った後は縁から {@value #ANCHOR_OUT} まで許す。
     *
     * @param anchored 前の問いで居座っていたか
     */
    public static boolean anchors(double distance, double radius, boolean anchored) {
        double margin = anchored ? ANCHOR_OUT : Math.min(ANCHOR_IN, radius * ANCHOR_IN_SHARE);

        return distance <= radius - margin;
    }

    /**
     * 円へ向かう車両のスロットルの上限。止まるまでに走る距離が、円に入ってから半径ぶんに収まる速さ。
     *
     * <p>居座ると決めた瞬間にブレーキを掛けても、速い車両は円の反対側まで滑って出る。止まるまでの距離は離散の
     * {@code v²/2a + v/2}（[[turret-slew-is-a-braking-problem]] と同じ式）なので、許せる距離から逆に解く。既定の
     * 半径16では全速でも収まり、効くのは小さな円だけ。
     */
    private float approach(ObjectiveState duty) {
        GroundVehicleDefinition.Powertrain powertrain = this.vehicle.getStats().powertrain();

        if (powertrain.maxSpeed() <= 0.0F || powertrain.braking() <= 0.0F) {
            return 1.0F;
        }

        Vec3 here = this.vehicle.position();
        double dx = here.x - duty.centre().x;
        double dz = here.z - duty.centre().z;
        double edge = duty.radius() - Math.min(ANCHOR_IN, duty.radius() * ANCHOR_IN_SHARE);
        double room = duty.radius() + Math.max(0.0, Math.sqrt(dx * dx + dz * dz) - edge);

        return Mth.clamp((float) (stoppingSpeed(room, powertrain.braking()) / powertrain.maxSpeed()),
                APPROACH_LEAST, 1.0F);
    }

    /** 毎 tick {@code braking} ずつ減速して、{@code room} ブロックで止まれる速さ（ブロック/tick）。 */
    public static double stoppingSpeed(double room, double braking) {
        return (-braking + Math.sqrt(braking * braking + 8.0 * braking * Math.max(room, 0.0))) / 2.0;
    }

    /** 前線の方角にある行き先。持ち場、敵か中立の一番近い持ち場、それも無ければ前方。 */
    private Vec3 front(BattleState state) {
        if (state.objective() != null) {
            return this.spotIn(state.objective());
        }

        Vec3 here = this.vehicle.position();
        ObjectiveState nearest = null;

        for (ObjectiveState objective : state.objectives()) {
            if (objective.wantsTaking() && (nearest == null
                    || objective.centre().distanceToSqr(here) < nearest.centre().distanceToSqr(here))) {
                nearest = objective;
            }
        }

        if (nearest != null) {
            return this.spotIn(nearest);
        }

        return here.add(Vec3.directionFromRotation(0.0F, this.vehicle.getYRot()).scale(48.0));
    }

    /** 自分の撃ち合う距離。役割の目盛り×射程を、視界と下限・上限で切った物。 */
    private double engagementRange() {
        double effective = Math.min(WeaponReach.of(this.vehicle).maxRange(), AiConfig.sight().range());

        return Mth.clamp(effective * this.profile.preferredRange(), LEAST_RANGE, MOST_RANGE);
    }

    private double climb() {
        return this.vehicle.getStats().suspension().climbHeight();
    }

    /** 脅威から真っ直ぐ遠ざかった点。脅威が分からなければ真後ろ。 */
    private Vec3 awayPoint(List<CoverFinder.Threat> threats) {
        Vec3 here = this.vehicle.position();
        Vec3 from = centroid(threats);
        Vec3 away = from == null ? Vec3.directionFromRotation(0.0F, this.vehicle.getYRot() + 180.0F)
                : here.subtract(from);
        Vec3 flat = new Vec3(away.x, 0.0, away.z);

        return flat.lengthSqr() < 1.0E-4 ? here : here.add(flat.normalize().scale(AWAY));
    }

    /** 隠れたい相手。脅威のある敵を強い順に数件。見えていない敵は最後に知っている位置で。 */
    private static List<CoverFinder.Threat> threats(BattleState state) {
        List<EnemyObservation> ranked = new ArrayList<>();

        for (EnemyObservation enemy : state.enemies()) {
            if (enemy.alive() && enemy.threatLevel() > THREAT_FLOOR) {
                ranked.add(enemy);
            }
        }

        ranked.sort((left, right) -> Double.compare(right.threatLevel(), left.threatLevel()));

        List<CoverFinder.Threat> threats = new ArrayList<>(Math.min(ranked.size(), MOST_THREATS));

        for (int at = 0; at < Math.min(ranked.size(), MOST_THREATS); at++) {
            EnemyObservation enemy = ranked.get(at);
            Vec3 sight = enemy.visible() ? LineOfSight.sightOf(enemy.target())
                    : enemy.lastKnownPosition().add(0.0, enemy.target().getBbHeight() * 0.75, 0.0);

            threats.add(new CoverFinder.Threat(sight, enemy.threatLevel()));
        }

        return threats;
    }

    @Nullable
    private static Vec3 centroid(List<CoverFinder.Threat> threats) {
        if (threats.isEmpty()) {
            return null;
        }

        double x = 0.0;
        double y = 0.0;
        double z = 0.0;
        double weight = 0.0;

        for (CoverFinder.Threat threat : threats) {
            x += threat.sight().x * threat.weight();
            y += threat.sight().y * threat.weight();
            z += threat.sight().z * threat.weight();
            weight += threat.weight();
        }

        return weight <= 0.0 ? null : new Vec3(x / weight, y / weight, z / weight);
    }

    /** 線の上を16ブロックずつ進み、危険が限度を超える手前で止まる。24ブロックより手前では止まらない。 */
    private static Vec3 frontier(ThreatMap threats, Vec3 from, Vec3 goal, double limit) {
        Vec3 step = goal.subtract(from);
        double length = Math.sqrt(step.x * step.x + step.z * step.z);

        if (length < 24.0) {
            return goal;
        }

        Vec3 unit = new Vec3(step.x / length, 0.0, step.z / length);
        Vec3 last = from;

        for (double along = 16.0; along < length; along += 16.0) {
            Vec3 sample = from.add(unit.scale(along));

            if (threats.danger(sample.x, sample.z) > limit) {
                return along <= 24.0 ? from.add(unit.scale(24.0)) : last;
            }

            last = sample;
        }

        return goal;
    }
}
