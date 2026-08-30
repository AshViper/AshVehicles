package com.ashvehicles.client.ghost;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.ghost.dh.DHIntegration;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;

import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * ゴースト描画パス。ゲームのエンティティループ直後に毎フレーム1回走る自前のパスで、tickが「描くべき」と決めた
 * 全ゴーストを描く。
 *
 * <h2>どこで走るか</h2>
 *
 * <p>{@link RenderLevelStageEvent.Stage#AFTER_PARTICLES} であり、この選択には理由がある。その時点でゲームは地形と
 * エンティティを描き終えているので、深度バッファにはゲームが知る「ゴーストが隠れるべき物」が全て入っている。Distant
 * Horizons はずっと前（solid レイヤーの番）に自分の地形を描いているので、ゴーストはその地形の上に合成され、DH が残さ
 * なかった深度の代役は {@link GhostOcclusion} が務める。そして——これが {@code AFTER_ENTITIES} でない理由だ——DH の
 * <em>バニラフェード</em>も既に走り終えている。あれは translucent と tripwire レイヤーの先頭で、ゲームが自分の
 * フェード距離より遠くに描いた全ピクセルを、自前の地形がある所では自前の地形で塗り直す（{@code fade/gl/vanilla_fade.frag}）。
 * そのパスより前に描かれたゴーストは描かれてから塗り潰され、背後が空の場所でしか生き残らない。後に描けば残る。イベント
 * が渡す pose stack はエンティティループ自身の物——単位行列で、カメラの回転はまだモデルビュースタックに載っている——
 * なので、ワールド軸の平行移動はゲームのループとまったく同じに働く。
 *
 * <p>このステージには1つ注意点がある。<em>描画優先（Fabulous）</em>ではゲームがパーティクル用フレームバッファを
 * バインドした状態でディスパッチするので、ゴーストはメインターゲットへ直接描かれるのではなくパーティクル層と合成される。
 * あの層はメイン深度バッファの複製を持つので、隠れる相手も着地位置も変わらない。違うのはブレンドだけだ。処理優先・
 * 均衡では——透過チェーンが存在しない——メインターゲットがバインドされ、ゴーストはそこへ直接入る。
 *
 * <p>以上はゲーム自身のパイプラインについての話で、Iris シェーダーパックの下では1つも当てはまらない。パックはフレーム
 * の組み立てを丸ごと置き換える。各ステージが描いた物はパック自身のバッファへ入りパック自身の合成パスを通るので、
 * {@code AFTER_PARTICLES} で渡したジオメトリ——このパス全体を含む——は、フレームが画面へ届く前に単に塗り潰される。よって
 * パック使用中はパスを {@link RenderLevelStageEvent.Stage#AFTER_LEVEL} へ移す。あちらはフレーム組み立て後にディス
 * パッチされる。ゴーストは完成した画像へ直接描かれ、まさにこの種の遅延描画のためにパイプラインが残すシーン深度に対して
 * 深度テストされる。このステージでは2つが異なり、それぞれ生じる場所で対処している。イベントは pose stack を運ばないし、
 * あの場所では RenderSystem の行列を一切信用できない——フレームの組み立てが残した物が残っているだけだ——ので、パスは
 * レベル自身の投影と自前の pose stack を立て、どちらも見つけた状態に戻す。そしてレベルの霧は既に解除済みなので、近距離
 * フェーズは霧無しで描く——パック自身の空を背にした、より小さな嘘だ。パックが使用中かは Iris の API へリフレクションで
 * 問うので、Iris が無くてもコストも意味も無い。
 *
 * <h2>誰が何を描くか</h2>
 *
 * <p>ゴーストは各自のアダプタがスナップショットから、近い順に {@link GhostConfig#maxGhosts()} まで描く。ゲーム自身の
 * レンダラーを {@link EntityRenderDispatcher} 経由で使うのは1つの場合だけだ。{@link GhostLOD#FULL} 階層にありながら、
 * ゲーム自身のループが自前の判定で描画を拒否したゴースト——クライアントが構築済みチャンクセクションを持たない場所に立って
 * いるか、投影の遠方面より遠いか。その判定はゲーム自身の物なので二重描画は起きないし、{@code ghostStartDistance} より
 * 遠くではゲームのレンダラーが {@link #claims} を問うて降板する。
 *
 * <h2>遠方面</h2>
 *
 * <p>投影は {@code 描画距離 × 64} ブロックでクリップし、ここでそれを動かすことはしない。代わりに、それより遠いゴースト
 * を同じ比率で手前かつ小さく描く。視点からの線に沿って滑らせ、動かしたのとちょうど同じだけ縮めるので、同じ位置の同じ
 * ピクセルを覆う。この写像はゴーストの順序を保つ——遠いゴーストは今も遠くに描かれる——し、引き寄せられたゴーストも全て
 * ロード範囲の外に留まるので、ゲームの地形に対する深度は影響を受けない。{@link #pull} 参照。
 *
 * <h2>霧と光</h2>
 *
 * <p>このパスは2フェーズで描く。クライアントが構築した世界の中に立てるほど近いゴーストと、その縁の外にいるゴーストは
 * 別物だからだ。
 *
 * <p><b>構築済み世界の内側</b>——引き継ぎが {@code ghostStartDistance} にある以上、プレイヤーの描画距離がそこを超えて
 * いれば {@link GhostLOD#GHOST} 階層の大半がこれにあたる——ではクライアントが光を知っており霧も適用されるので、ゴースト
 * はその位置の実光量で描き、霧はそのままにする。そうすればゲーム自身のループが描いた物と何も変わらない。それが要点だ。
 * 引き継ぎを手前へ動かしたせいで、150ブロック先の機体が暗闇で光ってはならない。
 *
 * <p><b>その外側</b>では光レベルが「不明」ではなく0を返すので、それで照らしたゴーストは黒い染みになる。しかも霧は
 * モデルではなくシェーダーの性質なので、何かを霧から外す唯一の方法は霧を動かすことだ。それらのゴーストは最大輝度で、
 * 霧の面を押し広げた状態で描き、まだ動かしているうちにバッチをフラッシュする。さもないと変更が次に描かれる物へ乗って
 * しまう。第1フェーズを霧を動かす前にフラッシュするのも同じ理由だ。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class GhostRenderDispatcher {
    /** 引き寄せたゴーストのうち最も近い物を、遠方面のどれだけ内側に置くか。 */
    private static final double PULL_MARGIN = 0.85;
    /** 最も遠くなりうるゴーストを、それに対する割合でさらにどれだけ先へ置くか。順序を保つための値。 */
    private static final double PULL_SPREAD = 0.15;

    /** 近い順。毎フレーム必要で内容も変わらないので、都度作らず保持する。 */
    private static final Comparator<EntityGhost> NEAREST_FIRST =
            Comparator.comparingDouble(EntityGhost::distanceSq);

    /**
     * 2つの作業リスト。毎回作らずフレーム間で保持する。
     *
     * <p>このパスは毎フレーム1回、レンダースレッドでしか走らないので共有相手はいない。しかも毎フレーム数百のゴースト
     * では、このシステム全体が生むゴミはこの2つのリストだけだった。どちらもパスの先頭で空にし、フレーム間では何も保持
     * しない。
     */
    private static final List<EntityGhost> GATHERED = new ArrayList<>();
    private static final List<Draw> DRAWS = new ArrayList<>();

    private static int drawnLastFrame;
    private static int culledLastFrame;
    private static double farPlaneLastFrame;

    private GhostRenderDispatcher() {
    }

    // ------------------------------------------------------------------
    // 引き継ぎ
    // ------------------------------------------------------------------

    /**
     * このフレーム、このエンティティを描くのがゲームのエンティティループではなくゴーストパスか。ゲーム自身の
     * レンダラーが {@code shouldRender} から問う。
     *
     * <p>エンティティのtick位置から測る。スナップショットが保持するのも同じ位置なので、レンダラーとパスは同じフレーム
     * で同じ答えに達する。
     *
     * <p>問うのは<em>ゴーストの存在</em>であって「ゴーストを持つ型かどうか」ではない。両者はほぼ常に同じ物だが、そう
     * でない場合こそ、この引き継ぎが決して起こしてはならない唯一の失敗——丸見えの場所に立つ機体を誰も描かない——にあたる。
     * マネージャは参加イベントからしかエンティティを知らずレベルを走査しないので、ゴーストを持たない機体は生涯持たない
     * ままだし、そこでゲームのレンダラーを降板させれば130ブロック先で見えなくなる。ゴースト自体を問い合わせるコストは
     * UUID 検索1回で、それを間違えることはありえない。
     */
    public static boolean claims(Entity entity, double camX, double camY, double camZ) {
        if (!GhostConfig.enabled()) {
            return false;
        }

        if (EntityGhostManager.ghostOf(entity) == null) {
            return false;
        }

        return entity.position().distanceToSqr(camX, camY, camZ) >= GhostConfig.startSq();
    }

    // ------------------------------------------------------------------
    // パス本体
    // ------------------------------------------------------------------

    @SubscribeEvent
    static void onRenderLevel(RenderLevelStageEvent event) {
        // 通常は AFTER_PARTICLES。Iris シェーダーパックがフレームを持っている間は AFTER_LEVEL。クラスの注記参照。
        if (event.getStage() != activeStage()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;

        if (level == null || minecraft.isPaused() || !GhostConfig.enabled()) {
            return;
        }

        // AFTER_LEVEL は pose stack 無しで、レベルのモデルビューが pop された後にディスパッチされる——そして、
        // 噛み付いてきたのはこの部分だ——フレームの組み立てがたまたま RenderSystem へ残していった行列が何であれ、
        // それが載った状態で来る。Iris パックの下ではそれはレベルの投影ではなく合成器の残りかすだ。パスは走り、判定は
        // DRAWN と言い、そして全頂点が画面のどこにも着地しなかった。バニラもこの時点では何も信用していない——この
        // イベントの次にやることは、手を描く前に投影をリセットすることだ——のでこのパスも信用しない。レベル自身の投影と
        // 単位行列のモデルビューを立て、新しい pose stack の底へカメラの回転を置き（それで全頂点が AFTER_PARTICLES
        // 経路と正確に同じ位置へ着地する）、両方の行列を見つけた状態に戻す。
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            Matrix4f projection = RenderSystem.getProjectionMatrix();
            VertexSorting sorting = RenderSystem.getVertexSorting();
            Matrix4fStack modelView = RenderSystem.getModelViewStack();

            RenderSystem.setProjectionMatrix(event.getProjectionMatrix(), VertexSorting.DISTANCE_TO_ORIGIN);
            modelView.pushMatrix();
            modelView.identity();
            RenderSystem.applyModelViewMatrix();

            PoseStack own = new PoseStack();
            own.mulPose(event.getModelViewMatrix());

            try {
                pass(event, minecraft, own);
            } finally {
                modelView.popMatrix();
                RenderSystem.applyModelViewMatrix();
                RenderSystem.setProjectionMatrix(projection, sorting);
            }

            return;
        }

        pass(event, minecraft, event.getPoseStack());
    }

    /** パス本体。行列は呼び出し元が既に整えてある。 */
    private static void pass(RenderLevelStageEvent event, Minecraft minecraft, PoseStack poseStack) {
        Camera camera = event.getCamera();
        Vec3 eye = camera.getPosition();
        Frustum frustum = event.getFrustum();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        double farPlane = minecraft.gameRenderer.getDepthFar();
        double ghostBeyond = ghostStyleRadius();

        // 先に集める。霧を動かすのは、それで描く物が実際にある場合だけにするためだ。
        List<EntityGhost> ghosts = GATHERED;
        ghosts.clear();
        ghosts.addAll(EntityGhostManager.ghosts());
        ghosts.sort(NEAREST_FIRST);

        if (ghosts.isEmpty()) {
            drawnLastFrame = culledLastFrame = 0;
            return;
        }

        int budget = GhostConfig.maxGhosts();
        int culled = 0;

        // 先に算出して後で描く。2つのフェーズは霧の設定が異なるし、位置が分かるまでどのフェーズに振り分けるか決められ
        // ないからだ。
        List<Draw> draws = DRAWS;
        draws.clear();

        for (EntityGhost ghost : ghosts) {
            if (draws.size() >= budget) {
                ghost.record(ghost.lod(), ghost.distanceSq(), GhostVerdict.BUDGET);
                continue;
            }

            // 階層はtick位置から決める。ゲームのレンダラーが claims() で測るのもそれだ。描画自体は補間後の位置で行う。
            double distanceSq = ghost.current().position().distanceToSqr(eye);
            GhostLOD lod = GhostLOD.of(distanceSq);
            Vec3 position = ghost.position(partialTick);
            GhostVerdict verdict;

            if (lod == GhostLOD.FULL) {
                // ゲームの階層。ゲーム自身のループが拒否しない限りゲームがエンティティを描く。拒否した場合はここで
                // ゲーム自身のレンダラーを使って描く。
                Entity entity = ghost.entity();

                if (entity == null || gameDraws(entity, minecraft, eye, farPlane)) {
                    verdict = GhostVerdict.GAME;
                } else if (ghost.isOccluded()) {
                    verdict = GhostVerdict.OCCLUDED;
                } else {
                    draws.add(Draw.ofEntity(entity, minecraft, distanceSq, ghostBeyond, partialTick, dispatcher));
                    verdict = GhostVerdict.DRAWN;
                }
            } else if (lod.isGhost()) {
                GhostSnapshot snapshot = ghost.current();
                double pull = pull(Math.sqrt(distanceSq), farPlane);

                // 構築済み世界の内側では深度バッファが遮蔽を決着させる——ピクセル単位で、実際に遮っている地面に
                // よって——し、線は一切トレースしない（GhostOcclusion 参照）。フラグは機体が世界の外にいた頃の答えを
                // まだ保持しうるし、再問い合わせは数tickごとだ。ここでそれを鵜呑みにすると、ロード範囲の縁を越えて
                // 入ってくる機体がその時間だけ消えてしまう。
                if (ghost.isOccluded() && !isBuilt(BlockPos.containing(position))) {
                    verdict = GhostVerdict.OCCLUDED;
                } else if (!inView(frustum, snapshot, position, eye, pull)) {
                    culled++;
                    verdict = GhostVerdict.CULLED;
                } else {
                    draws.add(Draw.ofGhost(ghost, lod, position, minecraft, distanceSq, ghostBeyond, pull));
                    verdict = GhostVerdict.DRAWN;
                }
            } else {
                verdict = GhostVerdict.HIDDEN;
            }

            ghost.record(lod, distanceSq, verdict);
        }

        int drawn = draws.size();

        // 第1フェーズ。クライアントが構築した世界の中に立つ全てを、その世界自身の光と霧で描く。ゲームが描いたのと
        // 正確に同じになるようにするためだ。霧に触れる前にフラッシュする。
        for (Draw draw : draws) {
            if (draw.inWorld()) {
                draw.render(eye, partialTick, poseStack, buffers, camera, dispatcher, farPlane);
            }
        }

        buffers.endBatch();

        // 第2フェーズ。その外の全てを、何にも照らされず霧からも外して描く。
        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);

        try {
            for (Draw draw : draws) {
                if (!draw.inWorld()) {
                    draw.render(eye, partialTick, poseStack, buffers, camera, dispatcher, farPlane);
                }
            }

            // 霧を動かしたままの状態で行う。さもないと、この時点以降に描かれるバッチ済みジオメトリが「霧から出てきた
            // ジオメトリ」になってしまい、それは誤ったジオメトリだ。
            buffers.endBatch();
        } finally {
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
        }

        drawnLastFrame = drawn;
        culledLastFrame = culled;
        farPlaneLastFrame = farPlane;

        if (GhostConfig.debugBoxes()) {
            GhostDebug.drawBoxes(ghosts, eye, partialTick, poseStack, buffers, farPlane);
        }

        // フレーム間で何も保持しない。ここで生かし続けたゴーストは、そのエンティティより長生きしてしまう。
        draws.clear();
        ghosts.clear();
    }

    // ------------------------------------------------------------------
    // 描画対象1つ
    // ------------------------------------------------------------------

    /**
     * 算出済みで順番待ちのゴースト1つ、またはエンティティ1つ。どのフェーズに属し、どの光を取り、どれだけ引き寄せるべきか。
     *
     * @param ghost アダプタがスナップショットから描く場合のゴースト
     * @param entity ゲーム自身のレンダラーが描く場合のエンティティ
     * @param inWorld クライアントが構築済みチャンクセクションを持つ場所に立っているか。つまり世界の光と霧がこれに対して
     *        意味を持つか
     */
    private record Draw(@Nullable EntityGhost ghost, @Nullable Entity entity, GhostLOD lod,
            @Nullable Vec3 position, double distanceSq, double pull, int light, boolean ghostStyle,
            boolean inWorld) {

        static Draw ofGhost(EntityGhost ghost, GhostLOD lod, Vec3 position, Minecraft minecraft,
                double distanceSq, double ghostBeyond, double pull) {
            boolean inWorld = isBuilt(BlockPos.containing(position));
            // 立っている場所の光で照らす。ただし立つ場所がある場合に限る。構築済み世界の外では、レベルは「分からない」
            // ではなく「光は無い」と答えるからだ。
            int light = inWorld
                    ? LevelRenderer.getLightColor(minecraft.level, BlockPos.containing(ghost.current()
                            .centre().subtract(ghost.current().position()).add(position)))
                    : LightTexture.FULL_BRIGHT;

            ghost.recordLight(light, inWorld);

            return new Draw(ghost, null, lod, position, distanceSq, pull, light,
                    distanceSq >= ghostBeyond * ghostBeyond, inWorld);
        }

        static Draw ofEntity(Entity entity, Minecraft minecraft, double distanceSq, double ghostBeyond,
                float partialTick, EntityRenderDispatcher dispatcher) {
            boolean inWorld = isBuilt(entity.blockPosition());
            // レンダラー自身の答え。ゲームのループが使ったであろう値だ。
            int light = inWorld
                    ? dispatcher.getPackedLightCoords(entity, partialTick)
                    : LightTexture.FULL_BRIGHT;

            return new Draw(null, entity, GhostLOD.FULL, null, distanceSq, 0.0, light,
                    distanceSq >= ghostBeyond * ghostBeyond, inWorld);
        }

        void render(Vec3 eye, float partialTick, PoseStack poseStack, MultiBufferSource buffers,
                Camera camera, EntityRenderDispatcher dispatcher, double farPlane) {
            if (this.entity != null) {
                drawWithGameRenderer(this.entity, eye, partialTick, poseStack, buffers, dispatcher,
                        farPlane, this.ghostStyle, this.light);
                return;
            }

            EntityGhost drawing = this.ghost;
            Vec3 to = this.position.subtract(eye);
            GhostRenderContext context = new GhostRenderContext(poseStack, buffers, camera, to, partialTick,
                    this.light, this.ghostStyle, this.distanceSq);

            poseStack.pushPose();
            poseStack.scale((float) this.pull, (float) this.pull, (float) this.pull);
            poseStack.translate(to.x, to.y, to.z);
            GhostRenderContext.enter(this.ghostStyle);

            try {
                drawing.adapter().render(drawing, this.lod, context);
            } finally {
                GhostRenderContext.exit();
                poseStack.popPose();
            }
        }
    }

    // ------------------------------------------------------------------
    // ゲーム自身のレンダラーで描く
    // ------------------------------------------------------------------

    /**
     * 実体を、登録済みレンダラーで描く。必要なら遠方面へ向けて引き寄せる。スケールをオフセットより前に掛けるのが引き寄せ
     * を機能させる鍵だ。レンダラーが行う全て——エンティティへのオフセットも含め——がその内側で起きる。
     */
    private static void drawWithGameRenderer(Entity entity, Vec3 eye, float partialTick, PoseStack poseStack,
            MultiBufferSource buffers, EntityRenderDispatcher dispatcher, double farPlane, boolean ghostStyle,
            int light) {
        Vec3 to = new Vec3(
                Mth.lerp(partialTick, entity.xOld, entity.getX()),
                Mth.lerp(partialTick, entity.yOld, entity.getY()),
                Mth.lerp(partialTick, entity.zOld, entity.getZ())).subtract(eye);
        double away = to.length();

        if (away < 1.0E-4) {
            return;
        }

        float pull = (float) pull(away, farPlane);
        float yaw = Mth.lerp(partialTick, entity.yRotO, entity.getYRot());

        poseStack.pushPose();
        poseStack.scale(pull, pull, pull);
        GhostRenderContext.enter(ghostStyle);

        try {
            dispatcher.render(entity, to.x, to.y, to.z, yaw, partialTick, poseStack, buffers, light);
        } finally {
            GhostRenderContext.exit();
            poseStack.popPose();
        }
    }

    /**
     * クライアントがその地点で世界を構築済みか。つまりこのフレーム、その周りにゲームが地形を描いたか。
     *
     * <p>別々に聞こえて実は同一の2つの問いのために使う。そこに立つ物がどの光を取るか——構築済み世界の内側ではレベルが
     * 知っており、外側では「分からない」ではなく0を返す。そして、それを隠すのに深度バッファを信頼できるか——
     * {@link GhostOcclusion} が存在する／しない理由そのものだ。
     */
    static boolean isBuilt(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();

        return minecraft.level != null && !minecraft.level.isOutsideBuildHeight(pos.getY())
                && minecraft.levelRenderer.isSectionCompiled(pos);
    }

    /**
     * このフレーム、ゲーム自身のエンティティループがこのエンティティを描いたか／描くか——ゲーム自身の判定で問うので、
     * 二重描画も描き漏らしも起きない。
     *
     * <p>ループがエンティティを描くのは、クライアントが構築済みチャンクセクションを持つ場所か、建築高度より上だけ。
     * その後、遠方面より遠ければ投影がクリップする。
     */
    static boolean gameDraws(Entity entity, Minecraft minecraft, Vec3 eye, double farPlane) {
        BlockPos pos = entity.blockPosition();
        boolean loopDraws = minecraft.level.isOutsideBuildHeight(pos.getY())
                || minecraft.levelRenderer.isSectionCompiled(pos);

        return loopDraws && entity.position().distanceToSqr(eye) < farPlane * farPlane;
    }

    // ------------------------------------------------------------------
    // ジオメトリ
    // ------------------------------------------------------------------

    /**
     * その距離にある物を遠方面の内側へ収めるための、拡大率かつ移動係数。
     *
     * <p>{@code PULL_MARGIN × 遠方面} より近い物では1。それより遠いと、真の距離が無限大へ向かうにつれ描画距離が
     * そこから {@code (PULL_MARGIN + PULL_SPREAD) × 遠方面} へ向かって上がる。だから距離の違う2つのゴーストは今も
     * 違う深度に、正しい順序で描かれ、どちらも面そのものには到達しない。
     */
    static double pull(double away, double farPlane) {
        double reach = farPlane * PULL_MARGIN;

        if (away <= reach) {
            return 1.0;
        }

        double drawnAt = reach * (1.0 + PULL_SPREAD * (1.0 - reach / away));

        return drawnAt / away;
    }

    /** ゴーストの箱が、描かれる形で視錐台内にあるか。 */
    private static boolean inView(Frustum frustum, GhostSnapshot snapshot, Vec3 position, Vec3 eye, double pull) {
        AABB bounds = snapshot.bounds();

        if (pull >= 1.0) {
            return frustum.isVisible(bounds.move(position));
        }

        // 描画時と同様、視点を中心に拡縮する。空の同じ部分が、より近くに来る。
        Vec3 drawnAt = eye.add(position.subtract(eye).scale(pull));
        AABB drawn = new AABB(
                bounds.minX * pull, bounds.minY * pull, bounds.minZ * pull,
                bounds.maxX * pull, bounds.maxY * pull, bounds.maxZ * pull).move(drawnAt);

        return frustum.isVisible(drawn);
    }

    /**
     * 誰かが地面を描いている距離（ブロック）。その9/10を超えるとゴーストは実体ではなくゴースト——半透明の、空を背にした
     * 接触点——として描かれる。内側では背後に地面があるので実体として描かれる。
     */
    public static double ghostStyleRadius() {
        double vanilla = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;

        return Math.max(vanilla, DHIntegration.drawnRadius()) * 0.9;
    }

    // ------------------------------------------------------------------
    // デバッグ用の数値
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // どのステージで描くか
    // ------------------------------------------------------------------

    /**
     * このパスが描くフレーム内ステージ。イベントごとに1回問う。
     *
     * <p>保持せずイベントごとに問うのは、ゲーム稼働中に答えが変わるからだ。シェーダーパックはメニュー1つ先にあり、
     * 飛行中に有効化したユーザーは、さもないと次の再起動まで空っぽの空を飛ぶことになる。問い合わせは boolean への
     * バインド済みメソッドハンドルで、コストは無に等しい。
     */
    private static RenderLevelStageEvent.Stage activeStage() {
        return IrisShaders.inUse()
                ? RenderLevelStageEvent.Stage.AFTER_LEVEL
                : RenderLevelStageEvent.Stage.AFTER_PARTICLES;
    }

    /**
     * 現在 Iris シェーダーパックがフレームを持っているか。Iris 自身へ問う。
     *
     * <p>依存としてではなく {@code IrisApi} へリフレクションで問う。Iris はパックが同梱する物であってこの MOD が必要
     * とする物ではないし、問う唯一の事柄——シェーダーパックが使用中か——は Iris 側で安定した API だ。インストール済み
     * だがパック未選択は「未使用」と数える。それが正しい。その場合ゲームが自分でフレームを組み立てるので、通常のステージ
     * が設計通り機能する。まったく存在しなければハンドルは見つからず、答えは常に no になる。
     */
    private static final class IrisShaders {
        @Nullable
        private static final MethodHandle IN_USE = find();

        static boolean inUse() {
            if (IN_USE == null) {
                return false;
            }

            try {
                return (boolean) IN_USE.invokeExact();
            } catch (Throwable refused) {
                return false;
            }
        }

        @Nullable
        private static MethodHandle find() {
            try {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                Object instance = api.getMethod("getInstance").invoke(null);

                return MethodHandles.publicLookup()
                        .unreflect(api.getMethod("isShaderPackInUse"))
                        .bindTo(instance);
            } catch (Throwable absent) {
                return null;
            }
        }

        private IrisShaders() {
        }
    }

    public static int drawnLastFrame() {
        return drawnLastFrame;
    }

    public static int culledLastFrame() {
        return culledLastFrame;
    }

    /** 前フレームで投影が打ち切られた距離。引き寄せの基準になる値。 */
    public static double farPlaneLastFrame() {
        return farPlaneLastFrame;
    }
}
