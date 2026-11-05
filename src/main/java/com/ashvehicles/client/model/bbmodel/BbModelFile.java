package com.ashvehicles.client.model.bbmodel;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;

/**
 * Blockbench のプロジェクトファイル（{@code .bbmodel}）1つを、Blockbench 自身が Bedrock / GeckoLib 形式で書き出す
 * のと同じ JSON に組み直す。ゲームの型には触らない——JSON から JSON へだけ——ので、ゲームの外で書き出し済みの
 * geo.json と突き合わせて確かめられる。
 *
 * <p><b>座標の約束は Blockbench の書き出しの写し。</b>Blockbench の画面は x が右だが Bedrock の geo は x が左なので、
 * x にかかる物だけ符号を返す: ボーンの支点 {@code [-x, y, z]}、回転 {@code [-rx, -ry, rz]}、キューブの原点は
 * {@code [-to.x, from.y, from.z]}。GeckoLib は焼くときにもう一度 x を返すので、焼いた後は Blockbench の画面に戻る
 * （記憶ノート {@code geo-coordinates-to-aircraft-axes}）。面ごとの UV は {@code up} と {@code down} だけ
 * 半回転して（終点から始点へ）書く。
 *
 * <p>グループの持ち方は版で違う。4.x は {@code outliner} の入れ子にグループの全部を書き、5.0 は {@code groups} の
 * 一覧に書いて {@code outliner} は uuid と子だけを持つ。両方を重ねて読む。
 *
 * <p>メッシュ（{@code type: mesh}）は geo.json に書けない（GeckoLib は {@code poly_mesh} を描かない）ので、ここでは
 * 置かれた場所と UV を {@link Mesh} にして返すだけ。焼くのは {@link BbModels}。
 */
public final class BbModelFile {
    /** グループの外に置かれた要素が入るボーン。Blockbench の書き出しと同じ名前。 */
    public static final String LOOSE = "bb_main";

    private static final String DATA_URL = "base64,";

    private final JsonObject root;
    private final String source;
    private final Map<String, JsonObject> elements = new HashMap<>();
    private final Map<String, JsonObject> groups = new HashMap<>();

    /** グループの uuid → ボーンの名前。同じ名前が2つあれば後の方に {@code _2} などを付けてある。 */
    private final Map<String, String> names = new HashMap<>();

    /** 付け直した名前。ログに出すため。 */
    private final List<String> renamed = new ArrayList<>();

    public BbModelFile(JsonObject root, String source) {
        this.root = root;
        this.source = source;

        for (JsonElement element : array(root, "elements")) {
            JsonObject object = element.getAsJsonObject();
            this.elements.put(string(object, "uuid", ""), object);
        }

        for (JsonElement group : array(root, "groups")) {
            JsonObject object = group.getAsJsonObject();
            this.groups.put(string(object, "uuid", ""), object);
        }

        this.name(array(root, "outliner"), new HashSet<>());
    }

    /**
     * ボーンの名前を outliner の順に決める。GeckoLib はボーンを名前で引くので同じ名前は2つ置けないが、
     * Blockbench の一部の形式（Java ブロックなど）は許す。後から出てきた方に番号を付けて読む。
     */
    private void name(JsonArray nodes, Set<String> taken) {
        for (JsonElement node : nodes) {
            if (!node.isJsonObject()) {
                continue;
            }

            JsonObject entry = node.getAsJsonObject();
            String uuid = string(entry, "uuid", "");
            JsonObject listed = this.groups.get(uuid);
            String name = string(entry, "name", listed == null ? "bone" : string(listed, "name", "bone"));
            String unique = name;

            for (int n = 2; !taken.add(unique); n++) {
                unique = name + "_" + n;
            }

            if (!unique.equals(name)) {
                this.renamed.add(name + " -> " + unique);
            }

            this.names.put(uuid, unique);
            this.name(array(entry, "children"), taken);
        }
    }

    /** 名前が重なって付け直したボーン（{@code 元 -> 新}）。 */
    public List<String> renamed() {
        return this.renamed;
    }

    /** メッシュ1つの面。頂点は Blockbench の画面の空間でブロック単位、UV はテクスチャの幅と高さで割った値。 */
    public record Face(float[][] points, float[][] uvs) {
    }

    /** ボーンに付くメッシュの面の集まり。 */
    public record Mesh(String bone, List<Face> faces) {
    }

    /** {@link #geometry} の答え。 */
    public record Geometry(JsonObject json, List<Mesh> meshes) {
    }

    /**
     * geo.json の本文と、geo.json に書けないメッシュ。
     *
     * @param identifier {@code description.identifier} に入れる名前（{@code geometry.} は付けて渡す）
     */
    public Geometry geometry(String identifier) {
        Map<String, JsonObject> bones = new LinkedHashMap<>();
        List<Mesh> meshes = new ArrayList<>();
        boolean boxUv = this.boxUv();

        for (JsonElement node : array(this.root, "outliner")) {
            this.walk(node, null, bones, meshes, boxUv);
        }

        int[] size = this.uvSize();
        JsonObject description = new JsonObject();
        description.addProperty("identifier", identifier);
        description.addProperty("texture_width", size[0]);
        description.addProperty("texture_height", size[1]);
        description.addProperty("visible_bounds_width", 4);
        description.addProperty("visible_bounds_height", 4);
        description.add("visible_bounds_offset", vector(0, 1, 0));

        JsonArray boneList = new JsonArray();
        bones.values().forEach(boneList::add);

        JsonObject geometry = new JsonObject();
        geometry.add("description", description);
        geometry.add("bones", boneList);

        JsonArray geometries = new JsonArray();
        geometries.add(geometry);

        JsonObject json = new JsonObject();
        json.addProperty("format_version", "1.12.0");
        json.add("minecraft:geometry", geometries);

        return new Geometry(json, meshes);
    }

    private void walk(JsonElement node, @Nullable String parent, Map<String, JsonObject> bones, List<Mesh> meshes,
            boolean boxUv) {
        if (node.isJsonPrimitive()) {
            JsonObject element = this.elements.get(node.getAsString());

            if (element != null && bool(element, "export", true)) {
                this.place(element, parent == null ? loose(bones) : parent, bones, meshes, boxUv);
            }

            return;
        }

        JsonObject entry = node.getAsJsonObject();
        JsonObject group = new JsonObject();
        JsonObject listed = this.groups.get(string(entry, "uuid", ""));

        if (listed != null) {
            listed.entrySet().forEach(field -> group.add(field.getKey(), field.getValue()));
        }

        entry.entrySet().forEach(field -> group.add(field.getKey(), field.getValue()));

        if (!bool(group, "export", true)) {
            return;
        }

        String name = this.names.getOrDefault(string(group, "uuid", ""), string(group, "name", "bone"));
        float[] origin = floats(group, "origin", 0.0F);
        float[] rotation = floats(group, "rotation", 0.0F);
        JsonObject bone = new JsonObject();
        bone.addProperty("name", name);

        if (parent != null) {
            bone.addProperty("parent", parent);
        }

        bone.add("pivot", vector(-origin[0], origin[1], origin[2]));

        if (rotation[0] != 0.0F || rotation[1] != 0.0F || rotation[2] != 0.0F) {
            bone.add("rotation", vector(-rotation[0], -rotation[1], rotation[2]));
        }

        bone.add("cubes", new JsonArray());

        bones.put(name, bone);

        for (JsonElement child : array(group, "children")) {
            this.walk(child, name, bones, meshes, boxUv);
        }
    }

    private static String loose(Map<String, JsonObject> bones) {
        bones.computeIfAbsent(LOOSE, name -> {
            JsonObject bone = new JsonObject();
            bone.addProperty("name", name);
            bone.add("pivot", vector(0, 0, 0));
            bone.add("cubes", new JsonArray());

            return bone;
        });

        return LOOSE;
    }

    private void place(JsonObject element, String bone, Map<String, JsonObject> bones, List<Mesh> meshes,
            boolean boxUv) {
        String type = string(element, "type", "cube");

        if ("mesh".equals(type)) {
            meshes.add(new Mesh(bone, this.faces(element)));
        } else if ("cube".equals(type)) {
            bones.get(bone).getAsJsonArray("cubes").add(this.cube(element, boxUv));
        }
        // 他（locator・null_object・テクスチャメッシュ）は形を持たない。
    }

    private JsonObject cube(JsonObject element, boolean boxUv) {
        double[] from = doubles(element, "from");
        double[] to = doubles(element, "to");
        double[] rotation = doubles(element, "rotation");
        double inflate = element.has("inflate") ? element.get("inflate").getAsDouble() : 0.0;
        JsonObject cube = new JsonObject();

        cube.add("origin", exact(-to[0], from[1], from[2]));
        cube.add("size", exact(to[0] - from[0], to[1] - from[1], to[2] - from[2]));

        if (inflate != 0.0) {
            cube.addProperty("inflate", inflate);
        }

        if (rotation[0] != 0.0 || rotation[1] != 0.0 || rotation[2] != 0.0) {
            double[] origin = doubles(element, "origin");

            cube.add("pivot", exact(-origin[0], origin[1], origin[2]));
            cube.add("rotation", exact(-rotation[0], -rotation[1], rotation[2]));
        }

        if (bool(element, "box_uv", boxUv)) {
            float[] offset = floats(element, "uv_offset", 0.0F);
            JsonArray uv = new JsonArray();
            uv.add(offset[0]);
            uv.add(offset[1]);
            cube.add("uv", uv);

            if (bool(element, "mirror_uv", false)) {
                cube.addProperty("mirror", true);
            }

            return cube;
        }

        JsonObject faces = new JsonObject();
        JsonObject written = element.has("faces") ? element.getAsJsonObject("faces") : new JsonObject();

        for (Map.Entry<String, JsonElement> entry : written.entrySet()) {
            JsonObject face = entry.getValue().getAsJsonObject();

            if (!textured(face)) {
                continue;
            }

            float[] uv = floats(face, "uv", 0.0F, 4);
            JsonObject out = new JsonObject();

            if ("up".equals(entry.getKey()) || "down".equals(entry.getKey())) {
                out.add("uv", pair(uv[2], uv[3]));
                out.add("uv_size", pair(uv[0] - uv[2], uv[1] - uv[3]));
            } else {
                out.add("uv", pair(uv[0], uv[1]));
                out.add("uv_size", pair(uv[2] - uv[0], uv[3] - uv[1]));
            }

            if (face.has("rotation") && face.get("rotation").getAsInt() != 0) {
                out.addProperty("uv_rotation", face.get("rotation").getAsInt());
            }

            faces.add(entry.getKey(), out);
        }

        cube.add("uv", faces);

        return cube;
    }

    /**
     * メッシュの面を、置かれた場所へ。頂点は要素の原点からの相対で、要素の回転（Blockbench の要素は Z→Y→X、
     * ベクトルには X から掛かる）を掛けてから原点を足す。それで Blockbench の画面の座標になり、16 で割れば
     * GeckoLib が焼いた後の空間と同じ（{@code ObjGeometry} が OBJ の頂点をそのまま入れるのと同じ理由）。
     */
    private List<Face> faces(JsonObject element) {
        float[] origin = floats(element, "origin", 0.0F);
        float[] rotation = floats(element, "rotation", 0.0F);
        int[] size = this.uvSize();
        JsonObject vertices = element.has("vertices") ? element.getAsJsonObject("vertices") : new JsonObject();
        JsonObject written = element.has("faces") ? element.getAsJsonObject("faces") : new JsonObject();
        List<Face> faces = new ArrayList<>();

        for (Map.Entry<String, JsonElement> entry : written.entrySet()) {
            JsonObject face = entry.getValue().getAsJsonObject();

            if (!textured(face)) {
                continue;
            }

            JsonArray keys = face.getAsJsonArray("vertices");
            JsonObject uv = face.has("uv") ? face.getAsJsonObject("uv") : new JsonObject();

            if (keys == null || keys.size() < 3) {
                continue;
            }

            float[][] points = new float[keys.size()][];
            float[][] uvs = new float[keys.size()][];

            for (int i = 0; i < keys.size(); i++) {
                String key = keys.get(i).getAsString();
                JsonArray at = vertices.getAsJsonArray(key);

                if (at == null) {
                    throw new JsonParseException(this.source + ": a mesh face refers to a missing vertex " + key);
                }

                float[] point = rotate(at.get(0).getAsFloat(), at.get(1).getAsFloat(), at.get(2).getAsFloat(),
                        rotation);

                points[i] = new float[] { (point[0] + origin[0]) / 16.0F, (point[1] + origin[1]) / 16.0F,
                        (point[2] + origin[2]) / 16.0F };

                JsonArray texel = uv.getAsJsonArray(key);
                uvs[i] = texel == null ? new float[2]
                        : new float[] { texel.get(0).getAsFloat() / size[0], texel.get(1).getAsFloat() / size[1] };
            }

            faces.add(new Face(points, uvs));
        }

        return faces;
    }

    /** 度で書かれた回転を X、Y、Z の順にベクトルへ掛ける（行列は Rz·Ry·Rx）。 */
    private static float[] rotate(float x, float y, float z, float[] degrees) {
        double a = Math.toRadians(degrees[0]);
        double b = Math.toRadians(degrees[1]);
        double c = Math.toRadians(degrees[2]);
        double y1 = y * Math.cos(a) - z * Math.sin(a);
        double z1 = y * Math.sin(a) + z * Math.cos(a);
        double x2 = x * Math.cos(b) + z1 * Math.sin(b);
        double z2 = -x * Math.sin(b) + z1 * Math.cos(b);
        double x3 = x2 * Math.cos(c) - y1 * Math.sin(c);
        double y3 = x2 * Math.sin(c) + y1 * Math.cos(c);

        return new float[] { (float) x3, (float) y3, (float) z2 };
    }

    /**
     * GeckoLib の {@code animations} の中身（アニメーション名 → 本文）。アニメーションを持たなければ null。
     *
     * <p>キーフレームの値は Blockbench が Bedrock へ書き出すときと同じくそのまま——回転も位置も、Blockbench は
     * 画面に出すときに x と y を返しており、ファイルの値は既に Bedrock の向き。数に読めない値は Molang として
     * 文字列のまま渡す。ベジェは GeckoLib に無いので線形になる。
     */
    @Nullable
    public JsonObject animations() {
        JsonArray written = array(this.root, "animations");

        if (written.isEmpty()) {
            return null;
        }

        JsonObject animations = new JsonObject();

        for (JsonElement element : written) {
            JsonObject animation = element.getAsJsonObject();
            JsonObject out = new JsonObject();
            String loop = string(animation, "loop", "once");

            if ("loop".equals(loop)) {
                out.addProperty("loop", true);
            } else if ("hold".equals(loop)) {
                out.addProperty("loop", "hold_on_last_frame");
            }

            double length = animation.has("length") ? animation.get("length").getAsDouble() : 0.0;

            if (length > 0.0) {
                out.addProperty("animation_length", length);
            }

            JsonObject bones = new JsonObject();
            JsonObject animators = animation.has("animators") ? animation.getAsJsonObject("animators")
                    : new JsonObject();

            for (Map.Entry<String, JsonElement> entry : animators.entrySet()) {
                JsonObject animator = entry.getValue().getAsJsonObject();

                if (!"bone".equals(string(animator, "type", "bone"))) {
                    continue;
                }

                String name = this.names.getOrDefault(entry.getKey(), string(animator, "name", ""));
                JsonObject channels = channels(array(animator, "keyframes"));

                if (!name.isEmpty() && channels.size() > 0) {
                    bones.add(name, channels);
                }
            }

            out.add("bones", bones);
            animations.add(string(animation, "name", "animation"), out);
        }

        return animations;
    }

    private static JsonObject channels(JsonArray keyframes) {
        Map<String, TreeMap<Double, JsonElement>> channels = new LinkedHashMap<>();

        for (JsonElement element : keyframes) {
            JsonObject keyframe = element.getAsJsonObject();
            String channel = string(keyframe, "channel", "");

            if (!"rotation".equals(channel) && !"position".equals(channel) && !"scale".equals(channel)) {
                continue;
            }

            JsonArray points = array(keyframe, "data_points");

            if (points.isEmpty()) {
                continue;
            }

            JsonArray post = point(points.get(points.size() - 1).getAsJsonObject());
            String interpolation = string(keyframe, "interpolation", "linear");
            JsonElement value;

            if (points.size() > 1 || "catmullrom".equals(interpolation) || "step".equals(interpolation)) {
                JsonObject shaped = new JsonObject();

                if (points.size() > 1) {
                    shaped.add("pre", point(points.get(0).getAsJsonObject()));
                }

                shaped.add("post", post);

                if ("catmullrom".equals(interpolation) || "step".equals(interpolation)) {
                    shaped.addProperty("lerp_mode", interpolation);
                }

                value = shaped;
            } else {
                value = post;
            }

            channels.computeIfAbsent(channel, key -> new TreeMap<>())
                    .put(keyframe.has("time") ? keyframe.get("time").getAsDouble() : 0.0, value);
        }

        JsonObject out = new JsonObject();

        channels.forEach((channel, frames) -> {
            JsonObject timeline = new JsonObject();
            frames.forEach((time, value) -> timeline.add(Double.toString(time), value));
            out.add(channel, timeline);
        });

        return out;
    }

    private static JsonArray point(JsonObject point) {
        JsonArray vector = new JsonArray();

        for (String axis : new String[] { "x", "y", "z" }) {
            JsonElement value = point.get(axis);

            if (value == null || value.isJsonNull()) {
                vector.add(0);
                continue;
            }

            String text = value.getAsString().trim();

            try {
                vector.add(text.isEmpty() ? 0.0 : Double.parseDouble(text));
            } catch (NumberFormatException molang) {
                vector.add(new JsonPrimitive(text));
            }
        }

        return vector;
    }

    /**
     * 模型が描くテクスチャの PNG。面が一番多く使うものを1枚。プロジェクトに埋め込まれていなければ（外部ファイル
     * を参照しているだけなら）null。
     *
     * <p>GeckoLib の模型はテクスチャを1枚しか持てないので、複数を使い分ける模型は1枚にまとめる必要がある。
     */
    @Nullable
    public byte[] texture() {
        JsonObject chosen = this.chosenTexture();

        if (chosen == null) {
            return null;
        }

        String data = string(chosen, "source", "");
        int start = data.indexOf(DATA_URL);

        return start < 0 ? null : Base64.getMimeDecoder().decode(data.substring(start + DATA_URL.length()));
    }

    /** 面が使うテクスチャの数。1より多ければ、選ばなかった方の面は違う絵で描かれる。 */
    public int texturesUsed() {
        return this.textureUse().size();
    }

    @Nullable
    private JsonObject chosenTexture() {
        JsonArray textures = array(this.root, "textures");

        if (textures.isEmpty()) {
            return null;
        }

        Map<String, Integer> use = this.textureUse();
        String best = null;

        for (Map.Entry<String, Integer> entry : use.entrySet()) {
            if (best == null || entry.getValue() > use.get(best)) {
                best = entry.getKey();
            }
        }

        if (best != null) {
            for (int i = 0; i < textures.size(); i++) {
                JsonObject texture = textures.get(i).getAsJsonObject();

                if (best.equals(Integer.toString(i)) || best.equals(string(texture, "uuid", null))) {
                    return texture;
                }
            }
        }

        return textures.get(0).getAsJsonObject();
    }

    /** テクスチャ（番号か uuid）ごとの、それを使う面の数。 */
    private Map<String, Integer> textureUse() {
        Map<String, Integer> use = new HashMap<>();

        for (JsonObject element : this.elements.values()) {
            if (!element.has("faces")) {
                continue;
            }

            for (Map.Entry<String, JsonElement> face : element.getAsJsonObject("faces").entrySet()) {
                JsonElement texture = face.getValue().getAsJsonObject().get("texture");

                if (texture != null && texture.isJsonPrimitive() && !texture.getAsJsonPrimitive().isBoolean()) {
                    use.merge(texture.getAsString(), 1, Integer::sum);
                }
            }
        }

        return use;
    }

    /** UV を数える寸法。テクスチャが自分の UV 寸法を持っていればそれ、無ければプロジェクトの解像度。 */
    private int[] uvSize() {
        JsonObject texture = this.chosenTexture();

        if (texture != null && texture.has("uv_width") && texture.has("uv_height")) {
            return new int[] { texture.get("uv_width").getAsInt(), texture.get("uv_height").getAsInt() };
        }

        JsonObject resolution = this.root.has("resolution") ? this.root.getAsJsonObject("resolution")
                : new JsonObject();

        return new int[] { resolution.has("width") ? resolution.get("width").getAsInt() : 16,
                resolution.has("height") ? resolution.get("height").getAsInt() : 16 };
    }

    private boolean boxUv() {
        JsonObject meta = this.root.has("meta") ? this.root.getAsJsonObject("meta") : new JsonObject();

        return bool(meta, "box_uv", false);
    }

    /** 描かれる面か。Blockbench はテクスチャを外した面を {@code texture: null} で残す。 */
    private static boolean textured(JsonObject face) {
        JsonElement texture = face.get("texture");

        return texture == null || !texture.isJsonNull()
                && !(texture.isJsonPrimitive() && texture.getAsJsonPrimitive().isBoolean() && !texture.getAsBoolean());
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement value = object.get(key);

        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static String string(JsonObject object, String key, @Nullable String fallback) {
        JsonElement value = object.get(key);

        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement value = object.get(key);

        return value != null && value.isJsonPrimitive() ? value.getAsBoolean() : fallback;
    }

    private static float[] floats(JsonObject object, String key, float fallback) {
        return floats(object, key, fallback, 3);
    }

    private static float[] floats(JsonObject object, String key, float fallback, int count) {
        float[] numbers = new float[count];
        JsonArray value = array(object, key);

        for (int i = 0; i < count; i++) {
            numbers[i] = i < value.size() ? value.get(i).getAsFloat() : fallback;
        }

        return numbers;
    }

    private static double[] doubles(JsonObject object, String key) {
        double[] numbers = new double[3];
        JsonArray value = array(object, key);

        for (int i = 0; i < 3 && i < value.size(); i++) {
            numbers[i] = value.get(i).getAsDouble();
        }

        return numbers;
    }

    /**
     * 差を取った値を、ファイルに書かれた桁へ丸めて。GeckoLib は box UV の面の大きさを {@code floor} で切るので、
     * {@code 2.9999998} を 3 と読ませないと面が1ピクセルずれる。Blockbench の書き出しも丸めた数を書く。
     */
    private static JsonArray exact(double x, double y, double z) {
        JsonArray vector = new JsonArray();
        vector.add(Math.round(x * 1.0E6) / 1.0E6);
        vector.add(Math.round(y * 1.0E6) / 1.0E6);
        vector.add(Math.round(z * 1.0E6) / 1.0E6);

        return vector;
    }

    private static JsonArray vector(float x, float y, float z) {
        JsonArray vector = new JsonArray();
        vector.add(x);
        vector.add(y);
        vector.add(z);

        return vector;
    }

    private static JsonArray pair(float a, float b) {
        JsonArray pair = new JsonArray();
        pair.add(a);
        pair.add(b);

        return pair;
    }
}
