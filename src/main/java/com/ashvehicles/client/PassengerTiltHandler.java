package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.AircraftEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import com.ashvehicles.vehicle.Attitude;

import org.joml.Quaternionf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

/**
 * 機体の搭乗者を機体へ固定し、機体と共にバンク・ピッチさせる。
 *
 * <p>Minecraft は全エンティティを直立で描き、位置も各自で補間するので、放っておくと搭乗者は、周りの機体が翼端で
 * 立っている間も水平のままだ。しかもきつい旋回では2つの補間が食い違い、コックピットから流れ出てしまう。
 *
 * <p>たまたま描かれた位置で搭乗者を傾けるのではなく、機体からポーズを組み直す。機体自身の原点へ戻り、機体の姿勢
 * で回し、そこから座席へ出る。モデルレンダラーが使うのと同じ原点・同じ回転なので、搭乗者自身の位置が何をして
 * いようと両者がずれることはない。
 *
 * <p>ポーズは搭乗者の描画前に push し、後で pop する。pre イベントをキャンセルすると post イベントは飛ばされる
 * し、キャンセルされたイベントはここへ届かないので、2つは対のまま保たれる。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class PassengerTiltHandler {
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderRiderPre(RenderLivingEvent.Pre<?, ?> event) {
        Entity rider = event.getEntity();

        if (!(rider.getVehicle() instanceof AircraftEntity aircraft)) {
            return;
        }

        float partialTick = event.getPartialTick();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();

        // このフレームで機体モデルが描かれている位置まで戻る。
        Vec3 correction = aircraft.getPosition(partialTick).subtract(rider.getPosition(partialTick));
        poseStack.translate(correction.x, correction.y, correction.z);

        // 機体座標系へ回す。+Z が機首方向、+Y が上、したがって +X は左。
        Quaternionf attitude = aircraft.getAttitude(partialTick);
        poseStack.mulPose(attitude);

        // 座席へ出て、そこから搭乗者自身の取り付け点だけ下がる。
        //
        // <p><b>この引き算が無いと、描かれる搭乗者だけが座席の上に浮く。</b>座席のオフセットが指すのは
        // 「乗り物側の取り付け点」であって搭乗者の足元ではない。Minecraft は搭乗者を
        // {@code 乗り物側 − 搭乗者側} に置き（{@code Entity.positionRider}）、プレイヤーの搭乗者側は
        // {@code Player.DEFAULT_VEHICLE_ATTACHMENT}＝{@code (0, 0.6, 0)} なので、実体は座席の 0.6 下に
        // 座っている。ここは搭乗者の位置を使わずに機体から組み直すので、引かなければその 0.6 が戻って
        // しまい、当たり判定・視点・降車位置より 0.6 高い所にモデルだけが描かれることになる。
        //
        // <p><b>機体の下へ引く。ワールドの下ではない。</b> バニラのこの値は Y 軸周りにしか回らないが、
        // {@link com.ashvehicles.entity.VehicleEntityBase#getPassengerAttachmentPoint} が置く側でそれを
        // 機体軸へ直しているので、こちらも同じ軸で引く——だから {@code mulPose} の<em>後</em>、座席と
        // 同じ平行移動に畳み込む。世界の鉛直へ引いていた頃は、傾けた機体で乗員が低い翼の側へ滑り出して
        // いた（90 度バンクで約 0.85 ブロック）。取り付け点は縦にしか伸びないので、引くのは y だけでよい。
        //
        // <p>地上車両にはこの経路が無く、搭乗者は最初から実体の位置に描かれている。合わせているのは
        // そちらであって、ずれていたのは機体だけだった。
        Vec3 seat = aircraft.getSeatOffset(aircraft.getSeatIndex(rider));
        Vec3 drop = rider.getVehicleAttachmentPoint(aircraft);
        poseStack.translate(-seat.x, seat.y - drop.y, seat.z);

        // 方位を打ち消す。軸は機体ではなく座席。搭乗者は機体と共に傾くが、顔は自分が見ている方を向いたまま。
        poseStack.mulPose(Axis.YP.rotationDegrees(Attitude.heading(attitude)));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderRiderPost(RenderLivingEvent.Post<?, ?> event) {
        if (event.getEntity().getVehicle() instanceof AircraftEntity) {
            event.getPoseStack().popPose();
        }
    }

    private PassengerTiltHandler() {
    }
}
