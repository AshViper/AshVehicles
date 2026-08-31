package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.GroundVehicleEntity;

import org.joml.Quaternionf;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

import javax.annotation.Nullable;

/**
 * 戦車の砲手照準。使用ボタン——右クリック——を押している間、視界を砲腔線へ預ける。
 *
 * <p><b>離している間の視界は乗員の頭だ。</b>砲塔はその頭を追っており、旋回速度の分だけ遅れて、俯仰の可動端で
 * 止まる。だから画面中央と砲の線は「砲が追い付いている間だけ」一致する。据わっている車両では気にならないが、
 * 砲塔を振っている最中と、俯角の尽きた斜面ではまさに食い違う——照準を詰めたい場面がちょうどその2つだ。
 *
 * <p><b>押している間は、映像の方を砲から取る。</b>画面中央が常に砲腔線になり、{@link GroundVehicleHud} が
 * 置く弾着マークもそこに重なる。砲塔が追い付くまで視界は遅れ、可動端では頭だけが先へ行って映像が止まる。
 * 実物の照準眼鏡と同じ関係で、AC-130 の砲手が覗いている物（{@link GunCamera}）とも同じだ。
 *
 * <p><b>目の位置は変えない。</b>一人称視点が既にいる場所——{@code camera.cockpit}、砲塔上面のハッチ——が
 * そのまま照準の接眼部になる。砲身に括り付けた箱にすれば「ガンカメラ」の語には忠実だが、車体の2ブロック
 * 前方に浮かぶ視点は、地形へ潜り、遮蔽の陰から向こうを覗ける。実物の照準眼鏡も砲塔上にあって砲の俯仰に
 * 連動するだけだ。三人称で押した場合もここへ来る——覗いているのは装置であって、カメラの種類ではない。
 *
 * <p>視界の傾きは車体から来る。斜面に止まった戦車の照準は斜面の分だけ傾いており、それは砲手が知るべき
 * ことだ——傾いた車両の砲は、水平に構えたつもりでも横へずれる。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class TurretSight {
    /**
     * 今この瞬間、三人称の倒し分として頭へ貸してある角（度）。抜ける時にきっちり同じだけ返すためにここに
     * 置く。差分ではなく残高を持つのは、貸したまま乗員が降りたり視点を切り替えたりしても帳尻が合うからだ。
     */
    private static float lent;

    private TurretSight() {
    }

    /**
     * 今この瞬間、砲手照準が上がっているか。
     *
     * <p>条件は「砲塔を持つ地上車両を操っている者が照準キーを押している」こと。同じキーが同じ瞬間に視野を
     * 狭める（{@link AimZoom}）——照準を覗くとは、その2つが同時に起きることを指す。あちらの答えを借りずキーを
     * 自分で読むのは、あちらもこれも同じ {@code ClientTickEvent.Pre} で走り、2つの実行順に定めが無いからだ。
     * 借りれば、キーを離したtickの答えが1tick古いことになる。
     *
     * <p>砲塔を持たない車両では上がらない。振る物が無ければ砲腔線は車首方向そのものであり、それに視界を縛れば、
     * 乗員は周りを見る手段を失うだけだ。
     */
    public static boolean isShowing() {
        return vehicle() != null;
    }

    /**
     * 毎tick1度、{@link GroundVehicleInputHandler} から呼ばれる。照準の出入りに伴う帳尻を合わせる。
     *
     * <p><b>入る時に砲を動かさない。</b>三人称の視界は車両の {@code camera.tilt} だけ下へ倒れており、砲は
     * その分を織り込んで据えられている（{@code GroundVehicleEntity.setSightTilt}）。照準は倒れていないので、
     * 何もしなければ入った瞬間に「倒し分の指令」が消え、砲が10度ほど上へ流れ出す——狙いを詰めるために覗いた
     * 相手から、狙いが逃げていく。だから倒し分をそのまま頭へ貸し、抜ける時に返す。指令角は前後で変わらず、
     * 砲は1度も動かない。
     *
     * <p>フレームではなくtickで行う。砲塔が据えられるのがtickであり、倒し角を渡すのもこの同じ場所だからだ。
     * 片方をフレーム側でやると、両者が食い違う1tickが必ず生まれる。
     */
    public static void follow() {
        LocalPlayer player = Minecraft.getInstance().player;

        if (player == null) {
            lent = 0.0F;

            return;
        }

        GroundVehicleEntity sighted = vehicle();
        // 覗いていなければ倒しは戻る。乗員が三人称のままなら、その倒し角がここで貸す量になる。
        float want = sighted == null || Minecraft.getInstance().options.getCameraType().isFirstPerson()
                ? 0.0F
                : sighted.getStats().camera().tilt();

        if (want != lent) {
            player.setXRot(player.getXRot() + (want - lent));
            lent = want;
        }

        if (sighted != null) {
            rein(sighted);
        }
    }

    /**
     * 照準が上がっている車両。上がっていなければ null。
     *
     * <p>{@link #isShowing} と同じ判定を、答えが要る側へ車両ごと返す版。カメラも入力も直後にその車両へ
     * 問い合わせるので、2度探させる理由が無い。
     */
    @Nullable
    public static GroundVehicleEntity vehicle() {
        if (!ModKeyMappings.AIM.isDown()) {
            return null;
        }

        LocalPlayer player = Minecraft.getInstance().player;

        if (player == null || !(player.getVehicle() instanceof GroundVehicleEntity vehicle)) {
            return null;
        }

        return vehicle.getControllingPassenger() == player && vehicle.getStats().turret().exists()
                ? vehicle
                : null;
    }

    /** ワールドでの照準の向き。車体姿勢に砲塔の旋回と砲の俯仰を重ねた物、つまり砲腔線そのもの。 */
    public static Quaternionf world(GroundVehicleEntity vehicle, float partialTick) {
        return vehicle.getAimAttitude(partialTick);
    }

    /**
     * 頭を砲の可動範囲へ収める。理由と式は {@code GroundVehicleEntity.clampSightPitch} に書いてある。
     *
     * <p>方位は縛らない。砲塔は一周するので、頭がどこを向いても砲はいつかそこへ着く。縛る意味があるのは
     * 端のある俯仰だけだ。
     */
    private static void rein(GroundVehicleEntity vehicle) {
        LocalPlayer player = Minecraft.getInstance().player;

        if (player == null) {
            return;
        }

        float held = vehicle.clampSightPitch(player.getXRot());

        if (held != player.getXRot()) {
            player.setXRot(held);
        }
    }

    /**
     * 照準を覗いている本人は描かない。
     *
     * <p>接眼部は砲塔上面にあり、乗員自身は車体の中——その1ブロックほど下に座っている。一人称ではそもそも
     * 描かれないので何も起きないが、三人称のまま右クリックした乗員には自分の後頭部が画面の下半分に映る。
     * 描かないのが正しい：覗いている本人は、覗いている物の後ろにはいない。
     */
    @SubscribeEvent
    public static void onRenderRider(RenderLivingEvent.Pre<?, ?> event) {
        if (event.getEntity() == Minecraft.getInstance().player && isShowing()) {
            event.setCanceled(true);
        }
    }
}
