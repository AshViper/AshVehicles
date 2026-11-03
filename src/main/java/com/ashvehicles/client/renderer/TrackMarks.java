package com.ashvehicles.client.renderer;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * 地上車両の走行跡。クライアントだけの飾りで、同期も保存もしない。
 *
 * <p>車両が {@link #STEP} ブロック進むごとに、左右の接地点を真下への clip で地面に落とし、前回の点と結んだ
 * 四角を1枚ずつ溜める。四角は {@link #LIFE} tick かけて薄れて消え、総数は {@link #MAX} で頭打ち。
 * 船と、真下に地面の無い点（水・空中）は跡を残さない。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class TrackMarks {
    private static final double STEP = 0.6;
    private static final int LIFE = 20 * 60;
    private static final int MAX = 6000;
    /** 地面からの浮かせ。z-fighting を避けるだけの量。 */
    private static final double LIFT = 0.015;
    /** 車幅に対する左右の轍の中心位置と轍の幅。 */
    private static final double SPREAD = 0.36;
    private static final double TREAD = 0.2;
    private static final double RANGE_SQR = 160.0 * 160.0;

    private record Mark(Vec3 a0, Vec3 a1, Vec3 b0, Vec3 b1, long born) {
    }

    private record Last(Vec3 at, Vec3 left, Vec3 right) {
    }

    private static final ArrayDeque<Mark> MARKS = new ArrayDeque<>();
    private static final Map<Integer, Last> LAST = new HashMap<>();
    private static ClientLevel seenLevel;

    private TrackMarks() {
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;

        if (level != seenLevel) {
            MARKS.clear();
            LAST.clear();
            seenLevel = level;
        }

        if (level == null || minecraft.isPaused()) {
            return;
        }

        long now = level.getGameTime();

        while (!MARKS.isEmpty() && (now - MARKS.peekFirst().born() > LIFE || MARKS.size() > MAX)) {
            MARKS.pollFirst();
        }

        Map<Integer, Boolean> alive = new HashMap<>();

        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof GroundVehicleEntity vehicle) || vehicle.getStats().isShip()) {
                continue;
            }

            alive.put(vehicle.getId(), Boolean.TRUE);
            Vec3 at = vehicle.position();
            Last last = LAST.get(vehicle.getId());

            if (last != null && last.at().distanceToSqr(at) < STEP * STEP) {
                continue;
            }

            if (!vehicle.onGround() || vehicle.isInWater()) {
                LAST.remove(vehicle.getId());
                continue;
            }

            double yaw = Math.toRadians(vehicle.getYRot());
            Vec3 side = new Vec3(Math.cos(yaw), 0.0, Math.sin(yaw));
            double offset = vehicle.getBbWidth() * SPREAD;
            Vec3 left = ground(level, vehicle, at.add(side.scale(offset)));
            Vec3 right = ground(level, vehicle, at.subtract(side.scale(offset)));

            if (last != null && last.at().distanceToSqr(at) < 4.0 * STEP * STEP) {
                double half = Math.max(vehicle.getBbWidth() * TREAD * 0.5, 0.15);
                Vec3 across = side.scale(half);

                if (left != null && last.left() != null) {
                    MARKS.addLast(new Mark(last.left().add(across), last.left().subtract(across),
                            left.add(across), left.subtract(across), now));
                }

                if (right != null && last.right() != null) {
                    MARKS.addLast(new Mark(last.right().add(across), last.right().subtract(across),
                            right.add(across), right.subtract(across), now));
                }
            }

            LAST.put(vehicle.getId(), new Last(at, left, right));
        }

        LAST.keySet().removeIf(id -> !alive.containsKey(id));
    }

    /** 点の真下の地面。見つからなければ null（崖の縁や水の上）。 */
    private static Vec3 ground(ClientLevel level, Entity vehicle, Vec3 p) {
        BlockHitResult hit = level.clip(new ClipContext(p.add(0.0, 1.0, 0.0), p.add(0.0, -1.5, 0.0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, vehicle));

        if (hit.getType() != HitResult.Type.BLOCK || !level.getFluidState(hit.getBlockPos()).isEmpty()) {
            return null;
        }

        return hit.getLocation().add(0.0, LIFT, 0.0);
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();

        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || minecraft.level == null
                || MARKS.isEmpty()) {
            return;
        }

        Vec3 eye = event.getCamera().getPosition();
        Matrix4f matrix = event.getPoseStack().last().pose();
        long now = minecraft.level.getGameTime();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer buffer = buffers.getBuffer(RenderType.debugQuads());

        for (Iterator<Mark> it = MARKS.iterator(); it.hasNext();) {
            Mark mark = it.next();

            if (mark.a0().distanceToSqr(eye) > RANGE_SQR) {
                continue;
            }

            float fade = 1.0F - (float) (now - mark.born()) / LIFE;
            float alpha = 0.45F * Math.min(fade * 3.0F, 1.0F);

            if (alpha <= 0.0F) {
                continue;
            }

            vertex(matrix, buffer, mark.a0(), eye, alpha);
            vertex(matrix, buffer, mark.a1(), eye, alpha);
            vertex(matrix, buffer, mark.b1(), eye, alpha);
            vertex(matrix, buffer, mark.b0(), eye, alpha);
        }

        buffers.endBatch(RenderType.debugQuads());
    }

    private static void vertex(Matrix4f matrix, VertexConsumer buffer, Vec3 p, Vec3 eye, float alpha) {
        buffer.addVertex(matrix, (float) (p.x - eye.x), (float) (p.y - eye.y), (float) (p.z - eye.z))
                .setColor(0.16F, 0.13F, 0.10F, alpha);
    }
}
