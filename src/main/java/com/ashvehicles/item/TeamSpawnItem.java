package com.ashvehicles.item;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

/**
 * 出撃地点を持ち運ぶためのアイテム。
 *
 * <p>一言添えるのは、置いただけでは何も起きないから。旗になるのは
 * {@code /tdm spawn <陣営>} を通した後で、それを他から知る手立てが無い。
 */
public class TeamSpawnItem extends BlockItem {
    public TeamSpawnItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.ashvehicles.team_spawn").withStyle(ChatFormatting.DARK_GRAY));
    }
}
