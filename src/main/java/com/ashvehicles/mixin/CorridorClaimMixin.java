package com.ashvehicles.mixin;

import java.util.function.Function;

import com.ashvehicles.AshVehicles;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * この MOD のチケットを、tick スレッドで地形を建てずに置く。
 *
 * <p><b>これも1行の話だ。</b> {@code ForcedChunkManager.forceChunk} は帳簿へチケットを足した直後に
 * {@code level.getChunk(chunkX, chunkZ)} を呼ぶ（21.1.234 のソース96行、バイトコードでは
 * {@code invokevirtual ServerLevel.getChunk:(II)LevelChunk}）。戻り値は {@code pop} され、誰も使わない。
 * つまりこの行が買っているのは値ではなく<em>同期性</em>だけ——「この呼び出しが返る時、chunk は在る」。
 * その代金は {@code ServerChunkCache.getChunk} → {@code managedBlock} で、サーバースレッドが
 * {@code Thread.yield()} と 100µs の {@code parkNanos} を繰り返しながら、別スレッドの生成器が
 * 手付かずの土地を建て終わるのを待つ。
 *
 * <p><b>チケット自体は待たない。</b> 行を飛ばしても {@code tickets.add} は済んでおり、
 * {@code saveData.setDirty(true)} も、その後の
 * {@code level.getChunkSource().addRegionTicket(type, pos, 2, owner, ticking)} も走る。距離2は
 * {@code DistanceManager.addRegionTicket} で {@code ChunkLevel.byStatus(FULL) - 2 = 31}——
 * {@code ENTITY_TICKING} そのものだ。次の {@code ServerChunkCache.tick} が
 * {@code runDistanceManagerUpdates} を回し、ホルダーが 31 に落ち、{@code ChunkHolder.updateFutures} が
 * {@code prepareAccessibleChunk}／{@code prepareTickingChunk}／{@code prepareEntityTickingChunk} を積み、
 * {@code ChunkMap.runGenerationTasks} がそれを {@code worldgenMailbox}——
 * {@code Util.backgroundExecutor()} の上のメールボックス——へ渡す。生成は起きる。ただ、tick スレッドの
 * 上ではない。
 *
 * <p><b>再起動を越える性質も失わない。</b> 保存されるのは {@code tickets.add} が触った
 * {@code ForcedChunksSavedData} であって、消す1行ではない。
 *
 * <p><b>失うのは「返った瞬間に地形が在る」だけ。</b> チケットが着いてから地形が届くまでの数tick、その
 * chunk の {@code Visibility} は {@code HIDDEN} のままで、そこへ入った機体は
 * {@code PersistentEntitySectionManager.Callback.onMove} に {@code stopTracking} される。だから
 * <em>この mixin だけでは足りない</em>——機体の下の chunk については、{@code Entity.setPosRaw} の
 * NeoForge 追加行が今も同じ待ちを行っている（{@link RoundChunkLoadMixin} 参照）。この行を消して意味が
 * あるのは、そちらも同時に扱い、追跡を保つ別の手立てを用意した後だ。
 *
 * <p><b>自分のチケットだけ。</b> 名前空間で見分ける。他の MOD の強制ロードは、それが同期であることを
 * 前提に書かれているかもしれない。
 */
@Mixin(net.neoforged.neoforge.common.world.chunk.ForcedChunkManager.class)
public abstract class CorridorClaimMixin {
    /**
     * 取り立てを飛ばす。戻り値は呼び出し側で {@code pop} されるので null で構わない。
     *
     * <p>後半の9個は {@code forceChunk} 自身の引数をそのまま受け取っている（mixin の引数取り込み）。
     * 欲しいのは2番目の {@code id}——チケットの持ち主のコントローラ ID だ。{@code forceChunk} という名前の
     * メソッドはこのクラスに2つあるので、{@code method} には記述子まで書く。
     */
    @Redirect(method = "forceChunk(Lnet/minecraft/server/level/ServerLevel;"
                    + "Lnet/minecraft/resources/ResourceLocation;Ljava/lang/Comparable;IIZZ"
                    + "Lnet/minecraft/server/level/TicketType;Ljava/util/function/Function;)Z",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;getChunk(II)"
                            + "Lnet/minecraft/world/level/chunk/LevelChunk;"))
    private static LevelChunk ashvehicles$claimWithoutBuilding(ServerLevel level, int chunkX, int chunkZ,
            ServerLevel target, ResourceLocation id, Comparable<?> owner, int x, int z,
            boolean add, boolean ticking, TicketType<?> type, Function<?, ?> ticketGetter) {
        if (AshVehicles.MODID.equals(id.getNamespace())) {
            return null;
        }

        return level.getChunk(chunkX, chunkZ);
    }
}
