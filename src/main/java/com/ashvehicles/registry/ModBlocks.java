package com.ashvehicles.registry;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.block.VehicleWorkbenchBlock;

import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * この MOD のブロック。今のところ工廠1つだけ。
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

    private ModBlocks() {
    }
}
