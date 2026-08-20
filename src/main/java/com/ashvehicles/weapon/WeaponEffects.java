package com.ashvehicles.weapon;

import javax.annotation.Nullable;

import com.ashvehicles.network.BlastSoundPayload;
import com.ashvehicles.particle.TintedParticleOption;
import com.ashvehicles.registry.ModParticles;

import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * What a weapon looks and sounds like where it lands, sent from the server so that everyone who
 * could see or hear it gets the same thing.
 *
 * <p>The one thing worth knowing about this file is why every particle packet goes out with the
 * long-distance flag set. Ordinary particles are sent to nobody more than thirty-two blocks away, and thrown away
 * again by a client that somehow receives one from further off than that. Thirty-two blocks is a
 * sensible distance for a torch and a nonsensical one for ordnance: a bomb is aimed from a thousand
 * feet up, and the whole question a pilot has after releasing it is whether it went off, and where.
 * The flag lifts both limits to five hundred and twelve blocks, which is the protocol's own ceiling.
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
public final class WeaponEffects {
    /** Soot: the colour of the cloud a blast leaves, whatever the weapon's own colour is. */
    private static final int SOOT = 0x3A3631;
    /** And of what it throws off, which is burning rather than glowing. */
    private static final int EMBER = 0xFFB449;

    /** The largest blast that is drawn any bigger, or heard any further off, than the last one. */
    private static final float BIGGEST = 12.0F;

    /**
     * The smallest blast that throws a wave worth drawing. Below it there is nothing to see: a
     * warhead the size of a missile's does its work in a few blocks, and a ring racing out of one
     * would be an aeroplane's worth of effect for a hand grenade's worth of explosive. Bombs are
     * comfortably over it; a rocket is comfortably under.
     */
    private static final float WAVE = 4.0F;
    /** How far the wave runs, per point of blast. */
    private static final float WAVE_REACH = 2.2F;
    /** Held clear of the ground it is running over, so the ring does not fight it for the pixels. */
    private static final double WAVE_LIFT = 0.35;
    /**
     * The dust a blast throws up.
     *
     * <p>White, and the same white wherever it goes off. It used to be the colour of whatever the
     * bomb landed on — sand over a desert, grey over stone — which is what dust really does and
     * which looked, in practice, like the explosion changing colour from one target to the next.
     * A wall of dust reads as a wall of dust; what it was made of is not worth that.
     */
    private static final int DUST = 0xFFFFFF;

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

    /**
     * Everything that happens where a round lands: the blast if it carries one, the sparks either
     * way, and a scatter of whatever it went into.
     *
     * @param at where it went off
     * @param round the round that did it, for its size and its colour
     * @param struck the block it hit, if it hit one
     */
    public static void detonation(ServerLevel level, Vec3 at, WeaponDefinition.Projectile round,
            @Nullable BlockState struck) {
        float power = Mth.clamp(round.explosion(), 0.0F, BIGGEST);

        if (power > 0.0F) {
            fireball(level, at, power, round.tracer());
            boom(level, at, power);

            if (power >= WAVE) {
                wave(level, at, power);
            }
        } else {
            sparks(level, at, round.tracer(), 1.0F);
        }

        if (struck != null && !struck.isAir()) {
            debris(level, at, struck, Math.max(power, 1.0F));
        }
    }

    /** Fire, then smoke, then fragments: the three things anyone watching an explosion sees. */
    private static void fireball(ServerLevel level, Vec3 at, float power, int tracer) {
        send(level, at, ModParticles.BLAST.get().of(tracer, power * 0.34F),
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
     * rather than ground.
     */
    private static void wave(ServerLevel level, Vec3 at, float power) {
        send(level, at.add(0.0, WAVE_LIFT, 0.0),
                ModParticles.SHOCKWAVE.get().of(DUST, power * WAVE_REACH), 1, 0.0, 0.0);
    }

    /**
     * The bang, sent as the mod's own packet so that a client can time it, place it and shape it
     * for itself. Everyone who could hear it is told; the rest are not troubled. See
     * {@link BlastSoundPayload}.
     */
    private static void boom(ServerLevel level, Vec3 at, float power) {
        double carry = BlastSoundPayload.carry(power);
        BlastSoundPayload payload = new BlastSoundPayload(at.x, at.y, at.z, power);

        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(at) < carry * carry) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    /**
     * The blast proper: the damage, the fire and the knockback, with vanilla's own noise and its own
     * puff of smoke taken off it.
     *
     * <p>The particle slots are given the mod's fireball, since the client draws one of them whether
     * or not the server would like it to; and the sound slot is given {@link #SILENCE}, because the
     * bang belongs to {@link #boom} and having both would be one explosion heard twice, once on time
     * and once late.
     */
    public static void blast(ServerLevel level, Entity source, Vec3 at, WeaponDefinition.Projectile round) {
        float power = Mth.clamp(round.explosion(), 1.0F, BIGGEST);
        ParticleOptions fireball = ModParticles.BLAST.get().of(round.tracer(), power * 0.3F);

        level.explode(source, Explosion.getDefaultDamageSource(level, source), null,
                at.x, at.y, at.z, round.explosion(), round.fire(), Level.ExplosionInteraction.MOB,
                fireball, fireball, SILENCE);
    }

    private static void sparks(ServerLevel level, Vec3 at, int colour, float power) {
        send(level, at, ModParticles.SPARK.get().of(colour, 1.0F),
                5 + (int) (power * 3.5F), 0.05, 0.09 + power * 0.05);
    }

    /** Chips of whatever was hit, in the colour of the block they came off. */
    private static void debris(ServerLevel level, Vec3 at, BlockState struck, float power) {
        int colour = struck.getMapColor(level, BlockPos.containing(at)).col;

        send(level, at, ModParticles.DEBRIS.get().of(colour, 1.0F),
                5 + (int) (power * 2.5F), 0.08, 0.08 + power * 0.03);
    }

    /**
     * One packet per player, because the long-distance flag can only be set on the per-player call.
     * The server drops it for anyone out of range itself, so this is a loop over the player list and
     * a handful of packets, not a broadcast.
     */
    private static void send(ServerLevel level, Vec3 at, TintedParticleOption particle, int count,
            double spread, double speed) {
        for (ServerPlayer player : level.players()) {
            level.sendParticles(player, particle, true, at.x, at.y, at.z, count, spread, spread, spread, speed);
        }
    }

    private WeaponEffects() {
    }
}
