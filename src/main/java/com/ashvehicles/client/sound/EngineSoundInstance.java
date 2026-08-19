package com.ashvehicles.client.sound;

import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.entity.AircraftEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

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
 * <p>Distance is handled here rather than by the sound engine, because the range comes from the
 * aircraft's file and the sound engine only knows the one written into {@code sounds.json}. It also
 * means the sound stops itself once nobody can hear it, freeing the channel, and {@link
 * EngineSounds} starts a fresh one when the aircraft comes back into earshot.
 */
public class EngineSoundInstance extends AbstractTickableSoundInstance {
    /** Fraction of the gap between the current and target volume closed each tick. */
    private static final float VOLUME_RATE = 0.12F;
    /** The same for pitch, slower: spooling a turbine takes a moment. */
    private static final float PITCH_RATE = 0.05F;
    /** Ticks of silence before the sound gives its channel back. */
    private static final int SILENT_TICKS_BEFORE_STOP = 60;
    /** Below this the fade-out is treated as finished. */
    private static final float SILENCE = 0.004F;

    private final AircraftEntity aircraft;
    /** Loudness before distance is taken into account, in [0, 1] of the file's volume. */
    private float gain;
    private float currentPitch;
    private int silentTicks;

    public EngineSoundInstance(AircraftEntity aircraft, SoundEvent sound) {
        super(sound, SoundSource.NEUTRAL, SoundInstance.createUnseededRandom());
        this.aircraft = aircraft;
        this.looping = true;
        this.delay = 0;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.volume = 0.0F;
        // Start on the right note: an aircraft that comes into earshot at full throttle should not
        // sweep up from idle first.
        this.currentPitch = targetPitch(aircraft, aircraft.getStats().sound());
        this.pitch = this.currentPitch;
        this.moveToAircraft();
    }

    /** Whether the engine is turning: running up on the ground, or in the air at any setting. */
    public static boolean isEngineRunning(AircraftEntity aircraft) {
        return !aircraft.isRemoved()
                && (aircraft.getThrottle() > 0.001F || aircraft.getVelocity().lengthSqr() > 0.01);
    }

    /** How much of full volume is left at this distance from the listener, in [0, 1]. */
    public static float falloff(AircraftEntity aircraft, AircraftDefinition.SoundSetup setup) {
        Vec3 listener = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double distance = listener.distanceTo(aircraft.position());

        return (float) Mth.clamp(1.0 - distance / Math.max(setup.range(), 1.0E-3), 0.0, 1.0);
    }

    private static float targetPitch(AircraftEntity aircraft, AircraftDefinition.SoundSetup setup) {
        return Mth.lerp(aircraft.getThrottle(), setup.pitchMin(), setup.pitchMax());
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public boolean canPlaySound() {
        return !this.aircraft.isSilent();
    }

    @Override
    public void tick() {
        if (this.aircraft.isRemoved() || this.aircraft.level() != Minecraft.getInstance().level) {
            this.stop();
            return;
        }

        AircraftDefinition.SoundSetup setup = this.aircraft.getStats().sound();
        this.moveToAircraft();

        boolean running = isEngineRunning(this.aircraft);
        float targetGain = running ? Mth.lerp(this.aircraft.getThrottle(), setup.idleVolume(), 1.0F) : 0.0F;
        this.gain += (targetGain - this.gain) * VOLUME_RATE;
        this.currentPitch += (targetPitch(this.aircraft, setup) - this.currentPitch) * PITCH_RATE;

        float falloff = falloff(this.aircraft, setup);
        this.volume = setup.volume() * this.gain * falloff;
        this.pitch = this.currentPitch;

        // Give the channel back once there is nothing to hear: engine off and faded out, or the
        // aircraft has gone beyond earshot. EngineSounds will start another when that changes.
        boolean audible = running ? falloff > 0.0F : this.gain > SILENCE;
        this.silentTicks = audible ? 0 : this.silentTicks + 1;

        if (this.silentTicks > SILENT_TICKS_BEFORE_STOP) {
            this.stop();
        }
    }

    private void moveToAircraft() {
        this.x = this.aircraft.getX();
        this.y = this.aircraft.getY();
        this.z = this.aircraft.getZ();
    }
}
