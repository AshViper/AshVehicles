package com.ashvehicles.vehicle;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

/**
 * What kind of machine a file describes, named in the file rather than left to be guessed at.
 *
 * <p>Until now the kind was implied by two things and never written down. Which directory the file
 * sat in said whether it flew or drove — {@code aircraft/} against {@code vehicle/} — and whether
 * the file had a {@code rotor} block said whether it was a helicopter. That is enough for the game
 * to run on and too little for anyone reading the file to see at a glance, and it has no room in it
 * at all for a third thing that neither flies nor drives on land. So the kind is a field: an
 * aeroplane and a helicopter are two values of it that share the aircraft's whole machinery, a tank
 * and a warship are two that share the ground vehicle's, and the one that is new — the ship — is a
 * ground vehicle held up by the water under it instead of the ground.
 *
 * <p>The value governs the physics and nothing about how the file is read: an aircraft file is
 * still an {@link com.ashvehicles.aircraft.AircraftDefinition} whether it is marked {@code aircraft}
 * or {@code helicopter}, and a {@code vehicle/} file is still a {@link GroundVehicleDefinition}
 * whether it is a {@code ground_vehicle} or a {@code ship}. What the value decides is what happens
 * once one is in the world — whether it holds itself up on a wing or a rotor, and whether it is
 * pressed against the ground or floated on the sea.
 */
public enum VehicleType implements StringRepresentable {
    /** A fixed-wing aircraft, held up by a wing it has to fly to keep air over. */
    AIRCRAFT("aircraft"),
    /** A rotary-wing aircraft, held up by a rotor that makes its own lift standing still. */
    HELICOPTER("helicopter"),
    /** A vehicle on the ground, pressed onto it by gravity and lying along whatever it drives over. */
    GROUND_VEHICLE("ground_vehicle"),
    /** A vessel on the water, floated at its waterline by the sea under it rather than the ground. */
    SHIP("ship");

    public static final Codec<VehicleType> CODEC = StringRepresentable.fromEnum(VehicleType::values);

    private final String name;

    VehicleType(String name) {
        this.name = name;
    }

    /** Whether this kind flies, on a wing or a rotor: an aircraft or a helicopter. */
    public boolean flies() {
        return this == AIRCRAFT || this == HELICOPTER;
    }

    /** Whether this kind is a helicopter, held up by a rotor rather than a wing. */
    public boolean isHelicopter() {
        return this == HELICOPTER;
    }

    /** Whether this kind is floated by the water under it rather than resting on the ground. */
    public boolean floats() {
        return this == SHIP;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }
}
