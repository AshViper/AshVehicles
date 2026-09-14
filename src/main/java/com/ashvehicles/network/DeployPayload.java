package com.ashvehicles.network;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.match.AirPresets;
import com.ashvehicles.match.Deathmatch;
import com.ashvehicles.match.Loadout;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 出撃の注文。どの旗から、何に乗って、どのプリセットで出るか。
 *
 * <p><b>運ぶのはプリセットの名前だけで、何をどこに吊るかは運ばない。</b> サーバーは名前から同じ規則で
 * 組み直す（{@link AirPresets#order}）ので、作ったパケットで好きな物を吊ることはできない。地上車両と、
 * プリセットを持たない機体は空の名前で来る。
 *
 * <p>届いた注文は信用しない。その機体に無いプリセット、他陣営の旗、明けていない出撃待ち——どれも門前で
 * 断り、断った理由はそのまま1行として本人へ返る（{@link Deathmatch#deploy}）。
 */
public record DeployPayload(BlockPos flag, ResourceLocation vehicle, String preset) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DeployPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "deploy"));

    /** プリセット名の長さの上限。役割の名前はどれも10文字に満たない。 */
    private static final int MOST_NAME = 32;

    public static final StreamCodec<FriendlyByteBuf, DeployPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.flag());
                buf.writeResourceLocation(payload.vehicle());
                buf.writeUtf(payload.preset(), MOST_NAME);
            },
            buf -> new DeployPayload(buf.readBlockPos(), buf.readResourceLocation(), buf.readUtf(MOST_NAME)));

    @Override
    public CustomPacketPayload.Type<DeployPayload> type() {
        return TYPE;
    }

    public static void handle(DeployPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }

            Loadout loadout = AirPresets.order(payload.vehicle(), payload.preset());

            if (loadout == null) {
                player.displayClientMessage(Component.translatable("message.ashvehicles.match.unknown_preset",
                        payload.preset()), true);

                return;
            }

            Deathmatch.Deployed deployed = Deathmatch.deploy(player, payload.flag(), loadout);

            player.displayClientMessage(deployed.message(), true);
        });
    }
}
