package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.VehicleEntityBase;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * 照準を目元へ引き寄せる。照準キーを押している間、視野角を狭める。
 *
 * <p>照準器は遠方の目標に置く小さなマークだが、通常の視野角では、弾が届く距離の目標は数ピクセル幅しかない。
 * 視界を引き寄せれば据えられる物になる。照準器の存在意義はそれが全てだ。砲を据えるのは依然として操縦者であり
 * ——ここは何も照準しない。機体のマークの意味は {@link GunSight}、戦車は {@link GroundVehicleHud} 参照——
 * マウス感度は視野を狭めたのと同じだけ落とすので、画面上の移動量は照準の有無に関わらず同じになる。
 *
 * <p>MOD の全機体で1つの照準、1つのキーから。降車キーが1つである理由と同じだ。照準を覗く前にどの座席にいるか
 * 思い出さねばならない乗員には、間違ったキーが割り当てられている。得られるのは操縦者だけ。割り当て先がバニラの
 * 使用キーであり、それが飲み込まれるのは操縦中の乗員だけだからだ——搭乗者の右クリックは従来通り機能すべきで、
 * ズームまでするべきではない。
 *
 * <p>瞬時ではなく数tickかけて滑らかに動かす。跳ぶ視界は目が居場所を探し直す視界であり、照準を上げる瞬間は乗員が
 * 目標を見失うまいとしているまさにその瞬間だからだ。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class AimZoom {
    /** 照準が物をどれだけ近付けるか。 */
    public static final float ZOOM = 2.5F;
    /** 1tickで視界が進む量。往復の全行程に対する割合。 */
    private static final float RATE = 0.3F;

    private static float progress;
    private static float progressO;
    private static boolean aiming;

    private AimZoom() {
    }

    /**
     * 毎tick、照準を上げるべきかを判定する。条件は「何かの操縦者が照準キーを押している」こと。誰も操縦して
     * いなくてもtickするので、キーを押したまま降車しても視界は元に戻る。
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;

        if (player == null || minecraft.isPaused()) {
            return;
        }

        boolean atControls = player.getVehicle() instanceof VehicleEntityBase vehicle
                && vehicle.getControllingPassenger() == player;

        // ポッド表示中は不可。そもそもポッドを上げたのが照準キーだ——AircraftInputHandler 参照——し、既に8倍の
        // ポッドの上にさらに視野を狭めれば、パイロットが1回押したキーに対して倍率が2つ掛かってしまう。
        tick(atControls && !PodCamera.isShowing() && ModKeyMappings.AIM.isDown());
    }

    private static void tick(boolean wanted) {
        aiming = wanted;
        progressO = progress;
        progress = wanted ? Math.min(progress + RATE, 1.0F) : Math.max(progress - RATE, 0.0F);
    }

    /** 照準キーが押されているか。 */
    public static boolean isAiming() {
        return aiming;
    }

    /** 現時点で視界が通常よりどれだけ狭いか。1なら通常のまま。 */
    public static float factor(float partialTick) {
        return 1.0F + (ZOOM - 1.0F) * Mth.lerp(partialTick, progressO, progress);
    }

    /** 同じ値の、このフレーム用。 */
    public static float factor() {
        return factor(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true));
    }

    /**
     * 照準中はワールドの視野角を狭める。ワールドだけだ。手は独自の視野角で描かれるし、望遠鏡サイズの手は要らない。
     */
    @SubscribeEvent
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (!event.usedConfiguredFov()) {
            return;
        }

        // 狭めるのはどちらか一方で、両方は無い。ポッドは独自の、しかもはるかに長い照準であり、これと併用では
        // なくこれの代わりに上がる。
        float factor = PodCamera.isShowing()
                ? PodCamera.factor((float) event.getPartialTick())
                : factor((float) event.getPartialTick());

        if (factor > 1.0001F) {
            event.setFOV(event.getFOV() / factor);
        }
    }
}
