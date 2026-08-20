package com.ashvehicles.client.ghost.adapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.aircraft.AircraftManager;
import com.ashvehicles.aircraft.AircraftShape;
import com.ashvehicles.aircraft.Attitude;
import com.ashvehicles.client.ghost.EntityGhost;
import com.ashvehicles.client.ghost.EntityGhostRenderer;
import com.ashvehicles.client.ghost.GhostAdapter;
import com.ashvehicles.client.ghost.GhostConfig;
import com.ashvehicles.client.ghost.GhostLOD;
import com.ashvehicles.client.ghost.GhostRenderContext;
import com.ashvehicles.client.ghost.GhostSnapshot;
import com.ashvehicles.client.ghost.geo.GhostAnimatable;
import com.ashvehicles.client.ghost.geo.GhostGeoRenderer;
import com.ashvehicles.client.model.AircraftModel;
import com.ashvehicles.client.renderer.MountedStore;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.weapon.WeaponMounts;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.renderer.GeoObjectRenderer;

import org.joml.Matrix3f;
import org.joml.Quaternionf;

/**
 * Aircraft as ghosts.
 *
 * <p>The snapshot carries the aircraft's attitude, its model files, the pose of its moving parts
 * and what is hanging under its wings; the ghost is drawn from those and nothing else. In the
 * ghost tier that is the model, posed and armed — a simplified animation, a handful of bone
 * rotations read from the snapshot, with no controllers behind it. In the simplified tier it is
 * the model as authored, or a few boxes in Distant Horizons' pass. Beyond that, when enabled, it
 * is the aircraft's item icon as a billboard.
 *
 * <p>Aircraft are sent to every client wherever they are (see {@code EntityTrackingMixin}), so one
 * the client stops receiving is one that is gone; its ghost goes with it rather than lingering.
 */
public final class AircraftGhostAdapter implements GhostAdapter<AircraftEntity> {
    /** The boxes a simplified ghost is drawn as by Distant Horizons: a dark airframe grey. */
    private static final int DH_BOX_COLOUR = 0xFF3C3C42;

    /** Whether each aircraft has an item icon to use as a billboard. Asked once per aircraft. */
    private final Map<ResourceLocation, ResourceLocation> billboards = new HashMap<>();

    @Override
    public boolean keepAfterLeave(AircraftEntity entity) {
        return false;
    }

    @Override
    public boolean supportsDhBoxes() {
        return true;
    }

    // ------------------------------------------------------------------
    // Snapshot
    // ------------------------------------------------------------------

    @Override
    public GhostSnapshot snapshot(AircraftEntity aircraft, @Nullable GhostSnapshot previous, long gameTime) {
        ResourceLocation id = aircraft.getAircraftId();
        AircraftDefinition stats = aircraft.getStats();
        AircraftDefinition.ModelSetup setup = stats.model();
        Vec3 position = aircraft.position();
        AABB bounds = aircraft.getBoundingBox().move(position.reverse());
        float animationTime = previous == null ? 0.0F : previous.animationTime() + 0.05F;

        Payload payload = new Payload(id, setup, AircraftModel.Pose.of(aircraft, 1.0F), stores(aircraft, stats),
                AircraftManager.shape(id).boxes());

        return new GhostSnapshot(
                aircraft.getUUID(),
                aircraft.getId(),
                aircraft.getType(),
                position,
                aircraft.getDeltaMovement(),
                aircraft.getYRot(),
                aircraft.getXRot(),
                aircraft.getYRot(),
                new Quaternionf(aircraft.getAttitude()),
                setup.scale(),
                AircraftModel.geometryFile(id),
                AircraftModel.textureFile(id),
                this.billboard(id),
                bounds,
                true,
                animationTime,
                gameTime,
                payload);
    }

    /** The stores hanging on pylons: position in the aircraft's frame and which weapon. */
    private static List<Store> stores(AircraftEntity aircraft, AircraftDefinition stats) {
        List<AircraftDefinition.Hardpoint> hardpoints = stats.hardpoints();
        List<WeaponMounts.Mount> mounts = aircraft.getWeapons().mounts();

        if (hardpoints.isEmpty()) {
            return List.of();
        }

        List<Store> stores = new ArrayList<>();

        for (int slot = 0; slot < Math.min(hardpoints.size(), mounts.size()); slot++) {
            AircraftDefinition.Hardpoint hardpoint = hardpoints.get(slot);
            WeaponMounts.Mount mount = mounts.get(slot);

            if (hardpoint.isFixed() || mount.isEmpty()) {
                continue;
            }

            // A missile that has been launched is somewhere else now; an empty pod stays bolted on.
            if (mount.ammo() <= 0 && AircraftManager.weapon(mount.weapon()).leavesRail()) {
                continue;
            }

            stores.add(new Store(hardpoint.pos(), mount.weapon()));
        }

        return stores;
    }

    /** The aircraft's item icon, if it has one, as its billboard. */
    @Nullable
    private ResourceLocation billboard(ResourceLocation id) {
        return this.billboards.computeIfAbsent(id, key -> {
            ResourceLocation icon = ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID,
                    "textures/item/" + key.getPath() + "_item.png");

            return Minecraft.getInstance().getResourceManager().getResource(icon).isPresent() ? icon : null;
        });
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
            // No icon: the static model will do.
        }

        PoseStack poseStack = context.poseStack();
        poseStack.pushPose();
        // Into the aircraft's own frame. The half turn is the model's: geometry faces north, and the
        // aircraft is described from the nose down +Z.
        poseStack.mulPose(attitude(ghost, context.partialTick()));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));

        if (lod == GhostLOD.GHOST) {
            EntityGhostRenderer.drawModel(ghost, snapshot, context, GhostConfig.animation() ? POSER : null);
            drawStores(snapshot, context);
        } else {
            EntityGhostRenderer.drawModel(ghost, snapshot, context, null);
        }

        poseStack.popPose();
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

    /** What was hanging under the wings when the snapshot was taken. */
    private static void drawStores(GhostSnapshot snapshot, GhostRenderContext context) {
        Payload payload = (Payload) snapshot.payload();

        if (payload == null || payload.stores().isEmpty()) {
            return;
        }

        PoseStack poseStack = context.poseStack();
        MultiBufferSource buffers = context.buffers();
        GeoObjectRenderer<MountedStore> renderer = MountedStore.renderer();

        for (Store store : payload.stores()) {
            poseStack.pushPose();
            poseStack.translate(store.pos().x, store.pos().y, -store.pos().z);

            MountedStore mounted = MountedStore.of(store.weapon());
            ResourceLocation texture = renderer.getTextureLocation(mounted);
            // A ghost's stores are part of the ghost.
            RenderType type = GhostGeoRenderer.renderType(texture, context.ghostStyle());

            renderer.render(poseStack, mounted, buffers, type, buffers.getBuffer(type),
                    context.packedLight(), context.partialTick());
            poseStack.popPose();
        }
    }

    /** The simplified animation: the aircraft's surfaces and undercarriage as last seen. */
    private static final GhostAnimatable.GhostPoser POSER = (model, snapshot) -> {
        Payload payload = (Payload) snapshot.payload();

        if (payload != null) {
            AircraftModel.applyPose(model, payload.setup(), payload.pose());
        }
    };

    // ------------------------------------------------------------------
    // Distant Horizons boxes
    // ------------------------------------------------------------------

    /**
     * The aircraft's collision boxes, turned to its attitude and squared off: each part's box
     * rotated and then re-boxed along the world's axes. At the distance these are drawn at, that
     * is a silhouette, which is all that is wanted. An aircraft with no shape file is its bounding
     * box.
     */
    @Override
    public List<AABB> dhBoxes(EntityGhost ghost) {
        GhostSnapshot snapshot = ghost.current();
        Payload payload = (Payload) snapshot.payload();
        Quaternionf attitude = snapshot.attitude();

        if (payload == null || payload.shape().isEmpty() || attitude == null) {
            return List.of(snapshot.worldBounds());
        }

        List<AABB> boxes = new ArrayList<>(payload.shape().size());
        Vec3 origin = snapshot.position();

        for (AircraftShape.Box box : payload.shape()) {
            Vec3 centre = origin.add(Attitude.toWorld(attitude, box.offset()));
            Quaternionf turn = new Quaternionf(attitude).mul(box.orientation());
            Matrix3f rotation = new Matrix3f().rotation(turn);
            double hx = box.size().x * 0.5;
            double hy = box.size().y * 0.5;
            double hz = box.size().z * 0.5;
            // The axis-aligned extent of a turned box is the absolute rotation times its half-size.
            double ex = Math.abs(rotation.m00) * hx + Math.abs(rotation.m10) * hy + Math.abs(rotation.m20) * hz;
            double ey = Math.abs(rotation.m01) * hx + Math.abs(rotation.m11) * hy + Math.abs(rotation.m21) * hz;
            double ez = Math.abs(rotation.m02) * hx + Math.abs(rotation.m12) * hy + Math.abs(rotation.m22) * hz;

            boxes.add(new AABB(centre.x - ex, centre.y - ey, centre.z - ez,
                    centre.x + ex, centre.y + ey, centre.z + ez));
        }

        return boxes;
    }

    @Override
    public int dhBoxColour(EntityGhost ghost) {
        return DH_BOX_COLOUR;
    }

    // ------------------------------------------------------------------
    // What the snapshot carries
    // ------------------------------------------------------------------

    /** A store on a pylon: where, in the aircraft's frame, and what. */
    record Store(Vec3 pos, ResourceLocation weapon) {
    }

    /** Everything aircraft-specific a snapshot carries. */
    record Payload(ResourceLocation aircraftId, AircraftDefinition.ModelSetup setup, AircraftModel.Pose pose,
            List<Store> stores, List<AircraftShape.Box> shape) {
    }
}
