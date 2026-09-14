package com.ashvehicles.weapon;

/**
 * 弾が装甲板を抜けたか、板に止められたか。
 *
 * <p>比べるのは2つの厚さだけ。弾が抜ける厚さ（弾側の {@code penetration}）と、弾が入った面の装甲厚（箱の
 * {@code plate}、{@code VehicleShape.Plate} 参照）。抜けた弾はファイルの威力をそのまま渡し、止められた弾は
 * {@link #HELD} 倍を渡す。板の裏まで届かなかった弾も、衝撃と内側の剥離で中を痛めつける。
 *
 * <p><b>跳弾とは別の問いで、順番がある。</b> 先に {@link Ricochet} が「そもそも板に入ったか」を角度で
 * 決め、入った弾にだけここが「抜けたか」を厚さで決める。弾かれた弾は威力を渡さないので、ここへは来ない。
 *
 * <p>意図的に掛けていない物が2つある。
 * <ul>
 * <li><b>傾斜。</b> 見かけの厚さ（板厚 ÷ cos 入射角）にはしない。角度は跳弾が幾何から既に測っており、
 *     同じ角度を2度数えると傾いた板が二重に得をする。ファイルに書くのも板の厚さそのもの</li>
 * <li><b>距離。</b> 貫通力は飛んだ距離で落とさない。威力の方は {@code Projectile.energyAfter} で既に
 *     減っているので、遠くの当たりは抜けても軽い</li>
 * </ul>
 */
public final class Penetration {
    /** 板に止められた弾が渡す威力の割合。 */
    public static final float HELD = 0.5F;

    private Penetration() {
    }

    /**
     * その厚さの板を抜けるか。
     *
     * <p>厚さ 0 以下は装甲が無い面で、貫通の数値を持たない弾でも抜ける。だから {@code plate} を1つも書いて
     * いない機体では、どの弾も従来通り全部の威力を渡す。
     */
    public static boolean pierces(WeaponDefinition.Projectile round, float thickness) {
        return thickness <= 0.0F || round.penetration() >= thickness;
    }
}
