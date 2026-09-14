package com.ashvehicles.client;

import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.sensor.Iff;
import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.weapon.Magazine;
import com.ashvehicles.weapon.TurretStations;
import com.ashvehicles.weapon.WeaponDefinition;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.network.chat.Component;
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
 * <p><b>環は2つあり、別々の問いに答える。</b>バニラの十字線は搭乗中ずっと外してある——{@link CrewHudSuppressor}
 * 参照。あれは砲について何も知らないのに画面中央に居座るので、残せば誤った方に砲を据えろという誘いになる。
 * 代わりに置くのがこの2つだ。明るい方は<em>弾がどこへ落ちるか</em>、暗く小さい中央の方は<em>乗員が今どこを
 * 見ているか</em>——砲塔が追っている先そのもの。{@link #drawAimPoint} 参照。
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

    /**
     * 燃料計が琥珀になる残量。
     *
     * <p>0.2 は機体側の BINGO と同じ値。戦車には帰投という決断こそ無いが、「補給に戻るか、この一戦を
     * 受けるか」は同じ問いだ。
     */
    private static final float LOW_FUEL = 0.2F;
    /** 残弾表示が琥珀色に変わる閾値。交戦2回分。 */
    private static final int LOW_ROUNDS = 6;

    /**
     * 画面中央の環の半径。
     *
     * <p>砲の環（9）より内側に収まる大きさにしてある。砲塔が追い付いて2つが重なった時に入れ子になるのが
     * 狙った絵で、「砲が来た」を形1つで言う。
     */
    private static final int AIM_RING = 4;

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

        drawAimPoint(graphics, centreX, centreY);

        // 独立砲塔の砲手は、車両の主砲ではなく自分の砲塔の照準を覗く。運転手はそうならない——1人で乗って
        // いれば全砲塔が自分の物だが、覗いているのは主砲の照準器だ。{@link #gunnedStation} 参照。
        int station = gunnedStation(minecraft, vehicle);

        // どちらか一方で、両方は無い。トリガーがどの兵装を撃つかが、乗員がどの照準を覗いているかだ。1画面に2つの
        // マークは、1つの問いへの2つの答えになってしまう。
        if (station >= 0) {
            drawStationMark(graphics, minecraft, vehicle, station, partialTick, centreX, centreY);
        } else if (vehicle.isMissileMode()) {
            drawSeeker(graphics, minecraft, vehicle, partialTick, centreX, centreY);
        } else {
            drawGunMark(graphics, minecraft, vehicle, partialTick, centreX, centreY);
        }

        drawCompass(graphics, minecraft.font, vehicle, partialTick, centreX, centreY);

        // ここから下は計器で、機体のそれと同じく1段小さく描く。窓の外に対応する印——照準と方位——は上に
        // 済ませてあり、あちらは縮まない。{@link HudScale} 参照。
        //
        // 機体の平面図は隅そのものへ置き、数値表示はその分ずらす。両者が重なるのではなく左下を分け合う
        // ようにするためだ。
        HudScale.push(graphics);

        PlanView.draw(graphics, vehicle, partialTick);
        drawPanels(graphics, minecraft.font, vehicle, partialTick);

        HudScale.pop(graphics);

        // 直近の着弾があれば、その結果。独立レイヤーではなく乗員自身の計器から描くので、何かに搭乗している間だけ
        // 表示され、降りた瞬間に消える。
        HitReadout.draw(graphics, minecraft.font);
        // どちらの計器も、ファイルがレーダーを与えている機体だけが描く。全ての発射機がそうで、戦車は1台もそうでない。
        RadarDisplay.draw(graphics, minecraft.font, vehicle);
    }

    /**
     * この画面の持ち主が回している独立砲塔。運転手と、砲塔を持たない乗員では {@code -1}。
     *
     * <p>運転手を外すのは、あの席が主砲の砲手席でもあるからだ。1人で乗っていれば全砲塔がその人の物に
     * なる（{@link com.ashvehicles.weapon.TurretStations} 参照）が、覗いている照準は主砲の物であり、
     * 画面中央のマークもそれでなければならない。
     */
    private static int gunnedStation(Minecraft minecraft, GroundVehicleEntity vehicle) {
        if (vehicle.getControllingPassenger() == minecraft.player) {
            return -1;
        }

        return vehicle.getTurrets().liveStationOf(minecraft.player);
    }

    /**
     * 独立砲塔の弾の落着点。車両の主砲に対する {@link #drawGunMark} とまったく同じ物で、解く砲が違うだけ。
     *
     * <p>山なりに撃つ砲の分岐（{@link #drawFallMark}）はここには無い。あれは車両の主砲について解く物で、
     * 独立砲塔に榴弾砲を積んだ車両はまだ無い。
     */
    private static void drawStationMark(GuiGraphics graphics, Minecraft minecraft, GroundVehicleEntity vehicle,
            int station, float partialTick, int centreX, int centreY) {
        GunSight.Solution sight = GunSight.solve(vehicle, station);

        if (sight == null) {
            return;
        }

        float focal = AircraftHud.focalLength(minecraft, graphics);
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        Vec3 point = sight.bore().muzzle(partialTick)
                .add(sight.bore().direction(partialTick).scale(sight.pipperRange()))
                .add(sight.pipperDrop());
        int colour = vehicle.getTurrets().roundsOf(station) > 0 ? AircraftHud.GREEN : AircraftHud.WARNING;
        int[] mark = AircraftHud.project(minecraft, point.subtract(camera).normalize(), focal, centreX,
                centreY);

        if (mark != null) {
            gunMark(graphics, minecraft.font, mark[0], mark[1], colour,
                    sight.struck() ? Math.round(sight.pipperRange()) + " m" : null);
        }

        drawLead(graphics, minecraft, sight, partialTick, focal, camera, centreX, centreY);
    }

    /**
     * 乗員自身の視線を、画面のちょうど中央に。
     *
     * <p>兵装のマークがどれもワールド上にあるということは、画面上に「今どこを向いているか」を言う物が何も無い
     * ということでもある。砲のマークが中央へ来るのは砲塔が追い付いてからで、旋回の最初の1秒——照準を詰めている
     * まさにその間——乗員は自分が何を注文したのかを見られない。バニラの十字線は外してあるので、他に手掛かりも無い。
     *
     * <p>砲手照準を覗いている間も同じ物が同じ場所にある。あれは視界を砲へ預ける装置ではなく、接眼部へ寄って
     * 倍率を掛けるだけの物だからだ——{@link TurretSight} 参照。照準の中と外で読み方が変わらないのは、そもそも
     * 操作が同じだからで、この環はその「同じ操作」の側に属している。
     *
     * <p><b>撃つ時に見る物ではない。</b>だから小さく、暗い。弾がどこへ落ちるかを言えるのは砲のマークだけで、
     * 砲塔が振れている間と俯角の尽きた斜面では、2つは意図的に別の場所にある。同じ明るさで並べれば、乗員は近い方
     * ——つまり中央——で照準してしまう。
     */
    private static void drawAimPoint(GuiGraphics graphics, int centreX, int centreY) {
        AircraftHud.circle(graphics, centreX, centreY, AIM_RING, AircraftHud.DIM);
    }

    /**
     * 弾の落着点を、画面中央ではなくワールド上に描く。動く目標に対しては、そこへ届かせるために砲身がどこにあるべきかも
     * 描く。
     *
     * <p>薬室に弾があれば緑、無ければ琥珀。撃てるかどうかと、どこを向いているかが一目で分かる。
     *
     * <p><b>山なりに撃つ砲だけは、照準器ではなく {@link GunReach} が点を出す。</b>榴弾は照準器が世界へ問い合わせる
     * 512 ブロックの遥か先へ落ちるので、あちらに答えを求めれば基準距離 300 ブロックの印しか返らない。見越しは
     * どちらでも同じように描く——あれが要求するのは飛翔時間だけで、それは照準器が持っている。
     */
    static void drawGunMark(GuiGraphics graphics, Minecraft minecraft, GroundVehicleEntity vehicle,
            float partialTick, int centreX, int centreY) {
        GunSight.Solution sight = GunSight.solve(vehicle);

        if (sight == null) {
            return;
        }

        float focal = AircraftHud.focalLength(minecraft, graphics);
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();

        if (!drawFallMark(graphics, minecraft, vehicle, partialTick, focal, camera, centreX, centreY)) {
            // 前tickの値を読むのではなく、このフレームの砲腔方向から組み直す。1tick古いのはマークまでの距離だけであり、
            // マークは砲身の動きと同じなめらかさで追従する。
            Vec3 muzzle = sight.bore().muzzle(partialTick);
            Vec3 bore = sight.bore().direction(partialTick);
            Vec3 point = muzzle.add(bore.scale(sight.pipperRange())).add(sight.pipperDrop());
            int colour = vehicle.isLoaded() ? AircraftHud.GREEN : AircraftHud.WARNING;
            int[] mark = AircraftHud.project(minecraft, point.subtract(camera).normalize(), focal, centreX,
                    centreY);

            if (mark != null) {
                gunMark(graphics, minecraft.font, mark[0], mark[1], colour,
                        sight.struck() ? Math.round(sight.pipperRange()) + " m" : null);
            }
        }

        drawLead(graphics, minecraft, sight, partialTick, focal, camera, centreX, centreY);
    }

    /**
     * 山なりに撃つ砲の着弾点。この砲がそうでなければ何も描かず false を返し、呼び手は照準器のピッパーへ落ちる。
     *
     * <p>乗員も、砲の脇に立つ者（{@link GunCrewHud}）も同じ物を見る。砲が同じなら弾は同じ場所へ落ちるので、
     * 乗り込んだかどうかでマークが動いてよい理由は無い。
     */
    static boolean drawFallMark(GuiGraphics graphics, Minecraft minecraft, GroundVehicleEntity vehicle,
            float partialTick, float focal, Vec3 camera, int centreX, int centreY) {
        GunReach.Shot shot = GunReach.lobs(vehicle) ? GunReach.solve(vehicle) : null;

        if (shot == null) {
            return false;
        }

        // ピッパーと同じく、1tick 古いのは「どれだけ先か」だけ。マークはこのフレームの砲身から組み直す。
        Vec3 point = vehicle.getMuzzle(partialTick)
                .add(vehicle.getAimDirection(partialTick).scale(shot.alongBore()))
                .add(shot.drop());
        int[] mark = AircraftHud.project(minecraft, point.subtract(camera).normalize(), focal, centreX,
                centreY);

        if (mark == null) {
            return true;
        }

        // 色は薬室が言う——弾があれば緑、無ければ琥珀。地面に届かない弾はどちらでもないので暗い。地面が推測かどうかは
        // 色ではなく距離のチルダで言う。2つの事実を1つの色に重ねれば、どちらも読めなくなる。
        int colour = !shot.lands() ? AircraftHud.DIM
                : vehicle.isLoaded() ? AircraftHud.GREEN : AircraftHud.WARNING;

        gunMark(graphics, minecraft.font, mark[0], mark[1], colour, shot.label());

        return true;
    }

    /**
     * 山なりに撃つ砲の射距離と飛翔時間。直射砲では何も足さない——あちらは砲身を目標へ向ければ当たるので、
     * 距離は読む物ではなくマークが指している物だ。
     *
     * <p>榴弾砲では逆で、仰角は「何ブロック先か」を言わない。155mm は 15 度で 724 m、45 度で 1388 m、70 度で
     * 889 m——同じ距離へ届く据えが 2 つあり、どちらも仰角の数字からは読めない。だから射距離は推し量る物では
     * なく読む物でなければならない。
     */
    static void reach(HudPanel panel, GroundVehicleEntity vehicle) {
        GunReach.Shot shot = GunReach.lobs(vehicle) ? GunReach.solve(vehicle) : null;

        if (shot == null) {
            return;
        }

        // 見えている地面に落ちる弾だけが緑。推測した地面の上と、そもそも落ちない弾は暗い——読み手が、確かな数字と
        // そうでない数字を色で見分けられる。
        panel.pair("RNG", AircraftHud.DIM, shot.label(),
                shot.lands() && !shot.estimated() ? AircraftHud.GREEN : AircraftHud.DIM);

        // 飛翔時間は届く弾にだけ。落ちない弾のそれは「消えるまでの時間」であって、待つ意味のある数字ではない。
        if (shot.lands()) {
            panel.pair("TOF", AircraftHud.DIM, String.format(Locale.ROOT, "%.1f s", shot.seconds()),
                    AircraftHud.DIM);
        }
    }

    /**
     * 砲のマークそのもの。環、中央の点、両脇のスタジア線、そしてその下の距離。
     *
     * <p>乗員の照準器と、砲の脇に立つ者の照準（{@link GunCrewHud}）が同じ絵を描く。答えているのは同じ問い——弾が
     * どこへ落ちるか——であり、乗り込んだ瞬間にマークの読み方が変わってよい理由は無い。距離をどこから得るかだけが
     * 違う。乗員のそれは照準器の弾道で、牽引砲のそれは {@link GunReach} だ。
     *
     * <p>中央は点を除いて開けてある。撃たれる対象が、それを指すマークに隠れないためだ。スタジア線の用途はまさに
     * それで、既知の幅を持つ地上の物がその間に収まれば距離が分かる。
     */
    static void gunMark(GuiGraphics graphics, Font font, int x, int y, int colour, @Nullable String range) {
        AircraftHud.circle(graphics, x, y, 9, colour);
        graphics.fill(x - 1, y - 1, x + 1, y + 1, colour);
        graphics.fill(x - 15, y - 3, x - 14, y + 4, colour);
        graphics.fill(x + 15, y - 3, x + 16, y + 4, colour);

        if (range != null) {
            graphics.drawString(font, range, x - font.width(range) / 2, y + 18, AircraftHud.DIM, true);
        }
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

        if (isBeam(missile)) {
            drawBeam(graphics, minecraft, vehicle, partialTick, focal, centreX, centreY);

            return;
        }
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

    /** 発射筒の弾が視線誘導か。捕捉の手順を持たないので、計器の形がまるごと変わる。 */
    private static boolean isBeam(WeaponDefinition missile) {
        return missile.guidance()
                .map(guidance -> guidance.seeker() == WeaponDefinition.Guidance.Seeker.BEAM)
                .orElse(false);
    }

    /**
     * 視線誘導の照準。環も枠も進行度も無い——捕捉という手順が無いからだ。
     *
     * <p>あるのは砲腔線の印1つと、その意味を言う1語だけ。この弾に対して乗員がすべきことは「照準を目標へ
     * 置き、当たるまで置き続ける」ことの1つしか無いので、計器がそれ以上言えば嘘になる。飛んでいる間は印を
     * 警告色にして、まだ手を離してはいけないことを示す。
     */
    private static void drawBeam(GuiGraphics graphics, Minecraft minecraft, GroundVehicleEntity vehicle,
            float partialTick, float focal, int centreX, int centreY) {
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        Vec3 rail = vehicle.turretToWorld(vehicle.getStats().launcher().rail(), partialTick);
        Vec3 bore = vehicle.getAimDirection(partialTick);
        boolean guiding = !MissileTrack.shots().isEmpty();
        boolean loaded = vehicle.getMissiles() > 0 && vehicle.getMissileReload() <= 0;
        int colour = guiding ? AircraftHud.WARNING : loaded ? AircraftHud.GREEN : AircraftHud.DIM;
        int[] at = AircraftHud.project(minecraft, rail.add(bore.scale(64.0)).subtract(camera).normalize(),
                focal, centreX, centreY);

        if (at != null) {
            // 弾が乗る線の印。実際に据えるのは線であって点ではないので、中央を開けた十字にする。
            graphics.fill(at[0] - 11, at[1], at[0] - 4, at[1] + 1, colour);
            graphics.fill(at[0] + 5, at[1], at[0] + 12, at[1] + 1, colour);
            graphics.fill(at[0], at[1] - 11, at[0] + 1, at[1] - 4, colour);
            graphics.fill(at[0], at[1] + 5, at[0] + 1, at[1] + 12, colour);
            graphics.fill(at[0] - 1, at[1] - 1, at[0] + 2, at[1] + 2, colour);
        }

        String status = guiding ? "GUIDING" : loaded ? "BEAM" : "RELOADING";

        graphics.drawString(minecraft.font, status, centreX - minecraft.font.width(status) / 2,
                centreY + 54, colour, true);

        if (guiding) {
            String hold = "HOLD ON TARGET";

            graphics.drawString(minecraft.font, hold, centreX - minecraft.font.width(hold) / 2,
                    centreY + 64, AircraftHud.DIM, true);
        }
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

        // 「据えていない」と「据えた点がこちらから見えない」は別物だ。後者は弾道弾では普通の状態——マーカーが
        // 届くのは2304ブロックまでで、射程はその26倍ある。同じに扱っていたので、実際には起立して撃てる発射機が
        // 「まだどこも指していない」と表示していた。GroundVehicleEntity#hasDesignation 参照。
        if (!vehicle.hasDesignation()) {
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

        // 印も数字も、点がこちら側にある時だけ。届いていない点の方角は誰にも分からないし、推測して描いた印は
        // 「そこにある」と言ってしまう。状態表示の方は保持だけで決まるので、遠くを撃つ乗員にも出る。
        int[] at = laid == null ? null
                : AircraftHud.project(minecraft, laid.subtract(camera).normalize(), focal, centreX, centreY);
        int colour = settled ? AircraftHud.WARNING : AircraftHud.GREEN;

        if (at != null) {
            AircraftHud.diamond(graphics, at[0], at[1], 7, colour);
            AircraftHud.circle(graphics, at[0], at[1], 12, colour);
        }

        String status = settled ? "TARGET SET" : "ALIGNING";
        graphics.drawString(minecraft.font, status, centreX - minecraft.font.width(status) / 2,
                centreY + 54, colour, true);

        if (laid == null) {
            return;
        }

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

    /**
     * 下の両隅の計器。機体のそれと同じ分け方だ——右下に兵装と残存度、左下にそれ以外。
     *
     * <p>左下は平面図が隅そのものを取っているので、こちらはその分だけ内側へ寄せる。重なるのではなく左下を
     * 分け合う。
     */
    private static void drawPanels(GuiGraphics graphics, Font font, GroundVehicleEntity vehicle,
            float partialTick) {
        int bottom = HudScale.height(graphics) - 8;

        crew(vehicle).bottomLeft(graphics, font, 8 + PlanView.SIZE + 6, bottom);
        arms(vehicle, partialTick).bottomRight(graphics, font, HudScale.width(graphics) - 8, bottom);
    }

    /** 左下。乗員と、車体が今どれだけの速さで動いているか。 */
    private static HudPanel crew(GroundVehicleEntity vehicle) {
        HudPanel panel = new HudPanel();
        List<Entity> aboard = vehicle.getPassengers();

        panel.title("CREW / STATUS");

        if (!aboard.isEmpty()) {
            Entity commander = vehicle.getControllingPassenger();

            for (Entity rider : aboard) {
                panel.crew((rider == commander ? "C " : "- ") + rider.getName().getString(),
                        rider == commander ? AircraftHud.GREEN : AircraftHud.DIM);
            }
        }

        panel.divider();

        // 1ブロック=1m、1秒=20tick。後進かどうかを添えるのは、戦車が生涯のかなりを後進で過ごすし、運転手には
        // どちらか伝えるべきだからだ。
        float speed = vehicle.getSpeed();
        int kmh = (int) Math.round(Math.abs(speed) * 20.0 * 3.6);

        panel.pair("SPD", AircraftHud.DIM, kmh + " km/h" + (speed < -0.001F ? " R" : ""), AircraftHud.GREEN);

        // 燃料。地上車両は書かなくても既定で積んでいる（{@code VehicleChassis.Fuel.GROUND}——容量1000、
        // 全開でおよそ40分）ので、燃料計が無いことは「燃料が無い」ことではなかった。運転手には減っている
        // ものが見えないまま、いつか止まる車両に見えていたはずだ。
        //
        // 割合で出すのは機体側と同じ理由。「あと何単位か」は車種ごとに意味が変わるが、「あと何割か」は
        // どの車両でも同じことを意味する。低残量で琥珀になり、尽きれば赤で言い切る——エンジンが止まった
        // 理由が運転手に分かる必要があるのはその瞬間だけだ。
        //
        // 燃料を積まない車両——容量0の据置マウント——では1行も割かない。動かない数字は読まれなくなり、
        // その時に隣の数字も一緒に読まれなくなる。
        if (vehicle.fuelSetup().fitted()) {
            float fuel = vehicle.getFuelFraction();

            panel.bar("FUEL", fuel, vehicle.isOutOfFuel() ? "DRY" : Math.round(fuel * 100.0F) + "%",
                    fuel <= LOW_FUEL ? AircraftHud.WARNING : AircraftHud.GREEN);
        }

        return panel;
    }

    /** 右下。引き金が何を撃つか、あと何発あるか、いつ撃てるか、そして車体があと何発受けられるか。 */
    private static HudPanel arms(GroundVehicleEntity vehicle, float partialTick) {
        HudPanel panel = new HudPanel();
        GroundVehicleEntity.Armament selected = vehicle.selected();
        boolean missiles = selected == GroundVehicleEntity.Armament.MISSILE;
        boolean gun = vehicle.getStats().armament().exists();
        // 選べる物が2つ以上あって初めて、選択に意味がある。
        int carried = (gun ? 1 : 0) + (vehicle.hasCoaxial() ? 1 : 0) + (vehicle.hasMissiles() ? 1 : 0);

        panel.title("WEAPONS / STATUS");

        // 2種以上積む車両では、トリガーが何を撃つか。1種しか積まない車両では何も出さない。答えが疑わしく
        // なることは無いからだ。
        if (carried > 1) {
            panel.pair("SEL", AircraftHud.DIM, switch (selected) {
                case MISSILE -> "MSL";
                case COAX -> "MG";
                case MAIN -> "GUN";
            }, AircraftHud.GREEN);
        }

        if (missiles) {
            tubes(panel, vehicle);
        } else if (gun) {
            gun(panel, vehicle);
        }

        // 選択中の架台が弾種を積んでいるなら、その内訳。残弾の1行だけでは足りない——上の行が示すのは
        // 選択中の弾種だけなので、他に何がどれだけ残っているかはここにしか出ない。そして「あと1発で
        // 徹甲弾が尽きる」は、砲手が次の1発を撃つ前に知るべきことだ。
        rounds(panel, vehicle, selected);

        if (missiles || gun || vehicle.hasCoaxial()) {
            // 砲塔内で砲身がどうなっているか。ワールド上のマークでは示せない情報だ。斜面のマークは仰角10度でも2度でも
            // 同じに見えるし、頭上の機体に対しては「砲架がストッパーに当たるまであとどれだけか」が、掃射できるか1秒を
            // 無駄にするかの違いになる。
            panel.pair("ELV", AircraftHud.DIM,
                    String.format("%+d°", Math.round(vehicle.getGunPitch(partialTick))), AircraftHud.GREEN);
            // 山なりに撃つ砲では、その仰角が何ブロック先を意味するかが仰角自身からは読めない。reach 参照。
            reach(panel, vehicle);
        }

        // 機関銃。選択されていてもいなくても常に表示する——専用の引き金を持ち続けているので、トリガーが
        // 何を向いていようと撃てるからだ。ベルトが尽きたら琥珀にする。二度見に値するのはそれだけだ。
        if (vehicle.hasCoaxial()) {
            int belt = vehicle.getCoaxRounds();

            panel.pair("MG", AircraftHud.DIM, String.format("%d / %d", belt, vehicle.getCoaxCapacity()),
                    belt > 0 ? AircraftHud.GREEN : AircraftHud.WARNING);

            if (belt <= 0) {
                need(panel, vehicle, GroundVehicleEntity.Armament.COAX);
            }
        }

        // 独立砲塔。この画面の持ち主が回している物だけを並べる——1人で乗っている運転手には全部が並び、
        // 砲手席の乗員には自分の1つだけが出る。
        stations(panel, vehicle);

        float health = vehicle.getHealth();

        panel.divider();
        panel.pair("HP", AircraftHud.DIM,
                String.format("%d / %d", Math.round(health), Math.round(vehicle.getMaxHealth())),
                vehicle.getHealthFraction() <= LOW_HEALTH ? AircraftHud.WARNING : AircraftHud.GREEN);

        return panel;
    }

    /**
     * 自分が回している独立砲塔を1行ずつ。残弾と、装填中かどうか。
     *
     * <p>持っていない砲塔は出さない。他人が回している砲塔の弾数は、読み手が何かできる数字ではないからだ
     * ——1人で乗っていれば全部が自分の物になるので、そのときは全部出る。
     */
    private static void stations(HudPanel panel, GroundVehicleEntity vehicle) {
        TurretStations turrets = vehicle.getTurrets();

        if (!turrets.exists()) {
            return;
        }

        List<Integer> mine = turrets.stationsOf(Minecraft.getInstance().player);

        if (mine.isEmpty()) {
            return;
        }

        panel.divider();

        for (int index : mine) {
            int rounds = turrets.roundsOf(index);
            String label = turrets.station(index).label().toUpperCase(Locale.ROOT);

            panel.pair(label, AircraftHud.DIM,
                    String.format("%d / %d", rounds, turrets.capacityOf(index))
                            + (turrets.reloadOf(index) > 0 ? " …" : ""),
                    rounds > 0 ? AircraftHud.GREEN : AircraftHud.WARNING);
        }
    }

    /**
     * 空になった砲を満たすアイテムの名前。
     *
     * <p>「弾が無い」だけでは半分しか言っていない。乗員が次にすることは弾を取りに行くことで、そのために
     * 要るのは「何を」だ。答えは車両ファイルと兵装ファイルから求まる物であって、覚えている物ではない。
     * {@link AmmoHint} 参照。
     */
    private static void need(HudPanel panel, GroundVehicleEntity vehicle,
            GroundVehicleEntity.Armament station) {
        Component item = AmmoHint.forStation(vehicle, station);

        if (item != null) {
            panel.pair("NEED", AircraftHud.DIM, item.getString(), AircraftHud.WARNING);
        }
    }

    /**
     * <b>積んでいる</b>弾種を1行ずつ。選択中の物が緑、残りは沈める。
     *
     * <p>弾種を並べていない車両では1行も出ない。MOD 内の大半がそれで、そこでは何も変わらない。
     *
     * <p><b>出すのは弾倉の中身であって、その架台が受け付ける一覧ではない。</b>
     * {@link Magazine#types} が答えるのは「この砲に入りうる弾種」——車両ファイルに並んでいる物すべて——で
     * あり、そのまま並べていたので、榴弾しか積んでいない戦車の計器にも徹甲弾と対戦車榴弾の行が 0 発で
     * 並んでいた。乗員が問うのは今何を撃てるかであり、積んでいない弾種の名前は、切り替えキーで回せる
     * 選択肢に見えるぶん邪魔ですらある。補給に要る名前は下の NEED 行が別に出す。
     *
     * <p>薬室の1発だけは残す。選択中の弾種が尽きても行ごと消えれば、乗員には「何が入っていたか」も
     * 「なぜ撃てないか」も画面から消えることになる。0 発の琥珀はそれ自体が知らせだ。
     */
    private static void rounds(HudPanel panel, GroundVehicleEntity vehicle,
            GroundVehicleEntity.Armament station) {
        List<ResourceLocation> types = Magazine.types(vehicle, station);

        if (types.isEmpty()) {
            return;
        }

        ResourceLocation loaded = Magazine.selected(vehicle, station);

        for (ResourceLocation type : types) {
            int left = Magazine.rounds(vehicle, station, type);
            boolean chambered = type.equals(loaded);

            if (left <= 0 && !chambered) {
                continue;
            }

            panel.pair(roundName(type), chambered ? AircraftHud.GREEN : AircraftHud.DIM,
                    String.valueOf(left),
                    left > 0 ? (chambered ? AircraftHud.GREEN : AircraftHud.DIM) : AircraftHud.WARNING);
        }
    }

    /**
     * 弾種の短い名前。計器に収まる幅で、翻訳を通す。
     *
     * <p>翻訳が無ければ ID のパスをそのまま大文字で出す。パックが足した弾種は言語ファイルを持たない
     * ことがあり、そこで空欄が並ぶより「125MM_APFSDS」と出る方がはるかに役に立つ。
     */
    private static String roundName(ResourceLocation type) {
        String key = "hud.ashvehicles.round." + type.getNamespace() + "." + type.getPath();
        Component name = Component.translatable(key);
        String text = name.getString();

        return key.equals(text) ? type.getPath().toUpperCase(Locale.ROOT) : text;
    }

    /** 砲。残弾と装填の進行。 */
    static void gun(HudPanel panel, GroundVehicleEntity vehicle) {
        int rounds = vehicle.getRounds();

        panel.pair("RDS", AircraftHud.DIM, String.format("%d / %d", rounds, vehicle.getRoundCapacity()),
                rounds > LOW_ROUNDS ? AircraftHud.GREEN : AircraftHud.WARNING);

        if (rounds <= 0) {
            panel.line("NO ROUNDS", AircraftHud.WARNING);
            need(panel, vehicle, GroundVehicleEntity.Armament.MAIN);

            return;
        }

        if (vehicle.isLoaded()) {
            panel.line("LOADED");

            return;
        }

        panel.bar("LOAD", done(vehicle.getReload(), vehicle.getReloadTicks()),
                seconds(vehicle.getReload()), AircraftHud.WARNING);
    }

    /** 発射筒。残ミサイル数と、次弾までの待ち時間。 */
    private static void tubes(HudPanel panel, GroundVehicleEntity vehicle) {
        int tubes = vehicle.getMissiles();

        panel.pair("MSL", AircraftHud.DIM, String.format("%d / %d", tubes, vehicle.getMissileCapacity()),
                tubes > 0 ? AircraftHud.GREEN : AircraftHud.WARNING);

        if (tubes <= 0) {
            panel.line("TUBES EMPTY", AircraftHud.WARNING);
            need(panel, vehicle, GroundVehicleEntity.Armament.MISSILE);

            return;
        }

        if (vehicle.getMissileReload() <= 0) {
            // 「準備完了」と「発射可能」は別だ。追う相手の無い誘導弾は筒に留まるし、乗員にはどちらが妨げているのかを
            // 伝えるべきだ。座標へ飛ぶ弾では、妨げているのがロックではなく「まだどこも指していない」ことになる。
            // 視線誘導には捕捉が無いので、装填されていればいつでも撃てる。座標を据える弾は据えるまで撃てず、
            // シーカーを持つ弾はロックするまで撃てない。妨げているのがどれかを乗員に伝える。
            WeaponDefinition missile = missileOf(vehicle);
            boolean beam = missile != null && isBeam(missile);
            // 保持を問うのであって、マーカーがこちらへ届いているかではない。据えた点が追跡距離の外にある弾道弾
            // では後者は常に偽で、実際には据わっている発射機が「NO TARGET」と表示していた。
            // GroundVehicleEntity#hasDesignation 参照。
            boolean armed = beam || (vehicle.aimsAtPoint()
                    ? vehicle.hasDesignation() : vehicle.isSeekerLocked());
            String state = armed ? "READY" : vehicle.laysPoint() ? "NO TARGET" : "NO LOCK";

            panel.line(state, armed ? AircraftHud.GREEN : AircraftHud.WARNING);

            return;
        }

        panel.bar("LOAD", done(vehicle.getMissileReload(), vehicle.getMissileReloadTicks()),
                seconds(vehicle.getMissileReload()), AircraftHud.WARNING);
    }

    /** 装填がどこまで進んだか。残りtick数を、目盛りが読む向きの割合に直す。 */
    private static float done(int left, int total) {
        return Mth.clamp(1.0F - (float) left / Math.max(total, 1), 0.0F, 1.0F);
    }

    /**
     * 待ちの残り。目盛りの隣に置く数字なので、tickではなく秒で出す。
     *
     * <p>目盛りは「留まるか下がるか」に答え、秒は「あと1回撃てるか」に答える。乗員は交戦の途中で両方を問う。
     */
    private static String seconds(int left) {
        return String.format(java.util.Locale.ROOT, "%.1fs", left / 20.0F);
    }
}
