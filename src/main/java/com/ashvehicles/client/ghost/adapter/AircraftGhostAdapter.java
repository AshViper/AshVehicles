package com.ashvehicles.client.ghost.adapter;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.client.ghost.EntityGhost;
import com.ashvehicles.client.ghost.EntityGhostRenderer;
import com.ashvehicles.client.ghost.GhostAdapter;
import com.ashvehicles.client.ghost.GhostConfig;
import com.ashvehicles.client.ghost.GhostLOD;
import com.ashvehicles.client.ghost.GhostRenderContext;
import com.ashvehicles.client.ghost.GhostSnapshot;
import com.ashvehicles.client.ghost.geo.GhostAnimatable;
import com.ashvehicles.client.ghost.geo.GhostGeoRenderer;
import com.ashvehicles.client.item.VehicleIcons;
import com.ashvehicles.client.model.AircraftAnimations;
import com.ashvehicles.client.model.AircraftModel;
import com.ashvehicles.client.renderer.MountedStore;
import com.ashvehicles.client.renderer.VehicleRenderer;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.weapon.WeaponMounts;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.renderer.GeoObjectRenderer;

import org.joml.Quaternionf;

/**
 * Aircraft as ghosts.
 *
 * <p>The snapshot carries the aircraft's attitude, its model and animation files, the pose of its
 * moving parts and what is hanging under its wings; the ghost is drawn from those and nothing
 * else. What that comes to is the aeroplane as it is: the model, armed, with its surfaces at the
 * rates it last turned at, its rotors wound on to this moment of this tick, and its undercarriage
 * playing the cycle out of its own animation file — the same controller the aircraft registers for
 * itself, given the same two figures. Only at the billboard distance, when that is switched on, is
 * it something else: the aircraft's item icon, flat and facing the camera.
 *
 * <p>Aircraft are sent to every client wherever they are (see {@code EntityTrackingMixin}), so one
 * the client stops receiving is one that is gone; its ghost goes with it rather than lingering.
 */
public final class AircraftGhostAdapter implements GhostAdapter<AircraftEntity> {
    @Override
    public boolean keepAfterLeave(AircraftEntity entity) {
        return false;
    }

    // ------------------------------------------------------------------
    // Snapshot
    // ------------------------------------------------------------------

    @Override
    public GhostSnapshot snapshot(AircraftEntity aircraft, @Nullable GhostSnapshot previous, long gameTime) {
        ResourceLocation id = aircraft.getAircraftId();
        AircraftDefinition stats = aircraft.getStats();
        VehicleChassis.Model setup = stats.model();
        Vec3 position = aircraft.position();
        // The box the aircraft is drawn within, not the one it collides with: the plain box covers
        // the fuselage and nothing else, and a ghost culled against it is a fifteen-metre aeroplane
        // blinking out while most of it is still on the screen. It is the box the game's own
        // renderer culls the aircraft against on the near side of the hand-over, so nothing about
        // when a machine leaves the screen changes as it crosses.
        AABB bounds = aircraft.getBoundingBoxForCulling().move(position.reverse());
        Payload payload = new Payload(id, setup, AircraftModel.Pose.of(aircraft, 1.0F), stores(aircraft, stats),
                aircraft.isGearDown(), aircraft.getGearCycleTicks());

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
                aircraft.isWrecked() ? VehicleRenderer.CHARRED : 1.0F,
                AircraftModel.geometryFile(id),
                AircraftModel.textureFile(id),
                AircraftModel.animationFile(id),
                this.billboard(id),
                bounds,
                true,
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
            if (mount.ammo() <= 0 && Definitions.weapon(mount.weapon()).leavesRail()) {
                continue;
            }

            stores.add(new Store(hardpoint.pos(), mount.weapon()));
        }

        return stores;
    }

    /**
     * The picture the aircraft's own item is drawn as, which is a picture of the aircraft: the right thing
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
        // Into the aircraft's own frame. The half turn is the model's: geometry faces north, and the
        // aircraft is described from the nose down +Z.
        poseStack.mulPose(attitude(ghost, context.partialTick()));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));

        EntityGhostRenderer.drawModel(ghost, snapshot, context, GhostConfig.animation() ? POSER : null);
        drawStores(snapshot, context);
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
        Payload payload = payload(snapshot);

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

    // ------------------------------------------------------------------
    // Moving as the aeroplane moves
    // ------------------------------------------------------------------

    /**
     * Everything about an aircraft that follows the flight from moment to moment, set from the
     * last two snapshots the way the aircraft's own model sets it from the aircraft: the surfaces
     * at the rates it turned at, the gear and flaps and nozzle part way to wherever they are going,
     * the rotors wound on from the end of the last tick to this moment of this one.
     *
     * <p>Between two snapshots rather than from the newest, because that is what the game draws for
     * the aeroplane standing next to this ghost: what is on the screen at any moment is the tick
     * before last blended into the last one. A ghost posed from the newest snapshot alone would run
     * a tick ahead of it and jump once a tick besides.
     */
    private static final GhostAnimatable.GhostPoser POSER = (model, ghost, partialTick) -> {
        Payload now = payload(ghost.current());

        if (now == null) {
            return;
        }

        Payload then = payload(ghost.previous());
        AircraftModel.Pose pose = then == null
                ? now.pose()
                : AircraftModel.Pose.between(then.pose(), now.pose(), partialTick);

        AircraftModel.applyPose(model, now.setup(), pose);
    };

    // ------------------------------------------------------------------
    // The undercarriage
    // ------------------------------------------------------------------

    /**
     * The gear cycle, registered for a ghost exactly as {@code AircraftEntity} registers it for
     * itself: the same two halves out of the same animation file, the same blend between them, and
     * the same figure deciding how fast they play. A ghost's legs therefore come out in the order
     * the file says they come out in, doors and all, rather than being swung approximately from
     * code — which is what an aircraft with no cycle in its file gets, from the poser above.
     */
    @Override
    public void registerGhostControllers(AnimatableManager.ControllerRegistrar controllers,
            GhostAnimatable animatable) {
        controllers.add(new AnimationController<>(animatable, "gear", AircraftAnimations.TRANSITION_TICKS,
                AircraftGhostAdapter::gearCycle).setAnimationSpeedHandler(AircraftGhostAdapter::gearSpeed));
    }

    /** Which half is playing: the one that ends with the gear where the pilot has asked for it. */
    private static PlayState gearCycle(AnimationState<GhostAnimatable> state) {
        Payload payload = payload(state.getAnimatable().snapshot());

        if (payload == null || payload.pose().sweepGear() || !GhostConfig.animation()) {
            return PlayState.STOP;
        }

        return state.setAndContinue(AircraftAnimations.cycleFor(payload.gearDown()));
    }

    /**
     * How fast, from the aircraft's own cycle time, and held at the end of the cycle when the gear
     * is already where it belongs — so a ghost that comes into view with its wheels down is sitting
     * on them rather than lowering them again for the benefit of whoever just looked.
     */
    private static double gearSpeed(GhostAnimatable animatable) {
        Payload payload = payload(animatable.snapshot());

        if (payload == null) {
            return 1.0;
        }

        boolean settled = payload.pose().gear() == (payload.gearDown() ? 1.0F : 0.0F);

        return AircraftAnimations.gearSpeed(animatable.snapshot().animation(), payload.gearDown(),
                payload.gearCycleTicks(), settled);
    }

    // ------------------------------------------------------------------
    // What the snapshot carries
    // ------------------------------------------------------------------

    @Nullable
    private static Payload payload(GhostSnapshot snapshot) {
        return (Payload) snapshot.payload();
    }

    /** A store on a pylon: where, in the aircraft's frame, and what. */
    record Store(Vec3 pos, ResourceLocation weapon) {
    }

    /**
     * Everything aircraft-specific a snapshot carries.
     *
     * @param gearDown where the pilot has asked for the undercarriage to be, which is what decides
     *        the half of the cycle a ghost plays
     * @param gearCycleTicks how long this aircraft takes to raise or lower it
     */
    record Payload(ResourceLocation aircraftId, VehicleChassis.Model setup, AircraftModel.Pose pose,
            List<Store> stores, boolean gearDown, int gearCycleTicks) {
    }
}
