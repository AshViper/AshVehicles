package com.ashvehicles.client.sound;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.VehicleEntityBase;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/**
 * Gives every machine this client can see an engine note, and picks which recording it gets.
 *
 * <p><b>Which recording.</b> Sounds are ordinary resource-pack sounds: an entry in
 * {@code sounds.json} pointing at an {@code .ogg}. An vehicle is given, in order of preference,
 * the event its file names under {@code sound.engine}; the event named after it,
 * {@code <namespace>:engine.<name>}; or the mod's default {@code ashvehicles:engine.default}. An
 * event only counts if the resource pack actually has it and its file exists, so a missing or
 * misspelt recording falls through to the default rather than to silence.
 *
 * <p><b>When it plays.</b> Rather than tying one sound to each machine for life, {@link LiveSounds}
 * keeps the list and asks here for a sound whenever a machine has none; a sound stops itself once
 * it has nothing to say. That way parked machines cost nothing, and a machine whose sound was lost
 * to a resource reload or a muted volume slider gets it back on its own.
 */
public final class EngineSounds {
    /** How often a machine without a live sound is looked at again. */
    private static final int RETRY_TICKS = 10;

    /** Every machine this client can see, and the engine note each one has. */
    public static final LiveSounds<VehicleEntityBase> SOUNDS =
            new LiveSounds<>(VehicleEntityBase.class, RETRY_TICKS, EngineSounds::start);

    /** Requested sounds already complained about, so a missing file is one line in the log, not one a second. */
    private static final Set<ResourceLocation> WARNED = new HashSet<>();

    /** A note for a machine that is running and near enough to be heard, else nothing. */
    @Nullable
    private static EngineSoundInstance start(VehicleEntityBase vehicle) {
        if (!EngineSoundInstance.isEngineRunning(vehicle)
                || EngineSoundInstance.falloff(vehicle, vehicle.soundSetup()) <= 0.0F) {
            return null;
        }

        return new EngineSoundInstance(vehicle, engineSound(Minecraft.getInstance().getSoundManager(), vehicle));
    }

    /**
     * The engine recording for a machine: the one its file asks for, else the one named after it,
     * else the default. Resolved afresh each time a sound is started, so a resource pack change is
     * picked up without a restart.
     */
    public static SoundEvent engineSound(SoundManager sounds, VehicleEntityBase vehicle) {
        VehicleChassis.Sound setup = vehicle.soundSetup();
        Optional<ResourceLocation> requested = setup.engine();

        if (requested.isPresent()) {
            if (ModSounds.exists(sounds, requested.get())) {
                return SoundEvent.createVariableRangeEvent(requested.get());
            }

            if (WARNED.add(requested.get())) {
                AshVehicles.LOGGER.warn("Machine {} asks for engine sound {} which no resource pack provides; using the default",
                        vehicle.getVehicleId(), requested.get());
            }
        }

        ResourceLocation byName = ModSounds.named(vehicle.getVehicleId(), ModSounds.ENGINE_PREFIX);

        return SoundEvent.createVariableRangeEvent(
                ModSounds.exists(sounds, byName) ? byName : ModSounds.ENGINE);
    }

    private EngineSounds() {
    }
}
