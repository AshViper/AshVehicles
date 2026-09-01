package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.weapon.GunStations;

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
 * 思い出さねばならない乗員には、間違ったキーが割り当てられている。得られるのは操縦者と砲座の乗員だけ。割り当て先が
 * バニラの使用キーであり、それが飲み込まれるのはその2者だけだからだ——ただ乗っているだけの搭乗者の右クリックは
 * 従来通り機能すべきで、ズームまでするべきではない。
 *
 * <p>瞬時ではなく数tickかけて滑らかに動かす。跳ぶ視界は目が居場所を探し直す視界であり、照準を上げる瞬間は乗員が
 * 目標を見失うまいとしているまさにその瞬間だからだ。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class AimZoom {
    /** 照準が物をどれだけ近付けるか。肩に構える照準器と、砲塔の照準眼鏡。 */
    public static final float ZOOM = 2.5F;
    /**
     * 砲座の映像を覗いているときの倍率。
     *
     * <p>別の数にしてあるのは別の装置だからだ。あちらは乗員が目を寄せる照準器だが、こちらは砲身に載った
     * カメラで、砲手が見ているのは最初から画面である。撃つ距離も違う——AC-130 の砲手が狙う物は、キャノピー
     * 越しなら数ピクセルの点にしかならない距離にいる。ポッドの8倍には届かせない。あちらは地面を舐めるため
     * の望遠鏡で、こちらは弾を当てるための照準であり、視野を失いすぎれば砲を振る先を見失う。
     */
    public static final float SENSOR_ZOOM = 6.0F;
    /** 1tickで視界が進む量。往復の全行程に対する割合。 */
    private static final float RATE = 0.3F;

    private static float progress;
    private static float progressO;
    private static boolean aiming;
    /** 今の照準が到達する倍率。座席によって違う。 */
    private static float zoom = ZOOM;

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
                && vehicle.getAviator() == player;
        // 砲手も同じキーで同じことをする。砲座の映像は窓ではなく装置越しの視界であり、AC-130 の砲手が撃つ
        // 距離では、通常の視野角に映る目標は数ピクセルしかない。据えるべき物が据えられる大きさで映って
        // いないなら、砲を振れることに意味が無い。{@link GunnerDisplay} 参照。
        boolean gunner = GunnerDisplay.manned(minecraft) != GunStations.NONE;
        boolean sighted = atControls || gunner;

        // ポッド表示中は不可。そもそもポッドを上げたのが照準キーだ——AircraftInputHandler 参照——し、既に8倍の
        // ポッドの上にさらに視野を狭めれば、パイロットが1回押したキーに対して倍率が2つ掛かってしまう。
        tick(sighted && !PodCamera.isShowing() && ModKeyMappings.AIM.isDown(),
                gunner ? SENSOR_ZOOM : ZOOM);
    }

    private static void tick(boolean wanted, float wantedZoom) {
        aiming = wanted;

        // 倍率を差し替えてよいのは視界が完全に下りている間だけ。途中で入れ替えると、同じ進捗が別の視野角を
        // 意味することになり、席を移った瞬間に視界が1フレームで跳ぶ。
        if (progress <= 0.0F) {
            zoom = wantedZoom;
        }

        progressO = progress;
        progress = wanted ? Math.min(progress + RATE, 1.0F) : Math.max(progress - RATE, 0.0F);
    }

    /** 照準キーが押されているか。 */
    public static boolean isAiming() {
        return aiming;
    }

    /** 現時点で視界が通常よりどれだけ狭いか。1なら通常のまま。 */
    public static float factor(float partialTick) {
        return 1.0F + (zoom - 1.0F) * Mth.lerp(partialTick, progressO, progress);
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
