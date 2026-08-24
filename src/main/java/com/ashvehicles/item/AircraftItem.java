package com.ashvehicles.item;

import java.util.function.Supplier;

import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.vehicle.Attitude;

import net.minecraft.world.entity.EntityType;

/** Places the aircraft it was registered with onto the clicked block, facing the player. */
public class AircraftItem extends VehicleItem<AircraftEntity> {
    public AircraftItem(Supplier<? extends EntityType<? extends AircraftEntity>> type, Properties properties) {
        super(type, properties);
    }

    /** Wings level on whatever heading it was put down on, which is all an aeroplane on the apron is. */
    @Override
    protected void point(AircraftEntity aircraft, float yaw) {
        aircraft.snapAttitude(Attitude.of(yaw, 0.0F));
    }
}
