package com.ashvehicles.client.sound;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.entity.AircraftEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Gives every aircraft this client can see an engine note, and picks which recording it gets.
 *
 * <p><b>Which recording.</b> Sounds are ordinary resource-pack sounds: an entry in
 * {@code sounds.json} pointing at an {@code .ogg}. An aircraft is given, in order of preference,
 * the event its file names under {@code sound.engine}; the event named after it,
 * {@code <namespace>:engine.<name>}; or the mod's default {@code ashvehicles:engine.default}. An
 * event only counts if the resource pack actually has it and its file exists, so a missing or
 * misspelt recording falls through to the default rather than to silence.
 *
 * <p><b>When it plays.</b> Rather than tying one sound to each aircraft for life, this keeps a list
 * of the aircraft it knows about and makes sure each one that is running, and close enough to hear,
 * has a live sound; a sound stops itself once it has nothing to say. That way parked aircraft cost
 * nothing, and an aircraft whose sound was lost to a resource reload or a muted volume slider gets
 * it back on its own.
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class EngineSounds {
    /** The recording every aircraft falls back on. */
    public static final ResourceLocation DEFAULT_ENGINE =
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "engine.default");
    /** Prefix of the event an aircraft is matched to by name: {@code engine.<name>}. */
    private static final String ENGINE_PREFIX = "engine.";
    /** How often an aircraft without a live sound is looked at again. */
    private static final int RETRY_TICKS = 10;

    /** Aircraft in the current level, and the sound each one has, if it has one right now. */
    private static final Map<AircraftEntity, EngineSoundInstance> AIRCRAFT = new HashMap<>();
    /** Requested sounds already complained about, so a missing file is one line in the log, not one a second. */
    private static final Set<ResourceLocation> WARNED = new HashSet<>();

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() && event.getEntity() instanceof AircraftEntity aircraft) {
            AIRCRAFT.putIfAbsent(aircraft, null);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null) {
            AIRCRAFT.clear();
            return;
        }

        if (minecraft.isPaused()) {
            return;
        }

        SoundManager sounds = minecraft.getSoundManager();
        Iterator<Map.Entry<AircraftEntity, EngineSoundInstance>> entries = AIRCRAFT.entrySet().iterator();

        while (entries.hasNext()) {
            Map.Entry<AircraftEntity, EngineSoundInstance> entry = entries.next();
            AircraftEntity aircraft = entry.getKey();

            if (aircraft.isRemoved() || aircraft.level() != minecraft.level) {
                entries.remove();
                continue;
            }

            EngineSoundInstance sound = entry.getValue();

            if (sound != null && !sound.isStopped() && sounds.isActive(sound)) {
                continue;
            }

            // Not every tick: a sound that could not start, because the volume is turned down, say,
            // would otherwise be tried again twenty times a second.
            if (aircraft.tickCount % RETRY_TICKS != 0 || !shouldStart(aircraft)) {
                continue;
            }

            sound = new EngineSoundInstance(aircraft, engineSound(sounds, aircraft));
            sounds.play(sound);
            entry.setValue(sound);
        }
    }

    /** Running, and near enough that starting a sound would be heard. */
    private static boolean shouldStart(AircraftEntity aircraft) {
        return EngineSoundInstance.isEngineRunning(aircraft)
                && EngineSoundInstance.falloff(aircraft, aircraft.getStats().sound()) > 0.0F;
    }

    /**
     * The engine recording for an aircraft: the one its file asks for, else the one named after it,
     * else the default. Resolved afresh each time a sound is started, so a resource pack change is
     * picked up without a restart.
     */
    public static SoundEvent engineSound(SoundManager sounds, AircraftEntity aircraft) {
        AircraftDefinition.SoundSetup setup = aircraft.getStats().sound();
        Optional<ResourceLocation> requested = setup.engine();

        if (requested.isPresent()) {
            if (exists(sounds, requested.get())) {
                return SoundEvent.createVariableRangeEvent(requested.get());
            }

            if (WARNED.add(requested.get())) {
                AshVehicles.LOGGER.warn("Aircraft {} asks for engine sound {} which no resource pack provides; using the default",
                        aircraft.getAircraftId(), requested.get());
            }
        }

        ResourceLocation id = aircraft.getAircraftId();
        ResourceLocation byName = id.withPath(ENGINE_PREFIX + id.getPath());

        return SoundEvent.createVariableRangeEvent(exists(sounds, byName) ? byName : DEFAULT_ENGINE);
    }

    /** True if the resource packs define this event and at least one of its files was found. */
    private static boolean exists(SoundManager sounds, ResourceLocation id) {
        WeighedSoundEvents event = sounds.getSoundEvent(id);

        return event != null && event.getWeight() > 0;
    }

    private EngineSounds() {
    }
}
