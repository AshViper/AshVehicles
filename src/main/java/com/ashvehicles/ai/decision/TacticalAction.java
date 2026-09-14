package com.ashvehicles.ai.decision;

import java.util.Locale;

import javax.annotation.Nullable;

/**
 * AI が選ぶ行動。{@link DecisionPolicy} の答えはこの1つだけ。
 *
 * <p><b>行動は「何をするか」であって「どこへ行くか」ではない。</b> 行き先・狙う相手・通る道は、行動を受け取った
 * 下の層が決める（{@code tactics/Tactics}・{@code combat/TargetSelector}・{@code navigation/RoutePlanner}）。
 * 方針の答えを離散の10択に絞っておくのは、ルールで書いた方針と学習した方針を同じ口へ差し替えるためだ——
 * 学習させるのは「いつ側面へ回るか」であって、「側面とはどこか」の幾何ではない。
 *
 * <p>宣言順は優先順ではない。優先はその場の点数で決まる（{@code RuleBasedPolicy}）。
 */
public enum TacticalAction {
    /** 敵か中立の拠点を取りに行き、円の中に居座る。 */
    CAPTURE_OBJECTIVE,
    /** 自陣が握っていたのに奪われた拠点を取り返す。 */
    RECAPTURE_OBJECTIVE,
    /** 自陣の拠点に留まり、撃てる位置から守る。 */
    DEFEND_OBJECTIVE,

    /** 前線へ出る。危険の縁で止まる。 */
    ADVANCE,
    /** 目標を撃つ。撃ち合う距離まで詰めて、止まって撃つ。 */
    ATTACK,
    /** 目標の正面を避けて、側面か後ろへ回る。 */
    FLANK,
    /** 脅威から身を隠せる位置へ入る。撃てるならそこから撃つ。 */
    SEEK_COVER,
    /** 安全な場所——味方・自陣の拠点・遮蔽——へ下がる。撃ちながら。 */
    RETREAT,

    /** 見失った敵を探しに出る。 */
    SEARCH_ENEMY,
    /** 撃たれている味方のところへ行き、撃っている相手を撃つ。 */
    SUPPORT_ALLY;

    public static final TacticalAction[] VALUES = values();

    /** 記録に書く名前。 */
    public String id() {
        return this.name().toLowerCase(Locale.ROOT);
    }

    /** 拠点を仕事にしている行動か。 */
    public boolean isObjective() {
        return this == CAPTURE_OBJECTIVE || this == RECAPTURE_OBJECTIVE || this == DEFEND_OBJECTIVE;
    }

    /** 撃ち合いから身を引く行動か。この間は足を止めて撃たない。 */
    public boolean isWithdrawal() {
        return this == SEEK_COVER || this == RETREAT;
    }

    @Nullable
    public static TacticalAction byName(String name) {
        for (TacticalAction action : VALUES) {
            if (action.name().equalsIgnoreCase(name.trim())) {
                return action;
            }
        }

        return null;
    }
}
