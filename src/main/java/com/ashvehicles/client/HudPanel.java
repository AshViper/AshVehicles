package com.ashvehicles.client;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * 画面の隅にまとめて置く計器の1枚。行を全部集めてから、それが収まる大きさの枠を1枚描き、その中に並べる。
 *
 * <p><b>なぜ枠なのか。</b>この計器の行はどれも条件付きだ——増槽を吊っていれば1行増え、可変翼なら1行増え、
 * 砲手が乗れば1行増える。文字ごとに影を敷いていた頃、その結果は行数ぶんの黒い短冊であり、明るい空を背にすると
 * 短冊の間から空が透けて、機体ごと・瞬間ごとに輪郭の違う塊になっていた。1枚の枠は、行が何行であっても計器を
 * 1つの物として見せる。
 *
 * <p><b>なぜ組み立ててから描くのか。</b>枠の大きさは中身が決めるので、中身を全部知る前には描けない。呼ぶ側が
 * 行数を数えて座標を決めていた頃、条件付きの行を1つ足すたびに、その下にある全部の座標がずれた。ここへ行を積んで
 * から隅を指定すれば、高さも幅も列の位置もこちらが出す。
 *
 * <p><b>数字は右端で揃える。</b>行の左は名前、右は値。値の右端が1本の線に並ぶので、どれが尽きかけているかを
 * 数字を1つずつ追わずに見つけられる。最小幅を持たせてあるのも同じ理由で、中身が変わるたびに枠の幅が動けば、
 * その線は毎フレーム別の場所へ行ってしまう。
 */
final class HudPanel {
    /** 枠の内側と文字の隙間。 */
    private static final int PADDING = 6;
    /** 1行の高さ。 */
    private static final int ROW = 11;
    /** 区切り1本に割く高さ。 */
    private static final int RULE = 7;
    /** 見出し1行に割く高さ。文字の下に罫線が入る分だけ広い。 */
    private static final int TITLE = 15;
    /** 中身が細くても枠がこれより狭くならない幅。右端の数字の列を留めるため。 */
    private static final int MIN_WIDTH = 134;
    /** 名前と値の間に必ず空ける幅。 */
    private static final int GAP = 10;
    /** 目盛りの1目とその隙間。 */
    private static final int CELL = 5;
    private static final int CELL_GAP = 1;
    /** 目盛りに最低限割く幅。 */
    private static final int BAR_MIN = 40;

    /** 枠の地。真っ黒ではなく僅かに緑を含んだ暗色で、計器の色と地続きに見える。 */
    private static final int BACKGROUND = 0xC8040E06;
    /** 目盛りの、まだ埋まっていない側。 */
    private static final int TRACK = 0x2E3BE86A;
    /** 区切りの点線。 */
    private static final int DASH = 0x603BE86A;

    /** 値の左に付く小さな三角。可動部が今どちらにあるかを、読む前に形で言う。 */
    enum Mark {
        NONE,
        UP,
        DOWN
    }

    private sealed interface Row permits Title, Line, Bar, Divider {
    }

    private record Title(String text) implements Row {
    }

    private record Line(boolean crew, @Nullable String left, int leftColour, @Nullable String right,
            int rightColour, Mark mark) implements Row {
    }

    private record Bar(String label, float filled, String value, int colour) implements Row {
    }

    private record Divider() implements Row {
    }

    private final List<Row> rows = new ArrayList<>();

    boolean isEmpty() {
        return rows.isEmpty();
    }

    /** 枠の名前。1枚につき1つ、いちばん上に置く。下に罫線が入る。 */
    HudPanel title(String text) {
        rows.add(new Title(text));

        return this;
    }

    /** 点線1本。話題が変わる所に入れる。中身が無ければ足さないので、条件付きの塊の前に置いてよい。 */
    HudPanel divider() {
        if (!rows.isEmpty() && !(rows.get(rows.size() - 1) instanceof Divider)) {
            rows.add(new Divider());
        }

        return this;
    }

    HudPanel line(String text) {
        return line(text, AircraftHud.GREEN);
    }

    HudPanel line(@Nullable String text, int colour) {
        return pair(text, colour, null, colour);
    }

    /** 名前と値。値は枠の右端で揃う。どちらも省ける。両方無ければ行そのものが出ない。 */
    HudPanel pair(@Nullable String left, int leftColour, @Nullable String right, int rightColour) {
        return pair(left, leftColour, right, rightColour, Mark.NONE);
    }

    HudPanel pair(@Nullable String left, int leftColour, @Nullable String right, int rightColour, Mark mark) {
        if (left == null && right == null) {
            return this;
        }

        rows.add(new Line(false, left, leftColour, right, rightColour, mark));

        return this;
    }

    /** 搭乗者1人。名前の前に人の形が付くので、兵装の行と読み違えようがない。 */
    HudPanel crew(String name, int colour) {
        rows.add(new Line(true, name, colour, null, colour, Mark.NONE));

        return this;
    }

    /**
     * 目盛り1行。割合で読む物——燃料、レバー、装填の待ち——のため。
     *
     * <p>数字も一緒に出す。目盛りは「あとどれだけか」を一目で言い、数字は「正確にいくつか」に答える。どちらも
     * 要る場面があり、1行に両方載る。
     */
    HudPanel bar(String label, float filled, String value, int colour) {
        rows.add(new Bar(label, filled, value, colour));

        return this;
    }

    /** 左下隅へ。枠の左上のy座標を返すので、その上へもう1枚積める。 */
    int bottomLeft(GuiGraphics graphics, Font font, int left, int bottom) {
        return draw(graphics, font, left, bottom - height());
    }

    /** 右下隅へ。枠の右辺が指定した座標に来る。 */
    int bottomRight(GuiGraphics graphics, Font font, int right, int bottom) {
        return draw(graphics, font, right - width(font), bottom - height());
    }

    private int draw(GuiGraphics graphics, Font font, int left, int top) {
        if (rows.isEmpty()) {
            return top;
        }

        int width = width(font);
        int right = left + width;
        int bottom = top + height();

        frame(graphics, left, top, right, bottom);

        int inner = left + PADDING;
        int edge = right - PADDING;
        int labels = barLabel(font);
        int values = barValue(font);
        int y = top + PADDING;

        for (Row row : rows) {
            if (row instanceof Title title) {
                graphics.drawString(font, title.text(), inner, y, AircraftHud.GREEN, false);
                graphics.fill(inner, y + 11, edge, y + 12, AircraftHud.DIM);
                y += TITLE;
            } else if (row instanceof Divider) {
                dashes(graphics, inner, edge, y + 3);
                y += RULE;
            } else if (row instanceof Line line) {
                int text = inner + (line.crew() ? 9 : 0);

                if (line.crew()) {
                    person(graphics, inner, y + 1, line.leftColour());
                }

                // 枠の地の上なので文字の影は要らない。影は地の無い所で文字を読ませるための物で、ここでは
                // 縁を太らせて数字を潰すだけだ。
                if (line.left() != null) {
                    graphics.drawString(font, line.left(), text, y, line.leftColour(), false);
                }

                if (line.right() != null) {
                    int x = edge - font.width(line.right());

                    graphics.drawString(font, line.right(), x, y, line.rightColour(), false);

                    if (line.mark() != Mark.NONE) {
                        arrow(graphics, x - 9, y + 2, line.mark(), line.rightColour());
                    }
                } else if (line.mark() != Mark.NONE) {
                    arrow(graphics, edge - 7, y + 2, line.mark(), line.rightColour());
                }

                y += ROW;
            } else if (row instanceof Bar gauge) {
                graphics.drawString(font, gauge.label(), inner, y, AircraftHud.DIM, false);
                graphics.drawString(font, gauge.value(), edge - font.width(gauge.value()), y,
                        gauge.colour(), false);
                cells(graphics, inner + labels + 4, edge - values - 4, y + 1, gauge.filled(), gauge.colour());
                y += ROW;
            }
        }

        return top;
    }

    /** 角を落とした枠。地と縁を、平面図の枠と同じ書き方で。 */
    private static void frame(GuiGraphics graphics, int left, int top, int right, int bottom) {
        graphics.fill(left, top + 2, right, bottom - 2, BACKGROUND);
        graphics.fill(left + 2, top, right - 2, top + 2, BACKGROUND);
        graphics.fill(left + 2, bottom - 2, right - 2, bottom, BACKGROUND);

        graphics.fill(left + 2, top, right - 2, top + 1, AircraftHud.GREEN);
        graphics.fill(left + 2, bottom - 1, right - 2, bottom, AircraftHud.GREEN);
        graphics.fill(left, top + 2, left + 1, bottom - 2, AircraftHud.GREEN);
        graphics.fill(right - 1, top + 2, right, bottom - 2, AircraftHud.GREEN);

        graphics.fill(left + 1, top + 1, left + 2, top + 2, AircraftHud.GREEN);
        graphics.fill(right - 2, top + 1, right - 1, top + 2, AircraftHud.GREEN);
        graphics.fill(left + 1, bottom - 2, left + 2, bottom - 1, AircraftHud.GREEN);
        graphics.fill(right - 2, bottom - 2, right - 1, bottom - 1, AircraftHud.GREEN);
    }

    private static void dashes(GuiGraphics graphics, int left, int right, int y) {
        for (int x = left; x < right; x += 4) {
            graphics.fill(x, y, Math.min(x + 2, right), y + 1, DASH);
        }
    }

    /** 目盛り本体。連続した棒ではなく目に分ける。どこまで埋まっているかを、端を見ずに数えられる。 */
    private static void cells(GuiGraphics graphics, int left, int right, int y, float filled, int colour) {
        int span = Math.max(right - left, BAR_MIN);
        int count = Math.max(span / (CELL + CELL_GAP), 1);
        int lit = Math.round(count * Mth.clamp(filled, 0.0F, 1.0F));

        for (int cell = 0; cell < count; cell++) {
            int x = left + cell * (CELL + CELL_GAP);

            graphics.fill(x, y, x + CELL, y + 8, cell < lit ? colour : TRACK);
        }
    }

    /** 値の側を指す小さな三角。 */
    private static void arrow(GuiGraphics graphics, int x, int y, Mark mark, int colour) {
        for (int step = 0; step < 3; step++) {
            int row = mark == Mark.DOWN ? y + step : y + 2 - step;

            graphics.fill(x + step, row, x + 7 - step, row + 1, colour);
        }
    }

    /** 人1人。頭と肩だけの、5×7の形。 */
    private static void person(GuiGraphics graphics, int x, int y, int colour) {
        graphics.fill(x + 2, y, x + 5, y + 3, colour);
        graphics.fill(x + 1, y + 4, x + 6, y + 7, colour);
        graphics.fill(x, y + 5, x + 1, y + 7, colour);
        graphics.fill(x + 6, y + 5, x + 7, y + 7, colour);
    }

    int height() {
        int height = 0;

        for (Row row : rows) {
            height += switch (row) {
                case Title ignored -> TITLE;
                case Divider ignored -> RULE;
                default -> ROW;
            };
        }

        return rows.isEmpty() ? 0 : height + PADDING * 2;
    }

    int width(Font font) {
        if (rows.isEmpty()) {
            return 0;
        }

        int content = 0;

        for (Row row : rows) {
            content = Math.max(content, switch (row) {
                case Title title -> font.width(title.text());
                case Line line -> (line.crew() ? 9 : 0)
                        + (line.left() == null ? 0 : font.width(line.left()))
                        + (line.right() == null && line.mark() == Mark.NONE ? 0 : GAP)
                        + (line.right() == null ? 0 : font.width(line.right()))
                        + (line.mark() == Mark.NONE ? 0 : 9);
                case Bar gauge -> font.width(gauge.label()) + 8 + BAR_MIN + font.width(gauge.value());
                case Divider ignored -> 0;
            });
        }

        return Math.max(MIN_WIDTH, content + PADDING * 2);
    }

    /** 目盛り行の名前の列。全ての目盛りで同じ幅を取るので、棒の左端が縦に揃う。 */
    private int barLabel(Font font) {
        int width = 0;

        for (Row row : rows) {
            if (row instanceof Bar gauge) {
                width = Math.max(width, font.width(gauge.label()));
            }
        }

        return width;
    }

    private int barValue(Font font) {
        int width = 0;

        for (Row row : rows) {
            if (row instanceof Bar gauge) {
                width = Math.max(width, font.width(gauge.value()));
            }
        }

        return width;
    }
}
