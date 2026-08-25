package com.ashvehicles.vehicle;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.util.StringRepresentable;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * The boxes a machine is made of, written in the {@code hitbox} block of its own file beside the
 * plain box they stand in for.
 *
 * <p>These boxes are what shots land on, what the world collides with, and what anything standing on
 * the machine is standing on, which is what a deck needs. A machine that lists none falls back to
 * its plain hitbox and behaves as it always did.
 *
 * <p>They used to be a file of their own, under {@code data/&lt;pack&gt;/collision/}, on the grounds
 * that a page of figures a pilot would recognise and a description of a shape fitted against a model
 * are edited at different times by different eyes. In practice they are edited by the same person in
 * the same afternoon, and being in two places meant two files to find, two to copy when a machine
 * was cloned, and one of them silently absent when it was not — which is a machine with no shape at
 * all rather than an error anybody sees.
 */
public record VehicleShape(List<Box> boxes) {
    public static final VehicleShape NONE = new VehicleShape(List.of());

    /**
     * Read straight into the block it is part of rather than as a value of its own, so that the file
     * says {@code "hitbox": { "width": …, "boxes": [ … ] }} and not a nesting deeper.
     */
    public static final MapCodec<VehicleShape> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Box.CODEC.listOf().optionalFieldOf("boxes", List.of()).forGetter(VehicleShape::boxes)
    ).apply(instance, VehicleShape::new));

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
     * @param mount what the box is bolted to. Everything on an aircraft is on the hull and nothing
     *              here has to say so; a tank has a turret, and a box on it is swung about the
     *              turret ring by however far the turret is traversed. A gun barrel whose box stayed
     *              pointing over the bow while the turret was laid abeam would be a shield in one
     *              place and a hole in another. The barrel itself is a further case: it rides the
     *              turret round in traverse the same as any turret box, but it also rocks in
     *              elevation about the trunnion as the gun is laid up or down, which a plain turret
     *              box does not
     */
    public record Box(String name, Vec3 offset, Vec3 size, Vec3 rotation, Mount mount) {
        public static final Codec<Box> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("name", "part").forGetter(Box::name),
                Vec3.CODEC.fieldOf("offset").forGetter(Box::offset),
                Vec3.CODEC.fieldOf("size").forGetter(Box::size),
                Vec3.CODEC.optionalFieldOf("rotation", Vec3.ZERO).forGetter(Box::rotation),
                Mount.CODEC.optionalFieldOf("mount", Mount.HULL).forGetter(Box::mount)
        ).apply(instance, Box::new));

        /** This box's own turn within the aircraft, as a rotation. */
        public Quaternionf orientation() {
            return Attitude.rotate(new Quaternionf(), (float) this.rotation.z,
                    (float) this.rotation.x, (float) this.rotation.y);
        }
    }

    /**
     * What a box is bolted to.
     *
     * <p>Only the hull moves as one with the vehicle. Anything else is somewhere the vehicle can put
     * it, and where that is has to be worked out afresh every tick from whatever the vehicle has
     * done with it since.
     */
    public enum Mount implements StringRepresentable {
        /** Part of the machine itself, and where the file says it is. Everything on an aircraft. */
        HULL("hull"),
        /** Carried round by the turret, swung about its ring. */
        TURRET("turret"),
        /**
         * Carried round by the turret the same as {@link #TURRET}, and also rocked in elevation
         * about the trunnion by however far the gun is laid up or down. For the barrel itself, and
         * anything bolted to it rather than to the turret roof.
         */
        GUN("gun");

        public static final Codec<Mount> CODEC = StringRepresentable.fromEnum(Mount::values);

        private final String name;

        Mount(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }
}
