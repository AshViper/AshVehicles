package com.ashvehicles.ai.battlefield;

import java.util.Map;
import java.util.WeakHashMap;

import com.ashvehicles.ai.AiConfig;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * 4×4 ブロックのマスごとの地形の読み。全 AI で共有し、しばらく覚える。
 *
 * <p><b>世界全体を解析しない。</b> 読むのは誰かが訊いたマスだけで、訊くのは AI の周りの戦術格子
 * （{@link TacticalMap}）と遮蔽の探索だけだ。1マスの値段はハイトマップ5列と、中心の1列を数ブロック
 * （{@link BuildingAnalysis}）。同じ会場の40両は同じ地形を見ているので、1両が読んだマスは全員の物にする。
 *
 * <p><b>覚えるのは読めたマスだけ。</b> ロードされていない土地は {@link Column#UNKNOWN} を返し、覚えない——次に
 * 訊かれた時に chunk が届いていれば、そこで初めて読む。地形は試合中ほとんど変わらない（爆発は既定で地形を
 * 壊さない——{@code Deathmatch.protectsTerrain}）が、車両は柵や木を薙ぎ倒すので、覚える長さは有限にしてある
 * （{@code threat.terrainCacheTicks}）。
 *
 * <p>サーバースレッド専用。
 */
public final class TerrainCache {
    /** マスの一辺（ブロック）。経路探索の格子（{@code navigation/RoutePlanner}）と同じ。 */
    public static final int CELL = 4;

    /** 覚えるマスの上限。戦域 500 ブロックの円は約5万マスなので、その4倍。超えたら一度全部忘れる。 */
    private static final int MOST = 200_000;

    /** 古いマスを掃く間隔（tick）。 */
    private static final int PRUNE_EVERY = 400;

    private static final Map<Level, TerrainCache> BY_LEVEL = new WeakHashMap<>();

    /**
     * 1マスの読み。
     *
     * @param known     読めた。偽なら他の値は全部意味が無い
     * @param ground    中心の地面の高さ（上面の y、葉を除く）
     * @param minGround 中心と四隅の地面の、一番低い所
     * @param maxGround 同じく一番高い所
     * @param sight     中心で視線を遮る面の高さ（葉を含む）
     * @param maxSight  中心と四隅で視線を遮る面の、一番高い所
     * @param water     深い水
     * @param roof      屋根のある建物
     * @param manmade   一番上が人工物
     * @param stamp     読んだ tick
     */
    public record Column(boolean known, double ground, double minGround, double maxGround, double sight,
            double maxSight, boolean water, boolean roof, boolean manmade, int stamp) {
        public static final Column UNKNOWN = new Column(false, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, false, false, false, 0);

        /** マスの中の起伏（ブロック）。 */
        public double rise() {
            return this.maxGround - this.minGround;
        }
    }

    private final Level level;
    private final Long2ObjectOpenHashMap<Column> cells = new Long2ObjectOpenHashMap<>();
    private int prunedAt = Integer.MIN_VALUE;

    private TerrainCache(Level level) {
        this.level = level;
    }

    /** そのワールドの読み。 */
    public static TerrainCache of(Level level) {
        return BY_LEVEL.computeIfAbsent(level, TerrainCache::new);
    }

    /** サーバーが止まったとき。 */
    public static void clear() {
        BY_LEVEL.clear();
    }

    /** 古い読みを掃く。{@code core/AiDirector} から毎 tick 呼ばれ、中で間引く。 */
    public static void pruneAll(int now) {
        for (TerrainCache cache : BY_LEVEL.values()) {
            cache.prune(now);
        }
    }

    public static int cellOf(double coord) {
        return Mth.floor(coord / CELL);
    }

    /** そのマスの中心（ブロック座標）。 */
    public static double centreOf(int cell) {
        return cell * CELL + CELL / 2.0;
    }

    public int size() {
        return this.cells.size();
    }

    /** そのマスの読み。覚えていれば覚えている物、無ければ今読む。 */
    public Column column(int cellX, int cellZ, int now) {
        long key = ChunkPos.asLong(cellX, cellZ);
        Column known = this.cells.get(key);

        if (known != null && now - known.stamp() <= AiConfig.threats().terrainCacheTicks()) {
            return known;
        }

        Column read = this.read(cellX, cellZ, now);

        if (read.known()) {
            if (this.cells.size() >= MOST) {
                this.cells.clear();
            }

            this.cells.put(key, read);
        }

        return read;
    }

    private void prune(int now) {
        // 一度も掃いていない印（MIN_VALUE）との差は桁あふれして負になる。印は差を取る前に見る。
        if (this.prunedAt != Integer.MIN_VALUE && now - this.prunedAt < PRUNE_EVERY) {
            return;
        }

        this.prunedAt = now;

        int life = AiConfig.threats().terrainCacheTicks();
        ObjectIterator<Long2ObjectOpenHashMap.Entry<Column>> walk = this.cells.long2ObjectEntrySet().fastIterator();

        while (walk.hasNext()) {
            if (now - walk.next().getValue().stamp() > life) {
                walk.remove();
            }
        }
    }

    private Column read(int cellX, int cellZ, int now) {
        HeightField field = HeightField.of(this.level);
        int baseX = cellX * CELL;
        int baseZ = cellZ * CELL;
        int middleX = baseX + CELL / 2;
        int middleZ = baseZ + CELL / 2;
        double ground = field.ground(middleX, middleZ);

        if (Double.isNaN(ground)) {
            return Column.UNKNOWN;
        }

        double sight = field.sightLine(middleX, middleZ);
        double low = ground;
        double high = ground;
        double tallest = sight;

        for (int corner = 0; corner < 4; corner++) {
            int x = baseX + ((corner & 1) == 0 ? 0 : CELL - 1);
            int z = baseZ + ((corner & 2) == 0 ? 0 : CELL - 1);
            double corneredGround = field.ground(x, z);

            // マスが chunk の境を跨いでいて、向こう側がまだ無い。読めた隅だけで決める。
            if (Double.isNaN(corneredGround)) {
                continue;
            }

            low = Math.min(low, corneredGround);
            high = Math.max(high, corneredGround);
            tallest = Math.max(tallest, field.sightLine(x, z));
        }

        ChunkAccess chunk = this.level.getChunkSource().getChunkNow(middleX >> 4, middleZ >> 4);

        if (chunk == null) {
            return Column.UNKNOWN;
        }

        BuildingAnalysis.Reading reading = BuildingAnalysis.read(chunk, middleX, (int) ground - 1, middleZ);

        return new Column(true, ground, low, high, sight, tallest, reading.water(), reading.roof(),
                reading.manmade(), now);
    }
}
