package com.ashvehicles.client.ghost;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;

/**
 * What an adapter is handed when it is asked to draw a ghost, plus the one thing the game's own
 * renderers need to know about the ghost pass: whether they are inside it.
 *
 * <p>The static part exists for renderers that serve both the entity loop and the ghost pass —
 * {@code AircraftRenderer} draws an aircraft either way, but a ghost is see-through and lit by
 * nothing, and the renderer has to be told which it is drawing. It is set around each draw by the
 * dispatcher and read on the render thread only.
 */
public final class GhostRenderContext {
    private static boolean drawingGhost;
    private static boolean translucent;

    private final PoseStack poseStack;
    private final MultiBufferSource buffers;
    private final Camera camera;
    private final float partialTick;
    private final int packedLight;
    private final boolean ghostStyle;
    private final double distanceSq;

    GhostRenderContext(PoseStack poseStack, MultiBufferSource buffers, Camera camera, float partialTick,
            int packedLight, boolean ghostStyle, double distanceSq) {
        this.poseStack = poseStack;
        this.buffers = buffers;
        this.camera = camera;
        this.partialTick = partialTick;
        this.packedLight = packedLight;
        this.ghostStyle = ghostStyle;
        this.distanceSq = distanceSq;
    }

    /** At the ghost's origin, world axes, far-plane pull already applied. */
    public PoseStack poseStack() {
        return this.poseStack;
    }

    public MultiBufferSource buffers() {
        return this.buffers;
    }

    public Camera camera() {
        return this.camera;
    }

    public float partialTick() {
        return this.partialTick;
    }

    /** Always full bright: out there the world reports no light rather than unknown light. */
    public int packedLight() {
        return this.packedLight;
    }

    /**
     * Whether the ghost should be drawn as a ghost — translucent, a contact against the sky — rather
     * than as the thing itself. True when there is no drawn terrain behind it.
     */
    public boolean ghostStyle() {
        return this.ghostStyle;
    }

    public double distanceSq() {
        return this.distanceSq;
    }

    // ------------------------------------------------------------------
    // For the game's own renderers
    // ------------------------------------------------------------------

    /** Whether the ghost pass is drawing right now. Render thread only. */
    public static boolean isDrawingGhost() {
        return drawingGhost;
    }

    /** Whether what the ghost pass is drawing should be see-through. Render thread only. */
    public static boolean isTranslucent() {
        return drawingGhost && translucent;
    }

    static void enter(boolean translucentStyle) {
        drawingGhost = true;
        translucent = translucentStyle;
    }

    static void exit() {
        drawingGhost = false;
        translucent = false;
    }
}
