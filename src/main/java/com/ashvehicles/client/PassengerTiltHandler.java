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

        // 座席へ出る。ここでは座席が定義された軸で測ることになる。
        Vec3 seat = aircraft.getSeatOffset(aircraft.getSeatIndex(rider));
        poseStack.translate(-seat.x, seat.y, seat.z);

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
