package com.ashvehicles.item;

import java.util.List;
import java.util.function.Supplier;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.vehicle.GroundVehicleDefinition;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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

    @Override
    protected void specs(List<Component> lines) {
        if (!Definitions.VEHICLES.has(this.vehicle())) {
            return;
        }

        GroundVehicleDefinition definition = Definitions.VEHICLES.get(this.vehicle());
        GroundVehicleDefinition.Hull hull = definition.hull();

        // 最高速度 0 は固定砲座。0 km/h と書くより行が無い方が正直だ。
        if (definition.powertrain().maxSpeed() > 0.0F) {
            spec(lines, "top_speed", Math.round(definition.powertrain().maxSpeed() * BLOCKS_PER_TICK_TO_KMH));
        }

        spec(lines, "health", Math.round(hull.health()));

        if (hull.armour() > 0.0F) {
            spec(lines, "armour", Math.round(hull.armour()));
        }

        if (hull.mass() > 0.0F) {
            spec(lines, "mass", Math.round(hull.mass()));
        }

        spec(lines, "seats", hull.seats().size());

        definition.armament().main().ifPresent(gun -> spec(lines, "main_gun", weaponName(gun)));
        definition.coaxial().gun().ifPresent(gun -> spec(lines, "coaxial", weaponName(gun)));
        definition.launcher().missile().ifPresent(missile -> spec(lines, "launcher", weaponName(missile)));

        // 度/tick を度/秒へ。
        if (definition.turret().traverseRate() > 0.0F) {
            spec(lines, "traverse", Math.round(definition.turret().traverseRate() * 20.0F));
        }
    }

    private static Component weaponName(ResourceLocation weapon) {
        return Component.translatable("item." + weapon.getNamespace() + "." + weapon.getPath());
    }
}
