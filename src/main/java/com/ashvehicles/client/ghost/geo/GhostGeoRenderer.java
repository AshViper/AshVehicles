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
import net.minecraft.util.Mth;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoObjectRenderer;
import software.bernie.geckolib.util.Color;

/**
 * Draws a ghost's GeckoLib model from its snapshot — the geometry and texture the snapshot names,
 * at the snapshot's scale, played and posed as the entity it stands for is.
 *
 * <p>It is not the entity's renderer and needs no entity: everything it draws from was copied out
 * when the snapshot was taken, so it draws just as well after the entity has gone. What it does
 * with that is the same in both halves as the entity's own renderer: whatever the adapter has
 * registered controllers for is played from the animation file the snapshot names, and the
 * adapter's poser then sets the bones that follow the flight from moment to moment.
 *
 * <p>One renderer and one model serve every ghost, as one serves every entity of a kind; the
 * animatable is the shared {@link GhostAnimatable}, set before each draw, and each ghost's
 * animation state is kept apart by {@link #getInstanceId}.
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
        startClock(animatable);
        RenderType type = renderType(snapshot.texture(), context.ghostStyle());
        MultiBufferSource buffers = context.buffers();

        INSTANCE.render(context.poseStack(), animatable, buffers, type, buffers.getBuffer(type),
                context.packedLight(), context.partialTick());
    }

    /**
     * Starts every ghost's animation clock at the same moment, before GeckoLib starts it at the
     * one the ghost happened to first be drawn at.
     *
     * <p>GeckoLib times anything that is not an entity from its own first frame, and then advances
     * one clock — the model's — by the difference between one draw and the next. That is fine when
     * a model draws one thing. Ours draws every ghost, and ghosts first appear at different
     * moments, so left alone the clock would leap back and forth by the difference between their
     * starting points and every animation with it. Pinning the start of each to zero makes the
     * figure the same absolute clock for all of them, which is what the game does for entities and
     * why one model can draw a hundred of those.
     */
    private static void startClock(GhostAnimatable animatable) {
        AnimatableManager<?> manager = animatable.getAnimatableInstanceCache()
                .getManagerForId(INSTANCE.getInstanceId(animatable));

        if (manager.getFirstTickTime() == -1) {
            manager.startedAt(0.0);
        }
    }

    /**
     * One ghost, one animation state: GeckoLib files controllers under this, and two aeroplanes
     * putting their gear down at different times must not share a set. The ghost's UUID is its
     * identity for the same reason it is everywhere else — entity ids are reused.
     */
    @Override
    public long getInstanceId(GhostAnimatable animatable) {
        return animatable.ghost().uuid().getLeastSignificantBits();
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
        // How see-through it is is the distance's business; how dark it is is the entity's. A wreck
        // is charred at any range, and a ghost drawn in the aeroplane's colours would have one come
        // back to life the moment the game's own renderer handed it over.
        int alpha = GhostRenderContext.isTranslucent() ? (int) (GHOST_ALPHA * 255.0F) : 255;
        int level = (int) (255.0F * Mth.clamp(animatable.snapshot().shade(), 0.0F, 1.0F));

        return Color.ofRGBA(level, level, level, alpha);
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

    /** Geometry, texture and animations from the snapshot; pose from the adapter's poser. */
    private static final class Model extends GeoModel<GhostAnimatable> {
        /**
         * Stands in for the animation file of a ghost that has none. Nothing asks for an animation
         * out of it: a ghost with nothing to play has no controllers to ask.
         */
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
            ResourceLocation animation = animatable.snapshot().animation();

            return animation == null ? NO_ANIMATION : animation;
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
                poser.pose(this, animatable.ghost(), animationState.getPartialTick());
            }
        }
    }
}
