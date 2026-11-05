package com.ashvehicles.item;

import java.util.List;

import com.ashvehicles.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 耐Gスーツ。胴の枠のベスト（加圧呼吸の胴衣）と脚の枠のズボン（下肢と腹の袋）の2点で、着た分だけパイロットが
 * 耐えられる正の荷重が上がる。読むのは {@code client.PilotLoad} が {@link #tolerance} で問う。
 *
 * <p><b>効くのは正のGだけ。</b>袋が締めるのは脚と腹で、血を頭へ押し戻す側の荷重——押し込み——には何もしない。
 * 負の限界（{@code PilotLoad.NEGATIVE_TOLERANCE}）は着ても変わらない。
 *
 * <p>機体ではなく乗る人が持つ物にしてある。理由は {@link HmdItem} と同じで、どの機体に乗り換えても同じように効く。
 * 守りは持たない（{@link ModItems#G_SUIT_MATERIAL} は防御0）。服であって装甲ではない。模型はバニラの防具レイヤー
 * （{@code textures/models/armor/g_suit_layer_1/2.png}）で描く。
 */
public class GSuitItem extends ArmorItem {
    /** 何も着ていない人間が何時間でも耐えられる荷重。F-15 / F-16 の機体限界 9G より手前で視界が曇り始める。 */
    public static final float BARE = 7.0F;

    /**
     * ズボンの分。実機の耐Gスーツの本体はこちらで、脚と腹を締めて血が下へ溜まるのを止める。
     * これだけで 11G。
     */
    public static final float TROUSERS = 4.0F;

    /** ベストの分。胸を外から押して高いGでも息を吸わせる。ズボンの補助で、単独では 9G。 */
    public static final float VEST = 2.0F;

    public GSuitItem(Type type, Properties properties) {
        super(ModItems.G_SUIT_MATERIAL, type, properties);
    }

    /**
     * この者が何時間でも耐えられる正の荷重。素で {@link #BARE}、上下とも着て 13G。
     *
     * <p>枠に入っているかで見る。手に持っているだけ、別の枠に入れただけでは効かない（ズボンは脚の枠にしか入らない
     * ので実際には起きないが）。
     */
    public static float tolerance(LivingEntity wearer) {
        float tolerance = BARE;

        if (worn(wearer, EquipmentSlot.LEGS)) {
            tolerance += TROUSERS;
        }

        if (worn(wearer, EquipmentSlot.CHEST)) {
            tolerance += VEST;
        }

        return tolerance;
    }

    private static boolean worn(LivingEntity wearer, EquipmentSlot slot) {
        return wearer.getItemBySlot(slot).getItem() instanceof GSuitItem suit && suit.getEquipmentSlot() == slot;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        String key = getType() == Type.LEGGINGS ? "tooltip.ashvehicles.g_suit_trousers"
                : "tooltip.ashvehicles.g_suit_vest";

        lines.add(Component.translatable(key).withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.translatable("tooltip.ashvehicles.g_suit").withStyle(ChatFormatting.DARK_GRAY));
    }
}
