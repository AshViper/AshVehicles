package com.ashvehicles.item;

import java.util.List;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.registry.ModItems;
import com.ashvehicles.weapon.RackDefinition;
import com.ashvehicles.weapon.WeaponDefinition;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * ラック。持ち運んで機体のパイロンに取り付ける。
 *
 * <p>ただの金具で、何も保持しない。翼下で戦争を1つ過ごしたラックも箱から出したてのラックも同じ物なので、
 * 兵装と違ってスタックに覚えることが無く、複数まとめられる。何を載せているかは機体側の情報で、ラックを
 * 外しても機体に残る。
 */
public class RackItem extends Item {
    private final ResourceLocation rack;

    public RackItem(ResourceLocation rack, Properties properties) {
        super(properties);
        this.rack = rack;
    }

    /** このアイテムがどのラックファイルか。 */
    public ResourceLocation getRackId() {
        return this.rack;
    }

    public RackDefinition getRack() {
        return Definitions.rack(this.rack);
    }

    /** そのラック1個分のスタック。アイテムが登録されていなければ空。 */
    public static ItemStack stackOf(ResourceLocation rack) {
        DeferredItem<RackItem> item = ModItems.racks().get(rack);

        return item == null ? ItemStack.EMPTY : new ItemStack(item.get());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        RackDefinition definition = this.getRack();

        lines.add(Component.translatable("tooltip.ashvehicles.rack_capacity", definition.capacity())
                .withStyle(ChatFormatting.GRAY));

        if (!definition.accepts().isEmpty()) {
            String kinds = definition.accepts().stream()
                    .map(WeaponDefinition.Type::getSerializedName)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");

            lines.add(Component.translatable("tooltip.ashvehicles.rack_accepts", kinds)
                    .withStyle(ChatFormatting.GRAY));
        }

        // 翼端レールは付く場所が違う。それを言わなければ、翼下のパイロンで灰色の枠しか出ない理由が
        // どこにも書かれていないことになる。
        if (definition.wingtip()) {
            lines.add(Component.translatable("tooltip.ashvehicles.rack_wingtip")
                    .withStyle(ChatFormatting.GRAY));
        }

        if (definition.mass() > 0.0F) {
            lines.add(Component.translatable("tooltip.ashvehicles.mass", Math.round(definition.mass()))
                    .withStyle(ChatFormatting.GRAY));
        }

        lines.add(Component.translatable("tooltip.ashvehicles.rack").withStyle(ChatFormatting.DARK_GRAY));
    }
}
