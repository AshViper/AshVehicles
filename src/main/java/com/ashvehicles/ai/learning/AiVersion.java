package com.ashvehicles.ai.learning;

import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.ashvehicles.ai.decision.ParameterSet;
import com.ashvehicles.ai.decision.RuleBasedPolicy;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * AI の版1つ。方針の種類と、その方針に渡すパラメータの集まり。
 *
 * <p><b>版を比べるとは、これを入れ替えて同じ試合を回すこと。</b> 物理も車両も兵装も同じで、違うのは
 * 「どの重みで判断するか」だけ——だから勝率の差がそのまま判断の差になる（{@link BattleStatistics}・
 * {@link Evaluation}）。
 *
 * <p>ファイルは {@code <ゲームフォルダ>/ashvehicles_ai/versions/<id>.json}:
 *
 * <pre>
 * {
 *   "id": "rule_v2",
 *   "policy": "rule_based",
 *   "parent": "rule_v1",
 *   "description": "撤退を早めた",
 *   "parameters": { "policy.survival": 1.2, "profile.tank.retreat_health": 0.35 }
 * }
 * </pre>
 *
 * <p>書いていないパラメータはコードの既定値のまま。
 *
 * @param id          版の名前。小文字・数字・{@code _ . -} だけ
 * @param policy      方針の種類（{@code rule_based}、将来 {@code reinforcement_learning}）
 * @param description 人が読む説明
 * @param parent      どの版から作ったか
 * @param parameters  既定値からの差分
 * @param builtIn     コードに組み込まれた版（ファイルで上書きできない）
 */
public record AiVersion(String id, String policy, String description, @Nullable String parent,
        ParameterSet parameters, boolean builtIn) {

    /** 版の名前に使える文字。 */
    public static final Pattern NAME = Pattern.compile("[a-z0-9_.\\-]{1,48}");

    /** JSON へ。パラメータは渡された物（既定値を足した物を渡せば、全部の名前が並ぶ）。 */
    public JsonObject toJson(ParameterSet shown) {
        JsonObject json = new JsonObject();

        json.addProperty("id", this.id);
        json.addProperty("policy", this.policy);
        json.addProperty("description", this.description);

        if (this.parent != null) {
            json.addProperty("parent", this.parent);
        }

        json.add("parameters", shown.toJson());

        return json;
    }

    /** JSON から。名前が使えなければ null。 */
    @Nullable
    public static AiVersion fromJson(String fileName, JsonObject json) {
        String id = text(json, "id", fileName);

        if (!NAME.matcher(id).matches()) {
            return null;
        }

        JsonElement parameters = json.get("parameters");

        return new AiVersion(id, text(json, "policy", RuleBasedPolicy.ID), text(json, "description", ""),
                json.has("parent") && !json.get("parent").isJsonNull() ? json.get("parent").getAsString() : null,
                parameters != null && parameters.isJsonObject() ? ParameterSet.fromJson(parameters.getAsJsonObject())
                        : ParameterSet.EMPTY,
                false);
    }

    private static String text(JsonObject json, String key, String fallback) {
        JsonElement value = json.get(key);

        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }
}
