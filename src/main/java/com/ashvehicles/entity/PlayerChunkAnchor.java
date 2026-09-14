package com.ashvehicles.entity;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.ashvehicles.AshVehicles;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * プレイヤーが今いる chunk は、何があろうとロードされる。
 *
 * <p><b>なぜ要るのか。</b> この MOD はロードの中心を進行方向へ動かしている
 * （{@code FlightChunkCentreMixin}、最大10 chunk 先）。動かしているのは正方形の<em>位置</em>だけなので、
 * プレイヤー自身は今も四角の内側にいる——描画距離12・ずらし8なら、縁まで4 chunk の余裕がある。
 * だから「要求されない」ことは無い。<b>問題は要求されるかどうかではなく、何番目に叶えられるかだ。</b>
 *
 * <p>チケット水準はそのまま生成器の優先度になる。バニラでは
 * {@code ChunkHolder.queueLevel} から {@code ChunkTaskPriorityQueue} のバケット添字になり、低い方が先。
 * c2me が入っていれば {@code SchedulingManager.updatePriorityFromLevel} が同じ数字を
 * {@code BucketTaskPriorityQueue} の添字にする。どちらも同じ水準の中は到着順だ。そして視界の四角が
 * 買えるのは水準33で、c2me の no-tick ローダーはその四角を<em>中心から外へ</em>螺旋で歩き、一度に
 * 24 個ずつしか要求しない。中心は機体の8 chunk 前にある。つまりプレイヤー自身の足元の chunk は、
 * 螺旋の上でおよそ 17x17 個ぶん後ろに並ぶ。飽和した生成器では、それがそのまま「自分の下だけ無い」になる。
 *
 * <p><b>買っているのは順番であって量ではない。</b> 距離2のリージョンチケットは中心を水準31——
 * {@code PLAYER_TICKET_LEVEL} と同じ、entity ticking——に置き、外へ32、33 と傾斜する。31 は視界の
 * 四角の33より小さいので、この1個は四角の全部より前に生成される。増えるチャンクは5x5ぶんで、しかも
 * その大半は四角が既に頼んでいる物と同じだ。
 *
 * <p><b>同期ではない。</b> {@code addRegionTicket} はチケットを置いて返る。生成は生成器のスレッドで
 * 走り、tick スレッドは止まらない（{@link AircraftChunkLoader} の先読みと同じ性質で、
 * NeoForge の {@code forceChunk} とは違う。{@link CorridorClaim} 参照）。<b>約束しているのは
 * 「その chunk が最優先で作られること」であって「今この瞬間に地面があること」ではない。</b>
 * 届くまでの数 tick の扱いは {@link LateWorld} が持っている。
 *
 * <p><b>置きっぱなしにしない。</b> 時速1000km の機体は毎 tick 近く chunk を跨ぐので、素直に足すだけだと
 * entity ticking の帯が航跡に沿って伸び続ける。プレイヤーが chunk を移ったら前の1個は明示的に外す。
 * それでも取りこぼす経路——ログアウト、ディメンション移動、次元ごと消える——があるので、チケット自体にも
 * タイムアウトを持たせてある。帳簿が漏れても数秒で消える。
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class PlayerChunkAnchor {
    /**
     * 錨の届く距離。{@code 33 - distance} が水準なので、2 で31＝entity ticking。
     *
     * <p>バニラがプレイヤーのために使っている {@code DistanceManager.PLAYER_TICKET_LEVEL} と同じ数字を
     * 選んである。ここを更に下げても、生成の順番はもう視界の四角より前なので何も早くならない。
     * 上げると（距離1や0）足元が block ticking や border に落ちて、この錨が答えようとしている
     * 「当たり判定と着陸と被弾判定が問われる場所」ではなくなる。
     */
    private static final int DISTANCE = 2;

    /**
     * 帳簿が漏れたチケットが自分で消えるまで（tick）。
     *
     * <p>毎 tick 付け直すので、生きている錨がこの秒数を意識することは無い。効くのは帳簿から外れた時
     * だけ——プレイヤーが消えた、次元が変わった、サーバーが落ちかけている——で、その時に数秒で
     * 消えてくれれば足りる。
     */
    private static final int TIMEOUT = 60;

    /**
     * 錨のチケット型。バニラのリージョンチケットで、非同期・自動失効・非永続。
     *
     * <p>NeoForge の強制ロードを使わない理由は {@link AircraftChunkLoader#update} と同じだ。あれは
     * チケットを足した直後に {@code level.getChunk} を呼んで地形の完成を待つので、tick スレッドの上で
     * ワールド生成が走る。ここが直したいのはまさにその「待ち」なので、待つ道具では直せない。
     */
    private static final TicketType<ChunkPos> ANCHOR = TicketType.create(
            AshVehicles.MODID + ":player_anchor",
            Comparator.comparingLong(ChunkPos::toLong), TIMEOUT);

    /**
     * 今どこに錨を下ろしているか。プレイヤーごとに1つ。
     *
     * <p>次元も覚えるのは、外す時に<em>足した時と同じレベル</em>へ頼まなければならないからだ。
     * ディメンションを移ったプレイヤーの古い錨を新しい世界から外そうとしても、そこには無い。
     */
    private static final Map<UUID, Anchored> DROPPED = new HashMap<>();

    private PlayerChunkAnchor() {
    }

    /**
     * 錨が実際にどの水準・どの状態になるかを、起動時に1度だけ言わせる。
     *
     * <p>c2me はチケット水準から chunk の状態への写像を自分の物に差し替えている
     * （記憶ノート {@code c2me-rewrites-the-chunk-rules}）。そこで FULL 未満へ写る水準は「保留」に
     * 変えられ、チケットを置いても何も生成されない。31 は FULL より下（数字が小さい方が強い）なので
     * 保留にはならないはずだが、<b>はずだ、で済ませた所がこの MOD では何度か外れている。</b>
     * 出るのが {@code ENTITY_TICKING} でなければ、この錨は効いていない。
     */
    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        int level = ChunkLevel.byStatus(ChunkStatus.FULL) - DISTANCE;

        AshVehicles.LOGGER.info("[chunk] プレイヤーの錨は水準 {}（{}）。視界の四角は 33 なので、"
                + "足元は四角より先に作られる", level, ChunkLevel.fullStatus(level));
    }

    /**
     * 全プレイヤーの足元へ錨を置き直す。
     *
     * <p>{@code Pre} で走らせるのは、この tick のレベル更新
     * （{@code ServerChunkCache.tick} → {@code runDistanceManagerUpdates}）に間に合わせるため。
     * {@code Post} だと1 tick 遅れて効く。
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Pre event) {
        MinecraftServer server = event.getServer();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.serverLevel();
            ChunkPos pos = player.chunkPosition();
            Anchored was = DROPPED.get(player.getUUID());

            if (was != null && was.matches(level.dimension(), pos)) {
                // 同じ場所。付け直して時計だけ進める。addTicket は既にある物の createdTick を
                // 上書きするので、これがタイムアウトの更新になる。
                level.getChunkSource().addRegionTicket(ANCHOR, pos, DISTANCE, pos);

                continue;
            }

            if (was != null) {
                was.lift(server);
            }

            level.getChunkSource().addRegionTicket(ANCHOR, pos, DISTANCE, pos);
            DROPPED.put(player.getUUID(), new Anchored(level.dimension(), pos));
        }

        // 消えたプレイヤーの錨を落とす。チケット自体もタイムアウトで消えるが、帳簿を放っておくと
        // サーバーの寿命ぶん溜まる。
        DROPPED.entrySet().removeIf(entry -> {
            if (server.getPlayerList().getPlayer(entry.getKey()) != null) {
                return false;
            }

            entry.getValue().lift(server);

            return true;
        });
    }

    /** サーバーが止まる時に全部外す。次の起動へ持ち越さないため。 */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (Anchored anchored : DROPPED.values()) {
            anchored.lift(event.getServer());
        }

        DROPPED.clear();
    }

    /**
     * 下ろしてある錨1つ。
     *
     * <p>外すには足した時と同じ次元・同じ座標・同じ距離が要る。距離は定数なので、覚えるのは前の2つ。
     */
    private record Anchored(ResourceKey<Level> dimension, ChunkPos pos) {
        private boolean matches(ResourceKey<Level> other, ChunkPos here) {
            return this.dimension.equals(other) && this.pos.equals(here);
        }

        private void lift(MinecraftServer server) {
            ServerLevel level = server.getLevel(this.dimension);

            if (level != null) {
                level.getChunkSource().removeRegionTicket(ANCHOR, this.pos, DISTANCE, this.pos);
            }
        }
    }
}
