package com.ashvehicles.item;

import java.util.List;

import com.ashvehicles.aircraft.AircraftManager;
import com.ashvehicles.registry.ModItems;
import com.ashvehicles.weapon.WeaponDefinition;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * A weapon, carried about and hung on an aircraft's pylon.
 *
 * <p>The item remembers how many rounds are in it, so a pod taken off half-empty goes back on half
 * empty. A fresh one from the creative tab is full.
 */
public class WeaponItem extends Item {
    /** Where the remaining rounds are kept on the stack. Absent means full. */
    private static final String AMMO_KEY = "Ammo";

    private final ResourceLocation weapon;

    public WeaponItem(ResourceLocation weapon, Properties properties) {
        super(properties);
        this.weapon = weapon;
    }

    /** Which weapon file this item is. */
    public ResourceLocation getWeaponId() {
        return this.weapon;
    }

    public WeaponDefinition getWeapon() {
        return AircraftManager.weapon(this.weapon);
    }

    /** One of a weapon, carrying the rounds given. */
    public static ItemStack stackOf(ResourceLocation weapon, int ammo) {
        DeferredItem<WeaponItem> item = ModItems.weapons().get(weapon);

        if (item == null) {
            return ItemStack.EMPTY;
        }

        ItemStack stack = new ItemStack(item.get());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(net.minecraft.Util.make(
                new net.minecraft.nbt.CompoundTag(), tag -> tag.putInt(AMMO_KEY, Math.max(ammo, 0)))));

        return stack;
    }

    /** Rounds in a stack, or -1 for "however many it holds": a fresh one is full. */
    public static int ammoOf(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);

        return data != null && data.copyTag().contains(AMMO_KEY) ? data.copyTag().getInt(AMMO_KEY) : -1;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        WeaponDefinition definition = this.getWeapon();
        int ammo = ammoOf(stack);

        lines.add(Component.translatable("tooltip.ashvehicles.ammo",
                        ammo < 0 ? definition.ammo() : ammo, definition.ammo())
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.ashvehicles.mount").withStyle(ChatFormatting.DARK_GRAY));
    }
}
