package com.ashvehicles.weapon;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * 弾種。同じ砲から出る、中身の違う弾。
 *
 * <p><b>なぜ兵装ファイルと別なのか。</b> {@code data/ashvehicles/weapon/} の1ファイルは「砲」だ——砲身が
 * 何本あり、毎秒何発出て、どれだけ散り、どんな音がするか。それは弾を替えても変わらない。変わるのは砲口を
 * 出た後の話——貫くのか、破片を撒くのか、どれだけ速く、どれだけ落ちるか——で、それがこのファイルの全部
 * になる。だから 125mm 砲は1つのままで、そこへ入る弾が3種類ある。砲を弾の数だけ複製する必要は無い。
 *
 * <p><b>書くのは {@code projectile} ブロック1つだけ。</b> 兵装ファイルのそれとまったく同じ形で、同じ
 * フィールドが同じ意味を持つ。{@link WeaponDefinition.Projectile} 参照。弾種を書くとは「その砲弾が飛んで
 * 当たるまでに何をするか」を書くことであり、それ以上でも以下でもない。
 *
 * <p><b>ここに無い物。</b> 発射速度も散布も音も無い。それは砲の性質であって弾の性質ではないからだ。
 * 誘導も無い——シーカーは弾頭ではなく弾体の設計であり、対空ミサイルと対戦車ミサイルは同じ発射筒に入る
 * 「別の弾種」ではなく別の兵装だ。弾種で替えられるのは、砲がどこへ何を送り出すかであって、送り出した物が
 * 自分で考えるかどうかではない。
 *
 * <p><b>どの車両が受け付けるかはこのファイルに書かない。</b> 車両ファイルの
 * {@code armament.ammunition} が受け付ける弾種を並べる。同じ 125mm 徹甲弾が T-64 にも T-80 にも入るのは
 * その2両が同じ弾を並べているからで、弾の側が車両を知っているからではない——ちょうど兵装ファイルが
 * 「自分をどの機体が積むか」を知らないのと同じ。
 *
 * <p>{@code data/<namespace>/ammunition/} に置く。1ファイル1弾種で、ファイル名がその ID になり、
 * {@code item} が偽でなければ同じ名前のアイテムが1つ登録される。
 *
 * @param gunClass この弾を撃つ砲の種類。戦車砲・榴弾砲・機関砲・機関銃のどれか。砲の側は兵装ファイルの
 *                 同名の欄で自分の種類を名乗り、種類の合わない弾はその砲へ入らない。{@link GunClass} 参照
 * @param item アイテムを持つか。偽にすると、装填する手段の無い弾種になる——他の弾種を書くための下敷きや、
 *             何かが内部的にだけ撃つ弾のためにある。兵装ファイルの同名フィールドと同じ意味
 * @param roundsPerItem アイテム1個が何発分か。砲弾は1、機関砲のベルトは30といった具合。弾倉が受け取るのは
 *                      1個分丸ごとか何も無いかのどちらかで、それは{@link AmmoKind}が定めていた規則と同じ
 * @param projectile 砲口を出た後の全部。兵装ファイルの {@code projectile} と同じ形
 */
public record AmmunitionDefinition(GunClass gunClass, boolean item, int roundsPerItem,
        WeaponDefinition.Projectile projectile) {

    public static final Codec<AmmunitionDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            // 省略できない。どの砲が撃つ弾なのかを言わない弾種は、置き場所の無い弾だ。
            GunClass.CODEC.fieldOf("gun_class").forGetter(AmmunitionDefinition::gunClass),
            Codec.BOOL.optionalFieldOf("item", true).forGetter(AmmunitionDefinition::item),
            Codec.INT.optionalFieldOf("rounds_per_item", 1).forGetter(AmmunitionDefinition::roundsPerItem),
            WeaponDefinition.Projectile.CODEC.fieldOf("projectile").forGetter(AmmunitionDefinition::projectile)
    ).apply(instance, AmmunitionDefinition::new));

    /**
     * 読めるファイルが1つも無い弾種に使う値。撃ちはするのでゲームは動き続けるが、誰も本物の砲弾とは
     * 思わない物。兵装側の {@link WeaponDefinition#FALLBACK} と同じ考え方。
     */
    public static final AmmunitionDefinition FALLBACK =
            new AmmunitionDefinition(GunClass.TANK_GUN, true, 1, WeaponDefinition.Projectile.DEFAULT);

    /** アイテム1個が満載時に持つ発数。0以下にはならない——1個で0発の弾薬箱は装填を無限ループにする。 */
    public int perItem() {
        return Math.max(this.roundsPerItem, 1);
    }
}
