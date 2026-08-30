package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.GroundVehicleInput;
import com.ashvehicles.network.GroundVehicleInputPayload;

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

        // 1つのキーが MOD 内すべての兵装を順送りするが、キーマッピングはクリックを最初に要求した呼び出し元へ
        // 渡す。このハンドラと AircraftInputHandler は順序の定めなく毎tick走るので、2つの条件は互いの正確な鏡で
        // なければ競合する。こちらはプレイヤーが地上車両に乗っているとき押下を取り、あちらは乗っていないとき取る。
        // 実行順に関わらず、ちょうど一方だけが取る。
        //
        // 運転中だけでなく単に同乗している間も吸い出し、その場合は捨てる。後席での押下がキューに残り、席を移った
        // 瞬間に兵装を切り替えてしまうのを防ぐためだ。
        //
        // 以前のように無条件で吸い出すと、パイロットの足元から押下を奪ってしまう。コックピットではこのハンドラは
        // クリックに用が無いのに取ってしまい、機体側のハンドラには何も残らなかった。キーを押しっ放しにすると直る
        // ように見えたのは、押しっ放しがリピートして2回目のクリックを作り、それを他方が拾えたからだ。兵装切り替え
        // に長押しが要ると言われていた理由はそれが全てだ。
        boolean cycleWeapon = player.getVehicle() instanceof GroundVehicleEntity
                && ModKeyMappings.CYCLE_WEAPON.consumeClick();

        GroundVehicleEntity vehicle = drivenVehicle(player);

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

        GroundVehicleInput input = new GroundVehicleInput(
                axis(ModKeyMappings.DRIVE_FORWARD, ModKeyMappings.DRIVE_BACK),
                axis(ModKeyMappings.STEER_RIGHT, ModKeyMappings.STEER_LEFT),
                ModKeyMappings.VEHICLE_BRAKE.isDown(),
                minecraft.options.keyAttack.isDown(),
                ModKeyMappings.FIRE_COAXIAL.isDown(),
                // シーカーの引き金。コックピットとまったく同じキーで、まったく同じ意味を持つ
                ModKeyMappings.RADAR_LOCK.isDown());

        vehicle.setInput(input);
        // 車両のtickより前に行う。このイベントが Pre である理由はそれが全てだ。砲塔はそのtick内で据えられるので、
        // 据える経路となる視界がどう傾いているかを知っている必要がある。
        vehicle.setSightTilt(sightTilt(minecraft, vehicle));
        // 車体・速度・砲塔も同送する。サーバーはそのどれも見られないからだ。ここから運転される車両はサーバー上で
        // tickの合間に届くパケットによって動かされるが、バニラの移動パケットは方位と仰角しか運ばない。
        PacketDistributor.sendToServer(new GroundVehicleInputPayload(input, vehicle.getAttitude(),
                vehicle.getSpeed(), vehicle.getTurretYaw(1.0F), vehicle.getGunPitch(1.0F), cycleWeapon));
    }

    /**
     * 運転中、攻撃ボタンにバニラの動作を一切させない。殴打も、戦車がたまたま寄りかかっている物の採掘もしない。
     * それは今やトリガーであり、トリガーは {@link #onClientTick} で読む。使用ボタンも同じ理由で同じ扱いだ。
     * それは照準であって、乗員の手持ちを使うことは押し続けた意図ではない。
     */
    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if ((event.isAttack() || event.isUseItem()) && Minecraft.getInstance().player instanceof LocalPlayer player
                && drivenVehicle(player) != null) {
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
        return minecraft.options.getCameraType().isFirstPerson() ? 0.0F : vehicle.getStats().camera().tilt();
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
