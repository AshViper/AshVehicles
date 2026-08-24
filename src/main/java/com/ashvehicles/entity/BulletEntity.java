package com.ashvehicles.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * One round from an aircraft's gun.
 *
 * <p>Fast, straight and short-lived. It is given its whole speed at the muzzle and does nothing
 * afterwards but fall, which is the whole of what a bullet does; everything else it needs is in
 * {@link VehicleProjectile}.
 */
public class BulletEntity extends VehicleProjectile {
    public BulletEntity(EntityType<? extends BulletEntity> type, Level level) {
        super(type, level);
    }

    /** Nothing steers a bullet. Gravity is applied for it after the move, as for everything else. */
    @Override
    protected void steer() {
    }
}
