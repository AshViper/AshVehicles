package com.ashvehicles.item;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

/**
 * 車両工廠を持ち運ぶためのアイテム。置くだけで、ブロックの側に全部ある。
 *
 * <p>一言だけ添えるのは、これが要ることを他から知る手立てが無いから。機体のレシピは工廠の型で
 * 書かれていてバニラの卓に出てこないので、工廠を知らないプレイヤーには機体のレシピが存在しないのと
 * 同じに見える。
 */
public class VehicleWorkbenchItem extends BlockItem {
    public VehicleWorkbenchItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.ashvehicles.vehicle_workbench").withStyle(ChatFormatting.DARK_GRAY));
    }
}
