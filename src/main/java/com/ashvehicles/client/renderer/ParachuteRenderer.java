package com.ashvehicles.client.renderer;

import java.util.Map;
import java.util.WeakHashMap;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.EjectionSeat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

/**
 * 開いた傘を、降りている者の頭上に描く。
 *
 * <p>白と橙の16枚のゴアでできた浅いドームと、縁から肩へ下りる吊索。開いた瞬間は細く畳まれていて、
 * {@link EjectionSeat#INFLATE_TICKS} かけて横へ膨らむ。膨らみ具合は各クライアントが「開いているのを
 * 初めて見た tick」から数えるので、遠くで開いた傘は途中から見え始めても破綻しない。
 *
 * <p>傘の向きは動きに沿って少しだけ傾ける。流れている方へ吊られた者が遅れて付いていく形。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class ParachuteRenderer {
    private static final int GORES = 16;
    private static final int RINGS = 4;

    /** 傘の半径と、足元から縁・頂点までの高さ（ブロック）。 */
    private static final float RADIUS = 3.4F;
    private static final float RIM = 6.2F;
    private static final float CROWN = 7.8F;

    /** 吊索の付く肩の高さ。 */
    private static final float SHOULDER = 1.45F;

    private static final int WHITE = 0xFFF2F0EA;
    private static final int ORANGE = 0xFFE86A1C;
    private static final int LINE = 0xFFB0B0A8;

    /** 開いているのを初めて見た tick。 */
    private static final Map<Player, Integer> SEEN = new WeakHashMap<>();

    private ParachuteRenderer() {
    }

    @SubscribeEvent
    public static void onRenderPlayer(RenderLivingEvent.Post<?, ?> event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        if (!EjectionSeat.deployed(player)) {
            SEEN.remove(player);

            return;
        }

        float partialTick = event.getPartialTick();
        int since = player.tickCount - SEEN.computeIfAbsent(player, key -> player.tickCount);
        float open = Mth.clamp((since + partialTick) / EjectionSeat.INFLATE_TICKS, 0.05F, 1.0F);

        draw(event.getPoseStack(), event.getMultiBufferSource(), player, open);
    }

    private static void draw(PoseStack poseStack, MultiBufferSource buffers, Player player, float open) {
        VertexConsumer buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(EjectedSeatRenderer.WHITE));
        int light = LightTexture.FULL_SKY;
        // 膨らむ前は細長い吹き流し。横に開くほど縮んで丸くなる
        float radius = RADIUS * (0.15F + 0.85F * open);
        float rim = RIM + (1.0F - open) * 1.5F;
        float crown = CROWN + (1.0F - open) * 1.0F;
        Vec3 drift = player.getDeltaMovement();
        float leanX = (float) Mth.clamp(-drift.x * 2.0, -0.6, 0.6);
        float leanZ = (float) Mth.clamp(-drift.z * 2.0, -0.6, 0.6);

        poseStack.pushPose();

        PoseStack.Pose pose = poseStack.last();

        for (int gore = 0; gore < GORES; gore++) {
            double a0 = Math.PI * 2.0 * gore / GORES;
            double a1 = Math.PI * 2.0 * (gore + 1) / GORES;
            int colour = (gore & 1) == 0 ? WHITE : ORANGE;

            for (int ring = 0; ring < RINGS; ring++) {
                float t0 = (float) ring / RINGS;
                float t1 = (float) (ring + 1) / RINGS;
                Vec3 p00 = dome(a0, t0, radius, rim, crown, leanX, leanZ);
                Vec3 p01 = dome(a1, t0, radius, rim, crown, leanX, leanZ);
                Vec3 p11 = dome(a1, t1, radius, rim, crown, leanX, leanZ);
                Vec3 p10 = dome(a0, t1, radius, rim, crown, leanX, leanZ);
                Vec3 normal = p01.subtract(p00).cross(p10.subtract(p00)).normalize();

                EjectedSeatRenderer.Boxes.quad(pose, buffer, light, colour,
                        (float) normal.x, (float) normal.y, (float) normal.z,
                        (float) p00.x, (float) p00.y, (float) p00.z,
                        (float) p01.x, (float) p01.y, (float) p01.z,
                        (float) p11.x, (float) p11.y, (float) p11.z,
                        (float) p10.x, (float) p10.y, (float) p10.z);
            }
        }

        // 吊索。縁から肩へ、細い板で
        for (int line = 0; line < GORES; line += 2) {
            double angle = Math.PI * 2.0 * line / GORES;
            Vec3 top = dome(angle, 0.0F, radius, rim, crown, leanX, leanZ);
            Vec3 bottom = new Vec3(Math.cos(angle) * 0.25, SHOULDER, Math.sin(angle) * 0.25);
            Vec3 along = top.subtract(bottom);
            Vec3 side = along.cross(new Vec3(0.0, 1.0, 0.0));

            if (side.lengthSqr() < 1.0E-6) {
                continue;
            }

            side = side.normalize().scale(0.03);

            EjectedSeatRenderer.Boxes.quad(pose, buffer, light, LINE, 0.0F, 1.0F, 0.0F,
                    (float) (bottom.x - side.x), (float) bottom.y, (float) (bottom.z - side.z),
                    (float) (bottom.x + side.x), (float) bottom.y, (float) (bottom.z + side.z),
                    (float) (top.x + side.x), (float) top.y, (float) (top.z + side.z),
                    (float) (top.x - side.x), (float) top.y, (float) (top.z - side.z));
        }

        poseStack.popPose();
    }

    /** ドームの面上の点。t=0 が縁、t=1 が頂点。上ほど流れと逆へ傾く。 */
    private static Vec3 dome(double angle, float t, float radius, float rim, float crown, float leanX,
            float leanZ) {
        double polar = t * Math.PI / 2.0;
        double r = radius * Math.cos(polar);
        double y = rim + (crown - rim) * Math.sin(polar);
        double height = y / crown;

        return new Vec3(Math.cos(angle) * r + leanX * height * 3.0, y, Math.sin(angle) * r + leanZ * height * 3.0);
    }
}
