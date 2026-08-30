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
 * データパックを読める段階になる前に、MOD 本体と {@code ashvehiclespack/} のパックから JSON ディレクトリ
 * を直接読む。
 *
 * <p>エンティティ型とアイテムはレジストリが開いている間に登録せねばならず、それはデータパックに触れる
 * ずっと前に終わってしまう。そこで、後でデータパックが供給するのと同じファイルを起動時にここで読み、
 * 「何が存在するか」だけを知る。それ以外の中身は後でデータパック側から読み直され、パックによる上書き
 * が効く。
 *
 * <p><b>コンテンツパックもここで読まれる。</b> パックが機体を1機足すというのは、この起動時の一覧に1行
 * 増えるということだ。ここに現れなければエンティティ型もアイテムも作られず、後からデータパックとして
 * 中身が届いても置く先が無い。だからパックの中の {@code data/<名前空間>/<ディレクトリ>/} も同じ手順で
 * 歩く。{@link ContentPacks} 参照。
 */
public final class BuiltInFiles {
    private static final String SUFFIX = ".json";

    /**
     * MOD 本体と全パックの {@code data/<名前空間>/<directory>/} を解析し、名前空間とファイル名から作った
     * ID をキーにして返す。解析できないファイルはログに出して飛ばす。
     *
     * <p>MOD 本体が先で、パックはフォルダ内の名前順。同じ ID を2つが出したら先に読んだ方が勝ち、負けた方
     * はログに残る。名前空間はパックごとに違うのが普通なので、これが起きるのはパックが意図して MOD 本体
     * の物を置き換えに来た場合だけだ。
     *
     * @param what ログ用の語。"aircraft" や "weapons" など
     */
    public static <T> Map<ResourceLocation, T> read(String directory, Codec<T> codec, String what) {
        Map<ResourceLocation, T> found = new LinkedHashMap<>();
        Path path = locate(directory);

        if (path == null || !Files.isDirectory(path)) {
            AshVehicles.LOGGER.error("No {} directory found in the mod itself", directory);
        } else {
            readFolder(path, AshVehicles.MODID, codec, what, found);
        }

        ContentPacks.forEachRoot(root -> readPack(root, directory, codec, what, found));

        AshVehicles.LOGGER.info("Found {} {}: {}", found.size(), what, found.keySet());

        return found;
    }

    /**
     * 1つのパックの中身。{@code data/} の下にあるフォルダがそのまま名前空間で、その中に探している
     * ディレクトリがあれば読む。
     *
     * <p>名前空間を1つに決め打たないのがこの MOD 本体との唯一の違いだ。パックは自分の名前で物を出す
     * ——{@code data/mypack/aircraft/foo.json} は {@code mypack:foo} になる——ので、2つのパックが同じ
     * ファイル名を選んでも互いに何も起きない。
     */
    private static <T> void readPack(Path root, String directory, Codec<T> codec, String what,
            Map<ResourceLocation, T> into) {
        Path data = root.resolve("data");

        if (!Files.isDirectory(data)) {
            return;
        }

        try (Stream<Path> namespaces = Files.list(data)) {
            namespaces.filter(Files::isDirectory)
                    .sorted()
                    .forEach(namespace -> {
                        Path folder = namespace.resolve(directory);
                        String name = trim(namespace.getFileName().toString());

                        if (!Files.isDirectory(folder)) {
                            return;
                        }

                        if (!ResourceLocation.isValidNamespace(name)) {
                            AshVehicles.LOGGER.error("Skipping {} in {}: not a valid namespace", what, name);

                            return;
                        }

                        readFolder(folder, name, codec, what, into);
                    });
        } catch (Exception exception) {
            AshVehicles.LOGGER.error("Cannot list the namespaces in a content pack", exception);
        }
    }

    private static <T> void readFolder(Path folder, String namespace, Codec<T> codec, String what,
            Map<ResourceLocation, T> into) {
        try (Stream<Path> files = Files.list(folder)) {
            files.filter(file -> trim(file.getFileName().toString()).endsWith(SUFFIX))
                    .sorted()
                    .forEach(file -> parse(file, namespace, codec, what, into));
        } catch (Exception exception) {
            AshVehicles.LOGGER.error("Cannot list the {} in {}", what, folder, exception);
        }
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

    private static <T> void parse(Path file, String namespace, Codec<T> codec, String what,
            Map<ResourceLocation, T> into) {
        String name = trim(file.getFileName().toString());
        String id = name.substring(0, name.length() - SUFFIX.length());

        if (!ResourceLocation.isValidPath(id)) {
            AshVehicles.LOGGER.error("Skipping {} file {}: the name is not a valid identifier", what, name);

            return;
        }

        try (Reader reader = Files.newBufferedReader(file)) {
            JsonElement json = JsonParser.parseReader(reader);
            codec.parse(JsonOps.INSTANCE, json)
                    .resultOrPartial(error -> AshVehicles.LOGGER.error("Cannot read {} {}: {}", what, name, error))
                    .ifPresent(value -> keep(ResourceLocation.fromNamespaceAndPath(namespace, id), value,
                            what, into));
        } catch (Exception exception) {
            AshVehicles.LOGGER.error("Cannot read {} {}", what, name, exception);
        }
    }

    /** 先に読んだ方が名前を取る。後から来た方は失ってログに残る。 */
    private static <T> void keep(ResourceLocation id, T value, String what, Map<ResourceLocation, T> into) {
        if (into.putIfAbsent(id, value) != null) {
            AshVehicles.LOGGER.error("Two files call themselves {} {}; the later one is ignored", what, id);
        }
    }

    /**
     * zip の中のファイル名に付く末尾のスラッシュを落とす。
     *
     * <p>zip ファイルシステムはディレクトリ項目の名前を {@code "aircraft/"} のように返すことがあり、
     * 素の {@code endsWith(".json")} はそれを取りこぼす。普通のフォルダでは何も変わらない。
     */
    private static String trim(String name) {
        return name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
    }

    private BuiltInFiles() {
    }
}
