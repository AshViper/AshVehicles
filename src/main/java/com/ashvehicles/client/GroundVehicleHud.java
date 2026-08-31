package com.ashvehicles.client;

import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.sensor.Iff;
import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.weapon.WeaponDefinition;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * 地上車両に搭乗している間、世界の上に描かれる計器類。
 *
 * <p>戦車の乗員は、航空機の乗員が読む物をほとんど読まない。高度は無い。見せる価値のある姿勢も無い——車体は地面が
 * 決める姿勢で寝ており、それについてできることは何も無い——し、維持すべき対気速度も無い。代わりにあるのが砲であり、
 * ここの全ては砲についての物だ。
 *
 * <p><b>照準は選択中の兵装によって別の計器になる。</b>航空機と同じ理由で、兵装はその照準方法に沿って表示される。
 * 砲は砲塔を据えて照準するので、弾が落ちる地点のマークと、さらに——ファイルがレーダーを与えている車両に限り——動く
 * 目標に対して弾が届くには砲身がどこにあるべきかを示す2つ目のマークが付く。{@link GunSight} 参照。あれは機体自身の
 * 照準器に砲塔について問い合わせた物だ。ミサイルはそもそも照準しない。<em>与えられる</em>のだから、描くのはシーカー
 * の円錐と捕捉対象を囲む枠になる。
 *
 * <p><b>砲のマークは環であり、画面上で唯一のマークだ。</b>誰かが搭乗している間バニラの十字線は外される——
 * {@link CrewHudSuppressor} 参照——2つのマークは、乗員が発している1つの問いへの2つの答えであり、乗員は砲が乗っている
 * 方かどうかに関わらず、画面中央にある方で照準してしまうからだ。
 *
 * <p><b>それでも画面上のマークではなくワールド上のマークである。</b>砲は視界の中央へ据えられるので環もそこへ落ち着き
 * 両者は一致する——ただし砲塔が追い付いてからだ。乗員は好きな方を見るが砲塔は毎tick数度で追うので、旋回の最初の1秒
 * ほどは環が中央から大きく外れる。それが照準の伝えていることだ。しかも環は砲身の延長線ではなく砲が据えられた<em>点</em>
 * に乗るので、方向だけでなく距離も読める。目標の手前の尾根に当たる弾は、環を尾根の上に置く。
 *
 * <p>その隣が {@link PlanView}。機体そのものを真上から見た図で、視線がパネル上方向、車体はその下で振れる。砲塔を真横
 * へ据えた戦車の運転手には、車体の向きを知る他の手段が無いし、砲の向きへ発進するのは溝に落ちる古典的な方法だ。
 *
 * <p>そして照準の反対側が {@link HitReadout}。直近数発が目標のどこに当たったかを示す。ここの他の全ては「弾を撃ち出す」
 * ことについての物だが、これだけが「着弾したとき何が起きたか」を伝える。戦車砲の交戦距離では、乗員が自分で見て取れる
 * 情報ではないからだ。
 *
 * <p>全て、全クライアントへ届く状態から読む。だから搭乗者にも乗員と同じ計器が見え、0並びのパネルにはならない。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class GroundVehicleHud implements LayeredDraw.Layer {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "vehicle_hud");

    /** 表示が琥珀色に変わる、車体残存率の閾値。 */
    private static final float LOW_HEALTH = 0.3F;
    /** 残弾表示が琥珀色に変わる閾値。交戦2回分。 */
    private static final int LOW_ROUNDS = 6;

    private static final int RELOAD_BAR_WIDTH = 62;
    /** 装填バーの空の部分。存在はするが、埋まった部分と目立ち合わない色。 */
    private static final int TRACK = 0x40FFFFFF;

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, ID, new GroundVehicleHud());
    }

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null || minecraft.options.hideGui) {
            return;
        }

        if (!(minecraft.player.getVehicle() instanceof GroundVehicleEntity vehicle)) {
            return;
        }

        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        int centreX = graphics.guiWidth() / 2;
        int centreY = graphics.guiHeight() / 2;

        // どちらか一方で、両方は無い。トリガーがどの兵装を撃つかが、乗員がどの照準を覗いているかだ。1画面に2つの
        // マークは、1つの問いへの2つの答えになってしまう。
        if (vehicle.isMissileMode()) {
            drawSeeker(graphics, minecraft, vehicle, partialTick, centreX, centreY);
        } else {
            drawGunMark(graphics, minecraft, vehicle, partialTick, centreX, centreY);
        }

        drawCompass(graphics, minecraft.font, vehicle, partialTick, centreX, centreY);

        // 機体の平面図は隅そのものへ置き、数値表示はその分ずらす。両者が重なるのではなく左下を分け合うようにする
        // ためだ。
        PlanView.draw(graphics, vehicle, partialTick);
        drawStatus(graphics, minecraft.font, vehicle, partialTick, PlanView.SIZE + 6);
        drawCrew(graphics, minecraft.font, vehicle);
        // 直近の着弾があれば、その結果。独立レイヤーではなく乗員自身の計器から描くので、何かに搭乗している間だけ
        // 表示され、降りた瞬間に消える。
        HitReadout.draw(graphics, minecraft.font);
        // どちらの計器も、ファイルがレーダーを与えている機体だけが描く。全ての発射機がそうで、戦車は1台もそうでない。
        RadarDisplay.draw(graphics, minecraft.font, vehicle);
    }

    /**
     * 弾の落着点を、画面中央ではなくワールド上に描く。動く目標に対しては、そこへ届かせるために砲身がどこにあるべきかも
     * 描く。
     *
     * <p>薬室に弾があれば緑、無ければ琥珀。撃てるかどうかと、どこを向いているかが一目で分かる。
     */
    private static void drawGunMark(GuiGraphics graphics, Minecraft minecraft, GroundVehicleEntity vehicle,
            float partialTick, int centreX, int centreY) {
        GunSight.Solution sight = GunSight.solve(vehicle);

        if (sight == null) {
            return;
        }

        float focal = AircraftHud.focalLength(minecraft, graphics);
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        // 前tickの値を読むのではなく、このフレームの砲腔方向から組み直す。1tick古いのはマークまでの距離だけであり、
        // マークは砲身の動きと同じなめらかさで追従する。
        Vec3 muzzle = sight.bore().muzzle(partialTick);
        Vec3 bore = sight.bore().direction(partialTick);
        Vec3 point = muzzle.add(bore.scale(sight.pipperRange())).add(sight.pipperDrop());
        int colour = vehicle.isLoaded() ? AircraftHud.GREEN : AircraftHud.WARNING;
        int[] mark = AircraftHud.project(minecraft, point.subtract(camera).normalize(), focal, centreX, centreY);

        if (mark != null) {
            int x = mark[0];
            int y = mark[1];

            // 照準点を囲む環と、その中央の点。機体の機関砲と同じマークだ——AircraftHud.drawGunSight 参照。中央は
            // 点を除いて開けてあるので、撃たれる対象が、それを指すマークに隠れない。
            AircraftHud.circle(graphics, x, y, 9, colour);
            graphics.fill(x - 1, y - 1, x + 1, y + 1, colour);

            // 環の両脇のスタジア線。用途はまさにそれで、既知の幅を持つ地上の物がその間に収まれば、距離が分かる。
            graphics.fill(x - 15, y - 3, x - 14, y + 4, colour);
            graphics.fill(x + 15, y - 3, x + 16, y + 4, colour);

            if (sight.struck()) {
                String text = Math.round(sight.pipperRange()) + " m";

                graphics.drawString(minecraft.font, text, x - minecraft.font.width(text) / 2, y + 18,
                        AircraftHud.DIM, true);
            }
        }

        drawLead(graphics, minecraft, sight, partialTick, focal, camera, centreX, centreY);
    }

    /**
     * 見越し点。今撃った弾が到達する頃に目標がいる位置へ菱形を置き、弾自身の落下分だけ持ち上げる。砲のマークを菱形へ
     * 合わせて撃てばよい。
     *
     * <p>これは対空砲架のための物で、戦車は与えられない。数百ブロック先を横切る機体は1秒近い飛翔時間の彼方にあり、
     * 機体自身に砲身を据える砲手は機体が<em>いた</em>場所に据えている——だが「どこにいるか」を知るには距離と変化率を
     * 測るレーダーが要るので、そもそも提示されるのはレーダー付きの車両だけだ。判断は {@code GunSight.leads} が行い、
     * ここは返ってきた物を描く以外に何も要らない。戦車では目標無しが返る。目標が弾の到達距離の外なら暗く、内側なら
     * 緑、砲身が今撃てば当たる程度まで近付けば——文字付きで——琥珀になる。
     */
    private static void drawLead(GuiGraphics graphics, Minecraft minecraft, GunSight.Solution sight,
            float partialTick, float focal, Vec3 camera, int centreX, int centreY) {
        Entity target = sight.target();

        if (target == null || target.isRemoved()) {
            return;
        }

        // 見越しを算出したtickから目標は動いているが、オフセットは動いていない。だからマークは、このフレームで目標が
        // 描かれる位置に乗って一緒に動く。
        Vec3 lead = target.getPosition(partialTick)
                .add(0.0, target.getBbHeight() * 0.5, 0.0)
                .add(sight.leadOffset());
        int[] mark = AircraftHud.project(minecraft, lead.subtract(camera).normalize(), focal, centreX, centreY);

        if (mark == null) {
            return;
        }

        int colour = !sight.inRange() ? AircraftHud.DIM
                : sight.onTarget() ? AircraftHud.WARNING : AircraftHud.GREEN;

        AircraftHud.diamond(graphics, mark[0], mark[1], 6, colour);

        String reach = Math.round(sight.targetRange()) + " m";
        graphics.drawString(minecraft.font, reach, mark[0] - minecraft.font.width(reach) / 2, mark[1] + 10,
                colour, true);

        if (sight.inRange() && sight.onTarget()) {
            String cue = "SHOOT";
            graphics.drawString(minecraft.font, cue, mark[0] - minecraft.font.width(cue) / 2, mark[1] + 20,
                    AircraftHud.WARNING, true);
        }
    }

    /**
     * ミサイル照準。シーカーが見られる円錐と、捕捉対象を囲む枠。
     *
     * <p>ここに照準点は無い。ミサイルは目標へ据えるのではなく手渡されるので、乗員が実際にやっているのは、目標を環の中へ
     * 入れ、シーカーのキーを押したままロックが閉じるまで砲架を保つことだ——だから描く価値があるのは「どこを見られるか」
     * と「どこまで進んだか」の2つになる。環はシーカー自身の円錐を実寸で描いた物だ。目標をその中へ入れて捕捉させれば
     * ロックは成立するし、外にいる限り乗員がどれだけ待っても何も起きない。キーを押さなければ、環の中にいる物も掴まない
     * ——{@link com.ashvehicles.client.ModKeyMappings#RADAR_LOCK} 参照。
     */
    private static void drawSeeker(GuiGraphics graphics, Minecraft minecraft, GroundVehicleEntity vehicle,
            float partialTick, int centreX, int centreY) {
        WeaponDefinition missile = missileOf(vehicle);

        if (missile == null) {
            return;
        }

        // 座標へ飛ぶ弾はシーカーを持たないので、環も、進行度も、捕捉枠も描く物が無い。代わりに描くのは
        // 「どこを撃つと言ったか」だ。drawLaunchPoint 参照。
        if (vehicle.laysPoint()) {
            drawLaunchPoint(graphics, minecraft, vehicle, partialTick, centreX, centreY);

            return;
        }

        float focal = AircraftHud.focalLength(minecraft, graphics);
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        Vec3 rail = vehicle.turretToWorld(vehicle.getStats().launcher().rail(), partialTick);
        Vec3 bore = vehicle.getAimDirection(partialTick);
        boolean locked = vehicle.isSeekerLocked();
        boolean loaded = vehicle.getMissiles() > 0 && vehicle.getMissileReload() <= 0;
        int[] boresight = AircraftHud.project(minecraft, rail.add(bore.scale(64.0)).subtract(camera).normalize(),
                focal, centreX, centreY);

        if (boresight != null && missile.guidance().isPresent()) {
            WeaponDefinition.Guidance guidance = missile.guidance().get();
            // 発射前にレーダーで指示されるレーダー誘導ミサイルでは、弾のシーカーではなくレーダー自身の走査範囲を使う
            // ——TargetLock#bestCandidate 参照。同じ選択を同じやり方で行っており、この環が正直であるべき対象はそれだ。
            boolean radarCued = guidance.seeker() == WeaponDefinition.Guidance.Seeker.RADAR
                    && vehicle.radar().fitted();
            float angle = radarCued ? vehicle.radar().arc() : guidance.lockAngle();
            int radius = Math.round((float) Math.tan(Math.toRadians(angle)) * focal);
            int colour = locked ? AircraftHud.WARNING : loaded ? AircraftHud.GREEN : AircraftHud.DIM;

            AircraftHud.circle(graphics, boresight[0], boresight[1], Mth.clamp(radius, 10, 220), colour);
            graphics.fill(boresight[0] - 1, boresight[1] - 1, boresight[0] + 1, boresight[1] + 1, colour);
        }

        Entity target = vehicle.getSeekerTarget();

        if (target == null || target.isRemoved()) {
            String seeking = "SEEK";
            graphics.drawString(minecraft.font, seeking, centreX - minecraft.font.width(seeking) / 2,
                    centreY + 54, AircraftHud.DIM, true);

            return;
        }

        // 枠は固定位置ではなく目標が実際に画面上にある場所へ描く。だから乗員がまだ見つけていない物を発見する手段にも
        // なる。
        Vec3 middle = target.getPosition(partialTick).add(0.0, target.getBbHeight() * 0.5, 0.0);
        int[] at = AircraftHud.project(minecraft, middle.subtract(camera).normalize(), focal, centreX, centreY);
        // 機体の HMD と同じ扱い。シーカーは味方を避けないので、避けさせずに知らせる。AircraftHud#drawLock 参照。
        boolean friendly = Iff.between(vehicle, target) == Iff.FRIEND;
        int colour = friendly ? AircraftHud.IFF_FRIEND : locked ? AircraftHud.WARNING : AircraftHud.GREEN;

        if (at != null) {
            // ロックが閉じるにつれ枠が締まるので、目標から目を離さずに進行度を読める。
            int half = Math.round(Mth.lerp(vehicle.getSeekerProgress(), 26.0F, 11.0F));

            AircraftHud.corner(graphics, at[0] - half, at[1] - half, 1, 1, colour);
            AircraftHud.corner(graphics, at[0] + half, at[1] - half, -1, 1, colour);
            AircraftHud.corner(graphics, at[0] - half, at[1] + half, 1, -1, colour);
            AircraftHud.corner(graphics, at[0] + half, at[1] + half, -1, -1, colour);
        }

        String status = friendly ? "FRIENDLY" : locked ? "LOCK" : "SEEK";
        graphics.drawString(minecraft.font, status, centreX - minecraft.font.width(status) / 2,
                centreY + 54, colour, true);

        int range = (int) Math.round(vehicle.position().distanceTo(target.position()));
        String reach = range + " m";
        graphics.drawString(minecraft.font, reach, centreX - minecraft.font.width(reach) / 2,
                centreY + 64, AircraftHud.DIM, true);
    }

    /**
     * 座標で狙う発射機の照準。
     *
     * <p><b>据える前に描く物は無い。</b>照準環も、捕捉枠も、十字線の下の地面も出さない——この発射機は見える物を
     * 撃たないので、画面のどこにも「狙っている方向」が無いからだ。代わりに出すのは、目標を入れる盤の開け方
     * 1つ。{@link LaunchPoint} 参照。
     *
     * <p><b>据えた後は点そのものと、そこまでの距離と、撃てるまでの間。</b>座標が視界の中にあれば——普通は無い
     * ——そこに印が乗る。無くても数字は出る。射程は地平線の向こうであり、乗員が確かめられるのは数字の方だ。
     */
    private static void drawLaunchPoint(GuiGraphics graphics, Minecraft minecraft,
            GroundVehicleEntity vehicle, float partialTick, int centreX, int centreY) {
        float focal = AircraftHud.focalLength(minecraft, graphics);
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        Vec3 laid = vehicle.getDesignatedPoint();
        boolean loaded = vehicle.getMissiles() > 0;
        boolean settled = vehicle.getMissileReload() <= 0;

        if (laid == null) {
            String prompt = loaded ? "NO TARGET" : "TUBES EMPTY";

            graphics.drawString(minecraft.font, prompt, centreX - minecraft.font.width(prompt) / 2,
                    centreY + 54, AircraftHud.WARNING, true);

            // 盤の開け方。乗員が自分で見つけられる物ではないし、この車両には他に押す物が無い。実際の
            // バインドから引くので、割り当てを変えれば表示も変わる。
            String key = ModKeyMappings.RADAR_LOCK.getTranslatedKeyMessage().getString()
                    + "  FIRE CONTROL";
            graphics.drawString(minecraft.font, key, centreX - minecraft.font.width(key) / 2,
                    centreY + 64, AircraftHud.DIM, true);

            return;
        }

        int[] at = AircraftHud.project(minecraft, laid.subtract(camera).normalize(), focal, centreX, centreY);
        int colour = settled ? AircraftHud.WARNING : AircraftHud.GREEN;

        if (at != null) {
            AircraftHud.diamond(graphics, at[0], at[1], 7, colour);
            AircraftHud.circle(graphics, at[0], at[1], 12, colour);
        }

        String status = settled ? "TARGET SET" : "ALIGNING";
        graphics.drawString(minecraft.font, status, centreX - minecraft.font.width(status) / 2,
                centreY + 54, colour, true);

        String where = String.format("%d  %d  %d m", Math.round(laid.x), Math.round(laid.z),
                Math.round(vehicle.position().distanceTo(laid)));
        graphics.drawString(minecraft.font, where, centreX - minecraft.font.width(where) / 2,
                centreY + 64, AircraftHud.DIM, true);
    }

    /** 発射筒の弾。積んでいない車両では null。 */
    @Nullable
    private static WeaponDefinition missileOf(GroundVehicleEntity vehicle) {
        return vehicle.getStats().launcher().missile().map(Definitions::weapon).orElse(null);
    }

    /** 車体の指向を、機体と同じコンパスで示す。 */
    private static void drawCompass(GuiGraphics graphics, Font font, GroundVehicleEntity vehicle,
            float partialTick, int centreX, int centreY) {
        int heading = Math.floorMod(Math.round(Attitude.heading(vehicle.getAttitude(partialTick))) + 180, 360);
        String compass = heading + "  " + AircraftHud.cardinal(heading);

        graphics.drawString(font, compass, centreX - font.width(compass) / 2, centreY - 78,
                AircraftHud.GREEN, true);
        graphics.fill(centreX - 1, centreY - 66, centreX + 1, centreY - 62, AircraftHud.GREEN);
    }

    /** 車両の残存度、速度、そして兵装が伝えること。 */
    private static void drawStatus(GuiGraphics graphics, Font font, GroundVehicleEntity vehicle,
            float partialTick, int indent) {
        int left = 8 + indent;
        int bottom = graphics.guiHeight() - 8;

        float health = vehicle.getHealth();
        int healthColour = vehicle.getHealthFraction() <= LOW_HEALTH ? AircraftHud.WARNING : AircraftHud.GREEN;
        AircraftHud.value(graphics, font,
                String.format("HP %d/%d", Math.round(health), Math.round(vehicle.getMaxHealth())),
                left, bottom - 52, healthColour);

        // 1ブロック=1m、1秒=20tick。符号付きにするのは、戦車が生涯のかなりを後進で過ごすし、運転手にはどちらか
        // 伝えるべきだからだ。
        float speed = vehicle.getSpeed();
        int kmh = (int) Math.round(Math.abs(speed) * 20.0 * 3.6);
        String gear = speed < -0.001F ? " R" : "";
        AircraftHud.value(graphics, font, kmh + " km/h" + gear, left, bottom - 42);

        // 機関銃。下の2行のどちらにも属さない。決して選択されない——ただそこにある——ので、トリガーが何を向いていても
        // 表示するし、2列目に置くことで「乗員が代わりに撃てる3つ目の物」に見えないようにする。ベルトが尽きたら琥珀に
        // する。二度見に値するのはそれだけだ。
        if (vehicle.hasCoaxial()) {
            int belt = vehicle.getCoaxRounds();

            AircraftHud.value(graphics, font, String.format("MG %d/%d", belt, vehicle.getCoaxCapacity()),
                    left + 84, bottom - 20, belt > 0 ? AircraftHud.GREEN : AircraftHud.WARNING);
        }

        boolean missiles = vehicle.isMissileMode();

        if (missiles) {
            drawTubes(graphics, font, vehicle, left, bottom);
        } else if (vehicle.getStats().armament().exists()) {
            drawGun(graphics, font, vehicle, left, bottom);
        } else {
            return;
        }

        // 砲塔内で砲身がどうなっているか。ワールド上のマークでは示せない情報だ。斜面のマークは仰角10度でも2度でも
        // 同じに見えるし、頭上の機体に対しては「砲架がストッパーに当たるまであとどれだけか」が、掃射できるか1秒を
        // 無駄にするかの違いになる。
        int elevation = Math.round(vehicle.getGunPitch(partialTick));
        AircraftHud.value(graphics, font, String.format("ELV %+d°", elevation), left + 84, bottom - 32);

        // 両方積む車両では、トリガーがどちらを撃つか。1種しか積まない車両では何も出さない。答えが疑わしくなることは
        // 無いからだ。
        if (vehicle.hasMissiles() && vehicle.getStats().armament().exists()) {
            AircraftHud.value(graphics, font, missiles ? "SEL MSL" : "SEL GUN", left + 84, bottom - 42);
        }
    }

    /** 砲。残弾と装填の進行。 */
    private static void drawGun(GuiGraphics graphics, Font font, GroundVehicleEntity vehicle, int left, int bottom) {
        int rounds = vehicle.getRounds();

        AircraftHud.value(graphics, font, String.format("RDS %d/%d", rounds, vehicle.getRoundCapacity()),
                left, bottom - 32, rounds > LOW_ROUNDS ? AircraftHud.GREEN : AircraftHud.WARNING);

        if (vehicle.getRounds() <= 0) {
            AircraftHud.value(graphics, font, "NO ROUNDS", left, bottom - 20, AircraftHud.WARNING);

            return;
        }

        if (vehicle.isLoaded()) {
            AircraftHud.value(graphics, font, "LOADED", left, bottom - 20);

            return;
        }

        drawWait(graphics, left, bottom - 20, vehicle.getReload(), vehicle.getReloadTicks());
    }

    /** 発射筒。残ミサイル数と、次弾までの待ち時間。 */
    private static void drawTubes(GuiGraphics graphics, Font font, GroundVehicleEntity vehicle,
            int left, int bottom) {
        int tubes = vehicle.getMissiles();

        AircraftHud.value(graphics, font, String.format("MSL %d/%d", tubes, vehicle.getMissileCapacity()),
                left, bottom - 32, tubes > 0 ? AircraftHud.GREEN : AircraftHud.WARNING);

        if (tubes <= 0) {
            AircraftHud.value(graphics, font, "TUBES EMPTY", left, bottom - 20, AircraftHud.WARNING);

            return;
        }

        if (vehicle.getMissileReload() <= 0) {
            // 「準備完了」と「発射可能」は別だ。追う相手の無い誘導弾は筒に留まるし、乗員にはどちらが妨げているのかを
            // 伝えるべきだ。座標へ飛ぶ弾では、妨げているのがロックではなく「まだどこも指していない」ことになる。
            boolean armed = vehicle.laysPoint() ? vehicle.getDesignated() != null : vehicle.isSeekerLocked();
            String state = armed ? "READY" : vehicle.laysPoint() ? "NO TARGET" : "NO LOCK";

            AircraftHud.value(graphics, font, state, left, bottom - 20,
                    armed ? AircraftHud.GREEN : AircraftHud.WARNING);

            return;
        }

        drawWait(graphics, left, bottom - 20, vehicle.getMissileReload(), vehicle.getMissileReloadTicks());
    }

    /**
     * 次弾までの待ち時間。カウントダウンに応じて埋まっていくバー。
     *
     * <p>秒数ではなくバーにしてある。乗員が実際に判断しているのは「留まるか後退するか」であり、それは残り時間が何tick
     * かではなく、待ちがどれだけ残っているかについての問いだからだ。
     */
    private static void drawWait(GuiGraphics graphics, int x, int y, int left, int total) {
        float done = Mth.clamp(1.0F - (float) left / Math.max(total, 1), 0.0F, 1.0F);

        graphics.fill(x - 2, y - 2, x + RELOAD_BAR_WIDTH + 2, y + 8, AircraftHud.SHADOW);
        graphics.fill(x, y, x + RELOAD_BAR_WIDTH, y + 6, TRACK);
        graphics.fill(x, y, x + Math.round(RELOAD_BAR_WIDTH * done), y + 6, AircraftHud.WARNING);
    }

    private static void drawCrew(GuiGraphics graphics, Font font, GroundVehicleEntity vehicle) {
        List<Entity> aboard = vehicle.getPassengers();

        if (aboard.isEmpty()) {
            return;
        }

        Entity commander = vehicle.getControllingPassenger();
        int right = graphics.guiWidth() - 8;
        int y = graphics.guiHeight() - 8 - aboard.size() * 10;

        for (Entity rider : aboard) {
            String name = (rider == commander ? "C  " : "-  ") + rider.getName().getString();

            graphics.drawString(font, name, right - font.width(name), y,
                    rider == commander ? AircraftHud.GREEN : AircraftHud.DIM, true);
            y += 10;
        }
    }
}
