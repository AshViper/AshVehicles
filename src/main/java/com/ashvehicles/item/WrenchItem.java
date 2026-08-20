package com.ashvehicles.item;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * The ground crew's tool: what an aeroplane is taken apart with.
 *
 * <p>Everything that undoes work on an aircraft asks for one of these. Click a loaded pylon with it
 * and the store comes off; click the aeroplane itself, once its pylons are bare, and the whole thing
 * folds back into the item it was put down from.
 *
 * <p>It exists because that used to be a sneak-click, and a sneak-click is not something anybody
 * asks for on purpose. Crouching is how a player walks along a wing without falling off it, and
 * doing that near the fuselage packed the aeroplane away underneath them. Putting it behind a tool
 * means the aircraft can only be taken to pieces by somebody holding the thing that takes aircraft
 * to pieces.
 *
 * <p>The item does nothing on its own; all of it is in
 * {@link com.ashvehicles.entity.AircraftEntity#interact}.
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
