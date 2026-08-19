package com.ashvehicles.client.renderer;

import com.ashvehicles.client.model.WeaponModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animatable.instance.SingletonAnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.renderer.GeoObjectRenderer;

/**
 * A weapon hanging on a pylon, as something GeckoLib can draw.
 *
 * <p>A store under a wing is not an entity and not a block: it is part of the aircraft's own
 * rendering. GeckoLib draws that sort of thing through {@link GeoObjectRenderer}, which needs
 * something animatable to point at, so this is that — a holder carrying nothing but which weapon is
 * being drawn.
 *
 * <p>One instance is reused for every pylon on every aircraft, its weapon set immediately before
 * each draw. That is safe because rendering happens on one thread and each draw is finished before
 * the next begins, and it avoids making a fresh object several times a frame for every armed
 * aircraft in sight.
 */
public final class MountedStore implements GeoAnimatable {
    /** The one shared holder, and the renderer that draws it. Both are client-render-thread only. */
    private static final MountedStore INSTANCE = new MountedStore();
    private static final GeoObjectRenderer<MountedStore> RENDERER = new StoreRenderer();

    private final AnimatableInstanceCache cache = new SingletonAnimatableInstanceCache(this);
    private ResourceLocation weapon = ResourceLocation.withDefaultNamespace("air");

    private MountedStore() {
    }

    /** The shared holder, set to draw the given weapon. */
    public static MountedStore of(ResourceLocation weapon) {
        INSTANCE.weapon = weapon;

        return INSTANCE;
    }

    public static GeoObjectRenderer<MountedStore> renderer() {
        return RENDERER;
    }

    public ResourceLocation weapon() {
        return this.weapon;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    /**
     * Stores do not animate, so there is no clock to report. GeckoLib only asks for this to drive
     * animations, and a store has none.
     */
    @Override
    public double getTick(Object entity) {
        return 0.0;
    }

    /** Draws whichever weapon the holder was last set to. */
    private static class Model extends WeaponModel<MountedStore> {
        @Override
        protected ResourceLocation weaponId(MountedStore animatable) {
            return animatable.weapon();
        }
    }

    /**
     * The object renderer, with its block-shaped assumption taken back out.
     *
     * <p>GeckoLib's own {@link GeoObjectRenderer} shifts what it draws by half a block on each axis,
     * because the objects it was written for sit in a block space whose origin is a corner. A store
     * hangs at a point on a wing, given in the aircraft's own axes, so that shift would leave every
     * pylon's load floating half a block up and to one side of the pylon.
     */
    private static class StoreRenderer extends GeoObjectRenderer<MountedStore> {
        StoreRenderer() {
            super(new Model());
        }

        @Override
        public void preRender(PoseStack poseStack, MountedStore animatable, BakedGeoModel model,
                MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                float partialTick, int packedLight, int packedOverlay, int colour) {
            super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick,
                    packedLight, packedOverlay, colour);
            poseStack.translate(-0.5F, -0.51F, -0.5F);
        }
    }
}
