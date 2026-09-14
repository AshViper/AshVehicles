package com.ashvehicles.ai.decision;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * 名前付きの数値の集まり。AI の版（{@code learning/AiVersion}）が持つ、方針・拠点評価・経路・目標選択の重み。
 *
 * <p><b>学習が触ってよいのはここだけ。</b> 重みはコードの既定値を持ち、ここに名前があればそちらを使う。版を
 * 比べるとは、この集まりを入れ替えて同じ試合を回すことだ。物理・当たり判定・弾道・車両の動きはこの集まりに
 * 1つも入らない——入れれば、強くなったのが AI なのか車両なのか分からなくなる。
 *
 * <p>名前はドット区切りの平らな文字列（{@code policy.retreat}）。JSON では入れ子にも平らにも書け、
 * 読むときに平らへ畳む。知らない名前は黙って持っているだけで、誰も読まない。
 *
 * <p><b>既定値は重みを読むコードの側にしか書かない。</b> 版ファイルへ書き出すときの「全部の名前と既定値」は、
 * 記録する集まり（{@link #recorder}）を各重みの読み手に通して集める——既定値の一覧を別に持つと、コードと一覧が
 * 必ずずれる。
 */
public final class ParameterSet {
    public static final ParameterSet EMPTY = new ParameterSet(Map.of());

    private final Map<String, Double> values;

    /** 訊かれた名前と既定値を書き留める先。記録する集まりだけが持つ。 */
    @Nullable
    private final Map<String, Double> asked;

    public ParameterSet(Map<String, Double> values) {
        this.values = Map.copyOf(values);
        this.asked = null;
    }

    private ParameterSet(Map<String, Double> values, Map<String, Double> asked) {
        this.values = Map.copyOf(values);
        this.asked = asked;
    }

    /** 中身が空で、訊かれた名前と既定値を書き留める集まり。 */
    public static ParameterSet recorder() {
        return new ParameterSet(Map.of(), new TreeMap<>());
    }

    /** 書き留めた名前と既定値（名前順）。記録する集まりでなければ空。 */
    public Map<String, Double> asked() {
        return this.asked == null ? Map.of() : this.asked;
    }

    /** その名前の値。無いか、数でなければ既定。 */
    public double get(String key, double fallback) {
        if (this.asked != null) {
            this.asked.put(key, fallback);
        }

        Double value = this.values.get(key);

        return value == null || !Double.isFinite(value) ? fallback : value;
    }

    public boolean has(String key) {
        return this.values.containsKey(key);
    }

    /** こちらを土台に、相手の値で上書きした物。 */
    public ParameterSet overriddenBy(ParameterSet other) {
        if (other.values.isEmpty()) {
            return this;
        }

        Map<String, Double> merged = new HashMap<>(this.values);

        merged.putAll(other.values);

        return new ParameterSet(merged);
    }

    public Map<String, Double> asMap() {
        return this.values;
    }

    public int size() {
        return this.values.size();
    }

    /** JSON から。入れ子の物はドットで繋いで平らにする。数でない値は捨てる。 */
    public static ParameterSet fromJson(JsonObject json) {
        Map<String, Double> flat = new HashMap<>();

        flatten("", json, flat);

        return new ParameterSet(flat);
    }

    /** JSON へ。名前順の平らな形で、人が差分を読める順に並べる。 */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();

        for (Map.Entry<String, Double> entry : new TreeMap<>(this.values).entrySet()) {
            json.add(entry.getKey(), new JsonPrimitive(entry.getValue()));
        }

        return json;
    }

    private static void flatten(String prefix, JsonObject json, Map<String, Double> into) {
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonElement value = entry.getValue();

            if (value.isJsonObject()) {
                flatten(key, value.getAsJsonObject(), into);
            } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                into.put(key, value.getAsDouble());
            }
        }
    }
}
