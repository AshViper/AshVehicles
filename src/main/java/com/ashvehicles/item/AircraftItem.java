package com.ashvehicles.item;

import java.util.function.Supplier;

import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.vehicle.Attitude;

import net.minecraft.world.entity.EntityType;

/** 登録時に紐づけられた機体を、クリックしたブロックの上へプレイヤー向きで設置する。 */
public class AircraftItem extends VehicleItem<AircraftEntity> {
    public AircraftItem(Supplier<? extends EntityType<? extends AircraftEntity>> type, Properties properties) {
        super(type, properties);
    }

    /** 置いた向きのまま翼水平。エプロン上の機体とはそれだけのもの。 */
    @Override
    protected void point(AircraftEntity aircraft, float yaw) {
        aircraft.snapAttitude(Attitude.of(yaw, 0.0F));
    }
}
