package com.ashvehicles.sensor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.RocketEntity;
import com.ashvehicles.network.SensorPayload;
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
 * What one aircraft can tell about everything else in the sky: the radar looking forward, and the
 * warning receiver listening in every direction at once.
 *
 * <p>The two are one class because they are one sweep. Both want the same question asked of the same
 * piece of sky — what is out there, and where — and asking it twice would be paying twice for it.
 * What they do with the answer is opposite: the radar reports what <em>this</em> aircraft can see
 * ahead of it, and the receiver reports who can see <em>this</em> aircraft, from wherever they are.
 *
 * <p><b>All of it runs on the server</b>, like the seeker in {@link TargetLock}, because deciding
 * what an aircraft knows about is not something a client may do. The result is sent to the one
 * player flying it and to nobody else: a radar picture is the pilot's instrument, not a broadcast,
 * and it is a good deal of it. See {@link SensorPayload}.
 *
 * <p>Nothing happens at all while the aircraft is empty. A parked aeroplane's radar is off, which
 * costs nothing to run and also means it paints nobody — so an unmanned aircraft sitting on an
 * apron does not set off warning receivers across the map.
 *
 * <p><b>What it does not do.</b> There is no terrain in this: a contact behind a mountain is still a
 * contact. Radar in life is not so obliging, but the alternative is a line-of-sight trace per
 * contact per sweep out to several hundred blocks, over ground that is very often not even loaded.
 */
public final class Sensors {
    /** The most that is worth drawing on a scope, and the most that is worth sending. */
    private static final int MOST_CONTACTS = 16;
    private static final int MOST_THREATS = 8;

    private final AircraftEntity aircraft;
    private List<Contact> contacts = List.of();
    private List<Threat> threats = List.of();
    private int sinceSweep;

    public Sensors(AircraftEntity aircraft) {
        this.aircraft = aircraft;
    }

    /** What the radar found on its last pass. Empty for an aircraft nobody is flying. */
    public List<Contact> contacts() {
        return this.contacts;
    }

    /** Who is looking at this aircraft, worst first. */
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

    /** One tick. Sweeps when it is due, and tells the pilot what the sweep found. */
    public void tick() {
        if (!(this.aircraft.level() instanceof ServerLevel level)) {
            return;
        }

        ServerPlayer pilot = this.pilot();

        if (pilot == null) {
            this.clear();

            return;
        }

        AircraftDefinition.Radar radar = this.aircraft.getStats().radar();

        // An aeroplane with neither a radar nor a receiver has nothing to sweep for.
        if (!radar.fitted() && radar.warningRange() <= 0.0F) {
            this.clear();

            return;
        }

        if (++this.sinceSweep < Math.max(radar.sweepTicks(), 1)) {
            return;
        }

        this.sinceSweep = 0;
        this.sweep(level, radar);
        PacketDistributor.sendToPlayer(pilot, new SensorPayload(this.contacts, this.threats));
    }

    private void clear() {
        this.contacts = List.of();
        this.threats = List.of();
    }

    /**
     * One pass of the aerial, in one walk of everything nearby.
     *
     * <p>Aircraft are asked both questions — are they in front of me, and are they interested in me
     * — because those are the only things that can be either. A player on foot goes on the scope and
     * nothing else; a missile in the air is a warning and nothing else.
     */
    private void sweep(ServerLevel level, AircraftDefinition.Radar radar) {
        Vec3 from = this.aircraft.position();
        Vec3 nose = flat(this.aircraft.getNoseVector());
        // Square to the heading rather than to the wings: a scope read while the aeroplane is banked
        // should not have the world tipping over on it.
        Vec3 right = new Vec3(-nose.z, 0.0, nose.x);
        double reach = radar.reach();
        double widest = Math.cos(Math.toRadians(radar.arc()));

        List<Contact> found = new ArrayList<>();
        List<Threat> warnings = new ArrayList<>();
        AABB box = this.aircraft.getBoundingBox().inflate(reach);

        for (Entity other : level.getEntities(this.aircraft, box, Sensors::worthLookingAt)) {
            Vec3 gap = other.position().subtract(from);
            double distance = gap.length();

            if (distance > reach || distance < 1.0E-3) {
                continue;
            }

            float bearing = bearing(gap, nose, right);

            if (other instanceof RocketEntity missile) {
                if (missile.getTarget() == this.aircraft && distance <= radar.warningRange()) {
                    warnings.add(new Threat(bearing, Threat.Kind.MISSILE));
                }

                continue;
            }

            if (other instanceof AircraftEntity hostile && distance <= radar.warningRange()) {
                Threat.Kind attention = this.attentionFrom(hostile);

                if (attention != null) {
                    warnings.add(new Threat(bearing, attention));
                }
            }

            if (radar.fitted() && distance <= radar.range()
                    && gap.scale(1.0 / distance).dot(nose) > widest) {
                found.add(new Contact(other.getId(), bearing, (float) distance,
                        (float) (other.getY() - this.aircraft.getY()),
                        other == this.aircraft.getWeapons().lock().target(),
                        other instanceof AircraftEntity));
            }
        }

        found.sort(Comparator.comparingDouble(Contact::range));
        warnings.sort(Comparator.comparingInt((Threat threat) -> threat.kind().ordinal()).reversed());

        this.contacts = List.copyOf(found.subList(0, Math.min(found.size(), MOST_CONTACTS)));
        this.threats = List.copyOf(warnings.subList(0, Math.min(warnings.size(), MOST_THREATS)));
    }

    /**
     * What one other aircraft is doing about this one, or null if it has not noticed it.
     *
     * <p>Its seeker counts for more than its radar: being on somebody's scope is a fact about the
     * afternoon, and being in their seeker is a fact about the next few seconds.
     */
    @Nullable
    private Threat.Kind attentionFrom(AircraftEntity other) {
        TargetLock lock = other.getWeapons().lock();

        if (lock.target() == this.aircraft) {
            return lock.isLocked() ? Threat.Kind.LOCK : Threat.Kind.SEARCH;
        }

        return other.getSensors().paints(this.aircraft) ? Threat.Kind.SEARCH : null;
    }

    /** Aeroplanes, people on foot, and anything already on its way here. */
    private static boolean worthLookingAt(Entity candidate) {
        if (!candidate.isAlive()) {
            return false;
        }

        if (candidate instanceof RocketEntity) {
            return true;
        }

        if (candidate instanceof AircraftEntity) {
            return true;
        }

        // Somebody riding this aircraft is crew, not a contact; and a spectator is not there at all.
        return candidate instanceof Player player && !player.isSpectator() && player.getVehicle() == null;
    }

    /** Degrees off the nose, positive to the right, measured flat. */
    private static float bearing(Vec3 gap, Vec3 nose, Vec3 right) {
        return (float) Mth.wrapDegrees(Math.toDegrees(
                Math.atan2(gap.x * right.x + gap.z * right.z, gap.x * nose.x + gap.z * nose.z)));
    }

    /** The heading alone, with the climb taken out of it. */
    private static Vec3 flat(Vec3 direction) {
        Vec3 level = new Vec3(direction.x, 0.0, direction.z);

        return level.lengthSqr() < 1.0E-8 ? new Vec3(0.0, 0.0, 1.0) : level.normalize();
    }

    @Nullable
    private ServerPlayer pilot() {
        return this.aircraft.getControllingPassenger() instanceof ServerPlayer player ? player : null;
    }
}
