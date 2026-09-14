package com.ashvehicles.ai.air;

import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * 空から見た地面の高さ。
 *
 * <p><b>読むのは既に在る chunk だけ</b>（{@code getChunkNow}）。飛んでいる AI が地形を訊くたびに生成を起こせば、1機が
 * 毎秒数 km の地面を tick スレッドの上で作ることになる（[[explosions-generate-chunks]] と同じ穴）。読めない列は
 * 「分からない」と答え、呼ぶ側がそれを高い側に倒して読む。
 */
public final class Ground {
    private Ground() {
    }

    /** その列で動きを止める一番上の面の高さ。木の葉と水面を含む。読めなければ {@link Double#NaN}。 */
    public static double at(Level level, double x, double z) {
        int blockX = Mth.floor(x);
        int blockZ = Mth.floor(z);
        LevelChunk chunk = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(blockX),
                SectionPos.blockToSectionCoord(blockZ));

        return chunk == null ? Double.NaN : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ);
    }

    /**
     * 線の上で一番高い地面。{@code from} から水平の向き {@code along} へ {@code length} ブロック、{@code step} おき。
     *
     * @param unknown 読めない列の高さとして数える値
     */
    public static double highest(Level level, Vec3 from, Vec3 along, double length, double step, double unknown) {
        double flat = Math.sqrt(along.x * along.x + along.z * along.z);
        double dx = flat < 1.0E-6 ? 0.0 : along.x / flat;
        double dz = flat < 1.0E-6 ? 0.0 : along.z / flat;
        double highest = Double.NEGATIVE_INFINITY;

        for (double at = 0.0; at <= length; at += step) {
            double ground = at(level, from.x + dx * at, from.z + dz * at);

            highest = Math.max(highest, Double.isNaN(ground) ? unknown : ground);
        }

        return highest;
    }
}
