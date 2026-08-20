package com.ashvehicles.weapon;

import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.AircraftProjectile;
import com.ashvehicles.entity.CountermeasureEntity;
import com.ashvehicles.sensor.Contact;

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
    /** How near a decoy has to be to what the seeker is looking at to hide it, in blocks. */
    private static final double SCREENED = 24.0;

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

        // Lost in the decoys: treated exactly as though nothing were there, so the grace period runs
        // and the lock falls away rather than snapping back the moment the cloud thins.
        if (best != null && this.screened(best, guidance)) {
            best = null;
        }

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

    /**
     * Whether the seeker has lost the target in whatever the target has just thrown out.
     *
     * <p>Countermeasures work before launch as well as after it, and this is the half that decides
     * whether a shot can be taken at all: a pilot who sees the lock warning and pulls the right
     * handle denies the shot rather than merely surviving it. The wrong handle denies nothing —
     * a flare is invisible to a radar seeker and a cloud of foil is invisible to a heat-seeking one.
     *
     * <p>Only what is near the <em>target</em> counts. Decoys hanging behind somebody else's
     * aeroplane on the far side of the sky are not between this seeker and what it is looking at.
     */
    private boolean screened(Entity target, WeaponDefinition.Guidance guidance) {
        AABB box = target.getBoundingBox().inflate(SCREENED);

        return !this.aircraft.level()
                .getEntitiesOfClass(CountermeasureEntity.class, box, decoy -> decoy.fools(guidance.seeker()))
                .isEmpty();
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
     *
     * <p><b>How far it can reach is two figures, not one.</b> The weapon's own {@code lock_range} is
     * what its seeker manages unaided, which for a heat-seeking missile is a few hundred blocks and
     * is the whole story on an aeroplane with no radar. An aeroplane <em>with</em> one can do better:
     * anything the radar is holding can be taken at the range the radar holds it, because that is
     * what a radar is for — the seeker is being handed a track rather than finding one.
     *
     * <p>Which is what makes the two instruments agree. Without it a pilot watches a contact on the
     * scope at six hundred blocks, points the nose squarely at it, and is told the seeker can see
     * nothing — the aircraft knowing perfectly well where something is and refusing to shoot at it.
     *
     * <p>None of this has anything to do with what is drawn. Everything here runs on the server,
     * where an aircraft in the air is loaded wherever it is: it holds its own chunk open, so it is
     * as findable a thousand blocks away as it is overhead. A client's view distance decides only
     * whether the pilot can <em>see</em> what the seeker has taken, and an aircraft is drawn as a
     * ghost long after the ordinary renderer has given up on it.
     */
    @Nullable
    private Entity bestCandidate(WeaponDefinition.Guidance guidance) {
        Vec3 nose = this.aircraft.getNoseVector();
        Vec3 from = this.aircraft.position();
        double seeker = guidance.lockRange();
        double widest = Math.cos(Math.toRadians(guidance.lockAngle()));
        Aim aim = new Aim(from, nose, widest);

        // Close in, the seeker finds things for itself, and it finds everything: an aeroplane, a
        // player, anything alive that wandered into the cone.
        AABB box = this.aircraft.getBoundingBox().inflate(seeker);

        for (Entity candidate : this.aircraft.level().getEntities(this.aircraft, box, this::couldTarget)) {
            aim.consider(candidate, seeker);
        }

        // Further out it takes what the radar hands it, and only that. Asked as a list of contacts
        // rather than as another sweep of the sky, which at these ranges matters: the radar's reach
        // is measured in kilometres and this runs every tick, so a box that size would be walked
        // twenty times a second for the sake of a dozen things the radar has already found.
        for (Contact contact : this.aircraft.getSensors().contacts()) {
            Entity candidate = this.aircraft.level().getEntity(contact.id());

            if (candidate != null && this.couldTarget(candidate)) {
                aim.consider(candidate, Double.MAX_VALUE);
            }
        }

        return aim.best;
    }

    /** Keeps whichever candidate is nearest the boresight as they are offered one at a time. */
    private static final class Aim {
        private final Vec3 from;
        private final Vec3 nose;
        private double bestAlignment;
        @Nullable
        private Entity best;

        private Aim(Vec3 from, Vec3 nose, double widest) {
            this.from = from;
            this.nose = nose;
            this.bestAlignment = widest;
        }

        private void consider(Entity candidate, double reach) {
            Vec3 middle = candidate.position().add(0.0, candidate.getBbHeight() * 0.5, 0.0);
            Vec3 gap = middle.subtract(this.from);
            double distance = gap.length();

            if (distance > reach || distance < 1.0E-3) {
                return;
            }

            double alignment = gap.scale(1.0 / distance).dot(this.nose);

            if (alignment > this.bestAlignment) {
                this.bestAlignment = alignment;
                this.best = candidate;
            }
        }
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

        // Somebody sitting in another aeroplane is not a target of their own. A seeker that takes the
        // pilot instead of the aircraft is pointed at the same patch of sky and says the wrong thing
        // about it: the scope would show the aircraft as a plain contact while the missile chased the
        // man inside it, and letting go of the stick would leave the missile chasing a falling body.
        if (candidate.getVehicle() instanceof AircraftEntity) {
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
