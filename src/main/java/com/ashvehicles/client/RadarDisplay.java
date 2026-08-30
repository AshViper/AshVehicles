package com.ashvehicles.client;

import java.util.List;

import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.sensor.Contact;
import com.ashvehicles.sensor.Iff;
import com.ashvehicles.sensor.Threat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * 空に他に何がいるかを伝える2つの計器。レーダースコープと警戒受信機。
 *
 * <p><b>スコープは B スコープ</b>で、レーダーが正直に描ける画がこれだ。横軸は機首からの方位で、走査範囲の左右端
 * まで。縦軸は距離で、下端の機体からレーダーの到達距離である上端まで。地図ではない——正面200ブロックの接触と正面
 * 600ブロックの接触は、世界がどう並んでいようと上下に並ぶ——が、アンテナが知っていることそのものであり、正面の
 * 接触は機体がどちらへ旋回していても画面中央の真上に来る。
 *
 * <p><b>受信機は円</b>だ。答えるべき唯一のことが「どちらから」であり、前方の円錐ではなく空全体について一度に
 * 答えるからだ。描くのは方位と深刻度だけで、それが受信機の正直に言えることの全てだ。マークが中心に近いほど「注意
 * がどこまで進んだか」を表すのであって、距離を表すのではない。
 *
 * <p><b>接触の色は身元</b>だ。{@link Iff} 参照。味方は青、敵は琥珀、判定の付かない相手は計器の地の緑。チームを
 * 一つも作っていないワールドでは全部が最後のものになるので、画は IFF を入れる前と変わらない。受信機の側は色を持た
 * ない——味方の照射はそもそも {@link com.ashvehicles.sensor.Sensors} が鳴らさないので、ここに届く時点で全部が
 * 「知らせる価値のある」照射になっている。
 *
 * <p>どちらも最後の走査結果を描く。{@link RadarReadout} 参照。ファイルがレーダーを与えていない機体ではどちらも
 * 描かれない。それがレーダー無しの機体の在り方だ。
 */
public final class RadarDisplay {
    /** スコープの寸法（画面ピクセル）。接触2つを見分けられる幅で、それ以上は広げない。 */
    private static final int SCOPE_WIDTH = 104;
    private static final int SCOPE_HEIGHT = 68;
    /** 計器を寄せる画面端からの余白。 */
    private static final int EDGE = 8;
    /** スコープ上の距離環。レーダー到達距離に対する割合で指定する。 */
    private static final float[] RINGS = {0.05F, 0.25F, 0.5F};

    /** 受信機の環と、脅威が深刻化するにつれ乗る3つの環。 */
    private static final int RWR_RADIUS = 34;
    private static final float SEARCH_RING = 0.86F;
    private static final float LOCK_RING = 0.6F;
    private static final float MISSILE_RING = 0.34F;

    /** 警告が点滅する際の点灯tick数と消灯tick数。 */
    private static final int BLINK_TICKS = 4;
    /** スコープが高度差を示すのに必要な上下差（ブロック）。 */
    private static final float NOTABLE_CLIMB = 20.0F;

    public static void draw(GuiGraphics graphics, Font font, VehicleEntityBase vehicle) {
        VehicleChassis.Radar radar = vehicle.radar();

        // 枠は1つではなく2つ。レーダーを持たない機体でも他人のレーダーは聞こえるし、それを最も必要とするのは
        // まさにそういう機体だ。
        if (radar.fitted()) {
            drawScope(graphics, font, radar);
        }

        if (radar.warningRange() > 0.0F) {
            drawReceiver(graphics, font);
        }
    }

    /** 横軸が方位、縦軸が距離。アンテナが機体前方で見つけた物。 */
    private static void drawScope(GuiGraphics graphics, Font font, VehicleChassis.Radar radar) {
        int left = EDGE;
        int right = left + SCOPE_WIDTH;
        // 左辺の中ほど。スコープはボアサイトから横目で読む物であって、隅を覗き込む物ではない。この辺の下部は
        // ステータス列が専有している。
        int top = (graphics.guiHeight() - SCOPE_HEIGHT) / 2;
        int bottom = top + SCOPE_HEIGHT;
        int middle = (left + right) / 2;

        graphics.fill(left, top, right, bottom, AircraftHud.SHADOW);

        // 枠、中央のボアサイト、そして距離を読む環を1〜2本。
        graphics.fill(left, top, right, top + 1, AircraftHud.DIM);
        graphics.fill(left, bottom - 1, right, bottom, AircraftHud.DIM);
        graphics.fill(left, top, left + 1, bottom, AircraftHud.DIM);
        graphics.fill(right - 1, top, right, bottom, AircraftHud.DIM);
        graphics.fill(middle, top + 1, middle + 1, bottom - 1, AircraftHud.DIM);

        // 到達距離の1/4と1/2に環を置く。位置は接触と同じ圧縮スケールで決め、対応する距離をラベルにする——この種
        // のスケールでは、環の位置から距離を推測することはできないからだ。
        for (float ring : RINGS) {
            int y = bottom - 3 - Math.round(up(ring) * (SCOPE_HEIGHT - 6));

            graphics.fill(left + 1, y, right - 1, y + 1, AircraftHud.DIM);
            graphics.drawString(font, distance(radar.range() * ring), left + 3, y - 9, AircraftHud.DIM, false);
        }

        AircraftHud.label(graphics, font, "RDR", left + 3, top - 10);

        String reach = distance(radar.range());
        AircraftHud.label(graphics, font, reach, right - font.width(reach) - 3, top - 10);

        List<Contact> contacts = RadarReadout.contacts();

        if (contacts.isEmpty()) {
            String empty = "NO CONTACT";
            graphics.drawString(font, empty, middle - font.width(empty) / 2, top + SCOPE_HEIGHT / 2 - 4,
                    AircraftHud.DIM, false);

            return;
        }

        for (Contact contact : contacts) {
            plot(graphics, font, contact, radar, left, right, top, bottom);
        }
    }

    /** 接触1つを、横軸の方位と縦軸の距離の位置へ描く。 */
    private static void plot(GuiGraphics graphics, Font font, Contact contact,
            VehicleChassis.Radar radar, int left, int right, int top, int bottom) {
        float across = Mth.clamp(contact.bearing() / Math.max(radar.arc(), 1.0F), -1.0F, 1.0F);
        float out = up(Mth.clamp(contact.range() / Math.max(radar.range(), 1.0F), 0.0F, 1.0F));

        int middle = (left + right) / 2;
        int x = middle + Math.round(across * (SCOPE_WIDTH / 2.0F - 4.0F));
        int y = bottom - 3 - Math.round(out * (SCOPE_HEIGHT - 6));
        // 色は身元、括弧はロック。AircraftHud#contactColour 参照——HMD のキューと同じ規則で描く。
        int colour = AircraftHud.contactColour(contact.iff(), contact.locked());
        // 野原に立っている誰かより機体の方がパイロットの注意に値するので、より目立つマークで描く。
        int half = contact.aircraft() ? 2 : 1;

        graphics.fill(x - half, y - half, x + half, y + half, colour);

        if (contact.locked()) {
            graphics.fill(x - 4, y - 4, x + 4, y - 3, colour);
            graphics.fill(x - 4, y + 3, x + 4, y + 4, colour);
        }

        // 意味のある対象には上下を示す。数値は収まらないし読まれもしない。行動を変えるのは「どちら側にいるか」
        // の部分だ。
        if (contact.aircraft() && Math.abs(contact.altitude()) > NOTABLE_CLIMB) {
            String mark = contact.altitude() > 0.0F ? "+" : "-";

            graphics.drawString(font, mark, x + half + 1, y - 4, colour, false);
        }
    }

    /** 誰がどこからこちらを見ているか。 */
    private static void drawReceiver(GuiGraphics graphics, Font font) {
        // スコープの反対、右辺の中ほど。空に他に何がいるかを伝える2つの計器は、同じ高さでボアサイトを挟んで
        // 左右に置く。
        int centreX = graphics.guiWidth() - EDGE - RWR_RADIUS;
        int centreY = graphics.guiHeight() / 2;

        AircraftHud.circle(graphics, centreX, centreY, RWR_RADIUS, AircraftHud.DIM);
        AircraftHud.circle(graphics, centreX, centreY, Math.round(RWR_RADIUS * LOCK_RING), AircraftHud.DIM);

        // 機首方向の印。環の上下を悩まずに読めるようにするため。
        graphics.fill(centreX, centreY - RWR_RADIUS - 3, centreX + 1, centreY - RWR_RADIUS + 3, AircraftHud.DIM);
        graphics.fill(centreX - 1, centreY - 1, centreX + 1, centreY + 1, AircraftHud.DIM);

        List<Threat> threats = RadarReadout.threats();

        if (threats.isEmpty()) {
            return;
        }

        boolean lit = lit();

        for (Threat threat : threats) {
            float ring = switch (threat.kind()) {
                case SEARCH -> SEARCH_RING;
                case LOCK -> LOCK_RING;
                case MISSILE -> MISSILE_RING;
            };
            double angle = Math.toRadians(threat.bearing());
            int x = centreX + (int) Math.round(Math.sin(angle) * RWR_RADIUS * ring);
            int y = centreY - (int) Math.round(Math.cos(angle) * RWR_RADIUS * ring);

            // 捜索はその日の状況についての事実で、静かにそこに在る。ロックやミサイルはこの数秒についての事実で
            // あり、無視させない。
            if (threat.kind() == Threat.Kind.SEARCH) {
                graphics.fill(x - 1, y - 1, x + 2, y + 2, AircraftHud.DIM);
            } else if (lit) {
                graphics.fill(x - 2, y - 2, x + 3, y + 3, AircraftHud.WARNING);
            }
        }

        Threat.Kind worst = RadarReadout.worst();

        if (worst == Threat.Kind.SEARCH || !lit) {
            return;
        }

        String warning = worst == Threat.Kind.MISSILE ? "MISSILE" : "LOCKED";
        graphics.drawString(font, warning, centreX - font.width(warning) / 2, centreY + RWR_RADIUS + 4,
                AircraftHud.WARNING, true);
    }

    /**
     * ある距離がスコープの縦方向どこに来るか。スコープ高に対する割合。
     *
     * <p>単純な「距離÷到達距離」ではない。それでは数km 見えるレーダーは、知る価値のある物すべてを下端の数ピクセル
     * に積み上げてしまう。到達距離5km なら、500ブロック先——20秒以内に問題になるほど近い——の接触は縦の1/10の位置に
     * 来て、400ブロック先の接触と区別できない。
     *
     * <p>そこでスケールを圧縮する。画面の手前半分が到達距離の手前1/4を受け持ち、遠端はそれに合わせて圧縮される。
     * 距離環にラベルを付けているのはそのためだ。
     */
    private static float up(float fraction) {
        return (float) Math.sqrt(fraction);
    }

    /** パイロットが言う形式の距離表記。1km までは m、それ以降は km。 */
    private static String distance(float blocks) {
        return blocks < 1000.0F
                ? Math.round(blocks) + "m"
                : String.format("%.1fk", blocks / 1000.0F);
    }

    /** 点滅中の警告が今この瞬間に点灯しているか。 */
    private static boolean lit() {
        Minecraft minecraft = Minecraft.getInstance();

        return minecraft.level == null || (minecraft.level.getGameTime() / BLINK_TICKS) % 2 == 0;
    }

    private RadarDisplay() {
    }
}
