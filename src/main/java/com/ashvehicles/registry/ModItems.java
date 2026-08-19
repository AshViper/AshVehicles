package com.ashvehicles.registry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.item.AircraftItem;
import com.ashvehicles.item.WeaponItem;
import com.ashvehicles.weapon.WeaponLoader;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * One item per aircraft, named after it, so an aircraft can be carried and put down; and one per
 * weapon that asks for one, so a weapon can be carried and hung on a pylon.
 */
public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AshVehicles.MODID);

    private static final Map<ResourceLocation, DeferredItem<AircraftItem>> AIRCRAFT = registerAircraft();
    private static final Map<ResourceLocation, DeferredItem<WeaponItem>> WEAPONS = registerWeapons();

    private static Map<ResourceLocation, DeferredItem<AircraftItem>> registerAircraft() {
        Map<ResourceLocation, DeferredItem<AircraftItem>> items = new LinkedHashMap<>();

        ModEntities.aircraft().forEach((id, type) -> items.put(id, ITEMS.registerItem(id.getPath(),
                properties -> new AircraftItem(type, properties), new Item.Properties().stacksTo(1))));

        return Collections.unmodifiableMap(items);
    }

    /**
     * Weapons share the item namespace with the aircraft, so a weapon may not be named after one.
     * A weapon whose file says {@code "item": false} is built into an airframe and gets none.
     */
    private static Map<ResourceLocation, DeferredItem<WeaponItem>> registerWeapons() {
        Map<ResourceLocation, DeferredItem<WeaponItem>> items = new LinkedHashMap<>();

        WeaponLoader.builtIn().forEach((id, definition) -> {
            if (!definition.item()) {
                return;
            }

            if (AIRCRAFT.containsKey(id)) {
                AshVehicles.LOGGER.error("Weapon {} shares its name with an aircraft; it gets no item", id);

                return;
            }

            items.put(id, ITEMS.registerItem(id.getPath(),
                    properties -> new WeaponItem(id, properties), new Item.Properties().stacksTo(1)));
        });

        return Collections.unmodifiableMap(items);
    }

    /** Every aircraft item, by the id of the aircraft it places. */
    public static Map<ResourceLocation, DeferredItem<AircraftItem>> aircraft() {
        return AIRCRAFT;
    }

    /** Every weapon item, by the id of the weapon it is. */
    public static Map<ResourceLocation, DeferredItem<WeaponItem>> weapons() {
        return WEAPONS;
    }

    private ModItems() {
    }
}
