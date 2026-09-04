package com.ashvehicles.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.VehiclePart;
import com.ashvehicles.item.EquipmentItem;
import com.ashvehicles.item.RackItem;
import com.ashvehicles.item.WeaponItem;
import com.ashvehicles.registry.ModItems;
import com.ashvehicles.weapon.WeaponMounts;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * 手に持っている物が機体のどこに付くかを、その場所に枠で示す。
 *
 * <p><b>なぜ要るのか。</b>パイロンは主翼の下面にあり、機体の箱の内側にあり、そして何も吊っていなければ
 * 見えない。どこをクリックすれば良いかを知る手段が、当たり判定オーバーレイ（F3+B）を出して赤い箱を数える
 * ことしか無かった——それは開発用の表示であって、機体を武装させに来た人が使う物ではない。
 *
 * <p><b>持っている物によって答えが変わる。</b>だから枠は「パイロンの一覧」ではなく「この物の行き先」を
 * 示す。同じ主翼が、ラックを持っていれば緑の枠を4つ返し、ミサイルを持っていれば——まだラックが付いて
 * いなければ——琥珀の枠を4つ返す。琥珀は「ここに付くが、先にラックが要る」という意味で、これが分から
 * ないことこそ、この仕組みで一番詰まる場所だからだ。
 *
 * <p><b>見ている1つは明るく描く。</b>枠は重なって見えるし、翼下に並んだ4つは同じ形をしている。実際に
 * クリックが行く先は、機体が使うのと同じ規則——視線が最初に入ったパイロン——で選んで濃く描く。
 *
 * <p>{@link VehicleShapeRenderer} とは別物だ。あちらはファイルに書いた形が意図通りかを確かめる開発用の
 * 表示で、F3+B と一緒に出る。こちらは遊んでいる最中の案内で、物を持っている間だけ出る。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class MountPoints {
    /** 今そこに付く。 */
    private static final float[] FITS = {0.25F, 1.0F, 0.4F};
    /** ここに付く物ではあるが、先に付ける物がある——裸のパイロンへ差し出されたミサイル。 */
    private static final float[] NEEDS_RACK = {1.0F, 0.72F, 0.2F};
    /** ここには付かない。既に埋まっているか、種別が違うか、重量に余裕が無いか。 */
    private static final float[] TAKEN = {0.5F, 0.5F, 0.55F};

    /** 見ているパイロンと、それ以外の濃さ。 */
    private static final float AIMED_ALPHA = 1.0F;
    private static final float ALPHA = 0.45F;

    /**
     * これより遠い機体には描かない。
     *
     * <p>手が届く距離ではなく、機体1機がまるごと入る距離。爆撃機の反対側の翼に空きがあるかは、そちらへ
     * 歩き出す前に見えていた方がよい。
     */
    private static final double RANGE = 32.0;

    /** 一度に名前を挙げるラックの数。全部並べると読む前に行が画面から出る。 */
    private static final int MOST_NAMED = 3;

    private static final ResourceLocation HINT = ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID,
            "mount_hint");

    /**
     * 見ているパイロンが待っている物。無ければ null。
     *
     * <p>世界の描画で決めて画面の描画で出す。同じフレームの中で前者が必ず先に走るので、出る頃には
     * 今見ている物の答えになっている。両方で視線を辿り直すより、辿った側が答えを置いていく方が安い
     * ——そして2箇所の規則が食い違いようがない。
     */
    @Nullable
    private static Component hint;

    private MountPoints() {
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, HINT, MountPoints::drawHint);
    }

    /**
     * 琥珀の枠が何を待っているかを、照準の下に1行で。
     *
     * <p>枠の色は「ここには今は付かない」までしか言えない。何を先に付ければ緑になるのかは名前でしか
     * 言えないし、それこそが知りたいことだ。
     */
    private static void drawHint(GuiGraphics graphics, DeltaTracker delta) {
        Component line = hint;

        if (line == null || Minecraft.getInstance().options.hideGui) {
            return;
        }

        int x = (graphics.guiWidth() - Minecraft.getInstance().font.width(line)) / 2;

        graphics.drawString(Minecraft.getInstance().font, line, x, graphics.guiHeight() / 2 + 14,
                0xFFB84D, true);
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        hint = null;

        if (minecraft.level == null || player == null || minecraft.options.hideGui) {
            return;
        }

        ItemStack held = mountable(player);

        if (held.isEmpty()) {
            return;
        }

        Vec3 eye = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        PoseStack poseStack = event.getPoseStack();

        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (!(entity instanceof AircraftEntity aircraft) || aircraft.isWrecked()
                    || !entity.position().closerThan(eye, RANGE)) {
                continue;
            }

            draw(poseStack, lines, aircraft, held, player, eye);
        }

        buffers.endBatch(RenderType.lines());
    }

    /**
     * 機体1機分の枠。
     *
     * <p>枠はハードポイントの座標から組み立て直さず、実際にクリックを受ける部品の箱をそのまま描く。
     * 見えている枠とクリックが当たる場所が食い違わないための唯一の方法であり、パイロンの箱は吊る物の
     * 大きさで変わる（{@code AircraftEntity} 参照）ので、こちらで計算し直せばいつか必ずずれる。
     */
    private static void draw(PoseStack poseStack, VertexConsumer lines, AircraftEntity aircraft,
            ItemStack held, Player player, Vec3 eye) {
        VehiclePart aimed = inSight(aircraft, player);

        for (VehiclePart part : aircraft.getParts()) {
            if (!part.isPylon() || !part.isPickable()) {
                continue;
            }

            float[] colour = colourOf(aircraft, part.getPylon(), held);

            if (colour == null) {
                continue;
            }

            // 見ている1つが琥珀なら、何を待っているかを名前で言う。
            if (part == aimed && colour == NEEDS_RACK) {
                hint = rackHint(aircraft, part.getPylon(), held);
            }

            // 部品の箱は世界軸に沿っている。機体が駐機していれば、それは翼下の物の形そのものだ。
            AABB box = part.getBoundingBox().move(-eye.x, -eye.y, -eye.z);
            float alpha = part == aimed ? AIMED_ALPHA : ALPHA;

            LevelRenderer.renderLineBox(poseStack, lines, box.minX, box.minY, box.minZ,
                    box.maxX, box.maxY, box.maxZ, colour[0], colour[1], colour[2], alpha);
        }
    }

    /**
     * そのパイロンをどの色で描くか。描かないなら null。
     *
     * <p>判定は機体自身が使う物と同じ問い合わせを通す（{@code WeaponMounts.canFitRackAt} など）。別の
     * 規則で色を決めれば、緑の枠をクリックしても何も起きない日がいつか来る。
     */
    @Nullable
    private static float[] colourOf(AircraftEntity aircraft, int slot, ItemStack held) {
        WeaponMounts mounts = aircraft.getWeapons();
        AircraftDefinition.Hardpoint hardpoint = mounts.hardpoint(slot);

        if (hardpoint == null) {
            return null;
        }

        if (held.getItem() instanceof RackItem rack) {
            // ラックは weapon パイロンにしか付かない。ポッド専用のステーションに灰色の枠を出しても、
            // 「ここには来ない」ということしか言わない。
            return hardpoint.isWeaponPylon()
                    ? (mounts.canFitRackAt(slot, rack.getRackId()) ? FITS : TAKEN)
                    : null;
        }

        if (held.getItem() instanceof EquipmentItem pod) {
            return hardpoint.isSpecialPylon()
                    ? (mounts.canFitEquipmentAt(slot, pod.getEquipmentId()) ? FITS : TAKEN)
                    : null;
        }

        if (!(held.getItem() instanceof WeaponItem weapon) || !hardpoint.isWeaponPylon()) {
            return null;
        }

        if (mounts.canMountAt(slot, weapon.getWeaponId())) {
            return FITS;
        }

        // 裸のパイロンは「入らない」のではなく「まだ入らない」。ラックを1つ付ければ緑になる場所であり、
        // それを灰色で描くのは嘘に近い。
        return mounts.mounts().get(slot).hasRack() ? TAKEN : NEEDS_RACK;
    }

    /**
     * このパイロンにその兵装を吊るには、どのラックを先に付ければよいか。
     *
     * <p>名前を挙げるのは、実際にそこへ<em>付けられる</em>ラックだけだ。パイロンの重量に収まり、しかも
     * その兵装を積める物。「ラックが要る」とだけ言われた人が、積めないラックを取りに戻る道理は無い。
     *
     * <p>アイテムの表示名で挙げる。プレイヤーが工廠と持ち物で探すのはその名前であって、ファイルの ID
     * ではない。
     */
    private static Component rackHint(AircraftEntity aircraft, int slot, ItemStack held) {
        if (!(held.getItem() instanceof WeaponItem weapon)) {
            return Component.translatable("hud.ashvehicles.needs_rack");
        }

        WeaponMounts mounts = aircraft.getWeapons();
        List<Component> names = new ArrayList<>();

        for (Map.Entry<ResourceLocation, DeferredItem<RackItem>> entry : ModItems.racks().entrySet()) {
            if (names.size() >= MOST_NAMED) {
                break;
            }

            if (Definitions.rack(entry.getKey()).takes(Definitions.weapon(weapon.getWeaponId()))
                    && mounts.canFitRackAt(slot, entry.getKey())) {
                names.add(entry.getValue().get().getDescription());
            }
        }

        if (names.isEmpty()) {
            return Component.translatable("hud.ashvehicles.no_rack");
        }

        Component listed = names.get(0);

        for (int at = 1; at < names.size(); at++) {
            listed = Component.empty().append(listed).append(" / ").append(names.get(at));
        }

        return Component.translatable("hud.ashvehicles.needs_rack_named", listed);
    }

    /**
     * 今クリックすればどのパイロンへ行くか。機体が同じ判断に使う規則をそのまま辿る——視線が入った
     * パイロンのうち最も手前。
     */
    @Nullable
    private static VehiclePart inSight(AircraftEntity aircraft, Player player) {
        if (player.isSecondaryUseActive()) {
            return null;
        }

        Vec3 eye = player.getEyePosition();
        Vec3 reach = eye.add(player.getViewVector(1.0F).scale(player.entityInteractionRange()));
        VehiclePart nearest = null;
        double closest = Double.MAX_VALUE;

        for (VehiclePart part : aircraft.getParts()) {
            if (!part.isPylon() || !part.isPickable()) {
                continue;
            }

            Optional<Vec3> hit = part.clip(eye, reach, 0.0);

            if (hit.isEmpty()) {
                continue;
            }

            double distance = eye.distanceToSqr(hit.get());

            if (distance < closest) {
                closest = distance;
                nearest = part;
            }
        }

        return nearest;
    }

    /** 手にある、機体に付く物。両手を見て、無ければ空。 */
    private static ItemStack mountable(Player player) {
        for (ItemStack held : new ItemStack[] {player.getMainHandItem(), player.getOffhandItem()}) {
            if (held.getItem() instanceof WeaponItem || held.getItem() instanceof RackItem
                    || held.getItem() instanceof EquipmentItem) {
                return held;
            }
        }

        return ItemStack.EMPTY;
    }
}
