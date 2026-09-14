package com.ashvehicles.command;

import java.nio.file.Path;
import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.ai.battlefield.TacticalMap;
import com.ashvehicles.ai.core.AiBudget;
import com.ashvehicles.ai.core.AiProfiler;
import com.ashvehicles.ai.debug.AiDebug;
import com.ashvehicles.ai.decision.ParameterSet;
import com.ashvehicles.ai.learning.AiVersion;
import com.ashvehicles.ai.learning.AiVersions;
import com.ashvehicles.ai.learning.BattleStatistics;
import com.ashvehicles.ai.learning.Evaluation;
import com.ashvehicles.ai.learning.MapKnowledge;
import com.ashvehicles.ai.learning.MapMemory;
import com.ashvehicles.ai.learning.SelfPlay;
import com.ashvehicles.match.Bots;
import com.ashvehicles.match.Deathmatch;
import com.ashvehicles.match.MatchState;
import com.ashvehicles.match.MatchTeam;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /tdm ai} ——戦闘 AI の可視化・計測・版・自己対戦。
 *
 * <pre>
 * /tdm ai debug [on|off]                   判断の可視化
 * /tdm ai inspect [半径]                    近くの AI の今の姿を文字で
 * /tdm ai perf                             系ごとの 1 tick あたりの処理時間と予算の不足
 * /tdm ai versions                         版の一覧（reload / export &lt;版&gt; / fork &lt;版&gt; &lt;新しい名前&gt;）
 * /tdm ai team &lt;陣営&gt; &lt;版|default&gt;         陣営の AI の版
 * /tdm ai stats [版]                        版ごとの戦績
 * /tdm ai eval &lt;挑戦者&gt; &lt;採用版&gt;            直接の対戦成績で採用してよいか
 * /tdm ai promote &lt;版&gt; [force]              採用版にする（門を通らなければ断る）
 * /tdm ai selfplay start &lt;戦闘数&gt; &lt;版A&gt; &lt;版B&gt; | stop | status
 * /tdm ai map [here|reset]                 試合を重ねて覚えた地図（今いるディメンション）
 * </pre>
 *
 * <p>{@code /tdm} そのものは {@link MatchCommand} が持つ。ここは {@code ai} の枝だけを同じ名前の下へ足す——
 * Brigadier は同じ名前の節を1つに合わせる。全部権限2。
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class AiCommand {
    private static final SuggestionProvider<CommandSourceStack> VERSIONS = (context, builder) ->
            SharedSuggestionProvider.suggest(AiVersions.ids(), builder);

    private static final SuggestionProvider<CommandSourceStack> TEAMS = (context, builder) ->
            SharedSuggestionProvider.suggest(MatchState.of(context.getSource().getServer()).teams().keySet(),
                    builder);

    private AiCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("tdm").then(node()));
        dispatcher.register(Commands.literal(AshVehicles.MODID).then(Commands.literal("tdm").then(node())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("ai")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("debug")
                        .executes(context -> debug(context, null))
                        .then(Commands.literal("on").executes(context -> debug(context, true)))
                        .then(Commands.literal("off").executes(context -> debug(context, false))))
                .then(Commands.literal("inspect")
                        .executes(context -> inspect(context, 64.0))
                        .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1.0, 1024.0))
                                .executes(context -> inspect(context,
                                        DoubleArgumentType.getDouble(context, "radius")))))
                .then(Commands.literal("perf").executes(AiCommand::perf))
                .then(Commands.literal("versions")
                        .executes(AiCommand::listVersions)
                        .then(Commands.literal("reload").executes(AiCommand::reloadVersions))
                        .then(Commands.literal("export")
                                .then(Commands.argument("version", StringArgumentType.word()).suggests(VERSIONS)
                                        .executes(AiCommand::exportVersion)))
                        .then(Commands.literal("fork")
                                .then(Commands.argument("version", StringArgumentType.word()).suggests(VERSIONS)
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(AiCommand::forkVersion)))))
                .then(Commands.literal("team")
                        .then(Commands.argument("team", StringArgumentType.word()).suggests(TEAMS)
                                .then(Commands.literal("default").executes(context -> setTeam(context, "")))
                                .then(Commands.argument("version", StringArgumentType.word()).suggests(VERSIONS)
                                        .executes(context -> setTeam(context,
                                                StringArgumentType.getString(context, "version"))))))
                .then(Commands.literal("stats")
                        .executes(context -> stats(context, null))
                        .then(Commands.argument("version", StringArgumentType.word()).suggests(VERSIONS)
                                .executes(context -> stats(context, StringArgumentType.getString(context, "version")))))
                .then(Commands.literal("eval")
                        .then(Commands.argument("challenger", StringArgumentType.word()).suggests(VERSIONS)
                                .then(Commands.argument("champion", StringArgumentType.word()).suggests(VERSIONS)
                                        .executes(AiCommand::evaluate))))
                .then(Commands.literal("promote")
                        .then(Commands.argument("version", StringArgumentType.word()).suggests(VERSIONS)
                                .executes(context -> promote(context, false))
                                .then(Commands.literal("force").executes(context -> promote(context, true)))))
                .then(Commands.literal("map")
                        .executes(AiCommand::mapStatus)
                        .then(Commands.literal("here").executes(AiCommand::mapHere))
                        .then(Commands.literal("reset").executes(AiCommand::mapReset)))
                .then(Commands.literal("selfplay")
                        .then(Commands.literal("start")
                                .then(Commands.argument("battles", IntegerArgumentType.integer(1, 10000))
                                        .then(Commands.argument("first", StringArgumentType.word()).suggests(VERSIONS)
                                                .then(Commands.argument("second", StringArgumentType.word())
                                                        .suggests(VERSIONS)
                                                        .executes(AiCommand::startSelfPlay)))))
                        .then(Commands.literal("stop").executes(AiCommand::stopSelfPlay))
                        .then(Commands.literal("status").executes(context -> {
                            context.getSource().sendSuccess(SelfPlay::status, false);

                            return 1;
                        })));
    }

    // ------------------------------------------------------------------
    // 見る
    // ------------------------------------------------------------------

    private static int debug(CommandContext<CommandSourceStack> context, @Nullable Boolean wanted)
            throws CommandSyntaxException {
        boolean on = AiDebug.toggle(context.getSource().getPlayerOrException(), wanted);

        context.getSource().sendSuccess(() -> Component.translatable(on
                ? "command.ashvehicles.tdm.ai.debug_on" : "command.ashvehicles.tdm.ai.debug_off"), false);

        return on ? 1 : 0;
    }

    private static int inspect(CommandContext<CommandSourceStack> context, double radius) {
        CommandSourceStack source = context.getSource();
        Entity from = source.getEntity();

        if (from == null) {
            // コンソール。場所を持たないので、全部の AI を出す。
            int shown = 0;

            for (var pilot : Bots.pilots()) {
                for (Component line : AiDebug.inspect(source.getServer(), pilot.vehicle(), 0.5)) {
                    source.sendSuccess(() -> line, false);
                }

                shown++;
            }

            return shown;
        }

        List<Component> lines = AiDebug.inspect(source.getServer(), from, radius);

        if (lines.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.ai.none_near",
                    Math.round(radius)), false);

            return 0;
        }

        for (Component line : lines) {
            source.sendSuccess(() -> line, false);
        }

        return lines.size() / 3;
    }

    private static int perf(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        if (!AiProfiler.ready()) {
            source.sendSuccess(() -> Component.literal("AI perf: the first " + AiProfiler.WINDOW
                    + "-tick window has not closed yet"), false);

            return 0;
        }

        source.sendSuccess(() -> Component.literal(String.format("AI perf (ms/tick, last %d ticks): total %.3f",
                AiProfiler.WINDOW, AiProfiler.totalMillisPerTick())), false);

        StringBuilder line = new StringBuilder("  ");

        for (AiProfiler.Section section : AiProfiler.Section.values()) {
            line.append(String.format("%s %.3f  ", section.name().toLowerCase(java.util.Locale.ROOT),
                    AiProfiler.millisPerTick(section)));
        }

        source.sendSuccess(() -> Component.literal(line.toString()), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "  budget denied so far: rays %d, plans %d, searches %d | live AI %d",
                AiBudget.denied(AiBudget.Kind.RAY), AiBudget.denied(AiBudget.Kind.PLAN),
                AiBudget.denied(AiBudget.Kind.SEARCH), Bots.pilots().size())), false);

        return 1;
    }

    // ------------------------------------------------------------------
    // 版
    // ------------------------------------------------------------------

    private static int listVersions(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String fallback = AiVersions.defaultId();

        for (AiVersion version : AiVersions.all()) {
            BattleStatistics.Tally tally = BattleStatistics.overall(version.id());

            source.sendSuccess(() -> Component.literal(String.format("%s%s — %s, %d parameters, %d battles, win %.1f%%%s",
                    version.id(), version.id().equals(fallback) ? " (default)" : "", version.policy(),
                    version.parameters().size(), tally.battles, tally.winRate() * 100.0,
                    version.description().isEmpty() ? "" : " — " + version.description())), false);
        }

        return AiVersions.all().size();
    }

    private static int reloadVersions(CommandContext<CommandSourceStack> context) {
        AiVersions.load();
        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.ai.versions_reloaded",
                AiVersions.all().size()), true);

        return AiVersions.all().size();
    }

    private static int exportVersion(CommandContext<CommandSourceStack> context) {
        String id = StringArgumentType.getString(context, "version");
        Path file = AiVersions.export(id);

        if (file == null) {
            context.getSource().sendFailure(Component.translatable("command.ashvehicles.tdm.ai.unknown_version", id));

            return 0;
        }

        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.ai.exported",
                file.toString()), false);

        return 1;
    }

    private static int forkVersion(CommandContext<CommandSourceStack> context) {
        String from = StringArgumentType.getString(context, "version");
        String name = StringArgumentType.getString(context, "name");
        Path file = AiVersions.fork(from, name);

        if (file == null) {
            context.getSource().sendFailure(Component.translatable("command.ashvehicles.tdm.ai.fork_failed", from,
                    name));

            return 0;
        }

        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.ai.exported",
                file.toString()), true);

        return 1;
    }

    private static int setTeam(CommandContext<CommandSourceStack> context, String version) {
        MinecraftServer server = context.getSource().getServer();
        MatchState state = MatchState.of(server);
        MatchTeam team = state.team(StringArgumentType.getString(context, "team"));

        if (team == null) {
            context.getSource().sendFailure(Component.translatable("command.ashvehicles.tdm.no_team"));

            return 0;
        }

        if (!version.isEmpty() && AiVersions.find(version) == null) {
            context.getSource().sendFailure(Component.translatable("command.ashvehicles.tdm.ai.unknown_version",
                    version));

            return 0;
        }

        team.setAiVersion(version);
        state.setDirty();
        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.ai.team_version",
                team.display(), AiVersions.forTeam(server, team.id()).id()), true);

        return 1;
    }

    private static int stats(CommandContext<CommandSourceStack> context, @Nullable String only) {
        CommandSourceStack source = context.getSource();
        int shown = 0;

        for (String version : BattleStatistics.versions()) {
            if (only != null && !only.equals(version)) {
                continue;
            }

            BattleStatistics.Tally tally = BattleStatistics.overall(version);

            source.sendSuccess(() -> Component.literal(String.format(
                    "%s — battles %d, win %.1f%%, kills/life %.2f, deaths/life %.2f, captures/battle %.2f,"
                            + " defenses/battle %.2f, survival %.0f s, damage/life %.0f, reward/life %.1f",
                    version, tally.battles, tally.winRate() * 100.0, tally.killRate(), tally.deathRate(),
                    tally.captureRate(), tally.defenseRate(), tally.averageSurvivalSeconds(), tally.averageDamage(),
                    tally.averageReward())), false);

            for (String opponent : BattleStatistics.opponents(version)) {
                BattleStatistics.Tally head = BattleStatistics.versus(version, opponent);

                source.sendSuccess(() -> Component.literal(String.format("  vs %s: battles %d, win %.1f%%", opponent,
                        head.battles, head.winRate() * 100.0)), false);
            }

            shown++;
        }

        if (shown == 0) {
            source.sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.ai.no_stats"), false);
        }

        return shown;
    }

    private static int evaluate(CommandContext<CommandSourceStack> context) {
        Evaluation.Verdict verdict = Evaluation.judge(StringArgumentType.getString(context, "challenger"),
                StringArgumentType.getString(context, "champion"));

        context.getSource().sendSuccess(() -> Component.literal(String.format("%s vs %s: %d battles, win %.1f%% — %s (%s)",
                verdict.challenger(), verdict.champion(), verdict.battles(), verdict.winRate() * 100.0,
                verdict.approved() ? "APPROVED" : "not approved", verdict.reason())), false);

        return verdict.approved() ? 1 : 0;
    }

    private static int promote(CommandContext<CommandSourceStack> context, boolean force) {
        String id = StringArgumentType.getString(context, "version");

        if (AiVersions.find(id) == null) {
            context.getSource().sendFailure(Component.translatable("command.ashvehicles.tdm.ai.unknown_version", id));

            return 0;
        }

        Evaluation.Verdict verdict = Evaluation.judge(id, AiVersions.defaultId());

        if (!force && !verdict.approved()) {
            context.getSource().sendFailure(Component.translatable("command.ashvehicles.tdm.ai.not_promoted", id,
                    verdict.reason()));

            return 0;
        }

        AiVersions.promote(id);
        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.ai.promoted", id), true);

        return 1;
    }

    // ------------------------------------------------------------------
    // 覚えた地図
    // ------------------------------------------------------------------

    private static int mapStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MapMemory memory = MapMemory.of(source.getLevel());

        if (memory == null) {
            source.sendSuccess(() -> Component.literal("AI map memory is off (learning.mapMemory = false)"), false);

            return 0;
        }

        MapKnowledge knowledge = memory.knowledge();
        MapKnowledge.Summary summary = knowledge.summary();

        source.sendSuccess(() -> Component.literal(String.format(
                "AI map memory of %s: %d battles, %d cells — %d where machines get stuck, %d under water,"
                        + " %d where they are destroyed, %d clean lanes",
                memory.dimension().location(), knowledge.battles(), summary.cells(), summary.traps(), summary.wet(),
                summary.deadly(), summary.lanes())), false);

        return summary.cells();
    }

    private static int mapHere(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MapMemory memory = MapMemory.of(source.getLevel());

        if (memory == null) {
            source.sendSuccess(() -> Component.literal("AI map memory is off (learning.mapMemory = false)"), false);

            return 0;
        }

        int cellX = TacticalMap.cellOf(source.getPosition().x);
        int cellZ = TacticalMap.cellOf(source.getPosition().z);
        MapKnowledge knowledge = memory.knowledge();
        MapKnowledge.Reading reading = knowledge.reading(cellX, cellZ);
        double cost = reading.cost(MapKnowledge.Weights.of(ParameterSet.EMPTY), 1.0);

        source.sendSuccess(() -> Component.literal(String.format(
                "cell %d, %d: entered %.1f, passed cleanly %.1f, stuck %.1f, under water %.1f, destroyed %.1f"
                        + " — trap %.2f, water %.2f, death %.2f, lane %.2f, route cost %+.2f per step (default weights)",
                cellX, cellZ, reading.visits(), knowledge.amount(cellX, cellZ, MapKnowledge.PASSES),
                knowledge.amount(cellX, cellZ, MapKnowledge.STALLS), knowledge.amount(cellX, cellZ, MapKnowledge.WET),
                knowledge.amount(cellX, cellZ, MapKnowledge.DEATHS), reading.trap(), reading.water(), reading.death(),
                reading.flow(), cost)), false);

        return 1;
    }

    private static int mapReset(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MapMemory memory = MapMemory.of(source.getLevel());

        if (memory == null) {
            source.sendSuccess(() -> Component.literal("AI map memory is off (learning.mapMemory = false)"), false);

            return 0;
        }

        memory.reset();
        source.sendSuccess(() -> Component.literal("AI map memory of " + memory.dimension().location()
                + " cleared"), true);

        return 1;
    }

    // ------------------------------------------------------------------
    // 自己対戦
    // ------------------------------------------------------------------

    private static int startSelfPlay(CommandContext<CommandSourceStack> context) {
        String first = StringArgumentType.getString(context, "first");
        String second = StringArgumentType.getString(context, "second");

        for (String version : List.of(first, second)) {
            if (AiVersions.find(version) == null) {
                context.getSource().sendFailure(Component.translatable(
                        "command.ashvehicles.tdm.ai.unknown_version", version));

                return 0;
            }
        }

        Component answer = SelfPlay.start(context.getSource().getServer(),
                IntegerArgumentType.getInteger(context, "battles"), first, second);

        context.getSource().sendSuccess(() -> answer, true);

        return 1;
    }

    private static int stopSelfPlay(CommandContext<CommandSourceStack> context) {
        Component answer = SelfPlay.stop(context.getSource().getServer());

        context.getSource().sendSuccess(() -> answer, true);
        Deathmatch.sync(context.getSource().getServer(), MatchState.of(context.getSource().getServer()));

        return 1;
    }
}
