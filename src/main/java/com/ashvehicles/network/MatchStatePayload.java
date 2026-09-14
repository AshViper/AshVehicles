package com.ashvehicles.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.MatchView;
import com.ashvehicles.match.Bots;
import com.ashvehicles.match.MatchPoint;
import com.ashvehicles.match.MatchState;
import com.ashvehicles.match.MatchTeam;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 試合の掲示板。相、陣営ごとの残りチケット、拠点の持ち主と制圧の進み、残り時間、そして受け取る本人の
 * 所属と出撃待ち。
 *
 * <p><b>1人ずつ違う内容になる。</b> チケットと時間と拠点は全員に同じだが、「自分がどちらか」と「あと何
 * tick で出撃できるか」は受け手ごとに違う。片方だけを別のパケットに分けても得はない——どちらも同じ
 * 瞬間に同じ画面が要求する。
 *
 * <p>毎秒送る。試合中に変わり続ける値は残り時間と制圧の進みで、どちらも秒の粒度で足りる。
 */
public record MatchStatePayload(int phase, List<Team> teams, List<Point> points, String own, int ticksLeft,
        int startTickets, int waitTicks, int purse) implements CustomPacketPayload {
    /**
     * 掲示板に載る陣営1つ。
     *
     * <p><b>名簿まで配る。</b> 味方を色で囲うには「その人がどちら側か」をクライアントが知っている必要が
     * あり、自分の所属だけでは足りない。配るのは味方の分も敵の分も同じだが、囲うのは味方だけだ
     * （{@code client/MatchMarks}）——敵の位置を壁越しに配る計器はこの MOD の趣味ではない。
     *
     * <p><b>{@code machines} は AI の車両。</b> 人の機体は「乗っている者」から味方かどうかが決まるが
     * （{@code MatchMarks.isFriendly}）、AI には乗っている者がいない——20 対 20 の戦場で味方の戦車が1両も
     * 光らないのでは、この名簿を配っている意味が半分無くなる。陣営タグはサーバーの持ち物なので、
     * どの車両が自陣の AI かはここでしか伝えられない。
     */
    public record Team(String id, String name, String color, int tickets, int kills, List<UUID> members,
            List<UUID> machines) {
    }

    /** 掲示板に載る拠点1つ。持ち主が空文字なら中立。 */
    public record Point(BlockPos pos, String name, double radius, String owner, String taking, float progress,
            boolean contested) {
    }

    public static final CustomPacketPayload.Type<MatchStatePayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "match_state"));

    public static final StreamCodec<FriendlyByteBuf, MatchStatePayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeVarInt(payload.phase());
                buf.writeVarInt(payload.teams().size());

                for (Team team : payload.teams()) {
                    buf.writeUtf(team.id());
                    buf.writeUtf(team.name());
                    buf.writeUtf(team.color());
                    buf.writeVarInt(team.tickets());
                    buf.writeVarInt(team.kills());
                    buf.writeVarInt(team.members().size());

                    for (UUID member : team.members()) {
                        buf.writeUUID(member);
                    }

                    buf.writeVarInt(team.machines().size());

                    for (UUID machine : team.machines()) {
                        buf.writeUUID(machine);
                    }
                }

                buf.writeVarInt(payload.points().size());

                for (Point point : payload.points()) {
                    buf.writeBlockPos(point.pos());
                    buf.writeUtf(point.name());
                    buf.writeDouble(point.radius());
                    buf.writeUtf(point.owner());
                    buf.writeUtf(point.taking());
                    buf.writeFloat(point.progress());
                    buf.writeBoolean(point.contested());
                }

                buf.writeUtf(payload.own());
                buf.writeVarInt(payload.ticksLeft());
                buf.writeVarInt(payload.startTickets());
                buf.writeVarInt(payload.waitTicks());
                buf.writeVarInt(payload.purse());
            },
            buf -> {
                int phase = buf.readVarInt();
                int teamCount = buf.readVarInt();
                List<Team> teams = new ArrayList<>(teamCount);

                for (int at = 0; at < teamCount; at++) {
                    String id = buf.readUtf();
                    String name = buf.readUtf();
                    String colour = buf.readUtf();
                    int tickets = buf.readVarInt();
                    int kills = buf.readVarInt();
                    int roster = buf.readVarInt();
                    List<UUID> members = new ArrayList<>(roster);

                    for (int who = 0; who < roster; who++) {
                        members.add(buf.readUUID());
                    }

                    int fleet = buf.readVarInt();
                    List<UUID> machines = new ArrayList<>(fleet);

                    for (int which = 0; which < fleet; which++) {
                        machines.add(buf.readUUID());
                    }

                    teams.add(new Team(id, name, colour, tickets, kills, members, machines));
                }

                int pointCount = buf.readVarInt();
                List<Point> points = new ArrayList<>(pointCount);

                for (int at = 0; at < pointCount; at++) {
                    points.add(new Point(buf.readBlockPos(), buf.readUtf(), buf.readDouble(), buf.readUtf(),
                            buf.readUtf(), buf.readFloat(), buf.readBoolean()));
                }

                return new MatchStatePayload(phase, teams, points, buf.readUtf(), buf.readVarInt(),
                        buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
            });

    @Override
    public CustomPacketPayload.Type<MatchStatePayload> type() {
        return TYPE;
    }

    /** その1人に宛てた今の掲示板。 */
    public static MatchStatePayload of(MatchState state, ServerPlayer player) {
        List<Team> teams = new ArrayList<>();

        // 残りチケット順ではなく作られた順に載せる。計器はバーを左右に1つずつ並べるので、順が入れ替わる
        // と、1枚減るたびに赤と青が入れ替わる掲示板になる。
        for (MatchTeam team : state.teams().values()) {
            teams.add(new Team(team.id(), team.name(), team.color().getName(), team.tickets(), team.kills(),
                    List.copyOf(team.members()), Bots.machinesOf(team.id())));
        }

        List<Point> points = new ArrayList<>();

        for (MatchPoint point : state.points().values()) {
            points.add(new Point(point.pos(), point.name(), point.radius(),
                    point.owner() == null ? "" : point.owner(),
                    point.taking() == null ? "" : point.taking(), point.fraction(), point.contested()));
        }

        MatchTeam own = state.teamOf(player.getUUID());
        long now = player.level().getGameTime();
        long ready = state.readyAt(player.getUUID());

        return new MatchStatePayload(state.phase().ordinal(), teams, points, own == null ? "" : own.id(),
                Math.max(state.ticksLeft(), 0), state.startTickets(),
                (int) Math.max(ready - now, 0L), state.pointsOf(player.getUUID()));
    }

    public static void send(MatchState state, ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, of(state, player));
    }

    /**
     * クライアント向けとしてのみ登録されているので、これはクライアントでしか走らない。専用サーバーが
     * {@link MatchView} を解決することはない。
     */
    public static void handle(MatchStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> MatchView.accept(payload));
    }
}
