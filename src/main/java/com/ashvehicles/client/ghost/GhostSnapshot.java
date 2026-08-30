package com.ashvehicles.client.ghost;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaternionf;

/**
 * ゴーストレンダラーがエンティティについて知る必要のある全てを、一度にコピーした物。
 *
 * <p>ゲームスレッドで毎tick撮影し、レンダースレッドへ丸ごと渡す。不変であることが要点だ。レンダラーは自分の下で
 * 変化しないスナップショットを読み、次のtickは中身ではなく参照を差し替える。位置は描画時に前回スナップショットと
 * これの間で補間する。ゲームがエンティティを前tickと今tickの間で補間するのとまったく同じだ。
 *
 * @param uuid エンティティの UUID。ゴーストの同一性はこれで決まる。エンティティIDは再利用されるが UUID はされない
 * @param entityId エンティティのネットワークID。実体が存在する間に再発見するため
 * @param type エンティティの種類
 * @param position 撮影tick時点の位置
 * @param velocity 移動量（ブロック/tick）
 * @param yaw 方位（度）。ゲーム流儀
 * @param pitch ピッチ（度）
 * @param bodyYaw 体の方位（度）。体が別にない物では {@code yaw} と同じ
 * @param attitude 完全な姿勢を回転として。姿勢が2角では足りない物向けで、方位とピッチで語り尽くせるなら {@code null}
 * @param scale モデルの拡大率
 * @param shade 自身の色をどれだけ保つか。1なら全部。ゴーストの見た目のうち距離ではなくエンティティの管轄である
 *        唯一の要素だ。全損した機体はどこから描かれても焦げているし、機体色で描かれるゴーストは引き継ぎ距離で
 *        残骸を生き返らせてしまう
 * @param model ゴーストを描く元のジオメトリファイル。無ければ {@code null}
 * @param texture 描画に使うテクスチャ。無ければ {@code null}
 * @param animation ゴーストが再生するサイクルを含むアニメーションファイル。再生する物が無ければ {@code null}
 * @param billboard 最遠階層用の平坦アイコン。無ければ {@code null}
 * @param bounds エンティティが描かれる範囲の箱。{@code position} 相対。ゴーストのカリング対象であり、遮蔽トレース
 *        の狙い先であり、ビルボードの寸法元でもある。エンティティが衝突に使う箱とは意図的に別物で、この種の機体
 *        では衝突箱は実体より小さく保たれている
 * @param useGeckoLib {@code model} が GeckoLib のジオメトリファイルか
 * @param gameTime 撮影したゲームtick
 * @param payload 撮影したアダプタが描画時に受け取りたいその他の情報
 */
public record GhostSnapshot(
        UUID uuid,
        int entityId,
        EntityType<?> type,
        Vec3 position,
        Vec3 velocity,
        float yaw,
        float pitch,
        float bodyYaw,
        @Nullable Quaternionf attitude,
        float scale,
        float shade,
        @Nullable ResourceLocation model,
        @Nullable ResourceLocation texture,
        @Nullable ResourceLocation animation,
        @Nullable ResourceLocation billboard,
        AABB bounds,
        boolean useGeckoLib,
        long gameTime,
        @Nullable Object payload) {

    /** このゴーストがワールドで占める箱。カリングとデバッグ輪郭用。 */
    public AABB worldBounds() {
        return this.bounds.move(this.position);
    }

    /** 足元ではなくエンティティの中心。遮蔽判定が最初に狙う点。 */
    public Vec3 centre() {
        return this.position.add(0.0, (this.bounds.minY + this.bounds.maxY) * 0.5, 0.0);
    }

    /**
     * エンティティの上端。遮蔽判定のフォールバック先。地上にある物を同程度の高さの視点から見ると、その中心への
     * 線は全行程で地表を掠めてしまう。
     */
    public Vec3 top() {
        return this.position.add(0.0, this.bounds.maxY, 0.0);
    }
}
