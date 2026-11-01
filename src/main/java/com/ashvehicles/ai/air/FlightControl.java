package com.ashvehicles.ai.air;

import javax.annotation.Nullable;

import com.ashvehicles.vehicle.Attitude;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * AI の操縦桿。「どちらへ機首を向けたいか」「どれだけの速さで、どちらを向いて浮いていたいか」を、人のパイロットが
 * 出すのと同じ3軸の舵（{@link Stick}）に直す。
 *
 * <p><b>固定翼機の舵は、人のマウス操縦（{@code client/MouseAim}）と同じ式で出す。</b> 機首からの外れを左右と上下に
 * 分け、左右はバンク角の要求に、上下は昇降舵に、進行中の回転で減衰させる。人の手で試合に耐えてきた式であり、AI の
 * ために別の飛ばし方を作れば、機体の調整のたびに2つの操縦を見直すことになる
 * （[[bots-are-a-pilot-object-on-the-vehicle]]）。あちらはクライアントの物なので、サーバーで回るここへ写してある。
 * 違いは3つ——真後ろの方向を、機首の円錐へ引き戻すと真っ直ぐに化けるので、右へ回り始める向きに直す。そして固定翼機は、
 * 円錐の外の向きへは水平に回り（{@link #level}）、引きは飛行経路が求める上りを越えない分だけにする（{@link #holdPath}）
 * ——人は機首が上がり過ぎるのを見てマウスを戻すが、AI はそのまま縦か斜めのループに入り、爆撃機はその頂で失速する
 * （2026-09-14）。
 *
 * <p><b>回転の速さは自分で測る。</b> 機体の {@code getPitchDelta} は、tick の頭で前の姿勢が今の姿勢に揃えられた後に
 * 読むと常に0になり、AI が考えるのはまさにその位置だ。だから前の tick に見た姿勢を覚えて差を取る
 * （{@link #sense}）。
 */
public final class FlightControl {
    /** 機首を向けたい方向を、機首からこの角の円錐へ引き戻す（{@code MouseAim.CONE}）。 */
    private static final double CONE = Math.toRadians(35.0);
    private static final double CONE_COS = Math.cos(CONE);
    private static final double CONE_SIN = Math.sin(CONE);

    /** 以下は {@code MouseAim} と同じ値。 */
    private static final float BANK_PER_DEGREE = 3.0F;
    private static final float TURN_HOLD = 0.8F;
    private static final double UPRIGHT = 0.1;
    private static final float ROLL_GAIN = 0.04F;
    private static final float ROLL_DAMPING = 0.15F;
    private static final float PITCH_GAIN = 0.08F;
    private static final float PITCH_DAMPING = 0.15F;
    private static final float YAW_GAIN = 0.02F;
    private static final float ROTOR_YAW_GAIN = 0.05F;
    private static final float YAW_DAMPING = 0.15F;

    /**
     * 回転翼機: 速さの差1ブロック/tick あたりに傾ける角（度）、傾きの差1度あたりの舵、回転1度/tick あたりの減衰。
     *
     * <p>サイクリックは角速度の指令で、機体は置いた姿勢を保つ（{@code AircraftEntity} の回転翼の飛行）。だから速さは
     * 「傾き」で作り、傾きは舵で作る——内側の輪が姿勢、外側の輪が速さ。
     */
    private static final float TILT_PER_SPEED = 12.0F;
    private static final float TILT_GAIN = 0.1F;
    private static final float TILT_DAMPING = 0.3F;

    /** 回転翼機が、求める速さの向きへ機首を回し始める速さ（ブロック/tick）。 */
    private static final double TURN_SPEED = 0.3;

    /** コレクティブ: 高さの差1ブロックあたりの上り下りの要求。20ブロック離れていれば一杯。 */
    private static final float CLIMB_PER_BLOCK = 0.05F;

    /** スロットル: 速さの差1ブロック/tick あたりの要求。 */
    private static final float THROTTLE_GAIN = 2.0F;

    /** これより上へスロットルを押し込まない。押し切り続けるとアフターバーナーのゲートが開く（{@code AircraftEntity.tickAfterburner}）。 */
    private static final float LEVER_TOP = 0.98F;

    /** 機首の円錐の外の向きへ水平に回る間に許す上り下りの角（度、{@link #level}）。 */
    private static final double TURN_CLIMB = 10.0;
    private static final double TURN_CLIMB_RADIANS = Math.toRadians(TURN_CLIMB);

    /**
     * 飛行経路で引きを切る（{@link #holdPath}）: 求めた上り角を越えてよい角（度）、越えた1度あたりに抜く引き、抜いた先で
     * 押してよい量の上限。
     */
    private static final double PATH_SLACK = 3.0;
    private static final float PATH_GAIN = 0.05F;
    private static final float PATH_PUSH = 0.3F;

    /** 失速の手前の余裕を越えた速さ（失速速度の倍率）1あたりに許す上りの角（度、{@link #sustainableClimb}）。 */
    private static final double CLIMB_PER_RESERVE = 30.0;

    /** 操縦桿の3軸。各 -1〜1。 */
    public record Stick(float pitch, float roll, float yaw) {
        public static final Stick NONE = new Stick(0.0F, 0.0F, 0.0F);
    }

    @Nullable
    private Quaternionf previous;
    private float pitchRate;
    private float yawRate;
    private float rollRate;

    /** 前の tick からの回転を測る。舵を出す前に毎 tick 1回。 */
    public void sense(Quaternionf attitude) {
        if (this.previous == null) {
            this.pitchRate = 0.0F;
            this.yawRate = 0.0F;
            this.rollRate = 0.0F;
        } else {
            float[] rates = bodyRates(this.previous, attitude);

            this.pitchRate = rates[0];
            this.yawRate = rates[1];
            this.rollRate = rates[2];
        }

        this.previous = new Quaternionf(attitude);
    }

    /** 機首をその方向へ持っていく舵。測った回転で減衰させる。 */
    public Stick point(Quaternionf attitude, Vec3 want, boolean rotorcraft, float bankLimit) {
        return point(attitude, want, this.pitchRate, this.yawRate, this.rollRate, rotorcraft, bankLimit);
    }

    /** 回転翼機をその水平速度へ運び、その向きへ機首を向ける舵。測った回転で減衰させる。 */
    public Stick rotor(Quaternionf attitude, Vec3 motion, Vec3 wanted, @Nullable Vec3 face, float tiltLimit) {
        return rotor(attitude, motion, wanted, face, this.pitchRate, this.yawRate, this.rollRate, tiltLimit);
    }

    /**
     * 機首を {@code want} の方向へ持っていく舵。
     *
     * <p>固定翼機はバンクして引いて向ける。回転翼機は機首の上下を昇降舵で、左右をペダルで向け、ロールは触らない
     * （{@code MouseAim.stick} と同じ分け方）。
     *
     * @param pitchRate 機首上げの回転（度/tick）
     * @param yawRate   機首右への回転（度/tick）
     * @param rollRate  右ロールの回転（度/tick）
     * @param bankLimit 要求してよいバンク角（度）
     */
    public static Stick point(Quaternionf attitude, Vec3 want, float pitchRate, float yawRate, float rollRate,
            boolean rotorcraft, float bankLimit) {
        Vec3 nose = Attitude.nose(attitude);
        Vec3 up = Attitude.up(attitude);
        Vec3 right = Attitude.right(attitude);
        Vec3 aim = inCone(nose, right, want);
        float offYaw = (float) Math.toDegrees(Mth.atan2(aim.dot(right), aim.dot(nose)));
        float offPitch = (float) Math.toDegrees(Math.asin(Mth.clamp(aim.dot(up), -1.0, 1.0)));
        float pitch = Mth.clamp(offPitch * PITCH_GAIN - pitchRate * PITCH_DAMPING, -1.0F, 1.0F);

        if (rotorcraft) {
            return new Stick(pitch, 0.0F, Mth.clamp(offYaw * ROTOR_YAW_GAIN - yawRate * YAW_DAMPING, -1.0F, 1.0F));
        }

        float bank = Attitude.bank(attitude);
        float wanted = Mth.clamp(offYaw * BANK_PER_DEGREE, -bankLimit, bankLimit);
        float roll = Mth.clamp((wanted - bank) * ROLL_GAIN - rollRate * ROLL_DAMPING, -1.0F, 1.0F);

        return new Stick(Mth.clamp(pitch + holdTheTurn(bank), -1.0F, 1.0F), roll,
                Mth.clamp(offYaw * YAW_GAIN, -1.0F, 1.0F));
    }

    /**
     * 回転翼機の舵。前へ速めたいなら機首を下げ、右へ速めたいなら右へ傾ける——ディスクが傾いた向きへ機体は引かれる。
     * 機首は {@code face}（無ければ、ある程度の速さを求めているときだけその向き）へペダルで向ける。高さは
     * コレクティブ（{@link #collective}）が別に持つ。
     *
     * @param motion    今の速度（ブロック/tick）
     * @param wanted    求める水平速度（ブロック/tick）
     * @param face      機首を向けたい水平の向き。無ければ null
     * @param tiltLimit 傾けてよい角（度）
     */
    public static Stick rotor(Quaternionf attitude, Vec3 motion, Vec3 wanted, @Nullable Vec3 face, float pitchRate,
            float yawRate, float rollRate, float tiltLimit) {
        Vec3 forward = flat(Attitude.nose(attitude));
        Vec3 right = flat(Attitude.right(attitude));

        if (forward == null || right == null) {
            return Stick.NONE;
        }

        double speedAhead = motion.x * forward.x + motion.z * forward.z;
        double speedAside = motion.x * right.x + motion.z * right.z;
        double wantAhead = wanted.x * forward.x + wanted.z * forward.z;
        double wantAside = wanted.x * right.x + wanted.z * right.z;
        float wantPitch = Mth.clamp((float) -(wantAhead - speedAhead) * TILT_PER_SPEED, -tiltLimit, tiltLimit);
        float wantBank = Mth.clamp((float) (wantAside - speedAside) * TILT_PER_SPEED, -tiltLimit, tiltLimit);
        float pitchUp = -Attitude.elevation(attitude);
        float bank = Attitude.bank(attitude);
        float pitch = Mth.clamp((wantPitch - pitchUp) * TILT_GAIN - pitchRate * TILT_DAMPING, -1.0F, 1.0F);
        float roll = Mth.clamp((wantBank - bank) * TILT_GAIN - rollRate * TILT_DAMPING, -1.0F, 1.0F);
        Vec3 look = face != null ? flat(face)
                : Math.sqrt(wanted.x * wanted.x + wanted.z * wanted.z) > TURN_SPEED ? flat(wanted) : null;
        float yaw = -yawRate * YAW_DAMPING;

        if (look != null) {
            yaw += (float) Math.toDegrees(Mth.atan2(look.x * right.x + look.z * right.z,
                    look.x * forward.x + look.z * forward.z)) * ROTOR_YAW_GAIN;
        }

        return new Stick(pitch, roll, Mth.clamp(yaw, -1.0F, 1.0F));
    }

    /**
     * コレクティブへの要求。正で上り、負で下り、0でその高さを保つ（{@code AircraftEntity.trimCollective}）。
     */
    public static float collective(double y, double wantY) {
        return Mth.clamp((float) (wantY - y) * CLIMB_PER_BLOCK, -1.0F, 1.0F);
    }

    /**
     * スロットルレバーを動かす要求。レバーは要求を積分する（{@code AircraftInput.throttle}）ので、速さの差をそのまま
     * 渡せば速さに釣り合う位置へ寄っていく。{@value #LEVER_TOP} より上へは押し込まない。
     *
     * @param lever 今のレバー位置（0〜1）
     */
    public static float throttle(double speed, double wanted, float lever) {
        float push = Mth.clamp((float) (wanted - speed) * THROTTLE_GAIN, -1.0F, 1.0F);

        return lever >= LEVER_TOP && push > 0.0F ? 0.0F : push;
    }

    /**
     * 向けたい方向を、機首からの円錐の中へ引き戻した物。外れた向きの側へ円錐の縁まで回す。
     *
     * <p><b>真後ろは右へ。</b> 機首と真後ろの間には回す平面が決まらず、そのままでは「真っ直ぐ飛べ」に化ける——追って
     * くる相手や通り過ぎた目標から、機体はいつまでも離れていく。
     */
    public static Vec3 inCone(Vec3 nose, Vec3 right, Vec3 want) {
        double length = want.length();

        if (length < 1.0E-8) {
            return nose;
        }

        Vec3 aim = want.scale(1.0 / length);
        double along = aim.dot(nose);

        if (along >= CONE_COS) {
            return aim;
        }

        Vec3 across = aim.subtract(nose.scale(along));
        Vec3 side = across.lengthSqr() < 1.0E-8 ? right : across.normalize();

        return nose.scale(CONE_COS).add(side.scale(CONE_SIN));
    }

    /**
     * 固定翼機: 機首から水平に円錐より外の向きを、<b>水平に回る</b>向きに直す。水平の向きを機首の側から円錐の縁まで
     * 回し、上り下りは {@value #TURN_CLIMB} 度までに切る。円錐の内なら何もしない。真後ろは {@link #inCone} と同じく
     * 機体の右へ。
     *
     * <p><b>{@link #inCone} は、機首と向けたい方向を含む平面の中で円錐の縁へ引き戻す。</b> 後ろの少し上なら、その平面は
     * ほぼ縦になる——向けたい方向が真後ろから上へ20度ずれているだけで回る向きは「上」になり、機体はバンクせずに
     * 引き続けてループに入る（2026-09-14 の指示「爆撃機とかで垂直に旋回しようとする動きがあるので控えたい」）。人は
     * マウスを横へ振り直すが、AI の向けたい方向は抜けた後の旋回の間ずっと後ろにある。
     */
    public static Vec3 level(Quaternionf attitude, Vec3 want) {
        Vec3 nose = flat(Attitude.nose(attitude));
        Vec3 bearing = flat(want);

        if (nose == null || bearing == null || nose.dot(bearing) >= CONE_COS) {
            return want;
        }

        Vec3 side = new Vec3(-nose.z, 0.0, nose.x);
        double across = side.dot(bearing);

        if (Math.abs(across) < 1.0E-3 ? side.dot(Attitude.right(attitude)) < 0.0 : across < 0.0) {
            side = side.scale(-1.0);
        }

        Vec3 turn = nose.scale(CONE_COS).add(side.scale(CONE_SIN));
        double rise = Mth.clamp(Math.asin(Mth.clamp(want.y / want.length(), -1.0, 1.0)), -TURN_CLIMB_RADIANS,
                TURN_CLIMB_RADIANS);

        return new Vec3(turn.x * Math.cos(rise), Math.sin(rise), turn.z * Math.cos(rise));
    }

    /**
     * 固定翼機: 引きを、飛行経路の上り角が {@code want} の上り角を {@value #PATH_SLACK} 度より越えない分に切る。
     * バンクの分の引き（{@link #holdTheTurn}）は残す——水平に回るのに要る引きまでは削らない。押している舵と、背面
     * （バンク90度以上）には何もしない。
     *
     * <p><b>向けたい方向が機首の円錐の外にある間、マウス操縦の式は引きを一杯に張り付ける</b>（円錐の縁は機首から35度で、
     * 昇降舵は外れ1度に {@value #PITCH_GAIN}）。バンクは「外れの左右 × 3」が付いた角で釣り合い、真横の向きなら60度前後
     * になる。そこで一杯に引けば翼の荷重の上向きの分が重さを越え、機体は回りながら上っていく——斜めのループだ。ロールの
     * 遅い機体（B-52 の {@code roll_rate} は戦闘機の5分の1ほど）は、バンクが20度しか付かないうちにほぼ一杯に引いていて、
     * ほとんど真上へ上がる。上った分だけ速さを失い、頂で失速する。
     *
     * @param motion 今の速度（ブロック/tick）
     * @param want   機首を向けたい方向（{@link #point} へ渡した物）
     */
    public static float holdPath(float pitch, Quaternionf attitude, Vec3 motion, Vec3 want) {
        double speed = motion.length();
        double length = want.length();
        float bank = Attitude.bank(attitude);

        if (pitch <= 0.0F || speed < 1.0E-3 || length < 1.0E-8 || Math.abs(bank) >= 90.0F) {
            return pitch;
        }

        double path = Math.toDegrees(Math.asin(Mth.clamp(motion.y / speed, -1.0, 1.0)));
        double wanted = Math.toDegrees(Math.asin(Mth.clamp(want.y / length, -1.0, 1.0)));
        float allowed = holdTheTurn(bank) + (float) (wanted + PATH_SLACK - path) * PATH_GAIN;

        return Math.min(pitch, Math.max(allowed, -PATH_PUSH));
    }

    /**
     * その速さで、失速速度の {@code margin} 倍の余裕を残して水平に回れるバンク（度）。失速速度を1G の失速と見れば、翼が
     * 出せる荷重倍数は (速さ ÷ 失速速度)²、水平旋回に要るのは 1 ÷ cos(バンク)。余裕の速さ以下なら0。
     *
     * <p>巡航（失速の2.8倍）で余裕1.3なら78度で、普段のバンクの上限（75度）は変わらない。上って失速の1.6倍まで落ちれば
     * 49度——遅い機体ほど深く傾けて回れず、傾けて引けば翼の荷重で速さを失う。
     */
    public static float sustainableBank(double speed, double stallSpeed, double margin) {
        double reserve = speed / Math.max(stallSpeed * margin, 1.0E-6);
        double load = reserve * reserve;

        return load <= 1.0 ? 0.0F : (float) Math.toDegrees(Math.acos(1.0 / load));
    }

    /**
     * その速さで許す上りの角（度）。失速速度の {@code margin} 倍で0、そこから倍率1ごとに {@value #CLIMB_PER_RESERVE} 度。
     * 余裕1.3なら、巡航（失速の2.8倍）で45度、上って失速の2倍まで落ちれば21度、1.6倍で9度——上るほど速さを失い、上って
     * よい角が自分で閉じていく。降りる角には使わない。
     */
    public static double sustainableClimb(double speed, double stallSpeed, double margin) {
        return Math.max(0.0, (speed / Math.max(stallSpeed, 1.0E-6) - margin) * CLIMB_PER_RESERVE);
    }

    /**
     * 姿勢 {@code from} から {@code to} への、機体の軸で測った回転（度）。並びは機首上げ・機首右・右ロールで、符号は
     * {@code AircraftEntity.getPitchDelta} / {@code getYawDelta} / {@code getRollDelta} と同じ。
     */
    public static float[] bodyRates(Quaternionf from, Quaternionf to) {
        Quaternionf change = new Quaternionf(from).conjugate().mul(to).normalize();

        // q と -q は同じ回転。遠回りの側で読むと、ほとんど回っていない機体が1回転近く回ったと答える。
        if (change.w < 0.0F) {
            change.set(-change.x, -change.y, -change.z, -change.w);
        }

        float sine = (float) Math.sqrt(Math.max(0.0, 1.0 - change.w * change.w));

        if (sine < 1.0E-5F) {
            return new float[3];
        }

        float scale = (float) Math.toDegrees(2.0 * Math.acos(Mth.clamp(change.w, -1.0F, 1.0F))) / sine;

        return new float[] {-change.x * scale, -change.y * scale, change.z * scale};
    }

    /** バンクで失う上向きの揚力を補う引き（{@code MouseAim.holdTheTurn}）。 */
    private static float holdTheTurn(float bank) {
        double upright = Math.cos(Math.toRadians(bank));

        if (upright <= UPRIGHT) {
            return 0.0F;
        }

        return Mth.clamp((float) (1.0 / upright - 1.0) * TURN_HOLD, 0.0F, 1.0F);
    }

    /** 水平成分の単位ベクトル。ほぼ真上か真下なら null。 */
    @Nullable
    private static Vec3 flat(Vec3 direction) {
        double length = Math.sqrt(direction.x * direction.x + direction.z * direction.z);

        return length < 1.0E-4 ? null : new Vec3(direction.x / length, 0.0, direction.z / length);
    }
}
