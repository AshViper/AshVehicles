package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.network.OpenVehicleHoldPayload;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 搭乗中の機体の弾庫を開くキー。
 *
 * <p>MOD 内の全機体の全座席で共通の1キーであり、座席にいるときだけ有効。外からはスニーク＋右クリックで開く
 * ——クリックならエプロン上のどの機体かを指定できるが、キー押下では推測するしかないからだ。
 *
 * <p>このクラスがするのはキーが押されたと伝えることだけ。どの機体を指し中に何があるかはサーバーの管轄で、
 * {@link OpenVehicleHoldPayload} で決まる。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class VehicleHoldHandler {
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null || minecraft.level == null) {
            ModKeyMappings.OPEN_HOLD.consumeClick();

            return;
        }

        boolean asked = false;

        // 読むのではなく吸い出すので、後で発火する押下がキューに残ることはない。1tickに何回押されても開く
        // メニューは1つだけ。
        while (ModKeyMappings.OPEN_HOLD.consumeClick()) {
            asked = true;
        }

        if (asked && minecraft.screen == null) {
            PacketDistributor.sendToServer(OpenVehicleHoldPayload.INSTANCE);
        }
    }

    private VehicleHoldHandler() {
    }
}
