package com.ashvehicles.network;

import java.util.List;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.RadarReadout;
import com.ashvehicles.sensor.Contact;
import com.ashvehicles.sensor.Threat;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * One sweep of the radar and one reading of the warning receiver, for the pilot.
 *
 * <p>Sent to that one player rather than to everybody tracking the aircraft. A radar picture is an
 * instrument in a cockpit: nobody outside it has any business with the contents, and there is rather
 * a lot of it to be sending to people who cannot see the scope anyway.
 *
 * <p>Everything arrives as figures — a bearing, a range, a height difference — rather than as
 * entities to look up. It has to: the radar reaches several hundred blocks, and most of what it
 * finds out there is not being sent to this client as an entity at all. See
 * {@link com.ashvehicles.sensor.Sensors}.
 */
public record SensorPayload(List<Contact> contacts, List<Threat> threats) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SensorPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "sensors"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SensorPayload> STREAM_CODEC = StreamCodec.composite(
            Contact.STREAM_CODEC.apply(ByteBufCodecs.list()), SensorPayload::contacts,
            Threat.STREAM_CODEC.apply(ByteBufCodecs.list()), SensorPayload::threats,
            SensorPayload::new);

    @Override
    public CustomPacketPayload.Type<SensorPayload> type() {
        return TYPE;
    }

    /**
     * Registered as client-bound only, so this runs on a client and nowhere else; a dedicated server
     * never resolves {@link RadarReadout}.
     */
    public static void handle(SensorPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> RadarReadout.accept(payload.contacts(), payload.threats()));
    }
}
