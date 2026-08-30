package com.ashvehicles.network;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.VehicleEntityBase;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 乗員が、乗っている機体の弾庫を開く操作。
 *
 * <p>キー押下で送られ、何も名指ししない。機体は送信者が乗っている物で、{@link AircraftInputPayload} が
 * 入力の持ち主を判別するのと同じ仕組み。だからマップの反対側にある他人の機体を狙えない。そもそもメニュー
 * を開くのはサーバーの仕事で、コンテナの中身についてクライアントに発言権は無いし、与えてもいけない。
 *
 * <p>機体の<em>外</em>に立っている者はここの対象外。そちらはしゃがみ＋右クリックで機体自体を指定して
 * 開く。エプロンからのキー押下では、並んだ機体のどれを指しているか推測するしかなくなる。
 */
public record OpenVehicleHoldPayload() implements CustomPacketPayload {
    public static final OpenVehicleHoldPayload INSTANCE = new OpenVehicleHoldPayload();

    public static final CustomPacketPayload.Type<OpenVehicleHoldPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "open_vehicle_hold"));

    /** 通信内容は空。押されたという事実がメッセージの全て。 */
    public static final StreamCodec<FriendlyByteBuf, OpenVehicleHoldPayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<OpenVehicleHoldPayload> type() {
        return TYPE;
    }

    public static void handle(OpenVehicleHoldPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            // どの席でもよく、距離判定も一切しない。機体に座っている者は形状が何と言おうと機体の中に
            // おり、400ノットで飛ぶ機体は誰かの手の届く距離を測る基準として不適切。
            if (context.player().getRootVehicle() instanceof VehicleEntityBase machine) {
                machine.openHold(context.player());
            }
        });
    }
}
