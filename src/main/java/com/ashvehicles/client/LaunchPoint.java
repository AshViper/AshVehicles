package com.ashvehicles.client;

import com.ashvehicles.client.screen.LaunchConsoleScreen;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.network.DesignatePayload;
import com.ashvehicles.weapon.WeaponDefinition;

import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 座標へ飛ぶ弾を積んだ発射機の照準。乗員は盤に<em>座標を打ち込んで</em>目標を決める。
 *
 * <p><b>なぜシーカーではないのか。</b>この種の兵器は物を探さない。発射前に座標を入れ、弾はそこへ飛ぶ。
 * 掃引も捕捉も無く、狙われている側に警報も出ない——そこが、空を掃いて機体を掴む発射筒
 * （{@link com.ashvehicles.weapon.TargetLock}）との違いの全部だ。
 * {@link WeaponDefinition.Guidance.Seeker#POINT} 参照。
 *
 * <p><b>なぜ十字線でもないのか。</b>射程は地平線の遥か向こうにあり、乗員が目標を目にすることは一度も無い。
 * 見えない物に十字線を合わせる手順は作れないので、代わりに盤がある——{@link LaunchConsoleScreen}。開くのは
 * シーカーを持つ車両が目標を掴むのと同じキーで、言っていることも同じだ。「この筒に次は何を撃たせるか」。
 *
 * <p>ここが持つのはクライアント側の半分だけ。座標を保持してよいかを決めるのはサーバーで
 * （{@link GroundVehicleEntity#designate}）、射程の判定はここと向こうで同じ数を見ている——ここのは、押す前に
 * 見せるための先出しである。
 */
public final class LaunchPoint {
    private LaunchPoint() {
    }

    /** この車両が座標で狙う発射機を積んでいるか。盤も計器もこの1つの問いで分かれる。 */
    public static boolean lays(GroundVehicleEntity vehicle) {
        return vehicle.laysPoint();
    }

    /**
     * 毎tick。ロックキーが押されたら盤を開く。
     *
     * <p>押下で読む。押しっぱなしは「押している間ずっと開き直す」であって、乗員の意図ではない。
     */
    public static void tick(GroundVehicleEntity vehicle) {
        if (!lays(vehicle)) {
            return;
        }

        while (ModKeyMappings.RADAR_LOCK.consumeClick()) {
            LaunchConsoleScreen.open(vehicle);
        }
    }

    /** 打ち込まれた座標を発射機へ渡す。 */
    public static void lock(GroundVehicleEntity vehicle, Vec3 point) {
        // 高さは入力させず、こちらで最善の推測を置く。列がロード済みならその地表、そうでなければ車両自身の
        // 高さ。どちらにせよ推定と申告するので、マークは自分の下に地面が現れた時点で真下へ降りる。
        // DesignationEntity 参照。
        double surface = Terrain.surface(vehicle.level(), point);
        Vec3 aimed = new Vec3(point.x, Double.isNaN(surface) ? vehicle.getY() : surface, point.z);

        PacketDistributor.sendToServer(new DesignatePayload(false, aimed, -1, true));
    }

    /** 据えてある座標を捨てる。 */
    public static void clear() {
        PacketDistributor.sendToServer(DesignatePayload.CLEAR);
    }

    /**
     * その座標が発射機の届く範囲にあるか。水平距離で測る。
     *
     * <p>射程はミサイルファイルの {@code guidance.lock_range}。シーカーを持つ弾ではあれが「どこまで掴めるか」
     * だが、掴む物を持たないこの弾では「どこまで送れるか」になる——どちらにせよ、その弾が交戦できる距離を
     * 1つの数で言っている場所だ。サーバーも同じ数で判断する。
     */
    public static boolean withinReach(GroundVehicleEntity vehicle, Vec3 point) {
        return point.subtract(vehicle.position()).horizontalDistance() <= reachOf(vehicle);
    }

    /** その車両の発射機が届く水平距離（ブロック）。座標で狙う弾でなければ0。 */
    public static double reachOf(GroundVehicleEntity vehicle) {
        WeaponDefinition missile = vehicle.getStats().launcher().missile()
                .map(Definitions::weapon).orElse(null);

        return missile == null ? 0.0
                : missile.guidance().map(WeaponDefinition.Guidance::lockRange).orElse(0.0F);
    }
}
