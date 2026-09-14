package com.ashvehicles.ai.log;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.ashvehicles.ai.AiConfig;
import com.ashvehicles.ai.BotPilot;
import com.ashvehicles.ai.decision.BattleState;
import com.ashvehicles.ai.decision.TacticalAction;
import com.ashvehicles.ai.learning.AiVersions;
import com.ashvehicles.match.Bots;
import com.ashvehicles.match.MatchPoint;
import com.ashvehicles.match.MatchState;
import com.ashvehicles.match.MatchTeam;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * 進行中の戦闘1回の記録。{@link BattleEvents} だけが使う。
 *
 * <p>始まりに会場と陣営の版を書き、途中は出来事と判断を1行ずつ足し、終わりに結果と学習用の行を書く。書き出しは
 * 全部 {@link AiFiles} の書き手スレッドで、ここは文字列を作って渡すだけ。
 */
final class BattleRecorder {
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private static final Gson COMPACT = new GsonBuilder().serializeNulls().create();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static int counter;

    final String battleId;
    final long startTick;
    final Map<String, String> teamVersions = new LinkedHashMap<>();
    final Map<UUID, LifeRecord> lives = new LinkedHashMap<>();

    private final Path directory;
    private final Path events;
    private final Path decisions;
    private final Path flights;
    private final boolean writeDecisions;

    private BattleRecorder(String battleId, long startTick) {
        this.battleId = battleId;
        this.startTick = startTick;
        this.directory = AiFiles.battles().resolve(battleId);
        this.events = this.directory.resolve("events.jsonl");
        this.decisions = this.directory.resolve("decisions.jsonl");
        this.flights = this.directory.resolve("flights.jsonl");
        this.writeDecisions = AiConfig.get().logging().decisions();
    }

    /** 戦闘を始める。会場・陣営・版・特徴量の並びを書く。 */
    static BattleRecorder begin(MinecraftServer server, MatchState state, long now) {
        String id = LocalDateTime.now().format(STAMP) + "-" + (++counter);
        BattleRecorder recorder = new BattleRecorder(id, now);
        JsonObject meta = new JsonObject();
        ServerLevel level = state.level(server);
        Vec3 centre = Bots.centre(server, state);

        meta.addProperty("battle", id);
        meta.addProperty("started", LocalDateTime.now().toString());
        meta.addProperty("start_tick", now);
        meta.addProperty("world", level == null ? null : level.dimension().location().toString());
        meta.addProperty("arena_x", Math.round(centre.x));
        meta.addProperty("arena_z", Math.round(centre.z));
        meta.addProperty("arena_radius", state.arenaRadius());
        meta.addProperty("tickets", state.startTickets());
        meta.addProperty("duration_ticks", state.duration());

        JsonArray points = new JsonArray();

        for (MatchPoint point : state.points().values()) {
            JsonObject one = new JsonObject();

            one.addProperty("name", point.name());
            one.addProperty("x", point.pos().getX());
            one.addProperty("y", point.pos().getY());
            one.addProperty("z", point.pos().getZ());
            one.addProperty("radius", point.radius());
            one.addProperty("home", point.home());
            points.add(one);
        }

        meta.add("points", points);

        JsonArray teams = new JsonArray();

        for (MatchTeam team : state.teams().values()) {
            String version = AiVersions.forTeam(server, team.id()).id();
            JsonObject one = new JsonObject();

            recorder.teamVersions.put(team.id(), version);
            one.addProperty("id", team.id());
            one.addProperty("version", version);
            one.addProperty("bots", team.bots());
            one.addProperty("players", team.members().size());
            teams.add(one);
        }

        meta.add("teams", teams);

        JsonArray features = new JsonArray();

        BattleState.FEATURES.forEach(features::add);
        meta.add("features", features);
        AiFiles.replace(recorder.directory.resolve("meta.json"), PRETTY.toJson(meta));

        return recorder;
    }

    /** その AI の一生の記録。まだ無ければ作る。 */
    LifeRecord life(BotPilot pilot, long now) {
        return this.lives.computeIfAbsent(pilot.vehicle().getUUID(), key -> new LifeRecord(pilot, now));
    }

    /** 出来事を1行。 */
    void event(long now, BattleEventType type, @Nullable LifeRecord life, @Nullable String team,
            @Nullable JsonObject detail, double reward) {
        this.event(now, type, life, team, detail, reward, null);
    }

    /**
     * 出来事を1行。{@code at} があれば、その車両がいた位置も書く——どこで水に入り、どこで倒されたかを地図に置くため
     * （{@code tool/ai/viewer}）。判断の行の位置から推すと、最長5秒ずれる。
     */
    void event(long now, BattleEventType type, @Nullable LifeRecord life, @Nullable String team,
            @Nullable JsonObject detail, double reward, @Nullable Vec3 at) {
        JsonObject json = new JsonObject();

        json.addProperty("battle", this.battleId);
        json.addProperty("t", now);
        json.addProperty("type", type.id());
        json.addProperty("team", life != null ? life.team : team);
        json.addProperty("bot", life == null ? null : life.uuid.toString());
        json.addProperty("version", life == null ? (team == null ? null : this.teamVersions.get(team)) : life.version);
        json.addProperty("vehicle", life == null ? null : life.vehicle);

        if (at != null) {
            json.addProperty("x", Math.round(at.x * 10.0) / 10.0);
            json.addProperty("y", Math.round(at.y * 10.0) / 10.0);
            json.addProperty("z", Math.round(at.z * 10.0) / 10.0);
        }

        if (reward != 0.0) {
            json.addProperty("reward", BattleDecisionLog.round(reward));
        }

        if (detail != null) {
            json.add("detail", detail);
        }

        AiFiles.append(this.events, COMPACT.toJson(json));
    }

    /** 航空機の飛び方を1行（{@link FlightLog}）。 */
    void flight(JsonObject row) {
        AiFiles.append(this.flights, COMPACT.toJson(row));
    }

    /** 判断を1つ受け取る。前の判断はここで報酬が締まって書かれる。 */
    void decision(LifeRecord life, BattleDecisionLog log) {
        this.closeDecision(life, log.timestamp());
        life.open = log;
        life.decisions++;
        life.actions.merge(log.action(), 1, Integer::sum);
    }

    /** 一生を終える。受け取っている最中の判断を書く。 */
    void closeLife(LifeRecord life, long now, boolean destroyed) {
        if (life.ended()) {
            return;
        }

        this.closeDecision(life, now);
        life.endTick = now;
        life.destroyed = destroyed;
    }

    /** 戦闘を終える。結果と学習用の行を書き、統計のための要約を返す。 */
    BattleSummary end(@Nullable String winner, long now, boolean aborted) {
        for (LifeRecord life : this.lives.values()) {
            this.closeLife(life, now, life.destroyed);
        }

        JsonObject summary = new JsonObject();

        summary.addProperty("battle", this.battleId);
        summary.addProperty("aborted", aborted);
        summary.addProperty("winner", winner);
        summary.addProperty("duration_ticks", now - this.startTick);

        JsonObject versions = new JsonObject();

        this.teamVersions.forEach(versions::addProperty);
        summary.add("versions", versions);

        JsonArray lives = new JsonArray();

        for (LifeRecord life : this.lives.values()) {
            JsonObject row = life.toJson(this.battleId, now, winner);

            lives.add(row);

            if (!aborted) {
                AiFiles.append(AiFiles.training().resolve("lives.jsonl"), COMPACT.toJson(row));
            }
        }

        JsonObject battleRow = summary.deepCopy();

        summary.add("lives", lives);
        AiFiles.replace(this.directory.resolve("summary.json"), PRETTY.toJson(summary));

        if (!aborted) {
            AiFiles.append(AiFiles.training().resolve("battles.jsonl"), COMPACT.toJson(battleRow));
        }

        AiFiles.closeFile(this.events);
        AiFiles.closeFile(this.decisions);
        AiFiles.closeFile(this.flights);
        AiFiles.flush();

        return new BattleSummary(this.battleId, aborted, winner, Map.copyOf(this.teamVersions),
                new ArrayList<>(this.lives.values()), now);
    }

    private void closeDecision(LifeRecord life, long now) {
        BattleDecisionLog open = life.open;

        life.open = null;

        if (open != null && this.writeDecisions) {
            AiFiles.append(this.decisions, COMPACT.toJson(open.toJson(this.battleId, now)));
        }
    }

    /** 記録の中の行動の名前。 */
    static String name(@Nullable TacticalAction action) {
        return action == null ? "-" : action.name();
    }
}
