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
 * データパックを読める段階になる前に、MOD 本体から JSON ディレクトリを直接読む。
 *
 * <p>エンティティ型とアイテムはレジストリが開いている間に登録せねばならず、それはデータパックに触れる
 * ずっと前に終わってしまう。そこで、後でデータパックが供給するのと同じファイルを起動時にここで読み、
 * 「何が存在するか」だけを知る。それ以外の中身は後でデータパック側から読み直され、パックによる上書き
 * が効く。
 */
public final class BuiltInFiles {
    private static final String SUFFIX = ".json";

    /**
     * {@code data/ashvehicles/<directory>/} の全ファイルを解析し、ファイル名から作った MOD の ID を
     * キーにして返す。解析できないファイルはログに出して飛ばす。
     *
     * @param what ログ用の語。"aircraft" や "weapons" など
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
     * MOD 内のディレクトリ。MOD ファイルに訊くのが正攻法で、MOD リストがまだ組み上がっていない場合の
     * second opinion がクラスローダー。
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
