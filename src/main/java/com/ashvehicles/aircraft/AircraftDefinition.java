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
        CameraMount camera, SoundSetup sound, List<Hardpoint> hardpoints) {

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
            Hardpoint.CODEC.listOf().optionalFieldOf("hardpoints", List.of()).forGetter(AircraftDefinition::hardpoints)
    ).apply(instance, AircraftDefinition::new));

    /**
     * Used when an aircraft has no file the game can read at all. Deliberately docile: it flies, so
     * the game keeps running and the log says what went wrong, but nobody will mistake it for the
     * real numbers.
     */
    public static final AircraftDefinition FALLBACK = new AircraftDefinition(
            Hitbox.DEFAULT,
            ModelSetup.DEFAULT,
            new Engine(0.02F, 0.02F),
            new Wing(0.0F, 0.7F, 0.038F, 5.5F, 15.0F, 0.006F, 0.02F, 0.15F),
            new Handling(1.5F, 3.0F, 1.0F, 0.25F, 3.0F, 0.85F),
            new Airframe(Airframe.DEFAULT_HEALTH, 0.9F, 3.0F, 0.0F, List.of(new Vec3(0.0, 0.5, 0.0))),
            new Undercarriage(40, 0.6F, 0.995F, 0.85F),
            new Surface(20, 0.5F, 0.4F),
            CameraMount.DEFAULT,
            SoundSetup.DEFAULT,
            List.of());

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

        private Bone() {
        }
    }

    /** @param maxThrust acceleration along the nose at full throttle
     *  @param throttleRate throttle travel per tick while a throttle key is held */
    public record Engine(float maxThrust, float throttleRate) {
        public static final Codec<Engine> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.fieldOf("max_thrust").forGetter(Engine::maxThrust),
                Codec.FLOAT.fieldOf("throttle_rate").forGetter(Engine::throttleRate)
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
     */
    public record Wing(float maxSpeed, float stallSpeed, float lift, float liftSlope, float stallAngle,
            float drag, float inducedDrag, float lateralDrag) {

        public static final Codec<Wing> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("max_speed", 0.0F).forGetter(Wing::maxSpeed),
                Codec.FLOAT.fieldOf("stall_speed").forGetter(Wing::stallSpeed),
                Codec.FLOAT.fieldOf("lift").forGetter(Wing::lift),
                Codec.FLOAT.optionalFieldOf("lift_slope", 5.5F).forGetter(Wing::liftSlope),
                Codec.FLOAT.optionalFieldOf("stall_angle", 15.0F).forGetter(Wing::stallAngle),
                Codec.FLOAT.fieldOf("drag").forGetter(Wing::drag),
                Codec.FLOAT.optionalFieldOf("induced_drag", 0.02F).forGetter(Wing::inducedDrag),
                Codec.FLOAT.optionalFieldOf("lateral_drag", 0.15F).forGetter(Wing::lateralDrag)
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
     */
    public record Handling(float pitchRate, float rollRate, float yawRate, float controlLag,
            float weathervane, float alphaLimit) {

        public static final Codec<Handling> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.fieldOf("pitch_rate").forGetter(Handling::pitchRate),
                Codec.FLOAT.fieldOf("roll_rate").forGetter(Handling::rollRate),
                Codec.FLOAT.fieldOf("yaw_rate").forGetter(Handling::yawRate),
                Codec.FLOAT.optionalFieldOf("control_lag", 0.25F).forGetter(Handling::controlLag),
                Codec.FLOAT.optionalFieldOf("weathervane", 3.0F).forGetter(Handling::weathervane),
                Codec.FLOAT.optionalFieldOf("alpha_limit", 0.85F).forGetter(Handling::alphaLimit)
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
     *              steadily out to there
     */
    public record SoundSetup(Optional<ResourceLocation> engine, Optional<ResourceLocation> gear,
            float volume, float idleVolume, float pitchMin, float pitchMax, float range) {
        public static final SoundSetup DEFAULT =
                new SoundSetup(Optional.empty(), Optional.empty(), 1.0F, 0.35F, 0.7F, 1.25F, 128.0F);

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
     */
    public record Undercarriage(int cycleTicks, float dragPenalty, float rollingFriction, float brakeFriction) {
        public static final Codec<Undercarriage> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("cycle_ticks").forGetter(Undercarriage::cycleTicks),
                Codec.FLOAT.fieldOf("drag_penalty").forGetter(Undercarriage::dragPenalty),
                Codec.FLOAT.optionalFieldOf("rolling_friction", 0.995F).forGetter(Undercarriage::rollingFriction),
                Codec.FLOAT.optionalFieldOf("brake_friction", 0.85F).forGetter(Undercarriage::brakeFriction)
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
