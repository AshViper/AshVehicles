package com.ashvehicles.client.renderer;

import com.ashvehicles.client.ghost.GhostRenderContext;
import com.ashvehicles.client.ghost.geo.GhostGeoRenderer;
import com.ashvehicles.client.model.WeaponModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animatable.instance.SingletonAnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.renderer.GeoObjectRenderer;
import software.bernie.geckolib.util.Color;

/**
 * ステーションに吊られている物を、GeckoLib が描ける形にした物。兵装、それを吊るラック、あるいは特殊ステーションの
 * ポッド。
 *
 * <p>3つともエンティティでもブロックでもない。機体自身の描画の一部だ。GeckoLib はその種の物を
 * {@link GeoObjectRenderer} で描き、それは指し示す animatable を必要とする。だからこれがそれだ——描画元ディレクトリ
 * とその中のファイル名しか持たないホルダーである。
 *
 * <p>1つのインスタンスを全機体の全ステーションで再利用し、各描画の直前に設定する。描画は単一スレッドで行われ、
 * 各描画は次が始まる前に終わるので安全であり、視界内の武装機体ごとに毎フレーム数回オブジェクトを新規作成せずに済む。
 */
public final class MountedStore implements GeoAnimatable {
    /** 共有ホルダー1つと、それを描くレンダラー。どちらもクライアント描画スレッド限定。 */
    private static final MountedStore INSTANCE = new MountedStore();
    private static final GeoObjectRenderer<MountedStore> RENDERER = new StoreRenderer();

    private final AnimatableInstanceCache cache = new SingletonAnimatableInstanceCache(this);
    private ResourceLocation weapon = ResourceLocation.withDefaultNamespace("air");
    private String folder = WeaponModel.WEAPONS;

    private MountedStore() {
    }

    /** 指定の兵装を描くよう設定した共有ホルダー。 */
    public static MountedStore of(ResourceLocation weapon) {
        return of(WeaponModel.WEAPONS, weapon);
    }

    /** 3つのディレクトリのいずれかから、指定名の物を描くよう設定した共有ホルダー。 */
    public static MountedStore of(String folder, ResourceLocation id) {
        INSTANCE.folder = folder;
        INSTANCE.weapon = id;

        return INSTANCE;
    }

    /** ラックを描くよう設定した共有ホルダー。 */
    public static MountedStore rack(ResourceLocation rack) {
        return of(WeaponModel.RACKS, rack);
    }

    /** ポッドを描くよう設定した共有ホルダー。 */
    public static MountedStore equipment(ResourceLocation equipment) {
        return of(WeaponModel.EQUIPMENT, equipment);
    }

    public static GeoObjectRenderer<MountedStore> renderer() {
        return RENDERER;
    }

    public ResourceLocation weapon() {
        return this.weapon;
    }

    public String folder() {
        return this.folder;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    /**
     * ステーションに吊られる物はアニメーションしないので、報告すべき時計は無い。GeckoLib がこれを問うのは
     * アニメーション駆動のためだけであり、これらはどれもアニメーションを持たない。
     */
    @Override
    public double getTick(Object entity) {
        return 0.0;
    }

    /** ホルダーに最後に設定された物を、指定されたディレクトリから描く。 */
    private static class Model extends WeaponModel<MountedStore> {
        @Override
        protected ResourceLocation weaponId(MountedStore animatable) {
            return animatable.weapon();
        }

        @Override
        protected String folder(MountedStore animatable) {
            return animatable.folder();
        }
    }

    /**
     * オブジェクトレンダラー。ブロック前提の分を打ち消してある。
     *
     * <p>GeckoLib の {@link GeoObjectRenderer} は描画対象を各軸へ半ブロックずらす。想定していたオブジェクトが、
     * 原点を角に持つブロック空間に収まるからだ。兵装は機体座標系で与えられた主翼上の一点に吊られるので、そのずれを
     * 放置すると、どのパイロンの搭載物もパイロンから半ブロック上かつ横へ浮いてしまう。
     */
    private static class StoreRenderer extends GeoObjectRenderer<MountedStore> {
        StoreRenderer() {
            super(new Model());
        }

        @Override
        public void preRender(PoseStack poseStack, MountedStore animatable, BakedGeoModel model,
                MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                float partialTick, int packedLight, int packedOverlay, int colour) {
            super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick,
                    packedLight, packedOverlay, colour);
            poseStack.translate(-0.5F, -0.51F, -0.5F);
        }

        /**
         * 生きた機体の上では素の白＝そのままの色。ゴーストパスの中では機体と同じ透過度と、DH の霧の同じ
         * 濃さ。これが無いと霧に薄れていく機体の主翼の下に、くっきりしたミサイルだけが残る。
         */
        @Override
        public Color getRenderColor(MountedStore animatable, float partialTick, int packedLight) {
            float alpha = (GhostRenderContext.isTranslucent() ? GhostGeoRenderer.GHOST_ALPHA : 1.0F)
                    * (1.0F - GhostRenderContext.fogFactor());

            return alpha >= 0.999F ? Color.WHITE
                    : Color.ofRGBA(255, 255, 255, (int) (255.0F * Mth.clamp(alpha, 0.0F, 1.0F)));
        }
    }
}
