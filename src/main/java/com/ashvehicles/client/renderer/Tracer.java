package com.ashvehicles.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * What a round in flight is drawn as, at whatever distance it is being drawn from.
 *
 * <p>One class rather than two because a round is drawn by two different things — its own renderer
 * while it is near, and the ghost pass once it is past the hand-over — and it must not change
 * appearance as it crosses between them. Everything about the shape of a tracer lives here and both
 * of them ask for it.
 *
 * <h2>Why a tracer needs a level of detail at all</h2>
 *
 * <p>Nothing about it can be dropped: it is four vertices, and there is no cheaper way to draw a
 * line. What changes with distance is whether it can be seen. A round is a few centimetres across,
 * and a hundredth of a block is a fraction of a pixel by the time it is a couple of hundred blocks
 * off — so the streak thins away to nothing, and what survives of it flickers as the quad falls
 * across the sampling grid or misses it. That is the exact range at which the ghost pass takes over,
 * and it is also the range at which a gunner most needs to read where their rounds are going.
 *
 * <p>So the streak is held at a width on the <em>screen</em> rather than a width in the world. Close
 * in the world figure is the larger and nothing changes; past the distance at which it would drop
 * below a pixel and a half the drawn width stops shrinking. Which is also what a tracer really does:
 * what the eye is following at that range is not the round but the light it is making, and light
 * does not get thinner than the thing it is being drawn on.
 *
 * <p>{@link #dot} is the furthest tier, for a ghost the pass has put in {@code BILLBOARD}: the
 * streak given up entirely for the point of light it has become. At the ranges a round is actually
 * sent to a client — sixteen chunks, see {@code ModEntities.BULLET} — its streak is still tens of
 * pixels long and that tier is never reached, so this is what the adapter answers with if somebody
 * moves {@code billboardDistance} inside the round's tracking range rather than something anyone
 * will see by default.
 */
public final class Tracer {
    /** How much of a tick's travel the streak covers. */
    public static final float LENGTH = 0.9F;
    /**
     * Longest the streak is ever drawn, in blocks. A cannon round crosses forty blocks in a tick,
     * and a forty-block streak reads as a beam rather than a tracer.
     */
    public static final double MAX_LENGTH = 8.0;
    /** Half-width of the streak in the world, in blocks. What is drawn close in. */
    public static final float HALF_WIDTH = 0.05F;

    /** The thinnest a streak is ever drawn, in screen pixels, however far away it is. */
    private static final double MIN_PIXELS = 1.5;
    /** How wide the furthest tier's point of light is drawn, in screen pixels. */
    private static final double DOT_PIXELS = 2.0;

    private Tracer() {
    }

    /** Where the streak's tail sits, relative to the round: back along the path, within reason. */
    public static Vec3 tail(Vec3 travel) {
        Vec3 tail = travel.scale(-LENGTH);

        return tail.lengthSqr() > MAX_LENGTH * MAX_LENGTH ? tail.normalize().scale(MAX_LENGTH) : tail;
    }

    /**
     * A quad from the round back down its path, bright at the head and gone at the tail. Drawn at
     * the pose stack's current origin, which both callers have already put at the round.
     *
     * @param travel this tick's step, which is the line the streak lies along
     * @param distanceSq how far the round is from the camera, squared; what decides the width
     */
    public static void streak(VertexConsumer buffer, Matrix4f pose, Vec3 travel, double distanceSq, int colour) {
        Vec3 tail = tail(travel);
        // Square to both the streak and the viewer, so it reads as a line from any angle.
        Vec3 across = travel.normalize().cross(new Vec3(0.0, 1.0, 0.0));
        across = (across.lengthSqr() < 1.0E-6 ? new Vec3(1.0, 0.0, 0.0) : across.normalize())
                .scale(halfWidth(distanceSq));

        vertex(buffer, pose, across, colour);
        vertex(buffer, pose, across.scale(-1.0), colour);
        vertex(buffer, pose, tail.add(across.scale(-1.0)), colour & 0x00FFFFFF);
        vertex(buffer, pose, tail.add(across), colour & 0x00FFFFFF);
    }

    /**
     * The furthest tier: a square of light facing the camera, at the round's head, with no length
     * and no direction left in it.
     */
    public static void dot(PoseStack poseStack, VertexConsumer buffer, Camera camera, double distanceSq,
            int colour) {
        float half = (float) (blocksPerPixel(Math.sqrt(distanceSq)) * DOT_PIXELS * 0.5);

        if (half <= 0.0F) {
            return;
        }

        poseStack.pushPose();
        // Faces the camera exactly as a particle does.
        poseStack.mulPose(camera.rotation());
        Matrix4f pose = poseStack.last().pose();

        buffer.addVertex(pose, -half, -half, 0.0F).setColor(colour);
        buffer.addVertex(pose, half, -half, 0.0F).setColor(colour);
        buffer.addVertex(pose, half, half, 0.0F).setColor(colour);
        buffer.addVertex(pose, -half, half, 0.0F).setColor(colour);
        poseStack.popPose();
    }

    /**
     * Half the width to draw the streak at, in blocks: the world figure, or whatever comes to a
     * pixel and a half at this distance, whichever is the wider.
     */
    public static float halfWidth(double distanceSq) {
        double floor = blocksPerPixel(Math.sqrt(distanceSq)) * MIN_PIXELS * 0.5;

        return (float) Math.max(HALF_WIDTH, floor);
    }

    /**
     * How many blocks one screen pixel covers at that distance.
     *
     * <p>From the projection the game is actually using: the top of the frustum is
     * {@code distance × tan(fov / 2)} above the line of sight and half the window's pixels away
     * from the middle of it. Measured against the framebuffer rather than the interface scale,
     * since it is real pixels a quad is rasterised onto.
     *
     * <p>The far-plane pull the ghost pass applies does not come into it. That slides a ghost in
     * and shrinks it by exactly as much, so a ghost covers the same pixels either way — which is
     * the whole point of it — and a width worked out from the true distance is drawn at the width
     * it was asked for.
     */
    private static double blocksPerPixel(double distance) {
        Minecraft minecraft = Minecraft.getInstance();
        int height = minecraft.getWindow().getHeight();

        if (height <= 0) {
            return 0.0;
        }

        double fov = minecraft.options.fov().get();

        return 2.0 * distance * Math.tan(Math.toRadians(fov) * 0.5) / height;
    }

    private static void vertex(VertexConsumer buffer, Matrix4f pose, Vec3 at, int colour) {
        buffer.addVertex(pose, (float) at.x, (float) at.y, (float) at.z).setColor(colour);
    }
}
