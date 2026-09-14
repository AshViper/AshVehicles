package com.ashvehicles.match;

import java.util.Comparator;

import com.ashvehicles.AshVehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * 試合中、陣地と拠点の周りを開けたままにする。
 *
 * <p><b>1人で試合を回すと、敵陣は誰の視界にも入らない。</b> Minecraft が chunk を開けておくのはプレイヤー
 * の周りだけなので、向こう側の旗は存在しないのと同じ状態にある——そして AI の出撃は、置く場所の地面を
 * {@code getChunkNow} で確かめてから置く（[[explosions-generate-chunks]] と同じ理由で、出撃1回が
 * ワールド生成1回になってはいけない）。確かめられなければ置けない。<b>「1人だと敵が湧かない」の正体は
 * これで、AI の問題ではなく陣地が閉じていたことの問題だった。</b>
 *
 * <p>開けるのは旗の周り5×5 chunk。出撃の散らばり（旗から半径30ブロック）がそこに収まる大きさで、
 * 出た AI はその先を自分のチケットで運んでいく（{@code ai/BotChunkLoader}）。
 *
 * <p><b>チケットは自動失効する物を置き直す。</b> 保存される強制ロードにすると、試合を片付けた後も
 * ——あるいは旗を壊した後も——その chunk がワールドの寿命いっぱい開いたままになる。試合が止まれば
 * 置き直す者がいなくなり、数秒で勝手に閉じる。
 *
 * <p><b>どの世界かは帳簿が覚えている</b>（{@link MatchState#world}）。旗そのものは次元を持たない——
 * 制圧の判定は「その者の世界にその旗竿が建っているか」で閉じており、それは人が必ずどこかに立っている
 * から成り立つ。人のいない陣地を開けるには、それだけでは足りない。
 */
public final class MatchAnchors {
    /** チケットの寿命（tick）と置き直す間隔（tick）。 */
    private static final int TIMEOUT = 200;
    private static final int EVERY = 40;

    /**
     * 旗のまわりに開ける半径（chunk）。3 で 7×7。
     *
     * <p><b>出撃が散らばる円（旗から半径30ブロック）が全部その中に入る大きさ</b>でなければならない。
     * 足りないと、円の外側に落ちた候補は「まだ無い地面」として捨てられ、残った内側の候補だけで
     * 散らすことになる——旗のすぐ近くに全車両が固まる形で現れる。
     */
    private static final int RADIUS = 3;

    /** 自動失効・非保存のチケット。{@code ai/BotChunkLoader} の物と同じ性質。 */
    private static final TicketType<ChunkPos> FLAG = TicketType.create(
            AshVehicles.MODID + ":match_flag", Comparator.comparingLong(ChunkPos::toLong), TIMEOUT);

    private MatchAnchors() {
    }

    /**
     * 陣地と拠点の chunk を開けたままにする。{@link Deathmatch#onServerTick} から毎tick呼ばれ、中で間引く。
     *
     * <p>動いている試合の間だけ。設営中の会場や、終わった試合の跡地を開けておく理由は無い。
     */
    public static void hold(MinecraftServer server, MatchState state) {
        if (!state.isRunning() || server.getTickCount() % EVERY != 0) {
            return;
        }

        ServerLevel level = state.level(server);

        if (level == null) {
            return;
        }

        for (MatchTeam team : state.teams().values()) {
            for (BlockPos spawn : team.spawns()) {
                anchor(level, spawn);
            }
        }

        for (MatchPoint point : state.points().values()) {
            anchor(level, point.pos());
        }
    }

    /**
     * その座標のまわりを開ける。要求は非同期で、生成は生成器が自分のスレッドで行う——ここで待って
     * しまえば、試合の開始が tick スレッド上のワールド生成になる。
     */
    private static void anchor(ServerLevel level, BlockPos at) {
        ChunkPos chunk = new ChunkPos(at);

        level.getChunkSource().addRegionTicket(FLAG, chunk, RADIUS, chunk);
    }
}
