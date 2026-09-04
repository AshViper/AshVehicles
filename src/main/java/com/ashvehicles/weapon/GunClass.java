package com.ashvehicles.weapon;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

/**
 * 砲の種類。どの弾が入るかを決める唯一の物。
 *
 * <p><b>なぜ口径の数値ではないのか。</b> 「どの弾が入るか」は数直線上の位置ではなく、砲の設計そのものだ。
 * どの1つの数値で並べても、必ずどこかの組を取り違える——MOD 内の砲で実際に確かめた通りに：
 *
 * <ul>
 *   <li>口径で並べると、100mm の 2A70 が 125mm 戦車砲と同じ箱に入る。片方は初速12.5でもう片方は85だ</li>
 *   <li>威力で並べると、155mm 榴弾砲（160）が 120mm 戦車砲（160）と同じ箱に入る。榴弾砲に装弾筒付き
 *       徹甲弾が装填できてしまう</li>
 *   <li>初速で並べると、その 155mm 榴弾砲（47.25）がボフォース 40mm 機関砲（50）と並ぶ。もっと悪い</li>
 * </ul>
 *
 * <p>加えて、威力から導くのは循環でもある。弾種が威力を持つようになった今、砲ファイルの威力は「弾種を
 * 積んでいないときの既定弾の威力」でしかない。そこから受け付ける弾種を決めれば、砲のバランスを調整した
 * 瞬間に、戦車が積んでいた弾が黙って入らなくなる。
 *
 * <p>だから砲は自分が何であるかを一語で名乗る。数値から推測させない。
 *
 * <p><b>名乗らない砲。</b> 兵装ファイルにこれを書かない砲は種類を持たず、車両ファイルが並べた弾種を
 * そのまま全部受け付ける。ミサイルも爆弾もそうだし、弾種を使わない砲は書く必要が無い。
 */
public enum GunClass implements StringRepresentable {
    /**
     * 戦車砲。直射で、初速が高く、装甲を貫くために撃つ砲。
     *
     * <p>装弾筒付き徹甲弾が入る唯一の種類。長い侵徹体を高い初速で送り出せる薬室がここにしか無い。
     */
    TANK_GUN("tank_gun"),
    /**
     * 榴弾砲。低初速で、山なりに撃ち、当てるのではなく面を制圧するために撃つ砲。低圧砲もここ。
     *
     * <p>戦車砲と口径が重なることに意味は無い。155mm 榴弾砲と 120mm 戦車砲は口径でも威力でも隣り合うが、
     * 同じ弾は撃たない。BMD-4 の 100mm 2A70 も、口径ではなく設計でここに入る。
     */
    HOWITZER("howitzer"),
    /** 機関砲。ベルト給弾で、20mm から 40mm あたりの、連射して当てる砲。 */
    AUTOCANNON("autocannon"),
    /** 機関銃。同じくベルト給弾で、装甲ではなく人と軽車両に向ける小銃口径の物。 */
    MACHINE_GUN("machine_gun");

    public static final Codec<GunClass> CODEC = StringRepresentable.fromEnum(GunClass::values);

    private final String name;

    GunClass(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }
}
