package com.ashvehicles.item;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * ヘルメット装着型表示器。頭に被っている間だけ、機体の計器のうち視界に重なる物——中央の姿勢・照準・ロック枠と、
 * 左右の B スコープと警戒受信機——が画面に出る。
 *
 * <p>機体ではなく乗る人が持つ物にしてある。どの機体に乗り換えても同じように効き、脱げば同じように消える。
 * 被っていない者はコクピットの MFD の頁（RDR / RWR / PFD）を読みに行くことになる。
 *
 * <p>防具ではない。守りは持たず、頭の枠を1つ占めるだけ。アイテム自体は何もしない——読むのは
 * {@code client.AircraftHud} が {@link #isWorn} で問う。
 */
public class HmdItem extends Item implements Equipable {
    public HmdItem(Properties properties) {
        super(properties);
    }

    @Override
    public EquipmentSlot getEquipmentSlot() {
        return EquipmentSlot.HEAD;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return this.swapWithEquipmentSlot(this, level, player, hand);
    }

    /** この者が今 HMD を被っているか。 */
    public static boolean isWorn(LivingEntity wearer) {
        return wearer.getItemBySlot(EquipmentSlot.HEAD).getItem() instanceof HmdItem;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.ashvehicles.hmd").withStyle(ChatFormatting.DARK_GRAY));
    }
}
