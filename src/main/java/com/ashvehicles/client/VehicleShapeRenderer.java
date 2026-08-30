package com.ashvehicles.client;

import java.util.List;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.AshVehicles;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.vehicle.VehicleShape;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 車両の当たり判定の箱を、書かれている通りに描く。車両と共に傾き、砲塔と共に回り、各箱が持つ角度も反映する。
 *
 * <p>Minecraft 自身の当たり判定オーバーレイではこれを表示できない。ゲームが衝突相手にするのは傾いた箱を囲む直立
 * の箱なので、オーバーレイは機体がバンクした瞬間に主翼を背の高い板として表示し、主翼そのものは決して表示しない
 * ——戦車の砲も、砲塔が車首から外れた瞬間にほぼ正方形の箱になる。ゲームが何と衝突するかについては正直だが、
 * 形状がモデルに合っているかの確認には役立たない。こちらの用途はそれだ。
 *
 * <p>当たり判定オーバーレイと連動して表示するので F3+B で出る。隣に出るゲーム自身の輪郭を読む補助ではなく、
 * これが形状そのものだ。今や Minecraft が描く物はどれも、機体が被弾・衝突する相手ではない。この箱がそれだ。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class VehicleShapeRenderer {
    private static final float RED = 0.25F;
    private static final float GREEN = 1.0F;
    private static final float BLUE = 0.45F;
    private static final float ALPHA = 0.9F;
    /** 砲腔線を引く長さ（ブロック）。砲がどこを狙っているかが読めれば足りる。 */
    private static final double BORE_LINE = 24.0;
    /** パイロンは赤で描く。兵装を吊る位置が機体構造の一部と誤解されないように。 */
    private static final float PYLON_RED = 1.0F;
    private static final float PYLON_GREEN = 0.2F;
    private static final float PYLON_BLUE = 0.2F;
    /**
     * 砲塔が運ぶ箱は琥珀色で描く。確認する価値があるのは「砲塔と共に回るか」だけであり、黙って車体に留まった箱は
     * 砲を真横へ据えるまで正しい箱とまったく同じに見えるからだ。
     */
    private static final float TURRET_RED = 1.0F;
    private static final float TURRET_GREEN = 0.75F;
    private static final float TURRET_BLUE = 0.2F;
    /** これを超えると確認する物は無く、遅くする要素ばかりになる。 */
    private static final double RANGE = 96.0;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null || !minecraft.getEntityRenderDispatcher().shouldRenderHitBoxes()) {
            return;
        }

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 eye = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        PoseStack poseStack = event.getPoseStack();

        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (!entity.position().closerThan(eye, RANGE)) {
                continue;
            }

            if (entity instanceof AircraftEntity aircraft) {
                draw(poseStack, lines, aircraft, eye, partialTick);
            } else if (entity instanceof GroundVehicleEntity vehicle) {
                draw(poseStack, lines, vehicle, eye, partialTick);
            }
        }

        buffers.endBatch(RenderType.lines());
    }

    private static void draw(PoseStack poseStack, VertexConsumer lines, AircraftEntity aircraft,
            Vec3 eye, float partialTick) {
        VehicleShape shape = Definitions.shape(aircraft.getAircraftId());
        List<AircraftDefinition.Hardpoint> hardpoints = aircraft.getStats().hardpoints();

        if (shape.boxes().isEmpty() && hardpoints.isEmpty()) {
            return;
        }

        Vec3 position = aircraft.getPosition(partialTick);

        poseStack.pushPose();
        poseStack.translate(position.x - eye.x, position.y - eye.y, position.z - eye.z);
        poseStack.mulPose(aircraft.getAttitude(partialTick));

        for (VehicleShape.Box box : shape.boxes()) {
            poseStack.pushPose();
            // 機体座標系では +X が左を指すので、右へのオフセットは負値になる。
            poseStack.translate(-box.offset().x, box.offset().y, box.offset().z);
            poseStack.mulPose(box.orientation());

            Vec3 half = box.size().scale(0.5);
            LevelRenderer.renderLineBox(poseStack, lines,
                    -half.x, -half.y, -half.z, half.x, half.y, half.z, RED, GREEN, BLUE, ALPHA);

            poseStack.popPose();
        }

        // パイロンは赤で、機体構造とは別種の物として読めるようにする。これらは機体の一部ではなく物を吊る場所
        // であり、ハードポイントを目視で配置している間はその正確な位置が非常に重要だ。
        for (AircraftDefinition.Hardpoint hardpoint : hardpoints) {
            poseStack.pushPose();
            poseStack.translate(-hardpoint.pos().x, hardpoint.pos().y, hardpoint.pos().z);

            double half = AircraftEntity.PYLON_BOX / 2.0;
            LevelRenderer.renderLineBox(poseStack, lines,
                    -half, -half, -half, half, half, half, PYLON_RED, PYLON_GREEN, PYLON_BLUE, ALPHA);

            poseStack.popPose();
        }

        poseStack.popPose();

        // 砲座は砲腔線で示す。位置は既にパイロンの箱が示しているので、ここで足りないのは向きだけだ——
        // そして可動範囲を機体ファイルで詰めている間に見たいのはまさにそれ。線はワールド座標で引く。
        // 機体の姿勢を通した後の答えであり、それが弾の出ていく線そのものだから。
        for (int index = 0; index < aircraft.getStations().count(); index++) {
            Vec3 from = aircraft.getStations().muzzle(index, partialTick).subtract(eye);
            Vec3 to = from.add(aircraft.getStations().direction(index, partialTick).scale(BORE_LINE));

            lines.addVertex(poseStack.last(), (float) from.x, (float) from.y, (float) from.z)
                    .setColor(PYLON_RED, PYLON_GREEN, PYLON_BLUE, ALPHA)
                    .setNormal(poseStack.last(), 0.0F, 1.0F, 0.0F);
            lines.addVertex(poseStack.last(), (float) to.x, (float) to.y, (float) to.z)
                    .setColor(PYLON_RED, PYLON_GREEN, PYLON_BLUE, ALPHA)
                    .setNormal(poseStack.last(), 0.0F, 1.0F, 0.0F);
        }
    }

    /**
     * 地上車両向けの同じ処理。砲塔の箱は旋回輪の周りに回す。
     *
     * <p>車両が組み立てるのと同じ手順で組む——旋回輪へ入り、旋回量だけ回し、そこから箱へ出る——ので、描かれる物は
     * 実際に撃たれている物であって、それについての二つ目の見解ではない。車両座標系では +X が左を指すので右への
     * オフセットは負値になり、同じ理由で旋回は Y 軸周りの負の回転になる。
     */
    private static void draw(PoseStack poseStack, VertexConsumer lines, GroundVehicleEntity vehicle,
            Vec3 eye, float partialTick) {
        VehicleShape shape = Definitions.shape(vehicle.getVehicleId());

        if (shape.boxes().isEmpty()) {
            return;
        }

        Vec3 position = vehicle.getPosition(partialTick);
        Vec3 ring = vehicle.getStats().turret().ring();
        Vec3 trunnion = vehicle.getStats().armament().trunnion();
        float traverse = vehicle.getTurretYaw(partialTick);
        float elevation = vehicle.getGunPitch(partialTick);

        poseStack.pushPose();
        poseStack.translate(position.x - eye.x, position.y - eye.y, position.z - eye.z);
        poseStack.mulPose(vehicle.getAttitude(partialTick));

        for (VehicleShape.Box box : shape.boxes()) {
            boolean onTurret = box.mount() == VehicleShape.Mount.TURRET || box.mount() == VehicleShape.Mount.GUN;
            boolean onGun = box.mount() == VehicleShape.Mount.GUN;
            Vec3 offset = onTurret
                    ? box.offset().subtract(onGun ? trunnion : ring)
                    : box.offset();

            poseStack.pushPose();

            if (onTurret) {
                poseStack.translate(-ring.x, ring.y, ring.z);
                poseStack.mulPose(Axis.YP.rotationDegrees(-traverse));
            }

            // 砲自身の支点は砲塔上のどこかにあるので、旋回後は旋回済み座標系内でさらに移動して到達する。上で
            // 車両原点から旋回輪へ到達したのとまったく同じ手順だ。
            if (onGun) {
                Vec3 fromRing = trunnion.subtract(ring);
                poseStack.translate(-fromRing.x, fromRing.y, fromRing.z);
                poseStack.mulPose(Axis.XP.rotationDegrees(-elevation));
            }

            poseStack.translate(-offset.x, offset.y, offset.z);
            poseStack.mulPose(box.orientation());

            Vec3 half = box.size().scale(0.5);
            LevelRenderer.renderLineBox(poseStack, lines,
                    -half.x, -half.y, -half.z, half.x, half.y, half.z,
                    onTurret ? TURRET_RED : RED,
                    onTurret ? TURRET_GREEN : GREEN,
                    onTurret ? TURRET_BLUE : BLUE,
                    ALPHA);

            poseStack.popPose();
        }

        poseStack.popPose();
    }

    private VehicleShapeRenderer() {
    }
}
