package com.ashvehicles.weapon;

import java.util.Map;

import com.ashvehicles.aircraft.AircraftManager;
import com.ashvehicles.data.BuiltInFiles;

import net.minecraft.resources.ResourceLocation;

/**
 * Finds the weapons the mod ships with, before the game is far enough along to read data packs, so
 * that an item can be registered for each. The figures themselves are read again later by
 * {@link AircraftManager}, from the data packs.
 */
public final class WeaponLoader {
    private static Map<ResourceLocation, WeaponDefinition> builtIn;

    /** Every weapon shipped inside the mod, by id. Read once, on first use. */
    public static synchronized Map<ResourceLocation, WeaponDefinition> builtIn() {
        if (builtIn == null) {
            builtIn = BuiltInFiles.read(AircraftManager.WEAPON_DIRECTORY, WeaponDefinition.CODEC, "weapons");
        }

        return builtIn;
    }

    public static WeaponDefinition builtIn(ResourceLocation id) {
        return builtIn().getOrDefault(id, WeaponDefinition.FALLBACK);
    }

    private WeaponLoader() {
    }
}
