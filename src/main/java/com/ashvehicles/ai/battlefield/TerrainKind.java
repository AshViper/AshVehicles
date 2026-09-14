package com.ashvehicles.ai.battlefield;

/**
 * 戦術格子の1マスが、車両にとってどんな土地か。
 *
 * <p>1マスに1つだけ付ける、代表の読み。遮蔽の価値や経路のコストはこれとは別に数値で持っている
 * （{@link TacticalCell}）ので、これは可視化と記録と、方針が「開けた場所にいるか」を一言で訊くための物。
 */
public enum TerrainKind {
    /** 平らで開けている。 */
    FLAT,
    /** 登れる坂。 */
    SLOPE,
    /** 深い水。車両は入らない。 */
    WATER,
    /** 屋根のある建物の中か上。 */
    BUILDING,
    /** 立ち上がった壁か崖の縁。 */
    WALL,
    /** 両脇が塞がった狭い通り道。 */
    NARROW,
    /** 周りに遮る物が何も無い。 */
    OPEN,
    /** 周りより高い。 */
    HIGH_GROUND,
    /** ロードされていない。安全ではない。 */
    UNKNOWN
}
