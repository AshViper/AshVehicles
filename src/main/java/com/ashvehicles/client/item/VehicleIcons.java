package com.ashvehicles.client.item;

import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;

import com.ashvehicles.AshVehicles;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * 平面で用が足りる場所で機体の代役を務める絵。アイテム上と、モデルを描く価値の無いほど遠いゴースト上で使う。手描き
 * ではなく機体自身のジオメトリから撮るので、モデルを持つ機体は絵も持ち、誰かが作るのを覚えておく必要が無い。
 *
 * <p><b>なぜモデルではなく絵なのか。</b>アイテムは毎フレーム、現れる全スロットで、両手で、地面で、カーソル下の
 * ツールチップで描かれる——ホットバーが埋まった状態でインベントリを開けば、他の何より先に同じ物を十数回描くことに
 * なる。そこで GeckoLib の機体を描くとは、16ピクセルの変わらない絵のために、毎フレーム十数回ボーンを歩き立方体を
 * 埋めることだ。だから各機体は<em>1回だけ</em>専用テクスチャへ描き、以後アイテムが描くのは正方形1枚——三角形2つで
 * ボーンは無し——になる。置き換え元のアイテムより軽い。バニラの平坦アイテムは、穴という穴の周りに押し出した縁を持つ
 * 板だが、これはそうではないからだ。
 *
 * <p>撮影は1フレームのコストがかかり、以後はリソースリロードまで保持する。撮るのは1フレームに1つ、フレームの先頭で
 * あって画面描画の途中では決してやらない。機体で一杯のクリエイティブタブを初めて開いたとき、10体分で一度に詰まらない
 * ようにするためだ。絵が用意できるまでその機体のアイテムは何も描かないが、それは1〜10フレームの話で誰の目にも留まら
 * ない。
 */
public final class VehicleIcons {
    /**
     * カメラの位置。真後ろからの回転角と、甲板からの仰角。
     *
     * <p>135° はカメラを機体の<em>右前方</em>へ置く。機首はカメラ側かつ絵の左へ、右舷はカメラ側かつ絵の右へ振れる
     * ので、正面・側面・上面が1つの視界に入り、3つとも短縮されて潰れない。225° は反対側の前方、0° は真後ろ、45° と
     * 315° は斜め後方だ。
     *
     * <p>仰角30° は Minecraft がインベントリのブロックを見下ろす角度とちょうど同じで、偶然ではない。これらの棚が
     * ブロックの棚と並んだとき、1つの棚として読めるべきだからだ。
     */
    private static final float AZIMUTH = 135.0F;
    private static final float ELEVATION = 30.0F;

    /**
     * 保持する絵の大きさと、そこへ縮める前にどれだけ大きく描くか。
     *
     * <p>縮小がスムージングの全てだ。128ピクセルへ直接描くと、角から見た機体の全エッジに硬い階段が残る——機体の
     * エッジは全て斜めだからだ。倍で描いて4ピクセルを1つへ平均すれば同じエッジが階調を持ち、目はそれを直線として
     * 読む。4倍のピクセル数は、1回きりなら4倍の「無」でしかない。
     */
    private static final int SIZE = 128;
    private static final int OVERSAMPLE = 2;
    private static final int DRAWN_SIZE = SIZE * OVERSAMPLE;

    /** 絵を機体よりどれだけ広く切るか。何も端に接しないようにするため。 */
    private static final float MARGIN = 1.08F;

    /**
     * 深度テストのために機体の前後へ残す余裕（ブロック）。値は何でもよい。機体は平行投影で描かれ、絵の中に他の物は
     * 無いからだ。
     */
    private static final float DEPTH_MARGIN = 1.0F;

    private static final Map<ResourceLocation, ResourceLocation> TAKEN = new ConcurrentHashMap<>();
    private static final Set<ResourceLocation> WAITING = ConcurrentHashMap.newKeySet();
    private static final Set<ResourceLocation> FAILED = ConcurrentHashMap.newKeySet();
    private static final Queue<ResourceLocation> QUEUE = new ConcurrentLinkedQueue<>();

    /** ターゲット1つを各機体が順に借りる。最初の撮影時に構築し、以後保持する。 */
    @Nullable
    private static RenderTarget target;

    private VehicleIcons() {
    }

    /**
     * 機体の絵。まだ無ければ {@code null} を返し、その場合は撮影を要求するので1〜2フレーム後には用意される。
     *
     * <p>どこから呼んでも安全。撮影はレンダースレッド以外では起きない。
     */
    @Nullable
    public static ResourceLocation of(ResourceLocation vehicle) {
        ResourceLocation taken = TAKEN.get(vehicle);

        if (taken != null) {
            return taken;
        }

        if (!FAILED.contains(vehicle) && WAITING.add(vehicle)) {
            QUEUE.add(vehicle);
        }

        return null;
    }

    /**
     * 待っている機体があれば、次の1体の絵を撮る。フレームの先頭で呼ばれる。まだ画面が描かれておらず、後で戻すべき
     * 他人の状態が無い時点だ。
     */
    public static void takeNext() {
        ResourceLocation vehicle = QUEUE.poll();

        if (vehicle == null) {
            return;
        }

        WAITING.remove(vehicle);

        try {
            TAKEN.put(vehicle, take(vehicle));
        } catch (Exception exception) {
            // 1回だけ。モデルやテクスチャがロードできない機体は、アイテムがもう一度描かれたからといってロード
            // できるようになりはしないし、ログは毎秒60行で埋まってしまう。
            FAILED.add(vehicle);
            AshVehicles.LOGGER.error("Cannot draw an item picture for {}; its item will be blank", vehicle,
                    exception);
        }
    }

    /**
     * 全ての絵を捨て、要求され次第撮り直させる。撮影元のモデルとテクスチャが今リロードされたので、全て古い物に
     * なった——失敗した分も含めて。修正されたリソースパックが今それを直したかもしれないからだ。
     */
    public static void forget() {
        Minecraft minecraft = Minecraft.getInstance();

        TAKEN.values().forEach(texture -> minecraft.getTextureManager().release(texture));
        TAKEN.clear();
        FAILED.clear();
        WAITING.clear();
        QUEUE.clear();
    }

    /** 機体をオフスクリーンターゲットへ描き、出来上がった物をテクスチャとして保持する。 */
    private static ResourceLocation take(ResourceLocation vehicle) {
        Minecraft minecraft = Minecraft.getInstance();
        Quaternionf view = view();
        VehicleIconGeo.Bounds bounds = VehicleIconGeo.measure(vehicle, view);

        if (bounds.isEmpty()) {
            throw new IllegalStateException("There is nothing in the model to draw");
        }

        RenderTarget into = target();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();

        into.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        into.clear(Minecraft.ON_OSX);
        into.bindWrite(true);
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(framing(bounds), VertexSorting.ORTHOGRAPHIC_Z);
        // 視点処理の全ては下の pose stack にある。測った物がそのまま描かれるようにするためだ。前フレームがここに
        // 残した物はその一部ではない。
        modelView.pushMatrix();
        modelView.identity();
        RenderSystem.applyModelViewMatrix();

        try {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            // ここにある物は天候の中に立っていない。放っておくと、プレイヤーがたまたま居る場所の霧が、投影の箱が
            // 置いた距離に応じて機体の上に描かれてしまう。
            FogRenderer.setupNoFog();
            // 正午のワールドと同じ照明。上面は明るく側面は陰る。機体の絵は機体らしく見えるべきだ。
            Lighting.setupLevel();

            PoseStack poseStack = new PoseStack();
            poseStack.mulPose(view);

            MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
            VehicleIconGeo.draw(poseStack, vehicle, buffers);
            buffers.endBatch();
        } finally {
            // 中で何が起きようと、これから描かれるフレームには自分の行列とカードの状態が戻る。
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            into.unbindWrite();
            minecraft.getMainRenderTarget().bindWrite(true);
        }

        return keep(vehicle, into);
    }

    /**
     * 機体座標系からカメラ座標系への回転。
     *
     * <p>機体を自身の鉛直軸周りに回して船首を振り、そのうえで全体をカメラ側へ倒して見下ろす——この順序で、それが
     * こう組んだクォータニオンの適用順だ。他には何もしない。カメラは原点に座り自身の −Z を見ており、機体までの距離は
     * 平行投影の視界には関与しない。
     *
     * <p>角度はジオメトリがベイクされる座標系での物で、機首が −Z、<em>右</em>舷が −X を向く。どちらも Minecraft が
     * 決めた物ではなく機体側の事情だ。ここのジオメトリは北を向いて作られるし、GeckoLib はベイク時に X を鏡像化する。
     * 同じ2つの事実を反対側から見た説明は {@code GroundVehicleModel} 参照。
     */
    private static Quaternionf view() {
        return new Quaternionf()
                .rotationX(ELEVATION * (float) (Math.PI / 180.0))
                .rotateY(AZIMUTH * (float) (Math.PI / 180.0));
    }

    /**
     * 絵を切り取る箱。正方形で、機体を中心に据え、縦横の長い方がちょうど収まる大きさ。
     *
     * <p>正方形なのは絵がそうだから。機体ごとの数値ではなく機体そのものに合わせて切るのは、戦車も爆撃機も同じ16
     * ピクセルを埋めねばならないからだ。透視ではなく平行投影にするのは、長い機体が端から端まで歪まないようにし、
     * カメラを向いた主翼が膨らまないようにするためだ。
     */
    private static Matrix4f framing(VehicleIconGeo.Bounds bounds) {
        // 0にはしない。幅がまったく無い箱は無限大だらけの投影になる。
        float half = Math.max(bounds.across() * 0.5F * MARGIN, 0.001F);
        float middleX = bounds.middleX();
        float middleY = bounds.middleY();

        // 近接面と遠方面はカメラ自身の視線方向の距離として与えるが、その向きは機体を測った軸とは逆だ。
        return new Matrix4f().setOrtho(middleX - half, middleX + half, middleY - half, middleY + half,
                -bounds.nearest() - DEPTH_MARGIN, -bounds.furthest() + DEPTH_MARGIN);
    }

    /** 描いた結果をカードから読み戻し、縮小し、テクスチャとして登録する。 */
    private static ResourceLocation keep(ResourceLocation vehicle, RenderTarget from) {
        NativeImage drawn = new NativeImage(DRAWN_SIZE, DRAWN_SIZE, false);

        try {
            from.bindRead();
            drawn.downloadTexture(0, false);
            from.unbindRead();
            // フレームバッファは下から上へ読まれ、画像は上から下へ書かれる。
            drawn.flipY();

            ResourceLocation name = ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID,
                    "vehicle_icon/" + vehicle.getPath());
            DynamicTexture texture = new DynamicTexture(shrink(drawn));
            // ドット絵ではなく平滑化する。これは機体の写真でありピクセルアートではないし、スロット・手・地面で
            // それぞれ違う大きさに描かれるからだ。
            texture.setFilter(true, false);
            Minecraft.getInstance().getTextureManager().register(name, texture);

            return name;
        } finally {
            drawn.close();
        }
    }

    /**
     * 描画済みピクセルの正方形ブロックを1ピクセルへ平均する。
     *
     * <p>各ピクセルの存在量で重み付けする。これが全エッジで効く。エッジのピクセルは一部が機体で一部が「無」であり、
     * ここでの「無」は透明な<em>黒</em>だからだ。単純平均すると、何も無い背景に対する白い翼端が灰色になり、どの機体も
     * 汚れた輪郭を纏って描かれてしまう。
     */
    private static NativeImage shrink(NativeImage drawn) {
        NativeImage icon = new NativeImage(SIZE, SIZE, false);

        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int alpha = 0;
                int red = 0;
                int green = 0;
                int blue = 0;

                for (int downY = 0; downY < OVERSAMPLE; downY++) {
                    for (int downX = 0; downX < OVERSAMPLE; downX++) {
                        // 上位からアルファ・青・緑・赤の順に詰められている。
                        int pixel = drawn.getPixelRGBA(x * OVERSAMPLE + downX, y * OVERSAMPLE + downY);
                        int weight = pixel >>> 24;

                        alpha += weight;
                        blue += (pixel >> 16 & 0xFF) * weight;
                        green += (pixel >> 8 & 0xFF) * weight;
                        red += (pixel & 0xFF) * weight;
                    }
                }

                icon.setPixelRGBA(x, y, alpha == 0 ? 0
                        : (alpha / (OVERSAMPLE * OVERSAMPLE)) << 24
                                | (blue / alpha) << 16 | (green / alpha) << 8 | (red / alpha));
            }
        }

        return icon;
    }

    private static RenderTarget target() {
        RenderTarget built = target;

        if (built == null) {
            // 深度バッファ付き。機体の手前側が奥側を覆う必要がある。
            built = new TextureTarget(DRAWN_SIZE, DRAWN_SIZE, true, Minecraft.ON_OSX);
            target = built;
        }

        return built;
    }
}
