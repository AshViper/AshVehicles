package com.ashvehicles.ai.navigation;

import java.util.ArrayDeque;

import javax.annotation.Nullable;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import net.minecraft.world.phys.Vec3;

/**
 * 1両が走ってきた跡と、その車両が抜け出せなかったマス。{@link Navigator} が持つ。
 *
 * <p><b>跡は「確かに通れた道」。</b> 建物の中へ入り込んだ車両、屋根へ登った車両には、触角にも道の探索にも出口が
 * 見えない——入ってきた口は、入った後では後ろにあるからだ。そこへ入った道は自分が走ってきた道で、それを逆に辿れば
 * 出られる。
 *
 * <p>跡を置くのは乾いた地面を前へ走っている間だけ（{@value #SPACING} ブロックおき、新しい方から {@value #KEEP} 個）。
 * 後退中や戻っている最中に置けば、戻る先に「今いる所」が混ざる。
 *
 * <p><b>避けるマスは塞がない。</b> 道の探索に高い代償を付けるだけで（{@link RoutePlanner.CellCost}）、他に道が無ければ
 * 通る。持ち場がその向こうにしか無いこともある。
 */
public final class Breadcrumbs {
    /** 跡を置く間隔（ブロック）。高さの差も数えるので、階段を登った車両は登った分だけ跡が増える。 */
    private static final double SPACING = 6.0;

    /** 覚えておく跡の数。 */
    private static final int KEEP = 32;

    /** 古い方が先、新しい方が後ろ。 */
    private final ArrayDeque<Vec3> trail = new ArrayDeque<>();

    /** 避けるマスと、避ける期限（{@link Navigator} の tick）。 */
    private final Long2IntOpenHashMap avoided = new Long2IntOpenHashMap();

    /** 今いる所を跡に置く。前の跡から離れていなければ何もしない。 */
    public void drop(Vec3 here) {
        Vec3 last = this.trail.peekLast();

        if (last != null && last.distanceToSqr(here) < SPACING * SPACING) {
            return;
        }

        this.trail.addLast(here);

        if (this.trail.size() > KEEP) {
            this.trail.removeFirst();
        }
    }

    /** 一番新しい跡。最後に乾いた地面を走っていた所。 */
    @Nullable
    public Vec3 newest() {
        return this.trail.peekLast();
    }

    /** 跡を逆に辿るときの次の点。もう着いた点（{@code reached} ブロック以内）は捨てていく。尽きたら null。 */
    @Nullable
    public Vec3 retreat(Vec3 here, double reached) {
        while (!this.trail.isEmpty()) {
            Vec3 last = this.trail.peekLast();
            double dx = last.x - here.x;
            double dz = last.z - here.z;

            if (dx * dx + dz * dz >= reached * reached) {
                return last;
            }

            this.trail.removeLast();
        }

        return null;
    }

    /** そのマスと周りの {@code radius} マスを、{@code until} まで避ける。 */
    public void avoid(int cellX, int cellZ, int radius, int until) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                long key = RoutePlanner.cellKey(cellX + dx, cellZ + dz);

                this.avoided.put(key, Math.max(this.avoided.get(key), until));
            }
        }
    }

    /** そのマスを今避けているか。期限の切れたマスはここで捨てる。 */
    public boolean avoided(int cellX, int cellZ, int now) {
        if (this.avoided.isEmpty()) {
            return false;
        }

        long key = RoutePlanner.cellKey(cellX, cellZ);
        int until = this.avoided.get(key);

        if (until == 0) {
            return false;
        }

        if (until < now) {
            this.avoided.remove(key);

            return false;
        }

        return true;
    }
}
