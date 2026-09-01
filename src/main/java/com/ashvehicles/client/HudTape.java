package com.ashvehicles.client;

import javax.annotation.Nullable;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 動く目盛り。数字の代わりに、目盛りの方を数字の下で滑らせる。
 *
 * <p><b>なぜ数字1つでは足りないのか。</b>「420」はパイロットの問いに答えない。実際に問われているのは
 * 「上がっているか下がっているか、どれくらいの速さで」だ。数字1つはそれを言えない——読んだ瞬間の値しか
 * 持たないので、変化は前に読んだ値を覚えていた者にしか見えない。目盛りが流れていれば、その速さと向きが
 * 見えている物そのものになる。実機の計器が丸ごとこの形に移ったのはそのためだ。
 *
 * <p>読む値は箱の中に置く。目盛りは箱の下を通り抜け、箱は動かない。だから「今いくつか」は常に同じ場所に
 * あり、「どこへ向かっているか」は目盛りの流れが言う。1つの計器が2つの問いに別々に答える。
 *
 * <p>上端の方位帯も同じ物だ。中央の三角が今の方位を指し、その下の箱がそれを字で繰り返す。
 */
final class HudTape {
    /** 縦の目盛りが中央から上下へ伸びる長さ。 */
    private static final int REACH = 50;
    /** 縦の目盛りの、1単位あたりのピクセル。100単位で20px——数字が5つ乗る。 */
    private static final float PER_UNIT = 0.2F;
    /** 数字の付く間隔と、付かない目盛りの間隔。 */
    private static final int MAJOR = 100;
    private static final int MINOR = 25;
    /** 値の箱の幅。 */
    private static final int BOX = 47;
    /** 軸から右へ、箱の尖った先までの幅。目盛りを置く側が、他の物との間隔を測るのに要る。 */
    static final int RIGHT_REACH = BOX + 5;
    /** 軸から左へ、菱形の外までの幅。 */
    static final int LEFT_REACH = 13;

    /** 横の目盛りが中央から左右へ伸びる長さ。 */
    private static final int SPAN = 80;
    /** 横の目盛りの、1度あたりのピクセル。10度で25px。 */
    private static final float PER_DEGREE = 2.5F;
    private static final int WIDE = 10;
    private static final int FINE = 2;

    /** 目盛りの裏に敷く地。明るい空を背にしても数字が読めるだけの濃さで、風景を潰さないだけの薄さ。 */
    private static final int BACKGROUND = 0x58000000;

    private HudTape() {
    }

    /**
     * 縦の目盛り1本。対気速度と高度。
     *
     * @param x     目盛りの軸のx座標。数字も箱もここから右へ出る
     * @param name  目盛りの上に置く名前
     * @param unit  目盛りの下に置く単位
     * @param note  単位の下にもう1行。要らなければ null
     */
    static void vertical(GuiGraphics graphics, Font font, int x, int centreY, String name, double value,
            String unit, @Nullable String note, @Nullable String noteValue) {
        int top = centreY - REACH;
        int bottom = centreY + REACH;

        graphics.fill(x - 2, top - 2, x + 40, bottom + 2, BACKGROUND);
        graphics.fill(x, top, x + 1, bottom, AircraftHud.DIM);

        graphics.drawString(font, name, x + 1, top - 12, AircraftHud.DIM, true);
        graphics.drawString(font, unit, x + 1, bottom + 5, AircraftHud.DIM, true);

        if (note != null) {
            graphics.drawString(font, note, x + 1, bottom + 17, AircraftHud.DIM, true);
            graphics.drawString(font, noteValue == null ? "" : noteValue, x + 1, bottom + 27,
                    AircraftHud.GREEN, true);
        }

        // 端から半目盛り外まで回す。画面外へ出る目盛りを描かせないためではなく、入ってくる目盛りを
        // 途中から生やさないため。
        long reach = Math.round(REACH / PER_UNIT);
        long first = Math.floorDiv(Math.round(value) - reach, MINOR) * MINOR;

        HudScale.scissor(graphics, x - 2, top - 2, x + 40, bottom + 2);

        for (long mark = first; mark <= Math.round(value) + reach; mark += MINOR) {
            int y = centreY - Math.round((float) (mark - value) * PER_UNIT);
            boolean labelled = Math.floorMod(mark, MAJOR) == 0;

            graphics.fill(x, y, x + (labelled ? 7 : 4), y + 1, labelled ? AircraftHud.GREEN : AircraftHud.DIM);

            // 箱の高さに掛かる数字は出さない。箱の方が手前に来るので、下に半分隠れた数字が残るだけだ。
            if (labelled && Math.abs(y - centreY) > 10) {
                graphics.drawString(font, String.valueOf(mark), x + 11, y - 4, AircraftHud.DIM, true);
            }
        }

        graphics.disableScissor();

        box(graphics, font, x, centreY, String.valueOf(Math.round(value)));
    }

    /**
     * 読んでいる値の箱。目盛りの上に重ね、左に小さな菱形を添える。
     *
     * <p>菱形は目盛りの外側にある唯一の印だ。目の端で計器を捉えたとき、どこが「今」なのかがそれで分かる。
     */
    private static void box(GuiGraphics graphics, Font font, int x, int centreY, String text) {
        int left = x - 3;
        int right = left + BOX;
        int top = centreY - 7;
        int bottom = centreY + 8;

        graphics.fill(left, top, right, bottom, 0xD0000000);
        graphics.fill(left, top, right, top + 1, AircraftHud.GREEN);
        graphics.fill(left, bottom - 1, right, bottom, AircraftHud.GREEN);
        graphics.fill(left, top, left + 1, bottom, AircraftHud.GREEN);

        // 右辺は真っ直ぐではなく尖らせる。菱形と合わせて、この箱がどちらの目盛りに属しているかを言う。
        for (int step = 0; step < 5; step++) {
            graphics.fill(right - 1 + step, top + step, right + step, bottom - step, AircraftHud.GREEN);
        }

        graphics.drawString(font, text, right - 4 - font.width(text), centreY - 3, AircraftHud.GREEN, false);

        diamond(graphics, x - 11, centreY, AircraftHud.GREEN);
        graphics.fill(x - 8, centreY, left, centreY + 1, AircraftHud.DIM);
    }

    private static void diamond(GuiGraphics graphics, int x, int y, int colour) {
        for (int step = 0; step < 4; step++) {
            int half = 3 - Math.abs(step - 1) - (step == 3 ? 1 : 0);

            graphics.fill(x - half, y - 2 + step, x + half + 1, y - 1 + step, colour);
        }
    }

    /**
     * 横の目盛り1本。上端の方位帯。
     *
     * @param compass 目盛りが方位か。真なら 45 度ごとに数字ではなく方位名が乗り、360 で巻く
     * @param readout 目盛りの下の箱に入れる字
     */
    static void horizontal(GuiGraphics graphics, Font font, int centreX, int y, float value,
            boolean compass, String readout) {
        int left = centreX - SPAN;
        int right = centreX + SPAN;

        graphics.fill(left - 3, y - 2, right + 3, y + 18, BACKGROUND);
        graphics.fill(left, y, right, y + 1, AircraftHud.DIM);

        int reach = Math.round(SPAN / PER_DEGREE);
        int first = Math.floorDiv(Math.round(value) - reach, FINE) * FINE;

        HudScale.scissor(graphics, left - 3, y - 2, right + 3, y + 18);

        for (int mark = first; mark <= Math.round(value) + reach; mark += FINE) {
            int at = centreX + Math.round((mark - value) * PER_DEGREE);
            boolean labelled = Math.floorMod(mark, WIDE) == 0;

            graphics.fill(at, y + 1, at + 1, y + 1 + (labelled ? 5 : 3),
                    labelled ? AircraftHud.GREEN : AircraftHud.DIM);

            if (labelled) {
                String text = compass ? point(mark) : String.valueOf(Math.floorMod(mark + 180, 360) - 180);

                graphics.drawString(font, text, at - font.width(text) / 2, y + 8, AircraftHud.DIM, true);
            }
        }

        graphics.disableScissor();

        // 今の値を指す三角。目盛りの側ではなく画面に固定する。動くのは目盛りだ。
        for (int step = 0; step < 4; step++) {
            graphics.fill(centreX - 3 + step, y - 5 + step, centreX + 4 - step, y - 4 + step, AircraftHud.GREEN);
        }

        int half = font.width(readout) / 2 + 4;

        graphics.fill(centreX - half, y + 19, centreX + half, y + 31, 0xD0000000);
        graphics.fill(centreX - half, y + 19, centreX + half, y + 20, AircraftHud.GREEN);
        graphics.fill(centreX - half, y + 30, centreX + half, y + 31, AircraftHud.GREEN);
        graphics.fill(centreX - half, y + 19, centreX - half + 1, y + 31, AircraftHud.GREEN);
        graphics.fill(centreX + half - 1, y + 19, centreX + half, y + 31, AircraftHud.GREEN);
        graphics.drawString(font, readout, centreX - font.width(readout) / 2, y + 22, AircraftHud.GREEN, false);
    }

    /** 方位帯に乗る字。45 度ごとは方位名、それ以外は度数。 */
    private static String point(int degrees) {
        int heading = Math.floorMod(degrees, 360);

        return heading % 45 == 0 ? AircraftHud.cardinal(heading) : String.valueOf(heading);
    }
}
