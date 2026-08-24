package com.ashvehicles.network;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.GroundVehicleInput;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.joml.Quaternionf;

/**
 * Sent once a tick by the client at the controls.
 *
 * <p>Position, yaw and pitch already reach the server through vanilla's vehicle movement packet;
 * this carries the state vanilla knows nothing about — the hull's roll, how fast it is really going,
 * and where the turret is laid. The payload names no entity: the server applies it to whatever the
 * sending player is driving, so it cannot be aimed at somebody else's tank.
 */
public record GroundVehicleInputPayload(GroundVehicleInput input, Quaternionf attitude, float speed,
        float turretYaw, float gunPitch, boolean cycleWeapon) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<GroundVehicleInputPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "vehicle_input"));

    public static final StreamCodec<FriendlyByteBuf, GroundVehicleInputPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                payload.input().write(buf);
                buf.writeFloat(payload.attitude().x);
                buf.writeFloat(payload.attitude().y);
                buf.writeFloat(payload.attitude().z);
                buf.writeFloat(payload.attitude().w);
                buf.writeFloat(payload.speed());
                buf.writeFloat(payload.turretYaw());
                buf.writeFloat(payload.gunPitch());
                buf.writeBoolean(payload.cycleWeapon());
            },
            buf -> new GroundVehicleInputPayload(GroundVehicleInput.read(buf),
                    new Quaternionf(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readBoolean()));

    @Override
    public CustomPacketPayload.Type<GroundVehicleInputPayload> type() {
        return TYPE;
    }

    public static void handle(GroundVehicleInputPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player().getVehicle() instanceof GroundVehicleEntity vehicle)) {
                return;
            }

            if (vehicle.getControllingPassenger() != context.player()) {
                return;
            }

            vehicle.setInput(payload.input());
            vehicle.reportState(payload.attitude(), payload.speed(), payload.turretYaw(), payload.gunPitch());

            if (payload.cycleWeapon()) {
                vehicle.cycleWeapon();
            }
        });
    }
}
