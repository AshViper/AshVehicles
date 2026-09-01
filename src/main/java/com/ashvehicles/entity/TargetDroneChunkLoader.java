package com.ashvehicles.entity;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;

import com.ashvehicles.AshVehicles;

import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;

/**
 * 全員が離れた後も標的ドローンを回し続ける。
 *
 * <p>これが無いとドローンは輪の途中で凍る。{@code isAlwaysTicking} はエンティティを世界に残し、問い合わせ
 * にも追跡にも応じさせるが、tick そのものは通さない——{@code ServerLevel} は乗り物に乗っていない全エンティ
 * ティを {@code inEntityTickingRange} で門前払いし、そこに常時 tick の例外は無い。弾が chunk の外でも飛び
 * 続けるのは、{@link WeaponTicker} がワールド tick の後に自分で tick を渡しているからで（弾は地面を1
 * chunk も開かない。当たり判定を捨てて生成を捨てた側だ）、そのどちらも持たないドローンはシミュレーション
 * 距離の縁——半径150の輪なら中心に立っていても届く距離——で tick を止め、空中で静止する。クライアント
 * だけが円を描き続け、5tickごとの位置パケットが凍った真実へ引き戻す。あの震えがこれだ。
 *
 * <p>作りは {@link AircraftChunkLoader} の縮小版。エンティティ所有のチケットの短い回廊を輪に沿って手渡して
 * いく。違いは2つ。確保は非tick——ドローンはブロックに一切触れないので、欲しいのはエンティティが tick する
 * ことだけで、誰もいない野原のランダムtickではない。そして先読みは接線ではなく<em>弧</em>に沿う。輪は既知
 * なのだから、5秒先の機体は接線の延長ではなく円周の先にいる。
 *
 * <p><b>チケットが再起動を生き延びるのも {@link AircraftChunkLoader} と同じ理由で意図的。</b> 検証コール
 * バックを持たないので保存されたチケットはそのまま戻り、chunk を戻し、chunk がドローンを戻す。ドローンは
 * 保持している集合を自分の NBT に保存しており、戻った最初の tick に突き合わせて要らない物を手放す。撃墜・
 * 回収は {@code TargetDroneEntity.remove} が全部を解放するので、チケットが的より長生きすることは無い。
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class TargetDroneChunkLoader {
    /** 確保が輪のどこまで先へ届くか（飛行tick数）と、1機が保持する chunk 数の上限。 */
    private static final double LEAD_TICKS = 20.0;
    private static final int MOST_CHUNKS = 4;

    /** 経路をサンプリングする間隔（ブロック）。半 chunk なので弧上の chunk を飛ばさない。 */
    private static final double SAMPLE = 8.0;

    /**
     * 先読みが確保よりどれだけ先まで届くか（飛行tick数と chunk 数）。5秒・130ブロックの弧。初回の1周で
     * 輪の下の地形が生成し終わり、以後の確保は全て「数値が1増える」だけになる。
     */
    private static final double PREFETCH_TICKS = 100.0;
    private static final int PREFETCH_CHUNKS = 24;

    /** 先読みを向け直す間隔（tick）と、そのチケットの寿命（tick）。理屈は機体側と同じ。 */
    private static final int PREFETCH_EVERY = 8;
    private static final int PREFETCH_TIMEOUT = 300;

    /** 先読みチケット。完全生成・非tick・自動失効・非保存。{@link AircraftChunkLoader} の物と同じ性質。 */
    private static final TicketType<ChunkPos> PREFETCH = TicketType.create(
            AshVehicles.MODID + ":target_drone_prefetch",
            Comparator.comparingLong(ChunkPos::toLong), PREFETCH_TIMEOUT);

    /** {@link #update} が作業に使う集合。使い回すが、埋めた呼び出しを越えて保持することは無い。 */
    private static final Set<ChunkPos> SCRATCH = new LinkedHashSet<>();

    /**
     * 検証コールバックを意図的に持たない。保存されたチケットこそが、停止時に誰もいない空を回っていた
     * ドローンを再ロードする唯一の手段だから。{@link AircraftChunkLoader} が同じ穴に落ちて学んだ話。
     */
    private static final TicketController CONTROLLER = new TicketController(
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "target_drone"));

    @SubscribeEvent
    public static void onRegisterTicketControllers(RegisterTicketControllersEvent event) {
        event.register(CONTROLLER);
    }

    /**
     * ドローンの確保を、これから飛ぶ弧の下へ移す。毎tick呼んで安い。chunk 集合が変わった時しか何もしない
     * ので、2.6ブロック/tickでは数tickに1度の帳簿処理になる。
     *
     * <p>呼ぶのはドローン自身の tick からだけ。チケットの取得はその場で chunk をロードしうるので、chunk
     * システムのコールバックから呼べば再入で落ちる。そちらからは {@link #release} を使うこと。
     *
     * @param held ドローンが現在保持している chunk
     * @return この呼び出し後に保持している chunk。次の tick で渡し返す
     */
    public static Set<ChunkPos> update(TargetDroneEntity drone, Set<ChunkPos> held) {
        if (!(drone.level() instanceof ServerLevel level)) {
            return held;
        }

        boolean wants = !drone.isRemoved();

        // 回廊より前に。この tick が知り得た最も早い時点で、弧の先の地面を生成器へ伝えるため。
        if (wants) {
            prefetch(drone, level);
        }

        Set<ChunkPos> scratch = SCRATCH;
        scratch.clear();

        if (wants) {
            ahead(drone, scratch);

            // まだ生成されていない地面はこの tick では取らない。取れば tick スレッド上の地形生成になる
            // し、先読みが既に別スレッドで作っている。代償を問わない例外は機体側と同じ2つ——今いる chunk
            // と、次の1歩が届く chunk。この2つを失った瞬間にドローンの tick が止まり、tick が止まった物は
            // 二度と確保を要求できない。
            Vec3 step = drone.position().add(drone.getDeltaMovement());
            ChunkPos own = drone.chunkPosition();
            ChunkPos next = new ChunkPos(SectionPos.blockToSectionCoord(Mth.floor(step.x)),
                    SectionPos.blockToSectionCoord(Mth.floor(step.z)));

            scratch.removeIf(pos -> !pos.equals(own) && !pos.equals(next) && !held.contains(pos)
                    && !level.getChunkSource().hasChunk(pos.x, pos.z));
        }

        if (scratch.equals(held)) {
            scratch.clear();

            return held;
        }

        Set<ChunkPos> wanted = scratch.isEmpty() ? Set.of() : Set.copyOf(scratch);
        scratch.clear();

        for (ChunkPos pos : held) {
            if (!wanted.contains(pos)) {
                CONTROLLER.forceChunk(level, drone, pos.x, pos.z, false, false);
            }
        }

        for (ChunkPos pos : wanted) {
            if (!held.contains(pos)) {
                CONTROLLER.forceChunk(level, drone, pos.x, pos.z, true, false);
            }
        }

        return wanted;
    }

    /** ドローンが今いる地面と、弧に沿ってこれから来る地面。 */
    private static void ahead(TargetDroneEntity drone, Set<ChunkPos> chunks) {
        chunks.add(drone.chunkPosition());

        double speed = drone.getDeltaMovement().length();

        if (speed < 1.0E-3) {
            return;
        }

        double stepTicks = SAMPLE / speed;

        for (int i = 1; i * stepTicks <= LEAD_TICKS && chunks.size() < MOST_CHUNKS; i++) {
            Vec3 at = drone.alongOrbit(i * stepTicks);

            chunks.add(new ChunkPos(SectionPos.blockToSectionCoord(Mth.floor(at.x)),
                    SectionPos.blockToSectionCoord(Mth.floor(at.z))));
        }
    }

    /**
     * 弧の数秒先の地面を、静かにバックグラウンドで生成器へ要求する。確保がそこを保持したくなる頃には
     * 生成が済んでいるように。輪は固定なので、これは事実上「最初の1周だけの支払い」になる。
     */
    private static void prefetch(TargetDroneEntity drone, ServerLevel level) {
        if ((level.getGameTime() + drone.getId()) % PREFETCH_EVERY != 0) {
            return;
        }

        double speed = drone.getDeltaMovement().length();

        if (speed < 1.0E-3) {
            return;
        }

        double stepTicks = SAMPLE / speed;
        double samples = Math.min(PREFETCH_TICKS / stepTicks, PREFETCH_CHUNKS * 16.0 / SAMPLE);
        ChunkPos last = null;

        for (int i = 1; i <= samples; i++) {
            Vec3 at = drone.alongOrbit(i * stepTicks);
            ChunkPos pos = new ChunkPos(SectionPos.blockToSectionCoord(Mth.floor(at.x)),
                    SectionPos.blockToSectionCoord(Mth.floor(at.z)));

            // 距離0。指定 chunk を最後まで生成し、周囲は昇格させず、tick もさせない。
            if (!pos.equals(last)) {
                level.getChunkSource().addRegionTicket(PREFETCH, pos, 0, pos);
                last = pos;
            }
        }
    }

    /**
     * ドローンが保持している chunk を全部手放し、新たに要求しない。チケットを落とすのはレベル変更を予約
     * するだけで何もロードしないので、サーバースレッドのどこからでも——chunk システムのコールバックの中
     * からでも——安全に呼べる。
     *
     * @return 空集合。呼び出し後に保持している物
     */
    public static Set<ChunkPos> release(TargetDroneEntity drone, Set<ChunkPos> held) {
        if (!held.isEmpty() && drone.level() instanceof ServerLevel level) {
            for (ChunkPos pos : held) {
                CONTROLLER.forceChunk(level, drone, pos.x, pos.z, false, false);
            }
        }

        return Set.of();
    }

    private TargetDroneChunkLoader() {
    }
}
