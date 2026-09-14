package com.ashvehicles.client;

import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.network.AiDebugPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * 戦闘 AI の可視化の、クライアント側の半分。{@code /tdm ai debug} で開く。
 *
 * <ul>
 * <li><b>ワールドの中</b>——AI ごとの道（行動の色）、目標への線（赤）、行き先の箱、探した遮蔽・側面・撤退先の箱、
 *     脅威マップのマス（危険が濃いほど赤く）、頭上の2行（名前と役割、行動と持ち場と脅威）
 * <li><b>画面の左</b>——見ている AI の詳しい姿:
 * <pre>
 * AI #12 leopard_2a6 [TANK] rule_v1
 * Objective: RECAPTURE B
 * Action:    FLANK
 * Target:    T-80U #4
 * Threat: 0.72   Route Risk: 0.31   HP: 64%
 * </pre>
 * </ul>
 *
 * <p>描くのはサーバーが送ってきた写しだけで、判断には一切使わない。線は {@code MatchMarks} と同じ
 * {@code RenderType.lines()}。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class AiDebugView {
    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID,
            "ai_debug");

    /** この長さ（tick）届かなければ描かない。 */
    private static final int STALE = 40;

    /** 画面の中央からこの角の内にいる AI を「見ている」とする。 */
    private static final double FOCUS = Math.cos(Math.toRadians(8.0));

    /** 見ている AI がいないとき、この距離の内の一番近い AI を出す（ブロック）。 */
    private static final double NEAR = 32.0;

    private static final float TEXT_SCALE = 0.025F;

    @Nullable
    private static AiDebugPayload shown;

    private static long receivedAt;

    private AiDebugView() {
    }

    public static void accept(AiDebugPayload payload) {
        shown = payload.machines().isEmpty() && payload.cells().isEmpty() ? null : payload;
        receivedAt = now();
    }

    private static long now() {
        return Minecraft.getInstance().level == null ? 0L : Minecraft.getInstance().level.getGameTime();
    }

    @Nullable
    private static AiDebugPayload fresh() {
        return shown != null && now() - receivedAt <= STALE ? shown : null;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        AiDebugPayload payload = fresh();
        Minecraft minecraft = Minecraft.getInstance();

        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || payload == null
                || minecraft.level == null || minecraft.options.hideGui) {
            return;
        }

        Vec3 eye = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        PoseStack poseStack = event.getPoseStack();
        PoseStack.Pose pose = poseStack.last();

        for (AiDebugPayload.Cell cell : payload.cells()) {
            double size = payload.cellSize();
            double y = minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING, cell.x() + (int) (size / 2),
                    cell.z() + (int) (size / 2)) + 0.1;
            int colour = dangerColour(cell.danger(), cell.exposure());
            double x0 = cell.x() - eye.x + 0.5;
            double z0 = cell.z() - eye.z + 0.5;
            double x1 = cell.x() + size - eye.x - 0.5;
            double z1 = cell.z() + size - eye.z - 0.5;
            double yy = y - eye.y;

            segment(pose, lines, x0, yy, z0, x1, yy, z0, colour);
            segment(pose, lines, x1, yy, z0, x1, yy, z1, colour);
            segment(pose, lines, x1, yy, z1, x0, yy, z1, colour);
            segment(pose, lines, x0, yy, z1, x0, yy, z0, colour);
        }

        for (AiDebugPayload.Machine machine : payload.machines()) {
            Vec3 at = positionOf(machine);
            int colour = actionColour(machine.action());
            double lift = 0.4;
            Vec3 previous = at;

            for (Vec3 point : machine.route()) {
                double y = minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(point.x),
                        (int) Math.floor(point.z)) + lift;

                segment(pose, lines, previous.x - eye.x, previous.y + lift - eye.y, previous.z - eye.z,
                        point.x - eye.x, y - eye.y, point.z - eye.z, colour);
                previous = new Vec3(point.x, y - lift, point.z);
            }

            if (machine.destination() != null) {
                box(poseStack, lines, machine.destination(), eye, 0.6, colour);
            }

            if (machine.tactical() != null) {
                box(poseStack, lines, machine.tactical(), eye, 1.0, tacticalColour(machine.tacticalKind()));
            }

            Entity target = machine.targetId() < 0 ? null : minecraft.level.getEntity(machine.targetId());

            if (target != null) {
                Vec3 to = target.getBoundingBox().getCenter();

                segment(pose, lines, at.x - eye.x, at.y + 2.0 - eye.y, at.z - eye.z, to.x - eye.x, to.y - eye.y,
                        to.z - eye.z, 0xFFFF3030);
            }
        }

        buffers.endBatch(RenderType.lines());

        for (AiDebugPayload.Machine machine : payload.machines()) {
            Vec3 at = positionOf(machine);
            Entity entity = minecraft.level.getEntity(machine.entityId());
            double top = at.y + (entity == null ? 3.0 : entity.getBbHeight()) + 1.4;

            DebugRenderer.renderFloatingText(poseStack, buffers, machine.label() + " [" + machine.role() + "]",
                    at.x, top + 0.3, at.z, machine.colour(), TEXT_SCALE, true, 0.0F, true);
            DebugRenderer.renderFloatingText(poseStack, buffers, String.format("%s  %s  T:%.2f R:%.2f",
                    machine.action(), machine.objective(), machine.threat(), machine.routeRisk()),
                    at.x, top, at.z, actionColour(machine.action()), TEXT_SCALE, true, 0.0F, true);
        }

        buffers.endBatch();
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CHAT, LAYER, AiDebugView::draw);
    }

    private static void draw(GuiGraphics graphics, DeltaTracker delta) {
        AiDebugPayload payload = fresh();
        Minecraft minecraft = Minecraft.getInstance();

        if (payload == null || minecraft.player == null || minecraft.options.hideGui) {
            return;
        }

        AiDebugPayload.Machine focus = focused(payload.machines(), minecraft);

        if (focus == null) {
            return;
        }

        List<String> text = List.of(
                focus.label() + "  [" + focus.role() + "]  " + focus.version(),
                "Objective: " + focus.objective(),
                "Action:    " + focus.action(),
                "Target:    " + focus.target(),
                String.format("Threat: %.2f   Route Risk: %.2f   HP: %d%%", focus.threat(), focus.routeRisk(),
                        Math.round(focus.health() * 100.0F)));
        int x = 6;
        int y = graphics.guiHeight() / 2 - text.size() * 5;
        int width = 0;

        for (String line : text) {
            width = Math.max(width, minecraft.font.width(line));
        }

        graphics.fill(x - 3, y - 3, x + width + 3, y + text.size() * 10 + 1, 0x90000000);

        for (int at = 0; at < text.size(); at++) {
            graphics.drawString(minecraft.font, text.get(at), x, y + at * 10,
                    at == 0 ? focus.colour() : at == 2 ? actionColour(focus.action()) : 0xFFE8E8E8, true);
        }
    }

    /** 画面の中央に一番近い AI。いなければ近くの一番近い AI。 */
    @Nullable
    private static AiDebugPayload.Machine focused(List<AiDebugPayload.Machine> machines, Minecraft minecraft) {
        Vec3 eye = minecraft.player.getEyePosition();
        Vec3 look = minecraft.player.getViewVector(1.0F);
        AiDebugPayload.Machine best = null;
        double bestDot = FOCUS;
        AiDebugPayload.Machine nearest = null;
        double nearestDistance = NEAR * NEAR;

        for (AiDebugPayload.Machine machine : machines) {
            Vec3 at = positionOf(machine).add(0.0, 1.5, 0.0);
            Vec3 towards = at.subtract(eye);
            double distance = towards.lengthSqr();

            if (distance < 1.0E-4) {
                continue;
            }

            double dot = look.dot(towards.normalize());

            if (dot > bestDot) {
                bestDot = dot;
                best = machine;
            }

            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = machine;
            }
        }

        return best != null ? best : nearest;
    }

    private static Vec3 positionOf(AiDebugPayload.Machine machine) {
        Minecraft minecraft = Minecraft.getInstance();
        Entity entity = minecraft.level == null ? null : minecraft.level.getEntity(machine.entityId());

        return entity == null ? machine.at() : entity.position();
    }

    private static void box(PoseStack poseStack, VertexConsumer lines, Vec3 at, Vec3 eye, double half, int colour) {
        LevelRenderer.renderLineBox(poseStack, lines, at.x - half - eye.x, at.y - eye.y, at.z - half - eye.z,
                at.x + half - eye.x, at.y + half * 2.0 - eye.y, at.z + half - eye.z, red(colour), green(colour),
                blue(colour), 1.0F);
    }

    /** 行動の色。 */
    static int actionColour(String action) {
        return switch (action) {
            case "CAPTURE_OBJECTIVE", "RECAPTURE_OBJECTIVE" -> 0xFFFFD740;
            case "DEFEND_OBJECTIVE" -> 0xFF4FC3F7;
            case "ATTACK" -> 0xFFFF5252;
            case "FLANK" -> 0xFFFFAB40;
            case "SEEK_COVER" -> 0xFF69F0AE;
            case "RETREAT" -> 0xFFB388FF;
            case "SEARCH_ENEMY" -> 0xFFBDBDBD;
            case "SUPPORT_ALLY" -> 0xFF40C4FF;
            default -> 0xFFFFFFFF;
        };
    }

    /** 探した点の色。遮蔽は緑、射撃位置は黄緑、側面は橙、撤退先は紫。 */
    private static int tacticalColour(int kind) {
        return switch (kind) {
            case 0 -> 0xFF00E676;
            case 1 -> 0xFFC6FF00;
            case 2 -> 0xFFFF9100;
            case 3 -> 0xFFD500F9;
            default -> 0xFFFFFFFF;
        };
    }

    /** 危険は黄から赤へ、見通されているマスは明るく。 */
    private static int dangerColour(float danger, float exposure) {
        float level = Math.min(1.0F, Math.max(danger, exposure * 0.8F));
        int red = 255;
        int green = (int) (220 * (1.0F - level));
        int blue = exposure > 0.3F ? 80 : 0;

        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    /** 線1本。{@code RenderType.lines()} は法線を要求する（{@code MatchMarks.segment} と同じ）。 */
    private static void segment(PoseStack.Pose pose, VertexConsumer lines, double x1, double y1, double z1, double x2,
            double y2, double z2, int colour) {
        float nx = (float) (x2 - x1);
        float ny = (float) (y2 - y1);
        float nz = (float) (z2 - z1);
        float length = Math.max((float) Math.sqrt(nx * nx + ny * ny + nz * nz), 1.0E-5F);

        lines.addVertex(pose, (float) x1, (float) y1, (float) z1).setColor(colour)
                .setNormal(pose, nx / length, ny / length, nz / length);
        lines.addVertex(pose, (float) x2, (float) y2, (float) z2).setColor(colour)
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
