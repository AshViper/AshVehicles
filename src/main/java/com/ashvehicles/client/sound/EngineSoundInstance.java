package com.ashvehicles.client.sound;

import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.entity.VehicleEntityBase;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/**
 * One vehicle's engine, as heard by this client.
 *
 * <p>A single recording, looped, played louder and faster as the throttle goes up. The recording is
 * whatever {@link EngineSounds} resolved for the vehicle when this was started; that includes the
 * mod's default for vehicle with no recording of their own.
 *
 * <p>Both volume and pitch chase their targets rather than jumping to them, so opening the throttle
 * sounds like an engine spooling up rather than a switch being thrown, and closing it lets the note
 * die away.
 *
 * <p>Distance and the giving back of the channel are {@link EntitySoundInstance}'s, but the range is
 * this vehicle's own, out of its file, rather than one the sound engine could have known about.
 */
public class EngineSoundInstance extends EntitySoundInstance<VehicleEntityBase> {
    /** Fraction of the gap between the current and target volume closed each tick. */
    private static final float VOLUME_RATE = 0.12F;
    /** The same for pitch, slower: spooling a turbine takes a moment. */
    private static final float PITCH_RATE = 0.05F;
    /** Ticks of silence before the sound gives its channel back. */
    private static final int SILENT_TICKS_BEFORE_STOP = 60;
    /**
     * What full reheat adds to the note, over and above the top of the file's own pitch range.
     *
     * <p>Added rather than folded into the range, because the range is what the engine can do and
     * this is not the engine. The lever is already against its stop when the burner lights: the
     * turbine is turning no faster and nothing about the recording should suggest it is. What
     * changes is that there is now a second fire behind it, and what that sounds like is the same
     * note with a harder edge on it.
     */
    private static final float AFTERBURNER_PITCH = 0.18F;
    /** And what it adds to the loudness, which is the half of it anybody hears from outside. */
    private static final float AFTERBURNER_GAIN = 0.35F;

    /** Loudness before distance is taken into account, in [0, 1] of the file's volume. */
    private float gain;
    private float currentPitch;

    public EngineSoundInstance(VehicleEntityBase vehicle, SoundEvent sound) {
        super(vehicle, sound, SoundSource.NEUTRAL, SILENT_TICKS_BEFORE_STOP);
        // Start on the right note: a machine that comes into earshot at full throttle should not
        // sweep up from idle first.
        this.currentPitch = targetPitch(vehicle, vehicle.soundSetup());
        this.pitch = this.currentPitch;
    }

    /**
     * Whether the engine is turning: running up on the ground, or in the air at any setting.
     *
     * <p>Read off {@code getEngineNote} rather than off the throttle, so that a helicopter is heard
     * from the moment its rotor starts to wind up. Its collective is still at the bottom then and
     * will be for several seconds, and a machine that stayed silent through the whole start-up would
     * be a strange thing to be standing next to.
     *
     * <p>A wreck is answered before either of those is asked. Its engine note is already nothing,
     * but a write-off still on its way down is moving, and movement alone is enough to hold the
     * sound open — so a burnt-out airframe would go on being heard all the way to the ground.
     */
    public static boolean isEngineRunning(VehicleEntityBase vehicle) {
        return !vehicle.isRemoved() && !vehicle.isWrecked()
                && (vehicle.getEngineNote() > 0.001F || vehicle.getVelocity().lengthSqr() > 0.01);
    }

    /** How much of full volume is left at this distance, over the range the vehicle's file names. */
    public static float falloff(VehicleEntityBase vehicle, VehicleChassis.Sound setup) {
        return falloff(vehicle, setup.range());
    }

    private static float targetPitch(VehicleEntityBase vehicle, VehicleChassis.Sound setup) {
        return Mth.lerp(vehicle.getEngineNote(), setup.pitchMin(), setup.pitchMax())
                + vehicle.getAfterburner() * AFTERBURNER_PITCH;
    }

    @Override
    protected void update() {
        VehicleEntityBase vehicle = this.entity();
        VehicleChassis.Sound setup = vehicle.soundSetup();

        boolean running = isEngineRunning(vehicle);
        // Past one in reheat, and deliberately. The figure is a multiplier on the file's own
        // volume rather than a level, and a burner is genuinely louder than the engine it is bolted
        // to — loud enough to be heard from further off, which is what a volume over one buys.
        float targetGain = running
                ? Mth.lerp(vehicle.getEngineNote(), setup.idleVolume(), 1.0F)
                        * (1.0F + vehicle.getAfterburner() * AFTERBURNER_GAIN)
                : 0.0F;
        this.gain = approach(this.gain, targetGain, VOLUME_RATE);
        this.currentPitch = approach(this.currentPitch, targetPitch(vehicle, setup), PITCH_RATE);

        float falloff = this.falloff(setup.range());
        this.volume = setup.volume() * this.gain * falloff;
        this.pitch = this.currentPitch;

        // Nothing to hear once the engine is off and faded out, or once the vehicle is beyond
        // earshot. EngineSounds will start another when that changes.
        this.heard(running ? falloff > 0.0F : this.gain > SILENCE);
    }
}
