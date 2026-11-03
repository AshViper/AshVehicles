package com.ashvehicles.weapon;

import java.util.Comparator;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.VehicleProjectile;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * 爆弾が落ちる先の地面をロードさせる。<b>「兵装はチャンクをロードしない」の例外は爆弾だけ</b>
 * （[[weapons-never-load-chunks]]）。弾と誘導弾は今もロードしない。
 *
 * <p>通常の爆弾（{@link #ahead}）は、落ちていく先——速度に沿って {@link #LEAD} tick ぶん——の地面を、爆風の半径に
 * 1チャンク足した広さで頼む。ロードされていない地面に落ちた爆弾は何にも当たらずに地面を抜けるからだ。
 *
 * <p>核（{@link #hold}）は届く範囲を、誰も見ていなくてもロードしておく。<b>「兵装はチャンクをロードしない」の唯一の例外</b>
 * （[[weapons-never-load-chunks]]）。
 *
 * <p>波が打つのは起爆の瞬間に {@code getEntities} で集めた者だけで、それはロード済みのチャンクにいる者だけだ。
 * 到達距離は600ブロック、描画距離の外にいる機体は存在しないのと同じなので、遠くで起爆すると波は誰にも届かない。
 * だから<b>落ちている間に</b>周りをロードさせ、着く頃にはそこの機体がワールドに出ている状態にする。
 *
 * <p>要求は {@link com.ashvehicles.ai.BotChunkLoader} と同じ、自動失効・非保存の region チケット。生成もロードも
 * 生成器のスレッドで進み、tick スレッドの上で地形を建てることはない（{@code forceChunk} は使わない）。置き直す者が
 * いなくなれば——爆弾が消え、波が終われば——数秒で勝手に外れる。
 *
 * <p>水準は FULL（33）までしか要らない。エンティティは FULL のチャンクで {@code getEntities} に見え、波の打撃は
 * tick ではなく {@code hurt} の直呼びなので、tick させる必要は無い。1枚で半径42チャンクを頼むとチケットの水準が
 * 負になるので、半径 {@link #TILE} の小さなチケットを格子に敷く。
 */
public final class BombChunkLoader {
    /** チケットの寿命（tick）。置き直しの間隔 {@link #EVERY} より十分に長く。 */
    private static final int TIMEOUT = 100;

    /** 置き直す間隔（tick）。 */
    public static final int EVERY = 20;

    /** 格子の1枚の半径（チャンク）。1枚で (2×TILE+1) 四方を FULL にする。 */
    private static final int TILE = 8;

    /** 届く距離に足す余白（ブロック）。{@code NuclearBlast.gather} の箱の余白と、落ちる間の横の流れ。 */
    private static final double MARGIN = 96.0;

    /** 通常の爆弾が先を頼む長さ（tick）。置き直しの間隔の倍。 */
    private static final int LEAD = 40;
    /** その刻み（tick）。 */
    private static final int SAMPLE = 4;

    private static final TicketType<ChunkPos> BOMB = TicketType.create(
            AshVehicles.MODID + ":bomb", Comparator.comparingLong(ChunkPos::toLong), TIMEOUT);

    private static final TicketType<ChunkPos> NUCLEAR = TicketType.create(
            AshVehicles.MODID + ":nuclear", Comparator.comparingLong(ChunkPos::toLong), TIMEOUT);

    private BombChunkLoader() {
    }

    /** {@code at} を中心に、{@code reach} ブロックまでをロードしたままにする。毎 {@link #EVERY} tick 呼ぶこと。 */
    static void hold(ServerLevel level, Vec3 at, double reach) {
        int centreX = SectionPos.blockToSectionCoord(Mth.floor(at.x));
        int centreZ = SectionPos.blockToSectionCoord(Mth.floor(at.z));
        int radius = Mth.ceil((reach + MARGIN) / 16.0);
        int step = TILE * 2 + 1;
        // 格子の枚数。中心の1枚の外側に、radius を覆うだけ並べる。
        int tiles = Mth.ceil((radius - TILE) / (double) step);

        for (int i = -tiles; i <= tiles; i++) {
            for (int j = -tiles; j <= tiles; j++) {
                ChunkPos pos = new ChunkPos(centreX + i * step, centreZ + j * step);

                level.getChunkSource().addRegionTicket(NUCLEAR, pos, TILE, pos);
            }
        }
    }

    /** 通常の爆弾の落ちる先を頼む。弾自身の tick から {@link #EVERY} tick ごとに呼ぶこと。 */
    public static void ahead(VehicleProjectile bomb) {
        if (!(bomb.level() instanceof ServerLevel level) || bomb.isRemoved()) {
            return;
        }

        WeaponDefinition.Projectile shell = bomb.getWeapon().projectile();
        int radius = Mth.ceil(Math.max(shell.blast(), shell.explosion()) / 16.0F) + 1;
        Vec3 velocity = bomb.getDeltaMovement();
        long last = Long.MIN_VALUE;

        // 抗力と重力は無視して直線で先を読む。外れた分は次の置き直しで取り戻す。
        for (int t = 0; t <= LEAD; t += SAMPLE) {
            ChunkPos pos = new ChunkPos(
                    SectionPos.blockToSectionCoord(Mth.floor(bomb.getX() + velocity.x * t)),
                    SectionPos.blockToSectionCoord(Mth.floor(bomb.getZ() + velocity.z * t)));

            if (pos.toLong() != last) {
                level.getChunkSource().addRegionTicket(BOMB, pos, radius, pos);
                last = pos.toLong();
            }
        }
    }
}
