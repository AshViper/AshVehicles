package com.ashvehicles.client.renderer;

import com.ashvehicles.client.ghost.GhostRenderDispatcher;
import com.ashvehicles.entity.VehicleEntityBase;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.util.Color;

/**
 * Draws any of the mod's machines, taking the way it is lying and the size it is drawn at from the
 * machine itself.
 *
 * <p>Both of those are the same job whether the thing flies or drives, and both are things Minecraft
 * would otherwise get wrong. It would turn the model by a heading read off the body rotation of a
 * living entity, which neither of these is; and it would draw the model at whatever size it was
 * built, which is rarely Minecraft's.
 */
public abstract class VehicleRenderer<T extends VehicleEntityBase & GeoEntity> extends GeoEntityRenderer<T> {
    /**
     * How much of its colour a machine that has been written off keeps.
     *
     * <p>Low enough to read as burnt through from the other side of an airfield, and high enough
     * that the markings, the panel lines and the shape of the thing are all still there. A wreck
     * should be recognisably the machine it was: that is the whole reason for leaving it standing
     * instead of removing it.
     *
     * <p>Public because the ghost pass draws the same machines from a snapshot rather than through
     * this renderer, and a wreck that came back to life at the hand-over distance would be worse
     * than one that was never charred at all.
     */
    public static final float CHARRED = 0.28F;

    protected VehicleRenderer(EntityRendererProvider.Context context, GeoModel<T> model) {
        super(context, model);
    }

    /**
     * Draws a wreck scorched, and is the whole of what a wreck looks like.
     *
     * <p>Deliberately the whole of it. The same geometry, the same texture and the same attitude the
     * machine came down in, put through a burn — rather than a second set of broken geometry for
     * every aircraft and every tank in the mod, which is a model each and would have to be drawn by
     * somebody for machines that are otherwise finished.
     */
    @Override
    public Color getRenderColor(T animatable, float partialTick, int packedLight) {
        Color colour = super.getRenderColor(animatable, partialTick, packedLight);

        if (!animatable.isWrecked()) {
            return colour;
        }

        // The alpha is left alone: what is see-through about a machine is a question about how far
        // away it is, and a ghost of a wreck is still a ghost. See AircraftRenderer.
        return Color.ofRGBA(
                (int) (colour.getRed() * CHARRED),
                (int) (colour.getGreen() * CHARRED),
                (int) (colour.getBlue() * CHARRED),
                colour.getAlpha());
    }

    /**
     * Stands down beyond the ghost start distance, where the ghost pass takes over.
     *
     * <p>The test is the one the pass itself makes, from the same camera, so a machine is always one
     * or the other's and never both. It lives here rather than in each renderer because it is the
     * same hand-over for anything the mod draws: a tank at two kilometres is out of the game's reach
     * for exactly the reasons an aeroplane is.
     */
    @Override
    public boolean shouldRender(T machine, Frustum frustum, double camX, double camY, double camZ) {
        if (GhostRenderDispatcher.claims(machine, camX, camY, camZ)) {
            return false;
        }

        return super.shouldRender(machine, frustum, camX, camY, camZ);
    }

    /** What the machine's file says to draw it at. Not known until there is one to draw. */
    protected abstract float scaleOf(T animatable);

    /**
     * Turns the model to match the machine's attitude, which is a rotation and so needs no angles
     * pulling out of it. The base implementation is deliberately not called: it would apply a
     * heading of its own, and it reads that heading off the body rotation of a living entity.
     *
     * <p>The half turn afterwards is the model's own: geometry is authored facing north, which is
     * the entity's −Z, and a machine's rotation is described from the front down +Z.
     */
    @Override
    protected void applyRotations(T animatable, PoseStack poseStack, float ageInTicks, float rotationYaw,
            float partialTick, float nativeScale) {
        poseStack.mulPose(animatable.getAttitude(partialTick));
        this.applyBodyMotion(animatable, poseStack, partialTick);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
    }

    /**
     * Anything the machine's body does relative to the hull the attitude describes, applied in the
     * hull's own frame — where +Z runs over the bow, +Y is up and +X is therefore out to the left.
     *
     * <p>Nothing, for a machine whose body is bolted to it. An airframe is one; a hull sitting on
     * torsion bars is not, and a ground vehicle puts its suspension here. It is between the attitude
     * and the model's half turn so that it is measured against the machine rather than against the
     * world: a tank that dips its nose under the brakes does it relative to the hillside it is on.
     */
    protected void applyBodyMotion(T animatable, PoseStack poseStack, float partialTick) {
    }

    @Override
    public void preRender(PoseStack poseStack, T animatable, BakedGeoModel model, MultiBufferSource bufferSource,
            VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay,
            int colour) {
        float scale = this.scaleOf(animatable);
        this.scaleWidth = scale;
        this.scaleHeight = scale;
        this.shadowRadius = animatable.getBbWidth() * 0.5F;

        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick,
                packedLight, packedOverlay, colour);
    }

    /** A name tag floating over a moving machine is more distracting than useful. */
    @Override
    public boolean shouldShowName(T animatable) {
        return false;
    }
}
