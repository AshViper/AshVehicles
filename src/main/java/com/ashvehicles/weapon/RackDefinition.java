package com.ashvehicles.weapon;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.world.phys.Vec3;

/**
 * 懸架具。パイロンと、そこに吊る兵装との間に入る金具。{@code data/ashvehicles/rack/} にファイルを置け
 * ば起動時に MOD がアイテムを登録する。
 *
 * <p>兵装パイロンは翼の取付点でしかない。そこへ直接何かを吊ることはなく、まずランチレールか投下ラックを
 * 付け、兵装はその上に載る。どのラックを付けるかが決めるのは2つだけ——そのステーションが何発をどこに吊る
 * かと、そもそもどの種類の兵装を受けるか。ランチレールはミサイルを受け、投下ラックはファイルが与えた位置
 * に爆弾を4発受ける。
 *
 * <p>MOD が読む他の物と同様、起動時に「何が存在するか」を知るために一度、{@code /reload} のたびにデータ
 * パックからもう一度読まれるので、再起動なしでラックの見た目や寸法を変えられる。リロードで搭載位置が減った
 * ラックに爆弾を4発吊っていたステーションは、載る分だけ残して残りを落とす
 * （{@code WeaponMounts.ensureLayout} 参照）。
 *
 * @param item MOD がアイテムを登録すべきか。プレイヤーが取り付ける物には要る。機体が最初から積んでいる
 *             設定の物には要らない
 * @param stations このラック上の各兵装が吊られる位置。パイロンからのオフセットを機体自身の軸で。並び順が
 *                 装填される順なので、装填したい順に並べること。空なら「パイロン位置に1発」で、それが
 *                 ただのレール
 * @param accepts このラックが受ける兵装の種類。空なら全種類
 * @param mass 金具そのものの重さ（kg）。実物の重量をそのまま書く——単装レールが40、多連装ラックが100
 *             あたり。機体の搭載可能重量から、その上に載る兵装と一緒に引かれる。爆弾4発を吊るために
 *             ラックを付けたパイロンは、ラックの分だけ先に重くなっている
 */
public record RackDefinition(boolean item, List<Vec3> stations, List<WeaponDefinition.Type> accepts,
        float mass) {
    /** 自前の搭載位置を持たないラック。パイロン位置に1発だけ。 */
    private static final List<Vec3> SINGLE = List.of(Vec3.ZERO);

    public static final Codec<RackDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("item", true).forGetter(RackDefinition::item),
            Vec3.CODEC.listOf().optionalFieldOf("stations", List.of()).forGetter(RackDefinition::stations),
            WeaponDefinition.Type.CODEC.listOf().optionalFieldOf("accepts", List.of())
                    .forGetter(RackDefinition::accepts),
            Codec.FLOAT.optionalFieldOf("mass", 0.0F).forGetter(RackDefinition::mass)
    ).apply(instance, RackDefinition::new));

    /**
     * ゲームが読めるファイルが1つも無いラックに使う値。何でも受ける単装レールにして、正体不明のラックが
     * 付いたステーションでも0発ではなく1発は積めるようにする。
     */
    public static final RackDefinition FALLBACK = new RackDefinition(true, List.of(), List.of(), 0.0F);

    /** このラック上の全搭載位置。パイロンからのオフセットで。空にはならない。 */
    public List<Vec3> places() {
        return this.stations.isEmpty() ? SINGLE : this.stations;
    }

    /** このラックが積める数。1、またはファイルが与えた位置の数。 */
    public int capacity() {
        return this.places().size();
    }

    /**
     * このラック上の1発分の位置。パイロンからのオフセットで。
     *
     * <p>範囲外の添字は例外ではなく最も近い実在の位置を返す。訊かれているのは「どこに描くか」であり、
     * 爆弾を4発吊っている最中の {@code /reload} で位置が減ったラックはクラッシュに値しない。
     */
    public Vec3 place(int at) {
        List<Vec3> places = this.places();

        return places.get(Math.max(0, Math.min(at, places.size() - 1)));
    }

    /** このラックがその兵装を積めるか。何も指定していないラックは何でも積める。 */
    public boolean takes(WeaponDefinition weapon) {
        return this.accepts.isEmpty() || this.accepts.contains(weapon.type());
    }
}
