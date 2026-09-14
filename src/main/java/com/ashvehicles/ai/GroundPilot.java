package com.ashvehicles.ai;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.ashvehicles.ai.battlefield.TacticalCell;
import com.ashvehicles.ai.battlefield.TacticalMap;
import com.ashvehicles.ai.battlefield.TeamIntel;
import com.ashvehicles.ai.combat.CombatController;
import com.ashvehicles.ai.combat.Resupply;
import com.ashvehicles.ai.control.DriveCommand;
import com.ashvehicles.ai.control.FireCommand;
import com.ashvehicles.ai.control.GroundVehicleController;
import com.ashvehicles.ai.core.AiDirector;
import com.ashvehicles.ai.core.AiProfiler;
import com.ashvehicles.ai.core.Cadence;
import com.ashvehicles.ai.debug.PilotSnapshot;
import com.ashvehicles.ai.decision.BattleState;
import com.ashvehicles.ai.decision.DecisionPolicy;
import com.ashvehicles.ai.decision.PolicyFactory;
import com.ashvehicles.ai.decision.TacticalAction;
import com.ashvehicles.ai.learning.AiVersion;
import com.ashvehicles.ai.learning.MapKnowledge;
import com.ashvehicles.ai.log.BattleEventType;
import com.ashvehicles.ai.log.BattleEvents;
import com.ashvehicles.ai.log.PilotRecorder;
import com.ashvehicles.ai.navigation.Navigator;
import com.ashvehicles.ai.navigation.RoutePlanner;
import com.ashvehicles.ai.objective.CaptureState;
import com.ashvehicles.ai.objective.ObjectiveState;
import com.ashvehicles.ai.perception.AllyObservation;
import com.ashvehicles.ai.perception.DamageMemory;
import com.ashvehicles.ai.perception.EnemyObservation;
import com.ashvehicles.ai.perception.Perception;
import com.ashvehicles.ai.role.Roles;
import com.ashvehicles.ai.role.TacticalProfile;
import com.ashvehicles.ai.role.VehicleRole;
import com.ashvehicles.ai.tactics.MovementIntent;
import com.ashvehicles.ai.tactics.Tactics;
import com.ashvehicles.ai.team.SupportCalls;
import com.ashvehicles.ai.team.TeamBrain;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.match.Bots;
import com.ashvehicles.match.MatchState;
import com.ashvehicles.match.MatchTeam;
import com.google.gson.JsonObject;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 地上車両1両の AI。<b>自分では何も判断しない</b>——各系を組み立て、毎 tick 決まった順に回すだけ。
 *
 * <pre>
 * 観測 (Perception)        4 Hz   名簿の敵味方・射線・脅威
 * 戦術格子 (TacticalMap)   周期   車両の目で見た地形・脅威・遮蔽
 * 判断 (DecisionPolicy)    1 Hz   ＋出来事で前倒し（拠点を失った・撃たれ始めた・目標を失った）
 * 戦術 (Tactics)           毎 tick 行動 → 行き先と動き方（遮蔽・側面・撤退の探索は周期と予算の内）
 * 射撃 (CombatController)  毎 tick 目標は 2 Hz、照準と引き金は毎 tick
 * 経路 (Navigator)         毎 tick 道は数秒ごと
 * 出口 (GroundVehicleController)  運転と引き金を入力1つに畳む
 * </pre>
 *
 * <p><b>判断・経路・射撃・車両操作は互いを知らない。</b> 方針は {@link BattleState} だけを読み、経路は
 * {@link MovementIntent} だけを読み、射撃は移動を知らず、車両に触るのは出口だけ。だから方針を学習した物へ
 * 差し替えても、経路と射撃と車両は1行も変わらない。
 *
 * <p>元の {@code GroundPilot}（走行・迷子の案内・射撃判定を1クラスで持っていた）の中身は、{@code navigation/Navigator}
 * と {@code combat/} へ理由ごと移してある。
 */
public final class GroundPilot extends BotPilot {
    /** 判断を前倒ししてよい最短の間隔（tick）。出来事が続けて起きても、毎 tick 考え直さない。 */
    private static final int REDECIDE = 5;

    /** 助けに行く味方を探す距離（ブロック）。 */
    private static final double SUPPORT_REACH = 150.0;

    /** 近くの味方と数える距離（ブロック）。 */
    private static final double NEAR = 60.0;

    /** 陣営の支援要請に応える役割（{@code team/SupportCalls}）。砲兵は前に出ず、防空は空を見ている。 */
    private static final Set<VehicleRole> ANSWERING = EnumSet.of(VehicleRole.TANK, VehicleRole.IFV, VehicleRole.APC,
            VehicleRole.SCOUT, VehicleRole.SUPPORT);

    /** 目標がこちらに正面を向けていると見なす角の余弦。 */
    private static final double FRONTAL = Math.cos(Math.toRadians(45.0));

    private final GroundVehicleEntity ground;
    private final AiVersion version;
    private final DecisionPolicy policy;
    private final VehicleRole role;
    private final TacticalProfile profile;
    private final GroundVehicleController control;
    private final DamageMemory damage = new DamageMemory();
    private final Perception perception;
    private final TacticalMap map = new TacticalMap();
    private final CombatController combat;
    private final Navigator navigator;
    private final Tactics tactics;
    private final PilotRecorder recorder;
    private final Cadence perceptionCadence;
    private final Cadence decisionCadence;
    private final Cadence mapCadence;

    private TacticalAction action = TacticalAction.ADVANCE;
    private boolean decided;
    private long actionSince;
    private int decidedAt = Integer.MIN_VALUE;
    private int boardRevision = -1;
    private int mapRevision = -1;
    private boolean wasUnderFire;
    private MovementIntent intent;

    @Nullable
    private BattleState state;

    @Nullable
    private ObjectiveState objective;

    GroundPilot(GroundVehicleEntity ground, String team, AiVersion version) {
        super(ground, team);
        this.ground = ground;
        this.version = version;
        this.policy = PolicyFactory.create(version);
        this.role = Roles.of(ground);
        this.profile = TacticalProfile.tuned(this.role, version.parameters());
        this.control = new GroundVehicleController(ground);
        this.perception = new Perception(ground, this.damage);
        this.combat = new CombatController(ground, this.control, this.profile, this.role, version.parameters());
        this.navigator = new Navigator(ground, team, RoutePlanner.Weights.of(version.parameters()),
                MapKnowledge.Weights.of(version.parameters()));
        this.tactics = new Tactics(ground, this.profile, version.parameters());
        this.recorder = new PilotRecorder(this);
        this.intent = MovementIntent.hold(TacticalAction.ADVANCE, null, CombatController.CLOSE_FIGHT);

        int id = ground.getId();
        AiConfig.Timing timing = AiConfig.timing();

        // 系ごとに違う素数で位相をずらす。同じ車両の観測と判断が同じ tick に重ならないように。
        this.perceptionCadence = new Cadence(id * 3, timing.perception());
        this.decisionCadence = new Cadence(id * 5, timing.decision());
        this.mapCadence = new Cadence(id * 11, timing.tactics());

        this.perception.setListener(this.recorder::enemyDetected);
        this.combat.setListener(this.recorder);
        this.navigator.setRouteListener(this.recorder::routeSelected);
    }

    @Override
    protected void think() {
        MinecraftServer server = this.ground.getServer();

        if (server == null) {
            return;
        }

        long now = this.ground.level().getGameTime();
        int tick = server.getTickCount();
        AiConfig.Timing timing = AiConfig.timing();
        TeamBrain brain = AiDirector.brain(this.team());
        TeamIntel intel = brain.intel();
        List<ObjectiveState> objectives = brain.board().states();

        long started = AiProfiler.start();

        if (this.perceptionCadence.tick(timing.perception())) {
            this.perception.observe(now, this.team(), intel, objectives);
        }

        AiProfiler.stop(AiProfiler.Section.PERCEPTION, started);

        started = AiProfiler.start();

        // 脅威マップが塗り直されたら、格子の覚えも捨てる。周期だけで捨てると、最大で1周期前の危険で道を引く。
        if (this.mapCadence.tick(timing.tactics()) || !this.map.ready()
                || intel.threats().revision() != this.mapRevision) {
            this.mapRevision = intel.threats().revision();
            this.map.refresh(this.ground.level(), this.ground.position(), intel, objectives,
                    this.perception.threatCentroid(), this.ground.getBbHeight(),
                    this.ground.getStats().suspension().climbHeight(), tick);
        }

        AiProfiler.stop(AiProfiler.Section.TACTICS, started);

        started = AiProfiler.start();

        boolean underFire = this.perception.underFire(now);
        boolean event = brain.board().revision() != this.boardRevision || (underFire && !this.wasUnderFire)
                || this.combat.consumeLostTarget();
        boolean changed = false;

        this.wasUnderFire = underFire;

        if (this.decisionCadence.tick(timing.decision()) || !this.decided
                || (event && tick - this.decidedAt >= REDECIDE)) {
            this.boardRevision = brain.board().revision();
            this.objective = brain.board().assignmentOrBest(this, intel);

            BattleState built = this.buildState(server, now, intel, objectives);
            TacticalAction next = this.policy.decide(built);
            TacticalAction previous = this.decided ? this.action : null;

            changed = next != this.action || !this.decided;
            this.decidedAt = tick;
            this.decided = true;
            this.state = built;
            this.callForSupport(brain, built, now);
            this.recorder.decided(built, previous, next, this.policy.lastUtilities(), this.objective,
                    this.navigator.status(), now);

            if (changed) {
                this.action = next;
                this.actionSince = now;
                this.navigator.forceReplan();
            }
        }

        AiProfiler.stop(AiProfiler.Section.DECISION, started);

        started = AiProfiler.start();

        if (this.state != null) {
            this.intent = this.tactics.resolve(this.action, this.state, intel, this.bases(server), changed);
        }

        AiProfiler.stop(AiProfiler.Section.TACTICS, started);

        started = AiProfiler.start();

        FireCommand fire = this.combat.tick(now, tick, this.perception, this.intent, intel);

        AiProfiler.stop(AiProfiler.Section.COMBAT, started);

        started = AiProfiler.start();

        DriveCommand drive = this.navigator.tick(this.intent, this.combat.targetEntity(),
                this.combat.wantsToHold(this.intent), this.map);

        AiProfiler.stop(AiProfiler.Section.NAVIGATION, started);

        this.control.apply(drive, fire);
        this.recorder.tick(now, this.perception, this.map, this.action, this.navigator.waterDepth());
    }

    @Override
    protected void idle() {
        this.control.park();
    }

    /** 判断1回ぶんの戦場を、各系の結果から組み立てる。 */
    private BattleState buildState(MinecraftServer server, long now, TeamIntel intel,
            List<ObjectiveState> objectives) {
        Vec3 here = this.ground.position();
        EnemyObservation aim = this.combat.target();
        TacticalCell cell = this.map.ready() ? this.map.cellAt(here.x, here.z) : null;
        boolean frontal = aim != null && (aim.aimingAtMe() || facesMe(aim.target(), here));
        int onTarget = aim == null ? 0 : intel.engagedBy(aim.target().getId(), this.ground.getId(), now);
        AllyObservation trouble = null;
        double need = 0.0;

        for (AllyObservation ally : this.perception.allies()) {
            if (ally.distance() > SUPPORT_REACH || !ally.underFire()) {
                continue;
            }

            double urgency = ((1.0 - ally.healthFraction()) * 0.6 + (ally.attackerId() >= 0 ? 0.4 : 0.2))
                    * (1.0 - 0.5 * ally.distance() / SUPPORT_REACH);

            if (urgency > need) {
                need = urgency;
                trouble = ally;
            }
        }

        // 陣営の頭が支援に割り当てた要請（team/SupportCalls）。見かけた味方より先にする——数を合わせて割り当てた物だ。
        SupportCalls.Call call = AiDirector.brain(this.team()).support().answerFor(this.ground.getId(), now);
        boolean called = false;

        if (call != null) {
            Entity caller = this.ground.level().getEntity(call.caller());

            if (caller != null && caller.isAlive() && !(caller instanceof VehicleEntityBase wreck && wreck.isWrecked())) {
                int attacker = call.attackers().length > 0 ? call.attackers()[0] : -1;
                AllyObservation seen = null;

                for (AllyObservation ally : this.perception.allies()) {
                    if (ally.entity() == caller) {
                        seen = ally;
                        break;
                    }
                }

                trouble = seen != null && seen.attackerId() >= 0 ? seen
                        : AllyObservation.called(caller, caller.position().distanceTo(here),
                                caller instanceof VehicleEntityBase machine ? machine.getHealthFraction() : 1.0F,
                                attacker, now);
                need = Math.max(need, call.urgency());
                called = true;
            }
        }

        ObjectiveState duty = this.objective;
        double best = 0.0;

        for (ObjectiveState each : objectives) {
            best = Math.max(best, each.score());
        }

        double distance = duty == null ? 0.0 : duty.centre().subtract(here).horizontalDistance();
        double risk = this.navigator.route() != null ? this.navigator.routeRisk()
                : duty != null ? intel.threats().riskAlong(here, duty.centre(), 6) : 0.0;
        MatchState match = MatchState.of(server);
        double radius = Bots.arenaRadius(server, match);
        double arena = radius > 0.0 ? radius : 500.0;

        return new BattleState(now, this.ground.getId(), this.team(), this.version.id(), this.role, this.profile,
                here, this.ground.getHealthFraction(), Resupply.ammoFraction(this.ground),
                Bots.beyondArena(this.ground), this.perception.enemies(), this.perception.allies(),
                this.perception.threat(), intel.threats().exposure(here.x, here.z), this.perception.underFire(now),
                this.perception.engaging(), this.perception.alliesWithin(NEAR), intel.knownEnemies(now), aim,
                frontal, onTarget, aim != null && this.combat.ledger().ineffective(aim.target().getId(), now),
                trouble, need, called, objectives, duty, distance, duty != null && duty.contains(here),
                duty == null || best <= 0.0 ? 0.0 : Math.max(0.0, duty.score()) / best, risk,
                this.map.ready() ? this.map : null, cell, cell != null && cell.coverValue() >= 0.5,
                this.tactics.position() != null && this.tactics.position().protection() >= 0.5, this.action,
                now - this.actionSince, arena);
    }

    /**
     * 押されていれば陣営へ支援を要請し（出し直し）、押されていなければ取り下げる（{@code team/SupportCalls}、2026-09-14 の指示
     * 「人数不利だったら近くにいる味方に支援を要請」）。押されているとは、自分を撃てる敵の数が近くの味方と自分を足した数より多いか、
     * 自分の弾が効かない相手に撃たれていること。要請の主を撃っている敵は、陣営の航空機が狙う相手の点数にも入る（航空支援）。
     */
    private void callForSupport(TeamBrain brain, BattleState state, long now) {
        int id = this.ground.getId();
        int deficit = state.enemiesEngaging() - state.alliesNear() - 1;
        boolean helpless = state.underFire() && state.targetIneffective();

        if (deficit < 1 && !helpless) {
            brain.support().withdraw(id);

            return;
        }

        IntArrayList attackers = new IntArrayList();

        for (EnemyObservation enemy : state.enemies()) {
            if (enemy.alive() && (enemy.attackingMe() || (enemy.seenBy() && enemy.inTheirRange()))) {
                attackers.add(enemy.target().getId());
            }
        }

        int lacking = Math.max(deficit, 1);
        double urgency = SupportCalls.urgency(lacking, state.health(), helpless);

        if (brain.support().raise(new SupportCalls.Call(id, this.ground.position(), lacking, urgency,
                attackers.toIntArray(), now))) {
            JsonObject detail = new JsonObject();

            detail.addProperty("deficit", lacking);
            detail.addProperty("urgency", Math.round(urgency * 100.0) / 100.0);
            detail.addProperty("helpless", helpless);
            detail.addProperty("attackers", attackers.size());
            BattleEvents.pilotEvent(this, BattleEventType.SUPPORT_REQUESTED, detail);
        }
    }

    /** その相手の車体の正面が、こちらを向いているか。 */
    private static boolean facesMe(Entity target, Vec3 me) {
        Vec3 forward = Vec3.directionFromRotation(0.0F, target.getYRot());
        Vec3 towards = me.subtract(target.position());
        double length = Math.sqrt(towards.x * towards.x + towards.z * towards.z);

        return length > 1.0E-3 && (forward.x * towards.x + forward.z * towards.z) / length > FRONTAL;
    }

    /** 自陣の旗。撤退の候補。 */
    private List<Vec3> bases(MinecraftServer server) {
        MatchTeam team = MatchState.of(server).team(this.team());

        if (team == null || team.spawns().isEmpty()) {
            return List.of();
        }

        List<Vec3> bases = new ArrayList<>(team.spawns().size());

        for (BlockPos spawn : team.spawns()) {
            bases.add(Vec3.atCenterOf(spawn));
        }

        return bases;
    }

    // ------------------------------------------------------------------
    // 外から訊かれること
    // ------------------------------------------------------------------

    @Nullable
    @Override
    public Entity target() {
        return this.combat.targetEntity();
    }

    @Nullable
    @Override
    public TacticalAction action() {
        return this.decided ? this.action : null;
    }

    @Override
    public VehicleRole role() {
        return this.role;
    }

    @Override
    public TacticalProfile profile() {
        return this.profile;
    }

    @Override
    public String version() {
        return this.version.id();
    }

    @Override
    public boolean leads() {
        return this.navigator.leads();
    }

    /**
     * 陣営の支援要請に回せるか（{@code team/SupportCalls}）。回さないのは、応える役割でない物（砲兵・防空）、まだ判断していない物、
     * 下がっている物、自分が撃ち合っている物、取りかけか守りかけの拠点の円の中にいる物（出れば進みが消える）。
     */
    public boolean canAnswer(long now) {
        BattleState last = this.state;

        if (!ANSWERING.contains(this.role) || last == null || this.action == TacticalAction.RETREAT
                || this.perception.engaging() > 0 || this.perception.underFire(now)) {
            return false;
        }

        ObjectiveState duty = this.objective;

        return duty == null || !last.atObjective() || (duty.state() == CaptureState.FRIENDLY && !duty.beingTaken());
    }

    @Override
    public boolean underFire(long now) {
        return this.perception.underFire(now);
    }

    @Override
    public int attackerId(long now) {
        Entity attacker = this.damage.lastAttacker(this.ground.level(), now, AiConfig.sight().attackedWindowTicks());

        return attacker == null ? -1 : attacker.getId();
    }

    @Override
    public void onHurt(@Nullable Entity attacker, float amount, long now) {
        this.damage.record(attacker, amount, now);
    }

    @Override
    public void onDamageDealt(Entity victim, float amount, long now) {
        this.combat.dealt(victim, amount, now);
        this.recorder.damageDealt(victim, amount, now);
    }

    @Override
    public PilotRecorder recorder() {
        return this.recorder;
    }

    @Override
    public PilotSnapshot snapshot() {
        Map<TacticalAction, Double> utilities = Map.copyOf(this.policy.lastUtilities());

        return new PilotSnapshot(this.ground.getId(), this.ground.getVehicleId().getPath(), this.team(),
                this.version.id(), this.role, this.action(), this.objective == null ? null : this.objective.id(),
                this.combat.targetEntity(), this.perception.threat(), this.navigator.routeRisk(),
                this.ground.getHealthFraction(), this.ground.position(), this.intent.destination(),
                this.intent.position(), List.copyOf(this.navigator.remaining()), utilities);
    }
}
