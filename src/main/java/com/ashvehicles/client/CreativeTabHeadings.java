package com.ashvehicles.client;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.registry.ModCreativeTabs;
import com.ashvehicles.registry.ModItems;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;

/**
 * クリエイティブタブのジャンル見出しを描く。
 *
 * <p>タブは1枚しか無い（{@link ModCreativeTabs}）ので、機体も兵装も弾も1本の帯に繋がっている。
 * その変わり目には横一列ぶんの仕切りアイテムが差し込まれていて、ここがその9枠の上に帯を敷き、
 * ジャンル名を1つ置く。アイテムのテクスチャ自体は透明なので、プレイヤーが見るのはこの帯だけだ。
 *
 * <p>描く場所を機体の一覧から数えて求めることはしない。クリエイティブ画面はスクロール位置に応じて
 * 45枠を詰め替えるので、今どの行に何が載っているかを知っているのは画面自身であり、それはスロットの
 * 中身として読める。<b>行の9枠すべてが同じ題を持つ仕切りである行だけ</b>を見出しと見なすので、
 * 仕切りを1つ掴んで持ち帰った者のインベントリに帯が伸びることもない。
 *
 * <p>{@link ContainerScreenEvent.Render.Foreground} に乗るのは、そこがアイテムの後・ツールチップの
 * 前だからだ。帯はアイテムの上に敷かれ、カーソルを乗せたときの説明はその上に出る。この時点の
 * {@code PoseStack} は既に画面の左上へ寄せてあるので、スロットの座標をそのまま使える。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class CreativeTabHeadings {
    /** クリエイティブ画面が一度に見せる枠。5行×9列で、それ以降のスロットはホットバー。 */
    private static final int GRID = 45;

    /** スロットの縦横の間隔。バニラのクリエイティブ画面が 18px 刻みで並べる。 */
    private static final int PITCH = 18;

    /** 帯の地色。スロットの絵を隠す濃さが要る。 */
    private static final int BAR = 0xFF23232B;

    /** 帯の上端と下端。1px ずつ明暗を置くと、平らな矩形が板に見える。 */
    private static final int TOP_EDGE = 0xFF55555F;
    private static final int BOTTOM_EDGE = 0xFF121218;

    /** 題の左右へ伸ばす罫。横一列を使い切っていることがこれで読める。 */
    private static final int RULE = 0xFF55555F;

    private static final int TEXT = 0xFFEDEDF2;

    /** 罫と題の間の空き。 */
    private static final int GAP = 4;

    /**
     * 見出しを描く高さ。アイテムより手前でなければならないが、ツールチップより奥でなければならない。
     * バニラがこの画面で頁数を描くのに使うのと同じ 300。
     */
    private static final int DEPTH = 300;

    @SubscribeEvent
    public static void onForeground(ContainerScreenEvent.Render.Foreground event) {
        if (!(event.getContainerScreen() instanceof CreativeModeInventoryScreen screen)) {
            return;
        }

        AbstractContainerMenu menu = screen.getMenu();

        if (menu.slots.size() < GRID) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        Font font = Minecraft.getInstance().font;

        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, DEPTH);

        for (int row = 0; row * ModCreativeTabs.COLUMNS < GRID; row++) {
            Component title = heading(menu, row);

            if (title != null) {
                draw(graphics, font, menu.getSlot(row * ModCreativeTabs.COLUMNS), title);
            }
        }

        graphics.pose().popPose();
    }

    /**
     * その行に載っている見出しの題。9枠が揃って同じ題の仕切りでなければ見出しではない。
     *
     * <p>題を持たない仕切り——見出しの手前で行末を埋めるための枠——もここで弾かれる。埋め草の行は
     * 存在しない（埋めるのは常に行の途中から末尾まで）が、埋め草だけが並ぶ行があっても帯は出ない。
     */
    @Nullable
    private static Component heading(AbstractContainerMenu menu, int row) {
        Component title = null;

        for (int column = 0; column < ModCreativeTabs.COLUMNS; column++) {
            ItemStack stack = menu.getSlot(row * ModCreativeTabs.COLUMNS + column).getItem();

            if (!stack.is(ModItems.TAB_DIVIDER.get())) {
                return null;
            }

            Component name = stack.get(DataComponents.CUSTOM_NAME);

            if (name == null || (title != null && !title.equals(name))) {
                return null;
            }

            title = name;
        }

        return title;
    }

    /** 行頭のスロットから横一列ぶんの帯を敷き、題を中央に、余った幅を罫で埋める。 */
    private static void draw(GuiGraphics graphics, Font font, Slot first, Component title) {
        // スロットの絵は枠の1px 外側まであるので、帯もその外まで届かせる。
        int left = first.x - 1;
        int right = first.x + ModCreativeTabs.COLUMNS * PITCH - 1;
        int top = first.y - 1;
        int bottom = first.y + 17;

        graphics.fill(left, top, right, bottom, BAR);
        graphics.fill(left, top, right, top + 1, TOP_EDGE);
        graphics.fill(left, bottom - 1, right, bottom, BOTTOM_EDGE);

        int width = font.width(title);
        int centre = (left + right) / 2;
        int textLeft = centre - width / 2;
        int rule = (top + bottom) / 2;

        graphics.fill(left + GAP, rule, textLeft - GAP, rule + 1, RULE);
        graphics.fill(textLeft + width + GAP, rule, right - GAP, rule + 1, RULE);

        graphics.drawString(font, title, textLeft, bottom - 13, TEXT, false);
    }

    private CreativeTabHeadings() {
    }
}
