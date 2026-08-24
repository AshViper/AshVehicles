package com.ashvehicles.weapon;

import java.util.Optional;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.vehicle.Hitbox;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * A round thrown off armour instead of going into it.
 *
 * <p>What decides it is one angle: how far off square the round struck the plate, measured from the
 * plate's own normal. A shot arriving head-on has all of its speed pointed into the metal and goes
 * in; the same shot arriving nearly along the surface has almost none of it pointed into anything,
 * skids, and leaves. Between the two there is a band a few degrees wide where it may do either,
 * because a real one does — the same gun at the same angle bites one round and throws the next.
 *
 * <p><b>The slope is not a figure anywhere.</b> It does not have to be. A machine's boxes lie at
 * whatever angle the machine is lying at — see {@link Hitbox} — so the plate a shot meets is the
 * plate that is really there: a hull turned to meet the fire, a tank sitting nose-up on a bank, a
 * turret traversed away from the shot. All of that arrives here as the angle, already worked out by
 * the geometry, and a crew angling their hull are doing the one thing that actually helps rather
 * than triggering a rule about it. What the files carry is only the two halves nothing can derive:
 * how well the round bites ({@code ricochet} on the round) and how good the plate is
 * ({@code armour} on the vehicle).
 *
 * <p>A round thrown off is still a round. It goes on flying, on a new line, having lost most of its
 * speed and with it most of what it was worth — see {@link #energy} — and it can hit whatever is
 * behind or beside the thing that threw it off. That includes the thing itself: a shot that skids
 * off a glacis into the turret ring is exactly the shot everybody who has ever aimed at a tank is
 * hoping for.
 */
public final class Ricochet {
    /** The tail of the sound event's name: {@code weapon.<weapon>.ricochet}. */
    public static final String SOUND_ROLE = "ricochet";

    /** What any weapon with no clang of its own falls back on. Named by the server. */
    public static final ResourceLocation SOUND = ResourceLocation.fromNamespaceAndPath(
            AshVehicles.MODID, WeaponMounts.SOUND_PREFIX + SOUND_ROLE);

    /**
     * How loud a ricochet is, on the same scale a weapon's report uses: the figure is a reach rather
     * than a loudness. Under a gun's own, and deliberately — a round skidding off a turret is a hard
     * noise but it is not the gun going off, and the person who most needs to hear it is the one who
     * fired, a long way away.
     */
    public static final float VOLUME = 0.9F;
    /** Pitched up, because it is a strike on plate rather than anything going off inside it. */
    public static final float PITCH = 1.35F;

    /**
     * The two together, as the one object both ends of the sound read them from.
     *
     * <p>The server asks it how far the clang should carry and the client asks it how loud that
     * comes to where the listener is standing; they have to be the same figures or the sound arrives
     * at the wrong loudness or not at all. See {@link WeaponDefinition.SoundSetup#packetVolume()}.
     */
    public static final WeaponDefinition.SoundSetup SOUND_SETUP =
            new WeaponDefinition.SoundSetup(Optional.empty(), VOLUME, PITCH);

    /**
     * How many times one round may be thrown off before it is spent.
     *
     * <p>A cap rather than a rule about energy, and it is here to end the one case the arithmetic
     * cannot: a round skidding between two plates that face each other has somewhere to go for as
     * long as it has speed, and each bounce leaves it enough to make the next. Two is past what
     * anybody will notice and well short of a round living in the running gear.
     */
    public static final int MOST = 2;

    /** How much of its speed a round keeps as it is thrown off. */
    public static final double SPEED_KEPT = 0.55;

    /**
     * How far past the margin a hit is allowed the round is put before it flies on, in blocks.
     *
     * <p>Added to that margin rather than used alone, and it has to be. Every test that looks for a
     * hit grows the box first — vanilla by a third of a block, and the mod matches it — so a round
     * left on the surface, or anywhere inside that margin, is still inside the box as far as the next
     * tick is concerned. A line that starts inside a box comes back with where it started, so the
     * round would be thrown off the same plate again, from the same place, until it ran out of
     * bounces. Past the margin there is nothing to be thrown off and it simply leaves.
     */
    public static final double CLEARANCE = 0.05;

    /**
     * How wide the band between never and always is, in degrees.
     *
     * <p>At the round's own angle it is thrown off one time in none, and this far past it one time
     * in one, with the odds running evenly between. The band matters more than either end: without
     * it the angle is a wall, and a gunner who has found one degree past it is not fighting a tank
     * any more, they are fighting a number.
     */
    private static final double BAND = 12.0;

    /** How much a ricochet wanders off the line the geometry gives it, as a share of its speed. */
    private static final double SCATTER = 0.06;

    /**
     * How much of the round's push into the plate comes back out of it.
     *
     * <p>Well under half, so that a ricochet hugs the plate rather than mirroring off it. A shot
     * thrown off armour is not a ball off a wall — it is a hardened lump skidding along a slope,
     * shedding the part of its speed that was pointed into the metal and keeping the part that was
     * pointed along it — and what leaves is travelling nearly the way the plate runs. Which is also
     * why a ricochet off the glacis so often goes into the turret.
     */
    private static final double REBOUND = 0.25;

    /** Below this a direction is no direction at all, and the normal stands in for it. */
    private static final double NOTHING = 1.0E-9;

    private Ricochet() {
    }

    /** The sound event for one weapon's ricochet, which a pack may record on its own. */
    public static ResourceLocation soundFor(ResourceLocation weapon) {
        return weapon.withPath(WeaponMounts.SOUND_PREFIX + weapon.getPath() + "." + SOUND_ROLE);
    }

    /**
     * How far off square a round struck a plate, in degrees from the plate's own normal.
     *
     * @param velocity where the round was going. Need not be a unit vector
     * @param normal the plate's outward unit normal, from {@link Hitbox#normalAt}
     * @return nought for a square hit and ninety for one along the surface, or ninety for a round
     *         that was somehow already leaving, which is the angle that bites least
     */
    public static double angle(Vec3 velocity, Vec3 normal) {
        double speed = velocity.length();

        if (speed < NOTHING) {
            return 90.0;
        }

        // Into the plate is against its normal, so the square hit is the one whose dot is -1.
        double square = -velocity.dot(normal) / speed;

        return Math.toDegrees(Math.acos(Mth.clamp(square, 0.0, 1.0)));
    }

    /**
     * Whether the plate throws this round off rather than letting it in.
     *
     * <p>Both halves are asked for whole rather than as one number worked out beforehand, because
     * the two mean different things and only one of them can say never. A round whose file gives it
     * no angle at all is never thrown off by anything, however good the plate — that is a shaped
     * charge, or a bomb, and what it does on contact is go off. Armour, on the other hand, only ever
     * moves the angle, and armour good enough to take the angle down to nothing still does not throw
     * off a shot that arrived dead square: there is no angle left in that hit to skid along.
     *
     * @param round the round that struck, for the angle it needs before it can be thrown off at all
     * @param armour what the plate is worth, in degrees off that angle
     */
    public static boolean thrownOff(Vec3 velocity, Vec3 normal, WeaponDefinition.Projectile round,
            float armour, RandomSource random) {
        if (!round.canRicochet()) {
            return false;
        }

        double off = angle(velocity, normal) - Math.max(round.ricochet() - armour, 0.0);

        return off >= BAND || (off > 0.0 && random.nextDouble() < off / BAND);
    }

    /**
     * Where the round goes once the plate has thrown it off.
     *
     * <p>The part of its speed that was pointed along the plate is kept; the part that was pointed
     * into it mostly is not, and what comes back out is {@link #REBOUND} of it. The whole is then
     * cut to {@link #SPEED_KEPT}, because a round that has just skidded the length of a glacis is
     * not the round that arrived.
     */
    public static Vec3 away(Vec3 velocity, Vec3 normal, RandomSource random) {
        double speed = velocity.length();
        Vec3 thrown = velocity.subtract(normal.scale(velocity.dot(normal) * (1.0 + REBOUND)));
        double scatter = speed * SCATTER;
        Vec3 wandered = thrown.add(random.nextGaussian() * scatter,
                random.nextGaussian() * scatter, random.nextGaussian() * scatter);

        // Never back into the plate. The scatter is small and the rebound points out of it, so this
        // only ever catches a round thrown off almost exactly along the surface -- and along the
        // surface is where that one should go.
        if (wandered.dot(normal) < 0.0) {
            wandered = wandered.subtract(normal.scale(wandered.dot(normal)));
        }

        if (wandered.lengthSqr() < NOTHING) {
            wandered = normal;
        }

        return wandered.normalize().scale(speed * SPEED_KEPT);
    }

    /**
     * What a round is still worth after being thrown off however many times, as a share of what it
     * was worth when it left the barrel.
     *
     * <p>The speed it kept, and nothing else. A round carries its damage in how fast it is going, so
     * one that has skidded off a turret roof and gone on into a truck should not arrive at the truck
     * as though it had come straight from the gun.
     */
    public static float energy(int deflections) {
        return (float) Math.pow(SPEED_KEPT, Math.max(deflections, 0));
    }
}
