package com.ashvehicles.client.ghost.adapter;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.ghost.EntityGhost;
import com.ashvehicles.client.ghost.EntityGhostRenderer;
import com.ashvehicles.client.ghost.GhostAdapter;
import com.ashvehicles.client.ghost.GhostConfig;
import com.ashvehicles.client.ghost.GhostLOD;
import com.ashvehicles.client.ghost.GhostRenderContext;
import com.ashvehicles.client.ghost.GhostSnapshot;
import com.ashvehicles.client.model.WeaponModel;
import com.ashvehicles.entity.TargetDroneEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaternionf;

/**
 * ゴーストとしての標的ドローン。
 *
 * <p>ミサイル（{@link RocketGhostAdapter}）と同じ流儀で、姿勢は速度から取り、ジオメトリは兵装ディレクトリ
 * の自分のファイルから描く。違いはバンクだ。ミサイルはロールしないが、これは輪を回り続ける物で、引き継ぎ
 * 距離を跨いだ瞬間に翼が水平へ跳ね戻るなら、引き継ぎは隠せていない。だから姿勢は2角ではなく回転そのもの
 * （{@link GhostSnapshot#attitude}）で運び、機体と同じ経路で補間する。
 *
 * <p>最遠階層のビルボードにはアイテムのアイコンを使う。機体のような「ジオメトリから撮った写真」の仕組みに
 * は乗っていないが、上面から見たオレンジの標的機のドット絵は、数ピクセルに縮んだ距離では同じ仕事をする。
 */
public final class TargetDroneGhostAdapter implements GhostAdapter<TargetDroneEntity> {
    private static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "target_drone");
    private static final ResourceLocation BILLBOARD =
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "textures/item/target_drone_item.png");

    @Override
    public GhostSnapshot snapshot(TargetDroneEntity drone, @Nullable GhostSnapshot previous, long gameTime) {
        Vec3 position = drone.position();
        Vec3 travel = drone.getDeltaMovement();
        AABB bounds = drone.getBoundingBoxForCulling().move(position.reverse());
        float yaw = 0.0F;
        float pitch = 0.0F;

        if (travel.lengthSqr() > 1.0E-8) {
            double flat = Math.sqrt(travel.x * travel.x + travel.z * travel.z);
            yaw = (float) Math.toDegrees(Math.atan2(travel.x, travel.z));
            pitch = (float) -Math.toDegrees(Math.atan2(travel.y, flat));
        } else if (previous != null) {
            yaw = previous.yaw();
            pitch = previous.pitch();
        }

        // 生きたレンダラーが積むのと同じ順の回転。Y（方位）→X（仰角）→Z（バンク）。モデル由来の半回転は
        // 描画側で足す。
        Quaternionf attitude = new Quaternionf()
                .rotateY((float) Math.toRadians(yaw))
                .rotateX((float) Math.toRadians(pitch))
                .rotateZ((float) Math.toRadians(drone.roll(1.0F)));

        return new GhostSnapshot(
                drone.getUUID(),
                drone.getId(),
                drone.getType(),
                position,
                travel,
                yaw,
                pitch,
                yaw,
                attitude,
                1.0F,
                1.0F,
                WeaponModel.geometryFile(ID),
                WeaponModel.textureFile(ID),
                null,
                BILLBOARD,
                bounds,
                true,
                gameTime,
                null);
    }

    @Override
    public void render(EntityGhost ghost, GhostLOD lod, GhostRenderContext context) {
        GhostSnapshot snapshot = ghost.current();

        if (lod == GhostLOD.BILLBOARD || !GhostConfig.geckoLibGhosts()) {
            if (EntityGhostRenderer.drawBillboard(snapshot, context) || !GhostConfig.geckoLibGhosts()) {
                return;
            }
        }

        PoseStack poseStack = context.poseStack();

        poseStack.pushPose();
        poseStack.mulPose(attitude(ghost, context.partialTick()));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        EntityGhostRenderer.drawModel(ghost, snapshot, context, null);
        poseStack.popPose();
    }

    /**
     * 描画に使う姿勢。{@link AircraftGhostAdapter} と同じく直近2スナップショット間を補間するが、こちらは
     * 必ず近い側の半球から出発する。ここの姿勢は毎tickオイラー角から作り直しており、方位が atan2 の縁
     * （±180度）を跨ぐ tick では前後のクォータニオンが逆符号の表現に落ちる。同じ回転だが、そのまま slerp
     * すると長い方の弧——1tickかけての一回転——を通ってしまう。機体は積分し続けた姿勢なので起きないが、
     * 角度から組む物はここで符号を揃える必要がある。
     */
    private static Quaternionf attitude(EntityGhost ghost, float partialTick) {
        Quaternionf now = ghost.current().attitude();
        Quaternionf then = ghost.previous().attitude();

        if (now == null) {
            return new Quaternionf();
        }

        if (then == null || then == now) {
            return now;
        }

        Quaternionf from = new Quaternionf(then);

        if (from.dot(now) < 0.0F) {
            from.set(-from.x, -from.y, -from.z, -from.w);
        }

        return from.slerp(now, partialTick).normalize();
    }
}
