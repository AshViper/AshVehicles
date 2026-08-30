package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.EjectionSeat;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.network.EjectPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * MOD 内のあらゆる乗り物から降りるキーは1つで、alt だ。
 *
 * <p>shift ではありえない。コックピットで shift はスロットルであり、スロットルを開けて上昇中のパイロットは
 * その操作で機外へ出てしまう。最初はパイロットだけ alt へ移したが、それは他の全員——パイロット後席の搭乗者や
 * 戦車の全乗員——を、コックピットが黙って奪ったキーで降りる羽目にした。全機体の全座席で1キーにするのがこの
 * クラスの目的の全てだ。
 *
 * <p>降車の判断はサーバーが、クライアントが入力パケットで報告する shift 状態から行う。これは入力が読まれた
 * 直後、そのパケットが出る前に発火するので、ここでフラグを書き換えるだけで足りる。alt を押せばサーバーは期待
 * 通りの内容を受け取り、shift を押しても何も受け取らない。
 *
 * <p><b>射出座席のある機体では同じキーが2つの意味を持つ。</b>叩けば降りる、押し続ければ飛び出す。だから
 * そこでは報告を遅らせる——押している間は shift を伏せ、離した時にまだ1秒に満たなければ、その1tickだけ
 * 立てて降車させる。伏せずに素通しすると、長押しの1tick目でサーバーが降車させてしまい、長押しはこの世に
 * 存在できない。
 *
 * <p>乗り込み時にバニラが画面へ出す行は、<em>バニラが</em>この役目だと思っているキーを名指しする。それは shift
 * であり、今や誤りだ。{@code MountHintMixin} が実際に効くキーをここへ問い合わせる。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class VehicleDismountHandler {
    /**
     * ハンドルを引き切るまでの長さ（tick）。
     *
     * <p>1秒。叩いて降りる操作と取り違える余地が無く、かつ機体が地面に届く前に間に合う長さ。短くすれば
     * 降りるつもりの人が飛び出し、長くすれば飛び出すつもりの人が墜落に間に合わない。
     */
    private static final int HOLD_TICKS = 20;

    /** 今このキーを押し続けている tick 数。押していなければ 0。 */
    private static int held;

    /** この押し込みで既に射出したか。離すまで次は無い。 */
    private static boolean fired;

    /** 離した瞬間に立て、1tick だけサーバーへ報告する降車要求。 */
    private static boolean leaving;

    @SubscribeEvent
    public static void onMovementInputUpdate(MovementInputUpdateEvent event) {
        Entity rider = event.getEntity();

        if (!isAboard(rider)) {
            reset();

            return;
        }

        boolean down = ModKeyMappings.DISMOUNT.isDown();

        if (!canEject(rider)) {
            // 射出座席の無い物——戦車、艦、ヘリ——では従来通り、押した瞬間に降りる
            reset();
            event.getInput().shiftKeyDown = down;

            return;
        }

        if (down) {
            hold();
        } else {
            release();
        }

        // 押している間は伏せる。立つのは離した直後の1tickだけ
        event.getInput().shiftKeyDown = take();
    }

    /** 押し続けている間。引き切れば射出し、以後は離すまで何も起きない。 */
    private static void hold() {
        if (fired) {
            return;
        }

        held++;

        if (held >= HOLD_TICKS) {
            fired = true;
            held = HOLD_TICKS;

            PacketDistributor.sendToServer(EjectPayload.INSTANCE);
        }
    }

    /** 離した。引き切る前なら、それは「降りる」という意味だった。 */
    private static void release() {
        if (held > 0 && !fired) {
            leaving = true;
        }

        held = 0;
        fired = false;
    }

    /** 溜めていた降車要求を1度だけ渡す。 */
    private static boolean take() {
        boolean now = leaving;

        leaving = false;

        return now;
    }

    private static void reset() {
        held = 0;
        fired = false;
        leaving = false;
    }

    /** この搭乗者が MOD の乗り物のいずれかの座席にいるか。操縦中・運転中・それ以外を問わない。 */
    public static boolean isAboard(Entity rider) {
        return rider != null && rider.getVehicle() instanceof VehicleEntityBase;
    }

    /** 今乗っている物に射出座席があるか。 */
    private static boolean canEject(Entity rider) {
        return rider.getVehicle() instanceof AircraftEntity aircraft && EjectionSeat.has(aircraft);
    }

    /**
     * ハンドルの引き具合（0〜1）。射出座席のある機体で自分がキーを押している間だけ 0 より大きい。
     * 画面がこれを目盛りにする。{@link AircraftHud} 参照。
     */
    public static float ejectCharge() {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null || !canEject(minecraft.player)) {
            return 0.0F;
        }

        return Mth.clamp((float) held / HOLD_TICKS, 0.0F, 1.0F);
    }

    /** バニラの「降りるにはこれを押す」行が名指しすべきキー。 */
    public static Component dismountKeyName() {
        return ModKeyMappings.DISMOUNT.getTranslatedKeyMessage();
    }

    private VehicleDismountHandler() {
    }
}
