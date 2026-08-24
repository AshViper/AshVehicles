package com.ashvehicles.client.renderer;

import javax.annotation.Nullable;

import com.ashvehicles.client.model.GroundVehicleModel;
import com.ashvehicles.client.model.TrackBelt;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;

/** Draws any ground vehicle. Everything about how is {@link VehicleRenderer}'s. */
public class GroundVehicleRenderer extends VehicleRenderer<GroundVehicleEntity> {
    /**
     * The model being drawn, kept from the start of the draw because the run of track needs to look
     * the road wheels up in it and the bone loop is not handed it.
     */
    @Nullable
    private BakedGeoModel drawing;

    public GroundVehicleRenderer(EntityRendererProvider.Context context) {
        super(context, new GroundVehicleModel());
    }

    @Override
    protected float scaleOf(GroundVehicleEntity animatable) {
        return animatable.getStats().model().scale();
    }

    @Override
    public void preRender(PoseStack poseStack, GroundVehicleEntity animatable, BakedGeoModel model,
            MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender, float partialTick,
            int packedLight, int packedOverlay, int colour) {
        this.drawing = model;

        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick,
                packedLight, packedOverlay, colour);
    }

    /**
     * Draws the whole run of track wherever the model has the one link it is built out of, and
     * everything else as it comes.
     *
     * <p>Caught here rather than after the model is drawn because here is where the pose stack is
     * already in the link's own parent's frame — which is the frame the run is worked out in — and
     * because a link put somewhere and drawn is exactly what GeckoLib does with any bone. So each
     * link is drawn through the same path with the same lighting, the same colour and the same
     * layers as the rest of the vehicle: a wreck's track is charred with it, and nothing has to know
     * that these particular cubes were drawn a hundred times.
     */
    @Override
    public void renderRecursively(PoseStack poseStack, GroundVehicleEntity animatable, GeoBone bone,
            @Nullable RenderType renderType, MultiBufferSource bufferSource, @Nullable VertexConsumer buffer,
            boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
        BakedGeoModel model = this.drawing;

        if (model != null && TrackBelt.isLink(animatable.getStats().model(), bone)
                && TrackBelt.draw(model, animatable.getStats().model(), bone,
                        animatable.getWheelAngle(partialTick),
                        link -> super.renderRecursively(poseStack, animatable, link, renderType, bufferSource,
                                buffer, isReRender, partialTick, packedLight, packedOverlay, colour))) {
            return;
        }

        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender,
                partialTick, packedLight, packedOverlay, colour);
    }
}
