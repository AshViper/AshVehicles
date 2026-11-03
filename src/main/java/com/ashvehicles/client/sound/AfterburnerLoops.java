package com.ashvehicles.client.sound;

import javax.annotation.Nullable;

import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.vehicle.VehicleChassis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * バーナーが点いている間ずっと鳴る轟音。点火の瞬間の一発は {@link AfterburnerSounds} が鳴らし、こちらはその後を
 * 支える。
 *
 * <p>録音は {@code engine.<機体名>.afterburner.loop}、無ければ {@code engine.afterburner.loop}。どちらも
 * 無ければ鳴らさない。ループにまともな代役は無い。操縦席の一人称では {@code .cockpit} 版を絞って鳴らす。
 */
public final class AfterburnerLoops {
    private static final int RETRY_TICKS = 5;
    private static final String LOOP = "." + ModSounds.AFTERBURNER_ROLE + ".loop";

    public static final LiveSounds<VehicleEntityBase> SOUNDS =
            new LiveSounds<>(VehicleEntityBase.class, RETRY_TICKS, AfterburnerLoops::start);

    @Nullable
    private static Loop start(VehicleEntityBase vehicle) {
        if (!Loop.burning(vehicle) || EngineSoundInstance.falloff(vehicle, vehicle.soundSetup()) <= 0.0F) {
            return null;
        }

        SoundManager sounds = Minecraft.getInstance().getSoundManager();
        ResourceLocation recording = ModSounds.firstPresent(sounds,
                ModSounds.named(vehicle.getVehicleId(), ModSounds.ENGINE_PREFIX).withSuffix(LOOP),
                ModSounds.AFTERBURNER.withSuffix(".loop"));

        if (recording == null) {
            return null;
        }

        boolean cockpit = EngineSoundInstance.inCockpit(vehicle);

        if (cockpit) {
            recording = EngineSounds.cockpitSound(sounds, recording);
        }

        return new Loop(vehicle, SoundEvent.createVariableRangeEvent(recording), cockpit);
    }

    private static final class Loop extends EntitySoundInstance<VehicleEntityBase> {
        /** バーナーの点き消えに追う速さ。点火は速く、消えるのはそれより遅い。 */
        private static final float RISE_RATE = 0.25F;
        private static final float FALL_RATE = 0.10F;
        private static final int SILENT_TICKS_BEFORE_STOP = 20;

        private final boolean cockpit;
        private float gain;

        Loop(VehicleEntityBase vehicle, SoundEvent sound, boolean cockpit) {
            super(vehicle, sound, SoundSource.NEUTRAL, SILENT_TICKS_BEFORE_STOP);
            this.cockpit = cockpit;
        }

        static boolean burning(VehicleEntityBase vehicle) {
            return !vehicle.isRemoved() && !vehicle.isWrecked() && vehicle.getAfterburner() > 0.01F;
        }

        @Override
        protected void update() {
            VehicleEntityBase vehicle = this.entity();

            if (EngineSoundInstance.inCockpit(vehicle) != this.cockpit) {
                this.stop();

                return;
            }

            VehicleChassis.Sound setup = vehicle.soundSetup();
            float target = burning(vehicle) ? vehicle.getAfterburner() : 0.0F;
            this.gain = approach(this.gain, target, target > this.gain ? RISE_RATE : FALL_RATE);

            float falloff = this.carried(setup.range());
            this.volume = setup.volume() * this.gain * falloff
                    * (this.cockpit ? EngineSounds.COCKPIT_GAIN : 1.0F);

            this.heard(this.gain > SILENCE && falloff > 0.0F);
        }
    }

    private AfterburnerLoops() {
    }
}
