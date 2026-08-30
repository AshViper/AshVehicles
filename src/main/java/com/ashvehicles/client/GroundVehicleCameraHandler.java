package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.GroundVehicleEntity;

import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * 三人称カメラを地上車両から後ろへ下げ、車両へ向けて下向きに倒す。
 *
 * <p>バニラの4ブロックは搭乗者の目から測られるが、戦車はそれより長い。カメラはエンジンデッキの上あたりに出て、
 * 画面は車体で埋まり、乗員が奪い合っている地面は何も映らない。代わりにカメラを直接、車両中心から
 * {@code camera.pos} の位置へ置く——{@link ChaseCamera} 参照。機体と同じ配置で、理由も同じだ。
 *
 * <p>ここで違うのは傾きだ。機体が自身の視線に沿って見るのは、重要な物が同じ高度の前方にあるから。戦車の目標は
 * 乗っている地面の上にあり、砲身に沿って水平に置いた視界は上半分を空に費やす。{@code camera.tilt} は視界全体を
 * 数度下へ回し、それに伴ってカメラも持ち上がる——オフセットは見下ろしている軸に沿って測るからだ——ので、車両を
 * 上から見る形になり、空だった場所に地面が入る。
 *
 * <p>倒れるのは視界であって乗員ではない。マウスは従来通り頭を動かすし、ここはそれに触れない。砲がそれに対して
 * 行うのは同じ量だけ下げることだ——{@code GroundVehicleEntity.setSightTilt} 参照。{@link GroundVehicleInputHandler}
 * が毎tick傾きを渡している——ので、画面中央は引き続き砲の線であり続ける。放っておけば両者は傾きの分そのままずれ、
 * 乗員が画面中央を目標に合わせても砲はその10度上を向き、弾はそちらへ飛ぶ。砲自身のマークは今も、弾が到達する点に
 * ワールド上で描かれる。それが方向だけでなく距離をも示す仕組みであり——{@link GroundVehicleHud} 参照——今やそれが
 * 乗員の見ている場所に来る。
 *
 * <p>一人称は逆の場合で、機体と同じく逆の扱いを受ける。カメラを車両座標系の {@code camera.cockpit} へ直接置くので、
 * どのハッチから外を見るかはファイルが決める。バニラが搭乗者の目を置く場所任せにはしない——車体の床に書かれた座席
 * では装甲の内側を眺めることになる。その点は<em>砲塔</em>に乗り、砲と共に旋回輪の周りを回る。戦車のハッチはそこに
 * あるからだ。砲を真横へ据えれば視界も車体側面越しに回り込む。実物のキューポラと同じだ。機体から取るのは位置だけ
 * で、方向は依然として乗員の物。そもそも砲塔を据えているのがそれだからだ。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class GroundVehicleCameraHandler {
    /**
     * 倒した視界が垂直へどこまで近付けるか（度）。ゲームの他の全視点にバニラが課すのと同じ「真上の少し手前」だ。
     * それを越えると方位が意味を失い、画が滑る。
     */
    private static final float POLE = 89.5F;

    @SubscribeEvent
    public static void onCalculateCameraDistance(CalculateDetachedCameraDistanceEvent event) {
        if (riddenVehicle(event.getCamera()) != null) {
            // 位置は下で直接設定するので、バニラには乗員の目の位置に置いたままにしてもらう。
            event.setDistance(0.0F);
        }
    }

    /**
     * 三人称視界を車両自身の {@code camera.tilt} だけ下へ倒す。
     *
     * <p>配置と一緒にではなくイベント経由で行う必要がある。カメラの角度を設定できるのはイベントだけだからだ——
     * どのみち順序としても正しい。先に傾きを適用し、オフセットはそれが残した軸に沿って測るので、カメラは傾きの
     * 下にぶら下がるのではなく傾きへ登っていく。
     */
    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Camera camera = event.getCamera();
        GroundVehicleEntity vehicle = riddenVehicle(camera);

        if (vehicle == null || !camera.isDetached()) {
            return;
        }

        float tilt = vehicle.getStats().camera().tilt();

        if (tilt == 0.0F) {
            return;
        }

        // バニラはこのイベントの後に反転視点を上下逆にする——搭乗者を振り返るため仰角を反転する——ので、傾きは
        // 反転して入れておかないと、そこを抜けた後に下を向いたままにならない。
        boolean reversed = Minecraft.getInstance().options.getCameraType() == CameraType.THIRD_PERSON_FRONT;

        event.setPitch(Mth.clamp(event.getPitch() + (reversed ? -tilt : tilt), -POLE, POLE));
    }

    /**
     * {@link com.ashvehicles.mixin.CameraMixin} から、バニラがカメラ配置を終えた後に呼ばれる。新しい位置が実際に
     * 定着する最初の瞬間がそこだ。
     */
    public static void placeCamera(Camera camera, Entity viewer, boolean detached, float partialTick) {
        if (!(viewer.getVehicle() instanceof GroundVehicleEntity vehicle)) {
            return;
        }

        if (!detached) {
            // 実際に座っている座席の視点を、その座席の視点が固定されている物に乗せて運ぶ。車長の視点は砲塔上面
            // にあり砲と共に回る。操縦手の視点は前面装甲にあり回らない。CV90 後部の歩兵にはそのどちらから外を見る
            // 筋合いも無い。どれがどれかは機体ファイルが述べる。述べていない場合、各座席は従来通りの物を受け取る。
            camera.setPosition(vehicle.eyeOf(viewer, partialTick));

            return;
        }

        ChaseCamera.place(camera, vehicle, vehicle.getStats().camera().pos(), partialTick);
    }

    /** カメラの持ち主が搭乗している車両。搭乗していなければ null。 */
    private static GroundVehicleEntity riddenVehicle(Camera camera) {
        Entity viewer = camera.getEntity();

        return viewer != null && viewer.getVehicle() instanceof GroundVehicleEntity vehicle ? vehicle : null;
    }

    private GroundVehicleCameraHandler() {
    }
}
