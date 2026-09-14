package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.network.MatchStatePayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 試合中にワールドへ描き足すもの——拠点の光柱と制圧範囲の環、そして「誰が味方か」の判定そのもの。
 *
 * <p><b>味方は発光で示す。</b> 線の箱ではなく、バニラの光る効果（{@code /effect glowing}）とまったく
 * 同じ輪郭だ。描いているのはここではなくゲーム本体のアウトラインパスで、この MOD が足しているのは
 * 「光って見えるか」（{@code MatchGlowMixin}）と「何色か」（{@code MatchGlowColourMixin}）の2つの答え
 * だけ。箱は機体の形を教えないし、パーツ箱の和は翼端の外まで膨らむ——輪郭は模型そのものをなぞる。
 *
 * <p><b>味方だけ。</b> 敵の位置を教える計器はこの MOD の趣味ではないし、レーダーと照準器が既にその
 * 仕事を——見つける手間と引き換えに——持っている。光らせるのは「撃ってよいか」を一瞬で決めるためで、
 * 上空から見下ろした戦車が味方か敵かは、塗装では絶対に読めない。
 *
 * <p><b>自分の機体は光らせない。</b> 一人称視点の機内が輪郭で縁取られても、誰の役にも立たない。
 *
 * <p>拠点は深度判定のある線で描く（{@code RenderType.lines()}）ので、環は地面に沿って見える。光柱だけ
 * は48ブロックと高く、尾根の向こうからでも旗の在り処が読める。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class MatchMarks {
    /** 拠点を描く距離。旗は遠くから見えてこそ意味がある。 */
    private static final double POINT_RANGE = 384.0;

    /** 制圧範囲の環の分割数。 */
    private static final int RING_STEPS = 72;

    /** 光柱の高さ（ブロック）と太さ。 */
    private static final double BEAM_HIGH = 48.0;
    private static final double BEAM_WIDE = 0.35;

    /** 中立の拠点の色。 */
    private static final int NEUTRAL = 0xFFB0B0B0;

    private static final float ALPHA = 0.85F;

    private MatchMarks() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null || minecraft.player == null || minecraft.options.hideGui) {
            return;
        }

        if (MatchView.points().isEmpty()) {
            return;
        }

        Vec3 eye = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        PoseStack poseStack = event.getPoseStack();

        // 拠点は設営中にも出す——半径を決めるのは旗を立てている最中で、その時に見えない環は役に立たない。
        drawPoints(poseStack, lines, eye);

        buffers.endBatch(RenderType.lines());
    }

    // ------------------------------------------------------------------
    // 味方
    // ------------------------------------------------------------------

    /**
     * その相手を味方として光らせるか。ゲーム本体のアウトラインパスに繋がる唯一の述語。
     *
     * <p><b>毎フレーム、世界の全エンティティについて訊かれる。</b> 弾も破片もここを通るので、型で落と
     * せるものは名簿を引く前に落とす。
     *
     * <p>機体が味方かどうかは<b>乗っている者</b>で決める。機体に書かれた陣営タグはサーバーの持ち物で、
     * クライアントには届かない（{@code Deathmatch.TEAM_KEY}）。空の機体が光らないのはそのためで、
     * 乗っていない機体を味方と呼ぶ理由も特に無い。<b>例外は AI の車両</b>——あれは乗っている者を持たない
     * まま戦うので、サーバーが UUID の名簿を別に配っている。
     */
    public static boolean isFriendly(Entity entity) {
        if (!(entity instanceof Player) && !(entity instanceof VehicleEntityBase)) {
            return false;
        }

        if (!MatchView.running()) {
            return false;
        }

        String own = MatchView.ownTeam();

        if (own == null) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Player self = minecraft.player;

        // 自分と、自分が今乗っている機体は光らせない。一人称の機内を輪郭で縁取っても誰の役にも立たない。
        if (entity == self || (self != null && entity == self.getVehicle())) {
            return false;
        }

        if (entity instanceof Player player) {
            // 機体に乗っている味方は機体の側が光る。中の人まで光らせると輪郭が二重になる。
            return !(player.getVehicle() instanceof VehicleEntityBase)
                    && own.equals(MatchView.teamIdOf(player.getUUID()));
        }

        VehicleEntityBase vehicle = (VehicleEntityBase) entity;

        if (vehicle.isWrecked()) {
            return false;
        }

        for (Entity rider : vehicle.getIndirectPassengers()) {
            if (rider instanceof Player player && own.equals(MatchView.teamIdOf(player.getUUID()))) {
                return true;
            }
        }

        // 乗っている者のいない車両でも、AI なら陣営が分かる。20 対 20 の戦場で味方の戦車が1両も光らない
        // のでは、この輪郭の意味が半分無くなる（{@code MatchStatePayload.Team.machines}）。
        return own.equals(MatchView.teamOfMachine(vehicle.getUUID()));
    }

    /** 味方の輪郭の色（RGB）。自陣の色そのもの。 */
    public static int friendlyColour() {
        String own = MatchView.ownTeam();

        return own == null ? 0xFFFFFF : MatchView.colourOfTeam(own) & 0xFFFFFF;
    }

    // ------------------------------------------------------------------
    // 拠点
    // ------------------------------------------------------------------

    private static void drawPoints(PoseStack poseStack, VertexConsumer lines, Vec3 eye) {
        for (MatchStatePayload.Point point : MatchView.points()) {
            Vec3 centre = new Vec3(point.pos().getX() + 0.5, point.pos().getY() + 1.0, point.pos().getZ() + 0.5);

            if (!centre.closerThan(eye, POINT_RANGE)) {
                continue;
            }

            int owner = point.owner().isEmpty() ? NEUTRAL : MatchView.colourOfTeam(point.owner());
            // 取られかけている拠点は、取っている側の色の環になる。制圧の進みは計器のダイヤが数で持つ
            // ので、ここで言うべきなのは「誰が今それをやっているか」だ。
            int ring = point.taking().isEmpty() ? owner : MatchView.colourOfTeam(point.taking());

            drawRing(poseStack, lines, centre, point.radius(), eye, ring);
            drawBeam(poseStack, lines, centre, eye, owner);
        }
    }

    /** 制圧範囲の環。地面の起伏は追わない——判定が円柱なので、見せるのも円でよい。 */
    private static void drawRing(PoseStack poseStack, VertexConsumer lines, Vec3 centre, double radius,
            Vec3 eye, int colour) {
        PoseStack.Pose pose = poseStack.last();
        double y = centre.y - eye.y + 0.05;

        for (int step = 0; step < RING_STEPS; step++) {
            double from = step * Math.PI * 2.0 / RING_STEPS;
            double to = (step + 1) * Math.PI * 2.0 / RING_STEPS;
            double x1 = centre.x - eye.x + Math.cos(from) * radius;
            double z1 = centre.z - eye.z + Math.sin(from) * radius;
            double x2 = centre.x - eye.x + Math.cos(to) * radius;
            double z2 = centre.z - eye.z + Math.sin(to) * radius;

            segment(pose, lines, x1, y, z1, x2, y, z2, colour);
        }
    }

    /** 拠点の光柱。線4本の細い箱で、遠くからでもどの尾根の向こうにあるかが分かる高さにしてある。 */
    private static void drawBeam(PoseStack poseStack, VertexConsumer lines, Vec3 centre, Vec3 eye, int colour) {
        double x = centre.x - eye.x;
        double y = centre.y - eye.y;
        double z = centre.z - eye.z;

        LevelRenderer.renderLineBox(poseStack, lines,
                x - BEAM_WIDE, y, z - BEAM_WIDE, x + BEAM_WIDE, y + BEAM_HIGH, z + BEAM_WIDE,
                red(colour), green(colour), blue(colour), ALPHA);
    }

    /**
     * 線1本。
     *
     * <p>{@code RenderType.lines()} は法線を要求する——線の太さをそれで決めるからで、渡さないと線が
     * 消える。向きそのものを渡せばよい。
     */
    private static void segment(PoseStack.Pose pose, VertexConsumer lines, double x1, double y1, double z1,
            double x2, double y2, double z2, int colour) {
        float nx = (float) (x2 - x1);
        float ny = (float) (y2 - y1);
        float nz = (float) (z2 - z1);
        float length = Math.max((float) Math.sqrt(nx * nx + ny * ny + nz * nz), 1.0E-5F);

        lines.addVertex(pose, (float) x1, (float) y1, (float) z1)
                .setColor(colour)
                .setNormal(pose, nx / length, ny / length, nz / length);
        lines.addVertex(pose, (float) x2, (float) y2, (float) z2)
                .setColor(colour)
                .setNormal(pose, nx / length, ny / length, nz / length);
    }

    private static float red(int colour) {
        return (colour >> 16 & 0xFF) / 255.0F;
    }

    private static float green(int colour) {
        return (colour >> 8 & 0xFF) / 255.0F;
    }

    private static float blue(int colour) {
        return (colour & 0xFF) / 255.0F;
    }
}
