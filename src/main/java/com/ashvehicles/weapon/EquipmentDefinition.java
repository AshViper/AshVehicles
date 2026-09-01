package com.ashvehicles.weapon;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.util.StringRepresentable;
import net.minecraft.world.phys.Vec3;

/**
 * 撃つのではなく積むポッド。照準ポッド、ジャマー、デコイ発射機など。
 * {@code data/ashvehicles/equipment/} にファイルを置けば起動時に MOD がアイテムを登録し、どの機体の専用
 * ステーションにも取り付けられるようになる。
 *
 * <p>これらは兵装パイロンではなく専用ステーションに吊る。ポッドはステーションへ直付けで、ラックは無い。
 * ラックは兵装を吊る物であり、ポッドは兵装ではないから。選択されず、撃たれず、減らない。積んでいる限り
 * ずっと仕事をする。
 *
 * <p><b>ポッドがすることは全部が倍率。</b> 5つあり、それぞれ機体が既に持っていた数値1つに掛かる。何も
 * 言わないファイルではそれぞれ1のまま。これは意図的で、ポッドは独自の規則ではなく機体に取り付ける物だ。
 * 「シーカーを良くし排気を冷やす」ファイルは、2つの新機構ではなく2つの数値でそう言えばいい。複数積めば
 * 掛け合わされるので、ジャマー2個は1個より良く、2倍よりはっきり劣る。
 *
 * @param kind ポッドの種別。読むのはツールチップだけで、規則ではなくラベル。ポッドが<em>すること</em>は
 *             下の4つの数値
 * @param item MOD がアイテムを登録すべきか
 * @param seekerRange 選択中の兵装のシーカー探知距離への倍率。照準ポッドの存在理由——同じミサイルを、より
 *                    遠くで
 * @param lockRate ロック完了までの速さへの倍率。待ち時間を半分にするポッドは {@code 2} と書く
 * @param radarGain 自機のレーダー反射断面積への倍率。ジャマーでは1未満で、見つかりにくく捉え続けられ
 *                  にくくなる
 * @param heatGain 赤外線放射に対する同じ物。熱源追尾の弾頭が向かう先
 * @param lockDelay <em>この機体を狙っている</em>レーダーシーカーが、ロックを決めるまでに要する時間への
 *                  倍率。ジャマーでは1を超え、相手のロックが閉じるのを遅らせる。上の3つと違い、これだけ
 *                  は積んでいる機体ではなく撃とうとしている相手に効く——だから熱源追尾には何もしない。
 *                  電波を濁らせるのであって排気を冷やすのではないから。{@code TargetLock} 参照
 * @param camera このポッドのレンズ位置。{@link #lensAt} 参照
 * @param mass ポッドの重さ（kg）。実物の重量をそのまま書く——照準ポッドが200、ジャマーが300あたり。
 *             専用ステーションは兵装パイロンと場所を奪い合わないが、重さは奪い合う。翼下に何を吊ろうと
 *             機体は1つで、持ち上げるのは同じ主翼だからだ
 */
public record EquipmentDefinition(Kind kind, boolean item, float seekerRange, float lockRate,
        float radarGain, float heatGain, float lockDelay, Vec3 camera, float mass) {

    public static final Codec<EquipmentDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Kind.CODEC.optionalFieldOf("type", Kind.TARGETING).forGetter(EquipmentDefinition::kind),
            Codec.BOOL.optionalFieldOf("item", true).forGetter(EquipmentDefinition::item),
            Codec.FLOAT.optionalFieldOf("seeker_range", 1.0F).forGetter(EquipmentDefinition::seekerRange),
            Codec.FLOAT.optionalFieldOf("lock_rate", 1.0F).forGetter(EquipmentDefinition::lockRate),
            Codec.FLOAT.optionalFieldOf("radar_gain", 1.0F).forGetter(EquipmentDefinition::radarGain),
            Codec.FLOAT.optionalFieldOf("heat_gain", 1.0F).forGetter(EquipmentDefinition::heatGain),
            Codec.FLOAT.optionalFieldOf("lock_delay", 1.0F).forGetter(EquipmentDefinition::lockDelay),
            Vec3.CODEC.optionalFieldOf("camera", Vec3.ZERO).forGetter(EquipmentDefinition::camera),
            Codec.FLOAT.optionalFieldOf("mass", 0.0F).forGetter(EquipmentDefinition::mass)
    ).apply(instance, EquipmentDefinition::new));

    /**
     * ゲームが読めるファイルが1つも無いポッドに使う値。吊られているだけで何もしない物にして、ゲームは
     * 動き続け、ステーションが穴を抱えないようにする。
     */
    public static final EquipmentDefinition FALLBACK =
            new EquipmentDefinition(Kind.TARGETING, true, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, Vec3.ZERO, 0.0F);

    /**
     * 吊られているステーションを与えたときの、このポッドのレンズの位置。
     *
     * <p><b>映像はポッドから出るので、ポッドと一緒に動く。</b> {@code camera} はステーションからの
     * オフセットを機体自身の軸で表した物。ポッドが描かれるのと同じ軸なので、モデルからそのまま読める——
     * ボールの前面、胴体の少し下、そのポッドにおける実際の位置。同じポッドを胴体内側のステーションに吊れば
     * 腹の下から外を見ることになり、外側に吊れば翼下から、間にある兵装越しに見ることになる。その違いこそ
     * が、搭載構成を選択たらしめている。
     *
     * <p>ファイルが何も言わないポッドはレンズをステーション位置に置く。そこはパイロンの中で、翼のアップに
     * なる。正直な既定値だが有用ではない。覗くためのポッドは全部レンズ位置を書くべき。
     *
     * @param station ポッドが吊られている位置。機体自身の軸で
     */
    public Vec3 lensAt(Vec3 station) {
        return station.add(this.camera);
    }

    /** ポッドの種別。プレイヤーに何を持っているか伝えるツールチップ用。 */
    public enum Kind implements StringRepresentable {
        /** 見る系。シーカーが良くなり、ロックが早く決まる。 */
        TARGETING("targeting_pod"),
        /** 欺く系。この機体を探している物への反射を小さくする。 */
        JAMMER("jammer"),
        /** 隠す系。放った機体よりシーカーが好む物を出す。 */
        DECOY("decoy");

        public static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);

        private final String name;

        Kind(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }
}
