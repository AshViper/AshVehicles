package com.ashvehicles.vehicle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 断面で見た車体の転がり。1軸ぶん（横転ならロール、前転ならピッチ）。
 *
 * <p>車体を断面の多角形——当たり判定の箱を断面へ投影した凸包（{@link Section}）——として扱い、地面に着いて
 * いる一番低い頂点を支点に回す。重心に掛かる力（重力の斜面方向の成分、旋回の遠心力、爆風）が支点のまわりに
 * 作るモーメントが、そのまま角加速度になる。だから転倒の境目は数字で選ぶ物ではなく形から出る: 合力の線が
 * 支点の外へ出たとき。長方形なら静的安定係数 {@code g·a/b} と同じ式になる。
 *
 * <p>凸包の別の頂点が地面に届くたび（辺が地面に着くたび）エネルギーの一部を失い、支点が次の頂点へ移る。
 * 勢いが残っていれば転がり続け、残っていなければその辺で止まる。面は 90 度ごとにあるのではなく、箱の形が
 * 作る辺ごとにある: 逆さまの戦車は車体の天面ではなく砲塔の角と車体の肩が作る辺に乗り、傾いて止まる。
 * 張り出した箱（側面の砲塔、牽引砲の脚）はそのまま凸包の頂点になって支えに入る。
 *
 * <p>以前は代表寸法の長方形（半幅 a・重心高 b）だった。逆さまの戦車が包絡の天面でベタ着きになり、張り出しが
 * 支えにならなかったので、箱から凸包を取る形にした。長方形の凸包を渡せば以前と同じに動く。
 *
 * <p>単位はブロックと tick。角度はラジアンで、正は断面の +x 側（右、または前）が持ち上がる向き。
 */
public final class Tumble {
    /** 面が地面を打ったときに残る角速度の割合。正方形の転がりの理論値（約 0.5）より少し鈍い。 */
    private static final double LANDING_KEEP = 0.4;
    /** これより遅く面に着いた物はそこで止まる。 */
    private static final double SETTLE_RATE = 0.012;
    private static final double FLAT = 1.0E-4;
    /** 同じ高さと見なす頂点の差（ブロック）。辺が地面に着いているかの判定。 */
    private static final double TOUCH = 1.0E-3;
    /** 支点まわりの回転の減衰。地面と車体のこすれ。 */
    private static final double SCRUB = 0.97;
    /** 静止から倒れ始めるのに要る、重力の復元に対する余り（割合）。 */
    private static final double START_MARGIN = 0.05;

    private double angle;
    private double rate;
    /** 最後の接地 tick で、辺（2頂点以上）が地面に着いていたか。 */
    private boolean resting = true;
    /** 最後に辺が着いたときの角度。次に着くまでに回った角度が、その着地で失う勢いを決める。 */
    private double landedAt;

    public double angle() {
        return this.angle;
    }

    public double rate() {
        return this.rate;
    }

    public void set(double angle, double rate) {
        this.angle = wrap(angle);
        this.rate = rate;
        this.resting = rate == 0.0;
        this.landedAt = this.angle;
    }

    /** 角速度を足す。爆風や衝突の一撃。 */
    public void kick(double rate) {
        this.rate += rate;
    }

    /** 角速度を割合で削る。車体が履帯ではなく側面や屋根で地面を擦っているとき。 */
    public void damp(double keep) {
        this.rate *= keep;
    }

    /** 辺で地面に着いて止まっているか（向きは問わない。横倒しでも屋根でも）。 */
    public boolean atRest() {
        return this.rate == 0.0 && this.resting;
    }

    /** 立っている向き（角度 0）で止まっているか。 */
    public boolean upright() {
        return this.rate == 0.0 && Math.abs(this.angle) < FLAT;
    }

    /**
     * 接地している1tick。
     *
     * @param section 断面の凸包（重心から測った頂点）
     * @param lateral 重心に掛かる断面の +x 方向の加速度（ブロック/tick²）
     * @param normal  地面へ押し付ける加速度。重力の地面に垂直な成分
     * @return 支点が地面に留まるよう重心が断面の x 方向へ動いた量
     */
    public double step(Section section, double lateral, double normal) {
        double[][] points = section.points;

        if (points.length < 2) {
            return 0.0;
        }

        double[] pivot = this.pivot(section, lateral, normal);

        if (pivot == null) {
            this.rate = 0.0;

            return 0.0;
        }

        double before = rotatedX(pivot, this.angle);
        double px = before;
        double py = rotatedY(pivot, this.angle);
        // 重心（原点）に掛かる力 (lateral, -normal) の、支点まわりのモーメント。反時計回りが正。
        double torque = -(px * -normal - py * lateral);
        // 支点まわりの慣性: 重心まわりの回転半径の2乗に、支点までの距離の2乗を足す（平行軸の定理）。
        double k2 = section.gyration + pivot[0] * pivot[0] + pivot[1] * pivot[1];

        this.rate = (this.rate + torque / k2) * SCRUB;

        // 別の頂点が地面（支点の高さ）へ届くまでの角度。届けば辺が着いた。
        double landing = this.landing(points, pivot, Math.abs(this.rate));

        if (!Double.isNaN(landing)) {
            // 辺が着いた。回る向きはそのまま、勢いだけを失う——残っていれば次の頂点を越えて転がり続ける。
            this.angle += Math.signum(this.rate) * landing;
            // 失う勢いは、前の辺から今の辺までに回った角度（頂点の外角）で決まる。90 度の角を越えた着地で
            // LANDING_KEEP、浅い角ほど失わない。凸包は箱ごとに細かい辺を持つので、毎回一律に 6 割失わせると
            // 転がりが途中の小さな辺で失速して止まり、物理に逆らって見えた。
            double turned = Math.min(Math.abs(this.angle - this.landedAt), Math.PI / 2.0);
            this.rate *= 1.0 - (1.0 - LANDING_KEEP) * (1.0 - Math.cos(turned));
            this.landedAt = this.angle;
            this.resting = true;

            if (Math.abs(this.rate) < SETTLE_RATE) {
                this.rate = 0.0;
            }
        } else {
            this.angle += this.rate;
            this.resting = false;
        }

        double moved = before - rotatedX(pivot, this.angle);
        this.angle = wrap(this.angle);

        return moved;
    }

    /** 宙にある1tick。何も触れていないので角速度はそのまま。 */
    public void drift() {
        this.angle = wrap(this.angle + this.rate);
        this.resting = false;
    }

    /** 重心が地面からどれだけ上にあるか。一番低い頂点の深さ。 */
    public double lift(Section section) {
        return section.lift(this.angle);
    }

    /**
     * 支点 {@code pivot} のまわりに {@code rate} の向きへ回したとき、別の頂点が支点の高さへ下りてくるまでの
     * 角度（正）。{@code reach} より先なら NaN。
     */
    private double landing(double[][] points, double[] pivot, double reach) {
        if (reach <= 0.0) {
            return Double.NaN;
        }

        double dir = Math.signum(this.rate);
        double best = Double.NaN;

        for (double[] q : points) {
            double rx = q[0] - pivot[0];
            double ry = q[1] - pivot[1];
            double r = Math.hypot(rx, ry);

            if (r < TOUCH) {
                continue;
            }

            // 支点から見た q の高さは r·sin(φ + α)。0 を上から下へ跨ぐ φ を探す。
            double alpha = Math.atan2(ry, rx);
            double delta = ((-alpha - this.angle) * dir) % Math.PI;

            if (delta < 0.0) {
                delta += Math.PI;
            }

            // 跨ぐ向きは π ごとに入れ替わる。下へ向かって跨ぐ方を取る。
            if (Math.cos(this.angle + dir * delta + alpha) * dir > 0.0) {
                delta += Math.PI;
            }

            if (delta <= reach && (Double.isNaN(best) || delta < best)) {
                best = delta;
            }
        }

        return best;
    }

    /**
     * 今回す支点の頂点。辺で静止していて、押す力がどちらの端も越えさせないなら null（動かない）。
     */
    private double[] pivot(Section section, double lateral, double normal) {
        double[][] points = section.points;
        double lowest = Double.MAX_VALUE;

        for (double[] p : points) {
            lowest = Math.min(lowest, rotatedY(p, this.angle));
        }

        double[] left = null;
        double[] right = null;
        int touching = 0;

        for (double[] p : points) {
            if (rotatedY(p, this.angle) > lowest + TOUCH) {
                continue;
            }

            touching++;
            double x = rotatedX(p, this.angle);

            if (left == null || x < rotatedX(left, this.angle)) {
                left = p;
            }

            if (right == null || x > rotatedX(right, this.angle)) {
                right = p;
            }
        }

        if (touching < 2) {
            return left;
        }

        // 辺が下。反時計回りに回すときは x の小さい方（左）の端が、時計回りなら大きい方が支点。
        if (this.rate > 0.0) {
            return left;
        }

        if (this.rate < 0.0) {
            return right;
        }

        // 止まっている。どちらかの端のまわりで、力が車体を持ち上げる向きのモーメントを作るときだけ動き出す。
        double leftTorque = -(rotatedX(left, this.angle) * -normal - rotatedY(left, this.angle) * lateral);
        double rightTorque = -(rotatedX(right, this.angle) * -normal - rotatedY(right, this.angle) * lateral);

        // 動き出すには、支点の外へ出た合力が重力の復元の一部を超えなければならない。理屈では0で倒れるが、
        // 地面は1ブロック単位で読まれるので、境目ぎりぎりの斜面では読み値の跳ねだけで揺すられ続けた。
        double margin = START_MARGIN * normal * section.reach;

        if (leftTorque > margin) {
            return left;
        }

        if (rightTorque < -margin) {
            return right;
        }

        return null;
    }

    public static double rotatedX(double[] p, double angle) {
        return p[0] * Math.cos(angle) - p[1] * Math.sin(angle);
    }

    public static double rotatedY(double[] p, double angle) {
        return p[0] * Math.sin(angle) + p[1] * Math.cos(angle);
    }

    private static double wrap(double angle) {
        while (angle > Math.PI + FLAT) {
            angle -= 2.0 * Math.PI;
        }

        while (angle < -Math.PI - FLAT) {
            angle += 2.0 * Math.PI;
        }

        return angle;
    }

    /**
     * 断面の形。重心から測った凸包の頂点と、車体の原点（底面の基準点）の位置。
     *
     * <p>定義を読んだときに1度だけ作る。転がりは毎 tick この頂点を回すだけで、箱を走査しない。
     *
     * @param points    凸包の頂点 {x, y}。重心が原点
     * @param origin    車体の原点の、重心に対する位置 {x, y}
     * @param gyration  重心まわりの回転半径の2乗（外接長方形の一様な板として）
     * @param reach     重心から一番遠い頂点まで。動き出しの余りの尺度
     */
    public record Section(double[][] points, double[] origin, double gyration, double reach) {
        /**
         * 断面へ投影した点（車体の座標）と重心から作る。
         *
         * @param raw    投影した箱の角 {x, y}。車体の座標（原点が底面の基準点）
         * @param centre 重心 {x, y}。同じ座標
         */
        public static Section of(List<double[]> raw, double[] centre) {
            List<double[]> shifted = new ArrayList<>(raw.size());

            for (double[] p : raw) {
                shifted.add(new double[] {p[0] - centre[0], p[1] - centre[1]});
            }

            double[][] hull = hull(shifted);
            double minX = 0.0, maxX = 0.0, minY = 0.0, maxY = 0.0, reach = 0.0;

            for (double[] p : hull) {
                minX = Math.min(minX, p[0]);
                maxX = Math.max(maxX, p[0]);
                minY = Math.min(minY, p[1]);
                maxY = Math.max(maxY, p[1]);
                reach = Math.max(reach, Math.hypot(p[0], p[1]));
            }

            double w = (maxX - minX) * 0.5;
            double h = (maxY - minY) * 0.5;

            return new Section(hull, new double[] {-centre[0], -centre[1]}, (w * w + h * h) / 3.0,
                    Math.max(reach, 0.25));
        }

        /** 半幅 a・重心から底面まで b の長方形。箱を持たない車両の代役。 */
        public static Section rectangle(double a, double b) {
            return of(List.of(new double[] {-a, 0.0}, new double[] {a, 0.0}, new double[] {-a, 2.0 * b},
                    new double[] {a, 2.0 * b}), new double[] {0.0, b});
        }

        /** 角度 {@code angle} のとき、重心が一番低い頂点からどれだけ上にあるか。 */
        public double lift(double angle) {
            double lowest = Double.MAX_VALUE;

            for (double[] p : this.points) {
                lowest = Math.min(lowest, rotatedY(p, angle));
            }

            return -lowest;
        }

        /** 重心の、一番低い頂点からの高さ（立っているとき）。 */
        public double height() {
            return this.lift(0.0);
        }

        /** 凸包（反時計回り）。Andrew の単調鎖。 */
        private static double[][] hull(List<double[]> input) {
            double[][] p = input.toArray(new double[0][]);
            Arrays.sort(p, (u, v) -> u[0] != v[0] ? Double.compare(u[0], v[0]) : Double.compare(u[1], v[1]));

            if (p.length < 3) {
                return p;
            }

            double[][] out = new double[p.length * 2][];
            int k = 0;

            for (double[] q : p) {
                while (k >= 2 && cross(out[k - 2], out[k - 1], q) <= 0.0) {
                    k--;
                }

                out[k++] = q;
            }

            for (int i = p.length - 2, low = k + 1; i >= 0; i--) {
                while (k >= low && cross(out[k - 2], out[k - 1], p[i]) <= 0.0) {
                    k--;
                }

                out[k++] = p[i];
            }

            return Arrays.copyOf(out, Math.max(k - 1, 1));
        }

        private static double cross(double[] o, double[] a, double[] b) {
            return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
        }
    }
}
