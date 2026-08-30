package com.ashvehicles.client.renderer;

import com.ashvehicles.entity.CountermeasureEntity;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;

/**
 * 意図的に何も描かない。
 *
 * <p>フレアは炎、チャフの雲は箔の粉塵であり、どちらも形を持つオブジェクトではないし、どちらも既に描かれている
 * ——エンティティ自身が、落下した位置から毎tick自前のパーティクルを撒いている。{@link CountermeasureEntity}
 * 参照。
 *
 * <p>これが存在するのは、ゲームが描画対象の全エンティティタイプにレンダラーを要求し、不要と見なす代わりに
 * クラッシュするからだ。
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
