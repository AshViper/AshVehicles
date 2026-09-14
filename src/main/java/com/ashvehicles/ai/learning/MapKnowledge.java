package com.ashvehicles.ai.learning;

import com.ashvehicles.ai.battlefield.TacticalMap;
import com.ashvehicles.ai.decision.ParameterSet;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

/**
 * 試合を重ねて覚えた地図。4×4 ブロックのマス（戦術格子と同じ目）ごとに、AI の車両がそこで何を経験したかを数える。
 *
 * <p><b>地形の読み（{@code battlefield/TerrainCache}・{@code navigation/Obstacles}）では分からない物を覚える。</b>
 * 読みが「通れる」と言っても車両が必ず詰まる柱の列、降りたら上がれない岸、入ると出られない建物、いつも撃破される
 * 開けた交差点——そういう場所は、走ってみた者の結果からしか分からない。数えるのは5つ:
 *
 * <pre>
 * visits  入った回数
 * passes  詰まらずに素早く抜けた回数
 * stalls  詰まった回数（抜け出せずに戻り始めた分は重く）
 * wet     車体が沈む水に入った回数
 * deaths  倒された回数
 * </pre>
 *
 * <p>そこから読むのは割合で、道の1歩の代償になる（{@link Reading#cost}）:
 *
 * <pre>
 * trap  = stalls / (stalls + passes + 2)    抜けた者に比べて、どれだけ詰まったか
 * water = wet    / (visits + 2)
 * death = deaths / (visits + 2)             危険の嫌い方を掛ける
 * flow  = passes / (visits + 2)             引く
 * </pre>
 *
 * <p>2は見えない経験。1回しか入られていないマスを、1回の結果で決め付けない。
 *
 * <p><b>経験は2段で持つ。</b> 今の試合の分と、それまでの試合から持ち越した分。道を引くときは両方を足して読むので、
 * 同じ試合の中でも3両目は1両目が詰まった所を避ける。試合が終わると（{@link #commit}）持ち越した分を
 * {@code learning.mapKeep} 倍に減らしてから今の試合の分を足す——建物は壊れ、柵は倒れ、地形は変わるので、昔の
 * 経験は少しずつ忘れる。
 *
 * <p><b>1つの試合から1マスへ足せる詰まりと水には上限がある</b>（{@value #FRESH_CAP}）。立ち往生した1両は同じマスで
 * 何千回も詰まる——それをそのまま足すと、1両の不運が何十試合も消えない。持ち越す数にも上限があり
 * （{@value #LEARNED_CAP}）、超えたらそのマスの5つを同じ割合で縮める。1つずつ切り詰めると、よく通るマスの割合が
 * 狂う。
 *
 * <p>世界に触れない。読み書きと試合の区切りは {@link MapMemory} の仕事で、ここは数と算術と JSON だけ——だから
 * ゲームを起動せずに試せる（{@code tool/ai/check}）。
 */
public final class MapKnowledge {
    public static final int VISITS = 0;
    public static final int PASSES = 1;
    public static final int STALLS = 2;
    public static final int WET = 3;
    public static final int DEATHS = 4;

    private static final int KINDS = 5;
    private static final String[] NAMES = {"visits", "passes", "stalls", "wet", "deaths"};

    /** ファイルの数え方の形。変えたら上げる。違う形のファイルは読まずに覚え直す。 */
    public static final int FORMAT = 1;

    /** 見えない経験（回）。 */
    private static final double PRIOR = 2.0;

    /** 1つの試合から1マスへ足せる詰まりと水の上限。 */
    public static final float FRESH_CAP = 12.0F;

    /** 持ち越す数の上限。 */
    public static final float LEARNED_CAP = 60.0F;

    /** 持ち越した数がどれもこれより小さいマスは忘れる。 */
    private static final float FORGET = 0.05F;

    /** 集計（{@link #summary}）で「そういう所」と数える読みの下限。 */
    private static final double TRAP = 0.5;
    private static final double WATER = 0.3;
    private static final double DEADLY = 0.3;
    private static final double LANE = 0.6;

    /**
     * 覚えた地図をどれだけ信じるか。版のパラメータ（{@code map.*}）から読む。どれも1歩の距離に対する倍率。
     *
     * @param trap  詰まる所
     * @param water 水に落ちる所
     * @param death 倒される所（危険の嫌い方を掛ける）
     * @param flow  すんなり抜けられる所（引く）
     */
    public record Weights(double trap, double water, double death, double flow) {
        public static Weights of(ParameterSet parameters) {
            return new Weights(
                    parameters.get("map.trap", 3.0),
                    parameters.get("map.water", 6.0),
                    parameters.get("map.death", 1.0),
                    parameters.get("map.flow", 0.2));
        }
    }

    /** 1マスの読み。{@code visits} 以外は 0〜1。 */
    public record Reading(double visits, double trap, double water, double death, double flow) {
        public static final Reading NONE = new Reading(0.0, 0.0, 0.0, 0.0, 0.0);

        /** そのマスへ入る1歩に足す代償。 */
        public double cost(Weights weights, double riskWeight) {
            return weights.trap() * this.trap + weights.water() * this.water
                    + weights.death() * riskWeight * this.death - weights.flow() * this.flow;
        }
    }

    /** 覚えている量の要約。 */
    public record Summary(int cells, int traps, int wet, int deadly, int lanes) {
    }

    /** マスごとの数。前の5つが持ち越した分、後ろの5つが今の試合の分。 */
    private final Long2ObjectOpenHashMap<float[]> cells = new Long2ObjectOpenHashMap<>();

    private int battles;
    private boolean fresh;

    private static long key(int cellX, int cellZ) {
        return ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
    }

    /** 今の試合の経験を足す。 */
    public void add(int cellX, int cellZ, int kind, float amount) {
        long key = key(cellX, cellZ);
        float[] cell = this.cells.get(key);

        if (cell == null) {
            cell = new float[KINDS * 2];
            this.cells.put(key, cell);
        }

        float next = cell[KINDS + kind] + amount;

        cell[KINDS + kind] = kind == STALLS || kind == WET ? Math.min(FRESH_CAP, next) : next;
        this.fresh = true;
    }

    /** そのマスの、持ち越した分と今の試合の分を足した数。 */
    public float amount(int cellX, int cellZ, int kind) {
        float[] cell = this.cells.get(key(cellX, cellZ));

        return cell == null ? 0.0F : cell[kind] + cell[KINDS + kind];
    }

    /** そのマスの読み。誰も入っていなければ {@link Reading#NONE}。 */
    public Reading reading(int cellX, int cellZ) {
        float[] cell = this.cells.get(key(cellX, cellZ));

        return cell == null ? Reading.NONE : read(cell);
    }

    /** そのマスへ入る1歩に足す代償。誰も入っていなければ0。 */
    public double cost(int cellX, int cellZ, Weights weights, double riskWeight) {
        float[] cell = this.cells.get(key(cellX, cellZ));

        return cell == null ? 0.0 : read(cell).cost(weights, riskWeight);
    }

    private static Reading read(float[] cell) {
        double visits = cell[VISITS] + cell[KINDS + VISITS];
        double passes = cell[PASSES] + cell[KINDS + PASSES];
        double stalls = cell[STALLS] + cell[KINDS + STALLS];
        double wet = cell[WET] + cell[KINDS + WET];
        double deaths = cell[DEATHS] + cell[KINDS + DEATHS];
        double seen = visits + PRIOR;

        return new Reading(visits, Math.min(1.0, stalls / (stalls + passes + PRIOR)), Math.min(1.0, wet / seen),
                Math.min(1.0, deaths / seen), Math.min(1.0, passes / seen));
    }

    /**
     * 試合を1つ締める。持ち越した分を {@code keep} 倍に減らして今の試合の分を足し、ほとんど何も残っていないマスを
     * 忘れる。
     */
    public void commit(double keep) {
        ObjectIterator<Long2ObjectMap.Entry<float[]>> walk = this.cells.long2ObjectEntrySet().fastIterator();

        while (walk.hasNext()) {
            float[] cell = walk.next().getValue();
            float largest = 0.0F;

            for (int kind = 0; kind < KINDS; kind++) {
                cell[kind] = (float) (cell[kind] * keep) + cell[KINDS + kind];
                cell[KINDS + kind] = 0.0F;
                largest = Math.max(largest, cell[kind]);
            }

            if (largest < FORGET) {
                walk.remove();

                continue;
            }

            if (largest > LEARNED_CAP) {
                float scale = LEARNED_CAP / largest;

                for (int kind = 0; kind < KINDS; kind++) {
                    cell[kind] *= scale;
                }
            }
        }

        this.battles++;
        this.fresh = false;
    }

    /** 最後に締めてから、経験が1つでも足されたか。 */
    public boolean hasFresh() {
        return this.fresh;
    }

    /** 締めた試合の数。 */
    public int battles() {
        return this.battles;
    }

    /** 覚えているマスの数。 */
    public int size() {
        return this.cells.size();
    }

    /** 全部忘れる。 */
    public void clear() {
        this.cells.clear();
        this.battles = 0;
        this.fresh = false;
    }

    public Summary summary() {
        int traps = 0;
        int wet = 0;
        int deadly = 0;
        int lanes = 0;

        for (float[] cell : this.cells.values()) {
            Reading reading = read(cell);

            traps += reading.trap() >= TRAP ? 1 : 0;
            wet += reading.water() >= WATER ? 1 : 0;
            deadly += reading.death() >= DEADLY ? 1 : 0;
            lanes += reading.flow() >= LANE ? 1 : 0;
        }

        return new Summary(this.cells.size(), traps, wet, deadly, lanes);
    }

    /** 写し。書き出しを書き手スレッドへ渡すため——向こうが読んでいる間も、こちらは足し続ける。 */
    public MapKnowledge copy() {
        MapKnowledge copy = new MapKnowledge();

        copy.battles = this.battles;
        copy.fresh = this.fresh;

        for (Long2ObjectMap.Entry<float[]> entry : this.cells.long2ObjectEntrySet()) {
            copy.cells.put(entry.getLongKey(), entry.getValue().clone());
        }

        return copy;
    }

    /** ファイルの形。1マス1行の配列で、列の名前を先頭に書く。数は持ち越した分と今の試合の分の合計。 */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        JsonArray names = new JsonArray();
        JsonArray rows = new JsonArray(this.cells.size());

        json.addProperty("format", FORMAT);
        json.addProperty("cell", TacticalMap.CELL);
        json.addProperty("battles", this.battles);
        names.add("x");
        names.add("z");

        for (String name : NAMES) {
            names.add(name);
        }

        json.add("columns", names);

        for (Long2ObjectMap.Entry<float[]> entry : this.cells.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            float[] cell = entry.getValue();
            JsonArray row = new JsonArray(2 + KINDS);

            row.add((int) (key >> 32));
            row.add((int) key);

            for (int kind = 0; kind < KINDS; kind++) {
                row.add(Math.round((cell[kind] + cell[KINDS + kind]) * 1000.0) / 1000.0);
            }

            rows.add(row);
        }

        json.add("cells", rows);

        return json;
    }

    /** ファイルから。形が違えば空の地図——読み違えるより、覚え直す方がいい。 */
    public static MapKnowledge fromJson(JsonObject json) {
        MapKnowledge knowledge = new MapKnowledge();

        if (json.has("format") && json.get("format").getAsInt() != FORMAT) {
            return knowledge;
        }

        knowledge.battles = json.has("battles") ? json.get("battles").getAsInt() : 0;

        int[] column = {2, 3, 4, 5, 6};

        if (json.has("columns") && json.get("columns").isJsonArray()) {
            JsonArray names = json.getAsJsonArray("columns");

            for (int kind = 0; kind < KINDS; kind++) {
                column[kind] = -1;

                for (int index = 0; index < names.size(); index++) {
                    if (NAMES[kind].equals(names.get(index).getAsString())) {
                        column[kind] = index;
                    }
                }
            }
        }

        if (!json.has("cells") || !json.get("cells").isJsonArray()) {
            return knowledge;
        }

        for (JsonElement element : json.getAsJsonArray("cells")) {
            if (!element.isJsonArray() || element.getAsJsonArray().size() < 2) {
                continue;
            }

            JsonArray row = element.getAsJsonArray();
            float[] cell = new float[KINDS * 2];

            for (int kind = 0; kind < KINDS; kind++) {
                int index = column[kind];

                if (index >= 0 && index < row.size()) {
                    cell[kind] = Math.max(0.0F, row.get(index).getAsFloat());
                }
            }

            knowledge.cells.put(key(row.get(0).getAsInt(), row.get(1).getAsInt()), cell);
        }

        return knowledge;
    }
}
