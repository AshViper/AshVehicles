package com.ashvehicles.client.item;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.item.HmdItem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoArmorRenderer;

/**
 * 被った HMD の模型。{@code geo/armor/hmd.geo.json} の {@code armorHead} が頭に付く。
 * 手に持った時の絵は普通のアイテム用テクスチャのまま。
 */
public final class HmdRenderer extends GeoArmorRenderer<HmdItem> {
    private static final ResourceLocation GEO = id("geo/armor/hmd.geo.json");
    private static final ResourceLocation TEXTURE = id("textures/armor/hmd.png");

    private HmdRenderer() {
        super(new GeoModel<>() {
            @Override
            public ResourceLocation getModelResource(HmdItem animatable) {
                return GEO;
            }

            @Override
            public ResourceLocation getTextureResource(HmdItem animatable) {
                return TEXTURE;
            }

            @Override
            public ResourceLocation getAnimationResource(HmdItem animatable) {
                return null;
            }
        });
    }

    /** {@code RegisterClientExtensionsEvent} へ渡す口。描き手は1つを使い回す。 */
    public static IClientItemExtensions extensions() {
        return new IClientItemExtensions() {
            private HmdRenderer renderer;

            @Override
            public HumanoidModel<?> getHumanoidArmorModel(LivingEntity wearer, ItemStack stack,
                    EquipmentSlot slot, HumanoidModel<?> original) {
                if (renderer == null) renderer = new HmdRenderer();
                // 廃止予定の4引数版が中で埋めていた値を自分で渡す。手足の振りは頭には効かないので0
                Minecraft mc = Minecraft.getInstance();
                renderer.prepForRender(wearer, stack, slot, original, mc.renderBuffers().bufferSource(),
                        mc.getTimer().getGameTimeDeltaPartialTick(true), 0, 0, 0, 0);
                return renderer;
            }
        };
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, path);
    }
}
