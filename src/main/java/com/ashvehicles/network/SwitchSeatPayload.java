package com.ashvehicles.network;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.VehicleEntityBase;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * A crew member asking to move to the next seat of the machine they are aboard.
 *
 * <p>Naming nothing, exactly as {@link OpenVehicleHoldPayload} does: the machine is whichever one the
 * sender is riding, so the request cannot reach across the map to somebody else's aeroplane, and
 * which seat is next is the server's to work out — it owns the seating and the client has no honest
 * view of who is where until it is told. The move itself is
 * {@link VehicleEntityBase#switchToNextSeat}.
 */
public record SwitchSeatPayload() implements CustomPacketPayload {
    public static final SwitchSeatPayload INSTANCE = new SwitchSeatPayload();

    public static final CustomPacketPayload.Type<SwitchSeatPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "switch_seat"));

    /** Nothing on the wire: the press is the whole message. */
    public static final StreamCodec<FriendlyByteBuf, SwitchSeatPayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<SwitchSeatPayload> type() {
        return TYPE;
    }

    public static void handle(SwitchSeatPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            // The seat a rider is in belongs to the machine they are directly aboard, not to
            // whatever that machine is itself riding, so the direct vehicle is the one asked.
            if (context.player().getVehicle() instanceof VehicleEntityBase machine) {
                machine.switchToNextSeat(context.player());
            }
        });
    }
}
