package com.ashvehicles.client.ghost;

/**
 * ある距離でエンティティをどこまで描くか。
 *
 * <pre>
 *   FULL        0 .. ghostStartDistance        ゲーム自身のエンティティレンダラー。MOD 側は何もしない
 *   GHOST       .. ghostEndDistance            MOD のパス: モデルを、エンティティの動きに合わせて描く
 *   BILLBOARD   billboardDistance ..           MOD のパス: 平坦なアイコン（有効時のみ）
 *   HIDDEN      ghostEndDistance ..            何も描かない
 * </pre>
 *
 * <p>描画階層が3つではなく1つなのは、近いゴーストに与えられて遠いゴーストが省ける物が無いからだ。モデルはどの
 * 距離でも同じモデルだし、ポーズ付けとサイクル再生のコストはボーン回転数個分にすぎない。かつての簡略階層は
 * モデルを静止させて描くか、箱数個として Distant Horizons へ渡していた。箱は廃止したし、動いているモデルの隣に
 * ある静止モデルは、機体が数ピクセルの大きさでも気付かれる類の差だ。
 *
 * <p>2乗距離から選ぶので、描画ループが平方根を取ることは無い。
 */
public enum GhostLOD {
    FULL,
    GHOST,
    BILLBOARD,
    HIDDEN;

    /** カメラからこの距離にある物の階層。 */
    public static GhostLOD of(double distanceSq) {
        if (distanceSq < GhostConfig.startSq()) {
            return FULL;
        }

        if (distanceSq >= GhostConfig.endSq()) {
            return HIDDEN;
        }

        if (GhostConfig.billboards() && distanceSq >= GhostConfig.billboardSq()) {
            return BILLBOARD;
        }

        return GHOST;
    }

    /** この階層をゲームではなくゴーストパスが描くか。 */
    public boolean isGhost() {
        return this == GHOST || this == BILLBOARD;
    }
}
