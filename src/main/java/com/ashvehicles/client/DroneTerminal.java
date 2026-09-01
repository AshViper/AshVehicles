package com.ashvehicles.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.screen.DroneTerminalScreen;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.registry.ModItems;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 携帯管制端末のクライアント側。右クリックで管制画面を開き、繋げる機体を数え上げる。
 *
 * <p><b>機体の一覧はサーバーに訊かない。</b>訊く必要が無いからだ。この MOD の機体は
 * {@link com.ashvehicles.mixin.EntityTrackingMixin} によって、距離にもロード済み chunk にも関係なく全
 * クライアントへ送られている。つまり操作者の手元には既に、世界中の無人機が位置も姿勢も速度も付いた本物の
 * エンティティとして存在している。端末が数えるのはそれだけだ。
 *
 * <p>これは偶然の再利用ではなく、同じ判断の別の結果である。あの mixin が外した制限——「見えている世界の
 * 外にある物は知らせない」——は、地平線の向こうの機体を眺めるためだけでなく、地平線の向こうの機体を
 * <em>飛ばす</em>ためにも要る物だった。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class DroneTerminal {
    private DroneTerminal() {
    }

    /**
     * 端末を持って空へ右クリックした。
     *
     * <p><b>ブロックを向いている場合はこちらは発火しない。</b>Minecraft は狙っている先で経路を分けており、
     * 空なら {@code RightClickItem}、ブロックなら {@code RightClickBlock} が飛ぶ。片方だけ拾うと、端末は
     * 「空を向いていれば開くが、足元の地面を向いていると何も起きない道具」になる——そして人は普通、地面を
     * 向いて立っている。
     */
    @SubscribeEvent
    public static void onRightClickAir(PlayerInteractEvent.RightClickItem event) {
        if (held(event)) {
            event.setCanceled(true);
        }
    }

    /** 端末を持ってブロックへ右クリックした。上と同じ物。 */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (held(event)) {
            event.setCanceled(true);
        }
    }

    /**
     * 端末を握っていたか。握っていたなら、開けるなら開いてある。
     *
     * <p>打ち消しは呼び出し側が行う。{@link PlayerInteractEvent} そのものは打ち消せず、打ち消せるのは
     * 個々の派生型だけだからだ。
     *
     * <p>何かに乗っている間は開かない。既に繋いでいる操作者にとって端末は用済みで——切るのは降車キーだ——
     * 有人機のコックピットで開く物でもない。それでも true を返すのは、そこでも打ち消してほしいからだ。
     * 持ち替えずに手に持ったまま乗り込んだ端末が、コックピットの内壁に向かって使われるのは誰の意図でも
     * ない。
     */
    private static boolean held(PlayerInteractEvent event) {
        if (!event.getLevel().isClientSide() || !event.getItemStack().is(ModItems.DRONE_TERMINAL.get())) {
            return false;
        }

        if (event.getEntity().getVehicle() == null) {
            DroneTerminalScreen.open();
        }

        return true;
    }

    /**
     * 今このクライアントが知っている無人機を、近い順に。
     *
     * <p>残骸は外す。繋いでも操縦桿は何もせず、落ちた場所に横たわっているだけだからだ。他人が繋いでいる
     * 機体は<em>残す</em>——繋げないことは画面に出るし、「そこに1機あって今使われている」は操作者が知って
     * よい事実である。
     */
    public static List<AircraftEntity> reachable() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;

        if (player == null || minecraft.level == null) {
            return List.of();
        }

        List<AircraftEntity> found = new ArrayList<>();

        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof AircraftEntity drone && drone.isUnmanned() && !drone.isWrecked()) {
                found.add(drone);
            }
        }

        found.sort(Comparator.comparingDouble(drone -> drone.distanceToSqr(player)));

        return found;
    }
}
