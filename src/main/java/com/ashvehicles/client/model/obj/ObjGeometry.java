package com.ashvehicles.client.model.obj;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.joml.Vector3f;
import org.slf4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.Direction;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
import software.bernie.geckolib.loading.json.raw.ModelProperties;

/**
 * OBJ ファイル1つを、GeckoLib が geo.json から焼くのと同じ {@link BakedGeoModel} に焼く。
 *
 * <p><b>座標はそのまま入れる。</b>焼いた後の GeckoLib の空間は Blockbench の画面と同じ——x が右、y が上、
 * 機首が −Z——で、geo.json の x の反転は Bedrock 書き出しと GeckoLib の焼き込みで2回起きて相殺している
 * （記憶ノート {@code geo-coordinates-to-aircraft-axes}）。OBJ にはその反転がどちらも無いので、頂点は
 * 1単位＝1ブロックのまま、支点だけモデル単位（×16）にして入れる。UV の v だけは OBJ が下から、テクスチャが
 * 上から数えるので裏返す。
 *
 * <p><b>ボーンは {@code o} の名前。</b>{@code o} を1つも持たない書き出し元のために、最初の {@code o} より前の
 * {@code g} も名前として読む。{@code o} の後の {@code g} は読まない——Blender は材質ごとに
 * {@code g 物体_材質} を書くので、それを読むと1つの部品が材質の数に割れ、定義ファイルに書いた名前が消える。
 * 同じ名前のグループは1つのボーンにまとまる。
 *
 * <p><b>OBJ が持たない物——親子と支点——は横の {@code .bones.json} から。</b>無ければ全ボーンが根に並び、支点は
 * 各ボーン自身の箱の中心になる。車輪・回転翼・砲塔の旋回はそれで回るが、蝶番で振れる部品（舵面・脚・扉・砲の
 * 俯仰）と、砲塔に砲を連れて回らせる親子はファイルに書く必要がある。
 *
 * <pre>
 * { "bones": {
 *     "turret": { "pivot": [0, 1.6, 0.4] },
 *     "gun":    { "parent": "turret", "pivot": [0, 2.1, -0.9] } } }
 * </pre>
 *
 * 数値は OBJ と同じ空間・同じ単位。OBJ に無い名前を書けば形を持たない中継ぎのボーンになる。<b>静止回転は
 * 持たない</b>——OBJ の頂点は既に置かれた場所にあるので、ボーンを回すと二重に回る。
 */
public final class ObjGeometry {
    /** 最初の面より前に {@code o} も {@code g} も無かったとき、その面が入るボーン。 */
    public static final String UNGROUPED = "root";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final float UNITS = 16.0F;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * 形を測る側が「平らな面」と読まない大きさ。GeckoLib は大きさに0の軸を持つキューブの法線を裏返す
     * （{@code RenderUtil.fixInvertedFlatCube}）ので、平面1枚のグループが裏から照らされないように。
     */
    private static final Vec3 NOT_FLAT = new Vec3(1.0, 1.0, 1.0);

    private ObjGeometry() {
    }

    /**
     * @param obj OBJ の本文
     * @param bones 親子と支点。無ければ null
     * @param source ログとモデルの名前に使う
     */
    public static BakedGeoModel bake(Reader obj, @Nullable Reader bones, String source) throws IOException {
        Mesh mesh = read(obj, source);
        Map<String, Joint> joints = bones == null ? Map.of() : joints(JsonParser.parseReader(bones), source);

        return build(mesh, joints, source);
    }

    /** OBJ に書かれた頂点・UV・法線と、ボーンごとの面。 */
    private static final class Mesh {
        final FloatArrayList positions = new FloatArrayList();
        final FloatArrayList uvs = new FloatArrayList();
        final FloatArrayList normals = new FloatArrayList();
        final Map<String, Group> groups = new LinkedHashMap<>();

        Group group(String name) {
            return this.groups.computeIfAbsent(name, Group::new);
        }
    }

    private static final class Group {
        final String name;

        /** 面の角ごとに位置・UV・法線の添字。書かれていなければ −1。 */
        final IntArrayList corners = new IntArrayList();

        /** 面ごとの角の数。 */
        final IntArrayList sizes = new IntArrayList();

        Group(String name) {
            this.name = name;
        }
    }

    /** {@code .bones.json} の1行。 */
    private record Joint(@Nullable String parent, @Nullable Vector3f pivot) {
    }

    /** 1ボーンぶんの焼いた形。 */
    private record Part(GeoCube cube, float[] normals, Vector3f min, Vector3f max) {
        Vector3f centre() {
            return new Vector3f(this.min).add(this.max).mul(0.5F);
        }
    }

    private static Mesh read(Reader source, String name) throws IOException {
        Mesh mesh = new Mesh();
        BufferedReader lines = source instanceof BufferedReader buffered ? buffered : new BufferedReader(source);
        Group current = null;
        boolean objects = false;
        int number = 0;
        String line;

        while ((line = lines.readLine()) != null) {
            number++;
            String text = line.strip();

            if (text.isEmpty() || text.charAt(0) == '#') {
                continue;
            }

            String[] words = WHITESPACE.split(text);

            try {
                switch (words[0]) {
                    case "v" -> numbers(mesh.positions, words, 3, 3);
                    case "vt" -> numbers(mesh.uvs, words, 2, 1);
                    case "vn" -> numbers(mesh.normals, words, 3, 3);
                    case "o" -> {
                        objects = true;
                        current = mesh.group(nameOf(text));
                    }
                    case "g" -> {
                        if (!objects) {
                            current = mesh.group(nameOf(text));
                        }
                    }
                    case "f" -> {
                        if (current == null) {
                            current = mesh.group(UNGROUPED);
                        }

                        face(mesh, current, words);
                    }
                    // 材質・スムージング・線・点は描く物を変えない。テクスチャはモデルと同じ名前の png から。
                    default -> {
                    }
                }
            } catch (RuntimeException e) {
                throw new IOException(name + " line " + number + ": " + e.getMessage(), e);
            }
        }

        return mesh;
    }

    /** キーワードの後ろ全部。名前に空白を含む書き出し元がある。 */
    private static String nameOf(String text) {
        String name = text.substring(1).strip();

        return name.isEmpty() ? UNGROUPED : name;
    }

    /** 足りない分は0で埋める。{@code vt} の v を省く書き出し元がある。余った数（色・w）は捨てる。 */
    private static void numbers(FloatArrayList into, String[] words, int count, int required) {
        if (words.length - 1 < required) {
            throw new IllegalArgumentException("'" + words[0] + "' needs " + required + " numbers");
        }

        for (int i = 1; i <= count; i++) {
            into.add(i < words.length ? Float.parseFloat(words[i]) : 0.0F);
        }
    }

    private static void face(Mesh mesh, Group group, String[] words) {
        int corners = words.length - 1;

        if (corners < 3) {
            throw new IllegalArgumentException("a face needs at least 3 corners");
        }

        for (int i = 1; i <= corners; i++) {
            String[] parts = words[i].split("/", -1);

            group.corners.add(index(parts[0], mesh.positions.size() / 3));
            group.corners.add(parts.length > 1 && !parts[1].isEmpty() ? index(parts[1], mesh.uvs.size() / 2) : -1);
            group.corners.add(parts.length > 2 && !parts[2].isEmpty()
                    ? index(parts[2], mesh.normals.size() / 3) : -1);
        }

        group.sizes.add(corners);
    }

    /**
     * 1始まりの添字を0始まりへ。負の添字は「ここまでに書かれた物の後ろから」なので、読んだ瞬間の数で解く。
     * 正の添字の範囲は全部読んだ後で確かめる——後ろに書かれた頂点を指すファイルもある。
     */
    private static int index(String word, int writtenSoFar) {
        int written = Integer.parseInt(word);

        if (written == 0) {
            throw new IllegalArgumentException("index 0 (OBJ counts from 1)");
        }

        return written < 0 ? writtenSoFar + written : written - 1;
    }

    private static BakedGeoModel build(Mesh mesh, Map<String, Joint> joints, String source) {
        Map<String, Part> parts = new LinkedHashMap<>();

        for (Group group : mesh.groups.values()) {
            if (!group.sizes.isEmpty()) {
                parts.put(group.name, bake(mesh, group));
            }
        }

        Set<String> names = new LinkedHashSet<>(parts.keySet());
        names.addAll(joints.keySet());

        Map<String, String> parents = parents(names, joints, source);
        Map<String, List<String>> children = new LinkedHashMap<>();
        List<String> tops = new ArrayList<>();

        for (String name : names) {
            children.put(name, new ArrayList<>());
        }

        for (String name : names) {
            String parent = parents.get(name);

            if (parent == null) {
                tops.add(name);
            } else {
                children.get(parent).add(name);
            }
        }

        List<GeoBone> bones = new ArrayList<>();

        for (String top : tops) {
            bones.add(bone(top, null, parts, joints, children));
        }

        return new BakedGeoModel(bones, properties(parts.values(), source));
    }

    /** 名前から親の名前へ。知らない親と輪になった親は捨てて根に置く。 */
    private static Map<String, String> parents(Set<String> names, Map<String, Joint> joints, String source) {
        Map<String, String> parents = new LinkedHashMap<>();

        for (String name : names) {
            Joint joint = joints.get(name);
            String parent = joint == null ? null : joint.parent();

            if (parent != null && !names.contains(parent)) {
                LOGGER.warn("{}: bone '{}' names parent '{}', which is neither a group nor a bone; it stays at the root",
                        source, name, parent);
                parent = null;
            }

            parents.put(name, parent);
        }

        for (String name : names) {
            String above = parents.get(name);

            for (int steps = 0; above != null && !above.equals(name) && steps < names.size(); steps++) {
                above = parents.get(above);
            }

            if (name.equals(above)) {
                LOGGER.warn("{}: bone '{}' is its own ancestor; it stays at the root", source, name);
                parents.put(name, null);
            }
        }

        return parents;
    }

    private static GeoBone bone(String name, @Nullable GeoBone parent, Map<String, Part> parts,
            Map<String, Joint> joints, Map<String, List<String>> children) {
        Part part = parts.get(name);
        GeoBone bone = part == null
                ? new GeoBone(parent, name, null, null, null, null)
                : new MeshBone(parent, name, part.cube(), part.normals());
        Joint joint = joints.get(name);
        Vector3f pivot = joint != null && joint.pivot() != null ? joint.pivot()
                : part != null ? part.centre()
                : new Vector3f();

        bone.updatePivot(pivot.x() * UNITS, pivot.y() * UNITS, pivot.z() * UNITS);

        for (String child : children.get(name)) {
            bone.getChildBones().add(bone(child, bone, parts, joints, children));
        }

        return bone;
    }

    /**
     * グループの面を GeckoLib の面へ。GeckoLib の描画は面1つにつき頂点を4つ流す（エンティティの描画型は
     * 四角形）ので、三角形は最後の頂点を重ねた四角形に、5角以上は扇に並べた四角形にする。どちらも凸で平らな
     * 面なら元の面と同じ物が描かれる。
     */
    private static Part bake(Mesh mesh, Group group) {
        int positions = mesh.positions.size() / 3;
        int uvs = mesh.uvs.size() / 2;
        int normalCount = mesh.normals.size() / 3;

        for (int at = 0; at < group.corners.size(); at += 3) {
            check(group.corners.getInt(at), positions, "vertex", group);
            check(group.corners.getInt(at + 1), uvs, "texture coordinate", group);
            check(group.corners.getInt(at + 2), normalCount, "normal", group);
        }

        List<GeoQuad> quads = new ArrayList<>();
        FloatArrayList normals = new FloatArrayList();
        Vector3f min = new Vector3f(Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE);
        int start = 0;

        for (int face = 0; face < group.sizes.size(); face++) {
            int size = group.sizes.getInt(face);
            Vector3f facing = facing(mesh, group.corners, start, size);

            for (int i = 1; i + 1 < size; i += 2) {
                int[] order = { 0, i, i + 1, Math.min(i + 2, size - 1) };

                quads.add(quad(mesh, group.corners, start, order, facing, normals, min, max));
            }

            start += size * 3;
        }

        GeoCube cube = new GeoCube(quads.toArray(GeoQuad[]::new), Vec3.ZERO, Vec3.ZERO, NOT_FLAT, 0.0, false);

        return new Part(cube, normals.toFloatArray(), min, max);
    }

    private static void check(int index, int count, String what, Group group) {
        if (index >= count || index < -1) {
            throw new IllegalArgumentException("group '" + group.name + "' refers to " + what + " " + (index + 1)
                    + " but the file has " + count);
        }
    }

    private static GeoQuad quad(Mesh mesh, IntArrayList corners, int start, int[] order, Vector3f facing,
            FloatArrayList normals, Vector3f min, Vector3f max) {
        GeoVertex[] vertices = new GeoVertex[4];

        for (int k = 0; k < 4; k++) {
            int at = start + order[k] * 3;
            int position = corners.getInt(at) * 3;
            int uv = corners.getInt(at + 1);
            int normal = corners.getInt(at + 2);
            Vector3f point = new Vector3f(mesh.positions.getFloat(position), mesh.positions.getFloat(position + 1),
                    mesh.positions.getFloat(position + 2));

            vertices[k] = uv < 0
                    ? new GeoVertex(point, 0.0F, 0.0F)
                    : new GeoVertex(point, mesh.uvs.getFloat(uv * 2), 1.0F - mesh.uvs.getFloat(uv * 2 + 1));
            min.min(point);
            max.max(point);

            Vector3f written = normal < 0 ? null : new Vector3f(mesh.normals.getFloat(normal * 3),
                    mesh.normals.getFloat(normal * 3 + 1), mesh.normals.getFloat(normal * 3 + 2));
            Vector3f towards = written != null && written.lengthSquared() > 1.0E-12F ? written.normalize() : facing;

            normals.add(towards.x());
            normals.add(towards.y());
            normals.add(towards.z());
        }

        return new GeoQuad(vertices, new Vector3f(facing), Direction.getNearest(facing.x(), facing.y(), facing.z()));
    }

    /**
     * 面の向き。Newell の方法で、角が4つ以上の少し歪んだ面でも1つに決まる。反時計回りに見える側が表——OBJ
     * の約束で、GeckoLib が焼く面の並びとも同じ。
     */
    private static Vector3f facing(Mesh mesh, IntArrayList corners, int start, int size) {
        float x = 0.0F;
        float y = 0.0F;
        float z = 0.0F;

        for (int i = 0; i < size; i++) {
            int p = corners.getInt(start + i * 3) * 3;
            int q = corners.getInt(start + (i + 1) % size * 3) * 3;
            float px = mesh.positions.getFloat(p);
            float py = mesh.positions.getFloat(p + 1);
            float pz = mesh.positions.getFloat(p + 2);
            float qx = mesh.positions.getFloat(q);
            float qy = mesh.positions.getFloat(q + 1);
            float qz = mesh.positions.getFloat(q + 2);

            x += (py - qy) * (pz + qz);
            y += (pz - qz) * (px + qx);
            z += (px - qx) * (py + qy);
        }

        Vector3f facing = new Vector3f(x, y, z);
        float length = facing.length();

        return length > 1.0E-12F ? facing.div(length) : new Vector3f(0.0F, 1.0F, 0.0F);
    }

    /**
     * GeckoLib が geo.json の {@code description} から読む物。テクスチャの寸法は UV を割るためだけのもので、
     * OBJ の UV は既に割ってあるので使わない。見える範囲は形から測る。
     */
    private static ModelProperties properties(Iterable<Part> parts, String source) {
        Vector3f min = new Vector3f(Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE);
        boolean any = false;

        for (Part part : parts) {
            min.min(part.min());
            max.max(part.max());
            any = true;
        }

        if (!any) {
            min.zero();
            max.zero();
        }

        double width = Math.max(max.x() - min.x(), max.z() - min.z());
        double height = max.y() - min.y();

        return new ModelProperties(null, null, null, null, null, null, null, null, null, null,
                "geometry." + source, null, 1.0, 1.0, height,
                new double[] { 0.0, (min.y() + max.y()) * 0.5, 0.0 }, width);
    }

    private static Map<String, Joint> joints(JsonElement root, String source) {
        Map<String, Joint> joints = new LinkedHashMap<>();
        JsonObject bones = GsonHelper.getAsJsonObject(GsonHelper.convertToJsonObject(root, source), "bones");

        for (Map.Entry<String, JsonElement> entry : bones.entrySet()) {
            JsonObject bone = GsonHelper.convertToJsonObject(entry.getValue(), entry.getKey());
            String parent = GsonHelper.getAsString(bone, "parent", null);
            Vector3f pivot = null;

            if (bone.has("pivot")) {
                JsonArray numbers = GsonHelper.getAsJsonArray(bone, "pivot");

                if (numbers.size() != 3) {
                    throw new JsonParseException(source + ": pivot of '" + entry.getKey() + "' needs 3 numbers");
                }

                pivot = new Vector3f(numbers.get(0).getAsFloat(), numbers.get(1).getAsFloat(),
                        numbers.get(2).getAsFloat());
            }

            joints.put(entry.getKey(), new Joint(parent, pivot));
        }

        return joints;
    }
}
