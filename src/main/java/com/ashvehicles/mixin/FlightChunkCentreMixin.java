package com.ashvehicles.mixin;

import com.ashvehicles.entity.FlightChunkOrder;

import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 飛んでいる間、サーバーが「そのプレイヤーはここに居る」と見なす場所を、進行方向へ数 chunk 進める。
 *
 * <p><b>生成の中心を動かす。</b> サーバーが chunk を生成し、送り、tick させる範囲は、どれもプレイヤーの
 * chunk を中心にした四角だ。中心はプレイヤーの実座標——牛に乗っている人には正しい。時速450kmの機体では、
 * その四角は常に機体の周りに置かれ、前縁は「今まさに必要になる地面」として毎秒何十個も新しく現れる。
 * 生成器はその前縁に追い立てられ、いつも遅れる。
 *
 * <p>中心を前へ置けば、同じ四角が機体より先に着く。前方の地面は機体が着く前から要求され、生成器は
 * その分だけ早く始められる。<b>要求の総量は1個も増えない</b>——四角の大きさは視界距離のままで、変わる
 * のは置き場所だけだ。後ろの地面はその分だけ早く捨てられる。
 *
 * <p><b>4か所を揃えて動かす。</b> 中心はバニラの中で4回読まれ、しかも互いに一致していることを前提に
 * している。{@code move} は前回の場所と今回の場所を比べて差があった時だけチケットを付け替えるので、
 * 記録側（{@code updatePlayerPos}）だけ素の位置のままにすると、毎tick「動いた」と判定され続けて
 * チケットの付け外しが止まらなくなる。だから読む所は全部同じ答えを返す。
 *
 * <p><b>ずらし過ぎないこと。</b> 動かしているのはプレイヤーを囲む四角なので、視界距離より遠くへ動かせば
 * プレイヤー自身が四角の外へ出る。そうなると足元の chunk が生成対象でも送信対象でもなくなり、前方だけに
 * 地面がある状態になる。上限は {@code FlightChunkOrder.lead} が視界距離から決める。
 */
@Mixin(ChunkMap.class)
public abstract class FlightChunkCentreMixin {
    @Shadow
    abstract int getPlayerViewDistance(ServerPlayer player);

    /**
     * チケットの中心。{@code updatePlayerStatus}、{@code updatePlayerPos}、{@code move} の3か所が
     * これを読み、その3つが一致している必要がある。
     *
     * <p>高さはそのまま。動かすのは水平の2軸だけで、上下に四角は無い。
     */
    @Redirect(method = {"updatePlayerStatus", "updatePlayerPos", "move"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/core/SectionPos;"
                            + "of(Lnet/minecraft/world/level/entity/EntityAccess;)"
                            + "Lnet/minecraft/core/SectionPos;"))
    private SectionPos ashvehicles$loadWhereWeAreGoing(EntityAccess entity) {
        if (!(entity instanceof ServerPlayer player)) {
            return SectionPos.of(entity);
        }

        ChunkPos ahead = FlightChunkOrder.ahead(player, this.getPlayerViewDistance(player));

        return SectionPos.of(ahead, SectionPos.blockToSectionCoord(Mth.floor(player.getY())));
    }

    /** 送信の四角の中心。上と同じ答えを返さないと、生成する場所と送る場所がずれる。 */
    @Redirect(method = "updateChunkTracking",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;chunkPosition()"
                            + "Lnet/minecraft/world/level/ChunkPos;"))
    private ChunkPos ashvehicles$trackWhereWeAreGoing(ServerPlayer player) {
        return FlightChunkOrder.ahead(player, this.getPlayerViewDistance(player));
    }
}
