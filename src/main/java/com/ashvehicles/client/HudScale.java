package com.ashvehicles.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 計器を描く大きさ。ゲーム本体のGUI拡大率より1段小さく、ただし2倍は下回らない。
 *
 * <p><b>なぜ計器だけ別の拡大率なのか。</b>この MOD の計器は数が多い——左辺にスコープ、右辺に警戒受信機、
 * 下の両隅に枠が1枚ずつ、中央の左右に速度と高度の目盛り、上端に方位帯、右上に着弾表示。どれも必要な物だが、
 * 全部がゲーム本体と同じ拡大率で描かれると、拡大率3の画面では計器が窓の大半を占める。実際そうなっていた。
 * 速度と高度の目盛りは人工水平線に重なっていて、パイロットが実際に外を見る帯——ボアサイトの左右——には、
 * 計器の載っていない場所がほとんど残っていなかった。
 *
 * <p><b>なぜ1段なのか。</b>拡大率を選んだのはプレイヤーだ。大きな文字が要るから大きくしている人の画面で、
 * 計器だけを勝手に半分にしてよい理由は無い。1段下げるだけなら、その人の選択の意味は残ったまま、面積は
 * 3拡大率で約4割減る——重なりを解くにはそれで足りる。2倍を下限にするのは、そこがゲーム本体が「小さい」と
 * 呼ぶ大きさであり、これ以上小さくすると4K画面で文字が読めなくなるからだ。
 *
 * <p><b>なぜ端数にしないのか。</b>1段下げた結果は必ず整数倍（3→2、4→3）になる。文字の1ピクセルが画面の
 * 整数ピクセルに乗るということで、だから縮んでも輪郭が滲まない。0.8倍のような値を選んでいれば、読みやすさ
 * のために縮めた計器が読みにくくなっていた。
 *
 * <p><b>世界に合わせて描く物はここを通さない。</b>人工水平線・ピッチラダー・飛行経路マーカー・照準環は、
 * 窓の外の物と同じ場所に乗っていることに意味がある印だ。縮めればその一致が壊れる。だから縮むのは計器だけで、
 * 中央の印は元の大きさのまま残る——計器が中央から退いて空いた場所は、そのまま外が見える場所になる。
 */
final class HudScale {
    /** これより小さくはしない実効拡大率。ゲーム本体が「小さい」と呼ぶ大きさ。 */
    private static final double LEAST = 2.0;

    private HudScale() {
    }

    /**
     * 計器に掛ける倍率。1.0 なら本体と同じ大きさ。
     *
     * <p>拡大率が既に2以下なら何もしない。そこから下げる先が無いし、下げれば本体のGUIより小さくなる。
     */
    static float factor() {
        double gui = Minecraft.getInstance().getWindow().getGuiScale();

        return gui <= LEAST ? 1.0F : (float) (Math.max(LEAST, gui - 1.0) / gui);
    }

    /** 縮めた座標系での画面の幅。この座標系で隅を指せば、画面の隅に来る。 */
    static int width(GuiGraphics graphics) {
        return Math.round(graphics.guiWidth() / factor());
    }

    /** 縮めた座標系での画面の高さ。 */
    static int height(GuiGraphics graphics) {
        return Math.round(graphics.guiHeight() / factor());
    }

    /**
     * 本体の座標系での長さを、縮めた座標系の長さに直す。
     *
     * <p>縮まない物——水平線やラダー——を避けるために使う。あちらの大きさは本体の座標系で書かれているので、
     * 計器の側がそれを自分の座標系で言い直さなければ、避けているつもりで重なる。
     */
    static int at(int pixels) {
        return Math.round(pixels / factor());
    }

    /** ここから {@link #pop} までに描く物を縮める。 */
    static void push(GuiGraphics graphics) {
        float factor = factor();

        graphics.pose().pushPose();
        graphics.pose().scale(factor, factor, 1.0F);
    }

    static void pop(GuiGraphics graphics) {
        graphics.pose().popPose();
    }

    /**
     * 縮めた座標系での切り抜き。
     *
     * <p>{@code GuiGraphics.enableScissor} だけは行列を見ない——切り抜きはGPUの矩形であって描画命令では
     * ないからだ。縮めた座標をそのまま渡すと、切り抜きだけが縮まずに残って、目盛りが箱の外まで流れる。
     */
    static void scissor(GuiGraphics graphics, int left, int top, int right, int bottom) {
        float factor = factor();

        graphics.enableScissor(Math.round(left * factor), Math.round(top * factor),
                Math.round(right * factor), Math.round(bottom * factor));
    }
}
