package com.ashvehicles.mixin;

import com.ashvehicles.entity.LateWorld;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link LateWorld} の窓が開いている間、まだ無い chunk を「空」として返す。生成もせず、待ちもしない。
 *
 * <p>{@code ServerChunkCache.getChunk} はサーバーの地面の問い合わせ全部が最後に通る1本道で、ここで
 * 「今持っているか」を先に訊けば、その上の全部——ブロック、流体、当たり判定、位置更新の「移動先を
 * ロードする」1行——が一度に片付く。呼び出し元ごとに免除を書いて回る代わりに、ここで1度だけ答える。
 *
 * <p>返す物は2種類。ロードを要求しない呼び出し（{@code requireChunk = false}、当たり判定がこれ）には
 * null——それはこの引数が元から約束している「無ければ無い」の答えで、待つ方がむしろ約束違反だった。
 * 要求する呼び出しには {@link EmptyLevelChunk}。クライアントが未受信の chunk に使っている物と同じで、
 * 読めば全部 {@code VOID_AIR}、書き込みは捨てられる。返した chunk は誰も保持しないので（位置更新は
 * 戻り値を捨て、ブロック読み取りは1状態を取って終わる）、直前の1個だけ覚えておけば足りる。
 *
 * <p><b>優先度が 1100 なのは {@code ChunkStallMixin} より後に適用されるため。</b> あちらは同じ入口と
 * 出口に計測を挟み、入口と出口の回数が揃っている前提で深さを数えている。後から適用された注入は入口の
 * 先頭に置かれるので、こちらが先に走って早期に返れば、あちらの入口は踏まれず、出口も要らない。逆の順に
 * すると入口だけ踏んで出口を飛ばすことになり、計測が二度と報告しなくなる。
 */
@Mixin(value = ServerChunkCache.class, priority = 1100)
public abstract class LateWorldMixin {
    @Shadow
    @Final
    public ServerLevel level;

    @Shadow
    public abstract LevelChunk getChunkNow(int chunkX, int chunkZ);

    @Unique
    private LevelChunk ashvehicles$sky;
    @Unique
    private long ashvehicles$skyPos = ChunkPos.INVALID_CHUNK_POS;

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)"
                    + "Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"), cancellable = true)
    private void ashvehicles$answerWithSky(int x, int z, ChunkStatus status, boolean requireChunk,
            CallbackInfoReturnable<ChunkAccess> callback) {
        if (!LateWorld.inside() || !this.level.getServer().isSameThread() || this.getChunkNow(x, z) != null) {
            return;
        }

        callback.setReturnValue(requireChunk ? this.ashvehicles$sky(x, z) : null);
    }

    /** その座標の空の chunk。直前と同じ座標なら同じ物。 */
    @Unique
    private LevelChunk ashvehicles$sky(int x, int z) {
        long key = ChunkPos.asLong(x, z);

        if (this.ashvehicles$sky == null || this.ashvehicles$skyPos != key) {
            this.ashvehicles$sky = new EmptyLevelChunk(this.level, new ChunkPos(x, z),
                    this.level.registryAccess().registryOrThrow(Registries.BIOME)
                            .getHolderOrThrow(Biomes.PLAINS));
            this.ashvehicles$skyPos = key;
        }

        return this.ashvehicles$sky;
    }
}
