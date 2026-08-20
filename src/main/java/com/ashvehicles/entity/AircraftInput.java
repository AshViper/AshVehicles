package com.ashvehicles.entity;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;

/**
 * A snapshot of the pilot's control inputs for a single tick.
 *
 * <p>All axes are normalised to [-1, 1]. {@code throttle} is a rate of change rather than an
 * absolute setting: the aircraft integrates it, so holding the key spools the engine up or down.
 * {@code fire} is the trigger, held or not; what it fires is whatever the aircraft has selected.
 * {@code flare} and {@code chaff} are the two countermeasure handles, which are separate because
 * they answer different threats; see {@link com.ashvehicles.weapon.Dispenser}.
 */
public record AircraftInput(float pitch, float roll, float yaw, float throttle, boolean brake, boolean fire,
        boolean flare, boolean chaff) {

    /** Controls centred, engine left where it is. Also what an empty cockpit does. */
    public static final AircraftInput NONE =
            new AircraftInput(0.0F, 0.0F, 0.0F, 0.0F, false, false, false, false);

    public AircraftInput {
        pitch = Mth.clamp(pitch, -1.0F, 1.0F);
        roll = Mth.clamp(roll, -1.0F, 1.0F);
        yaw = Mth.clamp(yaw, -1.0F, 1.0F);
        throttle = Mth.clamp(throttle, -1.0F, 1.0F);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeFloat(this.pitch);
        buf.writeFloat(this.roll);
        buf.writeFloat(this.yaw);
        buf.writeFloat(this.throttle);
        buf.writeBoolean(this.brake);
        buf.writeBoolean(this.fire);
        buf.writeBoolean(this.flare);
        buf.writeBoolean(this.chaff);
    }

    public static AircraftInput read(FriendlyByteBuf buf) {
        return new AircraftInput(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean());
    }
}
