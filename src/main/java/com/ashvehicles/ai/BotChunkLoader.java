package com.ashvehicles.ai;

import java.util.Comparator;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.VehicleEntityBase;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

/**
 * AI の車両が走っている地面を、誰も見ていなくてもロードしておく。
 *
 * <p><b>これが無いと AI は戦場の真ん中で凍る。</b> Minecraft が chunk をロードしておくのはプレイヤーの
 * 周りだけで、シミュレーション距離の外に出たエンティティは tick されない——2人で 20 対 20 を回すという
 * のは、40両のうち 38 両が誰のシミュレーション距離にも入っていない、という意味そのものだ。
 *
 * <p><b>要求は自動失効する region チケット1枚だけ。</b> {@code TicketController} の強制ロード——
 * {@link com.ashvehicles.entity.AircraftChunkLoader} が使う方——は保存され、持ち主が自分で手放すまで
 * 残る。機体はそのために保持している chunk 集合を NBT に書いて突き合わせているが、AI にそこまでの帳簿は
 * 要らない。<b>寿命の短いチケットを毎秒置き直す</b>方が、片付けの経路を1本も持たずに同じことができる
 * ——AI が死ねば置き直す者がいなくなり、数秒で勝手に消える。再起動を跨いで残る物も無い。
 *
 * <p>持つのは2種類。<b>確保</b>（距離3）は自分と隣の chunk を {@code ENTITY_TICKING}（水準31以下）に保つ。
 * 「自分の chunk だけ」では足りない——境を跨いだ瞬間に tick が止まり、止まった車両は自分でチケットを
 * 置き直せないからだ（{@link #RADIUS} 参照）。<b>先読み</b>（{@link #lookAhead}）は進路の
 * 64ブロック先までを<em>生成だけ</em>させる予約で、ロードもtickもさせない——着いた時には地面が在る、
 * という状態を作るためだけにある。
 *
 * <p>2つを分けているのは値段が違うからだ。確保した chunk は tick するので、進路ぶんを確保で先読みすれば
 * 戦場の何倍もの広さが常時 tick する。飛行機が同じ問題を別の重さで解いているのが
 * {@code AircraftChunkLoader}（[[chunks-reach-the-pilot-by-distance-order]]）で、あちらの回廊が要るのは
 * 毎tick 数 chunk を横切る速度に対してだ。戦車は毎秒1 chunk も進まない。
 */
public final class BotChunkLoader {
    /** チケットの寿命（tick）。置き直しの間隔より十分に長く取る。 */
    private static final int TIMEOUT = 120;

    /** 先読みチケットの寿命（tick）。進路を変えた車両が捨てた地面を、勝手に手放させる長さ。 */
    private static final int LOOK_TIMEOUT = 300;

    /** 進路の先をどこまで頼むか（ブロック）と、その刻み。刻みは半 chunk なので途中の chunk を飛ばさない。 */
    private static final double LEAD = 64.0;
    private static final double SAMPLE = 8.0;

    /** 置き直す間隔（tick）。半径3の余裕（1 chunk）を、この間に走り抜けない長さ。 */
    static final int EVERY = 10;

    /**
     * 半径。<b>3 でなければならない。</b>
     *
     * <p>チケットの水準は中心から1 chunk ごとに1ずつ上がり、エンティティが tick するのは水準31以下
     * （{@code ChunkLevel.isEntityTicking}）。半径2だと中心だけが31で、<b>隣の chunk は32——ロードは
     * されるが tick はしない</b>。そして tick しないエンティティはチケットを置き直せない。
     *
     * <p><b>つまり半径2の AI は、chunk の境を跨いだ瞬間に永久に凍る。</b> 自分の足元しか tick する水準に
     * していないのだから、1歩でもそこを出れば tick が止まり、止まったら最後、置き直す者がいない。
     * プレイヤーが近くにいる間だけ動いて見えたのは、その人のチケットが周囲を31にしていたからで、
     * AI 自身は最初から自分を運べていなかった。
     *
     * <p>半径3なら中心が30、その隣が31——<b>3×3が tick する</b>。次の置き直しまでに戦車が進むのは
     * 数ブロックなので、境を跨いでも必ず内側にいる。代償はロードされる範囲が7×7に広がることだが、
     * そのうち tick するのは9個で、残りは「在るだけ」の chunk だ。
     */
    private static final int RADIUS = 3;

    /** 自動失効・非保存の region チケット。{@code AircraftChunkLoader} の先読みと同じ性質の物。 */
    private static final TicketType<ChunkPos> BOT = TicketType.create(
            AshVehicles.MODID + ":bot", Comparator.comparingLong(ChunkPos::toLong), TIMEOUT);

    /**
     * 進路の先を<em>生成だけ</em>させるチケット。ロードもtickもさせない。
     *
     * <p>確保（{@link #BOT}）と分けてあるのは値段が違うからだ。確保した chunk は tick する——そこに
     * 湧いた牛も、水も、竈も動く——が、これは生成器に「その地面をそのうち作っておいてくれ」と言うだけで、
     * 車両が着いた時には既に在る。40両ぶんの進路を確保で先読みすると、戦場の何倍もの広さが常時 tick する
     * ことになる。
     */
    private static final TicketType<ChunkPos> AHEAD = TicketType.create(
            AshVehicles.MODID + ":bot_ahead", Comparator.comparingLong(ChunkPos::toLong), LOOK_TIMEOUT);

    private BotChunkLoader() {
    }

    /**
     * その車両の足元をロードしたままにする。車両自身の tick から呼ぶこと。
     *
     * <p>安い。置くのはチケット1枚で、chunk が既にあれば帳簿の時刻を更新するだけになる。要求は非同期で、
     * 生成は生成器が自分のスレッドで行う——{@code forceChunk} のように、呼んだ tick の中で地形を建てて
     * しまうことはない（[[explosions-generate-chunks]] と同じ理由でそれを避けている）。
     */
    public static void hold(VehicleEntityBase vehicle) {
        if (!(vehicle.level() instanceof ServerLevel level) || vehicle.isRemoved()) {
            return;
        }

        ChunkPos at = vehicle.chunkPosition();

        level.getChunkSource().addRegionTicket(BOT, at, RADIUS, at);
    }

    /**
     * これから走る先の地面を、静かに生成させておく。
     *
     * <p><b>確保ではなく予約だ。</b> 車両が着く頃には地面が在る、という状態を作るためだけにあり、
     * ロードもtickもさせない。走っている間だけ頼むので、止まって撃ち合っている車両は何も要求しない。
     *
     * <p>これが無いと、AI は<em>まだ無い地面</em>へ向かって走る。{@code ai/Obstacles} は読めない chunk を
     * 「まだ知らない」として通すので、生成が追い付かなければ、AI は自分で作らせながら進むことになる
     * ——それは tick スレッドの上でワールドを作るのと同じ速さでしか進めない、という意味だ。
     *
     * @param yaw 走っていく方角（度）
     */
    public static void lookAhead(VehicleEntityBase vehicle, float yaw) {
        if (!(vehicle.level() instanceof ServerLevel level) || vehicle.isRemoved()) {
            return;
        }

        double radians = Math.toRadians(yaw);
        double dx = -Math.sin(radians);
        double dz = Math.cos(radians);
        ChunkPos last = vehicle.chunkPosition();

        for (double along = SAMPLE; along <= LEAD; along += SAMPLE) {
            ChunkPos pos = new ChunkPos(
                    SectionPos.blockToSectionCoord(Mth.floor(vehicle.getX() + dx * along)),
                    SectionPos.blockToSectionCoord(Mth.floor(vehicle.getZ() + dz * along)));

            // 距離0。その chunk を最後まで生成し、周囲は昇格させず、tick もさせない。
            if (!pos.equals(last)) {
                level.getChunkSource().addRegionTicket(AHEAD, pos, 0, pos);
                last = pos;
            }
        }
    }
}
