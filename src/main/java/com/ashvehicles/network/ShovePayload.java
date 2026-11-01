package com.ashvehicles.network;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.VehicleEntityBase;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 機体を押す一撃。爆風・被弾・車両同士の衝突。
 *
 * <p><b>押すのはサーバーで、動かしているのは運転している側。</b> 運転中の機体の速度はクライアントが持っており、
 * サーバーが足した分は次の報告で巻き戻される（[[server-cannot-see-piloted-aircraft-velocity]]）。だから一撃は
 * 追跡している全員へ配り、受け取った側のうち物理を回している1人（{@code isControlledByLocalInstance}）だけが
 * 足す。他の者は運転側の報告で結果を見る。
 *
 * @param impulse 速度の変化（ブロック/tick）。質量はもう割ってある
 * @param at      押された点（ワールド座標）。重心から外れていれば車体が回る
 * @param tilts   車体を傾け・転がしてよいか。鎖の引きは向きだけを変える
 */
public record ShovePayload(int vehicle, Vec3 impulse, Vec3 at, boolean tilts) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ShovePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "shove"));

    public static final StreamCodec<FriendlyByteBuf, ShovePayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeVarInt(payload.vehicle());
                buf.writeVec3(payload.impulse());
                buf.writeVec3(payload.at());
                buf.writeBoolean(payload.tilts());
            },
            buf -> new ShovePayload(buf.readVarInt(), buf.readVec3(), buf.readVec3(), buf.readBoolean()));

    @Override
    public CustomPacketPayload.Type<ShovePayload> type() {
        return TYPE;
    }

    public static void handle(ShovePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Entity entity = context.player().level().getEntity(payload.vehicle());

            if (entity instanceof VehicleEntityBase machine && machine.isControlledByLocalInstance()) {
                machine.applyShove(payload.impulse(), payload.at(), payload.tilts());
            }
        });
    }
}
