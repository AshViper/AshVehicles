package com.ashvehicles.item;

import java.util.List;
import java.util.function.Supplier;

import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.vehicle.Attitude;

import net.minecraft.network.chat.Component;
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

    @Override
    protected void specs(List<Component> lines) {
        if (!Definitions.AIRCRAFT.has(this.vehicle())) {
            return;
        }

        AircraftDefinition definition = Definitions.AIRCRAFT.get(this.vehicle());
        AircraftDefinition.Airframe airframe = definition.airframe();
        boolean rotary = definition.rotor().isPresent();

        spec(lines, "top_speed", Math.round(topSpeed(definition) * BLOCKS_PER_TICK_TO_KMH));

        if (!rotary) {
            spec(lines, "stall_speed", Math.round(definition.wing().stallSpeed() * BLOCKS_PER_TICK_TO_KMH));
        }

        spec(lines, "health", Math.round(airframe.health()));
        spec(lines, "mass", Math.round(airframe.mass()));

        if (airframe.payload() > 0.0F) {
            spec(lines, "payload", Math.round(airframe.payload()));
        }

        spec(lines, "seats", airframe.seats().size());

        long pylons = definition.hardpoints().stream()
                .filter(h -> h.kind() == AircraftDefinition.Hardpoint.Kind.WEAPON).count();

        if (pylons > 0) {
            spec(lines, "pylons", pylons);
        }

        AircraftDefinition.Countermeasures decoys = definition.countermeasures();

        if (decoys.flares() > 0 || decoys.chaff() > 0) {
            spec(lines, "countermeasures", decoys.flares(), decoys.chaff());
        }
    }

    /**
     * 到達できる最高速度（block/tick）。固定翼の wing.max_speed は暴走を止めるクランプで、実際の上限は
     * 推力と抗力の釣り合い（tool/dump_definitions.py の top_speed_kmh と同じ式）。ヘリはローターが
     * 決めるのでクランプをそのまま出す。
     */
    private static float topSpeed(AircraftDefinition definition) {
        float clamp = definition.wing().maxSpeed();

        if (definition.rotor().isPresent()) {
            return clamp;
        }

        float drag = definition.wing().drag()
                * definition.wing().sweep().map(AircraftDefinition.Sweep::drag).orElse(1.0F);
        float thrust = definition.engine().maxThrust()
                * definition.engine().afterburner().map(AircraftDefinition.Afterburner::thrust).orElse(1.0F);

        if (drag <= 0.0F || thrust <= 0.0F) {
            return clamp;
        }

        return Math.min(clamp, (float) Math.sqrt(thrust / drag));
    }
}
