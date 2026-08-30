package com.ashvehicles.client;

import javax.annotation.Nullable;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * クライアントが地面について知っていること、そして見えない地面について進んで述べること。
 *
 * <p>地表を指す機体の計器はどれも同じ壁にぶつかる。チャンクはプレイヤーの周りにしか存在せず、飛ぶに値するどの
 * 高度からでも、指している物はそれより遠い。その外では全ブロックが空気と読まれるので、ブロックだけを問うトレース
 * は何も見つけず空で戻る——計器面では、パイロットが必要とするほど高く上がったまさにその瞬間に真っ白になる計器だ。
 *
 * <p>そこでロード範囲の外では、仮定した床——クライアントが最後に知っていた列の高さを持ち越した値で、この側の誰か
 * が目標について正直に言える最も近い物——に対して地面を追う。そうして求めた答えはそうと印を付けて返すので、要求
 * した側は「見せられた地面」と「教えられた地面」を区別できる。爆弾をそこへ落とす {@link BombSight} と、レーザー
 * マークをそこへ置く {@link PodCamera} 参照。
 */
public final class Terrain {
    /**
     * 直線トレースで列を何ブロックおきに標本化するか。
     *
     * <p>半チャンク。ロード範囲の内側ではこれより細かくしてもコストは変わらない。あちらで答えるのはブロック自体
     * であり、各ステップは長さに関わらずブロック単位で歩かれるからだ。外側では、これがレイと仮定した床を比較する
     * 粗さになる。交点自体は、それを含むステップが見つかった時点で厳密に求める。
     */
    private static final double STEP = 8.0;

    private Terrain() {
    }

    /**
     * 地面が見つかった位置と、その値の信頼度。
     *
     * @param point 地面の位置
     * @param estimated クライアントに実際に見えるブロックではなく仮定した床の上で見つかったか。true なら地点は
     *                  ロード範囲の外にあり高さは推測値だ。平坦な土地の上では正しく、ここからそこまでの高低差の
     *                  分だけ誤る
     */
    public record Ground(Vec3 point, boolean estimated) {
    }

    /**
     * 直線を、近くでも遠くでも地面に出会うまで追う。出会わなければ null——空へ向いた線か、許された距離まで進み
     * 切った線だ。
     *
     * <p>1回の走査で2種類の答えを出すのが要点。問い合わせるチャンクがある間はブロック自体に問い、答えは本物になる。
     * 斜面も屋根も含めて。無くなったら、走査が最後に知っていた床に対して線を追い、そこを抜ける位置が答えになる。
     * どちらの半分も相手の終わりを知らず、呼び出し元にはどちらの答えかが伝えられる。
     *
     * @param direction 線の向き。単位ベクトルであること。{@code reach} はこれに沿って測るので、長さの違うベクトル
     *                  は別の到達距離を意味してしまう
     * @param ignore トレースの持ち主。機体が自分自身の表面を見つけないようにする
     */
    @Nullable
    public static Ground along(Level level, Vec3 from, Vec3 direction, double reach,
            @Nullable Entity ignore) {
        // 実地形の最初の列を読むまでは海面高。コックピットから外を見る物にとってそれは機体自身が立っている列で
        // あり、最初のステップで読まれる。
        double floor = level.getSeaLevel();
        Vec3 position = from;

        for (double travelled = 0.0; travelled < reach; travelled += STEP) {
            Vec3 next = from.add(direction.scale(Math.min(travelled + STEP, reach)));
            double ground = surface(level, next);

            if (!Double.isNaN(ground)) {
                HitResult hit = level.clip(new ClipContext(position, next,
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ignore));

                if (hit.getType() != HitResult.Type.MISS) {
                    return new Ground(hit.getLocation(), false);
                }

                floor = ground;
            } else if (next.y <= floor) {
                return new Ground(crossing(position, next, floor), true);
            }

            position = next;
        }

        return null;
    }

    /**
     * ある地点が乗っている列の地面高。クライアントに問い合わせるチャンクが無ければ {@link Double#NaN}。
     *
     * <p>最上位ブロック自体ではなくその上面。物が着地する面であり、ブロックを見られるトレースが返してくるのも
     * その高さだ。
     *
     * <p>チャンク自身のハイトマップから読み、チャンクはロードを許さず要求する——{@code false} の意味はそれだ。
     * 代わりにレベルへ問うのが罠。サーバー側呼び出しでは {@code Level#getHeight} がまだ無い物を生成するし、ここ
     * では、気付くべきまさにその事実——ここには問い合わせる物が無いということ——を隠してしまう。
     */
    public static double surface(Level level, Vec3 at) {
        int x = Mth.floor(at.x);
        int z = Mth.floor(at.z);
        ChunkAccess chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, false);

        if (chunk == null) {
            return Double.NaN;
        }

        return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1.0;
    }

    /** ある点から次の点へのステップが、指定した高さを通過する位置。 */
    public static Vec3 crossing(Vec3 from, Vec3 to, double height) {
        double fall = from.y - to.y;

        if (fall <= 1.0E-6) {
            return to;
        }

        return from.add(to.subtract(from).scale(Mth.clamp((from.y - height) / fall, 0.0, 1.0)));
    }
}
