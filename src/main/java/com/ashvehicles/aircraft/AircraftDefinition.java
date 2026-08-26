package com.ashvehicles.aircraft;

import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.vehicle.VehicleType;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * One aircraft, described entirely in JSON. Drop a file in
 * {@code data/ashvehicles/ashvehicles/aircraft/} and the mod registers an entity type and an item
 * for it at start-up; no Java needed.
 *
 * <p>The file is read twice, for two different jobs. At start-up {@link DefinitionRegistry} reads the
 * copy inside the mod so that {@link Hitbox} is known while the registries are still open, since an
 * entity type's size is fixed the moment it is registered. At world load the same file goes through
 * the {@code ashvehicles:aircraft} data pack registry, and everything below the hitbox is read from
 * there instead, so a data pack can retune an aircraft and {@code /reload} will show it.
 *
 * <p>Speeds and accelerations are in blocks per tick and blocks per tick squared; at twenty ticks a
 * second, a speed of 1.0 is twenty blocks a second. Rates are degrees per tick.
 */
public record AircraftDefinition(VehicleChassis.Hitbox hitbox, VehicleChassis.Model model, Engine engine, Wing wing,
        Handling handling, Airframe airframe, Undercarriage landingGear, Surface flaps,
        VehicleChassis.CameraMount camera, VehicleChassis.Sound sound, VehicleChassis.Radar radar, Signature signature,
        Countermeasures countermeasures, VehicleType type, Optional<Vtol> vtol, Optional<Rotor> rotor,
        List<Hardpoint> hardpoints, Sync sync) {


    /**
     * The two ways an aircraft can be held up by something other than a wing, read as one field.
     *
     * <p>Only because a codec group cannot be longer than sixteen entries, and this pair is the one
     * worth spending the seam on: an aeroplane borrowing its engine for a minute and a helicopter
     * that has no other way of flying are alternatives, and nothing sensible has both. The two
     * {@code vtol} and {@code rotor} blocks are read and written exactly as if they were separate,
     * because as far as a file is concerned they are.
     */
    private static final MapCodec<Pair<Optional<Vtol>, Optional<Rotor>>> LIFT_SYSTEM =
            Codec.mapPair(Vtol.CODEC.optionalFieldOf("vtol"), Rotor.CODEC.optionalFieldOf("rotor"));

    /**
     * The kind of aircraft, read alongside the lift system as one field for the same reason the two
     * halves of that system are — a codec group cannot be longer than sixteen entries, and the kind
     * is small enough to ride along with the pair it is about. An aeroplane and a helicopter differ
     * in exactly what {@code vtol} and {@code rotor} describe, so the type belongs with them; it is
     * read and written as a plain {@code "type"} field beside {@code "vtol"} and {@code "rotor"}, as
     * if it were its own entry.
     */
    private static final MapCodec<Pair<VehicleType, Pair<Optional<Vtol>, Optional<Rotor>>>> KIND_AND_LIFT =
            Codec.mapPair(VehicleType.CODEC.optionalFieldOf("type", VehicleType.AIRCRAFT), LIFT_SYSTEM);

    public static final Codec<AircraftDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            VehicleChassis.Hitbox.CODEC.optionalFieldOf("hitbox", VehicleChassis.Hitbox.DEFAULT).forGetter(AircraftDefinition::hitbox),
            VehicleChassis.Model.CODEC.optionalFieldOf("model", VehicleChassis.Model.DEFAULT).forGetter(AircraftDefinition::model),
            Engine.CODEC.fieldOf("engine").forGetter(AircraftDefinition::engine),
            Wing.CODEC.fieldOf("wing").forGetter(AircraftDefinition::wing),
            Handling.CODEC.fieldOf("handling").forGetter(AircraftDefinition::handling),
            Airframe.CODEC.fieldOf("airframe").forGetter(AircraftDefinition::airframe),
            Undercarriage.CODEC.fieldOf("landing_gear").forGetter(AircraftDefinition::landingGear),
            Surface.CODEC.fieldOf("flaps").forGetter(AircraftDefinition::flaps),
            VehicleChassis.CameraMount.CODEC.optionalFieldOf("camera", VehicleChassis.CameraMount.DEFAULT).forGetter(AircraftDefinition::camera),
            VehicleChassis.Sound.CODEC.optionalFieldOf("sound", VehicleChassis.Sound.DEFAULT).forGetter(AircraftDefinition::sound),
            VehicleChassis.Radar.CODEC.optionalFieldOf("radar", VehicleChassis.Radar.DEFAULT)
                    .forGetter(AircraftDefinition::radar),
            Signature.CODEC.optionalFieldOf("signature", Signature.DEFAULT).forGetter(AircraftDefinition::signature),
            Countermeasures.CODEC.optionalFieldOf("countermeasures", Countermeasures.DEFAULT)
                    .forGetter(AircraftDefinition::countermeasures),
            KIND_AND_LIFT.forGetter(definition ->
                    Pair.of(definition.type(), Pair.of(definition.vtol(), definition.rotor()))),
            Hardpoint.CODEC.listOf().optionalFieldOf("hardpoints", List.of()).forGetter(AircraftDefinition::hardpoints),
            Sync.CODEC.optionalFieldOf("sync", Sync.DEFAULT).forGetter(AircraftDefinition::sync)
    ).apply(instance, (hitbox, model, engine, wing, handling, airframe, landingGear, flaps, camera,
            sound, radar, signature, countermeasures, kindLift, hardpoints, sync) ->
            new AircraftDefinition(hitbox, model, engine, wing, handling, airframe, landingGear, flaps,
                    camera, sound, radar, signature, countermeasures, kindLift.getFirst(),
                    kindLift.getSecond().getFirst(), kindLift.getSecond().getSecond(),
                    hardpoints, sync)));

    /**
     * Used when an aircraft has no file the game can read at all. Deliberately docile: it flies, so
     * the game keeps running and the log says what went wrong, but nobody will mistake it for the
     * real numbers.
     */
    public static final AircraftDefinition FALLBACK = new AircraftDefinition(
            VehicleChassis.Hitbox.DEFAULT,
            VehicleChassis.Model.DEFAULT,
            new Engine(0.02F, 0.02F, 0.06F, 1.0F, Optional.empty()),
            new Wing(0.0F, 0.7F, 0.038F, 5.5F, 15.0F, 0.006F, 20.0F, 0.02F, 0.15F, 0.28F, 6.0F, 0.0F),
            new Handling(1.5F, 3.0F, 1.0F, 0.25F, 3.0F, 0.85F, 0.06F),
            new Airframe(Airframe.DEFAULT_HEALTH, 1.8F, 3.0F, 0.0F, 0,
                    List.of(VehicleChassis.Seat.at(new Vec3(0.0, 0.5, 0.0)))),
            new Undercarriage(40, 0.6F, 0.995F, 0.85F, 0.55F, 1.1F, 1.2F, 1.05F, true, Optional.empty()),
            new Surface(20, 0.5F, 0.4F),
            VehicleChassis.CameraMount.DEFAULT,
            VehicleChassis.Sound.DEFAULT,
            VehicleChassis.Radar.DEFAULT,
            Signature.DEFAULT,
            Countermeasures.DEFAULT,
            VehicleType.AIRCRAFT,
            Optional.empty(),
            Optional.empty(),
            List.of(),
            Sync.DEFAULT);

    /**
     * Whether this is a helicopter: one that says so, or one that has a rotor and lets the rotor
     * say it. A file made before the type was a field names no type and is read as an aeroplane,
     * but a rotor block has always been the thing that makes it fly like a helicopter, so either is
     * taken as the answer and the older files keep working with nothing changed in them.
     */
    public boolean isHelicopter() {
        return this.type == VehicleType.HELICOPTER || this.rotor.isPresent();
    }

    /**
     * The speed, in blocks per tick, this machine can arrive on its wheels at and be walked away
     * from. A file may name its own; otherwise it is the one the kind of machine gets, and the two
     * are far apart — an aeroplane lands at a speed a helicopter could not survive touching
     * anything at. See {@link Undercarriage#landingSpeed}.
     */
    public float landingSpeed() {
        return this.landingGear.landingSpeed().orElse(this.isHelicopter()
                ? Undercarriage.HELICOPTER_LANDING_SPEED
                : Undercarriage.DEFAULT_LANDING_SPEED);
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
        /**
         * The main rotor of a helicopter, which turns about its own mast — so about the model's
         * vertical, whatever the aircraft is doing. Named here rather than animated because it is one
         * angle rather than a sequence: how fast it is turning is something the aircraft already
         * knows, and an animation file would have to be retimed every time that changed.
         */
        public static final String ROTOR = "rotor";
        /** The tail rotor, which turns about the model's lateral axis, its disc facing sideways. */
        public static final String TAIL_ROTOR = "tail_rotor";

        private Bone() {
        }
    }

    /**
     * How this aircraft is drawn on a client that is not flying it — see
     * {@code AircraftInterpolation}, which is where all three of these are spent and where the
     * reasoning behind them is written down.
     *
     * @param correctionTicks ticks a correction is flown out over. Short enough that the drawn
     *                        aircraft is honest, long enough that no correction is ever a step in the
     *                        speed it appears to be doing. A few server ticks suits anything
     * @param snapDistance blocks of error past which the aircraft is simply put where it belongs
     *                     instead of sliding there. Should be larger than any error ordinary flight
     *                     can produce and smaller than a teleport
     * @param maxPredictionTicks ticks of dead reckoning trusted after the last correction. Past it
     *                           the aircraft is handed back rather than coasting on a stale velocity
     */
    public record Sync(int correctionTicks, double snapDistance, int maxPredictionTicks) {
        public static final Sync DEFAULT = new Sync(5, 8.0, 10);

        public static final Codec<Sync> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.optionalFieldOf("correction_ticks", 5).forGetter(Sync::correctionTicks),
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
    public record Engine(float maxThrust, float throttleRate, float spoolRate, float seaLevelDensity,
            Optional<Afterburner> afterburner) {
        public static final Codec<Engine> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.fieldOf("max_thrust").forGetter(Engine::maxThrust),
                Codec.FLOAT.fieldOf("throttle_rate").forGetter(Engine::throttleRate),
                Codec.FLOAT.optionalFieldOf("spool_rate", 0.06F).forGetter(Engine::spoolRate),
                Codec.FLOAT.optionalFieldOf("sea_level_density", 1.0F).forGetter(Engine::seaLevelDensity),
                Afterburner.CODEC.optionalFieldOf("afterburner").forGetter(Engine::afterburner)
        ).apply(instance, Engine::new));
    }

    /**
     * Reheat: fuel sprayed into the jet pipe behind the turbine and lit there.
     *
     * <p>Not simply more throttle, and a file that tunes it as if it were has missed the point. At
     * the top of the military range the engine is already delivering everything it has; what the
     * afterburner does is burn fuel that has been through the engine once, in the only part of it
     * with any oxygen left, for a large gain in thrust and a larger one in everything a fighter
     * spends its time trying not to be. It is loud, it is visible, and it is hot -- and the last of
     * those is the one that matters, because anything homing on heat sees it from much further off.
     * Which is why the interesting decision is not whether to fit one but when to light it.
     *
     * <p><b>The gate.</b> There is no key for this. A throttle lever has a stop at full military
     * power and a detent past it, and the pilot has to push through the stop deliberately to reach
     * the reheat range; here, holding the throttle open with the lever already against its stop is
     * that push. See {@code AircraftEntity}, which owns the latch.
     *
     * @param thrust multiplier on {@code max_thrust} in full reheat. Half again is about right for
     *               a fighter; anything past double is a rocket rather than an aeroplane
     * @param lightRate fraction of the gap between commanded and delivered reheat closed each tick.
     *                  Quicker than the engine spools, because lighting the burner is a match rather
     *                  than a turbine coming up to speed. One lights it in a single tick
     * @param heat multiplier on the aircraft's infrared signature while fully lit. This is what it
     *             costs, and the reason a pilot who knows they are being hunted stays out of reheat.
     *             It counts against {@link Signature#heat}, which is the airframe cold: the product
     *             of the two is what a seeker is looking for, and one is as hot as anything gets
     * @param nozzles where the plume leaves the aircraft, in the aircraft's own axes -- {@code +Z}
     *                along the nose, {@code +Y} up through the canopy -- one entry per jet pipe. An
     *                empty list puts a single one out of the tail, worked out from the collision
     *                shape, which is right for one engine buried in the fuselage and wrong for a
     *                pair of them set apart
     */
    public record Afterburner(float thrust, float lightRate, float heat, List<Vec3> nozzles) {
        public static final Codec<Afterburner> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("thrust", 1.5F).forGetter(Afterburner::thrust),
                Codec.FLOAT.optionalFieldOf("light_rate", 0.2F).forGetter(Afterburner::lightRate),
                Codec.FLOAT.optionalFieldOf("heat", 3.0F).forGetter(Afterburner::heat),
                Vec3.CODEC.listOf().optionalFieldOf("nozzles", List.of()).forGetter(Afterburner::nozzles)
        ).apply(instance, Afterburner::new));

        /** The multiplier on thrust at this much reheat: one at nothing, {@code thrust} at full. */
        public double thrustFactor(float reheat) {
            return 1.0 + Math.max(this.thrust - 1.0F, 0.0F) * Mth.clamp(reheat, 0.0F, 1.0F);
        }

        /** The same for the infrared signature, which is what the thrust above is paid for with. */
        public float heatFactor(float reheat) {
            return 1.0F + Math.max(this.heat - 1.0F, 0.0F) * Mth.clamp(reheat, 0.0F, 1.0F);
        }
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
     * @param airBrakeDrag the multiplier {@code drag} is put through with the air brake out. A
     *                     board stood up in the airflow rather than anything the wing is doing, so
     *                     it is a multiplier on the shape's own drag and not a thing of its own —
     *                     and has to be sized against how slippery {@code drag} already says this
     *                     airframe is: a board worth four times the drag of a draggy old shape is
     *                     lost in the noise held out behind a clean one
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
            float drag, float airBrakeDrag, float inducedDrag, float lateralDrag, float groundEffect,
            float span, float rotateSpeed) {

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
                Codec.FLOAT.optionalFieldOf("air_brake_drag", 20.0F).forGetter(Wing::airBrakeDrag),
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
     * @param salvage how much metal is left in a wreck of one, in iron ingots, once it has been
     *                destroyed and somebody comes along with a wrench. Left out, it is worked out
     *                from the health instead
     * @param seats one entry per seat, along the aircraft's own axes: x right, y up, z towards the
     *              nose. The number of entries is the number of people who can climb aboard. Each
     *              is a bare point or a block that also says where that crew member looks out from,
     *              which is what a two-seater wants: see {@link VehicleChassis.Seat}
     */
    public record Airframe(float health, float crashSpeed, float explosionPower, float maxG,
            int salvage, List<VehicleChassis.Seat> seats) {

        /** What an aeroplane is worth in hit points if its file does not say. */
        public static final float DEFAULT_HEALTH = 300.0F;

        public static final Codec<Airframe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("health", DEFAULT_HEALTH).forGetter(Airframe::health),
                Codec.FLOAT.fieldOf("crash_speed").forGetter(Airframe::crashSpeed),
                Codec.FLOAT.fieldOf("explosion_power").forGetter(Airframe::explosionPower),
                Codec.FLOAT.optionalFieldOf("max_g", 0.0F).forGetter(Airframe::maxG),
                Codec.INT.optionalFieldOf("salvage", 0).forGetter(Airframe::salvage),
                VehicleChassis.Seat.CODEC.listOf().fieldOf("seats").forGetter(Airframe::seats)
        ).apply(instance, Airframe::new));
    }



    /**
     * How big the aircraft looks to somebody else's radar.
     *
     * <p>Not how big it <em>is</em>. A radar return depends on shape and on what the surface is made
     * of far more than on size: an aeroplane built to scatter what it is painted with can be the size
     * of a fighter and return what a bird does, and one hung about with pylons and missiles returns
     * far more than its own airframe would. Which is the whole of this: a number for the clean
     * airframe, and what each thing bolted to the outside adds to it.
     *
     * <p><b>The relationship to range is not linear.</b> A radar's reach against a target goes as the
     * fourth root of its cross-section, because the return falls off with the fourth power of
     * distance — so a target that returns a sixteenth as much is seen at half the distance, not a
     * sixteenth of it. Stealth is worth a great deal and is not worth everything; the arithmetic is
     * the same one aircraft designers are stuck with.
     *
     * @param radar cross-section of the clean airframe, against an ordinary fighter's 1.0. A tenth is
     *              hard to find, a hundredth is very hard. Zero would be invisible, which nothing is
     * @param store what each store carried <em>externally</em> adds. Stores in a bay add nothing,
     *              which is what bays are for; see {@link Hardpoint#internal()}
     */
    public record Signature(float radar, float store, float heat) {
        public static final Signature DEFAULT = new Signature(1.0F, 0.2F, 1.0F);

        public static final Codec<Signature> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("radar", DEFAULT.radar()).forGetter(Signature::radar),
                Codec.FLOAT.optionalFieldOf("store", DEFAULT.store()).forGetter(Signature::store),
                Codec.FLOAT.optionalFieldOf("heat", DEFAULT.heat()).forGetter(Signature::heat)
        ).apply(instance, Signature::new));

        /**
         * How far a heat-seeking head sees this, as a fraction of the range its file gives it.
         *
         * <p>A square root, where the radar above takes a fourth root, and the difference is the
         * physics rather than a taste in numbers. A radar has to light the target and then catch
         * what comes back, so its reach goes as the fourth root of the return; a seeker homing on
         * heat is only listening, and what it hears falls away with the square of the distance
         * alone. Which is why a burner that trebles the heat is worth nearly twice the range, where
         * trebling a radar return would barely move it.
         *
         * <p>Capped at one, and for the same reason the radar's is with rather more force behind
         * it. A seeker only ever considers what the sweep has already found, and that sweep is a
         * box the size of the seeker's own range — the single most expensive question the server
         * asks, and one that cannot be widened for the sake of a hot target without paying for the
         * whole cube of it. So {@code lock_range} is the range against the hottest thing the seeker
         * will ever look at: an ordinary fighter in full reheat. Everything colder than that is
         * found closer in, which is the whole of what this figure does.
         *
         * <p>Hence the shape a file wants. An airframe that has a burner sets {@code heat} to what
         * it is worth on <em>military power</em> — a good deal under one — and lets
         * {@code afterburner.heat} carry it back up towards the cap when the pilot lights it. Left
         * at one it is always as visible as it can be, which is the right answer for anything with
         * no burner to give away.
         */
        public static float heatReach(float heat) {
            return (float) Math.min(Math.sqrt(Math.max(heat, 0.0F)), 1.0);
        }

        /**
         * How far a radar sees this, as a fraction of what it manages against an ordinary fighter.
         *
         * @param cross the cross-section being looked for, airframe and stores together
         */
        public static float reach(float cross) {
            // Never past the radar's own reach. A larger return than an ordinary fighter's would
            // otherwise be found beyond the range the radar's file gives it, which would make that
            // figure mean nothing in particular; a small return is found closer in, and that is all
            // this is for.
            return (float) Math.min(Math.pow(Math.max(cross, 0.0F), 0.25), 1.0);
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
     * @param conversionSpeed the fastest the nozzle will swing <em>down</em>, in blocks per tick,
     *                        which stops it being used as an air brake at speed. Coming back up is
     *                        never refused: the lever has to be an answer to trouble, and a nozzle
     *                        that can only be stowed once the aeroplane is already going fast is one
     *                        that cannot be stowed by an aeroplane that is not
     */
    public record Vtol(float maxAngle, float rate, float liftThrust, float authority, float hoverDrag,
            float conversionSpeed) {

        /** Quoted in the docs above so the figure to beat is written down beside what beats it. */
        static final String GRAVITY_NOTE = "0.02453 blocks per tick squared";

        public static final Vtol DEFAULT = new Vtol(90.0F, 1.0F, 0.030F, 0.9F, 0.06F, 2.2F);

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
     * A lifting rotor, and with it a completely different aircraft. Present makes the machine a
     * helicopter and hands it {@code AircraftEntity}'s rotor flight model instead of the wing one;
     * absent leaves it an aeroplane.
     *
     * <p><b>What a helicopter is.</b> An aeroplane is thrown forward and holds itself up with the
     * air it is passing through, so it has to keep moving and it goes where its nose is pointing. A
     * helicopter carries its own airflow. The rotor makes a single force square to its own disc, and
     * that force is the whole aircraft: tilt the disc forward and the machine goes forward, tilt it
     * sideways and it goes sideways, leave it level and the machine hangs there. Nothing else pushes
     * it anywhere, which is why every helicopter is nose-down in the cruise and why one can stop dead
     * in the air without falling — the two things an aeroplane can never do.
     *
     * <p><b>What the pilot has.</b> The collective, which is the throttle lever and sets how hard the
     * rotor pulls; the cyclic, which is the pitch and roll stick and points the disc; and the pedals,
     * which are the tail rotor and swing the nose without moving the machine. All three work at a
     * standstill, because the rotor is turning whether or not the aircraft is going anywhere. That is
     * the whole difference from {@link Vtol}, which is an aeroplane borrowing its engine for a minute.
     *
     * <p><b>What is read from elsewhere.</b> The wing block still applies and still means what it
     * says: {@code drag} is what the fuselage costs and is what settles the top speed, {@code
     * lateral_drag} is the fin, {@code max_speed} is the never-exceed speed. Three of its figures
     * want reading again before they are set. {@code span} is the height the ground cushion reaches
     * to, which for a helicopter is the rotor's <em>diameter</em> rather than any wingspan. {@code
     * stall_speed} is only the reference the fin comes alive against, since nothing here stalls. And
     * {@code lift} is for a machine whose wings genuinely fly — a compound helicopter, or an
     * autogyro — and is best left at zero for an ordinary gunship: the angle of attack is measured
     * against the fuselage, an ordinary helicopter cruises well nose-down, and stub wings told to
     * read that as a negative angle would push the machine into the ground rather than help hold it
     * up. What they really contribute is small and this models it worse than not at all.
     *
     * @param lift acceleration the rotor makes at full collective and full speed, in blocks per tick
     *             squared. <b>It has to beat gravity</b> — {@value Vtol#GRAVITY_NOTE} — and by a
     *             margin, since the difference is everything the machine has left for climbing,
     *             turning and being loaded
     * @param spoolTicks how long the rotor takes to wind up to speed from a standstill. There is no
     *                   starter switch: climbing into the seat is the switch and this is the wait
     *                   afterwards, which is the same wait a real crew has and the reason a
     *                   helicopter cannot be jumped into and flown away from
     * @param translationalLift extra lift once the machine is moving, as a fraction. A rotor in a
     *                          hover is beating air it has already used; move it, and every blade
     *                          reaches undisturbed air. This is why a helicopter that cannot lift
     *                          itself vertically can often still fly away along the ground
     * @param translationalSpeed speed, in blocks per tick, by which that is fully in. Also the
     *                           reference the hover damping is spread over
     * @param authority how much control the rotor gives at full speed, against what an aeroplane's
     *                  surfaces have at their stalling speed. This is what makes a helicopter as
     *                  controllable standing still as it is at speed
     * @param maxTilt how far the disc can be tilted from level, in degrees, and therefore how hard
     *                the machine can be made to accelerate. <b>The cyclic walks the disc round and
     *                then leaves it there</b>, rather than springing back the moment the key comes
     *                up: that is what the attitude-hold system every modern helicopter carries does,
     *                and on a keyboard it is the only way to ask for a cruise at all. A key is all
     *                the way down or not down, so a stick that returned to level would leave the
     *                machine with two settings — hovering and charging — and nothing between them.
     *                Past this angle it is walked back, so a helicopter cannot be tipped over, by the
     *                pilot or by anything else
     * @param trim how fast the cyclic walks the disc, as a fraction of the rates in
     *             {@link Handling}. One hands the whole rate to the stick, which on a keyboard is a
     *             machine that snaps to full tilt the instant a key is touched; a half gives a second
     *             or so of travel, which is enough to stop anywhere along it
     * @param stability how hard the disc is walked back once it is past {@code max_tilt}, in degrees
     *                  per tick of rotation for each degree it is out. Nothing at all while the
     *                  machine is inside its limits, which is where it spends its life
     * @param hoverDrag how quickly a hover bleeds off drift, per tick, dying away as the machine
     *                  picks up speed. Without it a helicopter nudged sideways keeps going sideways;
     *                  left on at all speeds it is a parking brake rather than a hover
     * @param discDrag how hard it is to move the machine straight up or straight down, against the
     *                 square of the rate of climb. The rotor and everything slung under it present a
     *                 great flat area to air coming from below and next to none to air coming from
     *                 ahead, and the fuselage's own drag does not come close to explaining either
     *                 figure: this is what makes a rate of climb something quoted in feet per minute
     *                 while the speed beside it is in knots. It is also what a helicopter falls
     *                 against with the collective down, and it scales with the rotor, so one whose
     *                 rotor has stopped falls like the lump of metal it now is
     * @param bluffDrag how many times the fuselage's own drag it costs to fly the machine straight
     *                  backwards, blended round to one flying straight ahead. A fuselage is a shape
     *                  for going forwards; turned round it presents its whole side to the air, and
     *                  that difference is the only thing standing between a helicopter and reaching
     *                  its forward top speed in reverse. Real rearward and sideways limits are a
     *                  fraction of the forward one and this is why. One leaves the machine as happy
     *                  going backwards as forwards
     * @param torque yaw, in degrees per tick at full collective, that the fuselage is pushed round by
     *               the rotor it is hanging from. Positive swings the nose right, which is what a
     *               rotor turning anticlockwise seen from above does; a machine built the other way
     *               round takes a negative figure, and zero leaves the pedals to the pilot alone.
     *               Since it follows the collective, it is felt as the nose walking round whenever the
     *               machine is asked to climb — which is most of what flying one by hand consists of
     * @param rpm how fast the main rotor turns, in revolutions per minute. Drawing only
     * @param tailRpm the same for the tail rotor, which turns several times faster. Drawing only
     */
    public record Rotor(float lift, int spoolTicks, float translationalLift, float translationalSpeed,
            float authority, float maxTilt, float trim, float stability, float hoverDrag,
            float discDrag, float bluffDrag, float torque, float rpm, float tailRpm) {

        public static final Rotor DEFAULT = new Rotor(0.034F, 90, 0.15F, 0.8F, 1.0F, 22.0F, 0.5F,
                0.15F, 0.02F, 0.025F, 10.0F, 0.0F, 300.0F, 1500.0F);

        public static final Codec<Rotor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("lift", DEFAULT.lift()).forGetter(Rotor::lift),
                Codec.INT.optionalFieldOf("spool_ticks", DEFAULT.spoolTicks()).forGetter(Rotor::spoolTicks),
                Codec.FLOAT.optionalFieldOf("translational_lift", DEFAULT.translationalLift())
                        .forGetter(Rotor::translationalLift),
                Codec.FLOAT.optionalFieldOf("translational_speed", DEFAULT.translationalSpeed())
                        .forGetter(Rotor::translationalSpeed),
                Codec.FLOAT.optionalFieldOf("authority", DEFAULT.authority()).forGetter(Rotor::authority),
                Codec.FLOAT.optionalFieldOf("max_tilt", DEFAULT.maxTilt()).forGetter(Rotor::maxTilt),
                Codec.FLOAT.optionalFieldOf("trim", DEFAULT.trim()).forGetter(Rotor::trim),
                Codec.FLOAT.optionalFieldOf("stability", DEFAULT.stability()).forGetter(Rotor::stability),
                Codec.FLOAT.optionalFieldOf("hover_drag", DEFAULT.hoverDrag()).forGetter(Rotor::hoverDrag),
                Codec.FLOAT.optionalFieldOf("disc_drag", DEFAULT.discDrag()).forGetter(Rotor::discDrag),
                Codec.FLOAT.optionalFieldOf("bluff_drag", DEFAULT.bluffDrag()).forGetter(Rotor::bluffDrag),
                Codec.FLOAT.optionalFieldOf("torque", DEFAULT.torque()).forGetter(Rotor::torque),
                Codec.FLOAT.optionalFieldOf("rpm", DEFAULT.rpm()).forGetter(Rotor::rpm),
                Codec.FLOAT.optionalFieldOf("tail_rpm", DEFAULT.tailRpm()).forGetter(Rotor::tailRpm)
        ).apply(instance, Rotor::new));

        /** How far the main rotor turns in a tick, in degrees. Twenty ticks a second, sixty a minute. */
        public float degreesPerTick() {
            return this.rpm * 360.0F / (60.0F * 20.0F);
        }

        /** The same for the tail rotor. */
        public float tailDegreesPerTick() {
            return this.tailRpm * 360.0F / (60.0F * 20.0F);
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
     * <p><b>Racks.</b> A station holds one store unless the file gives it a {@code rack}, which is
     * where each store on it hangs relative to the station itself. That is what a multiple ejector
     * rack is: one place on the wing carrying four missiles rather than one, all of them the same
     * weapon, hung and taken off one at a time. Their order is the order they are filled in, and the
     * last one on is the first one off -- so list them the way they should empty.
     *
     * @param name a label for the log and for telling pylons apart; not shown to the player
     * @param pos position in the aircraft's own axes: x right, y up, z towards the nose. For a gun,
     *            put it at the muzzle
     * @param fixed the weapon built in here, or empty for a pylon
     * @param rack where each store on this station hangs, as an offset from {@link #pos}. Empty for
     *             a plain station, which is the same thing as a rack of one at no offset
     */
    public record Hardpoint(String name, Vec3 pos, Optional<ResourceLocation> fixed, boolean internal,
            List<Vec3> rack) {
        /** A station with no rack: one store, hanging where the station is. */
        private static final List<Vec3> SINGLE = List.of(Vec3.ZERO);

        public static final Codec<Hardpoint> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("name", "").forGetter(Hardpoint::name),
                Vec3.CODEC.fieldOf("pos").forGetter(Hardpoint::pos),
                ResourceLocation.CODEC.optionalFieldOf("fixed").forGetter(Hardpoint::fixed),
                Codec.BOOL.optionalFieldOf("internal", false).forGetter(Hardpoint::internal),
                Vec3.CODEC.listOf().optionalFieldOf("rack", List.of()).forGetter(Hardpoint::rack)
        ).apply(instance, Hardpoint::new));

        public boolean isFixed() {
            return this.fixed.isPresent();
        }

        /** Where every store on this station hangs, as offsets from it. Never empty. */
        public List<Vec3> stations() {
            return this.rack.isEmpty() ? SINGLE : this.rack;
        }

        /** How many stores this station takes: one, or as many places as its rack has. */
        public int capacity() {
            return this.stations().size();
        }

        /**
         * Where one store on this station hangs, in the aircraft's own axes.
         *
         * <p>Out-of-range places answer with the nearest real one rather than throwing. What is
         * being asked is where to draw something, and a rack that lost a place in a {@code /reload}
         * while four missiles were hanging on it is not worth a crash.
         */
        public Vec3 station(int place) {
            List<Vec3> stations = this.stations();

            return this.pos.add(stations.get(Math.max(0, Math.min(place, stations.size() - 1))));
        }

        /**
         * Whether what hangs here hangs inside the aeroplane.
         *
         * <p>Only the radar cares. A store in a bay is carried where nothing can see it and adds
         * nothing to what the aircraft returns; the same store on a rail under the wing is a corner
         * reflector bolted to a stealth aeroplane, and undoes a good deal of what the shape bought.
         * See {@link Signature}.
         */
        public boolean internal() {
            return this.internal;
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
     *                    an undercarriage does, and it applies only while the gear is down and the
     *                    aircraft is the right way up on it. Anything below 1 cannot clear a full
     *                    block, which is the step an aeroplane on a runway most often meets
     * @param retractable whether it goes up at all. Plenty of aircraft's does not — a helicopter's
     *                    wheels and a light aeroplane's alike — and one whose legs are welded down
     *                    should not answer the gear lever, since the only thing the pilot could
     *                    achieve with it is to lose the step-climbing the wheels give them on the
     *                    ground while nothing at all moves on the model
     * @param landingSpeed the speed, in blocks per tick, an arrival on the wheels is survivable at
     *                     whatever else is true of it. This is the figure on the pilot's readout, so
     *                     2.78 is the 200 km/h it shows. Left out, {@link #DEFAULT_LANDING_SPEED} or
     *                     {@link #HELICOPTER_LANDING_SPEED} by what the machine is
     */
    public record Undercarriage(int cycleTicks, float dragPenalty, float rollingFriction, float brakeFriction,
            float lateralFriction, float steerRate, float steerFade, float climbHeight, boolean retractable,
            Optional<Float> landingSpeed) {

        /**
         * Touchdown speed an aeroplane's undercarriage takes if its file does not say: 200 km/h, or
         * 2.78 blocks a tick. Well over anything a landing is flown at and deliberately so — the
         * point of it is that an approach flown at a sensible speed cannot be got wrong badly enough
         * to write the aircraft off, whatever the rate of descent at the end of it.
         */
        public static final float DEFAULT_LANDING_SPEED = 2.78F;
        /**
         * The same for a helicopter: 50 km/h, or 0.7 blocks a tick. Lower because a helicopter has
         * no approach speed to speak of — it comes to a stop and then descends — and it is the
         * descent this has to cover, since nothing about the way one lands looks like a rollout.
         */
        public static final float HELICOPTER_LANDING_SPEED = 0.7F;

        public static final Codec<Undercarriage> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("cycle_ticks").forGetter(Undercarriage::cycleTicks),
                Codec.FLOAT.fieldOf("drag_penalty").forGetter(Undercarriage::dragPenalty),
                Codec.FLOAT.optionalFieldOf("rolling_friction", 0.995F).forGetter(Undercarriage::rollingFriction),
                Codec.FLOAT.optionalFieldOf("brake_friction", 0.85F).forGetter(Undercarriage::brakeFriction),
                Codec.FLOAT.optionalFieldOf("lateral_friction", 0.55F).forGetter(Undercarriage::lateralFriction),
                Codec.FLOAT.optionalFieldOf("steer_rate", 1.1F).forGetter(Undercarriage::steerRate),
                Codec.FLOAT.optionalFieldOf("steer_fade", 1.2F).forGetter(Undercarriage::steerFade),
                Codec.FLOAT.optionalFieldOf("climb_height", 1.05F).forGetter(Undercarriage::climbHeight),
                Codec.BOOL.optionalFieldOf("retractable", true).forGetter(Undercarriage::retractable),
                Codec.FLOAT.optionalFieldOf("landing_speed").forGetter(Undercarriage::landingSpeed)
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
