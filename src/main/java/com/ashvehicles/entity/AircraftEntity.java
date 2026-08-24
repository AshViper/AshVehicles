package com.ashvehicles.entity;

import java.util.List;
import java.util.Set;
import java.util.Optional;

import javax.annotation.Nullable;

import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.vehicle.Hitbox;

import com.ashvehicles.vehicle.VehicleShape;
import com.ashvehicles.client.model.AircraftAnimations;
import com.ashvehicles.particle.TintedParticleOption;
import com.ashvehicles.sensor.Sensors;
import com.ashvehicles.registry.ModParticles;
import com.ashvehicles.item.WeaponItem;
import com.ashvehicles.item.WrenchItem;
import com.ashvehicles.weapon.Dispenser;
import com.ashvehicles.weapon.TargetLock;
import com.ashvehicles.weapon.WeaponMounts;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * Shared behaviour for every fixed-wing aircraft in the mod: a simplified flight model, seating,
 * and the damage handling that turns an airframe into a smoking hole.
 *
 * <p><b>Where the physics run.</b> Like vanilla boats, an aircraft is simulated by whoever
 * "controls" it: the piloting client while a player is at the stick, otherwise the server. That is
 * what {@link #isControlledByLocalInstance()} decides. The piloting client's position is pushed to
 * the server by vanilla's ServerboundMoveVehiclePacket, which also carries yaw and pitch; the bank
 * angle and throttle have no vanilla equivalent, so the client sends them in
 * {@link com.ashvehicles.network.AircraftInputPayload} and the server mirrors them into synched
 * data for everyone else.
 */
public class AircraftEntity extends VehicleEntityBase implements GeoEntity {
    /** Engine setting in [0, 1]. Synced so other clients can drive engine animations. */
    private static final EntityDataAccessor<Float> DATA_THROTTLE =
            SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.FLOAT);
    /**
     * How much reheat the engine is delivering, in [0, 1], from whichever side is flying it.
     *
     * <p>Synched for three separate reasons, which is unusual for one float. Every client that can
     * see the aeroplane draws the plume out of its nozzles and pitches its engine note up, and
     * neither of those can be worked out from anything else that is sent. And the server needs it
     * whether or not anybody is looking: what a burner really costs is the heat, and how far a
     * seeker can see this aircraft is decided over there. See {@link #reportAfterburner}.
     */
    private static final EntityDataAccessor<Float> DATA_AFTERBURNER =
            SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.FLOAT);
    /**
     * Which way the aircraft is pointing, as a rotation. Minecraft gives an entity a heading and an
     * elevation, which cannot describe an aeroplane upside down at the top of a loop, so the real
     * attitude is carried here and the vanilla angles are kept in step behind it.
     */
    private static final EntityDataAccessor<Quaternionf> DATA_ATTITUDE =
            SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.QUATERNION);
    /**
     * How fast the aircraft is really going, in blocks a tick, from whichever side is flying it.
     *
     * <p>Only one machine runs the flight model, and every other copy of the aircraft has to draw it
     * from a stream of positions. Working the speed back out of that stream cannot be done cleanly —
     * the updates arrive one a tick on average and never one a tick exactly, so a difference between
     * two of them reads the drift between three unsynchronised clocks as speed. Sending the figure
     * costs three floats a tick and removes the guesswork entirely. See {@link AircraftInterpolation}.
     */
    private static final EntityDataAccessor<Vector3f> DATA_VELOCITY =
            SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.VECTOR3);
    /**
     * How fast it is turning, in radians a tick about its own axes, for the same reason and against
     * the same drift. Written as a scaled axis rather than as three angles so that it stays additive
     * and has no seam of its own — see {@link Attitude#rotationVector}.
     */
    private static final EntityDataAccessor<Vector3f> DATA_BODY_RATE =
            SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.VECTOR3);
    /** Undercarriage selector. The legs swing to match over {@link #getGearCycleTicks()} ticks. */
    private static final EntityDataAccessor<Boolean> DATA_GEAR_DOWN =
            SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.BOOLEAN);
    /**
     * Lift-system selector, for an aircraft that has one. The nozzle swings to match over the time
     * its file says a conversion takes.
     */
    private static final EntityDataAccessor<Boolean> DATA_VTOL =
            SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.BOOLEAN);
    /** Flap selector. Lowered flaps buy lift at low speed and cost drag at any speed. */
    private static final EntityDataAccessor<Boolean> DATA_FLAPS_DOWN =
            SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.BOOLEAN);
    /**
     * What is hanging on the hardpoints, and what the trigger is pointed at. Sent as the same tag
     * the aircraft is saved with: the instruments need it, and so does the renderer, which draws a
     * pod on every loaded pylon.
     */
    private static final EntityDataAccessor<CompoundTag> DATA_WEAPONS =
            SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.COMPOUND_TAG);
    /**
     * What the airframe has left, in hit points.
     *
     * <p>Synched because it is not only the server's business: the pilot's instruments show it, and
     * an aircraft that has been shot at is the same aircraft to everybody looking at it. The server
     * owns the figure; a client reads whatever it was last told.
     */
    /**
     * Flares and chaff left aboard. Synched so the instruments can read them without a packet of
     * their own; the server is the only thing that ever changes them. See {@link Dispenser}.
     */
    private static final EntityDataAccessor<Integer> DATA_FLARES =
            SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_CHAFF =
            SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.INT);

    /**
     * Downward acceleration, in blocks/tick^2. A block is a metre and a tick is a twentieth of a
     * second, so this is 9.81 m/s^2: real gravity rather than Minecraft's, because the aircraft are
     * built to their real figures and those figures assume the real thing.
     */
    protected static final double GRAVITY = 0.02453;
    /**
     * How much of the stalling speed the hover damping is spread over.
     *
     * <p>A quarter of it, which is a walking pace, and the figure matters more than it looks. The
     * damping is there to stop a hovering aeroplane drifting away from where it was put; it is not
     * there to stop it going anywhere. Spread over the whole stalling speed it does both, and an
     * aeroplane that cannot reach its stalling speed can never hand the weight to its wing — it
     * hovers until it runs out of fuel, or in this case forever. Kept down here, a drift settles and
     * a deliberate acceleration is untouched by the time it is a walking pace.
     */
    private static final double HOVER_BAND = 0.25;
    /** Nozzle angle past which the wing is no longer what is holding the aircraft up, in degrees. */
    private static final float HOVERING_ANGLE = 30.0F;
    /**
     * How loud a helicopter at full rotor speed and no collective is, against one working.
     *
     * <p>High, and deliberately: a rotor turning is most of the noise a helicopter makes, and the
     * difference the collective makes is the engines taking up the load underneath it. A machine
     * sitting at flight idle is not quiet, it is merely not pulling.
     */
    private static final float ROTOR_IDLE_NOTE = 0.75F;

    /**
     * How far a collision box has to be inside a block before the aeroplane counts as embedded in it,
     * in blocks. Generous, so that a wingtip resting a hand's breadth into a slope is not mistaken
     * for an aeroplane that has had a mountain built around it.
     */
    private static final double EMBEDDED_MARGIN = 0.25;

    /** Airframe damage per tick for each G pulled beyond what the aircraft is stressed for. */
    private static final float OVER_G_DAMAGE = 4.0F;
    /** Load factor above which the wingtips start trailing vapour. */
    private static final float VORTEX_LOAD = 2.5F;
    /** Height above the aircraft's origin that the wings sit at, near enough for particles. */
    private static final double WING_HEIGHT = 1.5;
    /** Condensation is water and light, so it is the same pale puff wherever on the wing it forms. */
    private static final int VAPOUR_COLOUR = 0xF2F5F7;
    /** Fraction of top speed at which the cone forms. */
    private static final double VAPOUR_SPEED = 0.88;
    private static final double VAPOUR_RADIUS = 3.0;
    private static final double VAPOUR_AHEAD = 2.0;

    /**
     * Ticks the throttle has to be held open with the lever already against its stop before the
     * burner lights.
     *
     * <p>This is the detent, and it is the whole of the control. A real throttle quadrant has a
     * physical gate at full military power that the pilot has to lift the lever over, so that
     * nobody arrives in reheat by simply pushing the throttle forward and finding out afterwards.
     * Nothing here can put a notch under anybody's finger, so the notch is time instead: three
     * quarters of a second of asking for more than the engine has, which is far longer than the
     * moment a pilot spends reaching full power and far shorter than the wait would be if it were
     * a thing to be endured.
     */
    private static final int GATE_TICKS = 15;
    /** Reheat past which the burner counts as alight: for the instruments, the plume and the note. */
    private static final float LIT = 0.05F;
    /** The flame itself, which opens white and settles to this. */
    private static final int PLUME_COLOUR = 0xFFA33C;
    /** And the hot air behind it, which is what gives the plume its length. */
    private static final int EXHAUST_COLOUR = 0x8C8478;
    /** How far behind the nozzle the plume is drawn, in blocks at full reheat. */
    private static final double PLUME_LENGTH = 4.0;
    /** How hard it is thrown out of the pipe, in blocks a tick on top of the aircraft's own speed. */
    private static final double PLUME_SPEED = 0.9;
    /** Width of the flame at the lip, in blocks. */
    private static final float PLUME_SIZE = 0.85F;
    /** Puffs of each of the two layers, per nozzle, per tick at full reheat. */
    private static final int PLUME_PUFFS = 2;
    /** How far off the axis a puff may start, which is what stops the column reading as a line. */
    private static final double PLUME_SCATTER = 0.12;
    /**
     * How loud lighting the burner is where it happens, and at what pitch.
     *
     * <p>Its own loudness rather than a reach in the volume slot, unlike a weapon's report: this is
     * a noise the aeroplane makes, and the aeroplane already has an engine note that carries as far
     * as its file says. Public because the client has to know the figures to put a stand-in
     * recording at the same loudness; see {@code AfterburnerSounds}.
     */
    public static final float AFTERBURNER_VOLUME = 1.0F;
    public static final float AFTERBURNER_LIGHT_PITCH = 1.0F;
    /**
     * {@code engine.<aircraft>.afterburner}: the burner catching.
     *
     * <p>Named on this side because the server is the one that decides it has happened, and it can
     * only ever name a sound — resource packs are something the client has and the server has never
     * seen. Which recording actually plays is settled over there; see {@code AfterburnerSounds}.
     */
    public static final String AFTERBURNER_ROLE = "afterburner";

    /** Where the angle of attack limiter starts to bite, as a fraction of the limit it holds. */
    private static final float ALPHA_LIMITER_BITE = 0.6F;
    /**
     * Fraction of the rotation speed over which the elevator's hold on the nose fades in, so the
     * aircraft goes light towards the end of its run rather than the stick coming alive at a step.
     */
    private static final double ROTATION_FADE = 0.25;
    /**
     * How near upright the aircraft has to be for its wheels to be underneath it. This is the
     * vertical part of the direction lift acts in, so one is dead level and zero is on its side.
     */
    private static final double UPRIGHT = 0.5;
    /**
     * Rate of descent an undercarriage absorbs, as a fraction of the speed that writes the airframe
     * off. Above it the gear being down makes no difference: the aircraft is not landing, it is
     * hitting the ground with the wheels out.
     */
    private static final double TOUCHDOWN_SINK = 0.25;
    /** Nose-up attitude the wheels allow before the tail would strike the runway. */
    private static final float GROUND_PITCH_LIMIT = 15.0F;
    /** How firmly the undercarriage pulls the aircraft back to sitting flat, per tick. */
    private static final float GROUND_LEVELLING = 0.25F;
    /**
     * Most control authority the airflow will ever hand the pilot, as a multiple of what it gives at
     * the stalling speed. Authority now follows the dynamic pressure rather than the speed — which is
     * what a control surface actually works against — so this is the square of the old ceiling and
     * the two agree exactly at one and a half times the stalling speed.
     */
    private static final double AUTHORITY_CEILING = 2.25;
    /**
     * Height, as a multiple of the sea-level figure, that the air is never thinner than however high
     * the aircraft is taken. Thrust and lift both follow the air, so without a floor an aeroplane
     * taken high enough has neither, and what should be a ceiling becomes a trapdoor.
     */
    private static final double THINNEST_AIR = 0.35;
    /** Height the air is measured from, and the height the files' figures are quoted at. */
    private static final double DENSITY_DATUM = 64.0;
    /**
     * Height over which the air halves in density.
     *
     * <p>Deliberately gentler than the real atmosphere scaled to a Minecraft world would be. The
     * point of thinning air is that an aircraft has a ceiling rather than climbing for ever; making
     * that ceiling low enough to meet in ordinary flying costs more than it buys, and it is felt
     * worst by the one aircraft least able to argue — a lift system holding a hover has no speed to
     * make up the difference with, so a steep gradient simply forbids the F-35B to hover anywhere
     * above the treetops.
     */
    private static final double DENSITY_SCALE = 512.0;
    /**
     * Fastest a pilot's client is believed when it reports its own speed, in blocks per tick. Well
     * clear of anything an aircraft can reach, and there so that the figure cannot be used to hurl
     * a cannon round across the world.
     */
    private static final double MAX_PILOT_SPEED = 40.0;
    /** How big a pylon's box is, in blocks. Big enough to aim at, small enough to tell from its neighbour. */
    public static final double PYLON_BOX = 1.2;
    /** And how small it is allowed to shrink for an aircraft whose stations are close together. */
    private static final double SMALLEST_PYLON_BOX = 0.5;
    /** Speed, squared, below which an aircraft counts as standing still. */
    private static final double PARKED_SPEED = 1.0E-4;
    /**
     * What a wreck keeps of its speed each tick on the way down. A burnt-out airframe is a shape
     * falling through the air rather than a wing flying through it, so this is one figure standing in
     * for the whole of the aerodynamics: enough to stop a write-off carrying its airspeed to the
     * ground, nowhere near enough to hold it up.
     */
    private static final double WRECK_DRAG = 0.99;
    /** And what it keeps once it is down. A wreck does not slide; it arrives and it stays there. */
    private static final double WRECK_FRICTION = 0.7;

    private final AnimatableInstanceCache animatableCache = GeckoLibUtil.createInstanceCache(this);
    /** What the aircraft is carrying. Authoritative on the server; a copy of the tag on a client. */
    private final WeaponMounts weapons = new WeaponMounts(this);
    /** Flares and chaff, and when the dispenser will part with the next one. */
    private final Dispenser dispenser = new Dispenser(this);

    private AircraftInput input = AircraftInput.NONE;
    private float throttle;
    /**
     * What the engine is actually delivering, as against what the lever is asking for. Chases the
     * throttle rather than matching it, because an engine spools: a takeoff roll begun by slamming
     * the lever forward should start slowly and build, not leap.
     */
    private float thrustLevel;
    /**
     * Whether the pilot has taken the lever through the gate. A latch rather than a held key: a
     * throttle stays where it is put, and the way out of reheat is to pull it back.
     */
    private boolean reheatCommanded;
    /** What the burner is actually delivering, 0 to 1. It lights quickly, but it does not light at a step. */
    private float reheat;
    /** Ticks the lever has been held against its stop, counting towards {@link #GATE_TICKS}. */
    private int gateHeld;
    /**
     * How much of the aircraft's weight the wheels are still carrying, 1 sitting on them and 0 with
     * the wing holding everything. Ground friction is scaled by it, so the last of a takeoff roll
     * goes light as the wing takes over instead of gripping right up to the instant of lift-off.
     */
    private float weightOnWheels = 1.0F;
    /** Heading change over the last tick, handed to the pilot so their view turns with the aircraft. */
    private float deltaRotation;
    // Angular rates, in degrees per tick. Held between ticks so the controls have some weight.
    private float pitchVelocity;
    private float rollVelocity;
    private float yawVelocity;
    /** The angle the wing is meeting the airflow at, in degrees. Past the stalling angle, trouble. */
    private float angleOfAttack;
    /** Set by whichever side ran the physics when the airframe hit something at speed. */
    private boolean crashing;
    /** The chunk this aircraft is holding open while it flies, if any. */
    private Set<ChunkPos> heldChunks = Set.of();
    /** How this aircraft is drawn on a client that is not flying it. */
    private final AircraftInterpolation interpolation = new AircraftInterpolation();
    /** How fast the pilot's client says it is going. The server's only honest answer while flown. */
    private Vec3 pilotVelocity = Vec3.ZERO;
    /**
     * Server side: the attitude the last update came in at, so the turn since can be measured
     * against one whole tick of whoever is flying rather than against one of the server's own.
     */
    private final Quaternionf ratedAttitude = new Quaternionf();
    private boolean hasRatedAttitude;
    // Client side only: the last answer to whether the world stands in the way, and when it was
    // worked out. Tracing the line costs something and the answer barely changes within a tick.
    /** How far the undercarriage has swung out: 0 is up and locked, 1 is down and locked. */
    private float gearProgress = 1.0F;
    private float gearProgressO = 1.0F;
    /** How far the nozzle has swung, 0 stowed to 1 fully down. */
    private float vtolProgress;
    private float vtolProgressO;
    /** How far the flaps have travelled: 0 is retracted, 1 is fully down. */
    private float flapsProgress;
    private float flapsProgressO;
    /**
     * How fast the rotor is turning, as a fraction of governed. Zero for anything that has none.
     *
     * <p>Worked out on every side rather than sent, because every side already knows everything it
     * depends on: a rotor winds up while somebody is at the controls and runs down when nobody is,
     * and who is aboard is synced with the passengers. A packet a tick to say how fast a wheel is
     * going round would be a poor use of one.
     */
    private float rotorSpeed;
    private float rotorSpeedO;
    /**
     * Where the rotors have got to, in degrees. Drawing only, and integrated wherever it is drawn.
     *
     * <p>The tail is counted separately rather than scaled off the main one. It turns several times
     * faster and the two do not come back into step at any convenient angle, so a tail angle worked
     * out from the main one jumps every time the main one is brought back inside a turn.
     */
    private float rotorAngle;
    private float rotorAngleO;
    private float tailAngle;
    private float tailAngleO;

    // Interpolation state for instances that are not simulating this aircraft themselves.
    private int lerpSteps;
    private double lerpX;
    private double lerpY;
    private double lerpZ;
    private double lerpYRot;
    private double lerpXRot;

    public AircraftEntity(EntityType<? extends AircraftEntity> type, Level level) {
        super(type, level);
        // Culled against the view frustum like anything else. Beyond the ghost start distance the
        // game's entity loop stands down altogether and the ghost pass draws the aircraft from a
        // snapshot, so there is no longer anything for the frustum's far plane to throw away.
        // See com.ashvehicles.client.ghost.GhostRenderDispatcher.
        this.blocksBuilding = true;
        // Built here rather than on the first tick: the level records an entity's parts when it is
        // added, and an entity that has none yet is remembered as having none.
        this.buildParts();
        // A new airframe is a whole one. An aircraft read back out of the world overwrites this from
        // its tag, and a client is told the real figure with the rest of the synched data.
        this.setHealth(this.getMaxHealth());
        // A new aeroplane comes with full magazines, the same as it comes with a whole airframe.
        this.setCountermeasures(true, this.getStats().countermeasures().flares());
        this.setCountermeasures(false, this.getStats().countermeasures().chaff());
        // We integrate our own gravity in flightTick(). Telling the server that also stops it
        // counting a flying aircraft as a floating vehicle, which is otherwise a kick for
        // "flying is not enabled on this server" after four seconds airborne.
        this.setNoGravity(true);
    }

    // ------------------------------------------------------------------
    // Flight characteristics, read from the aircraft's data pack file.
    //
    // Held rather than looked up afresh, but only for as long as the files stand still: the copy is
    // thrown away the moment Definitions reports a different version, so /reload still takes
    // effect on aircraft that are already in the air. That is worth the two fields. An aircraft asks
    // for its own figures several dozen times in a tick — the flight model alone reads a dozen
    // records out of them — and each ask was a reverse lookup through the entity registry to build
    // the name, followed by a hash of that name and a map search. Once a tick is plenty.
    // ------------------------------------------------------------------

    /** The figures and the shape under that name, and which set of files they came out of. */
    @Nullable
    private AircraftDefinition stats;
    @Nullable
    private int statsVersion = -1;

    /**
     * This aircraft's id, which is its entity type's id. Everything else about it, from its file to
     * its model to the item that places it, is found under the same name.
     */
    public ResourceLocation getAircraftId() {
        return this.getVehicleId();
    }

    public AircraftDefinition getStats() {
        AircraftDefinition current = this.stats;

        if (current == null || this.statsVersion != Definitions.version()) {
            current = Definitions.AIRCRAFT.get(this.getAircraftId());
            this.stats = current;
            this.statsVersion = Definitions.version();
        }

        return current;
    }

    /** Acceleration along the nose at full throttle, in blocks/tick^2. */
    public float getMaxThrust() {
        return this.getStats().engine().maxThrust();
    }

    /** Throttle change per tick while a throttle key is held. */
    public float getThrottleRate() {
        return this.getStats().engine().throttleRate();
    }

    /** Whether this airframe has an afterburner at all. Most of them do not. */
    public boolean hasAfterburner() {
        return this.getStats().engine().afterburner().isPresent();
    }

    /** How much reheat the engine is delivering, 0 to 1. */
    @Override
    public float getAfterburner() {
        return this.reheat;
    }

    /** Whether the burner is alight: what the instruments, the plume and the note all read off. */
    public boolean isAfterburning() {
        return this.reheat > LIT;
    }

    /** Hard speed limit, in blocks/tick. */
    public float getMaxSpeed() {
        return this.getStats().wing().maxSpeed();
    }

    /** Below this airspeed the wings stop biting and the controls go soft. */
    public float getStallSpeed() {
        return this.getStats().wing().stallSpeed();
    }

    /** Lift produced per (block/tick)^2 of airspeed. Level flight happens where this equals gravity. */
    public float getLiftCoefficient() {
        return this.getStats().wing().lift();
    }

    /** Fraction of the current velocity lost every tick. */
    public float getDragCoefficient() {
        return this.getStats().wing().drag();
    }

    /** Degrees of pitch per tick at full deflection and full control authority. */
    public float getPitchRate() {
        return this.getStats().handling().pitchRate();
    }

    /** Degrees of roll per tick at full deflection and full control authority. */
    public float getRollRate() {
        return this.getStats().handling().rollRate();
    }

    /** Degrees of yaw per tick from the rudder alone. */
    public float getYawRate() {
        return this.getStats().handling().yawRate();
    }

    /** Impact speed, in blocks/tick, above which hitting something writes the aircraft off. */
    protected float getCrashSpeed() {
        return this.getStats().airframe().crashSpeed();
    }

    @Override
    protected float explosionPower() {
        return this.getStats().airframe().explosionPower();
    }

    /** Ticks the undercarriage takes to travel from up and locked to down and locked. */
    public int getGearCycleTicks() {
        return this.getStats().landingGear().cycleTicks();
    }

    /** Extra drag with the gear hanging out, as a fraction of the clean-airframe figure. */
    protected float getGearDragPenalty() {
        return this.getStats().landingGear().dragPenalty();
    }

    protected int getFlapsCycleTicks() {
        return this.getStats().flaps().cycleTicks();
    }

    /** Extra lift from fully lowered flaps, as a fraction of the clean-wing figure. */
    protected float getFlapsLiftBonus() {
        return this.getStats().flaps().liftBonus();
    }

    protected float getFlapsDragPenalty() {
        return this.getStats().flaps().dragPenalty();
    }

    // ------------------------------------------------------------------
    // What the base class needs from an aircraft
    // ------------------------------------------------------------------

    @Override
    public VehicleChassis.Hitbox hitbox() {
        return this.getStats().hitbox();
    }

    @Override
    public VehicleChassis.Sound soundSetup() {
        return this.getStats().sound();
    }

    @Override
    protected float health() {
        return this.getStats().airframe().health();
    }

    @Override
    protected int declaredSalvage() {
        return this.getStats().airframe().salvage();
    }

    @Override
    protected List<VehicleChassis.Seat> seats() {
        return this.getStats().airframe().seats();
    }

    @Override
    protected VehicleChassis.CameraMount cameraMount() {
        return this.getStats().camera();
    }

    /** Everything on an aeroplane is bolted to the airframe and is where the file says it is. */
    @Override
    protected Vec3 boxCentre(VehicleShape.Box box) {
        return this.position().add(Attitude.toWorld(this.attitude, box.offset()));
    }

    /** The box's own angle within the airframe, then the airframe's angle in the world. */
    @Override
    protected Quaternionf boxRotation(VehicleShape.Box box) {
        return new Quaternionf(this.attitude).mul(box.orientation());
    }

    /**
     * The pylons, which are not among the collision boxes: a place to hang a store is a place on the
     * aeroplane rather than a piece of it, and it is worth clicking on its own.
     */
    @Override
    protected List<VehiclePart> extraParts() {
        List<AircraftDefinition.Hardpoint> hardpoints = this.getStats().hardpoints();
        List<VehiclePart> pylons = new java.util.ArrayList<>(hardpoints.size());

        for (int i = 0; i < hardpoints.size(); i++) {
            pylons.add(VehiclePart.pylon(this, hardpoints.get(i).name(), i));
        }

        return pylons;
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_THROTTLE, 0.0F);
        builder.define(DATA_AFTERBURNER, 0.0F);
        builder.define(DATA_ATTITUDE, new Quaternionf());
        builder.define(DATA_VELOCITY, new Vector3f());
        builder.define(DATA_BODY_RATE, new Vector3f());
        builder.define(DATA_GEAR_DOWN, true);
        builder.define(DATA_FLAPS_DOWN, false);
        builder.define(DATA_VTOL, false);
        builder.define(DATA_WEAPONS, new CompoundTag());
        builder.define(DATA_FLARES, 0);
        builder.define(DATA_CHAFF, 0);
    }

    /**
     * What is on the hardpoints. The server's copy is the real one; a client reads whatever the
     * server last sent, which is enough for the instruments and for drawing the pods.
     */
    /**
     * The radar and the warning receiver.
     *
     * <p>Only ever asked on the server: by this aircraft's own tick, and by another aircraft's
     * receiver wanting to know whether this one's radar is holding it. A client is sent the picture
     * rather than allowed to work one out.
     */
    /**
     * How many flares, or how much chaff, is left aboard.
     *
     * @param flare true for flares, false for chaff
     */
    public int getCountermeasures(boolean flare) {
        return this.entityData.get(flare ? DATA_FLARES : DATA_CHAFF);
    }

    /** Never below nothing, and never above what the airframe holds. */
    public void setCountermeasures(boolean flare, int left) {
        int held = Mth.clamp(left, 0, this.getStats().countermeasures().capacity(flare));

        this.entityData.set(flare ? DATA_FLARES : DATA_CHAFF, held);
    }

    /**
     * How big this aircraft looks to a radar: its clean airframe plus whatever is bolted to the
     * outside of it.
     *
     * <p>Stores in a bay are not counted. That is the whole argument for a bay, and it is the one
     * decision a pilot of a stealth aeroplane actually gets to make about their own signature —
     * everything else about it was settled by whoever drew the shape.
     */
    public float radarCrossSection() {
        AircraftDefinition.Signature signature = this.getStats().signature();

        return signature.radar() + signature.store() * this.weapons.externalStores();
    }

    /**
     * How far a radar sees that entity, as a fraction of what the same radar manages against an
     * ordinary fighter. Anything that is not an aeroplane is an ordinary fighter as far as this is
     * concerned, which is a way of saying nothing has been decided about it.
     */
    public static float visibility(Entity entity) {
        return entity instanceof AircraftEntity aircraft
                ? AircraftDefinition.Signature.reach(aircraft.radarCrossSection())
                : 1.0F;
    }

    public WeaponMounts getWeapons() {
        return this.weapons;
    }

    /** An aeroplane aims by pointing itself, so where the weapons look is where the nose is. */
    @Override
    public Vec3 getAimDirection(float partialTick) {
        return Attitude.nose(this.getAttitude(partialTick));
    }

    @Override
    public VehicleChassis.Radar radar() {
        return this.getStats().radar();
    }

    /** The seeker lives with the pylons, since what it is looking for is what is hanging on them. */
    @Override
    public TargetLock lock() {
        return this.weapons.lock();
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);

        if (DATA_WEAPONS.equals(key) && this.level().isClientSide) {
            this.weapons.load(this.entityData.get(DATA_WEAPONS));
        }
    }

    public float getThrottle() {
        return this.throttle;
    }

    public void setThrottle(float throttle) {
        this.throttle = Mth.clamp(throttle, 0.0F, 1.0F);

        if (!this.level().isClientSide) {
            this.entityData.set(DATA_THROTTLE, this.throttle);
        }
    }

    /**
     * Points the aircraft. The heading and elevation Minecraft knows about are kept in step, since
     * vanilla sends those to everyone else and uses them to place riders.
     */
    public void setAttitude(Quaternionf attitude) {
        this.attitude = attitude.normalize(new Quaternionf());
        this.setYRot(Attitude.heading(this.attitude));
        this.setXRot(Attitude.elevation(this.attitude));

        if (!this.level().isClientSide) {
            this.entityData.set(DATA_ATTITUDE, this.attitude);
        }
    }

    /**
     * Snaps the attitude, leaving the renderer nothing to interpolate. For stand-in aircraft that
     * are placed and drawn in the same breath rather than ticked.
     */
    public void snapAttitude(Quaternionf attitude) {
        this.setAttitude(attitude);
        this.attitudeO = new Quaternionf(this.attitude);
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
        // Placed rather than flown here, so it is not turning and nothing should extrapolate as if
        // it were. Measuring the next tick's rate from here also stops the placement itself being
        // read as a turn, which on a stand-in aircraft can be most of a revolution.
        this.ratedAttitude.set(this.attitude);
        this.hasRatedAttitude = true;

        if (!this.level().isClientSide) {
            this.entityData.set(DATA_BODY_RATE, new Vector3f());
        }
    }

    /**
     * Takes the attitude from the client at the controls, one tick of theirs at a time.
     *
     * <p>Separate from {@link #setAttitude} for when it happens rather than for what it does: one of
     * these is one whole tick of the pilot's flight model, which is what makes the turn measured
     * across it the aircraft's real turn rate. The server's own tick is not a safe place to measure
     * that — the pilot's clock and the server's drift past one another, so a server tick sometimes
     * holds two of these updates and sometimes none, and a rate taken against it would report that
     * drift as an aeroplane rolling in fits.
     */
    public void reportAttitude(Quaternionf attitude) {
        this.setAttitude(attitude);
        this.recordTurnRate();
    }

    /**
     * Works out how far the aircraft has turned since this was last called and tells everyone
     * watching, as the rate they extrapolate its attitude with between updates.
     *
     * <p>Called once per update from the side that is flying: per packet for a piloted aircraft, per
     * tick for one the server is flying itself. Never more than once for the same rotation — the
     * flight model turns an aircraft in several steps within one tick, and each of those on its own
     * is a fraction of the turn rather than the rate.
     */
    private void recordTurnRate() {
        if (this.level().isClientSide) {
            return;
        }

        if (this.hasRatedAttitude) {
            this.entityData.set(DATA_BODY_RATE, Attitude.rotationVector(
                    new Quaternionf(this.ratedAttitude).conjugate().mul(this.attitude).normalize()));
        }

        this.ratedAttitude.set(this.attitude);
        this.hasRatedAttitude = true;
    }

    /**
     * Tells everyone watching how fast the aircraft is going, once a tick, from the server.
     *
     * <p>Nobody but the side running the flight model can answer this honestly, and every other copy
     * of the aircraft needs it to draw the thing without stuttering. {@link #getVelocity()} already
     * knows where to look — the pilot's own figure while one is at the stick, and what the aircraft
     * covered this tick otherwise — so this is only a matter of passing it on.
     */
    private void publishVelocity() {
        Vec3 velocity = this.getVelocity();

        this.entityData.set(DATA_VELOCITY,
                new Vector3f((float) velocity.x, (float) velocity.y, (float) velocity.z));
    }

    /** The attitude for rendering, taking the short way round between the last two ticks. */
    public Quaternionf getAttitude(float partialTick) {
        return new Quaternionf(this.attitudeO).slerp(this.attitude, partialTick).normalize();
    }

    /** Bank angle, positive with the right wing down. */
    public float getRoll() {
        return Attitude.bank(this.attitude);
    }

    public float getRoll(float partialTick) {
        return Attitude.bank(this.getAttitude(partialTick));
    }

    /**
     * How far the aircraft turned about its own axes over the last tick, in degrees. This is what
     * the renderer deflects the control surfaces by, and unlike the pilot's raw input it comes from
     * synced state, so it works on every client.
     */
    public float getRollDelta() {
        return this.bodyRate(2);
    }

    public float getPitchDelta() {
        return this.bodyRate(0);
    }

    public float getYawDelta() {
        return this.bodyRate(1);
    }

    /** One axis of the rotation from last tick to this one, measured in the aircraft's own frame. */
    private float bodyRate(int axis) {
        Quaternionf change = new Quaternionf(this.attitudeO).conjugate().mul(this.attitude).normalize();
        float sine = (float) Math.sqrt(Math.max(0.0, 1.0 - change.w * change.w));

        if (sine < 1.0E-5F) {
            return 0.0F;
        }

        float angle = 2.0F * (float) Math.acos(Mth.clamp(change.w, -1.0F, 1.0F));
        float component = switch (axis) {
            case 0 -> -change.x;
            case 1 -> -change.y;
            default -> change.z;
        };

        return (float) Math.toDegrees(angle * component / sine);
    }

    /** Whether this airframe has a lift system at all. */
    public boolean isVtolCapable() {
        return this.getStats().vtol().isPresent();
    }

    /** Whether the pilot has asked for the nozzle to be down. */
    public boolean isVtolSelected() {
        return this.entityData.get(DATA_VTOL);
    }

    /** How far the nozzle has actually swung, 0 to 1, interpolated for drawing. */
    public float getVtolProgress(float partialTick) {
        return Mth.lerp(partialTick, this.vtolProgressO, this.vtolProgress);
    }

    /** The same as an angle, in degrees off the tail. What the instruments show. */
    public float getNozzleAngle() {
        return this.vtolProgress * this.getStats().vtol().map(AircraftDefinition.Vtol::maxAngle).orElse(0.0F);
    }

    /**
     * Swings the nozzle the other way.
     *
     * <p>Refused above the aircraft's conversion speed, in the direction that puts it down: an engine
     * turned across the airflow at five hundred knots is not a lift system, it is an accident. Coming
     * back up is always allowed, which is what makes the conversion recoverable.
     */
    public void toggleVtol() {
        if (this.level().isClientSide || !this.isVtolCapable()) {
            return;
        }

        AircraftDefinition.Vtol vtol = this.getStats().vtol().get();

        if (!this.isVtolSelected() && this.getVelocity().length() > vtol.conversionSpeed()) {
            return;
        }

        this.entityData.set(DATA_VTOL, !this.isVtolSelected());
    }

    /** Whether this machine is held up by a rotor rather than by a wing. */
    public boolean isRotorcraft() {
        return this.getStats().rotor().isPresent();
    }

    /** How fast the rotor is turning, 0 stopped to 1 governed. Zero for anything without one. */
    public float getRotorSpeed() {
        return this.rotorSpeed;
    }

    /**
     * What the engine note should follow, 0 to 1.
     *
     * <p>The throttle, for an aeroplane, where the lever and the noise are the same thing. A
     * helicopter's rotor turns at one speed whatever the collective is doing, and it is the loudest
     * thing about the machine long before the pilot has asked it for anything — so there the note
     * follows the rotor coming up to speed, with a little of the collective over the top for the
     * load the engines take when the pilot pulls.
     */
    public float getEngineNote() {
        if (!this.isRotorcraft()) {
            return this.getThrottle();
        }

        return this.rotorSpeed * Mth.lerp(this.getThrottle(), ROTOR_IDLE_NOTE, 1.0F);
    }

    /** Where the main rotor has got to, in degrees, interpolated for drawing. */
    public float getRotorAngle(float partialTick) {
        return Mth.lerp(partialTick, this.rotorAngleO, this.rotorAngle);
    }

    /** The same for the tail rotor, which turns several times faster and is counted separately. */
    public float getTailRotorAngle(float partialTick) {
        return Mth.lerp(partialTick, this.tailAngleO, this.tailAngle);
    }

    public boolean isGearDown() {
        return this.entityData.get(DATA_GEAR_DOWN);
    }

    /**
     * Raises or lowers the undercarriage. Refused while the aircraft is sitting on its wheels, which
     * is the job a weight-on-wheels switch does on the real thing, and refused outright by an
     * aircraft whose legs do not go up.
     */
    public void toggleGear() {
        if (this.level().isClientSide || !this.getStats().landingGear().retractable()
                || (this.isGearDown() && this.onGround())) {
            return;
        }

        this.entityData.set(DATA_GEAR_DOWN, !this.isGearDown());
    }

    /**
     * Whether the undercarriage has finished going wherever it was going. The cycle animation is
     * held at its last frame while this is true rather than played, so an aircraft already sitting
     * on its wheels is not seen to lower them again.
     */
    public boolean isGearSettled() {
        return this.gearProgress == (this.isGearDown() ? 1.0F : 0.0F);
    }

    /** Undercarriage travel for rendering: 0 fully retracted, 1 fully extended. */
    public float getGearProgress(float partialTick) {
        return Mth.lerp(partialTick, this.gearProgressO, this.gearProgress);
    }

    public boolean isFlapsDown() {
        return this.entityData.get(DATA_FLAPS_DOWN);
    }

    public void toggleFlaps() {
        if (!this.level().isClientSide) {
            this.entityData.set(DATA_FLAPS_DOWN, !this.isFlapsDown());
        }
    }

    /** Flap travel for rendering: 0 retracted, 1 fully down. */
    public float getFlapsProgress(float partialTick) {
        return Mth.lerp(partialTick, this.flapsProgressO, this.flapsProgress);
    }

    public AircraftInput getInput() {
        return this.input;
    }

    public void setInput(AircraftInput input) {
        this.input = input;
    }

    /**
     * Whether the aeroplane is standing still enough for ground crew to work on it.
     *
     * <p>Wheels on the ground is the rule. "Not moving at all" is there beside it because that test
     * is not always awake: an aircraft settling onto its undercarriage, or one whose movement is
     * being run by a pilot's client rather than by the server, can report itself airborne for a tick
     * or two while plainly sitting on the apron. Arming a station is a single click, and a click
     * that lands in one of those ticks would silently do nothing at all, which is indistinguishable
     * from the aeroplane refusing the weapon.
     */
    public boolean isParked() {
        return this.onGround() || this.getVelocity().lengthSqr() < PARKED_SPEED;
    }

    /** True once the airframe has hit something hard enough to write it off. */
    public boolean isCrashing() {
        return this.crashing;
    }

    /** The angle the wing is meeting the airflow at, in degrees. */
    public float getAngleOfAttack() {
        return this.angleOfAttack;
    }

    /** True once the wing has stopped flying, which is a matter of angle rather than speed. */
    public boolean isStalled() {
        return !this.onGround() && !this.isHovering()
                && Math.abs(this.angleOfAttack) > this.getStats().wing().stallAngle();
    }

    /**
     * Whether the lift system is carrying enough of the aircraft for the wing not to matter.
     *
     * <p>Asked wherever something would otherwise be alarmed by a wing that has stopped flying. It
     * has stopped flying; that is what the nozzle is for. A helicopter answers yes always, its wing
     * having never been what was holding it up in the first place.
     */
    public boolean isHovering() {
        if (this.isRotorcraft()) {
            return true;
        }

        return this.getStats().vtol()
                .map(vtol -> this.vtolProgress * vtol.maxAngle() > HOVERING_ANGLE)
                .orElse(false);
    }

    // ------------------------------------------------------------------
    // Ticking
    // ------------------------------------------------------------------

    @Override
    public void tick() {
        if (this.getHurtTime() > 0) {
            this.setHurtTime(this.getHurtTime() - 1);
        }

        super.tick();
        this.attitudeO = new Quaternionf(this.attitude);
        this.tickGear();
        this.tickVtol();
        this.tickRotor();
        this.tickLerp();

        if (this.isControlledByLocalInstance()) {
            if (!(this.getControllingPassenger() instanceof Player)) {
                // Nobody at the stick: the controls centre, but the throttle stays where it was left
                // and the aircraft flies on until something takes it out of the air.
                this.input = AircraftInput.NONE;
            }

            // A wreck is not flown. It falls, and then it lies where it landed.
            if (this.isWrecked()) {
                this.wreckTick();
            } else {
                this.flightTick();
            }

            Vec3 impactVelocity = this.getDeltaMovement();

            // Ground that arrived around the aeroplane is not ground the aeroplane flew into.
            //
            // Chunks are asked for along the flight path and made on somebody else's threads, and at
            // a few hundred knots the asking can lose the race: the aircraft crosses air that has not
            // been decided yet, and a moment later the hillside that was always going to be there
            // exists, with the aeroplane inside it. Collided with in the ordinary way that is a
            // crash, and the pilot is destroyed by terrain they never saw and could not have avoided.
            //
            // So an aircraft already inside the world flies out of it rather than into it. Nothing
            // else changes: a hillside met from the outside stops the aeroplane at its surface, as it
            // always did, because the swept test in move() cannot be tunnelled through. The only way
            // to be inside something here is to have had it appear.
            //
            // Not for a wreck, though, and the difference matters: what flies an aircraft out of a
            // hillside is its own airspeed, and a wreck's is a slow drift downwards. Pushed through
            // the world by that with nothing to stop it -- this branch does not collide with
            // anything -- a write-off that came down on a slope would sink through the ground and go
            // on sinking. One embedded in a hillside simply stops there instead, which is what a
            // wreck in a hillside ought to do.
            //
            // Out, and never further in. This branch collides with nothing, so whatever it is
            // handed is a distance the aeroplane covers through solid rock; sideways and upwards
            // that is the escape, but in a world made of ground, downwards is only ever deeper.
            // Left to carry a rate of descent, an aircraft that found itself inside something low
            // down would be posted through the floor a little further every tick and never come to
            // anything that could stop it. Held level instead, it leaves on its airspeed, which is
            // what was meant to be getting it out.
            if (!this.isWrecked() && this.insideTerrain()) {
                this.setPos(this.getX() + impactVelocity.x,
                        this.getY() + Math.max(impactVelocity.y, 0.0),
                        this.getZ() + impactVelocity.z);
            } else {
                this.move(MoverType.SELF, impactVelocity);

                // A wreck meeting a hillside is a wreck meeting a hillside. There is no airframe left
                // to write off and nothing to be decided about how hard it arrived.
                if (!this.isWrecked()) {
                    this.detectCrash(impactVelocity);
                }
            }

            if (!this.level().isClientSide) {
                // Flown here, so this side is the one that knows. Measured after the move: what the
                // aircraft covered is what everyone else has to draw, and a hillside can take a good
                // deal of that away between the flight model asking and the world agreeing.
                this.recordTurnRate();
                this.publishVelocity();
            }
        } else {
            // How far this side actually saw the aircraft move, and deliberately not the speed the
            // pilot reports. An aircraft is registered for velocity updates, so the server
            // broadcasts a motion packet whenever this changes — and the pilot's own client is
            // among those told. Putting the true speed in here sent the pilot a packet every tick,
            // and their client applied it over the velocity its flight model had just worked out,
            // throwing away a tick of turn every tick. Left as the difference this side can see, it
            // is a steady zero on the server and nothing is ever broadcast.
            //
            // The over-G check is fed the same figure, which on the server is that same zero, so on
            // a piloted aircraft it does nothing. That is how it has always been, and switching it
            // on is a change of a different kind rather than a repair: at the Su-25's present
            // numbers a hard turn reaches about twelve times gravity against a limit of six and a
            // half, which would break the airframe in under half a second. The limit and the
            // damage want retuning together before they are given teeth.
            Vec3 travelled = this.travelled();
            this.setDeltaMovement(travelled);
            this.throttle = this.entityData.get(DATA_THROTTLE);
            // Not worked out on this side at all: there is no gate to hold here and no latch to
            // hold it with, so what the burner is doing is whatever the side flying the aeroplane
            // last said it was doing.
            this.reheat = this.entityData.get(DATA_AFTERBURNER);
            // Nothing here spools an engine, but this side may be handed the aircraft at any moment
            // — a pilot climbing out, or a client taking over the controls — and starting from a cold
            // engine on an aeroplane that is already flying would drop it out of the sky.
            this.thrustLevel = this.throttle;

            Quaternionf reported = this.entityData.get(DATA_ATTITUDE);

            if (this.level().isClientSide) {
                // Drawn, so it is worth keeping the aircraft turning between the attitudes that
                // arrive rather than letting it sit still and then snap. See AircraftInterpolation.
                Vector3f rate = this.entityData.get(DATA_BODY_RATE);

                // Before the attitude, so a correction is taken up knowing what the aircraft is
                // doing rather than having to work it out from the corrections themselves.
                this.interpolation.receiveBodyRate(rate.x(), rate.y(), rate.z());

                if (this.interpolation.isNewAttitude(reported)) {
                    this.interpolation.receiveAttitude(reported);
                }
                this.interpolation.advanceAttitude(this.attitude);
                this.setYRot(Attitude.heading(this.attitude));
                this.setXRot(Attitude.elevation(this.attitude));
            } else {
                // The server draws nothing and is the one place this value is authoritative.
                // Extrapolating here would only feed a guess back into what it broadcasts.
                this.attitude = new Quaternionf(reported);
            }

            if (!this.level().isClientSide) {
                this.checkStructuralLoad(travelled);
                // Flown by a client, so what goes out is what that client said. The turn rate is
                // recorded as each of its updates lands rather than here; see reportAttitude.
                this.publishVelocity();
            }
        }

        if (this.crashing) {
            this.crash();
        }

        this.tickParts();

        if (this.level().isClientSide) {
            this.spawnFlightEffects();
        } else if (!this.isWrecked()) {
            // A burnt-out airframe has no trigger to hold, no radar to sweep and no dispenser to fire.
            this.tickWeapons();
            this.getSensors().tick();
            this.dispenser.tick(this.input.flare(), this.input.chaff());
        }

        // Hold the chunk under us open, so flying beyond everyone's render distance does not simply
        // stop the aircraft existing.
        this.heldChunks = AircraftChunkLoader.update(this, this.heldChunks);

        this.checkInsideBlocks();
    }

    /**
     * Fires whatever the trigger is pointed at, and tells the clients what is left. Server only: the
     * flight model is run by whoever is flying, but rounds are the server's business, so a client
     * cannot conjure them or claim a hit.
     */
    private void tickWeapons() {
        this.weapons.tick(this.input.fire());

        if (this.weapons.consumeDirty()) {
            this.entityData.set(DATA_WEAPONS, this.weapons.syncTag());
        }
    }

    /** Selects the next weapon aboard. Called from the pilot's input packet, so server side. */
    public void cycleWeapon() {
        if (!this.level().isClientSide) {
            this.weapons.selectNext();
        }
    }

    /**
     * Whether the aircraft is inside the world rather than in front of it.
     *
     * <p>Asked before every move, and true only when the ground got there second: an aeroplane that
     * flies at a hillside is stopped at its face, so being <em>within</em> one means it appeared. On
     * the wheels this is never asked, since an aeroplane standing on the ground is standing on it and
     * a wheel resting in a block edge is not an emergency.
     *
     * <p>Being on the wheels is not the only way to be over the runway, though, and that is what the
     * floor line is for. The last few feet of an approach are flown nose-up, and an airframe is
     * longer than its undercarriage is tall: a flare puts the tail a good half-block below the
     * wheels, a bank does the same to a wingtip, and the wheels are still in the air the whole time.
     * Measured against every block alike, the aeroplane is then <em>inside</em> the runway it is
     * about to land on, and this branch flies it out of the world — downwards, because that is the
     * way it was going — and on down through the floor for good. So ground that reaches no higher
     * than the wheels is the floor, not the world: {@link #floorLine}, and the same line
     * {@link #move} already scrapes over.
     */
    private boolean insideTerrain() {
        return !this.onGround() && !this.hasRoomHere(EMBEDDED_MARGIN, this.floorLine());
    }

    /**
     * Decides whether the impact that just stopped the aircraft was survivable. The velocity from
     * before {@link #move} is what counts: move() zeroes the blocked axes, so by the time it returns
     * there is nothing left to measure.
     */
    private void detectCrash(Vec3 impactVelocity) {
        if (!this.horizontalCollision && !this.verticalCollision) {
            return;
        }

        float limit = this.getCrashSpeed();

        // Whether what just happened was a landing rather than an arrival. It takes all three: the
        // undercarriage out, the aircraft the right way up on it, and a rate of descent an
        // undercarriage can absorb. Flying into the ground fails the last of those however level the
        // wings are and however far down the gear is, which is the point — a shallow dive into a
        // field blocks only the vertical axis and lets the aircraft slide, so nothing about the
        // horizontal speed alone can tell a crash from a rollout.
        boolean landing = this.gearProgress > 0.5F
                && this.getLiftVector().y > UPRIGHT
                && impactVelocity.y > -limit * TOUCHDOWN_SINK;

        if (landing) {
            // Down safely, but there is still such a thing as running into something afterwards.
            // Measured as the speed the impact actually took away — move() has already zeroed
            // whichever axes were blocked — so that a wingtip brushing a runway light, or the corner
            // of a six-block-wide box catching a block edge, is the nothing it ought to be.
            Vec3 surviving = this.getDeltaMovement();
            double before = Math.sqrt(impactVelocity.x * impactVelocity.x + impactVelocity.z * impactVelocity.z);
            double after = Math.sqrt(surviving.x * surviving.x + surviving.z * surviving.z);

            if (this.horizontalCollision && before - after > limit) {
                this.crashing = true;
            }

            return;
        }

        // Anything else that has just met the world: how fast it was going into it, in all three
        // axes together. Sliding along the ground after the vertical axis was blocked does not make
        // the impact survivable, and reading only the axis that happened to be stopped is what let
        // a dive into terrain be walked away from.
        if (impactVelocity.length() > limit) {
            this.crashing = true;
        }
    }

    /**
     * The reheat gate, and the burner behind it. Run once a tick by whichever side is flying, before
     * the throttle lever is allowed to move.
     *
     * <p><b>Why there is no key for this.</b> An afterburner is not a switch on the panel; it is the
     * top of the throttle's own travel, past a stop the pilot has to push the lever through. So it
     * is flown with the throttle: hold the lever open with the engine already giving everything it
     * has and, after {@link #GATE_TICKS}, it goes through the gate. It latches there, because a
     * throttle stays where it is put and nobody flies a supersonic dash holding a key down.
     *
     * <p>Coming back out is the same gate from the other side. The first pull on the throttle takes
     * the lever out of reheat and no further — which is what the {@code true} this can return is
     * for — so a pilot who wanted military power gets military power rather than sliding through it
     * on the way down. The second pull, and every one after it, moves the lever as it always did.
     *
     * <p>What the burner then delivers chases the latch rather than matching it. Quicker than the
     * engine spools, because lighting reheat is a match rather than a turbine coming up to speed,
     * but not instant: the plume, the note and the shove all want a moment to arrive.
     *
     * @return true if this tick's throttle input was spent coming out of the gate rather than on
     *         the lever
     */
    private boolean tickAfterburner(AircraftDefinition definition) {
        AircraftDefinition.Afterburner burner = definition.engine().afterburner().orElse(null);

        if (burner == null) {
            this.reheatCommanded = false;
            this.gateHeld = 0;
            this.reheat = 0.0F;

            return false;
        }

        // Not with the lift system out, whatever the pilot asks for. The exhaust is being turned
        // down through a nozzle and a good deal of the engine is driving a fan in the roof: there is
        // nowhere to put reheat, and an aeroplane that lit it in the hover would be a rocket pointed
        // at the ground. Being held at full throttle is what a conversion looks like from in here,
        // so without this the gate would open every single time.
        boolean converted = this.vtolProgress > 0.0F;
        boolean swallowed = false;

        if (converted) {
            this.reheatCommanded = false;
            this.gateHeld = 0;
        } else if (this.input.throttle() < 0.0F) {
            this.gateHeld = 0;
            swallowed = this.reheatCommanded;
            this.reheatCommanded = false;
        } else if (this.reheatCommanded) {
            // Latched. Nothing to count towards, and nothing the pilot has to keep doing.
            this.gateHeld = GATE_TICKS;
        } else if (this.throttle >= 1.0F && this.input.throttle() > 0.0F) {
            this.reheatCommanded = ++this.gateHeld >= GATE_TICKS;
        } else {
            this.gateHeld = 0;
        }

        // And it goes out the moment the lever comes off its stop, whatever the latch says. The
        // burner is fed by the engine in front of it, and an engine at part throttle has nothing
        // spare to burn.
        float commanded = this.reheatCommanded && this.throttle >= 1.0F ? 1.0F : 0.0F;
        this.reheat += (commanded - this.reheat) * Mth.clamp(burner.lightRate(), 0.01F, 1.0F);

        if (this.reheat < 1.0E-3F) {
            this.reheat = 0.0F;
        }

        if (!this.level().isClientSide) {
            // Flown by this side, so this side is the one that publishes it. A client at the
            // controls sends its own figure up instead; see reportAfterburner.
            this.entityData.set(DATA_AFTERBURNER, this.reheat);
        }

        return swallowed;
    }

    /** What the burner is multiplying the engine's thrust by. One with no burner, or an unlit one. */
    private double reheatThrust() {
        if (this.reheat <= 0.0F) {
            return 1.0;
        }

        AircraftDefinition.Afterburner burner = this.getStats().engine().afterburner().orElse(null);

        return burner == null ? 1.0 : burner.thrustFactor(this.reheat);
    }

    /**
     * The reheat setting from the client that is flying, taken on trust and mirrored to everyone
     * else — the same arrangement, and for the same reason, as the throttle beside it.
     *
     * <p>Clamped, and refused outright by an airframe with no burner in its file, so that what
     * arrives can only ever be a figure the aircraft could have produced itself. It matters more
     * here than it does for the throttle: the thrust is the client's business either way, but how
     * far a seeker can see this aeroplane is decided on this side, off this number.
     */
    public void reportAfterburner(float level) {
        float delivered = this.hasAfterburner() ? Mth.clamp(level, 0.0F, 1.0F) : 0.0F;
        boolean was = this.reheat > LIT;

        this.reheat = delivered;
        this.entityData.set(DATA_AFTERBURNER, delivered);

        if (!was && delivered > LIT) {
            this.playAfterburnerLight();
        }
    }

    /**
     * The bang of the burner catching, heard where it happens.
     *
     * <p>Sent at its own loudness rather than with the reach in the volume slot, unlike a weapon's
     * report: this is a noise the aeroplane makes, and the aeroplane already has a note that
     * carries as far as its file says. What lighting the burner adds is for the pilot and for
     * anybody it has just gone over the top of.
     *
     * <p>The recording is looked for under this aircraft's own name and resolved on the client,
     * which is the only side that has ever seen a resource pack. Nothing here is shipped; see
     * {@code AfterburnerSounds}, which finds something to put in its place.
     */
    private void playAfterburnerLight() {
        ResourceLocation id = this.getAircraftId();
        ResourceLocation event = id.withPath(
                VehicleEntityBase.SOUND_PREFIX + id.getPath() + "." + AFTERBURNER_ROLE);

        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvent.createVariableRangeEvent(event), SoundSource.NEUTRAL,
                AFTERBURNER_VOLUME, AFTERBURNER_LIGHT_PITCH);
    }

    /**
     * How hot this aircraft looks to something homing on heat: what the airframe is worth cold, and
     * the burner over the top of it while it is lit.
     *
     * <p>The counterpart of {@link #radarCrossSection}, and deliberately not the same figure. What
     * a stealth aeroplane bought with its shape was a small radar return; nothing about that shape
     * makes its exhaust any cooler, and lighting the burner throws the difference away in any case.
     */
    public float infraredSignature() {
        AircraftDefinition.Afterburner burner = this.getStats().engine().afterburner().orElse(null);
        float clean = this.getStats().signature().heat();

        return burner == null ? clean : clean * burner.heatFactor(this.reheat);
    }

    /**
     * How far a heat-seeking head sees that entity, as a fraction of what the same head manages
     * against the hottest thing it will ever be pointed at. Anything that is not an aeroplane is
     * that hottest thing as far as this is concerned, which is a way of saying nothing has been
     * decided about it.
     */
    public static float heatVisibility(Entity entity) {
        return entity instanceof AircraftEntity aircraft
                ? AircraftDefinition.Signature.heatReach(aircraft.infraredSignature())
                : 1.0F;
    }

    /**
     * One tick of flight, under whichever model this machine is flown by.
     *
     * <p>Two of them, and they are genuinely different aircraft rather than one with a switch. An
     * aeroplane is thrown forward and held up by the air it is passing through; a helicopter carries
     * its own airflow and is held up by it standing still. Which one applies is decided by the file:
     * a {@code rotor} block makes the machine a helicopter, and nothing else does.
     */
    private void flightTick() {
        AircraftDefinition definition = this.getStats();
        AircraftDefinition.Rotor rotor = definition.rotor().orElse(null);

        if (rotor == null) {
            this.wingFlightTick(definition);
        } else {
            this.rotorFlightTick(definition, rotor);
        }
    }

    /**
     * One tick of flight on a wing.
     *
     * <p>The aircraft is not pushed along its nose. Thrust acts along the nose, gravity acts down,
     * and the wing produces lift square to the airflow in proportion to the angle it meets that
     * airflow at. Everything that makes an aeroplane feel like one falls out of that: it has to be
     * rotated to leave the ground, a bank turns it because the lift tilts with the wings, hauling
     * the nose up past the stalling angle drops it, and a hard turn bleeds speed because lift is not
     * free.
     */
    private void wingFlightTick(AircraftDefinition definition) {
        AircraftDefinition.Wing wing = definition.wing();
        AircraftDefinition.Handling handling = definition.handling();
        AircraftDefinition.Undercarriage gear = definition.landingGear();
        boolean rolling = this.onGround();

        Vec3 motion = this.getDeltaMovement();
        double speed = motion.length();

        // The gate is worked before the lever moves, because it sits in the lever's travel rather
        // than beside it: coming out of reheat is the first thing a pull on the throttle does, and
        // on that one tick it is all it does. See tickAfterburner.
        if (!this.tickAfterburner(definition)) {
            this.setThrottle(this.throttle + this.input.throttle() * definition.engine().throttleRate());
        }

        // The lever moves at once and the engine does not. A turbofan asked for full power takes
        // several seconds to give it, and most of what a takeoff roll feels like is that wait.
        float spool = Mth.clamp(definition.engine().spoolRate(), 0.01F, 1.0F);
        this.thrustLevel += (this.throttle - this.thrustLevel) * spool;

        // The air thins with height, and both the engine and the wing are working it. That is the
        // whole of why an aircraft has a ceiling: climb far enough and there is no longer enough air
        // to make the lift the weight needs, whatever the pilot does with the nose.
        double density = this.airDensity();

        // How far the nozzle has swung, as the fraction of the engine that is now holding the
        // aeroplane up rather than pushing it along. Zero for everything that cannot do it at all.
        AircraftDefinition.Vtol vtol = definition.vtol().orElse(null);
        double lifting = vtol == null
                ? 0.0
                : Math.sin(Math.toRadians(this.vtolProgress * vtol.maxAngle()));

        // Control surfaces only bite while air is flowing over them, and what they have to work with
        // is the dynamic pressure — the air's density against the square of the speed — rather than
        // the speed itself. One at the stalling speed at sea level, so the files' rates still mean
        // what they meant; less than the old figure below that and more above it, which is the
        // difference between an aeroplane that is soft near the stall and one that merely feels slow.
        double reference = Math.max(wing.stallSpeed(), 1.0E-4F);
        double pressure = density * speed * speed / (reference * reference);
        float authority = (float) Math.min(pressure, AUTHORITY_CEILING);

        // And the same surfaces that give the pilot authority damp the rotation they cause. Without
        // this the authority climbs with speed and nothing climbs with it to settle the result, so a
        // fast aircraft wallows instead of stiffening up the way a real one does.
        float damping = 1.0F + handling.aeroDamping() * (float) pressure;

        // A lift system does not care about any of that: what flies a hovering aeroplane is jets of
        // its own, and without them the pilot would have the controls of a brick from the moment the
        // wing stopped working.
        if (vtol != null) {
            authority = Math.max(authority, (float) (lifting * vtol.authority()));
        }
        float previousYRot = this.getYRot();
        float weathervaneYaw = 0.0F;

        // Commanded rates, reached over a few ticks rather than at once: a control surface has to
        // work against the mass of the aircraft. These are rates about the aircraft's own axes, not
        // the world's: the elevator swings the nose towards the top of the canopy wherever that is
        // pointing, which is why a banked aircraft pulls round into a turn instead of climbing.
        float lag = Mth.clamp(handling.controlLag(), 0.02F, 1.0F);

        float commandedPitch = this.limitToWing(
                this.input.pitch() * handling.pitchRate() * authority / damping, lifting);

        // On the wheels, the nose does not come up until there is enough air over the tailplane to
        // lift it. That speed is what makes a takeoff a takeoff: the aircraft runs, the stick does
        // nothing however hard it is pulled, and then within a few knots the nose becomes light and
        // comes up — after which the wing is already close to flying and the aircraft leaves the
        // ground on its own. Without the gate the pilot simply rotates on the spot and sits there
        // nose-high waiting for the wing to catch up, dragging the tail along the runway.
        float wheels = rolling ? this.rotationAuthority(wing, speed) : 1.0F;

        if (rolling && commandedPitch > 0.0F) {
            commandedPitch *= wheels;
        }

        float commandedRoll = this.input.roll() * handling.rollRate() * authority / damping;

        // And the same for the ailerons, for the same reason: what holds an aeroplane's wings level
        // on the runway is its undercarriage, not its controls. Left ungated they win the argument
        // slowly — the wheels level the aircraft a quarter of the way each tick, the ailerons keep
        // adding to it, and full deflection settles at several degrees of bank while the wheels are
        // still firmly on the ground. They come alive as the wing takes the weight, which is the
        // point at which they really would.
        if (rolling) {
            commandedRoll *= wheels;
        }

        this.pitchVelocity += (commandedPitch - this.pitchVelocity) * lag;
        this.rollVelocity += (commandedRoll - this.rollVelocity) * lag;
        this.yawVelocity += (this.input.yaw() * handling.yawRate() * authority / damping - this.yawVelocity) * lag;

        // The nosewheel, which is a different thing entirely and the reason an aeroplane can be
        // steered off a stand. A wheel on the ground does not care how fast the air is going past the
        // fin, so this bypasses the authority the airflow grants and answers at once rather than
        // through the control lag. It lets go as the rudder takes over, because a nosewheel that
        // still bit at speed would throw the aircraft off the runway rather than track it down one.
        float nosewheel = 0.0F;

        if (rolling) {
            float grip = (float) Mth.clamp(1.0 - speed / Math.max(gear.steerFade(), 1.0E-3F), 0.0, 1.0);

            nosewheel = this.input.yaw() * gear.steerRate() * grip;
        }

        Vec3 nose = this.getNoseVector();
        Vec3 up = this.getLiftVector();
        Vec3 right = Attitude.right(this.attitude);

        // Thrust along the nose, or under the aeroplane, or anywhere between the two. Turning the
        // nozzle down does not merely point the same push somewhere else: a lift system is worth more
        // than the cruise engine it is bolted to, and has to be, since nothing holds an aeroplane up
        // at a standstill but the engine and the engine has to beat gravity to do it.
        Vec3 thrustAxis = lifting <= 0.0 ? nose : nose.scale(Math.cos(Math.asin(lifting))).add(up.scale(lifting));
        double thrust = vtol == null
                ? definition.engine().maxThrust()
                : Mth.lerp(lifting, definition.engine().maxThrust(), vtol.liftThrust());

        // Against the air it is breathing, against what the engine is actually delivering rather
        // than what the lever is asking for, and with the burner over the top of that.
        Vec3 forces = new Vec3(0.0, -GRAVITY, 0.0)
                .add(thrustAxis.scale(thrust * this.thrustLevel * this.reheatThrust() * density));

        // And a hover is not a slide. Nothing aerodynamic bites at a walking pace -- drag is a square
        // law and squares of small numbers are nothing -- so an aeroplane nudged sideways in the
        // hover would keep going sideways until it hit something. This is the lift system holding
        // station, and it is the difference between hovering and merely falling slowly.
        //
        // It lets go as the wing takes over, and that matters more than the holding does. Left on at
        // all speeds it is not station-keeping but a parking brake: an aeroplane trying to accelerate
        // out of the hover reached a fraction of its stalling speed and stayed there, so the wing
        // never started flying and the conversion could not be made at all.
        if (lifting > 0.0 && speed > 1.0E-4) {
            double band = Math.max(wing.stallSpeed() * HOVER_BAND, 1.0E-4);
            double slow = Mth.clamp(1.0 - speed / band, 0.0, 1.0);

            forces = forces.add(motion.scale(-vtol.hoverDrag() * lifting * slow));
        }

        double lift = 0.0;

        if (speed > 1.0E-4) {
            Vec3 flow = motion.scale(1.0 / speed);

            // Angle of attack: how far below the wing the air is coming from.
            this.angleOfAttack = (float) Math.toDegrees(Math.asin(Mth.clamp(-flow.dot(up), -1.0, 1.0)));
            double liftCoefficient = wing.liftCoefficient(this.angleOfAttack)
                    * (1.0 + this.flapsProgress * this.getFlapsLiftBonus())
                    * this.groundEffect();

            // Lift acts square to the airflow, tilted with the wings. That tilt is what turns the
            // aircraft: bank, and the same force that was holding it up starts pulling it round.
            Vec3 liftAxis = up.subtract(flow.scale(up.dot(flow)));

            lift = wing.lift() * liftCoefficient * speed * speed * density;

            if (liftAxis.lengthSqr() > 1.0E-8) {
                forces = forces.add(liftAxis.normalize().scale(lift));
            }

            // Drag: what the shape costs, plus what the lift costs. The second is why a hard turn
            // washes speed off.
            double parasitic = wing.drag() * (1.0
                    + this.gearProgress * this.getGearDragPenalty()
                    + this.flapsProgress * this.getFlapsDragPenalty())
                    * (this.input.brake() ? 4.0 : 1.0);
            double drag = parasitic + wing.inducedDrag() * liftCoefficient * liftCoefficient;
            forces = forces.add(flow.scale(-drag * speed * speed * density));
            this.checkStructuralLoad(motion);

            // The fin drags the nose round onto the flight path. Like the rudder, it acts about the
            // aircraft's own vertical axis, so upside down it pulls the same way relative to the
            // aircraft and the opposite way relative to the world, exactly as a fin does. Not while
            // the wheels are down: on the runway it is the undercarriage that decides where the nose
            // points, and a fin arguing with it is how an aircraft ends up weaving down the centreline.
            if (!rolling) {
                weathervaneYaw = (float) (flow.dot(right) * handling.weathervane() * authority / damping);

                // And the fuselage refuses to fly sideways.
                motion = motion.subtract(right.scale(motion.dot(right) * wing.lateralDrag()));
            }
        } else {
            this.angleOfAttack = 0.0F;
        }

        // How much of the weight is still on the tyres. Kept for the ground handling below, and it
        // is what makes the end of a takeoff roll go light instead of gripping to the last instant.
        this.weightOnWheels = (float) Mth.clamp(1.0 - lift / GRAVITY, 0.0, 1.0);

        this.applyBodyRotation(this.rollVelocity, this.pitchVelocity, this.yawVelocity + weathervaneYaw + nosewheel);
        this.deltaRotation = Mth.wrapDegrees(this.getYRot() - previousYRot);
        motion = motion.add(forces);

        if (rolling) {
            motion = this.groundTick(motion);
        }

        // Only a backstop against a runaway, and only if the file asks for one: drag settles the
        // top speed on its own.
        if (wing.maxSpeed() > 0.0F && motion.length() > wing.maxSpeed()) {
            motion = motion.normalize().scale(wing.maxSpeed());
        }

        this.setDeltaMovement(motion);
    }

    /**
     * One tick of flight on a rotor.
     *
     * <p>The whole of a helicopter is one force. The rotor pulls square to its own disc, the disc is
     * square to the machine hanging under it, and so pointing the machine is the only steering there
     * is: nose down and it goes forward, banked and it goes sideways, level and it hangs there. There
     * is no thrust along the nose to be found anywhere below, because a helicopter has none — what
     * carries it forward is a share of the same force that is holding it up, which is exactly why one
     * is nose-down in the cruise and why hauling the collective in a hurry makes it climb rather than
     * accelerate.
     *
     * <p>The cyclic therefore walks the disc round and then leaves it there, rather than springing
     * back to level the moment the key comes up. That is what the attitude-hold system every modern
     * helicopter carries does, and on a keyboard it is the only way to ask for a cruise at all: a key
     * is all the way down or not down, so a stick that returned to level would leave the machine with
     * two settings, hovering and charging, and nothing whatever between them. Levelling off is
     * something the pilot does, exactly as it is in the real thing. The one thing the machine insists
     * on for itself is the tilt limit, past which the disc is walked back — so a helicopter here
     * cannot be turned over, by the pilot or by a blast or by flying into a hill.
     *
     * <p>Everything aerodynamic is still here and still means what it does on an aeroplane — drag,
     * the fin, the stub wings a gunship carries — but all of it goes as the square of the speed, and
     * a helicopter spends its life at speeds where squares of small numbers are nothing. That is the
     * point: what flies this machine is the rotor, and the rotor does not care whether it is going
     * anywhere.
     */
    private void rotorFlightTick(AircraftDefinition definition, AircraftDefinition.Rotor rotor) {
        AircraftDefinition.Wing wing = definition.wing();
        AircraftDefinition.Handling handling = definition.handling();
        AircraftDefinition.Undercarriage gear = definition.landingGear();
        boolean rolling = this.onGround();

        Vec3 motion = this.getDeltaMovement();
        double speed = motion.length();

        // The collective. Same lever and same keys as an aeroplane's throttle, doing a quite
        // different job: not how fast the machine goes, but how hard the rotor pulls and therefore
        // whether it goes up or down.
        this.setThrottle(this.throttle + this.input.throttle() * definition.engine().throttleRate());

        // Blade pitch answers within a moment. What takes time on a helicopter is the rotor itself,
        // and that is wound up in tickRotor rather than here.
        float spool = Mth.clamp(definition.engine().spoolRate(), 0.01F, 1.0F);
        this.thrustLevel += (this.throttle - this.thrustLevel) * spool;

        double density = this.airDensity();
        float collective = Mth.clamp(this.thrustLevel, 0.0F, 1.0F);

        // Lift goes as the square of the speed the blades are turning at, so a rotor at half speed is
        // worth a quarter of its lift and the machine is not going anywhere. This is what the wait
        // after climbing in actually buys.
        double turning = (double) this.rotorSpeed * this.rotorSpeed;

        // The fin and the stub wings, which are an aeroplane's arrangement and behave like one's: on
        // the dynamic pressure, and on nothing whatever while the machine is standing still.
        double reference = Math.max(wing.stallSpeed(), 1.0E-4F);
        double pressure = density * speed * speed / (reference * reference);
        float damping = 1.0F + handling.aeroDamping() * (float) pressure;

        // And what actually flies the helicopter, which is the rotor and is unaffected by any of
        // that. A machine at a standstill has full control and a machine at speed has no more, which
        // is the opposite of an aeroplane and is the whole reason one can be flown into a clearing.
        float bite = Math.min(this.rotorSpeed * this.rotorSpeed * rotor.authority(), 1.0F);

        float previousYRot = this.getYRot();
        float weathervaneYaw = 0.0F;
        float lag = Mth.clamp(handling.controlLag(), 0.02F, 1.0F);
        float tilt = Mth.clamp(rotor.maxTilt(), 1.0F, 89.0F);
        float trim = Mth.clamp(rotor.trim(), 0.01F, 1.0F);

        // The cyclic walks the disc round and then leaves it where it was put, which is what an
        // attitude-hold system does and the only way a keyboard can ask for a cruise: a key is all
        // the way down or not down at all, so a stick that sprang back to level would leave the
        // machine with two settings, hovering and charging, and nothing whatever between them.
        //
        // What is subtracted is the limiter, and it is nothing at all while the machine is inside
        // its limits — which is where it spends its life. Past them it walks the disc back, so a
        // helicopter cannot be tipped over, by the pilot or by a blast or by flying into a hill.
        // Minecraft's elevation is positive nose-down, hence the negation into the nose-up figure
        // the rest of this class works in; the bank angle is already positive with the right wing low.
        float commandedPitch = Mth.clamp(
                this.input.pitch() * handling.pitchRate() * trim
                        - overTilt(-this.getXRot(), tilt) * rotor.stability(),
                -handling.pitchRate(), handling.pitchRate()) * bite;
        float commandedRoll = Mth.clamp(
                this.input.roll() * handling.rollRate() * trim
                        - overTilt(this.getRoll(), tilt) * rotor.stability(),
                -handling.rollRate(), handling.rollRate()) * bite;

        // On the wheels the cyclic argues with the undercarriage and loses, as it should: what holds
        // a parked helicopter level is its wheels. It comes alive exactly as the rotor takes the
        // weight, which is the moment a real one goes light on the skids and starts to be flown.
        if (rolling) {
            float airborne = 1.0F - this.weightOnWheels;

            commandedPitch *= airborne;
            commandedRoll *= airborne;
        }

        // The pedals, which are a rate and not an angle: a tail rotor swings the nose and then leaves
        // it wherever it was put, without the machine going anywhere. An aeroplane's rudder cannot do
        // that at all, and it is why a helicopter can look one way while travelling another.
        float commandedYaw = this.input.yaw() * handling.yawRate() * bite / damping;

        this.pitchVelocity += (commandedPitch - this.pitchVelocity) * lag;
        this.rollVelocity += (commandedRoll - this.rollVelocity) * lag;
        this.yawVelocity += (commandedYaw - this.yawVelocity) * lag;

        // A steerable tail wheel, which is the same thing a nosewheel is and is here for the same
        // reason: rolling along the ground, the pedals turn a wheel rather than a rotor.
        float nosewheel = 0.0F;

        if (rolling) {
            float grip = (float) Mth.clamp(1.0 - speed / Math.max(gear.steerFade(), 1.0E-3F), 0.0, 1.0);

            nosewheel = this.input.yaw() * gear.steerRate() * grip;
        }

        Vec3 up = this.getLiftVector();
        Vec3 right = Attitude.right(this.attitude);
        Vec3 nose = this.getNoseVector();

        double disc = this.rotorLift(rotor, speed);
        Vec3 forces = new Vec3(0.0, -GRAVITY, 0.0).add(up.scale(disc));

        // Air coming from below or above meets the rotor and everything slung under it broadside;
        // air coming from ahead meets a fuselage. The difference is most of an order of magnitude,
        // and it is the reason a helicopter's rate of climb is quoted in feet per minute while the
        // speed beside it is in knots — the fuselage's own drag does not begin to explain either
        // figure. It is also what the machine falls against with the collective down, and it goes
        // with the rotor, so one whose rotor has stopped falls like the lump of metal it now is.
        double sink = motion.y;

        forces = forces.add(new Vec3(0.0,
                -rotor.discDrag() * sink * Math.abs(sink) * turning * density, 0.0));

        // The fuselage is hanging off a rotor, and a rotor turning one way pushes what it is bolted
        // to the other. The tail rotor is the answer to it, and since this follows the collective,
        // the nose walks round whenever the machine is asked to climb — which is most of what flying
        // one by hand consists of.
        float torqueYaw = (float) (rotor.torque() * collective * turning);

        double lift = 0.0;

        if (speed > 1.0E-4) {
            Vec3 flow = motion.scale(1.0 / speed);

            this.angleOfAttack = (float) Math.toDegrees(Math.asin(Mth.clamp(-flow.dot(up), -1.0, 1.0)));

            // The stub wings, which on a gunship are real wings and carry a useful share of the
            // machine at speed — they are also where the weapons hang, which is what they are for.
            // Nothing here stalls: past the critical angle the wing simply stops helping, and the
            // rotor was doing the work anyway.
            double liftCoefficient = wing.liftCoefficient(this.angleOfAttack);
            Vec3 liftAxis = up.subtract(flow.scale(up.dot(flow)));

            lift = wing.lift() * liftCoefficient * speed * speed * density;

            if (liftAxis.lengthSqr() > 1.0E-8) {
                forces = forces.add(liftAxis.normalize().scale(lift));
            }

            // Drag, and a great deal of it: a helicopter is a shape nobody chose for going fast, and
            // what settles its top speed is the disc running out of tilt against this.
            //
            // Which way round it is facing matters here and does not on an aeroplane, because a
            // helicopter can be flown in any direction it likes and an aeroplane cannot. A fuselage
            // is a shape for going forwards; turned round it is a barn door, and without saying so
            // the machine would reach its forward top speed flying backwards.
            double bluff = Mth.lerp((1.0 - Mth.clamp(flow.dot(nose), -1.0, 1.0)) * 0.5,
                    1.0, Math.max(rotor.bluffDrag(), 1.0F));
            double drag = wing.drag() * bluff * (this.input.brake() ? 4.0 : 1.0)
                    + wing.inducedDrag() * liftCoefficient * liftCoefficient;

            forces = forces.add(flow.scale(-drag * speed * speed * density));
            this.checkStructuralLoad(motion);

            if (!rolling) {
                // The fin, weakened by the same dynamic pressure everything aerodynamic here is,
                // so a hovering machine is not dragged round onto a flight path it barely has.
                double aerodynamic = Math.min(pressure, AUTHORITY_CEILING);

                weathervaneYaw = (float) (flow.dot(right) * handling.weathervane() * aerodynamic / damping);

                // And the fuselage's dislike of being flown sideways, which fades out with it. An
                // aeroplane's never does, because an aeroplane is never asked to fly sideways; a
                // helicopter is asked to constantly, and refusing would take away half of what one is
                // worth having.
                motion = motion.subtract(right.scale(motion.dot(right) * wing.lateralDrag()
                        * Mth.clamp(pressure, 0.0, 1.0)));
            }

            // A hover is not a slide. Nothing aerodynamic bites at a walking pace, so without this a
            // machine nudged sideways would keep going until it hit something. It lets go as the
            // helicopter picks up speed, or it would be a parking brake rather than a hover.
            //
            // It has to be gentler than it looks, and the reason is a trap worth writing down. Since
            // it grows with speed and then fades away again, it peaks partway up the band — at a
            // quarter of hover_drag times the band — and that peak is a wall the machine has to be
            // pushed over before it can go anywhere at all. Set generously it is not station-keeping
            // but a threshold: gentle forward stick does nothing whatever, and then somewhere past
            // it the helicopter leaps off. Keep the peak below the smallest tilt anybody would use
            // deliberately and the wall is under the floor, where it belongs.
            double band = Math.max(rotor.translationalSpeed() * HOVER_BAND, 1.0E-4);
            double slow = Mth.clamp(1.0 - speed / band, 0.0, 1.0);

            forces = forces.add(motion.scale(-rotor.hoverDrag() * turning * slow));
        } else {
            this.angleOfAttack = 0.0F;
        }

        // How much of the machine the wheels are still carrying. Read a tick late by the cyclic gate
        // above, which is a tick nobody can see and saves working the rotor out twice.
        this.weightOnWheels = (float) Mth.clamp(1.0 - (disc + lift) / GRAVITY, 0.0, 1.0);

        this.applyBodyRotation(this.rollVelocity, this.pitchVelocity,
                this.yawVelocity + weathervaneYaw + nosewheel + torqueYaw);
        this.deltaRotation = Mth.wrapDegrees(this.getYRot() - previousYRot);
        motion = motion.add(forces);

        if (rolling) {
            motion = this.groundTick(motion);
        }

        if (wing.maxSpeed() > 0.0F && motion.length() > wing.maxSpeed()) {
            motion = motion.normalize().scale(wing.maxSpeed());
        }

        this.setDeltaMovement(motion);
    }

    /**
     * What the rotor is pulling, in blocks per tick squared. The whole of a helicopter's lift.
     *
     * <p>Worked out here rather than inline so that the instruments can ask for the same figure the
     * flight model is using. Everything it needs is either synced or worked out identically on every
     * side, so the answer does not depend on running the physics.
     *
     * <p>The translational term in it is a rotor in a hover beating air it has already thrown down
     * and used: move the machine along and every blade reaches air nothing has touched, and the same
     * collective is worth more. It is why one too heavy to lift off vertically can often still fly
     * away along the ground, and why the first seconds of a departure feel like it finding its feet.
     */
    private double rotorLift(AircraftDefinition.Rotor rotor, double speed) {
        double translational = 1.0 + rotor.translationalLift()
                * Mth.clamp(speed / Math.max(rotor.translationalSpeed(), 1.0E-4F), 0.0, 1.0);

        return rotor.lift() * Mth.clamp(this.thrustLevel, 0.0F, 1.0F)
                * this.rotorSpeed * this.rotorSpeed
                * this.airDensity() * translational * this.groundEffect();
    }

    /**
     * How far past the angle the machine is willing to hold an attitude at it has got, in degrees.
     * Zero while it is inside that angle, which is where a helicopter spends its life; what comes
     * back is signed, so subtracting it from a commanded rate walks the disc the short way home.
     */
    private static float overTilt(float angle, float limit) {
        return angle - Mth.clamp(angle, -limit, limit);
    }

    /**
     * How much of a nose-up command the elevator can actually deliver while the wheels are down.
     *
     * <p>Nothing at all until shortly before the rotation speed, then fading in over the last of the
     * run so the nose becomes light rather than snapping up the instant a threshold is passed. The
     * result is the takeoff a pilot expects: accelerate, feel the aircraft go light, ease the nose
     * up, and fly off — instead of standing the aeroplane on its tail at walking pace.
     */
    private float rotationAuthority(AircraftDefinition.Wing wing, double speed) {
        float rotate = wing.effectiveRotateSpeed();

        if (rotate <= 0.0F) {
            return 1.0F;
        }

        double band = rotate * ROTATION_FADE;

        return (float) Mth.clamp((speed - (rotate - band)) / band, 0.0, 1.0);
    }

    /**
     * How big a step the aircraft rolls over instead of running into.
     *
     * <p>Only with the undercarriage down and the wheels on the ground: in the air an aeroplane does
     * not step over anything, and a belly landing has no wheels to do it with. On the ground it is
     * the difference between an undercarriage and a wall — the collision box is a single square box
     * six blocks across, so without this the lip of one block anywhere under it is a head-on impact,
     * and since the aircraft must pass its own crash speed to fly at all, that impact was fatal on
     * every takeoff from ground that was not perfectly flat.
     */
    @Override
    public float maxUpStep() {
        return this.onGround() && this.gearProgress > 0.5F
                ? this.getStats().landingGear().climbHeight()
                : 0.0F;
    }

    /**
     * Turns the aircraft about its own axes. Because the attitude is a rotation rather than a pair
     * of angles, there is nothing here to clamp and no direction the aircraft cannot point: it will
     * fly a loop, roll inverted, and go over the vertical without anything folding back on itself.
     *
     * @param roll rate about the nose, positive to the right
     * @param pitch rate about the wings, positive nose up
     * @param yaw rate about the aircraft's vertical, positive nose right
     */
    private void applyBodyRotation(float roll, float pitch, float yaw) {
        this.setAttitude(Attitude.rotate(new Quaternionf(this.attitude), roll, pitch, yaw));
    }

    /**
     * Holds the aircraft at the angle where the wing pulls hardest, instead of letting the pilot
     * haul straight past it.
     *
     * <p>The elevator can swing the nose several times faster than the wing can follow the flight
     * path round. Left alone, that means a pilot who pulls hard is at the stalling angle in a third
     * of a second and then falling rather than turning, which is a poor reward for asking for a
     * hard turn. So the stick is faded out as the angle of attack approaches the limit, and only in
     * the direction that would make it worse: unloading is always allowed, and so is pulling the
     * other way.
     *
     * <p>The result settles just under the limit, which is exactly where the tightest turn is.
     */
    private float limitToWing(float commanded, double lifting) {
        AircraftDefinition.Handling handling = this.getStats().handling();
        float limit = this.getStats().wing().stallAngle() * handling.alphaLimit();

        // Not on the runway. Rolling along the ground the angle of attack is simply the angle the
        // aircraft is sitting at, and the rotation needed to leave the ground is most of the stalling
        // angle: a limiter that reads that as an impending stall fades the stick out exactly when the
        // pilot is asking for the one thing the aircraft has to do, and the takeoff turns into a long
        // wait for the wing to catch up with a nose it was never allowed to raise.
        if (this.onGround()) {
            return commanded;
        }

        if (limit <= 0.0F || handling.alphaLimit() >= 1.0F || commanded * this.angleOfAttack <= 0.0F) {
            return commanded;
        }

        float bite = limit * ALPHA_LIMITER_BITE;
        float over = (Math.abs(this.angleOfAttack) - bite) / Math.max(limit - bite, 1.0E-3F);
        float limited = commanded * Mth.clamp(1.0F - over, 0.0F, 1.0F);

        // And the limiter stands aside for the lift system, in proportion to how much of the weight
        // it has taken. An aeroplane going straight up meets its own airflow from directly above, so
        // the wing reads ninety degrees of angle of attack and the limiter -- which exists to stop a
        // pilot stalling that wing -- refuses to let the nose come down at all. Which is the one
        // control input a vertical climb is entirely about: nothing about a hover is being flown by
        // the wing, and there is nothing there to protect.
        return (float) Mth.lerp(lifting, limited, commanded);
    }

    /**
     * Bends the airframe if the pilot pulls harder than it is stressed for. Applied on whichever
     * side is watching: the piloting client works it out from the aerodynamics it is running, and
     * the server works it out again from the attitude and the distance covered, so the damage does
     * not depend on being told about it.
     */
    private void checkStructuralLoad(Vec3 velocity) {
        float limit = this.getStats().airframe().maxG();

        if (limit <= 0.0F || this.level().isClientSide) {
            return;
        }

        float load = this.getLoadFactor(velocity);

        // Pulled hard enough for long enough, the wings come off in the air rather than waiting for
        // something to shoot them off.
        if (load > limit && this.wound((load - limit) * OVER_G_DAMAGE)) {
            this.crash();
        }
    }

    /**
     * Wheels on the ground: rolling friction along the aircraft, scrub across it, brakes, and an
     * attitude the undercarriage allows.
     *
     * <p>A tyre rolls one way and scrubs the other, and that difference is the whole of why an
     * aircraft tracks down a runway rather than sliding about on it. Damping the ground speed evenly
     * in both directions, which is what this used to do, gives an aeroplane on wheels the manners of
     * one on ice.
     *
     * <p>Both figures are scaled by how much weight is left on the tyres. Friction acts through the
     * load carrying it, so as the wing takes the aircraft's weight the wheels stop gripping, and a
     * takeoff roll goes light towards the end instead of holding on until it steps into the air.
     *
     * <p>What is deliberately <em>not</em> here is a pivot about the main wheels. A real aeroplane
     * rotates about them and its centre rises as the nose comes up; an entity's box is axis-aligned
     * and does not tilt, so there is nothing that rotation would push into the runway and nothing to
     * compensate for. Adding the rise anyway is worse than leaving it out — it is vertical speed the
     * wing did not make, so the aircraft is lifted off the ground by the act of rotating and then
     * dropped back on it by gravity, which reads as a bounce rather than a rotation. The nose coming
     * up over a second or so, and the wing taking the weight as it does, is the whole of the effect
     * worth having, and both of those are real here.
     */
    private Vec3 groundTick(Vec3 motion) {
        // The wheels decide the attitude, not the pilot: wings level, and the nose somewhere between
        // sitting on the nosewheel and rotated as far as the tail will allow.
        float rotation = Mth.clamp(this.getXRot(), -GROUND_PITCH_LIMIT, 0.0F);

        if (this.input.pitch() == 0.0F) {
            rotation = approach(rotation, 0.0F, 2.0F);
        }

        this.setAttitude(new Quaternionf(this.attitude)
                .slerp(Attitude.of(this.getYRot(), rotation), GROUND_LEVELLING));

        AircraftDefinition.Undercarriage gear = this.getStats().landingGear();

        double along = this.input.brake() ? gear.brakeFriction() : gear.rollingFriction();
        double across = gear.lateralFriction();

        // Only through the weight the tyres are still carrying. Nothing on the wheels, nothing to
        // rub: a wing holding the whole aircraft up leaves the ground no say in where it goes.
        along = Mth.lerp(this.weightOnWheels, 1.0, along);
        across = Mth.lerp(this.weightOnWheels, 1.0, across);

        Vec3 heading = this.getNoseVector();
        Vec3 forwards = new Vec3(heading.x, 0.0, heading.z);
        Vec3 ground = new Vec3(motion.x, 0.0, motion.z);

        if (forwards.lengthSqr() > 1.0E-8) {
            forwards = forwards.normalize();

            Vec3 sideways = new Vec3(-forwards.z, 0.0, forwards.x);

            ground = forwards.scale(ground.dot(forwards) * along)
                    .add(sideways.scale(ground.dot(sideways) * across));
        } else {
            // Pointing straight up or straight down, which is not a thing wheels have an opinion
            // about. Fall back to slowing it evenly rather than dividing by nothing.
            ground = ground.scale(along);
        }

        return new Vec3(ground.x, Math.max(motion.y, 0.0), ground.z);
    }

    /**
     * How much lift the wing is making compared with what it would make at the same speed and angle
     * in free air.
     *
     * <p>Close to the ground the wing works against its own reflection and makes more lift for the
     * same angle. It is what an aircraft rides off the runway on, and what makes it float down the
     * last few feet of a landing instead of arriving. Fully gone by a wingspan's height.
     */
    private double groundEffect() {
        AircraftDefinition.Wing wing = this.getStats().wing();

        if (wing.groundEffect() <= 0.0F) {
            return 1.0;
        }

        double reach = Math.max(wing.span(), 1.0);
        double height = this.heightAboveGround();

        if (height >= reach) {
            return 1.0;
        }

        return 1.0 + wing.groundEffect() * Mth.clamp(1.0 - height / reach, 0.0, 1.0);
    }

    /**
     * Height above whatever is underneath, in blocks.
     *
     * <p>Read off the heightmap rather than traced: this is wanted every tick, it only has to be
     * right to within a block for the ground effect to look after itself, and tracing a line down
     * from an aircraft over unloaded ground would generate the terrain to trace it against.
     *
     * <p>The chunk is asked for without being allowed to load or generate — that is what the
     * {@code false} means, and it also means a chunk short of fully generated comes back as nothing
     * — and the height is read off the chunk itself. Asking the level instead is the trap:
     * {@code Level#getHeight} fetches the chunk with loading allowed, so it quietly generates
     * whatever is not there yet, on the tick thread, stalling the whole server while it happens.
     * {@code hasChunkAt} does not guard against it either, since it answers for a chunk that merely
     * exists at some earlier stage. This aircraft is always ticking by design, so one flying itself
     * over ground nobody has visited would carve out a corridor of new terrain purely to ask how
     * high it was above it. Out there the answer does not matter anyway: no chunk, no ground effect,
     * which is the truth at altitude.
     */
    private double heightAboveGround() {
        if (this.onGround()) {
            return 0.0;
        }

        BlockPos at = this.blockPosition();
        ChunkAccess chunk = this.level().getChunkSource().getChunk(at.getX() >> 4, at.getZ() >> 4, false);

        if (chunk == null) {
            return Double.MAX_VALUE;
        }

        return Math.max(0.0,
                this.getY() - chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()));
    }

    /**
     * How thick the air is here, as a multiple of what the files' figures assume.
     *
     * <p>Thrust and lift are both worked against it, so an aircraft climbing runs out of engine and
     * wing together and settles at a ceiling rather than climbing for ever. Floored, because air that
     * reaches nothing turns a ceiling into a trapdoor.
     */
    private double airDensity() {
        double sea = this.getStats().engine().seaLevelDensity();
        double thinning = Math.pow(2.0, -(this.getY() - DENSITY_DATUM) / DENSITY_SCALE);
        // The floor cannot be above the ceiling, however odd a figure the file names for its air.
        double floor = Math.min(THINNEST_AIR, sea);

        return Mth.clamp(sea * thinning, floor, sea);
    }

    /**
     * How fast the aircraft is really going, in blocks a tick, on whichever side is asking.
     *
     * <p>Read this rather than the delta movement: only one machine runs the flight model, and every
     * other copy of the aircraft holds a delta movement that means nothing. The side that is flying
     * measures it; every other side is told, which is the same figure a tick later rather than a
     * guess. Anything that needs to know how fast an aeroplane is going — instruments, the speed a
     * weapon leaves with, the prediction that draws it — should come here.
     */
    public Vec3 getVelocity() {
        // On the server, an aircraft with a pilot at the stick is not moved by anything here: its
        // position arrives in packets, and those are applied between ticks, after the old position
        // has been stamped. Measured from here it has therefore not moved at all this tick, and the
        // difference is flatly zero however fast it is really going — which quietly robbed every
        // weapon fired from it of the speed it should have left with. The pilot's own figure is the
        // only truthful answer on that side.
        if (this.isControlledByLocalInstance()) {
            return this.travelled();
        }

        if (!this.level().isClientSide) {
            return this.pilotVelocity;
        }

        // A client that is not flying it is told, for the same reason the server is: what it can see
        // of the movement is a drawn approximation of it, smoothed and predicted, and the instruments
        // should read the aircraft rather than the drawing of it.
        Vector3f reported = this.entityData.get(DATA_VELOCITY);

        return new Vec3(reported.x(), reported.y(), reported.z());
    }

    /**
     * How far the aircraft moved over the last tick as far as <em>this</em> side could see, which on
     * the server is nothing at all while somebody else is flying it.
     *
     * <p>Almost nothing wants this: {@link #getVelocity()} is the honest answer and is what to reach
     * for. This is here for the one thing that must not be told the truth — the delta movement the
     * server keeps for a piloted aircraft, which is broadcast to the pilot and would fight their own
     * flight model if it held anything real.
     */
    private Vec3 travelled() {
        return this.position().subtract(this.xOld, this.yOld, this.zOld);
    }

    /**
     * Told by the pilot's client how fast it is really going, once a tick.
     *
     * <p>Clamped, because it arrives from a client and is used to throw things: without a limit it
     * would be a way to fire a cannon round at any speed the sender liked.
     */
    public void setPilotVelocity(Vec3 velocity) {
        double speed = velocity.length();

        this.pilotVelocity = speed > MAX_PILOT_SPEED ? velocity.scale(MAX_PILOT_SPEED / speed) : velocity;
    }

    /**
     * How many times its own weight the aircraft is currently pulling. One is level flight, zero is
     * weightless, and beyond what the airframe is stressed for it starts to bend.
     */
    public float getLoadFactor(Vec3 velocity) {
        double speed = velocity.length();

        AircraftDefinition.Rotor rotor = this.getStats().rotor().orElse(null);

        // A helicopter has no wing to read this off, and does not need one: what it is pulling is
        // whatever the rotor is pulling, which is the same figure hovering as it is in a turn.
        // Honest on every side too, unlike the wing figure below — nothing here depends on running
        // the flight model, so the server and every onlooker get the same answer as the pilot.
        if (rotor != null) {
            return (float) (this.rotorLift(rotor, speed) / GRAVITY);
        }

        if (speed < 1.0E-4) {
            return 0.0F;
        }

        AircraftDefinition.Wing wing = this.getStats().wing();
        Vec3 flow = velocity.scale(1.0 / speed);
        float angle = (float) Math.toDegrees(Math.asin(Mth.clamp(-flow.dot(this.getLiftVector()), -1.0, 1.0)));
        double coefficient = wing.liftCoefficient(angle) * (1.0 + this.flapsProgress * this.getFlapsLiftBonus());

        return (float) Math.abs(wing.lift() * coefficient * speed * speed / GRAVITY);
    }

    /** The direction lift acts in: up through the canopy, wherever that happens to be pointing. */
    public Vec3 getLiftVector() {
        return Attitude.up(this.attitude);
    }

    /** Where the nose points. */
    public Vec3 getNoseVector() {
        return Attitude.nose(this.attitude);
    }

    private static float approach(float current, float target, float step) {
        return current > target ? Math.max(current - step, target) : Math.min(current + step, target);
    }

    /**
     * One tick of the nozzle travelling. Nothing is animated from this beyond the nozzle itself; what
     * the doors do is the animation file's business.
     */
    private void tickVtol() {
        this.vtolProgressO = this.vtolProgress;

        AircraftDefinition.Vtol vtol = this.getStats().vtol().orElse(null);

        if (vtol == null) {
            this.vtolProgress = 0.0F;

            return;
        }

        this.vtolProgress = approach(this.vtolProgress, this.isVtolSelected() ? 1.0F : 0.0F,
                1.0F / Math.max(vtol.cycleTicks(), 1));
    }

    /**
     * One tick of the rotor turning.
     *
     * <p>Run on every side, and deliberately: what it depends on is who is in the seat, which every
     * side is told about anyway, so there is nothing to send. That also means the rotor keeps turning
     * on a helicopter nobody is simulating — a client watching one across the valley draws it exactly
     * as the pilot's client does, without a packet passing between them.
     *
     * <p>Climbing in is the starter and climbing out is the shutdown. A helicopter is not an aircraft
     * anybody sprints to and takes off in: the rotor has to come up to speed first, and the wait for
     * it is most of what makes flying one feel like flying one rather than like driving a hovering
     * brick. Getting out leaves it running down, so a machine left at a pad settles rather than
     * stopping dead.
     */
    private void tickRotor() {
        this.rotorSpeedO = this.rotorSpeed;
        this.rotorAngleO = this.rotorAngle;
        this.tailAngleO = this.tailAngle;

        AircraftDefinition.Rotor rotor = this.getStats().rotor().orElse(null);

        if (rotor == null) {
            this.rotorSpeed = 0.0F;
            this.rotorAngle = 0.0F;
            this.rotorAngleO = 0.0F;
            this.tailAngle = 0.0F;
            this.tailAngleO = 0.0F;

            return;
        }

        boolean running = this.getControllingPassenger() != null;

        this.rotorSpeed = approach(this.rotorSpeed, running ? 1.0F : 0.0F,
                1.0F / Math.max(rotor.spoolTicks(), 1));
        this.rotorAngle += this.rotorSpeed * rotor.degreesPerTick();
        this.tailAngle += this.rotorSpeed * rotor.tailDegreesPerTick();

        // Kept inside a turn so the floats never grow large. The previous angle comes back with each,
        // because a lerp between 359 and 1 across the seam draws the rotor spinning backwards for a
        // frame, and a rotor that stutters once a second is worse than one that does not turn at all.
        while (this.rotorAngle >= 360.0F) {
            this.rotorAngle -= 360.0F;
            this.rotorAngleO -= 360.0F;
        }

        while (this.tailAngle >= 360.0F) {
            this.tailAngle -= 360.0F;
            this.tailAngleO -= 360.0F;
        }
    }

    private void tickGear() {
        this.gearProgressO = this.gearProgress;
        this.gearProgress = approach(this.gearProgress, this.isGearDown() ? 1.0F : 0.0F,
                1.0F / Math.max(this.getGearCycleTicks(), 1));
        this.flapsProgressO = this.flapsProgress;
        this.flapsProgress = approach(this.flapsProgress, this.isFlapsDown() ? 1.0F : 0.0F,
                1.0F / Math.max(this.getFlapsCycleTicks(), 1));
    }

    /**
     * Puts the aircraft where a client that is not flying it should draw it.
     *
     * <p>Vanilla's own vehicle interpolation is deliberately not used here — see
     * {@link AircraftInterpolation} for what it does to an aeroplane at speed, which is to draw it
     * most of a chunk behind where it really is. The fallback below is only reached before the
     * prediction has a position to work from, or once it has given up waiting for one.
     */
    private void tickLerp() {
        if (this.isControlledByLocalInstance()) {
            this.lerpSteps = 0;
            this.interpolation.release();
            this.syncPacketPositionCodec(this.getX(), this.getY(), this.getZ());

            return;
        }

        if (!this.level().isClientSide) {
            // The server is not drawing anything, and for a piloted aircraft its position arrives
            // whole in the pilot's movement packets. Predicting on top of that would only fight it.
            return;
        }

        AircraftDefinition.Sync sync = this.getStats().sync();

        this.interpolation.tune(sync.correctionTicks(), sync.snapDistance(), sync.maxPredictionTicks());

        // What the side flying it says it is doing, rather than what the last two position updates
        // seem to say. Handed over before the prediction moves, so this tick already uses it.
        Vector3f velocity = this.entityData.get(DATA_VELOCITY);

        this.interpolation.receiveVelocity(velocity.x(), velocity.y(), velocity.z());

        if (this.interpolation.advance()) {
            this.lerpSteps = 0;
            this.setPos(this.interpolation.renderX(), this.interpolation.renderY(), this.interpolation.renderZ());

            return;
        }

        if (this.lerpSteps > 0) {
            this.lerpPositionAndRotationStep(this.lerpSteps, this.lerpX, this.lerpY, this.lerpZ, this.lerpYRot, this.lerpXRot);
            this.lerpSteps--;
        }
    }

    /**
     * The server's idea of how fast this aircraft is going, which the side flying it must ignore.
     *
     * <p>A piloted aircraft is flown by its pilot's client, and the server deliberately keeps a delta
     * movement of zero for it — see the note in {@link #tick()}. That zero is normally never sent,
     * because the game only broadcasts a velocity that has changed and this one never does. There is
     * one exception, and it is a bad one: an entity that has been hurt has its velocity
     * <em>force</em>-broadcast at the end of that tick, so that everyone watching sees the knockback.
     * An aeroplane has no knockback, so what went out was the zero — to every client tracking it, the
     * pilot's included, whose own flight model was then overwritten with a dead stop.
     *
     * <p>The effect was an aeroplane at three hundred knots stopping in mid-air the instant anything
     * touched it: one cannon round, one splinter of a blast, and the speed read zero. Nothing about
     * how fast it is going is worth hearing from the server; the side at the controls is the side
     * that knows.
     */
    @Override
    public void lerpMotion(double x, double y, double z) {
        if (this.isControlledByLocalInstance()) {
            return;
        }

        super.lerpMotion(x, y, z);
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        this.lerpX = x;
        this.lerpY = y;
        this.lerpZ = z;
        this.lerpYRot = yRot;
        this.lerpXRot = xRot;
        this.lerpSteps = 10;

        // The prediction is what actually draws this aircraft; the fields above are only the
        // fallback for before it is running. Note it is told where the aircraft is drawn as well as
        // where it belongs, so a first correction can start from the former rather than jumping.
        if (this.level().isClientSide && !this.isControlledByLocalInstance()) {
            this.interpolation.receivePosition(x, y, z, this.getX(), this.getY(), this.getZ());

            if (this.interpolation.consumeSnap()) {
                this.lerpSteps = 0;
                this.setPos(this.interpolation.renderX(), this.interpolation.renderY(),
                        this.interpolation.renderZ());
            }
        }
    }

    @Override
    public double lerpTargetX() {
        return this.interpolation.isSeeded() ? this.interpolation.targetX()
                : (this.lerpSteps > 0 ? this.lerpX : this.getX());
    }

    @Override
    public double lerpTargetY() {
        return this.interpolation.isSeeded() ? this.interpolation.targetY()
                : (this.lerpSteps > 0 ? this.lerpY : this.getY());
    }

    @Override
    public double lerpTargetZ() {
        return this.interpolation.isSeeded() ? this.interpolation.targetZ()
                : (this.lerpSteps > 0 ? this.lerpZ : this.getZ());
    }

    @Override
    public float lerpTargetXRot() {
        return this.lerpSteps > 0 ? (float) this.lerpXRot : this.getXRot();
    }

    @Override
    public float lerpTargetYRot() {
        return this.lerpSteps > 0 ? (float) this.lerpYRot : this.getYRot();
    }

    // ------------------------------------------------------------------
    // Damage
    // ------------------------------------------------------------------

    /**
     * Moves the aircraft's boxes to wherever its attitude has put them.
     *
     * <p>Each one is an upright box around a rotated one, because upright boxes are all the game can
     * collide with. The size therefore changes as the aircraft rolls: a wing is a thin slab flying
     * level and a tall one on its side, which is where the wing actually is.
     *
     * <p><b>How many boxes there are is settled once and never changes.</b> The level is told an
     * entity's boxes when it joins and has no way of being told about a different set afterwards, so
     * building a fresh set here would leave the level holding the old ones — frozen where they last
     * were, still solid, still shootable — while the new ones went unnoticed by everything. That was
     * survivable while the plain hitbox was a target too. Now that the boxes are the only way to hit
     * an aircraft, it would quietly make one unhittable, so the count is fixed for its lifetime.
     *
     * <p>A {@code /reload} that changes the shape therefore moves the boxes an aircraft already has
     * and takes full effect on the next one placed. A box the file no longer describes is folded
     * away inside the airframe rather than left standing in the air.
     */
    private void tickParts() {
        List<VehicleShape.Box> shape = this.getShape().boxes();

        List<AircraftDefinition.Hardpoint> hardpoints = this.getStats().hardpoints();

        for (int i = 0; i < this.parts.length; i++) {
            VehiclePart part = this.parts[i];

            if (part.isPylon()) {
                this.placePylon(part, hardpoints);

                continue;
            }

            if (i >= shape.size()) {
                part.fold(this.position());

                continue;
            }

            part.place(this.hitbox(shape.get(i)));
        }

        this.notePlacement();
        this.carryStanders();
    }

    /**
     * Puts a pylon's box where its hardpoint is, at a size a player can comfortably reach for.
     *
     * <p>Square rather than shaped to whatever is hanging there. A pylon is a place on the aeroplane
     * rather than an object, and it has to stay reachable when it is bare, which is exactly when
     * somebody wants to hang something on it.
     */
    private void placePylon(VehiclePart part, List<AircraftDefinition.Hardpoint> hardpoints) {
        int slot = part.getPylon();

        if (slot >= hardpoints.size()) {
            // The file no longer lists this one. It cannot be got rid of, so it is folded away.
            part.fold(this.position());

            return;
        }

        Vec3 where = hardpoints.get(slot).pos();
        Vec3 centre = this.position().add(Attitude.toWorld(this.attitude, where));
        part.place(centre, pylonBox(where, hardpoints, slot));
    }

    /**
     * How big to make a pylon's box: comfortably reachable, but never so big that it reaches past
     * the pylon next door.
     *
     * <p>A wing with five stations a metre apart would otherwise have five overlapping boxes across
     * it, and a click meant for one of them could land on any of the three around it. Which pylon a
     * player is pointing at is the whole meaning of the click, so where the stations are close
     * together the boxes shrink to match and each one stands over its own store.
     */
    private static double pylonBox(Vec3 where, List<AircraftDefinition.Hardpoint> hardpoints, int slot) {
        double room = PYLON_BOX;

        for (int i = 0; i < hardpoints.size(); i++) {
            if (i != slot) {
                room = Math.min(room, where.distanceTo(hardpoints.get(i).pos()));
            }
        }

        return Math.max(room, SMALLEST_PYLON_BOX);
    }

    /**
     * The air made visible: vapour off the wingtips when the wing is working hard, and a cone of
     * condensation around the aircraft as it runs up on its own top speed.
     *
     * <p>Both are the same piece of physics. Air pulled round a wing or squeezed ahead of a fast
     * aeroplane drops in pressure, and with it in temperature, until the moisture in it condenses.
     * So the wingtip vapour is tied to how hard the aircraft is pulling rather than to its speed,
     * which is why it appears in a hard turn and vanishes when the pilot unloads.
     *
     * <p>Drawn with the mod's own particle rather than vanilla's cloud, for the same two reasons the
     * weapons are: vanilla throws a particle away at thirty-two blocks, and draws whatever is left
     * in flat black once there is no chunk under it to read a light level from. A hard turn is the
     * most visible thing an aeroplane does and it is worth seeing from further off than that.
     */
    private void spawnFlightEffects() {
        // Nothing a wreck does is flying. Vapour off the wingtips of a burnt-out airframe on its way
        // down would read as the aeroplane still pulling.
        if (this.isWrecked()) {
            return;
        }

        Vec3 velocity = this.getVelocity();
        double speed = velocity.length();

        // Ahead of everything aerodynamic below, because none of that applies to it. A burner is lit
        // on the runway as often as it is in the air, and a plume that waited for the aeroplane to
        // be fast would be missing from exactly the moment the pilot lit it for.
        this.spawnAfterburnerPlume(velocity);

        if (speed < 0.5) {
            return;
        }

        Vec3 position = this.position();
        Vec3 up = this.getLiftVector();
        Vec3 right = Attitude.right(this.attitude);
        Vec3 nose = this.getNoseVector();
        Vec3 drift = velocity.scale(0.5);
        RandomSource random = this.level().random;
        // Worked out here rather than held as a constant: this class is loaded while the registries
        // are still being built, so there is no particle type to ask for yet at that point.
        TintedParticleOption vapour = ModParticles.VAPOUR.get().of(VAPOUR_COLOUR, 1.0F);

        // Wingtips: the harder the wing is pulling, the more of it there is to see.
        float load = this.getLoadFactor(velocity);

        if (load > VORTEX_LOAD) {
            double span = this.getWingSpan();
            int puffs = Math.min((int) ((load - VORTEX_LOAD) * 2.0F) + 1, 4);

            for (int side = -1; side <= 1; side += 2) {
                Vec3 tip = position.add(right.scale(span * side)).add(up.scale(WING_HEIGHT));

                for (int i = 0; i < puffs; i++) {
                    this.level().addParticle(vapour,
                            tip.x + random.nextGaussian() * 0.2,
                            tip.y + random.nextGaussian() * 0.2,
                            tip.z + random.nextGaussian() * 0.2,
                            drift.x, drift.y, drift.z);
                }
            }
        }

        // And the cone, once the aircraft is pushing the air ahead of it faster than it will move.
        double onset = this.getStats().wing().maxSpeed() > 0.0F
                ? this.getStats().wing().maxSpeed() * VAPOUR_SPEED
                : this.topSpeed() * VAPOUR_SPEED;

        if (speed > onset) {
            double thickness = Math.min((speed - onset) / Math.max(onset * 0.15, 1.0E-3), 1.0);
            Vec3 centre = position.add(up.scale(WING_HEIGHT)).add(nose.scale(VAPOUR_AHEAD));

            for (int i = 0; i < 1 + (int) (thickness * 6); i++) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                double radius = VAPOUR_RADIUS * (0.7 + random.nextDouble() * 0.3);
                Vec3 rim = centre.add(right.scale(Math.cos(angle) * radius))
                        .add(up.scale(Math.sin(angle) * radius));

                this.level().addParticle(vapour, rim.x, rim.y, rim.z, drift.x, drift.y, drift.z);
            }
        }
    }

    /**
     * The flame out of the jet pipes, while the burner is lit.
     *
     * <p>Two layers, because a plume is two things. In front is the flame, which opens white at the
     * lip and settles to orange behind it, and around and behind that is the hot air it leaves,
     * which is what gives the plume its length and most of what is seen of it from any distance.
     *
     * <p>Thrown along the aircraft's own nose rather than along its flight path, and the difference
     * shows in every hard turn: what comes out of a pipe goes the way the pipe is pointing, so an
     * aeroplane pulling round trails its plume off to one side rather than down its own tail. What
     * the exhaust is carried along by is the aircraft's velocity, less the speed it is thrown out
     * at — so at low speed the flame stands still behind the aeroplane, and at high speed it is a
     * streak that barely moves relative to it, which is what a plume does.
     *
     * <p>Drawn with the mod's own particles rather than vanilla's flame, for the same reason the
     * wingtip vapour is: this wants to be seen from further away than thirty-two blocks, and it
     * wants to be its own light — a burner beyond the loaded world would otherwise be drawn in flat
     * black, which is the one colour it certainly is not.
     */
    private void spawnAfterburnerPlume(Vec3 velocity) {
        AircraftDefinition.Afterburner burner = this.getStats().engine().afterburner().orElse(null);

        if (burner == null || this.reheat <= LIT) {
            return;
        }

        Vec3 nose = this.getNoseVector();
        Vec3 blown = velocity.subtract(nose.scale(PLUME_SPEED * this.reheat));
        RandomSource random = this.level().random;
        TintedParticleOption flame = ModParticles.BLAST.get().of(PLUME_COLOUR, PLUME_SIZE * this.reheat);
        TintedParticleOption exhaust = ModParticles.MOTOR_SMOKE.get().of(EXHAUST_COLOUR, this.reheat);
        double length = PLUME_LENGTH * this.reheat;
        int puffs = Math.max(1, Math.round(PLUME_PUFFS * this.reheat));

        for (Vec3 nozzle : this.nozzles(burner)) {
            Vec3 lip = this.position().add(Attitude.toWorld(this.attitude, nozzle));

            for (int i = 0; i < puffs; i++) {
                // Strung back down the plume rather than all left at the lip. One tick is a long way
                // at these speeds, and a single puff a tick would read as a dotted line rather than
                // as a column of flame.
                this.puff(flame, lip.subtract(nose.scale(random.nextDouble() * length * 0.5)), blown, random);
                this.puff(exhaust, lip.subtract(nose.scale(random.nextDouble() * length)), blown, random);
            }
        }
    }

    /** One particle of the plume, scattered a little off the axis so the column has some width. */
    private void puff(TintedParticleOption particle, Vec3 at, Vec3 blown, RandomSource random) {
        this.level().addParticle(particle,
                at.x + random.nextGaussian() * PLUME_SCATTER,
                at.y + random.nextGaussian() * PLUME_SCATTER,
                at.z + random.nextGaussian() * PLUME_SCATTER,
                blown.x, blown.y, blown.z);
    }

    /**
     * Where the plume comes out, in the aircraft's own axes.
     *
     * <p>What the file says, and for a file that says nothing, one pipe out of the back of the
     * collision shape. That fallback is right for a single engine buried in the fuselage and wrong
     * for a pair of them set apart, which is why any airframe that cares names its own.
     */
    private List<Vec3> nozzles(AircraftDefinition.Afterburner burner) {
        if (!burner.nozzles().isEmpty()) {
            return burner.nozzles();
        }

        double tail = -this.getBbWidth() / 2.0;

        for (VehicleShape.Box box : this.getShape().boxes()) {
            tail = Math.min(tail, box.offset().z - box.size().z / 2.0);
        }

        return List.of(new Vec3(0.0, WING_HEIGHT, tail));
    }

    /** Half the width of the widest part of the aircraft, taken from its collision shape. */
    private double getWingSpan() {
        double span = this.getBbWidth() / 2.0;

        for (VehicleShape.Box box : this.getShape().boxes()) {
            span = Math.max(span, Math.abs(box.offset().x) + box.size().x / 2.0);
        }

        return span;
    }

    /** Where thrust and drag balance out, for aircraft whose file sets no ceiling of its own. */
    private double topSpeed() {
        AircraftDefinition.Wing wing = this.getStats().wing();

        return wing.drag() > 0.0F ? Math.sqrt(this.getStats().engine().maxThrust() / wing.drag()) : 1.0;
    }

    /**
     * Reports an impact detected by the piloting client. The server only accepts it if the aircraft
     * really is up against something, so a stray packet cannot conjure an explosion out of thin air.
     */
    public void reportCrash() {
        if (!this.level().noCollision(this, this.getBoundingBox().inflate(0.5))) {
            this.crashing = true;
        }
    }

    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        // Let go of the chunk, whether the aircraft was destroyed or merely unloaded. Only let go:
        // this also fires when the chunk under us is demoted, from inside the chunk system's own
        // update loop, and taking a ticket there loads a chunk and re-enters that loop mid-iteration
        // (ConcurrentModificationException in DistanceManager.runAllUpdates). Anything still flying
        // asks again on its next tick.
        this.heldChunks = AircraftChunkLoader.release(this, this.heldChunks);
    }

    /**
     * The end of an aeroplane, under the name the flight model calls it by. What happens is
     * {@link VehicleEntityBase#wreck}'s and is the same whichever way the airframe was finished —
     * shot down, or flown into a hillside.
     */
    protected void crash() {
        this.wreck();
    }

    /**
     * Shuts the aeroplane down the moment it stops being one.
     *
     * <p>Three things, and each of them is something that would otherwise go on happening to a
     * burnt-out airframe. The engine is out, so the note it is heard at is nothing. What was hanging
     * under the wings went up with the aircraft, so the pylons are bare — a charred wreck carrying a
     * spotless missile it will not let anybody take off it is worse than one carrying nothing.
     *
     * <p>And the aircraft is no longer turning. That one matters on the clients rather than here:
     * they draw an aeroplane between attitude updates by carrying on the rate it was last turning
     * at, and a wreck written off in a hard bank would otherwise roll for ever. Snapping the
     * attitude to where it already is publishes a rate of zero without moving anything.
     */
    @Override
    protected void onWrecked() {
        this.setThrottle(0.0F);
        this.thrustLevel = 0.0F;
        this.reheatCommanded = false;
        this.gateHeld = 0;
        this.reheat = 0.0F;
        this.entityData.set(DATA_AFTERBURNER, 0.0F);
        this.input = AircraftInput.NONE;
        // Already answered. Left standing it would call crash() again on every tick of the fall, and
        // each of those is a wreck() that has to work out it has nothing to do.
        this.crashing = false;
        this.weapons.clear();
        this.entityData.set(DATA_WEAPONS, this.weapons.syncTag());
        this.snapAttitude(this.attitude);
    }

    /**
     * One tick of being a wreck, which is one tick of falling.
     *
     * <p>Nothing is flown here. There is no thrust, no lift and no control, and the airframe keeps
     * whatever attitude it was written off at: a wreck that levels its own wings on the way down is
     * an aeroplane, not a wreck. Gravity does the rest, what is left of the airspeed bleeds off on
     * the way, and the ground takes the last of it.
     *
     * <p>Whatever the world stopped last tick stays stopped. {@link #move} zeroes nothing of its
     * own when it is our collision boxes rather than the plain one that ran into something, so a
     * wreck lying against a hillside would otherwise go on accumulating a speed it can never spend —
     * and one lying on flat ground would never settle, because the vertical axis has to be pushed
     * into the floor every tick for {@code onGround} to keep saying so.
     */
    private void wreckTick() {
        Vec3 velocity = this.getDeltaMovement();
        double slide = this.onGround() ? WRECK_FRICTION : WRECK_DRAG;
        double x = this.horizontalCollision ? 0.0 : velocity.x * slide;
        double z = this.horizontalCollision ? 0.0 : velocity.z * slide;
        double y = this.verticalCollision ? 0.0 : velocity.y * WRECK_DRAG;

        this.setDeltaMovement(x, y - GRAVITY, z);
    }

    // ------------------------------------------------------------------
    // Riding
    // ------------------------------------------------------------------

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        // The client is in no position to judge any of this and must not try. It never runs the
        // movement of an aircraft nobody is flying, so onGround() is false over there however firmly
        // the aeroplane is sitting on its wheels, and any answer it works out from that is wrong.
        //
        // Worse than wrong: an answer that does not consume the click sends the whole interaction
        // round again with the other hand, and the game has already sent the first one to the server
        // by then. A pylon loaded by the main hand was being unloaded a moment later by the empty
        // off hand, which looked for all the world like the aircraft refusing to take the weapon at
        // all. So the client says yes to everything and the server decides what actually happened.
        if (this.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }

        // Crouching means the hold: whatever is in the player's hand, and whichever box of the
        // aeroplane the click landed on.
        //
        // It has to come before the pylons and before the stores, because those are what somebody
        // crouching at an aircraft is most likely to be holding — offer a missile to a bare pylon
        // and it goes on the pylon, which is exactly right for a click that was not crouching and
        // exactly wrong for one that was. Crouch and it goes in the hold instead.
        //
        // What this replaces is a crouched click meaning "not now" and falling through to whatever
        // the click would otherwise have done. That is still a thing a click can do; it is now
        // spelt without the crouch.
        if (player.isSecondaryUseActive()) {
            this.openHold(player);

            return InteractionResult.CONSUME;
        }

        // A click whose line passed through a pylon was a click on that pylon, whichever box the
        // game happened to hand it to.
        //
        // It has to be settled here rather than left to the pick, because a pylon's box is usually
        // inside a wing's: the game gives the click to whichever box the line enters first, and from
        // most angles that is the wing. Left alone, reaching for a pylon under a wing climbs into
        // the cockpit instead, which is the opposite of what was meant and hard to argue with once
        // it has happened.
        //
        // A pylon that has nothing to offer -- bare, with an empty hand -- answers PASS, and the
        // click carries on down and means what it usually means.
        VehiclePart pylon = this.pylonInSight(player);

        if (pylon != null) {
            InteractionResult reached = this.interactPylon(player, hand, pylon.getPylon());

            if (reached.consumesAction()) {
                return reached;
            }
        }

        ItemStack held = player.getItemInHand(hand);

        // Anything that can go on a pylon goes on a pylon, ahead of everything else the click might
        // have meant. Someone walking up to a parked aircraft holding a missile means to arm it, not
        // to climb in and not to fold the aeroplane back into their pocket.
        //
        // Whether it can go on is settled before the click is taken, rather than after trying: an
        // aircraft with no pylon free would otherwise swallow the click and do nothing at all, which
        // reads as the game ignoring the player. Offer it a weapon it has no room for and the click
        // falls through and means what it usually means.
        if (this.canBeArmedWith(held)) {
            if (this.weapons.mount(((WeaponItem) held.getItem()).getWeaponId(), WeaponItem.ammoOf(held))) {
                held.consume(1, player);
                this.entityData.set(DATA_WEAPONS, this.weapons.syncTag());
            }

            return InteractionResult.CONSUME;
        }

        if (held.getItem() instanceof WrenchItem) {
            return this.dismantle(player);
        }

        return player.startRiding(this) ? InteractionResult.CONSUME : InteractionResult.PASS;
    }

    /**
     * Takes the aeroplane apart with a wrench: the stores first, one at a time, and only then the
     * aeroplane itself.
     *
     * <p>That order is the whole point of doing it in one place. A loaded aircraft packed away would
     * go back into its item and quietly take everything hanging on it with it, so the pylons have to
     * be bare before the airframe will fold.
     *
     * <p>Which store comes off is usually settled before this is reached: a wrench pointed at a
     * particular pylon is handled as a click on that pylon. This is what answers a wrench pointed at
     * the aeroplane in general, and it works from the last station loaded backwards.
     */
    private InteractionResult dismantle(Player player) {
        // A wreck answers first and answers differently: there are no stores left to strip and no
        // aeroplane left to fold up, only a hulk to clear away and the metal in it. It is also not
        // asked to be parked — a write-off on its way down is a write-off, and there is nothing to
        // be gained by making somebody wait for it to land.
        if (this.isWrecked()) {
            return this.salvage();
        }

        // Ground work, and not while anybody is sitting in it.
        if (!this.getPassengers().isEmpty() || !this.isParked()) {
            return InteractionResult.PASS;
        }

        if (this.weapons.hasRemovable()) {
            ItemStack removed = this.weapons.unmount();
            this.entityData.set(DATA_WEAPONS, this.weapons.syncTag());

            if (!player.addItem(removed)) {
                player.drop(removed, false);
            }

            return InteractionResult.CONSUME;
        }

        // The pylons are bare by the time this is reached; the hold is not, and an airframe folded
        // up with a load still inside it would take the load with it.
        this.spillHold();
        this.destroy(this.getDropItem());

        return InteractionResult.CONSUME;
    }

    /**
     * A click on one particular pylon, which means that pylon and nothing else.
     *
     * <p>Offer it a weapon and it takes it; offer it a wrench and it hands back what it is holding.
     * Neither falls through to climbing aboard: someone who reached for a pylon was not reaching for
     * the cockpit, and treating a full pylon as an invitation to get in would be a poor answer to a
     * deliberate aim.
     *
     * <p>Anything else -- an empty hand above all -- passes straight through to the aeroplane behind.
     * A pylon's box sits inside the wing's, so the whole underside of a wing is pylon as far as a
     * click is concerned; if a bare hand stripped a station, walking up to an armed aircraft and
     * climbing in would scatter its stores across the apron on the way past.
     */
    public InteractionResult interactPylon(Player player, InteractionHand hand, int slot) {
        if (this.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }

        // Arming is ground work, and a pylon does nothing else. It will not let anyone climb aboard
        // and it will not fold the aeroplane away: a pylon is for hanging weapons on, and a click
        // that cannot hang or take one simply does nothing. Anyone who meant to get in has the whole
        // rest of the aeroplane to click on.
        //
        // A wreck has no stations at all. Its pylons are bare and there is nothing to hang a weapon
        // on, so the click falls through to the airframe behind, where a wrench clears the hulk away.
        if (this.isWrecked() || !this.isParked()) {
            return InteractionResult.PASS;
        }

        ItemStack held = player.getItemInHand(hand);

        if (held.getItem() instanceof WeaponItem weapon && this.weapons.canMountAt(slot)) {
            if (this.weapons.mountAt(slot, weapon.getWeaponId(), WeaponItem.ammoOf(held))) {
                held.consume(1, player);
                this.entityData.set(DATA_WEAPONS, this.weapons.syncTag());
            }

            return InteractionResult.CONSUME;
        }

        // Taking one off asks for the tool, the same as taking the aeroplane itself apart does. A
        // second click while still holding a store is somebody trying to load the next one, not
        // somebody undoing the last one.
        if (held.getItem() instanceof WrenchItem && this.weapons.canUnmountAt(slot)) {
            ItemStack removed = this.weapons.unmountAt(slot);
            this.entityData.set(DATA_WEAPONS, this.weapons.syncTag());

            if (!player.addItem(removed)) {
                player.drop(removed, false);
            }

            return InteractionResult.CONSUME;
        }

        return InteractionResult.PASS;
    }

    /**
     * The nearest pylon the player is looking through, or null if their line misses every one.
     *
     * <p>Asked because a pylon's box and a wing's box occupy the same air. The game hands a click to
     * whichever box the line of sight enters first, which under a wing is the wing, so a pylon that
     * is not the nearest thing along the line would never be reached for at all. Running the line
     * against the pylons alone answers the question the player was actually asking.
     *
     * <p>Nearest along the line rather than nearest to the aeroplane: an aircraft carrying stores
     * outboard and inboard on the same wing has both in view at once, and the one in front is the
     * one being pointed at.
     */
    @Nullable
    private VehiclePart pylonInSight(Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 reach = eye.add(player.getViewVector(1.0F).scale(player.entityInteractionRange()));
        VehiclePart nearest = null;
        double closest = Double.MAX_VALUE;

        for (VehiclePart part : this.parts) {
            if (!part.isPylon() || !part.isPickable()) {
                continue;
            }

            Optional<Vec3> hit = part.clip(eye, reach, 0.0);

            if (hit.isEmpty()) {
                continue;
            }

            double distance = eye.distanceToSqr(hit.get());

            if (distance < closest) {
                closest = distance;
                nearest = part;
            }
        }

        return nearest;
    }

    /**
     * Whether this hardpoint is one a player could hang something on, now or later: a pylon rather
     * than a gun bolted into the airframe.
     *
     * <p>Asked by the pylon's own box to decide whether it is worth reaching for at all. A wreck has
     * none: its stations went up with the aeroplane and there is nothing to be done with them, so
     * every box stands aside and lets the click reach the airframe behind — which is where a wrench
     * clears the hulk away. Worked out on both sides from state that reaches both, so the client and
     * the server agree about what any given click was aimed at.
     */
    public boolean isLoadablePylon(int slot) {
        if (this.isWrecked()) {
            return false;
        }

        List<AircraftDefinition.Hardpoint> hardpoints = this.getStats().hardpoints();

        return slot >= 0 && slot < hardpoints.size() && !hardpoints.get(slot).isFixed();
    }

    /**
     * Whether this stack is a weapon the aircraft could hang on a pylon right now: the right sort of
     * item, a pylon free for it, and the aircraft sitting still on its wheels. Arming is ground work.
     *
     * <p>Both sides work this out for themselves, from state that reaches both, so the client and the
     * server agree on what a click meant without having to be told.
     */
    private boolean canBeArmedWith(ItemStack held) {
        return held.getItem() instanceof WeaponItem && this.isParked() && this.weapons.hasFreePylon();
    }

    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction moveFunction) {
        super.positionRider(passenger, moveFunction);

        // Everyone aboard is carried round by the turn, the pilot included: the aircraft is flown
        // from the keys, so the view is free to sit where it likes and may as well face where the
        // aircraft is going.
        passenger.setYRot(passenger.getYRot() + this.deltaRotation);
        passenger.setYHeadRot(passenger.getYHeadRot() + this.deltaRotation);
        this.clampRotation(passenger);
    }

    @Override
    public void onPassengerTurned(Entity passenger) {
        this.clampRotation(passenger);
    }

    /**
     * Sits a passenger facing the way the aircraft is facing.
     *
     * <p>Their body only. Where they are <em>looking</em> used to be clamped here as well, to a
     * hundred and thirty-five degrees either side, on the grounds that a head does not turn further
     * than that. It is true of a head and it is not true of a camera: from outside the aircraft there
     * is no head involved at all, and the one thing an outside view is for is seeing what is behind
     * you — which that limit made impossible. The cockpit view still stops where a neck stops, but it
     * stops there in {@code CockpitView}, which is measuring against the aircraft rather than against
     * a compass and is the only one of the two that can do it correctly.
     */
    protected void clampRotation(Entity passenger) {
        passenger.setYBodyRot(this.getYRot());
    }

    /**
     * An aircraft that has boxes of its own is not solid in its own right: the boxes are.
     *
     * <p>Minecraft gives an entity one upright box with a square footprint, and for a fifteen-metre
     * aeroplane that is a shed — six and a half blocks across whatever the wings are doing, and
     * nowhere near the wing once it banks. The boxes in the aircraft's own file are the real
     * shape, so once there are any, they do the work and the plain box stops pretending to.
     *
     * <p>Nothing is lost by standing down: {@link VehiclePart} passes hits, clicks and pick results
     * straight to the aircraft, so being shot, being climbed into and being stood on all still reach
     * here. An aircraft with no boxes of its own keeps its plain box, because otherwise it would have
     * no way of being touched at all.
     *
     * <p>The plain box does still exist, and still earns its keep: it is what the aircraft's own
     * movement collides against, what decides whether it is on the ground, and what the game uses to
     * decide which chunk the aircraft is in and whether it is worth drawing. Only being an obstacle
     * and a target is handed over.
     */
    @Override
    public boolean canBeCollidedWith() {
        return !this.isRemoved() && !this.hasAirframeBoxes();
    }

    @Override
    public boolean isPickable() {
        return !this.isRemoved() && !this.hasAirframeBoxes();
    }

    /**
     * Whether any of the aircraft's boxes is a piece of airframe rather than a pylon.
     *
     * <p>Pylons alone are not enough to stand the plain box down. An aircraft with hardpoints but no
     * boxes of its own would otherwise have nothing to be clicked or shot but five small boxes hanging
     * under its wings, and no way to be climbed into at all.
     */
    private boolean hasAirframeBoxes() {
        for (VehiclePart part : this.parts) {
            if (!part.isPylon()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Moves the aircraft, stopped by the shape it actually has rather than by the box Minecraft
     * gives it.
     *
     * <p>An entity collides with one upright box, and for an aeroplane that box has to stay small —
     * a fifteen-metre box square to the world catches on everything and makes the aircraft
     * unplaceable. So the aircraft keeps its small box for everything the game does with it, and the
     * movement is measured against the real boxes first: whichever of them would be stopped soonest
     * decides how far the aeroplane gets. A wing that looks like it is about to hit a hillside now
     * hits it.
     *
     * <p>Only blocks are tested this way. Entities are left to the plain box, as they always were,
     * which also keeps the aircraft from colliding with its own boxes.
     */
    @Override
    public void move(MoverType type, Vec3 movement) {
        Vec3 allowed = this.limitToShape(movement);
        super.move(type, allowed);

        // super.move only knows whether the plain box was stopped. If it was our own shape that
        // stopped us, the flags have to say so, or a wing could be folded against a cliff without
        // anything noticing — crash detection reads exactly these.
        if (allowed.x != movement.x || allowed.z != movement.z) {
            this.horizontalCollision = true;
        }

        if (allowed.y != movement.y) {
            this.verticalCollision = true;
        }

        // Deliberately not setOnGround. Being on the ground is a question about the wheels, and the
        // plain box sits on them; a wingtip brushing a hillside in flight would otherwise have the
        // aeroplane decide it had landed, level itself out and lose its speed in mid-air.
    }

    /**
     * How far the aircraft may move before one of its own boxes runs into the world.
     *
     * <p>Each box is asked separately and the answers are combined by taking, on each axis, whatever
     * is nearest to standing still. That is not a perfect sweep of a jointed shape — no box knows
     * that another has already been stopped — but it is exact about never letting a box pass through
     * a wall, which is the part that shows.
     */
    private Vec3 limitToShape(Vec3 movement) {
        if (movement.lengthSqr() == 0.0 || this.level().isClientSide && !this.isControlledByLocalInstance()) {
            return movement;
        }

        List<VehicleShape.Box> shape = this.getShape().boxes();

        if (shape.isEmpty()) {
            return movement;
        }

        Vec3 allowed = movement;
        double underside = this.scrapeLine();

        for (VehicleShape.Box box : shape) {
            // The box as it is really lying, swept against the blocks. A wing banked over occupies a
            // thin plate on a slant; stopped against the upright slab drawn round that plate, an
            // aeroplane rolled into a turn is brought up short by air.
            Vec3 stopped = Hitboxes.throughBlocks(this, this.hitbox(box), movement, underside);

            allowed = new Vec3(
                    nearerToZero(allowed.x, stopped.x),
                    nearerToZero(allowed.y, stopped.y),
                    nearerToZero(allowed.z, stopped.z));
        }

        return allowed;
    }

    /** Whichever of the two allows less movement, keeping the sign the pilot asked for. */
    private static double nearerToZero(double a, double b) {
        return Math.abs(a) <= Math.abs(b) ? a : b;
    }

    /**
     * The height below which ground is something the aeroplane scrapes rather than something it
     * flies into.
     *
     * <p>The undercarriage is the lowest thing an aeroplane is meant to touch the ground with, and
     * it is not the lowest thing an aeroplane <em>has</em>. Rotating for takeoff swings the tail
     * below the wheels and into the runway; a flared landing does the same thing on the way in.
     * Swept against the blocks like anything else, that tail is a wall the aeroplane runs into at
     * flying speed — the takeoff roll stops dead, the speed the impact took away is the whole of it,
     * and {@link #detectCrash} quite correctly concludes that an aeroplane which lost eighty knots
     * in one tick has hit something. It has: it has hit the runway it was rolling along.
     *
     * <p>So while the aircraft is in the configuration where the wheels are what touches the ground
     * — undercarriage out, wings roughly level — nothing that reaches no higher than the wheels and
     * the step they roll over stops the airframe. Above that line everything still does, which is
     * the difference between scraping a runway and flying into the hill at the end of it. In the air
     * with the gear up, or rolled past the point where a wingtip is lower than a wheel, the aircraft
     * hits everything again.
     *
     * <p>It cannot let the aeroplane through the floor. The plain box sits on the wheels and is
     * settled against the world by {@code move} exactly as before, so ground below the wheels still
     * holds the aircraft up and a descent into it is still an arrival at whatever speed it arrived.
     */
    private double scrapeLine() {
        if (this.gearProgress <= 0.5F || this.getLiftVector().y <= UPRIGHT) {
            return Hitboxes.UNDERSIDE_NONE;
        }

        // The same step the undercarriage rolls over rather than the wheels alone: a kerb the wheels
        // climb is not a kerb the airframe should be stopped by. See maxUpStep.
        return this.getBoundingBox().minY + this.getStats().landingGear().climbHeight();
    }

    /**
     * The height below which blocks are the floor the aircraft is over, rather than world it is
     * buried in.
     *
     * <p>The companion to {@link #scrapeLine}, for {@link #insideTerrain} rather than for movement,
     * and the reason the two are not the same method is the aeroplane with its gear up. There is no
     * scraping to be done then — a belly has no wheels to roll on, so the airframe hits everything,
     * and an arrival is an arrival — but the question of what the aeroplane is <em>inside</em> has
     * the same answer either way: the plain box sits on the wheels and is settled against the world
     * by {@code move} in the ordinary way, so anything reaching no higher than the bottom of it is
     * what holds the aircraft up. Overlapping that is what standing on the ground looks like from
     * underneath. It is not the world having closed over an aeroplane, and flying out of it is not
     * the answer to it.
     *
     * <p>Terrain that really did arrive around an aircraft is untouched by this. A hillside that
     * appears where an aeroplane is flying reaches past the wheels and well above them — that is
     * what being inside a hillside is — and every block of it above this line still counts.
     */
    private double floorLine() {
        double scrape = this.scrapeLine();

        // On the wheels and the right way up, the step the undercarriage rolls over is floor too.
        return scrape == Hitboxes.UNDERSIDE_NONE ? this.getBoundingBox().minY : scrape;
    }

    /**
     * An aircraft is worth drawing as far away as it is sent.
     *
     * <p>Minecraft works out how far an entity is worth drawing from how big it is, which comes to
     * a few hundred blocks for an aeroplane and is nowhere near far enough: the server reports
     * aircraft out to their {@code ghost_range}, and something reported and not drawn is just a
     * hole in the sky. This is the outer limit only; where the game's own renderer stops and the
     * ghost pass takes over is decided on the client, in {@code AircraftRenderer.shouldRender}.
     */
    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        VehicleChassis.Hitbox hitbox = this.getStats().hitbox();

        if (!hitbox.hasGhostLimit()) {
            return true;
        }

        double range = hitbox.ghostRange();

        return distance < range * range;
    }

    /**
     * One controller, for the undercarriage. Everything else the aircraft does with itself follows
     * the flight from moment to moment and is posed in code by
     * {@link com.ashvehicles.client.model.AircraftModel#setCustomAnimations}; the gear is a sequence
     * and is played from the aircraft's animation file instead.
     *
     * <p>Both halves of it are worked out in
     * {@link com.ashvehicles.client.model.AircraftAnimations}, which is client code. Nothing here
     * reaches it: a controller is only ever processed while something is being drawn, so a server
     * registers this and then never looks at it again.
     *
     * <p>The transition covers a pilot who changes their mind halfway through a cycle. GeckoLib
     * cannot run an animation backwards, so what it does instead is blend from wherever the legs
     * have got to into the start of the other animation, and a few ticks of that reads as the gear
     * hesitating rather than as it teleporting.
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "gear", AircraftAnimations.TRANSITION_TICKS,
                AircraftAnimations::gearCycle).setAnimationSpeedHandler(AircraftAnimations::gearSpeed));
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.setThrottle(tag.getFloat("Throttle"));

        if (tag.contains("Attitude")) {
            ListTag stored = tag.getList("Attitude", Tag.TAG_FLOAT);
            this.snapAttitude(new Quaternionf(stored.getFloat(0), stored.getFloat(1),
                    stored.getFloat(2), stored.getFloat(3)));
        } else {
            this.snapAttitude(Attitude.of(this.getYRot(), this.getXRot()));
        }
        // An aircraft written to the world before this was a health system has no figure to read,
        // and comes back whole rather than coming back with nothing left.
        this.setHealth(tag.contains("Health") ? tag.getFloat("Health") : this.getMaxHealth());
        this.setCountermeasures(true, tag.contains("Flares")
                ? tag.getInt("Flares") : this.getStats().countermeasures().flares());
        this.setCountermeasures(false, tag.contains("Chaff")
                ? tag.getInt("Chaff") : this.getStats().countermeasures().chaff());
        this.entityData.set(DATA_GEAR_DOWN, !tag.contains("GearDown") || tag.getBoolean("GearDown"));
        this.gearProgress = this.isGearDown() ? 1.0F : 0.0F;
        this.gearProgressO = this.gearProgress;
        this.entityData.set(DATA_VTOL, tag.getBoolean("Vtol"));
        this.vtolProgress = this.isVtolSelected() ? 1.0F : 0.0F;
        this.vtolProgressO = this.vtolProgress;
        this.entityData.set(DATA_FLAPS_DOWN, tag.getBoolean("FlapsDown"));
        this.flapsProgress = this.isFlapsDown() ? 1.0F : 0.0F;
        this.flapsProgressO = this.flapsProgress;
        this.weapons.load(tag.getCompound("Weapons"));
        this.entityData.set(DATA_WEAPONS, this.weapons.syncTag());
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("Throttle", this.getThrottle());

        ListTag stored = new ListTag();
        stored.add(FloatTag.valueOf(this.attitude.x));
        stored.add(FloatTag.valueOf(this.attitude.y));
        stored.add(FloatTag.valueOf(this.attitude.z));
        stored.add(FloatTag.valueOf(this.attitude.w));
        tag.put("Attitude", stored);
        tag.putFloat("Health", this.getHealth());
        tag.putInt("Flares", this.getCountermeasures(true));
        tag.putInt("Chaff", this.getCountermeasures(false));
        tag.putBoolean("GearDown", this.isGearDown());
        tag.putBoolean("FlapsDown", this.isFlapsDown());
        tag.putBoolean("Vtol", this.isVtolSelected());
        tag.put("Weapons", this.weapons.save());
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.animatableCache;
    }
}
