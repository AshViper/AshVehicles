package com.ashvehicles.ai.decision;

import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.ai.battlefield.TacticalCell;
import com.ashvehicles.ai.battlefield.TacticalMap;
import com.ashvehicles.ai.objective.CaptureState;
import com.ashvehicles.ai.objective.ObjectiveState;
import com.ashvehicles.ai.perception.AllyObservation;
import com.ashvehicles.ai.perception.EnemyObservation;
import com.ashvehicles.ai.role.TacticalProfile;
import com.ashvehicles.ai.role.VehicleRole;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 判断1回ぶんの戦場。方針（{@link DecisionPolicy}）が読む唯一の物。
 *
 * <p><b>方針はここしか見ない。</b> 世界にもエンティティにも、車両の API にも触らない。だから同じ状態を渡せば
 * 同じ答えが出るし、ルールで書いた方針を学習した方針へ差し替えても、渡す物は変わらない。組み立てるのは車両の
 * 操縦役（{@code GroundPilot}）で、各系の結果を写すだけ。
 *
 * <p><b>学習のための平らな形も持つ</b>（{@link #features}）。並び順は {@link #FEATURES} が名前で決めていて、
 * 記録（{@code log/BattleDecisionLog}）にもこの名前で出る。<b>並びを変えたら版を分けること</b>——古い記録で
 * 学んだ物に新しい並びを食わせると、黙って別の意味の数字を読む。
 *
 * @param gameTime             判断した時刻
 * @param entityId             車両のエンティティ ID
 * @param team                 陣営
 * @param version              AI の版
 * @param role                 役割
 * @param profile              役割の目盛り
 * @param position             位置
 * @param health               残り耐久の割合
 * @param ammo                 残弾の割合（一番残っている架台）
 * @param beyondArena          戦域の外にいる
 * @param enemies              観測した敵（近い順）
 * @param allies               観測した味方（近い順）
 * @param threat               全部の敵の脅威を合わせた物（0〜1）
 * @param exposure             今いる所の露出（0〜1）
 * @param underFire            最近撃たれた
 * @param enemiesEngaging      こちらを見ていて射程に入れている敵の数
 * @param alliesNear           近くの味方の数
 * @param knownEnemies         陣営が覚えている敵の数
 * @param target               今の目標
 * @param targetFrontal        目標がこちらに正面を向けている（砲がこちらを向いている）
 * @param alliesOnTarget       目標を撃っている味方の数
 * @param targetIneffective    目標に自分の弾が効いていない
 * @param allyInTrouble        助けに行く価値のある、撃たれている味方
 * @param allyNeed             その味方の困り具合（0〜1）
 * @param supportCall          その味方は、陣営の頭が支援に割り当てた要請の主（{@code team/SupportCalls}）。特徴量には入れない
 * @param objectives           陣営から見た持ち場
 * @param objective            自分に割り当てられた持ち場
 * @param objectiveDistance    そこまでの距離
 * @param atObjective          その円の中にいる
 * @param objectiveWeight      持ち場の点数を、陣営の持ち場の中で一番高い物で割った値（0〜1）
 * @param routeRisk            走っている道（無ければ直線）の危険
 * @param tacticalMap          戦術格子
 * @param cell                 今いるマス
 * @param inCover              今いるマスが脅威の方向に遮蔽を持つ
 * @param coverNearby          近くに遮蔽を見付けてある
 * @param currentAction        今の行動
 * @param ticksInAction        今の行動を続けている長さ
 * @param arena                会場の広さの目安（ブロック）
 */
public record BattleState(long gameTime, int entityId, String team, String version, VehicleRole role,
        TacticalProfile profile, Vec3 position, double health, double ammo, boolean beyondArena,
        List<EnemyObservation> enemies, List<AllyObservation> allies, double threat, double exposure,
        boolean underFire, int enemiesEngaging, int alliesNear, int knownEnemies,
        @Nullable EnemyObservation target, boolean targetFrontal, int alliesOnTarget, boolean targetIneffective,
        @Nullable AllyObservation allyInTrouble, double allyNeed, boolean supportCall, List<ObjectiveState> objectives,
        @Nullable ObjectiveState objective, double objectiveDistance, boolean atObjective, double objectiveWeight,
        double routeRisk, @Nullable TacticalMap tacticalMap, @Nullable TacticalCell cell, boolean inCover,
        boolean coverNearby, TacticalAction currentAction, long ticksInAction, double arena) {

    /**
     * {@link #features} の並び。記録のヘッダでもある。
     */
    public static final List<String> FEATURES = List.of(
            "health", "ammo", "threat", "exposure", "under_fire", "enemies_engaging", "allies_near",
            "known_enemies", "has_target", "target_visible", "target_in_range", "target_distance",
            "target_threat", "target_priority", "target_frontal", "allies_on_target", "target_ineffective",
            "ally_need", "has_objective", "objective_friendly", "objective_enemy", "objective_contested",
            "objective_neutral", "recapture_required", "being_taken", "objective_distance", "at_objective",
            "objective_weight", "route_risk", "in_cover", "cover_nearby", "height", "beyond_arena",
            "role_scout", "role_tank", "role_ifv", "role_apc", "role_aa", "role_artillery", "role_support",
            "action_capture", "action_recapture", "action_defend", "action_advance", "action_attack",
            "action_flank", "action_seek_cover", "action_retreat", "action_search", "action_support",
            "ticks_in_action");

    /**
     * 学習のための平らな形。どの値もおおよそ 0〜1 に揃えてある。並びは {@link #FEATURES}。
     */
    public float[] features() {
        float[] out = new float[FEATURES.size()];
        int at = 0;
        EnemyObservation aim = this.target;
        ObjectiveState duty = this.objective;
        double sight = Math.max(this.arena, 1.0);

        out[at++] = (float) this.health;
        out[at++] = (float) this.ammo;
        out[at++] = (float) this.threat;
        out[at++] = (float) this.exposure;
        out[at++] = flag(this.underFire);
        out[at++] = unit(this.enemiesEngaging / 5.0);
        out[at++] = unit(this.alliesNear / 5.0);
        out[at++] = unit(this.knownEnemies / 10.0);
        out[at++] = flag(aim != null);
        out[at++] = flag(aim != null && aim.visible());
        out[at++] = flag(aim != null && aim.inWeaponRange());
        out[at++] = aim == null ? 1.0F : unit(aim.distance() / sight);
        out[at++] = aim == null ? 0.0F : (float) aim.threatLevel();
        out[at++] = aim == null ? 0.0F : unit(aim.targetPriority() / 2.0);
        out[at++] = flag(this.targetFrontal);
        out[at++] = unit(this.alliesOnTarget / 3.0);
        out[at++] = flag(this.targetIneffective);
        out[at++] = (float) this.allyNeed;
        out[at++] = flag(duty != null);
        out[at++] = flag(duty != null && duty.state() == CaptureState.FRIENDLY);
        out[at++] = flag(duty != null && duty.state() == CaptureState.ENEMY);
        out[at++] = flag(duty != null && duty.state() == CaptureState.CONTESTED);
        out[at++] = flag(duty != null && duty.state() == CaptureState.NEUTRAL);
        out[at++] = flag(duty != null && duty.recaptureRequired());
        out[at++] = flag(duty != null && duty.beingTaken());
        out[at++] = duty == null ? 1.0F : (float) Mth.clamp(this.objectiveDistance / sight, 0.0, 1.5);
        out[at++] = flag(this.atObjective);
        out[at++] = (float) this.objectiveWeight;
        out[at++] = (float) this.routeRisk;
        out[at++] = flag(this.inCover);
        out[at++] = flag(this.coverNearby);
        out[at++] = this.cell == null ? 0.0F : this.cell.heightValue();
        out[at++] = flag(this.beyondArena);

        for (VehicleRole each : VehicleRole.VALUES) {
            out[at++] = flag(this.role == each);
        }

        for (TacticalAction each : TacticalAction.VALUES) {
            out[at++] = flag(this.currentAction == each);
        }

        out[at] = unit(this.ticksInAction / 400.0);

        return out;
    }

    private static float flag(boolean value) {
        return value ? 1.0F : 0.0F;
    }

    private static float unit(double value) {
        return (float) Mth.clamp(value, 0.0, 1.0);
    }
}
