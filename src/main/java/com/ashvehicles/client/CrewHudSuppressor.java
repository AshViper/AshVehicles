package com.ashvehicles.client;

import java.util.Set;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.VehicleEntityBase;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * 誰かが乗り物に搭乗している間、ホットバーと手持ちアイテムを画面から消す。
 *
 * <p>乗員はホットバーの中身を携行しているのではない。自前の兵装・選択キー・計器を持つ機体に座っている——
 * {@link AircraftHud} と {@link GroundVehicleHud} 参照——のであり、その下に並ぶアイテムスロットは「発砲すると
 * 何が起きるか」への2つ目の、矛盾した答えだ。風防の前でツルハシを握る腕も、画面ではなく世界で起きる同じ誤りで
 * あり、コックピット視点ではパイロットと地面の間に居座る。
 *
 * <p>ステータスバーも一緒に消す。体力・空腹・防具・経験値バーはいずれも座席に座る肉体の値であり、座席が主役で
 * ある間、乗員が見るべきなのは機体の状態だ。それは計器が既に伝えている。残すのは空気ゲージだけ。溺れているとき
 * にしか出ないし、それは計器盤の何も伝えてくれない唯一の瞬間だからだ。
 *
 * <p>十字線も消す。同じ理由の最も鋭い形だ。この MOD の機体は自前の照準を持つ——弾が到達する点に置かれる環。
 * {@link GroundVehicleHud} と {@link AircraftHud} 参照——のに対し、バニラの十字は同じ問いに別の答えを出す2つ目
 * のマークだ。単に冗長なのではない。画面中央にあるマークこそ誰もが照準に使う物なので、残しておくのは誤った方に
 * 砲を据えろという誘いになる。
 *
 * <p>ここでは所持品もバーの数値も変えない——アイテムはそこにあり選択もされたまま、数値も動き続け、単に描かれない
 * だけ——なので、降りれば復元すべき状態も無く全て元に戻る。
 *
 * <p>判定対象は直上ではなくルートの乗り物なので、乗員と機体の間に座席その他の担ぎ手が挟まってもホットバーは
 * 戻ってこない。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class CrewHudSuppressor {
    /**
     * 乗員に用の無いバニラのレイヤー。携行インベントリ、肉体のバー、そして機体自身の照準が置き換える十字線。
     */
    private static final Set<ResourceLocation> HIDDEN = Set.of(
            VanillaGuiLayers.CROSSHAIR,
            VanillaGuiLayers.HOTBAR,
            VanillaGuiLayers.SELECTED_ITEM_NAME,
            VanillaGuiLayers.PLAYER_HEALTH,
            VanillaGuiLayers.FOOD_LEVEL,
            VanillaGuiLayers.ARMOR_LEVEL,
            VanillaGuiLayers.EXPERIENCE_BAR,
            VanillaGuiLayers.EXPERIENCE_LEVEL,
            VanillaGuiLayers.JUMP_METER);

    private CrewHudSuppressor() {
    }

    /** 搭乗中、それらのレイヤーを描画前に落とす。 */
    @SubscribeEvent
    public static void onRenderGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (aboard() && HIDDEN.contains(event.getName())) {
            event.setCanceled(true);
        }
    }

    /**
     * 搭乗中、一人称の手を落とす。レンダラーの手の描画経路は素手も地図も含め全てここを通るので、他所で拾うべき
     * 取りこぼしは無い。
     */
    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (aboard()) {
            event.setCanceled(true);
        }
    }

    private static boolean aboard() {
        LocalPlayer player = Minecraft.getInstance().player;

        return player != null && player.getRootVehicle() instanceof VehicleEntityBase;
    }
}
