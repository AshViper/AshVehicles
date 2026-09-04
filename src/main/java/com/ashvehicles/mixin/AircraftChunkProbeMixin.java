package com.ashvehicles.mixin;

import com.ashvehicles.entity.AircraftProfile;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 一時的。tick スレッドが chunk の生成を待った回数と時間を数える。{@link AircraftProfile} 参照。
 *
 * <p><b>ここを選んだ理由。</b> 「待ち」が実際に起きる場所は1つしかない。{@code ServerChunkCache.getChunk} の
 * {@code this.mainThreadProcessor.managedBlock(completablefuture::isDone)} で、その手前の
 * {@code getChunkFutureMainThread} は {@code requireChunk} が真の時だけ {@code TicketType.UNKNOWN} を足し、
 * {@code runDistanceManagerUpdates} を回し、生成タスクを積む。偽なら {@code UNLOADED_CHUNK_FUTURE} が
 * 即座に返り、{@code managedBlock} は一瞬で抜ける。だから数えるのは真の呼び出しだけでよい。
 *
 * <p>{@code Level.getChunk(int, int)} でも同じ数が取れる——{@code getBlockState}、{@code getFluidState}
 * （{@code getChunkAt} 経由）、{@code Entity.setPosRaw} の NeoForge 追加行、
 * {@code ForcedChunkManager.forceChunk} の取り立ては全部そこを通る——が、それは「誰が要求したか」の層で
 * あって「待ちが起きたか」の層ではない。{@code ChunkStatus} を {@code FULL} 以外で要求する経路や
 * {@code ServerChunkCache} を直接叩く経路を取りこぼすし、4件のキャッシュに当たって待たなかった呼び出しと
 * 待った呼び出しを区別できない。こちらは時間そのものを測るので、その区別が要らない。
 *
 * <p><b>ここは毎tick何百回も通る。</b> 足しているのは静的メソッド呼び出し2つで、その中身は
 * {@code Thread.currentThread() != measuring} の1判定だ——機体の段階の外では、それだけで戻る。調査が
 * 終わったらこのファイルと {@code AircraftProfile} を消し、{@code ashvehicles.mixins.json} の
 * {@code mixins} から1行外すこと。
 */
@Mixin(ServerChunkCache.class)
public abstract class AircraftChunkProbeMixin {
    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)"
            + "Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"))
    private void ashvehicles$chunkWaitBegan(int x, int z, ChunkStatus status, boolean requireChunk,
            CallbackInfoReturnable<ChunkAccess> callback) {
        AircraftProfile.chunkIn(requireChunk);
    }

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)"
            + "Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("RETURN"))
    private void ashvehicles$chunkWaitEnded(int x, int z, ChunkStatus status, boolean requireChunk,
            CallbackInfoReturnable<ChunkAccess> callback) {
        AircraftProfile.chunkOut(requireChunk);
    }
}
