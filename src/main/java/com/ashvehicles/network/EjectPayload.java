package com.ashvehicles.network;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.EjectionSeat;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 射出座席のハンドルを引いたという報せ。
 *
 * <p>{@link SwitchSeatPayload} と同じく何も名指ししない。飛び出すのは送信者自身で、乗っている機体は
 * サーバーが知っている。他人を機外へ撃ち出す言い方がそもそも無い。
 *
 * <p>操縦入力（{@link AircraftInputPayload}）に相乗りしないのは、あれが操縦者だけの物だからだ。
 * 後席の乗員にもハンドルはある。
 */
public record EjectPayload() implements CustomPacketPayload {
    public static final EjectPayload INSTANCE = new EjectPayload();

    public static final CustomPacketPayload.Type<EjectPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "eject"));

    /** 通信内容は空。引かれたという事実がメッセージの全て。 */
    public static final StreamCodec<FriendlyByteBuf, EjectPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<EjectPayload> type() {
        return TYPE;
    }

    public static void handle(EjectPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player().getVehicle() instanceof AircraftEntity aircraft) {
                EjectionSeat.pull(aircraft, context.player());
            }
        });
    }
}
