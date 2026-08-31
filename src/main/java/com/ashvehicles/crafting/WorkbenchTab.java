package com.ashvehicles.crafting;

import com.mojang.serialization.Codec;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/**
 * 工廠の棚。図面1枚がどの棚に載るかで、画面のどのタブに出るかが決まる。
 *
 * <p>元は機体しか組めなかったので棚は要らなかった。兵装・装備・弾薬まで工廠へ移すと1枚の一覧が
 * 60行を越え、機体を1機選ぶのにミサイルをかき分けることになる——クリエイティブのタブを3枚に割った
 * のと同じ理由で、ここも割る。
 *
 * <p>並びは作る順ではなく、探す順。機体が先頭なのは工廠へ来る理由の大半がそれだからで、弾薬が
 * 最後なのは一番よく作るものだからではなく、一番迷わないものだからだ。
 *
 * <p>図面に {@code "tab"} が無ければ {@link #VEHICLE}。工廠が機体だけを組んでいた頃に書かれた
 * 図面——コンテンツパックのものを含む——は、書き足さなくても今までと同じ棚に載る。
 */
public enum WorkbenchTab implements StringRepresentable {
    /** 機体・車両・艦艇。置けばそれ自身になるもの。 */
    VEHICLE("vehicle"),

    /** ミサイルと爆弾。ラックに載せて落とす／撃つもの。 */
    WEAPON("weapon"),

    /** ラックとポッド。それ自身は当たらないが、当たるものを積むための金具と箱。 */
    EQUIPMENT("equipment"),

    /** ベルトと砲弾とロケット弾。機体に内蔵された火砲へ入れるもの。 */
    AMMO("ammo");

    public static final Codec<WorkbenchTab> CODEC = StringRepresentable.fromEnum(WorkbenchTab::values);

    /**
     * 名前で送る。番号で送ると、棚を1つ足したり並べ替えたりしただけで、古いクライアントに別の棚が
     * 見えることになる。
     */
    public static final StreamCodec<ByteBuf, WorkbenchTab> STREAM_CODEC =
            ByteBufCodecs.STRING_UTF8.map(WorkbenchTab::byName, WorkbenchTab::getSerializedName);

    private final String id;

    WorkbenchTab(String id) {
        this.id = id;
    }

    @Override
    public String getSerializedName() {
        return this.id;
    }

    /** タブに出す名前。 */
    public Component label() {
        return Component.translatable("gui.ashvehicles.workbench.tab." + this.id);
    }

    /** 知らない名前は機体の棚。壊れた図面1枚で工廠が開かなくなるより、間違った棚に出る方がまし。 */
    public static WorkbenchTab byName(String name) {
        for (WorkbenchTab tab : values()) {
            if (tab.id.equals(name)) {
                return tab;
            }
        }

        return VEHICLE;
    }
}
