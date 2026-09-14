package com.ashvehicles.ai.perception;

import com.ashvehicles.entity.VehicleEntityBase;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * 敵1つがこちらにとってどれだけ危険か（0〜1）。
 *
 * <p><b>2段で出す。</b> まず「条件が揃ったらどれだけ速く壊されるか」（{@link #capability}）——相手の毎秒の威力と
 * こちらの残り耐久から、撃ち合いが続いたら何秒で壊れるかを見積もる。次にそれへ「今その条件が揃っているか」
 * （{@link #threat}）——距離、視線、砲がこちらを向いているか、実際に撃たれたか——を掛ける。
 *
 * <p><b>分からないことは安全ではない。</b> 視線を測れなかった相手（予算切れか、線の下にロードされていない土地が
 * ある）は、測れて遮られていた相手の2倍の重みで数える。
 *
 * <p>世界に触れない純粋な計算。視線と被弾は {@link Perception} が測って渡す。
 */
public final class ThreatModel {
    /**
     * 壊されるまでの見込み時間の基準（秒）。これと同じ時間で壊されるなら 0.5。
     *
     * <p>20秒は、戦車砲同士が数発撃ち合う長さ。5秒で壊される相手は 0.8、80秒掛かる相手は 0.2 になる。
     */
    private static final double KILL_SECONDS = 20.0;

    /** 砲がこちらを向いていると見なす角（度）。 */
    private static final double AIM_CONE = Math.cos(Math.toRadians(12.0));

    private ThreatModel() {
    }

    /**
     * 撃ち合いが続いたときに相手がこちらを壊す力（0〜1）。距離と視線はまだ掛けない。
     *
     * <p>歩いている人は装甲車両をほとんど壊せない（{@link WeaponReach#INFANTRY}）。
     */
    public static double capability(Entity attacker, WeaponReach reach, VehicleEntityBase victim) {
        if (attacker instanceof Player player && player.getVehicle() == null) {
            return victim.isArmoured() ? 0.05 : 0.2;
        }

        double dps = reach.dpsAgainst(victim.isArmoured(), victim.armour());

        if (dps <= 1.0E-6) {
            return 0.0;
        }

        double seconds = Math.max(victim.getHealth(), 1.0F) / dps;

        return KILL_SECONDS / (KILL_SECONDS + seconds);
    }

    /**
     * 今この瞬間の脅威。
     *
     * @param distance     相手までの距離（ブロック）
     * @param theirRange   相手の兵装がこちらに届く距離（ブロック）
     * @param seenBy       相手から見られている
     * @param lineKnown    視線を実際に測れた
     * @param aimingAtMe   相手の砲がこちらを向いている
     * @param attackingMe  最近この相手に撃たれた
     */
    public static double threat(double capability, double distance, double theirRange, boolean seenBy,
            boolean lineKnown, boolean aimingAtMe, boolean attackingMe) {
        if (capability <= 0.0 || theirRange <= 0.0) {
            return 0.0;
        }

        // 射程の外でもすぐには0にしない。射程の分だけ離れれば0で、その間は詰められれば届く距離だ。
        double range = distance <= theirRange ? 1.0
                : Math.max(0.0, 1.0 - (distance - theirRange) / Math.max(theirRange, 32.0));
        double sight = seenBy ? 1.0 : lineKnown ? 0.25 : 0.5;
        double aim = aimingAtMe ? 1.3 : 1.0;
        double hit = attackingMe ? 1.6 : 1.0;

        return Mth.clamp(capability * range * sight * aim * hit, 0.0, 1.0);
    }

    /** 相手の砲（生き物なら視線）が、こちらの視点を向いているか。 */
    public static boolean aimingAt(Entity attacker, Vec3 victimSight) {
        Vec3 facing = attacker instanceof VehicleEntityBase machine
                ? machine.getAimDirection(1.0F) : attacker.getViewVector(1.0F);
        Vec3 towards = victimSight.subtract(LineOfSight.sightOf(attacker));

        if (towards.lengthSqr() < 1.0E-4 || facing.lengthSqr() < 1.0E-4) {
            return false;
        }

        return facing.normalize().dot(towards.normalize()) >= AIM_CONE;
    }

    /**
     * 2つの脅威を合わせる。確率の和（どちらか一方でも当たる見込み）なので、足しても1を超えない。
     */
    public static double combine(double first, double second) {
        return 1.0 - (1.0 - first) * (1.0 - second);
    }
}
