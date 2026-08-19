package com.ashvehicles.registry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.aircraft.AircraftLoader;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.BulletEntity;
import com.ashvehicles.entity.RocketEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * One entity type per aircraft file. Nothing here names an aircraft: the list comes from whatever
 * {@link AircraftLoader} found in the mod's resources.
 */
public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, AshVehicles.MODID);

    private static final Map<ResourceLocation, DeferredHolder<EntityType<?>, EntityType<AircraftEntity>>> AIRCRAFT =
            registerAll();

    /**
     * One round from a gun. Small, fast and short-lived, so it is tracked far out and updated every
     * tick: a tracer that only moves every third tick is a dotted line.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<BulletEntity>> BULLET =
            ENTITY_TYPES.register("bullet",
                    () -> EntityType.Builder.<BulletEntity>of(BulletEntity::new, MobCategory.MISC)
                            .sized(0.2F, 0.2F)
                            .clientTrackingRange(16)
                            .updateInterval(1)
                            .setShouldReceiveVelocityUpdates(true)
                            .build("bullet"));

    /**
     * A rocket or a missile. Bigger and slower than a round, and worth watching all the way in, so
     * it is tracked further out than one.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<RocketEntity>> ROCKET =
            ENTITY_TYPES.register("rocket",
                    () -> EntityType.Builder.<RocketEntity>of(RocketEntity::new, MobCategory.MISC)
                            .sized(0.4F, 0.4F)
                            .clientTrackingRange(24)
                            .updateInterval(1)
                            .setShouldReceiveVelocityUpdates(true)
                            .build("rocket"));

    private static Map<ResourceLocation, DeferredHolder<EntityType<?>, EntityType<AircraftEntity>>> registerAll() {
        Map<ResourceLocation, DeferredHolder<EntityType<?>, EntityType<AircraftEntity>>> types = new LinkedHashMap<>();

        AircraftLoader.builtIn().forEach((id, definition) -> types.put(id, register(id, definition)));

        return Collections.unmodifiableMap(types);
    }

    private static DeferredHolder<EntityType<?>, EntityType<AircraftEntity>> register(
            ResourceLocation id, AircraftDefinition definition) {
        AircraftDefinition.Hitbox hitbox = definition.hitbox();

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

    /** Every registered aircraft, by id. */
    public static Map<ResourceLocation, DeferredHolder<EntityType<?>, EntityType<AircraftEntity>>> aircraft() {
        return AIRCRAFT;
    }

    private ModEntities() {
    }
}
