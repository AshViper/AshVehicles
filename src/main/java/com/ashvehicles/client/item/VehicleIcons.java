package com.ashvehicles.client.item;

import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;

import com.ashvehicles.AshVehicles;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * The picture of a machine that stands in for it wherever a flat one will do: on its item, and on a
 * ghost too far off to be worth a model. Taken from the machine's own geometry rather than drawn by
 * hand, so a machine that has a model has a picture, and nobody has to remember to make one.
 *
 * <p><b>Why it is a picture and not the model.</b> An item is drawn every frame, in every slot it
 * appears in, in both hands, on the ground and in the tooltip under the cursor — an inventory open
 * over a full hotbar is a dozen draws of the same thing before anything else on the screen. Drawing
 * a GeckoLib machine there means walking its bones and filling its cubes a dozen times a frame, for
 * a picture sixteen pixels across that never changes. So each machine is drawn <em>once</em>, into a
 * texture of its own, and what the item then draws is one square: two triangles and no bones at all,
 * which is less than the item it replaces — a flat vanilla item is a slab with an extruded edge
 * around every hole in it, and this is not.
 *
 * <p>The picture costs a frame to take and is then kept until the resources are reloaded. One is
 * taken per frame, at the top of the frame and never in the middle of a screen, so that opening a
 * creative tab full of machines for the first time cannot stall on ten of them at once. Until a
 * machine's picture is ready its item draws nothing, which lasts a frame or ten and is not something
 * anybody sees.
 */
public final class VehicleIcons {
    /**
     * Where the camera is: round from dead astern, and up from the deck.
     *
     * <p>135° round puts the camera off the machine's <em>right bow</em>: the nose swung towards the
     * camera and to the left of the picture, the right flank towards the camera and to the right of
     * it, so that the front, the side and the top are all in the one view and none of the three is
     * foreshortened away. 225° is the other bow, 0° is dead astern, and 45° and 315° are the
     * quarters.
     *
     * <p>30° up is exactly what Minecraft looks down on a block in the inventory at, which is not a
     * coincidence: a shelf of these next to a shelf of blocks should read as one shelf.
     */
    private static final float AZIMUTH = 135.0F;
    private static final float ELEVATION = 30.0F;

    /**
     * How big the kept picture is, and how much bigger it is drawn before being shrunk to that.
     *
     * <p>The shrink is the whole of the smoothing. Drawing straight into 128 pixels leaves a hard
     * staircase down every edge of a machine seen from the corner — every edge of one is a diagonal.
     * Drawn at twice that and averaged four pixels into one, the same edges come out graded, which
     * is what the eye reads as a straight line. Four times the pixels is four times nothing, once.
     */
    private static final int SIZE = 128;
    private static final int OVERSAMPLE = 2;
    private static final int DRAWN_SIZE = SIZE * OVERSAMPLE;

    /** How much wider than the machine the picture is cut, so that nothing is touching the edge. */
    private static final float MARGIN = 1.08F;

    /**
     * How much room is left in front of and behind the machine for the depth test to work in, in
     * blocks. Anything at all does: the machine is drawn flat on, with nothing else in the picture.
     */
    private static final float DEPTH_MARGIN = 1.0F;

    private static final Map<ResourceLocation, ResourceLocation> TAKEN = new ConcurrentHashMap<>();
    private static final Set<ResourceLocation> WAITING = ConcurrentHashMap.newKeySet();
    private static final Set<ResourceLocation> FAILED = ConcurrentHashMap.newKeySet();
    private static final Queue<ResourceLocation> QUEUE = new ConcurrentLinkedQueue<>();

    /** One target, borrowed by each machine in turn. Built on the first picture and then kept. */
    @Nullable
    private static RenderTarget target;

    private VehicleIcons() {
    }

    /**
     * The picture of a machine, or {@code null} if there is not one yet — in which case one is asked
     * for, and there will be within a frame or two.
     *
     * <p>Safe to call from anywhere; taking the picture happens on the render thread and nowhere
     * else.
     */
    @Nullable
    public static ResourceLocation of(ResourceLocation vehicle) {
        ResourceLocation taken = TAKEN.get(vehicle);

        if (taken != null) {
            return taken;
        }

        if (!FAILED.contains(vehicle) && WAITING.add(vehicle)) {
            QUEUE.add(vehicle);
        }

        return null;
    }

    /**
     * Takes the next machine's picture, if any machine is waiting for one. Called at the top of a
     * frame, where the screen has not been drawn yet and there is nothing of anybody else's to put
     * back afterwards.
     */
    public static void takeNext() {
        ResourceLocation vehicle = QUEUE.poll();

        if (vehicle == null) {
            return;
        }

        WAITING.remove(vehicle);

        try {
            TAKEN.put(vehicle, take(vehicle));
        } catch (Exception exception) {
            // Once. A machine whose model or texture will not load is not going to start loading
            // because its item was drawn again, and the log would fill at sixty lines a second.
            FAILED.add(vehicle);
            AshVehicles.LOGGER.error("Cannot draw an item picture for {}; its item will be blank", vehicle,
                    exception);
        }
    }

    /**
     * Throws every picture away, to be taken again as they are asked for. The models and the
     * textures they were taken from have just been reloaded, so every one of them is out of date —
     * including the ones that failed, which a fixed resource pack may have just fixed.
     */
    public static void forget() {
        Minecraft minecraft = Minecraft.getInstance();

        TAKEN.values().forEach(texture -> minecraft.getTextureManager().release(texture));
        TAKEN.clear();
        FAILED.clear();
        WAITING.clear();
        QUEUE.clear();
    }

    /** Draws the machine into the offscreen target and keeps what came out as a texture. */
    private static ResourceLocation take(ResourceLocation vehicle) {
        Minecraft minecraft = Minecraft.getInstance();
        Quaternionf view = view();
        VehicleIconGeo.Bounds bounds = VehicleIconGeo.measure(vehicle, view);

        if (bounds.isEmpty()) {
            throw new IllegalStateException("There is nothing in the model to draw");
        }

        RenderTarget into = target();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();

        into.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        into.clear(Minecraft.ON_OSX);
        into.bindWrite(true);
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(framing(bounds), VertexSorting.ORTHOGRAPHIC_Z);
        // The whole of the view is in the pose stack below, so that what is drawn is exactly what
        // was measured. Whatever the last frame left here is not part of it.
        modelView.pushMatrix();
        modelView.identity();
        RenderSystem.applyModelViewMatrix();

        try {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            // Nothing here is standing in the weather, and the fog of wherever the player happens to
            // be would otherwise be drawn over the machine at whatever range the box put it at.
            FogRenderer.setupNoFog();
            // Lit as it is in the world at noon: tops bright, sides shaded. A machine's picture
            // should look like the machine.
            Lighting.setupLevel();

            PoseStack poseStack = new PoseStack();
            poseStack.mulPose(view);

            MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
            VehicleIconGeo.draw(poseStack, vehicle, buffers);
            buffers.endBatch();
        } finally {
            // Whatever happened in there, the frame that is about to be drawn gets its own matrices
            // and its own card back.
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            into.unbindWrite();
            minecraft.getMainRenderTarget().bindWrite(true);
        }

        return keep(vehicle, into);
    }

    /**
     * The turn from the machine's own frame into the camera's.
     *
     * <p>The machine is turned about its own vertical to bring the bow round, and the whole of it is
     * then tipped towards the camera to look down on it — in that order, which is what a quaternion
     * built this way applies. Nothing else: the camera sits at the origin looking down its own −Z,
     * and how far away the machine is does not come into a flat-on view.
     *
     * <p>The angles are in the frame the geometry is baked in, where the nose is down −Z and the
     * <em>right</em> side is down −X. Both of those are the machine's own doing rather than
     * anything Minecraft settles: geometry here is authored facing north, and GeckoLib mirrors X
     * when it bakes. See {@code GroundVehicleModel} for the same two facts from the other end.
     */
    private static Quaternionf view() {
        return new Quaternionf()
                .rotationX(ELEVATION * (float) (Math.PI / 180.0))
                .rotateY(AZIMUTH * (float) (Math.PI / 180.0));
    }

    /**
     * The box the picture is cut to: square, centred on the machine, and just big enough for the
     * longer of its two sides.
     *
     * <p>Square because the picture is, and cut to the machine rather than to a figure per machine
     * because a tank and a bomber both have to fill the same sixteen pixels. Flat on rather than in
     * perspective, so that a long machine is not distorted end to end and a wing pointing at the
     * camera does not swell.
     */
    private static Matrix4f framing(VehicleIconGeo.Bounds bounds) {
        // Never nothing: a box with no width at all is a projection full of infinities.
        float half = Math.max(bounds.across() * 0.5F * MARGIN, 0.001F);
        float middleX = bounds.middleX();
        float middleY = bounds.middleY();

        // The near and far planes are given as distances down the camera's own line of sight, which
        // runs the other way from the axis the machine was measured along.
        return new Matrix4f().setOrtho(middleX - half, middleX + half, middleY - half, middleY + half,
                -bounds.nearest() - DEPTH_MARGIN, -bounds.furthest() + DEPTH_MARGIN);
    }

    /** Reads what was drawn back out of the card, shrinks it, and registers it as a texture. */
    private static ResourceLocation keep(ResourceLocation vehicle, RenderTarget from) {
        NativeImage drawn = new NativeImage(DRAWN_SIZE, DRAWN_SIZE, false);

        try {
            from.bindRead();
            drawn.downloadTexture(0, false);
            from.unbindRead();
            // A frame buffer is read from the bottom up and an image is written from the top down.
            drawn.flipY();

            ResourceLocation name = ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID,
                    "vehicle_icon/" + vehicle.getPath());
            DynamicTexture texture = new DynamicTexture(shrink(drawn));
            // Smoothed rather than blocky: this is a photograph of a machine and not pixel art, and
            // it is drawn at a different size in a slot, in the hand and on the ground.
            texture.setFilter(true, false);
            Minecraft.getInstance().getTextureManager().register(name, texture);

            return name;
        } finally {
            drawn.close();
        }
    }

    /**
     * Averages each square of drawn pixels down into one.
     *
     * <p>Weighted by how much of each pixel there is, which matters at every edge: an edge pixel is
     * part machine and part nothing, and nothing here is transparent <em>black</em>. Averaged
     * straight, a white wingtip against nothing comes out grey, and every machine is drawn with a
     * dirty outline around it.
     */
    private static NativeImage shrink(NativeImage drawn) {
        NativeImage icon = new NativeImage(SIZE, SIZE, false);

        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int alpha = 0;
                int red = 0;
                int green = 0;
                int blue = 0;

                for (int downY = 0; downY < OVERSAMPLE; downY++) {
                    for (int downX = 0; downX < OVERSAMPLE; downX++) {
                        // Packed as alpha, blue, green, red, from the top down.
                        int pixel = drawn.getPixelRGBA(x * OVERSAMPLE + downX, y * OVERSAMPLE + downY);
                        int weight = pixel >>> 24;

                        alpha += weight;
                        blue += (pixel >> 16 & 0xFF) * weight;
                        green += (pixel >> 8 & 0xFF) * weight;
                        red += (pixel & 0xFF) * weight;
                    }
                }

                icon.setPixelRGBA(x, y, alpha == 0 ? 0
                        : (alpha / (OVERSAMPLE * OVERSAMPLE)) << 24
                                | (blue / alpha) << 16 | (green / alpha) << 8 | (red / alpha));
            }
        }

        return icon;
    }

    private static RenderTarget target() {
        RenderTarget built = target;

        if (built == null) {
            // With a depth buffer: the near side of a machine has to cover the far side of it.
            built = new TextureTarget(DRAWN_SIZE, DRAWN_SIZE, true, Minecraft.ON_OSX);
            target = built;
        }

        return built;
    }
}
