package com.ashvehicles.ai.learning;

import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.ai.AiConfig;
import com.ashvehicles.match.Deathmatch;
import com.ashvehicles.match.MatchState;
import com.ashvehicles.match.MatchTeam;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * 自己対戦。2つの版を2つの陣営に乗せ、試合を続けて何度も回す。
 *
 * <pre>
 * 経験（戦闘記録） → 評価（BattleStatistics / Evaluation） → 学習（ゲームの外、tool/ai） → 新しい版 → 自己対戦 → …
 * </pre>
 *
 * <p><b>回すのは普通の試合そのもの。</b> 始めるのは {@code Deathmatch.start}、終わるのはチケットか時計——人が
 * 遊ぶ試合と1行も違わない。ここがするのは、陣営に版を割り当て、終わったら少し待って次を始めることだけ。記録と
 * 統計は試合の出口がいつも通り付ける（{@code log/BattleEvents}）。
 *
 * <p><b>陣営を入れ替える</b>（{@code versions.selfPlayAlternateSides}）。会場の片側が有利なら、同じ側に同じ版を
 * 置き続けた勝率は会場の偏りを測ってしまう。
 *
 * <p><b>時計の無い試合は決着しないことがある。</b> 制限時間が0の試合で始めたら、自己対戦の間だけ15分にし、終われば
 * 戻す。
 *
 * <p>自己対戦の状態は保存しない。サーバーが止まれば止まる（陣営の版と時計は戻す）。
 */
public final class SelfPlay {
    /** 制限時間の無い試合に、自己対戦の間だけ付ける長さ（分）。 */
    private static final int MINUTES = 15;

    private static final class Session {
        final int total;
        final String first;
        final String second;
        final String teamA;
        final String teamB;
        final String originalA;
        final String originalB;
        final int originalDuration;
        final boolean changedDuration;
        int played;
        long startAt;

        Session(int total, String first, String second, MatchTeam teamA, MatchTeam teamB, int originalDuration,
                boolean changedDuration, long startAt) {
            this.total = total;
            this.first = first;
            this.second = second;
            this.teamA = teamA.id();
            this.teamB = teamB.id();
            this.originalA = teamA.aiVersion();
            this.originalB = teamB.aiVersion();
            this.originalDuration = originalDuration;
            this.changedDuration = changedDuration;
            this.startAt = startAt;
        }
    }

    @Nullable
    private static Session session;

    private SelfPlay() {
    }

    /** 始める。返すのはコマンドへの1行。 */
    public static Component start(MinecraftServer server, int battles, String first, String second) {
        MatchState state = MatchState.of(server);

        if (session != null) {
            return status();
        }

        List<MatchTeam> teams = state.teams().values().stream().filter(team -> !team.spawns().isEmpty()).limit(2)
                .toList();

        if (state.isRunning() || teams.size() < 2) {
            return Component.translatable("command.ashvehicles.tdm.ai.selfplay_needs");
        }

        int duration = state.duration();
        boolean changed = duration <= 0;

        if (changed) {
            state.setDuration(MINUTES * 60 * 20);
        }

        session = new Session(battles, first, second, teams.get(0), teams.get(1), duration, changed,
                server.overworld().getGameTime());

        return Component.translatable("command.ashvehicles.tdm.ai.selfplay_started", battles, first, second);
    }

    /** 止める。動いている試合があれば決着させる（その試合は数えない）。 */
    public static Component stop(MinecraftServer server) {
        Session stopping = session;

        if (stopping == null) {
            return Component.translatable("command.ashvehicles.tdm.ai.selfplay_idle");
        }

        session = null;
        restore(server, stopping);

        MatchState state = MatchState.of(server);

        if (state.isRunning()) {
            Deathmatch.finish(server, state, Deathmatch.leader(state));
        }

        return Component.translatable("command.ashvehicles.tdm.ai.selfplay_stopped", stopping.played);
    }

    /** 次の試合を始める時が来ていれば始める。{@code core/AiDirector} から毎 tick。 */
    public static void tick(MinecraftServer server, MatchState state) {
        Session running = session;

        if (running == null || state.isRunning() || running.startAt < 0L
                || server.overworld().getGameTime() < running.startAt) {
            return;
        }

        MatchTeam teamA = state.team(running.teamA);
        MatchTeam teamB = state.team(running.teamB);

        if (teamA == null || teamB == null) {
            AshVehicles.LOGGER.warn("[ai] self-play stopped: a team was removed");
            session = null;

            return;
        }

        boolean swap = AiConfig.get().versions().alternateSides() && running.played % 2 == 1;

        teamA.setAiVersion(swap ? running.second : running.first);
        teamB.setAiVersion(swap ? running.first : running.second);
        state.setDirty();
        running.startAt = -1L;
        Deathmatch.start(server, state);
    }

    /** 試合が終わった。{@code log/BattleEvents} から。 */
    public static void battleEnded(MinecraftServer server, MatchState state, @Nullable MatchTeam winner) {
        Session running = session;

        if (running == null) {
            return;
        }

        running.played++;

        if (running.played >= running.total) {
            session = null;
            restore(server, running);
            Deathmatch.announce(server, Component.translatable("message.ashvehicles.ai.selfplay_done",
                    running.played).withStyle(ChatFormatting.AQUA));

            return;
        }

        int cooldown = AiConfig.get().versions().selfPlayCooldownTicks();

        running.startAt = server.overworld().getGameTime() + cooldown;
        Deathmatch.announce(server, Component.translatable("message.ashvehicles.ai.selfplay_next",
                running.played + 1, running.total, cooldown / 20).withStyle(ChatFormatting.AQUA));
    }

    public static Component status() {
        Session running = session;

        if (running == null) {
            return Component.translatable("command.ashvehicles.tdm.ai.selfplay_idle");
        }

        return Component.translatable("command.ashvehicles.tdm.ai.selfplay_status", running.played, running.total,
                running.first, running.second);
    }

    /** サーバーが止まるとき。陣営の版と時計を戻す。 */
    public static void abandon(MinecraftServer server) {
        Session running = session;

        session = null;

        if (running != null) {
            restore(server, running);
        }
    }

    /** 覚えている物を捨てる。 */
    public static void clear() {
        session = null;
    }

    private static void restore(MinecraftServer server, Session finished) {
        MatchState state = MatchState.of(server);
        MatchTeam teamA = state.team(finished.teamA);
        MatchTeam teamB = state.team(finished.teamB);

        if (teamA != null) {
            teamA.setAiVersion(finished.originalA);
        }

        if (teamB != null) {
            teamB.setAiVersion(finished.originalB);
        }

        if (finished.changedDuration) {
            state.setDuration(finished.originalDuration);
        }

        state.setDirty();
    }
}
