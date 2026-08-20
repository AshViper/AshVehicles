package com.ashvehicles.client.sound;

import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.entity.AircraftEntity;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/**
 * One aircraft's engine, as heard by this client.
 *
 * <p>A single recording, looped, played louder and faster as the throttle goes up. The recording is
 * whatever {@link EngineSounds} resolved for the aircraft when this was started; that includes the
 * mod's default for aircraft with no recording of their own.
 *
 * <p>Both volume and pitch chase their targets rather than jumping to them, so opening the throttle
 * sounds like an engine spooling up rather than a switch being thrown, and closing it lets the note
 * die away.
 *
 * <p>Distance and the giving back of the channel are {@link EntitySoundInstance}'s, but the range is
 * this aircraft's own, out of its file, rather than one the sound engine could have known about.
 */
public class EngineSoundInstance extends EntitySoundInstance<AircraftEntity> {
    /** Fraction of the gap between the current and target volume closed each tick. */
    private static final float VOLUME_RATE = 0.12F;
    /** The same for pitch, slower: spooling a turbine takes a moment. */
    private static final float PITCH_RATE = 0.05F;
    /** Ticks of silence before the sound gives its channel back. */
    private static final int SILENT_TICKS_BEFORE_STOP = 60;

    /** Loudness before distance is taken into account, in [0, 1] of the file's volume. */
    private float gain;
    private float currentPitch;

    public EngineSoundInstance(AircraftEntity aircraft, SoundEvent sound) {
        super(aircraft, sound, SoundSource.NEUTRAL, SILENT_TICKS_BEFORE_STOP);
        // Start on the right note: an aircraft that comes into earshot at full throttle should not
        // sweep up from idle first.
        this.currentPitch = targetPitch(aircraft, aircraft.getStats().sound());
        this.pitch = this.currentPitch;
    }

    /** Whether the engine is turning: running up on the ground, or in the air at any setting. */
    public static boolean isEngineRunning(AircraftEntity aircraft) {
        return !aircraft.isRemoved()
                && (aircraft.getThrottle() > 0.001F || aircraft.getVelocity().lengthSqr() > 0.01);
    }

    /** How much of full volume is left at this distance, over the range the aircraft's file names. */
    public static float falloff(AircraftEntity aircraft, AircraftDefinition.SoundSetup setup) {
        return falloff(aircraft, setup.range());
    }

    private static float targetPitch(AircraftEntity aircraft, AircraftDefinition.SoundSetup setup) {
        return Mth.lerp(aircraft.getThrottle(), setup.pitchMin(), setup.pitchMax());
    }

    @Override
    protected void update() {
        AircraftEntity aircraft = this.entity();
        AircraftDefinition.SoundSetup setup = aircraft.getStats().sound();

        boolean running = isEngineRunning(aircraft);
        float targetGain = running ? Mth.lerp(aircraft.getThrottle(), setup.idleVolume(), 1.0F) : 0.0F;
        this.gain = approach(this.gain, targetGain, VOLUME_RATE);
        this.currentPitch = approach(this.currentPitch, targetPitch(aircraft, setup), PITCH_RATE);

        float falloff = this.falloff(setup.range());
        this.volume = setup.volume() * this.gain * falloff;
        this.pitch = this.currentPitch;

        // Nothing to hear once the engine is off and faded out, or once the aircraft is beyond
        // earshot. EngineSounds will start another when that changes.
        this.heard(running ? falloff > 0.0F : this.gain > SILENCE);
    }
}
