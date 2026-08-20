package com.ashvehicles.client.ghost.adapter;

import javax.annotation.Nullable;

import com.ashvehicles.client.ghost.EntityGhost;
import com.ashvehicles.client.ghost.GhostAdapter;
import com.ashvehicles.client.ghost.GhostLOD;
import com.ashvehicles.client.ghost.GhostRenderContext;
import com.ashvehicles.client.ghost.GhostSnapshot;
import com.ashvehicles.entity.BulletEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;

/**
 * Cannon rounds as ghosts: the same streak the near renderer draws, drawn from a snapshot.
 *
 * <p>A round is a few centimetres across and crossing a hundred blocks a second, so there is
 * nothing to draw but the tracer, and the tracer is the thing worth seeing at range — a stream of
 * them is how a gunner reads where the rounds are going, and that reading matters most when the
 * target is far enough away to be out over unloaded ground. Every tier draws the same streak;
 * what the tiers decide is how far out it is drawn at all.
 *
 * <p>The numbers here are the near renderer's, and deliberately so: a round crossing the hand-over
 * distance must not change appearance as it goes.
 */
public final class BulletGhostAdapter implements GhostAdapter<BulletEntity> {
    /** How much of a tick's travel the streak covers. */
    private static final float LENGTH = 0.9F;
    /** Longest the streak is ever drawn, in blocks, so that a fast round reads as a tracer and not a beam. */
    private static final double MAX_LENGTH = 8.0;
    /** Half-width of the streak, in blocks. */
    private static final float HALF_WIDTH = 0.05F;

    @Override
    public GhostSnapshot snapshot(BulletEntity bullet, @Nullable GhostSnapshot previous, long gameTime) {
        Vec3 position = bullet.position();
        Vec3 travel = bullet.getDeltaMovement();
        // The box the streak occupies, not the box the round does: the round is the head of it and
        // the rest lies behind, and a streak whose head is just off the edge of the screen is still
        // most of the way on it.
        AABB bounds = bullet.getBoundingBox().move(position.reverse());
        Vec3 tail = tail(travel);
        float animationTime = previous == null ? 0.0F : previous.animationTime() + 0.05F;

        return new GhostSnapshot(
                bullet.getUUID(),
                bullet.getId(),
                bullet.getType(),
                position,
                travel,
                bullet.getYRot(),
                bullet.getXRot(),
                bullet.getYRot(),
                null,
                1.0F,
                null,
                null,
                null,
                bounds.minmax(bounds.move(tail)),
                false,
                animationTime,
                gameTime,
                // A round's colour is its weapon's, and the weapon outlives the round on every
                // client, but the colour is cheaper to carry than to look up once a frame.
                0xFF000000 | bullet.getRound().tracer());
    }

    @Override
    public void render(EntityGhost ghost, GhostLOD lod, GhostRenderContext context) {
        GhostSnapshot snapshot = ghost.current();
        Vec3 travel = snapshot.velocity();

        if (travel.lengthSqr() < 1.0E-6) {
            return;
        }

        Vec3 tail = tail(travel);
        // Square to both the streak and the viewer, so it reads as a line from any angle.
        Vec3 across = travel.normalize().cross(new Vec3(0.0, 1.0, 0.0));
        across = (across.lengthSqr() < 1.0E-6 ? new Vec3(1.0, 0.0, 0.0) : across.normalize()).scale(HALF_WIDTH);

        int colour = snapshot.payload() instanceof Integer tracer ? tracer : 0xFFFFC864;
        PoseStack poseStack = context.poseStack();
        Matrix4f pose = poseStack.last().pose();
        // Not lit and not shaded: the render type ignores light entirely, which is the right answer
        // for a tracer whether or not there is a world under it to take light from.
        VertexConsumer buffer = context.buffers().getBuffer(RenderType.lightning());

        // A quad from the round back down its path, bright at the head and gone at the tail.
        vertex(buffer, pose, across, colour);
        vertex(buffer, pose, across.scale(-1.0), colour);
        vertex(buffer, pose, tail.add(across.scale(-1.0)), colour & 0x00FFFFFF);
        vertex(buffer, pose, tail.add(across), colour & 0x00FFFFFF);
    }

    /** Where the streak's tail sits, relative to the round: back along the path, within reason. */
    private static Vec3 tail(Vec3 travel) {
        Vec3 tail = travel.scale(-LENGTH);

        return tail.lengthSqr() > MAX_LENGTH * MAX_LENGTH ? tail.normalize().scale(MAX_LENGTH) : tail;
    }

    private static void vertex(VertexConsumer buffer, Matrix4f pose, Vec3 at, int colour) {
        buffer.addVertex(pose, (float) at.x, (float) at.y, (float) at.z).setColor(colour);
    }

    /**
     * Never traced against the world. A round lives for a couple of seconds and there are a great
     * many of them at once, so they would take the whole ray budget and leave none for the aircraft
     * — which are the things a wrongly drawn ghost actually matters for. What this costs is a
     * tracer drawn over one of Distant Horizons' hills; the game's own depth buffer still hides one
     * behind terrain it has drawn itself.
     */
    @Override
    public boolean needsOcclusionCheck() {
        return false;
    }

    /** A streak is not a shape; there is nothing to make boxes out of. */
    @Override
    public boolean supportsDhBoxes() {
        return false;
    }
}
