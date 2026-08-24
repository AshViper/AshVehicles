package com.ashvehicles.entity;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;

/**
 * A snapshot of the driver's controls for a single tick.
 *
 * <p>Two axes, both normalised to [-1, 1]. {@code drive} is the throttle and the gear selector at
 * once — forward for ahead, back for astern — rather than a setting that is spooled up and left
 * there: a tank is driven with a foot on a pedal, not flown with a hand on a throttle quadrant, and
 * letting go means slowing down. {@code steer} is positive to the right.
 *
 * <p>{@code brake} is the parking brake as much as the service brake: held on, it stops the vehicle
 * and holds it on a slope. {@code fire} is the trigger.
 */
public record GroundVehicleInput(float drive, float steer, boolean brake, boolean fire) {

    /** Controls centred, and nothing held. */
    public static final GroundVehicleInput NONE = new GroundVehicleInput(0.0F, 0.0F, false, false);

    /**
     * What an empty driver's seat does: the brake on.
     *
     * <p>Not simply centred controls. A vehicle left with nothing pressed coasts, and a tank's
     * rolling resistance is small enough that one abandoned at speed carries on for the best part of
     * a quarter of a minute — long enough to leave the crew standing in a field watching their tank
     * drive away. Anybody getting out of one leaves the brake on, and so does this.
     */
    public static final GroundVehicleInput PARKED = new GroundVehicleInput(0.0F, 0.0F, true, false);

    public GroundVehicleInput {
        drive = Mth.clamp(drive, -1.0F, 1.0F);
        steer = Mth.clamp(steer, -1.0F, 1.0F);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeFloat(this.drive);
        buf.writeFloat(this.steer);
        buf.writeBoolean(this.brake);
        buf.writeBoolean(this.fire);
    }

    public static GroundVehicleInput read(FriendlyByteBuf buf) {
        return new GroundVehicleInput(buf.readFloat(), buf.readFloat(), buf.readBoolean(), buf.readBoolean());
    }
}
