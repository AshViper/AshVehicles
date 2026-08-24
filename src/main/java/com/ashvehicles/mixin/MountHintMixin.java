package com.ashvehicles.mixin;

import com.ashvehicles.client.VehicleDismountHandler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Names the key that actually gets the crew out again.
 *
 * <p>Minecraft writes "press shift to dismount" across the screen the moment anybody becomes a
 * passenger, and it says shift because for a horse or a boat shift is the answer. In here it is the
 * throttle, and the way out is alt — see {@link VehicleDismountHandler}. The line was telling
 * everyone who climbed into an aeroplane to do the one thing that would not work.
 *
 * <p>Only the key named in it changes. Vanilla's own wording is kept, so the line still arrives in
 * whatever language the player is reading the game in, and everything else that is climbed into —
 * horses, boats, minecarts — is left alone.
 */
@Mixin(ClientPacketListener.class)
public abstract class MountHintMixin {
    @Redirect(method = "handleSetEntityPassengersPacket(Lnet/minecraft/network/protocol/game/ClientboundSetPassengersPacket;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/chat/Component;translatable(Ljava/lang/String;[Ljava/lang/Object;)"
                            + "Lnet/minecraft/network/chat/MutableComponent;"))
    private MutableComponent ashvehicles$sayAlt(String key, Object[] arguments) {
        return VehicleDismountHandler.isAboard(Minecraft.getInstance().player)
                ? Component.translatable(key, VehicleDismountHandler.dismountKeyName())
                : Component.translatable(key, arguments);
    }
}
