package com.ashvehicles.aircraft;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * One aircraft, described entirely in JSON. Drop a file in
 * {@code data/ashvehicles/ashvehicles/aircraft/} and the mod registers an entity type and an item
 * for it at start-up; no Java needed.
 *
 * <p>The file is read twice, for two different jobs. At start-up {@link AircraftLoader} reads the
 * copy inside the mod so that {@link Hitbox} is known while the registries are still open, since an
 * entity type's size is fixed the moment it is registered. At world load the same file goes through
 * the {@code ashvehicles:aircraft} data pack registry, and everything below the hitbox is read from
 * there instead, so a data pack can retune an aircraft and {@code /reload} will show it.
 *
 * <p>Speeds and accelerations are in blocks per tick and blocks per tick squared; at twenty ticks a
 * second, a speed of 1.0 is twenty blocks a second. Rates are degrees per tick.
 */
public record AircraftDefinition(Hitbox hitbox, ModelSetup model, Engine engine, Wing wing,
        Handling handling, Airframe airframe, Undercarriage landingGear, Surface flaps,
        CameraMount camera, SoundSetup sound, Radar radar, Countermeasures countermeasures,
        Optional<Vtol> vtol, List<Hardpoint> hardpoints, Sync sync) {

    public static final Codec<AircraftDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Hitbox.CODEC.optionalFieldOf("hitbox", Hitbox.DEFAULT).forGetter(AircraftDefinition::hitbox),
            ModelSetup.CODEC.optionalFieldOf("model", ModelSetup.DEFAULT).forGetter(AircraftDefinition::model),
            Engine.CODEC.fieldOf("engine").forGetter(AircraftDefinition::engine),
            Wing.CODEC.fieldOf("wing").forGetter(AircraftDefinition::wing),
            Handling.CODEC.fieldOf("handling").forGetter(AircraftDefinition::handling),
            Airframe.CODEC.fieldOf("airframe").forGetter(AircraftDefinition::airframe),
            Undercarriage.CODEC.fieldOf("landing_gear").forGetter(AircraftDefinition::landingGear),
            Surface.CODEC.fieldOf("flaps").forGetter(AircraftDefinition::flaps),
            CameraMount.CODEC.optionalFieldOf("camera", CameraMount.DEFAULT).forGetter(AircraftDefinition::camera),
            SoundSetup.CODEC.optionalFieldOf("sound", SoundSetup.DEFAULT).forGetter(AircraftDefinition::sound),
            Radar.CODEC.optionalFieldOf("radar", Radar.DEFAULT).forGetter(AircraftDefinition::radar),
            Countermeasures.CODEC.optionalFieldOf("countermeasures", Countermeasures.DEFAULT)
                    .forGetter(AircraftDefinition::countermeasures),
            Vtol.CODEC.optionalFieldOf("vtol").forGetter(AircraftDefinition::vtol),
            Hardpoint.CODEC.listOf().optionalFieldOf("hardpoints", List.of()).forGetter(AircraftDefinition::hardpoints),
            Sync.CODEC.optionalFieldOf("sync", Sync.DEFAULT).forGetter(AircraftDefinition::sync)
    ).apply(instance, AircraftDefinition::new));

    /**
     * Used when an aircraft has no file the game can read at all. Deliberately docile: it flies, so
     * the game keeps running and the log says what went wrong, but nobody will mistake it for the
     * real numbers.
     */
    public static final AircraftDefinition FALLBACK = new AircraftDefinition(
            Hitbox.DEFAULT,
            ModelSetup.DEFAULT,
            new Engine(0.02F, 0.02F, 0.06F, 1.0F),
            new Wing(0.0F, 0.7F, 0.038F, 5.5F, 15.0F, 0.006F, 0.02F, 0.15F, 0.28F, 6.0F, 0.0F),
            new Handling(1.5F, 3.0F, 1.0F, 0.25F, 3.0F, 0.85F, 0.06F),
            new Airframe(Airframe.DEFAULT_HEALTH, 1.8F, 3.0F, 0.0F, List.of(new Vec3(0.0, 0.5, 0.0))),
            new Undercarriage(40, 0.6F, 0.995F, 0.85F, 0.55F, 1.1F, 1.2F, 1.05F),
            new Surface(20, 0.5F, 0.4F),
            CameraMount.DEFAULT,
            SoundSetup.DEFAULT,
            Radar.DEFAULT,
            Countermeasures.DEFAULT,
            Optional.empty(),
            List.of(),
            Sync.DEFAULT);

    /**
     * The collision box, which Minecraft can only describe as a box with a square footprint, and
     * which is fixed when the entity type is registered. Read from the mod's own copy of the file at
     * start-up, so unlike everything else here a data pack cannot change it.
     *
     * @param trackingRange how far away, in chunks, other players are sent the aircraft in full
     * @param ghostRange how far away, in blocks, the aircraft keeps being sent at all. Past the
     *                   tracking range, and past the edge of the chunks a player has loaded, it goes
     *                   on being reported and is drawn as a ghost: an aeroplane at altitude is
     *                   visible from much further away than the ground beneath it, and stopping it
     *                   at the edge of the loaded world would have aircraft blinking out of the sky.
     *                   Zero or less removes the limit entirely, so an aircraft is reported wherever
     *                   it is in the same world, however far that is
     */
    public record Hitbox(float width, float height, int trackingRange, int ghostRange) {
        public static final Hitbox DEFAULT = new Hitbox(4.0F, 2.0F, 12, 0);

        public static final Codec<Hitbox> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.fieldOf("width").forGetter(Hitbox::width),
                Codec.FLOAT.fieldOf("height").forGetter(Hitbox::height),
                Codec.INT.optionalFieldOf("tracking_range", 12).forGetter(Hitbox::trackingRange),
                Codec.INT.optionalFieldOf("ghost_range", 0).forGetter(Hitbox::ghostRange)
        ).apply(instance, Hitbox::new));

        /** Whether the aircraft stops being reported at some distance at all. */
        public boolean hasGhostLimit() {
            return this.ghostRange > 0;
        }
    }

    /**
     * How to draw the aircraft. The geometry, texture and animation files are found by name, so an
     * aircraft called {@code su_25} is drawn from {@code geo/entity/su_25.geo.json} and
     * {@code textures/entity/su_25.png} without being told where they are.
     *
     * @param scale uniform scale applied to the model, for models not built at Minecraft's scale
     * @param bones which bone in the geometry plays which part, keyed by the roles listed in
     *              {@link Bone}. Anything left out simply does not move.
     */
    public record ModelSetup(float scale, Map<String, String> bones) {
        public static final ModelSetup DEFAULT = new ModelSetup(1.0F, Map.of());

        public static final Codec<ModelSetup> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("scale", 1.0F).forGetter(ModelSetup::scale),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("bones", Map.of())
                        .forGetter(ModelSetup::bones)
        ).apply(instance, ModelSetup::new));

        /** The bone named for a role, or empty if this aircraft has no such part. */
        public String bone(String role) {
            return this.bones.getOrDefault(role, "");
        }
    }

    /** The roles a bone can be given in {@link ModelSetup#bones}. */
    public static final class Bone {
        public static final String ELEVATOR_LEFT = "elevator_left";
        public static final String ELEVATOR_RIGHT = "elevator_right";
        public static final String AILERON_LEFT = "aileron_left";
        public static final String AILERON_RIGHT = "aileron_right";
        public static final String FLAP_LEFT = "flap_left";
        public static final String FLAP_RIGHT = "flap_right";
        public static final String RUDDER = "rudder";
        public static final String RUDDER_LEFT = "rudder_left";
        public static final String RUDDER_RIGHT = "rudder_right";
        public static final String NOSE_GEAR = "nose_gear";
        public static final String LEFT_GEAR = "left_gear";
        public static final String RIGHT_GEAR = "right_gear";
        public static final String NOSE_GEAR_DOOR = "nose_gear_door";
        public static final String LEFT_GEAR_DOOR = "left_gear_door";
        public static final String RIGHT_GEAR_DOOR = "right_gear_door";
        /**
         * The engine nozzle of a lift-capable aircraft, which swings down as the aircraft converts to
         * the hover. Everything else that opens for a lift system — fan doors, roll posts, auxiliary
         * intakes — is a sequence rather than one angle, and belongs in the aircraft's animation file
         * as {@code vtol_open} and {@code vtol_closed}; see
         * {@link com.ashvehicles.client.model.AircraftAnimations}.
         */
        public static final String NOZZLE = "nozzle";

        private Bone() {
        }
    }

    /**
     * How this aircraft is drawn on a client that is not flying it — see
     * {@code AircraftInterpolation}, which is where all three of these are spent and where the
     * reasoning behind them is written down.
     *
     * @param correctionTicks ticks over which all but a sliver of a correction is absorbed. Short
     *                        enough that the drawn aircraft is honest, long enough that a correction
     *                        is not a step. A couple of server ticks suits anything
     * @param snapDistance blocks of error past which the aircraft is simply put where it belongs
     *                     instead of sliding there. Should be larger than any error ordinary flight
     *                     can produce and smaller than a teleport
     * @param maxPredictionTicks ticks of dead reckoning trusted after the last correction. Past it
     *                           the aircraft is handed back rather than coasting on a stale velocity
     */
    public record Sync(int correctionTicks, double snapDistance, int maxPredictionTicks) {
        public static final Sync DEFAULT = new Sync(3, 8.0, 10);

        public static final Codec<Sync> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.optionalFieldOf("correction_ticks", 3).forGetter(Sync::correctionTicks),
                Codec.DOUBLE.optionalFieldOf("snap_distance", 8.0).forGetter(Sync::snapDistance),
                Codec.INT.optionalFieldOf("max_prediction_ticks", 10).forGetter(Sync::maxPredictionTicks)
        ).apply(instance, Sync::new));
    }

    /** @param maxThrust acceleration along the nose at full throttle
     *  @param throttleRate throttle travel per tick while a throttle key is held
     *  @param spoolRate fraction of the gap between delivered and commanded thrust closed each tick.
     *                   The lever is not the engine: a turbofan asked for full power takes several
     *                   seconds to give it, and that wait is most of what a takeoff roll feels like.
     *                   1 hands back the old behaviour, where the thrust followed the lever exactly
     *  @param seaLevelDensity air density at sea level, as a multiplier on thrust and lift. Thrust
     *                         falls off with it, which is what puts a ceiling on the aircraft */
    public record Engine(float maxThrust, float throttleRate, float spoolRate, float seaLevelDensity) {
        public static final Codec<Engine> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.fieldOf("max_thrust").forGetter(Engine::maxThrust),
                Codec.FLOAT.fieldOf("throttle_rate").forGetter(Engine::throttleRate),
                Codec.FLOAT.optionalFieldOf("spool_rate", 0.06F).forGetter(Engine::spoolRate),
                Codec.FLOAT.optionalFieldOf("sea_level_density", 1.0F).forGetter(Engine::seaLevelDensity)
        ).apply(instance, Engine::new));
    }

    /**
     * The wing, which is what actually holds the aircraft up. Lift is not a function of speed alone:
     * it comes from the angle the wing meets the airflow at, which is why an aircraft has to be
     * rotated to leave the ground and why hauling the nose up too far drops it out of the sky.
     *
     * @param maxSpeed backstop against a runaway, not a figure the aircraft should ever reach: drag
     *                 already settles the top speed. Zero or less removes it entirely
     * @param stallSpeed reference speed for control authority: below it the surfaces go soft
     * @param lift overall lift scale. Multiplied by the lift coefficient and the square of the
     *             airspeed, so level flight is wherever that product comes to gravity
     * @param liftSlope lift coefficient gained per radian of angle of attack, near enough 2 pi for
     *                  a thin wing and lower for a thick one
     * @param stallAngle angle of attack, in degrees, past which the airflow separates and the lift
     *                   falls away instead of growing
     * @param drag parasitic drag: what the shape costs, against the square of the airspeed
     * @param inducedDrag drag that comes with lift, against the square of the lift coefficient. This
     *                    is what makes a hard turn bleed speed
     * @param lateralDrag how quickly a sideways slip is killed. A fuselage does not fly sideways
     * @param groundEffect extra lift close to the ground, as a fraction of the free-air figure, dying
     *                     away over a wingspan's height. The cushion an aircraft rides off the runway
     *                     on and floats down the last few feet of a landing on
     * @param span the wingspan, in blocks, which is the height the ground effect reaches to
     * @param rotateSpeed the speed the elevator can first lift the nose off the runway at. Below it
     *                    the aircraft simply rolls, however hard the stick is pulled: there is not
     *                    enough air over the tailplane to raise anything, which is why a takeoff is
     *                    a run first and a rotation second rather than a nose-up wait for the wing to
     *                    catch up. Zero derives it from the stalling speed
     */
    public record Wing(float maxSpeed, float stallSpeed, float lift, float liftSlope, float stallAngle,
            float drag, float inducedDrag, float lateralDrag, float groundEffect, float span,
            float rotateSpeed) {

        /**
         * Fraction of the stalling speed the nose comes up at, for a file that names no figure.
         *
         * <p>Above it, not below, and that is the whole point of the number. An aeroplane rotated
         * below its stalling speed is flying on nothing but the cushion of air under it: it leaves
         * the runway, climbs out of that cushion, finds it has no wing left and settles back on —
         * over and over, porpoising down the runway instead of departing. Real practice is to rotate
         * a little above the stall and climb away at a little above that, which is what this is.
         */
        private static final float DEFAULT_ROTATE_FRACTION = 1.05F;

        /** The speed the nose can first be raised at, derived from the stalling speed if unset. */
        public float effectiveRotateSpeed() {
            return this.rotateSpeed > 0.0F ? this.rotateSpeed : this.stallSpeed * DEFAULT_ROTATE_FRACTION;
        }

        public static final Codec<Wing> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("max_speed", 0.0F).forGetter(Wing::maxSpeed),
                Codec.FLOAT.fieldOf("stall_speed").forGetter(Wing::stallSpeed),
                Codec.FLOAT.fieldOf("lift").forGetter(Wing::lift),
                Codec.FLOAT.optionalFieldOf("lift_slope", 5.5F).forGetter(Wing::liftSlope),
                Codec.FLOAT.optionalFieldOf("stall_angle", 15.0F).forGetter(Wing::stallAngle),
                Codec.FLOAT.fieldOf("drag").forGetter(Wing::drag),
                Codec.FLOAT.optionalFieldOf("induced_drag", 0.02F).forGetter(Wing::inducedDrag),
                Codec.FLOAT.optionalFieldOf("lateral_drag", 0.15F).forGetter(Wing::lateralDrag),
                Codec.FLOAT.optionalFieldOf("ground_effect", 0.28F).forGetter(Wing::groundEffect),
                Codec.FLOAT.optionalFieldOf("span", 10.0F).forGetter(Wing::span),
                Codec.FLOAT.optionalFieldOf("rotate_speed", 0.0F).forGetter(Wing::rotateSpeed)
        ).apply(instance, Wing::new));

        /**
         * How much lift this wing makes at a given angle of attack. It climbs steadily with the
         * angle until the airflow lets go of the wing, and past that it falls away rather than
         * growing, which is the whole of what a stall is. Fully gone at twice the critical angle.
         */
        public double liftCoefficient(float angleOfAttackDegrees) {
            double stall = Math.max(this.stallAngle, 1.0F);
            double magnitude = Math.abs(angleOfAttackDegrees);

            if (magnitude <= stall) {
                return this.liftSlope * Math.toRadians(angleOfAttackDegrees);
            }

            double peak = this.liftSlope * Math.toRadians(stall);
            double remaining = Math.max(0.0, 1.0 - (magnitude - stall) / stall);

            return Math.signum(angleOfAttackDegrees) * peak * remaining;
        }
    }

    /**
     * What the controls do. The rates are what full deflection eventually reaches, not what it
     * reaches at once: a control surface has to work against the mass of the aircraft.
     *
     * @param controlLag fraction of the gap between the current and commanded rate closed each tick.
     *                   1 is the old instant response; lower gives the aircraft weight
     * @param weathervane degrees per tick the nose is dragged round onto the flight path. This is
     *                    the fin doing its job, and it is what turns a bank into a turn: the wing
     *                    pulls the aircraft round and the tail points it where it is going
     * @param alphaLimit how much of the stalling angle the pilot is allowed to reach, as a fraction.
     *                   The elevator can swing the nose several times faster than the wing can
     *                   follow, so a pilot hauling back on the stick reaches the stall in a third of
     *                   a second and stops turning altogether. This holds the aircraft at the angle
     *                   where the wing pulls hardest, which is the tightest turn available to it.
     *                   Set it to 1 or more to hand the stall back to the pilot
     * @param aeroDamping how much the airflow resists the aircraft being rotated, against the square
     *                    of the airspeed. The same surfaces that give the pilot authority also damp
     *                    the rotation they cause, which is why a fast aircraft is stiff rather than
     *                    twitchy. Without it, control authority rises with speed and nothing rises
     *                    with it to settle the result, and the aircraft wallows. Zero removes it
     */
    public record Handling(float pitchRate, float rollRate, float yawRate, float controlLag,
            float weathervane, float alphaLimit, float aeroDamping) {

        public static final Codec<Handling> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.fieldOf("pitch_rate").forGetter(Handling::pitchRate),
                Codec.FLOAT.fieldOf("roll_rate").forGetter(Handling::rollRate),
                Codec.FLOAT.fieldOf("yaw_rate").forGetter(Handling::yawRate),
                Codec.FLOAT.optionalFieldOf("control_lag", 0.25F).forGetter(Handling::controlLag),
                Codec.FLOAT.optionalFieldOf("weathervane", 3.0F).forGetter(Handling::weathervane),
                Codec.FLOAT.optionalFieldOf("alpha_limit", 0.85F).forGetter(Handling::alphaLimit),
                Codec.FLOAT.optionalFieldOf("aero_damping", 0.06F).forGetter(Handling::aeroDamping)
        ).apply(instance, Handling::new));
    }

    /**
     * @param health how much the airframe can take before it comes apart, in hit points. Damage is
     *               taken off point for point: a cannon shell that would cost a player two hearts
     *               costs the aeroplane four of these. Left out, {@link #DEFAULT_HEALTH}
     * @param crashSpeed impact speed above which hitting something writes the aircraft off
     * @param maxG how many times its own weight the airframe is stressed for. Pull harder than this
     *             and it starts to come apart. Zero or less means it never will
     * @param seats one entry per seat, along the aircraft's own axes: x right, y up, z towards the
     *              nose. The number of entries is the number of people who can climb aboard.
     */
    public record Airframe(float health, float crashSpeed, float explosionPower, float maxG,
            List<Vec3> seats) {

        /** What an aeroplane is worth in hit points if its file does not say. */
        public static final float DEFAULT_HEALTH = 300.0F;

        public static final Codec<Airframe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("health", DEFAULT_HEALTH).forGetter(Airframe::health),
                Codec.FLOAT.fieldOf("crash_speed").forGetter(Airframe::crashSpeed),
                Codec.FLOAT.fieldOf("explosion_power").forGetter(Airframe::explosionPower),
                Codec.FLOAT.optionalFieldOf("max_g", 0.0F).forGetter(Airframe::maxG),
                Vec3.CODEC.listOf().fieldOf("seats").forGetter(Airframe::seats)
        ).apply(instance, Airframe::new));
    }

    /**
     * Where the camera sits while flying this aircraft. Minecraft's four blocks behind the pilot's
     * head puts the viewer inside the fuselage of anything bigger than a horse.
     *
     * @param pos third-person offset from the middle of the aircraft, measured along the viewing
     *            axes: x to the right of the view, y straight up, z along the line of sight, so a
     *            camera behind and above has a negative z and a positive y. Measuring along the view
     *            rather than the aircraft's heading is what keeps the whole aeroplane in frame while
     *            it climbs and rolls. Terrain between the aircraft and the camera still pulls it in,
     *            so the distance is a maximum rather than a promise.
     * @param cockpit first-person eye position, in the aircraft's own axes like {@link
     *                Airframe#seats}: x right, y up, z towards the nose. This one is bolted to the
     *                airframe, pitch and bank included, so the view rolls with the wings.
     */
    public record CameraMount(Vec3 pos, Vec3 cockpit) {
        public static final CameraMount DEFAULT =
                new CameraMount(new Vec3(0.0, 2.5, -24.0), new Vec3(0.0, 2.5, 3.4));

        public static final Codec<CameraMount> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Vec3.CODEC.fieldOf("pos").forGetter(CameraMount::pos),
                Vec3.CODEC.fieldOf("cockpit").forGetter(CameraMount::cockpit)
        ).apply(instance, CameraMount::new));
    }

    /**
     * What the engine sounds like. The recording itself lives in the resource pack, in
     * {@code sounds.json} and an {@code .ogg} like any other Minecraft sound; this only says which
     * one to use and how to play it.
     *
     * <p>The recording is found in this order: the {@code engine} event named here if there is one;
     * failing that, an event named after the aircraft, so {@code su_25} looks for
     * {@code ashvehicles:engine.su_25}; and failing that the mod's default,
     * {@code ashvehicles:engine.default}, which is {@code sounds/enginesound.ogg}. So an aircraft
     * with no recording of its own still sounds like something, and giving it one is a matter of
     * dropping in the file and listing it in {@code sounds.json}, with nothing to change here.
     *
     * <p>The recording should be a steady loop of the engine at a constant setting: throttle is
     * expressed by playing it louder and faster, not by switching recordings.
     *
     * @param engine sound event to use, or empty to look one up by the aircraft's name
     * @param gear sound event for the undercarriage travelling, or empty to look one up by the
     *             aircraft's name. Also a loop, played only while the legs are on their way, and
     *             played at one volume and one pitch: the figures below are the engine's alone
     * @param volume how loud at full throttle, next to the aircraft; 1 is the recording as made
     * @param idleVolume fraction of that at zero throttle while the engine is still turning
     * @param pitchMin playback speed at zero throttle
     * @param pitchMax playback speed at full throttle
     * @param range distance, in blocks, beyond which the engine cannot be heard at all. It fades
     *              steadily out to there. Measured in hundreds of blocks rather than tens: a jet is
     *              heard long before it is seen, and an aeroplane that goes silent at the edge of the
     *              render distance is an aeroplane nobody can be sneaked up on by
     */
    public record SoundSetup(Optional<ResourceLocation> engine, Optional<ResourceLocation> gear,
            float volume, float idleVolume, float pitchMin, float pitchMax, float range) {
        public static final SoundSetup DEFAULT =
                new SoundSetup(Optional.empty(), Optional.empty(), 1.0F, 0.35F, 0.7F, 1.25F, 512.0F);

        public static final Codec<SoundSetup> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.optionalFieldOf("engine").forGetter(SoundSetup::engine),
                ResourceLocation.CODEC.optionalFieldOf("gear").forGetter(SoundSetup::gear),
                Codec.FLOAT.optionalFieldOf("volume", DEFAULT.volume()).forGetter(SoundSetup::volume),
                Codec.FLOAT.optionalFieldOf("idle_volume", DEFAULT.idleVolume()).forGetter(SoundSetup::idleVolume),
                Codec.FLOAT.optionalFieldOf("pitch_min", DEFAULT.pitchMin()).forGetter(SoundSetup::pitchMin),
                Codec.FLOAT.optionalFieldOf("pitch_max", DEFAULT.pitchMax()).forGetter(SoundSetup::pitchMax),
                Codec.FLOAT.optionalFieldOf("range", DEFAULT.range()).forGetter(SoundSetup::range)
        ).apply(instance, SoundSetup::new));
    }

    /**
     * The aircraft's radar, and how far its warning receiver can hear.
     *
     * <p>The radar looks forward and nowhere else: it sweeps a cone about the nose, so finding
     * somebody is a matter of pointing the aeroplane at where they might be, and turning away from a
     * contact loses it. That is what makes a radar worth having rather than a map of the sky.
     *
     * <p>The warning receiver is the other way round and has no cone at all. It hears somebody
     * else's radar wherever it is coming from, which is the whole point of one: what it is for is
     * the thing you did not see, and that is behind you.
     *
     * @param range how far the radar sees, in blocks — kilometres rather than hundreds of blocks,
     *              because that is the distance at which one aeroplane finds another and there is
     *              nothing else out there to find. Zero or less means the aircraft has none, and a
     *              pilot with no radar has no scope and can lock only what their seeker reaches
     * @param arc half-angle of the sweep, in degrees off the nose
     * @param sweepTicks how often the picture is redrawn. A radar does not see continuously; it
     *                   sweeps, and what is on the scope is where things were when it last passed
     * @param warningRange how far off somebody can be and still set off the warning receiver, in
     *                     blocks. Generous next to the radar's own reach: being painted from further
     *                     away than you can see is exactly the situation worth being told about
     */
    public record Radar(float range, float arc, int sweepTicks, float warningRange) {
        public static final Radar DEFAULT = new Radar(3000.0F, 55.0F, 10, 4000.0F);

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

        /** The furthest anything is worth looking for: the radar's reach or the receiver's. */
        public double reach() {
            return Math.max(this.range, this.warningRange);
        }
    }

    /**
     * A thrust-vectoring lift system: the nozzle that swings down and everything that follows from
     * it. Absent for an aeroplane that has to use a runway like everybody else.
     *
     * <p>What a nozzle at ninety degrees does is turn the engine from something that pushes the
     * aircraft along into something that holds it up, and the flight model needs nothing else told to
     * it — the wing stops making lift on its own once the aircraft has stopped moving, and gravity
     * was always there. The three figures below are what the engine has to be worth for that to work,
     * what flies the aeroplane once the wing has given up, and what stops a hover being a slide.
     *
     * @param maxAngle how far the nozzle swings, in degrees. Ninety is straight down
     * @param rate how fast it swings, in degrees per tick. The whole conversion at ninety degrees
     *             therefore takes {@code maxAngle / rate} ticks
     * @param liftThrust acceleration the engine manages with the nozzle fully down, in blocks per
     *                   tick squared. <b>It has to beat gravity</b> — {@value #GRAVITY_NOTE} — or the
     *                   aeroplane cannot hover, only fall slowly. Between the two ends it is blended
     *                   with the ordinary {@code engine.max_thrust}, so cruise thrust is unaffected
     * @param authority how much control the reaction jets give with the nozzle fully down, as a
     *                  fraction of what a wing at flying speed gives. Without this a hovering
     *                  aeroplane has no control at all, the air not moving over anything
     * @param hoverDrag how quickly a hover bleeds off sideways drift, per tick. Nothing to do with
     *                  the aerodynamic drag above, which does nothing at all at a walking pace
     * @param conversionSpeed the speed the conversion is finished at, in blocks per tick, and the
     *                        two things that follow from it. The nozzle will not swing <em>down</em>
     *                        above it, which stops it being used as an air brake at full speed; and
     *                        coming back up it is scheduled against the airspeed rather than swung on
     *                        a stopwatch, reaching fully aft here. Wants to be comfortably above the
     *                        wing's stalling speed, since that is what the lift is being handed to
     */
    public record Vtol(float maxAngle, float rate, float liftThrust, float authority, float hoverDrag,
            float conversionSpeed) {

        /** Quoted in the docs above so the figure to beat is written down beside what beats it. */
        static final String GRAVITY_NOTE = "0.02453 blocks per tick squared";

        public static final Vtol DEFAULT = new Vtol(90.0F, 1.5F, 0.030F, 0.9F, 0.06F, 2.2F);

        public static final Codec<Vtol> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("max_angle", DEFAULT.maxAngle()).forGetter(Vtol::maxAngle),
                Codec.FLOAT.optionalFieldOf("rate", DEFAULT.rate()).forGetter(Vtol::rate),
                Codec.FLOAT.optionalFieldOf("lift_thrust", DEFAULT.liftThrust()).forGetter(Vtol::liftThrust),
                Codec.FLOAT.optionalFieldOf("authority", DEFAULT.authority()).forGetter(Vtol::authority),
                Codec.FLOAT.optionalFieldOf("hover_drag", DEFAULT.hoverDrag()).forGetter(Vtol::hoverDrag),
                Codec.FLOAT.optionalFieldOf("conversion_speed", DEFAULT.conversionSpeed())
                        .forGetter(Vtol::conversionSpeed)
        ).apply(instance, Vtol::new));

        /** Ticks the nozzle takes to travel from stowed to fully down. */
        public int cycleTicks() {
            return (int) Math.max(this.maxAngle / Math.max(this.rate, 1.0E-3F), 1.0F);
        }
    }

    /**
     * What the aircraft can throw out behind it to spoil somebody's aim.
     *
     * <p>Two sorts, and which one to reach for is the question the warning receiver has just
     * answered. A flare is a fire hotter than an engine and fools anything homing on heat; chaff is
     * a cloud of foil that fools anything homing on a radar return. Firing the wrong one is firing
     * nothing at all, which is what makes the receiver worth reading rather than worth ignoring.
     *
     * @param flares how many are carried, or zero for an aircraft that carries none
     * @param chaff the same, for the other sort
     * @param intervalTicks how quickly the dispenser will let go of the next one. Held down, this is
     *                      the rate at which the load is spent
     * @param reloadTicks how long the ground crew take to refill a whole load, with the aircraft
     *                    parked. Counted for the full load however much of it is missing
     * @param speed how hard they are thrown clear of the aircraft, in blocks per tick
     */
    public record Countermeasures(int flares, int chaff, int intervalTicks, int reloadTicks, float speed) {
        public static final Countermeasures DEFAULT = new Countermeasures(30, 30, 6, 300, 0.35F);

        public static final Codec<Countermeasures> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.optionalFieldOf("flares", DEFAULT.flares()).forGetter(Countermeasures::flares),
                Codec.INT.optionalFieldOf("chaff", DEFAULT.chaff()).forGetter(Countermeasures::chaff),
                Codec.INT.optionalFieldOf("interval_ticks", DEFAULT.intervalTicks()).forGetter(Countermeasures::intervalTicks),
                Codec.INT.optionalFieldOf("reload_ticks", DEFAULT.reloadTicks()).forGetter(Countermeasures::reloadTicks),
                Codec.FLOAT.optionalFieldOf("speed", DEFAULT.speed()).forGetter(Countermeasures::speed)
        ).apply(instance, Countermeasures::new));

        /** How many of the given sort are carried when full. */
        public int capacity(boolean flare) {
            return flare ? this.flares : this.chaff;
        }
    }

    /**
     * Somewhere a weapon hangs. Every weapon fires straight along the nose; the point is where its
     * rounds leave from, and where a pod is drawn.
     *
     * <p>A hardpoint with a {@code fixed} weapon is part of the airframe: it always carries that
     * weapon, nothing else can be hung on it, and no pod is drawn there because the aircraft's own
     * model already shows it. A hardpoint without one is a bare pylon, and takes whatever weapon
     * item the player offers it.
     *
     * @param name a label for the log and for telling pylons apart; not shown to the player
     * @param pos position in the aircraft's own axes: x right, y up, z towards the nose. For a gun,
     *            put it at the muzzle
     * @param fixed the weapon built in here, or empty for a pylon
     */
    public record Hardpoint(String name, Vec3 pos, Optional<ResourceLocation> fixed) {
        public static final Codec<Hardpoint> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("name", "").forGetter(Hardpoint::name),
                Vec3.CODEC.fieldOf("pos").forGetter(Hardpoint::pos),
                ResourceLocation.CODEC.optionalFieldOf("fixed").forGetter(Hardpoint::fixed)
        ).apply(instance, Hardpoint::new));

        public boolean isFixed() {
            return this.fixed.isPresent();
        }
    }

    /**
     * The undercarriage: a surface that swings out into the airflow, and also what the aircraft
     * rolls along on before it is flying.
     *
     * @param cycleTicks time to travel from stowed to fully down
     * @param dragPenalty extra drag when down, as a fraction of the clean-airframe figure
     * @param rollingFriction the fraction of ground speed left after a tick of rolling on the
     *                        wheels. Wheels roll; anything much below 1 here and the aircraft can
     *                        never reach flying speed along the runway
     * @param brakeFriction the same while the brakes are on
     * @param lateralFriction the fraction of <em>sideways</em> speed left after a tick. A wheel rolls
     *                        one way and scrubs the other, and that difference is the whole of why an
     *                        aircraft tracks down a runway instead of sliding about on it. Much
     *                        nearer zero than the rolling figure
     * @param steerRate degrees per tick the nosewheel can swing the aircraft round at taxiing pace.
     *                  Nothing to do with the rudder: a wheel on the ground does not care how fast
     *                  the air is going past the fin, which is why an aircraft can be steered off a
     *                  stand at walking pace and the aerodynamic controls cannot do it
     * @param steerFade speed, in blocks per tick, by which nosewheel steering has faded out. Beyond
     *                  it the rudder is doing the work, and a nosewheel that still bit at speed would
     *                  simply throw the aircraft off the runway
     * @param climbHeight how big a step the undercarriage rolls over, in blocks, rather than running
     *                    into. An aircraft with none cannot cross the lip of a single block: the
     *                    collision box catches it, and since the aircraft has to be travelling faster
     *                    than its own crash speed to fly at all, every takeoff from anything but a
     *                    dead-flat runway ended in an explosion. This is the undercarriage doing what
     *                    an undercarriage does, and it applies only while it is down and on the ground
     */
    public record Undercarriage(int cycleTicks, float dragPenalty, float rollingFriction, float brakeFriction,
            float lateralFriction, float steerRate, float steerFade, float climbHeight) {
        public static final Codec<Undercarriage> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("cycle_ticks").forGetter(Undercarriage::cycleTicks),
                Codec.FLOAT.fieldOf("drag_penalty").forGetter(Undercarriage::dragPenalty),
                Codec.FLOAT.optionalFieldOf("rolling_friction", 0.995F).forGetter(Undercarriage::rollingFriction),
                Codec.FLOAT.optionalFieldOf("brake_friction", 0.85F).forGetter(Undercarriage::brakeFriction),
                Codec.FLOAT.optionalFieldOf("lateral_friction", 0.55F).forGetter(Undercarriage::lateralFriction),
                Codec.FLOAT.optionalFieldOf("steer_rate", 1.1F).forGetter(Undercarriage::steerRate),
                Codec.FLOAT.optionalFieldOf("steer_fade", 1.2F).forGetter(Undercarriage::steerFade),
                Codec.FLOAT.optionalFieldOf("climb_height", 1.05F).forGetter(Undercarriage::climbHeight)
        ).apply(instance, Undercarriage::new));
    }

    /**
     * Something that swings out into the airflow.
     *
     * @param cycleTicks time to travel from stowed to fully deployed
     * @param dragPenalty extra drag when deployed, as a fraction of the clean-airframe figure
     * @param liftBonus extra lift when deployed, as a fraction of the clean-wing figure
     */
    public record Surface(int cycleTicks, float dragPenalty, float liftBonus) {
        public static final Codec<Surface> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("cycle_ticks").forGetter(Surface::cycleTicks),
                Codec.FLOAT.fieldOf("drag_penalty").forGetter(Surface::dragPenalty),
                Codec.FLOAT.optionalFieldOf("lift_bonus", 0.0F).forGetter(Surface::liftBonus)
        ).apply(instance, Surface::new));
    }
}
