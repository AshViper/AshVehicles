package com.ashvehicles.client;

import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.entity.AircraftEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * ポインティング飛行。パイロットが空にマークを置き、機体がその上へ機首を持っていく。
 *
 * <p>マウスが動かすのは機体ではない。動かすのは方向であり、その下の小さなオートパイロットが「機首をそこへ持って
 * いくには操縦桿が何をすべきか」を求める。固定翼機ではバンクして引くことを意味する。機体が進路を変える方法はそれ
 * しか無いからだ。ヘリではペダルを意味する。ヘリが機首を向ける方法がそれだからだ。キー操作は今も存在し、今も機体を
 * 直接飛ばす。ここが埋めるのはパイロットが手で要求していない分だけだ。
 *
 * <p><b>マークは機首からの角度ではなくワールド方向として保持する。</b>これが機能する理由の全てだ。機体基準で保存
 * した角度は、機体がそちらへ回っても縮まない——マークが機体と一緒に回るので、機体は旋回へロールし、永久にロールし
 * 続ける。方向ならパイロットが置いた場所に留まり、機体が回るにつれ機首との誤差が閉じ、到達すれば操縦桿は自ら中立へ
 * 戻る。
 *
 * <p>これが {@link CockpitView} と別物である理由でもある。あちらは向こうで述べた理由からクランプされた2角でなければ
 * ならない。頭は回転であり自ら巻き上がりうるが、方向は巻き込むロールを持たず、何かを越えて宙返りすることもない。
 *
 * <p><b>「見ること」と「要求すること」は別の方向だ。</b>パイロットの視線は極を除いてまったく制限しない。三人称視点
 * ではカメラが全周を回れる必要があるし、後ろを見られることがあの視点の存在理由の半分だからだ。機体へ要求されるのは
 * その方向を機首まわりの円錐内へ収めた物で、それがこれを「向き直れという指示」ではなく「操縦桿」に留めている。マウス
 * を大きく振れば、カメラが尾部を越えて回る間、機体は全力でその後を追って旋回する——どちらへの答えとしても正しい。
 *
 * <p><b>マウスがどの軸で働くかは視点によるし、そうでなければならない。</b>コックピットではカメラが機体に固定され主翼
 * と共にロールするので、画面上の横は機体上の横であり、マウスは機体自身の軸周りに回す。三人称カメラは意図的に直立の
 * ままなので、あの画面での横は<em>ワールド</em>の横だ——そこで機体軸周りに回すマウスは、主翼が水平でなくなった瞬間に
 * 視界を画面の下へ送る。三人称視点を飛行不能にしていたのはまさにそれだ。
 */
public final class MouseAim {
    /**
     * マークを機首からどれだけ離せるか（度）。
     *
     * <p>意図的に小さい。これは操縦桿であって見回しではない。どこへでも置けるマークは、機体が到達に10秒かける旋回を
     * 要求できてしまう。その間ずっと操縦桿は一杯のままで、何をしているのかの手応えも無い。円錐内なら操縦桿らしく
     * 振る舞う——マークがどれだけ外れているかが、要求されている舵の量だ。これより遠くを見るためにフリールックキーが
     * あり、あちらは操縦しない。
     */
    private static final float CONE = 35.0F;

    /**
     * マークが横へ1度外れるごとに要求するバンク角と、その要求上限。
     *
     * <p>機体はバンクして引くことで方位を変える。方向舵はほとんど関与しない。だから右へ外れたマークは方向舵を蹴ること
     * ではなく、主翼が仕事をする間保つべきバンク角を意味する——それはパイロットが操縦桿でやることそのものであり、
     * この方式で飛ばす機体がマウスカーソルではなく機体らしく見える理由だ。
     */
    private static final float BANK_PER_DEGREE = 3.0F;
    private static final float MAX_BANK = 75.0F;

    /**
     * バンクが失う揚力を補うために足す引き量。主翼が自重の何倍を引くべきかにつき、舵一杯に対する割合で表す。
     *
     * <p>バンクした主翼は支える力が弱まるのではなく、間違った方向へ支えるだけだ。60度では揚力の半分しか空を向いて
     * おらず、機体は降下を始める。旋回を水平に保つのは誰かが引くことであり、パイロットは考えずにそれを供給する——だから
     * ここも供給する。これが無いと、マウスを片側へ倒し続けることは旋回ではなく地面への螺旋降下になる。実際そうなって
     * いた。
     *
     * <p>あくまでバイアスにすぎない。水平線より下へマークを置いたパイロットは降下を要求しており、誤差項がこれより
     * はるかに大きな声でそう言う。
     */
    private static final float TURN_HOLD = 0.8F;
    /**
     * 主翼がどれだけ倒れたら引くのを諦めるか。これを超えると引くべき有用な「上」が無く、背面で引けば機体は地面を向く。
     */
    private static final double UPRIGHT = 0.1;

    /**
     * 三人称カメラが垂直へどこまで近付けるか（度）。
     *
     * <p>真上の少し手前で、ゲームの他の全視点にバニラが課すのと同じ制限だ。それを越えると方位が意味を失い、画が滑る。
     * 方位自体はまったく制限しない——カメラは全周を回る。外部視点の存在理由の半分が後ろを見ることだからだ。
     */
    private static final float POLE = 89.5F;

    /** 誤差1度あたりの舵量と、既に進行中の回転1度/tickあたりの舵量。 */
    private static final float ROLL_GAIN = 0.04F;
    private static final float ROLL_DAMPING = 0.15F;
    private static final float PITCH_GAIN = 0.08F;
    private static final float PITCH_DAMPING = 0.15F;
    /**
     * 方向舵。固定翼機では旋回手段ではなくトリムだ。機首をマークへ最後の1〜2度寄せるには足り、機体を横滑りで回すには
     * 足りない量。
     */
    private static final float YAW_GAIN = 0.02F;
    /**
     * ヘリではまったく逆になる。ヘリはテールローターで機首を向け、そのためにバンクする必要が無いので、そこではペダル
     * が全てだ。
     */
    private static final float ROTOR_YAW_GAIN = 0.05F;
    private static final float YAW_DAMPING = 0.15F;

    /** 照準が操縦桿へ要求している内容。各値 -1〜1。 */
    public record Stick(float pitch, float roll, float yaw) {
        public static final Stick NONE = new Stick(0.0F, 0.0F, 0.0F);
    }

    /** このパイロットが操縦している機体。操縦していなければ null。 */
    private static AircraftEntity aircraft;
    /**
     * パイロットの視線をワールド方向として。極以外はどこへでも向けられる。
     */
    private static Vec3 look = Vec3.ZERO;
    /** そして機体へ要求されている方向。上記を円錐内へ収めた物。 */
    private static Vec3 aim = Vec3.ZERO;
    /**
     * そもそもマウスで機体を飛ばすか。
     *
     * <p>初期状態は有効。大半の人が最初に手を伸ばすのがそれだからだ。切り替え可能なのは、優れた方式ではなく別の方式
     * だからである。無効にすればマウスは見回すだけに戻り、機体はキーが完全に受け持つ。これが導入される前、この MOD が
     * 飛んでいた形だ。
     */
    private static boolean enabled = true;

    private MouseAim() {
    }

    /**
     * 毎フレーム呼ばれる。このプレイヤーが操縦席にいるかを記録し、何かに乗り込むたびマークを機首へ置き直す。
     *
     * <p>実際に操縦している者のみが対象。搭乗者のマウスには操縦する物が無いので、窓の外を眺めるに任せる。
     */
    public static void follow(AircraftEntity riding) {
        AircraftEntity flying = riding != null
                && riding.getAviator() == Minecraft.getInstance().player ? riding : null;

        if (flying != aircraft) {
            aircraft = flying;
            centre();
        }
    }

    /** マークが操縦する対象の機体がこのパイロットの手の下にある間 true。 */
    public static boolean isActive() {
        return enabled && aircraft != null && !aircraft.isRemoved();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** 機能全体を切り替える。どちらの場合もマークは機首へ戻す。 */
    public static void setEnabled(boolean on) {
        enabled = on;
        centre();
    }

    /** パイロットの視線をワールド方向として。円錐には制限されない。 */
    public static Vec3 look() {
        return look;
    }

    /** 両方の方向を機首へ戻す。何も要求せず、どこへも目を逸らしていない状態。 */
    public static void centre() {
        look = aircraft == null ? Vec3.ZERO : aircraft.getNoseVector();
        aim = look;
    }

    /**
     * マウス移動の分だけパイロットの視線を動かす（度）。
     *
     * @param inCockpit カメラが機体に固定されているか。移動を適用する軸を決める。クラスの注記参照
     */
    public static void turn(double deltaX, double deltaY, boolean inCockpit) {
        if (!isActive()) {
            return;
        }

        if (inCockpit) {
            // 機体自身の上方向と右方向の周りに回す。画面がそれに沿っているからだ。どちらも符号を反転する。軸周りの
            // 回転は、手が動いたのと逆向きに方向を運ぶからだ。
            Quaternionf attitude = aircraft.getAttitude(1.0F);

            look = spin(look, Attitude.up(attitude), -deltaX);
            look = spin(look, Attitude.right(attitude), -deltaY);

            return;
        }

        // 外部視点では、ワールドに対する方位と仰角——ゲームの他の場所でのマウスの意味であり、直立した画面が求める物
        // だ。方位は全周を回る。止まるのは仰角だけで、見上げの限界がその位置だ。
        float heading = (float) (Mth.atan2(-look.x, look.z) * (180.0 / Math.PI) + deltaX);
        float elevation = (float) Mth.clamp(
                -Math.asin(Mth.clamp(look.y, -1.0, 1.0)) * (180.0 / Math.PI) + deltaY, -POLE, POLE);

        look = Vec3.directionFromRotation(elevation, heading);
    }

    /**
     * プレイヤー自身の方位と仰角を、パイロットの視線へ向ける。
     *
     * <p>三人称視点用。他に誰もやらないからだ。コックピットではカメラが {@link CockpitView} から配置され、プレイヤー
     * の角度はそこから従う。だが分離カメラはプレイヤーが向いている方へ置かれるので、プレイヤーの向きがカメラそのもの
     * になる。
     */
    public static void applyToPlayer() {
        LocalPlayer player = Minecraft.getInstance().player;

        if (player == null || !isActive() || look.lengthSqr() < 1.0E-8) {
            return;
        }

        player.setYRot((float) (Mth.atan2(-look.x, look.z) * (180.0 / Math.PI)));
        player.setXRot((float) (-Math.asin(Mth.clamp(look.y, -1.0, 1.0)) * (180.0 / Math.PI)));
        player.setYHeadRot(player.getYRot());
    }

    /**
     * マークが要求する操縦桿の量。ついでにマークを機首の届く範囲へ引き戻す。
     *
     * <p>パイロットの入力から毎tick1回呼ばれる。これらが飛行になる唯一の場所だ。
     *
     * <p>フリールックキーを押している間はマークをその場に残し、パイロットの目だけを動かす。機体は最後に要求された方向
     * へ飛び続け、その間パイロットは肩越しに振り返る。見回しの目的はそれであり、機体が目に追従したら何の価値も無くなる。
     */
    public static Stick stick() {
        if (!isActive()) {
            return Stick.NONE;
        }

        if (!ModKeyMappings.FREE_LOOK.isDown()) {
            aim = look;
        }

        holdInCone();

        Quaternionf attitude = aircraft.getAttitude(1.0F);
        Vec3 nose = Attitude.nose(attitude);
        Vec3 up = Attitude.up(attitude);
        Vec3 right = Attitude.right(attitude);

        // マークが機首からどれだけ外れているかを、機体がそれについて誤りうる2通り——左右方向と上下方向——に分ける。
        float offYaw = (float) Math.toDegrees(Mth.atan2(aim.dot(right), aim.dot(nose)));
        float offPitch = (float) Math.toDegrees(Math.asin(Mth.clamp(aim.dot(up), -1.0, 1.0)));

        // 昇降舵。どちらの種類の機体でも同じだ。揚力が主翼由来でもローター由来でも、機首を上げるのは同じレバーで
        // ある。既に進行中の回転に対して減衰させる。さもないと機首は回転したままマークに到達し、そのまま行き過ぎる。
        float pitch = Mth.clamp(offPitch * PITCH_GAIN - aircraft.getPitchDelta() * PITCH_DAMPING,
                -1.0F, 1.0F);

        if (aircraft.isRotorcraft()) {
            // ヘリはバンクではなく指向で向ける。ペダルが機首をマークへ振ってそこに残し、サイクリックはキー用に空けて
            // おく——それが望まれる形だ。ヘリではロールは旋回手段ではなく横移動の手段だからである。
            float yaw = Mth.clamp(offYaw * ROTOR_YAW_GAIN - aircraft.getYawDelta() * YAW_DAMPING,
                    -1.0F, 1.0F);

            return new Stick(pitch, 0.0F, yaw);
        }

        // 固定翼機は指向ではなくバンクで向ける。マークがバンク角を決め、補助翼にはその角と現在のバンクの差を要求
        // する。だから機体はロールインし、主翼が機首を回す間その旋回を保ち、マークが中央へ来るにつれロールアウトする。
        // 最後の1〜2度のために小さな方向舵の項も添える。
        float bank = aircraft.getRoll();
        float wanted = Mth.clamp(offYaw * BANK_PER_DEGREE, -MAX_BANK, MAX_BANK);
        float roll = Mth.clamp((wanted - bank) * ROLL_GAIN
                - aircraft.getRollDelta() * ROLL_DAMPING, -1.0F, 1.0F);
        float yaw = Mth.clamp(offYaw * YAW_GAIN, -1.0F, 1.0F);

        return new Stick(Mth.clamp(pitch + holdTheTurn(bank), -1.0F, 1.0F), roll, yaw);
    }

    /**
     * バンクが要求する引き量。旋回が降下ではなく旋回のままであるようにする。
     *
     * <p>欲しいのは、揚力の上向き成分が依然として重量に等しくなるために主翼が引くべき荷重、つまりバンク角の割線だ。
     * 水平で1、60度で2、90度で無限大へ発散する。垂直を越えたら諦める。引くべき「上」が無く、引けば機体をさらに地面へ
     * 向けるだけだからだ。
     */
    private static float holdTheTurn(float bank) {
        double upright = Math.cos(Math.toRadians(bank));

        if (upright <= UPRIGHT) {
            return 0.0F;
        }

        return Mth.clamp((float) (1.0 / upright - 1.0) * TURN_HOLD, 0.0F, 1.0F);
    }

    /**
     * マークを機首まわりの円錐内に保つ。
     *
     * <p>マウスが動いたときだけでなく毎tick必要だ。機体も回っているからである。機体がロールで離れていく間放置された
     * マークは自ずと届かない場所へ流れ、パイロットの操作では説明の付かない舵一杯を残すことになる。
     */
    private static void holdInCone() {
        Vec3 nose = aircraft.getNoseVector();

        if (aim.lengthSqr() < 1.0E-8) {
            aim = nose;

            return;
        }

        double off = Math.toDegrees(Math.acos(Mth.clamp(aim.dot(nose), -1.0, 1.0)));

        if (off <= CONE) {
            return;
        }

        Vec3 axis = nose.cross(aim);

        // 真正面か真後ろ。戻すために回す平面が存在しない場合。
        aim = axis.lengthSqr() < 1.0E-9 ? nose : spin(nose, axis.normalize(), CONE);
    }


    /** 方向を軸周りに回す（度）。どちらも既に単位ベクトルなので、結果も単位ベクトルのままになる。 */
    private static Vec3 spin(Vec3 direction, Vec3 axis, double degrees) {
        Vector3f turned = new Quaternionf()
                .rotateAxis((float) Math.toRadians(degrees), (float) axis.x, (float) axis.y, (float) axis.z)
                .transform(new Vector3f((float) direction.x, (float) direction.y, (float) direction.z));

        return new Vec3(turned.x(), turned.y(), turned.z()).normalize();
    }
}
