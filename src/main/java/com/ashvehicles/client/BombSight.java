package com.ashvehicles.client;

import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.weapon.WeaponDefinition;
import com.ashvehicles.weapon.WeaponMounts;

import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 今この瞬間に投下した爆弾がどこへ落ちるか。
 *
 * <p>自由落下爆弾は何にも照準できない。機体の速度をそのまま持って離れ、あとは重力に掴まれるので、落着点は投下の
 * 瞬間の速度・高度・水平度で決まっている——そしてパイロットが答えを見られる頃には、変える手立ては遥かに手遅れだ。
 * だから爆弾を積んだ機体には常に、どこへ落ちるかをパイロットへ伝える物があった。これがそれだ。弾道をtickごとに
 * 前進させ、世界にぶつかるまで追う。
 *
 * <p>飛翔は爆弾自身が使うのと同じ計算——先に位置、次に重力、この順——で求めるので、パイロットが見せられるのは
 * 爆弾が実際にやることであって、端でずれていく近似ではない。
 *
 * <p><b>爆弾より先に世界が尽きる。</b>爆撃に値するどの高度からでも、ジェット機は積荷を数百ブロック前方へ投げる
 * ——クライアントがチャンクを持つ範囲より前方へ。その外では全ブロックが空気と読まれるので、ブロックだけを問う
 * トレースは何も見つけず、見えない地面をすり抜け、答え無しで戻ってくる。パイロットが必要とするほど高く上がった
 * まさにその瞬間に、マークが画面から消えていたわけだ。そこでロード範囲の外では、仮定した床——クライアントが最後に
 * 知っていた列の地面高で、ここにいる誰もが目標について言える最も近い値——に対して落下を追う。その答えは見た物では
 * なく算出した物なので、そうと印を付けて返し、計器がありのままに描けるようにする。各地点の床が何かは
 * {@link Terrain} の管轄だ。ターゲティングポッドがまったく同じ問題を抱えており、まったく同じ答えを持つべきだから。
 *
 * <p>毎フレームではなく毎tick算出する。世界を1歩ずつ歩くコストがかかるし、答えは1/60秒で意味のある変化はしない。
 */
public final class BombSight {
    /**
     * 追跡する価値のある最長飛翔時間（tick）。この種の機体が上れるどの高度からでも地面に届く長さ——爆弾は約300
     * tickで1000ブロック落ちる——であり、末尾は安い。ロード範囲外では1tickあたりの処理がブロック走査ではなく
     * 算術とチャンク検索だけになるからだ。
     */
    private static final int MAX_FLIGHT = 1200;

    private static AircraftEntity cachedFor;
    private static long cachedAt = Long.MIN_VALUE;
    @Nullable
    private static Solution cachedSolution;

    private BombSight() {
    }

    /**
     * 爆弾の落着点と、その値の信頼度。
     *
     * @param point 落着位置
     * @param estimated 落下がクライアントに実際に見えるブロックではなく仮定した床で終わったか。true なら目標は
     *                  ロード範囲の外にあり、そこの地面高は推測値だ。平坦な土地の上では正しく、ここからそこまでの
     *                  高低差の分だけ誤る
     */
    public record Solution(Vec3 point, boolean estimated) {
    }

    /**
     * 今投下した爆弾の着弾点。諦めるまでにどこにも着かなければ null——奈落の上か、{@link #MAX_FLIGHT} を過ぎても
     * 落下中の場合。
     */
    @Nullable
    public static Solution solve(AircraftEntity aircraft, WeaponDefinition weapon) {
        long now = aircraft.level().getGameTime();

        if (aircraft != cachedFor || now != cachedAt) {
            cachedFor = aircraft;
            cachedAt = now;
            cachedSolution = trace(aircraft, weapon);
        }

        return cachedSolution;
    }

    @Nullable
    private static Solution trace(AircraftEntity aircraft, WeaponDefinition weapon) {
        WeaponDefinition.Projectile round = weapon.projectile();
        Level level = aircraft.level();
        Vec3 up = aircraft.getLiftVector();

        // 機体が実際に行うのと同じ投下。ラックから、機体の速度で、自身の値の分だけ胴体下方へ押し出す。
        Vec3 position = aircraft.toWorld(rackOffset(aircraft, weapon), 1.0F);
        Vec3 velocity = aircraft.getVelocity().add(up.scale(-round.speed()));
        int flight = Math.min(MAX_FLIGHT, round.lifetime());

        // チャンクが尽きた後に地面と見なす高さ。実地形の最初の列——機体自身が上にいる列——を読むまでは海面高。
        double floor = level.getSeaLevel();

        for (int tick = 0; tick < flight; tick++) {
            Vec3 next = position.add(velocity);
            double ground = Terrain.surface(level, next);

            if (!Double.isNaN(ground)) {
                // クライアントが持つ地面。ブロック自体に対してトレースする。それが本当の答えであり、斜面や屋根
                // を知っている唯一の答えだ。
                HitResult hit = level.clip(new ClipContext(position, next,
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, aircraft));

                if (hit.getType() != HitResult.Type.MISS) {
                    return new Solution(hit.getLocation(), false);
                }

                floor = ground;
            } else if (next.y <= floor) {
                // ロード範囲の外。全ブロックが空気と読まれる。爆弾は地面が最後に立っていた高さに達したので、
                // ここが落着点になる。
                return new Solution(Terrain.crossing(position, next, floor), true);
            }

            position = next;
            velocity = velocity.subtract(0.0, round.gravity(), 0.0);
        }

        return null;
    }

    /**
     * 爆弾が機体のどこから離れるか。この兵装を積む最初のラックの最初の位置。おかげでマークは機体中央からではなく
     * 積荷と共に動く。
     */
    private static Vec3 rackOffset(AircraftEntity aircraft, WeaponDefinition weapon) {
        WeaponMounts weapons = aircraft.getWeapons();
        List<WeaponMounts.Mount> mounts = weapons.mounts();

        for (int slot = 0; slot < mounts.size(); slot++) {
            List<WeaponMounts.Load> loads = mounts.get(slot).loads();

            for (int place = 0; place < loads.size(); place++) {
                WeaponMounts.Load load = loads.get(place);

                if (!load.isEmpty() && load.ammo() > 0
                        && weapon.equals(Definitions.weapon(load.weapon()))) {
                    return weapons.placeOf(slot, place);
                }
            }
        }

        return Vec3.ZERO;
    }
}
