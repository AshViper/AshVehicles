package com.ashvehicles.network;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.VehicleEntityBase;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The crew opening the hold of the machine they are sitting in.
 *
 * <p>Sent on a key press and naming nothing: the machine is whichever one the sending player is
 * aboard, exactly as {@link AircraftInputPayload} works out whose aircraft an input belongs to, so
 * it cannot be aimed at somebody else's aeroplane on the other side of the map. A menu is the
 * server's to open in any case — the client has no say in what a container holds and must not be
 * given one.
 *
 * <p>Nothing here answers for somebody standing <em>outside</em> a machine. They open its hold by
 * crouching and right-clicking the machine itself, which names the one they meant exactly; a key
 * press from the apron would have to guess between the aeroplanes on it.
 */
public record OpenVehicleHoldPayload() implements CustomPacketPayload {
    public static final OpenVehicleHoldPayload INSTANCE = new OpenVehicleHoldPayload();

    public static final CustomPacketPayload.Type<OpenVehicleHoldPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "open_vehicle_hold"));

    /** Nothing on the wire: the press is the whole message. */
    public static final StreamCodec<FriendlyByteBuf, OpenVehicleHoldPayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<OpenVehicleHoldPayload> type() {
        return TYPE;
    }

    public static void handle(OpenVehicleHoldPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            // Any seat of it, and with no distance test of any sort: somebody sitting in an
            // aeroplane is inside it whatever its shape says, and an aircraft at four hundred knots
            // is a poor thing to measure anybody's reach against.
            if (context.player().getRootVehicle() instanceof VehicleEntityBase machine) {
                machine.openHold(context.player());
            }
        });
    }
}
