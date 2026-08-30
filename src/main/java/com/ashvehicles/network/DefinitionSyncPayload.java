package com.ashvehicles.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.data.DefinitionRegistry;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * サーバーが読み込んだ全ファイル（機体・地上車両・兵装）の写し。ログイン時と {@code /reload} のたびに
 * プレイヤーへ送る。
 *
 * <p>全部まとめて1パケット。クライアントは自分で物理計算を回すのでサーバーと同じ数値が要る。差分一覧では
 * なく定義を丸ごと送ることで話が単純になる——サーバーが今読み込んだ物が、そのままクライアントが運転し
 * 飛ばす物になる。
 *
 * <p>各レジストリは自分のディレクトリ名と自分のコーデックで自分を書くので、通信内容は自分が何かを名乗る。
 * どちらも知らない名前はプロトコル不一致であって読み飛ばす対象ではない——その項目の長さが分からなければ
 * 以降のストリームを読めない——ので、はっきり拒否する。
 */
public record DefinitionSyncPayload(List<DefinitionRegistry.Snapshot<?>> snapshots)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<DefinitionSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "definitions"));

    public static final StreamCodec<FriendlyByteBuf, DefinitionSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeVarInt(payload.snapshots().size());
                payload.snapshots().forEach(snapshot -> write(buf, snapshot));
            },
            buf -> {
                int count = buf.readVarInt();
                List<DefinitionRegistry.Snapshot<?>> snapshots = new ArrayList<>(count);

                for (int i = 0; i < count; i++) {
                    String directory = buf.readUtf();
                    DefinitionRegistry<?> registry = DefinitionRegistry.byDirectory(directory);

                    if (registry == null) {
                        throw new DecoderException("No registry reads " + directory);
                    }

                    snapshots.add(read(buf, registry));
                }

                return new DefinitionSyncPayload(snapshots);
            });

    /** レジストリ自身のコーデックで書く。型引数を捕まえているのはそのため。 */
    private static <T> void write(FriendlyByteBuf buf, DefinitionRegistry.Snapshot<T> snapshot) {
        buf.writeUtf(snapshot.registry().directory());
        buf.writeMap(snapshot.values(), FriendlyByteBuf::writeResourceLocation,
                (target, value) -> snapshot.registry().streamCodec().encode(target, value));
    }

    private static <T> DefinitionRegistry.Snapshot<T> read(FriendlyByteBuf buf, DefinitionRegistry<T> registry) {
        return new DefinitionRegistry.Snapshot<>(registry, buf.readMap(HashMap::new,
                FriendlyByteBuf::readResourceLocation, registry.streamCodec()::decode));
    }

    @Override
    public CustomPacketPayload.Type<DefinitionSyncPayload> type() {
        return TYPE;
    }

    public static void handle(DefinitionSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> DefinitionRegistry.acceptAll(payload.snapshots()));
    }
}
