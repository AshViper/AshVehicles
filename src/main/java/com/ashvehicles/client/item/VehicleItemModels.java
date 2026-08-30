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
 * 全機体分のアイテムモデルを、1つずつ書かずにゲームへ渡す。
 *
 * <p>Minecraft はアイテムごとのモデルファイルを要求し、無い物は描かない。ところが機体アイテムのモデルが述べる
 * 必要のあることに、機体固有の物は何も無い。絵は実行時に
 * {@link VehicleIcons 機体自身のジオメトリから撮影}され {@link VehicleItemRenderer} が描くので、ファイルは MOD
 * の全機体で同じ数行になり、次に誰かが追加する機体でも同じ数行になる。同一の定数を20個手作業で同期させるのは、
 * 新車両がクリエイティブタブで黒と紫の立方体になる典型的な原因だ。
 *
 * <p>そこで、実際に登録されたアイテムのリストから生成し、メモリ上に存在するリソースパックから配信する。機体に
 * 必要なのはデータファイル、ジオメトリ、テクスチャだけ。モデルもアイコンも、ここへの1行も要らない。
 *
 * <p>このパックは常時有効でリソースパック画面には出さない。誰かが選んだパックではないし、無効にしてもアイテムを
 * 壊すだけだからだ。
 */
public final class VehicleItemModels {
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

    /** パックが配信するファイル。存在するアイテムから一度だけ算出する。 */
    @Nullable
    private static Map<ResourceLocation, byte[]> files;

    private VehicleItemModels() {
    }

    /** ゲームが「パックを持っているのは誰か」と尋ねる時点で、このパックを差し出す。 */
    public static void addTo(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }

        PackLocationInfo where = new PackLocationInfo(NAME,
                Component.literal("AshVehicles vehicle item models"), PackSource.BUILT_IN, Optional.empty());
        // 読むのではなく構築する。読み出す pack.mcmeta は無いし、そこから得られたはずの答えは全てここで分かって
        // いる。非表示かつ常時有効。
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
     * アイテムを持つ機体1つにつきモデルファイル1つ。名前はゲームが要求する形にする。
     *
     * <p>データファイルではなくアイテムから読む。ゲームがモデルを探す対象はアイテムだからだ。ファイルはあっても
     * アイテム名を他に取られた機体にはアイテムが無く、モデルも要らない。
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
