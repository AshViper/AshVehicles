package com.ashvehicles.client.screen;

import com.ashvehicles.client.particle.BlastStageParticle;
import com.ashvehicles.item.BlastWandItem;
import com.ashvehicles.particle.Effects;
import com.ashvehicles.network.BlastPowerPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 試験棒の規模を決めるスライダー。
 *
 * <p>元は4段階の巡回だった。眺めるだけの道具としてはそれで足りていたが、演出の数値を詰める道具としては足り
 * ない——「12 と 30 の間のどこでキノコ雲にすべきか」のような問いに、4つの決め打ちでは答えられないからだ。
 * ここは 1 から {@link com.ashvehicles.particle.Effects#LARGEST} まで連続で、両端を含めて全部出せる。
 *
 * <p>選んだ値はスタックに載る（{@link BlastPowerPayload} 参照）ので、棒を2本持てば2つの規模を並べて比べ
 * られる。閉じたときに1度だけ送るので、つまみを動かしている間パケットは飛ばない。
 *
 * <p>ゲームを止めない。止めると、直前に起こした爆発が画面の向こうで固まって、次の値を決める材料が消える。
 */
public class BlastWandScreen extends Screen {
    private static final int WIDE = 200;
    private static final int TALL = 20;
    /** つまみを画面中央からどれだけ下げるか。上に見出し、下に注記が入る分。 */
    private static final int BELOW_MIDDLE = 10;
    private static final int LINE = 14;

    private int chosen;

    private BlastWandScreen(int power) {
        super(Component.translatable("screen.ashvehicles.blast_wand.title"));
        this.chosen = Mth.clamp(power, BlastWandItem.LEAST, BlastWandItem.MOST);
    }

    /** クライアントからのみ呼ばれる。{@link BlastWandItem} のスニーク使用の行き先。 */
    public static void open(int power) {
        Minecraft.getInstance().setScreen(new BlastWandScreen(power));
    }

    @Override
    protected void init() {
        this.addRenderableWidget(new PowerSlider(
                (this.width - WIDE) / 2, this.height / 2 - TALL / 2 + BELOW_MIDDLE));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int middle = this.width / 2;

        graphics.drawCenteredString(this.font, this.title, middle,
                this.height / 2 - TALL / 2 + BELOW_MIDDLE - LINE - LINE, 0xFFFFFF);

        graphics.drawCenteredString(this.font, this.tier(), middle,
                this.height / 2 - TALL / 2 + BELOW_MIDDLE + TALL + 6, 0xFFFFFF);
    }

    /**
     * 今の値がどの演出になるか。
     *
     * <p>数字を見ても分からない唯一のことなので書いておく。3段階あり、境目は形が変わる所にある——
     * 煙柱、キノコ雲、そして核。
     */
    private Component tier() {
        if (this.chosen >= Effects.NUCLEAR) {
            return Component.translatable("screen.ashvehicles.blast_wand.nuclear")
                    .withStyle(ChatFormatting.RED);
        }

        if (this.chosen >= BlastStageParticle.MUSHROOMS_ABOVE) {
            return Component.translatable("screen.ashvehicles.blast_wand.mushroom")
                    .withStyle(ChatFormatting.GOLD);
        }

        return Component.translatable("screen.ashvehicles.blast_wand.plain")
                .withStyle(ChatFormatting.DARK_GRAY);
    }

    /** 開いている間もワールドは動く。爆発を見ながら次の値を決めるための画面なので。 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 閉じるときに1度だけ送る。つまみを動かすたびに送っても、サーバーに届く必要があるのは最後の値だけだ。 */
    @Override
    public void onClose() {
        // 接続が既に無い状態で閉じることがある（切断、ワールドを抜けた直後）。そこへ送ると例外になるだけで、
        // 受け取る棒もどのみち存在しない。
        if (this.minecraft != null && this.minecraft.getConnection() != null) {
            PacketDistributor.sendToServer(new BlastPowerPayload(this.chosen));
        }

        super.onClose();
    }

    private final class PowerSlider extends AbstractSliderButton {
        private PowerSlider(int x, int y) {
            super(x, y, WIDE, TALL, Component.empty(), fraction(BlastWandScreen.this.chosen));
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.translatable("screen.ashvehicles.blast_wand.scale",
                    BlastWandScreen.this.chosen));
        }

        @Override
        protected void applyValue() {
            BlastWandScreen.this.chosen = BlastWandItem.LEAST
                    + (int) Math.round(this.value * (BlastWandItem.MOST - BlastWandItem.LEAST));
        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, this.createNarrationMessage());
        }
    }

    /** 規模をつまみの位置（0〜1）へ。 */
    private static double fraction(int power) {
        return (double) (power - BlastWandItem.LEAST) / (BlastWandItem.MOST - BlastWandItem.LEAST);
    }
}
