package com.ashvehicles.mixin;

import com.ashvehicles.entity.ChunkStalls;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 一時的。サーバースレッドを止めたチャンク要求を、呼び出し元の名前付きで記録する。
 *
 * <p><b>推論を終わらせるために在る。</b> {@code ServerChunkCache.getChunk} は、要求された chunk が
 * まだ無ければ {@code managedBlock} に入り、生成が終わるまでスレッドを回し続ける。この調査で「固まる」
 * と呼んでいる物は全部そこを通っているはずだが、<em>誰が呼んだか</em>はどの計測器も答えなかった。
 * ここが答える。
 *
 * <p>{@link ChunkStalls} が閾値を超えた1件ごとにスタックを畳んで1行にする。読み方は単純で、同じ行が
 * 積み上がっている場所が犯人。
 *
 * <p>調査が終わったらこのクラスと {@link ChunkStalls} を消すこと。
 */
@Mixin(ServerChunkCache.class)
public abstract class ChunkStallMixin {
    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)"
                    + "Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"))
    private void ashvehicles$stallBegins(int x, int z, ChunkStatus status, boolean requireChunk,
            CallbackInfoReturnable<ChunkAccess> callback) {
        ChunkStalls.enter();
    }

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)"
                    + "Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("RETURN"))
    private void ashvehicles$stallEnds(int x, int z, ChunkStatus status, boolean requireChunk,
            CallbackInfoReturnable<ChunkAccess> callback) {
        ChunkStalls.leave(x, z, requireChunk);
    }
}
