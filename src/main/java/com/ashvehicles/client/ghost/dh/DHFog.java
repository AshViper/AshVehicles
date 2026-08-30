package com.ashvehicles.client.ghost.dh;

import net.minecraft.util.Mth;

/**
 * Distant Horizons の遠方霧の、1フレーム分の写し。
 *
 * <p>DH は自分の地形にしか霧を掛けない——{@code fog.frag} は自前の深度バッファを読み、LOD の無い画素を
 * 素通しする——ので、ゴーストは霧の壁の中でも素の色のまま浮かんでいた。1km 先の空気に溶けた山並みの上を、
 * くっきりした機影が滑るのは、そこだけ空気が無いように見える。だからゴーストパスは DH と同じ式で同じ濃さを
 * 求め、その分だけゴーストを透明へ寄せる。背景は既に霧の色をしているのだから、透けさせることが「霧の色へ
 * 混ぜる」ことと同じ画になる。
 *
 * <p>式は DH の {@code shaders/fog/gl/fog.frag} の遠方霧そのもの。距離は<em>水平</em>で測り（DH の霧は
 * 既定で円筒形）、描画半径で割って正規化し、start からの残りを falloff（線形・指数・指数二乗）で濃さへ
 * 変える。高さ霧は写していない——既定の混合モード（BASIC）では遠方霧しか使われないから。
 *
 * <p>これは DH の型を一切持たない純粋な計算で、読むのは {@link DHIntegration#fog()} が毎フレーム1度
 * 作った写しだけ。DH の設定がフレーム中に変わっても、そのフレームのゴーストは全員同じ空気の中にいる。
 *
 * @param radius DH が地形を描く半径（ブロック）。距離をこの値で正規化する
 * @param falloff {@code EDhApiFogFalloff.value}: 0=線形, 1=指数, 2=指数二乗
 * @param start 霧が始まる正規化距離
 * @param length 霧が {@code start} から満ちるまでの正規化距離
 * @param min 霧の濃さの下限
 * @param range 下限から上限までの幅
 * @param density 指数系 falloff の勾配
 */
public record DHFog(double radius, int falloff, float start, float length,
        float min, float range, float density) {

    /** これ以上の濃さは「見えない」と扱ってよい値。描く側の打ち切り用。 */
    public static final float OPAQUE = 0.995F;

    /**
     * カメラからこれだけ離れた点の霧の濃さ。0が素通し、1が霧の色そのもの。
     *
     * @param dx カメラからの水平距離のX成分（ブロック）
     * @param dz 同Z成分
     */
    public float thickness(double dx, double dz) {
        if (this.radius <= 0.0) {
            return 0.0F;
        }

        double distance = Math.sqrt(dx * dx + dz * dz) / this.radius;
        // start==end の設定は0除算ではなく段差として扱う。
        double along = (distance - this.start) / Math.max(this.length, 1.0E-4);
        double thickness;

        if (this.falloff == 1) {
            double x = Math.max(along, 0.0) * this.density;

            thickness = this.min + this.range - this.range / Math.exp(x);
        } else if (this.falloff == 2) {
            double x = Math.max(along, 0.0) * this.density;

            thickness = this.min + this.range - this.range / Math.exp(x * x);
        } else {
            thickness = this.min + this.range * Mth.clamp(along, 0.0, 1.0);
        }

        return (float) Mth.clamp(thickness, 0.0, 1.0);
    }
}
