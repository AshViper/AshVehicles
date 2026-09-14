package com.ashvehicles.ai.combat;

import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.ai.battlefield.TeamIntel;
import com.ashvehicles.ai.decision.ParameterSet;
import com.ashvehicles.ai.perception.EnemyObservation;
import com.ashvehicles.ai.perception.WeaponReach;
import com.ashvehicles.ai.role.Roles;
import com.ashvehicles.ai.role.TacticalProfile;
import com.ashvehicles.ai.role.VehicleRole;
import com.ashvehicles.entity.GroundVehicleEntity;

import net.minecraft.world.entity.Entity;

/**
 * 誰を撃つか。観測した敵に優先度を付け、一番高い物を選ぶ。
 *
 * <p><b>一番近い敵を撃つのではない。</b> 並べる順はおおよそこうなる——
 *
 * <ol>
 * <li>自分を撃っている敵
 * <li>自陣の拠点を取りに来ている敵
 * <li>味方を撃っている敵
 * <li>遠くの、何もしていない敵
 * </ol>
 *
 * <p>ただし順位表ではなく点数の和で決める（{@link Weights}）。自分を撃っている装甲車より、拠点の円の中に居座る
 * 戦車の方が上に来ることはあるし、効かない相手（{@link DamageLedger}）は下がり、味方が既に3両で撃っている相手は
 * 少し下がる。重みは版のパラメータ（{@code target.*}）で変えられる。
 *
 * <p><b>世界に問い合わせない。</b> 候補は観測（{@code perception/Perception}）が名簿から作った物だけで、ここでは
 * 視線も引かない。
 */
public final class TargetSelector {
    /** 見失っても今の目標を持ち続ける長さ（tick）。丘の陰へ入った瞬間に砲が明後日を向かないように。 */
    private static final int KEEP_UNSEEN = 40;

    /**
     * 優先度の重み。
     *
     * @param threat      こちらへの脅威
     * @param attackingMe 自分を撃っている
     * @param objective   自陣の拠点を取りに来ている
     * @param ally        味方を撃っている
     * @param proximity   近さ
     * @param weak        残り耐久の少なさ（仕留めやすさ）
     * @param effect      自分の兵装の効き
     * @param inRange     自分の兵装が届く
     * @param sticky      今の目標に掛ける下駄。2両の間で毎秒行き来しないように
     * @param focus       味方も撃っている相手（集中）
     * @param overkill    味方が3両以上で撃っている相手（撃ちすぎ）
     * @param ineffective 撃っても効かなかった相手
     * @param antiAir     空の相手（防空の役割の度合いを掛ける）
     * @param preferred   戦術が名指しした相手（支援・側面の対象）
     * @param airDuty     空を撃てる車両が、空の相手に足す点。地上の相手のどんな事情より先に空を狙う大きさ
     */
    public record Weights(double threat, double attackingMe, double objective, double ally, double proximity,
            double weak, double effect, double inRange, double sticky, double focus, double overkill,
            double ineffective, double antiAir, double preferred, double airDuty) {
        public static Weights of(ParameterSet parameters) {
            return new Weights(
                    parameters.get("target.threat", 0.5),
                    parameters.get("target.attacking_me", 1.0),
                    parameters.get("target.objective", 0.6),
                    parameters.get("target.ally", 0.45),
                    parameters.get("target.proximity", 0.3),
                    parameters.get("target.weak", 0.15),
                    parameters.get("target.effect", 0.3),
                    parameters.get("target.in_range", 0.2),
                    parameters.get("target.sticky", 0.25),
                    parameters.get("target.focus", 0.1),
                    parameters.get("target.overkill", 0.1),
                    parameters.get("target.ineffective", 0.6),
                    parameters.get("target.anti_air", 0.5),
                    parameters.get("target.preferred", 0.8),
                    parameters.get("target.air_duty", 2.0));
        }
    }

    private TargetSelector() {
    }

    /**
     * 次に狙う相手。撃つ価値のある相手がいなければ null。候補全部に優先度を書き込む
     * （{@link EnemyObservation#setTargetPriority}）——方針と記録がそれを読む。
     *
     * @param current   今の目標
     * @param preferred 戦術が撃ってほしい相手。無ければ null
     * @param range     撃ち合う距離の上限（ブロック）
     */
    @Nullable
    public static EnemyObservation select(GroundVehicleEntity self, List<EnemyObservation> enemies,
            @Nullable EnemyObservation current, @Nullable Entity preferred, Weights weights, TacticalProfile profile,
            VehicleRole role, DamageLedger ledger, TeamIntel intel, long now, double range) {
        EnemyObservation best = null;
        double bestScore = 0.0;
        // 空の相手は、空を撃てる車両（対空ミサイル・レーダー・主砲が機関砲、Roles.defendsAir）だけが狙い、そのときは
        // 先に狙う。戦車砲や対戦車ミサイル、ロケットで飛行機は落ちない。届く距離は自分の対空の射程まで（撃つ物が
        // 砲なら FireControl.AIR_GUN_REACH まで）。
        boolean airDefence = Roles.defendsAir(self.getStats());
        double airReach = WeaponReach.of(self).rangeAgainst(true);
        double airRange = Math.max(range, Roles.airMissile(self.getStats()) && self.getMissiles() > 0 ? airReach
                : Math.min(airReach, FireControl.AIR_GUN_REACH));

        for (EnemyObservation enemy : enemies) {
            enemy.setTargetPriority(0.0);

            if (!enemy.alive() || (enemy.flying() && !airDefence)) {
                continue;
            }

            Entity target = enemy.target();
            boolean kept = enemy == current && enemy.unseenFor(now) <= KEEP_UNSEEN;

            if (!enemy.visible() && !kept) {
                continue;
            }

            double distance = target.position().distanceTo(self.position());
            double reach = enemy.flying() ? airRange : range;

            if (distance > reach && !kept && !enemy.attackingMe()) {
                continue;
            }

            double score = weights.threat() * enemy.threatLevel()
                    + (enemy.attackingMe() ? weights.attackingMe() : 0.0)
                    + (enemy.attackingObjective() ? weights.objective() : 0.0)
                    + (enemy.attackingAlly() ? weights.ally() : 0.0)
                    + weights.proximity() * Math.max(0.0, 1.0 - distance / Math.max(reach, 1.0))
                    + weights.weak() * (1.0 - enemy.healthFraction())
                    + weights.effect() * enemy.effectiveness()
                    + (enemy.inWeaponRange() ? weights.inRange() : 0.0);

            if (ledger.ineffective(target.getId(), now)) {
                score -= weights.ineffective();
            }

            if (enemy == current) {
                score += weights.sticky();
            }

            int others = intel.engagedBy(target.getId(), self.getId(), now);

            if (others > 0) {
                score += weights.focus();
            }

            if (others > 2) {
                score -= weights.overkill() * (others - 2);
            }

            if (enemy.flying()) {
                score += weights.antiAir() * profile.antiAirBias() + weights.airDuty();
            }

            if (preferred != null && (target == preferred || target == preferred.getVehicle())) {
                score += weights.preferred();
            }

            score += switch (role) {
                // 砲兵は遠くの相手ほど自分の仕事。近い相手は前線の仕事。
                case ARTILLERY -> 0.2 * Math.min(distance / Math.max(range, 1.0), 1.0);
                case TANK -> enemy.armoured() ? 0.1 : 0.0;
                case IFV, APC, SCOUT -> enemy.armoured() ? 0.0 : 0.1;
                default -> 0.0;
            };

            enemy.setTargetPriority(Math.max(score, 0.0));

            if (score > bestScore) {
                bestScore = score;
                best = enemy;
            }
        }

        return best;
    }
}
