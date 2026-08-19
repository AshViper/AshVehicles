package com.ashvehicles.data;

import java.io.Reader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import com.ashvehicles.AshVehicles;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;

/**
 * Reads a directory of JSON files straight out of the mod, before the game is far enough along to
 * read data packs.
 *
 * <p>Entity types and items have to be registered while the registries are open, which is over long
 * before any data pack is touched. So the same files the data packs will later provide are read out
 * of the mod here, at start-up, purely to learn what exists. Everything else about them is read
 * again later, from the data packs, where a pack can have overridden it.
 */
public final class BuiltInFiles {
    private static final String SUFFIX = ".json";

    /**
     * Every file in {@code data/ashvehicles/<directory>/}, parsed, keyed by the mod's id for the
     * file's name. Files that will not parse are logged and skipped.
     *
     * @param what a word for the log: "aircraft", "weapons"
     */
    public static <T> Map<ResourceLocation, T> read(String directory, Codec<T> codec, String what) {
        Map<ResourceLocation, T> found = new LinkedHashMap<>();
        Path path = locate(directory);

        if (path == null || !Files.isDirectory(path)) {
            AshVehicles.LOGGER.error("No {} directory found; no {} will be registered", directory, what);

            return found;
        }

        try (Stream<Path> files = Files.list(path)) {
            files.filter(file -> file.getFileName().toString().endsWith(SUFFIX))
                    .sorted()
                    .forEach(file -> parse(file, codec, what, found));
        } catch (Exception exception) {
            AshVehicles.LOGGER.error("Cannot list the {} in {}", what, path, exception);
        }

        AshVehicles.LOGGER.info("Found {} {}: {}", found.size(), what, found.keySet());

        return found;
    }

    /**
     * The directory inside the mod. Asking the mod file is the direct route; the class loader is a
     * second opinion for the case where the mod list is not built yet.
     */
    private static Path locate(String directory) {
        try {
            IModFileInfo mod = ModList.get().getModFileById(AshVehicles.MODID);

            if (mod != null) {
                Path path = mod.getFile().findResource("data", AshVehicles.MODID, directory);

                if (Files.isDirectory(path)) {
                    return path;
                }
            }
        } catch (Exception exception) {
            AshVehicles.LOGGER.debug("Cannot reach the mod file yet, falling back to the class loader", exception);
        }

        try {
            URL url = BuiltInFiles.class.getClassLoader()
                    .getResource("data/" + AshVehicles.MODID + "/" + directory);

            return url == null ? null : Path.of(url.toURI());
        } catch (Exception exception) {
            AshVehicles.LOGGER.error("Cannot locate the {} directory", directory, exception);

            return null;
        }
    }

    private static <T> void parse(Path file, Codec<T> codec, String what, Map<ResourceLocation, T> into) {
        String name = file.getFileName().toString();
        String id = name.substring(0, name.length() - SUFFIX.length());

        if (!ResourceLocation.isValidPath(id)) {
            AshVehicles.LOGGER.error("Skipping {} file {}: the name is not a valid identifier", what, name);

            return;
        }

        try (Reader reader = Files.newBufferedReader(file)) {
            JsonElement json = JsonParser.parseReader(reader);
            codec.parse(JsonOps.INSTANCE, json)
                    .resultOrPartial(error -> AshVehicles.LOGGER.error("Cannot read {} {}: {}", what, name, error))
                    .ifPresent(value -> into.put(ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, id), value));
        } catch (Exception exception) {
            AshVehicles.LOGGER.error("Cannot read {} {}", what, name, exception);
        }
    }

    private BuiltInFiles() {
    }
}
