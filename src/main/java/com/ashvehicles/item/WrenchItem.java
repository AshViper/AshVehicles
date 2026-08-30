package com.ashvehicles.item;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 整備員の工具。機体をばらすための道具。
 *
 * <p>機体に対する作業を戻す操作はすべてこれを要求する。搭載済みパイロンをこれでクリックすれば兵装が
 * 外れ、パイロンが空の状態で機体本体をクリックすれば、設置元のアイテムに畳まれて戻る。
 *
 * <p>これが存在するのは、以前それがスニーク＋クリックだったから。スニーク＋クリックは誰も意図して行う
 * 操作ではない。しゃがみは翼の上を落ちずに歩くための姿勢で、胴体の近くでそれをやると足元の機体が畳まれ
 * てしまった。工具の後ろに置けば、機体をばらせるのは「機体をばらす道具」を持っている者だけになる。
 *
 * <p>アイテム自体は何もしない。中身は全部
 * {@link com.ashvehicles.entity.AircraftEntity#interact} にある。
 */
public class WrenchItem extends Item {
    public WrenchItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.ashvehicles.wrench").withStyle(ChatFormatting.DARK_GRAY));
    }
}
