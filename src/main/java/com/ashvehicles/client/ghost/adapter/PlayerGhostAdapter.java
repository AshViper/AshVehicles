package com.ashvehicles.client.ghost.adapter;

import javax.annotation.Nullable;

import com.ashvehicles.client.ghost.EntityGhost;
import com.ashvehicles.client.ghost.GhostAdapter;
import com.ashvehicles.client.ghost.GhostLOD;
import com.ashvehicles.client.ghost.GhostRenderContext;
import com.ashvehicles.client.ghost.GhostSnapshot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * ゴーストとしての人。
 *
 * <p>機械のためにあった仕組みに人が加わるのは、人が機械と同じ距離から見えるからだ。滑走路に立っている
 * 整備員、僚機のコクピットに座っている僚友、2km 先の丘で手を振っている誰か。どれも、その人の足元の地面が
 * 送られなくなった瞬間に消えていた。消えたのが機械なら気付くのは操縦している本人だけだが、消えたのが人なら
 * 気付くのは消えた側と見ていた側の両方である。
 *
 * <p><b>描くのはゲーム自身のレンダラーだ。</b>他のアダプタと違い、ここではモデルを自前で組まない。理由は
 * 2つある。1つは、この距離のプレイヤーは数ピクセルであり、自前の簡略モデルとバニラのモデルの差——防具、
 * 手持ち、マント、スキンの細部——が全部その数ピクセルの中で潰れること。もう1つは、潰れないもの——スキンの
 * 色、体の向き、歩いている足——が全部バニラ側に既にあり、それを写し取った実装は必ずいつか本家とずれること。
 * ずれた方が正しいことは無い。
 *
 * <p>だから写真から描かない唯一のアダプタでもある。それが成り立つのは、人のゴーストが実体より長生きしない
 * からだ——{@link #keepAfterLeave} は既定の false で、受信が止まった瞬間にゴーストごと消える。機体と違って
 * 「誰もロードしていない土地に置きっぱなしの人」は存在せず、送信が止まった人が最後に見えた場所に立ち続けて
 * いる保証はどこにも無い。写真が要るのは実体より長生きする物であり、これはそうではない。
 *
 * <p>写真そのものは撮る。マネージャが位置の補間・階層の判定・遮蔽トレースに使うのはスナップショットであって
 * エンティティではないからで、その3つは人にも同じだけ要る。
 *
 * <p><b>半透明にはならない。</b>機械のゴーストは空を背にすると透けるが、バニラのレンダラーにその指示を渡す
 * 隙間は無い。人は透けずに描かれる。光量だけは{@link GhostRenderContext#packedLight()}から渡すので、
 * 構築済み世界の外で真っ黒な染みになることはない。
 */
public final class PlayerGhostAdapter implements GhostAdapter<Player> {
    @Override
    public GhostSnapshot snapshot(Player player, @Nullable GhostSnapshot previous, long gameTime) {
        Vec3 position = player.position();

        return new GhostSnapshot(
                player.getUUID(),
                player.getId(),
                player.getType(),
                position,
                player.getDeltaMovement(),
                // 頭と体は別に向く。ゴースト自身は姿勢を使わないが、遮蔽と階層より先に、いずれ写真から
                // 描く日が来たときに要るのはこの2つだ。
                player.getYHeadRot(),
                player.getXRot(),
                player.yBodyRot,
                null,
                1.0F,
                1.0F,
                // モデルもテクスチャもここでは指さない。描くのはバニラのレンダラーで、それは自分で引く。
                null,
                null,
                null,
                null,
                // 当たり判定箱をそのまま位置相対にした物。人ではこれが実寸であり、機体のように
                // 描画の箱と衝突の箱が食い違うことはない。
                player.getBoundingBox().move(position.reverse()),
                false,
                gameTime,
                null);
    }

    @Override
    public void render(EntityGhost ghost, GhostLOD lod, GhostRenderContext context) {
        Entity entity = ghost.entity();

        // 実体が無いゴーストはここでは描けない。keepAfterLeave が false なので普通は起こらないが、
        // 起こったときに落ちるより何も描かない方がよい。
        if (!(entity instanceof Player player)) {
            return;
        }

        EntityRenderer<? super Player> renderer =
                Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(player);
        float partialTick = context.partialTick();
        // ゲーム自身のエンティティループが渡すのと同じ補間方位。同じ人が引き継ぎ距離を歩いて跨ぐときに
        // 向きが飛ばないためには、両側が同じ式である必要がある。
        float yaw = Mth.lerp(partialTick, player.yRotO, player.getYRot());

        // 位置合わせは済んでいる——pose stack はゴーストの原点にあり、遠方面への引き寄せも掛かっている。
        // ディスパッチャ経由ではなくレンダラーを直に呼ぶのは、影を描かせないためだ。影は真下のブロックを
        // 問うが、ここで描いている人の真下は、このクライアントがロードしていない地面である。
        renderer.render(player, yaw, partialTick, context.poseStack(), context.buffers(),
                context.packedLight());
    }

    /**
     * 人の箱は小さいので、地形の陰に入ったかどうかは目で見て分かるほど効く。丘の向こうの人が丘の上に
     * 描かれるのは、機体で同じことが起きるより気付かれやすい——人は地面の近くにいるからだ。
     *
     * <p>レイ予算を食う相手ではある。ただし弾と違って人は同時に数十も存在しないし、1人につき
     * {@code occlusionIntervalTicks} に1回でしかない。
     */
    @Override
    public boolean needsOcclusionCheck() {
        return true;
    }
}
