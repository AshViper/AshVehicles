package com.ashvehicles.entity;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * 機体に乗っているプレイヤーへ chunk を送る順番を決める。進行方向の先から、後ろは最後に。
 *
 * <p>サーバーは1人のプレイヤーへ送る chunk を毎tick数個ずつに絞り、残りは待ち行列に置く
 * （{@code PlayerChunkSender}）。その待ち行列から次に送る物を選ぶ物差しは「プレイヤーの chunk からの
 * 距離」で、牛に乗っている間はそれで正しい。機体に乗っている間は正しくない。時速700kmの機体にとって
 * 真横の chunk は1秒後には後ろの chunk で、送っても描かれる前に捨てられる。一方で前方の縁——描画距離
 * いっぱいの、視界の端に現れるべき地面——は物差しの上で一番遠い物、つまり一番最後に送られる物だ。
 * 詰まるほど、パイロットの前だけが空になる。
 *
 * <p>だから物差しの原点を進行方向の先へ動かす。原点はこの先 {@link #LEAD_TICKS} tick で着く地点、ただし
 * 描画距離の縁まで。原点から近い順に送るのは同じなので、前方の縁が先頭になり、機体の真横がその次、
 * 後ろが最後になる。待ち行列が空なら順番は意味を持たず、何も変わらない。
 *
 * <p>ここで決めるのは<em>順番だけ</em>だ。どの chunk を送るか（描画距離の四角）も、いつ送るか（クライアント
 * が申告する処理速度）も触らない。前方の chunk がサーバーにまだ無ければ、順番が何であろうと送れない——
 * そちらは {@link AircraftChunkLoader} の先読みが生成器へ頼んでいる仕事で、ここはその結果が届く順番を
 * 飛行に合わせるだけ。
 *
 * <p>ゆっくり動いている間は素の位置を返す。ヘリのホバリングや駐機中の機体で、前と後ろに差を付ける理由は
 * 無い。
 */
public final class FlightChunkOrder {
    /** 原点を置く先。この tick 数ぶん先の地点で、描画距離の縁で頭打ち。 */
    private static final double LEAD_TICKS = 40.0;
    /**
     * 生成と送信の中心を進行方向へずらす chunk 数。
     *
     * <p>10 は要求された値。実際にずれる量は視界距離で頭打ちになる（{@link #lead} 参照）。
     */
    private static final int LEAD_CHUNKS = 10;
    /** 四角の縁とプレイヤーの間に必ず残す chunk 数。ずらし過ぎて自分が外へ出ないための余白。 */
    private static final int EDGE_MARGIN = 4;
    /** これより遅い（ブロック/tick）間は、前後の区別をしない。 */
    private static final double CRAWL = 0.5;

    /**
     * このプレイヤーへ送る chunk を並べる原点。
     *
     * <p>{@code PlayerChunkSender.sendNextChunks} が {@code player.chunkPosition()} を呼ぶ場所で、代わりに
     * これが呼ばれる。機体に乗っていなければ、あるいは機体が止まっていれば、答えはバニラと同じ物。
     */
    public static ChunkPos center(ServerPlayer player) {
        if (!(player.getRootVehicle() instanceof VehicleEntityBase machine)) {
            return player.chunkPosition();
        }

        // 操縦中の機体の速度をサーバーが測ることはできない（位置はパケットで届く）ので、機体が自分で
        // 申告している値を使う。AircraftEntity.getVelocity 参照。
        Vec3 velocity = machine.getVelocity();
        double speed = velocity.horizontalDistance();

        if (!(speed > CRAWL)) {
            return player.chunkPosition();
        }

        // 原点が四角の外に出ても順番としては成り立つ（前方の縁から順になる）が、外へ出す理由も無い。
        // 描画距離の縁に置けば、縁の chunk が先頭で、そこから機体へ向かって順に並ぶ。
        int view = player.getChunkTrackingView() instanceof ChunkTrackingView.Positioned square
                ? square.viewDistance()
                : player.requestedViewDistance();
        double reach = Math.min(speed * LEAD_TICKS, view * 16.0);
        double x = player.getX() + velocity.x / speed * reach;
        double z = player.getZ() + velocity.z / speed * reach;

        return new ChunkPos(SectionPos.blockToSectionCoord(Mth.floor(x)),
                SectionPos.blockToSectionCoord(Mth.floor(z)));
    }


    /**
     * 機体の進行方向へ何 chunk ずらした所を「プレイヤーの居場所」として扱うか。
     *
     * <p>0 ならバニラのまま。飛んでいない、止まっている、乗っていない——どれでも0を返す。
     *
     * <p><b>視界距離で頭打ちにする。</b> ここでずらすのはプレイヤーを中心にした四角そのものなので、
     * ずらし過ぎると<em>プレイヤー自身が四角の外に出る</em>。出た瞬間、足元の chunk は生成対象でも
     * 送信対象でもなくなる——地面が前方だけ在って、真下に無い状態だ。だから四角の縁までに余裕を4 chunk
     * 残す。視界距離12なら8、16なら10（要求どおり）になる。
     */
    public static int lead(ServerPlayer player, int viewDistance) {
        if (!(player.getRootVehicle() instanceof VehicleEntityBase machine)) {
            return 0;
        }

        if (machine.getVelocity().horizontalDistance() <= CRAWL) {
            return 0;
        }

        return Math.max(0, Math.min(LEAD_CHUNKS, viewDistance - EDGE_MARGIN));
    }

    /**
     * その先取りを当てはめた chunk 座標。ずらさないなら本人の chunk をそのまま返す。
     *
     * <p>ここが動かすのは「サーバーがどの chunk を生成し、どの chunk を送るか」の中心だ。前へ動かせば
     * 機体が着く前に地面の要求が始まり、後ろの地面は早く捨てられる。要求の総量は1個も変わらない——
     * 四角の大きさは視界距離のままで、置く場所だけが変わる。
     */
    public static ChunkPos ahead(ServerPlayer player, int viewDistance) {
        int lead = lead(player, viewDistance);

        if (lead <= 0) {
            return player.chunkPosition();
        }

        Vec3 velocity = ((VehicleEntityBase) player.getRootVehicle()).getVelocity();
        double flat = velocity.horizontalDistance();
        ChunkPos own = player.chunkPosition();

        return new ChunkPos(own.x + (int) Math.round(velocity.x / flat * lead),
                own.z + (int) Math.round(velocity.z / flat * lead));
    }

    private FlightChunkOrder() {
    }
}
