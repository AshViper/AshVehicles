package com.ashvehicles.item;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

/**
 * 拠点の旗竿を持ち運ぶためのアイテム。
 *
 * <p>置いただけでは何も起きない。拠点になるのは {@code /tdm point add} を通した後で、それを他から知る
 * 手立てが無い。
 */
public class CapturePointItem extends BlockItem {
    public CapturePointItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.ashvehicles.capture_point").withStyle(ChatFormatting.DARK_GRAY));
    }
}
