package com.ashvehicles.ai.log;

import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import com.ashvehicles.ai.AiConfig;
import com.ashvehicles.ai.BotPilot;
import com.ashvehicles.ai.battlefield.TacticalMap;
import com.ashvehicles.ai.combat.CombatController;
import com.ashvehicles.ai.decision.BattleState;
import com.ashvehicles.ai.decision.TacticalAction;
import com.ashvehicles.ai.navigation.Navigator;
import com.ashvehicles.ai.navigation.Obstacles;
import com.ashvehicles.ai.navigation.Route;
import com.ashvehicles.ai.objective.ObjectiveState;
import com.ashvehicles.ai.perception.EnemyObservation;
import com.ashvehicles.ai.perception.Perception;
import com.ashvehicles.entity.VehicleEntityBase;
import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * AI 1両の記録係。各系から合図を受け取り、記録に値する物だけを {@link BattleEvents} へ渡す。
 *
 * <p><b>判断は変わったときだけ書く。</b> 行動か持ち場が変わったとき、それに加えて {@value #SAMPLE_TICKS} tick に
 * 1回だけ、変わらない判断も1行書く——学習には「同じ行動を続けた」という判断も要るが、毎秒書く必要は無い。
 *
 * <p><b>自分で判断できる報酬はここで付ける。</b> 水に沈んだ（{@code WaterEntered}、沈んでいる間は
 * {@code Underwater}）、露出したまま撃ち合いもせずに居続けた（{@code LongExposure}）、
 * 側面へ回って相手の横か後ろから当てた（{@code GoodFlank}）、避けられた死（{@code UnnecessaryDeath}、撃破の
 * 出口から訊かれる）。撃破と拠点の報酬は試合の出口（{@link BattleEvents}）が付ける。
 */
public final class PilotRecorder implements CombatController.Listener {
    /** 変わらない判断を書く間隔（tick）。 */
    private static final int SAMPLE_TICKS = 100;

    /** 露出を確かめる間隔（tick）。 */
    private static final int EXPOSURE_EVERY = 20;

    /** 側面へ回ってから、横からの命中を手柄と数える長さ（tick）。 */
    private static final int FLANK_CREDIT = 400;

    /** 相手の正面からこの角より外なら、横か後ろからの命中。 */
    private static final double SIDE = Math.cos(Math.toRadians(60.0));

    /** 出てからこれより早く、何も与えずに倒されたら、避けられた死（tick）。 */
    private static final int WASTED_LIFE = 600;

    /**
     * 水から上がったと見なすまで乾いている長さ（tick）。岸の縁に掛かった車両は、車体が縁に乗っては水へ落ちるを
     * 繰り返し、深さの読みが数 tick おきに0と2以上を行き来する——それを毎回「入った」と数えると、1回の転落で
     * {@code WaterEntered} が4つ出た（2026-09-13 の記録）。
     */
    private static final int SURFACED_TICKS = 40;

    /** 同じ兵装の弾がこれ以内（tick）に続く間は、1つのまとまり（{@code WeaponReleased} 1行）として数える。 */
    private static final int BURST_GAP = 20;

    private final BotPilot pilot;

    private long lastSample = Long.MIN_VALUE;

    @Nullable
    private String loggedObjective;

    private long exposedSince = Long.MIN_VALUE;
    private long underwaterSince = Long.MIN_VALUE;
    private long underwaterChargedAt;
    private int dryTicks;

    /** 撃っている最中のまとまり。兵装・狙った相手・最初の1発の位置と tick・最後の1発の tick・弾の数・相手までの距離。 */
    @Nullable
    private ResourceLocation burstWeapon;

    @Nullable
    private String burstTarget;

    private Vec3 burstAt = Vec3.ZERO;
    private long burstStarted;
    private long burstLast;
    private int burstRounds;
    private double burstRange = -1.0;
    private long flankedAt = Long.MIN_VALUE;
    private int flankTarget = -1;
    private boolean flankCredited;

    private long firstDecision = Long.MIN_VALUE;
    private double lastThreat;
    private double lastHealth = 1.0;
    private float dealt;

    @Nullable
    private TacticalAction lastAction;

    public PilotRecorder(BotPilot pilot) {
        this.pilot = pilot;
    }

    public void enemyDetected(EnemyObservation enemy) {
        if (!BattleEvents.recording()) {
            return;
        }

        JsonObject detail = new JsonObject();

        detail.addProperty("enemy", describe(enemy.target()));
        detail.addProperty("distance", BattleDecisionLog.round(enemy.distance()));
        detail.addProperty("threat", BattleDecisionLog.round(enemy.threatLevel()));
        BattleEvents.pilotEvent(this.pilot, BattleEventType.ENEMY_DETECTED, detail);
    }

    public void routeSelected(Route route) {
        if (!BattleEvents.recording()) {
            return;
        }

        JsonObject detail = new JsonObject();

        detail.addProperty("points", route.points().size());
        detail.addProperty("cost", BattleDecisionLog.round(route.cost()));
        detail.addProperty("risk", BattleDecisionLog.round(route.risk()));
        detail.addProperty("complete", route.complete());
        detail.addProperty("risk_weight", BattleDecisionLog.round(route.riskWeight()));
        BattleEvents.pilotEvent(this.pilot, BattleEventType.ROUTE_SELECTED, detail);
    }

    @Override
    public void targetChanged(@Nullable EnemyObservation previous, @Nullable EnemyObservation next) {
        if (next == null || !BattleEvents.recording()) {
            return;
        }

        JsonObject detail = new JsonObject();

        detail.addProperty("target", describe(next.target()));
        detail.addProperty("priority", BattleDecisionLog.round(next.targetPriority()));
        detail.addProperty("threat", BattleDecisionLog.round(next.threatLevel()));
        detail.addProperty("distance", BattleDecisionLog.round(next.distance()));
        detail.addProperty("attacking_me", next.attackingMe());
        BattleEvents.pilotEvent(this.pilot, BattleEventType.TARGET_SELECTED, detail);
    }

    @Override
    public void attackStarted(EnemyObservation target) {
        if (!BattleEvents.recording()) {
            return;
        }

        JsonObject detail = new JsonObject();

        detail.addProperty("target", describe(target.target()));
        detail.addProperty("distance", BattleDecisionLog.round(target.distance()));
        BattleEvents.pilotEvent(this.pilot, BattleEventType.ATTACK_STARTED, detail);
    }

    @Override
    public void attackEnded(EnemyObservation target, boolean destroyed) {
        if (!BattleEvents.recording()) {
            return;
        }

        JsonObject detail = new JsonObject();

        detail.addProperty("target", describe(target.target()));
        detail.addProperty("destroyed", destroyed);
        BattleEvents.pilotEvent(this.pilot, BattleEventType.ATTACK_ENDED, detail);
    }

    /**
     * 判断した。
     *
     * @param navigation 記録に載せる今の走り方。位置と一緒に、進めていたかを後から読むため
     */
    public void decided(BattleState state, @Nullable TacticalAction previous, TacticalAction next,
            Map<TacticalAction, Double> utilities, @Nullable ObjectiveState objective, Navigator.Status navigation,
            long now) {
        if (this.firstDecision == Long.MIN_VALUE) {
            this.firstDecision = now;
        }

        this.lastThreat = state.threat();
        this.lastHealth = state.health();
        this.lastAction = next;

        if (!BattleEvents.recording()) {
            return;
        }

        String objectiveId = objective == null ? null : objective.id();
        boolean changed = previous != next;

        if (changed) {
            JsonObject detail = new JsonObject();

            detail.addProperty("from", BattleRecorder.name(previous));
            detail.addProperty("to", next.name());
            detail.addProperty("utility", BattleDecisionLog.round(utilities.getOrDefault(next, 0.0)));
            BattleEvents.pilotEvent(this.pilot, BattleEventType.ACTION_CHANGED, detail);

            if (next == TacticalAction.FLANK) {
                this.flankedAt = now;
                this.flankTarget = state.target() == null ? -1 : state.target().target().getId();
                this.flankCredited = false;

                JsonObject flank = new JsonObject();

                flank.addProperty("target", state.target() == null ? null : describe(state.target().target()));
                BattleEvents.pilotEvent(this.pilot, BattleEventType.FLANK_STARTED, flank);
            }

            if (next == TacticalAction.RETREAT) {
                JsonObject retreat = new JsonObject();

                retreat.addProperty("health", BattleDecisionLog.round(state.health()));
                retreat.addProperty("threat", BattleDecisionLog.round(state.threat()));
                retreat.addProperty("engaging", state.enemiesEngaging());
                BattleEvents.pilotEvent(this.pilot, BattleEventType.RETREAT_STARTED, retreat);
            }
        }

        if (changed || !Objects.equals(objectiveId, this.loggedObjective) || now - this.lastSample >= SAMPLE_TICKS) {
            BattleEvents.decision(this.pilot, BattleDecisionLog.of(this.pilot, state, previous, next, utilities,
                    objective, navigation));
            this.lastSample = now;
            this.loggedObjective = objectiveId;
        }
    }

    /**
     * 毎 tick。水に沈んでいる長さと、露出の長さを数える——見られて撃たれうる所に、遮蔽も無く、撃ち合いも身を
     * 引くこともせずに居続けた。
     *
     * @param water 車両が浸かっている水の深さ（ブロック、{@code navigation/Navigator#waterDepth}）
     */
    public void tick(long now, Perception perception, TacticalMap map, TacticalAction action, int water) {
        if (!BattleEvents.recording()) {
            return;
        }

        this.water(now, water);

        if (now % EXPOSURE_EVERY != 0) {
            return;
        }

        Vec3 here = this.pilot.vehicle().position();
        boolean busy = action == TacticalAction.ATTACK || action == TacticalAction.FLANK || action.isWithdrawal();
        boolean covered = map.ready() && map.cellAt(here.x, here.z).coverValue() >= 0.5F;

        if (perception.engaging() <= 0 || covered || busy) {
            this.exposedSince = Long.MIN_VALUE;

            return;
        }

        if (this.exposedSince == Long.MIN_VALUE) {
            this.exposedSince = now;

            return;
        }

        if (now - this.exposedSince >= AiConfig.get().rewards().longExposureTicks()) {
            JsonObject detail = new JsonObject();

            detail.addProperty("engaging", perception.engaging());
            detail.addProperty("seconds", (now - this.exposedSince) / 20);
            this.exposedSince = now;
            BattleEvents.reward(this.pilot, BattleEventType.LONG_EXPOSURE, detail);
        }
    }

    /**
     * 水に沈んだ。<b>入った瞬間に大きく引き</b>（{@code WaterEntered}）、沈んでいる間は {@code rewards.underwaterTicks}
     * ごとにまた引く（{@code Underwater}）。地上車両にとって水の底は、撃たれても動けず、岸が急なら上がれない場所だ。
     */
    private void water(long now, int depth) {
        if (depth < Obstacles.DEEP) {
            if (this.underwaterSince != Long.MIN_VALUE && ++this.dryTicks >= SURFACED_TICKS) {
                this.underwaterSince = Long.MIN_VALUE;
            }

            return;
        }

        this.dryTicks = 0;

        if (this.underwaterSince == Long.MIN_VALUE) {
            JsonObject detail = new JsonObject();

            this.underwaterSince = now;
            this.underwaterChargedAt = now;
            detail.addProperty("depth", depth);
            BattleEvents.reward(this.pilot, BattleEventType.WATER_ENTERED, detail);

            return;
        }

        if (now - this.underwaterChargedAt >= AiConfig.get().rewards().underwaterTicks()) {
            JsonObject detail = new JsonObject();

            this.underwaterChargedAt = now;
            detail.addProperty("depth", depth);
            detail.addProperty("seconds", (now - this.underwaterSince) / 20);
            BattleEvents.reward(this.pilot, BattleEventType.UNDERWATER, detail);
        }
    }

    /** 自分の弾が効いた。側面へ回った後の、横か後ろからの命中を手柄にする。 */
    public void damageDealt(Entity victim, float amount, long now) {
        this.dealt += amount;

        if (this.flankCredited || this.flankedAt == Long.MIN_VALUE || now - this.flankedAt > FLANK_CREDIT) {
            return;
        }

        Entity hit = victim.getVehicle() != null ? victim.getVehicle() : victim;

        if (hit.getId() != this.flankTarget || !(hit instanceof VehicleEntityBase machine) || !machine.isArmoured()) {
            return;
        }

        Vec3 forward = Vec3.directionFromRotation(0.0F, hit.getYRot());
        Vec3 towards = this.pilot.vehicle().position().subtract(hit.position());
        double length = Math.sqrt(towards.x * towards.x + towards.z * towards.z);

        if (length < 1.0E-3 || (forward.x * towards.x + forward.z * towards.z) / length >= SIDE) {
            return;
        }

        this.flankCredited = true;

        JsonObject detail = new JsonObject();

        detail.addProperty("target", describe(hit));
        detail.addProperty("angle", Math.round(Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0,
                (forward.x * towards.x + forward.z * towards.z) / length))))));
        BattleEvents.reward(this.pilot, BattleEventType.GOOD_FLANK, detail);
    }

    /**
     * 避けられた死だったか。<b>身を引かずに危険と深手を抱えていた</b>か、<b>出てすぐ何も与えずに倒された</b>か。
     */
    public boolean unnecessaryDeath(long now) {
        if (this.lastAction != null && this.lastAction.isWithdrawal()) {
            return false;
        }

        boolean reckless = this.lastThreat >= 0.6 && this.lastHealth <= 0.35;
        boolean wasted = this.firstDecision != Long.MIN_VALUE && now - this.firstDecision <= WASTED_LIFE
                && this.dealt <= 0.0F;

        return reckless || wasted;
    }

    /**
     * 兵装を撃った。航空機の操縦役が、残弾の減りで数えて呼ぶ。
     *
     * <p><b>1発ずつは書かない。</b> 機関砲は1秒に何十発も出る——同じ兵装の弾が {@value #BURST_GAP} tick 以内に続く間は
     * 1つのまとまりとして数え、止んでから {@code WeaponReleased} を1行だけ書く（{@link #flushReleases}）。位置と時刻は
     * まとまりの最初の1発の物。地図の上で「どこから撃ったか」を見るため（{@code tool/ai/viewer}）。
     *
     * @param rounds この tick に出た弾の数
     */
    public void released(ResourceLocation weapon, @Nullable Entity target, int rounds, long now) {
        if (!BattleEvents.recording() || rounds <= 0) {
            return;
        }

        if (this.burstWeapon != null && (!this.burstWeapon.equals(weapon) || now - this.burstLast > BURST_GAP)) {
            this.writeBurst();
        }

        if (this.burstWeapon == null) {
            this.burstWeapon = weapon;
            this.burstTarget = target == null ? null : describe(target);
            this.burstAt = this.pilot.vehicle().position();
            this.burstStarted = now;
            this.burstRange = target == null ? -1.0 : target.position().distanceTo(this.burstAt);
            this.burstRounds = 0;
        }

        this.burstRounds += rounds;
        this.burstLast = now;
    }

    /** 止んでから {@value #BURST_GAP} tick 経ったまとまりを書く。{@code log/BattleEvents} が周期的に、倒されたときと試合の終わりに呼ぶ。 */
    public void flushReleases(long now) {
        if (this.burstWeapon != null && now - this.burstLast > BURST_GAP) {
            this.writeBurst();
        }
    }

    private void writeBurst() {
        JsonObject detail = new JsonObject();

        detail.addProperty("weapon", this.burstWeapon.toString());
        detail.addProperty("rounds", this.burstRounds);
        detail.addProperty("seconds", BattleDecisionLog.round((this.burstLast - this.burstStarted) / 20.0));
        detail.addProperty("target", this.burstTarget);

        if (this.burstRange >= 0.0) {
            detail.addProperty("distance", BattleDecisionLog.round(this.burstRange));
        }

        this.burstWeapon = null;
        BattleEvents.pilotEvent(this.pilot, BattleEventType.WEAPON_RELEASED, detail, this.burstAt, this.burstStarted);
    }

    /** 記録に書く相手の名前。種類と ID。 */
    static String describe(Entity entity) {
        return entity.getType().toShortString() + "#" + entity.getId();
    }
}
