package com.ashvehicles.client.ghost.adapter;

import javax.annotation.Nullable;

import com.ashvehicles.client.ghost.EntityGhost;
import com.ashvehicles.client.ghost.EntityGhostRenderer;
import com.ashvehicles.client.ghost.GhostAdapter;
import com.ashvehicles.client.ghost.GhostConfig;
import com.ashvehicles.client.ghost.GhostLOD;
import com.ashvehicles.client.ghost.GhostRenderContext;
import com.ashvehicles.client.ghost.GhostSnapshot;
import com.ashvehicles.client.model.WeaponModel;
import com.ashvehicles.entity.RocketEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Rockets and missiles as ghosts.
 *
 * <p>The same arrangement as the aircraft: the snapshot carries where it is, which way it is
 * going, and which weapon's geometry to draw it from, and the ghost is drawn from those alone.
 * That matters more here than it does for an aeroplane — the interesting part of a missile's
 * flight is the part that happens over ground nobody has loaded, and a missile is aimed at
 * something a long way off by definition.
 *
 * <p>There is nothing to pose and nothing to animate, so every tier draws the same model; the
 * tiers still decide how far out it is drawn at all, and the pass still pulls it inside the far
 * plane and keeps it out of the fog.
 */
public final class RocketGhostAdapter implements GhostAdapter<RocketEntity> {
    @Override
    public GhostSnapshot snapshot(RocketEntity rocket, @Nullable GhostSnapshot previous, long gameTime) {
        Vec3 position = rocket.position();
        Vec3 travel = rocket.getDeltaMovement();
        AABB bounds = rocket.getBoundingBox().move(position.reverse());
        float animationTime = previous == null ? 0.0F : previous.animationTime() + 0.05F;

        // Heading and elevation along the flight path, in the turns the renderer applies rather
        // than in the game's own convention: a missile has no yaw of its own to speak of, it simply
        // lies along the way it is going.
        float yaw = 0.0F;
        float pitch = 0.0F;

        if (travel.lengthSqr() > 1.0E-8) {
            double flat = Math.sqrt(travel.x * travel.x + travel.z * travel.z);
            yaw = (float) Math.toDegrees(Math.atan2(travel.x, travel.z));
            pitch = (float) -Math.toDegrees(Math.atan2(travel.y, flat));
        } else if (previous != null) {
            yaw = previous.yaw();
            pitch = previous.pitch();
        }

        return new GhostSnapshot(
                rocket.getUUID(),
                rocket.getId(),
                rocket.getType(),
                position,
                travel,
                yaw,
                pitch,
                yaw,
                null,
                1.0F,
                WeaponModel.geometryFile(rocket.getWeaponId()),
                WeaponModel.textureFile(rocket.getWeaponId()),
                null,
                bounds,
                true,
                animationTime,
                gameTime,
                null);
    }

    @Override
    public void render(EntityGhost ghost, GhostLOD lod, GhostRenderContext context) {
        if (!GhostConfig.geckoLibGhosts()) {
            return;
        }

        GhostSnapshot snapshot = ghost.current();
        PoseStack poseStack = context.poseStack();

        poseStack.pushPose();
        // Along the flight path, then the model's own half turn: geometry is authored facing north,
        // which is -Z, and the heading worked out above points +Z along the path.
        poseStack.mulPose(Axis.YP.rotationDegrees(snapshot.yaw()));
        poseStack.mulPose(Axis.XP.rotationDegrees(snapshot.pitch()));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        EntityGhostRenderer.drawModel(ghost, snapshot, context, null);
        poseStack.popPose();
    }

    /**
     * Not handed to Distant Horizons. A box group is a buffer on the graphics card, and a missile
     * lives for a few seconds: making and destroying one per shot would cost far more than drawing
     * the model does, and at the distances the simplified tier covers a missile is a few pixels
     * either way.
     */
    @Override
    public boolean supportsDhBoxes() {
        return false;
    }
}
