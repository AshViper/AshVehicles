package com.ashvehicles.ai.learning;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.ai.AiConfig;
import com.ashvehicles.ai.battlefield.TacticalMap;
import com.ashvehicles.ai.log.AiFiles;
import com.ashvehicles.match.MatchState;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 試合を重ねて覚えた地図（{@link MapKnowledge}）を、ディメンションごとに1つ持ち、読み書きし、試合の区切りで締める。
 *
 * <pre>
 * &lt;ワールド&gt;/ashvehicles_ai/map_memory/&lt;名前空間&gt;/&lt;ディメンション&gt;.json
 * </pre>
 *
 * <p><b>ワールドに置く。</b> 覚えたのはその地形のことで、別のワールドへ持ち込めば嘘になる。記録や版（ゲームフォルダの
 * {@code ashvehicles_ai/}）と置き場所が違うのはそのため。
 *
 * <p><b>学習でゲームを重くしない。</b> 経験を1つ足すのは配列の足し算1回で、足すのは車両がマスを跨いだとき・詰まった
 * とき・水に入ったとき・倒されたときだけ（{@link MapTrace}）。締めるのは試合の終わりに1回で、ファイルの文字列は
 * 記録の書き手スレッド（{@link AiFiles}）が写しから作る。読むのは経路探索の1歩ごとのハッシュ引き1回。重い学習
 * （方針そのものを学ぶ物）は今まで通りゲームの外——ここがするのは、走った結果を数えて地図に書き込むことだけだ。
 *
 * <p>サーバースレッド専用。設定 {@code learning.mapMemory} が偽なら、何も覚えず何も読まない。
 */
public final class MapMemory {
    private static final Gson COMPACT = new Gson();

    private static final Map<ResourceKey<Level>, MapMemory> LOADED = new HashMap<>();

    private final ResourceKey<Level> dimension;
    private final Path file;
    private final MapKnowledge knowledge;

    private MapMemory(ResourceKey<Level> dimension, Path file, MapKnowledge knowledge) {
        this.dimension = dimension;
        this.file = file;
        this.knowledge = knowledge;
    }

    /** そのディメンションの覚えた地図。初めて訊かれたときにファイルから読む。覚えない設定なら null。 */
    @Nullable
    public static MapMemory of(Level level) {
        if (!(level instanceof ServerLevel server) || !AiConfig.get().learning().mapMemory()) {
            return null;
        }

        MapMemory known = LOADED.get(level.dimension());

        if (known == null) {
            known = load(server);
            LOADED.put(level.dimension(), known);
        }

        return known;
    }

    /** 倒された。{@code log/BattleEvents} から。 */
    public static void died(Entity vehicle) {
        MapMemory memory = of(vehicle.level());

        if (memory != null) {
            memory.add(TacticalMap.cellOf(vehicle.getX()), TacticalMap.cellOf(vehicle.getZ()), MapKnowledge.DEATHS,
                    1.0F);
        }
    }

    /** 試合が始まる。読み込みはここで済ませる——走り出してから、最初に道を引いた車両の tick を止めないように。 */
    public static void battleStarted(MinecraftServer server, MatchState state) {
        ServerLevel level = state.level(server);

        if (level != null) {
            of(level);
        }
    }

    /** 試合が終わった。今の試合の経験を締めて書く。 */
    public static void battleEnded() {
        for (MapMemory memory : LOADED.values()) {
            memory.commit();
        }
    }

    /**
     * サーバーが止まる。決着していない試合の経験も、実際に走った結果には違いないので締めて書き、忘れる——次に開く
     * ワールドへ持ち込まないように。
     */
    public static void serverStopping() {
        for (MapMemory memory : LOADED.values()) {
            memory.commit();
        }

        LOADED.clear();
    }

    /** 経験を1つ足す。 */
    public void add(int cellX, int cellZ, int kind, float amount) {
        this.knowledge.add(cellX, cellZ, kind, amount);
    }

    /** そのマスへ入る1歩に足す代償（{@code navigation/RoutePlanner.CellCost}）。 */
    public double cost(int cellX, int cellZ, MapKnowledge.Weights weights, double riskWeight) {
        return this.knowledge.cost(cellX, cellZ, weights, riskWeight);
    }

    public MapKnowledge knowledge() {
        return this.knowledge;
    }

    public ResourceKey<Level> dimension() {
        return this.dimension;
    }

    /** 全部忘れて、空の地図を書く。{@code /tdm ai map reset} から。 */
    public void reset() {
        this.knowledge.clear();
        this.save();
    }

    /** 経験が足されていれば締めて書く。何も走っていないのに締めれば、覚えた物がただ減る。 */
    private void commit() {
        if (!this.knowledge.hasFresh()) {
            return;
        }

        this.knowledge.commit(AiConfig.get().learning().mapKeep());
        this.save();
    }

    private void save() {
        MapKnowledge snapshot = this.knowledge.copy();

        AiFiles.replace(this.file, () -> COMPACT.toJson(snapshot.toJson()));
    }

    private static MapMemory load(ServerLevel level) {
        Path file = level.getServer().getWorldPath(LevelResource.ROOT).resolve("ashvehicles_ai").resolve("map_memory")
                .resolve(level.dimension().location().getNamespace())
                .resolve(level.dimension().location().getPath() + ".json");
        MapKnowledge knowledge = new MapKnowledge();
        String text = AiFiles.read(file);

        if (text != null) {
            try {
                JsonElement parsed = JsonParser.parseString(text);

                if (parsed.isJsonObject()) {
                    knowledge = MapKnowledge.fromJson(parsed.getAsJsonObject());
                }
            } catch (RuntimeException exception) {
                AshVehicles.LOGGER.warn("[ai] could not read the map memory {}", file, exception);
            }
        }

        return new MapMemory(level.dimension(), file, knowledge);
    }
}
