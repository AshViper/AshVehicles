package com.ashvehicles.client.ghost.adapter;

import javax.annotation.Nullable;

import com.ashvehicles.client.ghost.EntityGhost;
import com.ashvehicles.client.ghost.GhostAdapter;
import com.ashvehicles.client.ghost.GhostLOD;
import com.ashvehicles.client.ghost.GhostRenderContext;
import com.ashvehicles.client.ghost.GhostSnapshot;
import com.ashvehicles.client.renderer.Tracer;
import com.ashvehicles.entity.BulletEntity;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * ゴーストとしての機関砲弾。近距離レンダラーが描くのと同じ曳光の筋を、スナップショットから描く。
 *
 * <p>弾は差し渡し数センチで毎秒100ブロック進むので、描く物は曳光しか無いし、遠距離で見る価値があるのも曳光だ。
 * 砲手が弾道を読むのはその流れであり、その読み取りが最も重要になるのは、目標が未ロードの地面の上にいるほど
 * 遠いときだ。
 *
 * <p><b>ここでの階層の意味。</b>曳光には省ける部品が無い——頂点4つで、線はそれ未満では描けない——ので、階層が
 * 決めるのは「どこまで描くか」ではなく「距離に耐えるためにどう描くか」だ。{@code GHOST} 階層では筋として描き、
 * 幅をワールド基準ではなく画面基準で保つ。ゴーストパスが引き継ぐまさにその距離で、明滅する1ピクセル未満まで
 * 細るのを防ぐためだ。最遠階層 {@code BILLBOARD} では、筋を諦めて、そこで既になっている光点にする。どちらも
 * {@link Tracer} にあり、近距離レンダラーが描くのにも使う。引き継ぎ距離を横切る弾が見た目を変えてはならず、
 * それを保証する唯一の方法は1か所のコードで決めることだ。
 */
public final class BulletGhostAdapter implements GhostAdapter<BulletEntity> {
    /** スナップショットが何らかの理由で色を持たない場合に弾を描く色。 */
    private static final int DEFAULT_TRACER = 0xFFFFC864;

    @Override
    public GhostSnapshot snapshot(BulletEntity bullet, @Nullable GhostSnapshot previous, long gameTime) {
        Vec3 position = bullet.position();
        Vec3 travel = bullet.getDeltaMovement();
        // 弾ではなく筋が占める箱。弾はその先端で残りは後方にあり、先端が画面外に出たばかりの筋はまだ大半が
        // 画面内にある。
        AABB bounds = bullet.getBoundingBox().move(position.reverse());
        Vec3 tail = Tracer.tail(travel);
        return new GhostSnapshot(
                bullet.getUUID(),
                bullet.getId(),
                bullet.getType(),
                position,
                travel,
                bullet.getYRot(),
                bullet.getXRot(),
                bullet.getYRot(),
                null,
                1.0F,
                1.0F,
                null,
                null,
                null,
                null,
                bounds.minmax(bounds.move(tail)),
                false,
                gameTime,
                // 弾の色は兵器の色であり、兵器はどのクライアントでも弾より長生きする。それでも色は毎フレーム
                // 引くより持ち回る方が安い。
                0xFF000000 | bullet.getRound().tracer());
    }

    @Override
    public void render(EntityGhost ghost, GhostLOD lod, GhostRenderContext context) {
        GhostSnapshot snapshot = ghost.current();
        int colour = snapshot.payload() instanceof Integer tracer ? tracer : DEFAULT_TRACER;

        // DH の霧の分だけ暗く・薄く。この描画タイプの混合では色を縮めることが光量を絞ることなので、
        // アルファの扱いがどうであれ曳光は霧に沈む。
        if (context.fog() > 0.0F) {
            colour = faded(colour, 1.0F - context.fog());
        }

        // 照明も陰影も無し。この描画タイプは光を完全に無視する。下に光源となる世界があろうと無かろうと、
        // 曳光にはそれが正しい。
        VertexConsumer buffer = context.buffers().getBuffer(RenderType.lightning());

        if (lod == GhostLOD.BILLBOARD) {
            Tracer.dot(context.poseStack(), buffer, context.camera(), context.distanceSq(), colour);

            return;
        }

        Vec3 travel = snapshot.velocity();

        if (travel.lengthSqr() < 1.0E-6) {
            return;
        }

        Tracer.streak(context.poseStack(), buffer, context.camera(), context.fromCamera(), travel,
                context.distanceSq(), colour);
    }

    /** ARGB の4成分全部を等しく縮める。どの混合方式でもこれで薄くなる。 */
    private static int faded(int colour, float keep) {
        int a = (int) (((colour >>> 24) & 0xFF) * keep);
        int r = (int) (((colour >> 16) & 0xFF) * keep);
        int g = (int) (((colour >> 8) & 0xFF) * keep);
        int b = (int) ((colour & 0xFF) * keep);

        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * 世界に対して一切トレースしない。弾の寿命は2秒程度で同時に大量に存在するので、レイ予算を丸ごと食い潰し、
     * 機体——ゴーストの描き間違いが本当に問題になる相手——に何も残らなくなる。代償は Distant Horizons の丘の上に
     * 曳光が描かれること。ゲーム自身が描いた地形の背後には、ゲーム自身の深度バッファが今も隠してくれる。
     */
    @Override
    public boolean needsOcclusionCheck() {
        return false;
    }
}
