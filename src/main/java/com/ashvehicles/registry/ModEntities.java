package com.ashvehicles.registry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.AshVehicles;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.BulletEntity;
import com.ashvehicles.entity.CountermeasureEntity;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.RocketEntity;
import com.ashvehicles.vehicle.GroundVehicleDefinition;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * One entity type per aircraft file. Nothing here names an aircraft: the list comes from whatever
 * {@link Definitions} found in the mod's resources.
 */
public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, AshVehicles.MODID);

    private static final Map<ResourceLocation, DeferredHolder<EntityType<?>, EntityType<AircraftEntity>>> AIRCRAFT =
            registerAll();

    private static final Map<ResourceLocation, DeferredHolder<EntityType<?>, EntityType<GroundVehicleEntity>>>
            VEHICLES = registerVehicles();

    /**
     * One round from a gun. Small, fast and short-lived, so it is tracked far out and updated every
     * tick: a tracer that only moves every third tick is a dotted line.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<BulletEntity>> BULLET =
            ENTITY_TYPES.register("bullet",
                    () -> EntityType.Builder.<BulletEntity>of(BulletEntity::new, MobCategory.MISC)
                            .sized(0.2F, 0.2F)
                            .clientTrackingRange(16)
                            // Rarely, and without the velocity. Both of those would do more harm
                            // than good: the packets that carry an entity's speed cannot express one
                            // this fast and clamp it to a tenth of the truth, and a position that is
                            // one tick stale is a forty-block jump. The client is told the real speed
                            // once, in synched data, and flies the round itself from there; the
                            // occasional position is a check rather than a correction. See
                            // VehicleProjectile.
                            .updateInterval(20)
                            .setShouldReceiveVelocityUpdates(false)
                            .build("bullet"));

    /**
     * A rocket or a missile. Bigger and slower than a round, and worth watching all the way in, so
     * it is tracked further out than one — and, since {@link com.ashvehicles.mixin.EntityTrackingMixin}
     * lifts the cap against the player's view distance, that range is the one it actually gets.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<RocketEntity>> ROCKET =
            ENTITY_TYPES.register("rocket",
                    () -> EntityType.Builder.<RocketEntity>of(RocketEntity::new, MobCategory.MISC)
                            .sized(0.4F, 0.4F)
                            .clientTrackingRange(32)
                            // More often than a round, for the same reasons set out there: a missile
                            // steers, and the two sides can only agree about that for as long as they
                            // agree about what it is steering at.
                            .updateInterval(5)
                            .setShouldReceiveVelocityUpdates(false)
                            .build("rocket"));

    /**
     * A flare or a cloud of chaff. Small, short-lived, and worth seeing from as far off as the
     * missile chasing it, which is the whole spectacle.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<CountermeasureEntity>> COUNTERMEASURE =
            ENTITY_TYPES.register("countermeasure",
                    () -> EntityType.Builder.<CountermeasureEntity>of(CountermeasureEntity::new, MobCategory.MISC)
                            .sized(0.3F, 0.3F)
                            .clientTrackingRange(32)
                            // Thrown once and then left to fall. Corrected often, because it is
                            // thrown with a share of the aeroplane's speed and the packet that
                            // spawns it cannot carry more than 3.9 blocks a tick of that -- but
                            // slowly enough that the corrections are ordinary relative moves rather
                            // than the teleports a fast round needs.
                            .updateInterval(2)
                            .setShouldReceiveVelocityUpdates(false)
                            .build("countermeasure"));

    private static Map<ResourceLocation, DeferredHolder<EntityType<?>, EntityType<AircraftEntity>>> registerAll() {
        Map<ResourceLocation, DeferredHolder<EntityType<?>, EntityType<AircraftEntity>>> types = new LinkedHashMap<>();

        Definitions.AIRCRAFT.builtIn().forEach((id, definition) -> types.put(id, register(id, definition)));

        return Collections.unmodifiableMap(types);
    }

    private static DeferredHolder<EntityType<?>, EntityType<AircraftEntity>> register(
            ResourceLocation id, AircraftDefinition definition) {
        VehicleChassis.Hitbox hitbox = definition.hitbox();

        // Minecraft hitboxes are boxes with a square footprint, so they can never match the
        // silhouette of an aeroplane; the file gives a figure that covers the fuselage and wing
        // roots and lets the outer wings overhang. The size is fixed here and cannot be changed by
        // a data pack, because an entity type carries it from the moment it is registered.
        return ModEntities.ENTITY_TYPES.register(id.getPath(),
                () -> EntityType.Builder.<AircraftEntity>of(AircraftEntity::new, MobCategory.MISC)
                        .sized(hitbox.width(), hitbox.height())
                        .clientTrackingRange(hitbox.trackingRange())
                        .updateInterval(1)
                        .setShouldReceiveVelocityUpdates(true)
                        .build(id.getPath()));
    }

    private static Map<ResourceLocation, DeferredHolder<EntityType<?>, EntityType<GroundVehicleEntity>>>
            registerVehicles() {
        Map<ResourceLocation, DeferredHolder<EntityType<?>, EntityType<GroundVehicleEntity>>> types =
                new LinkedHashMap<>();

        Definitions.VEHICLES.builtIn().forEach((id, definition) -> {
            if (AIRCRAFT.containsKey(id)) {
                AshVehicles.LOGGER.error("Ground vehicle {} shares its name with an aircraft; it is skipped", id);

                return;
            }

            types.put(id, registerVehicle(id, definition));
        });

        return Collections.unmodifiableMap(types);
    }

    /**
     * One entity type per ground vehicle file, on the same terms as an aircraft: the size is fixed
     * here and cannot be changed by a data pack, because an entity type carries it from the moment
     * it is registered.
     *
     * <p>Updated every tick and told to carry its velocity, both for the reason an aircraft is: the
     * hull is simulated by whoever is driving, and everyone else has to be able to draw it moving
     * rather than stepping.
     */
    private static DeferredHolder<EntityType<?>, EntityType<GroundVehicleEntity>> registerVehicle(
            ResourceLocation id, GroundVehicleDefinition definition) {
        VehicleChassis.Hitbox hitbox = definition.hitbox();

        return ModEntities.ENTITY_TYPES.register(id.getPath(),
                () -> EntityType.Builder.<GroundVehicleEntity>of(GroundVehicleEntity::new, MobCategory.MISC)
                        .sized(hitbox.width(), hitbox.height())
                        .clientTrackingRange(hitbox.trackingRange())
                        .updateInterval(1)
                        .setShouldReceiveVelocityUpdates(true)
                        .build(id.getPath()));
    }

    /** Every registered aircraft, by id. */
    public static Map<ResourceLocation, DeferredHolder<EntityType<?>, EntityType<AircraftEntity>>> aircraft() {
        return AIRCRAFT;
    }

    /** Every registered ground vehicle, by id. */
    public static Map<ResourceLocation, DeferredHolder<EntityType<?>, EntityType<GroundVehicleEntity>>> vehicles() {
        return VEHICLES;
    }

    private ModEntities() {
    }
}
