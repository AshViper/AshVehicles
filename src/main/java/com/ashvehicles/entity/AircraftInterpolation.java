package com.ashvehicles.entity;

import com.ashvehicles.vehicle.Attitude;

import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * こちらで誰も操縦していない機体をどう描くか。
 *
 * <p>操縦席のクライアント以外は全員、この機体を又聞きで——サーバーが送る位置と姿勢の連なりから——見ている。
 * その素直な使い方、つまり毎tick最新値へ一定割合だけ近づける方法（バニラの乗り物補間がやっていること）は
 * 2つの別々の意味で間違っており、しかもどちらも機体が速いほど悪化する。このクラスはそのどちらもやらない
 * ために存在する。
 *
 * <h2>定常遅れ</h2>
 *
 * <p>バニラは毎tick目標との差の一定割合を詰める。1tickに {@code v} ブロック進む目標に対して1/10ずつ詰めて
 * も差はゼロに収束しない。詰める速度が目標の速度と釣り合う位置——つまり {@code v} の10倍後方——で落ち着く。
 * これは減衰する誤差ではなく速度に比例した<em>恒久的な</em>誤差だ。1tickに0.1ブロック進むボートでは見えな
 * い。1tickに3ブロック進む機体では30ブロック、このパックの最高速度なら80ブロック近くになる。パイロット
 * 以外の全員が、実際の位置よりほぼ1 chunk 後ろの機体を見ていた——そこへ撃ち、そこから撃たれ、そして機体が
 * 見えている場所には空しか無かった。
 *
 * <p>治療は「追いかけるのをやめて予測を始める」こと。このクラスは毎tick機体の速度で進む推測航法の姿勢を
 * 自前で保持する。直線飛行している物に対しては構造上まったく遅れず、吸収すべきはその予測と真値の差だけに
 * なる。
 *
 * <h2>3つの時計と、それでも機体がカクついていた理由</h2>
 *
 * <p>予測の質は、それを駆動する速度の質までしか上がらない。そして正直な速度はこちら側では測れない。操縦
 * されている機体はパイロットのクライアントで飛ばされ、サーバーが中継し、ここで見られている——20Hz の時計が
 * 3つ、どれも同期しておらず、各ペアの間にネットワークがある。届くのは<em>1tickに1回の位置更新ではない</em>。
 * 平均すれば1tickに1回だが、2回来る tick と1回も来ない tick が永遠に続く。時計が互いにずれていくからだ。
 * しかもこれは、パケットが1つも遅延していない状態での話。
 *
 * <p>だから連続する位置から速度を求めると、存在しないカクつきを読み取ることになる。1tickに更新が2回来れば
 * 機体は倍速で飛んでいるように見え、次の tick に来なければ止まったように見える。その推定値で推測航法を
 * 回すのは助けにならないどころか、揺らぎを描画の動きへ増幅する。だから速度はここで推測しない。本当に知って
 * いる側——パイロットのクライアント、あるいは無人機ならサーバー——が送り、速度と旋回率が位置と一緒に届く。
 * 更新の来ない tick はもうコストにならない。予測が既にその tick で何をすべきか知っているから。
 *
 * <h2>補正を「目に見える急加速」なしに吸収する</h2>
 *
 * <p>残るのは予測と真値の差で、その差の取り込み方が「機体が滑らかに見えるか」の全部を決める。毎tick一定
 * 割合を詰める方法——素直なやり方で、このクラスも以前はそうしていた——は<em>位置</em>については滑らかだが
 * 速度については滑らかでない。補正が届いた tick の描画ステップは「機体自身の移動＋誤差の大半」になり、次の
 * tick には元に戻る。1tick分の移動量に相当する補正は、1tickをほぼ倍速で描くことになる。それはまさに治そう
 * としていたカクつきであり、1tick 3ブロックの機体では2ブロックの跳びだ。
 *
 * <p>だから誤差は減衰させず、臨界減衰のバネで<em>飛ばして消す</em>。誤差は自分の速度を持ち、その速度は
 * 徐々にしか変わらないので、描画速度は補正をまたいで連続になり、届いた tick に跳ねない。補正が変えるのは
 * 「今後数tickで誤差をどう取り込むか」であって、この tick に描かれる物ではない。機体が何をしても描画速度に
 * 段差は入らない。段差の出どころがもうどこにも無いので。
 *
 * <p>姿勢も同じやり方で、同じバネで、同じ枠組みで回す。機体のロールは位置よりずっと目立つ——翼端は胴体より
 * ずっと大きく動く——ので、位置を滑らかにしながら姿勢だけ各更新へ飛びつかせれば、最も目に付く部分に、位置
 * から取り除いたばかりのカクつきを戻すことになる。
 */
public final class AircraftInterpolation {

    /** 機体ファイルが値を書かない場合に、補正を取り込む tick 数。 */
    public static final int DEFAULT_CORRECTION_TICKS = 5;

    /** これを超える誤差なら、滑らせずに機体を本来の位置へ置く距離（ブロック）。 */
    public static final double DEFAULT_SNAP_DISTANCE = 8.0;

    /**
     * 最後の補正から、推測航法を信じてこれを駆動し続ける tick 数。
     *
     * <p>上限が無いと、サーバーが話題にしなくなった機体——パイロットが切断した、追跡対象から外れた、サーバー
     * が詰まった——は最後に知っている速度で永遠に流れ続け、本体は静止したままで、引き戻す物が何も無くなる。
     * これらが送られる1tick間隔より十分長いので、通常の飛行で発動することは無い。
     */
    public static final int DEFAULT_MAX_PREDICTION_TICKS = 10;

    /**
     * その予算の末尾で、予測を急停止させずに減衰させる tick 数。
     *
     * <p>報告された速度を予算の最後の tick まで全力で回してから切ると、描画速度に「機体の速度そのもの」と
     * いう段差が入る。それはこのクラスが作り得る最大の段差であり、残りの全部がそれを防ぐために存在する当の
     * 物だ。見た目は「普通に飛んでいた機体が空中でぴたりと止まる」で、更新が0.5秒欠けるたびにクライアントが
     * 他人の機体について見る光景——一瞬の詰まり、パケットロスの塊、1拍飛ばしたサーバー。
     *
     * <p>減衰させれば、機体は予算の末尾にかけて惰性で止まり、やがて届く補正はそこからバネが他と同じように
     * 飛ばして消す。通常の飛行がここに近づくことは無い。1〜2tickごとの更新ならカウンタは1か2のままで、予測
     * は終始全力で走る。
     */
    private static final int COAST_OUT_TICKS = 4;

    /**
     * 報告された速度を信じる上限（1tickあたりブロック）。MOD 内にこれに近い速度で飛ぶ物は無い。壊れた値や
     * 悪意ある値が、2回の補正の間に機体を世界の反対側へ放り投げないようにするための物。
     */
    private static final double MAX_SPEED = 40.0;

    /** 報告された旋回率を信じる上限（1tickあたりラジアン）。1tickで1/4回転でも既に馬鹿げている。 */
    private static final float MAX_BODY_RATE = (float) (Math.PI / 2.0);

    /** これを超えたら滑らかにする価値の無い姿勢誤差（ラジアン）。 */
    private static final float SNAP_ANGLE = (float) Math.toRadians(90.0);

    // 機体が実際にいると信じている位置。補正と補正の間は推測航法で進める。
    private double simX;
    private double simY;
    private double simZ;
    private double velX;
    private double velY;
    private double velZ;

    /** 描画位置がその予測からどれだけ離れているか（軸ごと）と、それが詰まる速度。 */
    private final Offset offsetX = new Offset();
    private final Offset offsetY = new Offset();
    private final Offset offsetZ = new Offset();

    private double renderX;
    private double renderY;
    private double renderZ;

    private double lastTargetX;
    private double lastTargetY;
    private double lastTargetZ;

    private boolean seeded;
    private boolean hasLastTarget;
    private boolean snapRequested;
    /** 速度が、位置の流れから推測されたのではなくこちら側へ伝えられた物か。 */
    private boolean velocityReported;
    private int sinceCorrection;

    private int correctionTicks = DEFAULT_CORRECTION_TICKS;
    private double snapDistance = DEFAULT_SNAP_DISTANCE;
    private int maxPredictionTicks = DEFAULT_MAX_PREDICTION_TICKS;

    /** 外挿中の姿勢、最後に届いた姿勢、そして継続中の旋回。 */
    private final Quaternionf simAttitude = new Quaternionf();
    private final Quaternionf lastAttitude = new Quaternionf();
    /** 旋回率。機体自身の軸回りの1tickあたりラジアン。{@link Attitude#rotationVector} 参照。 */
    private final Vector3f spin = new Vector3f();
    /**
     * 描画姿勢の予測からのずれ。位置と同じバネに乗る。機体自身の各軸回りにまだ残っている回転量（ラジアン）
     * を、回転ベクトルの並び順で——x が翼を貫く軸、y がキャノピーを抜ける軸、z が機首方向。
     */
    private final Offset offsetAboutX = new Offset();
    private final Offset offsetAboutY = new Offset();
    private final Offset offsetAboutZ = new Offset();
    private boolean hasAttitude;
    private boolean rateReported;
    private int sinceAttitude;

    /** 作業用。毎tickの姿勢描画でメモリ確保が起きないように。 */
    private final Quaternionf scratch = new Quaternionf();
    private final Vector3f scratchVector = new Vector3f();

    /** 毎tick機体ファイルから与えられる。機体は存在した後で自分の調整値を知る。 */
    public void tune(int correctionTicks, double snapDistance, int maxPredictionTicks) {
        this.correctionTicks = Math.max(1, correctionTicks);
        this.snapDistance = Math.max(0.5, snapDistance);
        this.maxPredictionTicks = Math.max(1, maxPredictionTicks);
    }

    public boolean isSeeded() {
        return seeded;
    }

    public double renderX() {
        return renderX;
    }

    public double renderY() {
        return renderY;
    }

    public double renderZ() {
        return renderZ;
    }

    /** 機体が実際にいると信じている位置。描画位置ではなく目標位置が欲しい側のため。 */
    public double targetX() {
        return seeded ? simX : renderX;
    }

    public double targetY() {
        return seeded ? simY : renderY;
    }

    public double targetZ() {
        return seeded ? simZ : renderZ;
    }

    /** 駆動を止める。次の更新は古い姿勢からではなく最初から種を撒き直す。 */
    public void release() {
        releasePosition();
        hasAttitude = false;
        rateReported = false;
        sinceAttitude = 0;
        spin.zero();
        offsetAboutX.clear();
        offsetAboutY.clear();
        offsetAboutZ.clear();
    }

    /**
     * 位置だけを諦め、姿勢は動かし続ける。
     *
     * <p>2つは別々の経路で届き、別々の理由で沈黙する。静止している機体には位置更新がそもそも送られない
     * ——言うことが無いので——一方、車輪の上で旋回しているヘリコプターには毎tick姿勢が送られる。片方の沈黙を
     * もう片方をリセットする理由にすれば、ペダルターンに何の理由も無く引っ掛かりが入る。
     */
    private void releasePosition() {
        seeded = false;
        hasLastTarget = false;
        velocityReported = false;
        sinceCorrection = 0;
        velX = 0.0;
        velY = 0.0;
        velZ = 0.0;
        offsetX.clear();
        offsetY.clear();
        offsetZ.clear();
    }

    /**
     * 実際に機体を飛ばしている側から速度を受け取る（1tickあたりブロック）。
     *
     * <p>これが「機能する予測」と「カクつく予測」の分かれ目であり、なぜここで測ってはいけないのかをはっきり
     * 書いておく価値がある。補正は1tickに1回届くのではない。1tickに1回<em>平均で</em>届く。パイロットの
     * 時計、サーバーの時計、こちらの時計がどれも20Hz で、tick の始まりについて誰も一致していないからだ。
     * 連続する位置の差分はそのずれを速度として読む——ある tick は倍、次の tick はゼロ——ので、予測は揺らぎを
     * 吸収するどころか描画へ持ち込む。下の値にはそれが一切入っていない。飛行モデルが、それを走らせた機械の
     * 上で実際に出した値だ。
     */
    public void receiveVelocity(double x, double y, double z) {
        double speed = Math.sqrt(x * x + y * y + z * z);

        if (speed > MAX_SPEED) {
            double scale = MAX_SPEED / speed;
            x *= scale;
            y *= scale;
            z *= scale;
        }

        velX = x;
        velY = y;
        velZ = z;
        velocityReported = true;
    }

    /**
     * 機体を飛ばしている側から旋回率を受け取る。機体自身の軸回りの1tickあたりラジアンで、理由も、対抗して
     * いるずれも {@link #receiveVelocity} と同じ。
     */
    public void receiveBodyRate(float aboutX, float aboutY, float aboutZ) {
        scratchVector.set(aboutX, aboutY, aboutZ);

        float rate = scratchVector.length();

        if (rate > MAX_BODY_RATE) {
            scratchVector.mul(MAX_BODY_RATE / rate);
        }

        spin.set(scratchVector);
        rateReported = true;
    }

    /**
     * 権威ある新しい位置を受け取る。
     *
     * <p>やらないことに注目。描画姿勢は動かさない。補正は予測を狙い直し、差の全部をバネへ渡す。それこそが、
     * 補正自体が目に見える段差を作らない理由だ。例外は1つ、飛行誤差では有り得ないほど大きい誤差——テレポート、
     * リスポーン、圏外から戻ってきた機体——の場合。そこには滑らかにすべき物が無く、そのふりをすれば機体を
     * 世界の向こうまで滑らせることになる。
     *
     * @param currentX 現在機体が描かれている位置。最初の補正がそこから種を撒けるように
     */
    public void receivePosition(double x, double y, double z,
            double currentX, double currentY, double currentZ) {
        if (!seeded) {
            simX = x;
            simY = y;
            simZ = z;
            // 最初に存在を知った瞬間に別の場所へ置くのではなく、機体が既にいる場所から始めて、差はバネに
            // 飛ばして消させる。
            offsetX.set(currentX - x);
            offsetY.set(currentY - y);
            offsetZ.set(currentZ - z);
            renderX = currentX;
            renderY = currentY;
            renderZ = currentZ;
            lastTargetX = x;
            lastTargetY = y;
            lastTargetZ = z;
            hasLastTarget = true;
            seeded = true;
            snapRequested = true;
            sinceCorrection = 0;
            return;
        }

        // 常に予備手段。機体が速度を報告していれば——MOD 内の全部がそうする——ここには来ない。推測が劣る
        // 理由は receiveVelocity の説明にある。実時間ではなく tick で測るのは、詰まりの後にまとめて届いた
        // パケット群が、実時間では途方もない速度に読めるのに対し、ここでは本当の間隔として読めるから。
        if (!velocityReported && hasLastTarget) {
            int gap = Math.max(1, sinceCorrection);

            receiveVelocity((x - lastTargetX) / gap, (y - lastTargetY) / gap, (z - lastTargetZ) / gap);
            // 教えられたのではなく推測した値。そう記録しないと、1回の推測が次の推測を黙らせてしまう。
            velocityReported = false;
        }

        lastTargetX = x;
        lastTargetY = y;
        lastTargetZ = z;
        hasLastTarget = true;
        sinceCorrection = 0;

        // 描画姿勢はその場に留まる。予測がその下で動き、ずれが差を受け取る。前後で render = sim + offset
        // が最下位の桁まで保たれる。
        offsetX.shift(simX - x);
        offsetY.shift(simY - y);
        offsetZ.shift(simZ - z);

        simX = x;
        simY = y;
        simZ = z;

        double errX = offsetX.value();
        double errY = offsetY.value();
        double errZ = offsetZ.value();

        if (errX * errX + errY * errY + errZ * errZ > snapDistance * snapDistance) {
            offsetX.clear();
            offsetY.clear();
            offsetZ.clear();
            renderX = x;
            renderY = y;
            renderZ = z;
            snapRequested = true;
        }
    }

    /**
     * 推測航法を1tick進め、描画姿勢がまだ抱えている誤差を飛ばして消す。
     *
     * @return この tick の描画姿勢を機体へ適用すべきか。補正が来ないまま予測予算を使い切ったら false で、
     *         古い速度で永遠に流れ続けさせるのではなく機体を返す
     */
    public boolean advance() {
        if (!seeded) {
            return false;
        }

        sinceCorrection++;

        if (sinceCorrection > maxPredictionTicks) {
            releasePosition();
            return false;
        }

        double coast = this.coasting(sinceCorrection);

        simX += velX * coast;
        simY += velY * coast;
        simZ += velZ * coast;

        offsetX.step(correctionTicks);
        offsetY.step(correctionTicks);
        offsetZ.step(correctionTicks);

        renderX = simX + offsetX.value();
        renderY = simY + offsetY.value();
        renderZ = simZ + offsetZ.value();
        return true;
    }

    /** 種撒きまたはスナップの後に1度だけ true。呼び出し側へ「機体をそのまま置け」と伝える。 */
    public boolean consumeSnap() {
        boolean snap = snapRequested;
        snapRequested = false;
        return snap;
    }

    /**
     * 権威ある新しい姿勢を受け取る。
     *
     * <p>位置と同じく、予測を狙い直して描画中の物には触れない。両者の差は {@link #advanceAttitude} のバネ
     * へ渡り、続く数tickで回して消される。全体を通じて3つの角度ではなく回転として保持するのは、この機体が
     * まさに「天頂を突き抜け、背面へロールしても角度が折り返さない」ためにクォータニオンで組まれているから。
     */
    public void receiveAttitude(Quaternionfc authoritative) {
        if (!hasAttitude) {
            simAttitude.set(authoritative);
            lastAttitude.set(authoritative);
            offsetAboutX.clear();
            offsetAboutY.clear();
            offsetAboutZ.clear();
            spin.zero();
            hasAttitude = true;
            sinceAttitude = 0;
            return;
        }

        // 今この瞬間に画面に出ている物。補正がそれを動かせないよう保持しておく。
        drawnAttitude(scratch);

        // 旋回率を報告していない機体のための予備手段。前回の権威ある姿勢からの回転全体を1tick分へ割る。
        if (!rateReported) {
            int gap = Math.max(1, sinceAttitude);

            spin.set(Attitude.rotationVector(
                    new Quaternionf(lastAttitude).conjugate().mul(authoritative).normalize()))
                    .mul(1.0F / gap);
        }

        lastAttitude.set(authoritative);
        simAttitude.set(authoritative);
        sinceAttitude = 0;

        // 描画姿勢を、新しい予測からのずれとして機体自身の座標系で書く。バネが抱えている速度には触れない。
        // 補正が予測を動かす量はたいてい1度の何分の一かで、その範囲では古い座標系と新しい座標系は同じ物。
        Vector3f error = Attitude.rotationVector(
                scratch.premul(new Quaternionf(simAttitude).conjugate()).normalize());

        if (error.length() > SNAP_ANGLE) {
            offsetAboutX.clear();
            offsetAboutY.clear();
            offsetAboutZ.clear();
            return;
        }

        offsetAboutX.set(error.x);
        offsetAboutY.set(error.y);
        offsetAboutZ.set(error.z);
    }

    /**
     * 報告された速度で姿勢を回し続け、残った分を飛ばして消す。
     *
     * <p>前半が無ければ姿勢は補正の間ずっと静止し、次の補正が届いた瞬間に溜まった回転を全部こなす羽目に
     * なる。後半が無ければ届いた補正ごとに飛びつく。どちらも同じカクつきを描き、機体はそれを位置よりロール
     * ではるかに顕著に見せる。翼端の移動距離がずっと長いので。
     *
     * <p>回転を継続する期間は位置の推測航法と同じ長さまで。それを超えれば最後に聞いた回転率はもう何の証拠
     * でもないし、話題にされなくなった機体は自分の機首回りに永遠に回り続けるのではなく静止すべきだ。
     */
    public void advanceAttitude(Quaternionf out) {
        if (!hasAttitude) {
            return;
        }

        sinceAttitude++;

        float coast = (float) this.coasting(sinceAttitude);

        if (coast > 0.0F) {
            // 位置と同じ理由で予算の末尾にかけて減衰させる。しかもより切実に。急停止したロールは機体が
            // できる最も目立つ動きだから。
            simAttitude.mul(Attitude.rotationOf(scratchVector.set(spin).mul(coast))).normalize();
        }

        offsetAboutX.step(correctionTicks);
        offsetAboutY.step(correctionTicks);
        offsetAboutZ.step(correctionTicks);

        drawnAttitude(out);
    }

    /**
     * 報告された速度や旋回率のうち、予測がまだ運んでいる割合。予算が残っている間は全部、末尾の
     * {@link #COAST_OUT_TICKS} を使い切るにつれてゼロへ減っていく。
     *
     * @param since 進めている種類の直近の補正からの tick 数
     */
    private double coasting(int since) {
        int left = maxPredictionTicks - since + 1;

        if (left <= 0) {
            return 0.0;
        }

        int taper = Math.min(COAST_OUT_TICKS, maxPredictionTicks);

        return left >= taper ? 1.0 : (double) left / taper;
    }

    /** 予測を、バネがまだ取り込んでいない誤差の分だけ回した物。 */
    private void drawnAttitude(Quaternionf out) {
        scratchVector.set((float) offsetAboutX.value(), (float) offsetAboutY.value(), (float) offsetAboutZ.value());
        out.set(simAttitude).mul(Attitude.rotationOf(scratchVector)).normalize();
    }

    public boolean hasAttitude() {
        return hasAttitude;
    }

    /** その姿勢が新情報か、それとも機体が既に外挿元にしている物と同じか。 */
    public boolean isNewAttitude(Quaternionfc candidate) {
        return !hasAttitude
                || candidate.x() != lastAttitude.x
                || candidate.y() != lastAttitude.y
                || candidate.z() != lastAttitude.z
                || candidate.w() != lastAttitude.w;
    }

    /**
     * 機体の描画位置と予測位置の差の1軸分。臨界減衰のバネで詰まっていく。
     *
     * <p>臨界減衰であることが要点だ。ずれは速度を持つので、跳ねるのではなく徐々に離れ徐々にゼロへ達し、
     * しかも反対側へ行き過ぎて戻ってくることが無い。それが買うのは連続な描画速度——機体自身の速度に、毎tick
     * 少しずつしか変われない速度を足した物——であり、補正がどう届こうと「機体の見かけの速さ」に段差を入れ
     * られなくなる。
     *
     * <p>1tick分の更新はオイラー法ではなく臨界減衰バネの閉形式で行う。ここで欲しい整定時間に対して1tickは
     * 長い時間であり、素朴に積分すると2tickのバネは不安定になって振動するから。
     */
    private static final class Offset {
        private double value;
        private double rate;

        double value() {
            return value;
        }

        /** 速度を乱さずにずれだけを動かす。動いたのは予測であって描画ではないので。 */
        void shift(double by) {
            value += by;
        }

        void set(double to) {
            value = to;
        }

        void clear() {
            value = 0.0;
            rate = 0.0;
        }

        /** @param smoothTicks ずれを飛ばして消すのにかかるおおよその tick 数 */
        void step(double smoothTicks) {
            double omega = 2.0 / smoothTicks;
            // 1tick分なので時間刻みは1になり、以下の式から消える。
            double decay = 1.0 / (1.0 + omega + 0.48 * omega * omega + 0.235 * omega * omega * omega);
            double travel = rate + omega * value;

            rate = (rate - omega * travel) * decay;
            value = (value + travel) * decay;
        }
    }
}
