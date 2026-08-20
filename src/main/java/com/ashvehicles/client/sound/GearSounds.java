package com.ashvehicles.client.sound;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.AircraftEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/**
 * The undercarriage: whatever an aircraft sounds like while its legs are on their way up or down.
 *
 * <p>Nothing is sent about this. The gear lever is already synched, and both sides run the same
 * count from stowed to down, so a client can see the legs travelling for itself and start a sound
 * the moment they do. See {@link AircraftEntity#isGearSettled()}.
 *
 * <p><b>Which recording.</b> The event the aircraft's file names under {@code sound.gear}; else the
 * one named after the aircraft, {@code <namespace>:gear.<name>}; else the mod's
 * {@code ashvehicles:gear.default}. <b>None of the three is shipped</b>, and if a resource pack
 * provides none of them the gear is silent — a loop is either cut to loop or it is worse than
 * nothing, and there is no recording in the game that would do. Providing one is the whole of what
 * it takes; see {@link ModSounds}.
 */
public final class GearSounds {
    /**
     * How often an aircraft with no live gear sound is looked at again. Short, because a gear cycle
     * is a couple of seconds and starting the sound a quarter of one late would be heard.
     */
    private static final int RETRY_TICKS = 2;

    /** Every aircraft this client can see, and its undercarriage, while that has anything to say. */
    public static final LiveSounds<AircraftEntity> SOUNDS =
            new LiveSounds<>(AircraftEntity.class, RETRY_TICKS, GearSounds::start);

    /** Requested recordings already complained about, so a missing file is one line in the log. */
    private static final Set<ResourceLocation> WARNED = new HashSet<>();

    @Nullable
    private static GearSoundInstance start(AircraftEntity aircraft) {
        if (aircraft.isGearSettled()
                || EntitySoundInstance.falloff(aircraft, GearSoundInstance.RANGE) <= 0.0F) {
            return null;
        }

        ResourceLocation recording = gearSound(Minecraft.getInstance().getSoundManager(), aircraft);

        return recording == null
                ? null
                : new GearSoundInstance(aircraft, SoundEvent.createVariableRangeEvent(recording));
    }

    /**
     * The gear recording for an aircraft, or null if no resource pack provides one at all. Resolved
     * afresh each time a sound is started, so a resource pack change is picked up without a restart.
     */
    @Nullable
    public static ResourceLocation gearSound(SoundManager sounds, AircraftEntity aircraft) {
        Optional<ResourceLocation> requested = aircraft.getStats().sound().gear();

        if (requested.isPresent()) {
            if (ModSounds.exists(sounds, requested.get())) {
                return requested.get();
            }

            if (WARNED.add(requested.get())) {
                AshVehicles.LOGGER.warn("Aircraft {} asks for gear sound {} which no resource pack provides; looking for another",
                        aircraft.getAircraftId(), requested.get());
            }
        }

        return ModSounds.firstPresent(sounds,
                ModSounds.named(aircraft.getAircraftId(), ModSounds.GEAR_PREFIX), ModSounds.GEAR);
    }

    private GearSounds() {
    }
}
