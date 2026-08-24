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
 * The server's copy of every file it has loaded — aircraft, ground vehicles and weapons — sent to
 * a player on login and after each {@code /reload}.
 *
 * <p>One packet for the lot. The client runs the physics itself, so it needs the same figures the
 * server has, and sending the definitions whole rather than a list of what changed keeps this
 * honest: whatever the server just loaded is what the client will drive and fly with.
 *
 * <p>Each registry writes itself under its own directory name and with its own codec, so what is on
 * the wire says what it is. A name neither side recognises is a protocol mismatch rather than
 * something to skip past — the rest of the stream cannot be read without knowing how long that entry
 * was — so it is refused loudly.
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

    /** Written through the registry's own codec, which is what the type parameter is captured for. */
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
