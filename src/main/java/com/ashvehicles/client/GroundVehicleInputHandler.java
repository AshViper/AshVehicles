package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.GroundVehicleInput;
import com.ashvehicles.network.GroundVehicleInputPayload;
import com.ashvehicles.network.GunTriggerPayload;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 運転手の操作を毎tick車両入力へ変換する。
 *
 * <p>2軸、ブレーキ、そして2つのトリガー——選択中の兵装用の攻撃ボタンと、決して選択されない同軸機銃用の専用キー。
 * マウスは運転にまったく関与しない。マウスは乗員の視線であり、乗員の視線こそ砲塔を据える先だ——
 * {@code GroundVehicleEntity.tickTurret} 参照。戦車で必要な処理が機体よりずっと少ないのはそのためで、バニラから
 * 奪うのはマウス2ボタンだけ。攻撃ボタンは今やトリガー、使用ボタンは照準だ——{@link AimZoom} が自分で読む。降車は
 * alt。コックピットと同じキーであり、そこへ移した理由も同じ——MOD 内のあらゆる物から降りる唯一の方法だ。
 * {@link VehicleDismountHandler} 参照。
 *
 * <p>車両はこのクライアントでシミュレートされるので、生成された入力はローカルで適用しつつサーバーへも送る。
 * サーバーは車体と砲塔を他全員へ複製する。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class GroundVehicleInputHandler {
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;

        if (player == null || minecraft.isPaused()) {
            return;
        }

        // 砲手照準の出入りをここで済ませる。砲塔が据えられるのはこのtickの中であり、覗いているかどうかで
        // 倒し角も頭の可動範囲も変わる。TurretSight 参照。
        TurretSight.follow();

        // 砲手席の乗員はここで済む。運転入力は送らないが引き金は持っている。
        gunnerControls(minecraft, player);

        GroundVehicleEntity vehicle = drivenVehicle(player);
        // 兵装の切り替えはホイール。溜める場所は機体と共通で、取り出すのは運転している物が決まった後だ。
        // {@link WeaponScroll} 参照——受け口が1つなので、キー時代にあった機体側との取り合いは無い。
        int cycleWeapon = WeaponScroll.take(vehicle);

        if (vehicle == null) {
            return;
        }

        // 攻撃ボタンは今やトリガーだ。放っておけばバニラはそれを砲塔の内側への殴打と、車体が寄りかかっている物の
        // 採掘に費やす。使用ボタンは照準であり、バニラがそれでやること——食べる、内側から戦車をクリックする——は、
        // 目標に対して押し続けている乗員の意図ではない。
        while (minecraft.options.keyAttack.consumeClick()) {
        }

        while (minecraft.options.keyUse.consumeClick()) {
        }

        // 座標で狙う発射機では、シーカーのキーが射撃指揮盤を開く。掴む物が無い弾には捕捉の手順が無く、代わりに
        // 座標を打ち込む手順があるからで、キーが言っていることは同じ——「この筒に次は何を撃たせるか」。
        // LaunchPoint 参照。
        LaunchPoint.tick(vehicle);

        GroundVehicleInput input = new GroundVehicleInput(
                axis(ModKeyMappings.DRIVE_FORWARD, ModKeyMappings.DRIVE_BACK),
                axis(ModKeyMappings.STEER_RIGHT, ModKeyMappings.STEER_LEFT),
                ModKeyMappings.VEHICLE_BRAKE.isDown(),
                minecraft.options.keyAttack.isDown(),
                ModKeyMappings.FIRE_COAXIAL.isDown(),
                // シーカーのキー。コックピットとまったく同じ押下・同じ意味で、1押しの切り出しはサーバーが行う
                ModKeyMappings.RADAR_LOCK.isDown());

        vehicle.setInput(input);
        // 車両のtickより前に行う。このイベントが Pre である理由はそれが全てだ。砲塔はそのtick内で据えられるので、
        // 据える経路となる視界がどう傾いているかを知っている必要がある。
        float tilt = sightTilt(minecraft, vehicle);

        vehicle.setSightTilt(tilt);
        // 車体・速度・砲塔も同送する。サーバーはそのどれも見られないからだ。ここから運転される車両はサーバー上で
        // tickの合間に届くパケットによって動かされるが、バニラの移動パケットは方位と仰角しか運ばない。
        PacketDistributor.sendToServer(new GroundVehicleInputPayload(input, vehicle.getAttitude(),
                vehicle.getSpeed(), vehicle.getTurretYaw(1.0F), vehicle.getGunPitch(1.0F), cycleWeapon,
                tilt));
    }

    /**
     * 運転していない乗員の唯一の操作——引き金。
     *
     * <p>独立砲塔（{@code TurretStations}）を持つ車両の砲手席がこれで撃つ。運転入力のパケットは運転して
     * いる者しか送らないので、砲手の引き金はこの1ビットだけを運ぶ。押している間の状態なので、引いている間
     * は毎tick、離した瞬間に1度送る。機体の砲手とまったく同じ仕組みで、同じペイロードを使っている
     * （{@code AircraftInputHandler.gunnerControls}）。
     *
     * <p>バニラのクリックは運転手の場合と同じ理由で飲み込む。砲塔を回している間、攻撃ボタンは引き金で
     * あって、車内の壁を殴る手段ではない。
     */
    private static void gunnerControls(Minecraft minecraft, LocalPlayer player) {
        if (!(player.getVehicle() instanceof GroundVehicleEntity vehicle)
                || vehicle.getControllingPassenger() == player
                || vehicle.getTurrets().liveStationOf(player) < 0) {
            if (triggerHeld) {
                triggerHeld = false;
                PacketDistributor.sendToServer(new GunTriggerPayload(false));
            }

            return;
        }

        while (minecraft.options.keyAttack.consumeClick()) {
        }

        while (minecraft.options.keyUse.consumeClick()) {
        }

        boolean pressed = minecraft.options.keyAttack.isDown();

        // 引いている間は毎tick送る。サーバー側は報告が途切れた砲手の引き金を数tickで離すので、押しっぱなし
        // は「押している」と言い続けることでしか表せない。
        if (pressed || pressed != triggerHeld) {
            PacketDistributor.sendToServer(new GunTriggerPayload(pressed));
        }

        triggerHeld = pressed;
    }

    /** 前tickで引き金を引いていたか。離したことを1度だけ知らせるために持つ。 */
    private static boolean triggerHeld;

    /**
     * 運転中、攻撃ボタンにバニラの動作を一切させない。殴打も、戦車がたまたま寄りかかっている物の採掘もしない。
     * それは今やトリガーであり、トリガーは {@link #onClientTick} で読む。使用ボタンも同じ理由で同じ扱いだ。
     * それは照準であって、乗員の手持ちを使うことは押し続けた意図ではない。
     */
    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if ((event.isAttack() || event.isUseItem()) && Minecraft.getInstance().player instanceof LocalPlayer player
                && (drivenVehicle(player) != null || gunnedVehicle(player) != null)) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }

    /**
     * 乗員が使っている視界が、自分の目線からどれだけ下へ倒されているか（度）。
     *
     * <p>三人称視点は機体の {@code camera.tilt} だけ下へ回され、空ではなく地面が画面に入る。一人称視点はまったく
     * 回されない。砲がそれに対して行うのが {@code GroundVehicleEntity.setSightTilt} だ。同じ量だけ下げるので、
     * 画面中央はどちらの視点でも——片方だけでなく——砲の線になる。
     *
     * <p>したがって視点を切り替えると砲が動くし、他の照準と同様に砲塔本来の俯仰速度で動く。それが正直な挙動だ。
     * 2つの視点は別の場所を指しており、砲は乗員が覗いている方に従う。
     */
    private static float sightTilt(Minecraft minecraft, GroundVehicleEntity vehicle) {
        // 砲手照準を覗いている間は倒されていない。あれは砲腔線に沿って覗く物で、三人称で覗いても同じだ——
        // そこで倒し続ければ、砲は乗員が実際に見ている線から傾き分ずれた所へ据わる。TurretSight 参照。
        if (TurretSight.vehicle() == vehicle) {
            return 0.0F;
        }

        return minecraft.options.getCameraType().isFirstPerson() ? 0.0F : vehicle.getStats().camera().tilt();
    }

    /** この乗員が砲塔を回している車両。運転しているのではなく、砲手席に座っている場合。 */
    private static GroundVehicleEntity gunnedVehicle(LocalPlayer player) {
        return player.getVehicle() instanceof GroundVehicleEntity vehicle
                && vehicle.getControllingPassenger() != player
                && vehicle.getTurrets().liveStationOf(player) >= 0
                ? vehicle
                : null;
    }

    private static GroundVehicleEntity drivenVehicle(LocalPlayer player) {
        return player.getVehicle() instanceof GroundVehicleEntity vehicle
                && vehicle.getControllingPassenger() == player
                ? vehicle
                : null;
    }

    private static float axis(KeyMapping positive, KeyMapping negative) {
        return (positive.isDown() ? 1.0F : 0.0F) - (negative.isDown() ? 1.0F : 0.0F);
    }

    private GroundVehicleInputHandler() {
    }
}
