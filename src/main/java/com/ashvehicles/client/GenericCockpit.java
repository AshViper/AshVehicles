package com.ashvehicles.client;

import java.util.Arrays;
import java.util.List;

import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.vehicle.VehicleChassis;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * {@code camera.displays} を書いていない機体の MFD。座席の目の前の決まった3か所に画面を置く。
 *
 * <p>位置は、もとは共通の計器盤の模型の画面から測った物で、模型そのものは描かない。目の位置
 * {@link #EYE} で座席の目へ合わせるので、機体ごとに書くことは無い。機体へ {@code camera.displays} を
 * 書けばこちらは消える。
 * 要らない機体は {@code camera.generic_cockpit: false} で外す。
 *
 * <p>同梱の機体は全員の席の画面を書いてある（{@code tool/mfd/place.py} がこの3枚を当たり判定箱の計器盤の面へ
 * 載せた物）。この決まった位置は計器盤の形を知らないので、機体によっては盤に埋まり、0.8 ブロック手前に浮き、
 * ヘリの客席では宙に出ていた。これが出るのはパックの機体だけ。
 */
public final class GenericCockpit {
    /** 画面の位置を測った模型の中で目が来る点。Blockbench の画素。 */
    private static final Vec3 EYE = new Vec3(0.0, 14.5, 13.0);
    /** 画面の傾き。14 度、上端が奥へ寝ている。 */
    private static final float TILT = 14.0F;

    /** 画面3枚。中心（回転後の画素）・幅・高さ・頁。 */
    private static final Screen[] SCREENS = {
            new Screen(new Vec3(-3.446, 10.384, 2.641), 3.9, 3.1, "nav"),
            new Screen(new Vec3(-0.004, 9.414, 2.883), 2.6, 4.6, "pfd"),
            new Screen(new Vec3(3.446, 10.384, 2.641), 3.9, 3.1, "sms"),
    };

    private record Screen(Vec3 centre, double width, double height, String page) {
    }

    /**
     * 席を書いていない画面を、その席の物と見なす目からの距離（ブロック）。書かれた画面は目から 0.4〜1.1
     * ブロックにあり（並列複座の Su-34 で 1.1）、タンデム複座の後席から前席の画面までは 1.7 以上ある。
     */
    private static final double REACH = 1.25;

    private static Vec3 cachedEye;
    private static List<VehicleChassis.Display> cached = List.of();
    private static List<VehicleChassis.Display> ownSource;
    private static int ownSeat = -1;
    private static List<VehicleChassis.Display> ownCached = List.of();

    private GenericCockpit() {
    }

    /**
     * このクライアントの席から見える画面。機体が書いた画面のうちその席の物、1枚も無ければ共通の操縦席の画面。
     *
     * <p>席ごとに分けるのは、書かれた画面がどれも前席の計器盤にあるから。MiG-31 の後席に乗ると、以前は
     * 2 ブロック先の前席の計器盤だけが見え、自分の前には何も無かった。席を {@code seat} で書いた画面は
     * その席だけが、書いていない画面は目から {@link #REACH} 以内の席が見る。後席の画面を模型に合わせて
     * 書くまでは、後席には共通の操縦席の3枚がその目の前に出る。
     */
    public static List<VehicleChassis.Display> displays(AircraftEntity aircraft) {
        List<VehicleChassis.Display> own = aircraft.getStats().camera().displays().orElse(List.of());
        int seat = seat(aircraft);
        Vec3 eye = aircraft.getSeatEye(seat);

        if (own != ownSource || seat != ownSeat) {
            ownSource = own;
            ownSeat = seat;
            ownCached = own.stream().filter(display -> display.seat()
                    .map(owner -> owner == seat)
                    .orElseGet(() -> display.pos().distanceTo(eye) <= REACH)).toList();
        }

        if (!ownCached.isEmpty() || !aircraft.getStats().camera().genericCockpit()) {
            return ownCached;
        }


        if (!eye.equals(cachedEye)) {
            cachedEye = eye;
            cached = Arrays.stream(SCREENS).map(screen -> new VehicleChassis.Display(
                    local(eye, screen.centre()), (float) (screen.width() / 16.0), (float) (screen.height() / 16.0),
                    TILT, 0.0F, screen.page(), List.of())).toList();
        }

        return cached;
    }

    /** このクライアントのプレイヤーの席。乗っていなければ操縦席。 */
    private static int seat(AircraftEntity aircraft) {
        Minecraft minecraft = Minecraft.getInstance();

        return minecraft.player != null && minecraft.player.getVehicle() == aircraft
                ? Math.max(0, aircraft.getSeatIndex(minecraft.player)) : 0;
    }

    /** 模型の画素の点を機体軸へ。x は右、z は前（模型は -z が前）。 */
    private static Vec3 local(Vec3 eye, Vec3 pixel) {
        return new Vec3(eye.x + (pixel.x - EYE.x) / 16.0, eye.y + (pixel.y - EYE.y) / 16.0,
                eye.z + (EYE.z - pixel.z) / 16.0);
    }
}
