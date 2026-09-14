package com.ashvehicles.ai.learning;

import com.ashvehicles.ai.AiConfig;

/**
 * 新しい版を採用してよいかの門。
 *
 * <p><b>新しい版が古い版より弱いなら、採用しない。</b> 判断に使うのは挑戦者と採用版の<b>直接の対戦成績</b>
 * （{@link BattleStatistics#versus}）で、戦闘の数が足りていること（{@code versions.promoteMinBattles}）と、
 * 勝率が基準を超えていること（{@code versions.promoteWinRate}、既定 55%）の2つ。数が少ないうちの勝率は運の
 * 振れの方が大きいので、数を先に問う。
 *
 * <p>自己対戦（{@link SelfPlay}）は既定で試合ごとに陣営を入れ替えるので、会場の片側の有利は勝率から消える。
 */
public final class Evaluation {
    /**
     * 判断の結果。
     *
     * @param challenger 挑戦者
     * @param champion   採用版
     * @param battles    直接の対戦の数
     * @param winRate    挑戦者の勝率（引き分けは半分）
     * @param approved   採用してよい
     * @param reason     人に見せる理由
     */
    public record Verdict(String challenger, String champion, int battles, double winRate, boolean approved,
            String reason) {
    }

    private Evaluation() {
    }

    public static Verdict judge(String challenger, String champion) {
        BattleStatistics.Tally head = BattleStatistics.versus(challenger, champion);
        AiConfig.Versions settings = AiConfig.get().versions();
        double rate = head.winRate();

        if (challenger.equals(champion)) {
            return new Verdict(challenger, champion, head.battles, rate, false, "same version");
        }

        if (head.battles < settings.promoteMinBattles()) {
            return new Verdict(challenger, champion, head.battles, rate, false, String.format(
                    "needs %d head-to-head battles, has %d", settings.promoteMinBattles(), head.battles));
        }

        if (rate < settings.promoteWinRate()) {
            return new Verdict(challenger, champion, head.battles, rate, false, String.format(
                    "win rate %.1f%% is under %.1f%%", rate * 100.0, settings.promoteWinRate() * 100.0));
        }

        return new Verdict(challenger, champion, head.battles, rate, true, String.format(
                "win rate %.1f%% over %d battles", rate * 100.0, head.battles));
    }
}
