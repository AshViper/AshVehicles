package com.ashvehicles.command;

import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.match.Bots;
import com.ashvehicles.match.Deathmatch;
import com.ashvehicles.match.MatchPoint;
import com.ashvehicles.match.MatchState;
import com.ashvehicles.match.MatchTeam;
import com.ashvehicles.registry.ModBlocks;
import com.ashvehicles.registry.ModItems;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ColorArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /tdm} ——チームデスマッチの設営と進行。
 *
 * <p>この MOD で唯一のコマンド。運営の手順は決まっていて、それがそのまま並びになっている:
 *
 * <pre>
 * /tdm team add red red        陣営を作る
 * /tdm spawn red               見ている出撃地点ブロックを赤の旗にする
 * /tdm point add A 16          見ている旗竿を拠点にする（名前 A、半径16）
 * /tdm auto @a                 全員を人数の少ない方から振り分ける
 * /tdm start 100 20            チケット100枚・20分で開始
 * </pre>
 *
 * <p>拠点を1つでも置けば拠点制圧になる。種目を選ぶ設定は無い——握られている側のチケットが減り始める
 * というだけで、それが制圧戦だ。
 *
 * <p>陣営を作る・旗を立てる・試合を動かすのは運営（権限2）。所属と得点を<em>見る</em>だけの
 * {@code status} は誰でも打てる——自分が何点差で負けているかを知るのに権限は要らない。
 *
 * <p>出撃そのものはコマンドに無い。あれは旗を触って開く盤の仕事で、選ぶ物が多すぎてコマンドの引数には
 * 収まらない（{@code client/screen/DeployScreen}）。
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class MatchCommand {
    /** 旗を探す距離。見ている先にブロックが無ければコマンドは断る。 */
    private static final double REACH = 12.0;

    private static final SimpleCommandExceptionType NO_TEAM =
            new SimpleCommandExceptionType(Component.translatable("command.ashvehicles.tdm.no_team"));
    private static final SimpleCommandExceptionType NOT_A_SPAWN =
            new SimpleCommandExceptionType(Component.translatable("command.ashvehicles.tdm.not_a_spawn"));
    private static final SimpleCommandExceptionType EXISTS =
            new SimpleCommandExceptionType(Component.translatable("command.ashvehicles.tdm.team_exists"));
    private static final SimpleCommandExceptionType NOT_A_POINT =
            new SimpleCommandExceptionType(Component.translatable("command.ashvehicles.tdm.not_a_point"));
    private static final SimpleCommandExceptionType NEEDS_TEAMS =
            new SimpleCommandExceptionType(Component.translatable("command.ashvehicles.tdm.needs_teams"));

    /** 今ある陣営の名前を補完する。打ち間違いは試合中に一番したくないことだ。 */
    private static final SuggestionProvider<CommandSourceStack> TEAMS = (context, builder) ->
            SharedSuggestionProvider.suggest(
                    MatchState.of(context.getSource().getServer()).teams().keySet(), builder);

    /** AI に持たせられる車両と機体。撃てない物・艦・渡さない機体は最初から出さない。 */
    private static final SuggestionProvider<CommandSourceStack> BOT_VEHICLES = (context, builder) ->
            SharedSuggestionProvider.suggestResource(Bots.usable(Bots.machines()), builder);

    /** 今の一覧に入っている車両。外すときに打ち直さなくてよいように。 */
    private static final SuggestionProvider<CommandSourceStack> BOT_POOL = (context, builder) ->
            SharedSuggestionProvider.suggestResource(
                    MatchState.of(context.getSource().getServer()).botPool(), builder);

    private MatchCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("tdm")
                .then(Commands.literal("team")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("add")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.argument("color", ColorArgument.color())
                                                .executes(context -> addTeam(context,
                                                        StringArgumentType.getString(context, "id"),
                                                        ColorArgument.getColor(context, "color"),
                                                        StringArgumentType.getString(context, "id")))
                                                .then(Commands.argument("name", StringArgumentType.string())
                                                        .executes(context -> addTeam(context,
                                                                StringArgumentType.getString(context, "id"),
                                                                ColorArgument.getColor(context, "color"),
                                                                StringArgumentType.getString(context, "name")))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("team", StringArgumentType.word())
                                        .suggests(TEAMS)
                                        .executes(MatchCommand::removeTeam)))
                        .then(Commands.literal("list").executes(MatchCommand::listTeams)))
                .then(Commands.literal("spawn")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("remove")
                                .executes(context -> removeSpawn(context, looking(context)))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(context -> removeSpawn(context,
                                                BlockPosArgument.getLoadedBlockPos(context, "pos")))))
                        .then(Commands.argument("team", StringArgumentType.word())
                                .suggests(TEAMS)
                                .executes(context -> addSpawn(context, looking(context)))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(context -> addSpawn(context,
                                                BlockPosArgument.getLoadedBlockPos(context, "pos"))))))
                .then(Commands.literal("point")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("add")
                                .executes(context -> addPoint(context, "", MatchPoint.DEFAULT_RADIUS))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(context -> addPoint(context,
                                                StringArgumentType.getString(context, "name"),
                                                MatchPoint.DEFAULT_RADIUS))
                                        .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1.0, 256.0))
                                                .executes(context -> addPoint(context,
                                                        StringArgumentType.getString(context, "name"),
                                                        DoubleArgumentType.getDouble(context, "radius"))))))
                        .then(Commands.literal("remove")
                                .executes(MatchCommand::removePoint))
                        .then(Commands.literal("owner")
                                .then(Commands.literal("neutral")
                                        .executes(context -> setPointOwner(context, null)))
                                .then(Commands.argument("team", StringArgumentType.word())
                                        .suggests(TEAMS)
                                        .executes(context -> setPointOwner(context, team(context)))))
                        .then(Commands.literal("list").executes(MatchCommand::listPoints)))
                .then(Commands.literal("join")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("team", StringArgumentType.word())
                                .suggests(TEAMS)
                                .executes(context -> join(context, List.of(context.getSource().getPlayerOrException())))
                                .then(Commands.argument("players", EntityArgument.players())
                                        .executes(context -> join(context,
                                                EntityArgument.getPlayers(context, "players"))))))
                .then(Commands.literal("auto")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> auto(context, List.of(context.getSource().getPlayerOrException())))
                        .then(Commands.argument("players", EntityArgument.players())
                                .executes(context -> auto(context, EntityArgument.getPlayers(context, "players")))))
                .then(Commands.literal("leave")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> leave(context, List.of(context.getSource().getPlayerOrException())))
                        .then(Commands.argument("players", EntityArgument.players())
                                .executes(context -> leave(context, EntityArgument.getPlayers(context, "players")))))
                .then(Commands.literal("start")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> start(context, -1, -1))
                        .then(Commands.argument("tickets", IntegerArgumentType.integer(1))
                                .executes(context -> start(context,
                                        IntegerArgumentType.getInteger(context, "tickets"), -1))
                                .then(Commands.argument("minutes", IntegerArgumentType.integer(0))
                                        .executes(context -> start(context,
                                                IntegerArgumentType.getInteger(context, "tickets"),
                                                IntegerArgumentType.getInteger(context, "minutes"))))))
                .then(Commands.literal("stop")
                        .requires(source -> source.hasPermission(2))
                        .executes(MatchCommand::stop))
                .then(Commands.literal("reset")
                        .requires(source -> source.hasPermission(2))
                        .executes(MatchCommand::reset))
                .then(Commands.literal("clear")
                        .requires(source -> source.hasPermission(2))
                        .executes(MatchCommand::clear))
                .then(Commands.literal("bot")
                        .requires(source -> source.hasPermission(2))
                        .executes(MatchCommand::listBots)
                        .then(Commands.literal("list").executes(MatchCommand::listBots))
                        .then(Commands.literal("all")
                                .then(Commands.argument("count", IntegerArgumentType.integer(0, Bots.MOST))
                                        .executes(MatchCommand::setAllBots)))
                        .then(Commands.literal("pool")
                                .then(Commands.literal("add")
                                        .then(Commands.argument("vehicle", ResourceLocationArgument.id())
                                                .suggests(BOT_VEHICLES)
                                                .executes(context -> poolEdit(context, true))))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("vehicle", ResourceLocationArgument.id())
                                                .suggests(BOT_POOL)
                                                .executes(context -> poolEdit(context, false))))
                                .then(Commands.literal("clear").executes(MatchCommand::poolClear))
                                .then(Commands.literal("list").executes(MatchCommand::poolList)))
                        .then(Commands.argument("team", StringArgumentType.word())
                                .suggests(TEAMS)
                                .then(Commands.argument("count", IntegerArgumentType.integer(0, Bots.MOST))
                                        .executes(MatchCommand::setBots))))
                .then(Commands.literal("set")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("arena")
                                .then(Commands.argument("blocks", DoubleArgumentType.doubleArg(0.0, 10000.0))
                                        .executes(MatchCommand::setArena)))
                        .then(Commands.literal("remaining")
                                .then(Commands.argument("team", StringArgumentType.word())
                                        .suggests(TEAMS)
                                        .then(Commands.argument("tickets", IntegerArgumentType.integer(0))
                                                .executes(MatchCommand::setRemaining))))
                        .then(Commands.literal("tickets")
                                .then(Commands.argument("tickets", IntegerArgumentType.integer(1))
                                        .executes(MatchCommand::setTickets)))
                        .then(Commands.literal("bleed")
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1))
                                        .executes(MatchCommand::setBleed)))
                        .then(Commands.literal("loss")
                                .then(Commands.argument("tickets", IntegerArgumentType.integer(0))
                                        .executes(MatchCommand::setLoss)))
                        .then(Commands.literal("points")
                                .then(Commands.argument("points", IntegerArgumentType.integer(0))
                                        .executes(MatchCommand::setStartPoints)))
                        .then(Commands.literal("killaward")
                                .then(Commands.argument("points", IntegerArgumentType.integer(0))
                                        .executes(MatchCommand::setKillAward)))
                        .then(Commands.literal("captureaward")
                                .then(Commands.argument("points", IntegerArgumentType.integer(0))
                                        .executes(MatchCommand::setCaptureAward)))
                        .then(Commands.literal("time")
                                .then(Commands.argument("minutes", IntegerArgumentType.integer(0))
                                        .executes(MatchCommand::setTime)))
                        .then(Commands.literal("respawn")
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(0))
                                        .executes(MatchCommand::setRespawn)))
                        .then(Commands.literal("friendlyfire")
                                .then(Commands.argument("allowed", BoolArgumentType.bool())
                                        .executes(MatchCommand::setFriendlyFire)))
                        .then(Commands.literal("terrain")
                                .then(Commands.argument("allowed", BoolArgumentType.bool())
                                        .executes(MatchCommand::setTerrain))))
                .then(Commands.literal("points")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("players", EntityArgument.players())
                                .then(Commands.argument("points", IntegerArgumentType.integer(0))
                                        .executes(MatchCommand::givePoints))))
                .then(Commands.literal("status").executes(MatchCommand::status));

        dispatcher.register(root);
        // 長い方の綴りでも通す。この MOD のコマンドを探す人が最初に打つのは MOD の名前だ。
        dispatcher.register(Commands.literal(AshVehicles.MODID).then(root));
    }

    // ------------------------------------------------------------------
    // 陣営
    // ------------------------------------------------------------------

    private static int addTeam(CommandContext<CommandSourceStack> context, String id, ChatFormatting color,
            String name) throws CommandSyntaxException {
        MatchState state = state(context);

        if (state.team(id) != null) {
            throw EXISTS.create();
        }

        MatchTeam team = new MatchTeam(id, name, color);

        state.addTeam(team);
        Deathmatch.sync(context.getSource().getServer(), state);
        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.team_added",
                team.display()), true);

        return 1;
    }

    private static int removeTeam(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        MatchState state = state(context);
        MatchTeam team = team(context);

        state.removeTeam(team.id());
        Deathmatch.sync(context.getSource().getServer(), state);
        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.team_removed",
                team.display()), true);

        return 1;
    }

    private static int listTeams(CommandContext<CommandSourceStack> context) {
        MatchState state = state(context);

        if (state.teams().isEmpty()) {
            context.getSource().sendSuccess(
                    () -> Component.translatable("command.ashvehicles.tdm.no_teams"), false);

            return 0;
        }

        for (MatchTeam team : state.teams().values()) {
            context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.team_line",
                    team.display(), team.members().size(), team.spawns().size(), team.tickets(),
                    team.kills()), false);
        }

        return state.teams().size();
    }

    // ------------------------------------------------------------------
    // 旗
    // ------------------------------------------------------------------

    private static int addSpawn(CommandContext<CommandSourceStack> context, BlockPos pos)
            throws CommandSyntaxException {
        MatchState state = state(context);
        MatchTeam team = team(context);
        ServerLevel level = context.getSource().getLevel();

        if (!level.getBlockState(pos).is(ModBlocks.TEAM_SPAWN.get())) {
            throw NOT_A_SPAWN.create();
        }

        // 旗は1つの陣営の物。持ち主を替えるときに古い登録が残っていれば、両方の陣営がそこから湧く。
        MatchTeam previous = state.teamAt(pos);

        if (previous != null) {
            previous.removeSpawn(pos);
        }

        team.addSpawn(pos);
        // 旗を立てた者の世界が試合の世界。人のいない側の陣地を開けたままにするのに要る
        // （{@code match/MatchAnchors}）——旗そのものは次元を持たないので、ここで覚えるしかない。
        state.setWorld(level.dimension());
        state.setDirty();
        Deathmatch.sync(context.getSource().getServer(), state);
        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.spawn_added",
                team.display(), pos.getX(), pos.getY(), pos.getZ()), true);

        return 1;
    }

    private static int removeSpawn(CommandContext<CommandSourceStack> context, BlockPos pos) {
        MatchState state = state(context);
        MatchTeam owner = state.teamAt(pos);

        if (owner == null) {
            context.getSource().sendFailure(Component.translatable("command.ashvehicles.tdm.not_a_spawn"));

            return 0;
        }

        owner.removeSpawn(pos);
        state.setDirty();
        Deathmatch.sync(context.getSource().getServer(), state);
        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.spawn_removed",
                owner.display(), pos.getX(), pos.getY(), pos.getZ()), true);

        return 1;
    }

    /**
     * 打った者が見ているブロック。座標を書かずに済ませるための1本で、運営は旗の前に立っている。
     */
    private static BlockPos looking(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(REACH));
        HitResult hit = player.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, player));

        if (hit.getType() != HitResult.Type.BLOCK) {
            throw NOT_A_SPAWN.create();
        }

        return ((BlockHitResult) hit).getBlockPos();
    }

    // ------------------------------------------------------------------
    // 所属
    // ------------------------------------------------------------------

    private static int join(CommandContext<CommandSourceStack> context, Collection<ServerPlayer> players)
            throws CommandSyntaxException {
        MatchState state = state(context);
        MatchTeam team = team(context);
        MinecraftServer server = context.getSource().getServer();

        for (ServerPlayer player : players) {
            Deathmatch.join(server, state, player, team);
            player.displayClientMessage(Component.translatable("message.ashvehicles.match.joined",
                    team.display()), false);
        }

        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.joined",
                players.size(), team.display()), true);

        return players.size();
    }

    /** 人数の少ない陣営へ順に入れる。1人入れるたびに数え直すので、偶数でも奇数でも均す。 */
    private static int auto(CommandContext<CommandSourceStack> context, Collection<ServerPlayer> players)
            throws CommandSyntaxException {
        MatchState state = state(context);
        MinecraftServer server = context.getSource().getServer();

        if (state.teams().isEmpty()) {
            throw NEEDS_TEAMS.create();
        }

        for (ServerPlayer player : players) {
            MatchTeam team = state.smallestTeam();

            if (team == null) {
                break;
            }

            Deathmatch.join(server, state, player, team);
            player.displayClientMessage(Component.translatable("message.ashvehicles.match.joined",
                    team.display()), false);
        }

        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.balanced",
                players.size()), true);

        return players.size();
    }

    private static int leave(CommandContext<CommandSourceStack> context, Collection<ServerPlayer> players) {
        MatchState state = state(context);
        MinecraftServer server = context.getSource().getServer();

        for (ServerPlayer player : players) {
            Deathmatch.leave(server, state, player.getUUID());
        }

        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.left",
                players.size()), true);

        return players.size();
    }

    // ------------------------------------------------------------------
    // 進行
    // ------------------------------------------------------------------

    private static int start(CommandContext<CommandSourceStack> context, int tickets, int minutes)
            throws CommandSyntaxException {
        MatchState state = state(context);

        if (state.teams().size() < 2) {
            throw NEEDS_TEAMS.create();
        }

        if (tickets > 0) {
            state.setStartTickets(tickets);
        }

        if (minutes >= 0) {
            state.setDuration(minutes * 60 * 20);
        }

        // 旗を立てる前に世界を覚えそこねていても、始める者は必ず会場に立っている。
        if (state.world() == null) {
            state.setWorld(context.getSource().getLevel().dimension());
        }

        Deathmatch.start(context.getSource().getServer(), state);
        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.started",
                state.startTickets(), state.duration() / 20 / 60), true);

        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> context) {
        MatchState state = state(context);

        Deathmatch.finish(context.getSource().getServer(), state, Deathmatch.leader(state));

        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> context) {
        MatchState state = state(context);
        int removed = Deathmatch.clear(context.getSource().getServer());

        state.reset();
        Deathmatch.sync(context.getSource().getServer(), state);
        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.reset", removed),
                true);

        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> context) {
        int removed = Deathmatch.clear(context.getSource().getServer());

        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.cleared", removed),
                true);

        return removed;
    }

    // ------------------------------------------------------------------
    // AI
    // ------------------------------------------------------------------

    /**
     * その陣営が立てる AI の数を据える。
     *
     * <p><b>据えるのは数だけで、出すのは試合の側だ。</b> ここで数字を書いても、試合が動いていなければ何も
     * 湧かない——{@link Bots#tick} が補充するのは {@code RUNNING} の間だけであり、多すぎる分を下げるのも
     * そちらだ。だから試合中に数字を触れば、2秒ほどで実際の数がそこへ寄っていく。
     */
    private static int setBots(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        MatchState state = state(context);
        MatchTeam team = team(context);
        int count = IntegerArgumentType.getInteger(context, "count");

        team.setBots(count);
        state.setDirty();
        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.bots_set",
                team.display(), count), true);

        return count;
    }

    private static int setAllBots(CommandContext<CommandSourceStack> context) {
        MatchState state = state(context);
        int count = IntegerArgumentType.getInteger(context, "count");

        for (MatchTeam team : state.teams().values()) {
            team.setBots(count);
        }

        state.setDirty();
        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.bots_all",
                count), true);

        return count;
    }

    private static int listBots(CommandContext<CommandSourceStack> context) {
        MatchState state = state(context);

        context.getSource().sendSuccess(
                () -> Component.translatable("command.ashvehicles.tdm.bots_header"), false);

        for (MatchTeam team : state.teams().values()) {
            context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.bots_line",
                    team.display(), team.bots(), Bots.count(team.id()), Bots.ticking(team.id())), false);
        }

        return state.teams().size();
    }

    /** AI に持たせてよい車両の一覧を編集する。 */
    private static int poolEdit(CommandContext<CommandSourceStack> context, boolean add) {
        MatchState state = state(context);
        ResourceLocation vehicle = ResourceLocationArgument.getId(context, "vehicle");

        if (add && !Bots.usable(vehicle)) {
            context.getSource().sendFailure(
                    Component.translatable("command.ashvehicles.tdm.pool_unusable", vehicle.toString()));

            return 0;
        }

        if (add) {
            if (!state.botPool().contains(vehicle)) {
                state.botPool().add(vehicle);
            }
        } else {
            state.botPool().remove(vehicle);
        }

        state.setDirty();
        context.getSource().sendSuccess(() -> Component.translatable(add
                ? "command.ashvehicles.tdm.pool_added"
                : "command.ashvehicles.tdm.pool_removed", vehicle.toString()), true);

        return state.botPool().size();
    }

    private static int poolClear(CommandContext<CommandSourceStack> context) {
        MatchState state = state(context);

        state.botPool().clear();
        state.setDirty();
        context.getSource().sendSuccess(
                () -> Component.translatable("command.ashvehicles.tdm.pool_cleared"), true);

        return 1;
    }

    private static int poolList(CommandContext<CommandSourceStack> context) {
        MatchState state = state(context);

        if (state.botPool().isEmpty()) {
            context.getSource().sendSuccess(
                    () -> Component.translatable("command.ashvehicles.tdm.pool_empty"), false);

            return 0;
        }

        context.getSource().sendSuccess(
                () -> Component.translatable("command.ashvehicles.tdm.pool_header"), false);

        for (ResourceLocation vehicle : state.botPool()) {
            context.getSource().sendSuccess(() -> Component.translatable(
                    "command.ashvehicles.tdm.pool_line", vehicle.toString()), false);
        }

        return state.botPool().size();
    }

    // ------------------------------------------------------------------
    // 設定
    // ------------------------------------------------------------------

    private static int setArena(CommandContext<CommandSourceStack> context) {
        MatchState state = state(context);
        double blocks = DoubleArgumentType.getDouble(context, "blocks");

        state.setArenaRadius(blocks);
        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.arena_set",
                (int) blocks), true);

        return (int) blocks;
    }

    private static int setRemaining(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        MatchState state = state(context);
        MatchTeam team = team(context);
        int tickets = IntegerArgumentType.getInteger(context, "tickets");

        team.setTickets(tickets);
        state.setDirty();
        Deathmatch.sync(context.getSource().getServer(), state);
        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.remaining_set",
                team.display(), tickets), true);

        return tickets;
    }

    private static int setTickets(CommandContext<CommandSourceStack> context) {
        MatchState state = state(context);
        int tickets = IntegerArgumentType.getInteger(context, "tickets");

        state.setStartTickets(tickets);
        Deathmatch.sync(context.getSource().getServer(), state);
        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.tickets_set",
                tickets), true);

        return tickets;
    }

    /** 機体1つを失うごとに減るチケット。デスマッチだけの試合を早く終わらせたい運営の唯一の摘み。 */
    private static int setLoss(CommandContext<CommandSourceStack> context) {
        MatchState state = state(context);
        int tickets = IntegerArgumentType.getInteger(context, "tickets");

        state.setLossCost(tickets);
        Deathmatch.sync(context.getSource().getServer(), state);
        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.loss_set",
                tickets), true);

        return tickets;
    }

    private static int setStartPoints(CommandContext<CommandSourceStack> context) {
        MatchState state = state(context);
        int points = IntegerArgumentType.getInteger(context, "points");

        state.setStartPoints(points);
        Deathmatch.sync(context.getSource().getServer(), state);
        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.points_set",
                points), true);

        return points;
    }

    private static int setKillAward(CommandContext<CommandSourceStack> context) {
        MatchState state = state(context);
        int points = IntegerArgumentType.getInteger(context, "points");

        state.setKillAward(points);
        Deathmatch.sync(context.getSource().getServer(), state);
        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.kill_award_set",
                points), true);

        return points;
    }

    private static int setCaptureAward(CommandContext<CommandSourceStack> context) {
        MatchState state = state(context);
        int points = IntegerArgumentType.getInteger(context, "points");

        state.setCaptureAward(points);
        Deathmatch.sync(context.getSource().getServer(), state);
        context.getSource().sendSuccess(
                () -> Component.translatable("command.ashvehicles.tdm.capture_award_set", points), true);

        return points;
    }

    /** 誰かの財布を直に据える。配り忘れと、試合中の手当てのため。 */
    private static int givePoints(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        MatchState state = state(context);
        Collection<ServerPlayer> players = EntityArgument.getPlayers(context, "players");
        int points = IntegerArgumentType.getInteger(context, "points");

        for (ServerPlayer player : players) {
            state.setPoints(player.getUUID(), points);
        }

        Deathmatch.sync(context.getSource().getServer(), state);
        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.points_given",
                players.size(), points), true);

        return players.size();
    }

    private static int setBleed(CommandContext<CommandSourceStack> context) {
        MatchState state = state(context);
        int seconds = IntegerArgumentType.getInteger(context, "seconds");

        state.setBleedInterval(seconds * 20);
        Deathmatch.sync(context.getSource().getServer(), state);
        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.bleed_set",
                seconds), true);

        return seconds;
    }

    private static int setTime(CommandContext<CommandSourceStack> context) {
        MatchState state = state(context);
        int minutes = IntegerArgumentType.getInteger(context, "minutes");

        state.setDuration(minutes * 60 * 20);

        // 試合中に伸ばしたなら、残り時間もその場で伸びる。次の試合まで待たせる理由が無い。
        if (state.isRunning()) {
            state.setTicksLeft(state.duration());
        }

        Deathmatch.sync(context.getSource().getServer(), state);
        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.time_set",
                minutes), true);

        return minutes;
    }

    private static int setRespawn(CommandContext<CommandSourceStack> context) {
        MatchState state = state(context);
        int seconds = IntegerArgumentType.getInteger(context, "seconds");

        state.setRespawnTicks(seconds * 20);
        Deathmatch.sync(context.getSource().getServer(), state);
        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.respawn_set",
                seconds), true);

        return seconds;
    }

    private static int setFriendlyFire(CommandContext<CommandSourceStack> context) {
        MatchState state = state(context);
        boolean allowed = BoolArgumentType.getBool(context, "allowed");

        state.setFriendlyFire(allowed);
        Deathmatch.sync(context.getSource().getServer(), state);
        context.getSource().sendSuccess(() -> Component.translatable(allowed
                ? "command.ashvehicles.tdm.friendly_fire_on"
                : "command.ashvehicles.tdm.friendly_fire_off"), true);

        return allowed ? 1 : 0;
    }

    /** 試合中に地形を壊せるようにするか。既定は壊せない。 */
    private static int setTerrain(CommandContext<CommandSourceStack> context) {
        MatchState state = state(context);
        boolean allowed = BoolArgumentType.getBool(context, "allowed");

        state.setTerrainDamage(allowed);
        Deathmatch.sync(context.getSource().getServer(), state);
        context.getSource().sendSuccess(() -> Component.translatable(allowed
                ? "command.ashvehicles.tdm.terrain_on"
                : "command.ashvehicles.tdm.terrain_off"), true);

        return allowed ? 1 : 0;
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        MatchState state = state(context);

        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.status",
                Component.translatable("command.ashvehicles.tdm.phase."
                        + state.phase().name().toLowerCase(java.util.Locale.ROOT)),
                state.ticksLeft() / 20, state.startTickets()), false);

        for (MatchTeam team : state.ranked()) {
            context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.team_line",
                    team.display(), team.members().size(), team.spawns().size(), team.tickets(),
                    team.kills()), false);
        }

        for (MatchPoint point : state.points().values()) {
            MatchTeam owner = point.owner() == null ? null : state.team(point.owner());

            context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.point_line",
                    Component.literal(point.name()),
                    owner == null ? Component.translatable("message.ashvehicles.match.neutral") : owner.display(),
                    Math.round(point.radius()), Math.round(point.fraction() * 100.0F)), false);
        }

        return state.teams().size();
    }

    // ------------------------------------------------------------------
    // 拠点
    // ------------------------------------------------------------------

    private static int addPoint(CommandContext<CommandSourceStack> context, String name, double radius)
            throws CommandSyntaxException {
        MatchState state = state(context);
        BlockPos pos = looking(context);

        if (!context.getSource().getLevel().getBlockState(pos).is(ModBlocks.CAPTURE_POINT.get())) {
            throw NOT_A_POINT.create();
        }

        // 名前を書かなければ A から順に振る。制圧戦の拠点は現場で「A を取れ」と呼ばれるもので、
        // 座標では呼ばれない。
        String called = name.isEmpty()
                ? String.valueOf((char) (65 + Math.min(state.points().size(), 25)))
                : name;
        MatchPoint point = new MatchPoint(pos, called, radius);

        state.addPoint(point);
        state.setWorld(context.getSource().getLevel().dimension());
        Deathmatch.sync(context.getSource().getServer(), state);
        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.point_added",
                Component.literal(called), Math.round(radius), pos.getX(), pos.getY(), pos.getZ()), true);

        return 1;
    }

    private static int removePoint(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        MatchState state = state(context);
        BlockPos pos = looking(context);
        MatchPoint point = state.pointAt(pos);

        if (point == null) {
            throw NOT_A_POINT.create();
        }

        state.removePoint(pos);
        Deathmatch.sync(context.getSource().getServer(), state);
        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.point_removed",
                Component.literal(point.name())), true);

        return 1;
    }

    /**
     * 見ている拠点の持ち主を据える。<b>据えるのは初期配置</b>——今の持ち主が動くと同時に、次の試合の
     * 開始時に戻る先もここになる（{@code MatchPoint.home}）。試合中の制圧はこちらを書き換えない。
     */
    private static int setPointOwner(CommandContext<CommandSourceStack> context, @Nullable MatchTeam team)
            throws CommandSyntaxException {
        MatchState state = state(context);
        BlockPos pos = looking(context);
        MatchPoint point = state.pointAt(pos);

        if (point == null) {
            throw NOT_A_POINT.create();
        }

        point.setHome(team == null ? null : team.id());
        state.setDirty();
        Deathmatch.sync(context.getSource().getServer(), state);
        context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.point_owner",
                Component.literal(point.name()),
                team == null ? Component.translatable("message.ashvehicles.match.neutral") : team.display()),
                true);

        return 1;
    }

    private static int listPoints(CommandContext<CommandSourceStack> context) {
        MatchState state = state(context);

        if (state.points().isEmpty()) {
            context.getSource().sendSuccess(
                    () -> Component.translatable("command.ashvehicles.tdm.no_points"), false);

            return 0;
        }

        for (MatchPoint point : state.points().values()) {
            MatchTeam owner = point.owner() == null ? null : state.team(point.owner());

            context.getSource().sendSuccess(() -> Component.translatable("command.ashvehicles.tdm.point_line",
                    Component.literal(point.name()),
                    owner == null ? Component.translatable("message.ashvehicles.match.neutral") : owner.display(),
                    Math.round(point.radius()), Math.round(point.fraction() * 100.0F)), false);
        }

        return state.points().size();
    }

    // ------------------------------------------------------------------
    // 引数の解決
    // ------------------------------------------------------------------

    private static MatchState state(CommandContext<CommandSourceStack> context) {
        return MatchState.of(context.getSource().getServer());
    }

    private static MatchTeam team(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        MatchTeam team = state(context).team(StringArgumentType.getString(context, "team"));

        if (team == null) {
            throw NO_TEAM.create();
        }

        return team;
    }
}
