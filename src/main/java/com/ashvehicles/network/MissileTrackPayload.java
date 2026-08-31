package com.ashvehicles.network;

import java.util.ArrayList;
import java.util.List;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.MissileTrack;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 自分の発射機が撃った弾が、今どこを飛んでいるか。撃った本人だけに届く。
 *
 * <p><b>なぜ送るのか。</b>クライアントは自分の周りのエンティティしか知らない。ロケットの追跡距離は128 chunk
 * ——2048ブロック——で、それは弾道弾にとって発射から数秒でしかない。射撃指揮盤（{@code LaunchConsoleScreen}）が
 * 「自分の撃った弾がどこまで行ったか」を見せるには、エンティティを送るのでは間に合わないし、送る必要も無い。
 * 要るのは点だけだ。
 *
 * <p><b>安い。</b>1発あたり16バイト、数tickに1度、飛んでいる弾を持つ乗員1人にだけ。撃てる弾は筒の数までで、
 * それは今のところ2発である。
 *
 * <p><b>これは計器であって世界ではない。</b>受け取った点で描かれるのは地図上の印だけで、弾そのものは相変わらず
 * サーバーが動かし、当たり判定もそちらにある。届かなくなれば印が消えるだけだ。
 */
public record MissileTrackPayload(List<Shot> shots) implements CustomPacketPayload {
    /** 飛んでいる弾1発。地図に置くのに要る物しか運ばない。 */
    public record Shot(int id, double x, double y, double z) {
    }

    public static final CustomPacketPayload.Type<MissileTrackPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "missile_track"));

    /** 1発も飛んでいない。印を消すのに送る。 */
    public static final MissileTrackPayload NONE = new MissileTrackPayload(List.of());

    public static final StreamCodec<RegistryFriendlyByteBuf, MissileTrackPayload> STREAM_CODEC =
            StreamCodec.of(MissileTrackPayload::write, MissileTrackPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, MissileTrackPayload payload) {
        ByteBufCodecs.VAR_INT.encode(buf, payload.shots.size());

        for (Shot shot : payload.shots) {
            ByteBufCodecs.VAR_INT.encode(buf, shot.id());
            buf.writeDouble(shot.x());
            buf.writeDouble(shot.y());
            buf.writeDouble(shot.z());
        }
    }

    private static MissileTrackPayload read(RegistryFriendlyByteBuf buf) {
        int count = ByteBufCodecs.VAR_INT.decode(buf);
        List<Shot> shots = new ArrayList<>(count);

        for (int at = 0; at < count; at++) {
            shots.add(new Shot(ByteBufCodecs.VAR_INT.decode(buf),
                    buf.readDouble(), buf.readDouble(), buf.readDouble()));
        }

        return new MissileTrackPayload(List.copyOf(shots));
    }

    @Override
    public CustomPacketPayload.Type<MissileTrackPayload> type() {
        return TYPE;
    }

    public static void handle(MissileTrackPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> MissileTrack.take(payload.shots()));
    }
}
