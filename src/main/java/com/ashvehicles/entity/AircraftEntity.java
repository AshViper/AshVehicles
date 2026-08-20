package com.ashvehicles.entity;

import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.aircraft.Attitude;

import org.joml.Matrix3f;
import com.ashvehicles.aircraft.AircraftManager;
import com.ashvehicles.aircraft.AircraftShape;
import com.ashvehicles.client.model.AircraftAnimations;
import com.ashvehicles.particle.TintedParticleOption;
import com.ashvehicles.sensor.Sensors;
import com.ashvehicles.registry.ModParticles;
import com.ashvehicles.item.WeaponItem;
import com.ashvehicles.item.WrenchItem;
import com.ashvehicles.weapon.Dispenser;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
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
public class AircraftEntity extends VehicleEntity implements GeoEntity {
    /** Engine setting in [0, 1]. Synced so other clients can drive engine animations. */
    private static final EntityDataAccessor<Float> DATA_THROTTLE =
            SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.FLOAT);
    /**
     * Which way the aircraft is pointing, as a rotation. Minecraft gives an entity a heading and an
     * elevation, which cannot describe an aeroplane upside down at the top of a loop, so the real
     * attitude is carried here and the vanilla angles are kept in step behind it.
     */
    private static final EntityDataAccessor<Quaternionf> DATA_ATTITUDE =
            SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.QUATERNION);
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
    private static final EntityDataAccessor<Float> DATA_HEALTH =
            SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.FLOAT);

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

    /** Airframe damage per tick for each G pulled beyond what the aircraft is stressed for. */
    private static final float OVER_G_DAMAGE = 4.0F;
    /** Load factor above which the wingtips start trailing vapour. */
    private static final float VORTEX_LOAD = 2.5F;
    /** Blend into the gear cycle, for a pilot who changes their mind partway through one. */
    private static final int GEAR_TRANSITION_TICKS = 4;
    /** Height above the aircraft's origin that the wings sit at, near enough for particles. */
    private static final double WING_HEIGHT = 1.5;
    /** Condensation is water and light, so it is the same pale puff wherever on the wing it forms. */
    private static final int VAPOUR_COLOUR = 0xF2F5F7;
    /** Fraction of top speed at which the cone forms. */
    private static final double VAPOUR_SPEED = 0.88;
    private static final double VAPOUR_RADIUS = 3.0;
    private static final double VAPOUR_AHEAD = 2.0;

    /** Where the angle of attack limiter starts to bite, as a fraction of the limit it holds. */
    private static final float ALPHA_LIMITER_BITE = 0.6F;
    /** Nose-up attitude the wheels allow before the tail would strike the runway. */
    private static final float GROUND_PITCH_LIMIT = 15.0F;
    /** How firmly the undercarriage pulls the aircraft back to sitting flat, per tick. */
    private static final float GROUND_LEVELLING = 0.25F;
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

    private final AnimatableInstanceCache animatableCache = GeckoLibUtil.createInstanceCache(this);
    /** What the aircraft is carrying. Authoritative on the server; a copy of the tag on a client. */
    private final WeaponMounts weapons = new WeaponMounts(this);
    /** The radar and the warning receiver. Server side; the pilot is sent what they find. */
    private final Sensors sensors = new Sensors(this);
    /** Flares and chaff, and when the dispenser will part with the next one. */
    private final Dispenser dispenser = new Dispenser(this);

    private AircraftInput input = AircraftInput.NONE;
    private float throttle;
    private Quaternionf attitude = new Quaternionf();
    private Quaternionf attitudeO = new Quaternionf();
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
    /** The boxes that make up the aircraft's real shape, or empty if its file lists none. */
    private AircraftPart[] parts = new AircraftPart[0];
    /** The chunk this aircraft is holding open while it flies, if any. */
    private ChunkPos heldChunk;
    /** How fast the pilot's client says it is going. The server's only honest answer while flown. */
    private Vec3 pilotVelocity = Vec3.ZERO;
    /** The last blow taken, so one that arrives through several boxes at once only lands once. */
    private DamageSource lastHurtSource;
    private long lastHurtTime = Long.MIN_VALUE;
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
    // Flight characteristics, read from the aircraft's data pack file. Nothing is cached: the
    // lookup is a map hit, and going back to the registry every time means /reload takes effect
    // on aircraft that are already in the air.
    // ------------------------------------------------------------------

    /**
     * This aircraft's id, which is its entity type's id. Everything else about it, from its file to
     * its model to the item that places it, is found under the same name.
     */
    public ResourceLocation getAircraftId() {
        return BuiltInRegistries.ENTITY_TYPE.getKey(this.getType());
    }

    public AircraftDefinition getStats() {
        return AircraftManager.get(this.getAircraftId());
    }

    /** Acceleration along the nose at full throttle, in blocks/tick^2. */
    public float getMaxThrust() {
        return this.getStats().engine().maxThrust();
    }

    /** Throttle change per tick while a throttle key is held. */
    public float getThrottleRate() {
        return this.getStats().engine().throttleRate();
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

    /**
     * What a whole airframe of this sort is worth, in hit points.
     *
     * <p>Never nothing, whatever the file says. An aeroplane worth zero points is destroyed by the
     * first scratch it takes, which is not a thing anybody means to write down, and it would be very
     * hard to work out from the aeroplane vanishing.
     */
    public float getMaxHealth() {
        return Math.max(this.getStats().airframe().health(), 1.0F);
    }

    /** What this airframe has left, in hit points. Zero is a smoking hole. */
    public float getHealth() {
        return this.entityData.get(DATA_HEALTH);
    }

    /**
     * Sets what is left, never below nothing and never above what the airframe is worth.
     *
     * <p>The ceiling matters as much as the floor: an aircraft whose file has been edited down since
     * it was parked would otherwise come back out of the world with more than it can have.
     */
    public void setHealth(float health) {
        this.entityData.set(DATA_HEALTH, Mth.clamp(health, 0.0F, this.getMaxHealth()));
    }

    /** What is left as a fraction of a whole airframe, in [0, 1]. */
    public float getHealthFraction() {
        float max = this.getMaxHealth();

        return max <= 0.0F ? 0.0F : Mth.clamp(this.getHealth() / max, 0.0F, 1.0F);
    }

    /** Impact speed, in blocks/tick, above which hitting something writes the aircraft off. */
    protected float getCrashSpeed() {
        return this.getStats().airframe().crashSpeed();
    }

    protected float getExplosionPower() {
        return this.getStats().airframe().explosionPower();
    }

    public int getMaxPassengers() {
        return this.getStats().airframe().seats().size();
    }

    /**
     * Seat position relative to the entity origin, before the aircraft's attitude is applied, along
     * the aircraft's own axes: x to the right, y up, z towards the nose.
     */
    public Vec3 getSeatOffset(int passengerIndex) {
        List<Vec3> seats = this.getStats().airframe().seats();

        return seats.isEmpty() ? Vec3.ZERO : seats.get(Math.min(passengerIndex, seats.size() - 1));
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
    // State
    // ------------------------------------------------------------------

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_THROTTLE, 0.0F);
        builder.define(DATA_ATTITUDE, new Quaternionf());
        builder.define(DATA_GEAR_DOWN, true);
        builder.define(DATA_FLAPS_DOWN, false);
        builder.define(DATA_VTOL, false);
        builder.define(DATA_WEAPONS, new CompoundTag());
        // A figure rather than this aircraft's own maximum, because this runs from inside the entity
        // constructor, before there is an aircraft to ask. The constructor fills in the real one.
        builder.define(DATA_HEALTH, AircraftDefinition.Airframe.DEFAULT_HEALTH);
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

    public Sensors getSensors() {
        return this.sensors;
    }

    public WeaponMounts getWeapons() {
        return this.weapons;
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

    public Quaternionf getAttitude() {
        return this.attitude;
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

    public boolean isGearDown() {
        return this.entityData.get(DATA_GEAR_DOWN);
    }

    /**
     * Raises or lowers the undercarriage. Refused while the aircraft is sitting on its wheels, which
     * is the job a weight-on-wheels switch does on the real thing.
     */
    public void toggleGear() {
        if (this.level().isClientSide || (this.isGearDown() && this.onGround())) {
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
        return !this.onGround() && Math.abs(this.angleOfAttack) > this.getStats().wing().stallAngle();
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
        this.tickLerp();

        if (this.isControlledByLocalInstance()) {
            if (!(this.getControllingPassenger() instanceof Player)) {
                // Nobody at the stick: the controls centre, but the throttle stays where it was left
                // and the aircraft flies on until something takes it out of the air.
                this.input = AircraftInput.NONE;
            }

            this.flightTick();

            Vec3 impactVelocity = this.getDeltaMovement();
            this.move(MoverType.SELF, impactVelocity);
            this.detectCrash(impactVelocity);
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
            this.attitude = new Quaternionf(this.entityData.get(DATA_ATTITUDE));

            if (!this.level().isClientSide) {
                this.checkStructuralLoad(travelled);
            }
        }

        if (this.crashing) {
            this.crash();
        }

        this.tickParts();

        if (this.level().isClientSide) {
            this.spawnFlightEffects();
        } else {
            this.tickWeapons();
            this.sensors.tick();
            this.dispenser.tick(this.input.flare(), this.input.chaff());
        }

        // Hold the chunk under us open, so flying beyond everyone's render distance does not simply
        // stop the aircraft existing.
        this.heldChunk = AircraftChunkLoader.update(this, this.heldChunk);

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
     * Decides whether the impact that just stopped the aircraft was survivable. The velocity from
     * before {@link #move} is what counts: move() zeroes the blocked axes, so by the time it returns
     * there is nothing left to measure.
     */
    private void detectCrash(Vec3 impactVelocity) {
        if (this.horizontalCollision
                && Math.sqrt(impactVelocity.x * impactVelocity.x + impactVelocity.z * impactVelocity.z) > this.getCrashSpeed()) {
            this.crashing = true;
        }

        // A heavy landing counts too, but only a genuine descent: an aircraft parked on the ground
        // collides vertically every tick under gravity alone.
        if (this.verticalCollision && impactVelocity.y < -this.getCrashSpeed()) {
            this.crashing = true;
        }
    }

    /**
     * One tick of flight.
     *
     * <p>The aircraft is not pushed along its nose. Thrust acts along the nose, gravity acts down,
     * and the wing produces lift square to the airflow in proportion to the angle it meets that
     * airflow at. Everything that makes an aeroplane feel like one falls out of that: it has to be
     * rotated to leave the ground, a bank turns it because the lift tilts with the wings, hauling
     * the nose up past the stalling angle drops it, and a hard turn bleeds speed because lift is not
     * free.
     */
    private void flightTick() {
        AircraftDefinition definition = this.getStats();
        AircraftDefinition.Wing wing = definition.wing();
        AircraftDefinition.Handling handling = definition.handling();

        Vec3 motion = this.getDeltaMovement();
        double speed = motion.length();

        this.setThrottle(this.throttle + this.input.throttle() * definition.engine().throttleRate());

        // How far the nozzle has swung, as the fraction of the engine that is now holding the
        // aeroplane up rather than pushing it along. Zero for everything that cannot do it at all.
        AircraftDefinition.Vtol vtol = definition.vtol().orElse(null);
        double lifting = vtol == null
                ? 0.0
                : Math.sin(Math.toRadians(this.vtolProgress * vtol.maxAngle()));

        // Control surfaces only bite while air is flowing over them. A lift system does not care:
        // what flies a hovering aeroplane is jets of its own, and without them the pilot would have
        // the controls of a brick from the moment the wing stopped working.
        float authority = (float) Mth.clamp(speed / Math.max(wing.stallSpeed(), 1.0E-4F), 0.0, 1.5);

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
        float commandedPitch = this.limitToWing(this.input.pitch() * handling.pitchRate() * authority);
        this.pitchVelocity += (commandedPitch - this.pitchVelocity) * lag;
        this.rollVelocity += (this.input.roll() * handling.rollRate() * authority - this.rollVelocity) * lag;
        this.yawVelocity += (this.input.yaw() * handling.yawRate() * authority - this.yawVelocity) * lag;

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

        Vec3 forces = new Vec3(0.0, -GRAVITY, 0.0).add(thrustAxis.scale(thrust * this.throttle));

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

        if (speed > 1.0E-4) {
            Vec3 flow = motion.scale(1.0 / speed);

            // Angle of attack: how far below the wing the air is coming from.
            this.angleOfAttack = (float) Math.toDegrees(Math.asin(Mth.clamp(-flow.dot(up), -1.0, 1.0)));
            double liftCoefficient = wing.liftCoefficient(this.angleOfAttack)
                    * (1.0 + this.flapsProgress * this.getFlapsLiftBonus());

            // Lift acts square to the airflow, tilted with the wings. That tilt is what turns the
            // aircraft: bank, and the same force that was holding it up starts pulling it round.
            Vec3 liftAxis = up.subtract(flow.scale(up.dot(flow)));

            if (liftAxis.lengthSqr() > 1.0E-8) {
                forces = forces.add(liftAxis.normalize().scale(wing.lift() * liftCoefficient * speed * speed));
            }

            // Drag: what the shape costs, plus what the lift costs. The second is why a hard turn
            // washes speed off.
            double parasitic = wing.drag() * (1.0
                    + this.gearProgress * this.getGearDragPenalty()
                    + this.flapsProgress * this.getFlapsDragPenalty())
                    * (this.input.brake() ? 4.0 : 1.0);
            double drag = parasitic + wing.inducedDrag() * liftCoefficient * liftCoefficient;
            forces = forces.add(flow.scale(-drag * speed * speed));
            this.checkStructuralLoad(motion);

            // The fin drags the nose round onto the flight path. Like the rudder, it acts about the
            // aircraft's own vertical axis, so upside down it pulls the same way relative to the
            // aircraft and the opposite way relative to the world, exactly as a fin does.
            weathervaneYaw = (float) (flow.dot(right) * handling.weathervane() * authority);

            // And the fuselage refuses to fly sideways.
            motion = motion.subtract(right.scale(motion.dot(right) * wing.lateralDrag()));
        } else {
            this.angleOfAttack = 0.0F;
        }

        this.applyBodyRotation(this.rollVelocity, this.pitchVelocity, this.yawVelocity + weathervaneYaw);
        this.deltaRotation = Mth.wrapDegrees(this.getYRot() - previousYRot);
        motion = motion.add(forces);

        if (this.onGround()) {
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
    private float limitToWing(float commanded) {
        AircraftDefinition.Handling handling = this.getStats().handling();
        float limit = this.getStats().wing().stallAngle() * handling.alphaLimit();

        if (limit <= 0.0F || handling.alphaLimit() >= 1.0F || commanded * this.angleOfAttack <= 0.0F) {
            return commanded;
        }

        float bite = limit * ALPHA_LIMITER_BITE;
        float over = (Math.abs(this.angleOfAttack) - bite) / Math.max(limit - bite, 1.0E-3F);

        return commanded * Mth.clamp(1.0F - over, 0.0F, 1.0F);
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

    /** Wheels on the ground: rolling friction, brakes, and an attitude the undercarriage allows. */
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
        double friction = this.input.brake() ? gear.brakeFriction() : gear.rollingFriction();

        return new Vec3(motion.x * friction, Math.max(motion.y, 0.0), motion.z * friction);
    }

    /**
     * How far the aircraft moved over the last tick, which is its velocity wherever it is being
     * simulated. Read this rather than the delta movement: a piloted aircraft is flown by its
     * pilot's client and every other copy of it holds a delta movement of zero, so anything that
     * needs to know how fast it is going, instruments included, has to look at where it has been.
     */
    public Vec3 getVelocity() {
        // On the server, an aircraft with a pilot at the stick is not moved by anything here: its
        // position arrives in packets, and those are applied between ticks, after the old position
        // has been stamped. Measured from here it has therefore not moved at all this tick, and the
        // difference is flatly zero however fast it is really going — which quietly robbed every
        // weapon fired from it of the speed it should have left with. The pilot's own figure is the
        // only truthful answer on that side.
        if (!this.level().isClientSide && !this.isControlledByLocalInstance()) {
            return this.pilotVelocity;
        }

        return this.travelled();
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
     * How many times its own weight the wing is currently pulling. One is level flight, zero is
     * weightless, and beyond what the airframe is stressed for it starts to bend.
     */
    public float getLoadFactor(Vec3 velocity) {
        double speed = velocity.length();

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

    /**
     * Turns an offset written in the aircraft's own axes (x right, y up, z towards the nose) into a
     * world position. Used for seats and for the cockpit viewpoint, so both ride the airframe.
     */
    public Vec3 toWorld(Vec3 offset, float partialTick) {
        return this.getPosition(partialTick).add(Attitude.toWorld(this.getAttitude(partialTick), offset));
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

        this.vtolProgress = approach(this.vtolProgress, this.nozzleWanted(vtol),
                1.0F / Math.max(vtol.cycleTicks(), 1));
    }

    /**
     * Where the nozzle should be, which on the way back up is decided by the airspeed rather than by
     * the pilot.
     *
     * <p>Selecting conventional flight does not swing the nozzle up; it asks for it to be swung up as
     * fast as the aeroplane can afford, which is exactly as fast as it is going. At a standstill the
     * nozzle stays down whatever the lever says, and it comes up in step with the speed until, at the
     * conversion speed, it is fully aft and the wing has taken the weight.
     *
     * <p>Scheduled rather than timed because the alternative does not work and cannot be made to. A
     * nozzle that swings up on a stopwatch takes the lift away before there is any wing lift to
     * replace it — the aeroplane is left below its stalling speed with the engine pointing the wrong
     * way, and it comes down. Tying the two together means the lift is only ever given up in exchange
     * for the speed that replaces it, which is what a conversion is.
     *
     * <p>So the technique is the real one: hover, ease the nose down, let it accelerate, and the
     * nozzle follows the airspeed round without being touched again.
     */
    private float nozzleWanted(AircraftDefinition.Vtol vtol) {
        if (this.isVtolSelected()) {
            return 1.0F;
        }

        double speed = this.getVelocity().length();

        return (float) Mth.clamp(1.0 - speed / Math.max(vtol.conversionSpeed(), 1.0E-3F), 0.0, 1.0);
    }

    private void tickGear() {
        this.gearProgressO = this.gearProgress;
        this.gearProgress = approach(this.gearProgress, this.isGearDown() ? 1.0F : 0.0F,
                1.0F / Math.max(this.getGearCycleTicks(), 1));
        this.flapsProgressO = this.flapsProgress;
        this.flapsProgress = approach(this.flapsProgress, this.isFlapsDown() ? 1.0F : 0.0F,
                1.0F / Math.max(this.getFlapsCycleTicks(), 1));
    }

    private void tickLerp() {
        if (this.isControlledByLocalInstance()) {
            this.lerpSteps = 0;
            this.syncPacketPositionCodec(this.getX(), this.getY(), this.getZ());
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
    }

    @Override
    public double lerpTargetX() {
        return this.lerpSteps > 0 ? this.lerpX : this.getX();
    }

    @Override
    public double lerpTargetY() {
        return this.lerpSteps > 0 ? this.lerpY : this.getY();
    }

    @Override
    public double lerpTargetZ() {
        return this.lerpSteps > 0 ? this.lerpZ : this.getZ();
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
        List<AircraftShape.Box> shape = AircraftManager.shape(this.getAircraftId()).boxes();

        List<AircraftDefinition.Hardpoint> hardpoints = this.getStats().hardpoints();

        for (int i = 0; i < this.parts.length; i++) {
            AircraftPart part = this.parts[i];

            if (part.isPylon()) {
                this.placePylon(part, hardpoints);

                continue;
            }

            if (i >= shape.size()) {
                part.place(this.position(), 1.0E-3, 1.0E-3, 1.0E-3);

                continue;
            }

            AircraftShape.Box box = shape.get(i);
            Vec3 centre = this.position().add(Attitude.toWorld(this.attitude, box.offset()));
            Vec3 half = box.size().scale(0.5);
            // The box's own angle within the aircraft, then the aircraft's angle in the world.
            Matrix3f rotation = new Quaternionf(this.attitude).mul(box.orientation()).get(new Matrix3f());

            part.place(centre,
                    2.0 * extent(rotation, 0, half),
                    2.0 * extent(rotation, 1, half),
                    2.0 * extent(rotation, 2, half));
        }
    }

    /**
     * Puts a pylon's box where its hardpoint is, at a size a player can comfortably reach for.
     *
     * <p>Square rather than shaped to whatever is hanging there. A pylon is a place on the aeroplane
     * rather than an object, and it has to stay reachable when it is bare, which is exactly when
     * somebody wants to hang something on it.
     */
    private void placePylon(AircraftPart part, List<AircraftDefinition.Hardpoint> hardpoints) {
        int slot = part.getPylon();

        if (slot >= hardpoints.size()) {
            // The file no longer lists this one. It cannot be got rid of, so it is folded away.
            part.place(this.position(), 1.0E-3, 1.0E-3, 1.0E-3);

            return;
        }

        Vec3 where = hardpoints.get(slot).pos();
        Vec3 centre = this.position().add(Attitude.toWorld(this.attitude, where));
        double size = pylonBox(where, hardpoints, slot);
        part.place(centre, size, size, size);
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
        Vec3 velocity = this.getVelocity();
        double speed = velocity.length();

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

    /** Half the width of the widest part of the aircraft, taken from its collision shape. */
    private double getWingSpan() {
        double span = this.getBbWidth() / 2.0;

        for (AircraftShape.Box box : AircraftManager.shape(this.getAircraftId()).boxes()) {
            span = Math.max(span, Math.abs(box.offset().x) + box.size().x / 2.0);
        }

        return span;
    }

    /** Where thrust and drag balance out, for aircraft whose file sets no ceiling of its own. */
    private double topSpeed() {
        AircraftDefinition.Wing wing = this.getStats().wing();

        return wing.drag() > 0.0F ? Math.sqrt(this.getStats().engine().maxThrust() / wing.drag()) : 1.0;
    }

    /** One part per box in the aircraft's shape file. */
    /**
     * Builds the aircraft's boxes: the airframe first, from its collision file, and then one for
     * each pylon.
     *
     * <p>A pylon gets a box of its own so that it can be reached for on its own. Aiming at the left
     * inner pylon and aiming at the right outer one are different intentions, and until now the
     * aeroplane could not tell them apart: a click anywhere on it hung a weapon on whichever pylon
     * happened to be free first. The box is what makes the difference visible to the game.
     */
    private void buildParts() {
        List<AircraftShape.Box> shape = AircraftManager.shape(this.getAircraftId()).boxes();
        List<AircraftDefinition.Hardpoint> hardpoints = this.getStats().hardpoints();
        this.parts = new AircraftPart[shape.size() + hardpoints.size()];

        for (int i = 0; i < shape.size(); i++) {
            this.parts[i] = new AircraftPart(this, shape.get(i).name());
        }

        for (int i = 0; i < hardpoints.size(); i++) {
            this.parts[shape.size() + i] = new AircraftPart(this, hardpoints.get(i).name(), i);
        }

        // Numbered from the aircraft's own id rather than left with whatever the entity counter
        // handed out, so that the two sides agree about which box is which. See setId.
        this.setId(this.getId());
    }

    /**
     * Numbers the aircraft's boxes after the aircraft itself, so that both sides call the same box
     * by the same name.
     *
     * <p>A box is an entity with an id, and ids come from a counter each side keeps for itself. The
     * server makes an aircraft, its boxes take the next few numbers, and the client is then told the
     * aircraft's id and quietly renumbers only the aircraft — leaving its boxes on whatever numbers
     * its own counter had reached. The two sides then disagree about every box.
     *
     * <p>Nothing notices until a player clicks one. Being shot is decided by the server against its
     * own boxes and never crosses the gap, but a click is the client naming what it hit and asking
     * the server to act on it; named by a number the server does not recognise, the click reaches
     * nothing and climbing aboard or hanging a weapon on a pylon simply fails to happen. That is
     * what made this show up the moment the boxes became the only thing a click could land on.
     *
     * <p>Deriving each box's id from the aircraft's own makes the two sides agree by construction.
     * It is what vanilla does for the ender dragon, for the same reason.
     */
    @Override
    public void setId(int id) {
        super.setId(id);

        // Called once by the entity's own constructor, before there are any boxes to number.
        if (this.parts == null) {
            return;
        }

        for (int i = 0; i < this.parts.length; i++) {
            this.parts[i].setId(id + i + 1);
        }
    }

    /** Half the reach of a rotated box along one world axis: the usual sum of absolute terms. */
    private static double extent(Matrix3f rotation, int axis, Vec3 half) {
        return Math.abs(rotation.get(0, axis)) * half.x
                + Math.abs(rotation.get(1, axis)) * half.y
                + Math.abs(rotation.get(2, axis)) * half.z;
    }

    @Override
    public boolean isMultipartEntity() {
        return this.parts.length > 0;
    }

    @Override
    public AircraftPart[] getParts() {
        return this.parts;
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
        this.heldChunk = AircraftChunkLoader.release(this, this.heldChunk);
    }

    protected void crash() {
        if (this.level().isClientSide || this.isRemoved()) {
            return;
        }

        Vec3 pos = this.position();
        this.ejectPassengers();
        // Gone before the blast, not after. An explosion damages everything in reach as it goes off,
        // and this aircraft's own collision boxes are in reach: they pass the hit to the aircraft,
        // which is destroyed again, which explodes again. Removing it first makes hurt() a no-op and
        // the blast has nothing of ours left to hit.
        this.discard();
        this.level().explode(this, pos.x, pos.y + 0.5, pos.z, this.getExplosionPower(), Level.ExplosionInteraction.MOB);
    }

    /**
     * Takes a blow, once, however many of the aircraft's boxes it arrived through.
     *
     * <p>Anything that hurts an area — an explosion above all — asks the level for everything inside
     * it and hurts each in turn, and the aircraft's boxes are all in that list. Passed straight
     * through, a single blast would land once for every box the aircraft is described with: eleven
     * times over for the Su-25. That would make an aeroplane's toughness depend on how finely
     * somebody chose to draw its shape, which is precisely backwards.
     *
     * <p>So the same blow is counted once. Sameness is the damage source itself: one explosion builds
     * one of those and hands it to everything it touches, while two shells arriving in the same tick
     * bring one each and both count.
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide || this.isRemoved()) {
            return true;
        }

        if (this.isInvulnerableTo(source)) {
            return false;
        }

        long now = this.level().getGameTime();

        if (source == this.lastHurtSource && now == this.lastHurtTime) {
            return true;
        }

        this.lastHurtSource = source;
        this.lastHurtTime = now;

        // Deliberately not markHurt(). All that does is ask the server to broadcast this aircraft's
        // velocity at the end of the tick, which for a boat is its knockback and for an aeroplane is
        // the flat zero the server keeps for a piloted one. See lerpMotion.
        this.setHurtDir(-this.getHurtDir());
        this.setHurtTime(10);
        this.gameEvent(GameEvent.ENTITY_DAMAGE, source.getEntity());

        // Everything that hurts an aeroplane goes through its health and nothing else takes it out
        // early. A boat is removed outright by one punch from anyone in creative, and an aircraft
        // inherited that: an arrow, a stray swing, a test shot, and a whole airframe was gone with
        // three hundred points still on the gauge. Whoever wants rid of one sneaks and clicks it,
        // which puts it back in their pocket rather than scattering it over the runway.
        if (this.wound(amount)) {
            this.destroy(source);
        }

        return true;
    }

    /**
     * Takes points off the airframe, wherever they came from.
     *
     * <p>Point for point: what a weapon's file says it does is what it does here, with none of the
     * scaling a boat applies. An aeroplane is worth a few hundred of these and a player is worth
     * twenty, so the same round that costs a man two hearts costs an aeroplane four points of a
     * three-hundred-point airframe, which is the whole of what the two numbers mean.
     *
     * @return true if that was the last of it
     */
    protected boolean wound(float amount) {
        if (amount <= 0.0F) {
            return false;
        }

        this.setHealth(this.getHealth() - amount);

        return this.getHealth() <= 0.0F;
    }

    /** A shot-down aircraft comes apart rather than dropping a serviceable one. */
    @Override
    protected void destroy(DamageSource source) {
        this.crash();
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
        AircraftPart pylon = this.pylonInSight(player);

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

        // Crouching is how somebody walks along a wing without stepping off it, so it cannot also
        // mean "take this aeroplane to pieces" -- that is what the wrench is for. It still means
        // "not now" rather than falling through to the cockpit: a player holding something and
        // crouching by an aircraft is doing something else with it.
        if (player.isSecondaryUseActive()) {
            return InteractionResult.PASS;
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
        if (!this.isParked()) {
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
    private AircraftPart pylonInSight(Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 reach = eye.add(player.getViewVector(1.0F).scale(player.entityInteractionRange()));
        AircraftPart nearest = null;
        double closest = Double.MAX_VALUE;

        for (AircraftPart part : this.parts) {
            if (!part.isPylon() || !part.isPickable()) {
                continue;
            }

            Optional<Vec3> hit = part.getBoundingBox().clip(eye, reach);

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
     * <p>Asked by the pylon's own box to decide whether it is worth reaching for at all.
     */
    public boolean isLoadablePylon(int slot) {
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
    protected boolean canAddPassenger(Entity passenger) {
        return this.getPassengers().size() < this.getMaxPassengers();
    }

    @Override
    public LivingEntity getControllingPassenger() {
        return this.getFirstPassenger() instanceof LivingEntity pilot ? pilot : super.getControllingPassenger();
    }

    /** Which seat a rider occupies, or 0 if they are not aboard. */
    public int getSeatIndex(Entity passenger) {
        return Math.max(this.getPassengers().indexOf(passenger), 0);
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
        Vec3 seat = this.getSeatOffset(this.getSeatIndex(passenger));

        // Built from the aircraft's own axes rather than from yaw and pitch alone, so the seat banks
        // with the wings. Rotating the offset by the euler angles instead leaves the pilot sitting
        // upright in a rolled aircraft, adrift of the cockpit the model draws.
        Vec3 nose = this.getNoseVector();
        Vec3 up = this.getLiftVector();
        Vec3 right = Attitude.right(this.attitude);

        return right.scale(seat.x).add(up.scale(seat.y)).add(nose.scale(seat.z));
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

    /** Keeps a passenger from looking further round than they could from inside a cockpit. */
    protected void clampRotation(Entity passenger) {
        passenger.setYBodyRot(this.getYRot());
        float relative = Mth.wrapDegrees(passenger.getYRot() - this.getYRot());
        float clamped = Mth.clamp(relative, -135.0F, 135.0F);
        passenger.yRotO += clamped - relative;
        passenger.setYRot(passenger.getYRot() + clamped - relative);
        passenger.setYHeadRot(passenger.getYRot());
    }

    /**
     * An aircraft does not collide with itself. Its own boxes are solid to everyone else, which is
     * the point of them, but to the aircraft they are simply where it is: without this it spends
     * every tick shouldering its way past its own wings and never gets up to speed.
     */
    @Override
    public boolean canCollideWith(Entity other) {
        return !(other instanceof AircraftPart part && part.getParent() == this) && super.canCollideWith(other);
    }

    /**
     * An aircraft that has boxes of its own is not solid in its own right: the boxes are.
     *
     * <p>Minecraft gives an entity one upright box with a square footprint, and for a fifteen-metre
     * aeroplane that is a shed — six and a half blocks across whatever the wings are doing, and
     * nowhere near the wing once it banks. The boxes in the aircraft's collision file are the real
     * shape, so once there are any, they do the work and the plain box stops pretending to.
     *
     * <p>Nothing is lost by standing down: {@link AircraftPart} passes hits, clicks and pick results
     * straight to the aircraft, so being shot, being climbed into and being stood on all still reach
     * here. An aircraft with no collision file keeps its plain box, because otherwise it would have
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
     * collision file would otherwise have nothing to be clicked or shot but five small boxes hanging
     * under its wings, and no way to be climbed into at all.
     */
    private boolean hasAirframeBoxes() {
        for (AircraftPart part : this.parts) {
            if (!part.isPylon()) {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean isPushable() {
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

        List<AircraftShape.Box> shape = AircraftManager.shape(this.getAircraftId()).boxes();

        if (shape.isEmpty()) {
            return movement;
        }

        Vec3 allowed = movement;

        for (AircraftShape.Box box : shape) {
            Vec3 stopped = collideBoundingBox(this, movement, this.worldBox(box), this.level(), List.of());
            allowed = new Vec3(
                    nearerToZero(allowed.x, stopped.x),
                    nearerToZero(allowed.y, stopped.y),
                    nearerToZero(allowed.z, stopped.z));
        }

        return allowed;
    }

    /**
     * Whether the aircraft's real shape has room where it is standing, give or take a margin.
     *
     * <p>Used when one is put down. Now that the wings stop against the world, an aeroplane set down
     * with a wing inside a hillside would be wedged there and unable to move, so the whole shape has
     * to be clear rather than just the middle of it.
     *
     * @param margin how much each box may overlap the world and still count as clear, which keeps a
     *               wingtip resting a hair inside a slope from making the aircraft unplaceable
     */
    public boolean hasRoomHere(double margin) {
        List<AircraftShape.Box> shape = AircraftManager.shape(this.getAircraftId()).boxes();

        if (shape.isEmpty()) {
            return this.level().noCollision(this, this.getBoundingBox().deflate(margin));
        }

        for (AircraftShape.Box box : shape) {
            AABB room = this.worldBox(box).deflate(margin);

            if (room.getXsize() > 0.0 && room.getYsize() > 0.0 && room.getZsize() > 0.0
                    && !this.level().noCollision(this, room)) {
                return false;
            }
        }

        return true;
    }

    /** One of the aircraft's boxes as an upright box in the world, where it is standing right now. */
    private AABB worldBox(AircraftShape.Box box) {
        Vec3 centre = this.position().add(Attitude.toWorld(this.attitude, box.offset()));
        Vec3 half = box.size().scale(0.5);
        Matrix3f rotation = new Quaternionf(this.attitude).mul(box.orientation()).get(new Matrix3f());
        double x = extent(rotation, 0, half);
        double y = extent(rotation, 1, half);
        double z = extent(rotation, 2, half);

        return new AABB(centre.x - x, centre.y - y, centre.z - z, centre.x + x, centre.y + y, centre.z + z);
    }

    /** Whichever of the two allows less movement, keeping the sign the pilot asked for. */
    private static double nearerToZero(double a, double b) {
        return Math.abs(a) <= Math.abs(b) ? a : b;
    }

    /**
     * The box the renderer decides visibility against, which is deliberately not the box the
     * aircraft collides with.
     *
     * <p>The plain hitbox is kept small on purpose — it covers the fuselage so that an overhanging
     * wingtip does not make the aeroplane unplaceable or catch on every doorway. That is the right
     * size to collide with and quite the wrong size to be drawn against: a fifteen-metre aeroplane
     * whose six-metre box has just left the screen is still very much on it, and would blink out.
     * So the shape the aircraft really occupies is what culling is given.
     */
    @Override
    public AABB getBoundingBoxForCulling() {
        double reach = 0.0;

        for (AircraftShape.Box box : AircraftManager.shape(this.getAircraftId()).boxes()) {
            for (int axis = 0; axis < 3; axis++) {
                double corner = Math.abs(component(box.offset(), axis)) + component(box.size(), axis) / 2.0;
                reach = Math.max(reach, corner);
            }
        }

        return reach > 0.0 ? this.getBoundingBox().inflate(reach) : this.getBoundingBox();
    }

    private static double component(Vec3 v, int axis) {
        return axis == 0 ? v.x : axis == 1 ? v.y : v.z;
    }

    /**
     * Ticks wherever it is, but only on a client.
     *
     * <p>An aircraft beyond the world the player has loaded is still being sent, and is still drawn
     * as a ghost, but the client stops ticking anything whose chunk it does not have — and an
     * aircraft that is not ticked never runs the interpolation that the position packets feed, so
     * what is drawn out there is a contact frozen at the moment it crossed the edge. Saying it
     * always ticks is what keeps it flying.
     *
     * <p>Emphatically not on the server. Out there the aircraft holds its own chunk open and ticks
     * because of that; if the ticket is ever let go — a parked one lets go — then ticking anyway
     * would run the flight model over ground the server has not loaded, and every block it asked
     * about would be generated on the spot to answer.
     */
    @Override
    public boolean isAlwaysTicking() {
        return this.level().isClientSide;
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
        AircraftDefinition.Hitbox hitbox = this.getStats().hitbox();

        if (!hitbox.hasGhostLimit()) {
            return true;
        }

        double range = hitbox.ghostRange();

        return distance < range * range;
    }

    // ------------------------------------------------------------------
    // Persistence and GeckoLib
    // ------------------------------------------------------------------

    @Override
    protected net.minecraft.world.item.Item getDropItem() {
        return BuiltInRegistries.ITEM.get(this.getAircraftId());
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
        controllers.add(new AnimationController<>(this, "gear", GEAR_TRANSITION_TICKS,
                AircraftAnimations::gearCycle).setAnimationSpeedHandler(AircraftAnimations::gearSpeed));
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
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
