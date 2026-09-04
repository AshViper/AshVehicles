package com.ashvehicles.entity;

import net.minecraft.server.level.ServerLevel;

/**
 * 回廊がチケットを立てている最中であることの印。
 *
 * <p><b>1行を黙らせるためだけに在る。</b> NeoForge の {@code ForcedChunkManager.forceChunk} は、チケットを
 * 帳簿に足した直後にこうする——
 *
 * <pre>
 *   success = tickets.add(ticketOwner, chunk, ticking);
 *   if (success)
 *       level.getChunk(chunkX, chunkZ);   // 戻り値は捨てられる
 * </pre>
 *
 * <p>逆コンパイルではなくバイトコードで確かめた。{@code invokevirtual ServerLevel.getChunk} の次の命令は
 * {@code pop} で、返ってきた chunk は誰も見ない。あの行が買っているのは<em>同期性</em>だけだ。そして
 * {@code Level.getChunk(int,int)} は {@code ChunkStatus.FULL} を {@code requireChunk = true} で要求するので、
 * 地形が無ければ {@code ServerChunkCache.managedBlock} に入り、サーバースレッドは
 * {@code Thread.yield()} と {@code parkNanos(100µs)} を繰り返しながら、別スレッドが地形を作り終えるのを
 * 待つ。機体が未生成の chunk へ入るたびに、それが起きていた。
 *
 * <p><b>チケットだけで足りる。</b> {@code addRegionTicket(type, pos, 2, owner, ticking)} は
 * {@code ChunkLevel.byStatus(FULL) - 2 = 31}——ちょうど entity-ticking の水準——のチケットを立て、次の
 * {@code ServerChunkCache.tick} が {@code ChunkHolder} を作り、生成を {@code Util.backgroundExecutor()}
 * へ流す。誰も待たない。完了は {@code mainThreadMailbox} 経由で戻ってくる。
 *
 * <p><b>保存も失われない。</b> 再起動を跨ぐ保持は {@code tickets.add} と
 * {@code saveData.setDirty(true)} が書く {@code ForcedChunksSavedData} の仕事で、消す1行はそこに何も
 * 寄与していない。回廊が手放せない唯一の性質はそれなので、確かめてある。
 *
 * <p><b>印にしてあるのは、mixin が他人の呼び出しまで巻き込まないため。</b> あの mixin は NeoForge の
 * 共有クラスに載る。誰の {@code forceChunk} でも黙らせてしまえば、地面が在ることを本当に当てにしている
 * 他の MOD のチャンクローダーが静かに壊れる。ここを開けている間だけ——つまりこの MOD の回廊が自分の
 * チケットを立てている、同じスレッドの、その数命令の間だけ——飛ばす。
 */
public final class CorridorClaim {
    /** サーバースレッド専用。回廊の更新はそこからしか走らない。 */
    private static boolean inside;

    private CorridorClaim() {
    }

    /** 今、この MOD の回廊がチケットを立てている最中か。mixin だけが訊く。 */
    public static boolean inside() {
        return inside;
    }

    /**
     * 印を立てて確保し、必ず倒す。
     *
     * @param claim 実際に {@code forceChunk} を呼ぶ処理
     */
    public static void quietly(ServerLevel level, Runnable claim) {
        if (!(level.getServer().isSameThread())) {
            claim.run();

            return;
        }

        inside = true;

        try {
            claim.run();
        } finally {
            inside = false;
        }
    }
}
