package com.ashvehicles.registry;

import java.util.Collection;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.data.Definitions;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.ItemLike;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * クリエイティブタブ1枚と、その中の並び。
 *
 * <p>元は「航空機」「車両」「兵装」の3枚だった。1枚に戻したので、機体を探すのに上のタブ列のどれを
 * 押すかを覚える必要は無くなったが、代わりに 120 を超えるアイテムが1本の帯に繋がった。区切りが
 * 無ければ、どこで固定翼機が終わってヘリが始まるのかは並び順を知っている者にしか読めない。
 *
 * <p>そこでジャンルの変わり目に見出しを1行差し込む。見出しは9枠——横一列まるごと——を占める
 * {@link ModItems#TAB_DIVIDER} で、その9枠の上に帯と題を描くのが
 * {@code client/CreativeTabHeadings}。バニラのクリエイティブ画面は行の途中から項目を並べるので、
 * 見出しの前には行末までの余白を同じアイテムで埋める（{@link Shelf#pad()}）。埋めた枠は名前を持たない
 * ので、帯も題も描かれず、空きスロットに見える。
 *
 * <p>見出しの枠は {@link CreativeModeTab.TabVisibility#PARENT_TAB_ONLY} で入れる。検索タブと、
 * 検索タブの中身を読む JEI の一覧には、この飾りは出ない。
 */
public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, AshVehicles.MODID);

    /** クリエイティブ画面の1行の枠数。見出し1本の長さでもある。 */
    public static final int COLUMNS = 9;

    /** 見出しの翻訳キーの頭。後ろに付くのは {@link #contents} が渡すジャンル名。 */
    private static final String SECTION = "itemGroup.ashvehicles.section.";

    /**
     * この MOD の全部が入る唯一のタブ。並びはレジストリ順ではなく格納庫の順——まず道具、次に工廠と
     * 素材、そこから飛ぶもの・走るもの・浮かぶもの、最後に積むものと撃つもの。
     */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN =
            TABS.register("main",
                    () -> CreativeModeTab.builder()
                            .title(Component.translatable("itemGroup.ashvehicles"))
                            .withTabsBefore(CreativeModeTabs.COMBAT)
                            .icon(() -> tabIcon(ModItems.aircraft().values()))
                            .displayItems((parameters, output) -> contents(new Shelf(output)))
                            .build());

    /**
     * 棚に何をどの順で載せるか。見出しを1つ増やすときに触るのはこのメソッドだけで、行の幅も余白も
     * {@link Shelf} が面倒を見る。
     *
     * <p><b>タブに並ぶのはここに書いたものだけだ。</b>{@link ModItems} への登録はアイテムを存在させるが、
     * タブには何も出さない。機体・車両・兵装はファイル由来なので下の各行が丸ごと拾うが、手書きの道具は
     * この一覧に足さない限り、作れるのに誰も見つけられないアイテムになる。
     */
    private static void contents(Shelf shelf) {
        // ばらす道具と注ぐ燃料、無人機へ繋ぐ端末、撃つ的、そして爆発演出の棒。機体にも車両にも要る
        // ものなので、どの機種より先に置く。
        shelf.heading("tools");
        shelf.item(ModItems.WRENCH);
        shelf.item(ModItems.FUEL_CAN);
        shelf.item(ModItems.DRONE_TERMINAL);
        shelf.item(ModItems.TARGET_DRONE);
        shelf.item(ModItems.BLAST_WAND);

        // 工廠と、その上で使う中間素材。作る順——板・部品・基板、そこから装甲板・エンジン・
        // ジェットエンジン・アビオニクス、さらにシーカー・推力部品・信管・高性能爆薬——に並ぶ。
        shelf.heading("workshop");
        shelf.item(ModItems.VEHICLE_WORKBENCH);
        shelf.item(ModItems.TEAM_SPAWN);
        shelf.item(ModItems.CAPTURE_POINT);
        ModItems.MATERIALS.forEach(shelf::item);

        shelf.heading("aircraft");
        aircraft(shelf, false);

        shelf.heading("helicopters");
        aircraft(shelf, true);

        shelf.heading("vehicles");
        vehicles(shelf, false);

        shelf.heading("ships");
        vehicles(shelf, true);

        // 翼下の金具と、そこに載る箱。兵装より先に置くのは、積む順がラック→兵装だからだ。
        shelf.heading("mounts");
        ModItems.racks().values().forEach(shelf::item);
        ModItems.equipment().values().forEach(shelf::item);

        shelf.heading("weapons");
        ModItems.weapons().values().forEach(shelf::item);

        // 火砲に込めるものは、供給先ごとの弾薬箱と、砲弾の種類の2階建て。棚の上では同じ1つの棚板。
        shelf.heading("ammunition");
        ModItems.ammo().values().forEach(shelf::item);
        ModItems.ammunition().values().forEach(shelf::item);
    }

    /** 固定翼機か回転翼機か、片方だけを ID 順に。どちらかは機体ファイルの {@code type} が決める。 */
    private static void aircraft(Shelf shelf, boolean helicopters) {
        ModItems.aircraft().forEach((id, item) -> {
            if (Definitions.AIRCRAFT.get(id).isHelicopter() == helicopters) {
                shelf.item(item);
            }
        });
    }

    /** 地上車両か艦艇か、片方だけを ID 順に。こちらも決めるのは車両ファイルの {@code type}。 */
    private static void vehicles(Shelf shelf, boolean ships) {
        ModItems.vehicles().forEach((id, item) -> {
            if (Definitions.VEHICLES.get(id).isShip() == ships) {
                shelf.item(item);
            }
        });
    }

    /**
     * タブのアイコン。機体の1機目を使う。中身を全部削除したパック向けの保険としてレンチに
     * フォールバックする（まず起きないが、落ちる理由にはしない）。
     */
    private static ItemStack tabIcon(Collection<? extends DeferredItem<? extends Item>> items) {
        return items.stream().<Item>map(DeferredItem::get)
                .findFirst()
                .orElseGet(ModItems.WRENCH::get)
                .getDefaultInstance();
    }

    /**
     * 並べた枠を数えながらタブへ流す係。
     *
     * <p>数えるのは、見出しが必ず行頭から始まるようにするためだ。クリエイティブ画面は受け取った順に
     * 9枠ずつ折り返して並べるだけなので、見出しの手前で行を埋めておかない限り、9枠の帯は2行に割れて
     * 中途半端な位置から始まる。
     *
     * <p>同じアイテムを同じ内容で2度入れるとタブの構築は例外で落ちる（{@code ItemDisplayBuilder}）。
     * 埋め草はどれも同じアイテムなので、1枠ごとに違う {@link CustomModelData} を持たせて別物にする。
     * この値でモデルは変わらない——{@code tab_divider} のモデルに上書きが無いからだ。
     */
    private static final class Shelf {
        private final CreativeModeTab.Output output;

        /** ここまでに流した枠の数。行頭かどうかはこれを9で割った余りが言う。 */
        private int cells;

        /** 埋め草を別物にするための通し番号。 */
        private int serial;

        private Shelf(CreativeModeTab.Output output) {
            this.output = output;
        }

        /** ジャンルの見出しを1行。行末まで埋めてから、題を持つ枠を横一列ぶん置く。 */
        private void heading(String genre) {
            pad();

            Component title = Component.translatable(SECTION + genre);

            for (int column = 0; column < COLUMNS; column++) {
                divider(title);
            }
        }

        private void item(ItemLike item) {
            output.accept(item);
            cells++;
        }

        /** 行末までの余白。題を持たないので帯も描かれず、空きスロットとして見える。 */
        private void pad() {
            while (cells % COLUMNS != 0) {
                divider(null);
            }
        }

        private void divider(@Nullable Component title) {
            ItemStack stack = new ItemStack(ModItems.TAB_DIVIDER.get());

            stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(serial++));

            if (title != null) {
                stack.set(DataComponents.CUSTOM_NAME, title);
            }

            output.accept(stack, CreativeModeTab.TabVisibility.PARENT_TAB_ONLY);
            cells++;
        }
    }

    private ModCreativeTabs() {
    }
}
