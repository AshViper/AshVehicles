package com.ashvehicles.network;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.GroundVehicleEntity.Crank;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 砲の脇に立っている者が、今その砲に何をさせているか。どちらのハンドルをどちら向きに回し、引き金を引いて
 * いるか。
 *
 * <p><b>他の入力と違い、砲を名指しする。</b> 乗員の入力は「送信者が乗っている物」で宛先が決まる
 * （{@link GroundVehicleInputPayload} 参照）が、この操作者は乗っていない。立っている場所の周りに砲が2門
 * あることは十分あり得るので、どちらのハンドルを掴んだかは送る側にしか分からない。サーバーは受け取った
 * 番号の物が本当に人力操作の砲で、送信者がその手の届く所に立っているかを確かめる。
 *
 * <p><b>視線は送らない。</b> ハンドルが要求できるのは回す向きだけで、どこを見ているかは砲に関係が無い。
 * 乗員の入力が姿勢や砲塔角まで運ぶのは、あちらが「据える先」を要求できるからだ。
 *
 * <p>押している間、毎tick送られる。手を離した1tickぶんも向き 0 として届き、サーバーはそれで掴んだ状態を
 * 解く。届かなくなった場合も同じ後始末が数tick後に走る。
 */
public record GunCrewPayload(int vehicle, int handle, int direction, boolean trigger)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<GunCrewPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "gun_crew"));

    public static final StreamCodec<FriendlyByteBuf, GunCrewPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeVarInt(payload.vehicle());
                buf.writeByte(payload.handle());
                buf.writeByte(payload.direction());
                buf.writeBoolean(payload.trigger());
            },
            buf -> new GunCrewPayload(buf.readVarInt(), buf.readByte(), buf.readByte(),
                    buf.readBoolean()));

    @Override
    public CustomPacketPayload.Type<GunCrewPayload> type() {
        return TYPE;
    }

    public static void handle(GunCrewPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player operator = context.player();

            // 何かに乗っている者は砲の脇に立っていない。乗員の引き金は乗員の入力として既に届く。
            if (operator.getVehicle() != null) {
                return;
            }

            if (!(operator.level().getEntity(payload.vehicle()) instanceof GroundVehicleEntity gun)) {
                return;
            }

            if (!gun.isCrewed() || gun.isWrecked() || !gun.isWithinCrewReach(operator)) {
                return;
            }

            gun.crank(operator, Crank.byIndex(payload.handle()), payload.direction(),
                    payload.trigger());
        });
    }
}
