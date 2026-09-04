package com.ashvehicles.mixin;

import com.ashvehicles.entity.FlightChunkOrder;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 機体に乗っているプレイヤーへは、進行方向の chunk から送る。
 *
 * <p>{@code PlayerChunkSender.sendNextChunks} は待ち行列の chunk を「プレイヤーの chunk からの距離」で
 * 並べ、近い物から今tickの割り当てぶんを送る。その物差しの原点を1回だけ訊く場所——
 * {@code player.chunkPosition()}——を差し替え、機体が向かっている先を原点にする。並べ方も割り当ても
 * バニラのまま。理由は {@link FlightChunkOrder} 参照。
 */
@Mixin(PlayerChunkSender.class)
public abstract class FlightChunkSendMixin {
    @Redirect(method = "sendNextChunks",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;chunkPosition()"
                            + "Lnet/minecraft/world/level/ChunkPos;"))
    private ChunkPos ashvehicles$sendTheWayWeFly(ServerPlayer player) {
        return FlightChunkOrder.center(player);
    }
}
