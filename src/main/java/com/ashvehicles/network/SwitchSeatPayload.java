package com.ashvehicles.network;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.VehicleEntityBase;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 乗員が、乗っている機体の次の席へ移りたいという要求。
 *
 * <p>{@link OpenVehicleHoldPayload} と同じく何も名指ししない。機体は送信者が乗っている物なので、要求が
 * マップの向こうの他人の機体に届くことはない。次がどの席かはサーバーが決める——席割りはサーバーの持ち物
 * で、クライアントは知らされるまで誰がどこにいるかを正しく把握できない。移動処理自体は
 * {@link VehicleEntityBase#switchToNextSeat}。
 */
public record SwitchSeatPayload() implements CustomPacketPayload {
    public static final SwitchSeatPayload INSTANCE = new SwitchSeatPayload();

    public static final CustomPacketPayload.Type<SwitchSeatPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "switch_seat"));

    /** 通信内容は空。押されたという事実がメッセージの全て。 */
    public static final StreamCodec<FriendlyByteBuf, SwitchSeatPayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<SwitchSeatPayload> type() {
        return TYPE;
    }

    public static void handle(SwitchSeatPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            // 搭乗者の席は「直接乗っている機体」の持ち物で、その機体がさらに何かに乗っていてもそちら
            // には属さない。よって直接の乗り物に訊く。
            if (context.player().getVehicle() instanceof VehicleEntityBase machine) {
                machine.switchToNextSeat(context.player());
            }
        });
    }
}
