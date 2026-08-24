package com.ashvehicles.weapon;

import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.entity.VehicleProjectile;
import com.ashvehicles.entity.CountermeasureEntity;
import com.ashvehicles.sensor.Contact;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What the crew have the seeker on, and how far along it is.
 *
 * <p>Locking is the crew's work rather than the missile's: put the boresight on something, inside
 * the seeker's cone and within its reach, and hold it there. Wander off it and the seeker starts
 * again. That makes a missile shot something somebody has to work for, and it gives the target a way
 * out — break the line of sight or get outside the cone before it takes, and nothing is fired at you.
 *
 * <p><b>Whose boresight is not this class's business.</b> A pilot points the aeroplane; a launcher's
 * crew traverse the turret and never move the hull at all. Both come to the same question — how far
 * off the line the weapons look is the target — so what is asked is
 * {@link VehicleEntityBase#getAimDirection}, and everything below works the same either way.
 *
 * <p>All of this lives on the server, which is the only side that should be deciding what a weapon
 * is pointed at. The result is copied into the machine's synched data so that the instruments can
 * draw it; a client never chooses a target, it only sees the one the server chose.
 */
public final class TargetLock {
    /** How long a lost target is held before the seeker gives up on it, in ticks. */
    private static final int GRACE_TICKS = 10;
    /** How near a decoy has to be to what the seeker is looking at to hide it, in blocks. */
    private static final double SCREENED = 24.0;

    /**
     * How far out the seeker looks for itself every single tick, in blocks.
     *
     * <p>Everything within this is found the instant it arrives, which is what a dogfight needs.
     * Past it the sky is swept every {@link #SWEEP_TICKS} instead — see {@link #candidates}.
     */
    private static final double NEAR_REACH = 192.0;

    /**
     * How often the sky beyond {@link #NEAR_REACH} is swept for new candidates, in ticks.
     *
     * <p>Not every tick, and this is the whole of why: asking the level for everything inside a box
     * is paid for by the <em>size of the box</em> rather than by what is in it. The level walks one
     * strip of entity sections per sixteen blocks of it, so a seeker with a lock range of four and a
     * half kilometres — which is what an air-to-air missile's file asks for — walks better than five
     * hundred strips of the world, twenty times a second, for every armed aircraft in the air. That
     * one line was the most expensive thing on the server.
     *
     * <p>What the sweep does <em>not</em> throttle is the seeker itself. Every candidate it has
     * found is measured against the boresight afresh every tick, at its position that tick, so a
     * lock closes, holds and breaks exactly as it always did. Only the moment a distant aircraft is
     * first noticed moves, by at most half a second — against lock times measured in seconds, and
     * at a range where the pilot is holding the nose steady rather than snapping onto something.
     */
    private static final int SWEEP_TICKS = 10;

    private final VehicleEntityBase vehicle;
    @Nullable
    private Entity target;
    /** Ticks the target has been held in the cone. At the weapon's {@code lock_ticks} it is locked. */
    private int held;
    /** Ticks since the target was last seen, so a moment's wobble does not throw the lock away. */
    private int missing;
    private boolean locked;
    /**
     * What the last far sweep found, considered again every tick at wherever each has got to. Held
     * for at most {@link #SWEEP_TICKS} and re-tested against {@link #couldTarget} on every use, so
     * nothing dead or departed is ever fired at.
     */
    private List<Entity> distant = List.of();
    /** Ticks since the far sweep, and the reach it was made at: a wider seeker sweeps again at once. */
    private int sinceSweep = Integer.MAX_VALUE / 2;
    private double sweptTo;

    public TargetLock(VehicleEntityBase vehicle) {
        this.vehicle = vehicle;
    }

    @Nullable
    public Entity target() {
        return this.target;
    }

    /** True once the seeker has held the target long enough for a missile to take it. */
    public boolean isLocked() {
        return this.locked && this.target != null;
    }

    /**
     * True while the seeker is on something and working on it: the seconds between taking a target
     * and having it.
     *
     * <p>Which is the one stretch during which {@link #progress} changes without anything else
     * doing so, and therefore the one stretch a client has to be told about every tick. Neither the
     * target nor the lock changes while a lock is closing, so a machine that only reported those two
     * would send nothing at all from the moment the seeker took something until the moment it had
     * it — and the box on the glass, and the tone in the ear, would both sit still for the whole of
     * the wait and then jump. See {@code WeaponMounts.tick}.
     */
    public boolean isClosing() {
        return this.target != null && !this.locked;
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

        return !this.vehicle.level()
                .getEntitiesOfClass(CountermeasureEntity.class, box, decoy -> decoy.fools(guidance.seeker()))
                .isEmpty();
    }

    /** Forgets whatever it had. Used when the selected weapon cannot lock anything. */
    public void clear() {
        this.target = null;
        this.held = 0;
        this.missing = 0;
        this.locked = false;
        this.distant = List.of();
        this.sinceSweep = Integer.MAX_VALUE / 2;
    }

    /**
     * The most central thing in the seeker's cone: nearest to the boresight rather than nearest to
     * the machine, since where the crew are pointing is what they mean to shoot at.
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
        Vec3 bore = this.vehicle.getAimDirection(1.0F);
        Vec3 from = this.vehicle.position();
        double seeker = guidance.lockRange();
        double widest = Math.cos(Math.toRadians(guidance.lockAngle()));
        Aim aim = new Aim(from, bore, widest);

        // Close in, the seeker finds things for itself, and it finds everything: an aeroplane, a
        // player, anything alive that wandered into the cone. Every tick, because this is the range
        // at which things appear suddenly and a box this size costs almost nothing to ask about.
        AABB box = this.vehicle.getBoundingBox().inflate(Math.min(seeker, NEAR_REACH));

        for (Entity candidate : this.vehicle.level().getEntities(this.vehicle, box, this::couldTarget)) {
            aim.consider(candidate, reachAgainst(guidance, candidate, seeker));
        }

        // And further out, from the last sweep rather than from a fresh one. See SWEEP_TICKS.
        for (Entity candidate : this.candidates(seeker)) {
            if (this.couldTarget(candidate)) {
                aim.consider(candidate, reachAgainst(guidance, candidate, seeker));
            }
        }

        // Further out it takes what the radar hands it, and only that. Asked as a list of contacts
        // rather than as another sweep of the sky, which at these ranges matters: the radar's reach
        // is measured in kilometres and this runs every tick, so a box that size would be walked
        // twenty times a second for the sake of a dozen things the radar has already found.
        for (Contact contact : this.vehicle.getSensors().contacts()) {
            Entity candidate = this.vehicle.level().getEntity(contact.id());

            if (candidate != null && this.couldTarget(candidate)) {
                aim.consider(candidate, Double.MAX_VALUE);
            }
        }

        return aim.best;
    }

    /**
     * Everything the seeker could reach beyond {@link #NEAR_REACH}, swept for afresh when the last
     * sweep is stale and handed back unchanged in between.
     *
     * <p>The list is only ever a list of <em>candidates</em>. Which of them the seeker is actually
     * on is decided every tick, from their positions that tick, by the caller.
     */
    private List<Entity> candidates(double seeker) {
        if (seeker <= NEAR_REACH) {
            this.distant = List.of();

            return List.of();
        }

        // A wider seeker than the sweep was made for has not been swept for at all yet.
        if (++this.sinceSweep < SWEEP_TICKS && seeker <= this.sweptTo) {
            return this.distant;
        }

        this.sinceSweep = 0;
        this.sweptTo = seeker;

        AABB box = this.vehicle.getBoundingBox().inflate(seeker);
        List<Entity> found = this.vehicle.level().getEntities(this.vehicle, box, this::couldTarget);

        this.distant = found.isEmpty() ? List.of() : found;

        return this.distant;
    }

    /**
     * How far this seeker manages against that particular target.
     *
     * <p>Each head has the target's own signature against it, and they are not looking for the same
     * thing. A seeker homing on a radar return is up against the cross-section, the same as the
     * radar that found it; one homing on heat is up against the exhaust, and shaping an aeroplane
     * to scatter radar does nothing whatever about how hot that is. Which is the trade a stealth
     * aeroplane makes — very hard to find at range, no harder to hit once something with a
     * heat-seeking head is close enough to look at it.
     *
     * <p>What the pilot can still do about the second of those is fly on military power. An
     * afterburner is worth a great deal of thrust and a great deal of heat, and the heat is visible
     * from a long way further off than the airframe alone; see
     * {@link AircraftEntity#infraredSignature}.
     *
     * <p>Neither figure is ever more than one, and that is not a taste in numbers: nothing is
     * <em>considered</em> here that the sweep above did not find, and the sweep is a box the size of
     * the seeker's own range. A reach past that would be a reach into sky nobody has looked at.
     */
    private static double reachAgainst(WeaponDefinition.Guidance guidance, Entity candidate, double seeker) {
        return guidance.seeker() == WeaponDefinition.Guidance.Seeker.RADAR
                ? seeker * AircraftEntity.visibility(candidate)
                : seeker * AircraftEntity.heatVisibility(candidate);
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
     * What a seeker will look at: something alive, or another machine. Not the machine doing the
     * looking, nor anyone riding it, and not the mod's own projectiles — a missile chasing another
     * missile is not what anybody asked for.
     */
    private boolean couldTarget(Entity candidate) {
        if (candidate == this.vehicle || candidate instanceof VehicleProjectile
                || WeaponMounts.isPartOf(this.vehicle, candidate)) {
            return false;
        }

        if (!candidate.isAlive() || candidate.isSpectator()) {
            return false;
        }

        // Somebody sitting in another machine is not a target of their own. A seeker that takes the
        // crew instead of the machine is pointed at the same patch of sky and says the wrong thing
        // about it: the scope would show the machine as a plain contact while the missile chased the
        // people inside it, and letting go of the stick would leave the missile chasing a falling body.
        if (candidate.getVehicle() instanceof VehicleEntityBase) {
            return false;
        }

        // A burnt-out airframe is not worth a missile. Left targetable it is the easiest thing in the
        // sky to lock -- it does not manoeuvre, does not dispense flares and never goes away -- and a
        // seeker would settle on the aeroplane the pilot has already shot down instead of the one
        // shooting at them.
        if (candidate instanceof VehicleEntityBase machine) {
            return !machine.isWrecked();
        }

        return candidate instanceof LivingEntity;
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

        this.target = this.vehicle.level().getEntity(tag.getInt("Target"));
        this.locked = tag.getBoolean("Locked");
        this.held = tag.getInt("Held");
        this.missing = 0;
    }
}
