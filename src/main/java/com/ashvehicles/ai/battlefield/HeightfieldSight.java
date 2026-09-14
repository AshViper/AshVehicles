package com.ashvehicles.ai.battlefield;

import net.minecraft.world.phys.Vec3;

/**
 * 高さ地図の上での見通し。<b>見積もり</b>であって、撃つ前の射線ではない。
 *
 * <p><b>ブロックを歩かない射線。</b> 線分に沿って2ブロックおきに、その列の一番上の面（{@link HeightField#sightLine}）
 * が線より上にあるかだけを見る。遮蔽の候補を100点比べ、脅威マップを数千マス塗る仕事は、{@code Level.clip}
 * では払えない——あちらは線が通るブロックを全部1つずつ問う。精密な射線は撃つ直前に
 * {@code perception/LineOfSight} が1本だけ引く。
 *
 * <p><b>誤りの向き。</b> 高さ地図は「一番上の面」しか知らないので、橋の下を通る線も遮られたと答える。視界の
 * 見積もりとしては「見えないはずの所を見えないと言う」側の誤りで、害は小さい。逆に屋根の無い窓や柵の隙間は
 * 塞がっていると答える——ここで遮蔽と判断した点は、決める前に精密な射線で確かめる（{@code tactics/CoverFinder}）。
 *
 * <p><b>知らない土地は {@link Visibility#UNKNOWN}。</b> 線の下に1列でも読めない所があり、それ以外で遮られて
 * いなければ、見通せるとも遮られるとも言わない。どちらに倒すかは呼び手が決める——この AI の呼び手は、脅威を
 * 塗るときは「見られている」、遮蔽を探すときは「隠れていない」と、どちらも危険の側に倒す。
 */
public final class HeightfieldSight {
    /** 標本の間隔（ブロック）。1ブロックの壁は取り逃しうるが、戦車を隠す物は2ブロックより太い。 */
    public static final double STEP = 2.0;

    /** 両端のこの距離の内側は見ない（ブロック）。自分の立っている面と、相手の立っている面は遮蔽ではない。 */
    private static final double END_MARGIN = 1.5;

    /** 面が線をこれだけ超えたら遮られたと見なす（ブロック）。丸めで地面を掠っただけの線を遮らせない。 */
    private static final double CLEARANCE = 0.1;

    /** 見通しの答え。 */
    public enum Visibility {
        CLEAR,
        BLOCKED,
        UNKNOWN
    }

    private HeightfieldSight() {
    }

    /** from から to まで見通せるか。 */
    public static Visibility trace(HeightField field, Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double flat = Math.sqrt(dx * dx + dz * dz);

        if (flat < END_MARGIN * 2.0) {
            return Visibility.CLEAR;
        }

        int steps = (int) Math.ceil(flat / STEP);
        boolean unknown = false;

        for (int step = 1; step < steps; step++) {
            double along = (double) step / steps;
            double run = along * flat;

            if (run < END_MARGIN || flat - run < END_MARGIN) {
                continue;
            }

            double top = field.sightLine(from.x + dx * along, from.z + dz * along);

            if (Double.isNaN(top)) {
                unknown = true;

                continue;
            }

            if (top > from.y + dy * along + CLEARANCE) {
                return Visibility.BLOCKED;
            }
        }

        return unknown ? Visibility.UNKNOWN : Visibility.CLEAR;
    }
}
