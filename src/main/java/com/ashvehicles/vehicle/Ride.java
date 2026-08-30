package com.ashvehicles.vehicle;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 地上車両の車体がサスペンション上でどれだけ動いたか、およびそのサスペンション自体。
 *
 * <p><b>目的。</b> この MOD の戦車は4本の触角で支えられている。履帯の四隅の下の地面を読み、その4点が
 * 作る平面に沿って車体を寝かせる。これは車両が<em>最終的にどこに落ち着くか</em>の忠実な記述であって、
 * そこへどう至るかの記述ではまったくない。実車の車体は走行装置に直付けされておらず、30cm 以上のトーション
 * バーの上に乗っている。走る車両を走る車両らしく見せる要素は全部その30cm の中で起きる——制動での前のめり、
 * 旋回で外へ傾く車体、縁石から落ちた後に一度揺れて落ち着く動き。これが無いと、戦車は盤上の駒のように地形を
 * 滑る。
 *
 * <p><b>効く範囲。</b> 動くのは描画だけ。当たり判定の箱も、砲の照準も、車両の立っている位置も、従来通り
 * 剛体の車体から計算され、どれもこの値を見ない。これは意図的だ。発射の瞬間に車体が揺れていた方向へ弾が
 * 飛ぶ乗員は敵ではなくサスペンションと戦うことになるし、装甲が毎tick 数センチ動く車両は命中が毎回くじ引き
 * になる。動くのは絵——重量をゲームが計算していない機械にとって、それこそがサスペンションの<em>役目</em>。
 *
 * <p><b>通信が一切不要な理由。</b> どの側も既に、車両の速度・進行方位・高さ・車体の姿勢を知っている。
 * それらは運転者がローカルで計算しているか、別の理由で送られているかのどちらかだ。サスペンションはその
 * <em>変化</em>で駆動されるので、各側が自前で回して同じ絵に辿り着ける。1バイトも送らずに。多少ずれた2つの
 * 側の差は数センチの車体変位で、誰にも見えないし何もそれに依存していない。
 *
 * <p>3つの値は、描く価値のある「車体がサスペンション上で動く3通り」。4つ目は無い。左右・前後の変位は実車
 * にもあるがミリ単位だし、サスペンション上のヨーは装軌車体には起きない。
 *
 * @param heave 静止位置から車体が持ち上がった量（ブロック）。負ならサスペンションが縮んでいる
 * @param pitch 尾部に対して機首がどれだけ上がったか（度）
 * @param lean 左側に対して右側がどれだけ下がったか（度）。車体自身のバンクと同符号にして、両者が同じ向き
 *             に読めるようにしてある
 */
public record Ride(float heave, float pitch, float lean) {
    /** サスペンション上でまっすぐ座った車体。サスペンションを持たない車両は永遠にこの状態。 */
    public static final Ride LEVEL = new Ride(0.0F, 0.0F, 0.0F);

    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    /**
     * 車体が、描き分ける価値があるだけ動いたか。
     *
     * <p>訊く価値がある。サスペンションが落ち着いた停止中の車両が最も普通の状態で、この値を読む側——
     * ポーズスタック、全ての転輪、履帯の全リンク——は「動いていない」なら丸ごと省略できる。
     */
    public boolean isLevel() {
        return Math.abs(this.heave) < 1.0E-4F && Math.abs(this.pitch) < 1.0E-3F
                && Math.abs(this.lean) < 1.0E-3F;
    }

    /** 2つの状態の中間。自前のサスペンションを持たないゴースト表示用。 */
    public static Ride between(Ride previous, Ride now, float partialTick) {
        return new Ride(
                Mth.lerp(partialTick, previous.heave(), now.heave()),
                Mth.lerp(partialTick, previous.pitch(), now.pitch()),
                Mth.lerp(partialTick, previous.lean(), now.lean()));
    }

    /**
     * 車両上の点を、車体の変位が運んだ先へ移す。座標は車両自身の軸（x が右、y が上、z が艦首方向）で、
     * 機体ファイルの記述は全部この軸で書かれている。
     *
     * <p>高さだけでなく変位の全成分を使う。車体床から2ブロック上・中心から3ブロック前にある乗員の目は、
     * 機首が上がれば後ろへ、車体が傾けば横へ振られる。頭が固定されたまま車体だけが上下する絵ではなく、
     * それこそが内側から見た「揺さぶられている」感覚になる。
     *
     * <p>角度は小さいので微小角近似でピクセル単位まで正確。ひどく酷使したサスペンションが達する10度でも、
     * 誤差はセンチ規模の変位の1.5%。
     */
    public Vec3 carry(Vec3 point) {
        if (this.isLevel()) {
            return point;
        }

        float nod = this.pitch * DEG_TO_RAD;
        float heel = this.lean * DEG_TO_RAD;

        return new Vec3(
                point.x + point.y * heel,
                point.y + this.heave + point.z * nod - point.x * heel,
                point.z - point.y * nod);
    }

    /**
     * モデル内の点が車体変位によって真上へ運ばれる量（モデル自身のブロック単位）。
     *
     * <p>用途は走行装置で、元の位置に留まるにはちょうどこの分だけ逆へ動かす必要がある。車体はポーズ
     * スタック上で揺らされるのでモデルの全ボーンが一緒に動く。ここが返す量だけ押し下げた転輪は、上の車体が
     * 動いても地面に留まる——外から見たサスペンションとはそれが全て。
     *
     * <p><b>軸は車両ではなくモデルの軸。</b> 機体は姿勢を適用してから半回転させて描かれる
     * （{@code VehicleRenderer.applyRotations} 参照）ので、モデルの +X にある点は車両の左側、+Z にある点
     * は尾部側になる。その半回転が違いの全部で、{@link #carry} に対して両項の符号が反転している理由。
     *
     * @param scale モデル自身のスケール。モデルはその中で描かれるので、縮小後は車体変位1ブロックが
     *              1ブロック分より小さくなる
     */
    public float liftOf(float modelX, float modelZ, float scale) {
        return this.heave / Math.max(scale, 0.01F)
                - modelZ * this.pitch * DEG_TO_RAD
                - modelX * this.lean * DEG_TO_RAD;
    }

    // ------------------------------------------------------------------
    // サスペンション本体
    // ------------------------------------------------------------------

    /**
     * 1台分のサスペンション。車両が tick し、描画側が読む。
     *
     * <p>減衰付きバネ3本。車体が動ける方向ごとに1本ずつあり、それぞれ「車両に働く力が示す位置」へ引かれ、
     * 途中で行き過ぎる自由を持つ。目標へのイージングではなくバネにしたのは、行き過ぎこそが本質だから。
     * 静止線へまっすぐ収束する車体は制動で沈んだら沈んだままだが、実車は沈み、水平を越えて戻り、次の1秒で
     * 揺れながら静止する。
     *
     * <p>バネを励起する量は捏造ではなく実測で、その測定値はどの側でも同じ:
     *
     * <ul>
     * <li><b>車体の鉛直加速度。</b> 落下速度そのものではなく（斜面を一定速度で下る車両は揺さぶられていな
     * い）、その変化の鋭さ。1ブロック落ちて急停止した車体は、着地の瞬間に車体が持っていた速度の分だけ
     * バネを縮める。
     * <li><b>駆動系。</b> 発進で機首が上がり、制動で下がる。どちらもその車両にできる範囲に対する比率で
     * 効くので、ファイル側は「1tick二乗あたり何ブロック」を知らなくても車体の動く量を書ける。
     * <li><b>旋回。</b> 曲がりで外へ振られた車体は外側へ傾く。これもその車両が曲がれる最大に対する比率。
     * </ul>
     *
     * <p><b>そしてそれ以外は無い。</b> エンジンや路面による走行中の細かな振動は上乗せしていないし、意図的
     * にしていない。それは測定できない量だからだ。4本の触角が記述するのは車体が乗る平面であり、その平面と
     * 個々の転輪が実際に乗り越えている物との差は、この車両が一度も知ったことのない情報。つまり捏造するしか
     * なく、何も揺らしていない車体を揺らす波になる。ここにあるのは、実際に起きた事に車両が応答している分
     * だけ。荒れ地を渡る戦車にはそれが十分たくさんある。
     */
    public static final class Springs {
        /**
         * 車体の落下速度の急変のうち、上部車体が自分の分として受け取る割合。
         *
         * <p>全部ではない。着地した車体は1tick足らずで地面に減速させられるが、上部車体はサスペンション
         * によって1秒近くかけて減速する。だから車体が止まった tick の終わりに上部車体がまだ持っている
         * 速度は、その tick の始めに持っていた分の相当部分ではあるが全部ではない。
         */
        private static final float IMPACT = 0.4F;

        /** 1tick分の落下として数えてよい上限。テレポートが打ち上げにならないように。 */
        private static final float MAX_JOLT = 1.5F;

        private final Axis heave = new Axis();
        private final Axis pitch = new Axis();
        private final Axis lean = new Axis();

        /**
         * 差分の基準となる前 tick があるか。ここは全部が差分なので、車両の生涯最初の tick——あるいは
         * ワールドから読み戻された直後の tick——には引く相手が無い。
         */
        private boolean primed;

        private float wasSpeed;
        private float wasHeading;
        private double wasY;
        private double wasSink;

        /**
         * サスペンションの1tick分。
         *
         * @param speed 車体前方向の速度（1tickあたりブロック）
         * @param heading 車体の方位（度）
         * @param y 車両の高さ
         * @param onGround 揺さぶられる相手となる地面が下にあるか
         */
        public void tick(GroundVehicleDefinition definition, float speed, float heading, double y,
                boolean onGround) {
            GroundVehicleDefinition.Suspension setup = definition.suspension();

            float sink = (float) (y - this.wasY);
            float jolt = Mth.clamp(sink - (float) this.wasSink, -MAX_JOLT, MAX_JOLT);
            float along = speed - this.wasSpeed;
            float turn = Mth.degreesDifference(this.wasHeading, heading);

            this.wasSink = sink;
            this.wasY = y;
            this.wasSpeed = speed;
            this.wasHeading = heading;

            if (!this.primed) {
                this.primed = true;
                jolt = 0.0F;
                along = 0.0F;
                turn = 0.0F;
            }

            float travel = Math.max(setup.travel(), 0.0F);

            if (travel <= 0.0F) {
                // ファイルに「車体は動かない」と書かれた車両。全バネに目標ゼロ・可動域ゼロを渡すことで
                // 全部を静止に固定する。この処理に2本目の分岐を作らずに済む。
                this.heave.tick(0.0F, setup, 0.0F);
                this.pitch.tick(0.0F, setup, 0.0F);
                this.lean.tick(0.0F, setup, 0.0F);

                return;
            }

            // 各バネがストッパーに当たるまでの可動域。2つの角度は同じ可動域を車両を横断して読んだ物で、
            // 接地長の端の転輪が可動域を使い切ったとき、車体は「その量÷中心までの距離」だけ傾く。
            float halfLength = Math.max(setup.contactLength() * 0.5F, 0.5F);
            float halfWidth = Math.max(setup.contactWidth() * 0.5F, 0.5F);
            float nodLimit = (float) Math.toDegrees(travel / halfLength);
            float heelLimit = (float) Math.toDegrees(travel / halfWidth);

            if (onGround) {
                this.heave.kick(-jolt * IMPACT);
            }

            // 上下動の静止線は水平で、引かれる先も水平だけ。そこから外すのは上の衝撃。他の2つは駆動系
            // と旋回が押さえている位置に保たれ、それらが止まった瞬間に解放される。
            this.heave.tick(0.0F, setup, travel);
            this.pitch.tick(this.nod(definition, along), setup, nodLimit);
            this.lean.tick(this.heel(definition, speed, turn), setup, heelLimit);
        }

        /** 2つの tick の間の任意の瞬間の車体。描画側が欲しいのはこれ。 */
        public Ride at(float partialTick) {
            return new Ride(this.heave.at(partialTick), this.pitch.at(partialTick),
                    this.lean.at(partialTick));
        }

        /**
         * 駆動系が車体に要求している位置。機首上げの度数で。
         *
         * <p>固定の「1tick二乗あたり何ブロック」ではなくその車両が実際にできる範囲を基準にするので、
         * {@code dive} は文字通りの意味——この機械が最大加速・最大制動したときの沈み込み——になる。偵察車
         * でも60トンでも同じ。
         */
        private float nod(GroundVehicleDefinition definition, float along) {
            GroundVehicleDefinition.Powertrain powertrain = definition.powertrain();
            float hardest = Math.max(Math.max(powertrain.acceleration(), powertrain.braking()), 1.0E-4F);

            return Mth.clamp(along / hardest, -1.0F, 1.0F) * definition.suspension().dive();
        }

        /**
         * 旋回が車体を振っている方向。右側下がりの度数で。
         *
         * <p>符号が反転しており、そこが物理の全て。右へ回された車体は左へ取り残されるので、右旋回では
         * 左側のバネへ荷重が乗る。描く価値があるのはまさにそのためで、旋回の<em>内側</em>へバンクする車体
         * は飛行機に見えてしまう。
         */
        private float heel(GroundVehicleDefinition definition, float speed, float turn) {
            GroundVehicleDefinition.Powertrain powertrain = definition.powertrain();
            float hardest = Math.max(powertrain.maxSpeed(), 1.0E-4F)
                    * Math.max(powertrain.steerRate(), 1.0E-4F) * DEG_TO_RAD;

            return -Mth.clamp(speed * turn * DEG_TO_RAD / hardest, -1.0F, 1.0F)
                    * definition.suspension().lean();
        }


        /**
         * バネ1本と、その上で描く価値のある唯一の量。
         *
         * <p>素の減衰バネとして1tick刻みで積分する。ここには剛性が問題になるほど硬い物は無い——最速の
         * 車体でも水平へ戻るのに0.5秒、つまり1tickではなく10tickの波——し、そもそも車両の他の部分も
         * 1tick刻みで計算されている。
         */
        private static final class Axis {
            /** 現在位置、前 tick 終了時の位置、そして移動速度。 */
            private float value;
            private float previous;
            private float rate;

            /**
             * @param target 車両に働く力が車体を押さえている位置。駆動系や旋回が引っ張っていなければ
             *               水平
             * @param limit ストッパーに当たるまでの可動域
             */
            void tick(float target, GroundVehicleDefinition.Suspension setup, float limit) {
                this.previous = this.value;
                this.rate += (target - this.value) * Mth.clamp(setup.stiffness(), 0.0F, 1.0F);
                this.rate -= this.rate * Mth.clamp(setup.damping(), 0.0F, 1.0F);

                float moved = this.value + this.rate;

                // ストッパー。可動域を使い切ったバネは止まり、そこまで運んだ速度も失う。バンプストップ
                // に当たった車体とは、残りの運動をバネではなく車両本体に取り上げられた車体のこと。
                if (moved > limit) {
                    moved = limit;
                    this.rate = Math.min(this.rate, 0.0F);
                } else if (moved < -limit) {
                    moved = -limit;
                    this.rate = Math.max(this.rate, 0.0F);
                }

                this.value = moved;
            }

            void kick(float impulse) {
                this.rate += impulse;
            }

            float at(float partialTick) {
                return Mth.lerp(partialTick, this.previous, this.value);
            }
        }
    }
}
