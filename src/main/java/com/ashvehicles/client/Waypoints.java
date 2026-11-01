package com.ashvehicles.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.phys.Vec3;

/**
 * MFD の MAP 頁が持つ航路点。このクライアントだけの状態で、送らず、保存もしない。
 *
 * <p>機体を乗り換えても消さない。航路はパイロットの膝の上の地図で、機体の物ではないからだ。編集は全部 MAP 頁の
 * 縁のボタンで済む：今の位置を足す（MARK）、ポッドの捕捉点を足す（TGP 頁の WPT）、選んでいる点を消す・動かす。
 */
final class Waypoints {
    /** 置ける点の数。MFD の1画面で読み切れる数。 */
    static final int MOST = 9;
    /** N+/S+/E+/W+ で選んでいる点を動かす距離（ブロック）。 */
    static final double NUDGE = 100.0;

    private static final List<Vec3> POINTS = new ArrayList<>();
    private static int selected = -1;

    private Waypoints() {
    }

    static List<Vec3> points() {
        return POINTS;
    }

    /** 選んでいる点の添字。点が無ければ -1。 */
    static int selectedIndex() {
        return selected;
    }

    static Vec3 selected() {
        return selected < 0 ? null : POINTS.get(selected);
    }

    /** 点を足して選ぶ。満杯なら一番古い点を押し出す。 */
    static void add(Vec3 at) {
        if (POINTS.size() >= MOST) {
            POINTS.remove(0);
        }

        POINTS.add(at);
        selected = POINTS.size() - 1;
    }

    static void remove() {
        if (selected < 0) {
            return;
        }

        POINTS.remove(selected);
        selected = Math.min(selected, POINTS.size() - 1);
    }

    static void step(int by) {
        if (!POINTS.isEmpty()) {
            selected = Math.floorMod(selected + by, POINTS.size());
        }
    }

    static void nudge(double east, double south) {
        if (selected >= 0) {
            POINTS.set(selected, POINTS.get(selected).add(east, 0.0, south));
        }
    }
}
