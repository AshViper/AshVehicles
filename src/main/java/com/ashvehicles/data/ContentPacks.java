package com.ashvehicles.data;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.ashvehicles.AshVehicles;

import net.neoforged.fml.loading.FMLPaths;

/**
 * ゲームフォルダの {@code ashvehiclespack/} と、その中に置かれた物。
 *
 * <p>機体を1機足すのに MOD を組み直す必要は無い。{@code assets/} と {@code data/} を持つ zip をここへ
 * 落とせば、それだけで足りる——中の JSON は起動時に読まれて機体とアイテムになり、中のモデルとテクスチャ
 * はリソースパックとして常時有効で配られる。resourcepacks や datapacks のように「有効にする」操作は
 * 無い。置いた物は置いた時点で入っている。
 *
 * <p>zip でもフォルダでもよい。zip は配る形で、展開したフォルダは作っている最中の形だ。中身の並びは
 * どちらも同じで、MOD 自身の {@code src/main/resources} と同じ並びでもある。
 *
 * <pre>
 * ashvehiclespack/
 *   mypack.zip
 *     assets/mypack/geo/entity/foo.geo.json
 *     assets/mypack/textures/entity/foo.png
 *     assets/mypack/lang/en_us.json
 *     data/mypack/aircraft/foo.json
 * </pre>
 *
 * <p><b>名前空間はフォルダ名がそのまま。</b> 上の例の機体は {@code mypack:foo} になり、エンティティ型も
 * アイテムもその名前で登録される。だから2つのパックが同じ名前の機体を出しても衝突しない。名前空間に
 * {@code ashvehicles} を選べば MOD 本体の機体を上書きできるが、それは意図してやること。
 *
 * <p>MOD 本体の中身はここには出てこない。あれは jar の中にあり、パックを1つも置かなくても揃っている。
 */
public final class ContentPacks {
    /** ゲームフォルダ直下の、パックを落とす場所。 */
    public static final String DIRECTORY = "ashvehiclespack";

    private static final String README = "README.txt";
    private static final String ZIP = ".zip";

    /** 見つけたパック。起動時に一度だけ調べ、以後は変わらない——途中で足しても再起動までは入らない。 */
    private static List<Path> packs;

    private ContentPacks() {
    }

    /** パックを置く場所。無ければ作り、初めて作ったときは書き方を1枚置いていく。 */
    public static synchronized Path folder() {
        Path folder = FMLPaths.GAMEDIR.get().resolve(DIRECTORY);

        try {
            if (!Files.isDirectory(folder)) {
                Files.createDirectories(folder);
                AshVehicles.LOGGER.info("Created the content pack folder at {}", folder);
            }

            Path readme = folder.resolve(README);

            if (!Files.exists(readme)) {
                Files.writeString(readme, ContentPackTemplate.README, StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            AshVehicles.LOGGER.error("Cannot prepare the content pack folder at {}", folder, exception);
        }

        return folder;
    }

    /**
     * 置かれているパック。名前順で、zip と展開フォルダの両方。
     *
     * <p>順番が決まっているのは、2つのパックが同じ名前の物を出したときに「どちらが勝つか」が起動ごとに
     * 変わらないようにするため。先に読まれた方が勝つ（{@link BuiltInFiles} 参照）。
     */
    public static synchronized List<Path> packs() {
        if (packs != null) {
            return packs;
        }

        Path folder = folder();
        List<Path> found = new ArrayList<>();

        try (Stream<Path> entries = Files.list(folder)) {
            entries.filter(ContentPacks::isPack)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(found::add);
        } catch (IOException exception) {
            AshVehicles.LOGGER.error("Cannot list the content packs in {}", folder, exception);
        }

        packs = List.copyOf(found);

        if (!packs.isEmpty()) {
            AshVehicles.LOGGER.info("Found {} content pack(s): {}", packs.size(),
                    packs.stream().map(path -> path.getFileName().toString()).toList());
        }

        return packs;
    }

    /** zip 1つか、{@code assets} か {@code data} を持つフォルダ1つ。それ以外は読み物として無視する。 */
    private static boolean isPack(Path path) {
        if (Files.isDirectory(path)) {
            return Files.isDirectory(path.resolve("assets")) || Files.isDirectory(path.resolve("data"));
        }

        return path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(ZIP);
    }

    /**
     * 各パックの中身を、普通のフォルダとして順に渡す。
     *
     * <p>zip は開いている間だけ {@link FileSystem} として見え、渡し終われば閉じる。呼び出し先が
     * {@link Path} を持ち帰ってはいけないのはそのためで、持ち帰ってよいのは読み取った中身だけだ。
     *
     * <p>1つのパックが壊れていても他は読む。zip が壊れているというのは、そのパックの作者に伝えるべき
     * ことであって、ゲームを止めるべきことではない。
     */
    public static void forEachRoot(Consumer<Path> action) {
        for (Path pack : packs()) {
            if (Files.isDirectory(pack)) {
                accept(pack, pack, action);

                continue;
            }

            try (FileSystem zip = FileSystems.newFileSystem(pack)) {
                accept(pack, zip.getPath("/"), action);
            } catch (IOException | UncheckedIOException exception) {
                AshVehicles.LOGGER.error("Cannot open the content pack {}", pack.getFileName(), exception);
            }
        }
    }

    private static void accept(Path pack, Path root, Consumer<Path> action) {
        try {
            action.accept(root);
        } catch (Exception exception) {
            AshVehicles.LOGGER.error("Cannot read the content pack {}", pack.getFileName(), exception);
        }
    }

    /** そのパックが名乗る名前。ログとパック一覧に出る。 */
    public static String nameOf(Path pack) {
        String name = pack.getFileName().toString();

        return name.toLowerCase(java.util.Locale.ROOT).endsWith(ZIP)
                ? name.substring(0, name.length() - ZIP.length())
                : name;
    }
}
