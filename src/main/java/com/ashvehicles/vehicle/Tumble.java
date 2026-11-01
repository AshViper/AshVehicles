package com.ashvehicles.vehicle;

/**
 * 断面で見た車体の転がり。1軸ぶん（横転ならロール、前転ならピッチ）。
 *
 * <p>車体を断面の長方形——半幅 {@code a}、重心から底面まで {@code b}——として扱い、地面に着いている一番
 * 低い角を支点に回す。重心に掛かる力（重力の斜面方向の成分、旋回の遠心力、爆風）が支点のまわりに作る
 * モーメントが、そのまま角加速度になる。だから転倒の境目は数字で選ぶ物ではなく形から出る:
 * 横向きの加速度が {@code g·a/b} を超えたとき、つまり合力の線が支点の外へ出たとき。静的安定係数と同じ式。
 *
 * <p>面が地面に着くたび（角度が 90 度の倍数を跨ぐたび）エネルギーの一部を失い、支点が次の角へ移る。
 * 勢いが残っていれば側面から屋根へと転がり続け、残っていなければその面で止まる。側面で止まった車体は
 * 側面の長方形（半幅 {@code b}、高さ {@code a}）として次の力に答えるので、横倒しの戦車は立っている戦車より
 * ずっと転がりやすい——形の通りに。
 *
 * <p>単位はブロックと tick。角度はラジアンで、正は断面の +x 側（右、または前）が持ち上がる向き。
 */
public final class Tumble {
    private static final double FACE = Math.PI / 2.0;
    /** 面が地面を打ったときに残る角速度の割合。正方形の転がりの理論値（約 0.5）より少し鈍い。 */
    private static final double LANDING_KEEP = 0.4;
    /** これより遅く面に着いた物はそこで止まる。 */
    private static final double SETTLE_RATE = 0.012;
    private static final double FLAT = 1.0E-4;
    /** 支点まわりの回転の減衰。地面と車体のこすれ。 */
    private static final double SCRUB = 0.97;
    /** 静止から倒れ始めるのに要る、重力の復元に対する余り（割合）。 */
    private static final double START_MARGIN = 0.05;

    private double angle;
    private double rate;

    public double angle() {
        return this.angle;
    }

    public double rate() {
        return this.rate;
    }

    public void set(double angle, double rate) {
        this.angle = wrap(angle);
        this.rate = rate;
    }

    /** 角速度を足す。爆風や衝突の一撃。 */
    public void kick(double rate) {
        this.rate += rate;
    }

    /** 平らに着いて止まっているか（向きは問わない。横倒しでも屋根でも）。 */
    public boolean atRest() {
        return this.rate == 0.0 && onFace(this.angle);
    }

    /** 立っている向き（角度 0）で止まっているか。 */
    public boolean upright() {
        return this.rate == 0.0 && Math.abs(this.angle) < FLAT;
    }

    /**
     * 接地している1tick。
     *
     * @param a       断面の半幅（角度 0 のときの水平の半分）
     * @param b       重心から底面までの高さ（角度 0 のときの鉛直の半分）
     * @param lateral 重心に掛かる断面の +x 方向の加速度（ブロック/tick²）
     * @param normal  地面へ押し付ける加速度。重力の地面に垂直な成分
     * @return 支点が地面に留まるよう重心が断面の x 方向へ動いた量
     */
    public double step(double a, double b, double lateral, double normal) {
        if (a <= 0.0 || b <= 0.0) {
            return 0.0;
        }

        double[] pivot = this.pivot(a, b, lateral, normal);

        if (pivot == null) {
            this.rate = 0.0;
            this.angle = snap(this.angle);

            return 0.0;
        }

        double before = rotatedX(pivot, this.angle);
        double k2 = 4.0 / 3.0 * (a * a + b * b);
        double px = rotatedX(pivot, this.angle);
        double py = rotatedY(pivot, this.angle);
        // 重心（原点）に掛かる力 (lateral, -normal) の、支点まわりのモーメント。反時計回りが正。
        double torque = -(px * -normal - py * lateral);

        this.rate = (this.rate + torque / k2) * SCRUB;

        double lo = Math.floor(this.angle / FACE + FLAT) * FACE;
        double hi = lo + FACE;
        // 今いる面の上にちょうど居るなら、回る向きで区間を選ぶ。
        if (onFace(this.angle) && this.rate < 0.0) {
            hi = lo;
            lo = hi - FACE;
        }

        double next = this.angle + this.rate;

        if (next >= hi - FLAT || next <= lo + FLAT) {
            // 面が着いた。回る向きはそのまま、勢いだけを失う——残っていれば次の角を越えて転がり続ける。
            this.angle = next >= hi - FLAT ? hi : lo;
            this.rate *= LANDING_KEEP;

            if (Math.abs(this.rate) < SETTLE_RATE) {
                this.rate = 0.0;
            }
        } else {
            this.angle = next;
        }

        double moved = before - rotatedX(pivot, this.angle);
        this.angle = wrap(this.angle);

        return moved;
    }

    /** 宙にある1tick。何も触れていないので角速度はそのまま。 */
    public void drift() {
        this.angle = wrap(this.angle + this.rate);
    }

    /** 重心が地面からどれだけ上にあるか。一番低い角の深さ。 */
    public double lift(double a, double b) {
        double lowest = Double.MAX_VALUE;

        for (int i = 0; i < 4; i++) {
            lowest = Math.min(lowest, rotatedY(corner(i, a, b), this.angle));
        }

        return -lowest;
    }

    /**
     * 車体の原点（底面の中央）の、重心に対する位置。{x, y}。立っていれば {0, -b}。
     */
    public double[] origin(double b) {
        return new double[] {b * Math.sin(this.angle), -b * Math.cos(this.angle)};
    }

    /**
     * 今回す支点の角。面で静止していて、押す力がどちらの角も越えさせないなら null（動かない）。
     */
    private double[] pivot(double a, double b, double lateral, double normal) {
        double[] low = null;
        double[] other = null;
        double lowest = Double.MAX_VALUE;

        for (int i = 0; i < 4; i++) {
            double[] c = corner(i, a, b);
            double y = rotatedY(c, this.angle);

            if (y < lowest - FLAT) {
                other = null;
                low = c;
                lowest = y;
            } else if (Math.abs(y - lowest) <= FLAT) {
                other = c;
            }
        }

        if (other == null) {
            return low;
        }

        // 面が下。左右の角のうち、反時計回りに回すときは x の小さい方（左）が、時計回りなら大きい方が支点。
        double[] left = rotatedX(low, this.angle) < rotatedX(other, this.angle) ? low : other;
        double[] right = left == low ? other : low;

        if (this.rate > 0.0) {
            return left;
        }

        if (this.rate < 0.0) {
            return right;
        }

        // 止まっている。どちらかの角のまわりで、力が車体を持ち上げる向きのモーメントを作るときだけ動き出す。
        double leftTorque = -(rotatedX(left, this.angle) * -normal - rotatedY(left, this.angle) * lateral);
        double rightTorque = -(rotatedX(right, this.angle) * -normal - rotatedY(right, this.angle) * lateral);

        // 動き出すには、支点の外へ出た合力が重力の復元の一部を超えなければならない。理屈では0で倒れるが、
        // 地面は1ブロック単位で読まれるので、境目ぎりぎりの斜面では読み値の跳ねだけで揺すられ続けた。
        double margin = START_MARGIN * normal * Math.hypot(a, b);

        if (leftTorque > margin) {
            return left;
        }

        if (rightTorque < -margin) {
            return right;
        }

        return null;
    }

    private static double[] corner(int i, double a, double b) {
        return new double[] {(i & 1) == 0 ? -a : a, (i & 2) == 0 ? -b : b};
    }

    private static double rotatedX(double[] p, double angle) {
        return p[0] * Math.cos(angle) - p[1] * Math.sin(angle);
    }

    private static double rotatedY(double[] p, double angle) {
        return p[0] * Math.sin(angle) + p[1] * Math.cos(angle);
    }

    private static boolean onFace(double angle) {
        return Math.abs(angle - snap(angle)) < FLAT;
    }

    private static double snap(double angle) {
        return Math.round(angle / FACE) * FACE;
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
}
