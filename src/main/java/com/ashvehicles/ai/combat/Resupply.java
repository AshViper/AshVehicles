package com.ashvehicles.ai.combat;

import com.ashvehicles.ai.control.GroundVehicleController;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.match.Bots;

/**
 * 弾切れと燃料切れの面倒を見る。「装填」は車両自身が弾倉から勝手に進めるので、AI が面倒を見るのはその先——
 * 弾倉そのものが空になったとき。
 *
 * <p><b>AI には弾薬箱と燃料缶を運んでくる者がいない。</b> 人の乗る車両は車外の誰かが補給する物で
 * （{@code GroundVehicleEntity.interact}）、AI にそれを頼める相手はいない。
 *
 * <p><b>撃つ物が全部尽きたら、自爆して出直す</b>（2026-09-13 の指示「搭載している武装がなくなったら自滅して
 * リスポーン」）。以前はその場で自分に弾を満たしていたが、それは撃つ相手のいない間だけで、撃ち合いの最中に弾の尽きた
 * 車両は置物のまま戦場に残った。待つのは撃った弾が着くまでの {@value #SCUTTLE_AFTER} tick だけ。消し方は
 * {@link Bots#scuttle}——撃破ではないのでチケットは動かない。
 *
 * <p>燃料は今まで通り自分で満たす。撃つ相手がいない間だけ——20分の試合を走り切れずに戦場の真ん中で止まる戦車は、
 * 撃破されたのではなく忘れられただけの障害物になる。元の {@code GroundPilot.tickSupply}。
 */
public final class Resupply {
    /** 燃料が減ってから自分で満たすまで（tick）。目標を持っている間は数えない。 */
    private static final int AFTER = 200;

    /** 撃つ物が尽きてから自爆するまで（tick）。撃った砲弾とロケットが、撃った車両のいるうちに着くように。 */
    private static final int SCUTTLE_AFTER = 200;

    /** これを切ったら燃料を満たす（割合）。 */
    private static final float THIRSTY = 0.15F;

    private int dry;
    private int spent;

    public void tick(GroundVehicleEntity vehicle, GroundVehicleController control, boolean engaged) {
        boolean empty = vehicle.getRounds() <= 0 && vehicle.getCoaxRounds() <= 0 && vehicle.getMissiles() <= 0;

        if (empty) {
            if (++this.spent >= SCUTTLE_AFTER) {
                Bots.scuttle(vehicle);
            }

            return;
        }

        this.spent = 0;

        if (vehicle.getFuelFraction() >= THIRSTY || engaged) {
            this.dry = 0;

            return;
        }

        if (++this.dry >= AFTER) {
            this.dry = 0;
            control.rearm();
        }
    }

    /** 残弾の割合（0〜1）。3つの架台のうち一番残っている物。方針が「弾が尽きた」を訊くため。 */
    public static double ammoFraction(GroundVehicleEntity vehicle) {
        double best = 0.0;

        if (vehicle.getStats().armament().exists() && vehicle.getRoundCapacity() > 0) {
            best = Math.max(best, (double) vehicle.getRounds() / vehicle.getRoundCapacity());
        }

        if (vehicle.hasCoaxial() && vehicle.getCoaxCapacity() > 0) {
            best = Math.max(best, (double) vehicle.getCoaxRounds() / vehicle.getCoaxCapacity());
        }

        if (vehicle.hasMissiles() && vehicle.getMissileCapacity() > 0) {
            best = Math.max(best, (double) vehicle.getMissiles() / vehicle.getMissileCapacity());
        }

        return Math.min(best, 1.0);
    }
}
