package com.ashvehicles.client;

import java.util.Locale;

import javax.annotation.Nullable;

import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.weapon.WeaponDefinition;
import com.ashvehicles.weapon.WeaponMounts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * ターゲティングポッド越しにパイロットが見る物。十字線、捕捉対象のマーク、そして今投下した爆弾が届くかを決める
 * 数値いくつか。
 *
 * <p>飛行計器の上に重ねるのではなく、飛行計器の<em>代わりに</em>描く。通常の計器面にある物——姿勢ラダー、
 * 飛行経路マーカー、ガンサイト、爆弾投下線——はいずれもキャノピー越しのパイロット自身の視線に対して描かれる物
 * であり、主翼に取り付けられまったく別方向を向いたボールから来る映像の上では何の意味も持たない。ポッド映像の上に
 * 描かれたラダーは、間違った場所にある水平線だ。
 *
 * <p>ポッドはストロー越しの視界であり、それは意図的だ。地面を詳細に映し、それ以外は一切映さない。だからポッドを
 * 操作している間、パイロットはコックピットからの視界を失う。通常計器から残す唯一の物は兵装表示だ。主翼に何が残って
 * いるかは、パイロットがまさに答えようとしている問いだからだ。
 */
public final class PodDisplay {
    /** MOD 全計器の緑と、警告に使う琥珀と赤。 */
    private static final int GREEN = 0xFF3BE07A;
    private static final int DIM = 0xFF1F7A45;
    private static final int AMBER = 0xFFE0B23B;
    private static final int WHITE = 0xFFD8E4DC;

    /** ボアサイト十字の半幅（ピクセル）と、中央に空ける隙間。 */
    private static final int CROSS = 9;
    private static final int GAP = 3;
    /** 捕捉マークを囲む枠の一辺の半分。 */
    private static final int MARK = 7;

    private PodDisplay() {
    }

    /**
     * ポッドが描く物すべて。何か描いたかを返し、それが通常計器へ降板の合図になる。
     */
    public static boolean draw(GuiGraphics graphics, Minecraft minecraft, AircraftEntity aircraft,
            float partialTick, int centreX, int centreY) {
        if (!PodCamera.isActive()) {
            return false;
        }

        Font font = minecraft.font;
        Entity mark = PodCamera.designated();

        boresight(graphics, centreX, centreY, mark == null ? WHITE : DIM);

        if (mark != null) {
            held(graphics, centreX, centreY);
        }

        readout(graphics, font, aircraft, mark, partialTick);
        stores(graphics, font, aircraft);

        return true;
    }

    /**
     * ボアサイト。ポッドが見ている方向であり、マークが置かれる場所。
     *
     * <p>画面中央に描く。構造上そこがポッドの見ている方向だからだ——カメラはポッド自身の視線に沿って向けられて
     * いるので、映像の中央が視線であり、それを求めるための投影計算は要らない。
     */
    private static void boresight(GuiGraphics graphics, int x, int y, int colour) {
        graphics.fill(x - CROSS, y, x - GAP, y + 1, colour);
        graphics.fill(x + GAP, y, x + CROSS, y + 1, colour);
        graphics.fill(x, y - CROSS, x + 1, y - GAP, colour);
        graphics.fill(x, y + GAP, x + 1, y + CROSS, colour);
    }

    /**
     * 捕捉中のマークを囲む枠。
     *
     * <p>これも中央で、理由は同じ。ポッドは捕捉対象を追うので、指示した時点で映像はそれを中心に据え、枠の置き場所
     * は他に無い。完全な四角ではなく四隅として描くので、中の目標が見えたままになる。
     */
    private static void held(GuiGraphics graphics, int x, int y) {
        int arm = 4;

        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                int cornerX = x + sx * MARK;
                int cornerY = y + sy * MARK;

                graphics.fill(Math.min(cornerX, cornerX - sx * arm), cornerY,
                        Math.max(cornerX, cornerX - sx * arm), cornerY + 1, GREEN);
                graphics.fill(cornerX, Math.min(cornerY, cornerY - sy * arm),
                        cornerX + 1, Math.max(cornerY, cornerY - sy * arm), GREEN);
            }
        }
    }

    /**
     * 数値表示。ポッドが何を捕捉しているか、距離はどれだけか、主翼の兵装をそれに向けて投下できるか。
     */
    private static void readout(GuiGraphics graphics, Font font, AircraftEntity aircraft,
            @Nullable Entity mark, float partialTick) {
        int left = 8;
        int y = graphics.guiHeight() / 2 + 28;

        label(graphics, font, "POD", left, y);
        y += 11;

        if (mark == null) {
            line(graphics, font, "NO TARGET", left, y, AMBER);

            return;
        }

        Vec3 at = mark.position().add(0.0, mark.getBbHeight() * 0.5, 0.0);
        double range = aircraft.position().distanceTo(at);

        line(graphics, font, "DESIGNATED", left, y, GREEN);
        y += 10;
        line(graphics, font, String.format(Locale.ROOT, "RNG %.0f", range), left, y, GREEN);
        y += 10;
        line(graphics, font, String.format(Locale.ROOT, "ELEV %.0f", at.y), left, y, DIM);
        y += 10;

        // 対象が地面か、爆弾が空中にある間に走り去る物か。区別する価値はある。戦車に置いたマークは戦車を追う
        // が、斜面に置いたマークは見張る必要すら無い。
        line(graphics, font, mark instanceof com.ashvehicles.entity.DesignationEntity ? "POINT" : "MOVING",
                left, y, DIM);
    }

    /** 主翼に何があり、トリガーがそれに対して何かするか。 */
    private static void stores(GuiGraphics graphics, Font font, AircraftEntity aircraft) {
        WeaponMounts weapons = aircraft.getWeapons();
        WeaponDefinition selected = weapons.selectedWeapon();

        if (selected == null || weapons.selected() == null) {
            return;
        }

        int right = graphics.guiWidth() - 8;
        int y = graphics.guiHeight() / 2 + 28;
        String name = weapons.selected().getPath().replace('_', '-').toUpperCase(Locale.ROOT);
        int rounds = weapons.selectedAmmo();

        label(graphics, font, "STORES", right - font.width("STORES"), y);
        y += 11;

        String line = name + "  " + rounds;
        graphics.drawString(font, line, right - font.width(line), y, rounds > 0 ? GREEN : AMBER, true);
        y += 10;

        // ポッドを覗くパイロットが他に知る術の無い唯一の情報。今投下したら爆弾に誘導先があるか。何も捕捉せず
        // 投下すれば、翼はあるが指示の無い爆弾になり、無誘導弾とまったく同じ場所へ落ちる。
        boolean guided = PodCamera.designated() != null;
        String state = guided ? "GUIDED" : "BALLISTIC";
        graphics.drawString(font, state, right - font.width(state), y, guided ? GREEN : AMBER, true);
    }

    private static void label(GuiGraphics graphics, Font font, String text, int x, int y) {
        graphics.drawString(font, text, x, y, DIM, true);
    }

    private static void line(GuiGraphics graphics, Font font, String text, int x, int y, int colour) {
        graphics.drawString(font, text, x, y, colour, true);
    }
}
