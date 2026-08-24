package com.ashvehicles.client.item;

import javax.annotation.Nullable;

import com.ashvehicles.item.VehicleItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Draws a machine's item as one square carrying {@link VehicleIcons the machine's picture}.
 *
 * <p>Which is all any flat item is. It is deliberately not the machine itself: see
 * {@link VehicleIcons} for why a model drawn every frame in every slot is not what an item wants to
 * be, and why this ends up cheaper than the vanilla item it replaces rather than dearer.
 *
 * <p>The square is a square and not a slab with sides. A vanilla flat item is extruded so that it
 * has a thickness in the hand, which is worth having for a sword and is not worth a hundred quads
 * for a photograph of a tank. Both faces of it are drawn, so the item is still there when it is seen
 * from behind.
 */
public final class VehicleItemRenderer extends BlockEntityWithoutLevelRenderer {
    /** Where a flat item sits in the block the item transforms are worked out in: down the middle. */
    private static final float DEPTH = 0.5F;

    @Nullable
    private static VehicleItemRenderer instance;

    private VehicleItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),
                Minecraft.getInstance().getEntityModels());
    }

    /**
     * The one of these there is, built the first time an item asks for it rather than when the item
     * extensions are registered — which happens while the game is still being put together, before
     * there is a block entity renderer or a model set to hand it.
     */
    public static VehicleItemRenderer instance() {
        VehicleItemRenderer built = instance;

        if (built == null) {
            built = new VehicleItemRenderer();
            instance = built;
        }

        return built;
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
            MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (!(stack.getItem() instanceof VehicleItem<?> item)) {
            return;
        }

        ResourceLocation icon = VehicleIcons.of(item.vehicle());

        // Not taken yet, or the machine has no model to take one from. Either way there is nothing
        // to draw and the next frame or two will settle it.
        if (icon == null) {
            return;
        }

        // No cull, so that the far side of the square is the near side seen through: an item in the
        // hand is walked round, and one that vanished from behind would look like a hole.
        VertexConsumer buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(icon));
        PoseStack.Pose pose = poseStack.last();

        corner(buffer, pose, 0.0F, 0.0F, 0.0F, 1.0F, packedLight, packedOverlay);
        corner(buffer, pose, 1.0F, 0.0F, 1.0F, 1.0F, packedLight, packedOverlay);
        corner(buffer, pose, 1.0F, 1.0F, 1.0F, 0.0F, packedLight, packedOverlay);
        corner(buffer, pose, 0.0F, 1.0F, 0.0F, 0.0F, packedLight, packedOverlay);
    }

    private static void corner(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float u, float v,
            int packedLight, int packedOverlay) {
        buffer.addVertex(pose, x, y, DEPTH)
                .setColor(0xFFFFFFFF)
                .setUv(u, v)
                .setOverlay(packedOverlay)
                .setLight(packedLight)
                .setNormal(pose, 0.0F, 0.0F, 1.0F);
    }
}
