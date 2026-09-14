package com.ashvehicles.ai.learning;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.ai.AiConfig;
import com.ashvehicles.ai.air.KillScore;
import com.ashvehicles.ai.combat.TargetSelector;
import com.ashvehicles.ai.decision.ParameterSet;
import com.ashvehicles.ai.decision.RuleBasedPolicy;
import com.ashvehicles.ai.log.AiFiles;
import com.ashvehicles.ai.navigation.RoutePlanner;
import com.ashvehicles.ai.objective.ObjectiveScoring;
import com.ashvehicles.ai.role.TacticalProfile;
import com.ashvehicles.ai.role.VehicleRole;
import com.ashvehicles.ai.tactics.CoverFinder;
import com.ashvehicles.ai.tactics.FlankPlanner;
import com.ashvehicles.ai.tactics.RetreatPlanner;
import com.ashvehicles.ai.team.Reinforcements;
import com.ashvehicles.match.MatchState;
import com.ashvehicles.match.MatchTeam;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.minecraft.server.MinecraftServer;

/**
 * AI の版の一覧。組み込みの1つ（{@value #BUILT_IN}）と、ファイルから読んだ物。
 *
 * <p><b>どの陣営がどの版を使うか</b>は陣営が持つ（{@code MatchTeam.aiVersion}、{@code /tdm ai team}）。書いて
 * いなければ<b>採用版</b>——{@code champion.txt} に名前が書かれていればそれ、無ければ設定の
 * {@code versions.defaultVersion}、それも無ければ組み込みの版。
 *
 * <p><b>採用版を入れ替えるのは {@link Evaluation} を通ってから</b>（{@code /tdm ai promote}）。新しい版が古い版より
 * 弱いなら採用しない——その門がここにある。
 *
 * <p>読み込みはサーバーの起動時と {@code /tdm ai versions reload} だけ。
 */
public final class AiVersions {
    /** 組み込みの版の名前。 */
    public static final String BUILT_IN = "rule_v1";

    private static final AiVersion BUILT_IN_VERSION = new AiVersion(BUILT_IN, RuleBasedPolicy.ID,
            "Rule-based first version: the code defaults as they are.", null, ParameterSet.EMPTY, true);

    private static final Map<String, AiVersion> VERSIONS = new LinkedHashMap<>();

    @Nullable
    private static String champion;

    static {
        VERSIONS.put(BUILT_IN, BUILT_IN_VERSION);
    }

    private AiVersions() {
    }

    /** ファイルから読み直す。組み込みの版は残る。 */
    public static void load() {
        VERSIONS.clear();
        VERSIONS.put(BUILT_IN, BUILT_IN_VERSION);
        champion = null;

        Path folder = AiFiles.versions();

        if (Files.isDirectory(folder)) {
            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).sorted()
                        .toList()) {
                    read(file);
                }
            } catch (IOException exception) {
                AshVehicles.LOGGER.warn("[ai] could not list {}", folder, exception);
            }
        }

        String chosen = AiFiles.read(AiFiles.root().resolve("champion.txt"));

        if (chosen != null && VERSIONS.containsKey(chosen.trim())) {
            champion = chosen.trim();
        }

        AshVehicles.LOGGER.info("[ai] {} AI version(s) loaded, default {}", VERSIONS.size(), defaultId());
    }

    private static void read(Path file) {
        String text = AiFiles.read(file);
        String name = file.getFileName().toString();

        if (text == null) {
            return;
        }

        try {
            JsonElement json = JsonParser.parseString(text);
            AiVersion version = json.isJsonObject()
                    ? AiVersion.fromJson(name.substring(0, name.length() - ".json".length()), json.getAsJsonObject())
                    : null;

            if (version == null || version.id().equals(BUILT_IN)) {
                AshVehicles.LOGGER.warn("[ai] skipping {}: not a usable AI version (bad or reserved name)", file);

                return;
            }

            VERSIONS.put(version.id(), version);
        } catch (RuntimeException exception) {
            AshVehicles.LOGGER.warn("[ai] could not read AI version {}", file, exception);
        }
    }

    public static AiVersion builtIn() {
        return BUILT_IN_VERSION;
    }

    @Nullable
    public static AiVersion find(String id) {
        return VERSIONS.get(id);
    }

    /** その名前の版。無ければ既定の版。 */
    public static AiVersion get(String id) {
        AiVersion found = VERSIONS.get(id);

        return found != null ? found : VERSIONS.getOrDefault(defaultId(), BUILT_IN_VERSION);
    }

    public static Collection<AiVersion> all() {
        return VERSIONS.values();
    }

    /** 陣営が何も言わないときに使う版の名前。 */
    public static String defaultId() {
        if (champion != null) {
            return champion;
        }

        String configured = AiConfig.get().versions().defaultVersion();

        return VERSIONS.containsKey(configured) ? configured : BUILT_IN;
    }

    @Nullable
    public static String champion() {
        return champion;
    }

    /** その陣営の AI が使う版。 */
    public static AiVersion forTeam(MinecraftServer server, String teamId) {
        MatchTeam team = MatchState.of(server).team(teamId);
        String wanted = team == null ? "" : team.aiVersion();
        AiVersion found = wanted.isEmpty() ? null : VERSIONS.get(wanted);

        return found != null ? found : get(defaultId());
    }

    /** 採用版にする。書けたら true。 */
    public static boolean promote(String id) {
        if (!VERSIONS.containsKey(id)) {
            return false;
        }

        champion = id;

        return AiFiles.writeNow(AiFiles.root().resolve("champion.txt"), id);
    }

    /**
     * 全部のパラメータの名前と既定値。重みを読むコードに記録する集まりを通して集める
     * （{@link ParameterSet#recorder}）——一覧を別に書くと、コードと一覧が必ずずれる。
     */
    public static ParameterSet defaults() {
        ParameterSet recorder = ParameterSet.recorder();

        RuleBasedPolicy.Weights.of(recorder);
        ObjectiveScoring.Weights.of(recorder);
        RoutePlanner.Weights.of(recorder);
        MapKnowledge.Weights.of(recorder);
        TargetSelector.Weights.of(recorder);
        CoverFinder.Weights.of(recorder);
        FlankPlanner.Weights.of(recorder);
        RetreatPlanner.Weights.of(recorder);
        KillScore.Weights.of(recorder);
        Reinforcements.Weights.of(recorder);

        for (VehicleRole role : VehicleRole.VALUES) {
            TacticalProfile.tuned(role, recorder);
        }

        return new ParameterSet(recorder.asked());
    }

    /**
     * その版の、既定値を補った全部のパラメータを {@code exports/<id>.json} へ書く。読み込まれない場所——見るため。
     *
     * @return 書いたファイル。版が無いか書けなければ null
     */
    @Nullable
    public static Path export(String id) {
        AiVersion version = VERSIONS.get(id);

        if (version == null) {
            return null;
        }

        Path file = AiFiles.root().resolve("exports").resolve(id + ".json");
        String json = new GsonBuilder().setPrettyPrinting().create()
                .toJson(version.toJson(defaults().overriddenBy(version.parameters())));

        return AiFiles.writeNow(file, json) ? file : null;
    }

    /**
     * その版から新しい版を作る。全部のパラメータを書いた {@code versions/<name>.json} を置き、一覧へ足す。
     * 数値を直したら {@code /tdm ai versions reload}。
     *
     * @return 書いたファイル。元が無い・名前が使えない・既にある・書けないなら null
     */
    @Nullable
    public static Path fork(String from, String name) {
        AiVersion source = VERSIONS.get(from);

        if (source == null || !AiVersion.NAME.matcher(name).matches() || VERSIONS.containsKey(name)) {
            return null;
        }

        ParameterSet parameters = defaults().overriddenBy(source.parameters());
        AiVersion forked = new AiVersion(name, source.policy(), "Forked from " + from, from, parameters, false);
        Path file = AiFiles.versions().resolve(name + ".json");

        if (!AiFiles.writeNow(file, new GsonBuilder().setPrettyPrinting().create().toJson(forked.toJson(parameters)))) {
            return null;
        }

        VERSIONS.put(name, forked);

        return file;
    }

    /** 名前の一覧。補完のため。 */
    public static List<String> ids() {
        return List.copyOf(VERSIONS.keySet());
    }
}
