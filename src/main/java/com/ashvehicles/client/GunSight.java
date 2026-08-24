package com.ashvehicles.client;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.CountermeasureEntity;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.entity.VehicleProjectile;
import com.ashvehicles.weapon.WeaponDefinition;
import com.ashvehicles.weapon.WeaponMounts;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Where the gun is really pointing, and what it would take to hit something with it.
 *
 * <p>A gun is aimed by pointing the thing it is bolted to — an aeroplane by flying it, a turret by
 * traversing it — and the instruments used to say so with a mark on the boresight and nothing else.
 * That is nearly right from inside and wrong from everywhere else. The chase camera sits a dozen
 * blocks back and several up, and a direction drawn from <em>there</em> is not the line the rounds
 * leave along: laid on a hillside, the boresight from up there points at ground well beyond where
 * the rounds strike, by as much as the camera is above the muzzle. Rounds also fall, and a rocket
 * leaves slowly enough that the target has moved by the time it arrives. None of that is on a
 * boresight.
 *
 * <p><b>Which machine it is bolted to makes no difference here.</b> What a sight needs is where the
 * rounds leave and which way they leave along, and that is the whole of {@link Bore}: an aeroplane
 * answers with a pylon and its nose, a turret with its muzzle and its own laid line. Everything
 * below — the flight, the drop, the lead — is the same arithmetic either way, which is what lets an
 * anti-aircraft mounting borrow the sight an aeroplane already had.
 *
 * <p>So this is the round's flight worked out rather than assumed. The round is flown forward from
 * the muzzle with the same arithmetic the projectile itself uses — the motor, the speed the rail
 * carried out, the drop — until it runs into the world, and two things are read off the result:
 *
 * <ul>
 * <li><b>The pipper</b>: where the rounds would land, as a point in the world, on whatever they
 * would land on. Drawn where it is rather than along a direction, so it is right from any camera.
 * Over open sky, with nothing to land on, it sits at a reference range along the line.</li>
 * <li><b>The lead</b>: for whatever is nearest the boresight and within reach, where the nose would
 * have to be put for a round fired now to arrive where the target will be by then. Time of flight
 * from the flown path, the target's own speed carried forward by it, and the drop taken back off
 * so that the round's fall is already paid for.</li>
 * </ul>
 *
 * <p>Worked out once a tick, like the bomb sight, because it costs a walk through the world and a
 * sweep for targets, and neither answer changes in a sixtieth of a second. What it hands back is
 * not a pair of screen positions but what is needed to <em>rebuild</em> them every frame: a range
 * along the nose rather than a point, so that the mark follows the nose as smoothly as the nose
 * moves, and only how far out it sits is a tick old — which is invisible at any range worth firing
 * at. See {@link Solution}.
 */
public final class GunSight {
    /** Longest flight worth following, in ticks. Past this the round is over the horizon. */
    private static final int MAX_FLIGHT = 400;
    /**
     * How far out the world is asked what the round would hit, in blocks.
     *
     * <p>A walk through every block the round crosses is the expensive part of this, and a cannon
     * round is given a range of nine hundred. Beyond the render distance the client has nothing to
     * hit anyway — chunks that are not loaded are air — so asking further than this is paying to be
     * told nothing.
     */
    private static final double TRACE_REACH = 512.0;
    /**
     * Where the pipper sits when there is nothing for the round to land on, in blocks along the
     * line. A gunsight in life is harmonised at some such range; this is that.
     */
    private static final double REFERENCE_RANGE = 300.0;
    /**
     * How far out something is worth offering a lead on, in blocks.
     *
     * <p>Well inside what the round can fly. Past a few hundred blocks the time of flight is long
     * enough that nothing flying a target can be relied on to keep doing it, and the mark would be
     * a promise the sight cannot keep.
     */
    private static final double TARGET_REACH = 600.0;
    /**
     * How far off the nose something is taken as a target, and how far off it is kept once taken,
     * in degrees.
     *
     * <p>Generous, and the second wider than the first, on purpose. The lead on a crossing target
     * is a good many degrees, and the pilot flying to the lead mark has the target itself well off
     * the boresight — a cone tight enough to hold only what is under the nose would drop the target
     * at exactly the moment the pilot was about to fire at it.
     */
    private static final float SEARCH_CONE = 25.0F;
    private static final float HOLD_CONE = 40.0F;
    /**
     * How much nearer the boresight something else has to be before the sight lets go of what it
     * has for it, in degrees. Without a margin two targets near the middle would be swapped
     * between every few ticks, and the lead mark with them.
     */
    private static final float SWITCH_MARGIN = 5.0F;
    /** How close the nose has to be to the lead, in degrees, before the mark says to fire. */
    private static final float ON_TARGET = 1.5F;
    /**
     * How much of a target's speed is taken fresh each tick.
     *
     * <p>The speed of anything the client is not itself moving is read off how far it was drawn
     * moving last tick, and that is smoothed and stepped by the client's own interpolation. Taken
     * raw, the lead mark jitters; eased like this it settles within a few ticks and stays put.
     */
    private static final float VELOCITY_SMOOTHING = 0.5F;
    /** Passes of the lead calculation. The time of flight depends on the answer; this converges. */
    private static final int LEAD_PASSES = 4;

    /**
     * How often the sky is swept for something new to lead, in ticks.
     *
     * <p>Not every tick, and for the reason nothing in this mod asks the level for a large box every
     * tick: the cost of the question is the <em>size of the box</em> rather than what is in it. The
     * level walks the chunk positions the box covers, and a reach of six hundred blocks is a box
     * twelve hundred across -- five and a half thousand chunk look-ups, twenty times a second, for
     * the sake of finding a target a quarter of a second sooner.
     *
     * <p>What this does <em>not</em> throttle is the lead itself. Whatever is already being led is
     * measured afresh every tick, at where it has got to that tick, so the mark tracks the target
     * exactly as it always did and the shot is no less good. Only the moment something else is first
     * noticed moves, by at most a quarter of a second -- against a target that has to be held in the
     * sight for rather longer than that before anybody could fire at it.
     */
    private static final int SWEEP_TICKS = 5;

    /**
     * Where the rounds leave from and which way they leave along, asked afresh for each frame.
     *
     * <p>Two questions rather than a pair of stored vectors, because the answer moves: the mark is
     * rebuilt every frame from the line the weapon is laid on <em>now</em>, so it follows the nose
     * or the barrel as smoothly as they move, and only how far out it sits is a tick old.
     */
    public interface Bore {
        /** Where the rounds leave, in the world, at a moment between two ticks. */
        Vec3 muzzle(float partialTick);

        /** Which way they leave along, as a unit vector, at the same moment. */
        Vec3 direction(float partialTick);
    }

    /**
     * What the instruments need to draw the sight this tick.
     *
     * @param bore where the rounds leave and along what, for rebuilding the marks each frame
     * @param pipperRange how far along the bore the pipper sits, in blocks
     * @param pipperDrop how far off that line the round is by then, in the world. Tiny for a gun,
     *                   worth having for a rocket past its burn
     * @param struck whether the pipper is on something the round would hit, rather than in open sky
     * @param target what the lead is offered for, or null for nothing in reach
     * @param leadOffset where the lead mark sits, as an offset from the middle of the target.
     *                   The target moves between ticks; the offset is added to wherever it is drawn
     * @param targetRange how far away the target is now, in blocks
     * @param inRange whether the round can reach where the target will be
     * @param onTarget whether the nose is on the lead: fire now and the round arrives
     */
    public record Solution(Bore bore, double pipperRange, Vec3 pipperDrop, boolean struck,
            @Nullable Entity target, Vec3 leadOffset, double targetRange, boolean inRange, boolean onTarget) {
    }

    /** The round's flight, a position per tick from the muzzle, and what it ran into if anything. */
    private record Flight(List<Vec3> samples, @Nullable Vec3 impact) {
        private Vec3 last() {
            return this.samples.get(this.samples.size() - 1);
        }
    }

    private static VehicleEntityBase cachedFor;
    private static long cachedAt = Long.MIN_VALUE;
    @Nullable
    private static ResourceLocation cachedWeapon;
    @Nullable
    private static Solution cached;

    /** What the lead was last offered for, and how fast it was going, smoothed. */
    @Nullable
    private static Entity held;
    private static Vec3 heldVelocity = Vec3.ZERO;
    /** Ticks since the sky was last swept for something new. See {@link #SWEEP_TICKS}. */
    private static int sinceSweep;

    private GunSight() {
    }

    /** Whether this weapon is aimed with the sight: fired along the nose, and not steered after. */
    public static boolean aims(WeaponDefinition weapon) {
        return weapon.type() == WeaponDefinition.Type.GUN || weapon.type() == WeaponDefinition.Type.ROCKET;
    }

    /**
     * The sight for whatever the pilot has selected, or null if that is not a weapon aimed this way.
     *
     * <p>The rounds leave from the mean of the stations carrying it, so a pair of pods is aimed from
     * between them, and they leave along the nose because that is the only thing an aeroplane can
     * point a fixed gun with.
     */
    @Nullable
    public static Solution solve(AircraftEntity aircraft) {
        WeaponMounts weapons = aircraft.getWeapons();
        ResourceLocation selected = weapons.selected();
        WeaponDefinition weapon = weapons.selectedWeapon();

        if (selected == null || weapon == null || !aims(weapon)) {
            forget();

            return null;
        }

        Vec3 offset = muzzleOffset(aircraft, selected);
        Bore bore = new Bore() {
            @Override
            public Vec3 muzzle(float partialTick) {
                return aircraft.toWorld(offset, partialTick);
            }

            @Override
            public Vec3 direction(float partialTick) {
                return aircraft.getAimDirection(partialTick);
            }
        };

        return solve(aircraft, selected, weapon, bore);
    }

    /**
     * The same for a vehicle firing the gun built into its turret.
     *
     * <p>Here the two questions have plainer answers than an aeroplane can give: the rounds leave
     * from the muzzle, which the vehicle already works out from the trunnion and the barrel, and
     * they leave along the bore. Nothing about the hull comes into either.
     */
    @Nullable
    public static Solution solve(GroundVehicleEntity vehicle) {
        ResourceLocation selected = vehicle.getStats().armament().main().orElse(null);

        if (selected == null || vehicle.isMissileMode()) {
            forget();

            return null;
        }

        WeaponDefinition weapon = Definitions.weapon(selected);

        if (!aims(weapon)) {
            forget();

            return null;
        }

        Bore bore = new Bore() {
            @Override
            public Vec3 muzzle(float partialTick) {
                return vehicle.getMuzzle(partialTick);
            }

            @Override
            public Vec3 direction(float partialTick) {
                return vehicle.getAimDirection(partialTick);
            }
        };

        return solve(vehicle, selected, weapon, bore);
    }

    /** Worked out once a tick and remembered in between, whichever machine asked for it. */
    @Nullable
    private static Solution solve(VehicleEntityBase vehicle, ResourceLocation selected,
            WeaponDefinition weapon, Bore bore) {
        long now = vehicle.level().getGameTime();

        if (vehicle != cachedFor || now != cachedAt || !selected.equals(cachedWeapon)) {
            if (vehicle != cachedFor) {
                forget();
            }

            cachedFor = vehicle;
            cachedAt = now;
            cachedWeapon = selected;
            cached = work(vehicle, weapon, bore);
        }

        return cached;
    }

    private static void forget() {
        cached = null;
        held = null;
        heldVelocity = Vec3.ZERO;
        sinceSweep = Integer.MAX_VALUE / 2;
    }

    @Nullable
    private static Solution work(VehicleEntityBase vehicle, WeaponDefinition weapon, Bore bore) {
        Vec3 nose = bore.direction(1.0F);

        // Nothing to aim along. The mount refuses to fire in the same case, for the same reason.
        if (nose.lengthSqr() < 1.0E-6) {
            return null;
        }

        Vec3 muzzle = bore.muzzle(1.0F);
        // The launch the mount actually makes: the weapon own speed down the bore, plus whatever
        // speed the machine already has along it, since a rail carries out only that. See
        // WeaponMounts.fireRound. A vehicle standing still contributes nothing and one at speed
        // contributes what it is worth, which is the same rule read the other way.
        Vec3 launch = nose.scale(weapon.projectile().speed() + Math.max(0.0, vehicle.getVelocity().dot(nose)));
        Flight flight = fly(vehicle, weapon.projectile(), muzzle, nose, launch);

        double flown = flight.last().distanceTo(muzzle);

        Entity target = chooseTarget(vehicle, nose, Math.min(TARGET_REACH, flown));
        Vec3 leadOffset = Vec3.ZERO;
        double targetRange = 0.0;
        boolean inRange = false;
        boolean onTarget = false;
        int targetTick = -1;

        if (target == null) {
            held = null;
        } else {
            Vec3 centre = centre(target);
            Vec3 speed = velocityOf(target);
            Vec3 predicted = centre;

            // The time of flight depends on how far away the target will be, which depends on the
            // time of flight. A few passes settle it.
            for (int pass = 0; pass < LEAD_PASSES; pass++) {
                targetTick = tickAt(flight, muzzle, predicted.distanceTo(muzzle));
                predicted = centre.add(speed.scale(targetTick));
            }

            // The round arrives below the line by its drop, so the line is put above where the
            // target will be by the same amount.
            Vec3 drop = offLine(flight.samples().get(targetTick), muzzle, nose);
            Vec3 lead = predicted.subtract(drop);
            Vec3 line = lead.subtract(muzzle);
            double away = predicted.distanceTo(muzzle);
            // Ground in the way is a reason not to fire, not a reason to drop the target: the lead
            // is still worth having for the moment the ridge is cleared.
            boolean blocked = flight.impact() != null && flight.impact().distanceTo(muzzle) < away;

            leadOffset = lead.subtract(centre);
            targetRange = centre.distanceTo(muzzle);
            inRange = reaches(flight, muzzle, away);
            onTarget = inRange && !blocked && line.lengthSqr() > 1.0E-6
                    && degreesBetween(line.normalize(), nose) <= ON_TARGET;
        }

        // The pipper: at the target's range if there is one, at the reference range if not, and on
        // the ground instead if the round runs into it before either.
        Vec3 point = target != null
                ? flight.samples().get(targetTick)
                : flight.samples().get(tickAt(flight, muzzle, REFERENCE_RANGE));
        boolean struck = false;

        if (flight.impact() != null && flight.impact().distanceToSqr(muzzle) < point.distanceToSqr(muzzle)) {
            point = flight.impact();
            struck = true;
        }

        Vec3 offset = point.subtract(muzzle);
        double pipperRange = offset.dot(nose);

        return new Solution(bore, pipperRange, offset.subtract(nose.scale(pipperRange)), struck,
                target, leadOffset, targetRange, inRange, onTarget);
    }

    /**
     * The round flown forward, tick by tick, exactly as {@code VehicleProjectile} flies it: the
     * motor first for anything that has one, then the step, then the drop taken off for the next.
     *
     * <p>Flown on past whatever it runs into. The flight is what the lead is measured against, and
     * a target on the far side of a ridge is still a target the moment the ridge is cleared — it is
     * the pipper that goes on the ridge, not the lead that goes away. Only the first thing struck
     * is kept, and the world is not asked again after it, nor once the round is beyond where the
     * world could answer.
     */
    private static Flight fly(VehicleEntityBase vehicle, WeaponDefinition.Projectile round, Vec3 muzzle, Vec3 nose,
            Vec3 launch) {
        int flight = Math.min(MAX_FLIGHT, round.lifetime());
        List<Vec3> samples = new ArrayList<>(flight + 1);
        Vec3 position = muzzle;
        Vec3 velocity = launch;
        Vec3 impact = null;
        double topSpeed = round.topSpeed() > 0.0F ? round.topSpeed() : Double.MAX_VALUE;
        boolean tracing = true;

        samples.add(position);

        for (int age = 1; age <= flight; age++) {
            // A motor pushes along the axis it was fired on and nothing turns an unguided rocket,
            // so the whole of the speed goes back down the nose: what gravity did last tick becomes
            // a change of speed rather than of heading, which is why a rocket under power flies
            // straight. See RocketEntity.steer.
            if (round.hasMotor() && age <= round.burnTicks()) {
                double speed = Math.min(velocity.length() + thrustAt(round, age), topSpeed);
                velocity = nose.scale(speed);
            }

            Vec3 next = position.add(velocity);

            if (tracing) {
                if (position.distanceToSqr(muzzle) > TRACE_REACH * TRACE_REACH) {
                    tracing = false;
                } else {
                    HitResult hit = vehicle.level().clip(new ClipContext(position, next,
                            ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, vehicle));

                    if (hit.getType() != HitResult.Type.MISS) {
                        impact = hit.getLocation();
                        tracing = false;
                    }
                }
            }

            position = next;
            velocity = velocity.subtract(0.0, round.gravity(), 0.0);
            samples.add(position);
        }

        return new Flight(samples, impact);
    }

    /** What the motor is making at this age, worked up over its spool exactly as the rocket's is. */
    private static float thrustAt(WeaponDefinition.Projectile round, int age) {
        int spool = round.spoolTicks();

        if (spool <= 0) {
            return round.thrust();
        }

        return round.thrust() * Mth.clamp((age + 1) / (float) spool, 0.0F, 1.0F);
    }

    /**
     * The tick on which the round is first as far from the muzzle as {@code distance}, or the last
     * tick of the flight if it never gets there.
     */
    private static int tickAt(Flight flight, Vec3 muzzle, double distance) {
        List<Vec3> samples = flight.samples();
        double wanted = distance * distance;

        for (int tick = 0; tick < samples.size(); tick++) {
            if (samples.get(tick).distanceToSqr(muzzle) >= wanted) {
                return tick;
            }
        }

        return samples.size() - 1;
    }

    /** Whether the round gets as far as {@code distance} from the muzzle before it is given up on. */
    private static boolean reaches(Flight flight, Vec3 muzzle, double distance) {
        return flight.last().distanceToSqr(muzzle) >= distance * distance;
    }

    /** How far a point on the flight is off the straight line down the nose from the muzzle. */
    private static Vec3 offLine(Vec3 sample, Vec3 muzzle, Vec3 nose) {
        Vec3 offset = sample.subtract(muzzle);

        return offset.subtract(nose.scale(offset.dot(nose)));
    }

    /**
     * Where the rounds leave from, in the aircraft's own axes: the mean of every station carrying
     * the selected weapon with something left to fire, so a pair of pods is aimed from between
     * them. Falls back to the stations that carry it at all, and then to the middle of the aircraft.
     */
    private static Vec3 muzzleOffset(AircraftEntity aircraft, ResourceLocation selected) {
        WeaponMounts weapons = aircraft.getWeapons();
        List<WeaponMounts.Mount> mounts = weapons.mounts();
        Vec3 loadedSum = Vec3.ZERO;
        Vec3 carriedSum = Vec3.ZERO;
        int loaded = 0;
        int carried = 0;

        for (int slot = 0; slot < mounts.size(); slot++) {
            WeaponMounts.Mount mount = mounts.get(slot);
            AircraftDefinition.Hardpoint hardpoint = weapons.hardpoint(slot);

            if (hardpoint == null || !selected.equals(mount.weapon())) {
                continue;
            }

            carriedSum = carriedSum.add(hardpoint.pos());
            carried++;

            if (mount.ammo() > 0) {
                loadedSum = loadedSum.add(hardpoint.pos());
                loaded++;
            }
        }

        if (loaded > 0) {
            return loadedSum.scale(1.0 / loaded);
        }

        return carried > 0 ? carriedSum.scale(1.0 / carried) : Vec3.ZERO;
    }

    /**
     * What the lead is offered for: whatever is nearest the boresight inside the search cone and
     * within reach — with a preference for what it was offered for last tick, so that the mark
     * does not hop between two targets close to the middle.
     */
    @Nullable
    private static Entity chooseTarget(VehicleEntityBase vehicle, Vec3 nose, double reach) {
        if (reach <= 1.0) {
            return null;
        }

        Vec3 from = vehicle.position();

        // Whatever is already being led is kept without asking the level anything at all, so long as
        // it is still there and still somewhere the sight can use. Only the search for something
        // else waits for the sweep; see SWEEP_TICKS.
        if (held != null && ++sinceSweep < SWEEP_TICKS && stillWorthLeading(vehicle, from, nose, reach)) {
            return held;
        }

        sinceSweep = 0;

        AABB box = vehicle.getBoundingBox().inflate(reach);
        Entity best = null;
        double bestOff = SEARCH_CONE;
        double heldOff = Double.MAX_VALUE;

        for (Entity candidate : vehicle.level().getEntities(vehicle, box, entity -> couldTarget(vehicle, entity))) {
            Vec3 gap = centre(candidate).subtract(from);
            double distance = gap.length();

            if (distance > reach || distance < 1.0E-3) {
                continue;
            }

            double off = degreesBetween(gap.scale(1.0 / distance), nose);

            if (candidate == held) {
                heldOff = off;
            }

            if (off < bestOff) {
                bestOff = off;
                best = candidate;
            }
        }

        if (held != null && heldOff <= HOLD_CONE
                && (best == null || best == held || bestOff > heldOff - SWITCH_MARGIN)) {
            return held;
        }

        return best;
    }

    /**
     * Whether the target already being led is still one: still there, still something the sight
     * would take, still in reach and still inside the cone it is kept in.
     *
     * <p>All of it about the one entity that is already in hand, so it costs a couple of vectors and
     * asks the level for nothing -- which is the whole point of it, since this is what runs on the
     * four ticks in five that no sweep is made.
     */
    private static boolean stillWorthLeading(VehicleEntityBase vehicle, Vec3 from, Vec3 nose, double reach) {
        Entity target = held;

        if (target == null || !couldTarget(vehicle, target)) {
            return false;
        }

        Vec3 gap = centre(target).subtract(from);
        double distance = gap.length();

        if (distance > reach || distance < 1.0E-3) {
            return false;
        }

        return degreesBetween(gap.scale(1.0 / distance), nose) <= HOLD_CONE;
    }

    /**
     * What the sight will offer a lead on: something alive, or another machine. Not the machine
     * doing the looking, nor anything riding it, nor anyone riding anything else — the machine they
     * are in is the target, not the crew — and not the mod own rounds and decoys.
     */
    private static boolean couldTarget(VehicleEntityBase vehicle, Entity candidate) {
        if (candidate == vehicle || candidate instanceof VehicleProjectile
                || candidate instanceof CountermeasureEntity || WeaponMounts.isPartOf(vehicle, candidate)) {
            return false;
        }

        if (!candidate.isAlive() || candidate.isSpectator()) {
            return false;
        }

        if (candidate.getVehicle() instanceof VehicleEntityBase) {
            return false;
        }

        if (candidate instanceof VehicleEntityBase machine) {
            return !machine.isWrecked();
        }

        return candidate instanceof LivingEntity;
    }

    /**
     * How fast the target is going, in blocks a tick.
     *
     * <p>A machine of the mod's says so itself, and says it honestly on every side. Anything else
     * is read off how far it was drawn moving last tick, which on a client is a smoothed guess at
     * the truth, and is smoothed a little further here so the mark does not shake.
     */
    private static Vec3 velocityOf(Entity target) {
        Vec3 now = target instanceof VehicleEntityBase vehicle
                ? vehicle.getVelocity()
                : target.position().subtract(target.xOld, target.yOld, target.zOld);

        if (target == held) {
            now = heldVelocity.lerp(now, VELOCITY_SMOOTHING);
        }

        held = target;
        heldVelocity = now;

        return now;
    }

    private static Vec3 centre(Entity entity) {
        return entity.position().add(0.0, entity.getBbHeight() * 0.5, 0.0);
    }

    /** The angle between two unit directions, in degrees. */
    private static double degreesBetween(Vec3 a, Vec3 b) {
        return Math.toDegrees(Math.acos(Mth.clamp(a.dot(b), -1.0, 1.0)));
    }
}
