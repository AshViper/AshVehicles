package com.ashvehicles.client;

import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.entity.AircraftEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * パイロットの視線。コックピット内での頭の向きとして保持する。
 *
 * <p>Minecraft は視線をワールドに対する方位と仰角で保存する。地面に立つ者には十分だが、機体に固定された者には
 * 足りない。機体をバンクさせればマウスと画面が一致しなくなるし、機首を垂直近くへ向ければ方位が意味を失い、視界が
 * 滑るか固まる。
 *
 * <p>答えは頭に自由回転を与えることではない——それは独自の問題を招く——頭が実際に持つのと同じ2角を保ち、それを
 * ワールドではなく<em>機体に対して</em>測ることだ。そうすれば機体がどう寝ていても横は常に横であり、2角が衝突する
 * 姿勢も存在せず、頭が頭にできないことをすることもない。
 *
 * <p>最後の点は聞こえる以上に重要だ。自由回転として保持し、マウス移動ごとに自軸周りに回すと、頭は2通りに壊れる。
 * 垂直を越えてピッチできてしまい、そのまま上を回り込んでパイロットを背面・後ろ向きで吊るし、戻る手立ても無い。
 * さらに自軸周りの回転は交換法則を満たさないので、マウスで円を描くとロールが数度ずつ巻き込まれ、やがて水平線が
 * 恒久的に傾く。2角＋各々の制限にはどちらの問題も無い。
 *
 * <p>ワールドでの視線は、機体自身の姿勢の後にこの向きを掛けた物であり、カメラにはそれを再現する3角を渡す。
 *
 * <p>プレイヤー自身の方位と仰角も結果から更新し続ける。ゲームの他の全て——十字線が何の上にあるか、体がどちらを
 * 向くか——がそれを読むからだ。
 */
public final class CockpitView {
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);
    /**
     * パイロットが見上げ／見下ろせる限界（度）。ゲームの他の全視点にバニラが課すのと同じ制限で、理由も同じ。
     * 真上が見上げの限界であり、そこを越えて回り込む視界は「より高くを見た」のではなく「上下が逆になった」だけだ。
     */
    private static final float PITCH_LIMIT = 90.0F;
    /**
     * パイロットが左右へ振り向ける限界（度）。{@code AircraftEntity.clampRotation} で機体が既に搭乗者の体へ課して
     * いる制限と一致させてある。視界と十字線が別々の物を指す事態を避けるためだ。
     */
    private static final float YAW_LIMIT = 135.0F;

    /** コックピット内での頭の左右角。右が正。0で機首方向を見る。 */
    private static float yaw;
    /** コックピット内での頭の仰角。Minecraft 流に下を正として数える。 */
    private static float pitch;
    private static AircraftEntity aircraft;

    private CockpitView() {
    }

    /** ローカルプレイヤーが機体に搭乗し外を見ている間 true。 */
    public static boolean isActive() {
        return aircraft != null && !aircraft.isRemoved();
    }

    /**
     * カメラから毎フレーム呼ばれる。搭乗しているかを記録し、乗降のたび頭を正面向きにリセットする。
     */
    public static void follow(AircraftEntity riding) {
        if (riding != aircraft) {
            aircraft = riding;
            yaw = 0.0F;
            pitch = 0.0F;
        }
    }

    /**
     * マウス移動の分だけ頭を回す（度）。
     *
     * <p>2角とも機体に対して測るので、機体がどの向きでもその瞬間に画面上で左である方向が左になる。どちらも頭が
     * 止まる位置で止まるので、マウスで視界を上へ回り込ませたり巻き込ませたりはできない。
     */
    public static void turn(double deltaYaw, double deltaPitch) {
        yaw = Mth.clamp((float) (yaw + deltaYaw), -YAW_LIMIT, YAW_LIMIT);
        pitch = Mth.clamp((float) (pitch + deltaPitch), -PITCH_LIMIT, PITCH_LIMIT);
    }

    /**
     * 頭を、ワールド上の方向へ向けられる限り向ける。
     *
     * <p>ポインティング飛行中にマウスが動かすのは空のマークであって頭ではない。頭はその後マークを追うので、
     * パイロットは常に「機体に飛べと指示している先」を見ていることになる。2角は累積せず方向から毎回求め直すので
     * 何もドリフトしないし、マウス移動時とまったく同じくクランプされる——頭が回れる範囲を超えて動いたマークは、
     * 単に頭を可動端に残す。
     */
    public static void lookAlong(Vec3 direction) {
        if (!isActive() || direction.lengthSqr() < 1.0E-8) {
            return;
        }

        Quaternionf attitude = aircraft.getAttitude(1.0F);
        double ahead = direction.dot(Attitude.nose(attitude));
        double across = direction.dot(Attitude.right(attitude));
        double above = direction.dot(Attitude.up(attitude));

        yaw = Mth.clamp((float) Math.toDegrees(Mth.atan2(across, ahead)), -YAW_LIMIT, YAW_LIMIT);
        // 頭の仰角は、Minecraft が他の全てを数えるのと同じく下を正として数える。
        pitch = Mth.clamp((float) -Math.toDegrees(Math.asin(Mth.clamp(above, -1.0, 1.0))),
                -PITCH_LIMIT, PITCH_LIMIT);
    }

    /** ワールドでのパイロットの視線。機体の姿勢に、頭の向きを掛けた物。 */
    public static Quaternionf world(float partialTick) {
        if (!isActive()) {
            return new Quaternionf();
        }

        return new Quaternionf(aircraft.getAttitude(partialTick))
                .rotateY(-yaw * DEG_TO_RAD)
                .rotateX(pitch * DEG_TO_RAD)
                .normalize();
    }

    /**
     * プレイヤー自身の方位と仰角を、パイロットの視線に合わせ続ける。ロールはプレイヤーが持てない——Minecraft に
     * 置き場が無い——のでカメラに残るが、方向は一致させねばならない。さもないと十字線と視界が別の物を指す。
     */
    public static void applyToPlayer() {
        LocalPlayer player = Minecraft.getInstance().player;

        if (player == null || !isActive()) {
            return;
        }

        Quaternionf world = world(1.0F);
        player.setYRot(Attitude.heading(world));
        player.setXRot(Attitude.elevation(world));
        player.setYHeadRot(player.getYRot());
    }

    /** パイロットの視線を方向ベクトルで。 */
    public static Vec3 lookVector(float partialTick) {
        return Attitude.nose(world(partialTick));
    }
}
