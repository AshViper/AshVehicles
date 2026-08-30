package com.ashvehicles.client.ghost.geo;

import javax.annotation.Nullable;

import com.ashvehicles.client.ghost.EntityGhost;
import com.ashvehicles.client.ghost.GhostSnapshot;

import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animatable.instance.SingletonAnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.util.RenderUtil;

/**
 * GeckoLib が描ける物としてのゴースト。
 *
 * <p>GeckoLib は animatable を指すレンダラーを通して物を描くが、ゴーストはエンティティではない。だからこれが
 * animatable だ——どのゴーストを描いているか、どうポーズを付けるかを持つホルダーである。1つのインスタンスを全
 * ゴーストで共有し、各描画の直前に順番にそのゴーストへ向ける——描画は単一スレッドで、1つの描画は次が始まる前に
 * 終わる。
 *
 * <p>ホルダーの共有はアニメーションの共有を<em>意味しない</em>。GeckoLib は {@link AnimatableManager} を
 * animatable 単位ではなくインスタンスID単位で持ち、{@link GhostGeoRenderer#getInstanceId} が各ゴーストに固有の物
 * を与える。キー付きキャッシュを伴う共有 animatable こそ {@link SingletonAnimatableInstanceCache} の用途だ。
 * 別々の動きをする降着装置を持つ2機は、したがって各々が独自の状態を持つ2組のコントローラになり、どちらもこの1つの
 * オブジェクト経由で到達される。
 *
 * <p>ゴーストがどのコントローラを持つかはそのアダプタの管轄だ。GeckoLib は各ゴーストの初回描画時に
 * {@link #registerControllers} を問うが、それはそのゴースト自身の描画の内側で起きるので、問われるアダプタは正しい
 * 物になる。
 */
public final class GhostAnimatable implements GeoAnimatable {
    private static final GhostAnimatable INSTANCE = new GhostAnimatable();

    private final AnimatableInstanceCache cache = new SingletonAnimatableInstanceCache(this);

    private EntityGhost ghost;
    private GhostSnapshot snapshot;
    @Nullable
    private GhostPoser poser;

    private GhostAnimatable() {
    }

    /** 指定のゴーストを描くよう設定した共有ホルダー。 */
    static GhostAnimatable of(EntityGhost ghost, GhostSnapshot snapshot, @Nullable GhostPoser poser) {
        INSTANCE.ghost = ghost;
        INSTANCE.snapshot = snapshot;
        INSTANCE.poser = poser;

        return INSTANCE;
    }

    public EntityGhost ghost() {
        return this.ghost;
    }

    public GhostSnapshot snapshot() {
        return this.snapshot;
    }

    @Nullable
    GhostPoser poser() {
        return this.poser;
    }

    /**
     * 描画中のゴーストのコントローラ。GeckoLib がそのゴースト用のアニメーションマネージャを最初に必要とした時点
     * ——そのゴーストの初回描画の途中——で呼ばれる。
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        if (this.ghost != null) {
            this.ghost.adapter().registerGhostControllers(controllers, this);
        }
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    /**
     * ゴーストのアニメーションが走る時計。ゲームの描画クロックで、GeckoLib がエンティティ以外に使う物であり、
     * tick単位の段階ではなくtick間もなめらかに進む。
     */
    @Override
    public double getTick(Object entity) {
        return RenderUtil.getCurrentTick();
    }

    /**
     * ゴーストのモデルのボーンをスナップショットからポーズ付けする。飛行に毎瞬追従する回転を、機体自身が求めるのと
     * まったく同じやり方で直近2tickの間から求める。アニメーションファイルから再生される物はこれが走る時点でコント
     * ローラが既に再生済みであり、ここで設定した物がそれを上書きする——機体自身のモデルが使うのと同じ順序だ。
     */
    @FunctionalInterface
    public interface GhostPoser {
        void pose(GeoModel<GhostAnimatable> model, EntityGhost ghost, float partialTick);
    }
}
