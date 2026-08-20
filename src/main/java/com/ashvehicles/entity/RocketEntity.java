package com.ashvehicles.entity;

import javax.annotation.Nullable;

import com.ashvehicles.weapon.WeaponDefinition;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * A rocket, and — when its weapon file gives it guidance and the pilot had something locked when it
 * left — a missile.
 *
 * <p>The two are the same object because they are nearly the same thing. Both leave the rail slowly
 * and are pushed by a motor for a few seconds, which is why an aircraft briefly outruns its own
 * rockets after launch and why one fired from a standing start still gets going. Both coast
 * afterwards and both go off where they land. The only difference is that a missile bends its
 * flight path towards something while its motor burns.
 *
 * <p>What a missile can do is bounded, deliberately. It turns at a fixed number of degrees a tick,
 * so a target that turns harder than that will be missed; and it only follows what it can still see
 * ahead of it, so a target that gets behind it is lost and the missile flies on as a rocket. It also
 * steers under power only — once the motor is out it is ballistic, like everything else. None of
 * this homes unconditionally, which is the point: a missile should be beatable.
 */
public class RocketEntity extends AircraftProjectile implements GeoEntity {
    private final AnimatableInstanceCache animatableCache = GeckoLibUtil.createInstanceCache(this);

    /**
     * Who it is chasing. Synced so a client can draw the missile pointing where it is really going
     * rather than where it was when it launched.
     */
    private static final EntityDataAccessor<Integer> DATA_TARGET =
            SynchedEntityData.defineId(RocketEntity.class, EntityDataSerializers.INT);

    @Nullable
    private Entity target;
    /** True once the seeker has lost what it was chasing, so it stops looking. */
    private boolean lost;

    public RocketEntity(EntityType<? extends RocketEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_TARGET, -1);
    }

    /** Hands the missile what the pilot had locked. Without this it flies as an unguided rocket. */
    public void setTarget(@Nullable Entity target) {
        this.target = target;
        this.entityData.set(DATA_TARGET, target == null ? -1 : target.getId());
    }

    @Nullable
    public Entity getTarget() {
        if (this.target == null) {
            int id = this.entityData.get(DATA_TARGET);
            this.target = id < 0 ? null : this.level().getEntity(id);
        }

        return this.target;
    }

    /** Whether it is still under power, which is when it can both accelerate and steer. */
    public boolean isBurning() {
        return this.age <= this.getRound().burnTicks();
    }

    /** Under power there is a plume at the nozzle; coasting there is only the trail behind. */
    @Override
    protected boolean underPower() {
        return this.getRound().hasMotor() && this.isBurning();
    }

    @Override
    protected void steer() {
        WeaponDefinition.Projectile round = this.getRound();
        Vec3 velocity = this.getDeltaMovement();

        if (!this.isBurning() || !round.hasMotor()) {
            return;
        }

        Vec3 heading = velocity.lengthSqr() < 1.0E-8 ? new Vec3(0.0, 0.0, 1.0) : velocity.normalize();
        Vec3 wanted = this.guidedHeading(heading);

        // The motor pushes along the way it is pointing, up to the speed it will hold. Adding thrust
        // along the new heading rather than the old is what turns the missile: a rocket motor moves
        // its own nose, it does not slide the missile sideways.
        double speed = Math.min(velocity.length() + round.thrust(),
                round.topSpeed() > 0.0F ? round.topSpeed() : Double.MAX_VALUE);

        this.setDeltaMovement(wanted.scale(speed));
    }

    /**
     * Where the missile should be pointing this tick: at most {@code turn_rate} degrees off where it
     * is pointing now, bent towards the target. An unguided rocket, or one that has lost what it was
     * chasing, simply keeps its heading.
     */
    private Vec3 guidedHeading(Vec3 heading) {
        WeaponDefinition.Guidance guidance = this.getWeapon().guidance().orElse(null);
        Entity chasing = this.lost ? null : this.getTarget();

        if (guidance == null || chasing == null || !chasing.isAlive()) {
            return heading;
        }

        // Aim at the middle of it rather than its feet, and lead it: where it will be by the time
        // this arrives, not where it is now. A missile aimed at where something was always trails it.
        Vec3 middle = chasing.position().add(0.0, chasing.getBbHeight() * 0.5, 0.0);
        Vec3 gap = middle.subtract(this.position());
        double flightTicks = gap.length() / Math.max(this.getDeltaMovement().length(), 1.0E-3);
        Vec3 lead = middle.add(chasing.getDeltaMovement().scale(Math.min(flightTicks, 40.0)));
        Vec3 wanted = lead.subtract(this.position());

        if (wanted.lengthSqr() < 1.0E-8) {
            return heading;
        }

        wanted = wanted.normalize();

        // Anything it can no longer see ahead of it is gone for good. A seeker that could look
        // backwards would make the missile impossible to shake, which is not the intention.
        double off = Math.toDegrees(Math.acos(Math.min(1.0, Math.max(-1.0, wanted.dot(heading)))));

        if (off > guidance.trackAngle()) {
            this.lost = true;
            this.setTarget(null);

            return heading;
        }

        if (off <= guidance.turnRate()) {
            return wanted;
        }

        // Too far to reach this tick: turn as far as it can towards the target and no further.
        return this.turnTowards(heading, wanted, guidance.turnRate());
    }

    /** {@code heading} rotated {@code degrees} of the way towards {@code wanted}, in their own plane. */
    private Vec3 turnTowards(Vec3 heading, Vec3 wanted, float degrees) {
        Vec3 across = wanted.subtract(heading.scale(wanted.dot(heading)));

        if (across.lengthSqr() < 1.0E-10) {
            return heading;
        }

        across = across.normalize();
        double angle = Math.toRadians(degrees);

        return heading.scale(Math.cos(angle)).add(across.scale(Math.sin(angle))).normalize();
    }

    /**
     * A missile need not touch what it is chasing: it goes off when it gets as close as it is going
     * to get.
     *
     * <p>What matters here is that the test is made against the whole of this tick's flight rather
     * than against where the missile ends up. One of these crosses thirty blocks in a tick, so a
     * missile that passes clean through its target is a hundred blocks past it by the time anything
     * looks, and a fuse that only measured the end of the step would never once go off. Measuring
     * the nearest point of the segment catches the pass wherever in the tick it happened.
     */
    @Override
    @Nullable
    protected Vec3 earlyDetonation() {
        WeaponDefinition.Guidance guidance = this.getWeapon().guidance().orElse(null);
        Entity chasing = this.getTarget();

        if (guidance == null || chasing == null || !chasing.isAlive()) {
            return null;
        }

        // Where the target will be at the end of this tick, since it is moving too.
        Vec3 middle = chasing.position().add(chasing.getDeltaMovement())
                .add(0.0, chasing.getBbHeight() * 0.5, 0.0);
        Vec3 from = this.position();
        Vec3 to = from.add(this.getDeltaMovement());
        Vec3 nearest = nearestPointOn(from, to, middle);

        return nearest.distanceTo(middle) <= guidance.proximity() ? nearest : null;
    }

    /** The point of the segment {@code from}-{@code to} that lies closest to {@code target}. */
    private static Vec3 nearestPointOn(Vec3 from, Vec3 to, Vec3 target) {
        Vec3 along = to.subtract(from);
        double lengthSqr = along.lengthSqr();

        if (lengthSqr < 1.0E-8) {
            return from;
        }

        double t = Mth.clamp(target.subtract(from).dot(along) / lengthSqr, 0.0, 1.0);

        return from.add(along.scale(t));
    }

    /** A rocket that reaches the end of its life goes off rather than quietly disappearing. */
    @Override
    protected void expire() {
        if (this.getRound().explosion() > 0.0F && !this.level().isClientSide) {
            this.burst(this.position(), null);
        } else {
            this.discard();
        }
    }

    /**
     * No controllers: a missile has no animation to play. It is drawn from its geometry file and
     * turned to lie along its flight path by the renderer, and nothing about it moves on its own.
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.animatableCache;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.entityData.set(DATA_TARGET, tag.contains("Target") ? tag.getInt("Target") : -1);
        this.lost = tag.getBoolean("Lost");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Target", this.entityData.get(DATA_TARGET));
        tag.putBoolean("Lost", this.lost);
    }
}
