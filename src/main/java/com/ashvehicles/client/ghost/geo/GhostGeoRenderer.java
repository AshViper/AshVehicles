package com.ashvehicles.client.ghost.geo;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.ghost.EntityGhost;
import com.ashvehicles.client.ghost.GhostRenderContext;
import com.ashvehicles.client.ghost.GhostSnapshot;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoObjectRenderer;
import software.bernie.geckolib.util.Color;

/**
 * Draws a ghost's GeckoLib model from its snapshot — the geometry and texture the snapshot names,
 * at the snapshot's scale, posed by whatever the adapter's poser does and otherwise left as
 * authored.
 *
 * <p>This is the static model the simplified tiers are drawn from, and the simply-posed one the
 * ghost tier is drawn from. It is not the entity's renderer and needs no entity: everything it
 * draws from was copied out when the snapshot was taken, so it draws just as well after the
 * entity has gone.
 *
 * <p>One renderer and one model serve every ghost; the animatable is the shared
 * {@link GhostAnimatable}, set before each draw.
 */
public final class GhostGeoRenderer extends GeoObjectRenderer<GhostAnimatable> {
    /** How solid a ghost is. Enough to read against the sky, far too little to mistake for near. */
    public static final float GHOST_ALPHA = 0.55F;

    private static final GhostGeoRenderer INSTANCE = new GhostGeoRenderer();

    private GhostGeoRenderer() {
        super(new Model());
    }

    /**
     * Draws the ghost. The pose stack should already be at the ghost's origin and turned to the
     * ghost's orientation; this applies only the model's own scale.
     *
     * @param poser how to pose the bones, or {@code null} for the authored pose
     */
    public static void draw(EntityGhost ghost, GhostSnapshot snapshot, GhostRenderContext context,
            @Nullable GhostAnimatable.GhostPoser poser) {
        if (snapshot.model() == null || snapshot.texture() == null) {
            return;
        }

        GhostAnimatable animatable = GhostAnimatable.of(ghost, snapshot, poser);
        RenderType type = renderType(snapshot.texture(), context.ghostStyle());
        MultiBufferSource buffers = context.buffers();

        INSTANCE.render(context.poseStack(), animatable, buffers, type, buffers.getBuffer(type),
                context.packedLight(), context.partialTick());
    }

    /**
     * A ghost is drawn translucent and emissive when there is nothing behind it: emissive because
     * there is no light out there to light it by, translucent so that it reads as a contact rather
     * than as something near. Over drawn terrain it is drawn as what it is, still emissive.
     */
    public static RenderType renderType(ResourceLocation texture, boolean ghostStyle) {
        return ghostStyle
                ? RenderType.entityTranslucentEmissive(texture)
                : RenderType.entityCutoutNoCull(texture);
    }

    @Override
    public Color getRenderColor(GhostAnimatable animatable, float partialTick, int packedLight) {
        return GhostRenderContext.isTranslucent()
                ? Color.ofRGBA(255, 255, 255, (int) (GHOST_ALPHA * 255.0F))
                : Color.WHITE;
    }

    /**
     * The model's own scale, and GeckoLib's block-shaped assumption taken back out: the object
     * renderer shifts what it draws by half a block, because the objects it was written for sit in
     * a block whose origin is a corner. A ghost stands at a point.
     */
    @Override
    public void preRender(PoseStack poseStack, GhostAnimatable animatable, BakedGeoModel model,
            MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender, float partialTick,
            int packedLight, int packedOverlay, int colour) {
        float scale = animatable.snapshot().scale();
        this.scaleWidth = scale;
        this.scaleHeight = scale;

        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick,
                packedLight, packedOverlay, colour);
        poseStack.translate(-0.5F, -0.51F, -0.5F);
    }

    /** Geometry and texture from the snapshot; pose from the adapter's poser. */
    private static final class Model extends GeoModel<GhostAnimatable> {
        /** Only consulted if a controller ever plays a named animation, and a ghost has no controllers. */
        private static final ResourceLocation NO_ANIMATION =
                ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "animations/ghost/none.animation.json");

        @Override
        public ResourceLocation getModelResource(GhostAnimatable animatable) {
            return animatable.snapshot().model();
        }

        @Override
        public ResourceLocation getTextureResource(GhostAnimatable animatable) {
            return animatable.snapshot().texture();
        }

        @Override
        public ResourceLocation getAnimationResource(GhostAnimatable animatable) {
            return NO_ANIMATION;
        }

        /** A bone a poser names and the geometry lacks is skipped, not a crash. */
        @Override
        public boolean crashIfBoneMissing() {
            return false;
        }

        @Override
        public void setCustomAnimations(GhostAnimatable animatable, long instanceId,
                AnimationState<GhostAnimatable> animationState) {
            super.setCustomAnimations(animatable, instanceId, animationState);

            GhostAnimatable.GhostPoser poser = animatable.poser();

            if (poser != null) {
                poser.pose(this, animatable.snapshot());
            }
        }
    }
}
