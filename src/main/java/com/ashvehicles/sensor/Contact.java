package com.ashvehicles.sensor;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import io.netty.buffer.ByteBuf;

/**
 * レーダーが見つけた1件を、世界が持っている形ではなくスコープが必要とする形で表したもの。
 *
 * <p>中身は全部サーバー側で計算し、エンティティ参照ではなく数値として送る。これは意図的で、レーダーは
 * 数百ブロック届くのに対し地上のプレイヤーが他クライアントへ送られる距離はずっと短い。つまりスコープ上
 * の半分は、クライアントが存在すら知らされておらず自力では測れない相手になる。方位と距離なら描ける。
 *
 * <p>{@link #iff} も同じ理由でここに乗る。判定自体はクライアントでも出せる——チーム所属は同期される
 * ——が、そのためには相手のエンティティが手元に無ければならず、スコープの半分はそうではない。
 *
 * @param id エンティティ ID。シーカーが捉えている相手との照合用で、参照はしない
 * @param bearing 機首からの角度（度）。右が正
 * @param range 距離（ブロック）
 * @param altitude 自機より何ブロック高いか。下なら負
 * @param locked これがシーカーの捉えている相手か
 * @param aircraft 徒歩の相手ではなく航空機か
 * @param iff 味方か、敵か、判定が付かないか
 */
public record Contact(int id, float bearing, float range, float altitude, boolean locked, boolean aircraft,
        Iff iff) {
    /**
     * 手書きなのはフィールドが7つあるから。{@code StreamCodec.composite} は6つまでしか取らない。
     */
    public static final StreamCodec<ByteBuf, Contact> STREAM_CODEC =
            StreamCodec.of(Contact::write, Contact::read);

    private static void write(ByteBuf buf, Contact contact) {
        ByteBufCodecs.VAR_INT.encode(buf, contact.id());
        buf.writeFloat(contact.bearing());
        buf.writeFloat(contact.range());
        buf.writeFloat(contact.altitude());
        buf.writeBoolean(contact.locked());
        buf.writeBoolean(contact.aircraft());
        Iff.STREAM_CODEC.encode(buf, contact.iff());
    }

    private static Contact read(ByteBuf buf) {
        return new Contact(
                ByteBufCodecs.VAR_INT.decode(buf),
                buf.readFloat(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readBoolean(),
                buf.readBoolean(),
                Iff.STREAM_CODEC.decode(buf));
    }
}
