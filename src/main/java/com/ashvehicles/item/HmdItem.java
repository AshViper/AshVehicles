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
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * ヘルメット装着型表示器。頭に被っている間だけ、機体の計器のうち視界に重なる物——中央の姿勢・照準・ロック枠と、
 * 左右の B スコープと警戒受信機——が画面に出る。
 *
 * <p>機体ではなく乗る人が持つ物にしてある。どの機体に乗り換えても同じように効き、脱げば同じように消える。
 * 被っていない者はコクピットの MFD の頁（RDR / RWR / PFD）を読みに行くことになる。
 *
 * <p>守りは持たない（{@link ModItems#HMD_MATERIAL} は防御0）。{@link ArmorItem} なのは、バニラの防具レイヤーが
 * ArmorItem にしか頭の模型を描かせないから。模型は {@code geo/armor/hmd.geo.json} を GeckoLib が被せる
 * （{@code client.item.HmdRenderer}）。読むのは {@code client.AircraftHud} が {@link #isWorn} で問う。
 */
public class HmdItem extends ArmorItem implements GeoItem {
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public HmdItem(Properties properties) {
        super(ModItems.HMD_MATERIAL, Type.HELMET, properties);
    }

    /** この者が今 HMD を被っているか。 */
    public static boolean isWorn(LivingEntity wearer) {
        return wearer.getItemBySlot(EquipmentSlot.HEAD).getItem() instanceof HmdItem;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.ashvehicles.hmd").withStyle(ChatFormatting.DARK_GRAY));
    }
}
