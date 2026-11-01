package com.ashvehicles.client;

import java.util.List;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.sensor.Contact;
import com.ashvehicles.sensor.Threat;
import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.weapon.WeaponMounts;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 計器盤の多機能表示器（MFD）。一人称で乗っている機体にだけ、その計器盤の面へ生きた画面を描く。
 *
 * <p>模型の画面はただのテクスチャで、どこに画面があるかを模型は知らない。だから位置は定義ファイルの
 * {@code camera.displays} が持つ（{@link VehicleChassis.Display}）。書かない機体には何も描かない。
 * 以前は目の前下方へ既定の3枚を置いていたが、画面の無い操縦席では宙に浮いた板にしかならなかった。
 *
 * <p>描くのは乗っている本人の一人称だけ。三人称や他人の機体の計器盤は小さすぎて読めず、描くだけ無駄。
 *
 * <p>1枚は {@value #PX} 画素幅の仮想の画面として組み、画素をブロックへ縮めて計器盤へ貼る。背景と線は
 * {@link RenderType#textBackground()} の四角、文字はフォントで、どちらも全光量。夜の操縦席で画面が
 * 暗くなっては計器にならない。
 */
public final class CockpitDisplays {
    /** 仮想の画面の横幅（画素）。 */
    static final int PX = 128;
    /** 縁の札の文字の大きさ。頁の文字より一回り小さい。 */
    private static final float LABEL = 0.75F;
    /** 札の並ぶ縁で、頁を内側へ寄せる幅（画素）。左右と上下。 */
    private static final float INSET_SIDE = 16.0F;
    private static final float INSET_END = 8.0F;

    private static final int BACKGROUND = 0xF0050A08;
    private static final int FRAME = 0xFF1E3A26;
    private static final int GREEN = AircraftHud.GREEN;
    private static final int DIM = AircraftHud.DIM;
    private static final int WARNING = AircraftHud.WARNING;
    private static final int AMBER = 0xFFE8B33B;
    private static final int CYAN = 0xFF4FC3F7;
    private static final int WHITE = 0xFFE8F0EA;
    private static final int SKY = 0xFF1C5C9C;
    private static final int GROUND = 0xFF6B4423;

    /** 計器盤の面から目の側へ浮かせる距離（ブロック）。模型の画面と深度で争わないため。 */
    private static final float LIFT = 0.02F;

    private static final int LIGHT = LightTexture.FULL_BRIGHT;

    /** 描いている1枚の状態。描画は主スレッドで1枚ずつなので静的に持つ。 */
    private static Matrix4f matrix;
    private static VertexConsumer quads;
    private static MultiBufferSource buffers;
    private static Font font;
    private static int heightPx;
    /** 重ねる層。後から描く物ほど目の側へ少しずつ寄せる。 */
    private static float layer;
    /** レーダー頁の表示距離。到達距離に掛ける割合で、RNG のボタンが変える。 */
    private static float rangeScale = 1.0F;

    private CockpitDisplays() {
    }

    /** 自分が一人称で乗っているなら、その機体の画面を全部描く。姿勢を掛ける前の、機体原点の座標系で呼ぶ。 */
    public static void draw(AircraftEntity aircraft, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null || minecraft.player.getVehicle() != aircraft
                || !minecraft.options.getCameraType().isFirstPerson()) {
            return;
        }

        List<VehicleChassis.Display> displays = aircraft.getStats().camera().displays().orElse(List.of());

        if (displays.isEmpty()) {
            return;
        }

        font = minecraft.font;
        buffers = bufferSource;

        Quaternionf attitude = aircraft.getAttitude(partialTick);
        CockpitControls.aim(aircraft, displays, attitude, partialTick);

        poseStack.pushPose();
        // 搭載物と同じ入り方。半回転の後は +X が右、+Z が機尾方向。
        poseStack.mulPose(attitude);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        Matrix4f body = poseStack.last().pose();

        for (int index = 0; index < displays.size(); index++) {
            VehicleChassis.Display display = displays.get(index);
            int height = heightOf(display);
            Matrix4f frame = frame(display, height);
            Matrix4f screen = new Matrix4f(body).mul(frame);
            List<CockpitControls.Button> buttons =
                    CockpitControls.layout(aircraft, index, display, frame, height);

            matrix = screen;
            heightPx = height;
            layer = 0.0F;

            fill(-2, -2, PX + 2, heightPx + 2, FRAME);
            fill(0, 0, PX, heightPx, BACKGROUND);

            // 頁は札の内側へ縮めて描く。札の並ぶ縁だけ空ける。
            float left = 0.0F;
            float right = 0.0F;
            float top = 0.0F;
            float bottom = 0.0F;

            for (CockpitControls.Button button : buttons) {
                switch (button.edge()) {
                    case LEFT -> left = INSET_SIDE;
                    case RIGHT -> right = INSET_SIDE;
                    case TOP -> top = INSET_END;
                    case BOTTOM -> bottom = INSET_END;
                }
            }

            float shrink = (PX - left - right) / PX;
            matrix = new Matrix4f(screen).translate(left, top, 0.0F).scale(shrink, shrink, 1.0F);
            heightPx = Math.max(16, Math.round((height - top - bottom) / shrink));
            rangeScale = CockpitControls.rangeScale(aircraft, index);

            switch (CockpitControls.page(aircraft, index, display)) {
                case "nav" -> nav(aircraft, attitude);
                case "radar" -> radar(aircraft);
                case "sms" -> sms(aircraft);
                case "eng" -> eng(aircraft);
                default -> pfd(aircraft, attitude);
            }

            matrix = screen;
            heightPx = height;
            layer();
            buttons(index, buttons);
            pointer(index);
        }

        poseStack.popPose();

        matrix = null;
        quads = null;
        buffers = null;
        font = null;
    }

    /** 画面の縦の画素数。横は常に {@link #PX}。 */
    static int heightOf(VehicleChassis.Display display) {
        return Math.max(16, Math.round(display.height() / (display.width() / PX)));
    }

    /**
     * 画面の画素（左上が原点、x 右・y 下）から、機体の描画座標系（姿勢と半回転の後、+X 右・+Z 機尾）への変換。
     * 板を描くのにも、視線を画素へ戻すのにも、ボタンの位置を画素へ写すのにもこれ1つを使う。
     */
    static Matrix4f frame(VehicleChassis.Display display, int height) {
        float scale = display.width() / PX;

        return new Matrix4f()
                .translate((float) display.pos().x, (float) display.pos().y, (float) -display.pos().z)
                .rotateY((float) Math.toRadians(-display.yaw()))
                .rotateX((float) Math.toRadians(-display.tilt()))
                .translate(0.0F, 0.0F, LIFT)
                .scale(scale, -scale, scale)
                .translate(-PX / 2.0F, -height / 2.0F, 0.0F);
    }

    // ---- ボタン ----

    /**
     * 縁のボタンの札と、見ているボタンの印。
     *
     * <p>札は実機の MFD と同じく、ボタンの隣の画面の縁に出す。点いている状態（映している頁、下りている脚）は
     * 反転させる。見ているボタンは模型の四角そのものを枠で囲む——指がどこに掛かっているかを、画面の中では
     * なくボタンの上で見せるためだ。
     */
    private static void buttons(int index, List<CockpitControls.Button> buttons) {
        for (int i = 0; i < buttons.size(); i++) {
            CockpitControls.Button button = buttons.get(i);
            boolean hovered = CockpitControls.hovered(index, i);
            boolean flashing = CockpitControls.flashing(index, i);

            if (hovered || flashing) {
                float r = 8.0F;
                int colour = flashing ? WHITE : AMBER;
                outline(Math.round(button.x() - r), Math.round(button.y() - r), Math.round(button.x() + r),
                        Math.round(button.y() + r), colour);
            }

            if (button.label().isEmpty()) {
                continue;
            }

            float width = font.width(button.label()) * LABEL;
            float height = 8.0F * LABEL;
            float x;
            float y;

            switch (button.edge()) {
                case TOP -> {
                    x = button.x() - width / 2.0F;
                    y = 1.0F;
                }
                case BOTTOM -> {
                    x = button.x() - width / 2.0F;
                    y = heightPx - height - 1.0F;
                }
                case LEFT -> {
                    x = 1.0F;
                    y = button.y() - height / 2.0F;
                }
                default -> {
                    x = PX - width - 1.0F;
                    y = button.y() - height / 2.0F;
                }
            }

            x = Mth.clamp(x, 0.0F, PX - width);
            y = Mth.clamp(y, 0.0F, heightPx - height);

            boolean inverse = button.lit() || flashing;
            int ink = inverse ? 0xFF051008 : hovered ? AMBER : GREEN;

            if (inverse) {
                fill(x - 1.0F, y - 1.0F, x + width + 1.0F, y + height + 1.0F, flashing ? WHITE : GREEN);
            } else if (hovered) {
                outline(Math.round(x - 1.0F), Math.round(y - 1.0F), Math.round(x + width + 1.0F),
                        Math.round(y + height + 1.0F), AMBER);
            }

            label(button.label(), x, y, ink);
        }
    }

    /** 視線の当たっている点に小さな十字。見回しているときだけ。 */
    private static void pointer(int index) {
        float[] at = CockpitControls.cursor(index);

        if (at == null) {
            return;
        }

        layer();
        fill(at[0] - 3.0F, at[1] - 0.5F, at[0] + 3.0F, at[1] + 0.5F, WHITE);
        fill(at[0] - 0.5F, at[1] - 3.0F, at[0] + 0.5F, at[1] + 3.0F, WHITE);
    }

    /** 札の小さな文字。 */
    private static void label(String text, float x, float y, int colour) {
        layer();
        Matrix4f at = new Matrix4f(matrix).translate(x, y, -layer).scale(LABEL, LABEL, 1.0F);
        font.drawInBatch(text, 0.0F, 0.0F, colour, false, at, buffers, Font.DisplayMode.POLYGON_OFFSET, 0, LIGHT);
    }

    // ---- 頁 ----

    /** 主飛行表示。姿勢球、左に速度、右に高度、上に方位、下に昇降率と G と推力。 */
    private static void pfd(AircraftEntity aircraft, Quaternionf attitude) {
        Vec3 velocity = aircraft.getVelocity();
        float pitch = -Attitude.elevation(attitude);
        float bank = Attitude.bank(attitude);
        int heading = heading(attitude);

        // 姿勢球。列ごとに水平線の高さを求めて空と地面を塗る。
        int left = 30;
        int right = PX - 30;
        int top = 14;
        int bottom = heightPx - 16;
        int cx = (left + right) / 2;
        int cy = (top + bottom) / 2;
        float perDegree = (bottom - top) / 50.0F;
        // 水平線は機体と逆へ傾く。画面の y は下向きなので、描くときはバンクの符号を返す。
        float tilt = -bank;
        float slope = (float) Math.tan(Math.toRadians(Mth.clamp(tilt, -80.0F, 80.0F)));
        boolean inverted = Math.abs(bank) > 90.0F;

        for (int x = left; x < right; x++) {
            float horizon = cy + pitch * perDegree * (inverted ? -1.0F : 1.0F)
                    + (inverted ? -1.0F : 1.0F) * slope * (x + 0.5F - cx);
            int split = Mth.clamp(Math.round(horizon), top, bottom);

            fill(x, top, x + 1, split, inverted ? GROUND : SKY);
            fill(x, split, x + 1, bottom, inverted ? SKY : GROUND);
        }

        layer();

        // ピッチの梯子。10度ごとに、バンクに合わせて傾けた短い線。
        double radians = Math.toRadians(tilt);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);

        for (int rung = -30; rung <= 30; rung += 10) {
            float offset = (pitch - rung) * perDegree;
            float half = rung == 0 ? 30.0F : 12.0F;
            // 水平線から offset だけ下（画面座標）へ、バンクの分だけ回した線分。
            float mx = cx - sin * offset;
            float my = cy + cos * offset;
            float ax = mx - cos * half;
            float ay = my - sin * half;
            float bx = mx + cos * half;
            float by = my + sin * half;

            if (inside(ax, ay, left, top, right, bottom) && inside(bx, by, left, top, right, bottom)) {
                line(ax, ay, bx, by, rung == 0 ? WHITE : 0xC0FFFFFF);
            }
        }

        // 自機の記号は画面に固定。
        fill(cx - 14, cy, cx - 5, cy + 1, AMBER);
        fill(cx + 5, cy, cx + 14, cy + 1, AMBER);
        fill(cx - 1, cy - 1, cx + 1, cy + 1, AMBER);

        layer();

        int kmh = Math.round((float) velocity.length() * 20.0F * 3.6F);
        int climb = Math.round((float) velocity.y * 20.0F);

        box(1, cy - 6, left - 2, cy + 6, String.valueOf(kmh), GREEN);
        text("SPD", 3, cy - 16, DIM);
        text("km/h", 3, cy + 8, DIM);
        box(right + 1, cy - 6, PX - 1, cy + 6, String.valueOf(Math.round(aircraft.getY())), GREEN);
        text("ALT", right + 3, cy - 16, DIM);
        text(String.format("%+d", climb), right + 3, cy + 8, climb < -15 ? WARNING : DIM);

        String head = String.format("%03d %s", heading, AircraftHud.cardinal(heading));
        centred(head, PX / 2, 3, GREEN);

        float g = aircraft.getLoadFactor(velocity);
        int thr = Math.round(aircraft.getThrottle() * 100.0F);

        text(String.format("%.1fG", g), 2, heightPx - 11, Math.abs(g) > 7.0F ? WARNING : GREEN);
        centred(String.format("BANK %d", Math.round(bank)), PX / 2, heightPx - 11, DIM);
        String power = (aircraft.isAfterburning() ? "AB " : "") + thr + "%";
        text(power, PX - 2 - font.width(power), heightPx - 11, aircraft.isAfterburning() ? AMBER : GREEN);
    }

    /** 航法。方位の目盛り、自機の位置、対地速度と燃料から出す残りの航続。 */
    private static void nav(AircraftEntity aircraft, Quaternionf attitude) {
        int heading = heading(attitude);
        int cx = PX / 2;
        int cy = heightPx / 2 + 6;
        int radius = Math.min(PX, heightPx) / 2 - 18;

        centred("NAV", cx, 3, DIM);
        centred(String.format("HDG %03d", heading), cx, 13, GREEN);

        // 機首上の方位環。30度ごとに目盛り、90度ごとに文字。
        for (int mark = 0; mark < 360; mark += 10) {
            double angle = Math.toRadians(mark - heading);
            float sx = (float) Math.sin(angle);
            float sy = (float) -Math.cos(angle);
            float inner = mark % 30 == 0 ? radius - 6 : radius - 3;

            line(cx + sx * inner, cy + sy * inner, cx + sx * radius, cy + sy * radius, DIM);

            if (mark % 90 == 0) {
                String letter = AircraftHud.cardinal(mark);
                centred(letter, Math.round(cx + sx * (radius - 13)), Math.round(cy + sy * (radius - 13)) - 4,
                        mark == 0 ? AMBER : GREEN);
            }
        }

        // 自機。
        line(cx, cy - 6, cx, cy + 6, WHITE);
        line(cx - 6, cy, cx + 6, cy, WHITE);
        // 機首方向の線。
        fill(cx, cy - radius, cx + 1, cy - radius + 4, AMBER);

        Vec3 at = aircraft.position();
        Vec3 velocity = aircraft.getVelocity();
        double groundKmh = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z) * 20.0 * 3.6;

        text(String.format("X %d", Math.round(at.x)), 2, heightPx - 21, GREEN);
        text(String.format("Z %d", Math.round(at.z)), 2, heightPx - 11, GREEN);
        String gs = String.format("GS %d", Math.round(groundKmh));
        text(gs, PX - 2 - font.width(gs), heightPx - 21, GREEN);
        String alt = String.format("ALT %d", Math.round(at.y));
        text(alt, PX - 2 - font.width(alt), heightPx - 11, GREEN);
    }

    /** レーダー。HUD のスコープと同じ方位×距離の画と、警戒受信機の向き。 */
    private static void radar(AircraftEntity aircraft) {
        VehicleChassis.Radar radar = aircraft.radar();

        if (!radar.fitted()) {
            centred("NO RADAR", PX / 2, heightPx / 2 - 4, DIM);

            return;
        }

        int left = 4;
        int right = PX - 4;
        int top = 14;
        int bottom = heightPx - 14;
        int cx = (left + right) / 2;

        text("RDR", 2, 3, DIM);
        // 表示距離は RNG のボタンで到達距離の 1・1/2・1/4。外の接触は端へ寄せずに描かない。
        float shown = Math.max(radar.range() * rangeScale, 1.0F);
        String reach = distance(shown);
        text(reach, PX - 2 - font.width(reach), 3, DIM);

        // 枠と距離の環（1/4・1/2・3/4）と、方位の目盛り。
        outline(left, top, right, bottom, DIM);
        fill(cx, top, cx + 1, bottom, 0x603BE86A);

        for (int quarter = 1; quarter < 4; quarter++) {
            int y = bottom - (bottom - top) * quarter / 4;
            fill(left + 1, y, right - 1, y + 1, 0x403BE86A);
        }

        layer();

        List<Contact> contacts = RadarReadout.contacts();
        float arc = Math.max(radar.arc(), 1.0F);

        for (Contact contact : contacts) {
            if (contact.range() > shown) {
                continue;
            }

            float across = Mth.clamp(contact.bearing() / arc, -1.0F, 1.0F);
            float out = Mth.clamp(contact.range() / shown, 0.0F, 1.0F);
            int x = cx + Math.round(across * ((right - left) / 2.0F - 4.0F));
            int y = bottom - 3 - Math.round(out * (bottom - top - 6));
            int colour = AircraftHud.contactColour(contact.iff(), contact.locked());

            if (contact.aircraft()) {
                fill(x - 2, y - 2, x + 2, y + 2, colour);
            } else {
                fill(x - 1, y - 1, x + 1, y + 1, colour);
            }

            if (contact.locked()) {
                outline(x - 5, y - 5, x + 5, y + 5, colour);
                text(String.format("%s %+d", distance(contact.range()), Math.round(contact.altitude())),
                        x + 7, y - 4, colour);
            }
        }

        if (contacts.isEmpty()) {
            centred("NO CONTACT", cx, (top + bottom) / 2 - 4, DIM);
        }

        // 下端は警戒受信機。脅威があれば最悪の物の向きと種類。
        List<Threat> threats = RadarReadout.threats();

        if (threats.isEmpty()) {
            text("RWR CLEAR", 2, heightPx - 11, DIM);
        } else {
            Threat worst = threats.get(0);
            text(String.format("%s %+d", worst.kind().name(), Math.round(worst.bearing())), 2, heightPx - 11,
                    WARNING);
        }

        String count = contacts.size() + " TGT";
        text(count, PX - 2 - font.width(count), heightPx - 11, GREEN);
    }

    /** 兵装。吊っている物を1行ずつ、選んでいる物を反転して。下に機関砲以外の消耗品。 */
    private static void sms(AircraftEntity aircraft) {
        WeaponMounts weapons = aircraft.getWeapons();
        ResourceLocation selected = weapons.selected();

        centred("SMS", PX / 2, 3, DIM);

        List<ResourceLocation> carried = weapons.carried();
        int y = 15;
        int rows = Math.max(1, (heightPx - 15 - 26) / 10);

        if (carried.isEmpty()) {
            centred("NO STORES", PX / 2, y + 10, DIM);
        }

        for (int i = 0; i < Math.min(carried.size(), rows); i++) {
            ResourceLocation weapon = carried.get(i);
            int ammo = weapons.ammoOf(weapon);
            boolean chosen = weapon.equals(selected);
            String name = shorten(weapon.getPath().toUpperCase(), 15);
            String count = String.valueOf(ammo);

            if (chosen) {
                fill(1, y - 1, PX - 1, y + 9, GREEN);
                layer();
            }

            int colour = chosen ? 0xFF051008 : ammo > 0 ? GREEN : DIM;

            text(name, 4, y, colour);
            text(count, PX - 4 - font.width(count), y, colour);
            y += 10;
        }

        fill(2, heightPx - 25, PX - 2, heightPx - 24, DIM);

        int flares = aircraft.getCountermeasures(true);
        int chaff = aircraft.getCountermeasures(false);

        text("FLR " + flares, 2, heightPx - 21, flares > 0 ? GREEN : WARNING);
        String ch = "CHF " + chaff;
        text(ch, PX - 2 - font.width(ch), heightPx - 21, chaff > 0 ? GREEN : WARNING);

        String bay = !aircraft.hasBay() ? "" : aircraft.isBayOpen() ? "BAY OPEN" : "BAY SHUT";
        text(bay, 2, heightPx - 11, aircraft.isBayOpen() ? AMBER : DIM);
        String fuel = String.format("FUEL %d%%", Math.round(aircraft.getFuelFraction() * 100.0F));
        text(fuel, PX - 2 - font.width(fuel), heightPx - 11,
                aircraft.getFuelFraction() < 0.2F ? WARNING : GREEN);
    }

    /** 機体の状態。推力・燃料・損傷の棒と、脚・フラップ・後燃器の札。 */
    private static void eng(AircraftEntity aircraft) {
        centred("ENG", PX / 2, 3, DIM);

        bar("THR", 15, aircraft.getThrottle(), aircraft.isAfterburning() ? AMBER : GREEN);
        float fuel = aircraft.getFuelFraction();
        bar("FUEL", 33, fuel, fuel < 0.2F ? WARNING : GREEN);
        float health = aircraft.getHealthFraction();
        bar("HP", 51, health, health < 0.3F ? WARNING : GREEN);

        int y = 71;
        flag("GEAR", aircraft.isGearDown(), 2, y);
        flag("FLAP", aircraft.isFlapsDown(), 44, y);
        flag("A/B", aircraft.isAfterburning(), 86, y);

        if (aircraft.isOutOfFuel()) {
            centred("FUEL OUT", PX / 2, heightPx - 11, WARNING);
        }
    }

    // ---- 部品 ----

    private static int heading(Quaternionf attitude) {
        return Math.floorMod(Math.round(Attitude.heading(attitude)) + 180, 360);
    }

    private static String distance(float blocks) {
        return blocks >= 1000.0F ? String.format("%.1fkm", blocks / 1000.0F) : Math.round(blocks) + "m";
    }

    private static String shorten(String text, int most) {
        return text.length() <= most ? text : text.substring(0, most);
    }

    private static boolean inside(float x, float y, int left, int top, int right, int bottom) {
        return x >= left && x <= right && y >= top && y <= bottom;
    }

    private static void bar(String label, int y, float fraction, int colour) {
        text(label, 2, y, DIM);
        int left = 30;
        int right = PX - 30;
        outline(left, y - 1, right, y + 9, DIM);
        fill(left + 1, y, left + 1 + Math.round((right - left - 2) * Mth.clamp(fraction, 0.0F, 1.0F)), y + 8,
                colour);
        String percent = Math.round(fraction * 100.0F) + "%";
        text(percent, PX - 2 - font.width(percent), y, colour);
    }

    private static void flag(String label, boolean on, int x, int y) {
        int colour = on ? GREEN : DIM;
        outline(x, y - 2, x + 40, y + 10, colour);
        centred(label + (on ? " ON" : ""), x + 20, y, colour);
    }

    private static void box(int left, int top, int right, int bottom, String value, int colour) {
        fill(left, top, right, bottom, 0xFF000000);
        outline(left, top, right, bottom, colour);
        layer();
        text(value, right - 2 - font.width(value), top + 2, colour);
    }

    private static void outline(int left, int top, int right, int bottom, int colour) {
        fill(left, top, right, top + 1, colour);
        fill(left, bottom - 1, right, bottom, colour);
        fill(left, top, left + 1, bottom, colour);
        fill(right - 1, top, right, bottom, colour);
    }

    /** 以後に描く物を目の側へ一段寄せる。 */
    private static void layer() {
        // 1 画素は 0.126 ブロック幅の画面で約 0.001 ブロック。これより薄く重ねると深度の精度が足りず、
        // 背景と線と文字が奪い合って点滅する。
        layer -= 1.5F;
    }

    private static void fill(float left, float top, float right, float bottom, int colour) {
        if (right <= left || bottom <= top) {
            return;
        }

        quad(left, top, left, bottom, right, bottom, right, top, colour);
    }

    /** 太さ1画素の線分。 */
    private static void line(float ax, float ay, float bx, float by, int colour) {
        float dx = bx - ax;
        float dy = by - ay;
        float length = (float) Math.sqrt(dx * dx + dy * dy);

        if (length < 1.0E-3F) {
            return;
        }

        float nx = -dy / length * 0.5F;
        float ny = dx / length * 0.5F;

        quad(ax - nx, ay - ny, bx - nx, by - ny, bx + nx, by + ny, ax + nx, ay + ny, colour);
    }

    private static void quad(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3,
            int colour) {
        // 画素の y が下向きなので、時計回りに並べると目の側が表になる。両面を出して向きを気にしない。
        //
        // バッファは四角ごとに取り直す。文字が別の描画型を求めると、即時描画の MultiBufferSource は前の型の
        // バッファを閉じる。握ったままのバッファに書くと "Not building!" でゲームが落ちる。
        quads = buffers.getBuffer(RenderType.textBackground());
        vertex(x0, y0, colour);
        vertex(x1, y1, colour);
        vertex(x2, y2, colour);
        vertex(x3, y3, colour);
        vertex(x3, y3, colour);
        vertex(x2, y2, colour);
        vertex(x1, y1, colour);
        vertex(x0, y0, colour);
        layer -= 0.02F;
    }

    private static void vertex(float x, float y, int colour) {
        quads.addVertex(matrix, x, y, -layer).setColor(colour).setLight(LIGHT);
    }

    private static void text(String text, int x, int y, int colour) {
        if (text.isEmpty()) {
            return;
        }

        layer();
        Matrix4f at = new Matrix4f(matrix).translate(0.0F, 0.0F, -layer);
        font.drawInBatch(text, x, y, colour, false, at, buffers, Font.DisplayMode.POLYGON_OFFSET, 0, LIGHT);
    }

    private static void centred(String text, int x, int y, int colour) {
        text(text, x - font.width(text) / 2, y, colour);
    }
}
