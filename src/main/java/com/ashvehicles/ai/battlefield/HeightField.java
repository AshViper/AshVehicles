package com.ashvehicles.ai.battlefield;

import javax.annotation.Nullable;

import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 地面の高さ。視界の見積もり（{@link HeightfieldSight}・{@link ThreatMap}）と遮蔽探しが読む。
 *
 * <p><b>ブロックを歩かない。</b> 読むのは chunk が既に持っているハイトマップ1つで、1列あたり配列を1回引く
 * だけだ。視界の見積もりは1回に数千列を読むので、{@code Level.clip} のようにブロックを1つずつ歩く方法では
 * 払えない。精密な射線はここではなく {@code perception/LineOfSight} が、撃つ直前にだけ引く。
 *
 * <p><b>ハイトマップは経路の通行判定には使わない。</b> 橋の下・トンネル・切通しでは頭上の面が壁だと答える
 * （[[bots-are-a-pilot-object-on-the-vehicle]] の触角の項）。ここで答えるのは「どこまで見通せるか」と
 * 「どこに立てそうか」の見積もりで、橋の下が見通せないと言うのは視界としては正しい側の誤りだ。
 *
 * <p><b>知らない土地は NaN。</b> ロードされていない chunk を生成してまで読むことはしない
 * （[[explosions-generate-chunks]] と同じ穴）。NaN を安全と読むか危険と読むかは呼んだ側が決め、この AI では
 * どの呼び手も危険の側に倒す。
 *
 * <p>インターフェースにしてあるのは、合成した地形で試験するためと、将来ワールドの写しを別スレッドで読む実装へ
 * 差し替えるため。{@link #of} の実装はサーバースレッド専用で、別スレッドからは全部 NaN になる
 * （{@code getChunkNow} がそう答える）。
 */
public interface HeightField {
    /** その列で視線を遮る一番上の面の高さ（ブロックの上面の y）。葉と水面を含む。知らない土地なら NaN。 */
    double sightLine(int x, int z);

    /** その列で車両が立つ面の高さ（ブロックの上面の y）。葉を除く。知らない土地なら NaN。 */
    double ground(int x, int z);

    default double sightLine(double x, double z) {
        return this.sightLine(Mth.floor(x), Mth.floor(z));
    }

    default double ground(double x, double z) {
        return this.ground(Mth.floor(x), Mth.floor(z));
    }

    /**
     * そのワールドのハイトマップを読む物。<b>1回の仕事ごとに作り、tick を跨いで持ち越さないこと</b>——直前に
     * 読んだ chunk を1つ覚えているので、その chunk が降ろされた後も古い高さを答えてしまう。
     */
    static HeightField of(Level level) {
        return new Loaded(level);
    }

    /** ロード済みの chunk のハイトマップを読む実装。 */
    final class Loaded implements HeightField {
        private final Level level;
        private int chunkX = Integer.MIN_VALUE;
        private int chunkZ = Integer.MIN_VALUE;

        @Nullable
        private ChunkAccess chunk;

        Loaded(Level level) {
            this.level = level;
        }

        @Override
        public double sightLine(int x, int z) {
            ChunkAccess at = this.chunk(x, z);

            // chunk の getHeight は一番上のブロックそのものの y を返す（Level.getHeight が +1 して「その上の
            // 空気」にしている）。ここで欲しいのは面の高さなので同じく +1 する。
            return at == null ? Double.NaN : at.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1.0;
        }

        @Override
        public double ground(int x, int z) {
            ChunkAccess at = this.chunk(x, z);

            return at == null ? Double.NaN : at.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1.0;
        }

        @Nullable
        private ChunkAccess chunk(int x, int z) {
            int cx = x >> 4;
            int cz = z >> 4;

            if (cx != this.chunkX || cz != this.chunkZ) {
                this.chunkX = cx;
                this.chunkZ = cz;
                // getChunkNow は読み終わった chunk か null だけを返す。生成中の chunk にも「ある」と答える
                // hasChunk とは違う（[[ticket-level-is-not-chunk-presence]]）。
                this.chunk = this.level.getChunkSource().getChunkNow(cx, cz);
            }

            return this.chunk;
        }
    }
}
