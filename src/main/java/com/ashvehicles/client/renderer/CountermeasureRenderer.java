package com.ashvehicles.client.renderer;

import com.ashvehicles.entity.CountermeasureEntity;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;

/**
 * Draws nothing, on purpose.
 *
 * <p>A flare is fire and a cloud of chaff is foil dust; neither is an object with a shape, and both
 * are already drawn — by the entity itself, which throws its own particles every tick from wherever
 * it has fallen to. See {@link CountermeasureEntity}.
 *
 * <p>This exists because the game insists on a renderer for every entity type it is asked to draw,
 * and will crash rather than assume one is not wanted.
 */
public class CountermeasureRenderer extends EntityRenderer<CountermeasureEntity> {
    public CountermeasureRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(CountermeasureEntity decoy, float yaw, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource, int packedLight) {
    }

    @Override
    public ResourceLocation getTextureLocation(CountermeasureEntity decoy) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
