package com.ashvehicles.entity;

import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.vehicle.VehicleShape;
import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.item.WrenchItem;
import com.ashvehicles.vehicle.GroundVehicleDefinition;
import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.weapon.MainGun;
import com.ashvehicles.weapon.TargetLock;
import com.ashvehicles.weapon.TurretLauncher;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.FluidTags;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * Shared behaviour for every ground vehicle in the mod: a simple drivetrain, a hull that lies on
 * whatever it is standing on, a turret aimed independently of both, and the damage handling that
 * turns a tank into a burning one.
 *
 * <p><b>Where the movement runs.</b> As with an aircraft and with vanilla's boats, a vehicle is
 * simulated by whoever "controls" it: the driving client while a player is in the seat, otherwise
 * the server. That is what {@link #isControlledByLocalInstance()} decides. Position, yaw and pitch
 * reach the server through vanilla's ServerboundMoveVehiclePacket; the hull's roll, its speed and
 * where the turret is pointing have no vanilla equivalent, so the client sends them in
 * {@link com.ashvehicles.network.GroundVehicleInputPayload} and the server mirrors them into
 * synched data for everyone else.
 *
 * <p><b>What makes this not an aeroplane.</b> An aircraft chooses its attitude and the world has no
 * say in it. A tank is the other way round: the driver chooses a heading and nothing else, and the
 * hull's pitch and roll are read off the ground under its tracks every tick. So the attitude here is
 * built rather than integrated — a heading the driver steers, plus two angles the terrain dictates —
 * which is why there is no accumulated rotation to drift and nothing to normalise.
 *
 * <p>The turret is the one thing aimed in spite of all that. It is held in the hull's frame and
 * wound back by however far the hull has just turned, so a turret laid on a target stays laid on it
 * while the hull crosses a ditch underneath. That is what a stabiliser does, and it is the
 * difference between a tank and a self-propelled gun.
 */
public class GroundVehicleEntity extends VehicleEntityBase implements GeoEntity {
    /**
     * Which way the hull is lying, as a rotation. Minecraft gives an entity a heading and an
     * elevation and no roll at all, which cannot describe a tank across a slope, so the real
     * attitude is carried here and the vanilla angles are kept in step behind it.
     */
    private static final EntityDataAccessor<Quaternionf> DATA_ATTITUDE =
            SynchedEntityData.defineId(GroundVehicleEntity.class, EntityDataSerializers.QUATERNION);
    /**
     * How fast it is going along its own nose, in blocks a tick, from whichever side is driving.
     *
     * <p>Only one machine runs the movement, and every other copy has to draw the vehicle from a
     * stream of positions. Working the speed back out of that stream reads the drift between three
     * unsynchronised clocks as speed; sending the figure costs one float a tick and is what the road
     * wheels turn at and the engine note is pitched from.
     */
    private static final EntityDataAccessor<Float> DATA_SPEED =
            SynchedEntityData.defineId(GroundVehicleEntity.class, EntityDataSerializers.FLOAT);
    /** Where the turret is pointing, in degrees from dead ahead of the hull, positive to the right. */
    private static final EntityDataAccessor<Float> DATA_TURRET_YAW =
            SynchedEntityData.defineId(GroundVehicleEntity.class, EntityDataSerializers.FLOAT);
    /** How far the gun is elevated above the turret roof line, in degrees; negative is depressed. */
    private static final EntityDataAccessor<Float> DATA_GUN_PITCH =
            SynchedEntityData.defineId(GroundVehicleEntity.class, EntityDataSerializers.FLOAT);
    /** Rounds left for the main armament. */
    private static final EntityDataAccessor<Integer> DATA_ROUNDS =
            SynchedEntityData.defineId(GroundVehicleEntity.class, EntityDataSerializers.INT);
    /**
     * Ticks until the gun is loaded again, counting down.
     *
     * <p>Sent because every side needs it and none of them could work it out. It is the gauge the
     * crew fire on, it is what the barrel's recoil is drawn from — and a counter that has just
     * jumped up from nothing <em>is</em> the news that the gun has fired, so it does the work of an
     * event as well without being one. See {@link com.ashvehicles.weapon.MainGun}.
     */
    private static final EntityDataAccessor<Integer> DATA_RELOAD =
            SynchedEntityData.defineId(GroundVehicleEntity.class, EntityDataSerializers.INT);
    /** Missiles left in the tubes, and the wait before the next one may go. */
    private static final EntityDataAccessor<Integer> DATA_MISSILES =
            SynchedEntityData.defineId(GroundVehicleEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_MISSILE_RELOAD =
            SynchedEntityData.defineId(GroundVehicleEntity.class, EntityDataSerializers.INT);
    /**
     * Which of the two armaments the trigger fires.
     *
     * <p>Synched because the instruments are drawn from it and because the server is what decides
     * it: the key press arrives in a packet, and every other client has to draw the same sight the
     * crew are looking at.
     */
    private static final EntityDataAccessor<Boolean> DATA_MISSILE_MODE =
            SynchedEntityData.defineId(GroundVehicleEntity.class, EntityDataSerializers.BOOLEAN);
    /**
     * What the seeker is holding, as the entity number, and how far along the lock has got.
     *
     * <p>Sent rather than worked out, for the reason everything about a seeker is sent: deciding
     * what a weapon is pointed at is not something a client may do. Two figures are the whole of
     * what the instruments need -- which thing to draw the box round, and how nearly closed it is --
     * and the progress is already scaled against the missile own lock_ticks, so a client needs to
     * know nothing about the round in the tubes to draw it.
     */
    private static final EntityDataAccessor<Integer> DATA_LOCK_TARGET =
            SynchedEntityData.defineId(GroundVehicleEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_LOCK_PROGRESS =
            SynchedEntityData.defineId(GroundVehicleEntity.class, EntityDataSerializers.FLOAT);
    /**
     * How hard the wheel is over, from -1 to 1.
     *
     * <p>Sent for the same reason the speed is: only one machine runs the driving, and every other
     * copy has to draw the vehicle from a stream of positions. It cannot be worked back out of that
     * stream either -- a wheeled vehicle cannot turn at all standing still, so a driver holding full
     * lock at a stop changes nothing anybody could measure, and yet the front wheels are plainly
     * over. What the wheels follow is the driver, not the hull.
     */
    private static final EntityDataAccessor<Float> DATA_STEER =
            SynchedEntityData.defineId(GroundVehicleEntity.class, EntityDataSerializers.FLOAT);

    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    /** Blocks a tick squared. Vanilla's figure, applied by us because we move the vehicle ourselves. */
    private static final double GRAVITY = 0.08;
    /** Terminal velocity, so a vehicle that drives off a cliff does not fall through the world. */
    private static final double MAX_FALL = 3.0;

    /**
     * How far above and below the hull the ground is looked for, in blocks.
     *
     * <p>{@code PROBE_ABOVE} is a compromise between two things that pull opposite ways. It has to
     * reach the corner of the vehicle on the steepest slope it can hold, or the reading is capped
     * and the hull lies flatter than the hill it is on — half a five-block track on a thirty-five
     * degree slope puts a corner a block and three quarters above the middle. But it must also stay
     * <em>under</em> the roof of anything the vehicle could sensibly drive into, because a probe
     * that starts inside a tunnel ceiling reports the ceiling as the ground and the vehicle refuses
     * to enter. Two blocks sees a step of two and fits under a tunnel of three.
     *
     * <p>Capping errs the safe way in any case: a reading held down by this makes a rise look
     * smaller than it is, which lets a vehicle through rather than stopping it against nothing.
     */
    private static final double PROBE_ABOVE = 2.0;
    private static final double PROBE_BELOW = 3.0;

    /** Speeds below this are nothing: the vehicle is standing still and should be left standing. */
    private static final float STANDSTILL = 1.0E-4F;

    /**
     * How much of full lock the steered wheels swing in one tick.
     *
     * <p>A fraction of the lock rather than a rate in degrees, so that a vehicle with a lot of lock
     * and one with a little both take the same few ticks to reach it -- which is what a driver
     * winding a wheel over actually looks like, and is a thing nobody is going to want to tune
     * separately for every vehicle. A fifth of the way a tick is a little over a quarter of a second
     * from straight ahead to hard over.
     */
    private static final float STEER_SWING = 0.22F;

    /**
     * How tall a gap has to be before it is worth tracing at all, in blocks.
     *
     * <p>A vehicle whose whole height is inside its own step height has nothing overhead that could
     * stop it, and a trace of no length is a trace worth not making.
     */
    private static final double HEADROOM_MARGIN = 0.1;

    /**
     * A crumb of slack on the climb test, in blocks.
     *
     * <p>A ground reading is where a ray met a block face, worked out along that ray, so two probes
     * standing on faces exactly a block apart can disagree about that block by the last bit of a
     * double. Taken strictly, whether a tank drives over a one-block kerb — which {@code
     * climb_height: 1.0} plainly says it does, and which is every riser of a forty-five degree
     * slope — would come down to which way that bit happened to fall.
     */
    private static final double CLIMB_SLACK = 1.0E-3;

    /** How far clear of the vehicle somebody getting out is put down, in blocks. */
    private static final double DISMOUNT_MARGIN = 0.2;

    /**
     * How far above a ship's own waterline the ground has to rise before it counts as a shore that
     * stops the vessel, in blocks. Below this it is the sea floor under deep water, which a ship
     * sails clean over; above it, it is land breaking the surface, which a ship runs aground on.
     */
    private static final double SHORE_CLEARANCE = 0.5;

    /** Ticks a correction from the server is spread over on a client that is not driving. */
    private static final int LERP_TICKS = 3;

    private final AnimatableInstanceCache animatableCache = GeckoLibUtil.createInstanceCache(this);

    /** The gun in the turret, if this vehicle has one. Fired on the server and nowhere else. */
    private final MainGun gun = new MainGun(this);
    /** The missiles in the tubes, if it carries any. Also the seeker, which looks all the time. */
    private final TurretLauncher launcher = new TurretLauncher(this);
    /**
     * What the reload counter read last tick, on whichever side is driving. The tick it goes up is
     * the tick the gun fired, which is how the shove of firing reaches the side that owns the speed
     * — the server cannot apply it to a vehicle a client is driving, because the client's next
     * report overwrites the speed a moment later.
     */
    private int reloadWas;

    /** The heading the driver steers, in Minecraft's terms: degrees, zero along +Z. */
    private float heading;
    /** How far the nose is above the tail, in degrees, as the ground under the tracks dictates. */
    private float hullPitch;
    /** How far the right-hand side is below the left, in degrees, from the same reading. */
    private float hullBank;

    /** Speed along the hull's nose, in blocks a tick. Negative is astern. */
    private float speed;

    /**
     * How fast the vehicle is falling, in blocks a tick, and never anything else: a ground vehicle
     * is either standing on something or on its way down to something, and its height is settled by
     * {@link #rest} rather than by anything colliding.
     */
    private double fallSpeed;

    /**
     * Whether a ship is floating on water right now, as {@link #settleOnWater} last found it.
     *
     * <p>A ship makes way only through the water: its screws have nothing to bite on out of it, so
     * one run aground on a beach or dropped on dry land sits where it is and cannot drive itself
     * off. This is what {@link #steer} and {@link #accelerate} read to decide whether the drivetrain
     * does anything. It means nothing to a vehicle that is not a ship, which never floats and never
     * asks. Read a tick after it is set, which is imperceptible and saves ordering the settle before
     * the drivetrain that has to happen after it.
     */
    private boolean afloat;

    /** Where the turret is pointing, in the hull's frame, and what it was a tick ago. */
    private float turretYaw;
    private float turretYawO;
    private float gunPitch;
    private float gunPitchO;

    /**
     * How far the vehicle has driven altogether, in blocks, which is what the road wheels are turned
     * from. Wrapped inside a single revolution so that it cannot lose precision over a long journey.
     */
    private float trackDistance;
    private float trackDistanceO;

    /** How far the steered wheels are turned, in degrees, and what it was a tick ago. */
    private float steerAngle;
    private float steerAngleO;

    private GroundVehicleInput input = GroundVehicleInput.NONE;

    // Interpolation state for instances that are not simulating this vehicle themselves.
    private int lerpSteps;
    private double lerpX;
    private double lerpY;
    private double lerpZ;
    private double lerpYRot;

    /** The figures under that name, and which set of files they came out of. */
    @Nullable
    private GroundVehicleDefinition stats;
    private int statsVersion = -1;

    public GroundVehicleEntity(EntityType<? extends GroundVehicleEntity> type, Level level) {
        super(type, level);
        this.blocksBuilding = true;
        // Built here rather than on the first tick: the level records an entity's parts when it is
        // added, and an entity that has none yet is remembered as having none.
        this.buildParts();
        // A new vehicle is a whole one, and an empty one: the gun and the tubes are both loaded out
        // of the hold by the vehicle's own crew and out of nothing else, so one put down with
        // nothing aboard it has nothing to fire until somebody loads it. See MainGun and
        // TurretLauncher. One read back out of the world overwrites this from its tag, and a client
        // is told the real figures with the rest of the synched data.
        this.setHealth(this.getMaxHealth());
        // We apply our own gravity in driveTick(), which is also what keeps the hull pressed against
        // a downslope. Telling the server that stops it counting the vehicle as a floating one.
        this.setNoGravity(true);
    }

    public GroundVehicleDefinition getStats() {
        GroundVehicleDefinition current = this.stats;

        if (current == null || this.statsVersion != Definitions.version()) {
            current = Definitions.VEHICLES.get(this.getVehicleId());
            this.stats = current;
            this.statsVersion = Definitions.version();
        }

        return current;
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ATTITUDE, new Quaternionf());
        builder.define(DATA_SPEED, 0.0F);
        builder.define(DATA_TURRET_YAW, 0.0F);
        builder.define(DATA_GUN_PITCH, 0.0F);
        builder.define(DATA_ROUNDS, 0);
        builder.define(DATA_RELOAD, 0);
        builder.define(DATA_MISSILES, 0);
        builder.define(DATA_MISSILE_RELOAD, 0);
        builder.define(DATA_MISSILE_MODE, false);
        builder.define(DATA_LOCK_TARGET, -1);
        builder.define(DATA_LOCK_PROGRESS, 0.0F);
        builder.define(DATA_STEER, 0.0F);
    }

    /** Rounds left for the main armament. */
    public int getRounds() {
        return this.entityData.get(DATA_ROUNDS);
    }

    public void setRounds(int rounds) {
        this.entityData.set(DATA_ROUNDS, Math.max(rounds, 0));
    }

    /** Ticks until the gun is loaded again; zero is ready to fire. */
    public int getReload() {
        return this.entityData.get(DATA_RELOAD);
    }

    public void setReload(int ticks) {
        this.entityData.set(DATA_RELOAD, Math.max(ticks, 0));
    }

    /** Whether there is a round up the spout and the crew may fire it. */
    public boolean isLoaded() {
        return this.getReload() <= 0 && this.getRounds() > 0;
    }

    /** How long the loader takes altogether, in ticks: what {@link #getReload} counts down from. */
    public int getReloadTicks() {
        return this.gun.reloadTicks();
    }

    /** How many rounds a full magazine holds. */
    public int getRoundCapacity() {
        return this.gun.capacity();
    }

    /** Missiles left in the tubes. */
    public int getMissiles() {
        return this.entityData.get(DATA_MISSILES);
    }

    public void setMissiles(int missiles) {
        this.entityData.set(DATA_MISSILES, Math.max(missiles, 0));
    }

    /** Ticks before the next missile may leave; zero is ready. */
    public int getMissileReload() {
        return this.entityData.get(DATA_MISSILE_RELOAD);
    }

    public void setMissileReload(int ticks) {
        this.entityData.set(DATA_MISSILE_RELOAD, Math.max(ticks, 0));
    }

    /** How many tubes a full load fills, and how long the crew take between launches. */
    public int getMissileCapacity() {
        return this.launcher.capacity();
    }

    public int getMissileReloadTicks() {
        return this.launcher.reloadTicks();
    }

    /** Whether this vehicle carries missiles at all. */
    public boolean hasMissiles() {
        return this.getStats().launcher().exists();
    }

    /**
     * Which armament the trigger fires.
     *
     * <p>Worked out rather than simply read, so that a vehicle carrying only one of the two never
     * has to be told which it is on: with no tubes the answer is the gun whatever the flag says, and
     * with no gun it is the missiles. Only a vehicle with both -- which is what an anti-aircraft
     * mounting is -- has anything to cycle, and only there does the flag mean anything.
     */
    public boolean isMissileMode() {
        if (!this.hasMissiles()) {
            return false;
        }

        if (!this.getStats().armament().exists()) {
            return true;
        }

        return this.entityData.get(DATA_MISSILE_MODE);
    }

    /** Steps to the other armament, for a vehicle with two. Nothing at all for one with a single. */
    public void cycleWeapon() {
        if (this.hasMissiles() && this.getStats().armament().exists()) {
            this.entityData.set(DATA_MISSILE_MODE, !this.entityData.get(DATA_MISSILE_MODE));
        }
    }

    /** What the seeker is holding, as every side sees it, or null for nothing. */
    @Nullable
    public Entity getSeekerTarget() {
        int id = this.entityData.get(DATA_LOCK_TARGET);

        return id < 0 ? null : this.level().getEntity(id);
    }

    /** How far along the lock is, from nothing to one. One is a lock; less is still trying. */
    public float getSeekerProgress() {
        return this.entityData.get(DATA_LOCK_PROGRESS);
    }

    /** Whether the seeker has taken its target, so a guided round would have somewhere to go. */
    public boolean isSeekerLocked() {
        return this.getSeekerProgress() >= 1.0F && this.entityData.get(DATA_LOCK_TARGET) >= 0;
    }

    /**
     * The seeker itself, which lives with the tubes because what it is looking for is what they
     * hold. Only ever meaningful on the server; every other side reads the three figures above.
     */
    @Override
    public TargetLock lock() {
        return this.launcher.lock();
    }

    /**
     * How far the barrel has run back, from nothing to one, at a moment between two ticks.
     *
     * <p>Worked out from the reload counter rather than sent, because the counter already says
     * everything: it is at its maximum the tick the gun fires and comes down from there, so how long
     * ago the gun fired is the one figure both are made of. Runs back the instant the round leaves
     * and eases out again over the recoil time, which is what a recoil looks like — the run-out is
     * the slow half.
     */
    public float getRecoil(float partialTick) {
        GroundVehicleDefinition.Armament armament = this.getStats().armament();
        int ticks = Math.max(armament.recoilTicks(), 1);
        int loading = this.gun.reloadTicks();
        float since = loading - (this.getReload() - partialTick);

        if (since < 0.0F || since >= ticks) {
            return 0.0F;
        }

        return 1.0F - since / ticks;
    }

    public GroundVehicleInput getInput() {
        return this.input;
    }

    public void setInput(GroundVehicleInput input) {
        this.input = input;
    }

    /** Speed along the hull's nose, in blocks a tick, on whichever side is asking. */
    public float getSpeed() {
        return this.isControlledByLocalInstance() ? this.speed : this.entityData.get(DATA_SPEED);
    }

    /**
     * How hard the wheel is over, from -1 to 1, on whichever side is asking.
     *
     * <p>The driving side knows its own input a tick before anybody else does; every other side is
     * told, which is the same figure a tick late rather than a guess. The same arrangement the speed
     * has, and for the same reason.
     */
    public float getSteerInput() {
        return this.isControlledByLocalInstance() ? this.input.steer() : this.entityData.get(DATA_STEER);
    }

    /**
     * How far the steered wheels are turned at a moment between two ticks, in degrees, positive to
     * the right. Always nothing on a vehicle whose file names no steered wheels, which is every
     * tracked one.
     */
    public float getSteerAngle(float partialTick) {
        return Mth.lerp(partialTick, this.steerAngleO, this.steerAngle);
    }

    /** How hard the engine is working, in [0, 1]: what the engine note is pitched from. */
    public float getEngineNote() {
        float max = Math.max(this.getStats().powertrain().maxSpeed(), STANDSTILL);

        return Mth.clamp(Math.abs(this.getSpeed()) / max, 0.0F, 1.0F);
    }

    /** Points the vehicle somewhere with no interpolation: for one being placed or read back in. */
    public void snapAttitude(float heading, float pitch, float bank) {
        this.heading = heading;
        this.hullPitch = pitch;
        this.hullBank = bank;
        this.attitude = this.buildAttitude();
        this.attitudeO = new Quaternionf(this.attitude);
        this.entityData.set(DATA_ATTITUDE, new Quaternionf(this.attitude));
        this.setYRot(heading);
        this.setXRot(-pitch);
        this.yRotO = heading;
        this.xRotO = -pitch;
    }

    /** Applied on the server from what the driving client reports. */
    public void reportState(Quaternionf hull, float travelSpeed, float turret, float gun) {
        if (this.level().isClientSide) {
            return;
        }

        this.attitude = new Quaternionf(hull).normalize();
        this.entityData.set(DATA_ATTITUDE, new Quaternionf(this.attitude));
        this.heading = Attitude.heading(this.attitude);
        this.hullPitch = -Attitude.elevation(this.attitude);
        this.hullBank = Attitude.bank(this.attitude);
        this.speed = travelSpeed;
        this.entityData.set(DATA_SPEED, travelSpeed);
        this.setTurret(turret, gun);
    }

    public float getTurretYaw(float partialTick) {
        return Mth.rotLerp(partialTick, this.turretYawO, this.turretYaw);
    }

    public float getGunPitch(float partialTick) {
        return Mth.lerp(partialTick, this.gunPitchO, this.gunPitch);
    }

    /**
     * Which way the bore is pointing, in the world.
     *
     * <p>Three rotations, in the order they are bolted together: the hull lies where the ground puts
     * it, the turret is traversed on the hull, and the gun is elevated in the turret. Applied on the
     * right of each other, so each acts in the frame the one before it left — which is what makes a
     * gun laid twenty degrees left of the bow stay twenty degrees left of it as the hull crosses a
     * slope, rather than swinging about in the world.
     */
    @Override
    public Vec3 getAimDirection(float partialTick) {
        return Attitude.nose(this.aim(partialTick));
    }

    private Quaternionf aim(float partialTick) {
        return new Quaternionf(this.getAttitude(partialTick))
                .rotateY(-this.getTurretYaw(partialTick) * DEG_TO_RAD)
                .rotateX(-this.getGunPitch(partialTick) * DEG_TO_RAD);
    }

    /**
     * Where the muzzle is, in the world.
     *
     * <p>Built from the trunnion and a length rather than from a fixed point, because the barrel
     * swings: the trunnion is a place on the turret and rides round with it, and the muzzle is a
     * barrel's length from there along whichever way the gun is currently laid. A single point would
     * be right at one elevation and wrong at every other.
     */
    public Vec3 getMuzzle(float partialTick) {
        GroundVehicleDefinition.Armament armament = this.getStats().armament();
        Vec3 trunnion = this.position().add(
                Attitude.toWorld(this.getAttitude(partialTick), this.onTurret(armament.trunnion(), partialTick)));

        return trunnion.add(this.getAimDirection(partialTick).scale(armament.barrelLength()));
    }

    /**
     * A point on the turret, in the vehicle's own axes, swung about the ring by however far the
     * turret is traversed.
     *
     * <p>Measured in the vehicle's axes, where x runs to the right and z over the bow, so bringing
     * the turret right carries a point that was ahead of the ring round towards the right-hand side.
     */
    private Vec3 onTurret(Vec3 offset, float partialTick) {
        Vec3 ring = this.getStats().turret().ring();
        Vec3 local = offset.subtract(ring);
        float radians = this.getTurretYaw(partialTick) * DEG_TO_RAD;
        double sin = Mth.sin(radians);
        double cos = Mth.cos(radians);

        return ring.add(new Vec3(
                local.x * cos + local.z * sin,
                local.y,
                -local.x * sin + local.z * cos));
    }

    /**
     * A point on the turret, in the world: swung about the ring by the traverse, then out through
     * the hull's attitude. For anything that rides the turret round rather than sitting on the
     * hull — the commander's eye, most of all, which looks out of a hatch in the turret roof and
     * goes wherever the turret goes. A vehicle with no turret has nothing to swing it about, and
     * the point is simply on the hull.
     */
    public Vec3 turretToWorld(Vec3 offset, float partialTick) {
        return this.toWorld(this.getStats().turret().exists() ? this.onTurret(offset, partialTick) : offset,
                partialTick);
    }

    /**
     * How far the road wheels have gone round, in degrees, at a moment between two ticks.
     *
     * <p>Worked out from distance rather than sent, because every side already knows how fast the
     * vehicle is going and a packet a tick to say where a wheel had got to would be a poor use of
     * one. Interpolated by winding the distance on rather than by blending two angles: a wheel on a
     * tank at speed turns most of a revolution a tick, and the angle is wrapped.
     */
    public float getWheelAngle(float partialTick) {
        float radius = Math.max(this.getStats().suspension().wheelRadius(), 0.05F);
        float distance = Mth.lerp(partialTick, this.trackDistanceO, this.trackDistance);

        return distance / (float) (2.0 * Math.PI * radius) * 360.0F;
    }

    /**
     * Copies what the seeker found into the synched data, so that every side can draw it.
     *
     * <p>Once a tick and unconditionally: the entity data only sends what has actually changed, so a
     * seeker sitting on the same target costs nothing, and one that has just lost it says so on the
     * tick it does rather than on the next sweep.
     */
    private void reportSeeker() {
        Entity target = this.launcher.lock().target();

        this.entityData.set(DATA_LOCK_TARGET, target == null ? -1 : target.getId());
        this.entityData.set(DATA_LOCK_PROGRESS, this.launcher.progress());
    }

    private void setTurret(float yaw, float pitch) {
        this.turretYaw = Mth.wrapDegrees(yaw);
        this.gunPitch = pitch;
        this.entityData.set(DATA_TURRET_YAW, this.turretYaw);
        this.entityData.set(DATA_GUN_PITCH, this.gunPitch);
    }

    // ------------------------------------------------------------------
    // What the base class needs from a ground vehicle
    // ------------------------------------------------------------------

    @Override
    public VehicleChassis.Hitbox hitbox() {
        return this.getStats().hitbox();
    }

    @Override
    public VehicleChassis.Sound soundSetup() {
        return this.getStats().sound();
    }

    /**
     * What the vehicle can see with, which for almost all of them is nothing.
     *
     * <p>A tank has no use for a radar: what it is fighting is on the ground, in among the trees,
     * and the way to find it is to look. What the field is here for is the machine that cannot fight
     * without one — a launcher, whose targets are several kilometres up and moving faster than
     * anybody can search for by eye.
     */
    @Override
    public VehicleChassis.Radar radar() {
        return this.getStats().radar();
    }

    /**
     * How fast it is really going, as a vector. Along the hull's nose and nothing else: a tracked
     * vehicle goes where it is pointing, and what little it does sideways is a skid that is gone
     * within a tick or two.
     */
    @Override
    public Vec3 getVelocity() {
        return this.headingVector().scale(this.getSpeed());
    }

    @Override
    protected float health() {
        return this.getStats().hull().health();
    }

    /**
     * A tank is armour, and every box of one is: the hull, the turret, the skirts and the running
     * gear alike. All of them are plate of some thickness, and a round arriving along any of them
     * is a round that may be thrown off — which is why a tank is fought by turning it to meet the
     * fire rather than by pointing it at whatever is shooting.
     */
    @Override
    public boolean isArmoured() {
        return true;
    }

    @Override
    public float armour() {
        return this.getStats().hull().armour();
    }

    @Override
    protected int declaredSalvage() {
        return this.getStats().hull().salvage();
    }

    @Override
    protected float explosionPower() {
        return this.getStats().hull().explosionPower();
    }

    @Override
    protected List<VehicleChassis.Seat> seats() {
        return this.getStats().hull().seats();
    }

    @Override
    protected VehicleChassis.CameraMount cameraMount() {
        return this.getStats().camera();
    }

    /**
     * A tank's eye is on the turret unless the seat says otherwise: it looks out of the turret roof
     * and comes round with the gun, so a crew laid off to one side see the ground out to that side.
     * A ship's is the other way — the bridge is bolted to the hull and the guns train away from
     * under it — so its view stays put as the turret swings.
     */
    @Override
    protected VehicleShape.Mount defaultEyeMount() {
        return this.getStats().isShip() ? VehicleShape.Mount.HULL : VehicleShape.Mount.TURRET;
    }

    @Override
    protected Vec3 eyeToWorld(VehicleShape.Mount mount, Vec3 eye, float partialTick) {
        return mount == VehicleShape.Mount.TURRET
                ? this.turretToWorld(eye, partialTick)
                : this.toWorld(eye, partialTick);
    }

    /**
     * Where the middle of a box is: where the file put it, swung about the turret ring if the box is
     * on the turret, and then out into the world through the hull's own attitude.
     */
    @Override
    protected Vec3 boxCentre(VehicleShape.Box box) {
        return this.position().add(Attitude.toWorld(this.attitude, this.mountOffset(box)));
    }

    /**
     * The rotation a box is standing at: the hull's attitude, then the turret's traverse if the box
     * is on it, then the box's own angle within whatever carries it.
     */
    @Override
    protected Quaternionf boxRotation(VehicleShape.Box box) {
        Quaternionf rotation = new Quaternionf(this.attitude);

        if (box.mount() == VehicleShape.Mount.TURRET) {
            rotation.rotateY(-this.turretYaw * DEG_TO_RAD);
        }

        return rotation.mul(box.orientation());
    }

    /** The footprint is worked out from the boxes, so it is only ever as current as they are. */
    @Override
    protected void onShapeChanged() {
        this.footprint = null;
    }

    /**
     * Where a box sits in the vehicle's own axes right now. One on the hull is where the file says
     * it is; one on the turret is swung about the ring by however far the turret is traversed.
     */
    private Vec3 mountOffset(VehicleShape.Box box) {
        return box.mount() == VehicleShape.Mount.TURRET
                ? this.onTurret(box.offset(), 1.0F)
                : box.offset();
    }

    /**
     * Puts every box where the hull, and the turret it may be on, have carried it to, lying the way
     * the hull and the turret have left it. What the vehicle is stopped by, stood on and hit by is
     * that box and nothing else — see {@code Hitbox}, which is the mod's own shape and none of
     * Minecraft's.
     */
    private void tickParts() {
        List<VehicleShape.Box> shape = this.getShape().boxes();

        for (int i = 0; i < this.parts.length; i++) {
            VehiclePart part = this.parts[i];

            if (i >= shape.size()) {
                // A box the file no longer describes, after a reload that shortened it. Folded
                // away inside the hull rather than left standing in the air where it was.
                part.fold(this.position());

                continue;
            }

            VehicleShape.Box box = shape.get(i);

            part.place(this.hitbox(box));
        }

        this.notePlacement();
        this.carryStanders();
    }

    // ------------------------------------------------------------------
    // The tick
    // ------------------------------------------------------------------

    @Override
    public void tick() {
        if (this.getHurtTime() > 0) {
            this.setHurtTime(this.getHurtTime() - 1);
        }

        super.tick();

        this.attitudeO = new Quaternionf(this.attitude);
        this.turretYawO = this.turretYaw;
        this.gunPitchO = this.gunPitch;
        this.trackDistanceO = this.trackDistance;

        if (this.isControlledByLocalInstance()) {
            // Nobody in the seat: the brake goes on and the vehicle stops where it is. A wreck is the
            // same case for good — there is no seat left to be in — and it coasts to a halt on its
            // own tracks rather than stopping dead where it was hit.
            if (this.isWrecked() || !(this.getControllingPassenger() instanceof Player)) {
                this.input = GroundVehicleInput.PARKED;
            }

            this.holdPosition();
            this.driveTick();

            if (!this.level().isClientSide) {
                this.entityData.set(DATA_SPEED, this.speed);
            }
        } else {
            this.tickLerp();
            this.attitude = new Quaternionf(this.entityData.get(DATA_ATTITUDE));
            this.heading = Attitude.heading(this.attitude);
            this.hullPitch = -Attitude.elevation(this.attitude);
            this.hullBank = Attitude.bank(this.attitude);
            this.speed = this.entityData.get(DATA_SPEED);
            this.turretYaw = this.entityData.get(DATA_TURRET_YAW);
            this.gunPitch = this.entityData.get(DATA_GUN_PITCH);
            // Wound on from the speed this side was told, which is the same figure the driving side
            // is turning its own wheels from.
            this.windTrack(this.speed);
        }

        // The gun is the server's business alone, the same way an aircraft's rounds are: a client
        // that could fire could conjure rounds out of nothing and claim hits with them. What the
        // client sends is the trigger; what comes back is the reload counter.
        if (!this.level().isClientSide) {
            // An empty vehicle holds no trigger down. The input is whatever the last driver reported
            // and it stops arriving the moment they climb out, so somebody who left mid-press would
            // otherwise leave the gun firing itself for as long as the tank sat there.
            if (this.getControllingPassenger() == null || this.isWrecked()) {
                this.input = GroundVehicleInput.PARKED;
            }

            // A burnt-out hull has no gun to reload, no seeker to look with, and nothing to lay
            // either of them at.
            if (!this.isWrecked()) {
                // The trigger goes to whichever armament the crew have selected and to that one
                // alone. The other still ticks: a gun goes on reloading whether or not it is the
                // thing being fired, and the seeker goes on looking whether or not the tubes are
                // selected -- which is what warns the aeroplane that it is being tracked. See
                // TurretLauncher.
                boolean missiles = this.isMissileMode();

                this.gun.tick(this.input.fire() && !missiles);
                this.launcher.tick(this.input.fire() && missiles);
                this.reportSeeker();
                this.getSensors().tick();
            }
        }

        // Everywhere, and from the driver rather than from the hull: see DATA_STEER. The server
        // publishes what it was told before anybody reads it, so that every side is working from the
        // one figure.
        if (!this.level().isClientSide) {
            this.entityData.set(DATA_STEER, this.input.steer());
        }

        this.windSteering();

        this.tickParts();
        this.checkInsideBlocks();
    }

    /**
     * One tick of driving: steer, then drive, then lie down on whatever is underneath.
     *
     * <p>The order matters. Steering decides the heading, the drivetrain decides the speed along it,
     * and only then is the hull read off the ground — because where the corners of the hull are
     * depends on which way it is now pointing and where it has just got to.
     */
    private void driveTick() {
        GroundVehicleDefinition definition = this.getStats();

        this.steer(definition.powertrain());
        this.accelerate(definition.powertrain(), definition.suspension());
        this.absorbRecoil(definition.armament());
        this.travel();

        // How the hull is held up is the one thing a ship does differently from a tank, and it is
        // the whole of the difference: a tank lies along the ground under its tracks, and a ship
        // rides at the waterline the sea under it settles it to. Everything above — steering, the
        // drivetrain, the recoil, the turret — is the same machinery working the same way.
        if (definition.isShip()) {
            this.settleOnWater(definition.buoyancy());
        } else {
            this.settleOnGround(definition.suspension());
        }

        this.attitude = this.buildAttitude();
        this.tickTurret(definition.turret());
        this.windTrack(this.speed);

        if (!this.level().isClientSide) {
            this.entityData.set(DATA_ATTITUDE, new Quaternionf(this.attitude));
        }
    }

    /**
     * Brings the hull round.
     *
     * <p>A tracked vehicle turns by driving one track harder than the other, so it turns fastest
     * standing still and least at speed — the opposite of a steered wheel, and the reason the rate
     * is read between two figures rather than being one. A vehicle that cannot pivot has nothing at
     * the standing-still end and so cannot turn until it is rolling, which is exactly right for one.
     *
     * <p><b>Which way it comes round depends on which way it is going</b>, and not only on which way
     * the driver is steering. Reversing with the wheel over to the right swings the tail to the
     * right and therefore the nose to the <em>left</em> — the opposite of what the same wheel does
     * going forwards, and what anybody who has reversed a car into a space is expecting. It is as
     * true of tracks as of tyres: asking for right is asking for the right-hand side to travel less
     * far than the left, and which end of the vehicle that carries round changes the moment the
     * tracks change direction.
     *
     * <p>Standing still is neither, and is left following the driver. A vehicle pivoting on the spot
     * has no direction of travel to take a sign from, and a lever pulled right should pivot it
     * right; the speed is only consulted once there is one.
     */
    private void steer(GroundVehicleDefinition.Powertrain powertrain) {
        // A ship out of the water cannot turn any more than it can drive: there is nothing under the
        // hull for the rudder or the screws to work against.
        if (this.getStats().isShip() && !this.afloat) {
            return;
        }

        if (this.input.steer() == 0.0F) {
            return;
        }

        float top = Math.max(powertrain.maxSpeed(), STANDSTILL);
        float fraction = Mth.clamp(Math.abs(this.speed) / top, 0.0F, 1.0F);
        float rate = Mth.lerp(fraction, powertrain.pivotRate(), powertrain.steerRate());
        // The magnitude is the speed's and the sign is the travel's. Taking the magnitude alone --
        // which is what the abs above leaves -- turns a reversing vehicle the way it would go
        // forwards, so backing round a corner steers the wrong way about.
        float astern = this.speed < -STANDSTILL ? -1.0F : 1.0F;
        float turn = this.input.steer() * rate * astern;

        this.heading = Mth.wrapDegrees(this.heading + turn);
        this.setYRot(this.heading);
        // The turret is aimed at the world, not at the hull, so it is wound back by whatever the
        // hull has just done -- whichever way that was. This is the whole of the stabiliser: without
        // it every turn of the hull drags the gun off the target and the gunner spends the fight
        // re-laying it.
        this.turretYaw = Mth.wrapDegrees(this.turretYaw - turn);
    }

    /**
     * Works the drivetrain, the brakes and gravity along the slope into one speed.
     *
     * <p>{@code drive} is a pedal rather than a throttle lever: it names the speed being asked for,
     * and letting go asks for nothing and lets the vehicle roll to a stop. Accelerating is the
     * engine's business and slowing is the brakes', so which of the two figures is being spent
     * depends on whether the vehicle is being asked to go faster or slower than it is going.
     */
    private void accelerate(GroundVehicleDefinition.Powertrain powertrain,
            GroundVehicleDefinition.Suspension suspension) {
        // A ship out of the water is going nowhere under its own power. The screws have nothing to
        // bite on, so the drivetrain gives it nothing and whatever way it still had comes off as the
        // hull grinds to a stop on the ground it has run onto.
        if (this.getStats().isShip() && !this.afloat) {
            this.speed = approach(this.speed, 0.0F, powertrain.braking());

            if (Math.abs(this.speed) < STANDSTILL) {
                this.speed = 0.0F;
            }

            return;
        }

        float target;
        float step;

        if (this.input.brake()) {
            target = 0.0F;
            step = powertrain.braking();
        } else if (this.input.drive() > 0.0F) {
            target = this.input.drive() * powertrain.maxSpeed();
            step = this.speed < target ? powertrain.acceleration() : powertrain.braking();
        } else if (this.input.drive() < 0.0F) {
            target = this.input.drive() * powertrain.reverseSpeed();
            step = this.speed > target ? powertrain.acceleration() : powertrain.braking();
        } else {
            target = 0.0F;
            step = powertrain.rollingResistance();
        }

        // Too steep to climb. The drivetrain has nothing left for the hill, so it stops pulling and
        // gravity below decides what happens next — which on a slope like that is sliding back down.
        float limit = holdableSlope(suspension);

        if (target > 0.0F && this.hullPitch > limit) {
            target = 0.0F;
        }

        if (target < 0.0F && this.hullPitch < -limit) {
            target = 0.0F;
        }

        this.speed = approach(this.speed, target, step);

        // Gravity along the slope, which is what makes a hill cost something to climb and gives one
        // back on the way down. Only what the vehicle is standing on counts: in the air there is no
        // slope to run down, and the fall is handled with the rest of the vertical. A ship is level
        // on flat water and has no slope to run down at all, so it is left out of this.
        if (this.onGround() && !this.getStats().isShip()) {
            double along = -Attitude.nose(this.attitude).y;
            this.speed += (float) (along * GRAVITY * powertrain.gradeResistance());
        }

        if (Math.abs(this.speed) < STANDSTILL) {
            this.speed = 0.0F;
        }
    }

    /**
     * The steepest the hull may read and still be driven, in degrees: the vehicle's slope limit,
     * plus the coarsest a reading of a slope can be wrong by.
     *
     * <p>A slope in this world is a staircase, and the four probes land wherever the risers put
     * them. Across a five-block contact patch, a forty-five degree staircase reads anything from
     * {@code atan(4/5)} to {@code atan(6/5)} — thirty-nine degrees to fifty — as the vehicle
     * crosses them, and easing the hull down on to it only narrows that ripple rather than removing
     * it. Compared to the degree, the limit therefore cuts the power on a good half of the ticks of
     * the very slope the vehicle's own figures say it can hold, and the vehicle stalls half way up.
     * One block over the contact patch is the whole of that error; allowing for it is what makes
     * {@code slope_limit} mean the slope it says.
     */
    private static float holdableSlope(GroundVehicleDefinition.Suspension suspension) {
        double span = Math.max(suspension.contactLength(), 1.0);
        double rise = Math.tan(Math.toRadians(Mth.clamp(suspension.slopeLimit(), 0.0F, 89.0F))) * span;

        return (float) Math.toDegrees(Math.atan2(rise + 1.0, span));
    }

    /**
     * The shove of firing, taken by whichever side is driving.
     *
     * <p>Only what runs along the hull counts. A gun laid over the bow pushes the tank straight
     * backwards; one laid abeam pushes it sideways, which a sixty-tonne hull on tracks simply does
     * not do — it rocks, and rocking is not something this hull models, so abeam it does nothing.
     *
     * <p>Read off the reload counter rather than told, because the tick that counter jumps up is the
     * tick the round left. The server cannot apply this itself to a vehicle a client is driving: the
     * client's next report carries its own speed and would overwrite it within the tick.
     */
    private void absorbRecoil(GroundVehicleDefinition.Armament armament) {
        int reload = this.getReload();
        boolean fired = reload > this.reloadWas;
        this.reloadWas = reload;

        if (!fired || armament.kick() <= 0.0F) {
            return;
        }

        double along = this.getAimDirection(1.0F).dot(this.headingVector());
        this.speed -= (float) (along * armament.kick());
    }

    /**
     * Moves the vehicle over the map: along its own nose at the speed the drivetrain settled on, and
     * sideways at whatever it has not yet stopped sliding.
     *
     * <p>Horizontally, and only horizontally. How high a ground vehicle is standing is not the
     * outcome of falling and colliding at all — it is standing <em>on</em> something, and where that
     * something is is what {@link #settleOnGround} measures with its own probes. So the two are
     * separated: this decides where over the ground the vehicle is, and that decides how high and at
     * what angle. Nothing here consults the plain box, and nothing here needs to.
     *
     * <p>The sideways part is the only momentum carried between ticks. Everything along the nose is
     * the drivetrain's and is decided afresh, but a hull that has just been swung round is still
     * travelling the way it was a moment ago, and how quickly that goes away is what tracks biting
     * into the ground means.
     */
    private void travel() {
        Vec3 forward = this.headingVector();
        Vec3 sideways = new Vec3(-forward.z, 0.0, forward.x);
        Vec3 was = this.getDeltaMovement();
        double drift = new Vec3(was.x, 0.0, was.z).dot(sideways)
                * (1.0 - Mth.clamp(this.getStats().suspension().grip(), 0.0F, 1.0F));

        Vec3 asked = forward.scale(this.speed).add(sideways.scale(drift));
        Vec3 allowed = this.getStats().isShip() ? this.limitAfloat(asked) : this.limitToShape(asked);

        this.horizontalCollision = allowed.x != asked.x || allowed.z != asked.z;
        this.setDeltaMovement(allowed.x, this.fallSpeed, allowed.z);
        this.setPos(this.getX() + allowed.x, this.getY(), this.getZ() + allowed.z);

        // What the world actually let it have. A hillside can take a good deal of a tick's travel
        // away between the drivetrain asking and the world agreeing, and a vehicle whose speed still
        // says thirty while it sits against a wall spins its wheels for ever and pins the engine
        // note at full.
        double covered = new Vec3(allowed.x, 0.0, allowed.z).dot(forward);

        if (Math.abs(covered) < Math.abs(this.speed)) {
            this.speed = (float) covered;
        }
    }

    /**
     * How far the vehicle may move before it runs into something.
     *
     * <p>The plain box is not consulted, and neither, in the end, is the sweep an aircraft uses.
     * Both answer the wrong question for a ground vehicle. Minecraft can only describe an entity as
     * an upright box with a square footprint, which for a seven-metre tank is a shed; and sweeping
     * the collision boxes instead fails for a subtler reason — a box is carried around in the
     * <em>upright</em> box drawn around it, and the upright box around an eight-block hull tilted
     * onto a hillside dips two blocks below the tracks and fills the wedge of hillside in front of
     * them. Swept against that, a tank cannot climb any slope at all: it is permanently colliding
     * with the hill it is standing on.
     *
     * <p>So what is asked instead is the question a driver would ask, at the corners of the shape
     * the vehicle really has: <em>does the ground under this corner rise faster than the vehicle can
     * climb?</em> A hillside raises it a little each tick and is driven up. A kerb raises it by a
     * step and is driven over, which is what {@code climb_height} means. A wall raises it by more
     * than the vehicle can manage and stops it, and so does the lip of a cliff coming the other way.
     * Nothing about it depends on how the hull happens to be lying, which is exactly the property
     * the swept box lacked.
     *
     * <p>The two axes are asked separately, so a vehicle driven at a wall at an angle keeps the
     * share of its movement that runs along the wall rather than stopping dead against it.
     */
    private Vec3 limitToShape(Vec3 movement) {
        if (movement.lengthSqr() == 0.0
                || this.level().isClientSide && !this.isControlledByLocalInstance()) {
            return movement;
        }

        Footprint footprint = this.footprint();

        if (footprint.isEmpty()) {
            return movement;
        }

        Vec3[] corners = footprint.corners(this.position(), this.headingVector());
        double[] before = new double[corners.length];

        for (int i = 0; i < corners.length; i++) {
            before[i] = this.groundUnder(corners[i]);
        }

        double climb = this.getStats().suspension().climbHeight();
        double x = this.canStep(new Vec3(movement.x, 0.0, 0.0), corners, before, footprint, climb)
                ? movement.x : 0.0;
        double z = this.canStep(new Vec3(0.0, 0.0, movement.z), corners, before, footprint, climb)
                ? movement.z : 0.0;

        return new Vec3(x, movement.y, z);
    }

    /**
     * Whether the vehicle can take one step: no corner of it meets ground rising faster than it can
     * climb, and no corner of it meets something hanging over that ground.
     *
     * <p>The second test is what the first cannot do. A wall shows up as ground that has risen,
     * because a probe dropped down the outside of one lands on top of it; a bridge deck at head
     * height does not show up at all, since the ground under it is the same ground the vehicle is
     * already on. So the headroom is looked at separately, from a step above the local ground up to
     * the top of the vehicle, and anything in that gap is something the hull would have hit.
     */
    private boolean canStep(Vec3 step, Vec3[] corners, double[] before, Footprint footprint, double climb) {
        if (step.lengthSqr() == 0.0) {
            return true;
        }

        for (int i = 0; i < corners.length; i++) {
            Vec3 to = corners[i].add(step);
            double ground = this.groundUnder(to);

            if (Double.isNaN(ground)) {
                // Nothing under that corner at all: a ditch or the lip of a cliff, which a vehicle
                // is free to drive over and, if it commits to it, to fall off.
                continue;
            }

            if (!Double.isNaN(before[i]) && ground - before[i] > climb + CLIMB_SLACK) {
                return false;
            }

            if (!this.hasHeadroom(to, ground, footprint.top(), climb)) {
                return false;
            }
        }

        return true;
    }

    /** Whether the gap above the ground at a point is tall enough for the vehicle to pass through. */
    private boolean hasHeadroom(Vec3 where, double ground, double top, double climb) {
        Vec3 from = new Vec3(where.x, ground + climb, where.z);
        Vec3 to = new Vec3(where.x, ground + top, where.z);

        if (to.y - from.y < HEADROOM_MARGIN) {
            return true;
        }

        return this.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, this)).getType() == HitResult.Type.MISS;
    }

    /**
     * The reach of the vehicle's shape, in its own axes: how wide it is, how far it stands out
     * ahead and behind, and how tall it is.
     *
     * <p>Taken from the collision boxes rather than from the plain box, so the corners the movement
     * is measured at are the corners of the thing itself. The turret counts towards the height and
     * not towards the rest of it: a gun laid abeam is not something a driver expects to be stopped
     * by, and a barrel that could catch on scenery would leave a tank wedged every time the crew
     * traversed near a wall.
     *
     * @param front how far the shape reaches over the bow, which for a tank is the glacis rather
     *              than the muzzle
     * @param back the same behind, as a negative number
     */
    private record Footprint(double halfWidth, double front, double back, double top) {
        static Footprint of(VehicleShape shape) {
            double halfWidth = 0.0;
            double front = 0.0;
            double back = 0.0;
            double top = 0.0;

            for (VehicleShape.Box box : shape.boxes()) {
                Vec3 half = box.size().scale(0.5);
                top = Math.max(top, box.offset().y + half.y);

                if (box.mount() == VehicleShape.Mount.TURRET) {
                    continue;
                }

                halfWidth = Math.max(halfWidth, Math.abs(box.offset().x) + half.x);
                front = Math.max(front, box.offset().z + half.z);
                back = Math.min(back, box.offset().z - half.z);
            }

            return new Footprint(halfWidth, front, back, top);
        }

        boolean isEmpty() {
            return this.halfWidth <= 0.0 || this.top <= 0.0;
        }

        /** The four corners of the footprint, in the world, at a position and a heading. */
        Vec3[] corners(Vec3 at, Vec3 forward) {
            Vec3 right = new Vec3(-forward.z, 0.0, forward.x);
            Vec3 nose = forward.scale(this.front);
            Vec3 tail = forward.scale(this.back);
            Vec3 side = right.scale(this.halfWidth);

            return new Vec3[] {
                    at.add(nose).subtract(side), at.add(nose).add(side),
                    at.add(tail).subtract(side), at.add(tail).add(side)};
        }
    }

    /**
     * The footprint under the shape as last read, and which set of files it came out of. Rebuilt
     * with the shape, because it is a handful of comparisons over every box and the movement asks
     * for it twice a tick.
     */
    @Nullable
    private Footprint footprint;

    private Footprint footprint() {
        VehicleShape shape = this.getShape();
        Footprint current = this.footprint;

        if (current == null) {
            current = Footprint.of(shape);
            this.footprint = current;
        }

        return current;
    }

    /**
     * Nothing moves this vehicle by its plain box, vanilla included. Anything that pushes a tank
     * about is resolved against the boxes it is really made of, exactly as its own drivetrain is.
     *
     * <p>With one exception, and it matters. A {@link MoverType#PLAYER} move is the driving client
     * reporting where it has <em>already got to</em>, arriving through vanilla's vehicle packet, and
     * the server is in no position to argue with it: it is a tick behind, its ground probes read the
     * terrain at the old position, and anything it refuses vanilla reads as the client having "moved
     * wrongly" — at which point vanilla puts the vehicle back where it was and sends the client a
     * correction. That is a tank that drives at a hillside and is quietly dragged backwards. The
     * driving side has already run this test against the ground it could actually see; running it
     * again here, worse informed, can only take movement away.
     *
     * <p>This is only half of what vanilla does with that report. The other half asks whether the
     * plain box is standing in clear air, which for a shape this one is not made of is the same
     * mistake asked a different way — see {@code VehicleMoveCheckMixin}, which stands it down.
     */
    @Override
    public void move(MoverType type, Vec3 movement) {
        Vec3 allowed = type == MoverType.SELF
                ? (this.getStats().isShip() ? this.limitAfloat(movement) : this.limitToShape(movement))
                : movement;

        this.setPos(this.getX() + allowed.x, this.getY() + allowed.y, this.getZ() + allowed.z);
        this.horizontalCollision = allowed.x != movement.x || allowed.z != movement.z;
    }

    /**
     * Reads the ground under the four corners of the tracks and lies the hull down on it.
     *
     * <p>Four probes rather than one, because one says nothing about which way the ground is
     * sloping. The front pair against the back pair is the pitch; the left pair against the right is
     * the roll. A corner hanging over a hole reads as nothing at all and is left out of both, so a
     * tank with its nose over a ditch tips forward rather than snapping level.
     *
     * <p>The result is eased into rather than taken: the ground under a vehicle changes by a whole
     * block the moment a corner crosses a step, and a hull that took every reading as it came would
     * shake itself to pieces crossing a ploughed field.
     */
    private void settleOnGround(GroundVehicleDefinition.Suspension suspension) {
        Vec3 forward = this.headingVector();
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x);
        double halfLength = suspension.contactLength() * 0.5;
        double halfWidth = suspension.contactWidth() * 0.5;

        Vec3 centre = this.position();
        double frontLeft = this.groundUnder(centre.add(forward.scale(halfLength)).subtract(right.scale(halfWidth)));
        double frontRight = this.groundUnder(centre.add(forward.scale(halfLength)).add(right.scale(halfWidth)));
        double rearLeft = this.groundUnder(centre.subtract(forward.scale(halfLength)).subtract(right.scale(halfWidth)));
        double rearRight = this.groundUnder(centre.subtract(forward.scale(halfLength)).add(right.scale(halfWidth)));

        // Nothing underneath at all reads as level, which is what a vehicle in the air should ease
        // towards: it has no reason to hold the angle of the hillside it has just left.
        float targetPitch = slopeAngle(mean(frontLeft, frontRight), mean(rearLeft, rearRight), halfLength * 2.0);
        float targetBank = slopeAngle(mean(frontLeft, rearLeft), mean(frontRight, rearRight), halfWidth * 2.0);

        float rate = Mth.clamp(suspension.settleRate(), 0.0F, 1.0F);
        this.hullPitch = Mth.lerp(rate, this.hullPitch, targetPitch);
        this.hullBank = Mth.lerp(rate, this.hullBank, targetBank);
        this.setXRot(-this.hullPitch);

        // The middle of the contact patch, which with the hull lying along the same plane is where
        // the vehicle's own origin belongs.
        this.rest(mean(mean(frontLeft, frontRight), mean(rearLeft, rearRight)), suspension);
    }

    /**
     * Sets how high the vehicle is standing, from the ground its tracks have just been measured
     * against.
     *
     * <p>This is where a ground vehicle differs most from everything else in the mod. An aeroplane
     * falls, and what it hits stops it; a tank is <em>standing on</em> something, and how high it is
     * standing is a question with an answer rather than the leftovers of a collision. So the height
     * is taken from the ground under the contact patch and nothing collides with anything — which is
     * also what makes it possible to leave the plain box out of the movement altogether, since the
     * plain box was only ever holding the vehicle up.
     *
     * <p>Three cases, and the step height tells them apart. Ground level with or above the vehicle
     * is a kerb, and it drives up it. Ground a little below is a slope, and it follows it down —
     * gravity would have it hover a moment and then slam, since a tank at speed descends a great
     * deal faster than anything falls in the first few ticks. Ground a long way below, or none at
     * all, is a drop, and there it falls like anything else.
     */
    private void rest(double support, GroundVehicleDefinition.Suspension suspension) {
        double climb = suspension.climbHeight();
        double rise = support - this.getY();

        if (Double.isNaN(support) || rise < -climb) {
            this.fall();

            return;
        }

        // Onto the step, or down the slope. Anything taller than the vehicle can climb was stopped
        // horizontally before it got here; the cap is what keeps a misread from throwing it up a
        // cliff face rather than stopping it against one.
        this.setPos(this.getX(), this.getY() + Math.min(rise, climb), this.getZ());
        this.fallSpeed = 0.0;
        this.setOnGround(true);
    }

    /** One tick of falling, held to a terminal speed so a vehicle off a cliff outruns nothing. */
    private void fall() {
        this.fallSpeed = Math.max(this.fallSpeed - GRAVITY, -MAX_FALL);
        this.setPos(this.getX(), this.getY() + this.fallSpeed, this.getZ());
        this.setOnGround(false);
    }

    /**
     * The height of the ground under a point, or {@link Double#NaN} if there is none within reach.
     *
     * <p>Traced rather than read off the heightmap, because a heightmap knows nothing about the
     * inside of a cave, a bridge or a road cut into a hillside, and a tank drives through all three.
     * The trace is short — a couple of blocks either side of the hull — and stays inside the
     * vehicle's own footprint, which is loaded by definition because the vehicle is standing in it.
     *
     * <p>It reaches further downwards the faster the vehicle is falling. Otherwise a vehicle off a
     * cliff at terminal speed crosses more ground in a tick than the probe can see, and passes
     * clean through the floor it was going to land on without the probe ever meeting it.
     */
    private double groundUnder(Vec3 where) {
        double below = PROBE_BELOW + Math.abs(this.fallSpeed);
        Vec3 from = new Vec3(where.x, this.getY() + PROBE_ABOVE, where.z);
        Vec3 to = new Vec3(where.x, this.getY() - below, where.z);
        BlockHitResult hit = this.level().clip(
                new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));

        return hit.getType() == HitResult.Type.MISS ? Double.NaN : hit.getLocation().y;
    }

    // ------------------------------------------------------------------
    // Being a ship instead
    // ------------------------------------------------------------------

    /**
     * Floats the hull at the waterline instead of lying it on the ground, which is the one place a
     * ship parts company with a tank.
     *
     * <p>What holds a vessel up is the water it displaces, and the whole of that is one balance: the
     * further the hull is pushed from the depth it floats at, the harder the water pushes it back,
     * and the bobbing that would leave is taken out by the damping so it settles rather than rings.
     * So the height here is not read off anything the way a tank's is — it is integrated, like an
     * aircraft's, but towards a resting line rather than under gravity. On flat water the hull sits
     * flat, so unlike a tank there is no slope to lie along and the attitude is only ever eased back
     * to level.
     *
     * <p>Off the water there is nothing to float on. A ship dropped in mid-air, or driven up onto a
     * beach, falls and rests on the ground exactly as a tank does — which is what grounds it on the
     * shore rather than letting it sail up the sand.
     */
    private void settleOnWater(GroundVehicleDefinition.Buoyancy buoyancy) {
        // Flat water holds no slope, so a ship always eases back to level rather than holding the
        // angle of whatever tipped it.
        float trim = Mth.clamp(buoyancy.trimRate(), 0.0F, 1.0F);
        this.hullPitch = Mth.lerp(trim, this.hullPitch, 0.0F);
        this.hullBank = Mth.lerp(trim, this.hullBank, 0.0F);
        this.setXRot(-this.hullPitch);

        double surface = this.waterSurfaceUnder(this.position());

        if (Double.isNaN(surface)) {
            // Not over water at all: beached, or dropped in the air. It falls like any hull, and the
            // ground stops it wherever it comes down — a shore, most likely, which is where a ship
            // driven at the land ends up. Out of the water it makes no way; see the field.
            this.afloat = false;
            this.rest(this.groundUnder(this.position()), this.getStats().suspension());

            return;
        }

        this.afloat = true;

        // The spring towards the resting draught, damped so the hull settles in a second or two.
        double restY = surface - buoyancy.draught();
        double error = restY - this.getY();
        this.fallSpeed += error * buoyancy.buoyancy();
        this.fallSpeed *= 1.0 - Mth.clamp(buoyancy.damping(), 0.0F, 1.0F);
        this.fallSpeed = Mth.clamp(this.fallSpeed, -MAX_FALL, MAX_FALL);

        this.setPos(this.getX(), this.getY() + this.fallSpeed, this.getZ());
        // Held up by the water, so to everything else it counts as being on the ground: it is not
        // falling, so it neither banks the fall that would write it off nor reads as airborne.
        this.setOnGround(true);
    }

    /**
     * The height of the surface of the water under a point, or {@link Double#NaN} if there is none
     * within reach.
     *
     * <p>Traced from just above the hull down through however far it might be falling, and the first
     * water met from above is the surface — its own block's fill height, so a ship rides on the top
     * of the water rather than on the block boundary under it. Nothing but water counts: a ship
     * floats on the sea and on nothing else.
     */
    private double waterSurfaceUnder(Vec3 where) {
        int x = Mth.floor(where.x);
        int z = Mth.floor(where.z);
        int top = Mth.floor(this.getY() + PROBE_ABOVE);
        int bottom = Mth.floor(this.getY() - (PROBE_BELOW + Math.abs(this.fallSpeed)));
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int y = top; y >= bottom; y--) {
            pos.set(x, y, z);
            FluidState fluid = this.level().getFluidState(pos);

            if (fluid.is(FluidTags.WATER)) {
                return y + fluid.getHeight(this.level(), pos);
            }
        }

        return Double.NaN;
    }

    /**
     * How far a ship may move before it runs aground, on the same terms a tank's movement is limited
     * on but asking the question a ship's way.
     *
     * <p>A tank is stopped by ground rising faster than it can climb; a ship is stopped only by land
     * that breaks the surface it is floating on, and sails clean over a sea floor however close it
     * comes. So the test is not how fast the ground rises but how high it stands: anything reaching
     * above the vessel's own waterline ahead of it is a shore, and anything below is the deep it is
     * meant to be over. The two axes are asked apart, so a ship driven at a coast at an angle keeps
     * the share of its way that runs along the shore.
     */
    private Vec3 limitAfloat(Vec3 movement) {
        if (movement.lengthSqr() == 0.0
                || this.level().isClientSide && !this.isControlledByLocalInstance()) {
            return movement;
        }

        Footprint footprint = this.footprint();

        if (footprint.isEmpty()) {
            return movement;
        }

        Vec3[] corners = footprint.corners(this.position(), this.headingVector());
        double x = this.clearAfloat(new Vec3(movement.x, 0.0, 0.0), corners) ? movement.x : 0.0;
        double z = this.clearAfloat(new Vec3(0.0, 0.0, movement.z), corners) ? movement.z : 0.0;

        return new Vec3(x, movement.y, z);
    }

    /** Whether no corner of the ship, stepped by {@code step}, would meet land above its waterline. */
    private boolean clearAfloat(Vec3 step, Vec3[] corners) {
        if (step.lengthSqr() == 0.0) {
            return true;
        }

        double waterline = this.getY();

        for (Vec3 corner : corners) {
            double ground = this.groundUnder(corner.add(step));

            if (!Double.isNaN(ground) && ground > waterline + SHORE_CLEARANCE) {
                return false;
            }
        }

        return true;
    }

    /**
     * Brings the turret round to where the crew are looking, as fast as it is able.
     *
     * <p>Both angles are held in the hull's frame and aimed in the world's, so what is worked out
     * here is the difference: where the gunner is looking, less where the hull is pointing. The
     * mechanical limits are the hull's — a gun can only depress so far into its own roof — so they
     * are applied after the hull's own pitch has been taken out and not before.
     */
    private void tickTurret(GroundVehicleDefinition.Turret turret) {
        if (!turret.exists()) {
            return;
        }

        if (!(this.getControllingPassenger() instanceof LivingEntity crew)) {
            return;
        }

        float wantYaw = Mth.wrapDegrees(crew.getYHeadRot() - this.heading);
        float wantPitch = Mth.clamp(-crew.getXRot() - this.hullPitch, -turret.depression(), turret.elevation());

        float yaw = approachAngle(this.turretYaw, wantYaw, turret.traverseRate());
        float pitch = approach(this.gunPitch, wantPitch, turret.elevationRate());

        this.setTurret(yaw, pitch);
    }

    /**
     * Swings the steered wheels towards where the driver is asking for, a little each tick.
     *
     * <p>Towards rather than to: a wheel that snapped from lock to lock in one tick would read as a
     * glitch rather than as steering, and the swing is most of what says the vehicle is being driven
     * rather than sliding. It follows the <em>driver</em> and not the hull, so a vehicle stopped with
     * the wheel held over shows it -- which a wheeled one has to, since it cannot turn at a stop and
     * so the hull says nothing about what the driver is doing.
     */
    private void windSteering() {
        VehicleChassis.Model model = this.getStats().model();

        this.steerAngleO = this.steerAngle;

        if (!model.isSteered()) {
            this.steerAngle = 0.0F;

            return;
        }

        float lock = model.steerLock();

        this.steerAngle = approach(this.steerAngle, this.getSteerInput() * lock, lock * STEER_SWING);
    }

    /** Winds the road wheels on by a tick's travel, kept inside one revolution. */
    private void windTrack(float travelled) {
        float circumference = (float) (2.0 * Math.PI * Math.max(this.getStats().suspension().wheelRadius(), 0.05F));

        this.trackDistance = (this.trackDistance + travelled) % circumference;
        // Wrapped on both sides of the join, so that reversing past zero does not leave the previous
        // distance a whole revolution away and the wheels spinning backwards for one frame.
        if (Math.abs(this.trackDistance - this.trackDistanceO) > circumference * 0.5F) {
            this.trackDistanceO = this.trackDistance;
        }
    }

    /** The hull's rotation, built from the heading the driver steers and the two angles the ground gives. */
    private Quaternionf buildAttitude() {
        return Attitude.of(this.heading, -this.hullPitch).rotateZ(this.hullBank * DEG_TO_RAD);
    }

    /** Which way the hull is pointing, flat on the ground, as a unit vector. */
    private Vec3 headingVector() {
        float radians = this.heading * DEG_TO_RAD;

        return new Vec3(-Mth.sin(radians), 0.0, Mth.cos(radians));
    }

    /** The angle of a slope, in degrees, from the height difference across a span. */
    private static float slopeAngle(double high, double low, double span) {
        if (Double.isNaN(high) || Double.isNaN(low) || span <= 0.0) {
            return 0.0F;
        }

        return (float) (Math.atan2(high - low, span) * (180.0 / Math.PI));
    }

    /** The average of two readings, or whichever of them is a reading at all. */
    private static double mean(double a, double b) {
        if (Double.isNaN(a)) {
            return b;
        }

        if (Double.isNaN(b)) {
            return a;
        }

        return (a + b) * 0.5;
    }

    private static float approach(float current, float target, float step) {
        if (step <= 0.0F) {
            return target;
        }

        return current < target
                ? Math.min(current + step, target)
                : Math.max(current - step, target);
    }

    /** The same, the short way round a circle. */
    private static float approachAngle(float current, float target, float step) {
        float difference = Mth.wrapDegrees(target - current);

        if (step <= 0.0F || Math.abs(difference) <= step) {
            return Mth.wrapDegrees(target);
        }

        return Mth.wrapDegrees(current + Math.signum(difference) * step);
    }

    // ------------------------------------------------------------------
    // Being driven by somebody else
    // ------------------------------------------------------------------

    /**
     * Holds the handover ready, for the tick the driver climbs out and this side stops driving.
     *
     * <p>Two things are left standing by a vehicle being driven here, and both of them are aimed at
     * where it was when the driver climbed <em>in</em>. The first is the lerp below, which is simply
     * not run while this side is driving and so keeps whatever it was last told. The second is
     * subtler and much the worse: a tracked entity's position arrives as a <em>delta</em> from the
     * one before it, both ends keeping their own copy of what that was, and vanilla throws away
     * every such packet for a vehicle the local player is driving — before decoding it, so the
     * client's copy is never wound on. It is only ever set outright by a whole position, and the
     * server sends one of those once every four hundred ticks.
     *
     * <p>None of that shows while the driving lasts, because this side is drawing the vehicle from
     * its own physics and the packets are being ignored. It shows the moment the driver gets out:
     * the next delta is decoded at last, against a mark that can be twenty seconds of driving stale,
     * and the vehicle is put back at the far end of that — which the crew see as the tank they have
     * just stepped out of vanishing off down the road they came up. Winding the mark on here every
     * tick costs one vector and leaves the handover working from a position one tick old at worst.
     */
    private void holdPosition() {
        this.lerpSteps = 0;
        this.syncPacketPositionCodec(this.getX(), this.getY(), this.getZ());
    }

    /**
     * Eases towards the last position the server sent, rather than snapping to it.
     *
     * <p>A vehicle nobody here is driving arrives as a position a tick, and each of those is a step
     * if it is simply taken. Spread over a few ticks it is a vehicle driving, which is what it
     * actually is.
     */
    private void tickLerp() {
        if (this.lerpSteps <= 0) {
            return;
        }

        double x = this.getX() + (this.lerpX - this.getX()) / this.lerpSteps;
        double y = this.getY() + (this.lerpY - this.getY()) / this.lerpSteps;
        double z = this.getZ() + (this.lerpZ - this.getZ()) / this.lerpSteps;
        float yaw = this.getYRot() + Mth.wrapDegrees((float) this.lerpYRot - this.getYRot()) / this.lerpSteps;

        this.lerpSteps--;
        this.setPos(x, y, z);
        this.setYRot(yaw);
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        this.lerpX = x;
        this.lerpY = y;
        this.lerpZ = z;
        this.lerpYRot = yRot;
        this.lerpSteps = LERP_TICKS;
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
    public float lerpTargetYRot() {
        return this.lerpSteps > 0 ? (float) this.lerpYRot : this.getYRot();
    }

    /**
     * Sits the crew's bodies facing the way the hull is facing, and leaves their heads alone.
     *
     * <p>Deliberately not what an aircraft does. An aircraft turns its passengers' view with the
     * airframe, because it is flown from the keys and the view may as well face where it is going. A
     * tank is aimed with the view: turning it with the hull would drag the gun off the target every
     * time the driver touched the sticks, which is precisely what the stabiliser in {@link #steer}
     * exists to prevent.
     */
    /**
     * The last of the crew getting out leaves the vehicle where it stands.
     *
     * <p>Not braking to a halt: stopped. A tank's rolling resistance is a rounding error and even
     * its brakes take a second and a half to pull it up from sixty-five kilometres an hour, which is
     * thirteen blocks of hull sailing off across the field while the driver stands watching it. That
     * is not what anybody means by getting out, and the vehicle turning up somewhere other than
     * where it was left is a bug however faithfully it was simulated.
     *
     * <p>The brake stays on afterwards as well — see {@link GroundVehicleInput#PARKED} — so it holds
     * on a slope rather than rolling away once gravity has had a moment to work on it.
     */
    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);

        if (this.getPassengers().isEmpty()) {
            this.speed = 0.0F;
            this.setDeltaMovement(0.0, this.getDeltaMovement().y, 0.0);

            if (!this.level().isClientSide) {
                this.entityData.set(DATA_SPEED, 0.0F);
            }
        }
    }

    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction moveFunction) {
        super.positionRider(passenger, moveFunction);
        passenger.setYBodyRot(this.getYRot());
    }

    @Override
    public void onPassengerTurned(Entity passenger) {
        passenger.setYBodyRot(this.getYRot());
    }

    /**
     * Puts somebody getting out down beside the vehicle, on the ground, clear of it.
     *
     * <p>What Minecraft does instead is drop them at the middle of the plain box's roof, and for
     * this vehicle that is the worst place there is: nothing collides with the plain box, so the
     * roof it names is a foot inside the turret. Left there the crew are standing inside a solid
     * part box — and a box cannot be clicked from within, because the pick asks where a line
     * <em>enters</em> a box and a line starting inside one never enters it. So the tank cannot be
     * climbed back into, which is a poor reward for getting out of it.
     *
     * <p>Four places are tried, in the order somebody would actually step: down the left side, down
     * the right, off the back, off the front. Each has to be clear of the world <em>and</em> of the
     * vehicle's own boxes, which is what {@code noCollision} checks for us since the parts are
     * ordinary solid entities to anyone who is no longer riding. Failing all four they go on the
     * roof — on top of the tallest box rather than inside it, which is at least somewhere to stand.
     */
    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        Footprint footprint = this.footprint();

        if (footprint.isEmpty()) {
            return super.getDismountLocationForPassenger(passenger);
        }

        Vec3 forward = this.headingVector();
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x);
        double clearance = passenger.getBbWidth() * 0.5 + DISMOUNT_MARGIN;
        Vec3[] beside = {
                right.scale(-(footprint.halfWidth() + clearance)),
                right.scale(footprint.halfWidth() + clearance),
                forward.scale(footprint.back() - clearance),
                forward.scale(footprint.front() + clearance)};

        for (Vec3 offset : beside) {
            Vec3 spot = this.position().add(offset);
            double ground = this.groundUnder(spot);

            if (Double.isNaN(ground)) {
                continue;
            }

            Vec3 candidate = new Vec3(spot.x, ground, spot.z);
            AABB room = passenger.getDimensions(passenger.getPose()).makeBoundingBox(candidate);

            if (this.level().noCollision(passenger, room)) {
                return candidate;
            }
        }

        return this.position().add(0.0, footprint.top(), 0.0);
    }

    // ------------------------------------------------------------------
    // Being handled
    // ------------------------------------------------------------------

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        // The client is in no position to judge any of this and must not try; see the same note on
        // AircraftEntity.interact. It says yes to everything and the server decides what happened.
        if (this.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }

        // Crouching means the hold, whatever is in the player's hand — the same click that opens
        // an aircraft's, since a crew member should not have to remember which sort of machine they
        // are standing at. Taking one to pieces is the wrench's job without the crouch.
        if (player.isSecondaryUseActive()) {
            this.openHold(player);

            return InteractionResult.CONSUME;
        }

        if (player.getItemInHand(hand).getItem() instanceof WrenchItem) {
            return this.dismantle(player);
        }

        if (!this.canAddPassenger(player)) {
            return InteractionResult.PASS;
        }

        // Forced, which here means only that the three seconds vanilla makes somebody wait after
        // getting out of a boat do not apply. That delay is for a vehicle you fall into by walking
        // over it, and it is nothing but an annoyance for one that has to be deliberately clicked:
        // the crew who just climbed out to look at something want to climb straight back in. The two
        // things force skips are checked above — nobody boards while crouching, and nobody boards a
        // full vehicle.
        return player.startRiding(this, true) ? InteractionResult.CONSUME : InteractionResult.PASS;
    }

    /** Folds the vehicle back into its item. Ground work, and not while anybody is aboard. */
    private InteractionResult dismantle(Player player) {
        // A wreck answers first and answers differently: there is no vehicle left to fold up, only a
        // hulk to clear away and the metal in it.
        if (this.isWrecked()) {
            return this.salvage();
        }

        if (!this.getPassengers().isEmpty() || Math.abs(this.speed) > STANDSTILL) {
            return InteractionResult.PASS;
        }

        // Whatever is in the hold stays the player's, and a vehicle folded up with a load inside it
        // would take the load with it.
        this.spillHold();
        this.destroy(this.getDropItem());

        return InteractionResult.CONSUME;
    }

    /**
     * A vehicle that has boxes of its own is not solid in its own right: the boxes are.
     *
     * <p>Minecraft gives an entity one upright box with a square footprint, and for a seven-metre
     * tank that is a shed — square whatever the hull is doing, and nowhere near the tracks once it
     * is across a slope. The boxes in the vehicle's own file are the real shape, so once there are any,
     * they do the work and the plain box stops pretending to.
     *
     * <p>Nothing is lost by standing down: {@link VehiclePart} passes hits, clicks and pick results
     * straight to the vehicle, so being shot, being climbed into and being stood on all still reach
     * here. A vehicle with no boxes of its own keeps its plain box, because otherwise it would have no
     * way of being touched at all.
     *
     * <p>With {@link #limitToShape} and {@link #rest} the plain box has nothing left to do: it is no
     * longer an obstacle, no longer a target, no longer what the vehicle runs into, and no longer
     * what holds it up. What remains of it is bookkeeping — which chunk section the vehicle is filed
     * in, and being gathered up by a region query — and {@link #getBoundingBoxForCulling} sees to it
     * that the drawing is not decided by it either.
     */
    @Override
    public boolean isPickable() {
        return !this.isRemoved() && this.parts.length == 0;
    }

    @Override
    public boolean canBeCollidedWith() {
        return !this.isRemoved() && this.parts.length == 0;
    }

    // ------------------------------------------------------------------
    // Persistence and GeckoLib
    // ------------------------------------------------------------------

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);

        float storedHeading = tag.contains("Heading") ? tag.getFloat("Heading") : this.getYRot();
        this.snapAttitude(storedHeading, tag.getFloat("HullPitch"), tag.getFloat("HullBank"));
        this.setHealth(tag.contains("Health") ? tag.getFloat("Health") : this.getMaxHealth());
        this.setTurret(tag.getFloat("TurretYaw"), tag.getFloat("GunPitch"));
        this.turretYawO = this.turretYaw;
        this.gunPitchO = this.gunPitch;
        this.gun.load(tag);
        this.launcher.load(tag);
        this.entityData.set(DATA_MISSILE_MODE, tag.getBoolean("MissileMode"));
        this.reloadWas = this.getReload();
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("Heading", this.heading);
        tag.putFloat("HullPitch", this.hullPitch);
        tag.putFloat("HullBank", this.hullBank);
        tag.putFloat("Health", this.getHealth());
        tag.putFloat("TurretYaw", this.turretYaw);
        tag.putFloat("GunPitch", this.gunPitch);
        this.gun.save(tag);
        this.launcher.save(tag);
        tag.putBoolean("MissileMode", this.isMissileMode());
    }

    /**
     * Nothing is played from an animation file. Everything a ground vehicle does with itself — the
     * turret, the gun, the road wheels — follows a figure the vehicle already knows from moment to
     * moment, and is posed in code by
     * {@link com.ashvehicles.client.model.GroundVehicleModel#setCustomAnimations}. Hatches and
     * anything else that is a sequence rather than an angle will want a controller here.
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.animatableCache;
    }
}
