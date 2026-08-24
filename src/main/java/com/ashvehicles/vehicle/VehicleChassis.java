package com.ashvehicles.vehicle;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * The four blocks every machine's file has, whether it flies or drives: how big the game thinks it
 * is, how it is drawn, where the camera sits, and what it sounds like.
 *
 * <p>These were the same record written twice, once in each definition, and the pair had already
 * started to drift — the same field under two defaults, and a fix made to one of them. What differs
 * between an aeroplane and a tank is genuinely the aeroplane and the tank: the wing, the drivetrain,
 * the turret. None of it is here.
 *
 * <p>Where one kind of machine has a field the other has no use for, the field is simply optional
 * and the other kind leaves it out. An aircraft names a sound for its undercarriage and a tank does
 * not; a tank lists its road wheels and an aircraft does not. Nobody is made to write a line that
 * means nothing to them, and neither is made to have a record of their own for one field.
 */
public final class VehicleChassis {
    private VehicleChassis() {
    }

    /**
     * The plain box Minecraft files the entity under, fixed when the entity type is registered. Read
     * from the mod's own copy of the file at start-up, so unlike everything else a data pack cannot
     * change it.
     *
     * <p>Minecraft can only describe an entity as an upright box with a square footprint, which for
     * a fifteen-metre aeroplane or a seven-metre tank is a shed. So it is deliberately small: it
     * covers the fuselage and the wing roots, or the hull, and lets the rest overhang. The real shape
     * is the {@code boxes} beside it, and what a machine is shot at, stood on and — for a ground
     * vehicle — stopped by is those and not this.
     *
     * @param shape the boxes the machine is really made of, read from {@code boxes} in this same
     *              block. Unlike the width and the height, they are read afresh on every
     *              {@code /reload} like everything else in the file
     * @param trackingRange how far away, in chunks, other players are sent the machine
     * @param ghostRange how far away, in blocks, an aircraft keeps being sent at all. Past the
     *                   tracking range, and past the edge of the chunks a player has loaded, it goes
     *                   on being reported and is drawn as a ghost: an aeroplane at altitude is
     *                   visible from much further away than the ground beneath it. Zero removes the
     *                   limit entirely. Nothing on the ground has any use for it
     */
    public record Hitbox(float width, float height, int trackingRange, int ghostRange, VehicleShape shape) {
        public static final Hitbox DEFAULT = new Hitbox(4.0F, 2.0F, 12, 0, VehicleShape.NONE);

        public static final Codec<Hitbox> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.fieldOf("width").forGetter(Hitbox::width),
                Codec.FLOAT.fieldOf("height").forGetter(Hitbox::height),
                Codec.INT.optionalFieldOf("tracking_range", 12).forGetter(Hitbox::trackingRange),
                Codec.INT.optionalFieldOf("ghost_range", 0).forGetter(Hitbox::ghostRange),
                VehicleShape.MAP_CODEC.forGetter(Hitbox::shape)
        ).apply(instance, Hitbox::new));

        /** Whether the machine stops being reported at some distance at all. */
        public boolean hasGhostLimit() {
            return this.ghostRange > 0;
        }
    }

    /**
     * How to draw the machine. The geometry, texture and animation files are found by name, so one
     * called {@code su_25} is drawn from {@code geo/entity/su_25.geo.json} and
     * {@code textures/entity/su_25.png} without being told where they are.
     *
     * @param scale uniform scale applied to the model, for models not built at Minecraft's scale
     * @param bones which bone in the geometry plays which part, keyed by the roles each kind of
     *              machine lists. Anything left out simply does not move
     * @param roadWheels the bones of a tracked vehicle's road wheels and sprockets, which all turn
     *                   together at a speed worked out from how far it has travelled. A list rather
     *                   than roles because a tank has as many of them as it has and they are
     *                   interchangeable — eighteen on a Leopard 2, and nothing that reads this cares
     *                   which is which
     * @param steeredWheels the bones that turn with the steering, which on a wheeled vehicle is the
     *                      front axle or two and on a tracked one is nothing at all. A wheel here is
     *                      usually in {@link #roadWheels} as well: the two are different questions
     *                      about the same wheel — how far it has rolled, and which way it is
     *                      pointing — and a driven front wheel does both
     * @param steerLock how far those wheels turn at full lock, in degrees. Nothing, which is what a
     *                  file leaving it out gets, holds them straight however hard the driver steers
     * @param track the one track link the whole run of track is built out of, or empty for a
     *              vehicle whose track is drawn in the geometry like any other part
     * @param slavedTurrets the bones of any further gun mounts laid on the same target as the main
     *                      one — a warship's second turret, slaved to the same fire control. Each is
     *                      traversed and elevated to the main turret's aim about its own ring, so
     *                      they train together. The main mount itself is the {@code turret}/
     *                      {@code gun} bones and is not listed here; a vehicle with one gun leaves
     *                      this empty
     */
    public record Model(float scale, Map<String, String> bones, List<String> roadWheels,
            List<String> steeredWheels, float steerLock, Optional<Track> track, List<String> slavedTurrets) {
        public static final Model DEFAULT =
                new Model(1.0F, Map.of(), List.of(), List.of(), 0.0F, Optional.empty(), List.of());

        public static final Codec<Model> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("scale", 1.0F).forGetter(Model::scale),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("bones", Map.of())
                        .forGetter(Model::bones),
                Codec.STRING.listOf().optionalFieldOf("road_wheels", List.of()).forGetter(Model::roadWheels),
                Codec.STRING.listOf().optionalFieldOf("steered_wheels", List.of())
                        .forGetter(Model::steeredWheels),
                Codec.FLOAT.optionalFieldOf("steer_lock", 0.0F).forGetter(Model::steerLock),
                Track.CODEC.optionalFieldOf("track").forGetter(Model::track),
                Codec.STRING.listOf().optionalFieldOf("slaved_turrets", List.of())
                        .forGetter(Model::slavedTurrets)
        ).apply(instance, Model::new));

        /** Whether any wheel on this machine turns with the steering. */
        public boolean isSteered() {
            return this.steerLock > 0.0F && !this.steeredWheels.isEmpty();
        }

        /** The bone named for a role, or empty if this machine has no such part. */
        public String bone(String role) {
            return this.bones.getOrDefault(role, "");
        }
    }

    /**
     * A run of track built at draw time out of one link.
     *
     * <p>The alternative is what most tank models do: a chain of sixty-odd bones, one per link, laid
     * out by hand round the wheels and parented to one another so that the artist can bend the run.
     * That is an afternoon's work per vehicle, it is wrong the moment a wheel moves, and it cannot
     * be animated at all — a link is where it was put, so the track either does not run or every one
     * of the sixty bones has to be keyframed.
     *
     * <p>So: one link, drawn many times. Where the links go is worked out from the road wheels
     * themselves, which the file already names — each is taken with the size its own geometry gives
     * it, and the belt is the taut band round the lot of them, exactly as a real track is the taut
     * band round the sprocket, the idler and the road wheels. Move a wheel in the model, or give it
     * a bigger one, and the track follows without a line changing here.
     *
     * <p>Both sides come from the same link. The wheels fall into two groups by which side of the
     * hull they are on, and each group gets its own band at its own wheels' distance out, so a
     * vehicle needs one link bone rather than one per side.
     *
     * @param link the bone holding a single link, drawn once for every link in the run. Anything
     *             parented to it comes along, so a link with a guide horn or a pad is one bone here
     * @param wheels the bones the band is drawn round, or empty to use the road wheels. Worth
     *               setting when the run touches something that is not a road wheel and does not
     *               turn — a return roller, or a track skid — or when a wheel is inside the run
     *               rather than shaping it
     * @param pitch the distance from one link to the next, in blocks, or zero to take it from the
     *              link's own geometry. Taken from the geometry it is exactly the length of the
     *              link, which is what makes a run with no gaps in it
     * @param spacing pitch multiplier, for a link that is meant to overlap its neighbour or stand
     *                clear of it. Below one the links overlap and the run is denser
     * @param outset how far outside the wheel rims the band sits, in blocks, or empty for half the
     *               link's own thickness — which puts the inside face of the link against the wheel,
     *               where it belongs
     * @param maxLinks the most links one side is ever drawn with. A backstop against a pitch of
     *                 nearly nothing asking for ten thousand of them, not a figure to tune
     */
    public record Track(String link, List<String> wheels, float pitch, float spacing,
            Optional<Float> outset, int maxLinks) {
        public static final Codec<Track> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("link").forGetter(Track::link),
                Codec.STRING.listOf().optionalFieldOf("wheels", List.of()).forGetter(Track::wheels),
                Codec.FLOAT.optionalFieldOf("pitch", 0.0F).forGetter(Track::pitch),
                Codec.FLOAT.optionalFieldOf("spacing", 1.0F).forGetter(Track::spacing),
                Codec.FLOAT.optionalFieldOf("outset").forGetter(Track::outset),
                Codec.INT.optionalFieldOf("max_links", 256).forGetter(Track::maxLinks)
        ).apply(instance, Track::new));

        /** The bones the band is drawn round: the ones named here, or failing that the road wheels. */
        public List<String> wheelsOr(List<String> roadWheels) {
            return this.wheels.isEmpty() ? roadWheels : this.wheels;
        }
    }

    /**
     * Where the camera sits.
     *
     * @param pos the chase camera, measured along the <em>viewing</em> axes rather than the
     *            machine's: x to the right of the view, y straight up, z along the line of sight
     *            with negative meaning behind. Which is what keeps the machine still in frame
     *            however it is pointing; see {@link com.ashvehicles.client.ChaseCamera}
     * @param tilt degrees the chase view is tipped down, so the camera looks at the machine from
     *             above rather than along a horizon that happens to pass through it. Nothing to do
     *             with {@code pos.y}, which slides the machine down the screen without changing what
     *             else is in it: this rotates the whole view, and with it the axes {@code pos} is
     *             measured along, so the camera climbs as it tips. A tank wants a few degrees of it
     *             — the ground it is fighting over is worth more of the screen than the sky. An
     *             aeroplane wants none, since out there the sky is where everything is
     * @param cockpit the first-person eye, in the machine's own axes: x right, y up, z towards the
     *                front. Bolted to the machine, so the view rolls with the wings and leans onto a
     *                slope with the hull. On a machine with a turret it is a point on the turret,
     *                written with the turret at dead ahead, and swings about the ring with it
     *                <p>It is the eye of any seat that does not give one of its own, which is what
     *                every machine's file said before seats could — see {@link Seat}
     */
    public record CameraMount(Vec3 pos, float tilt, Vec3 cockpit) {
        public static final CameraMount DEFAULT =
                new CameraMount(new Vec3(0.0, 2.5, -24.0), 0.0F, new Vec3(0.0, 2.5, 3.4));

        public static final Codec<CameraMount> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Vec3.CODEC.fieldOf("pos").forGetter(CameraMount::pos),
                Codec.FLOAT.optionalFieldOf("tilt", 0.0F).forGetter(CameraMount::tilt),
                Vec3.CODEC.fieldOf("cockpit").forGetter(CameraMount::cockpit)
        ).apply(instance, CameraMount::new));
    }

    /**
     * A place aboard, and what whoever is in it can see out of.
     *
     * <p>A crew place used to be a point and nothing else, and the eye that went with it was one
     * point for the whole machine. That is right for a single-seater and wrong for everything else:
     * a seven-seat CV90 put its dismounts' eyes in the commander's cupola, an F-14's back-seater
     * looked out of the front canopy, and a destroyer sat a man eight blocks below the bridge and
     * showed him the bridge. So the eye belongs to the seat, beside the seat, where the two cannot
     * drift apart when a seat is moved or another one added.
     *
     * <p>A seat may still be written as a bare point, and the great many that are go on meaning
     * exactly what they meant: the eye falls back to {@code camera.cockpit} and the view is the one
     * it always was. Nothing has to be rewritten to keep working, and a machine is improved a seat
     * at a time.
     *
     * @param pos where the crew member is, in the machine's own axes — x right, y up, z towards the
     *            front — in blocks. Their <em>feet</em>: this is the point they are stood at, not
     *            the point they see from. The first seat is the one that drives or flies
     * @param eye where that crew member's eye is, in the same axes, or empty for the machine's own
     *            {@code camera.cockpit}. Given outright rather than as a height above the seat,
     *            because a head that leans out of a hatch is not over the feet that are on the
     *            floor of the hull
     * @param mount what the eye is bolted to, or empty for whatever the machine does by default —
     *             the turret on anything with one, the hull on a ship or an aircraft. It is worth
     *             saying per seat because a tank's crew genuinely differ: the commander's head is
     *             out of the turret roof and comes round with the gun, and the driver's is in the
     *             glacis and does not. Note that this is the <em>eye</em>, not the seat: where a
     *             crew member's body is put is the machine's business and is not changed here
     */
    public record Seat(Vec3 pos, Optional<Vec3> eye, Optional<VehicleShape.Mount> mount) {
        /** A seat that says nothing but where it is, which is what a bare point in a file becomes. */
        public static Seat at(Vec3 pos) {
            return new Seat(pos, Optional.empty(), Optional.empty());
        }

        private static final Codec<Seat> SPELLED_OUT = RecordCodecBuilder.create(instance -> instance.group(
                Vec3.CODEC.fieldOf("pos").forGetter(Seat::pos),
                Vec3.CODEC.optionalFieldOf("eye").forGetter(Seat::eye),
                VehicleShape.Mount.CODEC.optionalFieldOf("mount").forGetter(Seat::mount)
        ).apply(instance, Seat::new));

        /**
         * Either form: the bare point a file has always been allowed to write, or the block that
         * says more than where. Written back out in whichever form the seat actually needs, so a
         * file that says nothing new does not grow a set of braces round every seat in it.
         */
        public static final Codec<Seat> CODEC = Codec.either(Vec3.CODEC, SPELLED_OUT).xmap(
                either -> either.map(Seat::at, seat -> seat),
                seat -> seat.saysMoreThanWhere()
                        ? com.mojang.datafixers.util.Either.right(seat)
                        : com.mojang.datafixers.util.Either.left(seat.pos()));

        private boolean saysMoreThanWhere() {
            return this.eye.isPresent() || this.mount.isPresent();
        }

        /** This seat's eye, or the machine's own if it does not have one. */
        public Vec3 eyeOr(Vec3 machineWide) {
            return this.eye.orElse(machineWide);
        }

        /** What this seat's eye is bolted to, or whatever the machine does when nobody says. */
        public VehicleShape.Mount mountOr(VehicleShape.Mount machineWide) {
            return this.mount.orElse(machineWide);
        }
    }

    /**
     * What the engine sounds like. The recording itself lives in the resource pack, in
     * {@code sounds.json} and an {@code .ogg} like any other Minecraft sound; this only says which
     * one to use and how to play it.
     *
     * <p>The recording is found in this order: the {@code engine} event named here if there is one;
     * failing that, an event named after the machine, so {@code su_25} looks for
     * {@code ashvehicles:engine.su_25}; and failing that the mod's default. So a machine with no
     * recording of its own still sounds like something, and giving it one is a matter of dropping in
     * the file and listing it in {@code sounds.json}, with nothing to change here.
     *
     * <p>The recording should be a steady loop of the engine at a constant setting: how hard it is
     * working is expressed by playing it louder and faster, not by switching recordings.
     *
     * @param engine sound event to use, or empty to look one up by the machine's name
     * @param gear sound event for an aircraft's undercarriage travelling, or empty to look one up by
     *            the aircraft's name. Also a loop, played only while the legs are on their way, and
     *            played at one volume and one pitch: the figures below are the engine's alone.
     *            Nothing on the ground has one
     * @param volume how loud at full power, next to the machine; 1 is the recording as made
     * @param idleVolume fraction of that at rest while the engine is still turning
     * @param pitchMin playback speed at rest
     * @param pitchMax playback speed flat out
     * @param range distance, in blocks, beyond which the engine cannot be heard at all. It fades
     *              steadily out to there. A jet is heard long before it is seen and wants hundreds
     *              of blocks; a diesel carries a long way over open ground but nothing like as far
     */
    public record Sound(Optional<ResourceLocation> engine, Optional<ResourceLocation> gear,
            float volume, float idleVolume, float pitchMin, float pitchMax, float range) {
        public static final Sound DEFAULT =
                new Sound(Optional.empty(), Optional.empty(), 1.0F, 0.35F, 0.7F, 1.25F, 512.0F);

        public static final Codec<Sound> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.optionalFieldOf("engine").forGetter(Sound::engine),
                ResourceLocation.CODEC.optionalFieldOf("gear").forGetter(Sound::gear),
                Codec.FLOAT.optionalFieldOf("volume", DEFAULT.volume()).forGetter(Sound::volume),
                Codec.FLOAT.optionalFieldOf("idle_volume", DEFAULT.idleVolume()).forGetter(Sound::idleVolume),
                Codec.FLOAT.optionalFieldOf("pitch_min", DEFAULT.pitchMin()).forGetter(Sound::pitchMin),
                Codec.FLOAT.optionalFieldOf("pitch_max", DEFAULT.pitchMax()).forGetter(Sound::pitchMax),
                Codec.FLOAT.optionalFieldOf("range", DEFAULT.range()).forGetter(Sound::range)
        ).apply(instance, Sound::new));
    }

    /**
     * The machine's radar, and how far its warning receiver can hear.
     *
     * <p>The radar looks along whatever the machine aims with and nowhere else: it sweeps a cone
     * about that, so finding somebody is a matter of pointing at where they might be, and turning
     * away from a contact loses it. That is what makes a radar worth having rather than a map of the
     * sky. On an aeroplane the cone is about the nose, so the pilot points the aircraft; on a vehicle
     * with a turret it is about the bore, so the crew traverse — which is the same instrument
     * answering to whichever thing that machine aims with. See {@link com.ashvehicles.sensor.Sensors}.
     *
     * <p>The warning receiver is the other way round and has no cone at all. It hears somebody
     * else's radar wherever it is coming from, which is the whole point of one: what it is for is
     * the thing you did not see, and that is behind you.
     *
     * @param range how far the radar sees, in blocks — kilometres rather than hundreds of blocks,
     *              because that is the distance at which one aeroplane finds another and there is
     *              nothing else out there to find. Zero or less means the machine has none, and a
     *              crew with no radar has no scope and can lock only what their seeker reaches
     * @param arc half-angle of the sweep, in degrees off whatever the machine aims along. A hundred
     *            and eighty is no cone at all, which is what a set that turns on its own mounting
     *            rather than with the machine comes to
     * @param sweepTicks how often the picture is redrawn. A radar does not see continuously; it
     *                   sweeps, and what is on the scope is where things were when it last passed
     * @param warningRange how far off somebody can be and still set off the warning receiver, in
     *                     blocks. Generous next to the radar's own reach: being painted from further
     *                     away than you can see is exactly the situation worth being told about
     */
    public record Radar(float range, float arc, int sweepTicks, float warningRange) {
        public static final Radar DEFAULT = new Radar(3000.0F, 55.0F, 10, 4000.0F);
        /** A machine with neither a set nor a receiver, which is most of what drives on the ground. */
        public static final Radar NONE = new Radar(0.0F, 0.0F, 10, 0.0F);

        public static final Codec<Radar> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("range", DEFAULT.range()).forGetter(Radar::range),
                Codec.FLOAT.optionalFieldOf("arc", DEFAULT.arc()).forGetter(Radar::arc),
                Codec.INT.optionalFieldOf("sweep_ticks", DEFAULT.sweepTicks()).forGetter(Radar::sweepTicks),
                Codec.FLOAT.optionalFieldOf("warning_range", DEFAULT.warningRange()).forGetter(Radar::warningRange)
        ).apply(instance, Radar::new));

        /** Whether there is a radar aboard at all. */
        public boolean fitted() {
            return this.range > 0.0F;
        }

        /** Whether this machine has anything to sweep for — a set, a receiver, or both. */
        public boolean exists() {
            return this.fitted() || this.warningRange > 0.0F;
        }

        /** The furthest anything is worth looking for: the radar's reach or the receiver's. */
        public double reach() {
            return Math.max(this.range, this.warningRange);
        }
    }
}
