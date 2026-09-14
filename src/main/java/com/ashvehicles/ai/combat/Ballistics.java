package com.ashvehicles.ai.combat;

import javax.annotation.Nullable;

import com.ashvehicles.weapon.WeaponDefinition;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * AI の照準計算。<b>撃つ前に弾道を解く。</b>
 *
 * <p>近似式は書かない。弾が実際に受ける物——空気（{@code drag × 速さ²}）と重力——を1tickずつそのまま
 * 適用して飛ばし、目標の距離に届いたときの高さを読む。落ちた分だけ狙いを上げ、3回繰り返す。パイロットの
 * 照準器が同じことをしている（{@code client/GunSight.fly}）ので、AI と人は同じ弾道を見ていることになる。
 *
 * <p><b>単純な式で済ませなかった理由。</b> {@code 0.5 g t²} と {@code t = 距離 ÷ 初速} は、抗力の無い
 * 世界でしか合わない。この MOD の {@code drag} は実在の量（{@code ρ·Cd·A/2m}）であり
 * （[[ballistic-drop-is-drag-not-gravity]]）、120mm の平射ではほぼ効かない一方、120 m/s の榴弾砲では
 * 飛行時間が2割以上伸びる。同じ1つの式で両方を当てるには、弾が本当に飛ぶ通りに飛ばすしかない。
 *
 * <p><b>世界には一切触れない。</b> 純粋な計算で、ブロックもエンティティも読まない。40両が毎tick呼んでも
 * 値段は足し算の回数だけだ。遮蔽の判定は撃つ側の仕事（{@code perception/LineOfSight}）。
 */
public final class Ballistics {
    /** 狙いを上げ直す回数。3回で、この MOD の射程では収束する。 */
    private static final int PASSES = 3;

    /** 飛翔の打ち切り（tick）。榴弾砲の最大射程でも足りる長さ。 */
    private static final int MOST_TICKS = 400;

    /** これより近ければ弾道を解かない。砲口の目の前は真っ直ぐだ。 */
    private static final double CLOSE = 4.0;

    private Ballistics() {
    }

    /** 飛翔の結果。目標の距離に届いたか、そのときの高さと、掛かった tick 数。 */
    private record Flight(boolean reached, double height, double ticks) {
    }

    /**
     * その目標に当てるために砲を置く一点。届かなければ null。
     *
     * <p>返すのは<b>狙い点</b>であって角度ではない。砲塔に渡すのが点なので（{@code GroundVehicleEntity.aimAt}）、
     * 機械の限界を掛けるのは砲塔の仕事のまま——この計算は「どこを狙えば当たるか」だけに答える。
     *
     * @param muzzle 砲口。弾が実際に生まれる場所
     * @param target 目標の今の位置（当てたい点）
     * @param drift  目標の速度（ブロック/tick）。止まっている物には {@link Vec3#ZERO}
     */
    @Nullable
    public static Vec3 aim(Vec3 muzzle, Vec3 target, Vec3 drift, WeaponDefinition.Projectile round) {
        return aim(muzzle, target, drift, round, round.speed());
    }

    /**
     * 同じ物を、弾が出ていく速さを指定して。航空機の砲の弾は機首方向の機体の速度を足して出る
     * （{@code WeaponMounts.fireRound}）ので、砲口初速だけで解くと飛行時間を長く見積もり、見越しが外へずれる。
     *
     * @param speed 弾が出ていく速さ（ブロック/tick）
     */
    @Nullable
    public static Vec3 aim(Vec3 muzzle, Vec3 target, Vec3 drift, WeaponDefinition.Projectile round, double speed) {
        if (speed <= 1.0E-3) {
            return null;
        }

        if (muzzle.distanceToSqr(target) < CLOSE * CLOSE) {
            return target;
        }

        Vec3 predicted = target;
        Vec3 aim = target;

        for (int pass = 0; pass < PASSES; pass++) {
            Vec3 line = aim.subtract(muzzle);

            if (line.lengthSqr() < 1.0E-6) {
                return null;
            }

            Flight flight = fly(muzzle, line.normalize().scale(speed), round,
                    predicted.subtract(muzzle).horizontalDistance());

            if (!flight.reached()) {
                // 届かない。仰角を上げれば届く場合もあるが、そこは砲塔の限界と弾の寿命が決める話で、
                // 撃たない方を選ぶ——当たらない砲弾を撃ち続ける戦車は、弾が尽きた戦車になる。
                return null;
            }

            // 目標はその時間だけ動く。撃つ場所が変われば飛行時間も変わるので、次の周回で測り直す。
            predicted = target.add(drift.scale(flight.ticks()));
            // 落ちた分を上げる。狙い点の水平位置は見越した先、高さは「弾が来た高さ」との差で直す。
            aim = new Vec3(predicted.x, aim.y + (predicted.y - flight.height()), predicted.z);
        }

        return aim;
    }

    /**
     * 弾を飛ばして、水平距離 {@code flat} に届いたときの高さを読む。
     *
     * <p>順序は弾自身と同じ——モーター、移動、抗力、重力（{@code VehicleProjectile.fly} と
     * {@code GunSight.fly}）。<b>届いた瞬間は tick の途中にある</b>ので、跨いだ2点の間を補間する。
     */
    private static Flight fly(Vec3 muzzle, Vec3 launch, WeaponDefinition.Projectile round, double flat) {
        Vec3 at = muzzle;
        Vec3 velocity = launch;
        Vec3 nose = launch.normalize();
        double topSpeed = round.topSpeed() > 0.0F ? round.topSpeed() : Double.MAX_VALUE;
        int life = Math.min(MOST_TICKS, round.lifetime());
        double flown = 0.0;

        for (int age = 1; age <= life; age++) {
            if (round.hasMotor() && age <= round.burnTicks()) {
                double pushed = Math.min(velocity.length() + thrustAt(round, age), topSpeed);

                velocity = nose.scale(pushed);
            }

            Vec3 next = at.add(velocity);
            double reached = flown + velocity.horizontalDistance();

            if (reached >= flat) {
                double part = reached - flown < 1.0E-6 ? 0.0 : (flat - flown) / (reached - flown);

                return new Flight(true, Mth.lerp(part, at.y, next.y), age - 1 + part);
            }

            flown = reached;
            at = next;
            // 弾が受ける物をそのまま受ける。モーターを持つ物の抗力は燃焼中には掛からない。
            velocity = round.hasMotor() ? velocity : round.slowedByAir(velocity);
            velocity = velocity.subtract(0.0, round.gravity(), 0.0);
        }

        return new Flight(false, at.y, life);
    }

    /** この齢でモーターが出している推力。立ち上がりは弾自身と同じ形。 */
    private static float thrustAt(WeaponDefinition.Projectile round, int age) {
        int spool = round.spoolTicks();

        return spool <= 0 ? round.thrust()
                : round.thrust() * Mth.clamp((age + 1) / (float) spool, 0.0F, 1.0F);
    }
}
