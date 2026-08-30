package com.ashvehicles.client.renderer;

import com.ashvehicles.entity.DesignationEntity;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

/**
 * 指示地点を「何も描かない」形で描く。
 *
 * <p>パイロットに必要なマークは地上と計器面に出る——{@code AircraftHud} 参照——ので、同じ場所に空中で浮かぶ
 * オブジェクトは、世界中の誰にでも見える2つ目の、しかも劣ったマークになってしまう。とはいえ全エンティティ
 * タイプにレンダラーが要るので、これがそれだ。
 */
public class DesignationRenderer extends EntityRenderer<DesignationEntity> {
    public DesignationRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(DesignationEntity entity, float yaw, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource, int packedLight) {
    }

    @Override
    public boolean shouldRender(DesignationEntity entity, net.minecraft.client.renderer.culling.Frustum frustum,
            double x, double y, double z) {
        return false;
    }

    @Override
    public ResourceLocation getTextureLocation(DesignationEntity entity) {
        return ResourceLocation.withDefaultNamespace("textures/misc/white.png");
    }
}
