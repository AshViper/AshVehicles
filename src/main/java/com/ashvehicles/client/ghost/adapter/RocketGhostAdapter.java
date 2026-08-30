package com.ashvehicles.client.ghost.adapter;

import javax.annotation.Nullable;

import com.ashvehicles.client.ghost.EntityGhost;
import com.ashvehicles.client.ghost.EntityGhostRenderer;
import com.ashvehicles.client.ghost.GhostAdapter;
import com.ashvehicles.client.ghost.GhostConfig;
import com.ashvehicles.client.ghost.GhostLOD;
import com.ashvehicles.client.ghost.GhostRenderContext;
import com.ashvehicles.client.ghost.GhostSnapshot;
import com.ashvehicles.client.model.WeaponModel;
import com.ashvehicles.entity.RocketEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * ゴーストとしてのロケットとミサイル。
 *
 * <p>機体と同じ仕組みだ。スナップショットが位置・進行方向・描画元となる兵装のジオメトリを運び、ゴーストはそれだけ
 * から描かれる。機体よりここでの重要度は高い。ミサイルの飛翔の面白い部分は誰もロードしていない地面の上で起きるし、
 * ミサイルは定義上遠くの物を狙うからだ。
 *
 * <p>ポーズを付ける物もアニメーションする物も無いので、どの階層でも同じモデルを描く。階層が決めるのは「そもそも
 * どこまで描くか」であり、パスは今も遠方面の内側へ引き寄せ、霧から外す。
 */
public final class RocketGhostAdapter implements GhostAdapter<RocketEntity> {
    /**
     * 機体や地上車両と違い、ロケットはどこにいても全クライアントへ送られるわけではない——
     * {@code EntityTrackingMixin} 参照。あちらはロケットに、無制限ではなくエンティティタイプ登録時の有限の範囲を
     * 与える。その範囲の縁でまだ飛んでいるミサイルこそ、このゴーストが存在する理由そのものだ。クライアントが受信を
     * やめた瞬間に捨てるのではなく、最後のスナップショットから保持して描き続けるので、長射程の後半が黙って切り
     * 捨てられない。
     */
    @Override
    public boolean keepAfterLeave(RocketEntity entity) {
        return true;
    }

    @Override
    public GhostSnapshot snapshot(RocketEntity rocket, @Nullable GhostSnapshot previous, long gameTime) {
        Vec3 position = rocket.position();
        Vec3 travel = rocket.getDeltaMovement();
        AABB bounds = rocket.getBoundingBox().move(position.reverse());
        // 飛行経路に沿った方位と仰角。ゲーム流儀ではなくレンダラーが適用する回転の形で持つ。ミサイルに語るべき
        // 自前のヨーは無く、単に進行方向へ寝ているだけだ。
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

        return new GhostSnapshot(
                rocket.getUUID(),
                rocket.getId(),
                rocket.getType(),
                position,
                travel,
                yaw,
                pitch,
                yaw,
                null,
                1.0F,
                1.0F,
                WeaponModel.geometryFile(rocket.getWeaponId()),
                WeaponModel.textureFile(rocket.getWeaponId()),
                null,
                null,
                bounds,
                true,
                gameTime,
                null);
    }

    @Override
    public void render(EntityGhost ghost, GhostLOD lod, GhostRenderContext context) {
        if (!GhostConfig.geckoLibGhosts()) {
            return;
        }

        GhostSnapshot snapshot = ghost.current();
        PoseStack poseStack = context.poseStack();

        poseStack.pushPose();
        // まず飛行経路に沿わせ、次にモデル由来の半回転。ジオメトリは北——つまり -Z——を向いて作られるが、上で求めた
        // 方位は経路方向を +Z とする。
        poseStack.mulPose(Axis.YP.rotationDegrees(snapshot.yaw()));
        poseStack.mulPose(Axis.XP.rotationDegrees(snapshot.pitch()));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        EntityGhostRenderer.drawModel(ghost, snapshot, context, null);
        poseStack.popPose();
    }
}
