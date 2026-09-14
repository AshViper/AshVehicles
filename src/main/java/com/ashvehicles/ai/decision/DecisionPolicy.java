package com.ashvehicles.ai.decision;

import java.util.Map;

/**
 * 戦場の状態から行動を1つ選ぶ物。
 *
 * <p><b>差し替えの口。</b> 今の実装はルールで書いた {@link RuleBasedPolicy} だけで、将来の学習した方針
 * （{@code ReinforcementLearningPolicy}）も同じ形で差す——{@link BattleState} を受け取り、10択の
 * {@link TacticalAction} を1つ返す。行き先・目標・経路・射撃はこの外で決まるので、学習した方針が物理や当たり判定や
 * 弾道を学んでしまうことは構造上起きない。
 *
 * <p><b>ゲームの中では推論だけをする。</b> 学習はゲームの外で、記録（{@code log/}）を材料に行う
 * （{@code tool/ai/}）。方針の実装がサーバーの tick の中で重みを更新してはならない。
 *
 * <p>1両に1つ作る（{@code learning/PolicyFactory}）。直前の点数を持つので、共有しない。
 */
public interface DecisionPolicy {
    /** 次の行動。 */
    TacticalAction decide(BattleState state);

    /** 記録に書く方針の種類。 */
    String id();

    /** 直前の判断で各行動に付けた点数。記録と可視化のため。点数を持たない方針は空。 */
    default Map<TacticalAction, Double> lastUtilities() {
        return Map.of();
    }
}
