package com.ashvehicles.vehicle;

import com.ashvehicles.particle.Effects;
import com.ashvehicles.registry.ModParticles;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaternionf;

/**
 * What being destroyed looks and sounds like, from the hit to the last of the smoke.
 *
 * <p>Four moments, and they are meant to be watched in order.
 *
 * <p><b>The hit.</b> Not one fireball but several, strung along the machine rather than stacked on
 * its middle. An aeroplane is fifteen metres of aeroplane and a point explosion over the middle of
 * it reads as something going off <em>near</em> the aircraft; fire coming out of the whole length of
 * it reads as the aircraft. Torn airframe goes out with it, hard and in every direction.
 *
 * <p><b>The fall.</b> A machine written off in the air keeps whatever it was doing and comes down
 * trailing fire and black smoke. This is the part worth having: a kill a mile away is a bright mark
 * and a long dark line, and both are visible for as long as it takes to reach the ground.
 *
 * <p><b>The arrival.</b> Fire, dust and a heavy noise where it lands — but no second crater. The
 * blast that wrote the machine off has already dug whatever hole it was going to dig, and one
 * aeroplane digging two is one more than anybody asked for.
 *
 * <p><b>The burn.</b> And then it lies there and burns: hard for the first ten seconds, thinning
 * over the minute after, and cold at the end of it. A wreck that smoked for ever would be a column
 * of particles per aeroplane anyone ever shot down, and the world fills up with those.
 *
 * <p>All of it is sent from the server through {@link Effects}, so all of it carries the five hundred
 * blocks a particle packet can be made to carry rather than the thirty-two it carries by default.
 * A fire nobody can see from the air is not worth setting.
 */
public final class WreckEffects {
    /** The flame itself: hotter and more orange than the ember an explosion throws off. */
    private static final int FLAME = 0xFF8A2A;
    /** Torn airframe, thrown out of a machine that has just come apart. */
    private static final int SCRAP = 0x7A736B;

    /** How long a wreck burns at its fiercest before it starts to go out, in ticks. */
    private static final int FIERCE_TICKS = 200;
    /** And how long until it is cold and stops smoking at all. Rather over a minute in total. */
    public static final int BURN_OUT_TICKS = 1400;

    /**
     * Speed, squared, above which a wreck is still coming down rather than lying where it landed.
     *
     * <p>Public because the machine itself watches the same figure: the moment it drops below this is
     * the moment the wreck arrived, and that is when {@link #impact} is worth drawing.
     */
    public static final double FALLING = 0.04;

    /** Ticks between puffs off a wreck at rest: this closely at its fiercest, this rarely at its last. */
    private static final int FAST_PUFF = 2;
    private static final int SLOW_PUFF = 9;

    /** How often a burning wreck is heard to crackle, in ticks. */
    private static final int CRACKLE_TICKS = 19;
    /**
     * How loud the fire is. A sound reaches {@code max(volume, 1) * 16} blocks at both ends, so this
     * is a burning machine heard from forty blocks off — near enough to walk up to, far enough to be
     * found by.
     */
    private static final float FIRE_VOLUME = 2.5F;

    /** How much of the machine's own length the fire is spread over. */
    private static final double SPREAD = 0.45;
    /** And how flat that spread is: a wreck is a long low thing, not a ball. */
    private static final double FLATNESS = 0.3;

    /**
     * How hard something has to arrive for the impact to be worth drawing, in blocks a tick. Below it
     * the wreck has settled onto the ground rather than met it, and there is nothing to see.
     */
    private static final double HARD_ARRIVAL = 0.35;

    // ------------------------------------------------------------------
    // The hit
    // ------------------------------------------------------------------

    /**
     * The moment the machine stops being one: fire out of the whole length of it, a wave, wreckage,
     * and a bang that carries.
     *
     * <p>The blast itself — the damage and the hole — is {@link Effects#blast}'s and has already
     * happened by the time this is called. What is here is only what it looks like.
     *
     * @param power the machine's own explosion figure, which sizes everything
     * @param reach how far the machine extends from its middle, in blocks, so that a tank's fire
     *              comes out of a tank and an aeroplane's out of an aeroplane
     * @param attitude which way it is lying, so the fire runs along the airframe rather than north
     */
    public static void destroyed(ServerLevel level, Vec3 at, Quaternionf attitude, float power, double reach) {
        // Bigger than the hole it digs, and on purpose. The explosion figure in a machine's file is
        // what it does to the ground, and it is a modest number because a shot-down aeroplane has no
        // business levelling a village. What it should *look* like is the size of the machine, so
        // the drawing is given the airframe as well and the crater is left alone.
        float sized = Mth.clamp(power + (float) reach * 0.4F, 1.0F, Effects.BIGGEST);
        Vec3 along = Attitude.toWorld(attitude, new Vec3(0.0, 0.0, 1.0));

        // Strung along the airframe. Three of them, at the nose, the middle and the tail: enough for
        // the fire to have the shape of the machine, few enough not to be three explosions.
        Effects.fireball(level, at, sized, Effects.EMBER);
        Effects.fireball(level, at.add(along.scale(reach * 0.55)), sized * 0.6F, Effects.EMBER);
        Effects.fireball(level, at.subtract(along.scale(reach * 0.55)), sized * 0.6F, Effects.EMBER);

        Effects.wave(level, at, sized);
        Effects.boom(level, at, sized);
        wreckage(level, at, sized, reach);
    }

    /** Torn airframe, thrown out hard and in every direction, and burning as it goes. */
    private static void wreckage(ServerLevel level, Vec3 at, float power, double reach) {
        int pieces = 12 + (int) (reach * 3.0);

        Effects.send(level, at, ModParticles.DEBRIS.get().of(SCRAP, 1.4F),
                pieces, reach * 0.3, 0.25 + power * 0.05);
        Effects.send(level, at, ModParticles.SPARK.get().of(Effects.EMBER, 1.2F),
                pieces, reach * 0.25, 0.3 + power * 0.06);
        Effects.send(level, at, ModParticles.FIRE.get().of(FLAME, 1.6F),
                6 + (int) reach, reach * 0.35, 0.08);
    }

    // ------------------------------------------------------------------
    // The fall, and the burn afterwards
    // ------------------------------------------------------------------

    /**
     * One tick of a wreck being a wreck. Server side, once a tick, for as long as there is anything
     * left to see.
     *
     * @param age how long it has been a wreck, in ticks. Everything about how hard it burns is this
     * @param velocity how it is moving, which is what tells a wreck still falling from one that has
     *                 arrived — and, for one still falling, which way to lay the smoke
     */
    public static void burn(ServerLevel level, Vec3 at, Quaternionf attitude, int age, Vec3 velocity,
            double reach) {
        if (age > BURN_OUT_TICKS) {
            return;
        }

        float heat = heat(age);
        Vec3 middle = at.add(Attitude.toWorld(attitude, new Vec3(0.0, reach * 0.15, 0.0)));

        if (velocity.lengthSqr() > FALLING) {
            trail(level, middle, velocity, heat, reach);

            return;
        }

        smoulder(level, middle, age, heat, reach);
    }

    /**
     * How fiercely a wreck of this age burns, from one to nothing.
     *
     * <p>Flat for the first ten seconds and then straight down over the minute after. The flat part
     * is what makes a fresh kill read as a fire rather than as something already going out; the ramp
     * is what stops the world filling up with permanent smoke columns.
     */
    private static float heat(int age) {
        if (age <= FIERCE_TICKS) {
            return 1.0F;
        }

        return 1.0F - (float) (age - FIERCE_TICKS) / (BURN_OUT_TICKS - FIERCE_TICKS);
    }

    /**
     * A machine coming down on fire: flame at the airframe, dense smoke at it, and the trail proper
     * laid behind it.
     *
     * <p>Every tick, and unapologetically. This lasts as long as the fall does — a few seconds — and
     * it is the one thing anybody watching a kill actually sees.
     *
     * <p>The trail is put where the machine <em>was</em> rather than where it is, which is the whole
     * trick: smoke does not travel with the aeroplane, it is left behind by it, and a puff spawned at
     * the nose every tick draws a line that leads the wreck instead of following it.
     */
    private static void trail(ServerLevel level, Vec3 at, Vec3 velocity, float heat, double reach) {
        float size = (float) Math.max(reach * 0.25, 1.0);

        Effects.send(level, at, ModParticles.FIRE.get().of(FLAME, size * (0.6F + heat)),
                1 + (int) (heat * 3.0F), reach * 0.2, 0.04);
        Effects.send(level, at, ModParticles.MOTOR_SMOKE.get().of(Effects.SOOT, size * 1.1F),
                2 + (int) (heat * 2.0F), reach * 0.15, 0.03);
        // Behind, and given the machine's own speed to carry: what is drawn is the air the wreck has
        // already passed through, still full of what came out of it.
        Effects.aimed(level, at.subtract(velocity), ModParticles.CONTRAIL.get().of(Effects.SOOT, size * 1.4F),
                velocity.scale(0.25));

        if (heat > 0.5F) {
            Effects.sparks(level, at, Effects.EMBER, 1.0F);
        }
    }

    /**
     * A wreck lying where it landed, burning itself out: licks of flame off the airframe and a column
     * of black smoke going up off it.
     *
     * <p>Thinned by how much fire is left rather than switched off at some threshold, so a wreck goes
     * out the way a fire goes out — the flame first, then the smoke, and the last of it a wisp — and
     * there is no tick on which it visibly stops.
     */
    private static void smoulder(ServerLevel level, Vec3 at, int age, float heat, double reach) {
        int every = Math.round(Mth.lerp(heat, SLOW_PUFF, FAST_PUFF));

        if (age % Math.max(every, 1) != 0) {
            return;
        }

        float size = (float) Math.max(reach * 0.22, 0.8);
        Vec3 spread = new Vec3(reach * SPREAD, reach * SPREAD * FLATNESS, reach * SPREAD);

        if (heat > 0.1F) {
            Effects.send(level, at, ModParticles.FIRE.get().of(FLAME, size * (0.5F + heat)),
                    1 + (int) (heat * 2.0F), spread, 0.02);
        }

        // The column. Given a shove upwards rather than left to drift, so it climbs off the wreck
        // instead of sitting on it: what tells somebody a mile off that there is a wreck down there
        // is the smoke standing up out of the trees, and smoke that only expands never gets there.
        Effects.send(level, at, ModParticles.BLAST_SMOKE.get().of(Effects.SOOT, size * (0.9F + heat * 0.8F)),
                1 + (int) (heat * 2.0F), spread, 0.02);
        Effects.aimed(level, at, ModParticles.BLAST_SMOKE.get().of(Effects.SOOT, size * 1.3F),
                new Vec3(0.0, 0.06 + heat * 0.10, 0.0));

        if (heat > 0.35F) {
            Effects.sparks(level, at, Effects.EMBER, heat * 0.5F);
        }

        // The crackle. Vanilla's own fire, which every client already has, played louder than a
        // campfire and no more often than a fire crackles.
        if (heat > 0.2F && age % CRACKLE_TICKS == 0) {
            RandomSource random = level.getRandom();

            level.playSound(null, at.x, at.y, at.z, SoundEvents.FIRE_AMBIENT, SoundSource.BLOCKS,
                    FIRE_VOLUME * heat, 0.6F + random.nextFloat() * 0.3F);
        }
    }

    // ------------------------------------------------------------------
    // The arrival
    // ------------------------------------------------------------------

    /**
     * A wreck meeting the ground: fire, a wall of dust, and a heavy noise.
     *
     * <p>Deliberately no explosion. Nothing here damages a block or hurts anybody — the blast that
     * wrote the machine off has already dug whatever hole it was going to dig, and letting the hulk
     * dig a second one where it lands would double every crater in the mod. What is wanted at this
     * moment is the noise and the dust, and those are free.
     *
     * @param speed how fast it arrived, in blocks a tick, which sizes all of it
     */
    public static void impact(ServerLevel level, Vec3 at, double speed, double reach) {
        if (speed < HARD_ARRIVAL) {
            return;
        }

        float force = (float) Mth.clamp(speed * reach * 0.5, 1.0, Effects.BIGGEST);

        Effects.fireball(level, at, force, Effects.EMBER);
        Effects.wave(level, at, force);
        Effects.boom(level, at, force * 0.6F);
        Effects.send(level, at, ModParticles.DEBRIS.get().of(SCRAP, 1.2F),
                8 + (int) (force * 2.0F), reach * 0.2, 0.12 + speed * 0.1);
    }

    private WreckEffects() {
    }
}
