package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.weapon.GunStations;

import org.joml.Quaternionf;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * 三人称カメラを機体のデータパックファイルが指定する位置に据え、その間ずっと機体全体を画面に収める。
 *
 * <p>バニラはプレイヤーの目の4ブロック後ろに座ることしか知らない。15ブロックの機体ではそれは胴体内のどこかだ。
 * 代わりにカメラを直接、機体中心から {@code camera.pos} の位置へ置く。それがどこに着くか、なぜ機体軸ではなく
 * 視線に沿って測るかは {@link ChaseCamera} の管轄。
 *
 * <p>一人称は逆の場合で、逆の扱いを受ける。カメラは機体座標系の {@code camera.cockpit} でコックピットに固定され、
 * 主翼と共にロールする。視線方向は依然としてパイロットが選ぶ。それが機体を照準する手段だからだ。
 *
 * <p>2つの半分は別経路で届く。バンクは NeoForge のカメライベント経由で、これは {@code Camera.setup} の冒頭で
 * 発火し角度しか設定できない。位置は setup が自前の値を書き終えるまで待つ必要があり、そのために
 * {@link com.ashvehicles.mixin.CameraMixin} がある。イベントから位置を設定すると、うまくいったように見えて
 * 黙って取り消される。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class AircraftCameraHandler {
    @SubscribeEvent
    public static void onCalculateCameraDistance(CalculateDetachedCameraDistanceEvent event) {
        if (riddenAircraft(event.getCamera()) != null) {
            // 位置は下で直接設定するので、バニラにはパイロットの目の位置に置いたままにしてもらう。
            event.setDistance(0.0F);
        }
    }

    /**
     * 一人称で水平線を主翼と共に傾ける。機体から取るのはバンクだけ。ピッチと方位はパイロットの視線であり、それが
     * 機体を照準する手段だ。三人称は直立のままにする。あちらで水平線が回っても機体が見づらくなるだけだからだ。
     *
     * <p>頭の向き自体は<em>両方</em>の視点で追従する。以前はカメラが分離した瞬間に切り捨てており、マウスをバニラ
     * へ返すのと同時に、バンクに耐えないワールド基準の方位も返していた——多くの人が実際に飛ぶ視点で、主翼が水平で
     * なくなった瞬間に「横」が横でなくなっていたわけだ。三人称は今もロールを取らないが、頭は同じ頭であり機体軸で
     * 回る。バニラは分離カメラをプレイヤー自身の視線に沿って置くので、追跡カメラもそれと共に回る。
     */
    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Camera camera = event.getCamera();
        AircraftEntity aircraft = riddenAircraft(camera);

        CockpitView.follow(aircraft);
        MouseAim.follow(aircraft);

        // 砲手席のガンカメラが受け持つ砲座。映像は砲から来るので、頭がどこを向いていようと画面は砲腔線を
        // 中心に据える。頭の方は動き続けており、砲はそれを追っている。GunCamera 参照。三人称とポッド視点
        // では砲に縛る理由が無い——あちらは機体全体を映す物と、別の装置が映している物だ。
        int station = aircraft == null || camera.isDetached() || PodCamera.isShowing()
                ? GunStations.NONE
                : GunCamera.stationOf(aircraft, camera.getEntity());

        GunCamera.follow(aircraft, station);

        if (aircraft == null) {
            return;
        }

        // ポッド表示中はどちらのカメラでも視界を完全にポッドが持つ。主翼に取り付けたボールから来る映像であり、
        // パイロットの頭がどこを向いていようと関係無い。安定化してあるので、主翼がどうロールしても水平線は水平線
        // の位置に留まる。PodCamera.viewRoll 参照。
        if (PodCamera.isShowing()) {
            float partialTick = (float) event.getPartialTick();
            event.setYaw(PodCamera.viewYaw(partialTick));
            event.setPitch(PodCamera.viewPitch(partialTick));
            event.setRoll(PodCamera.viewRoll(partialTick));

            return;
        }

        if (camera.isDetached()) {
            return;
        }

        if (station != GunStations.NONE) {
            Quaternionf bore = GunCamera.world(aircraft, station, (float) event.getPartialTick());
            event.setYaw(Attitude.heading(bore));
            event.setPitch(Attitude.elevation(bore));
            event.setRoll(Attitude.bank(bore));

            return;
        }

        // パイロットの頭の回転と同じ回転になる3つの角度。方位と仰角だけでは表現できないが、ロールも加えれば
        // どの姿勢もそのまま渡せる。
        Quaternionf view = CockpitView.world((float) event.getPartialTick());
        event.setYaw(Attitude.heading(view));
        event.setPitch(Attitude.elevation(view));
        event.setRoll(Attitude.bank(view));
    }

    /**
     * {@link com.ashvehicles.mixin.CameraMixin} から、バニラがカメラ配置を終えた後に呼ばれる。新しい位置が実際に
     * 定着する最初の瞬間がそこだ。
     */
    public static void placeCamera(Camera camera, Entity viewer, boolean detached, float partialTick) {
        if (!(viewer.getVehicle() instanceof AircraftEntity aircraft)) {
            return;
        }

        // 座席ではなくポッドにいる。パイロットが直前にどちらのカメラだったかは無関係で、映像はポッドが取り付け
        // られたステーションから来る。
        if (PodCamera.isShowing()) {
            camera.setPosition(PodCamera.eye(partialTick));

            return;
        }

        if (!detached) {
            // 砲手席のガンカメラは砲身に固定された箱で、砲と一緒に振れて上下する。機体が答える点と同じ点
            // だが、砲の角を2tickの間で補間して置き直してある。GunCamera.eye 参照。
            int station = GunCamera.stationOf(aircraft, viewer);

            // 実際に座っている座席の視点。複座機ではパイロットの物ではない。F-14 の後席は前部キャノピーの
            // 1.5ブロック後ろにおり、自分のキャノピーから外を見るべきだ。
            camera.setPosition(station == GunStations.NONE
                    ? aircraft.eyeOf(viewer, partialTick)
                    : GunCamera.eye(aircraft, station, viewer, partialTick));

            return;
        }

        ChaseCamera.place(camera, aircraft, aircraft.getStats().camera().pos(), partialTick);
    }

    /** カメラの持ち主が搭乗している機体。搭乗していなければ null。 */
    private static AircraftEntity riddenAircraft(Camera camera) {
        Entity viewer = camera.getEntity();

        return viewer != null && viewer.getVehicle() instanceof AircraftEntity aircraft ? aircraft : null;
    }

    private AircraftCameraHandler() {
    }
}
