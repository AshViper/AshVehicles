package com.ashvehicles.item;

import java.util.function.Supplier;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.GroundVehicleEntity;

import net.minecraft.world.entity.EntityType;

/** 登録時に紐づけられた地上車両を、クリックしたブロックの上へプレイヤー向きで設置する。 */
public class GroundVehicleItem extends VehicleItem<GroundVehicleEntity> {
    public GroundVehicleItem(Supplier<? extends EntityType<? extends GroundVehicleEntity>> type,
            Properties properties) {
        super(type, properties);
    }

    /**
     * 艦は浮くのでボートと同じく水面へ進水させ、戦車は地面に乗るのでクリックしたブロックへ置く。どちら
     * かは、他の全情報と同じファイルから来る。
     */
    @Override
    protected boolean floatsOnWater() {
        return Definitions.VEHICLES.get(this.vehicle()).isShip();
    }

    /**
     * 置いた向きのまま水平。ただし水平でいるのは一瞬で、最初の tick が履帯の下の地面を読み、車体を実際
     * の地形に沿って寝かせる。
     */
    @Override
    protected void point(GroundVehicleEntity vehicle, float yaw) {
        vehicle.snapAttitude(yaw, 0.0F, 0.0F);
    }
}
