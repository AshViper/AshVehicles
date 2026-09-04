package com.ashvehicles.entity;

import com.ashvehicles.AshVehicles;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 「まだ無い地面は空」と答えてよい窓。機体の tick と、操縦報告の処理の間だけ開く。
 *
 * <p>サーバーで chunk を訊く方法は、無ければ<em>作る</em>か（{@code Level.getChunk}、ブロックや流体の
 * 読み取り全部がこれ）、ホルダーがあれば<em>待つ</em>か（{@code getChunkForCollisions} の「ロードしない」
 * 指定でも、先読みチケットでホルダーが立っていれば生成が終わるまで待つ）のどちらかで、どちらも tick
 * スレッドを止める。地上を歩く物にはそれで正しい。足元の地面が届くまで待つ以外に、正しい答えが無いから
 * だ。空を時速数百 km で飛ぶ物には正しくない。1tick に1〜2個の新しい chunk を跨ぐ機体は、跨ぐたびに
 * サーバーの時計を止め、止まった分だけ操縦報告が溜まり、溜まった報告が処理される頃には機体はもう別の
 * 場所にいる——さっきのログの「45 tick 遅れ」と「moved wrongly」の連打がそれだ。
 *
 * <p>だから機体については、chunk が今そこに無いなら空として答える。{@link com.ashvehicles.mixin.LateWorldMixin}
 * が {@code ServerChunkCache.getChunk} の入口でこの窓を見て、無い chunk には空の chunk（読めば全部
 * {@code VOID_AIR}）を返す。生成は今まで通り先読みとプレイヤー自身のチケットが別スレッドで進めるので、
 * 地面は数 tick 遅れて届く。届くまでの間の扱いは飛行モデルが既に持っている
 * （{@code AircraftEntity.beyondTheWorld} と {@code flyOnThroughLateWorld}）。
 *
 * <p>窓は2つ。{@code ServerLevel.tickNonPassenger} が機体を tick している間（乗員の tick も、機体が
 * 最後に呼ぶ回廊の確保も、この中に入る）と、{@code handleMoveVehicle} が操縦報告を適用している間。
 * どちらもサーバースレッド専用で、この旗もそこでしか触らない。
 *
 * <p>弾はこの窓を使わない。弾は最初から世界に訊かない規律で書かれている（{@code VehicleProjectile}）。
 *
 * <p>安全弁として毎 tick の頭で閉じる。閉じ忘れた旗は、その後の全員の足元を空にする——地面が遅れて
 * 届いた chunk へ歩いて入ったプレイヤーが床を抜ける——ので、開けっ放しは1tick で終わらせる。
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class LateWorld {
    private static boolean inside;

    /** 今、無い地面を空と答えてよいか。サーバースレッドから読むこと。 */
    public static boolean inside() {
        return inside;
    }

    /**
     * 窓を開ける。
     *
     * @return 開ける前の状態。{@link #restore} へ返す
     */
    public static boolean enter() {
        boolean was = inside;
        inside = true;

        return was;
    }

    /** 窓を閉じる。 */
    public static void leave() {
        inside = false;
    }

    /** {@link #enter} が返した状態へ戻す。入れ子で開いた側が、外側の窓を閉じてしまわないために。 */
    public static void restore(boolean was) {
        inside = was;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Pre event) {
        inside = false;
    }

    private LateWorld() {
    }
}
