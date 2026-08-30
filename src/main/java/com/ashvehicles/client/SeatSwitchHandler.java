package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.network.SwitchSeatPayload;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 搭乗中の機体の次の座席へ移動するキー。
 *
 * <p>このクラスがするのは「この MOD の乗り物に乗っている間にキーが押された」と伝えることだけ。次がどの座席か
 * ——そもそも移れる空席があるか——はサーバーの管轄で、{@link SwitchSeatPayload} で決まる。クライアントは通知
 * されるまで誰がどこに座っているか分からないし、推測してはならない。押下は読むのではなく吸い出すので、1tick後
 * に発火する押下がキューに残ることはなく、何回押されても要求する移動は1回だけだ。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class SeatSwitchHandler {
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null || minecraft.level == null) {
            ModKeyMappings.SWITCH_SEAT.consumeClick();

            return;
        }

        boolean asked = false;

        while (ModKeyMappings.SWITCH_SEAT.consumeClick()) {
            asked = true;
        }

        if (asked && minecraft.screen == null && minecraft.player.getVehicle() instanceof VehicleEntityBase) {
            PacketDistributor.sendToServer(SwitchSeatPayload.INSTANCE);
        }
    }

    private SeatSwitchHandler() {
    }
}
