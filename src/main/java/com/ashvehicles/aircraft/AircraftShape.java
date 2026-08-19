package com.ashvehicles.aircraft;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * The boxes an aircraft is made of, kept in its own file under {@code data/<pack>/collision/},
 * named after the aircraft it belongs to.
 *
 * <p>Separate from the aircraft's performance file on purpose: one is a page of numbers a pilot
 * would recognise, the other is a description of a shape that has to be worked out against the model
 * and fiddled with until it fits. They are edited at different times, by different eyes.
 *
 * <p>These boxes are what shots land on, what the world collides with, and what anything standing on
 * the aircraft is standing on, which is what a deck needs. An aircraft with no file here falls back
 * to its plain hitbox and behaves as it always did.
 */
public record AircraftShape(List<Box> boxes) {
    public static final AircraftShape NONE = new AircraftShape(List.of());

    public static final Codec<AircraftShape> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Box.CODEC.listOf().fieldOf("boxes").forGetter(AircraftShape::boxes)
    ).apply(instance, AircraftShape::new));

    /**
     * One box, in the aircraft's own axes: x to the right, y up, z towards the nose.
     *
     * <p>Describe an aircraft with a handful of them rather than one. A single box around a
     * fifteen-metre aeroplane is a shed, and a deck wants to be a deck rather than a lid over the
     * whole hull.
     *
     * @param name what this box is, for anyone reading the file
     * @param offset the middle of the box, measured from the aircraft's origin
     * @param size how wide, tall and long it is
     * @param rotation how the box itself is turned within the aircraft, in degrees: x pitches the
     *                 box nose up, y yaws it to the right, z rolls its right-hand side down. A swept
     *                 wing, a drooped tip or a canted fin is a box with an angle on it. Left out, the
     *                 box sits square to the airframe
     */
    public record Box(String name, Vec3 offset, Vec3 size, Vec3 rotation) {
        public static final Codec<Box> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("name", "part").forGetter(Box::name),
                Vec3.CODEC.fieldOf("offset").forGetter(Box::offset),
                Vec3.CODEC.fieldOf("size").forGetter(Box::size),
                Vec3.CODEC.optionalFieldOf("rotation", Vec3.ZERO).forGetter(Box::rotation)
        ).apply(instance, Box::new));

        /** This box's own turn within the aircraft, as a rotation. */
        public Quaternionf orientation() {
            return Attitude.rotate(new Quaternionf(), (float) this.rotation.z,
                    (float) this.rotation.x, (float) this.rotation.y);
        }
    }
}
