package com.ashvehicles.network;

import java.util.HashMap;
import java.util.Map;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.aircraft.AircraftManager;
import com.ashvehicles.aircraft.AircraftShape;
import com.ashvehicles.weapon.WeaponDefinition;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The server's copy of every aircraft, shape and weapon file, sent to a player on login and after
 * each {@code /reload}.
 *
 * <p>The piloting client runs the flight model itself, so it needs the same figures the server has.
 * Sending the definitions whole, rather than a list of what changed, keeps this honest: whatever the
 * server just loaded is what the client will fly with.
 */
public record AircraftSyncPayload(Map<ResourceLocation, AircraftDefinition> definitions,
        Map<ResourceLocation, AircraftShape> shapes,
        Map<ResourceLocation, WeaponDefinition> weapons) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AircraftSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "aircraft_sync"));

    private static final StreamCodec<io.netty.buffer.ByteBuf, AircraftDefinition> DEFINITION =
            ByteBufCodecs.fromCodec(AircraftDefinition.CODEC);
    private static final StreamCodec<io.netty.buffer.ByteBuf, AircraftShape> SHAPE =
            ByteBufCodecs.fromCodec(AircraftShape.CODEC);
    private static final StreamCodec<io.netty.buffer.ByteBuf, WeaponDefinition> WEAPON =
            ByteBufCodecs.fromCodec(WeaponDefinition.CODEC);

    public static final StreamCodec<FriendlyByteBuf, AircraftSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeMap(payload.definitions(), FriendlyByteBuf::writeResourceLocation,
                        (target, definition) -> DEFINITION.encode(target, definition));
                buf.writeMap(payload.shapes(), FriendlyByteBuf::writeResourceLocation,
                        (target, shape) -> SHAPE.encode(target, shape));
                buf.writeMap(payload.weapons(), FriendlyByteBuf::writeResourceLocation,
                        (target, weapon) -> WEAPON.encode(target, weapon));
            },
            buf -> new AircraftSyncPayload(
                    buf.readMap(HashMap::new, FriendlyByteBuf::readResourceLocation, DEFINITION::decode),
                    buf.readMap(HashMap::new, FriendlyByteBuf::readResourceLocation, SHAPE::decode),
                    buf.readMap(HashMap::new, FriendlyByteBuf::readResourceLocation, WEAPON::decode)));

    @Override
    public CustomPacketPayload.Type<AircraftSyncPayload> type() {
        return TYPE;
    }

    public static void handle(AircraftSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> AircraftManager.accept(payload.definitions(), payload.shapes(), payload.weapons()));
    }
}
