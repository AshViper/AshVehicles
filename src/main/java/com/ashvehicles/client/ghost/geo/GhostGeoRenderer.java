package com.ashvehicles.client.ghost.geo;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.ghost.EntityGhost;
import com.ashvehicles.client.ghost.GhostRenderContext;
import com.ashvehicles.client.ghost.GhostSnapshot;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoObjectRenderer;
import software.bernie.geckolib.util.Color;

/**
 * ゴーストの GeckoLib モデルをスナップショットから描く。スナップショットが指すジオメトリとテクスチャを、その
 * スケールで、代役先のエンティティと同じ再生・ポーズで描く。
 *
 * <p>これはエンティティのレンダラーではなく、エンティティを必要としない。描画元は全て撮影時にコピー済みなので、
 * エンティティが消えた後も同じように描ける。その扱い方は両半分ともエンティティ自身のレンダラーと同じだ。アダプタ
 * がコントローラを登録した物はスナップショットが指すアニメーションファイルから再生され、その後アダプタの poser が
 * 飛行に毎瞬追従するボーンを設定する。
 *
 * <p>1つのレンダラーと1つのモデルが全ゴーストに仕える。1つが同種の全エンティティに仕えるのと同じだ。animatable
 * は共有の {@link GhostAnimatable} で描画ごとに設定し、各ゴーストのアニメーション状態は {@link #getInstanceId}
 * で分離する。
 */
public final class GhostGeoRenderer extends GeoObjectRenderer<GhostAnimatable> {
    /** ゴーストの不透明度。空を背に読める程度で、近くの物と間違えるにははるかに足りない。 */
    public static final float GHOST_ALPHA = 0.55F;

    private static final GhostGeoRenderer INSTANCE = new GhostGeoRenderer();

    private GhostGeoRenderer() {
        super(new Model());
    }

    /**
     * ゴーストを描く。pose stack は既にゴーストの原点にあり姿勢も合わせてあるはずで、ここで適用するのはモデル自身
     * のスケールだけ。
     *
     * @param poser ボーンのポーズ付け方法。作成時のポーズのままなら {@code null}
     */
    public static void draw(EntityGhost ghost, GhostSnapshot snapshot, GhostRenderContext context,
            @Nullable GhostAnimatable.GhostPoser poser) {
        if (snapshot.model() == null || snapshot.texture() == null) {
            return;
        }

        GhostAnimatable animatable = GhostAnimatable.of(ghost, snapshot, poser);
        startClock(animatable);
        RenderType type = renderType(snapshot.texture(), context);
        MultiBufferSource buffers = context.buffers();

        // 素通しの覆いを1枚挟む。GeckoLib の頂点書き込みを乗っ取る最適化 MOD の生バイト経路が、この
        // パスの外相（通常のエンティティフェーズの外）で走ってテクスチャ対応を壊すのを防ぐ。
        // PlainVertices 参照。
        INSTANCE.render(context.poseStack(), animatable, buffers, type,
                new PlainVertices(buffers.getBuffer(type)),
                context.packedLight(), context.partialTick());
    }

    /**
     * 全ゴーストのアニメーション時計を同じ瞬間から始める。GeckoLib が「そのゴーストがたまたま最初に描かれた瞬間」
     * から始めてしまう前に。
     *
     * <p>GeckoLib はエンティティでない物を自身の最初のフレームから計時し、以後1つの時計——モデルの時計——を描画間の
     * 差分で進める。モデルが1つの物を描くならそれでよい。こちらのモデルは全ゴーストを描き、ゴーストの初出時刻は
     * それぞれ違うので、放っておけば時計は各々の開始点の差だけ前後に跳び、アニメーションもそれに引きずられる。
     * 各ゴーストの開始を0に固定すれば、その値は全ゴーストで同じ絶対時計になる。ゲームがエンティティに対して行って
     * いることであり、1つのモデルが100体を描ける理由でもある。
     */
    private static void startClock(GhostAnimatable animatable) {
        AnimatableManager<?> manager = animatable.getAnimatableInstanceCache()
                .getManagerForId(INSTANCE.getInstanceId(animatable));

        if (manager.getFirstTickTime() == -1) {
            manager.startedAt(0.0);
        }
    }

    /**
     * ゴースト1つにアニメーション状態1つ。GeckoLib はこれを鍵にコントローラを整理するし、別々の時刻に脚を下ろす
     * 2機がセットを共有してはならない。ゴーストの同一性が UUID なのは他所と同じ理由だ——エンティティIDは再利用
     * される。
     */
    @Override
    public long getInstanceId(GhostAnimatable animatable) {
        return animatable.ghost().uuid().getLeastSignificantBits();
    }

    /** これより濃い霧の中では、切り抜きではなく透過で描く必要がある。薄め始めの見た目の段差はこの下に隠れる。 */
    private static final float FOGGED = 0.01F;

    /**
     * 背後に何も無いゴーストは半透明かつ自発光で描く。自発光なのは、あの距離に照らす光が無いから。半透明なのは、
     * 近くの物ではなく「接触点」として読ませるためだ。描画済み地形の上では実体として描くが、自発光は保つ。
     */
    public static RenderType renderType(ResourceLocation texture, boolean ghostStyle) {
        return ghostStyle
                ? RenderType.entityTranslucentEmissive(texture)
                : RenderType.entityCutoutNoCull(texture);
    }

    /**
     * 同じ選択を、DH の霧も踏まえて。切り抜き描画はアルファを混ぜられないので、霧に薄まり始めた物は実体の
     * 距離でも透過型へ移す。移した瞬間のアルファは {@code 1 − 霧} ≈ 1 なので、切り替えは目に見えない。
     */
    public static RenderType renderType(ResourceLocation texture, GhostRenderContext context) {
        return renderType(texture, context.ghostStyle() || context.fog() > FOGGED);
    }

    @Override
    public Color getRenderColor(GhostAnimatable animatable, float partialTick, int packedLight) {
        // 透過度は距離の管轄、暗さはエンティティの管轄。残骸はどの距離でも焦げているし、機体色で描かれる
        // ゴーストは、ゲーム自身のレンダラーが引き継いだ瞬間に残骸を生き返らせてしまう。DH の霧はさらに
        // その上へ掛かる——濃さの分だけ透明へ寄せれば、既に霧の色をした背景が透けて「霧に混ざった」画になる。
        float alpha = (GhostRenderContext.isTranslucent() ? GHOST_ALPHA : 1.0F)
                * (1.0F - GhostRenderContext.fogFactor());
        int level = (int) (255.0F * Mth.clamp(animatable.snapshot().shade(), 0.0F, 1.0F));

        return Color.ofRGBA(level, level, level, (int) (255.0F * Mth.clamp(alpha, 0.0F, 1.0F)));
    }

    /**
     * モデル自身のスケールと、GeckoLib のブロック前提の打ち消し。オブジェクトレンダラーは描画対象を半ブロック
     * ずらす。想定していたオブジェクトが、原点を角に持つブロック内に収まるからだ。ゴーストは点に立つ。
     */
    @Override
    public void preRender(PoseStack poseStack, GhostAnimatable animatable, BakedGeoModel model,
            MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender, float partialTick,
            int packedLight, int packedOverlay, int colour) {
        float scale = animatable.snapshot().scale();
        this.scaleWidth = scale;
        this.scaleHeight = scale;

        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick,
                packedLight, packedOverlay, colour);
        poseStack.translate(-0.5F, -0.51F, -0.5F);
    }

    /** ジオメトリ・テクスチャ・アニメーションはスナップショットから、ポーズはアダプタの poser から。 */
    private static final class Model extends GeoModel<GhostAnimatable> {
        /**
         * アニメーションファイルを持たないゴーストの代役。ここからアニメーションを要求する物は無い。再生する物が
         * 無いゴーストには問い合わせるコントローラも無いからだ。
         */
        private static final ResourceLocation NO_ANIMATION =
                ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "animations/ghost/none.animation.json");

        @Override
        public ResourceLocation getModelResource(GhostAnimatable animatable) {
            return animatable.snapshot().model();
        }

        @Override
        public ResourceLocation getTextureResource(GhostAnimatable animatable) {
            return animatable.snapshot().texture();
        }

        @Override
        public ResourceLocation getAnimationResource(GhostAnimatable animatable) {
            ResourceLocation animation = animatable.snapshot().animation();

            return animation == null ? NO_ANIMATION : animation;
        }

        /** poser が指定しジオメトリに無いボーンはスキップする。クラッシュにはしない。 */
        @Override
        public boolean crashIfBoneMissing() {
            return false;
        }

        @Override
        public void setCustomAnimations(GhostAnimatable animatable, long instanceId,
                AnimationState<GhostAnimatable> animationState) {
            super.setCustomAnimations(animatable, instanceId, animationState);

            GhostAnimatable.GhostPoser poser = animatable.poser();

            if (poser != null) {
                poser.pose(this, animatable.ghost(), animationState.getPartialTick());
            }
        }
    }
}
