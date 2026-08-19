package com.ashvehicles.client.renderer;

import com.ashvehicles.entity.BulletEntity;
import com.ashvehicles.weapon.WeaponDefinition;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

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
    /** How much of a tick's travel the streak covers. */
    private static final float LENGTH = 0.9F;
    /**
     * Longest the streak is ever drawn, in blocks. A cannon round crosses forty blocks in a tick,
     * and a forty-block streak reads as a beam rather than a tracer.
     */
    private static final double MAX_LENGTH = 8.0;
    /** Half-width of the streak, in blocks. */
    private static final float HALF_WIDTH = 0.05F;

    public BulletRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(BulletEntity bullet, float yaw, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource, int packedLight) {
        WeaponDefinition.Projectile round = bullet.getWeapon().projectile();
        Vec3 travel = bullet.getDeltaMovement();

        if (travel.lengthSqr() < 1.0E-6) {
            return;
        }

        // Back along the flight path: the entity is where the round is now, and the streak is where
        // it has just been.
        Vec3 tail = travel.scale(-LENGTH);
        // Square to both the streak and the viewer, so it reads as a line from any angle.
        Vec3 across = travel.normalize().cross(new Vec3(0.0, 1.0, 0.0));
        across = (across.lengthSqr() < 1.0E-6 ? new Vec3(1.0, 0.0, 0.0) : across.normalize()).scale(HALF_WIDTH);

        int colour = 0xFF000000 | round.tracer();
        poseStack.pushPose();
        Matrix4f pose = poseStack.last().pose();
        VertexConsumer buffer = bufferSource.getBuffer(RenderType.lightning());

        // A quad from the round back down its path, bright at the head and gone at the tail.
        vertex(buffer, pose, across.add(Vec3.ZERO), colour);
        vertex(buffer, pose, across.scale(-1.0), colour);
        vertex(buffer, pose, tail.add(across.scale(-1.0)), colour & 0x00FFFFFF);
        vertex(buffer, pose, tail.add(across), colour & 0x00FFFFFF);

        poseStack.popPose();
    }

    private static void vertex(VertexConsumer buffer, Matrix4f pose, Vec3 at, int colour) {
        buffer.addVertex(pose, (float) at.x, (float) at.y, (float) at.z).setColor(colour);
    }

    @Override
    public ResourceLocation getTextureLocation(BulletEntity bullet) {
        return net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS;
    }
}
