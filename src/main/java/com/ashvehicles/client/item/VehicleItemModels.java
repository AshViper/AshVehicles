package com.ashvehicles.client.item;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.registry.ModItems;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.flag.FeatureFlagSet;
import net.neoforged.neoforge.event.AddPackFindersEvent;

/**
 * Hands the game an item model for every machine, without one being written for each.
 *
 * <p>Minecraft insists on a model file per item and will not draw one that has none. What a
 * machine's item needs one to say, though, is nothing about the machine: the picture is
 * {@link VehicleIcons taken from the machine's own geometry} at runtime and drawn by
 * {@link VehicleItemRenderer}, so the file is the same handful of lines for every machine in the mod
 * and would be the same handful of lines for the next one somebody adds. Twenty copies of a constant
 * kept in step by hand is exactly the sort of thing that ends with a new vehicle in the creative tab
 * as a black and violet cube.
 *
 * <p>So they are generated, out of the list of items that were actually registered, and served from
 * a resource pack that lives in memory. A machine needs its data file, its geometry and its texture,
 * and nothing else: no model, no icon, no line here.
 *
 * <p>The pack is always on and hidden from the resource pack screen, because it is not a pack
 * anybody chose and turning it off would only break the items.
 */
public final class VehicleItemModels {
    /**
     * What every machine's item model says.
     *
     * <p>{@code builtin/entity} is the only way to tell the game that something else will draw this
     * item — it is what a chest and a shield use, and it is what makes it ask
     * {@code IClientItemExtensions} for a renderer instead of looking for quads.
     *
     * <p>{@code gui_light: front} is what a flat item uses, and this is one: the picture already has
     * its shading painted into it, and lighting it a second time as though it were a block standing
     * in the world would only darken it.
     *
     * <p>The display block is vanilla's own for a flat item, so that a machine sits in the hand, on
     * the ground and in a frame exactly where any other flat item does. The particle texture is
     * never used by anything an item of this kind does; it is named only because a model without one
     * is a warning in the log at every load.
     */
    private static final String MODEL = """
            {
              "parent": "builtin/entity",
              "gui_light": "front",
              "textures": {
                "particle": "minecraft:block/iron_block"
              },
              "display": {
                "ground": {
                  "rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]
                },
                "head": {
                  "rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1, 1, 1]
                },
                "thirdperson_righthand": {
                  "rotation": [0, 0, 0], "translation": [0, 3, 1], "scale": [0.55, 0.55, 0.55]
                },
                "firstperson_righthand": {
                  "rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]
                },
                "fixed": {
                  "rotation": [0, 180, 0], "scale": [1, 1, 1]
                }
              }
            }
            """;

    private static final String NAME = AshVehicles.MODID + "/vehicle_item_models";

    /** The files the pack serves, worked out once from the items that exist. */
    @Nullable
    private static Map<ResourceLocation, byte[]> files;

    private VehicleItemModels() {
    }

    /** Puts the pack in front of the game, at the point where it is asking who has any. */
    public static void addTo(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }

        PackLocationInfo where = new PackLocationInfo(NAME,
                Component.literal("AshVehicles vehicle item models"), PackSource.BUILT_IN, Optional.empty());
        // Built rather than read: there is no pack.mcmeta to read it out of, and every answer it
        // would have given is known here. Hidden, and always on.
        Pack.Metadata about = new Pack.Metadata(Component.literal("Item models for the mod's machines"),
                PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of(), true);
        Pack pack = new Pack(where, new Pack.ResourcesSupplier() {
            @Override
            public PackResources openPrimary(PackLocationInfo location) {
                return new Models(location);
            }

            @Override
            public PackResources openFull(PackLocationInfo location, Pack.Metadata metadata) {
                return new Models(location);
            }
        }, about, new PackSelectionConfig(true, Pack.Position.TOP, true));

        event.addRepositorySource(packs -> packs.accept(pack));
    }

    /**
     * One model file per machine that has an item, named as the game will ask for it.
     *
     * <p>Read off the items rather than off the data files, because the items are what the game will
     * be looking for models for: a machine whose file was there but whose item lost its name to
     * something else has no item and needs no model.
     */
    private static synchronized Map<ResourceLocation, byte[]> files() {
        if (files == null) {
            byte[] model = MODEL.getBytes(StandardCharsets.UTF_8);
            Map<ResourceLocation, byte[]> built = new LinkedHashMap<>();

            Stream.concat(ModItems.aircraft().keySet().stream(), ModItems.vehicles().keySet().stream())
                    .forEach(id -> built.put(ResourceLocation.fromNamespaceAndPath(id.getNamespace(),
                            "models/item/" + id.getPath() + ".json"), model));

            AshVehicles.LOGGER.info("Generated {} vehicle item models", built.size());
            files = Map.copyOf(built);
        }

        return files;
    }

    /** The pack itself: a map of paths to bytes, and the seven answers a pack has to give. */
    private static final class Models implements PackResources {
        private final PackLocationInfo where;

        private Models(PackLocationInfo where) {
            this.where = where;
        }

        @Nullable
        @Override
        public IoSupplier<InputStream> getRootResource(String... elements) {
            return null;
        }

        @Nullable
        @Override
        public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
            if (type != PackType.CLIENT_RESOURCES) {
                return null;
            }

            byte[] file = files().get(location);

            return file == null ? null : () -> new ByteArrayInputStream(file);
        }

        @Override
        public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
            if (type != PackType.CLIENT_RESOURCES) {
                return;
            }

            files().forEach((location, file) -> {
                if (location.getNamespace().equals(namespace) && location.getPath().startsWith(path)) {
                    output.accept(location, () -> new ByteArrayInputStream(file));
                }
            });
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            return type == PackType.CLIENT_RESOURCES ? Set.of(AshVehicles.MODID) : Set.of();
        }

        @Nullable
        @Override
        public <T> T getMetadataSection(MetadataSectionSerializer<T> deserializer) {
            return null;
        }

        @Override
        public PackLocationInfo location() {
            return this.where;
        }

        @Override
        public void close() {
        }
    }
}
