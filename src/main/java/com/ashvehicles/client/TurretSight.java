package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.GroundVehicleEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

import javax.annotation.Nullable;

/**
 * 戦車の砲手照準。使用ボタン——右クリック——を押している間、乗員を接眼部へ寄せ、視野を狭める。
 *
 * <p><b>視界は最後まで乗員の頭だ。</b>照準を上げても、マウスが動かすのは今まで通り頭であり、砲塔はその頭を
 * 追う——旋回速度の分だけ遅れ、俯仰の可動端で止まる。{@code GroundVehicleEntity.tickTurret} 参照。つまり
 * 照準の中と外で操作は同じ物であり、変わるのは「どこから、どれだけの倍率で見ているか」だけになる。
 *
 * <p>視界を砲腔線へ預けることもできる——実物の照準眼鏡はそうだし、AC-130 の砲手が覗いている物
 * （{@link GunCamera}）は今もそうだ——が、砲は乗員の頭より遅い。映像を砲から取ると、マウスを振った分と画面が
 * 回った分が食い違い、砲が消化しきれない入力は「手を止めても回り続ける画面」として溜まる。撃つ物が1つしか
 * 無く、その1つを乗員自身が振っている戦車では、素直に頭で見た方が扱える。
 *
 * <p><b>変えるのは目の位置だ。</b>一人称視点が既にいる場所——{@code camera.cockpit}、砲塔上面のハッチ——が
 * そのまま照準の接眼部になる。三人称で押した場合もここへ来る：覗くとは接眼部へ寄ることであり、押す前に
 * どちらのカメラだったかは関係が無い。倍率を掛けるのは {@link AimZoom} で、こちらは何も倒さない。
 *
 * <p>三人称から入る時だけ帳尻が要る。追跡カメラは車両の {@code camera.tilt} だけ下へ倒れており、砲はその分を
 * 織り込んで据えられているからだ。{@link #follow} 参照。
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
     * <p>砲塔を持たない車両では上がらない。据える物が無ければ接眼部も無く、残るのは倍率だけになる——それは
     * 照準ではなく単眼鏡だ。
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
