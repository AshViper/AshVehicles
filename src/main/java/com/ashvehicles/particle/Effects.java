package com.ashvehicles.particle;

import javax.annotation.Nullable;

import com.ashvehicles.network.BlastSoundPayload;
import com.ashvehicles.registry.ModParticles;

import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Fire, smoke and noise, for anything in the mod that makes any.
 *
 * <p>Everything here was written for the weapons and then wanted by something that is not a weapon:
 * an aeroplane coming apart in the air makes the same fireball a warhead does, out of the same
 * particles, and is heard over the same distance in the same way. What is left in
 * {@link com.ashvehicles.weapon.WeaponEffects} is the part that really is a weapon's — what a round
 * does to the block it hits, what a barrel does as the round leaves it — and what a wreck does is in
 * {@code WreckEffects}. This is the shared middle.
 *
 * <p>The one thing worth knowing about this file is why every particle packet goes out with the
 * long-distance flag set. Ordinary particles are sent to nobody more than thirty-two blocks away,
 * and thrown away again by a client that somehow receives one from further off than that. Thirty-two
 * blocks is a sensible distance for a torch and a nonsensical one for ordnance: a bomb is aimed from
 * a thousand feet up, and the whole question a pilot has after releasing it is whether it went off,
 * and where. The flag lifts both limits to five hundred and twelve blocks, which is the protocol's
 * own ceiling.
 *
 * <p>It is also what makes a detonation beyond the loaded world visible at all. Nothing here needs
 * the world: the particles are told where to be and what colour to be, and
 * {@link com.ashvehicles.client.particle.WeaponParticle} sees to it that they are lit out there
 * rather than drawn in black.
 *
 * <p>The bang cannot be sent that way at all — a sound has no long-distance flag, and its reach is
 * fixed at {@code volume * 16} blocks at both ends — so it goes as the mod's own packet and is timed
 * and shaped by the client. See {@link BlastSoundPayload} and
 * {@link com.ashvehicles.client.sound.BlastSounds}.
 */
public final class Effects {
    /** Soot: the colour of the cloud a blast leaves, whatever the weapon's own colour is. */
    public static final int SOOT = 0x3A3631;
    /** And of what it throws off, which is burning rather than glowing. */
    public static final int EMBER = 0xFFB449;
    /**
     * The dust a blast throws up.
     *
     * <p>White, and the same white wherever it goes off. It used to be the colour of whatever the
     * bomb landed on — sand over a desert, grey over stone — which is what dust really does and
     * which looked, in practice, like the explosion changing colour from one target to the next.
     * A wall of dust reads as a wall of dust; what it was made of is not worth that.
     */
    public static final int DUST = 0xFFFFFF;

    /** The largest blast that is drawn any bigger, or heard any further off, than the last one. */
    public static final float BIGGEST = 12.0F;

    /**
     * Nothing the mod sets off leaves anything burning.
     *
     * <p>An explosion in Minecraft can scatter fire over everything it did not destroy, and half a
     * tonne of high explosive scatters it a long way. What that gives is not a battlefield but a
     * forest fire that spreads for the rest of the evening across ground nobody is looking at any
     * more — every bomb quietly setting light to a landscape the pilot has already flown away from.
     * The blast, the crater and the wave are the weapon; the fire afterwards is somebody else's mod.
     */
    private static final boolean NO_FIRE = false;

    /**
     * How far the wave runs, in blocks, for every point of blast.
     *
     * <p>This is the whole of what decides its size, so a warhead twice the size throws a ring twice
     * as wide and everything that goes off throws one of some size: a rocket's is a few blocks
     * across, a heavy bomb's covers a hundred feet of ground. Turn this one figure up and every
     * weapon's wave grows with it, in proportion.
     *
     * <p>Further than the blast itself reaches, deliberately. What is drawn here is the dust an
     * explosion throws outwards, which in life runs well beyond anything it actually damages.
     */
    private static final float WAVE_REACH = 2.8F;
    /** Held clear of the ground it is running over, so the ring does not fight it for the pixels. */
    private static final double WAVE_LIFT = 0.35;

    /**
     * The one sound the engine will not play and will not complain about not playing.
     *
     * <p>Vanilla puts the explosion's noise, its smoke and its knockback in a single packet, and
     * sends the lot to anyone within sixty-four blocks. The knockback has to go, so the packet has
     * to go, so the only way to be rid of the noise is to ask for a noise there is none of — which
     * is what {@code minecraft:intentionally_empty} is for.
     */
    private static final Holder<SoundEvent> SILENCE = Holder.direct(SoundEvent.createVariableRangeEvent(
            ResourceLocation.withDefaultNamespace("intentionally_empty")));

    /** Fire, then smoke, then fragments: the three things anyone watching an explosion sees. */
    public static void fireball(ServerLevel level, Vec3 at, float power, int colour) {
        send(level, at, ModParticles.BLAST.get().of(colour, power * 0.34F),
                4 + (int) (power * 1.6F), power * 0.16, power * 0.035);
        send(level, at, ModParticles.BLAST_SMOKE.get().of(SOOT, power * 0.42F),
                8 + (int) (power * 3.0F), power * 0.28, power * 0.022);
        sparks(level, at, EMBER, power);
    }

    /**
     * The blast wave: one particle, which draws the ring and raises the dust for itself. See
     * {@link com.ashvehicles.client.particle.ShockwaveParticle}.
     *
     * <p>The colour it carries is the dust's; the ring whitens itself against it, being squeezed air
     * rather than ground. The size it carries is how far the front is to run, which is the blast's
     * own size and nothing else — see {@link #WAVE_REACH} — so every warhead throws a wave in
     * proportion to itself rather than only the ones over some threshold.
     */
    public static void wave(ServerLevel level, Vec3 at, float power) {
        send(level, at.add(0.0, WAVE_LIFT, 0.0),
                ModParticles.SHOCKWAVE.get().of(DUST, power * WAVE_REACH), 1, 0.0, 0.0);
    }

    /**
     * The bang, sent as the mod's own packet so that a client can time it, place it and shape it
     * for itself. Everyone who could hear it is told; the rest are not troubled. See
     * {@link BlastSoundPayload}.
     */
    public static void boom(ServerLevel level, Vec3 at, float power) {
        double carry = BlastSoundPayload.carry(power);
        BlastSoundPayload payload = new BlastSoundPayload(at.x, at.y, at.z, power);

        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(at) < carry * carry) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    public static void sparks(ServerLevel level, Vec3 at, int colour, float power) {
        send(level, at, ModParticles.SPARK.get().of(colour, 1.0F),
                5 + (int) (power * 3.5F), 0.05, 0.09 + power * 0.05);
    }

    /**
     * The blast proper: the damage and the knockback, with vanilla's own noise and its own puff of
     * smoke taken off it.
     *
     * <p>The particle slots are given the mod's fireball, since the client draws one of them whether
     * or not the server would like it to; and the sound slot is given {@link #SILENCE}, because the
     * bang belongs to {@link #boom} and having both would be one explosion heard twice, once on time
     * and once late.
     *
     * @param power how much damage it does and how big a hole it leaves, unclamped: the drawing is
     *              capped at {@link #BIGGEST} so that the largest warheads do not fill the screen,
     *              but what they do to the ground is their own business
     */
    public static void blast(ServerLevel level, @Nullable Entity source, Vec3 at, float power, int colour) {
        float drawn = Mth.clamp(power, 1.0F, BIGGEST);
        ParticleOptions fireball = ModParticles.BLAST.get().of(colour, drawn * 0.3F);

        level.explode(source, Explosion.getDefaultDamageSource(level, source), null,
                at.x, at.y, at.z, power, NO_FIRE, Level.ExplosionInteraction.MOB,
                fireball, fireball, SILENCE);
    }

    /**
     * A scatter of particles: {@code count} of them, thrown out to {@code spread} blocks and given a
     * random direction at {@code speed}.
     *
     * <p>One packet per player, because the long-distance flag can only be set on the per-player
     * call. The server drops it for anyone out of range itself, so this is a loop over the player
     * list and a handful of packets, not a broadcast.
     */
    public static void send(ServerLevel level, Vec3 at, TintedParticleOption particle, int count,
            double spread, double speed) {
        for (ServerPlayer player : level.players()) {
            level.sendParticles(player, particle, true, at.x, at.y, at.z, count, spread, spread, spread, speed);
        }
    }

    /**
     * The same, for something whose spread is not the same on every axis.
     *
     * <p>A machine is a long low thing. Fire scattered over a sphere the size of an aeroplane's
     * wingspan puts as much of it above and below the aircraft as along it, which reads as a
     * fireball the aeroplane happens to be inside rather than as an aeroplane on fire.
     */
    public static void send(ServerLevel level, Vec3 at, TintedParticleOption particle, int count,
            Vec3 spread, double speed) {
        for (ServerPlayer player : level.players()) {
            level.sendParticles(player, particle, true, at.x, at.y, at.z, count,
                    spread.x, spread.y, spread.z, speed);
        }
    }

    /**
     * One particle with a velocity of its own rather than a scatter with a random one.
     *
     * <p>Vanilla's particle packet means one thing or the other by the same three numbers: a count
     * above zero makes them a spread and the direction random, and a count of zero makes them the
     * velocity. Both are wanted — a fireball scatters, a smoke trail streams — so both are here
     * under names that say which is which.
     */
    public static void aimed(ServerLevel level, Vec3 at, TintedParticleOption particle, Vec3 velocity) {
        for (ServerPlayer player : level.players()) {
            level.sendParticles(player, particle, true, at.x, at.y, at.z, 0,
                    velocity.x, velocity.y, velocity.z, 1.0);
        }
    }

    private Effects() {
    }
}
