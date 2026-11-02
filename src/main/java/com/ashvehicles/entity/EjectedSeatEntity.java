package com.ashvehicles.entity;

import com.ashvehicles.particle.Effects;
import com.ashvehicles.registry.ModParticles;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 乗員を押し出し終えた射出座席。ロケットが燃えている間は乗員と一緒に昇り、燃え尽きたら離れて落ちる。
 *
 * <p>乗員を座席に乗せては運ばない。乗せればプレイヤーの移動をサーバーが回すことになり、外す瞬間に
 * 位置の基準が飛ぶ（{@code driven-vehicle-position-base-goes-stale}）。両者は同じ速度で生まれ、同じ
 * 推力を同じ tick 数だけ受けるので、燃えている間は並んで飛ぶ。違うのは燃え尽きた後の抗力だけで、
 * 人は体を広げて減速し、重い座席は先へ落ちていく。
 *
 * <p>{@link CountermeasureEntity} と同じく、ロードされていない地面には何も訊かない。読めるチャンクの
 * 中でだけ地面に触れて消え、読めなければ寿命で消える。
 */
public class EjectedSeatEntity extends Entity {
    /** 落ちていく寿命（tick）。地面に着くか、これが尽きたら消える。 */
    private static final int LIFE = 400;

    /** 燃え尽きた座席の抗力と重力。人より重く、人より抗力が小さい。 */
    private static final double DRAG = 0.99;
    private static final double GRAVITY = 0.08;

    private Vec3 thrust = Vec3.ZERO;
    private int age;

    public EjectedSeatEntity(EntityType<? extends EjectedSeatEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /** 1tickあたりの推力。燃焼の間だけ足す。レベルへ追加する前に呼ぶ。 */
    public void setThrust(Vec3 thrust) {
        this.thrust = thrust;
    }

    /** ロケットがまだ燃えているか。描画が炎を出すかどうかもこれで決める。 */
    public boolean burning() {
        return this.age < EjectionSeat.ROCKET_TICKS;
    }

    @Override
    public void tick() {
        Vec3 velocity = this.getDeltaMovement();

        this.xOld = this.getX();
        this.yOld = this.getY();
        this.zOld = this.getZ();
        this.setPos(this.getX() + velocity.x, this.getY() + velocity.y, this.getZ() + velocity.z);

        if (this.burning()) {
            velocity = velocity.add(this.thrust);
        }

        this.setDeltaMovement(velocity.scale(DRAG).subtract(0.0, GRAVITY, 0.0));
        this.age++;

        if (this.level().isClientSide) {
            this.spawnExhaust();

            return;
        }

        BlockPos at = this.blockPosition();

        if (this.age > LIFE || (this.level().hasChunkAt(at) && !this.level().getBlockState(at).isAir())) {
            this.discard();
        }
    }

    /** 燃えている間は炎と煙、燃え尽きた後は薄い煙の筋。 */
    private void spawnExhaust() {
        RandomSource random = this.random;

        if (this.burning()) {
            for (int i = 0; i < 3; i++) {
                this.level().addParticle(ModParticles.BLAST.get().of(Effects.EMBER, 0.7F),
                        this.getX() + random.nextGaussian() * 0.15, this.getY() - 0.3,
                        this.getZ() + random.nextGaussian() * 0.15, 0.0, 0.0, 0.0);
            }

            this.level().addParticle(ModParticles.MOTOR_SMOKE.get().of(Effects.SOOT, 1.0F),
                    this.getX(), this.getY() - 0.4, this.getZ(), 0.0, 0.0, 0.0);
        } else if (this.age < EjectionSeat.ROCKET_TICKS + 30 && (this.age & 1) == 0) {
            this.level().addParticle(ModParticles.MOTOR_SMOKE.get().of(Effects.SOOT, 0.5F),
                    this.getX(), this.getY(), this.getZ(), 0.0, 0.0, 0.0);
        }
    }

    /** 高空で、たいてい誰もロードしていない地面の上に生まれる。tick が止まれば空に浮いたままになる。 */
    @Override
    public boolean isAlwaysTicking() {
        return true;
    }

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

    @Override
    public boolean canCollideWith(Entity other) {
        return false;
    }

    @Override
    public boolean canBeHitByProjectile() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean isIgnoringBlockTriggers() {
        return true;
    }

    @Override
    public boolean displayFireAnimation() {
        return false;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
