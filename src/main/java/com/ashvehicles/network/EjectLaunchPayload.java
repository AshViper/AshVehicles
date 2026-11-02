package com.ashvehicles.network;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.EjectionSeat;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 座席のロケットが燃える向きと強さを、射出された本人へ。
 *
 * <p>プレイヤーの移動はクライアントが回すので、燃焼の間の推力はクライアントが足す。サーバーが毎tick速度を
 * 送り付けると、送るたびにクライアントの重力と抗力を巻き戻してしまう。{@link EjectionSeat} 参照。
 */
public record EjectLaunchPayload(double x, double y, double z, float speed) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<EjectLaunchPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "eject_launch"));

    public static final StreamCodec<FriendlyByteBuf, EjectLaunchPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeDouble(payload.x);
                buffer.writeDouble(payload.y);
                buffer.writeDouble(payload.z);
                buffer.writeFloat(payload.speed);
            },
            buffer -> new EjectLaunchPayload(buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readFloat()));

    public EjectLaunchPayload(Vec3 up, float speed) {
        this(up.x, up.y, up.z, speed);
    }

    @Override
    public CustomPacketPayload.Type<EjectLaunchPayload> type() {
        return TYPE;
    }

    public static void handle(EjectLaunchPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> EjectionSeat.launched(context.player(),
                new Vec3(payload.x, payload.y, payload.z), payload.speed));
    }
}
