package com.ashvehicles.vehicle;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.util.StringRepresentable;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * 機体を構成する箱。自分のファイルの {@code hitbox} ブロックに、それが代わりを務める素の直方体と並べて
 * 書かれる。
 *
 * <p>この箱が、弾の当たる場所であり、世界が衝突する相手であり、機体の上に立つ物が立っている床でもある
 * ——甲板に必要なのはまさにそれ。1つも書かれていない機体は素の当たり判定に戻り、従来通りに振る舞う。
 *
 * <p>以前は {@code data/&lt;pack&gt;/collision/} に独立したファイルとして置いていた。パイロットが見て
 * 分かる性能値の表と、モデルに合わせた形状の記述は、別の時に別の目で編集されるという理屈で。実際には同じ
 * 人が同じ午後に編集するし、2箇所にあると探すファイルが2つ、機体を複製する時にコピーするファイルが2つ、
 * そしてコピーし忘れれば片方が黙って欠ける——それは誰かに見えるエラーではなく「形をまったく持たない
 * 機体」になる。
 */
public record VehicleShape(List<Box> boxes) {
    public static final VehicleShape NONE = new VehicleShape(List.of());

    /**
     * 独立した値としてではなく、所属するブロックへ直接読み込む。ファイルが
     * {@code "hitbox": { "width": …, "boxes": [ … ] }} と書けるように（もう1段ネストさせないため）。
     */
    public static final MapCodec<VehicleShape> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Box.CODEC.listOf().optionalFieldOf("boxes", List.of()).forGetter(VehicleShape::boxes)
    ).apply(instance, VehicleShape::new));

    /**
     * 箱1つ。機体自身の軸で、x が右、y が上、z が機首方向。
     *
     * <p>機体は1つではなく数個の箱で記述すること。15m の機体を1つの箱で囲めばただの小屋になるし、甲板は
     * 船体全体に被せた蓋ではなく甲板であってほしい。
     *
     * @param name この箱が何か。ファイルを読む人向け
     * @param offset 箱の中心。機体の原点からの距離
     * @param size 幅・高さ・長さ
     * @param rotation 機体内でのこの箱自身の回転（度）。x は機首上げ方向、y は右へのヨー、z は右舷を
     *                 下げるロール。後退翼・下反した翼端・傾斜した尾翼は角度の付いた箱になる。省略すれば
     *                 機体に対してまっすぐ
     * @param mount 箱が何に取り付いているか。機体では全部が船体側なので書く必要は無い。戦車には砲塔が
     *              あり、砲塔上の箱は砲塔の旋回角だけリング回りに振られる。砲塔を横に向けているのに箱だけ
     *              前を向いたままの砲身は、ある場所では盾に、別の場所では穴になる。砲身自体はさらに特殊
     *              で、旋回では砲塔と一緒に回るが、加えて仰俯角で砲耳回りに上下する。ただの砲塔上の箱は
     *              そこまではしない
     */
    public record Box(String name, Vec3 offset, Vec3 size, Vec3 rotation, Mount mount) {
        public static final Codec<Box> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("name", "part").forGetter(Box::name),
                Vec3.CODEC.fieldOf("offset").forGetter(Box::offset),
                Vec3.CODEC.fieldOf("size").forGetter(Box::size),
                Vec3.CODEC.optionalFieldOf("rotation", Vec3.ZERO).forGetter(Box::rotation),
                Mount.CODEC.optionalFieldOf("mount", Mount.HULL).forGetter(Box::mount)
        ).apply(instance, Box::new));

        /** 機体内でのこの箱自身の向きを、回転として返す。 */
        public Quaternionf orientation() {
            return Attitude.rotate(new Quaternionf(), (float) this.rotation.z,
                    (float) this.rotation.x, (float) this.rotation.y);
        }
    }

    /**
     * 箱の取り付け先。
     *
     * <p>車体と一体で動くのは船体だけ。それ以外は車両が動かせる場所にあり、その位置は車両がその後どう
     * 動かしたかから毎tick 計算し直す必要がある。
     */
    public enum Mount implements StringRepresentable {
        /** 機体そのものの一部で、位置はファイルの記述通り。機体側は全部これ。 */
        HULL("hull"),
        /** 砲塔に運ばれ、砲塔リング回りに振られる。 */
        TURRET("turret"),
        /**
         * {@link #TURRET} と同じく砲塔に運ばれ、さらに砲の仰俯角だけ砲耳回りに上下する。砲身自体と、
         * 砲塔上面ではなく砲身に付いている物のためのもの。
         */
        GUN("gun");

        public static final Codec<Mount> CODEC = StringRepresentable.fromEnum(Mount::values);

        private final String name;

        Mount(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }
}
