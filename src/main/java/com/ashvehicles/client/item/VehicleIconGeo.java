package com.ashvehicles.client.item;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.ashvehicles.client.model.TrackBelt;
import com.ashvehicles.client.model.VehicleGeoModel;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.vehicle.VehicleChassis;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animatable.instance.SingletonAnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoObjectRenderer;
import software.bernie.geckolib.util.RenderUtil;

/**
 * Draws one of the mod's machines from nothing but its name, as it was built.
 *
 * <p>This is what stands behind the picture an item is drawn as: no entity, no attitude, no
 * animation and no clock — the geometry and the texture the machine's id names, in the pose the
 * geometry file left them in. It is used exactly twice per machine, once to measure it and once to
 * take its picture, and never again while the client is running. See {@link VehicleIcons}.
 *
 * <p>The one thing it does beyond drawing the bones as they come is lay a run of track, because a
 * tank whose model carries a single link bone is a tank with no tracks until somebody walks it round
 * the wheels — and a picture of one like that would be a picture of a hull on bare road wheels. It
 * is the same call the vehicle's own renderer makes, with the wheels stopped.
 */
public final class VehicleIconGeo extends GeoObjectRenderer<VehicleIconGeo.Machine> {
    private static final VehicleIconGeo INSTANCE = new VehicleIconGeo();

    /**
     * The model being drawn, kept from the start of the draw because the run of track needs to look
     * the road wheels up in it and the bone loop is not handed it. As {@code GroundVehicleRenderer}.
     */
    @Nullable
    private BakedGeoModel drawing;

    private VehicleIconGeo() {
        super(new Model());
    }

    /**
     * Draws the machine into the given buffers, at the origin of the pose stack and in whatever
     * frame the caller has turned it to.
     *
     * <p>Full-bright, because a picture is not standing anywhere and there is no light where it is:
     * what shades it is the diffuse lighting the caller has set, not a lightmap.
     */
    public static void draw(PoseStack poseStack, ResourceLocation vehicle, MultiBufferSource buffers) {
        Machine machine = Machine.of(vehicle);
        RenderType type = RenderType.entityCutoutNoCull(VehicleGeoModel.textureFile(vehicle));

        INSTANCE.render(poseStack, machine, buffers, type, buffers.getBuffer(type),
                LightTexture.FULL_BRIGHT, 0.0F);
    }

    /**
     * How much room the machine takes up once it has been turned the way it will be drawn.
     *
     * <p>Every vertex, rather than the eight corners of an upright box turned afterwards: a machine
     * is a long thin thing seen from the corner, and the box round a turned box is half air. This is
     * what lets one framing suit a tank and an aeroplane four times its length without a figure per
     * machine anywhere.
     *
     * <p>The same walk GeckoLib makes when it draws — the same bone transforms in the same order,
     * out of {@link RenderUtil}, so that what is measured is what is drawn. Bones the model hides
     * are skipped for the same reason.
     */
    public static Bounds measure(ResourceLocation vehicle, Quaternionf view) {
        BakedGeoModel geometry = geometry(vehicle);
        PoseStack poseStack = new PoseStack();
        poseStack.mulPose(view);
        Bounds bounds = new Bounds();

        for (GeoBone bone : geometry.topLevelBones()) {
            measure(poseStack, bone, bounds);
        }

        return bounds;
    }

    /** The machine's baked geometry. Throws if the model has not been loaded, which is the caller's. */
    public static BakedGeoModel geometry(ResourceLocation vehicle) {
        return INSTANCE.getGeoModel().getBakedModel(VehicleGeoModel.geometryFile(vehicle));
    }

    private static void measure(PoseStack poseStack, GeoBone bone, Bounds bounds) {
        poseStack.pushPose();
        RenderUtil.prepMatrixForBone(poseStack, bone);

        if (!bone.isHidden()) {
            for (GeoCube cube : bone.getCubes()) {
                poseStack.pushPose();
                RenderUtil.translateToPivotPoint(poseStack, cube);
                RenderUtil.rotateMatrixAroundCube(poseStack, cube);
                RenderUtil.translateAwayFromPivotPoint(poseStack, cube);
                measure(poseStack.last().pose(), cube, bounds);
                poseStack.popPose();
            }
        }

        if (!bone.isHidingChildren()) {
            for (GeoBone child : bone.getChildBones()) {
                measure(poseStack, child, bounds);
            }
        }

        poseStack.popPose();
    }

    private static void measure(Matrix4f pose, GeoCube cube, Bounds bounds) {
        for (GeoQuad quad : cube.quads()) {
            // A face a cube has no depth for is left out of the baked model as a null.
            if (quad == null) {
                continue;
            }

            for (GeoVertex vertex : quad.vertices()) {
                bounds.add(pose.transformPosition(new Vector3f(vertex.position())));
            }
        }
    }

    @Override
    public long getInstanceId(Machine animatable) {
        return animatable.id().hashCode();
    }

    /**
     * The half block GeckoLib's object renderer shifts what it draws by, taken back out. It draws
     * things that sit in a block, whose origin is a corner; a machine stands at a point, and the
     * framing is worked out about that point.
     */
    @Override
    public void preRender(PoseStack poseStack, Machine animatable, BakedGeoModel model,
            MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender, float partialTick,
            int packedLight, int packedOverlay, int colour) {
        this.drawing = model;

        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick,
                packedLight, packedOverlay, colour);
        poseStack.translate(-0.5F, -0.51F, -0.5F);
    }

    /** Lays the whole run of track wherever the model has the one link it is built out of. */
    @Override
    public void renderRecursively(PoseStack poseStack, Machine animatable, GeoBone bone, RenderType renderType,
            MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender, float partialTick,
            int packedLight, int packedOverlay, int colour) {
        BakedGeoModel model = this.drawing;

        if (model != null && TrackBelt.isLink(animatable.chassis(), bone)
                && TrackBelt.draw(model, animatable.chassis(), bone, 0.0F,
                        link -> super.renderRecursively(poseStack, animatable, link, renderType, bufferSource,
                                buffer, isReRender, partialTick, packedLight, packedOverlay, colour))) {
            return;
        }

        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender,
                partialTick, packedLight, packedOverlay, colour);
    }

    /** How much room something takes up, built a vertex at a time. Empty until something is added. */
    public static final class Bounds {
        private float minX = Float.MAX_VALUE;
        private float minY = Float.MAX_VALUE;
        private float maxX = -Float.MAX_VALUE;
        private float maxY = -Float.MAX_VALUE;
        private float minZ = Float.MAX_VALUE;
        private float maxZ = -Float.MAX_VALUE;

        void add(Vector3f point) {
            this.minX = Math.min(this.minX, point.x());
            this.maxX = Math.max(this.maxX, point.x());
            this.minY = Math.min(this.minY, point.y());
            this.maxY = Math.max(this.maxY, point.y());
            this.minZ = Math.min(this.minZ, point.z());
            this.maxZ = Math.max(this.maxZ, point.z());
        }

        /** Whether anything was measured at all. A model of nothing cannot be framed. */
        public boolean isEmpty() {
            return this.maxX < this.minX;
        }

        public float middleX() {
            return (this.minX + this.maxX) * 0.5F;
        }

        public float middleY() {
            return (this.minY + this.maxY) * 0.5F;
        }

        /** The longer of the two sides across the picture, which is what the framing is cut to. */
        public float across() {
            return Math.max(this.maxX - this.minX, this.maxY - this.minY);
        }

        public float nearest() {
            return this.maxZ;
        }

        public float furthest() {
            return this.minZ;
        }
    }

    /**
     * A machine, as something GeckoLib can draw.
     *
     * <p>One holder pointed at each machine in turn immediately before its draw, as the ghost pass
     * does: nothing here has any state of its own, drawing is single-threaded, and a machine is
     * drawn once and then never again.
     */
    public static final class Machine implements GeoAnimatable {
        private static final Machine INSTANCE = new Machine();

        private final AnimatableInstanceCache cache = new SingletonAnimatableInstanceCache(this);

        private ResourceLocation id = null;
        private VehicleChassis.Model chassis = VehicleChassis.Model.DEFAULT;

        private Machine() {
        }

        static Machine of(ResourceLocation id) {
            INSTANCE.id = id;
            INSTANCE.chassis = chassisOf(id);

            return INSTANCE;
        }

        /**
         * What the machine's file says about its model, whichever kind of machine it is. Only the
         * track is read out of it here; the scale is not, because the framing settles that.
         */
        private static VehicleChassis.Model chassisOf(ResourceLocation id) {
            if (Definitions.VEHICLES.has(id)) {
                return Definitions.VEHICLES.get(id).model();
            }

            if (Definitions.AIRCRAFT.has(id)) {
                return Definitions.AIRCRAFT.get(id).model();
            }

            return VehicleChassis.Model.DEFAULT;
        }

        public ResourceLocation id() {
            return this.id;
        }

        VehicleChassis.Model chassis() {
            return this.chassis;
        }

        /** None. A picture is one moment, and the moment is the one the model was built in. */
        @Override
        public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        }

        @Override
        public AnimatableInstanceCache getAnimatableInstanceCache() {
            return this.cache;
        }

        @Override
        public double getTick(Object entity) {
            return 0.0;
        }
    }

    /** Geometry, texture and animations found under the machine's own name, as everywhere else. */
    private static final class Model extends GeoModel<Machine> {
        @Override
        public ResourceLocation getModelResource(Machine animatable) {
            return VehicleGeoModel.geometryFile(animatable.id());
        }

        @Override
        public ResourceLocation getTextureResource(Machine animatable) {
            return VehicleGeoModel.textureFile(animatable.id());
        }

        @Override
        public ResourceLocation getAnimationResource(Machine animatable) {
            return VehicleGeoModel.animationFile(animatable.id());
        }

        /** Nothing here poses a bone by name, but a machine with no animation file must not crash. */
        @Override
        public boolean crashIfBoneMissing() {
            return false;
        }
    }
}
