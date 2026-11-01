package com.ashvehicles.client.model.obj;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import software.bernie.geckolib.cache.object.BakedGeoModel;

/**
 * {@code assets/<名前空間>/obj/} の OBJ を、GeckoLib が geo.json を焼いて置くのと同じキャッシュへ、同じ名前で
 * 置く。
 *
 * <p><b>同じ名前で置くのが全部。</b>定義ファイルは模型の場所を持たず、機体の ID から
 * {@code geo/entity/<id>.geo.json} を組み立てて GeckoLib に訊く。その答えに OBJ から焼いた物が入っていれば、
 * 実体・ゴースト・アイコン・搭載物・{@code BakedGeometry} のどれもが OBJ を知らないまま同じ経路で描き、
 * 定義ファイルの {@code model.bones} もグループの名前でそのまま効く。別のレンダラーを立てる案は、GeckoLib の
 * 型で書かれた15ほどのクラス（姿勢・履帯・ゴースト・アイコン・搭載物）を二重に持つことになるので取らなかった。
 *
 * <pre>
 * obj/entity/foo.obj     → geo/entity/foo.geo.json   （機体・車両・船）
 * obj/foo.obj            → geo/entity/foo.geo.json   （種類のディレクトリを省いた物は機体として）
 * obj/weapon/bar.obj     → geo/weapon/bar.geo.json   （rack/ equipment/ も同じ）
 * obj/entity/foo.bones.json                          （あれば親子と支点。{@link ObjGeometry}）
 * textures/entity/foo.png                            （テクスチャは geo.json の時と同じ場所）
 * </pre>
 *
 * <p>同じ名前の geo.json もあれば OBJ が勝つ。GeckoLib が geo.json を入れ終えた後に入れるから。
 */
public final class ObjModels {
    public static final String DIRECTORY = "obj";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String EXTENSION = ".obj";
    private static final String BONES = ".bones.json";
    private static final String GEO = "geo/";
    private static final String GEO_EXTENSION = ".geo.json";

    /** 種類のディレクトリを持たない OBJ が入る先。 */
    private static final String MACHINES = "entity/";

    private ObjModels() {
    }

    /**
     * 見つけた OBJ を全部焼いて {@code models} へ入れる。GeckoLib のリロードの中、GeckoLib 自身の模型を入れ終えた
     * 直後に呼ばれる（{@code ObjModelLoadMixin}）。入れる先は GeckoLib の素の HashMap なので、入れるのは最後に
     * 1本のスレッドから。
     *
     * <p>読めない OBJ はログに出して飛ばす。リロードを落とすとリソースパック全部が巻き戻るので。
     */
    public static CompletableFuture<Void> load(Executor executor, ResourceManager resources,
            BiConsumer<ResourceLocation, BakedGeoModel> models) {
        return CompletableFuture
                .supplyAsync(() -> resources.listResources(DIRECTORY, file -> file.getPath().endsWith(EXTENSION)),
                        executor)
                .thenApplyAsync(found -> {
                    Map<ResourceLocation, CompletableFuture<BakedGeoModel>> tasks = new LinkedHashMap<>();

                    for (Map.Entry<ResourceLocation, Resource> entry : found.entrySet()) {
                        tasks.put(entry.getKey(), CompletableFuture.supplyAsync(
                                () -> bake(resources, entry.getKey(), entry.getValue()), executor));
                    }

                    return tasks;
                }, executor)
                .thenAcceptAsync(tasks -> {
                    Map<ResourceLocation, ResourceLocation> placed = new HashMap<>();

                    for (Map.Entry<ResourceLocation, CompletableFuture<BakedGeoModel>> task : tasks.entrySet()) {
                        BakedGeoModel model = task.getValue().join();

                        if (model == null) {
                            continue;
                        }

                        ResourceLocation file = task.getKey();
                        ResourceLocation geometry = geometryOf(file);
                        ResourceLocation earlier = placed.putIfAbsent(geometry, file);

                        if (earlier != null) {
                            LOGGER.error("{} and {} are both the model {}; keeping {}", earlier, file, geometry,
                                    earlier);
                            continue;
                        }

                        if (resources.getResource(geometry).isPresent()) {
                            LOGGER.warn("{} replaces {}", file, geometry);
                        }

                        if (model.topLevelBones().isEmpty()) {
                            LOGGER.warn("{} has no faces; {} draws nothing", file, geometry);
                        }

                        models.accept(geometry, model);
                    }

                    if (!placed.isEmpty()) {
                        LOGGER.info("Baked {} OBJ model(s) into the GeckoLib cache", placed.size());
                    }
                }, executor);
    }

    @Nullable
    private static BakedGeoModel bake(ResourceManager resources, ResourceLocation file, Resource resource) {
        Optional<Resource> bones = resources.getResource(sibling(file, BONES));

        try (Reader obj = resource.openAsReader()) {
            if (bones.isEmpty()) {
                return ObjGeometry.bake(obj, null, file.toString());
            }

            try (Reader joints = bones.get().openAsReader()) {
                return ObjGeometry.bake(obj, joints, file.toString());
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Could not read OBJ model {}", file, e);

            return null;
        }
    }

    /** OBJ の置き場所から、GeckoLib がその模型を探しに来る名前へ。 */
    public static ResourceLocation geometryOf(ResourceLocation file) {
        String path = file.getPath();
        String name = path.substring(DIRECTORY.length() + 1, path.length() - EXTENSION.length());

        return ResourceLocation.fromNamespaceAndPath(file.getNamespace(),
                GEO + (name.indexOf('/') < 0 ? MACHINES + name : name) + GEO_EXTENSION);
    }

    /**
     * その geo.json の代わりに置かれた OBJ があるか。{@link #geometryOf} の逆引きで、geo.json が無ければ既定の
     * 模型へ落ちる呼び出し元（{@code WeaponModel}）が OBJ を見落とさないように。
     */
    public static boolean standsIn(ResourceManager resources, ResourceLocation geometry) {
        String path = geometry.getPath();

        if (!path.startsWith(GEO) || !path.endsWith(GEO_EXTENSION)) {
            return false;
        }

        String name = path.substring(GEO.length(), path.length() - GEO_EXTENSION.length());

        return present(resources, geometry.getNamespace(), name)
                || name.startsWith(MACHINES) && name.indexOf('/', MACHINES.length()) < 0
                        && present(resources, geometry.getNamespace(), name.substring(MACHINES.length()));
    }

    private static boolean present(ResourceManager resources, String namespace, String name) {
        return resources.getResource(ResourceLocation.fromNamespaceAndPath(namespace,
                DIRECTORY + "/" + name + EXTENSION)).isPresent();
    }

    private static ResourceLocation sibling(ResourceLocation file, String suffix) {
        String path = file.getPath();

        return ResourceLocation.fromNamespaceAndPath(file.getNamespace(),
                path.substring(0, path.length() - EXTENSION.length()) + suffix);
    }
}
