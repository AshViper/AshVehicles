package com.ashvehicles.client.renderer;

import com.ashvehicles.client.ghost.GhostRenderDispatcher;
import com.ashvehicles.entity.BulletEntity;
import com.ashvehicles.weapon.WeaponDefinition;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Draws a round as a tracer: a short streak lying along the way it is going, in the colour its
 * weapon file asks for.
 *
 * <p>A round is a few centimetres across and crossing a hundred blocks a second, so there is nothing
 * to be gained from drawing it as an object. What a gunner actually sees is the streak, and the
 * streak is what says where the rounds are going, so that is what is drawn. Its length is a tick's
 * travel, which is the truth of how far it moves between frames.
 */
public class BulletRenderer extends EntityRenderer<BulletEntity> {
    public BulletRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    /**
     * Stands down beyond the ghost start distance, where the ghost pass takes over and draws the
     * same streak from a snapshot. The test is the one the pass makes, from the same camera, so a
     * round is always one or the other's and never both.
     */
    @Override
    public boolean shouldRender(BulletEntity bullet, Frustum frustum, double camX, double camY, double camZ) {
        if (GhostRenderDispatcher.claims(bullet, camX, camY, camZ)) {
            return false;
        }

        return super.shouldRender(bullet, frustum, camX, camY, camZ);
    }

    /**
     * A tracer is not lit — the render type it is drawn with ignores light entirely — so there is
     * nothing to do here but draw it. Being seen at all over unloaded ground, and being kept out of
     * the fog that would otherwise swallow a stream of them, belongs to the ghost pass, which draws
     * the same streak from {@code BulletGhostAdapter} once a round is past the hand-over.
     */
    @Override
    public void render(BulletEntity bullet, float yaw, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource, int packedLight) {
        this.drawTracer(bullet, partialTick, poseStack, bufferSource);
    }

    private void drawTracer(BulletEntity bullet, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource) {
        WeaponDefinition.Projectile round = bullet.getWeapon().projectile();
        // The step being drawn rather than the next one, so the streak lies along the line the round
        // is actually travelling down this frame.
        Vec3 travel = bullet.travel(partialTick);
        Camera camera = this.entityRenderDispatcher.camera;

        if (travel.lengthSqr() < 1.0E-6 || camera == null) {
            return;
        }

        // Where the round is being seen from, which is what the streak is turned to face. The
        // interpolated position rather than the tick one: it is the point the pose stack has already
        // been put at, and a direction taken from anywhere else would turn the quad to face
        // somewhere the round is not.
        Vec3 fromCamera = bullet.getPosition(partialTick).subtract(camera.getPosition());

        // Drawn by the same code the ghost pass draws it with, measured against the same camera
        // distance, so that nothing about a round changes as it crosses the hand-over. See Tracer.
        Tracer.streak(poseStack, bufferSource.getBuffer(RenderType.lightning()), camera, fromCamera, travel,
                this.entityRenderDispatcher.distanceToSqr(bullet), 0xFF000000 | round.tracer());
    }

    @Override
    public ResourceLocation getTextureLocation(BulletEntity bullet) {
        return net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS;
    }
}
