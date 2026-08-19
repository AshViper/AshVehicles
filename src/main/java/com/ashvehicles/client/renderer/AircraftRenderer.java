package com.ashvehicles.client.renderer;

import java.util.List;

import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.aircraft.AircraftManager;
import com.ashvehicles.client.model.AircraftModel;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.weapon.WeaponMounts;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.GeoObjectRenderer;
import software.bernie.geckolib.util.Color;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Draws any aircraft, taking its scale and its attitude from the aircraft itself.
 *
 * <p>An aircraft beyond the world the player has actually loaded is drawn as a <em>ghost</em>: the
 * same shape, translucent, lit by nothing, and — the point of the exercise — in front of the fog
 * rather than behind it. The server keeps reporting aircraft out to their {@code ghost_range}, far
 * past the chunks anyone has, because an aeroplane at altitude is visible from much further away
 * than the ground beneath it. Drawn normally, one out there would be a solid model hanging over an
 * empty void, or more likely a shape swallowed whole by the fog at the edge of the loaded world.
 * Drawn as a ghost it reads for what it is: a contact, seen at a distance, not quite of the
 * world in front of you.
 */
public class AircraftRenderer extends GeoEntityRenderer<AircraftEntity> {
    /**
     * How much of the loaded world's radius an aircraft has to be beyond before it is a ghost. Kept
     * just inside the edge so that the change happens while the aircraft is still in clear air,
     * rather than at the exact line where the terrain stops being drawn.
     */
    private static final double GHOST_FRACTION = 0.85;
    /** How solid a ghost is. Enough to read against the sky, far too little to mistake for near. */
    private static final float GHOST_ALPHA = 0.55F;

    /** Set for the duration of one aircraft's draw, so the model hooks know which way to draw it. */
    private boolean drawingGhost;

    public AircraftRenderer(EntityRendererProvider.Context context) {
        super(context, new AircraftModel());
    }

    /**
     * Whether this aircraft is beyond the world the player can actually see, and so should be drawn
     * as a ghost rather than as an aeroplane.
     *
     * <p>Measured against the render distance rather than against a fixed number of blocks, because
     * that is the thing that decides how much world there is to be in front of. Anything in a chunk
     * the client has not got is a ghost whatever the distance, which is what catches an aircraft
     * over ground that was never loaded.
     */
    private static boolean isGhost(AircraftEntity aircraft) {
        Minecraft minecraft = Minecraft.getInstance();
        double loaded = loadedRadius() * GHOST_FRACTION;
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();

        if (aircraft.position().distanceToSqr(camera) > loaded * loaded) {
            return true;
        }

        return !aircraft.level().hasChunkAt(aircraft.blockPosition());
    }

    /**
     * How far out this client actually has blocks, in blocks. Past here there is nothing to ask
     * about: unloaded ground is not solid to anything the client can see, whatever is really there.
     */
    private static double loadedRadius() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
    }

    /**
     * Draws the aircraft, and then whatever is hanging under its wings.
     *
     * <p>Only pylons are drawn. A gun built into the airframe is already part of the model, and
     * drawing something at its muzzle would put a floating pod on the nose.
     *
     * <p>A ghost is drawn with the fog pushed out beyond the horizon and put back afterwards. Fog is
     * a property of the shader rather than of the model, so the only way to keep a distant aircraft
     * out of it is to move the fog for the length of its draw; and because the buffer source batches
     * everything by material and draws it later, the batch has to be flushed while the fog is still
     * moved, or the change would land on whatever happened to be drawn next instead.
     */
    @Override
    public void render(AircraftEntity aircraft, float yaw, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource, int packedLight) {
        this.drawingGhost = isGhost(aircraft);

        if (this.drawingGhost) {
            // A ghost is drawn without fog and lit by nothing so that it reads against the sky. The
            // same treatment would have it read straight through a hillside, so one with the world
            // in the way is not drawn at all.
            if (aircraft.isHiddenFrom(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition(),
                    loadedRadius())) {
                return;
            }

            this.renderGhost(aircraft, yaw, partialTick, poseStack, bufferSource);

            return;
        }

        this.renderSolid(aircraft, yaw, partialTick, poseStack, bufferSource, packedLight);
    }

    /** The aircraft as a contact in the distance: no fog, no shading, and see-through. */
    private void renderGhost(AircraftEntity aircraft, float yaw, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource) {
        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);

        try {
            // Lit by nothing, because there is no telling what the light is like somewhere the
            // client has never loaded, and a ghost that went black over unlit ground would vanish.
            this.renderSolid(aircraft, yaw, partialTick, poseStack, bufferSource, LightTexture.FULL_BRIGHT);

            if (bufferSource instanceof MultiBufferSource.BufferSource batched) {
                batched.endBatch();
            }
        } finally {
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
        }
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
