package com.ashvehicles.block;

import com.ashvehicles.menu.VehicleWorkbenchMenu;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 車両工廠。触ると5x5の盤面が開く、この MOD で唯一のブロック。
 *
 * <p>持つ物は何も無い。バニラの作業台と同じく盤面は開いている間だけのもので、閉じれば中身は
 * 手元に返る。だから BlockEntity は要らず、ブロックは口を開けるだけの物になる。
 */
public class VehicleWorkbenchBlock extends Block {
    public static final MapCodec<VehicleWorkbenchBlock> CODEC = simpleCodec(VehicleWorkbenchBlock::new);

    private static final Component TITLE = Component.translatable("container.ashvehicles.vehicle_workbench");

    public VehicleWorkbenchBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<? extends VehicleWorkbenchBlock> codec() {
        return CODEC;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        player.openMenu(state.getMenuProvider(level, pos));

        return InteractionResult.CONSUME;
    }

    /**
     * 開く盤面。{@link ContainerLevelAccess} がブロックの位置を握っていて、これが離れたら
     * 盤面が閉じるという判定と、閉じたときに素材を返す先を決める。
     */
    @Override
    protected MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        return new SimpleMenuProvider(
                (containerId, inventory, player) -> new VehicleWorkbenchMenu(containerId, inventory,
                        ContainerLevelAccess.create(level, pos)),
                TITLE);
    }
}
