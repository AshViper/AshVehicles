package com.ashvehicles.client;

import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.vehicle.GroundVehicleDefinition;
import com.ashvehicles.vehicle.VehicleShape;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.phys.Vec3;

/**
 * 車両を真上から見た図。隅に機体そのものとして描く。自前のモデル、実際の位置にある自前の砲塔、砲手が振った側へ
 * 出た自前の砲身。
 *
 * <p><b>なぜ数値ではなく絵なのか。</b>戦車乗員の常なる問いは車体がどちらを向いているかであり、それが問いになる
 * のは答えが画面のどこにも無いからだ。視界は砲手の見る方へ行き、砲塔はそれに追随し、車体は運転手が最後に残した
 * 向きに留まる。砲の向きへ発進するのは溝に落ちる古典的な方法だ。度数の方位は一瞬の暗算を経てようやく答えになる
 * ——そして履帯が既に動いているとき、まさに手元に無いのがその一瞬だ。
 *
 * <p><b>静止させるのは車体ではなく画面だ。</b>パネルの上方向が乗員の視線なので、砲塔は上端近くに座り、その下で
 * <em>車体</em>が振れる。実際に問われるのはその向きの問いだ。「砲塔は何度を向いているか」ではなく「今履帯を出し
 * たら、見ている物に対してどこへ行き着くか」である。砲身とパネル上端の隙間はこれから回るべき旋回量なので、視界に
 * 追い付いていない砲塔はそうと示す。
 *
 * <p><b>本物のモデルだ。</b>当たり判定の箱から組んだ輪郭ではなく、ワールドで描かれるままの機体を同じレンダラーに
 * 通す。だから砲塔も砲身も後座も走行装置も全てモデル通りの位置にあり、パックへ追加した車両のためにここへ書く物は
 * 何も無い。コストは1フレームにモデル1つの2回目の描画で、それはプレイヤーが座っている機体1台だけだ。
 *
 * <p>当たり判定の箱も読むが、描画がどこまで届くべきかを決めるためだけだ。スケールは砲塔が掃く円全体を収めるので、
 * 真横へ据えた砲もパネル内に収まる。
 */
final class PlanView {
    /** パネルの大きさ（ピクセル）。砲塔の掃く範囲が正方なので正方形。 */
    static final int SIZE = 62;
    /** 画面隅からの寄せ幅。 */
    static final int INSET = 8;
    /** 枠の内側で機体を近付けない余白。 */
    private static final int MARGIN = 4;
    /**
     * モデルを画面のどれだけ奥に描くか。
     *
     * <p>土台のパネル（深度0で描く）より手前で、かつ前後に戦車半分の奥行きが収まるだけの余裕がある位置。
     */
    private static final float DEPTH = 60.0F;

    private PlanView() {
    }

    /** 左下隅に描く。 */
    static void draw(GuiGraphics graphics, GroundVehicleEntity vehicle, float partialTick) {
        int left = INSET;
        int top = HudScale.height(graphics) - INSET - SIZE;

        panel(graphics, left, top);

        float scale = (SIZE / 2.0F - MARGIN) / reach(vehicle);

        // 枠で切り取る。スケールは当たり判定の箱から取るが実際に描くのはモデルであり、後者が前者より大きく
        // ないという保証はどこにも無い。
        HudScale.scissor(graphics, left + 1, top + 1, left + SIZE - 1, top + SIZE - 1);
        model(graphics, vehicle, scale, left + SIZE / 2, top + SIZE / 2, partialTick);
        graphics.disableScissor();
    }

    /** 枠と、上端が乗員の視線方向であることを示す上端のマーク。 */
    private static void panel(GuiGraphics graphics, int left, int top) {
        int right = left + SIZE;
        int bottom = top + SIZE;

        graphics.fill(left, top, right, bottom, AircraftHud.SHADOW);
        graphics.fill(left, top, right, top + 1, AircraftHud.DIM);
        graphics.fill(left, bottom - 1, right, bottom, AircraftHud.DIM);
        graphics.fill(left, top, left + 1, bottom, AircraftHud.DIM);
        graphics.fill(right - 1, top, right, bottom, AircraftHud.DIM);

        int middleX = left + SIZE / 2;

        for (int row = 0; row < 3; row++) {
            graphics.fill(middleX - row, top + 2 + row, middleX + row + 1, top + 3 + row, AircraftHud.DIM);
        }
    }

    /**
     * 機体を、視線を画面上方向にして上から描く。
     *
     * <p>この計器専用の処理ではなくエンティティレンダラーに通すので、隅に出るのは窓の外の物と同じモデル・同じ姿勢
     * ・同じ砲塔角になる。回転2つで全てが済む。1つ目は世界を倒して「見下ろす」を「画面奥へ見る」に変え、2つ目は
     * 乗員が見ている方位が上に来るよう回す。
     *
     * <p>インベントリモデルとして照らし、意図的に最大輝度で描く。夜の坑道の底の戦車も運転手が読めるべき戦車だし、
     * 空と一緒に暗くなる計器は計器ではない。
     */
    private static void model(GuiGraphics graphics, GroundVehicleEntity vehicle, float scale,
            int centreX, int centreY, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        PoseStack pose = graphics.pose();
        // 車体の向きではなく乗員の視線を使う。それがこの計器の要点だ。プレイヤーではなくカメラから取るので、
        // 照準と三人称視点が互いに一致する。
        float bearing = minecraft.gameRenderer.getMainCamera().getYRot();

        pose.pushPose();
        pose.translate(centreX, centreY, DEPTH);
        // ブロックをピクセルへ。z を反転するのは、画面が世界と逆向きに深度を数えるからだ。2つの回転は右から左へ
        // 読む。まず機体を自身の鉛直軸周りに回し、次に世界全体を前へ倒して伏せさせる。
        pose.scale(scale, scale, -scale);
        pose.mulPose(Axis.XN.rotationDegrees(90.0F));
        pose.mulPose(Axis.YP.rotationDegrees(bearing + 180.0F));

        Lighting.setupForEntityInInventory();
        // 影は無し。下に地面が無いし、何も無い所へ描いた影はパネルを横切る黒い染みになる。
        dispatcher.setRenderShadow(false);
        dispatcher.render(vehicle, 0.0, 0.0, 0.0, 0.0F, partialTick, pose, graphics.bufferSource(),
                LightTexture.FULL_BRIGHT);
        // バッチに残さず今描く。この後の画面上の物は全て平面であり、バッファに残ったモデルはその上に出てしまう。
        graphics.flush();
        dispatcher.setRenderShadow(true);
        Lighting.setupFor3DItems();
        pose.popPose();
    }

    /**
     * 描画が中心からどこまで届く必要があるか（ブロック）。
     *
     * <p>車体は動かないのでそのままの位置で測る。砲塔上の物は掃く円として測る。車首方向ではパネルに収まるのに真横
     * へ据えた瞬間に枠外へ出る砲身は、まさにその角度を問われているときに機能しない計器だ。
     */
    private static float reach(GroundVehicleEntity vehicle) {
        GroundVehicleDefinition stats = vehicle.getStats();
        Vec3 ring = stats.turret().ring();
        double furthest = stats.hitbox().width() * 0.5;

        for (VehicleShape.Box box : vehicle.getShape().boxes()) {
            double halfWidth = box.size().x * 0.5;
            double halfLength = box.size().z * 0.5;

            if (box.mount() == VehicleShape.Mount.HULL) {
                furthest = Math.max(furthest, Math.abs(box.offset().x) + halfWidth);
                furthest = Math.max(furthest, Math.abs(box.offset().z) + halfLength);
            } else {
                double fromRing = Math.hypot(box.offset().x - ring.x, box.offset().z - ring.z);

                furthest = Math.max(furthest, Math.hypot(ring.x, ring.z) + fromRing
                        + Math.hypot(halfWidth, halfLength));
            }
        }

        return (float) Math.max(furthest, 0.5);
    }
}
