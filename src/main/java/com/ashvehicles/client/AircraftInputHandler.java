package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.AircraftInput;
import com.ashvehicles.network.AircraftInputPayload;
import com.ashvehicles.network.GunTriggerPayload;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * パイロットの操作を毎tick機体入力へ変換する。
 *
 * <p>3つの回転軸をそれぞれキー1組で直接操縦する。各組はパイロットが変更できるバインドで、初期設定は W/S がピッチ、
 * A/D がロール、Q/E がヨー。シフトとコントロールがスロットル。マウスは飛行にまったく関与せず、見回すだけだ。
 *
 * <p>機体はこのクライアントでシミュレートされる（{@link AircraftEntity} 参照）ので、生成された入力はローカルで
 * 適用しつつサーバーへも送る。サーバーは姿勢を他全員へ複製する。
 *
 * <p>降車はシフトではなく alt で、ここでは決めない。MOD の全機体・全座席で1キーであり、
 * {@link VehicleDismountHandler} にある。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class AircraftInputHandler {
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;

        if (player == null || minecraft.isPaused()) {
            return;
        }

        // コックピットの内外を問わず毎tick吸い出す。地上での押下がキューに残り、乗り込んだ瞬間に発火するのを
        // 防ぐためだ。
        boolean toggleGear = ModKeyMappings.TOGGLE_GEAR.consumeClick();
        boolean toggleFlaps = ModKeyMappings.TOGGLE_FLAPS.consumeClick();
        boolean toggleVtol = ModKeyMappings.TOGGLE_VTOL.consumeClick();
        boolean jettison = ModKeyMappings.JETTISON.consumeClick();
        // 1つのキーが MOD 内すべての兵装を順送りするが、押下を取れるハンドラは1つだけだ——キーマッピングは
        // クリックを最初に要求した側へ渡すし、この2つは順序の定めなく毎tick走る。よってこちらはプレイヤーが地上
        // 車両に乗っている間は完全に身を引き {@link GroundVehicleInputHandler} に譲り、あちらは乗っていないとき
        // 身を引く。2つの条件は互いの正確な鏡であり、それが「実行順に関わらずちょうど一方だけが取る」ことを保証
        // する。短絡がこの仕組みの全てだ。消費しないことが、もう一方が見つけられる形で押下を残すのだから。
        boolean cycleWeapon = !(player.getVehicle() instanceof com.ashvehicles.entity.GroundVehicleEntity)
                && ModKeyMappings.CYCLE_WEAPON.consumeClick();

        while (ModKeyMappings.TOGGLE_MOUSE_AIM.consumeClick()) {
            MouseAim.setEnabled(!MouseAim.isEnabled());
            // 明示的に通知する。マウスが機体に対して何をするかが変わるのに、今どちらの状態かを伝える物が画面上
            // に他に無いからだ。
            player.displayClientMessage(Component.translatable(
                    MouseAim.isEnabled() ? "message.ashvehicles.mouse_aim_on"
                            : "message.ashvehicles.mouse_aim_off"), true);
        }

        AircraftEntity aircraft = pilotedAircraft(player);

        // 誰か操縦中かに関わらずtickする。キーを押したまま降りたパイロットの視界がポッドから戻るようにするためだ。
        PodCamera.tick(aircraft);

        if (aircraft == null) {
            gunnerControls(minecraft, player);

            return;
        }

        podControls(minecraft, player, aircraft);

        // ここでは Q・E・F は方向舵とフラップだ。バニラは同じtickの後段でそれらを読むので、今クリックキューを空に
        // しておけば、パイロットが剣をコックピットの外へ落としたり、キャノピー越しにインベントリを開いたり、旋回中に
        // 持ち手を入れ替えたりせずに済む。
        while (minecraft.options.keyDrop.consumeClick()) {
        }

        // そして使用ボタンは照準だ。どのみちコックピットの内側に使う物は無いし、バニラがそれでやること——食べる、
        // 内側から機体をクリックする——は、目標に対してボタンを押し続けているパイロットの意図ではない。
        while (minecraft.options.keyUse.consumeClick()) {
        }

        while (minecraft.options.keyInventory.consumeClick()) {
        }

        while (minecraft.options.keySwapOffhand.consumeClick()) {
        }

        // トリガーは攻撃ボタンで、放っておけばバニラはそれをキャノピー内側への殴打に費やす。クリックではなく押し
        // 続けで読む。銃は押している間ずっと撃つからだ。
        while (minecraft.options.keyAttack.consumeClick()) {
        }

        // これらのキーが供給する移動インパルスではなく、専用のバインドから読む。既定では同じ2キー・同じ操縦桿だが、
        // 今やパイロットが動かせる操縦桿だ。ここでバインドすれば、ピッチとロールが機体の他の項目と並んで操作設定
        // 画面に現れるし、歩行を WASD 以外に割り当てているプレイヤーが、別の用途へ渡したキーで飛ばされることも
        // なくなる。
        float keyPitch = axis(ModKeyMappings.PITCH_UP, ModKeyMappings.PITCH_DOWN);
        float keyRoll = axis(ModKeyMappings.ROLL_RIGHT, ModKeyMappings.ROLL_LEFT);
        float keyYaw = axis(ModKeyMappings.YAW_RIGHT, ModKeyMappings.YAW_LEFT);

        // 操縦桿に手を掛ければその軸だけを取り戻す。両者を足し合わせるとキーの手応えが死ぬ。照準側が機首を元の
        // 位置に保とうと逆方向へ引くからだ——そしてキー1つで機体全体を取り戻す方式にすると、ヘリのパイロットは
        // マウスを機首から外さずに横滑りできなくなる。そもそもロールは回転翼機がキーに委ねている唯一の軸なのだ。
        MouseAim.Stick aim = MouseAim.stick();

        AircraftInput input = new AircraftInput(
                keyPitch != 0.0F ? keyPitch : aim.pitch(),
                keyRoll != 0.0F ? keyRoll : aim.roll(),
                keyYaw != 0.0F ? keyYaw : aim.yaw(),
                axis(ModKeyMappings.THROTTLE_UP, ModKeyMappings.THROTTLE_DOWN),
                ModKeyMappings.AIR_BRAKE.isDown(),
                minecraft.options.keyAttack.isDown(),
                // クリックではなく押し続け。ディスペンサーはハンドルを引いている間1発ずつ放出し、その速さは自身
                // の間隔が決める。
                ModKeyMappings.RELEASE_FLARE.isDown(),
                ModKeyMappings.RELEASE_CHAFF.isDown(),
                ModKeyMappings.RADAR_LOCK.isDown());

        aircraft.setInput(input);
        // 速度も同送する。サーバーには見えないからだ。ここから操縦される機体はサーバー上でtickの合間に届く
        // パケットによって動かされるので、向こうからはまったく動いていないように見える。これが無いと、そこから
        // 発射される物は全て初速0で出て行っていた。AircraftEntity.getVelocity 参照。
        PacketDistributor.sendToServer(new AircraftInputPayload(
                input, aircraft.getThrottle(), aircraft.getAfterburner(),
                aircraft.getAttitude(), aircraft.getVelocity(),
                aircraft.isCrashing(), toggleGear, toggleFlaps, toggleVtol, cycleWeapon, jettison));
    }

    /**
     * ターゲティングポッド。手が既に置かれている2キーで操作する。
     *
     * <p>照準キーで出し入れする。ただしポッドを必要とする兵装を選択中に限る——主翼の他の兵装は従来通りのガンサイト
     * を保つ。{@link PodCamera#isAvailable} 参照。スペースでマークを捕捉・解放する。トリガーはここには一切無い。
     * トリガーは既に選択中の物を投下するし、ポッドはそれを何も変えないからだ。
     *
     * <p>スペースはジャンプで、コックピット内では何の意味も無いので、ドロップやインベントリのキーと同様ここで
     * 飲み込む。バニラは同じtickの後段でクリックキューではなくキー状態から読むので、吸い出すだけでなく押下状態も
     * 抑える——さもないと指示操作でパイロットが座席から立ち上がってしまう。
     */
    private static void podControls(Minecraft minecraft, LocalPlayer player, AircraftEntity aircraft) {
        while (minecraft.options.keyJump.consumeClick()) {
        }

        minecraft.options.keyJump.setDown(false);

        // 照準キーがポッドを意味している間だけ。それ以外では手を出さないので、AimZoom は従来通り押下を受け取り、
        // 照準もこれまで通り上がる。
        if (PodCamera.isActive() || PodCamera.isAvailable(aircraft)) {
            boolean toggled = false;

            while (ModKeyMappings.AIM.consumeClick()) {
                toggled = true;
            }

            if (toggled) {
                // 明示的に通知する。他の手掛かりは視界の変化だけであり、誤って押したパイロットには今どちらの状態
                // かを伝えるべきだからだ。
                player.displayClientMessage(Component.translatable(PodCamera.toggle()
                        ? "message.ashvehicles.pod_on"
                        : "message.ashvehicles.pod_off"), true);
            }
        }

        if (!PodCamera.isActive()) {
            return;
        }

        while (ModKeyMappings.DESIGNATE.consumeClick()) {
            PodCamera.designate();
        }
    }

    /**
     * 飛行中、攻撃ボタンにバニラの動作を一切させない。殴打も、コックピットがたまたま寄りかかっている物の採掘も
     * しない。それは今やトリガーであり、トリガーは {@link #onClientTick} で読む。
     *
     * <p>中ボタンも同じ理由で同じ扱いだ。コックピット内ではフリールックの取っ手であり、機体が向いているブロックを
     * 拾うのは有用でもパイロットの意図でもない。右ボタンは照準——{@link AimZoom} 参照——なので、パイロットの手持ちを
     * 使うことも押し続けた意図ではない。
     */
    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if ((event.isAttack() || event.isPickBlock() || event.isUseItem())
                && Minecraft.getInstance().player instanceof LocalPlayer player
                && player.getVehicle() instanceof AircraftEntity) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }

    /**
     * 操縦していない乗員の唯一の操作——引き金。
     *
     * <p>操縦入力のパケットは操縦している者しか送らないので、砲手の引き金はこの1ビットだけを運ぶ。押している
     * 間の状態なので、引いている間は毎tick、離した瞬間に1度送る。押しっぱなしのまま降りたり席を移ったりした
     * 砲手のために、砲を持たなくなった時点でも1度送る——サーバー側は報告が途切れれば数tickで自然に消えるが、
     * 離したことを知らせられるなら知らせた方がいい。
     *
     * <p>バニラのクリックはパイロットの場合と同じ理由で飲み込む。砲座に座っている間、攻撃ボタンは引き金で
     * あって、機体の内壁を殴る手段ではない。
     */
    private static void gunnerControls(Minecraft minecraft, LocalPlayer player) {
        if (!(player.getVehicle() instanceof AircraftEntity aircraft)
                || aircraft.getStations().liveStationOf(player) < 0) {
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

        while (minecraft.options.keyInventory.consumeClick()) {
        }

        boolean pressed = minecraft.options.keyAttack.isDown();

        // 引いている間は毎tick送る。サーバー側は報告が途切れた砲手の引き金を数tickで離すので——切断した砲手
        // の砲が撃ち続けないための仕組みだ——押しっぱなしは「押している」と言い続けることでしか表せない。
        if (pressed || pressed != triggerHeld) {
            triggerHeld = pressed;
            PacketDistributor.sendToServer(new GunTriggerPayload(pressed));
        }
    }

    /** 最後にサーバーへ知らせた砲手の引き金の状態。変わった時だけ送るために持つ。 */
    private static boolean triggerHeld;

    private static AircraftEntity pilotedAircraft(LocalPlayer player) {
        return player.getVehicle() instanceof AircraftEntity aircraft && aircraft.getControllingPassenger() == player
                ? aircraft
                : null;
    }

    private static float axis(KeyMapping positive, KeyMapping negative) {
        return (positive.isDown() ? 1.0F : 0.0F) - (negative.isDown() ? 1.0F : 0.0F);
    }

    private AircraftInputHandler() {
    }
}
