package com.ashvehicles.ai.navigation;

/**
 * 命じた前進に対して、車両が実際に進めているか。立ち往生の物差し（{@link Navigator}）。
 *
 * <p><b>固定の距離では測れない。</b> 「20 tick で1ブロック」は軽い装輪車両には甘く、重い戦車には厳しすぎる
 * ——加速 0.004 ブロック/tick² の戦車は止まった所から走り出すと 20 tick で 0.84 ブロックしか進めず、走り出す
 * たびに詰まったと読まれる。だから<b>その車両の駆動系なら進めたはずの距離</b>を積み、その {@value #SHARE} も
 * 進めていなければ詰まりとする。
 *
 * <p><b>積むのは実際の速さではなく、駆動系の加速で伸ばした速さ。</b> 壁に押し付けられた車両は実際の速さを
 * 失う（{@code GroundVehicleEntity.travel} が進めた分へ切り詰める）ので、実際の速さを積むと「進めたはず」まで
 * 0 になって詰まりが見えない。実際の速さを読むのは前進を命じ始めた最初の1回だけで、<b>その後は窓を跨いでも
 * 伸ばし続ける</b>——窓ごとに実際の速さから始め直すと、壁の前で小刻みに揺れている車両は毎回「走り出した
 * ばかり」に見えて、いつまでも詰まりにならない（2026-09-13）。
 *
 * <p><b>ただし這ってでも動いている車両は詰まりではない。</b> 窓の間の実際の速さの平均が {@value #CRAWL} を
 * 超えていれば、坂を這い上がっているか壁沿いに滑っているかで、後退させる理由が無い。
 */
public final class StallWatch {
    /** これより短い「進めたはず」では判定しない（ブロック）。窓の終わり際に走り出した車両を詰まりと呼ばない。 */
    public static final double LEAST_EXPECTED = 0.5;

    /** 進めたはずの距離のうち、これだけも進めていなければ詰まり。 */
    public static final double SHARE = 0.3;

    /** 進めたはずの距離のうち、これだけ進めていれば走れている。迷った回数を忘れてよい。 */
    public static final double PROGRESS_SHARE = 0.6;

    /** 窓の平均の実際の速さ（ブロック/tick）がこれを超えていれば、少なくとも動いてはいる。 */
    public static final double CRAWL = 0.05;

    /** 前進を命じていると見なす踏み込み。 */
    private static final float DRIVING = 0.1F;

    private double expected;
    private double speedSum;
    private int ticks;
    private float model;
    private boolean engaged;

    /**
     * 1 tick 分の命令を積む。
     *
     * @param drive        この tick に命じた前進（0〜1）。{@value #DRIVING} 以下なら命令が途切れたとして、次に
     *                     命じたときに実際の速さから始め直す
     * @param speed        今の実際の速さ（ブロック/tick、後退は負）
     * @param maxSpeed     駆動系の最高速度
     * @param acceleration 駆動系の加速
     * @param braking      駆動系の制動
     */
    public void command(float drive, float speed, float maxSpeed, float acceleration, float braking) {
        if (drive <= DRIVING) {
            this.engaged = false;

            return;
        }

        if (!this.engaged) {
            this.model = speed;
            this.engaged = true;
        }

        float target = drive * maxSpeed;

        // 車両の加速（GroundVehicleEntity.accelerate）と同じ規則: 目標より遅ければ加速で、速ければ制動で寄せる。
        this.model = this.model < target ? Math.min(target, this.model + acceleration)
                : Math.max(target, this.model - braking);
        this.expected += this.model;
        this.speedSum += Math.abs(speed);
        this.ticks++;
    }

    /** 窓の間に実際に進んだ水平距離から、詰まっていたか。 */
    public boolean stalled(double moved) {
        return this.expected >= LEAST_EXPECTED && moved < this.expected * SHARE
                && (this.ticks == 0 || this.speedSum / this.ticks < CRAWL);
    }

    /** 窓の間に、進めたはずの距離の大半を進めたか。 */
    public boolean progressing(double moved) {
        return this.expected >= LEAST_EXPECTED && moved >= this.expected * PROGRESS_SHARE;
    }

    /** 窓を閉じる。前進の命令が続いていれば、伸ばしている速さはそのまま次の窓へ持ち越す。 */
    public void reset() {
        this.expected = 0.0;
        this.speedSum = 0.0;
        this.ticks = 0;
    }

    /** 窓を閉じ、命令の続きも捨てる。後退した後は、実際の速さから始め直す。 */
    public void release() {
        this.reset();
        this.engaged = false;
    }

    /** この窓で進めたはずの距離（ブロック）。 */
    public double expected() {
        return this.expected;
    }
}
