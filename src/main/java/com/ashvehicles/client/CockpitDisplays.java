package com.ashvehicles.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.DesignationEntity;
import com.ashvehicles.client.ghost.GhostRenderDispatcher;
import com.ashvehicles.item.HmdItem;
import com.ashvehicles.sensor.Contact;
import com.ashvehicles.sensor.Iff;
import com.ashvehicles.sensor.Threat;
import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.vehicle.VehicleShape;
import com.ashvehicles.weapon.EquipmentDefinition;
import com.ashvehicles.weapon.WeaponDefinition;
import com.ashvehicles.weapon.WeaponMounts;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;

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

    /** 描いている画面の番号と、頁を札の内側へ縮めた変換。TGP 頁が映像の位置を画面の画素で控えるため。 */
    private static int drawing;
    private static float pageLeft;
    private static float pageTop;
    private static float pageShrink = 1.0F;
    /** 画面ごとの、ポッド映像の四角（画面の画素）と、そこに映っている映像の割合。見て押すと振る先。 */
    private static float[][] videos = new float[0][];

    /** Iris の下で、遅いステージから描いている最中。{@link CockpitLatePass} 参照。 */
    static boolean late;

    private CockpitDisplays() {
    }

    /**
     * 模型と一緒には描かず、{@link CockpitLatePass} に任せるか。シェーダーパックはエンティティの段で渡した物を
     * 自分の合成パスへ通すので、全光量の画面と文字がパックの照明と影で暗く潰れ、半透明の板は消える。
     */
    private static boolean deferred() {
        return GhostRenderDispatcher.shadersInUse() && !late;
    }

    /**
     * TGP 頁の映像の上を見て押した。その点が中心へ来るようにポッドを振る。映像の外なら false。
     *
     * @param x 画面の画素（{@link CockpitControls#cursor} と同じ）
     */
    static boolean slewTo(int display, float x, float y) {
        if (display >= videos.length || videos[display] == null) {
            return false;
        }

        float[] video = videos[display];

        if (x < video[0] || x > video[2] || y < video[1] || y > video[3]) {
            return false;
        }

        // 映像の四角の中で -1〜1、そこに映っている割合を掛けて、半視野の角度へ。
        float sx = ((x - video[0]) / (video[2] - video[0]) * 2.0F - 1.0F) * video[4];
        float sy = ((y - video[1]) / (video[3] - video[1]) * 2.0F - 1.0F) * video[5];
        float half = PodFeed.field() / 2.0F;
        float halfY = (float) Math.toDegrees(Math.atan(Math.tan(Math.toRadians(half))
                * PodFeed.HEIGHT / PodFeed.WIDTH));
        PodCamera.turn(sx * half, sy * halfY);

        return true;
    }

    /** 自分が一人称で乗っているなら、その機体の画面を全部描く。姿勢を掛ける前の、機体原点の座標系で呼ぶ。 */
    public static void draw(AircraftEntity aircraft, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null || minecraft.player.getVehicle() != aircraft
                || !minecraft.options.getCameraType().isFirstPerson() || deferred()) {
            return;
        }

        List<VehicleChassis.Display> displays = GenericCockpit.displays(aircraft);

        if (displays.isEmpty()) {
            return;
        }

        font = minecraft.font;
        buffers = bufferSource;

        Quaternionf attitude = aircraft.getAttitude(partialTick);
        FuelWatch.update(aircraft);
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
            drawing = index;
            pageLeft = left;
            pageTop = top;
            pageShrink = shrink;

            if (videos.length < displays.size()) {
                videos = Arrays.copyOf(videos, displays.size());
            }

            videos[index] = null;

            switch (CockpitControls.page(aircraft, index, display)) {
                case "nav" -> nav(aircraft, attitude);
                case "radar" -> radar(aircraft);
                case "sms" -> sms(aircraft);
                case "eng" -> eng(aircraft);
                case "plan" -> plan(aircraft, index, partialTick);
                case "iff" -> iff(aircraft);
                case "rwr" -> rwr(aircraft);
                case "tpod" -> tpod(aircraft);
                case "map" -> map(aircraft, attitude);
                default -> pfd(aircraft, attitude);
            }

            // 帰投燃料を切った直後は、どの頁を映していても上に帯を出す。
            if (FuelWatch.justCrossed() && blink()) {
                layer();
                fill(PX / 2 - 22, 1, PX / 2 + 22, 12, WARNING);
                layer();
                centred("BINGO", PX / 2, 3, 0xFF051008);
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

    // ---- HUD ----

    /** HUD のガラス板の大きさ（ブロック）。実機のコンバイナーと同じく、目の前で視角 20 度余り。 */
    private static final float HUD_WIDTH = 0.24F;
    private static final float HUD_HEIGHT = 0.2F;
    /** 板を置く場所。目（{@code camera.cockpit}）から前へ、そして少し下へ。 */
    private static final float HUD_AHEAD = 0.55F;
    private static final float HUD_DROP = 0.05F;
    /** ガラスの色。透けて外が見える程度にだけ緑が乗る。 */
    private static final int HUD_TINT = 0x183BE86A;
    private static final int HUD_EDGE = 0x603BE86A;

    /** 目の位置を原点に、機体の描画座標系からガラスの画素へ戻す変換。描いている間だけ持つ。 */
    private static Matrix4f toGlass;

    /**
     * HUD。MFD と同じく、一人称で乗っている機体のコクピットの中にガラス板を1枚置き、その上へ描く。
     *
     * <p>HMD を被っている者には描かない。あちらは同じ物を全部頭に付けて画面に出している（{@code AircraftHud}）ので、
     * 板にも描けば重なって二重に見える。
     *
     * <p>板は模型の一部で、首を振れば視界の外へ出る。照準・ボアサイト・飛行経路マーカーは実機の HUD と同じく
     * コリメートされている——板の上の点ではなく「目からその方向へ伸ばした線が板を通る所」に描くので、窓の外の
     * 物と重なって見え、頭を動かしても外とずれない。数字と警告と着弾表示は板に貼り付いた文字。
     *
     * <p>板の位置は定義ファイルに書かない。目（{@code camera.cockpit}）の前に既定の距離で置く。どの機体でも
     * 目の前には風防があるので、模型を測らずに全機へ同じ規則で置ける。
     */
    public static void drawHud(AircraftEntity aircraft, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null || minecraft.player.getVehicle() != aircraft
                || !minecraft.options.getCameraType().isFirstPerson() || HmdItem.isWorn(minecraft.player)
                || deferred()) {
            return;
        }

        Vec3 eye = aircraft.getStats().camera().cockpit();
        VehicleChassis.Display glass = new VehicleChassis.Display(
                new Vec3(eye.x, eye.y - HUD_DROP, eye.z + HUD_AHEAD), HUD_WIDTH, HUD_HEIGHT, 0.0F, 0.0F,
                "hud", List.of());
        int height = heightOf(glass);
        Matrix4f frame = frame(glass, height);
        Quaternionf attitude = aircraft.getAttitude(partialTick);

        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 from = camera.getPosition();
        Vec3 at = aircraft.getPosition(partialTick);
        toGlass = new Matrix4f()
                .translate((float) (at.x - from.x), (float) (at.y - from.y), (float) (at.z - from.z))
                .rotate(attitude)
                .rotateY((float) Math.PI)
                .mul(frame)
                .invert();

        font = minecraft.font;
        buffers = bufferSource;

        poseStack.pushPose();
        poseStack.mulPose(attitude);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        matrix = new Matrix4f(poseStack.last().pose()).mul(frame);
        heightPx = height;
        layer = 0.0F;

        fill(0, 0, PX, heightPx, HUD_TINT);
        outline(0, 0, PX, heightPx, HUD_EDGE);
        hud(aircraft, attitude, partialTick, from);

        poseStack.popPose();

        toGlass = null;
        matrix = null;
        quads = null;
        buffers = null;
        font = null;
    }

    /** HUD の中身。 */
    private static void hud(AircraftEntity aircraft, Quaternionf attitude, float partialTick, Vec3 camera) {
        Vec3 velocity = aircraft.getVelocity();
        double speed = velocity.length();

        // コリメートされた印。外の方向に重なる物から先に。
        float[] nose = through(Attitude.nose(attitude));

        if (nose != null) {
            line(nose[0] - 6, nose[1], nose[0] - 2, nose[1], GREEN);
            line(nose[0] + 2, nose[1], nose[0] + 6, nose[1], GREEN);
            line(nose[0], nose[1] - 1, nose[0], nose[1] + 1, GREEN);
        }

        if (speed >= 0.05) {
            float[] path = through(velocity.scale(1.0 / speed));

            if (path != null) {
                ring(path[0], path[1], 3.0F, GREEN);
                line(path[0] - 7, path[1], path[0] - 3, path[1], GREEN);
                line(path[0] + 3, path[1], path[0] + 7, path[1], GREEN);
                line(path[0], path[1] - 3, path[0], path[1] - 6, GREEN);
            }
        }

        WeaponMounts weapons = aircraft.getWeapons();
        WeaponDefinition weapon = weapons.selectedWeapon();

        if (weapon != null && GunSight.aims(weapon)) {
            GunSight.Solution sight = GunSight.solve(aircraft);

            if (sight != null) {
                Vec3 pipper = sight.bore().muzzle(partialTick)
                        .add(sight.bore().direction(partialTick).scale(sight.pipperRange()))
                        .add(sight.pipperDrop());
                float[] spot = through(pipper.subtract(camera));

                if (spot != null) {
                    int colour = weapons.selectedAmmo() > 0 ? GREEN : WARNING;

                    ring(spot[0], spot[1], 6.0F, colour);
                    fill(spot[0] - 0.5F, spot[1] - 0.5F, spot[0] + 0.5F, spot[1] + 0.5F, colour);
                }
            }
        }

        if (weapon != null && weapon.isDropped() && weapons.selectedAmmo() > 0) {
            BombSight.Solution fall = BombSight.solve(aircraft, weapon);
            float[] spot = fall == null ? null : through(fall.point().subtract(camera));

            if (spot != null) {
                int colour = fall.estimated() ? DIM : GREEN;

                ring(spot[0], spot[1], 4.0F, colour);
                line(spot[0] - 7, spot[1], spot[0] - 4, spot[1], colour);
                line(spot[0] + 4, spot[1], spot[0] + 7, spot[1], colour);
            }
        }

        // 板に貼り付いた文字。左に速度、右に高度、上に方位、下に構えと兵装。
        int kmh = (int) Math.round(speed * 20.0 * 3.6);
        int climb = (int) Math.round(velocity.y * 20.0);
        int middle = heightPx / 2;

        hudBox(String.valueOf(kmh), 3, middle - 6, false);
        hudBox(String.valueOf(Math.round(aircraft.getY())), PX - 3, middle - 6, true);

        float load = aircraft.getLoadFactor(velocity);

        label(String.format("α %.0f", AircraftHud.angleOfAttack(attitude, velocity, speed)), 3, middle + 9, GREEN);
        label(String.format("G %.1f", load), 3, middle + 17, Math.abs(load) > 7.0F ? WARNING : GREEN);

        String vs = String.format("VS %+d", climb);
        String fuel = String.format("F %d%%", Math.round(aircraft.getFuelFraction() * 100.0F));

        label(vs, PX - 3 - font.width(vs) * LABEL, middle + 9, climb < -15 ? WARNING : GREEN);
        label(fuel, PX - 3 - font.width(fuel) * LABEL, middle + 17, GREEN);

        centred(String.format("%03d", heading(attitude)), PX / 2, 3, GREEN);

        label(AircraftHud.mode(aircraft), 3, heightPx - 9, GREEN);

        if (weapon != null) {
            String name = shorten(AircraftHud.name(weapons.selected()), 14) + " " + weapons.selectedAmmo();

            label(name, PX - 3 - font.width(name) * LABEL, heightPx - 9,
                    weapons.selectedAmmo() > 0 ? GREEN : WARNING);
        }

        String caution = AircraftHud.caution(aircraft, attitude, velocity, speed);

        if (caution != null) {
            centred(caution, PX / 2, 14, WARNING);
        }

        // 直近の着弾。画面の右上と同じ行を、板の下の方へ新しい順に積む。
        float[] row = {heightPx - 20.0F};

        HitCallout.forEachLive((text, colour) -> {
            if (row[0] > middle + 26) {
                label(text, PX / 2.0F - font.width(text) * LABEL / 2.0F, row[0], colour);
                row[0] -= 7.0F;
            }
        });
    }

    /** 目からこの方向へ伸ばした線がガラスを通る画素。通らない（後ろ向き・板の外）なら null。 */
    private static float[] through(Vec3 direction) {
        Vector3f origin = toGlass.transformPosition(new Vector3f());
        Vector3f along = toGlass.transformDirection(
                new Vector3f((float) direction.x, (float) direction.y, (float) direction.z));

        if (Math.abs(along.z) < 1.0E-7F) {
            return null;
        }

        float t = -origin.z / along.z;
        float x = origin.x + along.x * t;
        float y = origin.y + along.y * t;

        if (t <= 0.0F || x < 2.0F || x > PX - 2.0F || y < 2.0F || y > heightPx - 2.0F) {
            return null;
        }

        return new float[] {x, y};
    }

    /** 細い線で描く環。 */
    private static void ring(float x, float y, float radius, int colour) {
        int steps = 16;

        for (int i = 0; i < steps; i++) {
            double a = Math.PI * 2.0 * i / steps;
            double b = Math.PI * 2.0 * (i + 1) / steps;

            line(x + (float) (Math.cos(a) * radius), y + (float) (Math.sin(a) * radius),
                    x + (float) (Math.cos(b) * radius), y + (float) (Math.sin(b) * radius), colour);
        }
    }

    /** 数字の箱。{@code fromRight} なら x は右端。 */
    private static void hudBox(String value, int x, int y, boolean fromRight) {
        int width = 26;
        int left = fromRight ? x - width : x;

        outline(left, y, left + width, y + 12, GREEN);
        text(value, left + width - 3 - font.width(value), y + 2, GREEN);
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
            // IFF 頁の絞りで隠した所属は描かない。ロックしている物だけは隠さない。
            if (contact.range() > shown || !contact.locked() && !CockpitControls.shows(contact.iff())) {
                continue;
            }

            float across = Mth.clamp(contact.bearing() / arc, -1.0F, 1.0F);
            float out = Mth.clamp(contact.range() / shown, 0.0F, 1.0F);
            int x = cx + Math.round(across * ((right - left) / 2.0F - 4.0F));
            int y = bottom - 3 - Math.round(out * (bottom - top - 6));
            int colour = AircraftHud.contactColour(CockpitControls.identify(contact.iff()), contact.locked());

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

    /**
     * エンジンと燃料。N1 と EGT の円形計器、BINGO の印を付けた燃料の棒、今の減り方での残り時間、脚とフラップ。
     *
     * <p>飛行モデルは回転数も排気温度も持たないので、N1 と EGT は推力と後燃器と燃料から組んだ計器の上の値。
     * 回り上がりと冷え方に遅れを付けて、スロットルを動かしたときに計器らしく追い付かせる。
     */
    private static void eng(AircraftEntity aircraft) {
        centred("ENG", PX / 2, 3, DIM);
        spool(aircraft);

        int radius = Mth.clamp((heightPx - 62) / 2, 9, 18);
        int cy = 15 + radius;
        int egtColour = egt > 850.0F ? WARNING : egt > 780.0F ? AMBER : GREEN;

        dial("N1", PX / 4, cy, radius, n1, String.format("%.0f", n1 * 100.0F), n1 > 1.0F ? AMBER : GREEN);
        dial("EGT", PX * 3 / 4, cy, radius, egt / 950.0F, String.format("%.0f", egt), egtColour);

        float fuel = aircraft.getFuelFraction();
        float bingo = FuelWatch.bingo();
        boolean low = FuelWatch.belowBingo();
        int y = cy + radius + 13;

        bar("FUEL", y, fuel, aircraft.isOutOfFuel() || low ? WARNING : fuel < bingo + 0.1F ? AMBER : GREEN);
        layer();
        // 棒の上の BINGO の印。bar() と同じ左右の端。
        int mark = 31 + Math.round((PX - 62) * bingo);
        fill(mark, y - 3, mark + 1, y + 11, AMBER);

        y += 12;
        text(String.format("BNG %d%%", Math.round(bingo * 100.0F)), 2, y, low ? WARNING : AMBER);
        float left = FuelWatch.secondsLeft(fuel);
        String endurance = "END " + (left < 0.0F ? "--:--" : clock(left));
        text(endurance, PX - 2 - font.width(endurance), y, low ? WARNING : GREEN);

        y += 13;

        if (y + 10 < heightPx - 12) {
            flag("GEAR", aircraft.isGearDown(), 2, y);
            flag("FLAP", aircraft.isFlapsDown(), 44, y);
            flag("A/B", aircraft.isAfterburning(), 86, y);
        }

        if (aircraft.isOutOfFuel()) {
            centred("FUEL OUT", PX / 2, heightPx - 11, WARNING);
        } else if (low && blink()) {
            centred("BINGO FUEL", PX / 2, heightPx - 11, WARNING);
        }
    }

    // ENG 頁の計器の値。描画のたびに1 tick ぶん目標へ寄せる。
    private static float n1;
    private static float egt = 15.0F;
    private static long spooledAt = Long.MIN_VALUE;

    private static void spool(AircraftEntity aircraft) {
        Minecraft minecraft = Minecraft.getInstance();
        long now = minecraft.level == null ? 0L : minecraft.level.getGameTime();

        if (now == spooledAt) {
            return;
        }

        long ticks = spooledAt == Long.MIN_VALUE ? 1000L : Math.min(now - spooledAt, 1000L);
        spooledAt = now;

        boolean running = aircraft.hasFuel() && !aircraft.isWrecked();
        float throttle = aircraft.getThrottle();
        float wantN1 = !running ? 0.0F : aircraft.isAfterburning() ? 1.02F : 0.62F + 0.38F * throttle;
        float wantEgt = !running ? 15.0F : 420.0F + 380.0F * throttle + (aircraft.isAfterburning() ? 120.0F : 0.0F);

        n1 += (wantN1 - n1) * (1.0F - (float) Math.pow(1.0F - 0.08F, ticks));
        egt += (wantEgt - egt) * (1.0F - (float) Math.pow(1.0F - 0.05F, ticks));
    }

    /**
     * 上から見た機体の輪郭。当たり判定の箱をそれぞれ真上から潰した凸包で、機首が上。
     *
     * <p>模型ではなく箱から組む。画面は計器盤の面に貼る板で、そこへ模型をもう1機描くのは重いし、損傷を読む
     * 計器としては被弾を受ける形そのもの（箱）のほうが正しい。開いた倉の扉・ランプ・可変翼は今の角度で振れて
     * 描かれる。パイロンは点で、吊っていれば塗り、選んでいる兵装なら琥珀。輪郭の色は機体の耐久。
     */
    private static void plan(AircraftEntity aircraft, int index, float partialTick) {
        centred("PLAN", PX / 2, 3, DIM);

        List<VehicleShape.Box> boxes = aircraft.getShape().boxes();

        if (boxes.isEmpty()) {
            centred("NO OUTLINE", PX / 2, heightPx / 2 - 4, DIM);

            return;
        }

        // 平面の点は {右, 前}（ブロック）。箱ファイルの +x は機体の右。
        List<float[][]> hulls = new ArrayList<>();
        float half = 0.5F;
        float front = -Float.MAX_VALUE;
        float back = Float.MAX_VALUE;

        for (VehicleShape.Box box : boxes) {
            Vec3 swing = aircraft.swingOf(box, partialTick);
            Vec3 offset = AircraftEntity.swung(box, swing);
            // 描く側（VehicleShapeRenderer）と同じ順。この回転は +X が左の座標系で効く。
            Quaternionf turn = swing.lengthSqr() == 0.0 ? box.orientation()
                    : new Quaternionf(AircraftEntity.swingOf(swing)).mul(box.orientation());
            Vec3 size = box.size().scale(0.5);
            float[][] corners = new float[8][];

            for (int c = 0; c < 8; c++) {
                Vector3f r = turn.transform(new Vector3f((c & 1) == 0 ? (float) -size.x : (float) size.x,
                        (c & 2) == 0 ? (float) -size.y : (float) size.y,
                        (c & 4) == 0 ? (float) -size.z : (float) size.z));
                float x = (float) offset.x - r.x;
                float z = (float) offset.z + r.z;

                corners[c] = new float[] {x, z};
                half = Math.max(half, Math.abs(x));
                front = Math.max(front, z);
                back = Math.min(back, z);
            }

            hulls.add(hull(corners));
        }

        List<AircraftDefinition.Hardpoint> hardpoints = aircraft.getStats().hardpoints();

        for (AircraftDefinition.Hardpoint hardpoint : hardpoints) {
            half = Math.max(half, (float) Math.abs(hardpoint.pos().x));
            front = Math.max(front, (float) hardpoint.pos().z);
            back = Math.min(back, (float) hardpoint.pos().z);
        }

        // 吊っている兵装の種類。種類ごとに色を分け、下に凡例を出す。
        WeaponMounts weapons = aircraft.getWeapons();
        ResourceLocation selected = weapons.selected();
        List<ResourceLocation> kinds = new ArrayList<>();

        for (int slot = 0; slot < hardpoints.size(); slot++) {
            ResourceLocation weapon = weapons.weaponAt(slot);

            if (weapon != null && !kinds.contains(weapon)) {
                kinds.add(weapon);
            }
        }

        int legend = Math.min(kinds.size(), STORE_COLOURS.length);
        int left = 4;
        int right = PX - 4;
        int top = 14;
        int bottom = heightPx - 14 - legend * 8;
        float cx = (left + right) / 2.0F;
        float cy = (top + bottom) / 2.0F;
        float middle = (front + back) / 2.0F;
        float scale = Math.min((right - left) / (2.0F * half), (bottom - top) / Math.max(front - back, 0.5F));

        float health = aircraft.getHealthFraction();
        int colour = health < 0.3F ? WARNING : health < 0.6F ? AMBER : GREEN;

        int w = right - left;
        int h = Math.max(bottom - top, 1);
        int shape = Arrays.deepHashCode(hulls.toArray(new float[0][][]));
        Outline cached = OUTLINES.get(index);

        if (cached == null || cached.aircraft() != aircraft.getId() || cached.shape() != shape
                || cached.width() != w || cached.height() != h) {
            cached = new Outline(aircraft.getId(), shape, w, h, outline(hulls, w, h, left, top, cx, cy, middle,
                    scale));
            OUTLINES.put(index, cached);
        }

        int[] runs = cached.runs();

        for (int i = 0; i < runs.length; i += 3) {
            fill(left + runs[i], top + runs[i + 1], left + runs[i + 2], top + runs[i + 1] + 1, colour);
        }

        layer();

        drawStores(aircraft, hardpoints, weapons, selected, kinds, legend, cx, cy, middle, scale, bottom, health,
                colour);
    }

    /**
     * 上面図の外周を、行ごとの横の連なり {x0, y, x1} の並びとして。
     *
     * <p>箱の外形は重なり合うので、画素へ塗ってから外周の画素だけを残す。内側の継ぎ目は出ない。画素ごとに全部の
     * 箱を訊くので重く、結果は形と大きさが変わるまで {@link #OUTLINES} に取っておく。可動部が動いている間だけ
     * 毎フレーム組み直す。
     */
    private static int[] outline(List<float[][]> hulls, int w, int h, int left, int top, float cx, float cy,
            float middle, float scale) {
        boolean[] solid = new boolean[w * h];

        for (int py = 0; py < h; py++) {
            float z = middle - (top + py + 0.5F - cy) / scale;

            for (int px = 0; px < w; px++) {
                float x = (left + px + 0.5F - cx) / scale;

                for (float[][] hull : hulls) {
                    if (contains(hull, x, z)) {
                        solid[py * w + px] = true;
                        break;
                    }
                }
            }
        }

        List<Integer> runs = new ArrayList<>();

        for (int py = 0; py < h; py++) {
            int run = -1;

            for (int px = 0; px <= w; px++) {
                boolean edge = px < w && solid[py * w + px]
                        && (px == 0 || px == w - 1 || py == 0 || py == h - 1 || !solid[py * w + px - 1]
                                || !solid[py * w + px + 1] || !solid[(py - 1) * w + px] || !solid[(py + 1) * w + px]);

                if (edge && run < 0) {
                    run = px;
                } else if (!edge && run >= 0) {
                    runs.add(run);
                    runs.add(py);
                    runs.add(px);
                    run = -1;
                }
            }
        }

        return runs.stream().mapToInt(Integer::intValue).toArray();
    }

    /** 上面図の外周の取り置き。画面ごとに1つ。 */
    private record Outline(int aircraft, int shape, int width, int height, int[] runs) {
    }

    private static final Map<Integer, Outline> OUTLINES = new HashMap<>();

    /** 上面図のパイロンの弾と凡例と下の行。 */
    private static void drawStores(AircraftEntity aircraft, List<AircraftDefinition.Hardpoint> hardpoints,
            WeaponMounts weapons, ResourceLocation selected, List<ResourceLocation> kinds, int legend, float cx,
            float cy, float middle, float scale, int bottom, float health, int colour) {
        int loaded = 0;

        for (int slot = 0; slot < hardpoints.size(); slot++) {
            Vec3 at = hardpoints.get(slot).pos();
            float x = cx + (float) at.x * scale;
            float y = cy - ((float) at.z - middle) * scale;
            ResourceLocation weapon = weapons.weaponAt(slot);

            if (weapon == null) {
                outline(Math.round(x - 1), Math.round(y - 1), Math.round(x + 2), Math.round(y + 2), DIM);
                continue;
            }

            loaded++;
            int kind = kinds.indexOf(weapon);
            int ink = kind < STORE_COLOURS.length ? STORE_COLOURS[kind] : WHITE;

            // 機首を上にした細長い弾の形。選んでいる兵装は枠で囲む。
            fill(x - 0.5F, y - 4.0F, x + 0.5F, y + 4.0F, ink);
            fill(x - 1.5F, y + 2.0F, x + 1.5F, y + 3.0F, ink);

            if (weapon.equals(selected)) {
                outline(Math.round(x - 3), Math.round(y - 6), Math.round(x + 4), Math.round(y + 6), AMBER);
            }
        }

        for (int i = 0; i < legend; i++) {
            ResourceLocation weapon = kinds.get(i);
            int count = 0;

            for (int slot = 0; slot < hardpoints.size(); slot++) {
                if (weapon.equals(weapons.weaponAt(slot))) {
                    count += weapons.ammoAt(slot);
                }
            }

            float y = bottom + 2 + i * 8;
            fill(4, y + 1, 9, y + 5, STORE_COLOURS[i]);
            label(shorten(weapon.getPath().toUpperCase(), 18) + " x" + count, 12, y,
                    weapon.equals(selected) ? AMBER : STORE_COLOURS[i]);
        }

        text(String.format("HP %d%%", Math.round(health * 100.0F)), 2, heightPx - 11, colour);
        String stores = loaded + "/" + hardpoints.size() + " STA";
        text(stores, PX - 2 - font.width(stores), heightPx - 11, GREEN);
    }

    /** 上面図で兵装の種類を分ける色。これより多い種類は白で、凡例には出さない。 */
    private static final int[] STORE_COLOURS = {CYAN, WHITE, 0xFFC792EA, 0xFFFF8A65};

    /** 反時計回りの凸包の内側か。 */
    private static boolean contains(float[][] hull, float x, float z) {
        if (hull.length < 3) {
            return false;
        }

        float[] p = {x, z};

        for (int i = 0; i < hull.length; i++) {
            if (cross(hull[i], hull[(i + 1) % hull.length], p) < 0.0F) {
                return false;
            }
        }

        return true;
    }

    /**
     * IFF の管理。自分の名乗る所属、質問機の入切、レーダー頁の絞り、今の接触を所属ごとに数えて近い順に。
     *
     * <p>ここで変えるのは計器の表示だけ。シーカーは今も味方をロックできる（{@code iff-is-display-only-by-choice}）。
     */
    private static void iff(AircraftEntity aircraft) {
        centred("IFF", PX / 2, 3, DIM);

        Team own = aircraft.getTeam();

        if (own == null && Minecraft.getInstance().player != null) {
            own = Minecraft.getInstance().player.getTeam();
        }

        text("OWN", 2, 15, DIM);
        text(own == null ? "NO TEAM" : shorten(own.getName().toUpperCase(), 14), 26, 15, own == null ? AMBER : CYAN);

        boolean on = CockpitControls.interrogating();
        text(on ? "INTG ON" : "INTG OFF", 2, 25, on ? GREEN : AMBER);
        String shown = "SHOW " + CockpitControls.filterName();
        text(shown, PX - 2 - font.width(shown), 25, CockpitControls.filterName().equals("ALL") ? GREEN : AMBER);

        List<Contact> contacts = new ArrayList<>(RadarReadout.contacts());
        contacts.sort(Comparator.comparingDouble(Contact::range));
        int[] counts = new int[Iff.values().length];

        for (Contact contact : contacts) {
            counts[CockpitControls.identify(contact.iff()).ordinal()]++;
        }

        fill(2, 36, PX - 2, 37, DIM);
        text("F " + counts[Iff.FRIEND.ordinal()], 4, 40, AircraftHud.contactColour(Iff.FRIEND, false));
        centred("U " + counts[Iff.UNKNOWN.ordinal()], PX / 2, 40, AircraftHud.contactColour(Iff.UNKNOWN, false));
        String hostile = "H " + counts[Iff.HOSTILE.ordinal()];
        text(hostile, PX - 4 - font.width(hostile), 40, AircraftHud.contactColour(Iff.HOSTILE, false));

        int y = 53;

        for (Contact contact : contacts) {
            if (y > heightPx - 22) {
                break;
            }

            Iff iff = CockpitControls.identify(contact.iff());
            int colour = AircraftHud.contactColour(iff, contact.locked());
            String kind = contact.aircraft() ? "AIR" : "GND";

            text(iff.name().substring(0, 1) + " " + kind, 4, y, colour);
            String where = String.format("%s %+d", distance(contact.range()), Math.round(contact.altitude()));
            text(where, PX - 4 - font.width(where), y, colour);
            y += 10;
        }

        if (contacts.isEmpty()) {
            centred("NO CONTACT", PX / 2, 55, DIM);
        }

        if (own == null) {
            centred("NO TEAM: ALL UNKNOWN", PX / 2, heightPx - 11, DIM);
        } else if (!on) {
            centred("INTERROGATOR OFF", PX / 2, heightPx - 11, AMBER);
        }
    }

    /**
     * 警戒受信機。機首を上にした円に、発信源の記号を方位に置く。半径は危険の度合いで、内側ほど危ない
     * （捜索は外、ロックは中、飛んでくる誘導弾は内）。一番危ない1件は菱形で囲む。
     *
     * <p>誘導弾が1発でも来ていれば、上に赤い LAUNCH を他の何より先に出す。下は残っている対抗手段。
     */
    private static void rwr(AircraftEntity aircraft) {
        text("RWR", 2, 3, DIM);

        List<Threat> threats = new ArrayList<>(RadarReadout.threats());
        Threat priority = threats.isEmpty() ? null : threats.get(0);
        boolean launch = priority != null && priority.kind() == Threat.Kind.MISSILE;

        if (!CockpitControls.searchShown()) {
            threats.removeIf(threat -> threat.kind() == Threat.Kind.SEARCH && threat != priority);
        }

        String count = threats.size() + " EMT";
        text(count, PX - 2 - font.width(count), 3, threats.isEmpty() ? DIM : GREEN);

        int cx = PX / 2;
        int cy = 13 + (heightPx - 27) / 2;
        int radius = Math.max(10, Math.min(PX / 2 - 6, (heightPx - 27) / 2));

        // 外周と内側の2つの環を点線で、機首と左右と後ろに目盛り。
        for (int step = 0; step < 36; step++) {
            double angle = Math.toRadians(step * 10.0);
            float sx = (float) Math.sin(angle);
            float sy = (float) -Math.cos(angle);

            fill(cx + sx * radius - 0.5F, cy + sy * radius - 0.5F, cx + sx * radius + 0.5F, cy + sy * radius + 0.5F,
                    DIM);

            if (step % 2 == 0) {
                float inner = radius * 0.45F;
                fill(cx + sx * inner - 0.5F, cy + sy * inner - 0.5F, cx + sx * inner + 0.5F,
                        cy + sy * inner + 0.5F, 0x803BE86A);
            }

            if (step % 9 == 0) {
                line(cx + sx * (radius - 4), cy + sy * (radius - 4), cx + sx * radius, cy + sy * radius, GREEN);
            }
        }

        // 自機。
        line(cx, cy - 3, cx, cy + 3, WHITE);
        line(cx - 3, cy, cx + 3, cy, WHITE);
        layer();

        // 危なくない物から描き、危ない物を上に重ねる。
        threats.sort(Comparator.comparingInt(threat -> threat.kind().ordinal()));

        for (Threat threat : threats) {
            float out = switch (threat.kind()) {
                case SEARCH -> 0.8F;
                case LOCK -> 0.55F;
                case MISSILE -> 0.3F;
            };
            double angle = Math.toRadians(threat.bearing());
            int x = cx + Math.round((float) Math.sin(angle) * radius * out);
            int y = cy - Math.round((float) Math.cos(angle) * radius * out);
            int colour = switch (threat.kind()) {
                case SEARCH -> GREEN;
                case LOCK -> AMBER;
                case MISSILE -> WARNING;
            };

            if (threat.kind() == Threat.Kind.MISSILE && !blink()) {
                continue;
            }

            if (threat == priority) {
                int d = 7;
                line(x, y - d, x + d, y, colour);
                line(x + d, y, x, y + d, colour);
                line(x, y + d, x - d, y, colour);
                line(x - d, y, x, y - d, colour);
            }

            // ロックは記号の上に弧の代わりの帽子を載せる。実機の「追尾されている」印。
            if (threat.kind() == Threat.Kind.LOCK) {
                line(x - 4, y - 5, x, y - 8, colour);
                line(x, y - 8, x + 4, y - 5, colour);
            }

            centred(threat.symbol(), x, y - 3, colour);
        }

        if (launch) {
            layer();
            fill(PX / 2 - 24, 12, PX / 2 + 24, 23, blink() ? WARNING : 0xFF5A0A0A);
            layer();
            centred("LAUNCH", PX / 2, 14, WHITE);
        } else if (threats.isEmpty()) {
            centred("CLEAR", cx, cy + 6, DIM);
        }

        int flares = aircraft.getCountermeasures(true);
        int chaff = aircraft.getCountermeasures(false);

        text("FLR " + flares, 2, heightPx - 11, flares > 0 ? GREEN : WARNING);
        String ch = "CHF " + chaff;
        text(ch, PX - 2 - font.width(ch), heightPx - 11, chaff > 0 ? GREEN : WARNING);
    }

    /**
     * ターゲティングポッド。ジンバルの向けられる範囲の中にボールの向き、捕捉していれば照準枠、下に照射の状態と
     * 距離、選んでいる兵装が誘導で落とせるか。
     *
     * <p>映像は描かない。計器盤の板にもう1つ世界を描くのは重く、映像は照準キーで上がるポッド視界が持っている。
     * この頁が答えるのは「今投下したら誘導されるか」だけ。
     */
    private static void tpod(AircraftEntity aircraft) {
        text("TGP", 2, 3, DIM);

        WeaponMounts weapons = aircraft.getWeapons();

        if (!weapons.hasPod(EquipmentDefinition.Kind.TARGETING)) {
            centred("NO POD", PX / 2, heightPx / 2 - 4, DIM);

            return;
        }

        Entity mark = aircraft.getDesignated();
        boolean limited = mark != null && PodCamera.isGimbalLimited();
        String view = PodCamera.isActive() ? "VIEW" : "STBY";
        text(view, PX - 2 - font.width(view), 3, PodCamera.isActive() ? GREEN : DIM);

        int left = 4;
        int right = PX - 4;
        int top = 14;
        int bottom = Math.max(top + 20, heightPx - 36);
        int cx = (left + right) / 2;
        float span = PodCamera.PITCH_DOWN_LIMIT - PodCamera.PITCH_UP_LIMIT;
        int horizon = top + Math.round((bottom - top) * -PodCamera.PITCH_UP_LIMIT / span);

        float yaw = PodCamera.gimbalYaw();
        float pitch = PodCamera.gimbalPitch();
        int x;
        int y;

        if (PodFeed.request(aircraft)) {
            // ポッドの映像。十字線は映像の中心で、ボールが向いている所。マークを追っていればそこに居る。
            float[] spans = video(left, top, right, bottom);
            videos[drawing] = new float[] {pageLeft + left * pageShrink, pageTop + top * pageShrink,
                    pageLeft + right * pageShrink, pageTop + bottom * pageShrink, spans[0], spans[1]};
            layer();
            String setting = PodFeed.fieldName() + " " + PodFeed.modeName();
            text(setting, left + 2, top + 2, GREEN);
            outline(left, top, right, bottom, DIM);
            x = cx;
            y = (top + bottom) / 2;
            int gap = 7;
            line(left + 2, y, x - gap, y, GREEN);
            line(x + gap, y, right - 2, y, GREEN);
            line(x, top + 2, x, y - gap, GREEN);
            line(x, y + gap, x, bottom - 2, GREEN);

            // ジンバルの向きは隅の小さな図で。縦の線が機首、横の線が水平。
            int gw = 22;
            int gh = 14;
            int gl = right - 3 - gw;
            int gt = bottom - 3 - gh;
            fill(gl, gt, gl + gw, gt + gh, 0xA0050A08);
            layer();
            outline(gl, gt, gl + gw, gt + gh, DIM);
            fill(gl + gw / 2, gt + 1, gl + gw / 2 + 1, gt + gh - 1, 0x603BE86A);
            int gz = gt + Math.round(gh * -PodCamera.PITCH_UP_LIMIT / span);
            fill(gl + 1, gz, gl + gw - 1, gz + 1, 0x603BE86A);
            layer();
            int dx = gl + gw / 2 - Math.round(yaw / PodCamera.YAW_LIMIT * (gw / 2.0F - 2.0F));
            int dy = gt + 1 + Math.round((pitch - PodCamera.PITCH_UP_LIMIT) / span * (gh - 2));
            fill(dx - 1, dy - 1, dx + 1, dy + 1, mark != null && limited ? AMBER : WHITE);
            layer();
        } else {
            // ジンバルの向けられる範囲。縦の線が機首、横の線が水平。
            outline(left, top, right, bottom, DIM);
            fill(cx, top + 1, cx + 1, bottom - 1, 0x603BE86A);
            fill(left + 1, horizon, right - 1, horizon + 1, 0x603BE86A);
            layer();

            x = cx - Math.round(yaw / PodCamera.YAW_LIMIT * ((right - left) / 2.0F - 3.0F));
            y = top + 2 + Math.round((pitch - PodCamera.PITCH_UP_LIMIT) / span * (bottom - top - 4));
        }

        if (mark == null) {
            line(x - 4, y, x + 4, y, WHITE);
            line(x, y - 4, x, y + 4, WHITE);
        } else {
            int colour = limited ? AMBER : GREEN;
            int r = 6;
            int arm = 3;

            for (int sx = -1; sx <= 1; sx += 2) {
                for (int sy = -1; sy <= 1; sy += 2) {
                    int ax = x + sx * r;
                    int ay = y + sy * r;

                    line(ax, ay, ax - sx * arm, ay, colour);
                    line(ax, ay, ax, ay - sy * arm, colour);
                }
            }

            fill(x - 1, y - 1, x + 1, y + 1, colour);
        }

        // 照射は捕捉していてボールが向いている間だけ。範囲の端で止まっていれば遮られている。
        int row = bottom + 3;
        String laser = mark == null ? "LSR STBY" : limited ? "LSR MASK" : "LASING";
        text(laser, 2, row, mark == null ? DIM : limited ? AMBER : blink() ? WARNING : AMBER);

        String range = mark == null ? "RNG ---"
                : "RNG " + distance((float) aircraft.position().distanceTo(aircraft.getDesignatedPoint()));
        text(range, PX - 2 - font.width(range), row, mark == null ? DIM : GREEN);

        row += 10;
        WeaponDefinition selected = weapons.selectedWeapon();
        boolean laserGuided = selected != null
                && selected.requires().orElse(null) == EquipmentDefinition.Kind.TARGETING;
        String release;
        int releaseColour;

        if (!laserGuided) {
            release = "NO LGB SEL";
            releaseColour = DIM;
        } else if (mark == null) {
            release = "NO DESIG";
            releaseColour = AMBER;
        } else if (limited) {
            release = "GIMBAL";
            releaseColour = AMBER;
        } else {
            release = "GUIDED RDY";
            releaseColour = GREEN;
        }

        text(release, 2, row, releaseColour);

        if (mark != null) {
            String kind = mark instanceof DesignationEntity ? "POINT" : "MOVING";
            text(kind, PX - 2 - font.width(kind), row, DIM);
        }

        if (laserGuided && weapons.selected() != null) {
            String name = shorten(weapons.selected().getPath().toUpperCase(), 12) + " " + weapons.selectedAmmo();
            text(name, 2, heightPx - 11, weapons.selectedAmmo() > 0 ? GREEN : AMBER);
        }
    }

    /**
     * 地図。機首を上にして自機を下寄りに置き、航路点と航路の線を描く。下に選んでいる点への方位・距離・到達予想
     * と、着いたときに残る燃料（今の減り方で）。航路点の編集は全部この頁の縁のボタン（{@link Waypoints}）。
     */
    private static void map(AircraftEntity aircraft, Quaternionf attitude) {
        text("MAP", 2, 3, DIM);

        float shown = 8000.0F * rangeScale;
        String reach = distance(shown);
        text(reach, PX - 2 - font.width(reach), 3, DIM);

        int left = 3;
        int right = PX - 3;
        int top = 13;
        int bottom = Math.max(top + 20, heightPx - 25);
        int cx = (left + right) / 2;
        int cy = top + Math.round((bottom - top) * 0.7F);
        float scale = (cy - top - 2) / shown;
        int heading = heading(attitude);
        Vec3 at = aircraft.position();

        outline(left, top, right, bottom, 0x603BE86A);

        // 表示距離の半分と全部の環を、枠の内側だけ点で。
        for (float ring : new float[] {0.5F, 1.0F}) {
            float r = shown * ring * scale;

            for (int step = 0; step < 72; step++) {
                double angle = Math.toRadians(step * 5.0);
                float px = cx + (float) Math.sin(angle) * r;
                float py = cy - (float) Math.cos(angle) * r;

                if (inside(px, py, left + 1, top + 1, right - 1, bottom - 1)) {
                    fill(px - 0.5F, py - 0.5F, px + 0.5F, py + 0.5F, DIM);
                }
            }
        }

        // 北の向き。
        double north = Math.toRadians(-heading);
        float nx = cx + (float) Math.sin(north) * (cy - top - 8);
        float ny = cy - (float) Math.cos(north) * (cy - top - 8);

        if (inside(nx, ny, left + 4, top + 4, right - 4, bottom - 4)) {
            label("N", nx - 2, ny - 3, AMBER);
        }

        layer();

        List<Vec3> points = Waypoints.points();
        int chosen = Waypoints.selectedIndex();
        float[][] screen = new float[points.size()][];

        for (int i = 0; i < points.size(); i++) {
            Vec3 point = points.get(i);
            double dx = point.x - at.x;
            double dz = point.z - at.z;
            double relative = Math.toRadians(Math.toDegrees(Math.atan2(dx, -dz)) - heading);
            float range = (float) Math.sqrt(dx * dx + dz * dz) * scale;

            screen[i] = new float[] {cx + (float) Math.sin(relative) * range, cy - (float) Math.cos(relative) * range};
        }

        for (int i = 0; i + 1 < screen.length; i++) {
            if (inside(screen[i][0], screen[i][1], left, top, right, bottom)
                    && inside(screen[i + 1][0], screen[i + 1][1], left, top, right, bottom)) {
                line(screen[i][0], screen[i][1], screen[i + 1][0], screen[i + 1][1], i + 1 == chosen ? CYAN : DIM);
            }
        }

        // 選んでいる点へは自機から線を引く。
        if (chosen >= 0 && inside(screen[chosen][0], screen[chosen][1], left, top, right, bottom)) {
            line(cx, cy, screen[chosen][0], screen[chosen][1], 0x804FC3F7);
        }

        layer();

        for (int i = 0; i < screen.length; i++) {
            float px = Mth.clamp(screen[i][0], left + 2, right - 2);
            float py = Mth.clamp(screen[i][1], top + 2, bottom - 2);
            boolean off = px != screen[i][0] || py != screen[i][1];
            int colour = i == chosen ? CYAN : GREEN;

            if (off) {
                outline(Math.round(px - 2), Math.round(py - 2), Math.round(px + 2), Math.round(py + 2), colour);
            } else {
                fill(px - 2, py - 2, px + 2, py + 2, colour);
            }

            label(String.valueOf(i + 1), Mth.clamp(px + 3, left, right - 5), Mth.clamp(py - 7, top, bottom - 6),
                    colour);
        }

        // 自機。機首が上の三角。
        line(cx, cy - 5, cx - 4, cy + 3, WHITE);
        line(cx, cy - 5, cx + 4, cy + 3, WHITE);
        line(cx - 4, cy + 3, cx + 4, cy + 3, WHITE);

        Vec3 target = Waypoints.selected();

        if (target == null) {
            centred("NO WPT", PX / 2, bottom + 3, DIM);
            centred("MARK TO ADD", PX / 2, heightPx - 11, DIM);

            return;
        }

        double dx = target.x - at.x;
        double dz = target.z - at.z;
        float distance = (float) Math.sqrt(dx * dx + dz * dz);
        int bearing = Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(dx, -dz))), 360);
        Vec3 velocity = aircraft.getVelocity();
        float speed = (float) Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z) * 20.0F;

        text("WP" + (chosen + 1), 2, bottom + 3, CYAN);
        String where = String.format("%03d %s", bearing, distance(distance));
        text(where, PX - 2 - font.width(where), bottom + 3, GREEN);

        float eta = speed > 1.0F ? distance / speed : -1.0F;
        text("ETA " + (eta < 0.0F ? "--:--" : clock(eta)), 2, heightPx - 11, GREEN);

        if (eta >= 0.0F) {
            float fuel = aircraft.getFuelFraction() - FuelWatch.perSecond() * eta;
            String arrive = String.format("FUEL %d%%", Math.max(0, Math.round(fuel * 100.0F)));
            text(arrive, PX - 2 - font.width(arrive), heightPx - 11,
                    fuel <= 0.0F ? WARNING : fuel < FuelWatch.bingo() ? AMBER : GREEN);
        }
    }

    // ---- 部品 ----

    /** 平面の点の凸包（反時計回り）。8 点しか来ないので単純な単調鎖で足りる。 */
    private static float[][] hull(float[][] points) {
        float[][] sorted = points.clone();
        Arrays.sort(sorted, (a, b) -> a[0] != b[0] ? Float.compare(a[0], b[0]) : Float.compare(a[1], b[1]));
        float[][] out = new float[sorted.length * 2][];
        int k = 0;

        for (int pass = 0; pass < 2; pass++) {
            int start = k;

            for (int n = 0; n < sorted.length; n++) {
                float[] p = sorted[pass == 0 ? n : sorted.length - 1 - n];

                while (k >= start + 2 && cross(out[k - 2], out[k - 1], p) <= 0.0F) {
                    k--;
                }

                out[k++] = p;
            }

            k--;
        }

        return Arrays.copyOf(out, Math.max(k, 1));
    }

    private static float cross(float[] o, float[] a, float[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    /** 警告の点滅。0.5 秒ごと。 */
    private static boolean blink() {
        Minecraft minecraft = Minecraft.getInstance();

        return minecraft.level == null || minecraft.level.getGameTime() % 10 < 6;
    }

    private static String clock(float seconds) {
        int whole = Math.round(seconds);

        return whole >= 3600 ? String.format("%d:%02d:%02d", whole / 3600, whole / 60 % 60, whole % 60)
                : String.format("%02d:%02d", whole / 60, whole % 60);
    }

    /** 円形の計器。7時から5時まで 270 度を振る針と、中に数値。 */
    private static void dial(String name, int cx, int cy, int radius, float fraction, String value, int colour) {
        for (int step = 0; step <= 27; step++) {
            double angle = Math.toRadians(225.0 - step * 10.0);
            float sx = (float) Math.cos(angle);
            float sy = (float) -Math.sin(angle);
            float inner = step % 9 == 0 ? radius - 4 : radius - 2;
            int ink = step >= 25 ? WARNING : DIM;

            line(cx + sx * inner, cy + sy * inner, cx + sx * radius, cy + sy * radius, ink);
        }

        layer();
        double needle = Math.toRadians(225.0 - Mth.clamp(fraction, 0.0F, 1.05F) * 270.0);
        line(cx, cy, cx + (float) Math.cos(needle) * (radius - 2), cy - (float) Math.sin(needle) * (radius - 2),
                colour);
        centred(value, cx, cy + 3, colour);
        centred(name, cx, cy + radius + 2, DIM);
    }

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
        //
        // Iris の下（遅い段）では textBackground が1枚も出ず、文字とテクスチャを貼った四角だけが残った。そこでは
        // 文字と同じ描画型で白い1画素を貼って塗る。文字の型は不透明度 0.1 未満を捨てるので、薄い色は底上げする。
        if (late) {
            quads = buffers.getBuffer(RenderType.text(white()));
            colour = Math.max(colour >>> 24, 0x1A) << 24 | colour & 0xFFFFFF;
        } else {
            quads = buffers.getBuffer(RenderType.textBackground());
        }

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

    /**
     * ポッドの映像（{@link PodFeed#TEXTURE}）を四角へ貼る。四角の比に合わせて映像の中央を切り抜く。
     *
     * @return 映像のうち四角に映っている割合（横・縦）
     */
    private static float[] video(float left, float top, float right, float bottom) {
        float aspect = (right - left) / (bottom - top);
        float feed = PodFeed.aspect();
        float uSpan = aspect < feed ? aspect / feed : 1.0F;
        float vSpan = aspect < feed ? 1.0F : feed / aspect;
        float u0 = 0.5F - uSpan / 2.0F;
        float u1 = 0.5F + uSpan / 2.0F;
        float vTop = 0.5F - vSpan / 2.0F;
        float vBottom = 0.5F + vSpan / 2.0F;

        quads = buffers.getBuffer(RenderType.text(PodFeed.TEXTURE));
        videoVertex(left, top, u0, vTop);
        videoVertex(left, bottom, u0, vBottom);
        videoVertex(right, bottom, u1, vBottom);
        videoVertex(right, top, u1, vTop);
        videoVertex(right, top, u1, vTop);
        videoVertex(right, bottom, u1, vBottom);
        videoVertex(left, bottom, u0, vBottom);
        videoVertex(left, top, u0, vTop);
        layer -= 0.02F;

        return new float[] {uSpan, vSpan};
    }

    private static void videoVertex(float x, float y, float u, float v) {
        quads.addVertex(matrix, x, y, -layer).setColor(0xFFFFFFFF).setUv(u, v).setLight(LIGHT);
    }

    private static void vertex(float x, float y, int colour) {
        VertexConsumer vertex = quads.addVertex(matrix, x, y, -layer).setColor(colour).setLight(LIGHT);

        if (late) {
            vertex.setUv(0.5F, 0.5F);
        }
    }

    private static final ResourceLocation WHITE_PIXEL = ResourceLocation.fromNamespaceAndPath("ashvehicles", "mfd_white");
    private static boolean whiteMade;

    /** 白い1画素のテクスチャ。遅い段で四角を文字の描画型で塗るため。初めて要った時に作る。 */
    private static ResourceLocation white() {
        if (!whiteMade) {
            NativeImage pixel = new NativeImage(1, 1, false);
            pixel.setPixelRGBA(0, 0, 0xFFFFFFFF);
            Minecraft.getInstance().getTextureManager().register(WHITE_PIXEL, new DynamicTexture(pixel));
            whiteMade = true;
        }

        return WHITE_PIXEL;
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
