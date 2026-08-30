package com.ashvehicles.client;

import java.util.List;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.sensor.Contact;
import com.ashvehicles.sensor.Iff;
import com.ashvehicles.weapon.GunStations;
import com.ashvehicles.weapon.WeaponDefinition;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

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
import org.joml.Quaternionf;

/**
 * 機体に搭乗している間、世界の上に描かれる計器類。
 *
 * <p>ここにあるのはパイロットが実際に読む物だ。速度、高度、上下の向き、方位、そしてエンジンと可動部の状態。画面中央
 * には2つのマークがあり、区別する価値がある。ボアサイトは機首の指す方向。飛行経路マーカーは機体が実際に進んでいる
 * 方向で、上昇中やきつい旋回中は同じ場所ではない。両者の隙間が可視化された迎角だ。機関砲を選択すると3つ目が加わる。
 * ピッパー、つまり弾が落ちる位置で、三人称カメラからはこれもボアサイトの位置とは違う——{@link GunSight} 参照。
 *
 * <p>右上隅には {@link HitReadout}。直近数発が撃った相手のどこに当たったかを示す。ここの他の全ては「弾を撃ち出す」
 * ことについての物だが、これだけが「着弾したとき何が起きたか」を伝える。1マイル先への連射の結末は、パイロットが自分で
 * 見届けられる物ではない。
 *
 * <p>全て、全クライアントへ届く状態から読む。だから搭乗者にもパイロットと同じ計器が見え、0並びのパネルにはならない。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class AircraftHud implements LayeredDraw.Layer {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "aircraft_hud");

    static final int GREEN = 0xFF3BE86A;
    static final int DIM = 0xB03BE86A;
    static final int WARNING = 0xFFFF5A3B;
    static final int SHADOW = 0x90000000;
    /** 射出ハンドルの目盛りの、まだ引けていない側。 */
    private static final int EJECT_EMPTY = 0x50FF5A3B;
    /** 同じ目盛りの幅（ピクセル）。 */
    private static final int EJECT_BAR = 60;

    /**
     * IFF の色。味方は青、敵は琥珀、判定の付かない相手は計器の地の色そのまま。
     *
     * <p>敵に赤を使わない。赤はこの計器では「今すぐ何かしろ」の色——ロック、被ロック、ミサイル——であり、
     * チーム戦のスコープに載る物の大半は敵になる。そこを赤で埋めれば、本当に赤くなった1件を見つけられ
     * なくなる。琥珀は緑から十分離れていて、しかもまだ警報ではない。
     *
     * <p>{@link com.ashvehicles.sensor.Iff#UNKNOWN} が緑なのも同じ理由の裏返しで、チームを作っていない
     * ワールドでは全接触がこれになる。そこで画が変わらないことが正しい。
     */
    static final int IFF_FRIEND = 0xFF4FC3F7;
    static final int IFF_HOSTILE = 0xFFE8B33B;

    /**
     * 接触1件を描く色。スコープの点も HMD の菱形も、これ1つから取る。
     *
     * <p><b>味方はロックしていても青のまま。</b> 他は従来どおりロックすれば赤になる。ここが IFF の入って
     * いる意味そのものだ——両方を色で表そうとすると、この計器が最も伝えなければならない1件、つまり味方を
     * 捉えてしまっている接触が、敵を捉えている接触と同じ画になる。ロックされていること自体はスコープなら
     * 上下の括弧が、HMD なら菱形の大きさが言う。
     */
    static int contactColour(Iff iff, boolean locked) {
        if (iff == Iff.FRIEND) {
            return IFF_FRIEND;
        }

        return locked ? WARNING : switch (iff) {
            case HOSTILE -> IFF_HOSTILE;
            default -> GREEN;
        };
    }

    /** 同じ色を、計器の地に沈む明るさで。まだ何も起きていない接触のため。 */
    static int dim(int colour) {
        return (colour & 0x00FFFFFF) | (DIM & 0xFF000000);
    }

    /** 表示が琥珀色に変わる、機体残存率の閾値。 */
    private static final float LOW_HEALTH = 0.3F;

    /**
     * 燃料計が琥珀色に変わる残量の割合。
     *
     * <p>実機で BINGO と呼ぶ物——「今引き返せば帰り着ける残量」——にあたる。2割にしてあるのは、この世界の
     * 機体が概ね全開10分を積んでいるからで、残り2分は基地が見える範囲に居るなら足り、居ないなら足りない。
     * その線が引かれていること自体が、燃料を数字から判断に変えている。
     */
    private static final float BINGO_FUEL = 0.2F;

    /**
     * ヘリの表示が緑に変わるローター回転数（%）。
     *
     * <p>100ではない。揚力はその2乗に比例するので最後の数%はほとんど価値が無いし、到達した瞬間にしか目盛りに乗らない
     * 針は誰にも読めない。これは「コレクティブを引けば何かが起きる」点だ。
     */
    private static final int ROTOR_READY = 95;

    /** ピッチ1度あたり水平線が滑る画面ピクセル数。 */
    private static final float PIXELS_PER_DEGREE = 3.0F;
    /** ピッチラダーの目盛り間隔（度）。 */
    private static final int[] LADDER = {10, 20, 30, 45, 60};
    private static final int LADDER_WIDTH = 34;
    private static final int HORIZON_WIDTH = 60;
    /**
     * 爆弾の着弾環の地上での幅（ブロック）。画面上の大きさではなくワールド上の大きさで描くので、乗っている地面と同様に
     * 距離とともに縮むし、意味も持つ——爆弾が到達したときおおよそ何を吹き飛ばすか、である。
     */
    private static final double BOMB_RING_BLOCKS = 6.0;

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, ID, new AircraftHud());
    }

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null || minecraft.options.hideGui) {
            return;
        }

        if (!(minecraft.player.getVehicle() instanceof AircraftEntity aircraft)) {
            return;
        }

        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        Quaternionf attitude = aircraft.getAttitude(partialTick);
        Vec3 velocity = aircraft.getVelocity();
        double speed = velocity.length();

        int centreX = graphics.guiWidth() / 2;
        int centreY = graphics.guiHeight() / 2;

        // ターゲティングポッドを覗いている間、以下は一切描かない。下の各マークはキャノピー越しのパイロット自身の視線
        // に対して配置されるが、ポッドは8倍のレンズでまったく別の方向を見ている。PodDisplay 参照。
        if (PodDisplay.draw(graphics, minecraft, aircraft, partialTick, centreX, centreY)) {
            HitReadout.draw(graphics, minecraft.font);

            return;
        }

        drawAttitude(graphics, attitude, centreX, centreY);
        drawMarkers(graphics, minecraft, aircraft, attitude, velocity, speed, centreX, centreY);
        drawGunSight(graphics, minecraft, aircraft, partialTick, centreX, centreY);
        drawNumbers(graphics, minecraft.font, aircraft, attitude, velocity, speed);
        drawStatus(graphics, minecraft.font, aircraft, attitude, velocity, speed);
        drawStores(graphics, minecraft.font, aircraft, centreX, centreY);
        drawStations(graphics, minecraft.font, minecraft, aircraft, centreX, centreY);
        drawLock(graphics, minecraft, aircraft, partialTick, centreX, centreY);
        drawHMDCues(graphics, minecraft, aircraft, partialTick, centreX, centreY);
        drawBombSight(graphics, minecraft, aircraft, centreX, centreY);
        drawCrew(graphics, minecraft.font, aircraft);
        drawEject(graphics, minecraft.font, centreX, centreY);
        // 直近の着弾があれば、その結果。戦車乗員が得るのと同じ計器で、理由も同じだ。空から機関砲を撃つ距離では、
        // 当たった連射と外れた連射は照準の後ろからは見分けが付かない。
        HitReadout.draw(graphics, minecraft.font);
        RadarDisplay.draw(graphics, minecraft.font, aircraft);
    }

    /**
     * シーカー。選択中のミサイルが見ている物を囲む枠で、ロックが進むにつれ締まり、成立すると実線になる。シーカーを持た
     * ない兵装——全ての機関砲とロケット——では何も描かない。
     *
     * <p>枠は固定位置ではなく目標が実際に画面上にある場所へ描く。だからパイロットがまだ見つけていない目標を発見する
     * 手段にもなる。
     */
    private static void drawLock(GuiGraphics graphics, Minecraft minecraft, AircraftEntity aircraft,
            float partialTick, int centreX, int centreY) {
        WeaponDefinition weapon = aircraft.getWeapons().selectedWeapon();

        if (weapon == null || weapon.guidance().isEmpty()) {
            return;
        }

        // レーザー誘導兵器は何も探していない。追うのはターゲティングポッドが捉えている物であり、ポッドはシーカーが
        // 見つけるのではなくパイロットが据える。ポッド内だけでなくここにも描くので、進入はキャノピー越しに飛べる——
        // 指示し、ポッドを仕舞い、機体を飛ばしても、マークは計器面に残っている。
        if (weapon.guidance().get().seeker() == WeaponDefinition.Guidance.Seeker.LASER) {
            drawDesignation(graphics, minecraft, aircraft, partialTick, centreX, centreY);

            return;
        }

        Entity target = aircraft.getWeapons().lock().target();

        if (target == null) {
            String seeking = "SEEK";
            graphics.drawString(minecraft.font, seeking, centreX - minecraft.font.width(seeking) / 2,
                    centreY + 54, DIM, true);

            return;
        }

        boolean locked = aircraft.getWeapons().lock().isLocked();
        // シーカーは味方を避けない。避けさせずに、代わりに知らせる。判定はここで自分で出す——枠が描ける
        // 相手は手元にいる相手なので、スコープと違ってサーバーに訊く必要が無い。
        boolean friendly = Iff.between(aircraft, target) == Iff.FRIEND;
        Vec3 middle = target.getPosition(partialTick).add(0.0, target.getBbHeight() * 0.5, 0.0);
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        int[] at = project(minecraft, middle.subtract(camera).normalize(), focalLength(minecraft, graphics),
                centreX, centreY);
        // 味方を捉えている間は、ロックの赤より身元の青を優先する。ロックが閉じたことは分かっていて、
        // 分かっていないのは相手が誰かの方だから。
        int colour = friendly ? IFF_FRIEND : locked ? WARNING : GREEN;

        if (at != null) {
            // ロックが閉じるにつれ枠が締まるので、目標から目を離さずに進行度を読める。
            float progress = aircraft.getWeapons().lock().progress(weapon.guidance().get());
            int half = Math.round(Mth.lerp(progress, 26.0F, 11.0F));

            corner(graphics, at[0] - half, at[1] - half, 1, 1, colour);
            corner(graphics, at[0] + half, at[1] - half, -1, 1, colour);
            corner(graphics, at[0] - half, at[1] + half, 1, -1, colour);
            corner(graphics, at[0] + half, at[1] + half, -1, -1, colour);
        }

        String status = friendly ? "FRIENDLY" : locked ? "LOCK" : "SEEK";
        graphics.drawString(minecraft.font, status, centreX - minecraft.font.width(status) / 2,
                centreY + 54, colour, true);

        int range = (int) Math.round(aircraft.getPosition(partialTick).distanceTo(target.getPosition(partialTick)));
        String reach = range + " m";
        graphics.drawString(minecraft.font, reach, centreX - minecraft.font.width(reach) / 2,
                centreY + 64, DIM, true);
    }

    /**
     * ターゲティングポッドの捕捉位置を、捕捉している地面の上に描く。
     *
     * <p>シーカーが「探す頭」ではなく「光の点」である兵装向けの、{@link #drawLock} の対になる物。枠ではなく菱形にして
     * あるので、パイロットが手で置いたマークが、何かが自力で見つけたロックには見えない。締まるアニメーションも無い。
     * 締まる物が無いからだ——指示は保持されているか、いないかのどちらかである。
     *
     * <p>ポッドを積んでいなければ何も描かない。積んでいる兵装をそもそも使えない機体の計器面を、二度言わずに済ませる
     * ためだ。兵装表示の行が既に NO POD と伝えている。{@link com.ashvehicles.weapon.WeaponMounts#missingPod} 参照。
     */
    private static void drawDesignation(GuiGraphics graphics, Minecraft minecraft, AircraftEntity aircraft,
            float partialTick, int centreX, int centreY) {
        if (aircraft.getWeapons().missingPod(aircraft.getWeapons().selectedWeapon()) != null) {
            return;
        }

        Entity mark = aircraft.getDesignated();

        if (mark == null) {
            String none = "NO DESIG";
            graphics.drawString(minecraft.font, none, centreX - minecraft.font.width(none) / 2,
                    centreY + 54, DIM, true);

            return;
        }

        Vec3 middle = mark.getPosition(partialTick).add(0.0, mark.getBbHeight() * 0.5, 0.0);
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        int[] at = project(minecraft, middle.subtract(camera).normalize(), focalLength(minecraft, graphics),
                centreX, centreY);

        if (at != null) {
            diamond(graphics, at[0], at[1], 7, WARNING);
        }

        String status = "DESIG";
        graphics.drawString(minecraft.font, status, centreX - minecraft.font.width(status) / 2,
                centreY + 54, WARNING, true);

        int range = (int) Math.round(aircraft.getPosition(partialTick).distanceTo(middle));
        String reach = range + " m";
        graphics.drawString(minecraft.font, reach, centreX - minecraft.font.width(reach) / 2,
                centreY + 64, DIM, true);
    }


    /** この機体のキューを最後に組み直した元の走査結果。新しい走査を同一性で検出するため。 */
    private static List<Contact> cuedFrom = List.of();
    /** {@link #cuedFrom} の各要素が立っていると算出された位置。同じ順序。 */
    private static Vec3[] cuedAt = new Vec3[0];

    /**
     * より広い画。レーダーが現在保持している全接触を、隅のスコープ上だけでなく画面上の実際の位置へ印す。シーカー自身が
     * 捉えた物にだけ存在する {@link #drawLock} の枠と違い、これはレーダーが見つけた全てに描かれる——だからパイロットは
     * 描画された瞬間に接触を見られる。機首をそちらへ向けて何かが閉じるずっと前にだ。スコープではなくヘルメット装着型
     * キューの見た目にしてある。重要なのはどちらを見るかであって、スコープが綴る方位と距離ではない。
     *
     * <p>スコープ自身が描く元と同じ方位・距離・高度から求める——それがクライアントの持つ全てである理由は {@link Contact}
     * 参照。通知されたことすら無いかもしれないエンティティのワールド位置ではないのだ。再構成は {@code Sensors.sweep} を
     * 正確に鏡写しにする。上昇成分を除いた照準方向からの水平方位と、生の差分として送られた高度差から高さを復元する。
     *
     * <p><b>走査ごとに1度固定し、毎フレーム追いかけない。</b>レーダーは連続的には見ない——{@code Radar.sweepTicks}
     * 参照——ので、この古さの方位が正直でいられるのは「届いた時点でこの機体が立って向いていた位置」に対してだけだ。代わりに
     * <em>現在の</em>位置と方位から毎フレーム再構成すると、その後この機体が飛んだり回ったりした分だけ全キューが空を横切って
     * 引きずられる。使っている最中の戦闘機では、それは走査間隔1回分の運動量の大半にあたる。よってワールド上の点は、
     * {@link #cuedFrom} が {@link RadarReadout} の保持内容と一致しなくなって新しい走査が検出された瞬間に1度求め、
     * そこに保持する——スコープ自身と正確に同じだけ古く、正確に同じだけ正直に。
     */
    private static void drawHMDCues(GuiGraphics graphics, Minecraft minecraft, AircraftEntity aircraft,
            float partialTick, int centreX, int centreY) {
        List<Contact> contacts = RadarReadout.contacts();

        if (contacts.isEmpty()) {
            cuedFrom = List.of();
            cuedAt = new Vec3[0];

            return;
        }

        if (contacts != cuedFrom) {
            Vec3 along = flatAim(aircraft, partialTick);
            Vec3 right = new Vec3(-along.z, 0.0, along.x);
            Vec3 origin = aircraft.getPosition(partialTick);
            Vec3[] rebuilt = new Vec3[contacts.size()];

            for (int i = 0; i < contacts.size(); i++) {
                Contact contact = contacts.get(i);
                double range = contact.range();
                double climb = contact.altitude();
                double horizontal = Math.sqrt(Math.max(0.0, range * range - climb * climb));
                double bearing = Math.toRadians(contact.bearing());

                rebuilt[i] = origin.add(along.scale(Math.cos(bearing)).add(right.scale(Math.sin(bearing)))
                        .scale(horizontal)
                        .add(0.0, climb, 0.0));
            }

            cuedFrom = contacts;
            cuedAt = rebuilt;
        }

        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        float focal = focalLength(minecraft, graphics);

        for (int i = 0; i < cuedFrom.size(); i++) {
            Contact contact = cuedFrom.get(i);
            int[] at = project(minecraft, cuedAt[i].subtract(camera).normalize(), focal, centreX, centreY);

            if (at != null) {
                // まだ何も起きていないキューは沈めるが、身元は保つ。ロックした1件だけが地の明るさから
                // 上がってくる。判定の付かない相手ではこれは従来どおりの DIM と WARNING になる。
                int colour = contactColour(contact.iff(), contact.locked());

                diamond(graphics, at[0], at[1], contact.locked() ? 7 : 5,
                        contact.locked() ? colour : dim(colour));
            }
        }
    }

    /** 上昇成分を除いた照準方向。水平方位をワールド方位へ戻すのに使う。 */
    private static Vec3 flatAim(AircraftEntity aircraft, float partialTick) {
        Vec3 direction = aircraft.getAimDirection(partialTick);
        Vec3 level = new Vec3(direction.x, 0.0, direction.z);

        return level.lengthSqr() < 1.0E-8 ? new Vec3(0.0, 0.0, 1.0) : level.normalize();
    }

    /**
     * 機首上の照準。選択中の兵装によって別の計器になる。
     *
     * <p>兵装ごとに照準方法が違うのだから、表示も違ってしかるべきだ。機関砲やロケットは機体を向けて狙うので、機首上には
     * 機首の位置を示す素の十字だけを置く。弾が<em>落ちる</em>位置を示すピッパーは別の場所の別のマークであり、
     * {@link #drawGunSight} の管轄だ。ミサイルはそもそも狙わない——<em>与えられる</em>——ので重要なのはシーカーがどこを
     * 見られるかであり、描く環はまさにロック可能な円錐だ。爆弾は飛ぶことで狙うので、マークはここではなく落着する地面の
     * 上にある。
     *
     * @param focal 視線から1ラジアン外れると画面上で何ピクセルになるか。シーカーの円錐を正しい大きさの環に変える値
     */
    private static void drawSight(GuiGraphics graphics, AircraftEntity aircraft, int x, int y, float focal) {
        WeaponDefinition weapon = aircraft.getWeapons().selectedWeapon();
        WeaponDefinition.Type type = weapon == null ? null : weapon.type();

        if (type == WeaponDefinition.Type.BOMB) {
            // 機首上には何も描かない。爆弾はそこで狙う物ではないし、着弾環が全てを語る。
            return;
        }

        if (type == WeaponDefinition.Type.MISSILE && weapon.guidance().isPresent()) {
            WeaponDefinition.Guidance guidance = weapon.guidance().get();

            // この弾が実際に縛られている円錐。自力で見る赤外線誘導なら弾自身の狭いシーカー視野。レーダー誘導なら
            // レールを離れる前にレーダーが指示するので、代わりに描くのはレーダー自身のはるかに広い走査範囲だ——
            // TargetLock#bestCandidate 参照。同じ選択を同じやり方で行っている。
            boolean radarCued = guidance.seeker() == WeaponDefinition.Guidance.Seeker.RADAR
                    && aircraft.radar().fitted();
            float angle = radarCued ? aircraft.radar().arc() : guidance.lockAngle();
            int radius = Math.round((float) Math.tan(Math.toRadians(angle)) * focal);
            boolean locked = aircraft.getWeapons().lock().isLocked();

            circle(graphics, x, y, Mth.clamp(radius, 10, 220), locked ? WARNING : DIM);
            graphics.fill(x - 1, y - 1, x + 1, y + 1, locked ? WARNING : DIM);

            return;
        }

        // 機関砲、ロケット、あるいは何も選択していない場合。計器が昔から持っている素のボアサイトだ。暗くしてある。
        // 機関砲にとってこれは照準ではなく機首だからだ——コックピットからは両者が重なるが、三人称カメラからは重ならないし、
        // 発砲の基準になるのはもう一方である。
        graphics.fill(x - 5, y, x + 5, y + 1, DIM);
        graphics.fill(x, y - 5, x + 1, y + 5, DIM);
    }

    /**
     * 本来のガンサイト。弾が落ちる位置と、動く物に当てるために機首をどこへ置くべきか。両者の求め方は {@link GunSight}
     * 参照。
     *
     * <p>ピッパーはカメラからの方向ではなく、弾が到達するワールド上の点に描く。コックピットからは同じマークだが、
     * 十数ブロック後ろかつ数ブロック上の三人称カメラからは別物であり、その差は「機銃掃射」と「目標手前の耕された溝」の
     * 差になる。残弾があれば緑、無ければ琥珀にするので、撃てるかどうかと向きが一目で分かる。その下に据えた対象までの
     * 距離も出す。パイロットが機銃掃射で目安にする数字だ。
     *
     * <p>見越し点は、今撃った弾が到達する頃に目標がいる位置へ置いた菱形で、弾の落下分だけ持ち上げてある。ピッパーを菱形へ
     * 合わせて撃てばよい。目標が弾の到達距離の外なら暗く、内側なら緑、機首が見越し点に十分近く今撃てば当たる状態では——
     * 文字付きで——琥珀になる。
     */
    private static void drawGunSight(GuiGraphics graphics, Minecraft minecraft, AircraftEntity aircraft,
            float partialTick, int centreX, int centreY) {
        // 砲座を持っている乗員には、機体が向いている方向ではなくその砲の照準を出す。撃つ物が違えば狙う線も
        // 違うのであり、1人で飛ぶパイロットはこの2つを兵装切り替えキーで行き来する。
        GunStations stations = aircraft.getStations();
        int station = stations.liveStationOf(minecraft.player);

        if (station != GunStations.NONE) {
            // 空の旋回パイロンでは何も描かない。引き金はその砲座に繋がっているので、パイロンの兵装の照準を
            // 代わりに出せば「撃てない物への照準」を約束することになる。
            ResourceLocation laidGun = stations.weaponOf(station);
            GunSight.Solution laid = laidGun == null ? null : GunSight.solve(aircraft, station);

            if (laid != null) {
                drawSight(graphics, minecraft, laid, Definitions.weapon(laidGun),
                        stations.rounds(station), partialTick, centreX, centreY);
            }

            return;
        }

        WeaponDefinition weapon = aircraft.getWeapons().selectedWeapon();

        if (weapon == null || !GunSight.aims(weapon)) {
            return;
        }

        GunSight.Solution sight = GunSight.solve(aircraft);

        if (sight == null) {
            return;
        }

        drawSight(graphics, minecraft, sight, weapon, aircraft.getWeapons().selectedAmmo(),
                partialTick, centreX, centreY);
    }

    /**
     * 照準そのものを描く。どの砲の解であっても同じマークだ——ピッパーと見越し点は「弾がどこから、どちらへ、
     * どれだけ落ちながら飛ぶか」だけでできており、それを撃つのがパイロットか砲手かは何も変えない。
     *
     * @param rounds その砲の残弾。ピッパーの色になる
     */
    private static void drawSight(GuiGraphics graphics, Minecraft minecraft, GunSight.Solution sight,
            WeaponDefinition weapon, int rounds, float partialTick, int centreX, int centreY) {
        float focal = focalLength(minecraft, graphics);
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        // 前tickの値を読むのではなく、このフレームの機首方向から組み直す。1tick古いのはピッパーまでの距離だけであり、
        // マーク自体は機首の動きと同じなめらかさで追従する。
        Vec3 muzzle = sight.bore().muzzle(partialTick);
        Vec3 nose = sight.bore().direction(partialTick);
        Vec3 pipper = muzzle.add(nose.scale(sight.pipperRange())).add(sight.pipperDrop());
        int colour = rounds > 0 ? GREEN : WARNING;
        int[] at = project(minecraft, pipper.subtract(camera).normalize(), focal, centreX, centreY);

        if (at != null) {
            int x = at[0];
            int y = at[1];

            if (weapon.type() == WeaponDefinition.Type.GUN) {
                // ピッパー。ガンサイトが迷子の点ではなくガンサイトとして読めるように。
                circle(graphics, x, y, 9, colour);
            } else {
                // 上下を開ける。ロケットは機関砲より散布するので、マークは「1発ごとの行き先の閉じた約束」に意図的に
                // していない。
                graphics.fill(x - 9, y, x - 3, y + 1, colour);
                graphics.fill(x + 4, y, x + 10, y + 1, colour);
            }

            graphics.fill(x - 1, y - 1, x + 1, y + 1, colour);

            if (sight.struck()) {
                String reach = Math.round(sight.pipperRange()) + " m";
                graphics.drawString(minecraft.font, reach, x - minecraft.font.width(reach) / 2, y + 12, DIM, true);
            }
        }

        Entity target = sight.target();

        if (target == null || target.isRemoved()) {
            return;
        }

        // 見越しを算出したtickから目標は動いているが、オフセットは動いていない。だからマークは、このフレームで目標が
        // 描かれる位置に乗って一緒に動く。
        Vec3 lead = target.getPosition(partialTick).add(0.0, target.getBbHeight() * 0.5, 0.0).add(sight.leadOffset());
        int[] mark = project(minecraft, lead.subtract(camera).normalize(), focal, centreX, centreY);

        if (mark == null) {
            return;
        }

        int leadColour = !sight.inRange() ? DIM : sight.onTarget() ? WARNING : GREEN;
        diamond(graphics, mark[0], mark[1], 6, leadColour);

        String reach = Math.round(sight.targetRange()) + " m";
        graphics.drawString(minecraft.font, reach, mark[0] - minecraft.font.width(reach) / 2, mark[1] + 10,
                leadColour, true);

        if (sight.inRange() && sight.onTarget()) {
            String cue = "SHOOT";
            graphics.drawString(minecraft.font, cue, mark[0] - minecraft.font.width(cue) / 2, mark[1] + 20,
                    WARNING, true);
        }
    }

    /**
     * 太さ1ピクセルの菱形。角で立てた正方形だ。GUI が描けるのは直立した矩形だけで、4つ並べても菱形ではなく箱にしか
     * ならないので、1ピクセルずつ歩いて描く。
     */
    static void diamond(GuiGraphics graphics, int centreX, int centreY, int radius, int colour) {
        for (int step = 0; step < radius; step++) {
            graphics.fill(centreX + step, centreY - radius + step, centreX + step + 1, centreY - radius + step + 1, colour);
            graphics.fill(centreX + radius - step, centreY + step, centreX + radius - step + 1, centreY + step + 1, colour);
            graphics.fill(centreX - step, centreY + radius - step, centreX - step + 1, centreY + radius - step + 1, colour);
            graphics.fill(centreX - radius + step, centreY - step, centreX - radius + step + 1, centreY - step + 1, colour);
        }
    }

    /**
     * 太さ1ピクセルの円を、中点アルゴリズムで描く。
     *
     * <p>描く手段が無いので自前で書いてある。GUI が提供するのは直立した矩形だけで、それを数個並べて作った環は菱形になる。
     * ここでは円の1/8を歩き、残り7つへ鏡映する。厳密であり、ピクセルごとの三角関数も要らない。
     *
     * <p><b>1ピクセルずつではなく連続区間で描く。</b>GUI が描けるのは矩形だけなので、1ピクセルに矩形1つ分——頂点4つと、
     * それを入れるバッチの検索——のコストがかかる。しかもシーカーの円錐は半径数百ピクセルの環で、誘導兵装を選択中は毎
     * フレーム描き直される。だがアルゴリズムは {@code x} を数ステップ据え置くし、据え置いている間の各ステップは、8つの
     * 八分円のうち4つでは前と同じ行に、残り4つでは同じ列に乗る。だから一続きの区間を矩形1つで置ける。同じピクセルが
     * 1/3の作業量で点灯するわけだ。
     */
    static void circle(GuiGraphics graphics, int centreX, int centreY, int radius, int colour) {
        if (radius < 1) {
            return;
        }

        int x = radius;
        int y = 0;
        int error = 1 - radius;
        // 現在の x を共有するステップの連続区間を、覆う y 値の範囲として持つ。
        int runRow = x;
        int runFrom = 0;

        while (x >= y) {
            y++;

            if (error < 0) {
                error += 2 * y + 1;
            } else {
                x--;
                error += 2 * (y - x) + 1;
            }

            // x が動いたとき——あるいは走査が終わったとき——にだけ、それまでの区間が確定する。
            if (x != runRow || x < y) {
                plotOctants(graphics, centreX, centreY, runRow, runFrom, y - 1, colour);
                runRow = x;
                runFrom = y;
            }
        }
    }

    /**
     * ある {@code x} における点の連続区間（y は {@code from} から {@code to} まで）を、8つの八分円すべてへ反射する。
     * 列 {@code centreX ± x} に縦の区間4つ、行 {@code centreY ± x} に横の区間4つ。
     */
    private static void plotOctants(GuiGraphics graphics, int centreX, int centreY, int x,
            int from, int to, int colour) {
        if (to < from) {
            return;
        }

        graphics.fill(centreX + x, centreY + from, centreX + x + 1, centreY + to + 1, colour);
        graphics.fill(centreX - x, centreY + from, centreX - x + 1, centreY + to + 1, colour);
        graphics.fill(centreX + x, centreY - to, centreX + x + 1, centreY - from + 1, colour);
        graphics.fill(centreX - x, centreY - to, centreX - x + 1, centreY - from + 1, colour);

        graphics.fill(centreX + from, centreY + x, centreX + to + 1, centreY + x + 1, colour);
        graphics.fill(centreX - to, centreY + x, centreX - from + 1, centreY + x + 1, colour);
        graphics.fill(centreX + from, centreY - x, centreX + to + 1, centreY - x + 1, colour);
        graphics.fill(centreX - to, centreY - x, centreX - from + 1, centreY - x + 1, colour);
    }

    /**
     * 今投下した爆弾が落ちる位置を、落着する地面の上に描く。
     *
     * <p>爆弾専用だ。必要とするのが爆弾だけだからである。他は当てたい物へ向けて撃つが、爆弾は手放してから見守る。これが
     * 無いと爆撃は、パイロットが学ぶことすらできない当てずっぽうになる。答えが判断の数秒後に届くからだ。
     *
     * <p>機体が上昇していて爆弾が視聴者の後方へ落ちる場合、マークは画面下から外れる。それが正直な答えだ。ここからでは、
     * 見える範囲のどこにも落ちない。
     *
     * <p>高高度からの爆弾はクライアントが持つチャンクの外へ落ちるので、そこでは {@link BombSight} が実ブロックへの
     * トレースではなく仮定した床に対して落下を求めるしかない。マークは描き続ける——高度こそパイロットがそれを必要とする
     * 場面だ——が暗くし、距離にチルダを付ける。ありのままに読めるようにするためだ。「向こうの地面がここの地面と同じ高さ
     * なら、正しい場所」である。
     */
    private static void drawBombSight(GuiGraphics graphics, Minecraft minecraft, AircraftEntity aircraft,
            int centreX, int centreY) {
        WeaponDefinition weapon = aircraft.getWeapons().selectedWeapon();

        if (weapon == null || !weapon.isDropped() || aircraft.getWeapons().selectedAmmo() <= 0) {
            return;
        }

        BombSight.Solution fall = BombSight.solve(aircraft, weapon);

        if (fall == null) {
            return;
        }

        Vec3 impact = fall.point();
        int colour = fall.estimated() ? DIM : GREEN;
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        int[] at = project(minecraft, impact.subtract(camera).normalize(),
                focalLength(minecraft, graphics), centreX, centreY);

        if (at == null) {
            return;
        }

        // 落着する地面の上に描く環と、その中央の点。環の大きさは着弾までの距離で決めるので、目標が近くても1マイル先でも
        // 画面上で同じ大きさに留まるのではなく、実際のマークのように地面に乗る。
        double away = impact.distanceTo(camera);
        int radius = Mth.clamp((int) Math.round(BOMB_RING_BLOCKS / Math.max(away, 1.0)
                * focalLength(minecraft, graphics)), 4, 60);

        circle(graphics, at[0], at[1], radius, colour);
        graphics.fill(at[0] - 1, at[1] - 1, at[0] + 1, at[1] + 1, colour);

        // どれだけ前方に落ちるか。パイロットが実際に目安にする数字だ。
        int range = (int) Math.round(aircraft.position().distanceTo(impact));
        String reach = (fall.estimated() ? "~" : "") + range + " m";
        graphics.drawString(minecraft.font, reach, at[0] - minecraft.font.width(reach) / 2, at[1] + 10, DIM, true);
    }

    /** 目標枠の隅1つ。直角で交わる2本の短い線。同種の目標を同じ枠で囲む地上車両のシーカーと共有する。 */
    static void corner(GuiGraphics graphics, int x, int y, int alongX, int alongY, int colour) {
        int arm = 6;
        graphics.fill(Math.min(x, x + alongX * arm), y, Math.max(x, x + alongX * arm), y + 1, colour);
        graphics.fill(x, Math.min(y, y + alongY * arm), x + 1, Math.max(y, y + alongY * arm), colour);
    }

    /** 人工水平儀。主翼と共にロールし、機首と共に上下する線。 */
    private static void drawAttitude(GuiGraphics graphics, Quaternionf attitude, int centreX, int centreY) {
        float bank = Attitude.bank(attitude);
        float elevation = -Attitude.elevation(attitude);

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centreX, centreY, 0.0F);
        pose.mulPose(Axis.ZP.rotationDegrees(-bank));
        pose.translate(0.0F, elevation * PIXELS_PER_DEGREE, 0.0F);

        // 水平線本体。中央に隙間を空け、機体シンボルを隠さないようにする。
        graphics.fill(-HORIZON_WIDTH, -1, -14, 0, GREEN);
        graphics.fill(14, -1, HORIZON_WIDTH, 0, GREEN);

        for (int rung : LADDER) {
            drawRung(graphics, rung);
            drawRung(graphics, -rung);
        }

        pose.popPose();

        // 機体シンボル。画面に固定する。動くのは水平線であってこちらではない。
        graphics.fill(centreX - 13, centreY - 1, centreX - 4, centreY, GREEN);
        graphics.fill(centreX + 4, centreY - 1, centreX + 13, centreY, GREEN);
        graphics.fill(centreX - 1, centreY - 1, centreX + 1, centreY + 1, GREEN);
    }

    private static void drawRung(GuiGraphics graphics, int degrees) {
        int y = Math.round(-degrees * PIXELS_PER_DEGREE);
        int half = LADDER_WIDTH / 2;

        if (degrees > 0) {
            graphics.fill(-half, y, half, y + 1, DIM);
        } else {
            // 水平線より下ではラダーを破線にする。実物と同じだ。
            graphics.fill(-half, y, -half + 10, y + 1, DIM);
            graphics.fill(half - 10, y, half, y + 1, DIM);
        }
    }

    /** 機首の指す方向と、機体が実際に進んでいる方向。 */
    private static void drawMarkers(GuiGraphics graphics, Minecraft minecraft, AircraftEntity aircraft,
            Quaternionf attitude, Vec3 velocity, double speed, int centreX, int centreY) {
        float focal = focalLength(minecraft, graphics);
        Vec3 nose = Attitude.nose(attitude);

        int[] boresight = project(minecraft, nose, focal, centreX, centreY);

        if (boresight != null) {
            drawSight(graphics, aircraft, boresight[0], boresight[1], focal);
        }

        if (speed < 0.05) {
            return;
        }

        int[] path = project(minecraft, velocity.scale(1.0 / speed), focal, centreX, centreY);

        if (path != null) {
            // 古典的な円と翼。この大きさなら十分近い。
            graphics.fill(path[0] - 3, path[1] - 3, path[0] + 4, path[1] - 2, GREEN);
            graphics.fill(path[0] - 3, path[1] + 3, path[0] + 4, path[1] + 4, GREEN);
            graphics.fill(path[0] - 4, path[1] - 3, path[0] - 3, path[1] + 4, GREEN);
            graphics.fill(path[0] + 3, path[1] - 3, path[0] + 4, path[1] + 4, GREEN);
            graphics.fill(path[0] - 9, path[1], path[0] - 4, path[1] + 1, GREEN);
            graphics.fill(path[0] + 4, path[1], path[0] + 9, path[1] + 1, GREEN);
        }
    }

    /** 左に対気速度、右に高度、上に方位。いつもの配置。 */
    private static void drawNumbers(GuiGraphics graphics, Font font, AircraftEntity aircraft,
            Quaternionf attitude, Vec3 velocity, double speed) {
        int centreX = graphics.guiWidth() / 2;
        int centreY = graphics.guiHeight() / 2;

        // 1ブロック=1m、1秒=20tick。
        int kmh = (int) Math.round(speed * 20.0 * 3.6);
        int altitude = (int) Math.round(aircraft.getY());
        int climb = (int) Math.round(velocity.y * 20.0);
        int heading = Math.floorMod(Math.round(Attitude.heading(attitude)) + 180, 360);

        label(graphics, font, "SPD", centreX - 118, centreY - 14);
        value(graphics, font, kmh + " km/h", centreX - 118, centreY - 4);

        label(graphics, font, "ALT", centreX + 74, centreY - 14);
        value(graphics, font, altitude + " m", centreX + 74, centreY - 4);
        value(graphics, font, String.format("%+d m/s", climb), centreX + 74, centreY + 8);

        String compass = heading + "  " + cardinal(heading);
        graphics.drawString(font, compass, centreX - font.width(compass) / 2, centreY - 78, GREEN, true);
        graphics.fill(centreX - 1, centreY - 66, centreX + 1, centreY - 62, GREEN);
    }

    /** エンジン、可動部、そして噛み付いてくる2つ——迎角と失速。 */
    private static void drawStatus(GuiGraphics graphics, Font font, AircraftEntity aircraft,
            Quaternionf attitude, Vec3 velocity, double speed) {
        int left = 8;
        int bottom = graphics.guiHeight() - 8;

        // 残燃料。割合と、その割合で燃料計に何が起きているか。
        //
        // 割合で出すのは、パイロットが実際に問うのがそれだからだ。「あと何単位あるか」は機種ごとに意味が
        // 変わるが、「あと何割か」はどの機体でも同じことを意味する。BINGO——帰投を決める残量——を下回れば
        // 琥珀になり、そこから先は「戦闘を続けるか帰るか」の判断が始まる。空になれば下の中央に警告が出る。
        //
        // 燃料を持たない機体では1行も割かない。動かない数字はパイロットが読むことを覚え、それから読むのを
        // やめる物であり、そのとき隣の数字も一緒に読まれなくなる。
        if (aircraft.fuelSetup().fitted()) {
            float fuel = aircraft.getFuelFraction();
            value(graphics, font, String.format("FUEL %d%%", Math.round(fuel * 100.0F)),
                    left, bottom - 72, fuel <= BINGO_FUEL ? WARNING : GREEN);

            // 増槽の残量。本体の割合とは別に、量そのもので出す。パイロットがこの数字に対して問うのは「あと
            // 何割か」ではなく「まだ落とせないか」だからだ。0 になれば落としてよく、そこからは抗力だけを
            // 払っていることになる——だから空でも表示は残す。吊っていなければ1文字も出さない。
            if (aircraft.getWeapons().hasTank()) {
                int tanks = aircraft.getTankFuel();
                value(graphics, font, String.format("EXT %d", tanks), left + 84, bottom - 72,
                        tanks > 0 ? GREEN : DIM);
            }
        }

        // 機体の残存度。パイロットが戦闘より帰投を考えるべき量まで減ったら警告色にする。
        float health = aircraft.getHealth();
        int colour = aircraft.getHealthFraction() <= LOW_HEALTH ? WARNING : GREEN;
        value(graphics, font, String.format("HP %d/%d", Math.round(health), Math.round(aircraft.getMaxHealth())),
                left, bottom - 62, colour);

        // 放出できる残量。両方の数を1行にまとめ、どちらかが尽きたら琥珀にする。どちらが尽きたかが、次のロックを生き延び
        // られるかを決めるからだ。
        int flares = aircraft.getCountermeasures(true);
        int chaff = aircraft.getCountermeasures(false);
        value(graphics, font, String.format("CM  FL %d  CH %d", flares, chaff), left, bottom - 52,
                flares > 0 && chaff > 0 ? GREEN : WARNING);

        // 探している者に対して機体が返している反射と、それが現時点でどれだけのコストになっているか。1行割く価値がある
        // のは見つかりにくく作られた機体だけで、それ以外では値が動かず、意味を持ったこともない。
        float clean = aircraft.getStats().signature().radar();

        if (clean < 1.0F) {
            float cross = aircraft.radarCrossSection();

            value(graphics, font, String.format("RCS %.2f", cross), left + 84, bottom - 52,
                    cross > clean ? WARNING : GREEN);
        }

        int throttle = Math.round(aircraft.getThrottle() * 100.0F);

        if (aircraft.isRotorcraft()) {
            // 同じレバーをヘリでの呼び名で表示し、その隣に、ヘリのパイロットだけが持つ値——ローターの状態——を置く。
            // 回転が上がる前にコレクティブを引いてもまったく何も起きないので、待ち時間はパイロットが推測するのではなく
            // 見られる物である必要がある。到達するまでは琥珀。
            int rotor = Math.round(aircraft.getRotorSpeed() * 100.0F);

            value(graphics, font, "COLL " + throttle + "%", left, bottom - 42);
            value(graphics, font, "RTR " + rotor + "%", left, bottom - 32,
                    rotor >= ROTOR_READY ? GREEN : WARNING);
        } else {
            // レバーと、その上限の向こうの1段。アフターバーナーは百分率ではない——ゲートを抜けたか抜けていないかだ——
            // し、あの加速をしている機体の隣の「THR 100%」は、その理由について何も語らない。
            boolean reheat = aircraft.isAfterburning();

            value(graphics, font, reheat ? "THR A/B" : "THR " + throttle + "%", left, bottom - 42,
                    reheat ? WARNING : GREEN);
            value(graphics, font, "GEAR " + (aircraft.isGearDown() ? "DOWN" : "UP"), left, bottom - 32);
            value(graphics, font, "FLAP " + (aircraft.isFlapsDown() ? "DOWN" : "UP"), left, bottom - 22);
        }

        // 可変翼を持つ機体のみ。翼はパイロットが動かす物ではないが、だからこそ今どこにあるかは見えている必要が
        // ある——後退角は、この機体が同じ速度で何ができるかを丸ごと決めてしまう。作動中は琥珀。
        if (aircraft.hasSweepWing()) {
            value(graphics, font, "WING " + Math.round(aircraft.getWingSweep(1.0F)) + "°", left + 84, bottom - 32,
                    aircraft.getWingSweep(1.0F) == aircraft.getWingSweep(0.0F) ? GREEN : WARNING);
        }

        // 転換可能な機体のみ。ノズルが動作中は琥珀にする。転換は、この機体の飛行のうち「エンジンがどこを向いているか」
        // がパネル上で最も重要な数字になる唯一の局面だ。
        if (aircraft.isVtolCapable()) {
            int nozzle = Math.round(aircraft.getNozzleAngle());
            boolean settled = nozzle == 0 || nozzle == Math.round(aircraft.getStats().vtol().get().maxAngle());

            value(graphics, font, "VTOL " + nozzle + "°", left + 84, bottom - 22,
                    settled ? GREEN : WARNING);
        }

        float stallAngle = aircraft.getStats().wing().stallAngle();
        float angleOfAttack = angleOfAttack(attitude, velocity, speed);
        boolean stalled = speed > 0.05 && !aircraft.onGround() && !aircraft.isHovering()
                && Math.abs(angleOfAttack) > stallAngle;

        value(graphics, font, String.format("AOA %+.0f", angleOfAttack), left, bottom - 12);
        value(graphics, font, String.format("%.1f G", aircraft.getLoadFactor(velocity)), left, bottom - 2);

        if (stalled) {
            String warning = "STALL";
            int centreX = graphics.guiWidth() / 2;
            graphics.drawString(font, warning, centreX - font.width(warning) / 2,
                    graphics.guiHeight() / 2 + 42, WARNING, true);
        }

        // 燃料切れ。失速の1行下に出すのは、両方同時に起こりうるからだ——エンジンが止まった機体は速度を失い、
        // やがて主翼も止まる。そのときパイロットは2つとも知る必要がある。
        if (aircraft.isOutOfFuel()) {
            String warning = "FUEL OUT";
            int centreX = graphics.guiWidth() / 2;
            graphics.drawString(font, warning, centreX - font.width(warning) / 2,
                    graphics.guiHeight() / 2 + 52, WARNING, true);
        }
    }

    /**
     * 射出座席のハンドル。引いている間だけ、引き具合と一緒に出る。
     *
     * <p>この目盛りが無いと、長押しは「押しているつもりだが何も起きない1秒」になる。ハンドルを引く操作で
     * 一番知る必要があるのは、あとどれだけ引けば座席が動くかだ。
     *
     * <p>失速と燃料切れの警告の下に置く。3つとも同じ列に並び、同じ色を持ち、同じ種類のこと——今すぐ何か
     * しなければならない——を言う。順番もそのままで、ハンドルは最後の手段として一番下に来る。
     */
    private static void drawEject(GuiGraphics graphics, Font font, int centreX, int centreY) {
        float charge = VehicleDismountHandler.ejectCharge();

        if (charge <= 0.0F) {
            return;
        }

        String label = "EJECT";

        graphics.drawString(font, label, centreX - font.width(label) / 2, centreY + 66, WARNING, true);

        int left = centreX - EJECT_BAR / 2;
        int top = centreY + 78;

        graphics.fill(left - 1, top - 1, left + EJECT_BAR + 1, top + 4, SHADOW);
        graphics.fill(left, top, left + EJECT_BAR, top + 3, EJECT_EMPTY);
        graphics.fill(left, top, left + Math.round(EJECT_BAR * charge), top + 3, WARNING);
    }

    /**
     * パイロンの搭載物。選択中の兵装とその残弾を出し、その下に他の搭載物を暗く列挙するので、切り替えると次に何が来るか
     * が分かる。
     *
     * <p>機体が使う手段を持たない兵装は、トリガーを押して発見させるのではなく明示する。指示装置を積んでいない機体の
     * レーザー誘導爆弾がその例だ。吊られており、一覧にも出るが、投下されない。どれがどれかを地上で知らされる方が、目標
     * 上空で気付くよりはるかに有用だ。{@link com.ashvehicles.weapon.WeaponDefinition#requires} 参照。
     *
     * <p>何も積んでいない機体では何も描かない。非武装機の計器を従来通りに保つためだ。
     */
    private static void drawStores(GuiGraphics graphics, Font font, AircraftEntity aircraft, int centreX, int centreY) {
        // 撃てる物だけでなく吊っている物全部。増槽は選択されないが、翼の下にぶら下がっており、パイロットが
        // 搭載一覧に対して問うのは「今この機体は何を持っているか」だ。
        List<ResourceLocation> carried = aircraft.getWeapons().carriedStores();

        if (carried.isEmpty()) {
            return;
        }

        ResourceLocation selected = aircraft.getWeapons().selected();
        int right = graphics.guiWidth() - 8;
        // 警戒受信機を避ける。あれはこの辺の中央に座り、MISSILE と LOCKED の表示を自分の少し下に出す。
        int y = centreY + 52;

        label(graphics, font, "STORES", right - font.width("STORES"), y);
        y += 11;

        for (ResourceLocation weapon : carried) {
            boolean armed = weapon.equals(selected);
            int rounds = armed ? aircraft.getWeapons().selectedAmmo() : ammoOf(aircraft, weapon);
            boolean unusable = aircraft.getWeapons().missingPod(weapon) != null;
            String line = (armed ? "> " : "  ") + name(weapon)
                    + (unusable ? "  NO POD" : "  " + rounds);
            int colour = unusable || rounds <= 0 ? WARNING : (armed ? GREEN : DIM);

            graphics.drawString(font, line, right - font.width(line), y, colour, true);
            y += 10;
        }
    }

    /** 特定の兵装を積む全ステーションの残弾合計。 */
    private static int ammoOf(AircraftEntity aircraft, ResourceLocation weapon) {
        return aircraft.getWeapons().ammoOf(weapon);
    }

    /** 計器が表示する兵装名。名前空間を除いたパスを大文字にした物。 */
    private static String name(ResourceLocation weapon) {
        return weapon.getPath().replace('_', '-').toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * 砲座の一覧。各砲の名前・残弾・今それを持っているのが誰か。砲座を持たない機体では何も描かない。
     *
     * <p>この画面の持ち主が撃てる砲には矢印を付ける。1人で飛んでいれば全部が自分の物で、そのうち1つだけに
     * 矢印が付く——兵装切り替えキーが選んでいる砲だ。誰かが砲手席に着いていれば、その砲の行はその人の名前に
     * なり、矢印は消える。他人の砲だからだ。
     */
    private static void drawStations(GuiGraphics graphics, Font font, Minecraft minecraft,
            AircraftEntity aircraft, int centreX, int centreY) {
        GunStations stations = aircraft.getStations();

        if (!stations.exists()) {
            return;
        }

        int mine = stations.liveStationOf(minecraft.player);
        int left = 8;
        int y = centreY + 52;

        label(graphics, font, "GUNS", left, y);
        y += 11;

        for (int index = 0; index < stations.count(); index++) {
            AircraftDefinition.Station station = stations.station(index);
            Entity crew = stations.operatorOf(index);
            ResourceLocation gun = stations.weaponOf(index);
            int rounds = stations.rounds(index);
            boolean own = index == mine;
            // 砲座の名前ではなく、そこに今載っている砲の名前を出す。空の旋回パイロンは「積んでいない」と
            // 言うべきであって、砲座の名前だけを出せば「あるのに撃てない」に見える。
            String line = (own ? "> " : "  ")
                    + (gun == null ? station.label().toUpperCase(java.util.Locale.ROOT) : name(gun))
                    + (gun == null ? "  --" : "  " + rounds);

            graphics.drawString(font, line, left, y,
                    gun == null || rounds <= 0 ? WARNING : own ? GREEN : DIM, true);

            // 誰が撃つか。自分の砲では言うまでもないので、他人が持っている砲についてだけ名前を出す。
            if (crew != null && crew != minecraft.player) {
                graphics.drawString(font, crew.getName().getString(), left + 12, y + 9, DIM, true);
                y += 9;
            }

            y += 10;
        }
    }

    /** 搭乗者。パイロットが先頭。 */
    private static void drawCrew(GuiGraphics graphics, Font font, AircraftEntity aircraft) {
        List<Entity> aboard = aircraft.getPassengers();
        Entity pilot = aircraft.getControllingPassenger();
        int right = graphics.guiWidth() - 8;
        int y = graphics.guiHeight() - 8 - aboard.size() * 10;

        label(graphics, font, "CREW", right - font.width("CREW"), y - 10);

        for (Entity rider : aboard) {
            String name = (rider == pilot ? "P  " : "-  ") + rider.getName().getString();
            graphics.drawString(font, name, right - font.width(name), y, rider == pilot ? GREEN : DIM, true);
            y += 10;
        }
    }

    /**
     * 主翼が気流に対して成す角。機体から読まずここで求めるのは、空力を回すのが操縦中のクライアントだけだからだ。搭乗者を
     * 含む他の全クライアントは、姿勢と機体の進行方向からこれに辿り着くしかない。
     */
    private static float angleOfAttack(Quaternionf attitude, Vec3 velocity, double speed) {
        if (speed < 1.0E-4) {
            return 0.0F;
        }

        Vec3 flow = velocity.scale(1.0 / speed);

        return (float) Math.toDegrees(Math.asin(Mth.clamp(-flow.dot(Attitude.up(attitude)), -1.0, 1.0)));
    }

    /**
     * ワールド上の方向が画面のどこに落ちるか。透視は単純だ。視線からの外れ量を視線方向の距離で割り、視野角が示す焦点距離
     * で拡大する。視聴者の後方にある物には null を返す。
     */
    /**
     * カメラから出る方向が画面のどこに落ちるか。後方なら null。砲身に対して同じ仕事をする地上車両の計器と共有する。
     */
    static int[] project(Minecraft minecraft, Vec3 direction, float focal, int centreX, int centreY) {
        var camera = minecraft.gameRenderer.getMainCamera();
        Vec3 look = toVec3(camera.getLookVector());
        Vec3 up = toVec3(camera.getUpVector());
        Vec3 right = toVec3(camera.getLeftVector()).scale(-1.0);

        double along = direction.dot(look);

        if (along <= 0.05) {
            return null;
        }

        int x = centreX + (int) Math.round(direction.dot(right) / along * focal);
        int y = centreY - (int) Math.round(direction.dot(up) / along * focal);

        return new int[]{x, y};
    }

    /**
     * 視線から1ラジアン外れると画面上で何ピクセルになるか。このフレームで世界が描かれている視野角に基づく——照準を上げて
     * いる間は設定の値より狭いし、これで配置される物はワールドと一致しなければ誤った位置に置かれる。
     */
    static float focalLength(Minecraft minecraft, GuiGraphics graphics) {
        double fov = minecraft.options.fov().get() / AimZoom.factor();

        return (float) (graphics.guiHeight() / 2.0 / Math.tan(Math.toRadians(fov) / 2.0));
    }

    static String cardinal(int heading) {
        String[] points = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

        return points[Math.floorMod((int) Math.round(heading / 45.0), 8)];
    }

    static void label(GuiGraphics graphics, Font font, String text, int x, int y) {
        graphics.drawString(font, text, x, y, DIM, true);
    }

    static void value(GuiGraphics graphics, Font font, String text, int x, int y) {
        value(graphics, font, text, x, y, GREEN);
    }

    /** 同じ物を専用の色で。注意に値する表示のため。 */
    static void value(GuiGraphics graphics, Font font, String text, int x, int y, int colour) {
        graphics.fill(x - 2, y - 2, x + font.width(text) + 2, y + 10, SHADOW);
        graphics.drawString(font, text, x, y, colour, true);
    }

    private static Vec3 toVec3(org.joml.Vector3f vector) {
        return new Vec3(vector.x(), vector.y(), vector.z());
    }
}
