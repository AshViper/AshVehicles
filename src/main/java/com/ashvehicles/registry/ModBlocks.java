package com.ashvehicles.registry;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.block.CapturePointBlock;
import com.ashvehicles.block.TeamSpawnBlock;
import com.ashvehicles.block.VehicleWorkbenchBlock;

import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * この MOD のブロック。地面に残る物はここに並ぶ3つだけ。
 *
 * <p>置いたアイテムがそのまま乗り物になるこの MOD で、地面に残るのはここに並ぶ物だけだ。
 */
public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(AshVehicles.MODID);

    /**
     * 車両工廠。鉄の卓なので石のツルハシ以上で掘れ、素手では落ちない。
     */
    public static final DeferredBlock<VehicleWorkbenchBlock> VEHICLE_WORKBENCH = BLOCKS.registerBlock(
            "vehicle_workbench",
            VehicleWorkbenchBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(3.5F, 6.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops());

    /**
     * チームデスマッチの出撃地点。試合が登録して初めて陣営の旗になる（{@link TeamSpawnBlock}）。
     *
     * <p>工廠より硬く、爆風では壊れない。試合の最中に自陣の旗が爆撃で消えるのは、演出ではなく事故だ。
     */
    public static final DeferredBlock<TeamSpawnBlock> TEAM_SPAWN = BLOCKS.registerBlock(
            "team_spawn",
            TeamSpawnBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_GRAY)
                    .strength(3.5F, 1200.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops());

    /**
     * 拠点の旗竿。試合が登録して初めて拠点になる（{@link CapturePointBlock}）。
     *
     * <p>出撃地点と同じく爆風では壊れない。取り合っている旗が榴弾で消える試合は、制圧ではなく撤去に
     * なる。
     */
    public static final DeferredBlock<CapturePointBlock> CAPTURE_POINT = BLOCKS.registerBlock(
            "capture_point",
            CapturePointBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_YELLOW)
                    .strength(3.5F, 1200.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops());

    private ModBlocks() {
    }
}
