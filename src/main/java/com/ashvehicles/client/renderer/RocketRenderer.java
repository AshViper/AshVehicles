package com.ashvehicles.client.renderer;

import com.ashvehicles.client.ghost.GhostRenderDispatcher;
import com.ashvehicles.client.model.WeaponModel;
import com.ashvehicles.entity.RocketEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Draws a rocket or a missile from its weapon's geometry file, lying along the way it is going.
 *
 * <p>Unlike a cannon round, one of these is a thing rather than a streak: slow enough to look at,
 * large enough to see, and which way it is pointing is worth knowing, since that is the whole story
 * of whether a missile is going to make its turn.
 *
 * <p>Which is exactly why it has to keep being drawn once it is past the edge of the loaded world:
 * a missile is aimed at something a long way off, and the interesting part of its flight happens out
 * there. This renderer covers the near work only — out to {@code ghostStartDistance}, where it
 * stands down and {@code RocketGhostAdapter} draws the missile from a snapshot instead, in the
 * ghost pass. The two draw the same model from the same files, turned the same way, so nothing
 * about a missile changes as it crosses the hand-over.
 */
public class RocketRenderer extends GeoEntityRenderer<RocketEntity> {
    public RocketRenderer(EntityRendererProvider.Context context) {
        super(context, new Model());
    }

    /**
     * Stands down beyond the ghost start distance, where the ghost pass takes over. The test is the
     * same one the pass makes, from the same camera, so a missile is always one or the other's and
     * never both.
     */
    @Override
    public boolean shouldRender(RocketEntity rocket, Frustum frustum, double camX, double camY, double camZ) {
        if (GhostRenderDispatcher.claims(rocket, camX, camY, camZ)) {
            return false;
        }

        return super.shouldRender(rocket, frustum, camX, camY, camZ);
    }

    /**
     * Turns the model to lie along the flight path. The base implementation is deliberately not
     * called: it would apply a heading of its own, taken from the body rotation of a living entity,
     * which a missile is not.
     *
     * <p>The half turn afterwards is the model's own: geometry is authored facing north, which is
     * -Z, and the heading worked out here points +Z along the flight path.
     */
    @Override
    protected void applyRotations(RocketEntity animatable, PoseStack poseStack, float ageInTicks,
            float rotationYaw, float partialTick, float nativeScale) {
        Vec3 travel = animatable.travel(partialTick);

        if (travel.lengthSqr() > 1.0E-8) {
            double flat = Math.sqrt(travel.x * travel.x + travel.z * travel.z);
            poseStack.mulPose(Axis.YP.rotationDegrees((float) Math.toDegrees(Math.atan2(travel.x, travel.z))));
            poseStack.mulPose(Axis.XP.rotationDegrees((float) -Math.toDegrees(Math.atan2(travel.y, flat))));
        }

        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
    }

    /** A name tag floating over a missile would be a strange thing to draw. */
    @Override
    public boolean shouldShowName(RocketEntity animatable) {
        return false;
    }

    /** The weapon model, told which weapon to draw by the missile itself. */
    private static class Model extends WeaponModel<RocketEntity> {
        @Override
        protected ResourceLocation weaponId(RocketEntity animatable) {
            return animatable.getWeaponId();
        }
    }
}
