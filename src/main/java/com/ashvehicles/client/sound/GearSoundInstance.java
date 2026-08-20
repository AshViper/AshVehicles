package com.ashvehicles.client.sound;

import com.ashvehicles.entity.AircraftEntity;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * One aircraft's undercarriage, for as long as it is travelling.
 *
 * <p>The gear is the one thing on an aeroplane that makes a noise only while it is moving, and it
 * moves for a known number of ticks — {@code landing_gear.cycle_ticks} out of the aircraft's file —
 * so there is nothing to work out here beyond whether it is on its way. It is a loop rather than a
 * single recording of a whole cycle because the cycle is not the same length on every aircraft, and
 * because a pilot who changes their mind halfway through one turns the gear round without waiting
 * for it to finish.
 *
 * <p>It starts the moment the lever moves, at full volume: a gear motor does not spool up. It fades
 * out instead of stopping dead, so that the loop is not cut off mid-cycle when the legs lock.
 */
public class GearSoundInstance extends EntitySoundInstance<AircraftEntity> {
    /** How far the gear is heard. Short: it is a motor in a wheel well, not an engine. */
    static final double RANGE = 48.0;

    /** How loud, before distance. */
    private static final float VOLUME = 0.8F;
    /** Fraction of what is left of the volume shed each tick once the legs are down or up. */
    private static final float FADE_RATE = 0.3F;
    /** Ticks of silence before the sound gives its channel back. */
    private static final int SILENT_TICKS_BEFORE_STOP = 10;

    private float gain = 1.0F;

    public GearSoundInstance(AircraftEntity aircraft, SoundEvent sound) {
        super(aircraft, sound, SoundSource.NEUTRAL, SILENT_TICKS_BEFORE_STOP);
    }

    @Override
    protected void update() {
        boolean travelling = !this.entity().isGearSettled();
        this.gain = travelling ? 1.0F : approach(this.gain, 0.0F, FADE_RATE);

        float falloff = this.falloff(RANGE);
        this.volume = VOLUME * this.gain * falloff;

        this.heard(travelling ? falloff > 0.0F : this.gain > SILENCE);
    }
}
