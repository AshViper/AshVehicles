package com.ashvehicles.weapon;

import javax.annotation.Nullable;

import com.ashvehicles.particle.Effects;
import com.ashvehicles.registry.ModParticles;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * What a weapon looks and sounds like where it lands.
 *
 * <p>The fire, the smoke, the wave and the bang are not here: those are the same wherever they come
 * from, and a wreck burning makes exactly the same ones, so they live in {@link Effects}. What is
 * left is the part that really is a weapon's — what a round does to the block it struck, and what a
 * barrel does as the round leaves it.
 */
public final class WeaponEffects {
    /** How far in front of the muzzle the flash sits, per point of power. Clear of the barrel. */
    private static final double MUZZLE_STANDOFF = 0.22;

    /** How big a scatter of sparks a ricochet makes at the plate, on {@link Effects#sparks}'s scale. */
    private static final float RICOCHET_SPARKS = 1.6F;
    /** And how many of them are thrown down the new line rather than scattered. */
    private static final int RICOCHET_STREAKS = 6;
    /** How fast those are thrown, in blocks a tick. Fast enough to read as a streak, not as a spray. */
    private static final double RICOCHET_THROW = 0.9;
    /** And how far they fan out from that line, so the six of them are six. */
    private static final double RICOCHET_FAN = 0.18;
    /** A little smoke off the plate, for the metal that came with them. */
    private static final int RICOCHET_SMOKE = 4;
    private static final float RICOCHET_SMOKE_SIZE = 0.5F;

    /** How big a scatter of sparks a round going <em>in</em> makes, on {@link Effects#sparks}'s scale. */
    private static final float STRIKE_SPARKS = 2.4F;
    /** And how many are thrown back out of the hole rather than scattered about it. */
    private static final int STRIKE_SPATTER = 10;
    /**
     * How fast those come back out, in blocks a tick. Slower than a ricochet, and that difference is
     * deliberate: a round thrown off carries most of what it arrived with and goes somewhere, and
     * this is the little that came back out of a round that did not.
     */
    private static final double STRIKE_THROW = 0.5;
    /** And how wide a cone they come back in. Wide: nothing about this is aimed at anything. */
    private static final double STRIKE_FAN = 0.4;
    /** A shade upwards with it, so the spray arcs off the plate rather than lying flat against it. */
    private static final double STRIKE_LIFT = 0.14;
    /** The flash at the plate itself, on the same scale a blast is drawn at. */
    private static final float STRIKE_FLASH = 0.6F;
    private static final int STRIKE_SMOKE = 5;
    private static final float STRIKE_SMOKE_SIZE = 0.45F;

    /**
     * Everything that happens where a round lands: the blast if it carries one, the sparks either
     * way, and a scatter of whatever it went into.
     *
     * @param at where it went off
     * @param along the line it arrived on
     * @param round the round that did it, for its size and its colour
     * @param struck the block it hit, if it hit one
     * @param onPlate whether what it went into was armour rather than the ground or a bystander,
     *                which is the one case worth a flash of its own. See {@link #strike}
     */
    public static void detonation(ServerLevel level, Vec3 at, Vec3 along,
            WeaponDefinition.Projectile round, @Nullable BlockState struck, boolean onPlate) {
        float power = Mth.clamp(round.explosion(), 0.0F, Effects.BIGGEST);

        if (power > 0.0F) {
            Effects.fireball(level, at, power, round.tracer());
            Effects.boom(level, at, power);
            Effects.wave(level, at, power);
        } else if (onPlate) {
            strike(level, at, along, round);
        } else {
            Effects.sparks(level, at, round.tracer(), 1.0F);
        }

        if (struck != null && !struck.isAir()) {
            debris(level, at, struck, Math.max(power, 1.0F));
        }
    }

    /** The blast proper, sized and coloured by the round that carried it. See {@link Effects#blast}. */
    public static void blast(ServerLevel level, Entity source, Vec3 at, WeaponDefinition.Projectile round) {
        Effects.blast(level, source, at, round.explosion(), round.tracer());
    }

    /**
     * The flash and the smoke a gun makes as the round leaves it.
     *
     * <p>Thrown forwards rather than scattered, because that is what a muzzle blast does: the gas
     * behind the round comes out after it, faster than it, and for a moment there is a cone of fire
     * in front of the barrel. What that is worth is telling everyone within sight which way a tank
     * is pointing and that it has just fired, which is most of what there is to know about a tank.
     *
     * @param at the muzzle
     * @param along the way the barrel is pointing, as a unit vector
     * @param power how big the gun is, on the same scale as a blast: a tank's main armament is
     *              several, a machine gun a fraction of one
     * @param tracer the colour of the flash, which is the round's own
     */
    public static void muzzleBlast(ServerLevel level, Vec3 at, Vec3 along, float power, int tracer) {
        Vec3 ahead = at.add(along.scale(MUZZLE_STANDOFF * power));

        Effects.send(level, ahead, ModParticles.BLAST.get().of(tracer, power * 0.3F),
                2 + (int) (power * 1.2F), power * 0.06, power * 0.02);
        Effects.send(level, ahead, ModParticles.BLAST_SMOKE.get().of(Effects.SOOT, power * 0.36F),
                4 + (int) (power * 2.0F), power * 0.12, power * 0.03);
        // Blown out along the barrel rather than left where the flash was, so the smoke reads as
        // having been driven out of the gun instead of having been sitting in front of it.
        Effects.send(level, ahead.add(along.scale(power * 0.35)),
                ModParticles.BLAST_SMOKE.get().of(Effects.SOOT, power * 0.22F),
                2 + (int) power, power * 0.08, power * 0.05);
        Effects.sparks(level, ahead, Effects.EMBER, power * 0.5F);
    }

    /**
     * A round skidding off armour: a hard spray of sparks thrown down the line it left on.
     *
     * <p>Aimed rather than scattered, and that is the whole of what this has to say. A hit that went
     * in and a hit that did not look much the same as a flash on the plate; what tells the gunner
     * which of the two they just got is that the sparks went <em>somewhere</em> — off along the
     * slope, away from the tank, on the line the round itself is now travelling. A stream of those
     * coming back off a turret front is a gunner being told to aim somewhere else.
     *
     * @param at where it struck the plate
     * @param away where it is going now, as it leaves
     * @param round the round that did it, for its colour
     */
    public static void ricochet(ServerLevel level, Vec3 at, Vec3 away, WeaponDefinition.Projectile round) {
        Vec3 along = away.lengthSqr() < 1.0E-8 ? Vec3.ZERO : away.normalize();
        RandomSource random = level.getRandom();

        Effects.sparks(level, at, round.tracer(), RICOCHET_SPARKS);

        for (int i = 0; i < RICOCHET_STREAKS; i++) {
            // Each one thrown a little off the others, or six particles given the same velocity from
            // the same point are one particle drawn six times over.
            Vec3 thrown = along.scale(RICOCHET_THROW).add(
                    random.nextGaussian() * RICOCHET_FAN, random.nextGaussian() * RICOCHET_FAN,
                    random.nextGaussian() * RICOCHET_FAN);

            Effects.aimed(level, at, ModParticles.SPARK.get().of(Effects.EMBER, 1.0F), thrown);
        }

        Effects.send(level, at, ModParticles.BLAST_SMOKE.get().of(Effects.SOOT, RICOCHET_SMOKE_SIZE),
                RICOCHET_SMOKE, 0.08, 0.04);
    }

    /**
     * A round going into armour: a white flash at the plate and a spray of sparks back out of it.
     *
     * <p>The other half of {@link #ricochet}, and until now the half with almost nothing to show.
     * A hit that went in got the same handful of sparks a round into a hillside gets, which at the
     * ranges these are fought at is a hit the gunner cannot see they scored. What tells them is that
     * something bright happened <em>on the tank</em>, and it wants to be as loud to the eye as the
     * ricochet it has to be told apart from.
     *
     * <p>Which is why it is shaped the other way round. A ricochet throws its sparks off along the
     * line the round left on — away, in one direction, in a stream. This throws them <em>back</em>,
     * into a wide cone about the way the round came in, because nothing left: what comes back out of
     * the hole is the little that the plate would not keep, and it comes out at the shot rather than
     * past it. A gunner watching sparks come towards them has hit something; a gunner watching them
     * go somewhere else has not.
     *
     * @param at where it struck the plate
     * @param along the line the round arrived on
     * @param round the round that did it, for its colour
     */
    public static void strike(ServerLevel level, Vec3 at, Vec3 along, WeaponDefinition.Projectile round) {
        Vec3 back = along.lengthSqr() < 1.0E-8 ? Vec3.ZERO : along.normalize().reverse();
        RandomSource random = level.getRandom();

        // The flash: the round's own colour, since what is glowing at the plate is the round.
        Effects.send(level, at, ModParticles.BLAST.get().of(round.tracer(), STRIKE_FLASH),
                2, 0.05, 0.02);
        Effects.sparks(level, at, Effects.EMBER, STRIKE_SPARKS);

        for (int i = 0; i < STRIKE_SPATTER; i++) {
            // Each thrown a little off the others, or ten particles given the same velocity from the
            // same point are one particle drawn ten times over.
            Vec3 thrown = back.scale(STRIKE_THROW).add(
                    random.nextGaussian() * STRIKE_FAN,
                    random.nextGaussian() * STRIKE_FAN + STRIKE_LIFT,
                    random.nextGaussian() * STRIKE_FAN);

            Effects.aimed(level, at, ModParticles.SPARK.get().of(Effects.EMBER, 1.0F), thrown);
        }

        Effects.send(level, at, ModParticles.BLAST_SMOKE.get().of(Effects.SOOT, STRIKE_SMOKE_SIZE),
                STRIKE_SMOKE, 0.08, 0.05);
    }

    /** Chips of whatever was hit, in the colour of the block they came off. */
    private static void debris(ServerLevel level, Vec3 at, BlockState struck, float power) {
        int colour = struck.getMapColor(level, BlockPos.containing(at)).col;

        Effects.send(level, at, ModParticles.DEBRIS.get().of(colour, 1.0F),
                5 + (int) (power * 2.5F), 0.08, 0.08 + power * 0.03);
    }

    private WeaponEffects() {
    }
}
