package com.ashvehicles.client.ghost;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.ghost.dh.DHIntegration;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;

/**
 * 開発補助。F3 画面への数行と、各ゴーストを囲む赤枠。いずれも {@link GhostConfig} の個別設定で切り替える。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class GhostDebug {
    /** オーバーレイ表示中、その数値をログにも出す間隔（tick）。 */
    private static final int LOG_INTERVAL = 200;
    /** 1回のログ出力で個別に列挙するゴーストの上限数。 */
    private static final int LOG_DETAIL_LIMIT = 8;

    /** 各ゴーストの前回報告時の判定結果。変化した分だけをログに出すため。 */
    private static final Map<UUID, GhostVerdict> REPORTED = new HashMap<>();

    private GhostDebug() {
    }

    /** 同じ数値を時折ログへ。後からセッションを読み返すため。 */
    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();

        if (!GhostConfig.debugOverlay() || minecraft.level == null) {
            if (!REPORTED.isEmpty()) {
                REPORTED.clear();
            }

            return;
        }

        reportChanges();

        if (minecraft.level.getGameTime() % LOG_INTERVAL != 0) {
            return;
        }

        AshVehicles.LOGGER.info("[ghosts] tracked={} drawn={} culled={} GHOST={} BILLBOARD={} "
                + "occluded={} orphaned={} far={} dh={} ({}) dhRadius={}",
                EntityGhostManager.size(), GhostRenderDispatcher.drawnLastFrame(),
                GhostRenderDispatcher.culledLastFrame(),
                EntityGhostManager.countGhost(),
                EntityGhostManager.countBillboard(), EntityGhostManager.countOccluded(),
                EntityGhostManager.countOrphaned(), (int) GhostRenderDispatcher.farPlaneLastFrame(),
                DHIntegration.status(), DHIntegration.detail(minecraft.level), DHIntegration.drawnRadius());

        int listed = 0;

        for (EntityGhost ghost : EntityGhostManager.ghosts()) {
            if (listed++ >= LOG_DETAIL_LIMIT) {
                break;
            }

            AshVehicles.LOGGER.info("[ghost] {} {} at {} distance={} lod={} why={} occluded={} orphaned={} "
                    + "inWorld={} light=sky{}/block{}",
                    ghost.uuid().toString().substring(0, 8), ghost.current().type().toShortString(),
                    ghost.current().position(), (int) Math.sqrt(ghost.distanceSq()), ghost.lod(),
                    ghost.verdict(), ghost.isOccluded(), ghost.isOrphaned(),
                    ghost.wasInWorld(), LightTexture.sky(ghost.lastLight()), LightTexture.block(ghost.lastLight()));
        }
    }

    /**
     * ゴーストが描かれ始めた／描かれなくなった瞬間に、その理由を1行出す。
     *
     * <p>上の定期ダンプは10秒ごとにたまたま真である物を拾うだけで、「さっきは居たのに今は居ない」にはまるで
     * 役に立たない。次のダンプが来る頃には機体もプレイヤーも動いている。こちらは時系列だ——変化1つにつき1行、
     * 何も変わらない間は何も出さない。
     */
    private static void reportChanges() {
        Set<UUID> present = new HashSet<>();

        for (EntityGhost ghost : EntityGhostManager.ghosts()) {
            present.add(ghost.uuid());

            GhostVerdict now = ghost.verdict();
            GhostVerdict before = REPORTED.put(ghost.uuid(), now);

            if (before != now) {
                AshVehicles.LOGGER.info("[ghost] {} {} at {} blocks, {}: {} -> {}",
                        ghost.uuid().toString().substring(0, 8), ghost.current().type().toShortString(),
                        (int) Math.sqrt(ghost.distanceSq()), ghost.lod(),
                        before == null ? "new" : before, now);
            }
        }

        REPORTED.keySet().retainAll(present);
    }

    @SubscribeEvent
    static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        if (!GhostConfig.debugOverlay() || Minecraft.getInstance().level == null) {
            return;
        }

        List<String> left = event.getLeft();
        left.add("");
        left.add(String.format("[AshVehicles ghosts] %d tracked, %d drawn, %d culled",
                EntityGhostManager.size(), GhostRenderDispatcher.drawnLastFrame(),
                GhostRenderDispatcher.culledLastFrame()));
        left.add(String.format("GHOST: %d  BILLBOARD: %d  occluded: %d  orphaned: %d",
                EntityGhostManager.countGhost(), EntityGhostManager.countBillboard(),
                EntityGhostManager.countOccluded(), EntityGhostManager.countOrphaned()));
        left.add(String.format("Distances: %.0f / %.0f  far plane %.0f  ghost style beyond %.0f",
                Math.sqrt(GhostConfig.startSq()), Math.sqrt(GhostConfig.endSq()),
                GhostRenderDispatcher.farPlaneLastFrame(), GhostRenderDispatcher.ghostStyleRadius()));
        left.add(String.format("DH Integration: %s (%s)  drawn radius: %.0f",
                DHIntegration.status(), DHIntegration.detail(Minecraft.getInstance().level),
                DHIntegration.drawnRadius()));
    }

    /**
     * 描かれたゴーストを囲む赤枠。ゴーストの位置に合わせて描く。ゴーストパスから、その pose stack を使って、
     * ゴースト本体の後に呼ばれる。
     */
    static void drawBoxes(List<EntityGhost> ghosts, Vec3 eye, float partialTick, PoseStack poseStack,
            MultiBufferSource.BufferSource buffers, double farPlane) {
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        for (EntityGhost ghost : ghosts) {
            if (ghost.lod() == GhostLOD.HIDDEN) {
                continue;
            }

            Vec3 position = ghost.position(partialTick);
            double away = position.distanceTo(eye);
            double pull = GhostRenderDispatcher.pull(away, farPlane);
            AABB bounds = ghost.current().bounds();
            Vec3 drawnAt = eye.add(position.subtract(eye).scale(pull));
            AABB box = new AABB(
                    bounds.minX * pull, bounds.minY * pull, bounds.minZ * pull,
                    bounds.maxX * pull, bounds.maxY * pull, bounds.maxZ * pull).move(drawnAt.subtract(eye));

            // 描かれたゴーストは赤、遮蔽された物は琥珀、何も描かなかった物は青。
            float green = ghost.isOccluded() ? 0.6F : 0.0F;
            float blue = ghost.verdict() == GhostVerdict.DRAWN || ghost.isOccluded() ? 0.0F : 1.0F;
            LevelRenderer.renderLineBox(poseStack, lines, box, 1.0F, green, blue, 1.0F);
        }

        buffers.endBatch(RenderType.lines());
    }
}
