package com.ashvehicles.mixin;

import java.util.ArrayList;
import java.util.Iterator;

import com.ashvehicles.entity.VehicleEntityBase;
import com.google.common.collect.Lists;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 飛んでいる間、地形の形を作る順番を、機体の進む方へ寄せる。
 *
 * <p><b>届いた地面は、まだ見える地面ではない。</b> chunk がクライアントへ届いてから絵になるまでには、
 * もう1つ段がある——区画（section）ごとに面を組み立てる仕事だ。{@code LevelRenderer.compileSections} が
 * 毎フレーム、組み直しの要る区画を一覧へ集め、その一覧を順に {@code SectionRenderDispatcher} へ渡す。
 *
 * <p>そして<b>初めて組む区画は渡された順のまま処理される</b>。{@code createCompileTask} は一度も組まれて
 * いない区画を低優先の待ち行列へ入れ、そちらは素の {@code LinkedBlockingDeque}——到着順で、距離を1度も
 * 読まない。飛行機の前方に現れる地面は全部これに当たる。つまり機体の真正面の地面と、真後ろへ流れていく
 * 地面と、真横の地面が、完全に対等に並ぶ。時速450kmでは、後ろの地面が組み上がる頃には誰も見ていない。
 *
 * <p>ここでやるのは一覧を並べ替えることだけだ。仕事の総量も、スレッド数も、1フレームの上限も変えない
 * ——変えたところで速くはならない（構築は既に十数スレッドで走っている）。変わるのは<em>どれが先か</em>で、
 * パイロットにとってはそれが全部だ。同じ直し方をこの MOD は既にサーバー側の送信待ち行列へ施している
 * （{@code FlightChunkOrder}）。こちらはその描画側の片割れになる。
 *
 * <p><b>基準点を遠くへ置かないこと。</b> 送信側は描画距離の縁まで先を見てよいが、こちらは違う。縁は
 * {@code RenderSection.hasAllNeighbors} が満たされない場所——隣の chunk がまだ無いので、そこで組んだ面は
 * 隣が届くたびに組み直しになる。だから先取りは3〜4 chunk に留める。機体のすぐ前が先頭に来て、真下が
 * その次、後ろが最後になれば、それでこの仕事は終わっている。
 *
 * <p><b>掴む場所について。</b> 一覧が作られる所を差し替えて、並べ替えを知っている {@link Ahead} を
 * 返す。もっと素直なのは一覧が使われる直前へ入ることで、最初はそうした——{@code uploadAllPendingUploads}
 * の呼び出しを目印にして。あれは動かない。Distant Horizons が同じメソッドの中でその呼び出しを差し替えて
 * おり、目印にした命令はもうそこに無いからだ。{@code Lists} で一覧を作る所は誰も触っていない。
 */
@Mixin(LevelRenderer.class)
public abstract class SectionCompileOrderMixin {
    /** 基準点を置く先（tick）。この先で機体が着く辺りを、一覧の先頭にする。 */
    private static final double LEAD_TICKS = 20.0;

    /** 先取りの上限（ブロック）。描画距離の縁ではなく、隣が揃っている範囲の内側に留める。 */
    private static final double LEAD_MOST = 64.0;

    /** これより遅い機体（ブロック/tick）では並べ替えない。前後に差を付ける理由が無い。 */
    private static final double CRAWL = 0.5;

    @Redirect(method = "compileSections(Lnet/minecraft/client/Camera;)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/google/common/collect/Lists;newArrayList()Ljava/util/ArrayList;"))
    private ArrayList<SectionRenderDispatcher.RenderSection> ashvehicles$meshTheWayWeFly() {
        LocalPlayer player = Minecraft.getInstance().player;

        // getRootVehicle は何にも乗っていなければプレイヤー自身を返し、プレイヤーがこの MOD の機体で
        // あることは無いので、この1つの判定で両方を兼ねられる。PilotChunkGateMixin と同じ形。
        if (player == null || !(player.getRootVehicle() instanceof VehicleEntityBase machine)) {
            return Lists.newArrayList();
        }

        Vec3 velocity = machine.getVelocity();
        double speed = velocity.length();

        if (speed <= CRAWL) {
            return Lists.newArrayList();
        }

        return new Ahead(player.getEyePosition()
                .add(velocity.scale(Math.min(speed * LEAD_TICKS, LEAD_MOST) / speed)));
    }

    /**
     * 渡された順ではなく、機体の行く先に近い順に読み出される一覧。
     *
     * <p>並べるのは読み出しの直前だ。詰め終わってから1度だけ並べたいのに、詰め終わった瞬間を教えて
     * くれる物がここには無い——{@code add} は何度も来るし、その後に何が起きるかは呼ぶ側の都合だから。
     * 読み出しが始まる時が「詰め終わった時」であることは確かなので、そこで並べる。
     */
    private static final class Ahead extends ArrayList<SectionRenderDispatcher.RenderSection> {
        private final Vec3 lead;

        private Ahead(Vec3 lead) {
            this.lead = lead;
        }

        @Override
        public Iterator<SectionRenderDispatcher.RenderSection> iterator() {
            if (this.size() > 1) {
                this.sort((left, right) -> Double.compare(this.reach(left), this.reach(right)));
            }

            return super.iterator();
        }

        /** 基準点からその区画の中心までの距離の2乗。並べるためだけの値なので平方根は要らない。 */
        private double reach(SectionRenderDispatcher.RenderSection section) {
            BlockPos origin = section.getOrigin();
            double dx = origin.getX() + 8.0 - this.lead.x;
            double dy = origin.getY() + 8.0 - this.lead.y;
            double dz = origin.getZ() + 8.0 - this.lead.z;

            return dx * dx + dy * dy + dz * dz;
        }
    }
}
