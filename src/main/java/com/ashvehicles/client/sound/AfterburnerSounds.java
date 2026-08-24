package com.ashvehicles.client.sound;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.AircraftEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;

/**
 * Finds something to play when an afterburner lights and no resource pack has said what that sounds
 * like.
 *
 * <p>The same arrangement as {@link WeaponSounds}, and for the same reason. The server is the side
 * that knows the burner has caught — it is the side the flying client reports to — and naming a
 * sound event is the whole of what it can do about it, resource packs being something it has never
 * seen. So the event goes out under this aircraft's own name,
 * {@code ashvehicles:engine.<aircraft>.afterburner}, and whether anything answers to that is a
 * question only this side can ask.
 *
 * <p>What it falls back on, in order: the mod's own {@link ModSounds#AFTERBURNER}, if a pack
 * provides it; else the game's fire charge, which is the nearest thing vanilla has to a great deal
 * of fuel catching all at once. Unlike the engine note there is no reason to prefer silence here —
 * this is one short bang rather than a loop, and a stand-in bang is a good deal better than an
 * aeroplane that leaps forward for no audible reason.
 *
 * <p>Giving an aircraft its own therefore needs nothing but the files: add
 * {@code engine.<aircraft>.afterburner} to {@code sounds.json} with an {@code .ogg} beside it, or
 * {@code engine.afterburner} to cover every aircraft at once.
 *
 * <p>How loud and at what pitch comes from {@link AircraftEntity} rather than from the sound being
 * replaced. It has to: this event is fired before the sound engine has looked the recording up, so
 * the instance cannot yet say how loud it is and asking would throw.
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class AfterburnerSounds {
    /**
     * What a burner catching falls back on: the game's own fire charge, which is a lot of fuel going
     * up in one go and is therefore very nearly the right noise already.
     */
    private static final ResourceLocation FALLBACK =
            ResourceLocation.withDefaultNamespace("item.firecharge.use");

    /** The tail every one of these events is named with. See {@link ModSounds#AFTERBURNER_ROLE}. */
    private static final String SUFFIX = "." + ModSounds.AFTERBURNER_ROLE;

    private static final Set<ResourceLocation> WARNED = new HashSet<>();
    /** Whether the log already carries one report of this going wrong. */
    private static final AtomicBoolean FAILED = new AtomicBoolean();

    /**
     * Nothing that happens in here is worth losing the world over.
     *
     * <p>This event is fired from inside the handling of the packet that asked for the sound, so an
     * exception thrown here does not merely lose the sound: it fails the packet and drops the player
     * out of the game. Which recording an afterburner uses is not worth that, so anything unexpected
     * leaves the sound exactly as the server asked for it and says so once.
     */
    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        try {
            substituteDefault(event);
        } catch (Exception exception) {
            if (FAILED.compareAndSet(false, true)) {
                AshVehicles.LOGGER.error("Cannot choose an afterburner sound; leaving it to the server's choice",
                        exception);
            }
        }
    }

    private static void substituteDefault(PlaySoundEvent event) {
        SoundInstance sound = event.getSound();

        if (sound == null) {
            return;
        }

        ResourceLocation id = sound.getLocation();

        if (!isAfterburner(id)) {
            return;
        }

        SoundManager sounds = Minecraft.getInstance().getSoundManager();

        // The pack has one. Nothing here to decide: the server's figures are the aircraft's own.
        if (ModSounds.exists(sounds, id)) {
            return;
        }

        ResourceLocation shipped = ModSounds.firstPresent(sounds, ModSounds.AFTERBURNER);
        ResourceLocation recording = shipped == null ? FALLBACK : shipped;

        if (WARNED.add(id)) {
            AshVehicles.LOGGER.info("No resource pack provides {}; falling back on {}", id, recording);
        }

        // Same place and the same figures: only the recording changes.
        event.setSound(new SimpleSoundInstance(SoundEvent.createVariableRangeEvent(recording),
                sound.getSource(), AircraftEntity.AFTERBURNER_VOLUME, AircraftEntity.AFTERBURNER_LIGHT_PITCH,
                SoundInstance.createUnseededRandom(), sound.getX(), sound.getY(), sound.getZ()));
    }

    /** One of the mod's burner events: {@code engine.afterburner}, or one named after an aircraft. */
    private static boolean isAfterburner(@Nullable ResourceLocation id) {
        return id != null && AshVehicles.MODID.equals(id.getNamespace())
                && id.getPath().startsWith(ModSounds.ENGINE_PREFIX) && id.getPath().endsWith(SUFFIX);
    }

    private AfterburnerSounds() {
    }
}
