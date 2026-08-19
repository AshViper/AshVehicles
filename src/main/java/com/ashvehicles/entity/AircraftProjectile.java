package com.ashvehicles.entity;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.aircraft.AircraftManager;
import com.ashvehicles.weapon.WeaponDefinition;
import com.ashvehicles.weapon.WeaponMounts;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
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

    @Nullable
    private AircraftEntity firedFrom;
    private int firedFromId = -1;
    /** Ticks since it left, which is what its lifetime is measured against. */
    protected int age;

    protected AircraftProjectile(EntityType<? extends AircraftProjectile> type, Level level) {
        super(type, level);
        this.noCulling = true;
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

    @Override
    public void tick() {
        super.tick();
        WeaponDefinition.Projectile round = this.getRound();

        if (!this.level().isClientSide) {
            // Given up on once it has flown its range. What that means is the weapon's business: a
            // round simply stops existing, a rocket goes off.
            if (++this.age > round.lifetime()) {
                this.expire();

                return;
            }

            // Leaving loaded ground is a different matter and is never worth a bang. One frozen in
            // an unloaded chunk is one that will hit someone next spring, so it goes; but going off
            // out there would be an explosion nobody asked for, in a place nobody can see, possibly
            // in the middle of somebody's house.
            if (!this.level().hasChunkAt(this.blockPosition())) {
                this.discard();

                return;
            }

            this.steer();

            Vec3 fuse = this.earlyDetonation();

            if (fuse != null) {
                this.burst(fuse, null);

                return;
            }

            HitResult hit = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);

            if (hit.getType() != HitResult.Type.MISS && !EventHooks.onProjectileImpact(this, hit)) {
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
        this.setDeltaMovement(velocity.subtract(0.0, round.gravity(), 0.0));
        this.updateRotation();
    }

    /** What happens when it simply runs out of life. A round vanishes; a missile need not. */
    protected void expire() {
        this.discard();
    }

    /** Smoke behind a motor, drawn by each client for itself rather than sent one puff at a time. */
    protected void spawnTrail() {
        if (!this.getRound().trail()) {
            return;
        }

        Vec3 back = this.position().subtract(this.getDeltaMovement().scale(0.5));
        this.level().addParticle(ParticleTypes.SMOKE, back.x, back.y, back.z, 0.0, 0.01, 0.0);
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

    /** What it does where it lands: a puff of whatever it hit, and a blast if it carries one. */
    protected void burst(Vec3 where, @Nullable BlockState struck) {
        WeaponDefinition.Projectile round = this.getRound();

        if (this.level() instanceof ServerLevel level) {
            if (struck != null && !struck.isAir()) {
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, struck),
                        where.x, where.y, where.z, 6, 0.1, 0.1, 0.1, 0.05);
            } else {
                level.sendParticles(ParticleTypes.CRIT, where.x, where.y, where.z, 4, 0.1, 0.1, 0.1, 0.1);
            }
        }

        if (round.explosion() > 0.0F) {
            this.level().explode(this, where.x, where.y, where.z, round.explosion(),
                    round.fire(), Level.ExplosionInteraction.MOB);
        }

        this.discard();
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        // Small, but a tracer or a missile is meant to be seen from a long way off.
        return distance < 256.0 * 256.0;
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
