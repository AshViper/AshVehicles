package com.ashvehicles.ai.battlefield;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * 1列のブロックを数個だけ読んで、そこが建物か、屋根の下か、水か、を見分ける。
 *
 * <p><b>建物を「ブロックの集まり」ではなく「戦術的な形」として読む。</b> 車両にとって意味があるのは、壁（視線を
 * 切る物）、屋根（上からは見えない所）、中（床と屋根の間）、入口（壁の切れ目）の4つで、どのブロックで出来て
 * いるかではない。ここが答えるのは列1本についての事実で、壁や入口や遮蔽といった隣との関係は
 * {@link TacticalMap} が列同士を比べて出す。
 *
 * <p><b>読むのは列の上から数ブロックだけ</b>（{@value #SCAN}）。建物を丸ごと解析しない——必要なのは AI の周りの
 * 数十マスで、しかも {@link TerrainCache} が60秒覚える。
 *
 * <p><b>人工物かどうかは推定。</b> 自然の地面を作るブロック（土・砂・石・雪・氷・丸太・葉・テラコッタ・砂利・粘土）
 * の一覧に無い、動きを遮るブロックを人工物と読む。外れても害は小さい——遮蔽の価値は高さから別に出していて、
 * これが変えるのは「建物」という読みと可視化の色だけだ。
 */
public final class BuildingAnalysis {
    /** 屋根の下を探す深さ（ブロック）。2階建ての屋根から床まで。 */
    private static final int SCAN = 8;

    /** 屋根の下と見なすのに要る、屋根と床の間の空き（ブロック）。人が立てる高さ。 */
    private static final int HEADROOM = 2;

    /**
     * 1列の読み。
     *
     * @param water   上2ブロックが水。車両は入らない
     * @param roof    一番上のブロックが人工物で、その下に床と空きがある
     * @param manmade 一番上のブロックが人工物
     * @param floor   屋根の下の床の高さ（上面の y）。屋根が無ければ {@link Integer#MIN_VALUE}
     */
    public record Reading(boolean water, boolean roof, boolean manmade, int floor) {
    }

    private BuildingAnalysis() {
    }

    /**
     * @param top その列で一番上にある、動きを遮るブロックの y（葉を除く）
     */
    public static Reading read(ChunkAccess chunk, int x, int top, int z) {
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos(x, top, z);
        BlockState surface = chunk.getBlockState(at);
        boolean water = chunk.getFluidState(at).is(FluidTags.WATER)
                && chunk.getFluidState(at.set(x, top - 1, z)).is(FluidTags.WATER);

        if (water) {
            return new Reading(true, false, false, Integer.MIN_VALUE);
        }

        boolean manmade = surface.blocksMotion() && !natural(surface);
        int floor = Integer.MIN_VALUE;
        int gap = 0;

        for (int y = top - 1; y >= top - SCAN && y >= chunk.getMinBuildHeight(); y--) {
            BlockState state = chunk.getBlockState(at.set(x, y, z));

            if (!state.blocksMotion()) {
                gap++;

                continue;
            }

            if (gap >= HEADROOM) {
                floor = y + 1;

                break;
            }

            // 詰まっている。屋根が厚いか、ただの地面。空きを数え直す。
            gap = 0;
        }

        return new Reading(false, manmade && floor != Integer.MIN_VALUE, manmade, floor);
    }

    /** 自然の地面を作るブロックか。 */
    public static boolean natural(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.BASE_STONE_NETHER) || state.is(BlockTags.SNOW) || state.is(BlockTags.ICE)
                || state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.is(BlockTags.TERRACOTTA)
                || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(Blocks.SANDSTONE)
                || state.is(Blocks.RED_SANDSTONE) || !state.getFluidState().isEmpty();
    }
}
