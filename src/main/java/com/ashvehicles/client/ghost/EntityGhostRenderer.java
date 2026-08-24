package com.ashvehicles.client.ghost;

import javax.annotation.Nullable;

import com.ashvehicles.client.ghost.geo.GhostAnimatable;
import com.ashvehicles.client.ghost.geo.GhostGeoRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

/**
 * The drawing an adapter can lean on: a model from a snapshot, a flat icon from a snapshot, and
 * the orientation that goes in front of either.
 *
 * <p>Adapters are free to draw however they like — the aircraft adapter turns the model by the
 * aircraft's full attitude and hangs its stores on it — but most of what a ghost needs is the same
 * whatever it is a ghost of, and that lives here. Nothing here looks at an entity.
 */
public final class EntityGhostRenderer {
    private EntityGhostRenderer() {
    }

    /**
     * Turns the pose stack to the snapshot's orientation. The full attitude when the snapshot has
     * one, otherwise heading and pitch in the game's convention — the same turn the game's
     * entity renderers apply.
     */
    public static void orient(PoseStack poseStack, GhostSnapshot snapshot) {
        if (snapshot.attitude() != null) {
            poseStack.mulPose(snapshot.attitude());
            return;
        }

        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - snapshot.bodyYaw()));
        poseStack.mulPose(Axis.XP.rotationDegrees(snapshot.pitch()));
    }

    /**
     * Draws the snapshot's GeckoLib model at the pose stack's current origin and orientation.
     *
     * @param poser how to pose the bones, or {@code null} for the authored pose
     */
    public static void drawModel(EntityGhost ghost, GhostSnapshot snapshot, GhostRenderContext context,
            @Nullable GhostAnimatable.GhostPoser poser) {
        GhostGeoRenderer.draw(ghost, snapshot, context, poser);
    }

    /**
     * Draws the snapshot's billboard: a flat, camera-facing icon the size of the entity, at the
     * middle of where the entity is. The furthest tier, when enabled.
     *
     * @return whether anything was drawn; false if the snapshot has no billboard texture
     */
    public static boolean drawBillboard(GhostSnapshot snapshot, GhostRenderContext context) {
        ResourceLocation texture = snapshot.billboard();

        if (texture == null) {
            return false;
        }

        AABB bounds = snapshot.bounds();
        float size = (float) Math.max(bounds.getXsize(), Math.max(bounds.getYsize(), bounds.getZsize()));
        float half = size * 0.5F;
        PoseStack poseStack = context.poseStack();
        RenderType type = RenderType.entityTranslucentEmissive(texture);
        VertexConsumer buffer = context.buffers().getBuffer(type);
        int alpha = context.ghostStyle() ? (int) (GhostGeoRenderer.GHOST_ALPHA * 255.0F) : 255;
        // An icon standing in for a wreck is darkened the same way the model is, so the furthest
        // tier does not quietly repaint it.
        int level = (int) (255.0F * Mth.clamp(snapshot.shade(), 0.0F, 1.0F));
        int light = context.packedLight();

        poseStack.pushPose();
        poseStack.translate(0.0, (bounds.minY + bounds.maxY) * 0.5, 0.0);
        // Faces the camera exactly as a particle does.
        poseStack.mulPose(context.camera().rotation());
        PoseStack.Pose pose = poseStack.last();

        vertex(buffer, pose, -half, -half, 0.0F, 1.0F, level, alpha, light);
        vertex(buffer, pose, half, -half, 1.0F, 1.0F, level, alpha, light);
        vertex(buffer, pose, half, half, 1.0F, 0.0F, level, alpha, light);
        vertex(buffer, pose, -half, half, 0.0F, 0.0F, level, alpha, light);
        poseStack.popPose();

        return true;
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float u, float v,
            int level, int alpha, int light) {
        buffer.addVertex(pose, x, y, 0.0F)
                .setColor(level, level, level, alpha)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, 0.0F, 0.0F, 1.0F);
    }
}
