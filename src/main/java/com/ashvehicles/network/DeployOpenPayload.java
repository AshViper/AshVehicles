package com.ashvehicles.network;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.screen.DeployScreen;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * その旗の前に立っている者へ、出撃盤を開けという合図。
 *
 * <p><b>開けるかどうかを決めるのはサーバーだ。</b> 触ったブロックが自陣の旗か、試合が動いているか、
 * 本人が所属しているか——どれもクライアントには写ししか無い。写しで判断すれば、無所属の者に空の盤が
 * 開き、湧かない理由が画面の中に無い状態になる。だから触った結果を server が判定し、通ったときだけ
 * この1つの座標を送る。
 */
public record DeployOpenPayload(BlockPos flag) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DeployOpenPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "deploy_open"));

    public static final StreamCodec<FriendlyByteBuf, DeployOpenPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeBlockPos(payload.flag()),
            buf -> new DeployOpenPayload(buf.readBlockPos()));

    @Override
    public CustomPacketPayload.Type<DeployOpenPayload> type() {
        return TYPE;
    }

    public static void send(ServerPlayer player, BlockPos flag) {
        PacketDistributor.sendToPlayer(player, new DeployOpenPayload(flag));
    }

    /**
     * クライアント向けとしてのみ登録されているので、これはクライアントでしか走らない。専用サーバーが
     * {@link DeployScreen} を解決することはない。
     */
    public static void handle(DeployOpenPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> DeployScreen.open(payload.flag()));
    }
}
