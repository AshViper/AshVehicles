package com.ashvehicles.registry;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.AshVehicles;
import com.ashvehicles.item.AircraftItem;
import com.ashvehicles.item.AmmoItem;
import com.ashvehicles.item.GroundVehicleItem;
import com.ashvehicles.item.WeaponItem;
import com.ashvehicles.item.WrenchItem;
import com.ashvehicles.weapon.AmmoKind;

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

    /** The one item that is not an aircraft or a weapon: what aircraft are taken apart with. */
    public static final DeferredItem<WrenchItem> WRENCH =
            ITEMS.registerItem("wrench", WrenchItem::new, new Item.Properties().stacksTo(1));

    private static final Map<ResourceLocation, DeferredItem<AircraftItem>> AIRCRAFT = registerAircraft();
    private static final Map<ResourceLocation, DeferredItem<GroundVehicleItem>> VEHICLES = registerVehicles();
    /**
     * Before the weapons, so that a weapon named after one of them loses rather than crashing the
     * game. The ammunition names are the mod's own handful of constants; a weapon's is whatever
     * somebody dropped in a data pack.
     */
    private static final Map<AmmoKind, DeferredItem<AmmoItem>> AMMO = registerAmmo();
    private static final Map<ResourceLocation, DeferredItem<WeaponItem>> WEAPONS = registerWeapons();

    private static Map<ResourceLocation, DeferredItem<AircraftItem>> registerAircraft() {
        Map<ResourceLocation, DeferredItem<AircraftItem>> items = new LinkedHashMap<>();

        ModEntities.aircraft().forEach((id, type) -> items.put(id, ITEMS.registerItem(id.getPath(),
                properties -> new AircraftItem(type, properties), new Item.Properties().stacksTo(1))));

        return Collections.unmodifiableMap(items);
    }

    /** One item per ground vehicle, on the same terms: named after it, and it places it. */
    private static Map<ResourceLocation, DeferredItem<GroundVehicleItem>> registerVehicles() {
        Map<ResourceLocation, DeferredItem<GroundVehicleItem>> items = new LinkedHashMap<>();

        ModEntities.vehicles().forEach((id, type) -> items.put(id, ITEMS.registerItem(id.getPath(),
                properties -> new GroundVehicleItem(type, properties), new Item.Properties().stacksTo(1))));

        return Collections.unmodifiableMap(items);
    }

    /**
     * Weapons share the item namespace with the vehicles, so a weapon may not be named after one.
     * A weapon whose file says {@code "item": false} is built into an airframe and gets none.
     */
    private static Map<ResourceLocation, DeferredItem<WeaponItem>> registerWeapons() {
        Map<ResourceLocation, DeferredItem<WeaponItem>> items = new LinkedHashMap<>();

        Definitions.WEAPONS.builtIn().forEach((id, definition) -> {
            if (!definition.item()) {
                return;
            }

            if (AIRCRAFT.containsKey(id) || VEHICLES.containsKey(id) || isAmmoName(id)) {
                AshVehicles.LOGGER.error("Weapon {} shares its name with a vehicle or with ammunition;"
                        + " it gets no item", id);

                return;
            }

            items.put(id, ITEMS.registerItem(id.getPath(),
                    properties -> new WeaponItem(id, properties), new Item.Properties().stacksTo(1)));
        });

        return Collections.unmodifiableMap(items);
    }

    /**
     * One item per kind of gun ammunition: a shell, and a belt. Named for what they are rather than
     * for a calibre, because what a round goes into is settled by the weapon's file and not by the
     * item — one shell loads a Leopard, a T-64 and a BMD alike.
     *
     * <p>Registered from the enum rather than one by one so that a third kind, if there is ever one,
     * is a constant and a texture and nothing else.
     */
    private static Map<AmmoKind, DeferredItem<AmmoItem>> registerAmmo() {
        Map<AmmoKind, DeferredItem<AmmoItem>> items = new EnumMap<>(AmmoKind.class);

        for (AmmoKind kind : AmmoKind.values()) {
            items.put(kind, ITEMS.registerItem(kind.itemName(),
                    properties -> new AmmoItem(kind, properties), new Item.Properties()));
        }

        return Collections.unmodifiableMap(items);
    }

    private static boolean isAmmoName(ResourceLocation id) {
        for (AmmoKind kind : AmmoKind.values()) {
            if (kind.itemName().equals(id.getPath())) {
                return true;
            }
        }

        return false;
    }

    /** Every aircraft item, by the id of the aircraft it places. */
    public static Map<ResourceLocation, DeferredItem<AircraftItem>> aircraft() {
        return AIRCRAFT;
    }

    /** Every ground vehicle item, by the id of the vehicle it places. */
    public static Map<ResourceLocation, DeferredItem<GroundVehicleItem>> vehicles() {
        return VEHICLES;
    }

    /** Every weapon item, by the id of the weapon it is. */
    public static Map<ResourceLocation, DeferredItem<WeaponItem>> weapons() {
        return WEAPONS;
    }

    /** The ammunition items, by the kind of gun each one feeds. */
    public static Map<AmmoKind, DeferredItem<AmmoItem>> ammo() {
        return AMMO;
    }

    private ModItems() {
    }
}
