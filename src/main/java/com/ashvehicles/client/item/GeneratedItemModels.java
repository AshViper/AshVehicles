package com.ashvehicles.client.item;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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
 * 定義ファイルから生えるアイテムのモデルを、1つずつ書かずにゲームへ渡す。
 *
 * <p>Minecraft はアイテムごとのモデルファイルを要求し、無い物は描かない。ところがこれらのアイテムのモデルが
 * 述べる必要のあることに、その物固有の物は何も無い。絵はどちらの種類も実行時に自分自身のジオメトリから作られる
 * ——機体は{@link VehicleIcons 一度撮った写真}を {@link VehicleItemRenderer} が、パイロン搭載物は
 * {@link StoreItemRenderer} がジオメトリそのものを——ので、ファイルは MOD の全機体・全兵装で同じ数行になり、
 * 次に誰かが追加する物でも同じ数行になる。同一の定数を数十個手作業で同期させるのは、新しい物がクリエイティブ
 * タブで黒と紫の立方体になる典型的な原因だ。
 *
 * <p>そこで、実際に登録されたアイテムのリストから生成し、メモリ上に存在するリソースパックから配信する。新しい
 * 機体や兵装に必要なのはデータファイル、ジオメトリ、テクスチャだけ。モデルもここへの1行も要らない。<b>コンテンツ
 * パックが足した物も同じ経路で賄われる</b>——あちらの名前空間のアイテムにも、誰かがモデルを書いてやる必要は無い。
 *
 * <p>このパックは常時有効でリソースパック画面には出さない。誰かが選んだパックではないし、無効にしてもアイテムを
 * 壊すだけだからだ。
 */
public final class GeneratedItemModels {
    /**
     * 全機体のアイテムモデルの中身。
     *
     * <p>{@code builtin/entity} は「このアイテムは別の物が描く」とゲームへ伝える唯一の手段だ。チェストや盾が
     * 使っている物であり、これがあるからゲームはクアッドを探さず {@code IClientItemExtensions} にレンダラーを
     * 問い合わせる。
     *
     * <p>{@code gui_light: front} は平坦アイテムが使う設定で、これは平坦アイテムだ。絵には既に陰影が描き込まれて
     * おり、世界に立つブロックのように二度目の照明を当てても暗くなるだけだ。
     *
     * <p>display ブロックは平坦アイテム用のバニラそのままで、機体が手の中・地面・額縁で他の平坦アイテムと同じ
     * 位置に収まるようにする。パーティクルテクスチャはこの種のアイテムの動作では一切使わない。指定しているのは、
     * 無いモデルはロードのたびログに警告を出すからにすぎない。
     */
    private static final String FLAT = """
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

    /**
     * パイロンに吊る物——兵装・ラック・ポッド——のアイテムモデルの中身。
     *
     * <p>平坦アイテムとの違いは2つだけだ。1つは {@code gui_light} が無いこと。既定の {@code side} は「これは立体
     * だから立体として照らせ」という意味で、実際これは翼下に吊られているのと同じジオメトリが回っている物だ。
     * 描き込まれた陰影を持つ絵ではないので、正面から平坦に照らせば奥行きが消える。
     *
     * <p>もう1つは手の中での持ち方で、剣や棒と同じ {@code handheld} の値を使う。{@link StoreItemRenderer} は
     * ミサイルをスロットの対角線に沿わせる——平坦アイテムのスプライトが剣を描くのとまったく同じ向き——ので、
     * 握りの位置についてバニラが剣のために決めた答えがそのまま正しい答えになる。
     */
    private static final String SOLID = """
            {
              "parent": "builtin/entity",
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
                  "rotation": [0, -90, 55], "translation": [0, 4, 0.5], "scale": [0.85, 0.85, 0.85]
                },
                "thirdperson_lefthand": {
                  "rotation": [0, 90, -55], "translation": [0, 4, 0.5], "scale": [0.85, 0.85, 0.85]
                },
                "firstperson_righthand": {
                  "rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]
                },
                "firstperson_lefthand": {
                  "rotation": [0, 90, -25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]
                },
                "fixed": {
                  "rotation": [0, 180, 0], "scale": [1, 1, 1]
                }
              }
            }
            """;

    private static final String NAME = AshVehicles.MODID + "/generated_item_models";

    /** パックが配信するファイル。存在するアイテムから一度だけ算出する。 */
    @Nullable
    private static Map<ResourceLocation, byte[]> files;

    private GeneratedItemModels() {
    }

    /** ゲームが「パックを持っているのは誰か」と尋ねる時点で、このパックを差し出す。 */
    public static void addTo(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }

        PackLocationInfo where = new PackLocationInfo(NAME,
                Component.literal("AshVehicles generated item models"), PackSource.BUILT_IN, Optional.empty());
        // 読むのではなく構築する。読み出す pack.mcmeta は無いし、そこから得られたはずの答えは全てここで分かって
        // いる。非表示かつ常時有効。
        Pack.Metadata about = new Pack.Metadata(
                Component.literal("Item models for the mod's machines and pylon stores"),
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
     * アイテムを持つ物1つにつきモデルファイル1つ。名前はゲームが要求する形にする。
     *
     * <p>データファイルではなくアイテムから読む。ゲームがモデルを探す対象はアイテムだからだ。ファイルはあっても
     * アイテム名を他に取られた機体にはアイテムが無く、モデルも要らない。
     */
    private static synchronized Map<ResourceLocation, byte[]> files() {
        if (files == null) {
            Map<ResourceLocation, byte[]> built = new LinkedHashMap<>();

            Stream.concat(ModItems.aircraft().keySet().stream(), ModItems.vehicles().keySet().stream())
                    .forEach(id -> built.put(model(id), FLAT.getBytes(StandardCharsets.UTF_8)));

            Stream.of(ModItems.weapons(), ModItems.racks(), ModItems.equipment())
                    .flatMap(items -> items.keySet().stream())
                    .forEach(id -> built.put(model(id), SOLID.getBytes(StandardCharsets.UTF_8)));

            AshVehicles.LOGGER.info("Generated {} item models", built.size());
            files = Map.copyOf(built);
        }

        return files;
    }

    /** ゲームがそのアイテムのモデルを探しに行く場所。 */
    private static ResourceLocation model(ResourceLocation id) {
        return ResourceLocation.fromNamespaceAndPath(id.getNamespace(),
                "models/item/" + id.getPath() + ".json");
    }

    /** パック本体。パスからバイト列へのマップと、パックが答えるべき7つの応答。 */
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
            return type == PackType.CLIENT_RESOURCES ? namespaces() : Set.of();
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

    /**
     * このパックが物を持っている名前空間。MOD 自身の物とは限らない——コンテンツパックの機体や兵装は
     * パック自身の名前空間から登録される（{@code content-packs-load-at-registration}）ので、生成した
     * ファイルの側から数える。
     */
    private static Set<String> namespaces() {
        return files().keySet().stream().map(ResourceLocation::getNamespace)
                .collect(Collectors.toUnmodifiableSet());
    }
}
