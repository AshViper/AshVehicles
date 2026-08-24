package com.ashvehicles.sensor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.RocketEntity;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.network.SensorPayload;
import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.weapon.TargetLock;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * What one machine can tell about everything else around it: the radar looking where the weapons
 * look, and the warning receiver listening in every direction at once.
 *
 * <p>The two are one class because they are one sweep. Both want the same question asked of the same
 * piece of sky — what is out there, and where — and asking it twice would be paying twice for it.
 * What they do with the answer is opposite: the radar reports what <em>this</em> machine can see
 * ahead of it, and the receiver reports who can see <em>this</em> machine, from wherever they are.
 *
 * <p><b>Not an aircraft's instrument, a machine's.</b> A launcher on the ground looks for aeroplanes
 * with exactly the same set an aeroplane looks for aeroplanes with, and the two have to be the same
 * sort of thing or neither can warn the other: the whole of what makes flying over a battery
 * frightening is that the pilot's receiver goes off, and it can only go off about a radar that
 * exists on the same terms as theirs. So this is asked of {@link VehicleEntityBase} and knows
 * nothing about which kind it has — the one place it would have to is which way the set is pointing,
 * and {@link VehicleEntityBase#getAimDirection} answers that for both.
 *
 * <p><b>All of it runs on the server</b>, like the seeker in {@link TargetLock}, because deciding
 * what a crew knows about is not something a client may do. The result is sent to the one player at
 * the controls and to nobody else: a radar picture is an instrument, not a broadcast, and it is a
 * good deal of it. See {@link SensorPayload}.
 *
 * <p>Nothing happens at all while the machine is empty. A parked aeroplane's radar is off, which
 * costs nothing to run and also means it paints nobody — so an unmanned machine sitting on an apron
 * does not set off warning receivers across the map.
 *
 * <p><b>What it does not do.</b> There is no terrain in this: a contact behind a mountain is still a
 * contact. Radar in life is not so obliging, but the alternative is a line-of-sight trace per
 * contact per sweep out to several hundred blocks, over ground that is very often not even loaded.
 */
public final class Sensors {
    /** The most that is worth drawing on a scope, and the most that is worth sending. */
    private static final int MOST_CONTACTS = 16;
    private static final int MOST_THREATS = 8;

    private final VehicleEntityBase vehicle;
    private List<Contact> contacts = List.of();
    private List<Threat> threats = List.of();
    private int sinceSweep;

    public Sensors(VehicleEntityBase vehicle) {
        this.vehicle = vehicle;
    }

    /** What the radar found on its last pass. Empty for a machine nobody is aboard. */
    public List<Contact> contacts() {
        return this.contacts;
    }

    /** Who is looking at this machine, worst first. */
    public List<Threat> threats() {
        return this.threats;
    }

    /**
     * Whether this radar is holding that entity right now.
     *
     * <p>Asked by somebody else's warning receiver, which is the whole reason the contacts are kept
     * rather than merely sent: being painted is something you are told about by the other fellow's
     * radar, so the other fellow's radar has to be a thing that exists.
     */
    public boolean paints(Entity entity) {
        for (Contact contact : this.contacts) {
            if (contact.id() == entity.getId()) {
                return true;
            }
        }

        return false;
    }

    /** One tick. Sweeps when it is due, and tells the crew what the sweep found. */
    public void tick() {
        if (!(this.vehicle.level() instanceof ServerLevel level)) {
            return;
        }

        ServerPlayer crew = this.crew();

        if (crew == null) {
            this.clear();

            return;
        }

        VehicleChassis.Radar radar = this.vehicle.radar();

        // A machine with neither a radar nor a receiver has nothing to sweep for.
        if (!radar.exists()) {
            this.clear();

            return;
        }

        if (++this.sinceSweep < Math.max(radar.sweepTicks(), 1)) {
            return;
        }

        this.sinceSweep = 0;
        this.sweep(level, radar);
        PacketDistributor.sendToPlayer(crew, new SensorPayload(this.contacts, this.threats));
    }

    private void clear() {
        this.contacts = List.of();
        this.threats = List.of();
    }

    /**
     * One pass of the aerial, in one walk of everything nearby.
     *
     * <p>Machines are asked both questions — are they in front of me, and are they interested in me
     * — because those are the only things that can be either. A player on foot goes on the scope and
     * nothing else; a missile in the air is a warning and nothing else.
     */
    private void sweep(ServerLevel level, VehicleChassis.Radar radar) {
        Vec3 from = this.vehicle.position();
        // Where the set is looking, which is where the weapons are looking: an aeroplane's nose, a
        // turret's bore. Flattened, and the beam squared to that rather than to the machine's own
        // sides — a scope read while the aeroplane is banked should not have the world tipping over
        // on it, and neither should one read from a hull lying across a slope.
        Vec3 along = flat(this.vehicle.getAimDirection(1.0F));
        Vec3 right = new Vec3(-along.z, 0.0, along.x);
        double reach = radar.reach();
        double widest = Math.cos(Math.toRadians(radar.arc()));
        TargetLock lock = this.vehicle.lock();
        Entity seeking = lock == null ? null : lock.target();

        List<Contact> found = new ArrayList<>();
        List<Threat> warnings = new ArrayList<>();
        AABB box = this.vehicle.getBoundingBox().inflate(reach);

        for (Entity other : level.getEntities(this.vehicle, box, Sensors::worthLookingAt)) {
            Vec3 gap = other.position().subtract(from);
            double distance = gap.length();

            if (distance > reach || distance < 1.0E-3) {
                continue;
            }

            float bearing = bearing(gap, along, right);

            if (other instanceof RocketEntity missile) {
                if (missile.getTarget() == this.vehicle && distance <= radar.warningRange()) {
                    warnings.add(new Threat(bearing, Threat.Kind.MISSILE));
                }

                continue;
            }

            if (other instanceof VehicleEntityBase hostile && distance <= radar.warningRange()) {
                Threat.Kind attention = this.attentionFrom(hostile);

                if (attention != null) {
                    warnings.add(new Threat(bearing, attention));
                }
            }

            // How far this radar reaches against this particular thing, which is not how far it
            // reaches. A shape built to return nothing is found close in or not at all, and a stealth
            // aeroplane carrying its missiles on the outside is not one.
            if (radar.fitted() && distance <= radar.range() * AircraftEntity.visibility(other)
                    && gap.scale(1.0 / distance).dot(along) > widest) {
                found.add(new Contact(other.getId(), bearing, (float) distance,
                        (float) (other.getY() - this.vehicle.getY()),
                        other == seeking,
                        other instanceof AircraftEntity));
            }
        }

        found.sort(Comparator.comparingDouble(Contact::range));
        warnings.sort(Comparator.comparingInt((Threat threat) -> threat.kind().ordinal()).reversed());

        this.contacts = List.copyOf(found.subList(0, Math.min(found.size(), MOST_CONTACTS)));
        this.threats = List.copyOf(warnings.subList(0, Math.min(warnings.size(), MOST_THREATS)));
    }

    /**
     * What one other machine is doing about this one, or null if it has not noticed it.
     *
     * <p>Its seeker counts for more than its radar: being on somebody's scope is a fact about the
     * afternoon, and being in their seeker is a fact about the next few seconds.
     */
    @Nullable
    private Threat.Kind attentionFrom(VehicleEntityBase other) {
        TargetLock lock = other.lock();

        if (lock != null && lock.target() == this.vehicle) {
            return lock.isLocked() ? Threat.Kind.LOCK : Threat.Kind.SEARCH;
        }

        return other.getSensors().paints(this.vehicle) ? Threat.Kind.SEARCH : null;
    }

    /**
     * Machines, people on foot, and anything already on its way here.
     *
     * <p>Ground vehicles as well as aircraft, and both for the same two reasons: an aeroplane out to
     * attack a column wants them on its scope, and a battery on the ground has to be a thing an
     * aeroplane's receiver can hear.
     */
    private static boolean worthLookingAt(Entity candidate) {
        if (!candidate.isAlive()) {
            return false;
        }

        if (candidate instanceof RocketEntity) {
            return true;
        }

        if (candidate instanceof VehicleEntityBase machine) {
            // A wreck is scenery. It is still there and still made of metal, but a scope that goes on
            // painting everything anyone has ever shot down fills up with contacts that cannot be
            // fought, and the one that can be is somewhere in among them.
            return !machine.isWrecked();
        }

        // Somebody riding a machine is crew, not a contact; and a spectator is not there at all.
        return candidate instanceof Player player && !player.isSpectator() && player.getVehicle() == null;
    }

    /** Degrees off the boresight, positive to the right, measured flat. */
    private static float bearing(Vec3 gap, Vec3 along, Vec3 right) {
        return (float) Mth.wrapDegrees(Math.toDegrees(
                Math.atan2(gap.x * right.x + gap.z * right.z, gap.x * along.x + gap.z * along.z)));
    }

    /** The heading alone, with the climb taken out of it. */
    private static Vec3 flat(Vec3 direction) {
        Vec3 level = new Vec3(direction.x, 0.0, direction.z);

        return level.lengthSqr() < 1.0E-8 ? new Vec3(0.0, 0.0, 1.0) : level.normalize();
    }

    @Nullable
    private ServerPlayer crew() {
        return this.vehicle.getControllingPassenger() instanceof ServerPlayer player ? player : null;
    }
}
