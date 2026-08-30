package com.ashvehicles.client.ghost.adapter;

import javax.annotation.Nullable;

import com.ashvehicles.client.ghost.EntityGhost;
import com.ashvehicles.client.ghost.EntityGhostRenderer;
import com.ashvehicles.client.ghost.GhostAdapter;
import com.ashvehicles.client.ghost.GhostConfig;
import com.ashvehicles.client.ghost.GhostLOD;
import com.ashvehicles.client.ghost.GhostRenderContext;
import com.ashvehicles.client.ghost.GhostSnapshot;
import com.ashvehicles.client.ghost.geo.GhostAnimatable;
import com.ashvehicles.client.item.VehicleIcons;
import com.ashvehicles.client.model.GroundVehicleModel;
import com.ashvehicles.client.renderer.VehicleRenderer;
import com.ashvehicles.client.model.VehicleGeoModel;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.vehicle.GroundVehicleDefinition;
import com.ashvehicles.vehicle.Ride;
import com.ashvehicles.vehicle.VehicleChassis;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaternionf;

/**
 * ゴーストとしての地上車両。
 *
 * <p>戦車は機体より単純な写真だ。アニメーションファイルから再生する降着装置も、吊り下げる物も無いので、スナップ
 * ショットが運ぶのは車体の姿勢、描画元のモデル、そして動く4つ——砲塔の指向、砲の仰角、転輪の回転量、砲身の後座量
 * ——だけ。ゴーストはそれ以外から描かれない。
 *
 * <p>それが買うのは、遠距離の戦車が最も伝えるべき情報だ。2km 先の縦隊は何があろうと形の列でしかないが、こちらへ
 * 旋回した砲塔は、そっぽを向いた砲塔とは別の知らせであり、車両の他のどの情報も読めない距離でそれが読める。だから
 * 砲塔は、モデルを描く最も粗い詳細度でも運ぶ価値がある。
 *
 * <p>地上車両は機体と同様、どこにいても全クライアントへ送られる（{@code EntityTrackingMixin} 参照）が、車両は
 * 自分のチャンクを保持しないので、全員が離れれば地面ごとサーバーからアンロードされ、受信はそこで止まる。消えた
 * のではなく世界ごと眠っただけだ。だから止まっていた車両のゴーストは残る——2km 先の谷の縦隊は、まさにこうして
 * 見え続ける。走行中に受信が止まった車両（撃破）だけが即座に消える。{@code AircraftGhostAdapter} の同じ判断に
 * 理由を書いてある。
 */
public final class GroundVehicleGhostAdapter implements GhostAdapter<GroundVehicleEntity> {
    /** これ未満の速度の二乗なら、その車両は停まっていた——受信が止まった理由は撃破ではなくアンロードだ。 */
    private static final double STATIONARY = 1.0E-2;

    @Override
    public boolean keepAfterLeave(GroundVehicleEntity entity) {
        return entity.getVelocity().lengthSqr() < STATIONARY;
    }

    @Override
    public int orphanTicks() {
        return GhostConfig.machineTimeoutTicks();
    }

    // ------------------------------------------------------------------
    // スナップショット
    // ------------------------------------------------------------------

    @Override
    public GhostSnapshot snapshot(GroundVehicleEntity vehicle, @Nullable GhostSnapshot previous, long gameTime) {
        ResourceLocation id = vehicle.getVehicleId();
        GroundVehicleDefinition stats = vehicle.getStats();
        VehicleChassis.Model setup = stats.model();
        Vec3 position = vehicle.position();
        // 車両が描かれる範囲の箱であって、衝突に使う箱ではない——VehicleEntityBase.getBoundingBoxForCulling
        // 参照。車体サイズの箱でカリングすると、7m の戦車はゴーストパスが受け持った瞬間に画面端で消える。
        AABB bounds = vehicle.getBoundingBoxForCulling().move(position.reverse());
        Payload payload = new Payload(id, GroundVehicleModel.Setup.of(stats),
                GroundVehicleModel.Pose.of(vehicle, 1.0F));

        return new GhostSnapshot(
                vehicle.getUUID(),
                vehicle.getId(),
                vehicle.getType(),
                position,
                vehicle.getDeltaMovement(),
                vehicle.getYRot(),
                vehicle.getXRot(),
                vehicle.getYRot(),
                new Quaternionf(vehicle.getAttitude()),
                setup.scale(),
                vehicle.isWrecked() ? VehicleRenderer.CHARRED : 1.0F,
                VehicleGeoModel.geometryFile(id),
                VehicleGeoModel.textureFile(id),
                VehicleGeoModel.animationFile(id),
                this.billboard(id),
                bounds,
                true,
                gameTime,
                payload);
    }

    /**
     * 車両アイテムの絵として描かれる物、つまり車両の絵。モデルを描く価値の無い距離での代役として正しい物だ。
     *
     * <p>ここでは保持しない。機体自身のジオメトリから一度撮影され {@link VehicleIcons} が保持する。あちらは最初の
     * 撮影が済むまでの1〜2フレーム、何も返さない——ビルボードを持たないスナップショットは、次が撮られるまで単に
     * モデルを描く。
     */
    @Nullable
    private ResourceLocation billboard(ResourceLocation id) {
        return VehicleIcons.of(id);
    }

    // ------------------------------------------------------------------
    // 描画
    // ------------------------------------------------------------------

    @Override
    public void render(EntityGhost ghost, GhostLOD lod, GhostRenderContext context) {
        GhostSnapshot snapshot = ghost.current();

        if (lod == GhostLOD.BILLBOARD || !GhostConfig.geckoLibGhosts()) {
            if (EntityGhostRenderer.drawBillboard(snapshot, context) || !GhostConfig.geckoLibGhosts()) {
                return;
            }
            // アイコンが無い。モデル自体で用は足りる。
        }

        PoseStack poseStack = context.poseStack();
        poseStack.pushPose();
        // 車体座標系へ入る。半回転はモデル由来だ。ジオメトリは北を向き、機体は正面を +Z 方向として記述される。
        poseStack.mulPose(attitude(ghost, context.partialTick()));

        // そしてバネ上の車体を、車両自身のレンダラーと同じ方法・同じ順序で適用する——ただし走行装置をポーズ付け
        // している場合のみ。その下で自分を地面へ戻すのは車輪と履帯だからだ。
        if (GhostConfig.animation()) {
            Ride ride = ride(ghost, context.partialTick());

            poseStack.translate(0.0F, ride.heave(), 0.0F);
            poseStack.mulPose(Axis.XP.rotationDegrees(-ride.pitch()));
            poseStack.mulPose(Axis.ZP.rotationDegrees(ride.lean()));
        }

        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));

        EntityGhostRenderer.drawModel(ghost, snapshot, context, GhostConfig.animation() ? POSER : null);
        poseStack.popPose();
    }

    /** バネ上の車体の変位。他と同様、直近2つのスナップショットの間で求める。 */
    private static Ride ride(EntityGhost ghost, float partialTick) {
        Payload now = payload(ghost.current());

        if (now == null) {
            return Ride.LEVEL;
        }

        Payload then = payload(ghost.previous());

        return then == null
                ? now.pose().ride()
                : Ride.between(then.pose().ride(), now.pose().ride(), partialTick);
    }

    /** 描画に使う姿勢。直近2スナップショット間を近い側の経路で補間する。 */
    private static Quaternionf attitude(EntityGhost ghost, float partialTick) {
        Quaternionf now = ghost.current().attitude();
        Quaternionf then = ghost.previous().attitude();

        if (now == null) {
            return new Quaternionf();
        }

        if (then == null || then == now) {
            return now;
        }

        return new Quaternionf(then).slerp(now, partialTick).normalize();
    }

    // ------------------------------------------------------------------
    // 車両の動きに合わせて動かす
    // ------------------------------------------------------------------

    /**
     * 砲塔・砲・転輪・後座を、車両自身のモデルが車両から設定するのと同じやり方で、直近2スナップショットから設定
     * する。
     */
    private static final GhostAnimatable.GhostPoser POSER = (model, ghost, partialTick) -> {
        Payload now = payload(ghost.current());

        if (now == null) {
            return;
        }

        Payload then = payload(ghost.previous());
        GroundVehicleModel.Pose pose = then == null
                ? now.pose()
                : GroundVehicleModel.Pose.between(then.pose(), now.pose(), partialTick);

        GroundVehicleModel.applyPose(model, now.setup(), pose);
    };

    // ------------------------------------------------------------------
    // スナップショットが運ぶ物
    // ------------------------------------------------------------------

    @Nullable
    private static Payload payload(GhostSnapshot snapshot) {
        return (Payload) snapshot.payload();
    }

    /**
     * スナップショットが運ぶ地上車両固有の情報すべて。
     *
     * @param setup ポーズ適用の基準となる、車両ファイル由来の数値。ゴーストにはもう問い合わせる車両が無い
     */
    record Payload(ResourceLocation vehicleId, GroundVehicleModel.Setup setup, GroundVehicleModel.Pose pose) {
    }
}
