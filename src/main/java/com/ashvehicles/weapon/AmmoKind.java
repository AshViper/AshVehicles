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
 * <p>発射筒はベルトではなく砲弾側の扱い。ロケットは1本ずつ筒へ吊り込む物で、他に数え方も無い。誘導型と
 * 無誘導型は同じアイテムを共有する。これは簡略化であり意図的でもある——分けたいファイルは
 * {@code ammo_item} でそう書き、ここに自分の種類を得ればよい。
 *
 * <p><b>通貨は「発」で、アイテムは財布。</b> 機体の搭載兵装とまったく同じ考え方
 * （{@code WeaponMounts.draw} 参照）。半分だけ使ったベルトは捨てずに半分のベルトとして弾庫へ戻るので、
 * 弾倉を満たすコストは必要な分だけで済む。
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
    /** 筒へ1本ずつ吊り込む。誘導・無誘導を問わず発射筒が撃つ物。 */
    ROCKET("rocket", "launcher_rocket", 1);

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
