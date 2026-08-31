package com.ashvehicles.client;

import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.vehicle.VehicleShape;
import com.ashvehicles.weapon.GunStations;

import org.joml.Quaternionf;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 砲手席のガンカメラ。砲に固定された箱から見た映像を、その席の一人称視点として出す。
 *
 * <p><b>なぜ席の目では足りないか。</b>砲手は自分の砲の側に座っていない。AC-130 の 30mm も 105mm も左舷へ
 * 撃つが、砲手は胴体の中にいる。席に目を置けば、撃っている間ずっと機内の壁を見ていることになる——狙う物は
 * 反対側の、しかも機体の外にある。振れる砲を持つ他の機体でも事情は同じで、砲が機体に対してどこを向けるかを
 * 決めているのはファイルの可動範囲であって、席から何が見えるかではない。
 *
 * <p><b>だからカメラを砲へ移す。</b>位置は {@code AircraftEntity.eyeToWorld} が運ぶ——席の目が
 * {@code "mount": "gun"} と書かれていれば、その点は砲の耳軸周りに運ばれる。ここが受け持つのは向きの方で、
 * 機体の姿勢に砲の方位と仰角を掛けた物をそのままカメラへ渡す。結果、画面の中心は常に砲腔線であり、
 * {@link GunSight} が置く弾道マークもそこに重なる。
 *
 * <p><b>マウスは今まで通り頭を動かす。</b>砲が追うのは射手の視線（{@code GunStations.aim}）なので、操作は
 * 「見た方へ砲が向く」のままだ。変わるのは見えている物で、砲が旋回速度の分だけ遅れて追い付き、可動端では
 * 頭だけが先へ行って映像が止まる。ポッド視点と同じ関係——手元は動き続け、映しているのは装置の向き。
 *
 * <p>三人称では何もしない。あちらのカメラは機体全体を映す物で、砲の向きに縛る理由が無い。
 */
public final class GunCamera {
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    /** 今カメラを預けている相手。乗り込み・席移動・視点切り替えのたびに引き直す。 */
    private static AircraftEntity holding;
    private static int holdingStation = GunStations.NONE;

    /**
     * 砲の向きを、届いた順に2つ。
     *
     * <p>砲の向きは1tickに1つの値で、同期もtick単位で届く。それをそのままカメラに渡すと、毎秒20回だけ
     * 動く視界になる——4度/tickで振れる砲では、旋回のたびに画面が階段状に飛ぶ。描く物にとっては見えない
     * 差だが、視界そのものにとっては違う。だから他の全てと同じように、2tickの間を補間して渡す。
     */
    private static float wasYaw;
    private static float wasPitch;
    private static float isYaw;
    private static float isPitch;
    /** 直前に値を取り込んだ tick。同じ tick 内の2フレーム目で2度進めないための物。 */
    private static long stamp = Long.MIN_VALUE;

    private GunCamera() {
    }

    /**
     * その搭乗者の一人称が砲に据えられていれば、その砲座の番号。そうでなければ {@link GunStations#NONE}。
     *
     * <p>2つの条件を両方満たす場合だけ。席の目が砲に取り付いていると機体ファイルが言っていること、そして
     * その席が実際に砲座を受け持っていること。片方だけの機体——砲座を持たない席に {@code mount} が書かれて
     * いる、あるいは砲座はあるが目は機体に固定——では従来通りの視点になる。
     */
    public static int stationOf(AircraftEntity aircraft, Entity viewer) {
        int seat = aircraft.getSeatIndex(viewer);

        if (aircraft.getSeatEyeMount(seat) == VehicleShape.Mount.HULL) {
            return GunStations.NONE;
        }

        return aircraft.getStations().stationForSeat(seat);
    }

    /**
     * カメラから毎フレーム1度呼ばれる。砲の向きを取り込み、砲手の頭を砲の可動範囲に保つ（{@link #rein}）。
     * 席に着いた最初の1回だけは、頭をその砲が今向いている方へ置き直す。
     *
     * <p>頭を置き直すのは、置かないと砲が座った瞬間から頭のある正面へ振れ始めるからだ。砲手が席に着いて
     * まずすることが「自分の砲を目標へ戻す」ことになるのは順序が逆で、着いた時点で見えているべきなのは
     * 砲が既に狙っている先だ。
     *
     * <p>砲の向きが同期タグでまだ届いていない間は何もしない（{@link GunStations#isLaid}）。届いていない
     * 砲座に訊けば機首方向が返るので、それを信じて頭を正面へ置いてしまえば、直後に本当の向きが来ても
     * 手遅れになる。
     */
    public static void follow(AircraftEntity aircraft, int station) {
        if (aircraft == null || station == GunStations.NONE
                || !aircraft.getStations().isLaid(station)) {
            holding = null;
            holdingStation = GunStations.NONE;
            stamp = Long.MIN_VALUE;

            return;
        }

        GunStations stations = aircraft.getStations();
        boolean fresh = aircraft != holding || station != holdingStation;

        if (fresh) {
            holding = aircraft;
            holdingStation = station;
            CockpitView.lookAlong(stations.direction(station, 1.0F));
        }

        rein(aircraft, station);

        long now = aircraft.level().getGameTime();

        if (fresh || stamp == Long.MIN_VALUE) {
            wasYaw = isYaw = stations.yawOf(station);
            wasPitch = isPitch = stations.pitchOf(station);
        } else if (now != stamp) {
            wasYaw = isYaw;
            wasPitch = isPitch;
            isYaw = stations.yawOf(station);
            isPitch = stations.pitchOf(station);
        }

        stamp = now;
    }

    /**
     * 頭を、砲が実際に向ける範囲へ収める。
     *
     * <p>ガンカメラでは頭そのものは映らない。映っているのは砲で、頭は「砲にどこを向けと言っているか」で
     * しかない。だから可動端の外まで頭を振れるようにしておくと、砲手には何も見えないまま入力だけが溜まり、
     * 戻す時に同じ角度分の空振りが要る——操作が遅れているようにしか感じられない不感帯だ。頭を砲の範囲に
     * 縛れば、マウスを動かした分は必ず砲が動いた分になる。
     *
     * <p>収め方はサーバーが砲を据えるのと同じ式（{@code GunStations.aim}）にしてある。両者が違う角を
     * 「範囲内」と呼べば、砲手の画面と実際に撃つ方向が食い違う。
     */
    private static void rein(AircraftEntity aircraft, int station) {
        AircraftDefinition.Station laid = aircraft.getStations().station(station);
        Quaternionf attitude = aircraft.getAttitude(1.0F);
        Vec3 body = Attitude.toBody(attitude, CockpitView.lookVector(1.0F));

        if (body.lengthSqr() < 1.0E-6) {
            return;
        }

        body = body.normalize();

        double yaw = Math.toRadians(laid.clampYaw((float) Math.toDegrees(Math.atan2(body.x, body.z))));
        double pitch = Math.toRadians(laid.clampPitch(
                (float) Math.toDegrees(Math.asin(Mth.clamp(body.y, -1.0, 1.0)))));
        double flat = Math.cos(pitch);

        CockpitView.lookAlong(Attitude.toWorld(attitude,
                new Vec3(Math.sin(yaw) * flat, Math.sin(pitch), Math.cos(yaw) * flat)));
        CockpitView.applyToPlayer();
    }

    /**
     * ワールドでのガンカメラの向き。機体の姿勢に、砲の方位と仰角を掛けた物。
     *
     * <p>{@link GunStations#direction} とまったく同じ方向を向く回転で、そちらがベクトルしか答えないのに
     * 対しこちらは水平線の傾きも持っている。カメラに要るのは3角なので、方向だけでは足りない。
     */
    public static Quaternionf world(AircraftEntity aircraft, int station, float partialTick) {
        // 方位は右が正、仰角は上げが正。どちらも Attitude.rotate と同じ符号の約束で機体姿勢へ重ねる。
        return new Quaternionf(aircraft.getAttitude(partialTick))
                .rotateY(-yawAt(aircraft, station, partialTick) * DEG_TO_RAD)
                .rotateX(-pitchAt(aircraft, station, partialTick) * DEG_TO_RAD)
                .normalize();
    }

    /**
     * ワールドでのガンカメラの位置。
     *
     * <p>{@code AircraftEntity.eyeOf} が答えるのと同じ点を、補間した砲の角で置き直した物。向きだけを滑ら
     * かにして位置をtick刻みのまま残せば、視界が回るたびに数センチずつ跳ねる——砲身に固定された箱なのだ
     * から、2つは同じ角で動かねばならない。
     */
    public static Vec3 eye(AircraftEntity aircraft, int station, Entity viewer, float partialTick) {
        Vec3 seated = aircraft.getSeatEye(aircraft.getSeatIndex(viewer));
        Vec3 carried = aircraft.getStations().carry(station, seated,
                yawAt(aircraft, station, partialTick), pitchAt(aircraft, station, partialTick));

        return aircraft.toWorld(carried, partialTick);
    }

    /**
     * 補間した砲腔線。クライアントがこのフレームに「砲はここを向いている」として扱う方向。
     *
     * <p>{@link GunStations#direction} と同じ物の、tick間を埋めた版。カメラだけでなく照準マークもここから
     * 取る（{@link GunSight}）。片方が補間した値で、もう片方が1tickに1つの値で描けば、画面の中心と弾道
     * マークが旋回のたびに数度ずれて震える。同じフレームに同じ砲を指す物は同じ角から出さねばならない。
     *
     * <p>ガンカメラでない砲——溜めてある2つの値がその砲座の物でない場合——では砲座の今の角をそのまま返す
     * ので、パイロットが自分で振る砲でも従来通り正しい。
     */
    public static Vec3 bore(AircraftEntity aircraft, int station, float partialTick) {
        return Attitude.nose(world(aircraft, station, partialTick));
    }

    private static float yawAt(AircraftEntity aircraft, int station, float partialTick) {
        return held(aircraft, station)
                ? wasYaw + Mth.degreesDifference(wasYaw, isYaw) * partialTick
                : aircraft.getStations().yawOf(station);
    }

    private static float pitchAt(AircraftEntity aircraft, int station, float partialTick) {
        return held(aircraft, station)
                ? Mth.lerp(partialTick, wasPitch, isPitch)
                : aircraft.getStations().pitchOf(station);
    }

    /** 溜めてある2つの値がこの砲座の物か。違えば補間する材料が無いので、今の角をそのまま使う。 */
    private static boolean held(AircraftEntity aircraft, int station) {
        return aircraft == holding && station == holdingStation && stamp != Long.MIN_VALUE;
    }
}
