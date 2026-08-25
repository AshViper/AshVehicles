package com.ashvehicles.vehicle;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * One ground vehicle, described entirely in JSON. Drop a file in
 * {@code data/ashvehicles/vehicle/} and the mod registers an entity type and an item for it at
 * start-up; no Java needed.
 *
 * <p>The arrangement is the aircraft's, and deliberately so — see
 * {@link com.ashvehicles.aircraft.AircraftDefinition}. The file is read twice: at start-up by
 * the mod itself, so that {@link Hitbox} is known while the
 * registries are still open; and again on every {@code /reload} from the data packs, so that
 * everything below the hitbox can be retuned without restarting. Both reads are
 * {@code DefinitionRegistry}'s doing and neither is this file's business.
 *
 * <p>What it describes is a different machine. Nothing here holds itself up: a ground vehicle is
 * pressed against the ground by gravity and turns because one track is driven harder than the
 * other, so there is no wing, no throttle to spool and no attitude the driver chooses. The hull's
 * pitch and roll belong to the ground it is standing on, and the only thing aimed independently of
 * the hull is the turret.
 *
 * <p>Speeds are in blocks per tick and accelerations in blocks per tick squared; at twenty ticks a
 * second, a speed of 0.35 is seven blocks a second, or about 25 km/h. Rates are degrees per tick.
 */
public record GroundVehicleDefinition(VehicleChassis.Hitbox hitbox, VehicleChassis.Model model, Powertrain powertrain,
        Suspension suspension, Turret turret, Armament armament, Coaxial coaxial, Launcher launcher, Hull hull,
        VehicleChassis.CameraMount camera, VehicleChassis.Sound sound, VehicleChassis.Radar radar,
        VehicleType type, Buoyancy buoyancy) {

    public static final Codec<GroundVehicleDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            VehicleChassis.Hitbox.CODEC.optionalFieldOf("hitbox", VehicleChassis.Hitbox.DEFAULT).forGetter(GroundVehicleDefinition::hitbox),
            VehicleChassis.Model.CODEC.optionalFieldOf("model", VehicleChassis.Model.DEFAULT).forGetter(GroundVehicleDefinition::model),
            Powertrain.CODEC.fieldOf("powertrain").forGetter(GroundVehicleDefinition::powertrain),
            Suspension.CODEC.optionalFieldOf("suspension", Suspension.DEFAULT)
                    .forGetter(GroundVehicleDefinition::suspension),
            Turret.CODEC.optionalFieldOf("turret", Turret.NONE).forGetter(GroundVehicleDefinition::turret),
            Armament.CODEC.optionalFieldOf("armament", Armament.NONE).forGetter(GroundVehicleDefinition::armament),
            Coaxial.CODEC.optionalFieldOf("coaxial", Coaxial.NONE).forGetter(GroundVehicleDefinition::coaxial),
            Launcher.CODEC.optionalFieldOf("launcher", Launcher.NONE).forGetter(GroundVehicleDefinition::launcher),
            Hull.CODEC.fieldOf("hull").forGetter(GroundVehicleDefinition::hull),
            VehicleChassis.CameraMount.CODEC.optionalFieldOf("camera", VehicleChassis.CameraMount.DEFAULT).forGetter(GroundVehicleDefinition::camera),
            VehicleChassis.Sound.CODEC.optionalFieldOf("sound", VehicleChassis.Sound.DEFAULT).forGetter(GroundVehicleDefinition::sound),
            VehicleChassis.Radar.CODEC.optionalFieldOf("radar", VehicleChassis.Radar.NONE)
                    .forGetter(GroundVehicleDefinition::radar),
            // Which kind of ground machine this is, and — for the one kind that floats — how it sits
            // in the water. A file that names neither is a plain tank, which is what almost all of
            // them are; a warship sets the type to "ship" and, if it wants anything other than the
            // default trim, a "buoyancy" block to go with it.
            VehicleType.CODEC.optionalFieldOf("type", VehicleType.GROUND_VEHICLE)
                    .forGetter(GroundVehicleDefinition::type),
            Buoyancy.CODEC.optionalFieldOf("buoyancy", Buoyancy.DEFAULT).forGetter(GroundVehicleDefinition::buoyancy)
    ).apply(instance, GroundVehicleDefinition::new));

    /** Whether this vehicle is a ship, floated on the water rather than resting on the ground. */
    public boolean isShip() {
        return this.type == VehicleType.SHIP;
    }

    /**
     * Used when a vehicle has no file the game can read at all. Deliberately slow and docile: it
     * drives, so the game keeps running and the log says what went wrong, but nobody will mistake it
     * for the real numbers.
     */
    public static final GroundVehicleDefinition FALLBACK = new GroundVehicleDefinition(
            VehicleChassis.Hitbox.DEFAULT,
            VehicleChassis.Model.DEFAULT,
            new Powertrain(0.2F, 0.1F, 0.006F, 0.04F, 0.004F, 1.5F, 1.0F, 0.6F),
            Suspension.DEFAULT,
            Turret.NONE,
            Armament.NONE,
            Coaxial.NONE,
            Launcher.NONE,
            new Hull(Hull.DEFAULT_HEALTH, 3.0F, 0, 0.0F,
                    List.of(VehicleChassis.Seat.at(new Vec3(0.0, 1.0, 0.0)))),
            VehicleChassis.CameraMount.DEFAULT,
            VehicleChassis.Sound.DEFAULT,
            VehicleChassis.Radar.NONE,
            VehicleType.GROUND_VEHICLE,
            Buoyancy.DEFAULT);



    /** The roles a bone can be given in {@link ModelSetup#bones}. */
    public static final class Bone {
        /**
         * The turret, which traverses about its own ring — so about the model's vertical, whatever
         * the hull is sitting on. Everything mounted in the turret is a child of this bone in the
         * geometry and comes round with it for nothing.
         */
        public static final String TURRET = "turret";
        /**
         * The gun, which elevates about the trunnions. A child of the turret, so it is aimed in the
         * turret's frame and needs to know nothing about which way the turret is pointing.
         */
        public static final String GUN = "gun";
        /** The mantlet, if it is a separate bone that elevates with the gun rather than part of it. */
        public static final String MANTLET = "mantlet";
        /** A machine gun on the turret roof, which follows the gun's elevation but not its aim. */
        public static final String MG = "mg";
        public static final String COMMANDER_HATCH = "commander_hatch";
        public static final String DRIVER_HATCH = "driver_hatch";

        private Bone() {
        }
    }

    /**
     * What drives the vehicle and what turns it.
     *
     * <p>A tracked vehicle turns by driving one track harder than the other, which means it can turn
     * on the spot and turns <em>less</em> sharply the faster it is going — the opposite of a steered
     * wheel, and the reason the rate is a pair of figures rather than one. A wheeled vehicle is the
     * same record with {@code pivot_rate} set to nothing, which is exactly what a wheeled vehicle
     * can do standing still.
     *
     * @param maxSpeed flat-out forwards, blocks per tick
     * @param reverseSpeed flat out backwards, which for a tank is a good deal less
     * @param acceleration how hard it pulls away, blocks per tick squared
     * @param braking how hard the brakes stop it, in the same units
     * @param rollingResistance how quickly it slows with nothing pressed, in the same units. This is
     *                          the track and the transmission rather than the brakes, so it is small
     * @param steerRate degrees a tick it comes round at full speed
     * @param pivotRate degrees a tick it comes round standing still, which for a tracked vehicle is
     *                  faster. Zero for anything that cannot turn without rolling
     * @param gradeResistance how much of gravity along a slope the vehicle has to fight, as a
     *                        fraction. One is honest physics — a steep enough hill stops it dead and
     *                        rolls it back down — and less than one is a vehicle with more torque
     *                        than sense. Nothing at all makes hills free
     */
    public record Powertrain(float maxSpeed, float reverseSpeed, float acceleration, float braking,
            float rollingResistance, float steerRate, float pivotRate, float gradeResistance) {

        public static final Codec<Powertrain> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.fieldOf("max_speed").forGetter(Powertrain::maxSpeed),
                Codec.FLOAT.optionalFieldOf("reverse_speed", 0.15F).forGetter(Powertrain::reverseSpeed),
                Codec.FLOAT.fieldOf("acceleration").forGetter(Powertrain::acceleration),
                Codec.FLOAT.optionalFieldOf("braking", 0.05F).forGetter(Powertrain::braking),
                Codec.FLOAT.optionalFieldOf("rolling_resistance", 0.004F).forGetter(Powertrain::rollingResistance),
                Codec.FLOAT.optionalFieldOf("steer_rate", 1.6F).forGetter(Powertrain::steerRate),
                Codec.FLOAT.optionalFieldOf("pivot_rate", 1.1F).forGetter(Powertrain::pivotRate),
                Codec.FLOAT.optionalFieldOf("grade_resistance", 0.8F).forGetter(Powertrain::gradeResistance)
        ).apply(instance, Powertrain::new));
    }

    /**
     * How the hull sits on the ground and what it can drive over.
     *
     * @param climbHeight how tall a step it drives straight over, in blocks. A tank walks over a
     *                    metre of wall; a car does not
     * @param slopeLimit the steepest slope it can hold, in degrees. Steeper than this and the
     *                   drivetrain cannot hold it and it slides back. Forty-five degrees is a
     *                   staircase of whole blocks, which is as steep as this world's ground gets
     *                   short of a wall, so a tracked vehicle is given the lot. What it is compared
     *                   against is a reading taken off block terrain, which is coarse by a block
     *                   over the contact patch — see {@code GroundVehicleEntity.holdableSlope}
     * @param settleRate how much of the way the hull turns towards the ground's angle in one tick,
     *                   in [0, 1]. One snaps it flat against every block it crosses, which reads as
     *                   a twitch; a fifth of the way a tick is a hull that rolls onto a slope
     * @param grip how much of a sideways slide is killed each tick, in [0, 1]. Tracks bite, so this
     *             is high; ice and a hard turn are what it is for
     * @param wheelRadius the road wheels' radius in blocks, which is what turns distance travelled
     *                    into how far the wheels have gone round. Drawing only
     * @param contactLength the length of the track on the ground, in blocks. This is what the hull's
     *                      pitch is read across, so it is the ground contact patch rather than the
     *                      whole vehicle: a gun barrel hanging six blocks over the bow has no say in
     *                      which way the hull is lying
     * @param contactWidth the distance between the two tracks, in blocks, across which the roll is
     *                     read for the same reason
     */
    public record Suspension(float climbHeight, float slopeLimit, float settleRate, float grip,
            float wheelRadius, float contactLength, float contactWidth) {
        public static final Suspension DEFAULT =
                new Suspension(1.0F, 45.0F, 0.2F, 0.85F, 0.4F, 4.0F, 2.5F);

        public static final Codec<Suspension> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("climb_height", DEFAULT.climbHeight()).forGetter(Suspension::climbHeight),
                Codec.FLOAT.optionalFieldOf("slope_limit", DEFAULT.slopeLimit()).forGetter(Suspension::slopeLimit),
                Codec.FLOAT.optionalFieldOf("settle_rate", DEFAULT.settleRate()).forGetter(Suspension::settleRate),
                Codec.FLOAT.optionalFieldOf("grip", DEFAULT.grip()).forGetter(Suspension::grip),
                Codec.FLOAT.optionalFieldOf("wheel_radius", DEFAULT.wheelRadius()).forGetter(Suspension::wheelRadius),
                Codec.FLOAT.optionalFieldOf("contact_length", DEFAULT.contactLength())
                        .forGetter(Suspension::contactLength),
                Codec.FLOAT.optionalFieldOf("contact_width", DEFAULT.contactWidth())
                        .forGetter(Suspension::contactWidth)
        ).apply(instance, Suspension::new));
    }

    /**
     * The turret and the gun in it, aimed in the hull's frame rather than the world's.
     *
     * <p>That is the whole of why this is separate from the hull. Where the hull is pointing is
     * decided by the ground and by the driver; where the gun is pointing is decided by the gunner,
     * and the two have nothing to do with one another. A turret laid on a target holds that target
     * while the hull crosses a ditch underneath it.
     *
     * @param traverseRate degrees a tick the turret comes round at
     * @param elevationRate degrees a tick the gun rises and falls at
     * @param depression how far the gun goes below the turret roof line, in degrees, written as a
     *                   positive number. This is the figure that decides whether a vehicle can fight
     *                   from behind a crest
     * @param elevation how far above it, in degrees
     * @param ring where the turret ring is, in the vehicle's own axes. Only the collision boxes need
     *             this — the model turns the turret about whatever pivot the geometry gives its
     *             bone — but they need it exactly: a box marked {@code "turret"} in the collision
     *             file is swung about this point, and a ring half a block out puts the gun's box
     *             half a block off the gun at every angle but dead ahead
     */
    public record Turret(float traverseRate, float elevationRate, float depression, float elevation,
            Vec3 ring) {
        /** A vehicle with no turret at all: nothing traverses and nothing elevates. */
        public static final Turret NONE = new Turret(0.0F, 0.0F, 0.0F, 0.0F, Vec3.ZERO);

        public static final Codec<Turret> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("traverse_rate", 1.8F).forGetter(Turret::traverseRate),
                Codec.FLOAT.optionalFieldOf("elevation_rate", 1.2F).forGetter(Turret::elevationRate),
                Codec.FLOAT.optionalFieldOf("depression", 9.0F).forGetter(Turret::depression),
                Codec.FLOAT.optionalFieldOf("elevation", 20.0F).forGetter(Turret::elevation),
                Vec3.CODEC.optionalFieldOf("ring", Vec3.ZERO).forGetter(Turret::ring)
        ).apply(instance, Turret::new));

        /** Whether this vehicle has a turret worth turning. */
        public boolean exists() {
            return this.traverseRate > 0.0F || this.elevationRate > 0.0F;
        }
    }

    /**
     * The gun in the turret: which weapon it is, where its muzzle is, and what firing it does to the
     * vehicle.
     *
     * <p>How hard it hits, how far it reaches and how often it can fire are not here. Those belong
     * to the weapon's own file in {@code data/ashvehicles/weapon/}, exactly as they do for anything
     * an aircraft carries, and a tank gun is no different for being bolted in rather than hung on.
     * What is here is the part that is about <em>this vehicle</em>: where the barrel ends, and what
     * happens to the tank when the gun goes off.
     *
     * <p>The muzzle is described as a point and a length rather than as a point, because the barrel
     * swings. The trunnion is where the gun pivots in elevation, which is a fixed place on the
     * turret; the muzzle is that far along whichever way the gun is currently laid. Written as a
     * single point it would be right at one elevation and wrong at every other.
     *
     * @param main the weapon fired by the trigger, or empty for a vehicle with nothing to fire
     * @param trunnion where the gun pivots in elevation, in the vehicle's own axes. Swung about the
     *                 turret ring with everything else on the turret
     * @param barrelLength from the trunnion to the muzzle, in blocks, along the bore
     * @param recoil how far the barrel slides back when it fires, in blocks. Drawing only
     * @param recoilTicks how long it takes to run back out again
     * @param kick how hard firing shoves the vehicle backwards, in blocks per tick. A hundred and
     *             twenty millimetres against sixty tonnes is not much, but it is not nothing, and a
     *             tank that fires and does not move at all reads as a tank that fired a blank
     */
    public record Armament(Optional<ResourceLocation> main, Vec3 trunnion, float barrelLength,
            float recoil, int recoilTicks, float kick) {
        /** A vehicle with nothing to fire. */
        public static final Armament NONE =
                new Armament(Optional.empty(), Vec3.ZERO, 0.0F, 0.0F, 1, 0.0F);

        public static final Codec<Armament> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.optionalFieldOf("main").forGetter(Armament::main),
                Vec3.CODEC.optionalFieldOf("trunnion", Vec3.ZERO).forGetter(Armament::trunnion),
                Codec.FLOAT.optionalFieldOf("barrel_length", 0.0F).forGetter(Armament::barrelLength),
                Codec.FLOAT.optionalFieldOf("recoil", 0.35F).forGetter(Armament::recoil),
                Codec.INT.optionalFieldOf("recoil_ticks", 14).forGetter(Armament::recoilTicks),
                Codec.FLOAT.optionalFieldOf("kick", 0.06F).forGetter(Armament::kick)
        ).apply(instance, Armament::new));

        /** Whether there is anything to fire at all. */
        public boolean exists() {
            return this.main.isPresent();
        }
    }

    /**
     * The machine gun bolted alongside the main armament, and fired on its own trigger.
     *
     * <p><b>Why it is not simply a second {@link Armament}.</b> The main gun is the thing the whole
     * turret is built round: it recoils, it shoves the hull about when it goes off, it is what the
     * sight is harmonised for, and it is what the crew are cycling between when a vehicle also
     * carries missiles. A coaxial is none of that. It is a barrel clamped to the side of the mantlet
     * with a belt running into it, it does not move the tank, and it is never <em>selected</em> —
     * it is simply there, and a gunner engaging infantry with it has not put the main armament away
     * to do so. So it has its own trigger, its own belt and its own count, and nothing here about
     * recoil or kick, because there is nothing worth drawing.
     *
     * <p><b>Coaxial means what it says</b>: the barrel is clamped to the gun and looks exactly where
     * the gun looks. So there is no aim of its own to describe and none is offered — it fires along
     * the bore, which is what makes laying the main gun on something also lay this on it. All the
     * file says is where the rounds leave from.
     *
     * <p>How hard the rounds hit, how fast they leave and how quickly they come is not here either.
     * That is the weapon's own file under {@code data/ashvehicles/weapon/}, exactly as it is for the
     * main gun and for anything hung on a wing.
     *
     * @param gun the weapon the belt feeds, or empty for a vehicle that carries no machine gun
     * @param muzzle where the rounds leave, in the vehicle's own axes, with the turret at dead ahead
     *               and the gun level. Carried round the ring with the turret and rocked about the
     *               trunnion with the gun, since that is what it is bolted to
     */
    public record Coaxial(Optional<ResourceLocation> gun, Vec3 muzzle) {
        /** A vehicle with no machine gun, which is what a file saying nothing gets. */
        public static final Coaxial NONE = new Coaxial(Optional.empty(), Vec3.ZERO);

        public static final Codec<Coaxial> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.optionalFieldOf("gun").forGetter(Coaxial::gun),
                Vec3.CODEC.optionalFieldOf("muzzle", Vec3.ZERO).forGetter(Coaxial::muzzle)
        ).apply(instance, Coaxial::new));

        /** Whether there is a machine gun aboard at all. */
        public boolean exists() {
            return this.gun.isPresent();
        }
    }

    /**
     * The missiles, for a vehicle that carries them in tubes rather than hanging them on pylons.
     *
     * <p>Deliberately not {@link com.ashvehicles.weapon.WeaponMounts}, for the reason the gun is
     * not: a pylon is a place a store is <em>hung</em>, and most of that class is about which
     * station is selected and what somebody loaded onto it. A launcher's tubes are built in, they
     * all hold the same round, and the only questions are how many are left and whether the seeker
     * has anything. How hard the missile hits, how far it reaches, how hard it can turn and what its
     * seeker is looking for are none of this record's business either — they are in the weapon's own
     * file under {@code data/ashvehicles/weapon/}, exactly as they are for a missile on a wing.
     *
     * <p>What is here is the part that is about <em>this vehicle</em>: which round it carries and
     * where the round leaves from.
     *
     * @param missile the weapon the tubes hold, or empty for a vehicle with no missiles
     * @param rail where a round leaves, in the vehicle's own axes, with the turret at dead ahead.
     *             Carried round the ring with the turret and elevated with the gun, since on
     *             anything that carries both the tubes are bolted to the same mounting the barrels
     *             are — which is what makes laying the gun on a target also lay the tubes on it
     */
    public record Launcher(Optional<ResourceLocation> missile, Vec3 rail) {
        /** A vehicle with no missiles. */
        public static final Launcher NONE = new Launcher(Optional.empty(), Vec3.ZERO);

        public static final Codec<Launcher> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.optionalFieldOf("missile").forGetter(Launcher::missile),
                Vec3.CODEC.optionalFieldOf("rail", Vec3.ZERO).forGetter(Launcher::rail)
        ).apply(instance, Launcher::new));

        /** Whether there are any tubes at all. */
        public boolean exists() {
            return this.missile.isPresent();
        }
    }

    /**
     * The vehicle itself: what it is worth, what it does when it is finished, and where the crew sit.
     *
     * @param health what a whole vehicle of this sort is worth, in hit points
     * @param explosionPower how big a hole it leaves
     * @param salvage how much metal is left in a wreck of one, in iron ingots, once it has been
     *                destroyed and somebody comes along with a wrench. Left out, it is worked out
     *                from the health instead
     * @param armour how good the plate is at throwing a round off rather than letting it in, in
     *               degrees taken off the angle the round would otherwise need. Nought is plain
     *               steel and the round's own figure stands; a few degrees is composite armour, and
     *               is worth more than it sounds — every degree is armour the crew do not have to
     *               find by turning the hull. The slope itself is not here and never will be: the
     *               boxes are lying at whatever angle the vehicle is lying at, so a hull turned to
     *               meet the shot is already a shallower hit
     * @param seats the crew places, in the vehicle's own axes — x to the right, y up, z towards the
     *              front — in blocks. The first is the driver's, and the driver is who drives. Each
     *              is a bare point or a block that also says where that crew member looks out from;
     *              see {@link VehicleChassis.Seat}
     */
    public record Hull(float health, float explosionPower, int salvage, float armour,
            List<VehicleChassis.Seat> seats) {
        public static final float DEFAULT_HEALTH = 300.0F;

        public static final Codec<Hull> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("health", DEFAULT_HEALTH).forGetter(Hull::health),
                Codec.FLOAT.optionalFieldOf("explosion_power", 4.0F).forGetter(Hull::explosionPower),
                Codec.INT.optionalFieldOf("salvage", 0).forGetter(Hull::salvage),
                Codec.FLOAT.optionalFieldOf("armour", 0.0F).forGetter(Hull::armour),
                VehicleChassis.Seat.CODEC.listOf().fieldOf("seats").forGetter(Hull::seats)
        ).apply(instance, Hull::new));
    }

    /**
     * How a ship sits in the water. Read only by a vehicle whose {@code type} is {@code ship}; a
     * tank carries the default and never touches it.
     *
     * <p>What holds a ship up is not the ground under it but the water it displaces, and the whole
     * of that is one balance: the deeper the hull is pushed, the harder the water pushes back, and
     * the vessel rides where the two come level. So there is no wing to fly and no slope to lie
     * along — a ship on flat water sits flat — and the file's job is to say where that resting line
     * is and how briskly the hull returns to it after a wave, a shell, or being dropped in.
     *
     * <p>The model here is a spring rather than a full account of displaced volume: the hull is
     * pulled towards its resting draught at a rate that grows with how far it is from it, and the
     * bobbing that would leave is taken out by the damping. It is cheap, it is stable, and on
     * Minecraft's flat seas it is indistinguishable from the real thing.
     *
     * @param draught how deep the vehicle's origin floats below the water's surface at rest, in
     *                blocks. The origin of a ship model is usually its keel, so this is how much of
     *                the hull is under water — a metre or two for a boat, more for a warship. It is
     *                what sets the waterline on the drawn hull, so it is worth getting to look right
     * @param buoyancy how hard the water pushes the hull back towards that resting draught, as an
     *                 acceleration per block it is out of place — the stiffness of the spring. Higher
     *                 is a cork that pops straight back up; lower is a laden hull that wallows. Too
     *                 high and the hull springs out of the water it was dropped into
     * @param damping the fraction of the hull's up-and-down speed taken out each tick, in [0, 1].
     *                This is what stops a ship dropped in the sea bobbing for ever; most of one, so
     *                that it settles in a second or two rather than ringing like a bell
     * @param trimRate how quickly the hull returns to level after it has been tipped, in [0, 1] a
     *                 tick. A ship on flat water has no slope to hold, so unlike a tank it always
     *                 eases back to level; this is only how fast
     */
    public record Buoyancy(float draught, float buoyancy, float damping, float trimRate) {
        public static final Buoyancy DEFAULT = new Buoyancy(1.0F, 0.08F, 0.35F, 0.1F);

        public static final Codec<Buoyancy> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("draught", DEFAULT.draught()).forGetter(Buoyancy::draught),
                Codec.FLOAT.optionalFieldOf("buoyancy", DEFAULT.buoyancy()).forGetter(Buoyancy::buoyancy),
                Codec.FLOAT.optionalFieldOf("damping", DEFAULT.damping()).forGetter(Buoyancy::damping),
                Codec.FLOAT.optionalFieldOf("trim_rate", DEFAULT.trimRate()).forGetter(Buoyancy::trimRate)
        ).apply(instance, Buoyancy::new));
    }


}
