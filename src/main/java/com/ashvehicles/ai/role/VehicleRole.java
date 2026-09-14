package com.ashvehicles.ai.role;

import java.util.Locale;

import javax.annotation.Nullable;

/**
 * 車両が部隊の中で受け持つ役割。
 *
 * <p><b>同じ判断を全車両に当てないための物。</b> 戦車が偵察車の距離感で撃ち合えば正面の撃ち合いを避け続け、
 * 自走砲が戦車の距離感で前へ出れば最初の角で撃破される。役割ごとの違いは {@link TacticalProfile} の重みとして
 * 持ち、判断そのもの（{@code decision/RuleBasedPolicy}）は1つのまま——役割は判断を分岐させるのではなく、
 * 同じ判断の目盛りを変える。
 *
 * <p>どの車両がどの役割かは車両ファイルの事実から推定する（{@link Roles#classify}）。推定が外れる車両は
 * 設定の {@code roleOverrides} で名指しで直す。
 */
public enum VehicleRole {
    /** 偵察。速く、見付けて、陣営へ知らせる。撃ち合いは避ける。 */
    SCOUT,
    /** 戦車。高耐久で正面の撃ち合いを受け持ち、拠点を踏む。 */
    TANK,
    /** 歩兵戦闘車。戦車の脇で支援し、側面へ回る。 */
    IFV,
    /** 装甲兵員輸送車。前線の後ろで拠点を保つ。 */
    APC,
    /** 防空。空の相手を最優先し、味方の塊から離れない。 */
    AA,
    /** 砲兵。後方から遠くを撃つ。前へ出ない。 */
    ARTILLERY,
    /** 支援。味方の後ろを付いて行き、撃たれている味方を助ける。 */
    SUPPORT;

    public static final VehicleRole[] VALUES = values();

    /** 設定と記録に書く名前。 */
    public String id() {
        return this.name().toLowerCase(Locale.ROOT);
    }

    /** 名前から。大文字小文字を問わない。知らない名前なら null。 */
    @Nullable
    public static VehicleRole byName(String name) {
        for (VehicleRole role : VALUES) {
            if (role.name().equalsIgnoreCase(name.trim())) {
                return role;
            }
        }

        return null;
    }
}
