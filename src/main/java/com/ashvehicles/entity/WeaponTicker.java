package com.ashvehicles.entity;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.ashvehicles.AshVehicles;

import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.TickRateManager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * ロード済みの世界の外へ出た弾に、次の tick を渡す。地面は1 chunk も開かない。
 *
 * <p>{@code ServerLevel} は乗り物に乗っていない全エンティティを {@code inEntityTickingRange} で門前払い
 * する。{@code isAlwaysTicking} が通すのはその手前までだ——エンティティは世界に残り、問い合わせにも追跡にも
 * 応じるが、tick そのものはこの1つの判定で止まる。機体が持っているのは自分が飛ぶ回廊だけなので、そこから
 * 撃った物はレールを離れて1〜2tickでその外へ出る。何もしなければ、高高度で撃ったミサイルは境界を跨いだ次の
 * tick に空中で永久に凍り付く。
 *
 * <p><b>ここに以前あった物。</b> 弾が自分の下と前方の chunk をチケットで確保する仕組み（{@code
 * WeaponChunkLoader}）だった。あれは tick を買うと同時に<em>地面</em>も買っていた。{@code forceChunk} も
 * region チケットも、指した先に地形が無ければその場で作らせるからだ。空を横切るミサイル1発が、誰も訪れた
 * ことのない土地に自分専用の回廊を1本生成して置き去りにする。セーブは撃つたびに膨らみ、その代金で買って
 * いたのは二度と誰も見ない地形だった。だから確保も先読みも捨てた。ここが運ぶのは tick だけで、chunk は1つ
 * も要求しない。
 *
 * <p><b>当たるのはロード済みの地面の上だけになった。</b> 誰かが既に開いている chunk——プレイヤーの周り、
 * 機体の回廊、ドローンの輪——の上では、弾は今まで通りブロックへ問い合わせて当たる。その外では地形へ一切
 * 問い合わせない（{@link VehicleProjectile#tick} と {@code spanIsLoaded} 参照。問い合わせること自体が
 * tick スレッド上でのワールド生成だ）ので、そこにあるかもしれない斜面はすり抜け、弾は寿命まで飛んでから
 * 消える。エンティティはどちらでも当たる。長距離射撃が狙っている機体は、下の地面がどうであろうとロード
 * されているから。
 *
 * <p>バニラのループの<em>後</em>に、そこが飛ばさなかった分だけを拾う。判定に使うのはバニラと同じ
 * {@code inEntityTickingRange} なので、二重に tick される弾は無い。
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class WeaponTicker {
    /** 反復用の空配列。下の {@code toArray} の型を決めるためだけの物。 */
    private static final VehicleProjectile[] NONE = new VehicleProjectile[0];

    /**
     * 各ワールドで今飛んでいる弾。サーバースレッド専用で、弾の tick も下のワールド tick もそこで走る。
     *
     * <p>弱参照ではなく、ワールドのアンロードで明示的に捨てる。値（弾）が鍵（ワールド）を強く指しているので
     * {@code WeakHashMap} では決して空にならない。
     */
    private static final Map<ServerLevel, Set<VehicleProjectile>> FLYING = new HashMap<>();

    /**
     * この弾が空にいることを記録する。弾自身の tick から毎tick呼ぶ——安い（集合への追加1回）し、これで
     * 「ディスクから戻ってきた弾」まで含めて、tick している弾は全部ここに載る。
     *
     * <p>載せるのはバニラがまだ tick を運んでいるうちだ。ロード済みの世界を出た弾はもう自分では呼べない。
     */
    public static void flying(VehicleProjectile shot) {
        if (shot.level() instanceof ServerLevel level) {
            FLYING.computeIfAbsent(level, ignored -> new LinkedHashSet<>()).add(shot);
        }
    }

    /**
     * 弾を帳簿から外す。炸裂しようと寿命切れだろうと、世界から外れた時に呼ぶ。ここは chunk システム自身の
     * 更新ループの中からも飛ぶが、行うのは集合からの削除だけなので、どこから呼んでも安全。
     */
    public static void landed(VehicleProjectile shot) {
        if (shot.level() instanceof ServerLevel level) {
            Set<VehicleProjectile> flying = FLYING.get(level);

            if (flying != null) {
                flying.remove(shot);
            }
        }
    }

    /**
     * バニラのエンティティループが飛ばした弾を tick する。
     *
     * <p>ワールド tick の後に走るので、ロード済みの世界の中にいる弾はこの時点で既にこの tick を終えている。
     * ここが動かすのはその外に出た弾だけで、判定はバニラのループと同じ物——{@code inEntityTickingRange}
     * ——を同じ引数で引く。
     */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        Set<VehicleProjectile> flying = FLYING.get(level);

        if (flying == null || flying.isEmpty()) {
            return;
        }

        TickRateManager rates = level.tickRateManager();

        // ワールドが止まっている間は弾も止まる。バニラのループも同じ判定の後ろにある。
        if (!rates.runsNormally()) {
            return;
        }

        DistanceManager distance = level.getChunkSource().chunkMap.getDistanceManager();

        // 複製に対して回す。この中の tick は弾を消し、新しい弾を生み（クラスター弾）、この集合に触れる。
        for (VehicleProjectile shot : flying.toArray(NONE)) {
            if (shot.isRemoved()) {
                // 世界から外れた通知が来る前に消えた弾。次の tick を渡す相手はもういない。
                flying.remove(shot);

                continue;
            }

            if (rates.isEntityFrozen(shot) || distance.inEntityTickingRange(shot.chunkPosition().toLong())) {
                continue;
            }

            // バニラがこの弾に対して呼ぶはずだった物を、バニラが呼ぶ形のまま呼ぶ。落ちた時にクラッシュ
            // レポートへ弾の素性が載るのは guardEntityTick の仕事。
            level.guardEntityTick(level::tickNonPassenger, shot);
        }
    }

    /** アンロードされたワールドの帳簿を捨てる。中の弾はそのワールドと一緒に消えている。 */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            FLYING.remove(level);
        }
    }

    private WeaponTicker() {
    }
}
