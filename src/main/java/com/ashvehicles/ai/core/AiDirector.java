package com.ashvehicles.ai.core;

import java.util.HashMap;
import java.util.Map;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.ai.battlefield.TerrainCache;
import com.ashvehicles.ai.debug.AiDebug;
import com.ashvehicles.ai.learning.AiVersions;
import com.ashvehicles.ai.learning.BattleStatistics;
import com.ashvehicles.ai.learning.MapMemory;
import com.ashvehicles.ai.learning.SelfPlay;
import com.ashvehicles.ai.log.AiFiles;
import com.ashvehicles.ai.log.BattleEvents;
import com.ashvehicles.ai.perception.LineOfSight;
import com.ashvehicles.ai.perception.WeaponReach;
import com.ashvehicles.ai.team.TeamBrain;
import com.ashvehicles.match.MatchState;
import com.ashvehicles.match.MatchTeam;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * サーバー1つの戦闘 AI の司令塔。陣営の頭（{@link TeamBrain}）を持ち、陣営単位の仕事・可視化・自己対戦を回し、
 * サーバーの始まりと終わりで AI の持ち物を読み込み・片付ける。
 *
 * <p><b>AI 1両1両の tick はここではない。</b> あれは車両自身の tick から呼ばれる（{@code GroundVehicleEntity.tick}
 * → {@code BotPilot.tick}）——ロードされていない chunk の車両まで動かさないためで、ここが名簿を歩いて動かすと
 * その前提が AI だけ崩れる（[[bots-are-a-pilot-object-on-the-vehicle]] の却下した案）。ここが回すのは陣営で1回
 * で済む物だけ。
 *
 * <p>呼ぶのは {@code Deathmatch.onServerTick}。サーバースレッド専用。
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class AiDirector {
    private static final Map<String, TeamBrain> BRAINS = new HashMap<>();

    private AiDirector() {
    }

    /** その陣営の頭。無ければ作る。 */
    public static TeamBrain brain(String team) {
        return BRAINS.computeIfAbsent(team, TeamBrain::new);
    }

    /** 1 tick 分。{@code Deathmatch.onServerTick} から毎 tick 呼ばれる。 */
    public static void tick(MinecraftServer server, MatchState state) {
        int tick = server.getTickCount();

        AiProfiler.tick(tick);

        long started = AiProfiler.start();

        if (state.isRunning()) {
            BRAINS.keySet().retainAll(state.teams().keySet());

            for (MatchTeam team : state.teams().values()) {
                brain(team.id()).tick(server, state);
            }
        } else if (!BRAINS.isEmpty()) {
            // 試合の外で敵情と拠点の履歴を持ち越さない。次の試合の「奪還」は次の試合の中で決まる。
            BRAINS.clear();
        }

        TerrainCache.pruneAll(tick);
        AiProfiler.stop(AiProfiler.Section.TEAM, started);

        started = AiProfiler.start();
        AiDebug.tick(server, state);
        AiProfiler.stop(AiProfiler.Section.DEBUG, started);

        SelfPlay.tick(server, state);
        BattleEvents.tick(server);
    }

    /** 試合が始まるときに。前の試合の敵情と拠点の履歴を持ち越さない。 */
    public static void reset() {
        BRAINS.clear();
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        AiVersions.load();
        BattleStatistics.load();
    }

    /**
     * サーバーを閉じる。書きかけの記録を閉じ、覚えている物を全部捨てる——1つの JVM で次のワールドを開いたとき、
     * 前のワールドの地形や敵情を持ち込まないように。
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        BattleEvents.serverStopping(event.getServer());
        // 書き手を止める（AiFiles.close）より前に。覚えた地図の最後の書き出しを、書き手が拾えるように。
        MapMemory.serverStopping();
        SelfPlay.abandon(event.getServer());
        AiDebug.clear();
        BRAINS.clear();
        LineOfSight.clear();
        TerrainCache.clear();
        WeaponReach.clear();
        AiBudget.clear();
        AiProfiler.clear();
        AiFiles.close();
    }
}
