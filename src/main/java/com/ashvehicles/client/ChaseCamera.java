package com.ashvehicles.client;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Parks the third-person camera where the machine's data pack file asks for it, and keeps the whole
 * machine in frame while it does.
 *
 * <p>Vanilla only knows how to sit four blocks behind the rider's eyes, which for a fifteen-block
 * aeroplane is somewhere inside the fuselage and for a tank is close enough to see the engine deck
 * and nothing else. The camera is placed outright instead, at {@code camera.pos} from the middle of
 * the machine.
 *
 * <p>That offset is measured along the <em>viewing</em> axes, not the machine's: x to the right of
 * the view, y straight up, z along the line of sight with negative meaning behind. Hanging it off
 * the machine's own heading instead is the obvious thing to do and it is wrong, because the mouse
 * aims by looking: pull the nose up, or lay the turret round, and the view goes with it while a
 * heading-locked camera stays where the hull is pointing — dropping what is being aimed at out of
 * frame exactly when the crew most want to see it. Measured along the view, the machine sits still
 * in frame whatever it is doing.
 *
 * <p>Shared by everything with a chase view. The two vehicle camera handlers differ in what they do
 * with the angles, not in where the camera ends up: whatever they have already done to the view —
 * an aeroplane's nothing, a tank's few degrees of tip downwards — is in the camera's own rotation by
 * the time this reads it, so the offset is measured along the axes the crew are actually looking
 * down.
 */
public final class ChaseCamera {
    /** Kept between the camera and whatever block it would otherwise be pressed against. */
    private static final double BLOCK_CLEARANCE = 0.25;

    /**
     * Puts the camera at {@code offset} from the middle of the vehicle. Called from
     * {@link com.ashvehicles.mixin.CameraMixin} once vanilla has finished placing the camera, which
     * is the first moment a new position will actually stick.
     */
    public static void place(Camera camera, Entity vehicle, Vec3 offset, float partialTick) {
        // Following the line of sight rather than the machine's heading is what keeps it centred
        // through climbs, dives, rolls and traverses; it also gets the reversed third-person view
        // right for free, since there the line of sight is flipped and the camera therefore lands in
        // front of the machine looking back at it.
        Vec3 sight = Vec3.directionFromRotation(camera.getXRot(), camera.getYRot());
        Vec3 right = sight.cross(new Vec3(0.0, 1.0, 0.0));
        right = right.lengthSqr() < 1.0E-6 ? new Vec3(1.0, 0.0, 0.0) : right.normalize();

        // Middle of the machine rather than its origin, which sits down at the wheels.
        Vec3 middle = vehicle.getPosition(partialTick).add(0.0, vehicle.getBbHeight() * 0.5, 0.0);
        Vec3 target = middle
                .add(right.scale(offset.x))
                .add(0.0, offset.y, 0.0)
                .add(sight.scale(offset.z));

        camera.setPosition(clipToBlocks(vehicle, middle, target));
    }

    /**
     * Pulls the camera in if the line from the machine to it passes through the scenery. Vanilla
     * does the same for its own four blocks; without it the camera happily ends up inside a hill,
     * and the further back the camera is asked to sit the more of the time it would be.
     */
    private static Vec3 clipToBlocks(Entity vehicle, Vec3 anchor, Vec3 target) {
        Level level = vehicle.level();
        HitResult hit = level.clip(
                new ClipContext(anchor, target, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, vehicle));

        if (hit.getType() == HitResult.Type.MISS) {
            return target;
        }

        Vec3 blocked = hit.getLocation();
        Vec3 back = blocked.subtract(anchor);

        return back.lengthSqr() <= BLOCK_CLEARANCE * BLOCK_CLEARANCE
                ? anchor
                : blocked.subtract(back.normalize().scale(BLOCK_CLEARANCE));
    }

    private ChaseCamera() {
    }
}
