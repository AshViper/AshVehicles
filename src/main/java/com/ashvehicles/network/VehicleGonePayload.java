package com.ashvehicles.network;

import java.util.UUID;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.ghost.EntityGhostManager;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * この機体はもう存在しない。ゴーストも消してよい。
 *
 * <p><b>クライアントは機体が消えた理由を知らない。</b> サーバー側の除去理由——撃破、レンチでの回収、残骸
 * の片付け、チャンクのアンロード——はどれもクライアントには同じ1つのパケットとして届き、バニラはそれを
 * 一律 {@code DISCARDED} として処理する。ゴーストの仕組みが立っているのはまさにその区別が付かないから
 * であり、「駐機していた機体の受信が止まった」は既定で「世界ごと眠った」と読む。だから回収された機体も
 * 眠った機体として最後の姿で立ち続けた——誰も戻ってこないので、永久に。
 *
 * <p>推測できない事実はサーバーが述べる。機体が本当に世界から消えるとき（{@code shouldDestroy} な除去
 * 理由）に、その UUID を送る。
 *
 * <p><b>宛先はそのディメンションの全員。</b> 追跡している者だけでは足りない。残っているゴーストを持って
 * いるのは、まさにその機体をもう追跡していないクライアントだからだ。運ぶのは UUID 1つで、機体が消える
 * 瞬間にしか飛ばない。
 */
public record VehicleGonePayload(UUID vehicle) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<VehicleGonePayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "vehicle_gone"));

    public static final StreamCodec<FriendlyByteBuf, VehicleGonePayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeUUID(payload.vehicle()),
            buf -> new VehicleGonePayload(buf.readUUID()));

    @Override
    public CustomPacketPayload.Type<VehicleGonePayload> type() {
        return TYPE;
    }

    /** その機体を見ていた可能性のある全員へ知らせる。 */
    public static void broadcast(ServerLevel level, UUID vehicle) {
        PacketDistributor.sendToPlayersInDimension(level, new VehicleGonePayload(vehicle));
    }

    /**
     * クライアント向けとしてのみ登録されているので、これはクライアントでしか走らない。専用サーバーが
     * {@link EntityGhostManager} を解決することはない。
     */
    public static void handle(VehicleGonePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> EntityGhostManager.forget(payload.vehicle()));
    }
}
