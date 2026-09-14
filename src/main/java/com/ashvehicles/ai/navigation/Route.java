package com.ashvehicles.ai.navigation;

import java.util.List;

import net.minecraft.world.phys.Vec3;

/**
 * 引いた道1本。
 *
 * @param points     通過点。近い順で、最後が目的地（か、そこへ一番近付けたマス）。高さは持たない
 * @param cost       道の代償の合計。比べるためだけの値
 * @param risk       道の上の危険の平均（0〜1）。脅威マップの危険と、その半分の重みの露出
 * @param complete   目的地のマスまで届いた
 * @param expansions 探索で展開したマスの数
 * @param riskWeight この道を引いたときの危険の嫌い方
 * @param wetLegs    その通過点へ向かう区間が、車体が沈む深さの水を渡るか（{@code points} と同じ並び）
 */
public record Route(List<Vec3> points, double cost, double risk, boolean complete, int expansions,
        double riskWeight, boolean[] wetLegs) {
    /** 道が引けなかった。呼び手は目的地へ直進し、触角が避ける。 */
    public static Route none(double riskWeight) {
        return new Route(List.of(), 0.0, 0.0, false, 0, riskWeight, new boolean[0]);
    }

    /** どこかで深い水を渡るか。 */
    public boolean wet() {
        for (boolean leg : this.wetLegs) {
            if (leg) {
                return true;
            }
        }

        return false;
    }

    /**
     * その通過点へ向かう区間が深い水を渡るか。<b>触角が深い水を壁と呼ばないのはこの区間の上だけ</b>——道のどこかで
     * 水を渡ると決めたからといって、岸沿いの乾いた区間で水へ入ってよいことにはならない。
     */
    public boolean wetLeg(int index) {
        return index >= 0 && index < this.wetLegs.length && this.wetLegs[index];
    }
}
