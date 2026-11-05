package com.ashvehicles.client.model.bbmodel;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

import javax.annotation.Nullable;

import org.joml.Vector3f;
import org.slf4j.Logger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
import software.bernie.geckolib.loading.json.raw.Model;
import software.bernie.geckolib.loading.json.typeadapter.KeyFramesAdapter;
import software.bernie.geckolib.loading.object.BakedAnimations;
import software.bernie.geckolib.loading.object.BakedModelFactory;
import software.bernie.geckolib.loading.object.GeometryTree;

/**
 * {@code assets/<名前空間>/bbmodel/} の Blockbench プロジェクトを、そのまま模型・テクスチャ・アニメーションとして
 * 使う。書き出しの手間を無くすためのもので、置いた {@code .bbmodel} 1つが geo.json・png・animation.json の3つの
 * 代わりになる。
 *
 * <pre>
 * bbmodel/entity/foo.bbmodel → geo/entity/foo.geo.json       （機体・車両・船）
 *                            → textures/entity/foo.png        （埋め込まれたテクスチャ）
 *                            → animations/entity/foo.animation.json （アニメーションがあれば）
 * bbmodel/foo.bbmodel        → 種類を省いた物は機体として（entity/）
 * bbmodel/weapon/bar.bbmodel → geo/weapon/bar.geo.json と textures/weapon/bar.png（rack/ equipment/ も同じ）
 * </pre>
 *
 * <p><b>OBJ と同じく、GeckoLib が探しに来る名前で置くのが全部。</b>模型とアニメーションは GeckoLib のリロードの
 * 続きで同じキャッシュへ（{@code ObjModelLoadMixin}）、テクスチャはテクスチャマネージャへその名前で登録する。
 * 実体・ゴースト・アイコン・搭載物のどれも bbmodel を知らないまま描く。同じ名前のファイルが別にあれば bbmodel が
 * 勝つ（警告を出す）。
 *
 * <p>キューブは Blockbench の書き出しと同じ geo.json に組み直して GeckoLib 自身に焼かせる（{@link BbModelFile}）。
 * メッシュは geo.json に書けないので、焼いた模型のボーンへ面をキューブとして足す。
 */
public final class BbModels {
    public static final String DIRECTORY = "bbmodel";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String EXTENSION = ".bbmodel";
    private static final String MACHINES = "entity/";

    /** メッシュのキューブの大きさ。0 の軸を持つと GeckoLib が法線を裏返す（{@code ObjGeometry} と同じ理由）。 */
    private static final Vec3 NOT_FLAT = new Vec3(1.0, 1.0, 1.0);

    /** 前のリロードで登録したテクスチャ。bbmodel が消えたら手放す。描画スレッドからだけ触る。 */
    private static final Set<ResourceLocation> REGISTERED = new HashSet<>();

    private BbModels() {
    }

    /**
     * 見つけた bbmodel の模型を {@code models} へ入れ、テクスチャを登録する。GeckoLib が自分の模型（と OBJ）を
     * 入れ終えた後に呼ばれる。
     */
    public static CompletableFuture<Void> loadModels(Executor executor, ResourceManager resources,
            BiConsumer<ResourceLocation, BakedGeoModel> models) {
        return CompletableFuture.supplyAsync(() -> find(resources), executor)
                .thenApplyAsync(found -> {
                    Map<ResourceLocation, CompletableFuture<Baked>> tasks = new LinkedHashMap<>();

                    found.forEach((file, resource) -> tasks.put(file,
                            CompletableFuture.supplyAsync(() -> bake(file, resource), executor)));

                    return tasks;
                }, executor)
                .thenAcceptAsync(tasks -> {
                    Map<ResourceLocation, NativeImage> textures = new HashMap<>();

                    tasks.forEach((file, task) -> {
                        Baked baked = task.join();

                        if (baked == null) {
                            return;
                        }

                        ResourceLocation geometry = target(file, "geo/", ".geo.json");

                        if (resources.getResource(geometry).isPresent()) {
                            LOGGER.warn("{} replaces {}", file, geometry);
                        }

                        models.accept(geometry, baked.model());

                        if (baked.texture() != null) {
                            textures.put(target(file, "textures/", ".png"), baked.texture());
                        }
                    });

                    Minecraft.getInstance().execute(() -> register(textures));

                    if (!tasks.isEmpty()) {
                        LOGGER.info("Baked {} bbmodel(s) into the GeckoLib cache", tasks.size());
                    }
                }, executor);
    }

    /**
     * 見つけた bbmodel のアニメーション（animation.json の名前 → 中身）を読む。入れるのは呼び出し元で、GeckoLib が
     * マップを差し替えた後にゲームスレッドから（{@code ObjModelLoadMixin}）。GeckoLib は自分のアニメーションの
     * 書き込みを待たずに次へ進む（{@code loadAnimations} が {@code loadResources} の future を捨てている）ので、
     * 模型のように読み込みの続きへ繋いでも書き込みの後になる保証が無い。
     */
    public static CompletableFuture<Map<ResourceLocation, BakedAnimations>> readAnimations(Executor executor,
            ResourceManager resources) {
        return CompletableFuture.supplyAsync(() -> {
            Map<ResourceLocation, BakedAnimations> baked = new LinkedHashMap<>();

            find(resources).forEach((file, resource) -> {
                BakedAnimations read = animations(file, resource);

                if (read != null) {
                    baked.put(target(file, "animations/", ".animation.json"), read);
                }
            });

            return baked;
        }, executor);
    }

    /**
     * その geo.json か png の代わりに置かれた bbmodel があるか。geo.json や png が無ければ既定の模型へ落ちる
     * 呼び出し元（{@code WeaponModel}）が bbmodel を見落とさないように。
     */
    public static boolean standsIn(ResourceManager resources, ResourceLocation file) {
        String path = file.getPath();
        String name;

        if (path.startsWith("geo/") && path.endsWith(".geo.json")) {
            name = path.substring("geo/".length(), path.length() - ".geo.json".length());
        } else if (path.startsWith("textures/") && path.endsWith(".png")) {
            name = path.substring("textures/".length(), path.length() - ".png".length());
        } else {
            return false;
        }

        return present(resources, file.getNamespace(), name)
                || name.startsWith(MACHINES) && name.indexOf('/', MACHINES.length()) < 0
                        && present(resources, file.getNamespace(), name.substring(MACHINES.length()));
    }

    private static boolean present(ResourceManager resources, String namespace, String name) {
        return resources.getResource(ResourceLocation.fromNamespaceAndPath(namespace,
                DIRECTORY + "/" + name + EXTENSION)).isPresent();
    }

    private static Map<ResourceLocation, Resource> find(ResourceManager resources) {
        return resources.listResources(DIRECTORY, file -> file.getPath().endsWith(EXTENSION));
    }

    /** bbmodel の置き場所から、GeckoLib やテクスチャマネージャがそれを探しに来る名前へ。 */
    private static ResourceLocation target(ResourceLocation file, String root, String suffix) {
        String path = file.getPath();
        String name = path.substring(DIRECTORY.length() + 1, path.length() - EXTENSION.length());

        return ResourceLocation.fromNamespaceAndPath(file.getNamespace(),
                root + (name.indexOf('/') < 0 ? MACHINES + name : name) + suffix);
    }

    private record Baked(BakedGeoModel model, @Nullable NativeImage texture) {
    }

    @Nullable
    private static JsonObject read(ResourceLocation file, Resource resource) throws IOException {
        try (Reader reader = resource.openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    /** 読めない bbmodel はログに出して飛ばす。リロードを落とすとリソースパック全部が巻き戻るので。 */
    @Nullable
    private static Baked bake(ResourceLocation file, Resource resource) {
        try {
            BbModelFile project = new BbModelFile(read(file, resource), file.toString());
            String name = file.getPath().substring(file.getPath().lastIndexOf('/') + 1);
            BbModelFile.Geometry geometry = project.geometry(
                    "geometry." + name.substring(0, name.length() - EXTENSION.length()));
            Model raw = KeyFramesAdapter.GEO_GSON.fromJson(geometry.json(), Model.class);
            BakedGeoModel model = BakedModelFactory.getForNamespace(file.getNamespace())
                    .constructGeoModel(GeometryTree.fromModel(raw));

            for (BbModelFile.Mesh mesh : geometry.meshes()) {
                GeoBone bone = model.getBone(mesh.bone()).orElseThrow();
                bone.getCubes().add(cube(mesh.faces()));
            }

            if (!project.renamed().isEmpty()) {
                LOGGER.warn("{} names two groups alike; GeckoLib finds bones by name, so these were renamed: {}",
                        file, project.renamed());
            }

            byte[] png = project.texture();

            if (png == null) {
                LOGGER.warn("{} has no embedded texture; the png beside it is used instead", file);
            } else if (project.texturesUsed() > 1) {
                LOGGER.warn("{} paints its faces from {} textures; GeckoLib draws one, so faces on the others "
                        + "show the wrong picture. Merge them into one texture in Blockbench", file,
                        project.texturesUsed());
            }

            // Not read(byte[]): that copies onto the LWJGL stack (64 KB), which a big sheet overflows.
            return new Baked(model, png == null ? null : NativeImage.read(new ByteArrayInputStream(png)));
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Could not read bbmodel {}", file, e);

            return null;
        }
    }

    @Nullable
    private static BakedAnimations animations(ResourceLocation file, Resource resource) {
        try {
            JsonObject animations = new BbModelFile(read(file, resource), file.toString()).animations();

            return animations == null ? null
                    : KeyFramesAdapter.GEO_GSON.fromJson(animations, BakedAnimations.class);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Could not read the animations of bbmodel {}", file, e);

            return null;
        }
    }

    /**
     * メッシュの面を1つのキューブに。GeckoLib の描画は面1つにつき頂点を4つ流すので、三角形は最後の頂点を重ね、
     * 5角以上は扇に分ける（{@code ObjGeometry} と同じ）。法線は面ごとの1本で、Blockbench のメッシュの平らな
     * 陰影と同じに見える。
     */
    private static GeoCube cube(List<BbModelFile.Face> faces) {
        List<GeoQuad> quads = new ArrayList<>();

        for (BbModelFile.Face face : faces) {
            int[] order = loop(face.points());
            Vector3f facing = facing(face.points(), order);
            Direction direction = Direction.getNearest(facing.x(), facing.y(), facing.z());

            for (int i = 1; i + 1 < order.length; i += 2) {
                int[] corners = { order[0], order[i], order[i + 1], order[Math.min(i + 2, order.length - 1)] };
                GeoVertex[] vertices = new GeoVertex[4];

                for (int k = 0; k < 4; k++) {
                    float[] point = face.points()[corners[k]];
                    float[] uv = face.uvs()[corners[k]];

                    vertices[k] = new GeoVertex(new Vector3f(point[0], point[1], point[2]), uv[0], uv[1]);
                }

                quads.add(new GeoQuad(vertices, new Vector3f(facing), direction));
            }
        }

        return new GeoCube(quads.toArray(GeoQuad[]::new), Vec3.ZERO, Vec3.ZERO, NOT_FLAT, 0.0, false);
    }

    /**
     * 角を輪の順に。Blockbench は四角形の頂点を輪の順に持っているとは限らない（並べ直すのは描くときの
     * {@code getSortedVertices}）。3通りの並びのうち面積が一番大きい物が、自分と交差しない輪。
     */
    private static int[] loop(float[][] points) {
        if (points.length != 4) {
            int[] order = new int[points.length];

            for (int i = 0; i < order.length; i++) {
                order[i] = i;
            }

            return order;
        }

        int[][] candidates = { { 0, 1, 2, 3 }, { 0, 2, 1, 3 }, { 0, 1, 3, 2 } };
        int[] best = candidates[0];
        float area = -1.0F;

        for (int[] candidate : candidates) {
            float size = newell(points, candidate).length();

            if (size > area) {
                area = size;
                best = candidate;
            }
        }

        return best;
    }

    private static Vector3f facing(float[][] points, int[] order) {
        Vector3f normal = newell(points, order);
        float length = normal.length();

        return length > 1.0E-12F ? normal.div(length) : new Vector3f(0.0F, 1.0F, 0.0F);
    }

    /** Newell の方法の法線（長さは面積の2倍）。反時計回りに見える側が表。 */
    private static Vector3f newell(float[][] points, int[] order) {
        float x = 0.0F;
        float y = 0.0F;
        float z = 0.0F;

        for (int i = 0; i < order.length; i++) {
            float[] p = points[order[i]];
            float[] q = points[order[(i + 1) % order.length]];

            x += (p[1] - q[1]) * (p[2] + q[2]);
            y += (p[2] - q[2]) * (p[0] + q[0]);
            z += (p[0] - q[0]) * (p[1] + q[1]);
        }

        return new Vector3f(x, y, z);
    }

    /**
     * テクスチャをその名前で登録し、前のリロードで登録して今回は無い物を手放す。描画スレッドで。
     *
     * <p>登録した {@link DynamicTexture} はテクスチャマネージャのリロードで作り直されない（{@code reset} は
     * 自分を登録し直すだけ）ので、同じ名前の png がリソースにあっても bbmodel の絵が残る。
     */
    private static void register(Map<ResourceLocation, NativeImage> textures) {
        TextureManager manager = Minecraft.getInstance().getTextureManager();

        for (ResourceLocation stale : REGISTERED) {
            if (!textures.containsKey(stale)) {
                manager.release(stale);
            }
        }

        REGISTERED.clear();

        textures.forEach((location, image) -> {
            manager.register(location, new DynamicTexture(image));
            REGISTERED.add(location);
        });
    }
}
