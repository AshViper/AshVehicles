package com.ashvehicles.ai.perception;

import javax.annotation.Nullable;

import com.ashvehicles.ai.AiConfig;
import com.ashvehicles.ai.core.AiBudget;
import com.ashvehicles.entity.VehicleEntityBase;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * ブロックを歩く、本物の射線。建物と地形が視線を遮るかを {@code Level.clip} で問う。
 *
 * <p><b>答えは3つある。</b> 通る、遮られる、そして<b>分からない</b>。線の下に1つでもロードされていない chunk が
 * あれば、生成させてまで確かめない——{@code Level.getBlockState} は無い chunk をその場で作るので、遠くの相手への
 * 視線1本が tick スレッド上のワールド生成になる（[[explosions-generate-chunks]] と同じ穴。元は
 * {@code ai/BotTargets.sees} の規則）。分からないことを「見えない＝安全」と読んではいけない
 * （{@link ThreatModel} は危険の側に倒す）。
 *
 * <p><b>2者の間の射線は1本を両方向で使う。</b> 視線の起点は双方とも「視点」（{@link #sightOf}）で、ブロックの
 * 衝突形状に対する線分の判定は向きに依らない。だから A から B を見る線と、B から A に見られる線は同じ答えになり、
 * {@link #between} は組ごとに1本だけ引いて両者で分け合う。<em>見る側と見られる側で違うのは線ではなく距離の
 * 感覚</em>——視界と射程——で、それは {@link Perception} が掛ける。
 *
 * <p><b>引く本数には上限がある</b>（{@link AiBudget.Kind#RAY}）。溢れたら古い答えを使い、それも無ければ
 * 分からないと答える。
 */
public final class LineOfSight {
    /** 射線の答え。 */
    public enum Result {
        CLEAR,
        BLOCKED,
        UNKNOWN;

        public boolean clear() {
            return this == CLEAR;
        }
    }

    /** 線の下の chunk を確かめる間隔（ブロック）。半 chunk なので途中の chunk を飛ばさない。 */
    private static final double SAMPLE = 8.0;

    /** 目標点からこの距離の内側で当たったなら、届いたと見なす（ブロック）。相手が立っている地面の角を掠る線のため。 */
    private static final double REACHED = 2.0;

    /** 予算切れのとき、この齢までの古い答えなら使ってよい（tick）。 */
    private static final int STALE = 60;

    /** 使われない答えを掃く間隔（tick）。 */
    private static final int PRUNE_EVERY = 200;

    /** 組1つの答え。位置は2ブロック単位に丸めて覚え、どちらかがそれ以上動いたら引き直す。 */
    private static final class Entry {
        Result result = Result.UNKNOWN;
        int tick;
        long lowAt;
        long highAt;
    }

    private static final Long2ObjectOpenHashMap<Entry> CACHE = new Long2ObjectOpenHashMap<>();
    private static int prunedAt = Integer.MIN_VALUE;

    private LineOfSight() {
    }

    /**
     * from から to まで、ブロックに遮られずに見通せるか。予算も覚えも使わない、素の1本。
     *
     * @param ignore 衝突形状を問う主体。足場やパウダースノーの見え方が変わるだけで、null でよい
     */
    public static Result trace(Level level, Vec3 from, Vec3 to, @Nullable Entity ignore) {
        if (!loaded(level, from, to)) {
            return Result.UNKNOWN;
        }

        HitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                ignore == null ? CollisionContext.empty() : CollisionContext.of(ignore)));

        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(to) < REACHED * REACHED
                ? Result.CLEAR : Result.BLOCKED;
    }

    /**
     * 2者の視点の間の射線。組ごとに覚え、1 tick の予算の内で引く。
     */
    public static Result between(Entity first, Entity second) {
        MinecraftServer server = first.getServer();

        if (server == null || first.level() != second.level()) {
            return Result.UNKNOWN;
        }

        int now = server.getTickCount();

        prune(now);

        Entity low = first.getId() < second.getId() ? first : second;
        Entity high = low == first ? second : first;
        long key = ((long) low.getId() << 32) | (high.getId() & 0xFFFFFFFFL);
        long lowAt = quantize(low);
        long highAt = quantize(high);
        Entry entry = CACHE.get(key);

        if (entry != null && now - entry.tick <= AiConfig.sight().losCacheTicks() && entry.lowAt == lowAt
                && entry.highAt == highAt) {
            return entry.result;
        }

        if (!AiBudget.spend(server, AiBudget.Kind.RAY)) {
            return entry != null && now - entry.tick <= STALE ? entry.result : Result.UNKNOWN;
        }

        Result result = trace(first.level(), sightOf(low), sightOf(high), low);

        if (entry == null) {
            entry = new Entry();
            CACHE.put(key, entry);
        }

        entry.result = result;
        entry.tick = now;
        entry.lowAt = lowAt;
        entry.highAt = highAt;

        return result;
    }

    /**
     * その相手の視点。車両は砲塔の高さ（地面に沈んだ車体の中心ではない）、生き物は目、それ以外は箱の中心。
     */
    public static Vec3 sightOf(Entity entity) {
        if (entity instanceof VehicleEntityBase) {
            return entity.position().add(0.0, entity.getBbHeight() * 0.75, 0.0);
        }

        if (entity instanceof LivingEntity) {
            return entity.getEyePosition();
        }

        return entity.getBoundingBox().getCenter();
    }

    /** サーバーが止まったとき。 */
    public static void clear() {
        CACHE.clear();
        prunedAt = Integer.MIN_VALUE;
    }

    private static long quantize(Entity entity) {
        return BlockPos.asLong(Mth.floor(entity.getX() * 0.5), Mth.floor(entity.getY() * 0.5),
                Mth.floor(entity.getZ() * 0.5));
    }

    private static void prune(int now) {
        // 一度も掃いていない印（MIN_VALUE）との差は桁あふれして負になる。印は差を取る前に見る。
        if (prunedAt != Integer.MIN_VALUE && now - prunedAt < PRUNE_EVERY) {
            return;
        }

        prunedAt = now;

        ObjectIterator<Long2ObjectOpenHashMap.Entry<Entry>> walk = CACHE.long2ObjectEntrySet().fastIterator();

        while (walk.hasNext()) {
            if (now - walk.next().getValue().tick > STALE) {
                walk.remove();
            }
        }
    }

    /** その線が通る chunk が全部、待たずに読める形で在るか。 */
    private static boolean loaded(Level level, Vec3 from, Vec3 to) {
        Vec3 step = to.subtract(from);
        double flat = Math.sqrt(step.x * step.x + step.z * step.z);
        int samples = (int) Math.ceil(flat / SAMPLE);

        for (int at = 0; at <= samples; at++) {
            double along = samples == 0 ? 0.0 : (double) at / samples;
            double x = from.x + step.x * along;
            double z = from.z + step.z * along;

            if (level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(Mth.floor(x)),
                    SectionPos.blockToSectionCoord(Mth.floor(z))) == null) {
                return false;
            }
        }

        return true;
    }
}
