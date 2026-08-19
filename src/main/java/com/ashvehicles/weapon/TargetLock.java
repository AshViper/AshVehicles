package com.ashvehicles.weapon;

import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.AircraftProjectile;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What the pilot has the seeker on, and how far along it is.
 *
 * <p>Locking is the pilot's work rather than the missile's: put the nose on something, inside the
 * seeker's cone and within its reach, and hold it there. Wander off it and the seeker starts again.
 * That makes a missile shot something a pilot has to fly for, and it gives the target a way out —
 * break the line of sight or get outside the cone before it takes, and nothing is fired at you.
 *
 * <p>All of this lives on the server, which is the only side that should be deciding what a weapon
 * is pointed at. The result is copied into the aircraft's synched data so that the instruments can
 * draw it; a client never chooses a target, it only sees the one the server chose.
 */
public final class TargetLock {
    /** How long a lost target is held before the seeker gives up on it, in ticks. */
    private static final int GRACE_TICKS = 10;

    private final AircraftEntity aircraft;
    @Nullable
    private Entity target;
    /** Ticks the target has been held in the cone. At the weapon's {@code lock_ticks} it is locked. */
    private int held;
    /** Ticks since the target was last seen, so a moment's wobble does not throw the lock away. */
    private int missing;
    private boolean locked;

    public TargetLock(AircraftEntity aircraft) {
        this.aircraft = aircraft;
    }

    @Nullable
    public Entity target() {
        return this.target;
    }

    /** True once the seeker has held the target long enough for a missile to take it. */
    public boolean isLocked() {
        return this.locked && this.target != null;
    }

    /** How far along the lock is, from 0 to 1. What the instruments draw while it is closing. */
    public float progress(WeaponDefinition.Guidance guidance) {
        if (this.target == null) {
            return 0.0F;
        }

        return this.locked ? 1.0F : Math.min(1.0F, this.held / (float) Math.max(guidance.lockTicks(), 1));
    }

    /**
     * One tick of looking. Keeps the current target if it is still there and still ahead, otherwise
     * finds the best thing in the cone and starts on that.
     *
     * @param guidance the seeker of the weapon currently selected, or null if it has none
     * @return true if anything changed that the clients ought to hear about
     */
    public boolean tick(@Nullable WeaponDefinition.Guidance guidance) {
        Entity was = this.target;
        boolean wasLocked = this.locked;

        if (guidance == null) {
            this.clear();

            return was != null || wasLocked;
        }

        Entity best = this.bestCandidate(guidance);

        if (best != null && best == this.target) {
            // Still on it: the lock closes.
            this.missing = 0;
            this.held++;
            this.locked = this.held >= guidance.lockTicks();
        } else if (best != null && this.target == null) {
            this.target = best;
            this.held = 1;
            this.missing = 0;
            this.locked = guidance.lockTicks() <= 1;
        } else if (best != null) {
            // Something better, or the old one is gone: start again on the new one.
            this.target = best;
            this.held = 1;
            this.missing = 0;
            this.locked = false;
        } else if (this.target != null && ++this.missing > GRACE_TICKS) {
            this.clear();
        }

        return this.target != was || this.locked != wasLocked;
    }

    /** Forgets whatever it had. Used when the selected weapon cannot lock anything. */
    public void clear() {
        this.target = null;
        this.held = 0;
        this.missing = 0;
        this.locked = false;
    }

    /**
     * The most central thing in the seeker's cone: nearest to the boresight rather than nearest to
     * the aircraft, since where the pilot is pointing is what they mean to shoot at.
     */
    @Nullable
    private Entity bestCandidate(WeaponDefinition.Guidance guidance) {
        Vec3 nose = this.aircraft.getNoseVector();
        Vec3 from = this.aircraft.position();
        double reach = guidance.lockRange();
        double widest = Math.cos(Math.toRadians(guidance.lockAngle()));

        AABB box = this.aircraft.getBoundingBox().inflate(reach);
        List<Entity> nearby = this.aircraft.level().getEntities(this.aircraft, box, this::couldTarget);

        Entity best = null;
        double bestAlignment = widest;

        for (Entity candidate : nearby) {
            Vec3 middle = candidate.position().add(0.0, candidate.getBbHeight() * 0.5, 0.0);
            Vec3 gap = middle.subtract(from);
            double distance = gap.length();

            if (distance > reach || distance < 1.0E-3) {
                continue;
            }

            double alignment = gap.scale(1.0 / distance).dot(nose);

            if (alignment > bestAlignment) {
                bestAlignment = alignment;
                best = candidate;
            }
        }

        return best;
    }

    /**
     * What a seeker will look at: something alive, or another aircraft. Not the aircraft doing the
     * looking, nor anyone riding it, and not the mod's own projectiles — a missile chasing another
     * missile is not what anybody asked for.
     */
    private boolean couldTarget(Entity candidate) {
        if (candidate == this.aircraft || candidate instanceof AircraftProjectile
                || WeaponMounts.isPartOf(this.aircraft, candidate)) {
            return false;
        }

        if (!candidate.isAlive() || candidate.isSpectator()) {
            return false;
        }

        return candidate instanceof LivingEntity || candidate instanceof AircraftEntity;
    }

    /** What the instruments need: which entity, and whether the seeker has it yet. */
    public void save(CompoundTag tag) {
        if (this.target != null) {
            tag.putInt("Target", this.target.getId());
            tag.putBoolean("Locked", this.locked);
            tag.putInt("Held", this.held);
        }
    }

    /**
     * Reads back what the server sent. Only ever used on a client, where the entity is looked up by
     * the id that came over the wire.
     */
    public void load(CompoundTag tag) {
        if (!tag.contains("Target")) {
            this.clear();

            return;
        }

        this.target = this.aircraft.level().getEntity(tag.getInt("Target"));
        this.locked = tag.getBoolean("Locked");
        this.held = tag.getInt("Held");
        this.missing = 0;
    }
}
