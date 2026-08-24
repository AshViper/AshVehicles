package com.ashvehicles.item;

import java.util.function.Supplier;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.GroundVehicleEntity;

import net.minecraft.world.entity.EntityType;

/** Places the ground vehicle it was registered with onto the clicked block, facing the player. */
public class GroundVehicleItem extends VehicleItem<GroundVehicleEntity> {
    public GroundVehicleItem(Supplier<? extends EntityType<? extends GroundVehicleEntity>> type,
            Properties properties) {
        super(type, properties);
    }

    /**
     * A ship floats and is launched onto the water like a boat; a tank rests on the ground and is
     * set down on the block that was clicked. Which of the two this is comes from the same file
     * everything else about it does.
     */
    @Override
    protected boolean floatsOnWater() {
        return Definitions.VEHICLES.get(this.vehicle()).isShip();
    }

    /**
     * Flat on the heading it was put down on. It does not stay flat for long: the first tick reads
     * the ground under its tracks and lies the hull down on whatever is really there.
     */
    @Override
    protected void point(GroundVehicleEntity vehicle, float yaw) {
        vehicle.snapAttitude(yaw, 0.0F, 0.0F);
    }
}
