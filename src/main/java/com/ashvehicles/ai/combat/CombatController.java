package com.ashvehicles.ai.combat;

import javax.annotation.Nullable;

import com.ashvehicles.ai.AiConfig;
import com.ashvehicles.ai.battlefield.TeamIntel;
import com.ashvehicles.ai.control.FireCommand;
import com.ashvehicles.ai.control.GroundVehicleController;
import com.ashvehicles.ai.core.Cadence;
import com.ashvehicles.ai.decision.ParameterSet;
import com.ashvehicles.ai.perception.EnemyObservation;
import com.ashvehicles.ai.perception.Perception;
import com.ashvehicles.ai.role.TacticalProfile;
import com.ashvehicles.ai.role.VehicleRole;
import com.ashvehicles.ai.tactics.MovementIntent;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.GroundVehicleEntity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * 撃つことの全部。目標選び → 兵装選び → 照準 → 引き金 → 補給 → 効いているかの評価。
 *
 * <p><b>移動を知らない。</b> 砲塔は車体と別に回るので、走りながら狙い、据わった瞬間に撃てる——拠点へ向かう途中で
 * 見えた敵にも、下がりながら追ってくる敵にも同じように撃つ。足を止めるかどうかを決めるのは行動の側
 * （{@link MovementIntent#stopToFireRange}）で、ここはその距離に相手が入ったかを答えるだけ
 * （{@link #wantsToHold}）。
 *
 * <p><b>AI 専用の武器処理を持たない。</b> 撃つのは車両の砲そのもので、ここが出すのは引き金と狙い点と兵装の選択
 * だけ（{@code control/GroundVehicleController}）。装填・弾倉・シーカー・弾道・当たり判定は人が撃つときと同じ。
 *
 * <p>目標は周期（{@code timing.target}）で選び直し、照準と引き金は毎 tick。
 */
public final class CombatController {
    /**
     * 足を止めて撃ち合う距離の上限（ブロック）。元の {@code GroundPilot.CLOSE_FIGHT}。
     *
     * <p>ここまで詰められたら、拠点へ急ぐより先に片付ける。走りながらでは車体が振れて砲が追い付かない
     * （[[turret-slew-is-a-braking-problem]]）ので、近い相手ほど止まる価値が大きい。
     */
    public static final double CLOSE_FIGHT = 70.0;

    /** 最後に撃ってからこれだけ経てば、撃ち合いは終わった（tick）。 */
    private static final int ENGAGEMENT_LAPSE = 200;

    /** 撃ち合いの始まりと終わり、目標の替わり目を受け取る。記録のため。 */
    public interface Listener {
        void targetChanged(@Nullable EnemyObservation previous, @Nullable EnemyObservation next);

        void attackStarted(EnemyObservation target);

        void attackEnded(EnemyObservation target, boolean destroyed);
    }

    private final GroundVehicleEntity ground;
    private final GroundVehicleController control;
    private final FireControl fire;
    private final Resupply supply = new Resupply();
    private final DamageLedger ledger = new DamageLedger();
    private final TargetSelector.Weights weights;
    private final TacticalProfile profile;
    private final VehicleRole role;
    private final Cadence cadence;

    @Nullable
    private EnemyObservation target;

    @Nullable
    private EnemyObservation engagedWith;

    private GroundVehicleEntity.Armament chosen = GroundVehicleEntity.Armament.MAIN;
    private long lastShot = Long.MIN_VALUE;
    private boolean lostTarget;

    @Nullable
    private Listener listener;

    public CombatController(GroundVehicleEntity ground, GroundVehicleController control, TacticalProfile profile,
            VehicleRole role, ParameterSet parameters) {
        this.ground = ground;
        this.control = control;
        this.fire = new FireControl(ground, control);
        this.weights = TargetSelector.Weights.of(parameters);
        this.profile = profile;
        this.role = role;
        // 選び直しの位相を個体ごとにずらす。40両が同じ tick に一斉に目標を探すと、その1 tick だけが重い。
        this.cadence = new Cadence(ground.getId() * 13, AiConfig.timing().target());
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    /**
     * 1 tick 分。
     *
     * @param now  ゲーム時刻
     * @param tick サーバーの tick（予算のため）
     */
    public FireCommand tick(long now, int tick, Perception perception, MovementIntent intent, TeamIntel intel) {
        if (this.target != null && (!this.target.alive() || this.target.target().level() != this.ground.level())) {
            EnemyObservation gone = this.target;

            this.target = null;
            this.lostTarget = true;
            this.cadence.force();

            if (this.listener != null) {
                this.listener.targetChanged(gone, null);
            }

            this.endAttack(gone, !gone.alive());
        }

        if (this.cadence.tick(AiConfig.timing().target())) {
            EnemyObservation next = TargetSelector.select(this.ground, perception.enemies(), this.target,
                    intent.preferredTarget(), this.weights, this.profile, this.role, this.ledger, intel, now,
                    AiConfig.sight().range());

            if (next != this.target) {
                EnemyObservation previous = this.target;

                this.target = next;

                if (this.engagedWith != null && this.engagedWith != next) {
                    this.endAttack(this.engagedWith, false);
                }

                if (this.listener != null) {
                    this.listener.targetChanged(previous, next);
                }
            }
        }

        Entity quarry = this.target == null ? null : this.target.target();
        double range = quarry == null ? Double.MAX_VALUE : quarry.position().distanceTo(this.ground.position());

        this.supply.tick(this.ground, this.control, quarry != null);
        this.chosen = quarry == null ? GroundVehicleEntity.Armament.MAIN
                : WeaponSelector.choose(this.ground, quarry, range);

        FireCommand command = this.fire.lay(quarry, range, this.chosen, perception.allies(), tick);

        if (command.shooting() && this.target != null) {
            ResourceLocation weaponId = WeaponSelector.weaponOf(this.ground, this.chosen);

            this.lastShot = now;
            this.ledger.shot(quarry.getId(), weaponId != null && Definitions.weapon(weaponId).isAutomatic(), now);
            intel.engage(this.ground.getId(), quarry.getId(), now);

            if (this.engagedWith != this.target) {
                this.engagedWith = this.target;

                if (this.listener != null) {
                    this.listener.attackStarted(this.target);
                }
            }
        } else if (this.engagedWith != null && now - this.lastShot > ENGAGEMENT_LAPSE) {
            this.endAttack(this.engagedWith, false);
        }

        return command;
    }

    /**
     * 足を止めて撃ち合うべきか。相手が見えていて、行動が許す距離の内にいる。
     *
     * <p>機銃で戦う車両は機銃の間合いで止まる——砲の間合いで止まれば、届かない弾を撃ち続けたまま近寄らない。
     */
    public boolean wantsToHold(MovementIntent intent) {
        if (this.target == null || !this.target.visible() || intent.stopToFireRange() <= 0.0) {
            return false;
        }

        double stop = this.chosen == GroundVehicleEntity.Armament.COAX
                ? Math.min(intent.stopToFireRange(), WeaponSelector.COAX_RANGE)
                : intent.stopToFireRange();

        return this.target.target().position().distanceTo(this.ground.position()) <= stop;
    }

    /** 目標を失った（倒した・消えた）ことを1度だけ答える。方針を前倒しで呼ぶための合図。 */
    public boolean consumeLostTarget() {
        boolean was = this.lostTarget;

        this.lostTarget = false;

        return was;
    }

    /** 自分の弾が効いた。 */
    public void dealt(Entity victim, float amount, long now) {
        this.ledger.dealt(victim, amount, now);
    }

    @Nullable
    public EnemyObservation target() {
        return this.target;
    }

    @Nullable
    public Entity targetEntity() {
        return this.target == null ? null : this.target.target();
    }

    public DamageLedger ledger() {
        return this.ledger;
    }

    private void endAttack(EnemyObservation with, boolean destroyed) {
        if (this.engagedWith != with) {
            return;
        }

        this.engagedWith = null;

        if (this.listener != null) {
            this.listener.attackEnded(with, destroyed);
        }
    }
}
