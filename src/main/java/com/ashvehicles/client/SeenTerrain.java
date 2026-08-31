package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;

import it.unimi.dsi.fastutil.longs.Long2ShortLinkedOpenHashMap;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * 一度でも手元に届いた地面を覚えておく。クライアントがその chunk を手放した後も。
 *
 * <p><b>なぜ要るか。</b>{@link Terrain} が答えられるのは「今持っている chunk」までで、離れれば忘れる。だが
 * 走ってきた道を地図から消す理由は無い——通ってきた土地の形は、通った時点で本当に見た物だ。射撃指揮盤
 * （{@code LaunchMap}）が最も必要とするのもそこで、乗員が座標を選ぶのは<em>今見えている</em>場所ではなく
 * 大抵さっき通ってきた場所である。
 *
 * <p><b>覚えるのは chunk が届いた瞬間に1回だけ。</b>{@link ChunkEvent.Load} で16点——4ブロックに1点——を
 * 高さマップから読む。走査も polling も無く、コストは chunk 1つにつき16回の配列参照だ。
 *
 * <p><b>4ブロックに1点なのは値段の話だ。</b>1ブロックごとに覚えれば16倍になる。地図が1ピクセル1ブロックまで
 * 拡大できるとはいえ、その倍率で見ているのは自分の足元——つまり今まさに chunk を持っている場所——であり、
 * 記憶が要るのはもっと引いた倍率の方だ。
 *
 * <p><b>上限を持ち、古い物から捨てる。</b>ワールドを走り回れば際限なく増える種類の記憶なので、入った順に
 * 落とす。落ちるのは一番昔に通った土地で、それは地図の上で一番どうでもいい土地でもある。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class SeenTerrain {
    /** 何ブロックに1点覚えるか。 */
    private static final int STEP = 4;
    /** 1辺の点数。chunk は16ブロックなので4点。 */
    private static final int PER_CHUNK = 16 / STEP;

    /**
     * 覚えておく点の数の上限。
     *
     * <p>60万点は4ブロック格子で 3100×3100 ブロック相当——隙間なく塗り潰した場合で、実際には走った跡なので
     * もっと広い範囲に散る。1点あたり long と short で12バイト、ハッシュの余白を入れても十数MBに収まる。
     */
    private static final int MOST = 600_000;

    /** 覚えている高さ。挿入順を保つので、溢れたら一番古い点から落とせる。 */
    private static final Long2ShortLinkedOpenHashMap SEEN = new Long2ShortLinkedOpenHashMap();

    /** 覚えが無い点。{@link Short#MIN_VALUE} は Minecraft のどの高さでもない。 */
    private static final short NONE = Short.MIN_VALUE;

    static {
        SEEN.defaultReturnValue(NONE);
    }

    private SeenTerrain() {
    }

    /**
     * 覚えている地面の高さ。覚えが無ければ {@link Double#NaN}。
     *
     * <p>{@link Terrain#surface} と同じ「その列の地面の上面」を返すので、両者は取り替えが利く——片方が今の
     * 答え、もう片方が最後に見た答えというだけだ。
     */
    public static double height(double x, double z) {
        short found = SEEN.get(key(Math.floorDiv((int) Math.floor(x), STEP),
                Math.floorDiv((int) Math.floor(z), STEP)));

        return found == NONE ? Double.NaN : found;
    }

    /** chunk が届いた。その16点を覚える。 */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }

        ChunkAccess chunk = event.getChunk();
        int originX = chunk.getPos().getMinBlockX();
        int originZ = chunk.getPos().getMinBlockZ();

        for (int row = 0; row < PER_CHUNK; row++) {
            for (int column = 0; column < PER_CHUNK; column++) {
                int x = originX + column * STEP;
                int z = originZ + row * STEP;
                int top;

                try {
                    top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
                } catch (RuntimeException e) {
                    // 高さマップがまだ無い chunk。次に届いた時に覚える。
                    return;
                }

                remember(Math.floorDiv(x, STEP), Math.floorDiv(z, STEP), top);
            }
        }
    }

    private static void remember(int cellX, int cellZ, int top) {
        long at = key(cellX, cellZ);

        // 入れ直しは順序も更新する。同じ土地を通り直せば、その記憶は新しい物として扱われる。
        SEEN.remove(at);
        // 高さを short へ収める。Minecraft の世界より遥かに広いので、丸めても失う物は無い。
        SEEN.put(at, (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, top)));

        while (SEEN.size() > MOST) {
            SEEN.removeFirstShort();
        }
    }

    /** 別のワールドへ移ったら全部忘れる。座標は世界ごとに別の場所を指す。 */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            SEEN.clear();
        }
    }

    private static long key(int cellX, int cellZ) {
        return (cellX & 0xFFFFFFFFL) | ((long) cellZ << 32);
    }

    /** 覚えている点の数。デバッグ表示用。 */
    public static int size() {
        return SEEN.size();
    }
}
