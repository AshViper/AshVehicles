package com.ashvehicles.aircraft;

import java.util.Map;

import com.ashvehicles.data.BuiltInFiles;

import net.minecraft.resources.ResourceLocation;

/**
 * Finds the aircraft the mod ships with, before the game is far enough along to read data packs, so
 * that an entity type and an item can be registered for each. Everything but the hitbox is read
 * again later by {@link AircraftManager}, from the data packs.
 *
 * <p>The collision shapes are read here too, for a different reason. An aircraft builds its boxes
 * once, in its constructor, and the level records them the moment it joins; an aircraft built at a
 * time when no shape was known has no boxes and can never be given any. That is a silent failure of
 * exactly the wrong kind — the aeroplane flies perfectly well and quietly falls back to Minecraft's
 * plain hitbox — so a shape read out of the mod is always available to fall back on, however the
 * data packs are getting on.
 */
public final class AircraftLoader {
    private static Map<ResourceLocation, AircraftDefinition> builtIn;
    private static Map<ResourceLocation, AircraftShape> builtInShapes;

    /** Every aircraft shipped inside the mod, by id. Read once, on first use. */
    public static synchronized Map<ResourceLocation, AircraftDefinition> builtIn() {
        if (builtIn == null) {
            builtIn = BuiltInFiles.read(AircraftManager.DIRECTORY, AircraftDefinition.CODEC, "aircraft");
        }

        return builtIn;
    }

    public static AircraftDefinition builtIn(ResourceLocation id) {
        return builtIn().getOrDefault(id, AircraftDefinition.FALLBACK);
    }

    /** Every collision shape shipped inside the mod, by the id of the aircraft it belongs to. */
    public static synchronized Map<ResourceLocation, AircraftShape> builtInShapes() {
        if (builtInShapes == null) {
            builtInShapes = BuiltInFiles.read(AircraftManager.SHAPE_DIRECTORY, AircraftShape.CODEC, "aircraft shapes");
        }

        return builtInShapes;
    }

    public static AircraftShape builtInShape(ResourceLocation id) {
        return builtInShapes().getOrDefault(id, AircraftShape.NONE);
    }

    private AircraftLoader() {
    }
}
