package com.ashvehicles.network;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.AircraftInput;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.joml.Quaternionf;

/**
 * Sent once a tick by the client at the controls.
 *
 * <p>Position, yaw and pitch already reach the server through vanilla's vehicle movement packet;
 * this carries the state vanilla knows nothing about. The payload names no entity: the server
 * applies it to whatever the sending player is piloting, so it cannot be aimed at someone else's
 * aircraft.
 */
public record AircraftInputPayload(AircraftInput input, float throttle, float afterburner,
        Quaternionf attitude, Vec3 velocity, boolean crashed, boolean toggleGear, boolean toggleFlaps,
        boolean toggleVtol, boolean cycleWeapon)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AircraftInputPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "aircraft_input"));

    public static final StreamCodec<FriendlyByteBuf, AircraftInputPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                payload.input().write(buf);
                buf.writeFloat(payload.throttle());
                // Beside the throttle because it is part of the throttle: the gate that lights it is
                // the top of the same lever's travel, and only the flying client knows whether the
                // pilot has been through it. See AircraftEntity.tickAfterburner.
                buf.writeFloat(payload.afterburner());
                buf.writeFloat(payload.attitude().x);
                buf.writeFloat(payload.attitude().y);
                buf.writeFloat(payload.attitude().z);
                buf.writeFloat(payload.attitude().w);
                // How fast the aircraft is really going. The server cannot work this out for itself
                // while a client is flying: see AircraftEntity.getVelocity.
                buf.writeFloat((float) payload.velocity().x);
                buf.writeFloat((float) payload.velocity().y);
                buf.writeFloat((float) payload.velocity().z);
                buf.writeBoolean(payload.crashed());
                buf.writeBoolean(payload.toggleGear());
                buf.writeBoolean(payload.toggleFlaps());
                buf.writeBoolean(payload.toggleVtol());
                buf.writeBoolean(payload.cycleWeapon());
            },
            buf -> new AircraftInputPayload(AircraftInput.read(buf), buf.readFloat(), buf.readFloat(),
                    new Quaternionf(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()),
                    new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat()),
                    buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                    buf.readBoolean()));

    @Override
    public CustomPacketPayload.Type<AircraftInputPayload> type() {
        return TYPE;
    }

    public static void handle(AircraftInputPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player().getVehicle() instanceof AircraftEntity aircraft)) {
                return;
            }

            if (aircraft.getControllingPassenger() != context.player()) {
                return;
            }

            aircraft.setInput(payload.input());
            aircraft.setThrottle(payload.throttle());
            aircraft.reportAfterburner(payload.afterburner());
            aircraft.reportAttitude(payload.attitude());
            aircraft.setPilotVelocity(payload.velocity());

            if (payload.crashed()) {
                aircraft.reportCrash();
            }

            if (payload.toggleGear()) {
                aircraft.toggleGear();
            }

            if (payload.toggleVtol()) {
                aircraft.toggleVtol();
            }

            if (payload.toggleFlaps()) {
                aircraft.toggleFlaps();
            }

            if (payload.cycleWeapon()) {
                aircraft.cycleWeapon();
            }
        });
    }
}
