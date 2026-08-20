package com.ashvehicles.entity;

import com.ashvehicles.particle.TintedParticleOption;
import com.ashvehicles.registry.ModParticles;
import com.ashvehicles.weapon.WeaponDefinition;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A flare or a cloud of chaff, thrown out behind an aircraft to be shot at instead of it.
 *
 * <p>One class for both, because they differ in almost nothing: what they are made of, how long they
 * last, and which sort of seeker is fooled by them. What they <em>do</em> is identical — fall away
 * behind the aeroplane and be more interesting than it for a few seconds.
 *
 * <p>It is a real entity rather than a puff of particles because a missile has to be able to chase
 * it. Everything a missile knows how to follow is an entity, so a decoy that is not one is a decoy
 * nothing can be decoyed by. See {@link RocketEntity} for the being-followed and
 * {@link com.ashvehicles.weapon.TargetLock} for the spoiling of a lock that has not been fired yet.
 *
 * <p><b>It asks the world for nothing.</b> No blocks, no fluids, no collisions: it is thrown at
 * altitude, usually over ground nobody has loaded, and every question it might ask out there would
 * be answered by generating terrain on the spot. So the ordinary entity tick is not run at all — it
 * falls, it ages, and it is gone.
 */
public class CountermeasureEntity extends Entity {
    /** Which sort this is. Sent, because the client draws the two of them quite differently. */
    private static final EntityDataAccessor<Boolean> DATA_FLARE =
            SynchedEntityData.defineId(CountermeasureEntity.class, EntityDataSerializers.BOOLEAN);

    /** How long each sort is worth chasing, in ticks. A flare burns out; chaff drifts and thins. */
    private static final int FLARE_LIFE = 80;
    private static final int CHAFF_LIFE = 120;

    /** How quickly it loses the speed it was thrown with, and how fast it falls. */
    private static final double DRAG = 0.88;
    private static final double GRAVITY = 0.03;

    /** Fire, for a flare: white at the core and orange at the edge. */
    private static final int FLAME = 0xFFF0C0;
    private static final int EMBER = 0xFF9A2E;
    /** And foil, for chaff, which is a great deal of very small bright nothing. */
    private static final int FOIL = 0xD8DCE0;

    /** Puffs a tick. A flare is one bright thing; chaff is a cloud, and a cloud needs numbers. */
    private static final int FLARE_PUFFS = 2;
    private static final int CHAFF_PUFFS = 5;
    /** How far a tick's worth of chaff is scattered from the middle of the cloud, in blocks. */
    private static final double CHAFF_SPREAD = 1.6;

    private int age;

    public CountermeasureEntity(EntityType<? extends CountermeasureEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /** Sets which sort this is. Called before it is added to the level. */
    public void setFlare(boolean flare) {
        this.entityData.set(DATA_FLARE, flare);
    }

    public boolean isFlare() {
        return this.entityData.get(DATA_FLARE);
    }

    /** Whether this is the sort of thing that sort of seeker follows instead of its target. */
    public boolean fools(WeaponDefinition.Guidance.Seeker seeker) {
        return seeker.foolLetsGoOfFlares() == this.isFlare();
    }

    /** How much of its life is left, from one down to nothing. What the drawing fades on. */
    public float remaining() {
        return 1.0F - Math.min(1.0F, (float) this.age / this.life());
    }

    private int life() {
        return this.isFlare() ? FLARE_LIFE : CHAFF_LIFE;
    }

    /**
     * Falls, ages, and is gone. Deliberately without {@code super.tick()}: what that spends its time
     * on is fire, fluids, portals and what the entity is standing in, none of which a burning flare
     * at three thousand feet has any use for, and all of which it would find out about by reading
     * blocks that are very often not loaded.
     */
    @Override
    public void tick() {
        Vec3 velocity = this.getDeltaMovement();

        this.xOld = this.getX();
        this.yOld = this.getY();
        this.zOld = this.getZ();
        this.setPos(this.getX() + velocity.x, this.getY() + velocity.y, this.getZ() + velocity.z);
        this.setDeltaMovement(velocity.scale(DRAG).subtract(0.0, GRAVITY, 0.0));

        if (this.level().isClientSide) {
            this.spawnSmoke();
        } else if (++this.age > this.life()) {
            this.discard();
        }
    }

    /** The burn, or the cloud. Thrown by the client so that one entity buys the whole effect. */
    private void spawnSmoke() {
        RandomSource random = this.random;
        float left = this.remaining();

        if (this.isFlare()) {
            // A flare is bright and small, and it is the brightness that a seeker is following.
            send(ModParticles.BLAST.get().of(FLAME, 0.5F + left * 0.5F), FLARE_PUFFS, 0.12, random);
            send(ModParticles.SPARK.get().of(EMBER, 1.0F), FLARE_PUFFS, 0.25, random);

            return;
        }

        // Chaff is the opposite: nothing bright at all, spread over as much sky as it can manage.
        send(ModParticles.DEBRIS.get().of(FOIL, 0.6F), CHAFF_PUFFS, CHAFF_SPREAD * (1.0 - left) + 0.4, random);
    }

    private void send(TintedParticleOption particle, int count, double spread, RandomSource random) {
        for (int i = 0; i < count; i++) {
            this.level().addParticle(particle,
                    this.getX() + random.nextGaussian() * spread,
                    this.getY() + random.nextGaussian() * spread,
                    this.getZ() + random.nextGaussian() * spread,
                    0.0, 0.0, 0.0);
        }
    }

    /**
     * Keeps falling wherever it is. One of these is thrown at altitude, a long way from anyone, and
     * an entity that stops ticking out there is a decoy that hangs in the sky forever.
     */
    @Override
    public boolean isAlwaysTicking() {
        return true;
    }

    /** Nothing shoots at it, walks into it, or reaches for it. It is smoke and fire. */
    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /** Drawn as far as it is sent: the whole point is watching a missile go for it instead. */
    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_FLARE, true);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.setFlare(tag.getBoolean("Flare"));
        this.age = tag.getInt("Age");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putBoolean("Flare", this.isFlare());
        tag.putInt("Age", this.age);
    }

    /** Not a thing anybody rides, and not a thing anything rides on. */
    @Override
    public boolean canBeHitByProjectile() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    /** Where it is, for a missile deciding whether to follow it. */
    public Vec3 middle() {
        return this.position();
    }

    /** Everything about it that another entity needs, without asking the world anything. */
    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean displayFireAnimation() {
        return false;
    }

    @Override
    public boolean isIgnoringBlockTriggers() {
        return true;
    }

    /** Never in the way of anything, including the aircraft that threw it. */
    @Override
    public boolean canCollideWith(Entity other) {
        return false;
    }
}
