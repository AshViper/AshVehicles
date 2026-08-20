package com.ashvehicles.entity;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.aircraft.AircraftManager;
import com.ashvehicles.particle.TintedParticleOption;
import com.ashvehicles.registry.ModParticles;
import com.ashvehicles.weapon.WeaponDefinition;
import com.ashvehicles.weapon.WeaponEffects;
import com.ashvehicles.weapon.WeaponMounts;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import net.neoforged.neoforge.event.EventHooks;

/**
 * Anything an aircraft's weapons send on their way: a cannon round, a rocket, a missile.
 *
 * <p>What they have in common is everything except how they fly. Each knows which weapon fired it
 * and looks its figures up by name every tick, so a retuned file changes what is already in the air;
 * each is owned by the pilot, so what it kills is credited to them; each passes through the aircraft
 * that fired it, parts and passengers included, since it leaves already overlapping the wing; and
 * each does the same thing where it lands.
 *
 * <p>Movement is checked as a line rather than a point. At the speeds these travel they would
 * otherwise skip clean through anything thinner than a tick's flight.
 *
 * <p>Subclasses provide the flying: {@link #steer()} is their one tick of it, called before the
 * move, and whatever they leave in the delta movement is where this goes next.
 */
public abstract class AircraftProjectile extends Projectile {
    public static final ResourceKey<DamageType> DAMAGE_TYPE =
            ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "bullet"));

    /** The weapon that fired it, so a client knows what to draw and how it behaves. */
    private static final EntityDataAccessor<String> DATA_WEAPON =
            SynchedEntityData.defineId(AircraftProjectile.class, EntityDataSerializers.STRING);

    /**
     * The speed it left at, at full precision.
     *
     * <p>Sent as synched data because none of the packets that carry an entity's velocity can carry
     * this one: both the packet that spawns an entity and the one that corrects its motion clamp
     * every axis to 3.9 blocks a tick. That is fast for a snowball and a tenth of what leaves the
     * muzzle of a cannon, so a client is told 3.9, crawls forward that far, and is then dragged the
     * other forty by the next position packet — which is what a round warping across the sky is,
     * twenty times a second.
     *
     * <p>Told the truth instead, the client flies the round itself, at the right speed, and the
     * server's positions arrive to find it already there. See {@link #lerpTo}.
     */
    private static final EntityDataAccessor<Vector3f> DATA_LAUNCH =
            SynchedEntityData.defineId(AircraftProjectile.class, EntityDataSerializers.VECTOR3);

    /**
     * How far the server's idea of where a round is may differ from the client's before the client
     * is moved, in blocks.
     *
     * <p>There is normally nothing to correct: both sides fly the same round from the same figures
     * and arrive at the same place. What a tolerance buys is that the arithmetic never has to be
     * exactly identical — a round a metre out of place at forty blocks a tick is not something
     * anybody can see, and snapping it there is.
     */
    private static final double CORRECTION = 2.0;

    /** How far a round is drawn, in blocks. Past the furthest any of them is tracked. */
    private static final double RENDER_RANGE = 640.0;
    /** As many puffs as one tick of flight will ever be worth, however fast it is going. */
    private static final int MAX_PUFFS = 8;
    /** How much of the missile's speed a puff of trail is left holding. */
    private static final double TRAIL_DRIFT = 0.06;
    /** How far off the flight path a puff of trail is laid, in blocks. */
    private static final double TRAIL_SCATTER = 0.06;
    /** Puffs of plume a tick while the motor burns. */
    private static final int EXHAUST_PUFFS = 3;
    /** How far behind the missile the plume reaches, as a fraction of one tick's flight. */
    private static final double EXHAUST_REACH = 0.45;
    /** How hard the plume is blown backwards out of the nozzle, in blocks a tick. */
    private static final double EXHAUST_BLOW = 0.22;
    /** And how far off the nozzle it is laid, in blocks. */
    private static final double EXHAUST_SCATTER = 0.04;

    @Nullable
    private AircraftEntity firedFrom;
    private int firedFromId = -1;
    /** Ticks since it left, which is what its lifetime is measured against. */
    protected int age;
    /** The chunk this round is holding open ahead of itself, if it holds one. See {@link WeaponChunkLoader}. */
    @Nullable
    private ChunkPos heldChunk;
    /**
     * The step the round last took, which is the one it is being drawn along right now.
     *
     * <p>Kept because a round is drawn somewhere between where it was and where it is, and the step
     * that carries it between those two is the one that has already been taken. Pointing the model
     * along the step it is <em>about</em> to take instead puts its nose a tick ahead of its body,
     * which on anything that turns reads as a wobble. See {@link #travel}.
     */
    private Vec3 lastTravel = Vec3.ZERO;

    protected AircraftProjectile(EntityType<? extends AircraftProjectile> type, Level level) {
        super(type, level);
    }

    /**
     * @param weapon the weapon this came out of
     * @param aircraft what it was fired from, and so what it must not hit
     * @param pilot who pulled the trigger, if anyone
     */
    public void setup(ResourceLocation weapon, AircraftEntity aircraft, @Nullable Entity pilot) {
        this.entityData.set(DATA_WEAPON, weapon.toString());
        this.firedFrom = aircraft;
        this.firedFromId = aircraft.getId();
        this.setOwner(pilot);
    }

    public ResourceLocation getWeaponId() {
        ResourceLocation id = ResourceLocation.tryParse(this.entityData.get(DATA_WEAPON));

        return id != null ? id : ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "unknown");
    }

    public WeaponDefinition getWeapon() {
        return AircraftManager.weapon(this.getWeaponId());
    }

    public WeaponDefinition.Projectile getRound() {
        return this.getWeapon().projectile();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_WEAPON, "");
        builder.define(DATA_LAUNCH, new Vector3f());
    }

    /**
     * Sends the round on its way at the speed it leaves with.
     *
     * <p>Use this rather than setting the movement directly. It is the only thing that tells a
     * client how fast the round is really going; see {@link #DATA_LAUNCH}.
     */
    public void launch(Vec3 velocity) {
        this.setDeltaMovement(velocity);
        this.entityData.set(DATA_LAUNCH, velocity.toVector3f());
    }

    /** A client learns the real speed here, with the rest of the round's spawn data. */
    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);

        if (DATA_LAUNCH.equals(key) && this.level().isClientSide) {
            Vector3f launch = this.entityData.get(DATA_LAUNCH);

            this.setDeltaMovement(launch.x(), launch.y(), launch.z());
            // So the very first frame draws it lying along its flight rather than along nothing.
            this.lastTravel = this.getDeltaMovement();
        }
    }

    /**
     * What to make of the server's idea of where this round is.
     *
     * <p>Not simply moved there, which is what an entity ordinarily does. A round crosses up to
     * forty blocks in a tick, so being set to a position that is one tick stale is a forty-block
     * jump, and one that arrives a frame late is a forty-block jump back. The client is flying the
     * same round from the same figures and is already in the right place; a correction is only worth
     * taking when it says something the client did not already know.
     *
     * <p>The rotation is left alone entirely: it is worked out afresh from the flight path every
     * tick, on both sides, so there is nothing the server can usefully say about it.
     */
    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        if (this.position().distanceToSqr(x, y, z) > CORRECTION * CORRECTION) {
            this.setPos(x, y, z);
        }
    }

    /** One tick of flying, before the move. Whatever this leaves in the delta movement is taken. */
    protected abstract void steer();

    /**
     * A last look before the move, for anything that goes off without touching what it was aimed at.
     *
     * @return where it should go off, or null to fly on
     */
    @Nullable
    protected Vec3 earlyDetonation() {
        return null;
    }

    /**
     * Keeps flying whether or not there is a world underneath.
     *
     * <p>An aircraft holds open the one chunk it is over and no more, so anything it fires is out
     * over unloaded ground within a tick or two of leaving the rail. Vanilla stops ticking an entity
     * the moment its chunk stops ticking, and beyond the loaded world that is immediately: without
     * this a missile fired at altitude never moves again, and the weapons simply do not work out
     * there. Saying so here costs nothing — these are small, few and short-lived, and none of them
     * asks the world for anything it cannot answer.
     */
    @Override
    public boolean isAlwaysTicking() {
        return true;
    }

    @Override
    public void tick() {
        // Whether there is loaded ground under this tick's flight decides nearly everything below.
        // Out beyond it there is nothing to hit and nothing worth asking, and asking anyway is the
        // expensive mistake: on the server every block or fluid lookup out there generates the chunk
        // on the spot, on the main thread, so one missile crossing empty sky would carve a corridor
        // of new terrain thirty blocks a tick as it went.
        boolean overTheWorld = this.level().hasChunkAt(this.blockPosition());

        if (overTheWorld) {
            super.tick();
        } else {
            this.tickBeyondTheWorld();
        }

        WeaponDefinition.Projectile round = this.getRound();

        // Counted on both sides. It is not only the server's business how long a round has been
        // flying: how long a motor burns is measured against it, and the client is the one drawing
        // the plume and playing the note that stop when the motor does.
        this.age++;

        // Given up on once it has flown its range. What that means is the weapon's business: a round
        // simply stops existing, a rocket goes off. Only the server can make anything of it.
        if (!this.level().isClientSide && this.age > round.lifetime()) {
            this.expire();

            return;
        }

        // Steered on both sides, from the same figures, and before anything below is measured
        // against this tick's step. The server owns the flight, but a client that only coasts is
        // drawing a rocket that does not accelerate and a missile that does not turn, and is then
        // dragged back onto the real path by every position packet that arrives. Running the same
        // steering here leaves nothing to drag: the two agree between packets, and the round goes
        // where it is drawn going.
        this.steer();

        if (!this.level().isClientSide) {
            Vec3 fuse = this.earlyDetonation();

            if (fuse != null) {
                this.burst(fuse, null);

                return;
            }

            HitResult hit = this.strike();

            if (hit != null && hit.getType() != HitResult.Type.MISS && !EventHooks.onProjectileImpact(this, hit)) {
                this.onHit(hit);

                if (this.isRemoved()) {
                    return;
                }
            }
        } else {
            this.spawnTrail();
        }

        Vec3 velocity = this.getDeltaMovement();
        Vec3 next = this.position().add(velocity);
        this.setPos(next.x, next.y, next.z);
        this.lastTravel = velocity;
        this.setDeltaMovement(velocity.subtract(0.0, round.gravity(), 0.0));
        this.updateRotation();

        // Last, so the claim is made from where the round has got to rather than from where it was.
        // Only from here: taking a ticket re-enters the chunk system, which is safe from a tick and
        // is not safe from the callbacks it makes. See WeaponChunkLoader.
        this.heldChunk = WeaponChunkLoader.update(this, this.heldChunk);
    }

    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        // Let go whether the round went off, ran out of life or was merely unloaded with the rest of
        // its chunk. Only let go: this also fires from inside the chunk system's own update loop,
        // and asking for a chunk in there re-enters that loop mid-iteration.
        this.heldChunk = WeaponChunkLoader.release(this, this.heldChunk);
    }

    /**
     * The little of an entity's ordinary tick that still means anything where there is no world.
     *
     * <p>What the base class spends its tick on is portals, fluids, fire and what it is standing in,
     * none of which exists out here, and all of which it finds out about by reading blocks at the
     * projectile's own position — which out here is not a cheap question but an expensive one, see
     * {@link #tick}. So it is not called, and the two or three lines that do still matter are done
     * by hand instead.
     */
    private void tickBeyondTheWorld() {
        this.xRotO = this.getXRot();
        this.yRotO = this.getYRot();
        this.firstTick = false;
        this.checkBelowWorld();
    }

    /**
     * What this tick's flight runs into, as a line rather than a point: at these speeds a projectile
     * would otherwise skip clean through anything thinner than a tick's travel.
     *
     * <p>Blocks are only asked about when the whole of the step is over loaded ground. Past that
     * edge the terrain has not necessarily been generated, so there is nothing there to hit that
     * anyone has ever seen, and the asking would generate it. Entities are still checked either way:
     * an aircraft out there is loaded, whatever the ground under it is doing, and is exactly what
     * something fired at long range is aimed at.
     */
    @Nullable
    private HitResult strike() {
        Vec3 from = this.position();
        Vec3 to = from.add(this.getDeltaMovement());

        if (this.spanIsLoaded(from, to)) {
            return ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
        }

        return ProjectileUtil.getEntityHitResult(this.level(), this, from, to,
                this.getBoundingBox().expandTowards(this.getDeltaMovement()).inflate(1.0),
                this::canHitEntity);
    }

    /** Whether every chunk the given step passes over is loaded. */
    private boolean spanIsLoaded(Vec3 from, Vec3 to) {
        AABB span = new AABB(from, to).inflate(1.0);

        return this.level().hasChunksAt(Mth.floor(span.minX), Mth.floor(span.minZ),
                Mth.ceil(span.maxX), Mth.ceil(span.maxZ));
    }

    /**
     * Which way the round is pointing at this instant between two ticks.
     *
     * <p>Not simply its velocity. A round is drawn along the line from where it was to where it is,
     * and what carried it along that line is the step it took last tick, not the one it will take
     * next. Blending from one to the other across the frame keeps the nose on the line the body is
     * travelling and turns it smoothly rather than in twenty steps a second.
     *
     * @param partialTick how far through the current tick the frame is
     */
    public Vec3 travel(float partialTick) {
        Vec3 next = this.getDeltaMovement();

        if (this.lastTravel.lengthSqr() < 1.0E-8) {
            return next;
        }

        return this.lastTravel.add(next.subtract(this.lastTravel).scale(partialTick));
    }

    /** What happens when it simply runs out of life. A round vanishes; a missile need not. */
    protected void expire() {
        this.discard();
    }

    /** Whether the motor is still pushing, which is when there is a plume as well as a trail. */
    protected boolean underPower() {
        return false;
    }

    /**
     * Smoke behind a motor, drawn by each client for itself rather than sent one puff at a time.
     *
     * <p>It is laid along the path actually flown rather than dropped one puff a tick. A missile
     * covers twenty or thirty blocks between ticks, so a puff a tick is not a trail at all — it is a
     * row of dots with the whole of the interesting part in the gaps. Spacing them by distance
     * instead means the trail is the same density whether the missile is off the rail at walking
     * pace or running at its top speed, which is also the only way the trail behind an accelerating
     * one looks like anything.
     */
    protected void spawnTrail() {
        WeaponDefinition.Trail trail = this.getRound().trail().orElse(null);
        Vec3 step = this.getDeltaMovement();
        double flown = step.length();

        if (trail == null || flown < 1.0E-4) {
            return;
        }

        Vec3 head = this.position();
        RandomSource random = this.random;
        int puffs = Mth.clamp(Mth.ceil(flown * trail.density()), 1, MAX_PUFFS);
        // Once that cap bites — and at thirty blocks a tick it always does — what is left has to
        // cover more ground each, so it is made bigger. Which is what fast smoke does anyway: the
        // quicker it is laid down the more of it ends up in one place.
        float spread = (float) Mth.clamp(flown / puffs * 0.5, 1.0, 3.0);
        TintedParticleOption smoke = ModParticles.CONTRAIL.get().of(trail.colour(), trail.size() * spread);

        for (int i = 0; i < puffs; i++) {
            // Spread over the step with a little jitter, so successive ticks do not lay their puffs
            // down in the same places and turn the trail into a picket fence.
            Vec3 at = head.subtract(step.scale((i + random.nextDouble()) / puffs));
            // Each puff keeps a trace of the missile's own speed and then stops, which is what makes
            // the trail look drawn out behind it rather than printed there.
            Vec3 drift = step.scale(TRAIL_DRIFT / flown);

            this.level().addParticle(smoke,
                    at.x + random.nextGaussian() * TRAIL_SCATTER,
                    at.y + random.nextGaussian() * TRAIL_SCATTER,
                    at.z + random.nextGaussian() * TRAIL_SCATTER,
                    drift.x, drift.y, drift.z);
        }

        if (!this.underPower()) {
            return;
        }

        // And while the motor is burning there is the plume as well: what is coming out of the
        // nozzle this instant, close behind and still going the way the missile is.
        TintedParticleOption exhaust = ModParticles.MOTOR_SMOKE.get().of(trail.exhaust(), trail.size());

        for (int i = 0; i < EXHAUST_PUFFS; i++) {
            Vec3 at = head.subtract(step.scale(random.nextDouble() * EXHAUST_REACH));
            Vec3 blown = step.scale(-EXHAUST_BLOW / flown);

            this.level().addParticle(exhaust,
                    at.x + random.nextGaussian() * EXHAUST_SCATTER,
                    at.y + random.nextGaussian() * EXHAUST_SCATTER,
                    at.z + random.nextGaussian() * EXHAUST_SCATTER,
                    blown.x, blown.y, blown.z);
        }
    }

    /**
     * Not the aircraft it left, nor anything aboard it. The pilot is excluded by the owner check
     * already, but a passenger or a wing box is not, and this starts life inside the wing.
     */
    @Override
    protected boolean canHitEntity(Entity target) {
        AircraftEntity aircraft = this.firedFrom();

        if (aircraft != null && WeaponMounts.isPartOf(aircraft, target)) {
            return false;
        }

        return super.canHitEntity(target);
    }

    @Nullable
    protected AircraftEntity firedFrom() {
        if (this.firedFrom == null && this.firedFromId >= 0
                && this.level().getEntity(this.firedFromId) instanceof AircraftEntity aircraft) {
            this.firedFrom = aircraft;
        }

        return this.firedFrom;
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        super.onHitEntity(hit);
        DamageSource source = new DamageSource(
                this.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(DAMAGE_TYPE),
                this, this.getOwner());

        hit.getEntity().hurt(source, this.getRound().damage());
        this.burst(hit.getLocation(), null);
    }

    @Override
    protected void onHitBlock(BlockHitResult hit) {
        super.onHitBlock(hit);
        this.burst(hit.getLocation(), this.level().getBlockState(hit.getBlockPos()));
    }

    /**
     * What it does where it lands: the fire and the smoke and whatever it knocked off, and a blast
     * if it carries one.
     *
     * <p>The two are separated on purpose. Out beyond the loaded world the blast itself does not
     * happen — it would be an explosion nobody asked for, tearing up ground that has not been
     * generated yet, and generating it on the server thread in order to do so. But it is still seen:
     * the effect goes out to everyone within range whether or not there is a world under it, because
     * a pilot who has just put a missile into something four hundred blocks away has every right to
     * watch it go off, and a bang with nothing to show for it is the same as a miss.
     */
    protected void burst(Vec3 where, @Nullable BlockState struck) {
        WeaponDefinition.Projectile round = this.getRound();

        if (this.level() instanceof ServerLevel level) {
            WeaponEffects.detonation(level, where, round, struck);

            if (round.explosion() > 0.0F && level.hasChunkAt(BlockPos.containing(where))) {
                WeaponEffects.blast(level, this, where, round);
            }
        }

        this.discard();
    }

    /**
     * Drawn as far out as anything is ever sent. There is no point being stricter here than the
     * server is: it stops reporting a round at its own tracking range, and everything that does
     * arrive is worth drawing. A tracer or a missile is meant to be seen from a long way off.
     */
    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < RENDER_RANGE * RENDER_RANGE;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.entityData.set(DATA_WEAPON, tag.getString("Weapon"));
        this.age = tag.getInt("Age");
        this.firedFromId = tag.contains("FiredFrom") ? tag.getInt("FiredFrom") : -1;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("Weapon", this.entityData.get(DATA_WEAPON));
        tag.putInt("Age", this.age);
        tag.putInt("FiredFrom", this.firedFromId);
    }
}
