package com.ashvehicles.ai.debug;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.ashvehicles.ai.AiConfig;
import com.ashvehicles.ai.BotPilot;
import com.ashvehicles.ai.battlefield.ThreatMap;
import com.ashvehicles.ai.core.AiDirector;
import com.ashvehicles.match.Bots;
import com.ashvehicles.match.MatchState;
import com.ashvehicles.match.MatchTeam;
import com.ashvehicles.network.AiDebugPayload;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 戦闘 AI の判断を見えるようにする、サーバー側の半分。
 *
 * <p><b>見たい運営にだけ送る</b>（{@code /tdm ai debug}）。送るのは近くの AI の「今の姿」——行動・持ち場・目標・
 * 脅威・経路の危険・行き先・探した遮蔽・道——と、その陣営の脅威マップの近くのマス。描くのはクライアント
 * （{@code client/AiDebugView}）で、判断には一切使わない。
 *
 * <p><b>敵の位置を配る計器になりうる</b>ので、権限2のコマンドでしか開かない（[[team-deathmatch-shape]] の発光の項：
 * 敵を光らせないのは意図）。
 *
 * <p>{@value #EVERY} tick に1回。専用サーバーのコンソールからは {@link #inspect} が同じ内容を文字で出す。
 */
public final class AiDebug {
    /** 送る間隔（tick）。 */
    private static final int EVERY = 10;

    /** 脅威マップのマスを送る距離（ブロック）と数の上限。 */
    private static final double CELL_REACH = 96.0;
    private static final int MOST_CELLS = 600;

    /** 1両の道を送る点の上限。 */
    private static final int MOST_ROUTE = 24;

    private static final Set<UUID> WATCHERS = new HashSet<>();

    private AiDebug() {
    }

    /**
     * 可視化を切り替える。
     *
     * @param wanted null なら反転
     * @return 切り替えた後に表示しているか
     */
    public static boolean toggle(ServerPlayer player, @Nullable Boolean wanted) {
        boolean on = wanted != null ? wanted : !WATCHERS.contains(player.getUUID());

        if (on) {
            WATCHERS.add(player.getUUID());
        } else {
            WATCHERS.remove(player.getUUID());
            PacketDistributor.sendToPlayer(player, AiDebugPayload.EMPTY);
        }

        return on;
    }

    /** {@code core/AiDirector} から毎 tick。中で間引く。 */
    public static void tick(MinecraftServer server, MatchState state) {
        if (WATCHERS.isEmpty() || server.getTickCount() % EVERY != 0) {
            return;
        }

        AiConfig.Debugging settings = AiConfig.get().debug();
        List<BotPilot> pilots = Bots.pilots();

        for (UUID watcher : WATCHERS) {
            ServerPlayer player = server.getPlayerList().getPlayer(watcher);

            if (player == null) {
                continue;
            }

            List<BotPilot> near = nearest(pilots, player, settings.range(), settings.maxMachines());
            List<AiDebugPayload.Machine> machines = new ArrayList<>(near.size());

            for (BotPilot pilot : near) {
                machines.add(machine(pilot.snapshot(), colourOf(state, pilot.team())));
            }

            List<AiDebugPayload.Cell> cells = near.isEmpty() ? List.of()
                    : cells(AiDirector.brain(near.get(0).team()).intel().threats(), player.position());

            PacketDistributor.sendToPlayer(player, new AiDebugPayload(machines, cells, ThreatMap.CELL));
        }
    }

    /**
     * 近くの AI の今の姿を文字で。{@code /tdm ai inspect}。
     *
     * <p>直前の判断で点数の高かった行動も上から並べる——なぜその行動なのかは、2番手との差で読む。
     */
    public static List<Component> inspect(MinecraftServer server, Entity from, double radius) {
        MatchState state = MatchState.of(server);
        List<Component> lines = new ArrayList<>();

        for (BotPilot pilot : nearest(Bots.pilots(), from, radius, 64)) {
            PilotSnapshot snapshot = pilot.snapshot();
            MatchTeam team = state.team(snapshot.team());

            lines.add(Component.literal("AI #" + snapshot.entityId() + " " + snapshot.vehicle() + " ["
                    + snapshot.role().name() + "] " + snapshot.version() + " ")
                    .append(team == null ? Component.literal(snapshot.team()) : team.display()));
            lines.add(Component.literal(String.format("  Objective: %s   Action: %s   Target: %s",
                    snapshot.objective() == null ? "-" : snapshot.objective(),
                    snapshot.action() == null ? "-" : snapshot.action().name(), describe(snapshot.target()))));
            lines.add(Component.literal(String.format("  Threat: %.2f   Route Risk: %.2f   HP: %d%%   at %d %d %d",
                    snapshot.threat(), snapshot.routeRisk(), Math.round(snapshot.health() * 100.0F),
                    Math.round(snapshot.at().x), Math.round(snapshot.at().y), Math.round(snapshot.at().z))));

            if (!snapshot.utilities().isEmpty()) {
                StringBuilder scores = new StringBuilder("  Scores:");

                snapshot.utilities().entrySet().stream()
                        .sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).limit(4)
                        .forEach(entry -> scores.append(String.format("  %s %.2f", entry.getKey().name(),
                                entry.getValue())));
                lines.add(Component.literal(scores.toString()));
            }
        }

        return lines;
    }

    public static void clear() {
        WATCHERS.clear();
    }

    private static List<BotPilot> nearest(List<BotPilot> pilots, Entity from, double radius, int most) {
        List<BotPilot> found = new ArrayList<>();

        for (BotPilot pilot : pilots) {
            if (pilot.vehicle().level() == from.level()
                    && pilot.vehicle().distanceToSqr(from) <= radius * radius) {
                found.add(pilot);
            }
        }

        found.sort(Comparator.comparingDouble(pilot -> pilot.vehicle().distanceToSqr(from)));

        return found.size() > most ? found.subList(0, most) : found;
    }

    private static AiDebugPayload.Machine machine(PilotSnapshot snapshot, int colour) {
        List<Vec3> route = snapshot.route().size() > MOST_ROUTE ? snapshot.route().subList(0, MOST_ROUTE)
                : snapshot.route();

        return new AiDebugPayload.Machine(snapshot.entityId(), "AI #" + snapshot.entityId() + " "
                + snapshot.vehicle(), colour, snapshot.role().name(),
                snapshot.action() == null ? "-" : snapshot.action().name(),
                snapshot.objective() == null ? "-" : snapshot.objective(), describe(snapshot.target()),
                snapshot.target() == null ? -1 : snapshot.target().getId(), (float) snapshot.threat(),
                (float) snapshot.routeRisk(), snapshot.health(), snapshot.version(), snapshot.at(),
                snapshot.destination(), snapshot.tactical() == null ? null : snapshot.tactical().pos(),
                snapshot.tactical() == null ? -1 : snapshot.tactical().kind().ordinal(), List.copyOf(route));
    }

    private static List<AiDebugPayload.Cell> cells(ThreatMap threats, Vec3 around) {
        List<AiDebugPayload.Cell> cells = new ArrayList<>();
        int reach = (int) Math.ceil(CELL_REACH / ThreatMap.CELL);
        int centreX = ThreatMap.cellOf(around.x);
        int centreZ = ThreatMap.cellOf(around.z);

        threats.forEach((cellX, cellZ, danger, exposure) -> {
            if (cells.size() < MOST_CELLS && Math.abs(cellX - centreX) <= reach && Math.abs(cellZ - centreZ) <= reach
                    && (danger >= 0.05F || exposure >= 0.05F)) {
                cells.add(new AiDebugPayload.Cell(cellX * ThreatMap.CELL, cellZ * ThreatMap.CELL, danger, exposure));
            }
        });

        return cells;
    }

    private static int colourOf(MatchState state, String team) {
        MatchTeam found = state.team(team);
        Integer rgb = found == null ? null : found.color().getColor();

        return rgb == null ? 0xFFFFFFFF : 0xFF000000 | rgb;
    }

    private static String describe(@Nullable Entity target) {
        return target == null ? "-" : target.getName().getString() + " #" + target.getId();
    }
}
