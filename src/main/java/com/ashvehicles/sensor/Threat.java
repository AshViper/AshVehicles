package com.ashvehicles.sensor;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import io.netty.buffer.ByteBuf;

/**
 * この機体に良からぬ関心を向けている相手と、その度合い。
 *
 * <p>警戒受信機は「見る」のではなく「聞く」装置なので距離は無い。どちらから来ているかと、どれくらい
 * まずいかだけ。受信機が正直に言えるのはそこまでで、パイロットが読む時間があるのもそこまで。
 *
 * @param bearing 機首からの角度（度）。右が正
 * @param kind 関心がどの段階まで進んでいるか
 */
public record Threat(float bearing, Kind kind) {
    public static final StreamCodec<ByteBuf, Threat> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, Threat::bearing,
            ByteBufCodecs.idMapper(Kind::byId, Kind::ordinal), Threat::kind,
            Threat::new);

    /** 知らせる価値のある3段階を、普通に起きる順で。 */
    public enum Kind {
        /** 相手のレーダーのスコープに載った。存在を知られている。 */
        SEARCH,
        /** 相手のシーカーに捉えられた。次に起きるのは発射。 */
        LOCK,
        /** 何かが飛んでいて、こちらへ向かっている。 */
        MISSILE;

        private static final Kind[] BY_ID = values();

        public static Kind byId(int id) {
            return BY_ID[Math.floorMod(id, BY_ID.length)];
        }

        /** こちらが相手より深刻か。1機につき1件の脅威で代表させるために使う。 */
        public boolean worseThan(Kind other) {
            return this.ordinal() > other.ordinal();
        }
    }
}
