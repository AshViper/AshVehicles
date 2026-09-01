package com.ashvehicles.network;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.RemoteLink;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 管制端末の「接続」と「切断」。押した瞬間に1度だけ飛ぶ。
 *
 * <p><b>接続だけが機体を名指しする。</b>操作者はまだ何にも乗っていないので、サーバーには「どれに繋ぐのか」
 * を伝える他に方法が無い。切断は名指ししない——繋いでいる物は1つしかなく、それはサーバーが知っている。
 *
 * <p><b>名指しできる相手は限られている。</b>受け取ったサーバーは、送り主と同じワールドにいる無人機であること
 * を確かめる。無人機は誰の物でもない——この MOD の機体は元々そうで、歩いて近付いた者が F-16 に乗れるのと
 * 同じように、繋いだ者が無人機を飛ばす。守っているのは所有権ではなく、「これは繋げる物か」だけだ。
 */
public record DroneLinkPayload(int droneId, boolean connect) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DroneLinkPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "drone_link"));

    /** 切断。繋いでいる物は1つしかないので、機体を名指しする必要が無い。 */
    public static final DroneLinkPayload DISCONNECT = new DroneLinkPayload(-1, false);

    public static final StreamCodec<FriendlyByteBuf, DroneLinkPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeVarInt(payload.droneId());
                buf.writeBoolean(payload.connect());
            },
            buf -> new DroneLinkPayload(buf.readVarInt(), buf.readBoolean()));

    /** その機体へ繋ぎに行く。 */
    public static DroneLinkPayload to(Entity drone) {
        return new DroneLinkPayload(drone.getId(), true);
    }

    @Override
    public CustomPacketPayload.Type<DroneLinkPayload> type() {
        return TYPE;
    }

    public static void handle(DroneLinkPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer operator)) {
                return;
            }

            if (!payload.connect()) {
                RemoteLink.disconnect(operator);

                return;
            }

            if (!(operator.level().getEntity(payload.droneId()) instanceof AircraftEntity drone)
                    || !RemoteLink.connect(operator, drone)) {
                // 断られる理由はいくつもあるが、操作者にとってはどれも同じ意味だ——その機体には今繋げない。
                // 理由の一覧を画面に出しても、そのどれかを直す手段が操作者の側に無い。
                operator.displayClientMessage(
                        Component.translatable("message.ashvehicles.drone_link_failed"), true);
            }
        });
    }
}
