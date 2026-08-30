package com.ashvehicles.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * 機体の機銃から出る弾1発。
 *
 * <p>速く、真っ直ぐで、短命。砲口で全速度を与えられ、その後は落ちる以外に何もしない。弾がすることはそれが
 * 全部で、必要な残りは {@link VehicleProjectile} にある。
 */
public class BulletEntity extends VehicleProjectile {
    public BulletEntity(EntityType<? extends BulletEntity> type, Level level) {
        super(type, level);
    }

    /** 弾を誘導する物は無い。重力は他と同じく移動後に適用される。 */
    @Override
    protected void steer() {
    }
}
