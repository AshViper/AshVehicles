package com.ashvehicles.client.ghost.adapter;

import javax.annotation.Nullable;

import com.ashvehicles.client.ghost.EntityGhost;
import com.ashvehicles.client.ghost.GhostAdapter;
import com.ashvehicles.client.ghost.GhostLOD;
import com.ashvehicles.client.ghost.GhostRenderContext;
import com.ashvehicles.client.ghost.GhostSnapshot;
import com.ashvehicles.client.renderer.Tracer;
import com.ashvehicles.entity.BulletEntity;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Cannon rounds as ghosts: the same streak the near renderer draws, drawn from a snapshot.
 *
 * <p>A round is a few centimetres across and crossing a hundred blocks a second, so there is
 * nothing to draw but the tracer, and the tracer is the thing worth seeing at range — a stream of
 * them is how a gunner reads where the rounds are going, and that reading matters most when the
 * target is far enough away to be out over unloaded ground.
 *
 * <p><b>What the tiers mean here.</b> A tracer has no parts to leave off — it is four vertices, and
 * a line cannot be drawn with fewer — so what the tier decides is not how much of it is drawn but
 * how it is drawn to survive the distance. In the {@code GHOST} tier it is the streak, held to a
 * width on the screen rather than in the world so that it does not thin away to a flickering
 * fraction of a pixel exactly where the ghost pass takes over. In {@code BILLBOARD}, the furthest
 * tier, the streak is given up for the point of light it has become. Both live in {@link Tracer},
 * which is also what the near renderer draws with: a round crossing the hand-over distance must not
 * change appearance as it goes, and the only way to be sure of that is for one piece of code to
 * decide it.
 */
public final class BulletGhostAdapter implements GhostAdapter<BulletEntity> {
    /** What a round is drawn as if its snapshot somehow carries no colour. */
    private static final int DEFAULT_TRACER = 0xFFFFC864;

    @Override
    public GhostSnapshot snapshot(BulletEntity bullet, @Nullable GhostSnapshot previous, long gameTime) {
        Vec3 position = bullet.position();
        Vec3 travel = bullet.getDeltaMovement();
        // The box the streak occupies, not the box the round does: the round is the head of it and
        // the rest lies behind, and a streak whose head is just off the edge of the screen is still
        // most of the way on it.
        AABB bounds = bullet.getBoundingBox().move(position.reverse());
        Vec3 tail = Tracer.tail(travel);
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
                1.0F,
                null,
                null,
                null,
                null,
                bounds.minmax(bounds.move(tail)),
                false,
                gameTime,
                // A round's colour is its weapon's, and the weapon outlives the round on every
                // client, but the colour is cheaper to carry than to look up once a frame.
                0xFF000000 | bullet.getRound().tracer());
    }

    @Override
    public void render(EntityGhost ghost, GhostLOD lod, GhostRenderContext context) {
        GhostSnapshot snapshot = ghost.current();
        int colour = snapshot.payload() instanceof Integer tracer ? tracer : DEFAULT_TRACER;
        // Not lit and not shaded: the render type ignores light entirely, which is the right answer
        // for a tracer whether or not there is a world under it to take light from.
        VertexConsumer buffer = context.buffers().getBuffer(RenderType.lightning());

        if (lod == GhostLOD.BILLBOARD) {
            Tracer.dot(context.poseStack(), buffer, context.camera(), context.distanceSq(), colour);

            return;
        }

        Vec3 travel = snapshot.velocity();

        if (travel.lengthSqr() < 1.0E-6) {
            return;
        }

        Tracer.streak(buffer, context.poseStack().last().pose(), travel, context.distanceSq(), colour);
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
}
