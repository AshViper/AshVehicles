package com.ashvehicles.ai.battlefield;

import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2FloatMap;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * 陣営1つの脅威マップ。8×8 ブロックのマスごとに、2つの層を持つ。
 *
 * <ul>
 * <li><b>危険（danger）</b>——敵がそこを撃てる見込み。見失った敵の分も、記憶の長さに従って薄れながら残る。
 *     <b>「さっき敵がいた場所だから危険」はこの層の話。</b>更新のたびに古い値を減衰させ、新しい値と大きい方を取る
 * <li><b>露出（exposure）</b>——今見えている敵から、そこが見通されているか。見失った敵は塗らない。更新のたびに
 *     作り直す
 * </ul>
 *
 * <p><b>敵の周りを円で塗らない。</b> 敵から見えない谷の底は、射程の中でも危険ではない。だから敵ごとに高さ地図の
 * 上で視界を解く——敵の視点から放射状に線を伸ばし、それまでに通った地形の一番急な仰角より上に見えるマスだけを
 * 「見通されている」とする（地形解析で言う viewshed）。線は放射状に一度引くだけで、1本の上の全マスの見え方が
 * 同時に決まるので、マスごとに射線を引くより桁で安い。
 *
 * <p><b>知らない土地は見通されている側。</b> ロードされていないマスには設定の {@code threat.unknownExposure} を
 * 塗る。分からないことは安全ではない。
 *
 * <p>世界には {@link HeightField} を通してしか触らない。サーバースレッドで更新し、読むのも同じスレッド。
 */
public final class ThreatMap {
    /** マスの一辺（ブロック）。 */
    public static final int CELL = 8;

    /** 見通されているかを問う高さ。そのマスに立った車体の中ほど（ブロック）。 */
    private static final double TARGET_HEIGHT = 2.2;

    /** 地形の陰でも残す危険の割合。陰は完全な安全ではない——榴弾は稜線を越えてくる。 */
    private static final float HIDDEN = 0.15F;

    /** これより小さい危険は忘れる。 */
    private static final float FORGET = 0.02F;

    /** 放射の本数の上下限。上限の半径（128）で線の間隔がマスより細かくなる本数。 */
    private static final int LEAST_AZIMUTHS = 16;
    private static final int MOST_AZIMUTHS = 128;

    /** 近すぎる敵を1つにまとめる距離（ブロック）。同じ丘の上の3両は、同じ視界を3回解く理由が無い。 */
    private static final double CLUSTER = 16.0;

    private final Long2FloatOpenHashMap danger = new Long2FloatOpenHashMap();
    private final Long2FloatOpenHashMap exposure = new Long2FloatOpenHashMap();
    private final Long2FloatOpenHashMap freshDanger = new Long2FloatOpenHashMap();
    private final Long2FloatOpenHashMap freshExposure = new Long2FloatOpenHashMap();
    private final Long2FloatOpenHashMap oneDanger = new Long2FloatOpenHashMap();
    private final Long2FloatOpenHashMap oneExposure = new Long2FloatOpenHashMap();

    private long updated = Long.MIN_VALUE;
    private int revision;

    /** 1マス分の値を受け取る。可視化のため。 */
    @FunctionalInterface
    public interface CellVisitor {
        void visit(int cellX, int cellZ, float danger, float exposure);
    }

    /**
     * 塗り直す。
     *
     * @param sources         敵。強い順に並んでいること（多すぎる分は呼び手が切る）
     * @param memoryTicks     見失った敵を覚えている長さ。これを過ぎた敵は危険の層にも塗らない
     * @param decay           更新1回で残す、古い危険の割合
     * @param unknownExposure ロードされていない土地に塗る見通されの値
     */
    public void update(HeightField field, List<ThreatSource> sources, long now, int memoryTicks, double decay,
            double unknownExposure) {
        this.fade((float) decay);
        this.freshDanger.clear();
        this.freshExposure.clear();

        for (int at = 0; at < sources.size(); at++) {
            ThreatSource source = sources.get(at);
            double memory = source.current() ? 1.0
                    : Math.max(0.0, 1.0 - (double) (now - source.seenTick()) / Math.max(memoryTicks, 1));

            if (memory <= 0.0 || source.capability() <= 0.0 || this.coveredBy(sources, at)) {
                continue;
            }

            this.oneDanger.clear();
            this.oneExposure.clear();
            this.sweep(field, source, memory, unknownExposure);
            fold(this.oneDanger, this.freshDanger);
            fold(this.oneExposure, this.freshExposure);
        }

        ObjectIterator<Long2FloatMap.Entry> fresh = this.freshDanger.long2FloatEntrySet().fastIterator();

        while (fresh.hasNext()) {
            Long2FloatMap.Entry entry = fresh.next();
            long key = entry.getLongKey();

            this.danger.put(key, Math.max(this.danger.get(key), entry.getFloatValue()));
        }

        this.exposure.clear();
        this.exposure.putAll(this.freshExposure);
        this.updated = now;
        this.revision++;
    }

    /** そこの危険（0〜1）。 */
    public float danger(double x, double z) {
        return this.danger.get(ChunkPos.asLong(cellOf(x), cellOf(z)));
    }

    /** そこの露出（0〜1）。 */
    public float exposure(double x, double z) {
        return this.exposure.get(ChunkPos.asLong(cellOf(x), cellOf(z)));
    }

    /** 線分に沿った危険と露出の平均。直線で行った場合の危なさの目安。 */
    public double riskAlong(Vec3 from, Vec3 to, int samples) {
        double total = 0.0;

        for (int at = 1; at <= samples; at++) {
            double along = (double) at / samples;
            double x = Mth.lerp(along, from.x, to.x);
            double z = Mth.lerp(along, from.z, to.z);

            total += Math.min(1.0, this.danger(x, z) + 0.5 * this.exposure(x, z));
        }

        return samples <= 0 ? 0.0 : total / samples;
    }

    public void forEach(CellVisitor visitor) {
        ObjectIterator<Long2FloatMap.Entry> walk = this.danger.long2FloatEntrySet().fastIterator();

        while (walk.hasNext()) {
            Long2FloatMap.Entry entry = walk.next();
            long key = entry.getLongKey();

            visitor.visit(ChunkPos.getX(key), ChunkPos.getZ(key), entry.getFloatValue(), this.exposure.get(key));
        }
    }

    public long updated() {
        return this.updated;
    }

    /** 塗り直した回数。読み手が「変わったか」を安く訊くため。 */
    public int revision() {
        return this.revision;
    }

    public int cells() {
        return this.danger.size();
    }

    public void clear() {
        this.danger.clear();
        this.exposure.clear();
        this.revision++;
    }

    public static int cellOf(double coord) {
        return Mth.floor(coord / CELL);
    }

    private void fade(float decay) {
        ObjectIterator<Long2FloatMap.Entry> walk = this.danger.long2FloatEntrySet().fastIterator();

        while (walk.hasNext()) {
            Long2FloatMap.Entry entry = walk.next();
            float left = entry.getFloatValue() * decay;

            if (left < FORGET) {
                walk.remove();
            } else {
                entry.setValue(left);
            }
        }
    }

    /**
     * その敵が、既に塗った強い敵の近くにいるか。近ければ視界はほぼ同じなので、塗り直さない。
     */
    private boolean coveredBy(List<ThreatSource> sources, int index) {
        ThreatSource source = sources.get(index);

        for (int before = 0; before < index; before++) {
            ThreatSource earlier = sources.get(before);

            if (earlier.capability() >= source.capability() && earlier.current() == source.current()
                    && earlier.sight().distanceToSqr(source.sight()) < CLUSTER * CLUSTER
                    && earlier.reach() >= source.reach()) {
                return true;
            }
        }

        return false;
    }

    /**
     * 敵1つの視界を放射状に解き、その敵だけの危険と露出をマスごとの最大値で書く。
     */
    private void sweep(HeightField field, ThreatSource source, double memory, double unknownExposure) {
        double reach = source.reach();

        if (reach < CELL) {
            return;
        }

        Vec3 eye = source.sight();
        float capability = (float) source.capability();
        int azimuths = Mth.clamp((int) Math.ceil(Math.PI * 2.0 * reach / CELL), LEAST_AZIMUTHS, MOST_AZIMUTHS);
        int samples = (int) Math.ceil(reach / CELL);

        // 敵自身のマス。
        this.deposit(eye.x, eye.z, (float) (capability * memory), source.current() ? capability : 0.0F);

        for (int azimuth = 0; azimuth < azimuths; azimuth++) {
            double angle = Math.PI * 2.0 * azimuth / azimuths;
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);
            double steepest = Double.NEGATIVE_INFINITY;

            for (int sample = 1; sample <= samples; sample++) {
                double run = sample * CELL;
                double x = eye.x + dx * run;
                double z = eye.z + dz * run;
                double top = field.sightLine(x, z);
                float seen;
                float exposed;

                if (Double.isNaN(top)) {
                    // 知らない土地。見通されている側に倒す。そこから先の稜線も分からないので、仰角は据え置く。
                    seen = (float) Math.max(unknownExposure, HIDDEN);
                    exposed = (float) unknownExposure;
                } else {
                    boolean visible = (top + TARGET_HEIGHT - eye.y) / run >= steepest;

                    seen = visible ? 1.0F : HIDDEN;
                    exposed = visible ? 1.0F : 0.0F;
                    steepest = Math.max(steepest, (top - eye.y) / run);
                }

                double near = run / reach;
                float danger = (float) (capability * memory * (1.0 - near * near) * seen);
                float exposure = source.current() ? (float) (capability * (1.0 - 0.5 * near) * exposed) : 0.0F;

                this.deposit(x, z, danger, exposure);
            }
        }
    }

    private void deposit(double x, double z, float danger, float exposure) {
        long key = ChunkPos.asLong(cellOf(x), cellOf(z));

        if (danger > this.oneDanger.get(key)) {
            this.oneDanger.put(key, danger);
        }

        if (exposure > this.oneExposure.get(key)) {
            this.oneExposure.put(key, exposure);
        }
    }

    /** 敵1つの値を全体へ足す。確率の和なので、何両重なっても1を超えない。 */
    private static void fold(Long2FloatOpenHashMap from, Long2FloatOpenHashMap into) {
        ObjectIterator<Long2FloatMap.Entry> walk = from.long2FloatEntrySet().fastIterator();

        while (walk.hasNext()) {
            Long2FloatMap.Entry entry = walk.next();
            long key = entry.getLongKey();
            float before = into.get(key);

            into.put(key, 1.0F - (1.0F - before) * (1.0F - entry.getFloatValue()));
        }
    }
}
