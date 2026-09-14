package com.ashvehicles.client;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.RemoteLink;
import com.ashvehicles.entity.VehicleEntityBase;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;

/**
 * 兵装の切り替えはマウスホイール。機体でも車両でも、操縦している者の手元で同じ操作になる。
 *
 * <p><b>受け口が1つなのは、2つにすると取り合いになるからだ。</b>以前はキー1つを機体側と車両側の
 * ハンドラが奪い合っており、両者は「相手が乗っている時は身を引く」という互いの鏡の条件で辛うじて
 * 成立していた。片方がその条件を外した瞬間に押下は消え、症状は「兵装切り替えに長押しが要る」という
 * 無関係な形で現れる（{@code GroundVehicleInputHandler} の当時の注記）。溜める場所を1つにすれば、
 * その調整はそもそも要らない。
 *
 * <p><b>溜めてから渡す。</b>ホイールの通知は tick ではなくフレームで来る。そのまま送れば1tick に
 * 何本もパケットが出るので、1tick に1度まとめて渡す——入力の経路はキーだった頃と同じ形のままだ。
 *
 * <p><b>バニラの持ち替えは飲み込む。</b>操縦席でホットバーが動く理由は無いし、動けば兵装を1つ送る
 * たびに手持ちが変わる。飲み込むのは操縦している間だけなので、同乗者と砲手は従来通りホイールで
 * 持ち替えられる。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class WeaponScroll {
    /**
     * 1 tick で送れる最大段数。
     *
     * <p>勢いよく弾いたホイールは数十段を報告する。その1回で一覧を何周もするのは「送った」ではなく
     * 「飛んだ」であり、乗員には何が選ばれたのか読めない。20分の1秒の間に人が正直に回せる段数は
     * たかが知れている。
     */
    private static final int MOST = 4;

    /** 溜まっている送り数。 */
    private static int steps;

    /**
     * その送り数が誰に向けられた物か。エンティティ ID。
     *
     * <p>覚えておくのは、乗り換えを跨いで持ち越さないためだ。戦車で回してから降りて機体に乗ると、
     * 溜まったままの段数が最初の tick で機体の兵装を送ってしまう。持ち主が違えば捨てる。
     */
    private static int owner = -1;

    private WeaponScroll() {
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;

        if (player == null || minecraft.screen != null) {
            return;
        }

        Entity driven = controlled(player);

        if (driven == null) {
            return;
        }

        double turn = event.getScrollDeltaY();

        if (turn == 0.0) {
            return;
        }

        if (owner != driven.getId()) {
            steps = 0;
            owner = driven.getId();
        }

        // 奥へ回すと次、手前へ回すと前。getScrollDeltaY は奥が正で、その符号をそのまま送り数にする。
        steps += turn > 0.0 ? 1 : -1;
        event.setCanceled(true);
    }

    /**
     * 溜まっている送り数を取り出して空にする。
     *
     * <p><b>呼ぶのは機体と車両の両ハンドラで、毎tick、操縦していなくても呼ぶ。</b>だからここが
     * 「自分宛てでなかった」場合に溜まりを捨ててはならない。捨てると、同じtickの後から回ってくる
     * もう一方のハンドラは常に空を受け取る——受け口を1つにして無くしたはずのキーの取り合いが、
     * 取り出す側で復活する。実際そうなっていて、症状は「機体だけ兵装が切り替わらない」だった
     * （どちらが先に回るかは登録順で決まり、負けた方が全部落とす）。
     *
     * <p>捨ててよいのは持ち主自身から取り出したときだけ。他人宛ての溜まりには触れず、そのまま
     * 持ち主のハンドラへ渡す。
     *
     * @param driven 今その手が操縦している物。溜まった分の持ち主でなければ0を返し、溜まりは残す
     */
    public static int take(@Nullable Entity driven) {
        if (driven == null || driven.getId() != owner) {
            return 0;
        }

        int taken = Mth.clamp(steps, -MOST, MOST);
        steps = 0;
        owner = -1;

        return taken;
    }

    /**
     * このプレイヤーが今操縦している物。していなければ null。
     *
     * <p>無人機の操作者も含む。席に座っていないだけで操縦桿を握っているのはその人であり、同じ手元で
     * 同じ機体を飛ばしている以上、ホイールも同じ物を送るべきだ（{@code RemoteLink} 参照）。
     */
    @Nullable
    private static Entity controlled(LocalPlayer player) {
        if (player.getVehicle() instanceof VehicleEntityBase machine
                && machine.getControllingPassenger() == player) {
            return machine;
        }

        AircraftEntity drone = RemoteLink.linkedDrone(player);

        return drone;
    }
}
