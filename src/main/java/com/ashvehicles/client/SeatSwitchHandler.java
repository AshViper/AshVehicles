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
 * The key that moves the crew member to the next seat of the machine they are aboard.
 *
 * <p>All it does is say the key went down while the player is riding one of ours. Which seat is next
 * — and whether there is a free one to move to at all — is the server's, settled in
 * {@link SwitchSeatPayload}: the client cannot see who is sitting where until it is told, and must
 * not guess. The press is drained rather than read, so one is never left queued to fire a tick
 * later, and only one move is asked for however many presses piled up.
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
