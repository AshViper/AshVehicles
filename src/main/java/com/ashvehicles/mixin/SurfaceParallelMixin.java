package com.ashvehicles.mixin;

import java.util.concurrent.CompletableFuture;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.ParallelSurface;

import net.minecraft.Util;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 地表生成を、1 chunk ずつではなく層まるごと並列で走らせる。
 *
 * <p><b>なぜここだけなのか。</b> 生成は12段の階段で、1機の飛行が要求する chunk はその階段を層ごとに
 * 上がる。層の境界は本物の関門だ——{@code ChunkGenerationTask.waitForScheduledLayer} は、claim の中の
 * <em>全部</em>の chunk が層 N を終えるまで層 N+1 を始めない。だから層 N を走っている間、他の chunk は
 * 例外なく層 N−1 で凍っている。
 *
 * <p>その関門のおかげで、ノイズ段は既に完全に並列だ。{@code NoiseBasedChunkGenerator.fillFromNoise} は
 * {@code supplyAsync(..., Util.backgroundExecutor())} を返すので、層の全 chunk が15スレッドへ散る。
 * 地表段だけが {@code CompletableFuture.completedFuture(chunk)} を返しており、つまり<em>直列</em>だ——
 * ワールド生成メールボックスの1本のスレッドの上で、1 chunk ずつ順番に。同じ関門の内側にいるのに、
 * 隣の段が使っている並列性を使っていない。ここが埋めるのはその差であって、新しい並列性の発明ではない。
 *
 * <p><b>なぜ安全だと言えるのか。</b> 3つが重なっている。
 *
 * <ol>
 *   <li>生成ピラミッドで地表段は {@code blockStateWriteRadius(0)} を宣言している。書き込むのは自分の
 *       chunk だけで、それは {@code WorldGenRegion.ensureCanWrite} が実際に強制している。
 *   <li>{@code NoiseBasedChunkGenerator.buildSurface} は自分の chunk しか触らない。渡された chunk の
 *       {@code NoiseChunk} を作り、その柱を上から下へ埋めるだけで、隣を巡る輪が無い。<b>これが彫刻段
 *       （carvers）を並列にしていない理由でもある。</b>あちらは ±8 chunk を巡って隣の
 *       {@code ChunkAccess.carverBiome} を呼び、あれは同期無しの遅延初期化で隣のフィールドへ書く。
 *   <li>chunk ごとの生成の帳簿は元から並行前提で書かれている。{@code GenerationChunkHolder} の状態は
 *       {@code AtomicReferenceArray} と {@code AtomicReference} で、段を進める権利は
 *       {@code acquireStatusBump} の {@code compareAndExchange} が1人にだけ渡す。
 * </ol>
 *
 * <p><b>止め方。</b> {@link ParallelSurface#ENABLED} を false にすればバニラの直列動作へ戻る。世界の
 * データが壊れる類の疑いが出たら、まずここを切って再現するか確かめること。
 *
 * <p><b>例外を握り潰さないこと。</b> ここが返す future の中で投げられた物は、バニラでは誰も join しない
 * {@code ForkJoinTask} の中で消える——スレッド安全性の違反が「静かに壊れたセーブ」として現れる唯一の
 * 経路がそれだ。だから自分で捕まえて記録してから投げ直す。
 */
@Mixin(net.minecraft.world.level.chunk.status.ChunkStatusTasks.class)
public abstract class SurfaceParallelMixin {
    @Inject(method = "generateSurface", at = @At("HEAD"), cancellable = true)
    private static void ashvehicles$buildSurfaceOffTheMailbox(WorldGenContext worldGenContext, ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> callback) {
        if (!ParallelSurface.ENABLED) {
            return;
        }

        callback.setReturnValue(CompletableFuture.supplyAsync(() -> {
            try {
                ServerLevel level = worldGenContext.level();
                // 領域は生成器のスレッドで作る。バニラがメールボックスのスレッドで作っているのと同じ物で、
                // 中身は claim への読み取り窓と書き込み半径の検査だけだ。持ち回る値を持たない。
                WorldGenRegion region = new WorldGenRegion(level, cache, step, chunk);

                worldGenContext.generator().buildSurface(region,
                        level.structureManager().forWorldGenRegion(region),
                        level.getChunkSource().randomState(), chunk);

                return chunk;
            } catch (Throwable failed) {
                AshVehicles.LOGGER.error("[chunk] 地表生成が {} で落ちた。並列化を疑うなら ParallelSurface を切ること",
                        chunk.getPos(), failed);

                throw failed;
            }
        }, Util.backgroundExecutor()));
    }
}
