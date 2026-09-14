package com.ashvehicles.ai.learning;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.ai.log.AiFiles;
import com.ashvehicles.ai.log.BattleSummary;
import com.ashvehicles.ai.log.LifeRecord;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 版ごとの戦績の累計。全体と、相手の版ごと。
 *
 * <p><b>版の良し悪しはここで測る。</b> 勝率・撃破率・被撃破率・拠点の制圧率と防衛率・生存時間・与ダメージ・報酬を、
 * 戦闘が終わるたびに足していく（{@code stats/<版>.json}）。採用の門（{@link Evaluation}）が読むのは
 * <b>相手の版ごとの勝率</b>——全体の勝率は、弱い相手とばかり当たった版を強く見せる。
 *
 * <p>数えるのは決着した戦闘だけ。サーバーが途中で止まった戦闘は記録には残るが、ここには入れない。
 */
public final class BattleStatistics {
    /** 1つの累計。 */
    public static final class Tally {
        public int battles;
        public int wins;
        public int losses;
        public int draws;
        public int lives;
        public int kills;
        public int deaths;
        public int captures;
        public int recaptures;
        public int defenses;
        public double damage;
        public double reward;
        public long survivalTicks;

        /** 引き分けは半分の勝ち。 */
        public double winRate() {
            return this.battles == 0 ? 0.0 : (this.wins + 0.5 * this.draws) / this.battles;
        }

        /** 1回の出撃あたりの撃破。 */
        public double killRate() {
            return this.lives == 0 ? 0.0 : (double) this.kills / this.lives;
        }

        /** 1回の出撃あたりの被撃破。 */
        public double deathRate() {
            return this.lives == 0 ? 0.0 : (double) this.deaths / this.lives;
        }

        /** 1戦あたりの制圧（奪還を含む）。 */
        public double captureRate() {
            return this.battles == 0 ? 0.0 : (double) (this.captures + this.recaptures) / this.battles;
        }

        /** 1戦あたりの防衛成功。 */
        public double defenseRate() {
            return this.battles == 0 ? 0.0 : (double) this.defenses / this.battles;
        }

        public double averageSurvivalSeconds() {
            return this.lives == 0 ? 0.0 : this.survivalTicks / 20.0 / this.lives;
        }

        public double averageDamage() {
            return this.lives == 0 ? 0.0 : this.damage / this.lives;
        }

        public double averageReward() {
            return this.lives == 0 ? 0.0 : this.reward / this.lives;
        }

        void add(Tally other) {
            this.battles += other.battles;
            this.wins += other.wins;
            this.losses += other.losses;
            this.draws += other.draws;
            this.lives += other.lives;
            this.kills += other.kills;
            this.deaths += other.deaths;
            this.captures += other.captures;
            this.recaptures += other.recaptures;
            this.defenses += other.defenses;
            this.damage += other.damage;
            this.reward += other.reward;
            this.survivalTicks += other.survivalTicks;
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();

            json.addProperty("battles", this.battles);
            json.addProperty("wins", this.wins);
            json.addProperty("losses", this.losses);
            json.addProperty("draws", this.draws);
            json.addProperty("lives", this.lives);
            json.addProperty("kills", this.kills);
            json.addProperty("deaths", this.deaths);
            json.addProperty("captures", this.captures);
            json.addProperty("recaptures", this.recaptures);
            json.addProperty("defenses", this.defenses);
            json.addProperty("damage", this.damage);
            json.addProperty("reward", this.reward);
            json.addProperty("survival_ticks", this.survivalTicks);
            json.addProperty("win_rate", this.winRate());
            json.addProperty("kill_rate", this.killRate());
            json.addProperty("death_rate", this.deathRate());
            json.addProperty("capture_rate", this.captureRate());
            json.addProperty("defense_rate", this.defenseRate());
            json.addProperty("average_survival_seconds", this.averageSurvivalSeconds());
            json.addProperty("average_damage", this.averageDamage());
            json.addProperty("average_reward", this.averageReward());

            return json;
        }

        static Tally fromJson(JsonObject json) {
            Tally tally = new Tally();

            tally.battles = integer(json, "battles");
            tally.wins = integer(json, "wins");
            tally.losses = integer(json, "losses");
            tally.draws = integer(json, "draws");
            tally.lives = integer(json, "lives");
            tally.kills = integer(json, "kills");
            tally.deaths = integer(json, "deaths");
            tally.captures = integer(json, "captures");
            tally.recaptures = integer(json, "recaptures");
            tally.defenses = integer(json, "defenses");
            tally.damage = json.has("damage") ? json.get("damage").getAsDouble() : 0.0;
            tally.reward = json.has("reward") ? json.get("reward").getAsDouble() : 0.0;
            tally.survivalTicks = json.has("survival_ticks") ? json.get("survival_ticks").getAsLong() : 0L;

            return tally;
        }

        private static int integer(JsonObject json, String key) {
            return json.has(key) ? json.get(key).getAsInt() : 0;
        }
    }

    /** 版1つの累計。 */
    private static final class Record {
        final Tally overall = new Tally();
        final Map<String, Tally> versus = new TreeMap<>();
    }

    private static final Map<String, Record> BY_VERSION = new TreeMap<>();

    private BattleStatistics() {
    }

    /** ファイルから読み直す。サーバーの起動時に。 */
    public static void load() {
        BY_VERSION.clear();

        Path folder = AiFiles.stats();

        if (!Files.isDirectory(folder)) {
            return;
        }

        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                String text = AiFiles.read(file);

                if (text == null) {
                    continue;
                }

                try {
                    JsonElement parsed = JsonParser.parseString(text);

                    if (!parsed.isJsonObject()) {
                        continue;
                    }

                    JsonObject json = parsed.getAsJsonObject();
                    String name = file.getFileName().toString();
                    String version = json.has("version") ? json.get("version").getAsString()
                            : name.substring(0, name.length() - ".json".length());
                    Record record = new Record();

                    if (json.has("overall") && json.get("overall").isJsonObject()) {
                        record.overall.add(Tally.fromJson(json.getAsJsonObject("overall")));
                    }

                    if (json.has("versus") && json.get("versus").isJsonObject()) {
                        for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("versus").entrySet()) {
                            if (entry.getValue().isJsonObject()) {
                                record.versus.put(entry.getKey(), Tally.fromJson(entry.getValue().getAsJsonObject()));
                            }
                        }
                    }

                    BY_VERSION.put(version, record);
                } catch (RuntimeException exception) {
                    AshVehicles.LOGGER.warn("[ai] could not read statistics {}", file, exception);
                }
            }
        } catch (IOException exception) {
            AshVehicles.LOGGER.warn("[ai] could not list {}", folder, exception);
        }
    }

    /** 決着した戦闘1回を足す。 */
    public static void record(BattleSummary summary) {
        if (summary.aborted()) {
            return;
        }

        Set<String> touched = new HashSet<>();
        Map<String, String> teams = summary.teamVersions();

        for (Map.Entry<String, String> side : teams.entrySet()) {
            Tally result = new Tally();

            result.battles = 1;

            if (summary.winner() == null) {
                result.draws = 1;
            } else if (summary.winner().equals(side.getKey())) {
                result.wins = 1;
            } else {
                result.losses = 1;
            }

            Record record = BY_VERSION.computeIfAbsent(side.getValue(), key -> new Record());

            record.overall.add(result);

            for (Map.Entry<String, String> other : teams.entrySet()) {
                if (!other.getKey().equals(side.getKey())) {
                    record.versus.computeIfAbsent(other.getValue(), key -> new Tally()).add(result);
                }
            }

            touched.add(side.getValue());
        }

        for (LifeRecord life : summary.lives()) {
            Tally one = new Tally();

            one.lives = 1;
            one.kills = life.kills();
            one.deaths = life.destroyed() ? 1 : 0;
            one.captures = life.captures();
            one.recaptures = life.recaptures();
            one.defenses = life.defenses();
            one.damage = life.damageDealt();
            one.reward = life.reward();
            one.survivalTicks = life.survivalTicks(summary.endTick());

            Record record = BY_VERSION.computeIfAbsent(life.version(), key -> new Record());

            record.overall.add(one);

            for (Map.Entry<String, String> other : teams.entrySet()) {
                if (!other.getKey().equals(life.team())) {
                    record.versus.computeIfAbsent(other.getValue(), key -> new Tally()).add(one);
                }
            }

            touched.add(life.version());
        }

        for (String version : touched) {
            save(version);
        }
    }

    /** その版の全体の累計。記録が無ければ空の累計。 */
    public static Tally overall(String version) {
        Record record = BY_VERSION.get(version);

        return record == null ? new Tally() : record.overall;
    }

    /** その版の、ある相手に対する累計。 */
    public static Tally versus(String version, String opponent) {
        Record record = BY_VERSION.get(version);
        Tally tally = record == null ? null : record.versus.get(opponent);

        return tally == null ? new Tally() : tally;
    }

    /** その版が当たった相手。 */
    public static Set<String> opponents(String version) {
        Record record = BY_VERSION.get(version);

        return record == null ? Set.of() : record.versus.keySet();
    }

    /** 記録のある版。 */
    public static Set<String> versions() {
        return BY_VERSION.keySet();
    }

    private static void save(String version) {
        Record record = BY_VERSION.get(version);

        if (record == null) {
            return;
        }

        JsonObject json = new JsonObject();
        JsonObject versus = new JsonObject();

        json.addProperty("version", version);
        json.add("overall", record.overall.toJson());
        record.versus.forEach((opponent, tally) -> versus.add(opponent, tally.toJson()));
        json.add("versus", versus);
        AiFiles.replace(AiFiles.stats().resolve(version + ".json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(json));
    }
}
