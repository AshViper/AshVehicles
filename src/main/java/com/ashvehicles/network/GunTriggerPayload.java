package com.ashvehicles.network;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.AircraftEntity;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 砲手が引き金を引いた、あるいは離したという報告。
 *
 * <p>操縦している者の引き金は操縦入力の一部として既に届く（{@link AircraftInputPayload} 参照）。届かない
 * のは操縦していない乗員の引き金で、これはそのためだけの1ビットだ。押している間の状態を送るのであって発砲
 * 1回を送るのではない。
 *
 * <p>他のペイロードと同じくエンティティも砲座も名指ししない。サーバーは送信者が乗っている機体の、送信者が
 * 座っている席が持つ砲を撃つ——誰がどの砲を持っているかはサーバーが決めることであり、要求できるのは
 * 「引いた」だけだ。{@link com.ashvehicles.weapon.GunStations} 参照。
 */
public record GunTriggerPayload(boolean pressed) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<GunTriggerPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "gun_trigger"));

    public static final StreamCodec<FriendlyByteBuf, GunTriggerPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeBoolean(payload.pressed()),
            buf -> new GunTriggerPayload(buf.readBoolean()));

    @Override
    public CustomPacketPayload.Type<GunTriggerPayload> type() {
        return TYPE;
    }

    public static void handle(GunTriggerPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player().getVehicle() instanceof AircraftEntity aircraft) {
                aircraft.getStations().setTrigger(context.player(), payload.pressed());
            }
        });
    }
}
