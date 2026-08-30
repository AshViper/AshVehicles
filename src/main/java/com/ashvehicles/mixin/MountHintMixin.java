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
 * 実際に降りられるキーの名前を表示する。
 *
 * <p>Minecraft は誰かが搭乗者になった瞬間に「Shift で降りる」と画面に出す。馬やボートなら Shift が
 * 正解だからだ。ここでは Shift はスロットルで、降りるのは Alt（{@link VehicleDismountHandler} 参照）。
 * つまりこの表示は、機体に乗った全員に「唯一効かない操作」を案内していた。
 *
 * <p>変えるのはキー名だけ。バニラの文面はそのまま使うので、プレイヤーが読んでいる言語で表示されるし、
 * 馬・ボート・トロッコなど他の乗り物には一切触れない。
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
