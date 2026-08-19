package com.ashvehicles.client;

import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.aircraft.AircraftManager;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.weapon.WeaponDefinition;
import com.ashvehicles.weapon.WeaponMounts;

import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Where a bomb released this instant would land.
 *
 * <p>A free-fall bomb cannot be aimed at anything. It leaves with whatever speed the aeroplane had
 * and from there gravity has it, so where it lands was decided at the moment of release by how fast,
 * how high and how level the aircraft was — and by the time the pilot can see the answer it is far
 * too late to change it. Every aeroplane that ever carried bombs therefore had something to tell the
 * pilot where they would go. This is that: the trajectory flown forward, tick by tick, until it runs
 * into the world.
 *
 * <p>The flight is worked out with the same arithmetic the bomb itself will use — position first,
 * then gravity, in that order — so what the pilot is shown is what the bomb will do, rather than an
 * approximation of it that drifts at the edges.
 *
 * <p>Worked out once a tick rather than once a frame. It costs a walk through the world and the
 * answer does not meaningfully change in a sixtieth of a second.
 */
public final class BombSight {
    /** Longest flight worth following, in ticks. A bomb still falling after this is over water. */
    private static final int MAX_FLIGHT = 400;

    private static AircraftEntity cachedFor;
    private static long cachedAt = Long.MIN_VALUE;
    @Nullable
    private static Vec3 cachedImpact;

    private BombSight() {
    }

    /**
     * Where a bomb dropped now would hit, or null if it would not hit anything the client knows
     * about before it gave up.
     */
    @Nullable
    public static Vec3 impact(AircraftEntity aircraft, WeaponDefinition weapon) {
        long now = aircraft.level().getGameTime();

        if (aircraft != cachedFor || now != cachedAt) {
            cachedFor = aircraft;
            cachedAt = now;
            cachedImpact = trace(aircraft, weapon);
        }

        return cachedImpact;
    }

    @Nullable
    private static Vec3 trace(AircraftEntity aircraft, WeaponDefinition weapon) {
        WeaponDefinition.Projectile round = weapon.projectile();
        Vec3 up = aircraft.getLiftVector();

        // The same release the aircraft will actually make: from the rack, with the aeroplane's
        // speed, pushed down off the belly by its own figure.
        Vec3 position = aircraft.toWorld(rackOffset(aircraft, weapon), 1.0F);
        Vec3 velocity = aircraft.getVelocity().add(up.scale(-round.speed()));
        int flight = Math.min(MAX_FLIGHT, round.lifetime());

        for (int tick = 0; tick < flight; tick++) {
            Vec3 next = position.add(velocity);
            HitResult hit = aircraft.level().clip(new ClipContext(position, next,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, aircraft));

            if (hit.getType() != HitResult.Type.MISS) {
                return hit.getLocation();
            }

            position = next;
            velocity = velocity.subtract(0.0, round.gravity(), 0.0);
        }

        return null;
    }

    /**
     * Where on the aircraft the bomb will leave from: the first pylon carrying this weapon, so the
     * mark moves with the load rather than being drawn from the middle of the aeroplane.
     */
    private static Vec3 rackOffset(AircraftEntity aircraft, WeaponDefinition weapon) {
        List<AircraftDefinition.Hardpoint> hardpoints = aircraft.getStats().hardpoints();
        List<WeaponMounts.Mount> mounts = aircraft.getWeapons().mounts();

        for (int slot = 0; slot < Math.min(hardpoints.size(), mounts.size()); slot++) {
            WeaponMounts.Mount mount = mounts.get(slot);

            if (!mount.isEmpty() && mount.ammo() > 0 && weapon.equals(AircraftManager.weapon(mount.weapon()))) {
                return hardpoints.get(slot).pos();
            }
        }

        return Vec3.ZERO;
    }
}
