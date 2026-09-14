package com.ashvehicles.ai.air;

import javax.annotation.Nullable;

import com.ashvehicles.weapon.WeaponDefinition;

import net.minecraft.world.phys.Vec3;

/**
 * 爆弾をどこで放すか。
 *
 * <p><b>近似式ではなく、爆弾が実際に受ける物を1 tick ずつ当てて落とす</b>——移動、抗力、重力の順
 * （{@code VehicleProjectile.fly} と {@code combat/Ballistics} と同じ）。{@code drag} は実在の量なので
 * （[[ballistic-drop-is-drag-not-gravity]]）、高度150から落とす爆弾でも抗力を無視した放物線とは着弾が数十ブロック
 * ずれる。世界には触れない純粋な計算。
 */
public final class Delivery {
    /** 落とす打ち切り（tick）。高度600から落としても足りる長さ。 */
    private static final int MOST_TICKS = 1200;

    private Delivery() {
    }

    /** 落ちる点と、そこまでの tick。 */
    public record Impact(Vec3 at, double ticks) {
    }

    /**
     * 目標に対する外れ。
     *
     * @param along  進む向きに、落ちる点が目標のどれだけ手前か（ブロック）。負なら奥へ越えている
     * @param across 進む向きに対して横へどれだけ外れたか（ブロック、符号は左右）
     */
    public record Miss(double along, double across) {
    }

    /** ラックから放された爆弾の初速。機体の速度に、下へ押し出される分を足した物（{@code WeaponMounts.fireRound}）。 */
    public static Vec3 release(Vec3 velocity, Vec3 up, WeaponDefinition.Projectile round) {
        return velocity.add(up.scale(-round.speed()));
    }

    /** {@code from} から初速 {@code velocity} で放した爆弾が、高さ {@code groundY} に届く点。届かなければ null。 */
    @Nullable
    public static Impact drop(Vec3 from, Vec3 velocity, WeaponDefinition.Projectile round, double groundY) {
        Vec3 at = from;
        Vec3 motion = velocity;

        for (int age = 1; age <= MOST_TICKS; age++) {
            Vec3 next = at.add(motion);

            if (next.y <= groundY) {
                double fall = at.y - next.y;
                double part = fall < 1.0E-9 ? 0.0 : (at.y - groundY) / fall;

                return new Impact(at.add(motion.scale(part)), age - 1 + part);
            }

            at = next;
            motion = round.slowedByAir(motion).subtract(0.0, round.gravity(), 0.0);
        }

        return null;
    }

    /** 落ちる点 {@code impact} の、目標 {@code target} に対する外れ。進む向きは {@code track} の水平成分。 */
    public static Miss miss(Vec3 impact, Vec3 target, Vec3 track) {
        double dx = target.x - impact.x;
        double dz = target.z - impact.z;
        double flat = Math.sqrt(track.x * track.x + track.z * track.z);

        if (flat < 1.0E-6) {
            return new Miss(Math.sqrt(dx * dx + dz * dz), 0.0);
        }

        double ux = track.x / flat;
        double uz = track.z / flat;

        return new Miss(dx * ux + dz * uz, dz * ux - dx * uz);
    }
}
