package com.ashvehicles.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.vehicle.VehicleChassis;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.Vec3;

/**
 * MFD の縁の押しボタン。模型に作ってある小さな四角を、見て押す本物のボタンにする。
 *
 * <p><b>押し方。</b>見回しキー（{@link ModKeyMappings#FREE_LOOK}）を押している間——マウスで操縦して
 * いないなら常に——視界の中心が指先になる。見ているボタンが光り、攻撃ボタンで押す。その間は引き金を引かない。
 * 操縦の手を離さずに視線だけ落として押すのが、実際の操縦席での押し方だからだ。
 *
 * <p><b>役割は位置で決まる。</b>定義ファイルが書くのはボタンの位置だけ（{@link VehicleChassis.Display#buttons}）。
 * 画面のどの縁に並んでいるかで組に分け、最初の組（上の列、無ければ左の列）が頁の切り替え、残りの組が
 * 今映している頁の操作になる。機体ごとに配線を書かずに、模型の通りに並んだボタンがそのまま働く。
 *
 * <p>頁とレーダーの表示距離はこのクライアントだけの状態で、送らない。脚・フラップ・倉と兵装の選択は、
 * キーと同じ入力パケットに乗せる（{@link AircraftInputHandler}）。サーバーから見ればキーを押したのと区別が無い。
 */
public final class CockpitControls {
    /** 頁の名前。定義ファイルの {@code page} と同じ綴り。 */
    static final String[] PAGES = {"pfd", "nav", "radar", "sms", "eng"};
    private static final String[] TABS = {"PFD", "NAV", "RDR", "SMS", "ENG"};
    /** レーダーの表示距離。到達距離に掛ける割合。 */
    private static final float[] RANGES = {1.0F, 0.5F, 0.25F};
    /** ボタンの中心からこの画素以内を見ていれば、そのボタンを見ている。 */
    private static final float REACH = 11.0F;
    /** 画面の外へこの画素まで見ていれば、まだ計器盤を見ている（引き金を止める）。 */
    private static final float MARGIN = 30.0F;
    /** 押したボタンが光る時間（tick）。 */
    private static final int FLASH = 4;

    enum Edge { TOP, LEFT, RIGHT, BOTTOM }

    enum Action { NONE, PAGE, RANGE_OUT, RANGE_IN, PREV, NEXT, GEAR, FLAP, BAY }

    /**
     * 1つのボタン。座標はその画面の画素（左上が原点、右と下が正）。
     *
     * @param lit 今その状態にあるか（映している頁、下りている脚）。札を反転させる
     */
    record Button(float x, float y, Edge edge, String label, Action action, int page, boolean lit) {
    }

    private static int aircraftId = -1;
    /** 画面ごとの頁（{@link #PAGES} の添字）。-1 なら定義ファイルの頁。 */
    private static int[] pages = new int[0];
    private static int[] ranges = new int[0];

    // 描画のたびに決め直す、今見ている所。tick 側の押下はこれを読む。
    private static int hoverDisplay = -1;
    private static int hoverButton = -1;
    private static float hoverX;
    private static float hoverY;

    private static int pressedDisplay = -1;
    private static int pressedButton = -1;
    private static long pressedAt;

    // 次の入力パケットに乗せる分。
    private static boolean gear;
    private static boolean flaps;
    private static boolean bay;
    private static int weaponSteps;

    private CockpitControls() {
    }

    // ---- 状態 ----

    /** その画面が今映している頁。 */
    static String page(AircraftEntity aircraft, int index, VehicleChassis.Display display) {
        sync(aircraft, index + 1);

        return pages[index] < 0 ? display.page() : PAGES[pages[index]];
    }

    /** その画面のレーダー表示距離の割合。 */
    static float rangeScale(AircraftEntity aircraft, int index) {
        sync(aircraft, index + 1);

        return RANGES[ranges[index]];
    }

    /** 機体が替わったら頁を定義ファイルの通りへ戻す。 */
    private static void sync(AircraftEntity aircraft, int needed) {
        if (aircraft.getId() != aircraftId) {
            aircraftId = aircraft.getId();
            pages = new int[0];
            ranges = new int[0];
            hoverDisplay = -1;
        }

        if (pages.length < needed) {
            int from = pages.length;
            pages = Arrays.copyOf(pages, needed);
            ranges = Arrays.copyOf(ranges, needed);
            Arrays.fill(pages, from, needed, -1);
        }
    }

    // ---- ボタンの並び ----

    /**
     * その画面のボタンを、今の頁での役割を付けて並べる。描画と押下が同じ答えを使う。
     *
     * @param frame 画面の画素から機体の描画座標系への変換（{@link CockpitDisplays#frame}）
     */
    static List<Button> layout(AircraftEntity aircraft, int index, VehicleChassis.Display display,
            Matrix4f frame, int height) {
        if (display.buttons().isEmpty()) {
            return List.of();
        }

        Matrix4f toPixels = new Matrix4f(frame).invert();
        List<List<float[]>> edges = new ArrayList<>();

        for (int i = 0; i < Edge.values().length; i++) {
            edges.add(new ArrayList<>());
        }

        for (Vec3 at : display.buttons()) {
            // 機体軸から描画座標系へ（半回転の後なので z が反転する）、そこから画面の画素へ。
            Vector3f p = toPixels.transformPosition(new Vector3f((float) at.x, (float) at.y, (float) -at.z));
            edges.get(edgeOf(p.x, p.y, height).ordinal()).add(new float[] {p.x, p.y});
        }

        edges.get(Edge.TOP.ordinal()).sort(Comparator.comparingDouble(p -> p[0]));
        edges.get(Edge.BOTTOM.ordinal()).sort(Comparator.comparingDouble(p -> p[0]));
        edges.get(Edge.LEFT.ordinal()).sort(Comparator.comparingDouble(p -> p[1]));
        edges.get(Edge.RIGHT.ordinal()).sort(Comparator.comparingDouble(p -> p[1]));

        // 頁の組は上の列、無ければ左、右、下の順で最初にある物。
        Edge tabs = null;

        for (Edge edge : new Edge[] {Edge.TOP, Edge.LEFT, Edge.RIGHT, Edge.BOTTOM}) {
            if (!edges.get(edge.ordinal()).isEmpty()) {
                tabs = edge;
                break;
            }
        }

        String page = page(aircraft, index, display);
        List<Button> buttons = new ArrayList<>();
        List<float[]> tabRow = edges.get(tabs.ordinal());

        for (int i = 0; i < tabRow.size(); i++) {
            float[] p = tabRow.get(i);

            if (i < PAGES.length) {
                buttons.add(new Button(p[0], p[1], tabs, TABS[i], Action.PAGE, i, PAGES[i].equals(page)));
            } else {
                buttons.add(new Button(p[0], p[1], tabs, "", Action.NONE, -1, false));
            }
        }

        // 残りは頁の操作。右、左、下の順に上から（左から）埋める。
        List<Button> verbs = verbs(aircraft, index, page);
        int next = 0;

        for (Edge edge : new Edge[] {Edge.RIGHT, Edge.LEFT, Edge.BOTTOM, Edge.TOP}) {
            if (edge == tabs) {
                continue;
            }

            for (float[] p : edges.get(edge.ordinal())) {
                Button verb = next < verbs.size() ? verbs.get(next++) : null;

                buttons.add(verb == null
                        ? new Button(p[0], p[1], edge, "", Action.NONE, -1, false)
                        : new Button(p[0], p[1], edge, verb.label(), verb.action(), -1, verb.lit()));
            }
        }

        return buttons;
    }

    /** 頁ごとの操作。位置はまだ無く、並び順だけが意味を持つ。 */
    private static List<Button> verbs(AircraftEntity aircraft, int index, String page) {
        List<Button> verbs = new ArrayList<>();

        switch (page) {
            case "radar" -> {
                verbs.add(verb("RNG+", Action.RANGE_OUT, ranges[index] == 0));
                verbs.add(verb("RNG-", Action.RANGE_IN, ranges[index] == RANGES.length - 1));
            }
            case "sms" -> {
                verbs.add(verb("PREV", Action.PREV, false));
                verbs.add(verb("NEXT", Action.NEXT, false));
            }
            case "eng" -> {
                verbs.add(verb("GEAR", Action.GEAR, aircraft.isGearDown()));
                verbs.add(verb("FLAP", Action.FLAP, aircraft.isFlapsDown()));

                if (aircraft.hasBay()) {
                    verbs.add(verb("BAY", Action.BAY, aircraft.isBayOpen()));
                }
            }
            default -> {
            }
        }

        return verbs;
    }

    private static Button verb(String label, Action action, boolean lit) {
        return new Button(0.0F, 0.0F, Edge.RIGHT, label, action, -1, lit);
    }

    /** 画面の外のどの縁にあるか。一番はみ出している向き。 */
    private static Edge edgeOf(float x, float y, int height) {
        float top = -y;
        float bottom = y - height;
        float left = -x;
        float right = x - CockpitDisplays.PX;
        float most = Math.max(Math.max(top, bottom), Math.max(left, right));

        return most == top ? Edge.TOP : most == bottom ? Edge.BOTTOM : most == left ? Edge.LEFT : Edge.RIGHT;
    }

    // ---- 見て押す ----

    /** 今、視線が指先になっているか。操縦していて一人称で、見回しているか、マウスで操縦していない。 */
    static boolean enabled(AircraftEntity aircraft) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;

        return player != null && player.getVehicle() == aircraft && aircraft.getControllingPassenger() == player
                && minecraft.options.getCameraType().isFirstPerson()
                && (ModKeyMappings.FREE_LOOK.isDown() || !MouseAim.isActive());
    }

    /**
     * 視線が当たっている画面とボタンを決める。描画の最初に1回。
     *
     * <p>視線は描画のカメラそのもので、画面の板を描くのと同じ変換を逆に辿って画素へ戻す。だから光るボタンは
     * 見ている所からずれない。
     */
    static void aim(AircraftEntity aircraft, List<VehicleChassis.Display> displays, Quaternionf attitude,
            float partialTick) {
        hoverDisplay = -1;
        hoverButton = -1;

        if (!enabled(aircraft)) {
            return;
        }

        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 eye = camera.getPosition();
        Vec3 at = aircraft.getPosition(partialTick);
        Vector3f look = new Vector3f(camera.getLookVector());
        // カメラを原点に置いた、機体の描画座標系（姿勢と半回転の後）。
        Matrix4f body = new Matrix4f()
                .translate((float) (at.x - eye.x), (float) (at.y - eye.y), (float) (at.z - eye.z))
                .rotate(attitude)
                .rotateY((float) Math.PI);
        float nearest = Float.MAX_VALUE;

        for (int i = 0; i < displays.size(); i++) {
            VehicleChassis.Display display = displays.get(i);
            int height = CockpitDisplays.heightOf(display);
            Matrix4f frame = CockpitDisplays.frame(display, height);
            Matrix4f toPixels = new Matrix4f(body).mul(frame).invert();
            Vector3f origin = toPixels.transformPosition(new Vector3f());
            Vector3f along = toPixels.transformDirection(new Vector3f(look));

            if (Math.abs(along.z) < 1.0E-7F) {
                continue;
            }

            // look は単位ベクトルなので、t はそのまま目からの距離（ブロック）になる。
            float t = -origin.z / along.z;
            float x = origin.x + along.x * t;
            float y = origin.y + along.y * t;

            if (t <= 0.0F || t >= nearest || x < -MARGIN || x > CockpitDisplays.PX + MARGIN
                    || y < -MARGIN || y > height + MARGIN) {
                continue;
            }

            nearest = t;
            hoverDisplay = i;
            hoverX = x;
            hoverY = y;
            hoverButton = -1;

            List<Button> buttons = layout(aircraft, i, display, frame, height);
            float best = REACH * REACH;

            for (int b = 0; b < buttons.size(); b++) {
                float dx = buttons.get(b).x() - x;
                float dy = buttons.get(b).y() - y;

                if (dx * dx + dy * dy < best) {
                    best = dx * dx + dy * dy;
                    hoverButton = b;
                }
            }
        }
    }

    /** 視線が計器盤の上にあるか。あれば攻撃ボタンは引き金ではなく指になる。 */
    static boolean pointing(AircraftEntity aircraft) {
        return hoverDisplay >= 0 && enabled(aircraft);
    }

    static boolean hovered(int display, int button) {
        return display == hoverDisplay && button == hoverButton;
    }

    static boolean flashing(int display, int button) {
        Minecraft minecraft = Minecraft.getInstance();

        return display == pressedDisplay && button == pressedButton && minecraft.level != null
                && minecraft.level.getGameTime() - pressedAt < FLASH;
    }

    /** 視線の当たっている点。その画面の画素で。当たっていなければ null。 */
    static float[] cursor(int display) {
        return display == hoverDisplay ? new float[] {hoverX, hoverY} : null;
    }

    /** 攻撃ボタンが1回押された。見ているボタンがあれば押す。 */
    static void press(AircraftEntity aircraft) {
        if (!pointing(aircraft) || hoverButton < 0) {
            return;
        }

        List<VehicleChassis.Display> displays = aircraft.getStats().camera().displays().orElse(List.of());

        if (hoverDisplay >= displays.size()) {
            return;
        }

        VehicleChassis.Display display = displays.get(hoverDisplay);
        int height = CockpitDisplays.heightOf(display);
        List<Button> buttons = layout(aircraft, hoverDisplay, display, CockpitDisplays.frame(display, height),
                height);

        if (hoverButton >= buttons.size()) {
            return;
        }

        Button button = buttons.get(hoverButton);

        switch (button.action()) {
            case PAGE -> pages[hoverDisplay] = button.page();
            case RANGE_OUT -> ranges[hoverDisplay] = Math.max(0, ranges[hoverDisplay] - 1);
            case RANGE_IN -> ranges[hoverDisplay] = Math.min(RANGES.length - 1, ranges[hoverDisplay] + 1);
            case PREV -> weaponSteps--;
            case NEXT -> weaponSteps++;
            case GEAR -> gear = true;
            case FLAP -> flaps = true;
            case BAY -> bay = true;
            case NONE -> {
                return;
            }
        }

        Minecraft minecraft = Minecraft.getInstance();
        pressedDisplay = hoverDisplay;
        pressedButton = hoverButton;
        pressedAt = minecraft.level == null ? 0L : minecraft.level.getGameTime();
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.8F, 0.2F));
    }

    // ---- 入力パケットへ ----

    static boolean takeGear() {
        boolean taken = gear;
        gear = false;

        return taken;
    }

    static boolean takeFlaps() {
        boolean taken = flaps;
        flaps = false;

        return taken;
    }

    static boolean takeBay() {
        boolean taken = bay;
        bay = false;

        return taken;
    }

    static int takeWeaponSteps() {
        int taken = weaponSteps;
        weaponSteps = 0;

        return taken;
    }
}
