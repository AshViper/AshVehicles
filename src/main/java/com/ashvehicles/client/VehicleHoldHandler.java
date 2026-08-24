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
 * The key that opens the hold of the machine the crew are aboard.
 *
 * <p>One key for every seat of everything in the mod, and for the seats only. From outside, a hold
 * is opened by crouching and right-clicking the machine — a click names which aeroplane on the apron
 * was meant, and a key press would have to guess.
 *
 * <p>All this does is say that the key went down. Which machine that meant and what is in it are the
 * server's, and are settled in {@link OpenVehicleHoldPayload}.
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

        // Drained rather than read, so a press is never left in the queue to fire later; and only
        // one menu is opened however many presses have piled up in one tick.
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
