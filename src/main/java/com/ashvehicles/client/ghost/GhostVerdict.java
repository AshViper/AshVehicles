package com.ashvehicles.client.ghost;

/**
 * 前フレームでゴーストが描かれた、あるいは描かれなかった理由。
 *
 * <p>デバッグ表示用に保持している。保持する価値はある。「画面に何も出ない」には外から見て区別の付かない原因が
 * 半ダースあり——階層、視錐台、描画予算、遮る地形——スクリーンショットから推測しようとすると午後が消える。
 */
public enum GhostVerdict {
    /** ゲーム自身のエンティティループが描いている。ゴーストパスは手を出さなかった。 */
    GAME,
    /** ゴーストパスが描いた。 */
    DRAWN,
    /** カメラとの間に地形がある。ゲーム自身の地形か Distant Horizons の地形。 */
    OCCLUDED,
    /** 描かれるはずの位置が視錐台の外。 */
    CULLED,
    /** このフレームの {@link GhostConfig#maxGhosts()} を超えた。予算は手前のゴーストが取った。 */
    BUDGET,
    /** {@code ghostEndDistance} の外、またはそれ以外の理由で描画対象の階層に無い。 */
    HIDDEN
}
