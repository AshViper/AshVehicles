package com.ashvehicles.match;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.registry.ModItems;

import net.minecraft.resources.ResourceLocation;

/**
 * 1機出すのに要る出撃ポイント。
 *
 * <p><b>チケットは陣営の残機、ポイントは個人の財布。</b> 撃墜されれば陣営のチケットが1枚減り、次に何を
 * 出せるかは自分が稼いだポイントで決まる——だから、いい機体を落とされ続ける者は自然に軽い機体へ降りて
 * いく。どちらか片方では、その2つの別々の圧力が作れない。
 *
 * <p><b>値段は機体ファイルが持つ</b>（{@code airframe.cost} / {@code hull.cost}）。機体ごとの数値で
 * あり、機体ファイルは既にその類の数値を全部持っている。書かれていなければ種別の既定になるので、
 * コンテンツパックは何も書かなくても値段が付く。
 *
 * <p>既定は<b>航空機150・地上車両200・艦400</b>。艦だけ高いのは、あれが1隻で戦線そのものになる物
 * だからだ。強い機体に高い値段を付けるのは定義ファイル側の仕事で、ここには何も書かない。
 */
public final class Costs {
    /** 航空機の既定。 */
    public static final int AIRCRAFT = 150;

    /** 地上車両の既定。 */
    public static final int GROUND = 200;

    /** 艦の既定。 */
    public static final int SHIP = 400;

    private Costs() {
    }

    /** その機体1機の値段。知らない名前なら航空機の既定。 */
    public static int of(ResourceLocation vehicle) {
        if (ModItems.aircraft().containsKey(vehicle)) {
            int written = Definitions.AIRCRAFT.get(vehicle).airframe().cost();

            return written > 0 ? written : AIRCRAFT;
        }

        if (ModItems.vehicles().containsKey(vehicle)) {
            var definition = Definitions.VEHICLES.get(vehicle);
            int written = definition.hull().cost();

            return written > 0 ? written : (definition.isShip() ? SHIP : GROUND);
        }

        return AIRCRAFT;
    }

    /**
     * その注文書で出す値段。機体の値段に、吊る物の試合での値段（核の {@code nuclear.match.cost}）を足す。
     *
     * <p>出撃盤の表示もサーバーの勘定もここを通る。注文書はプリセットの名前からサーバーが組み直す物なので
     * （{@link AirPresets#order}）、画面が見せた値段とサーバーが引く値段は同じ規則から出る。
     */
    public static int of(Loadout loadout) {
        return of(loadout.vehicle()) + surcharge(loadout);
    }

    /** 吊る物の試合での値段の合計。核を吊らない注文書では0。 */
    public static int surcharge(Loadout loadout) {
        int extra = 0;

        for (Loadout.Entry entry : loadout.entries()) {
            if (ModItems.weapons().containsKey(entry.weapon())) {
                extra += Definitions.weapon(entry.weapon()).nuclear()
                        .map(nuke -> nuke.match().cost() * entry.count())
                        .orElse(0);
            }
        }

        return extra;
    }

    /** 注文書が吊る核の数。 */
    public static int nuclear(Loadout loadout) {
        int count = 0;

        for (Loadout.Entry entry : loadout.entries()) {
            if (ModItems.weapons().containsKey(entry.weapon()) && Definitions.weapon(entry.weapon()).isNuclear()) {
                count += entry.count();
            }
        }

        return count;
    }
}
