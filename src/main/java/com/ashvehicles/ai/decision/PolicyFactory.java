package com.ashvehicles.ai.decision;

import java.util.HashSet;
import java.util.Set;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.ai.learning.AiVersion;

/**
 * 版から方針を作る。
 *
 * <p><b>方針の種類はここで名前から引く。</b> 今あるのは {@link RuleBasedPolicy}（{@code rule_based}）だけ。
 * 学習した方針（{@code reinforcement_learning}）を足すときは、ここに1行と、版ファイルの {@code model} を読む
 * 実装1つを足す。その実装がすることは {@link BattleState#features} を入力にした推論だけで、学習はしない
 * （{@link DecisionPolicy} の約束）。
 *
 * <p>知らない種類の版は、ルールの方針に同じパラメータを渡して動かす。版ファイルの書き間違いで AI が1両も
 * 動かなくなるより、目盛りだけが効いた状態で動く方がましで、何が起きたかはログに1度だけ出る。
 */
public final class PolicyFactory {
    /** 学習した方針の種類の名前。予約だけで、まだ実装は無い。 */
    public static final String REINFORCEMENT_LEARNING = "reinforcement_learning";

    private static final Set<String> WARNED = new HashSet<>();

    private PolicyFactory() {
    }

    public static DecisionPolicy create(AiVersion version) {
        if (RuleBasedPolicy.ID.equals(version.policy())) {
            return new RuleBasedPolicy(version.parameters());
        }

        if (WARNED.add(version.id() + "/" + version.policy())) {
            AshVehicles.LOGGER.warn("[ai] version {} asks for policy '{}', which this build does not have;"
                    + " running it as {} with the same parameters", version.id(), version.policy(),
                    RuleBasedPolicy.ID);
        }

        return new RuleBasedPolicy(version.parameters());
    }
}
