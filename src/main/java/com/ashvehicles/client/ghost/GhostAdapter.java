package com.ashvehicles.client.ghost;

import javax.annotation.Nullable;

import com.ashvehicles.client.ghost.geo.GhostAnimatable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import software.bernie.geckolib.animation.AnimatableManager;

/**
 * ある種類のエンティティがどうゴーストになり、そのゴーストがどう描かれるか。
 *
 * <p>機体を何も知らないゴーストシステムと、ゴーストを何も知らない MOD の継ぎ目。アダプタは
 * {@link EntityGhostRegistry} にエンティティタイプごとに1つ登録し、毎tickエンティティを撮影すること、そして
 * カメラ距離が要求する {@link GhostLOD} でその写真を描くことを求められる。
 *
 * <p>アダプタが描く元はエンティティではなくスナップショットだ。ゴーストは、それを持っていたクライアント上の
 * エンティティより長生きしうるので、エンティティを必要とするゴーストは一緒に消えてしまう。
 *
 * @param <T> このアダプタが扱うエンティティ
 */
public interface GhostAdapter<T extends Entity> {
    /**
     * エンティティのスナップショットを撮る。ゲームスレッドで毎tick1回。
     *
     * @param entity 実体
     * @param previous 直前のスナップショット。初回なら {@code null}
     * @param gameTime 現在のゲームtick
     */
    GhostSnapshot snapshot(T entity, @Nullable GhostSnapshot previous, long gameTime);

    /**
     * ゴーストを描く。pose stack はゴーストの原点にありワールド軸を向いていて、遠方面への引き寄せは適用済み。
     * アダプタは姿勢とジオメトリを与える。
     */
    void render(EntityGhost ghost, GhostLOD lod, GhostRenderContext context);

    /**
     * エンティティが死んだわけでもなく受信が止まった後、{@link GhostConfig#timeoutTicks()} の間ゴーストを保持
     * すべきか——単に範囲外へ出ただけのエンティティは恐らくまだそこにいる。既定は「保持しない」。ゴースト自体は
     * 安いが、離れていったMob 全てに1つずつ残すのは安くない。
     */
    default boolean keepAfterLeave(T entity) {
        return false;
    }

    /**
     * 受信が止まったゴーストを何tick残すか。{@link #keepAfterLeave} が真と答えた場合にだけ意味を持つ。
     *
     * <p>既定は {@link GhostConfig#timeoutTicks()}——追跡範囲の縁を飛ぶ弾に与える短い猶予だ。駐機した機体の
     * ように「誰もロードしていない土地では変わりようがない」物のアダプタは、ずっと長い答えを返してよい。
     * 最後に見えた姿こそ、そこにある物の正確な絵だからだ。
     */
    default int orphanTicks() {
        return GhostConfig.timeoutTicks();
    }

    /**
     * エンティティが単にこのクライアントから消えたのではなく、死亡・破壊されたか。死んだエンティティのゴースト
     * は {@link #keepAfterLeave} が何と言おうと即座に削除する。
     */
    default boolean isDead(T entity) {
        if (entity instanceof LivingEntity living && living.isDeadOrDying()) {
            return true;
        }

        Entity.RemovalReason reason = entity.getRemovalReason();

        return reason == Entity.RemovalReason.KILLED;
    }

    /**
     * カメラとこのゴーストの間で世界をトレースすべきか。無効にするとゴーストは深度バッファでしか隠れなくなる
     * が、数が多く短命な物にはそれが正しい取引だ。レイ予算は小さく、共有されている。
     */
    default boolean needsOcclusionCheck() {
        return true;
    }

    /**
     * この種のゴーストが再生するアニメーションコントローラを、エンティティ自身が登録するのと同じ形で登録する。
     * GeckoLib はゴーストごとに、最初の描画時に1回問い合わせ、渡された物はそのゴースト専用になる。animatable は
     * 全ゴーストで共用なので、コントローラは渡された animatable からどのゴースト向けに再生しているかを読む。
     *
     * <p>大半の物には再生する物が無い——ミサイルは目標まで終始ミサイルだ——ので既定は何も登録しない。機体には
     * 降着装置があり、機体自身が登録するのと同じコントローラをここでも登録することが、ゴーストの脚を機体の脚と
     * 同じ動きに保つ。
     */
    default void registerGhostControllers(AnimatableManager.ControllerRegistrar controllers,
            GhostAnimatable animatable) {
    }
}
