package com.ashvehicles.client.ghost.adapter;

import javax.annotation.Nullable;

import com.ashvehicles.client.ghost.EntityGhost;
import com.ashvehicles.client.ghost.EntityGhostRenderer;
import com.ashvehicles.client.ghost.GhostAdapter;
import com.ashvehicles.client.ghost.GhostConfig;
import com.ashvehicles.client.ghost.GhostLOD;
import com.ashvehicles.client.ghost.GhostRenderContext;
import com.ashvehicles.client.ghost.GhostSnapshot;
import com.ashvehicles.client.ghost.geo.GhostAnimatable;
import com.ashvehicles.client.item.VehicleIcons;
import com.ashvehicles.client.model.GroundVehicleModel;
import com.ashvehicles.client.renderer.VehicleRenderer;
import com.ashvehicles.client.model.VehicleGeoModel;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.vehicle.GroundVehicleDefinition;
import com.ashvehicles.vehicle.Ride;
import com.ashvehicles.vehicle.VehicleChassis;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaternionf;

/**
 * Ground vehicles as ghosts.
 *
 * <p>A tank is a simpler photograph than an aeroplane. It has no undercarriage to play out of an
 * animation file and nothing hanging off it, so the snapshot carries the hull's attitude, the model
 * it is drawn from, and the four things that move: where the turret is pointing, how far the gun is
 * elevated, how far the road wheels have gone round, and how far the barrel has run back. The ghost
 * is drawn from those and nothing else.
 *
 * <p>What that buys is the thing a tank at long range most needs to say. A column two kilometres off
 * is a row of shapes whatever else happens, but a turret traversed onto you is a different piece of
 * news from a turret facing away, and it reads at a distance where nothing else about the vehicle
 * does. So the turret is worth carrying even at the coarsest level of detail that still draws a
 * model at all.
 *
 * <p>Ground vehicles are sent to every client wherever they are, exactly as aircraft are (see
 * {@code EntityTrackingMixin}), so one the client stops receiving is one that is gone; its ghost
 * goes with it rather than lingering.
 */
public final class GroundVehicleGhostAdapter implements GhostAdapter<GroundVehicleEntity> {
    @Override
    public boolean keepAfterLeave(GroundVehicleEntity entity) {
        return false;
    }

    // ------------------------------------------------------------------
    // Snapshot
    // ------------------------------------------------------------------

    @Override
    public GhostSnapshot snapshot(GroundVehicleEntity vehicle, @Nullable GhostSnapshot previous, long gameTime) {
        ResourceLocation id = vehicle.getVehicleId();
        GroundVehicleDefinition stats = vehicle.getStats();
        VehicleChassis.Model setup = stats.model();
        Vec3 position = vehicle.position();
        // The box the vehicle is drawn within, not the one it collides with — see
        // VehicleEntityBase.getBoundingBoxForCulling. Culled against a hull-sized box, a
        // seven-metre tank blinks out at the edge of the screen the moment the ghost pass has it.
        AABB bounds = vehicle.getBoundingBoxForCulling().move(position.reverse());
        Payload payload = new Payload(id, GroundVehicleModel.Setup.of(stats),
                GroundVehicleModel.Pose.of(vehicle, 1.0F));

        return new GhostSnapshot(
                vehicle.getUUID(),
                vehicle.getId(),
                vehicle.getType(),
                position,
                vehicle.getDeltaMovement(),
                vehicle.getYRot(),
                vehicle.getXRot(),
                vehicle.getYRot(),
                new Quaternionf(vehicle.getAttitude()),
                setup.scale(),
                vehicle.isWrecked() ? VehicleRenderer.CHARRED : 1.0F,
                VehicleGeoModel.geometryFile(id),
                VehicleGeoModel.textureFile(id),
                VehicleGeoModel.animationFile(id),
                this.billboard(id),
                bounds,
                true,
                gameTime,
                payload);
    }

    /**
     * The picture the vehicle's own item is drawn as, which is a picture of the vehicle: the right thing
     * to stand in for it at the range where a model is not worth drawing.
     *
     * <p>Not remembered here. It is taken once from the machine's own geometry and kept by
     * {@link VehicleIcons}, which also answers with nothing for the frame or two before the
     * first one has been taken — a snapshot without a billboard just draws its model until the
     * next one is taken.
     */
    @Nullable
    private ResourceLocation billboard(ResourceLocation id) {
        return VehicleIcons.of(id);
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    @Override
    public void render(EntityGhost ghost, GhostLOD lod, GhostRenderContext context) {
        GhostSnapshot snapshot = ghost.current();

        if (lod == GhostLOD.BILLBOARD || !GhostConfig.geckoLibGhosts()) {
            if (EntityGhostRenderer.drawBillboard(snapshot, context) || !GhostConfig.geckoLibGhosts()) {
                return;
            }
            // No icon: the model itself will do.
        }

        PoseStack poseStack = context.poseStack();
        poseStack.pushPose();
        // Into the hull's own frame. The half turn is the model's: geometry faces north, and a
        // machine is described from the front down +Z.
        poseStack.mulPose(attitude(ghost, context.partialTick()));

        // And the body on its springs, the same way and in the same order the vehicle's own
        // renderer applies it — but only when the running gear is being posed, since the wheels and
        // the track are what put themselves back on the ground underneath it.
        if (GhostConfig.animation()) {
            Ride ride = ride(ghost, context.partialTick());

            poseStack.translate(0.0F, ride.heave(), 0.0F);
            poseStack.mulPose(Axis.XP.rotationDegrees(-ride.pitch()));
            poseStack.mulPose(Axis.ZP.rotationDegrees(ride.lean()));
        }

        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));

        EntityGhostRenderer.drawModel(ghost, snapshot, context, GhostConfig.animation() ? POSER : null);
        poseStack.popPose();
    }

    /** How far the body has moved on its springs: between the last two snapshots, as everything is. */
    private static Ride ride(EntityGhost ghost, float partialTick) {
        Payload now = payload(ghost.current());

        if (now == null) {
            return Ride.LEVEL;
        }

        Payload then = payload(ghost.previous());

        return then == null
                ? now.pose().ride()
                : Ride.between(then.pose().ride(), now.pose().ride(), partialTick);
    }

    /** The attitude to draw at: the short way round between the last two snapshots. */
    private static Quaternionf attitude(EntityGhost ghost, float partialTick) {
        Quaternionf now = ghost.current().attitude();
        Quaternionf then = ghost.previous().attitude();

        if (now == null) {
            return new Quaternionf();
        }

        if (then == null || then == now) {
            return now;
        }

        return new Quaternionf(then).slerp(now, partialTick).normalize();
    }

    // ------------------------------------------------------------------
    // Moving as the vehicle moves
    // ------------------------------------------------------------------

    /**
     * The turret, the gun, the road wheels and the recoil, set from the last two snapshots the way
     * the vehicle's own model sets them from the vehicle.
     */
    private static final GhostAnimatable.GhostPoser POSER = (model, ghost, partialTick) -> {
        Payload now = payload(ghost.current());

        if (now == null) {
            return;
        }

        Payload then = payload(ghost.previous());
        GroundVehicleModel.Pose pose = then == null
                ? now.pose()
                : GroundVehicleModel.Pose.between(then.pose(), now.pose(), partialTick);

        GroundVehicleModel.applyPose(model, now.setup(), pose);
    };

    // ------------------------------------------------------------------
    // What the snapshot carries
    // ------------------------------------------------------------------

    @Nullable
    private static Payload payload(GhostSnapshot snapshot) {
        return (Payload) snapshot.payload();
    }

    /**
     * Everything ground-vehicle-specific a snapshot carries.
     *
     * @param setup the figures out of the vehicle's file that the pose is applied against, which
     *        the ghost has no vehicle left to ask for
     */
    record Payload(ResourceLocation vehicleId, GroundVehicleModel.Setup setup, GroundVehicleModel.Pose pose) {
    }
}
