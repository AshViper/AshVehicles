package com.ashvehicles.item;

import java.util.List;
import java.util.Locale;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.registry.ModItems;
import com.ashvehicles.weapon.EquipmentDefinition;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * ポッド。持ち運んで機体の専用ステーションに取り付ける。
 *
 * <p>スタックには何も持たせない。ポッドは撃たれず減りもしないので、外した物は付けた物とそのまま同じで、
 * 覚えておくことが無い。
 */
public class EquipmentItem extends Item {
    private final ResourceLocation equipment;

    public EquipmentItem(ResourceLocation equipment, Properties properties) {
        super(properties);
        this.equipment = equipment;
    }

    /** このアイテムがどの装備ファイルか。 */
    public ResourceLocation getEquipmentId() {
        return this.equipment;
    }

    public EquipmentDefinition getEquipment() {
        return Definitions.equipment(this.equipment);
    }

    /** そのポッド1個分のスタック。アイテムが登録されていなければ空。 */
    public static ItemStack stackOf(ResourceLocation equipment) {
        DeferredItem<EquipmentItem> item = ModItems.equipment().get(equipment);

        return item == null ? ItemStack.EMPTY : new ItemStack(item.get());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        EquipmentDefinition definition = this.getEquipment();

        lines.add(Component.translatable("tooltip.ashvehicles.equipment_kind."
                + definition.kind().getSerializedName()).withStyle(ChatFormatting.GRAY));

        // このポッドが実際に動かす数値だけ。ジャマーはシーカーについて言うことが無く、無い物まで書けば
        // 全ポッドに空行が4行付く。
        effect(lines, "seeker_range", definition.seekerRange(), true);
        effect(lines, "lock_rate", definition.lockRate(), true);
        effect(lines, "radar_gain", definition.radarGain(), false);
        effect(lines, "heat_gain", definition.heatGain(), false);
        // 唯一、相手側の数値。長い方がこのポッドを吊った者にとって良いので more は true。
        effect(lines, "lock_delay", definition.lockDelay(), true);

        lines.add(Component.translatable("tooltip.ashvehicles.equipment").withStyle(ChatFormatting.DARK_GRAY));
    }

    /**
     * 倍率1つをパーセント表示で。そのポッドが触らない項目なら何も出さない。
     *
     * @param more 値が大きい方が良いか。シーカーでは大きい方が良く、被探知性では悪い。照準ポッドと
     *             ジャマーの違いはまさにそこ
     */
    private static void effect(List<Component> lines, String key, float gain, boolean more) {
        if (Math.abs(gain - 1.0F) < 1.0E-3F) {
            return;
        }

        lines.add(Component.translatable("tooltip.ashvehicles." + key,
                        String.format(Locale.ROOT, "%.0f%%", gain * 100.0F))
                .withStyle(gain > 1.0F == more ? ChatFormatting.GREEN : ChatFormatting.RED));
    }
}
