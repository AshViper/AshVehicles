package com.ashvehicles.item;

import java.util.List;

import com.ashvehicles.data.Definitions;
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
 * 兵装。持ち運んで機体のパイロンに吊るす。
 *
 * <p>アイテムが残弾数を覚えているので、半分減った状態で外したポッドは半分減ったまま戻る。クリエイティブ
 * タブから出したての物は満載。
 */
public class WeaponItem extends Item {
    /** 残弾をスタックのどこに書くか。キーが無ければ満載の意味。 */
    private static final String AMMO_KEY = "Ammo";

    private final ResourceLocation weapon;

    public WeaponItem(ResourceLocation weapon, Properties properties) {
        super(properties);
        this.weapon = weapon;
    }

    /** このアイテムがどの兵装ファイルか。 */
    public ResourceLocation getWeaponId() {
        return this.weapon;
    }

    public WeaponDefinition getWeapon() {
        return Definitions.weapon(this.weapon);
    }

    /** その兵装1個分のスタック。指定した残弾を持たせる。 */
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

    /** スタックの残弾。-1 は「定数いっぱい」の意味で、出したての物は満載。 */
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

        // 対で使う兵装向けに、これを撃つ前に機体へ積んでおく必要がある物。
        definition.requires().ifPresent(kind -> lines.add(
                Component.translatable("tooltip.ashvehicles.requires",
                                Component.translatable("tooltip.ashvehicles.equipment_kind."
                                        + kind.getSerializedName()))
                        .withStyle(ChatFormatting.GOLD)));

        lines.add(Component.translatable("tooltip.ashvehicles.mount").withStyle(ChatFormatting.DARK_GRAY));
    }
}
