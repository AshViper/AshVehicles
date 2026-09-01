package com.ashvehicles.item;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 携帯管制端末。無人機へ繋ぐための箱。
 *
 * <p><b>アイテム自体は何もしない。</b>右クリックを見ているのは
 * {@link com.ashvehicles.client.DroneTerminal} で、開くのも
 * {@link com.ashvehicles.client.screen.DroneTerminalScreen} だ。ここに置かないのは、端末が世界に対して
 * 何一つ行わないから——押した結果はサーバーへの1本のパケットであり、それを送る判断は画面の側にある。
 * {@link WrenchItem} と同じ理由で同じ形をしている。
 *
 * <p><b>誰の物でもない機体へ繋ぐ。</b>この MOD の機体に所有者はいない。歩いて近付いた者が F-16 に乗れる
 * のと同じように、端末を持つ者が無人機を飛ばす。端末が要るのは権限のためではなく、無人機には乗り込む場所
 * が無く、繋ぐ手順がどこかに要るからだ。
 */
public class DroneTerminalItem extends Item {
    public DroneTerminalItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.ashvehicles.drone_terminal").withStyle(ChatFormatting.DARK_GRAY));
    }
}
