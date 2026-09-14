package com.ashvehicles.client;

import java.util.UUID;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.TargetDroneEntity;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.entity.VehiclePart;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * サーマル映像の中で、熱を持つ物を熱く描く。
 *
 * <p><b>なぜ要るのか。</b> {@link ThermalView} のセンサー像は画面の<em>色</em>から熱を推し量っている。
 * ポストエフェクトが読めるのはそれだけだからで、地形に対してはそれで十分うまくいく——木立は沈み、道と
 * 建物は浮く。だが探している物には効かない。緑の迷彩を着た戦車は森と同じ緑で、砂漠色の車両は砂と同じ
 * 色をしている。実際の赤外線センサーでそれらが光るのは、色ではなくエンジンと排気と人体が本当に熱いから
 * であり、色を見ている限りそこは決して再現されない。**狙う相手が最も明るく光る**という赤外線の一番の
 * 効能が、狙う相手にだけ効いていなかった。
 *
 * <p><b>やり方は2つあり、どちらも「赤く塗る」だけである。</b>熱に変えるのは今まで通りシェーダーの
 * 仕事で、こちらは色しか触らない。だから極性を黒熱へ切り替えても、強調した物だけが元の色で取り残される
 * ことが無い。
 *
 * <ol>
 *   <li><b>この MOD の機械はモデルごと赤で描く。</b>{@link #hot} が
 *       {@code VehicleRenderer.getRenderColor} と {@code GhostGeoRenderer.getRenderColor} の両方から
 *       問われ、真なら描画色が赤になる。描くのは今まで通りその機体のモデルなので、<b>熱がモデルの形を
 *       している</b>——主翼は主翼の形に、砲塔は砲塔の形に光る。追加の描画パスは無い。
 *   <li><b>生き物は当たり箱を塗る。</b>こちらはこのクラスが {@code AFTER_ENTITIES} で描く。人も牛も、
 *       砲手が見る距離では数ピクセルの熱源であり、人型かどうかを読める距離ではない。
 * </ol>
 *
 * <p><b>サーマルが掛かっている間だけ描く。</b> {@link ThermalView#isShowing} が偽——砲手席にいない、
 * あるいはシェーダーの読み込みに失敗した——なら1枚も描かず、色も変えない。失敗した側で塗ってしまうと、
 * 通常の視界に赤い機体と赤い箱が並ぶ。
 *
 * <p><b>何を熱いと見なすか。</b> 生き物と機械だ。ここで型の名簿を作らないのは意図的で、
 * 記憶ノート {@code targetable-entities-are-name-listed} が数えている「撃てる型の名簿」を6つ目に
 * 増やしたくないからでもあるが、それ以上に<b>それが正しいから</b>——赤外線センサーは敵味方も
 * 標的価値も知らない。温かい物が光る。牛が光るのは不具合ではなく、そう見えるのが本当だ。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class ThermalTargets {
    /**
     * 塗る色。赤。
     *
     * <p>白ではない。{@code thermal.fsh} の {@code heat()} は輝度を土台に、燃えている物の赤橙を
     * 大きく持ち上げる項を持っている（{@code fire})。白を塗ると輝度ぶんの熱にしかならず、明るい砂地と
     * 同じ高さで止まる。赤は {@code fire} の閾値を大きく越えるので上限まで振り切り、さらに滲み
     * （{@code Bloom}）の対象にもなる——遠くの車両が小さな滲みを伴った点として見えるのは、実際の
     * センサーがそう見える理由と同じだ。
     *
     * <p>この色が画面にそのまま出ることは無い。出るのはサーマルが掛かっている時だけで、その時この
     * 赤はシェーダーを通って白（黒熱なら黒）になる。
     */
    private static final float RED = 1.0F;
    private static final float GREEN = 0.0F;
    private static final float BLUE = 0.0F;
    /**
     * 塗りの不透明度。
     *
     * <p>1.0 にしない。塗り潰すと、その物は輪郭を持つだけの均一な塊になり、砲手には大きさしか分からなく
     * なる。0.85 なら下の絵が少し透けるので、車体と砲塔、機首と主翼が滲みの中に残る。実際の
     * センサー像でも熱源は一様な白板ではなく、熱い所ほど白い塊として見える。
     */
    private static final float ALPHA = 0.85F;

    /**
     * これより遠い物は塗らない（ブロック）。
     *
     * <p>ガンシップが撃つのは1〜2km 先なので、当たり判定を描く {@link VehicleShapeRenderer} の 96 では
     * まるで足りない。2048 は照準ポッドの到達距離と同じ数字で、そこまでは「見えているのに光らない」が
     * 起きない。クライアントが持っていない実体はそもそもここへ来ないので、上限は代金ではなく歯止めだ。
     */
    private static final double RANGE = 2048.0;

    private ThermalTargets() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES
                || !ThermalView.isShowing()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null) {
            return;
        }

        // 自分が乗っている物は塗らない。砲手席から見て最も近く最も大きい熱源が自機の胴体では、画面の
        // 半分が白い塊になる。実機の窓に自分の機体が映らないのと同じ理由で、ここでも映らないのが正しい。
        Entity camera = minecraft.getCameraEntity();
        Entity own = camera == null ? null : camera.getRootVehicle();

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 eye = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer filled = buffers.getBuffer(RenderType.debugFilledBox());
        PoseStack poseStack = event.getPoseStack();

        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (!warm(entity) || entity == own || entity.getRootVehicle() == own
                    || !entity.position().closerThan(eye, RANGE)) {
                continue;
            }

            paint(poseStack, filled, entity, eye, partialTick);
        }

        buffers.endBatch(RenderType.debugFilledBox());
    }

    /**
     * その実体が熱を持つか。<b>ここで塗るのは箱で描く物だけ</b>——生き物だ。
     *
     * <p>この MOD の機械はここに現れない。あちらは自分のモデルで熱く描かれる（{@link #hot} と
     * {@code VehicleRenderer.getRenderColor}）ので、箱で塗れば同じ物を2度塗ることになり、しかも
     * 悪い方——大きさしか分からない塊——が上に乗る。
     *
     * <p>生き物に箱を使うのはそれで足りるからだ。人も牛も、砲手が見る距離では数ピクセルの熱源であり、
     * その形が人型かどうかを読める距離ではない。
     */
    private static boolean warm(Entity entity) {
        if (entity instanceof VehiclePart || entity instanceof VehicleEntityBase) {
            return false;
        }

        // 標的ドローンはここに残す。生き物でもこの MOD の機械でもないので、上の色差し替えが届かない
        // ——あちらのレンダラーは {@code VehicleRenderer} を継いでいない——のに、ロックされるために
        // 存在する物である。両方の経路から漏れて1つも光らない、が起きうる唯一の型だ。
        return entity instanceof LivingEntity || entity instanceof TargetDroneEntity;
    }

    /**
     * その機械を今このフレーム、熱い色で描くべきか。{@code VehicleRenderer.getRenderColor} が問う。
     *
     * <p>サーマルが掛かっている間だけ、かつ自分が乗っている物を除く。砲手席から見て最も近く最も大きい
     * 熱源が自機の胴体では、画面の半分が白い塊になる。
     */
    public static boolean hot(Entity machine) {
        Entity own = own();

        return ThermalView.isShowing() && machine != own && machine.getRootVehicle() != own;
    }

    /**
     * 同じ判定を、実体ではなくゴーストに対して。
     *
     * <p>ゴーストは実体ではなく記録なので（{@code EntityGhost}）、同一性は UUID で見る。エンティティ ID は
     * 使い回されるが UUID は使い回されない、というのはゴースト側が既に採っている前提と同じだ。
     */
    public static boolean hot(UUID ghost) {
        Entity own = own();

        return ThermalView.isShowing() && (own == null || !own.getUUID().equals(ghost));
    }

    /** この画面の持ち主が乗っている物。乗っていなければ null。 */
    @Nullable
    private static Entity own() {
        Entity camera = Minecraft.getInstance().getCameraEntity();

        return camera == null ? null : camera.getRootVehicle();
    }

    /** 形を持たない物は、その当たり箱で塗る。人も牛も、遠くから見ればそれで足りる。 */
    private static void paint(PoseStack poseStack, VertexConsumer filled, Entity entity,
            Vec3 eye, float partialTick) {
        AABB box = entity.getBoundingBox().move(
                entity.getPosition(partialTick).subtract(entity.position()));

        LevelRenderer.addChainedFilledBoxVertices(poseStack, filled,
                box.minX - eye.x, box.minY - eye.y, box.minZ - eye.z,
                box.maxX - eye.x, box.maxY - eye.y, box.maxZ - eye.z,
                RED, GREEN, BLUE, ALPHA);
    }

}
