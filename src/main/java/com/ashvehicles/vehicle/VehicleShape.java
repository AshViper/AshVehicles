package com.ashvehicles.vehicle;

import java.util.List;
import java.util.Optional;

import com.mojang.datafixers.util.Either;
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
     *              そこまではしない。輸送機の後部ハッチも同じ話だ——開いたランプの上を歩け、閉じたランプが
     *              弾を止めるには、その箱がヒンジ回りに一緒に振れている必要がある
     * @param plate 面ごとの装甲厚（mm）。書かなければ全面 0 で、どの弾も抜ける。{@link Plate} 参照
     */
    public record Box(String name, Vec3 offset, Vec3 size, Vec3 rotation, Mount mount,
            Optional<Vec3> pivot, Optional<Integer> station, Plate plate) {
        public static final Codec<Box> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("name", "part").forGetter(Box::name),
                Vec3.CODEC.fieldOf("offset").forGetter(Box::offset),
                Vec3.CODEC.fieldOf("size").forGetter(Box::size),
                Vec3.CODEC.optionalFieldOf("rotation", Vec3.ZERO).forGetter(Box::rotation),
                Mount.CODEC.optionalFieldOf("mount", Mount.HULL).forGetter(Box::mount),
                Vec3.CODEC.optionalFieldOf("pivot").forGetter(Box::pivot),
                // どの砲塔に載っているか。書かなければ車両自身の砲塔——単砲塔の車両は全部それだ。
                // 番号は車両ファイルの {@code turrets} の添字で、その砲塔が持つ旋回輪と耳軸の周りに
                // 振られる。{@link com.ashvehicles.vehicle.GroundVehicleDefinition.Station} 参照。
                Codec.INT.optionalFieldOf("station").forGetter(Box::station),
                Plate.CODEC.optionalFieldOf("plate", Plate.NONE).forGetter(Box::plate)
        ).apply(instance, Box::new));

        /** この箱が載っている砲塔の番号。車両自身の砲塔なら −1。 */
        public int stationIndex() {
            return this.station.orElse(-1);
        }

        /**
         * この箱が振れるときの軸が通る点。書かれていなければ箱自身の中心——その場で回る。
         *
         * <p>可動部の箱にとってこれは飾りではない。ランプはヒンジから9ブロック先まで伸びており、その場で
         * 回した箱は板のある場所とまったく別の場所へ行く。エディタがボーンの支点から書き出すので、手で
         * 数える必要は無い。
         */
        public Vec3 hinge() {
            return this.pivot.orElse(this.offset);
        }

        /** 機体内でのこの箱自身の向きを、回転として返す。 */
        public Quaternionf orientation() {
            return Attitude.rotate(new Quaternionf(), (float) this.rotation.z,
                    (float) this.rotation.x, (float) this.rotation.y);
        }
    }

    /**
     * 箱の6面それぞれの装甲厚（mm）。弾側の {@code penetration} と比べる。
     * {@link com.ashvehicles.weapon.Penetration} 参照。
     *
     * <p>{@code "plate": 20} と数1つで書けば全面が同じ厚さ、{@code "plate": {"front": 500, "top": 40}} と
     * ブロックで書けば面ごと。ブロックで書かなかった面は 0。
     *
     * <p><b>面は箱自身の向きで数える。</b> 前は箱の前、つまり {@code rotation} と {@code mount} を掛けた後の
     * 箱の +z 側の面。砲塔の箱の前面は砲塔が向いている側で、横へ回した砲塔の前面装甲は横から来た弾を受ける
     * ——実物の砲塔もそうだ。{@code rotation} の y を 90 度振った箱は、前面が車体の右を向いていることになる。
     *
     * <p>書くのは板の厚さそのもので、傾斜で稼いだ見かけの厚さではない。角度は跳弾（{@code Ricochet}）が
     * 既に幾何から測っており、同じ角度を厚さへもう一度掛けることはしない。
     */
    public record Plate(float front, float rear, float left, float right, float top, float bottom) {
        public static final Plate NONE = all(0.0F);

        private static final Codec<Plate> FACES = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("front", 0.0F).forGetter(Plate::front),
                Codec.FLOAT.optionalFieldOf("rear", 0.0F).forGetter(Plate::rear),
                Codec.FLOAT.optionalFieldOf("left", 0.0F).forGetter(Plate::left),
                Codec.FLOAT.optionalFieldOf("right", 0.0F).forGetter(Plate::right),
                Codec.FLOAT.optionalFieldOf("top", 0.0F).forGetter(Plate::top),
                Codec.FLOAT.optionalFieldOf("bottom", 0.0F).forGetter(Plate::bottom)
        ).apply(instance, Plate::new));

        /** 数1つ（全面）とブロック（面ごと）の両方を読む。書き戻すときは全面同じなら数1つにする。 */
        public static final Codec<Plate> CODEC = Codec.either(Codec.FLOAT, FACES).xmap(
                either -> either.map(Plate::all, faces -> faces),
                plate -> plate.isUniform() ? Either.left(plate.front) : Either.right(plate));

        /** 全面が同じ厚さの板。 */
        public static Plate all(float thickness) {
            return new Plate(thickness, thickness, thickness, thickness, thickness, thickness);
        }

        private boolean isUniform() {
            return this.front == this.rear && this.front == this.left && this.front == this.right
                    && this.front == this.top && this.front == this.bottom;
        }

        /**
         * 外向き法線が指す面の厚さ。
         *
         * @param local 法線を箱自身の3軸へ射影した物。{@link Hitbox#local} の答えで、x が箱の<em>左</em>、
         *              y が上、z が前。稜線ちょうどで2軸が並んだら前後、左右、上下の順に取る——正面と
         *              側面の境目を撃った弾は正面装甲に当たったことにする
         */
        public float facing(Vec3 local) {
            double across = Math.abs(local.x);
            double aloft = Math.abs(local.y);
            double along = Math.abs(local.z);

            if (along >= across && along >= aloft) {
                return local.z >= 0.0 ? this.front : this.rear;
            }

            if (across >= aloft) {
                return local.x >= 0.0 ? this.left : this.right;
            }

            return local.y >= 0.0 ? this.top : this.bottom;
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
        GUN("gun"),
        /**
         * 後部ハッチに運ばれ、ハッチの開き角だけヒンジ回りに振られる。
         *
         * <p>砲塔の箱と同じ理由でこれが要る。開いたランプの上を歩き、閉じたランプで弾を止めるには、その箱が
         * 板と一緒に降りていなければならない。降りていなければ、開けたのに床は空中に残り、機内は塞がったまま
         * になる。
         */
        RAMP("ramp"),
        /** 兵装倉の扉。左右で逆へ、前後軸回りに振れる。 */
        BAY_LEFT("bay_left"),
        BAY_RIGHT("bay_right"),
        /** 揚力系のノズル。巡航姿勢から下向きまで、左右軸回りに振れる。 */
        NOZZLE("nozzle"),
        /**
         * 可変翼。翼根で鉛直軸回りに回り、翼端を尾部へ運ぶ。
         *
         * <p>どちら回りが「後ろ」かは左右で逆だが、それをこの名前では決めない——箱自身のヒンジの x の符号
         * から出す。モデル側が {@code VehicleGeoModel.sweepAboutY} でやっているのと同じ理屈で、同じ理由だ。
         */
        WING_LEFT("wing_left"),
        WING_RIGHT("wing_right");

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
