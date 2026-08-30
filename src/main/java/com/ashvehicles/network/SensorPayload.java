package com.ashvehicles.network;

import java.util.List;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.RadarReadout;
import com.ashvehicles.sensor.Contact;
import com.ashvehicles.sensor.Threat;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * レーダー1掃引分と警戒受信機1回分の読み取り結果を、パイロットへ。
 *
 * <p>機体を追跡している全員ではなく、その1人にだけ送る。レーダー画面はコックピット内の計器で、外にいる
 * 者に中身は関係ないし、スコープを見られない相手へ送るには量が多い。
 *
 * <p>中身は全部数値（方位・距離・高度差）で届き、参照すべきエンティティとしては届かない。そうするしか
 * ない。レーダーは数百ブロック届き、そこで見つかる物の大半はこのクライアントへエンティティとして送られ
 * てすらいない。{@link com.ashvehicles.sensor.Sensors} 参照。
 */
public record SensorPayload(List<Contact> contacts, List<Threat> threats) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SensorPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "sensors"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SensorPayload> STREAM_CODEC = StreamCodec.composite(
            Contact.STREAM_CODEC.apply(ByteBufCodecs.list()), SensorPayload::contacts,
            Threat.STREAM_CODEC.apply(ByteBufCodecs.list()), SensorPayload::threats,
            SensorPayload::new);

    @Override
    public CustomPacketPayload.Type<SensorPayload> type() {
        return TYPE;
    }

    /**
     * クライアント向けとしてのみ登録されているので、これはクライアントでしか走らない。専用サーバーが
     * {@link RadarReadout} を解決することはない。
     */
    public static void handle(SensorPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> RadarReadout.accept(payload.contacts(), payload.threats()));
    }
}
