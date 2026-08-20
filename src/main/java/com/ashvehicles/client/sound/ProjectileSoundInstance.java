package com.ashvehicles.client.sound;

import javax.annotation.Nullable;

import com.ashvehicles.entity.RocketEntity;
import com.ashvehicles.weapon.WeaponDefinition;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/**
 * What a weapon sounds like on its way to where it is going.
 *
 * <p>Two things it can be, and which one is decided by the weapon rather than by the entity: the
 * noise a rocket or a missile makes for the whole of its flight, loudest while the motor is still
 * pushing, or the air past something falling, which is a bomb for the whole of the way down. A gun's
 * round makes neither; it is already there.
 *
 * <p>Both are loops that follow the round, and both are worth having only because the sound engine's
 * own sixty-four blocks are useless here. A missile crosses that in two seconds and a bomb is
 * released from higher than that above the target; if either is to be heard at all it has to be
 * heard from much further off, which is why {@link EntitySoundInstance} does the distance.
 */
public class ProjectileSoundInstance extends EntitySoundInstance<RocketEntity> {
    /** Ticks of silence before the sound gives its channel back. */
    private static final int SILENT_TICKS_BEFORE_STOP = 20;

    private final Kind kind;
    /** Loudness before distance is taken into account, in [0, 1] of the kind's volume. */
    private float gain;

    public ProjectileSoundInstance(RocketEntity projectile, SoundEvent sound, Kind kind) {
        super(projectile, sound, SoundSource.NEUTRAL, SILENT_TICKS_BEFORE_STOP);
        this.kind = kind;
        // At its full loudness from the first tick rather than swelling into it. A motor is already
        // burning when it leaves the rail, and a round that has to fade up over a quarter of a second
        // has spent that quarter of a second travelling eight blocks a tick away from the listener:
        // by the time it was loud it was too far off to hear.
        this.gain = kind.targetGain(projectile);
    }

    @Override
    protected void update() {
        this.gain = approach(this.gain, this.kind.targetGain(this.entity()), this.kind.rate);

        float falloff = this.falloff(this.kind.range);
        this.volume = this.kind.volume * this.gain * falloff;
        this.pitch = this.kind.pitch(this.gain);

        this.heard(falloff > 0.0F && this.gain > SILENCE);
    }

    /**
     * The sound a weapon makes in flight, and everything that is different about the two of them.
     *
     * @param role the tail of the name a weapon's own recording goes under, {@code weapon.<name>.<role>}
     * @param fallback what everything without a recording of its own uses
     * @param range how far it is heard, in blocks
     * @param volume how loud it is at nothing
     * @param rate fraction of the gap to its target loudness closed each tick
     */
    public enum Kind {
        /**
         * A motor while it burns, and the air past the thing afterwards.
         *
         * <p>It does not stop at burnout. A rocket motor burns for a second, and a missile is in the
         * air for ten or twenty — cutting the sound with the motor left the whole interesting part of
         * the flight silent, which is not what anybody means by a flight sound. So the note drops
         * back at burnout, to what something crossing thirty blocks a tick sounds like when nothing
         * is pushing it, and stays there until it hits.
         */
        MOTOR(ModSounds.FLIGHT_ROLE, ModSounds.FLIGHT, 480.0, 0.9F, 0.25F) {
            @Override
            float targetGain(RocketEntity projectile) {
                return projectile.isBurning() ? 1.0F : COASTING;
            }

            @Override
            float pitch(float gain) {
                return 1.0F;
            }
        },
        /**
         * The air past something falling: quiet where it is let go, and rising the whole way down in
         * both loudness and pitch as gravity has more and more of it. Measured against how fast it is
         * going down rather than how fast it is going, because a bomb leaves with the whole of the
         * aeroplane's speed and none of that is the sound of it falling.
         */
        FALL(ModSounds.FALL_ROLE, ModSounds.FALL, 400.0, 1.0F, 0.15F) {
            @Override
            float targetGain(RocketEntity projectile) {
                return Mth.clamp((float) -projectile.getDeltaMovement().y / FULL_FALL, 0.0F, 1.0F);
            }

            @Override
            float pitch(float gain) {
                return Mth.lerp(gain, LOW_PITCH, HIGH_PITCH);
            }
        };

        /** How much of the motor's own note is left once it has burnt out and is merely travelling. */
        private static final float COASTING = 0.55F;
        /** Falling this fast, in blocks a tick, is as loud and as high as the whistle gets. */
        private static final float FULL_FALL = 2.0F;
        private static final float LOW_PITCH = 0.75F;
        private static final float HIGH_PITCH = 1.15F;

        final String role;
        final ResourceLocation fallback;
        final double range;
        final float volume;
        final float rate;

        Kind(String role, ResourceLocation fallback, double range, float volume, float rate) {
            this.role = role;
            this.fallback = fallback;
            this.range = range;
            this.volume = volume;
            this.rate = rate;
        }

        /**
         * Which of these a weapon makes in flight, or null for one that makes neither.
         *
         * <p>A rocket or a missile with nothing pushing it falls into the last case rather than the
         * first: a motor that does not burn has no note, and the round is coasting from the moment it
         * leaves the rail.
         */
        @Nullable
        public static Kind of(WeaponDefinition weapon) {
            if (weapon.isDropped()) {
                return FALL;
            }

            return weapon.type() != WeaponDefinition.Type.GUN && weapon.projectile().hasMotor() ? MOTOR : null;
        }

        abstract float targetGain(RocketEntity projectile);

        abstract float pitch(float gain);
    }
}
