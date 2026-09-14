package com.ashvehicles.ai.objective;

import com.ashvehicles.match.MatchPoint;

/**
 * ある陣営から見た拠点の状態。
 *
 * <p>同じ拠点でも陣営ごとに答えが違う——赤にとっての FRIENDLY は青にとっての ENEMY だ。だから拠点
 * （{@link MatchPoint}）自身には持たせず、見る側の陣営を渡して毎回決める。
 *
 * <p><b>NEUTRAL を足してある。</b> この MOD の拠点は初期配置を持たなければ中立から始まる
 * （{@code MatchPoint.home}）。中立を ENEMY に畳むと「取り返す」と「初めて取る」の区別が消え、奪還を急ぐ
 * 理由を中立の拠点にまで付けてしまう。
 *
 * <p>「奪還が必要」は状態ではなく履歴の話なので、ここには入れない（{@link ObjectiveState#recaptureRequired}）。
 */
public enum CaptureState {
    /** 自陣が握っている。 */
    FRIENDLY,
    /** 敵の陣営が握っている。 */
    ENEMY,
    /** 2つ以上の陣営が円の中にいて、進みが止まっている。 */
    CONTESTED,
    /** 誰の物でもない。 */
    NEUTRAL;

    public static CaptureState of(MatchPoint point, String team) {
        if (point.contested()) {
            return CONTESTED;
        }

        if (point.owner() == null) {
            return NEUTRAL;
        }

        return team.equals(point.owner()) ? FRIENDLY : ENEMY;
    }
}
