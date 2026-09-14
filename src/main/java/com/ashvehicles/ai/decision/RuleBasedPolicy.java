package com.ashvehicles.ai.decision;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import com.ashvehicles.ai.objective.CaptureState;
import com.ashvehicles.ai.objective.ObjectiveState;
import com.ashvehicles.ai.perception.EnemyObservation;
import com.ashvehicles.ai.role.TacticalProfile;

import net.minecraft.util.Mth;

/**
 * ルールで書いた方針。行動ごとに点数（効用）を出し、一番高い物を選ぶ。
 *
 * <p><b>固定の優先順位では決めない。</b> 基本の順——生存、奪還、防衛、制圧、攻撃、前進、探索——は各行動の
 * 基本の重み（{@link Weights}）の大小として入っているだけで、実際の点数はその場の状態で伸び縮みする。
 * 体力が残っていて脅威が小さければ撤退の点数は0に近く、奪還の必要な拠点でも割り当てられていなければ奪還は
 * 選ばれない。
 *
 * <p><b>ぐらつきを抑える。</b> 今の行動には下駄（{@code hysteresis}）を履かせ、始めてから
 * {@code min_dwell} tick の間は、明らかに良い行動（+0.25）でなければ乗り換えない。例外は撤退で、点数が
 * {@code emergency} を超えたら待たない——死にかけの車両に「さっき攻撃を決めたから」は通用しない。
 *
 * <p>重みは全部版のパラメータ（{@code policy.*}）で変えられる。学習が触るのはここ——「いつ撤退するか」
 * 「どれだけ危険を許容するか」「いつ側面へ回るか」——であって、行動の中身ではない。
 */
public final class RuleBasedPolicy implements DecisionPolicy {
    /** 種類の名前。版ファイルの {@code policy} に書く。 */
    public static final String ID = "rule_based";

    /**
     * 基本の重みと、ぐらつきの抑え。
     *
     * @param survival   撤退
     * @param recapture  奪還
     * @param defend     防衛
     * @param capture    制圧
     * @param attack     攻撃
     * @param flank      側面
     * @param cover      遮蔽
     * @param support    味方の支援
     * @param call       陣営の頭が割り当てた支援（{@code team/SupportCalls}）。役割の援護の癖に関わらず掛かる
     * @param advance    前進
     * @param search     探索
     * @param hysteresis 今の行動に履かせる下駄
     * @param minDwell   乗り換えを渋る長さ（tick）
     * @param emergency  これを超えた撤退は待たない
     */
    public record Weights(double survival, double recapture, double defend, double capture, double attack,
            double flank, double cover, double support, double call, double advance, double search, double hysteresis,
            double minDwell, double emergency) {
        public static Weights of(ParameterSet parameters) {
            return new Weights(
                    parameters.get("policy.survival", 1.0),
                    parameters.get("policy.recapture", 0.9),
                    parameters.get("policy.defend", 0.8),
                    parameters.get("policy.capture", 0.7),
                    parameters.get("policy.attack", 0.6),
                    parameters.get("policy.flank", 0.62),
                    parameters.get("policy.cover", 0.85),
                    parameters.get("policy.support", 0.55),
                    parameters.get("policy.call", 0.95),
                    parameters.get("policy.advance", 0.4),
                    parameters.get("policy.search", 0.25),
                    parameters.get("policy.hysteresis", 0.08),
                    parameters.get("policy.min_dwell", 40.0),
                    parameters.get("policy.emergency", 0.8));
        }
    }

    /** 乗り換えを渋っている間でも、これだけ良ければ乗り換える。 */
    private static final double CLEARLY_BETTER = 0.25;

    private final Weights weights;
    private final EnumMap<TacticalAction, Double> raw = new EnumMap<>(TacticalAction.class);
    private final EnumMap<TacticalAction, Double> shown = new EnumMap<>(TacticalAction.class);

    public RuleBasedPolicy(ParameterSet parameters) {
        this.weights = Weights.of(parameters);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Map<TacticalAction, Double> lastUtilities() {
        return Collections.unmodifiableMap(this.shown);
    }

    @Override
    public TacticalAction decide(BattleState state) {
        Weights w = this.weights;
        TacticalProfile profile = state.profile();
        ObjectiveState duty = state.objective();
        EnemyObservation aim = state.target();

        this.raw.clear();

        double danger = Mth.clamp(state.threat() * 0.7 + state.exposure() * 0.3, 0.0, 1.0);
        double risk = danger * (1.2 - profile.riskTolerance());
        double outnumbered = Mth.clamp((state.enemiesEngaging() - state.alliesNear() - 1) / 3.0, 0.0, 1.0);
        double retreatLine = profile.retreatHealth() + 0.15;
        double wounded = Mth.clamp((retreatLine - state.health()) / retreatLine, 0.0, 1.0);
        boolean contact = state.enemiesEngaging() > 0 || state.underFire();

        // ---- 生存
        double retreat = w.survival() * Math.min(1.5, wounded * (0.6 + danger)
                + outnumbered * (0.3 + 0.5 * danger)
                + (state.ammo() <= 0.0 ? (contact ? 0.6 : 0.2) : 0.0));

        // 取られかけている拠点を最後の1両で守っているなら、まだ粘る。体力が本当に尽きかけるまで。
        if (duty != null && duty.beingTaken() && state.atObjective() && state.health() > 0.3) {
            retreat -= 0.2 * profile.holdBias();
        }

        this.raw.put(TacticalAction.RETREAT, retreat);

        double cover = w.cover() * Mth.clamp(risk * (state.underFire() ? 1.0 : 0.6) * (state.inCover() ? 0.3 : 1.0)
                * (0.5 + profile.coverBias()) * (1.0 - 0.5 * wounded), 0.0, 1.0);

        if (!contact && state.exposure() < 0.2) {
            cover *= 0.3;
        }

        this.raw.put(TacticalAction.SEEK_COVER, cover);

        // ---- 撃ち合い
        double attack = 0.0;
        double flank = 0.0;

        // 空の相手は撃つだけで、追って走らない。砲塔が空を追っている間も、車体は持ち場の仕事を続ける——空を撃てる
        // 車両は空の相手に地上のどの相手より高い優先度を付ける（{@code combat/TargetSelector}）ので、ここで数えれば
        // 攻撃が拠点を押しのける。
        if (aim != null && !aim.flying()) {
            double priority = Mth.clamp(aim.targetPriority() / 1.5, 0.0, 1.2);
            double closeness = Math.max(0.0, 1.0 - aim.distance() / Math.max(state.arena(), 1.0));

            attack = w.attack() * priority * (aim.visible() ? 1.0 : 0.4) * (aim.inWeaponRange() ? 1.0 : 0.7)
                    * (1.0 + 0.3 * closeness)
                    * (1.0 - risk * (1.0 - profile.aggression()))
                    * (0.5 + 0.5 * aim.effectiveness())
                    * (state.targetIneffective() ? 0.4 : 1.0)
                    * (1.0 - 0.5 * wounded);

            // 側面へ回る価値があるのは、装甲を持つ相手が正面を向けていて、正面を受け持つ味方がいるか、自分が
            // 回り込む役割のとき。自分の弾が正面に効かないなら、なおさら。
            boolean flankable = aim.armoured() && state.targetFrontal() && aim.distance() >= 30.0
                    && aim.distance() <= 220.0 && (state.alliesOnTarget() >= 1 || profile.flankBias() >= 0.6);

            if (flankable) {
                flank = w.flank() * profile.flankBias() * Math.max(priority, 0.4) * (1.0 - danger * 0.5)
                        * (aim.effectiveness() < 0.6 || state.targetIneffective() ? 1.5 : 1.0);
            }
        }

        this.raw.put(TacticalAction.ATTACK, attack);
        this.raw.put(TacticalAction.FLANK, flank);

        double support = state.allyInTrouble() == null ? 0.0
                : w.support() * profile.supportBias() * state.allyNeed() * (1.0 - danger * 0.5);

        // 陣営の頭が割り当てた支援（team/SupportCalls、2026-09-14 の指示「人数不利だったら近くにいる味方に支援を要請」）。
        // 役割の援護の癖に関わらず行く——割り当てられたのは、手が空いていて近い車両だ。奪還と撤退はこれより重い。
        if (state.supportCall() && state.allyInTrouble() != null) {
            support = Math.max(support, w.call() * (0.6 + 0.4 * state.allyNeed()) * (1.0 - danger * 0.5));
        }

        this.raw.put(TacticalAction.SUPPORT_ALLY, support);

        // ---- 拠点
        double recapture = 0.0;
        double defend = 0.0;
        double capture = 0.0;

        if (duty != null) {
            double weight = Mth.clamp(state.objectiveWeight(), 0.0, 1.0) * (0.5 + 0.5 * profile.objectiveBias());

            if (duty.recaptureRequired()) {
                recapture = w.recapture() * weight;
            } else if (duty.state() == CaptureState.FRIENDLY) {
                if (duty.beingTaken() || duty.enemiesNear() > 0 || profile.holdBias() >= 0.6) {
                    defend = w.defend() * weight * (duty.beingTaken() ? 1.0 : 0.6);
                }
            } else {
                capture = w.capture() * weight;
            }
        }

        this.raw.put(TacticalAction.RECAPTURE_OBJECTIVE, recapture);
        this.raw.put(TacticalAction.DEFEND_OBJECTIVE, defend);
        this.raw.put(TacticalAction.CAPTURE_OBJECTIVE, capture);

        // ---- 前進と探索
        this.raw.put(TacticalAction.ADVANCE,
                w.advance() * (duty == null ? 1.0 : 0.2) * (state.knownEnemies() > 0 ? 1.0 : 0.6));
        this.raw.put(TacticalAction.SEARCH_ENEMY, w.search()
                * (state.knownEnemies() == 0 ? (duty == null ? 1.0 : 0.5) : 0.2) * (0.5 + profile.scoutBias()));

        return this.choose(state);
    }

    /** 下駄と乗り換えの渋りを掛けて1つ選ぶ。 */
    private TacticalAction choose(BattleState state) {
        TacticalAction current = state.currentAction();

        this.shown.clear();
        this.shown.putAll(this.raw);

        double currentRaw = this.raw.getOrDefault(current, 0.0);

        // 点数の無い行動（目標を失った攻撃）には下駄を履かせない。履かせれば、何もしない行動に居座る。
        if (currentRaw > 0.0) {
            this.shown.merge(current, this.weights.hysteresis(), Double::sum);
        }

        TacticalAction best = current;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (TacticalAction action : TacticalAction.VALUES) {
            double score = this.shown.getOrDefault(action, 0.0);

            if (score > bestScore) {
                bestScore = score;
                best = action;
            }
        }

        if (best != current && currentRaw > 0.0 && state.ticksInAction() < this.weights.minDwell()) {
            boolean emergency = best == TacticalAction.RETREAT && bestScore >= this.weights.emergency();

            if (!emergency && bestScore < this.shown.getOrDefault(current, 0.0) + CLEARLY_BETTER) {
                best = current;
            }
        }

        if (bestScore <= 0.0) {
            // どれにも点が付かない。前へ出る——止まったまま撃ち合う試合にしない。
            return TacticalAction.ADVANCE;
        }

        return best;
    }
}
