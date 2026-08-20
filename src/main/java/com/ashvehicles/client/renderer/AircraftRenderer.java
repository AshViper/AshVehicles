package com.ashvehicles.client.renderer;

import java.util.List;

import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.aircraft.AircraftManager;
import com.ashvehicles.client.ghost.GhostRenderContext;
import com.ashvehicles.client.ghost.GhostRenderDispatcher;
import com.ashvehicles.client.ghost.geo.GhostGeoRenderer;
import com.ashvehicles.client.model.AircraftModel;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.weapon.WeaponMounts;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.GeoObjectRenderer;
import software.bernie.geckolib.util.Color;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Draws any aircraft, taking its scale and its attitude from the aircraft itself.
 *
 * <p>This renderer draws the aircraft in full out to the ghost start distance. Beyond that it
 * stands down — {@link #shouldRender} says no — and the ghost pass ({@link GhostRenderDispatcher})
 * draws the aircraft instead, from a snapshot, through {@code AircraftGhostAdapter}. The one case
 * in which this renderer is used out of the ordinary is an aircraft the game's own entity loop
 * declined to draw (no built chunk section under it); the ghost pass then calls it directly, and
 * {@link GhostRenderContext} says so.
 *
 * <p>An aircraft with nothing behind it at all is drawn as a <em>ghost</em>: the same shape,
 * see-through, reading for what it is — a contact, seen at a distance, not quite of the world in
 * front of you. Over terrain the player can still see, which for anyone running Distant Horizons is
 * most of what is out there, an aeroplane stays an aeroplane.
 */
public class AircraftRenderer extends GeoEntityRenderer<AircraftEntity> {
    /** How solid a ghost is. Enough to read against the sky, far too little to mistake for near. */
    private static final float GHOST_ALPHA = GhostGeoRenderer.GHOST_ALPHA;

    /** Set for the duration of one aircraft's draw, so the model hooks know how to draw it. */
    private boolean drawingGhost;

    public AircraftRenderer(EntityRendererProvider.Context context) {
        super(context, new AircraftModel());
    }

    /**
     * Stands down beyond the ghost start distance, where the ghost pass takes over. The test is the
     * same one the pass makes, from the same camera, so an aircraft is always one or the other's
     * and never both.
     */
    @Override
    public boolean shouldRender(AircraftEntity aircraft, Frustum frustum, double camX, double camY, double camZ) {
        if (GhostRenderDispatcher.claims(aircraft, camX, camY, camZ)) {
            return false;
        }

        return super.shouldRender(aircraft, frustum, camX, camY, camZ);
    }

    /**
     * Draws the aircraft, and then whatever is hanging under its wings.
     *
     * <p>Only pylons are drawn. A gun built into the airframe is already part of the model, and
     * drawing something at its muzzle would put a floating pod on the nose.
     *
     * <p>Everything about being a long way off — being drawn at all out there, being drawn near
     * enough for the projection, and being kept out of the fog — belongs to the ghost pass and
     * happens around this call. All that is decided here is what the aeroplane should look like.
     */
    @Override
    public void render(AircraftEntity aircraft, float yaw, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource, int packedLight) {
        // Translucent only when the ghost pass is drawing this and says there is genuinely nothing
        // behind it. Over ground somebody else is still drawing — Distant Horizons, most likely —
        // an aeroplane is an aeroplane and should look like one; it simply cannot be lit or fogged
        // like one, there being no chunks out there to say what the light is doing.
        this.drawingGhost = GhostRenderContext.isTranslucent();
        this.renderSolid(aircraft, yaw, partialTick, poseStack, bufferSource, packedLight);
    }

    private void renderSolid(AircraftEntity aircraft, float yaw, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource, int packedLight) {
        super.render(aircraft, yaw, partialTick, poseStack, bufferSource, packedLight);

        List<AircraftDefinition.Hardpoint> hardpoints = aircraft.getStats().hardpoints();
        List<WeaponMounts.Mount> mounts = aircraft.getWeapons().mounts();

        if (hardpoints.isEmpty()) {
            return;
        }

        for (int slot = 0; slot < Math.min(hardpoints.size(), mounts.size()); slot++) {
            AircraftDefinition.Hardpoint hardpoint = hardpoints.get(slot);
            WeaponMounts.Mount mount = mounts.get(slot);

            if (hardpoint.isFixed() || mount.isEmpty() || isExpended(mount)) {
                continue;
            }

            poseStack.pushPose();
            // Into the aircraft's own frame, where the hardpoint was measured. The half turn is the
            // model's: geometry faces north, and the aircraft is described from the nose down +Z.
            poseStack.mulPose(aircraft.getAttitude(partialTick));
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
            poseStack.translate(hardpoint.pos().x, hardpoint.pos().y, -hardpoint.pos().z);

            // Drawn from the weapon's own geometry, the same file the missile is drawn from once it
            // has left, so a store looks the same hanging as it does flying.
            MountedStore store = MountedStore.of(mount.weapon());
            GeoObjectRenderer<MountedStore> renderer = MountedStore.renderer();
            ResourceLocation texture = renderer.getTextureLocation(store);
            // A ghost's stores are part of the ghost. Left solid they would be the one opaque thing
            // about a translucent aeroplane, which reads as a bug rather than as a missile.
            RenderType type = this.drawingGhost
                    ? RenderType.entityTranslucentEmissive(texture)
                    : RenderType.entityCutoutNoCull(texture);

            renderer.render(poseStack, store, bufferSource, type, bufferSource.getBuffer(type),
                    packedLight, partialTick);
            poseStack.popPose();
        }
    }

    /**
     * A ghost is drawn translucent and emissive: emissive so that it is lit by nothing, which is the
     * only sensible answer for something standing over ground the client has never loaded and has no
     * light values for.
     */
    @Override
    public RenderType getRenderType(AircraftEntity animatable, ResourceLocation texture,
            MultiBufferSource bufferSource, float partialTick) {
        return this.drawingGhost
                ? RenderType.entityTranslucentEmissive(texture)
                : super.getRenderType(animatable, texture, bufferSource, partialTick);
    }

    @Override
    public Color getRenderColor(AircraftEntity animatable, float partialTick, int packedLight) {
        Color colour = super.getRenderColor(animatable, partialTick, packedLight);

        return this.drawingGhost
                ? Color.ofRGBA(colour.getRed(), colour.getGreen(), colour.getBlue(),
                        (int) (GHOST_ALPHA * 255.0F))
                : colour;
    }

    /**
     * Whether a mount has nothing left to draw: a missile that has been launched is somewhere else
     * now, and leaving a copy of it hanging on the rail would be the same missile in two places.
     *
     * <p>An empty pod is not expended in this sense. A rocket pod or a gun pod is a container bolted
     * to the pylon, and it stays there whether or not there is anything left inside it.
     */
    private static boolean isExpended(WeaponMounts.Mount mount) {
        return mount.ammo() <= 0 && AircraftManager.weapon(mount.weapon()).leavesRail();
    }

    /**
     * Models are rarely built at Minecraft's scale, so each aircraft's file says what to draw it at.
     * The figure is not known when the renderer is built, only when there is an aircraft to draw.
     */
    @Override
    public void preRender(PoseStack poseStack, AircraftEntity animatable, BakedGeoModel model,
            MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender, float partialTick,
            int packedLight, int packedOverlay, int colour) {
        float scale = animatable.getStats().model().scale();
        this.scaleWidth = scale;
        this.scaleHeight = scale;
        this.shadowRadius = animatable.getBbWidth() * 0.5F;

        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick,
                packedLight, packedOverlay, colour);
    }

    /**
     * Turns the model to match the aircraft's attitude, which is a rotation and so needs no angles
     * pulling out of it. The base implementation is deliberately not called: it would apply a
     * heading of its own, and it reads that heading off the body rotation of a living entity, which
     * an aircraft is not.
     *
     * <p>The half turn afterwards is the model's own: geometry is authored facing north, which is
     * the entity's -Z, and the aircraft's rotation is described from the nose down +Z.
     */
    @Override
    protected void applyRotations(AircraftEntity animatable, PoseStack poseStack, float ageInTicks,
            float rotationYaw, float partialTick, float nativeScale) {
        poseStack.mulPose(animatable.getAttitude(partialTick));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
    }

    /** A name tag floating over a moving aircraft is more distracting than useful. */
    @Override
    public boolean shouldShowName(AircraftEntity animatable) {
        return false;
    }
}
