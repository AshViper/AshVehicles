package com.ashvehicles.weapon;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

/**
 * 火砲が何から給弾されるか。どの弾薬アイテムが入り、その1個が何発分か。
 *
 * <p>戦車の主砲は手で1発ずつ装填するので、砲弾1個＝1発。機関砲はベルト給弾で、パーンツィリに1400発を
 * 1発ずつ手渡す者はいない。よってアイテム1個は砲弾1発かベルト1本で、ベルトは {@link #AUTOCANNON} の
 * 定める発数分の価値を持つ。
 *
 * <p>発射筒はベルトではなく砲弾側の扱い。ロケットは1本ずつ筒へ吊り込む物で、他に数え方も無い。誘導型は
 * 無誘導型と同じアイテムを共有<em>しない</em>——ロケット弾の箱から対空ミサイルが出てくる補給は嘘なので、
 * 誘導ミサイルはシーカーの見る物で分かれる：空を見る物（熱・レーダー）は対空、地を見る物（レーザー・
 * 視線）は対地、座標へ飛ぶ物はどちらでもないただのミサイル。覆したいファイルは {@code ammo_item} で
 * そう書けばよい。
 *
 * <p><b>通貨は「発」で、アイテムは財布。</b> 機体の搭載兵装とまったく同じ考え方
 * （{@code WeaponMounts.reload} 参照）。ただしこちらのアイテムは残量を覚えない——弾倉が受け取るのは
 * アイテム1個分丸ごとか、何も無いかのどちらかで、1個分に満たない空きしか無い弾倉はもう満載扱い。
 *
 * <p>どの種類を取るかは兵装ファイルの {@code ammo_item} が言う。書かれていなければ発射方式から判定し、
 * それで MOD 内の全兵装が正しくなる。どのファイルにも1行足さずに。{@link WeaponDefinition#ammoKind()}
 * 参照。
 */
public enum AmmoKind implements StringRepresentable {
    /** 手で1発ずつ装填する。戦車砲や、BMD の低初速砲など。 */
    CANNON("cannon", "cannon_shell", 1),
    /** ベルト給弾。砲塔上でも翼下でも、機関砲はこれ。 */
    AUTOCANNON("autocannon", "autocannon_belt", 30),
    /** 筒へ1本ずつ吊り込む。発射筒が撃つ無誘導の物。 */
    ROCKET("rocket", "launcher_rocket", 1),
    /** 空の物を追うミサイル。熱源かレーダー反射をシーカーが見ている物。 */
    ANTI_AIR_MISSILE("anti_air_missile", "anti_air_missile", 1),
    /** 地の物を狙うミサイル。レーザーの光点か射手の照準線をシーカーが見ている物。 */
    ANTI_GROUND_MISSILE("anti_ground_missile", "anti_ground_missile", 1),
    /** どちらでもないミサイル。座標へ飛ぶ弾道弾がこれで、明示指定の受け皿でもある。 */
    MISSILE("missile", "missile", 1);

    public static final Codec<AmmoKind> CODEC = StringRepresentable.fromEnum(AmmoKind::values);

    private final String name;
    private final String itemName;
    private final int roundsPerItem;

    AmmoKind(String name, String itemName, int roundsPerItem) {
        this.name = name;
        this.itemName = itemName;
        this.roundsPerItem = roundsPerItem;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }

    /**
     * アイテム名。給弾先ではなく物そのものの名前になっている。一方は砲弾、他方はベルトで、ベルトを
     * 「1発」と呼べば30発分嘘をつくことになる。
     */
    public String itemName() {
        return this.itemName;
    }

    /** この種類のアイテム1個が満載時に持つ発数。 */
    public int roundsPerItem() {
        return this.roundsPerItem;
    }
}
