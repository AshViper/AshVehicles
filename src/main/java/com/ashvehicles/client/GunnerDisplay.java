package com.ashvehicles.client;

import java.util.Locale;

import javax.annotation.Nullable;

import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.weapon.GunStations;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * 砲手席の火器管制画面。センサー映像（{@link ThermalView}）の上に載る枠であり、砲手が読む物の全てだ。
 *
 * <p><b>飛行計器の上に重ねるのではなく、飛行計器の<em>代わりに</em>描く。</b>{@link PodDisplay} と同じ判断で、
 * 理由も同じだ。姿勢ラダー、飛行経路マーカー、速度、高度計——通常の計器面にある物はどれもキャノピー越しの
 * パイロット自身の視線に対して置かれている。砲手が見ているのは砲身に固定された箱からの映像で、機首とは別の
 * 方向を向いており、しかも砲手は操縦桿を握っていない。その画面に姿勢ラダーを描けば、間違った場所にある
 * 水平線を、それを使えない人に見せることになる。
 *
 * <p><b>機体を選ばない。</b>この画面が出る条件は「砲座を受け持っている乗員が一人称で座っている」ことだけで、
 * AC-130 の30mmでも105mmでも、砲座を持つ他のどの機体でも同じ画面が出る。砲座の一覧も、名前も、可動端も、
 * 全部その機体のファイルから読む。{@link GunStations} 参照。
 *
 * <p><b>操縦している者には出さない。</b>1人で飛ぶパイロットは兵装切り替えキーで砲座を持てる
 * （{@link GunStations#pilotHoldsStation}）が、その人はキャノピー越しに前を見ながら機体を飛ばしている。
 * 飛行計器を取り上げてよい相手ではない。
 *
 * <p><b>色は白1色。</b>実物の赤外線画面がそうだからでもあるが、それ以上に、警告を色で言えないことが
 * ここでは正しい。センサー映像の上では緑も琥珀も地の明暗に飲まれる——白熱表示の明るい地面の上に置いた琥珀の
 * 文字は、ただの読みにくい文字だ。だから注意を引くべき表示は色ではなく<em>反転</em>で出す
 * （{@link #inverse}）。単色の画面が昔からそうしてきた方法であり、地の明暗に関わらず必ず読める。
 */
public final class GunnerDisplay {
    /** 記号の白と、まだ何も起きていない表示の白。 */
    private static final int WHITE = 0xFFE6EAE8;
    private static final int DIM = 0xA0C4CCC8;
    /** 反転表示の地と文字。 */
    private static final int INVERSE_GROUND = 0xFFE6EAE8;
    private static final int INVERSE_TEXT = 0xFF0A0C0B;

    /** 十字線の腕の長さ、中心の空き、目盛りの間隔と長さ、両端の帽子の高さ（ピクセル）。 */
    private static final int ARM_X = 108;
    private static final int ARM_Y = 84;
    private static final int GAP = 7;
    private static final int TICK_STEP = 18;
    private static final int TICK = 4;
    private static final int CAP = 9;

    /** 捕捉枠の一辺の半分と、四隅の腕の長さ。 */
    private static final int MARK = 8;
    private static final int MARK_ARM = 4;

    /** 砲腔線に沿って地面を探す距離（ブロック）。 */
    private static final double REACH = 2048.0;

    /** 可動端に着いたと数える角度の余裕（度）。 */
    private static final float STOP_MARGIN = 0.5F;

    /**
     * 距離と対地高度を求め直した tick。
     *
     * <p>毎フレームではない。距離は砲腔線に沿って地面を追う走査で、ロード範囲の外まで届かせるために
     * {@link Terrain} を通す——数百回の探索だ。答えは1/60秒では変わらないし、変わったところで4桁の数字の
     * 下2桁が動くだけである。{@link GunSight} が同じ理由で同じことをしている。
     */
    private static long ranged = Long.MIN_VALUE;
    private static double slant;
    private static boolean estimated;
    private static double agl;

    private GunnerDisplay() {
    }

    /**
     * この画面の持ち主が今受け持っている砲座。受け持っていなければ {@link GunStations#NONE}。
     *
     * <p>センサー映像の側（{@link ThermalView}）もここを読む。映像と枠が別々の条件で出入りすれば、色付きの
     * 風景の上に火器管制画面が載る瞬間と、熱い画面に飛行計器が載る瞬間が両方できる。
     *
     * <p>三人称では受け持っていないことにする。あちらのカメラは砲に縛られておらず
     * （{@link AircraftCameraHandler} 参照）、映しているのは機体全体だ。センサーの枠を被せる相手ではない。
     */
    public static int manned(Minecraft minecraft) {
        if (minecraft.player == null
                || !(minecraft.player.getVehicle() instanceof AircraftEntity aircraft)
                || aircraft.getControllingPassenger() == minecraft.player
                || !minecraft.options.getCameraType().isFirstPerson()) {
            return GunStations.NONE;
        }

        return aircraft.getStations().liveStationOf(minecraft.player);
    }

    /**
     * 砲手席の画面が描く物すべて。何か描いたかを返し、それが飛行計器へ降板の合図になる。
     */
    public static boolean draw(GuiGraphics graphics, Minecraft minecraft, AircraftEntity aircraft,
            float partialTick, int centreX, int centreY) {
        int station = manned(minecraft);

        if (station == GunStations.NONE) {
            return false;
        }

        GunStations stations = aircraft.getStations();
        AircraftDefinition.Station laid = stations.station(station);
        ResourceLocation gun = stations.weaponOf(station);
        GunSight.Solution sight = gun == null ? null : GunSight.solve(aircraft, station);
        Quaternionf bore = GunCamera.world(aircraft, station, partialTick);

        measure(minecraft, aircraft, bore);

        Font font = minecraft.font;

        // 十字線は画面の中心ではなく砲腔線の上に置く。砲身に固定された箱から映している席
        // （{@code "mount": "gun"}）では両者は同じ点だが、頭が自由に回る砲手席——AH-64 の前席のように、
        // 砲が視線を追いかける機体——ではそうならない。砲が旋回速度の分だけ遅れている間、画面の中心は砲では
        // なく頭であり、そこに十字線を描けば「砲はここを向いている」という嘘になる。
        int[] cross = AircraftHud.project(minecraft, Attitude.nose(bore),
                AircraftHud.focalLength(minecraft, graphics), centreX, centreY);

        if (cross != null) {
            reticle(graphics, cross[0], cross[1]);
        }

        aim(graphics, minecraft, sight, partialTick, centreX, centreY);
        modes(graphics, font, stations, laid, station, sight, bore);
        readout(graphics, font, bore);
        keys(graphics, font);
        guns(graphics, font, stations, station);
        status(graphics, font, aircraft, stations, laid, station, gun, centreX);

        return true;
    }

    // ------------------------------------------------------------------
    // 十字線
    // ------------------------------------------------------------------

    /**
     * 目盛り付きの十字線。中心は砲腔線で、置き場所は {@link #draw} が投影した点だ。
     *
     * <p>目盛りは飾りではなく物差しだ。単色の映像には遠近を伝える物が何も無く、砲手が持っているのは
     * 「見えている物が十字線の何目盛り分か」だけである。中心に空きを置くのは、最も見たい物がちょうどそこに
     * 来るからだ。
     */
    private static void reticle(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - ARM_X, y, x - GAP, y + 1, WHITE);
        graphics.fill(x + GAP, y, x + ARM_X, y + 1, WHITE);
        graphics.fill(x, y - ARM_Y, x + 1, y - GAP, WHITE);
        graphics.fill(x, y + GAP, x + 1, y + ARM_Y, WHITE);

        for (int step = TICK_STEP; step <= ARM_X - TICK_STEP; step += TICK_STEP) {
            graphics.fill(x - step, y + 1, x - step + 1, y + 1 + TICK, WHITE);
            graphics.fill(x + step, y + 1, x + step + 1, y + 1 + TICK, WHITE);
        }

        for (int step = TICK_STEP; step <= ARM_Y - TICK_STEP; step += TICK_STEP) {
            graphics.fill(x - TICK, y - step, x, y - step + 1, WHITE);
            graphics.fill(x - TICK, y + step, x, y + step + 1, WHITE);
        }

        // 四隅ではなく四端の帽子。腕がどこで終わるかを言う物で、映像が明るい所へ流れても十字線の長さが
        // 読めなくならないようにする。
        graphics.fill(x - ARM_X, y - CAP, x - ARM_X + 1, y + CAP + 1, WHITE);
        graphics.fill(x + ARM_X, y - CAP, x + ARM_X + 1, y + CAP + 1, WHITE);
        graphics.fill(x - CAP, y - ARM_Y, x + CAP + 1, y - ARM_Y + 1, WHITE);
        graphics.fill(x - CAP, y + ARM_Y, x + CAP + 1, y + ARM_Y + 1, WHITE);
    }

    /**
     * 弾が落ちる位置と、動く物に当てるために砲をどこへ置くべきか。求め方は {@link GunSight} 参照で、
     * 飛行計器が描くのとまったく同じ解を、この画面の色で描いているだけだ。
     *
     * <p>十字線の中心と重ならないのが要点である。中心は砲が<em>向いている</em>方向で、ピッパーは弾が
     * <em>落ちる</em>位置だ。105mm を5000フィートから撃つとき、その2つは画面の端と端ほど離れる。
     */
    private static void aim(GuiGraphics graphics, Minecraft minecraft, @Nullable GunSight.Solution sight,
            float partialTick, int centreX, int centreY) {
        if (sight == null) {
            return;
        }

        float focal = AircraftHud.focalLength(minecraft, graphics);
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        Vec3 muzzle = sight.bore().muzzle(partialTick);
        Vec3 nose = sight.bore().direction(partialTick);
        Vec3 pipper = muzzle.add(nose.scale(sight.pipperRange())).add(sight.pipperDrop());
        int[] at = AircraftHud.project(minecraft, pipper.subtract(camera).normalize(), focal, centreX, centreY);

        if (at != null) {
            AircraftHud.circle(graphics, at[0], at[1], 7, WHITE);
            graphics.fill(at[0] - 1, at[1] - 1, at[0] + 1, at[1] + 1, WHITE);
        }

        Entity target = sight.target();

        if (target == null || target.isRemoved()) {
            return;
        }

        Vec3 lead = target.getPosition(partialTick).add(0.0, target.getBbHeight() * 0.5, 0.0)
                .add(sight.leadOffset());
        int[] mark = AircraftHud.project(minecraft, lead.subtract(camera).normalize(), focal, centreX, centreY);

        if (mark == null) {
            return;
        }

        held(graphics, mark[0], mark[1], sight.inRange() ? WHITE : DIM);

        String reach = Math.round(sight.targetRange()) + " m";
        graphics.drawString(minecraft.font, reach, mark[0] - minecraft.font.width(reach) / 2,
                mark[1] + MARK + 4, sight.inRange() ? WHITE : DIM, true);

        if (sight.inRange() && sight.onTarget()) {
            inverse(graphics, minecraft.font, "SHOOT", mark[0] - minecraft.font.width("SHOOT") / 2,
                    mark[1] + MARK + 15);
        }
    }

    /** 追っている物を囲む枠。四隅だけで描くので、中の目標が見えたままになる。 */
    private static void held(GuiGraphics graphics, int x, int y, int colour) {
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                int cornerX = x + sx * MARK;
                int cornerY = y + sy * MARK;

                graphics.fill(Math.min(cornerX, cornerX - sx * MARK_ARM), cornerY,
                        Math.max(cornerX, cornerX - sx * MARK_ARM), cornerY + 1, colour);
                graphics.fill(cornerX, Math.min(cornerY, cornerY - sy * MARK_ARM),
                        cornerX + 1, Math.max(cornerY, cornerY - sy * MARK_ARM), colour);
            }
        }
    }

    // ------------------------------------------------------------------
    // 文字
    // ------------------------------------------------------------------

    /**
     * 左上のモード欄。センサーが今どういう状態で物を見ているか。
     *
     * <p>実機の火器管制画面がそうであるように、1語1状態で並べる。動かない語を並べるくらいなら1行空ける方が
     * ましなので、ここにある語は全部、押せば変わるか状況で変わるかのどちらかだ。
     */
    private static void modes(GuiGraphics graphics, Font font, GunStations stations,
            AircraftDefinition.Station laid, int station, @Nullable GunSight.Solution sight, Quaternionf bore) {
        int left = 8;
        int y = 8;

        // 発射中の目印。押している間だけ塗り潰す。単色の画面では、撃っているかどうかを画面の外——曳光弾や
        // 音——に頼らせないための唯一の表示になる。
        //
        // 砲座ではなく手元のキーを読む。{@link GunStations#pulled} が見ている引き金の記録は、砲手自身の報告が
        // サーバーへ届いて初めて埋まる物で（{@link GunStations#setTrigger}）、報告した本人のクライアントには
        // 最後まで空のままだ。この表示が答えるべきなのは「自分は今引いているか」であり、それを知っているのは
        // キーの方である。
        graphics.fill(left, y + 1, left + 7, y + 8, WHITE);

        if (!Minecraft.getInstance().options.keyAttack.isDown()) {
            graphics.fill(left + 1, y + 2, left + 6, y + 7, INVERSE_TEXT);
        }

        // 砲が水平線より下を向いていれば対地。上を向けていれば対空だ。砲手が切り替える物ではなく、砲が今
        // どちらを見ているかの結果である。
        line(graphics, font, Attitude.elevation(bore) > 0.0F ? "A-G" : "A-A", left + 12, y, WHITE);
        // 見越しを出す相手を掴んでいるか。掴んでいれば砲手はマークを追えばよく、掴んでいなければ全部手動だ。
        line(graphics, font, sight != null && sight.target() != null ? "TRK" : "MAN", left + 44, y, WHITE);
        // 視野。照準キーで狭まる。AimZoom 参照。
        line(graphics, font, AimZoom.isAiming() ? "NARO" : "WIDE", left + 76, y, WHITE);

        y += 14;

        // 測距が答えを持っているか。持っていなければ距離欄は推測値ですらない。
        line(graphics, font, Double.isNaN(slant) ? "NORAY" : estimated ? "RAY EST" : "RAY", left, y,
                Double.isNaN(slant) ? DIM : WHITE);
        y += 12;

        line(graphics, font, "FOV " + Math.round(fieldOfView()), left, y, DIM);
        y += 12;

        // センサーの種別と極性。極性キーで入れ替わる。
        line(graphics, font, ThermalView.isShowing() ? "IR" : "TV", left, y, DIM);
        y += 12;

        // 照準基準。砲が可動端に着いていれば代わりにそれを言う。ガンカメラでは砲手の頭が砲の可動範囲へ縛られて
        // いるので（{@link GunCamera#rein}）、端に着いたことは画面が止まる以外に伝わらない——マウスが効かなく
        // なったのか、砲がそれ以上振れないのかを、砲手が区別できる必要がある。
        if (atStop(stations, laid, station)) {
            inverse(graphics, font, "LIMIT", left, y);
        } else {
            line(graphics, font, "BORE", left, y, DIM);
        }
    }

    /**
     * 右上の数値欄。砲が向いている方位、そこまでの距離、機体の対地高度、そして映像の極性。
     *
     * <p>方位は機首ではなく<em>砲</em>の物だ。砲手が誰かに場所を伝えるとき使うのはこちらで、機体がどちらを
     * 向いて飛んでいるかは砲手の仕事に何も足さない。
     */
    private static void readout(GuiGraphics graphics, Font font, Quaternionf bore) {
        int right = graphics.guiWidth() - 8;
        int y = 8;
        int heading = Math.floorMod(Math.round(Attitude.heading(bore)) + 180, 360);
        String bearing = String.format(Locale.ROOT, "%03d", heading);
        String range = Double.isNaN(slant) ? "----" : String.format(Locale.ROOT, "%.0f", slant);

        right(graphics, font, range, right, y, WHITE);
        right(graphics, font, bearing + " " + AircraftHud.cardinal(heading),
                right - font.width(range) - 24, y, WHITE);
        y += 12;

        right(graphics, font, Double.isNaN(agl) ? "---- AGL" : String.format(Locale.ROOT, "%.0f AGL", agl),
                right, y, WHITE);
        y += 12;

        right(graphics, font, ThermalView.isWhiteHot() ? "WHOT" : "BHOT", right, y, DIM);
    }

    /**
     * 右端の1文字の列。実機の画面でここに並んでいる操作記号にあたる物で、こちらは<em>今このキーを押せば
     * 何が起きるか</em>を実際のバインドから引いて並べる。
     *
     * <p>飾りで文字を置かない。砲手席は、乗員が自分で見つけられる手掛かりが最も少ない場所だ——操縦桿も無ければ
     * 飛行計器も無く、押せるキーは他の座席と違う。設定画面を開かせる代わりに、押せるキーをその場に出す。
     */
    private static void keys(GuiGraphics graphics, Font font) {
        int right = graphics.guiWidth() - 8;
        int y = graphics.guiHeight() / 2 - 34;

        for (String key : new String[]{
                ModKeyMappings.SENSOR_POLARITY.getTranslatedKeyMessage().getString(),
                ModKeyMappings.SWITCH_SEAT.getTranslatedKeyMessage().getString(),
                ModKeyMappings.OPEN_HOLD.getTranslatedKeyMessage().getString()}) {
            right(graphics, font, key, right, y, DIM);
            y += 14;
        }
    }

    /**
     * 左下の砲の一覧。この機体が積んでいる砲座を全部、口径の名前と残弾で。
     *
     * <p>自分の砲だけでは足りない。AC-130 の砲手は自分が撃っていない砲がまだ弾を持っているかを知る必要がある
     * し、同乗者がいれば、隣の席が何を撃ち尽くしたかもここに出る。受け持っている1つだけを明るく描くので、
     * どれが自分の物かは色が言う。
     */
    private static void guns(GuiGraphics graphics, Font font, GunStations stations, int station) {
        int left = 8;
        int count = stations.count();
        int y = graphics.guiHeight() - 30 - 12 * count;

        for (int index = 0; index < count; index++) {
            ResourceLocation gun = stations.weaponOf(index);
            String name = stations.station(index).label().toUpperCase(Locale.ROOT);
            String line = gun == null ? name + "  --" : name + "  " + stations.rounds(index);

            line(graphics, font, line, left, y, index == station ? WHITE : DIM);
            y += 12;
        }
    }

    /**
     * 下段。撃てる状態か、そして今が何時か。
     *
     * <p>時刻を出すのは、砲手が夜と昼を映像から判断できないからだ。赤外線の画面は昼も夜も同じに見える——それが
     * この装置の存在意義そのものである——ので、機外に出たとき何が待っているかを言う物が要る。
     */
    private static void status(GuiGraphics graphics, Font font, AircraftEntity aircraft, GunStations stations,
            AircraftDefinition.Station laid, int station, @Nullable ResourceLocation gun, int centreX) {
        int y = graphics.guiHeight() - 18;
        String name = laid.label().toUpperCase(Locale.ROOT);

        if (gun == null) {
            // 空の旋回パイロン。砲座はあるが、そこに撃つ物が載っていない。
            inverse(graphics, font, name + " NO GUN", centreX - font.width(name + " NO GUN") / 2, y);
        } else if (stations.rounds(station) <= 0) {
            inverse(graphics, font, name + " EMPTY", centreX - font.width(name + " EMPTY") / 2, y);
        } else {
            String ready = name + "  RDY";

            line(graphics, font, ready, centreX - font.width(ready) / 2, y, WHITE);
        }

        long time = aircraft.level().getDayTime() % 24000L;
        // Minecraft の1日は 24000 tick で、0 tick が朝6時。
        String clock = String.format(Locale.ROOT, "%02d:%02d",
                (time / 1000L + 6L) % 24L, time % 1000L * 60L / 1000L);

        right(graphics, font, clock, graphics.guiWidth() - 8, y, WHITE);
    }

    // ------------------------------------------------------------------
    // 数値
    // ------------------------------------------------------------------

    /**
     * 砲腔線に沿った地面までの距離と、機体の対地高度。1tickに1度だけ求める。
     *
     * <p>ロード範囲の外まで届かせるため {@link Terrain} を通す。砲手が撃っている物はほぼ常にクライアントが
     * チャンクを持つ範囲の外にあり、ブロックだけを問う走査は、砲手が距離を最も必要とする高度でちょうど空を
     * 返してくる。仮定した床の上で見つけた答えはそうと印を付けて返ってくるので、画面も {@code RAY EST} と
     * 断ってから数字を出す。
     */
    private static void measure(Minecraft minecraft, AircraftEntity aircraft, Quaternionf bore) {
        long now = aircraft.level().getGameTime();

        if (now == ranged) {
            return;
        }

        ranged = now;

        Vec3 from = minecraft.gameRenderer.getMainCamera().getPosition();
        Terrain.Ground ground = Terrain.along(aircraft.level(), from, Attitude.nose(bore), REACH, aircraft);

        slant = ground == null ? Double.NaN : from.distanceTo(ground.point());
        estimated = ground != null && ground.estimated();

        double surface = Terrain.surface(aircraft.level(), aircraft.position());

        agl = Double.isNaN(surface) ? Double.NaN : Math.max(aircraft.getY() - surface, 0.0);
    }

    /** このフレームでワールドが描かれている視野角。照準キーで狭まっている分をそのまま出す。 */
    private static double fieldOfView() {
        return Minecraft.getInstance().options.fov().get() / AimZoom.factor();
    }

    /** 砲が可動端に着いているか。方位でも仰角でも、どちらかが端なら着いている。 */
    private static boolean atStop(GunStations stations, AircraftDefinition.Station laid, int station) {
        if (!stations.isLaid(station)) {
            return false;
        }

        float yaw = stations.yawOf(station);
        float pitch = stations.pitchOf(station);

        return Math.abs(yaw - (laid.bearing() - laid.traverse())) < STOP_MARGIN
                || Math.abs(yaw - (laid.bearing() + laid.traverse())) < STOP_MARGIN
                || Math.abs(pitch - laid.elevation()) < STOP_MARGIN
                || Math.abs(pitch + laid.depression()) < STOP_MARGIN;
    }

    // ------------------------------------------------------------------
    // 描き方
    // ------------------------------------------------------------------

    private static void line(GuiGraphics graphics, Font font, String text, int x, int y, int colour) {
        graphics.drawString(font, text, x, y, colour, true);
    }

    private static void right(GuiGraphics graphics, Font font, String text, int x, int y, int colour) {
        graphics.drawString(font, text, x - font.width(text), y, colour, true);
    }

    /**
     * 反転表示。地を白く塗り、その上に暗い文字を置く。
     *
     * <p>単色の画面で注意を引く唯一の方法だ。色を変えても、白熱表示の明るい地面の上では何色も同じように
     * 消える。反転なら地の明暗に関わらず必ず読めるし、実物の単色画面が昔からそうしてきた。
     */
    private static void inverse(GuiGraphics graphics, Font font, String text, int x, int y) {
        graphics.fill(x - 2, y - 2, x + font.width(text) + 2, y + 9, INVERSE_GROUND);
        graphics.drawString(font, text, x, y, INVERSE_TEXT, false);
    }
}
