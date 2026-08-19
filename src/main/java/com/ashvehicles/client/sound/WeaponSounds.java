package com.ashvehicles.client.sound;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.aircraft.AircraftManager;
import com.ashvehicles.weapon.WeaponDefinition;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;

/**
 * Gives a weapon with no firing sound of its own the mod's default one.
 *
 * <p>The server decides what to play and names a sound event, because that is all it can do: sounds
 * live in resource packs, which the server has never seen. So a weapon with no recording would
 * simply be silent, and only the client is in a position to notice. This catches any of the mod's
 * {@code weapon.*} events that the resource packs cannot resolve and swaps in
 * {@code ashvehicles:weapon.gun} at the same place.
 *
 * <p>Giving a weapon its own sound therefore needs nothing but the files: add
 * {@code weapon.<name>} to {@code sounds.json} with an {@code .ogg} beside it, and it is used
 * instead. The engine note works the same way; see {@link EngineSounds}.
 *
 * <p>How loud and at what pitch comes from the weapon's own file rather than from the sound being
 * replaced. It has to: this event is fired before the sound engine has looked the recording up, so
 * the instance cannot yet say how loud it is and asking would throw.
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class WeaponSounds {
    /** What a gun falls back on. */
    public static final ResourceLocation DEFAULT_GUN =
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "weapon.gun");
    /** What anything with a motor falls back on: a rocket does not sound like a cannon. */
    public static final ResourceLocation DEFAULT_LAUNCH =
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "weapon.launch");
    /** Events under this prefix are ours to fall back on; anything else is left alone. */
    private static final String WEAPON_PREFIX = "weapon.";

    private static final Set<ResourceLocation> WARNED = new HashSet<>();
    /** Whether the log already carries one report of this going wrong. */
    private static final AtomicBoolean FAILED = new AtomicBoolean();

    /**
     * Nothing that happens in here is worth losing the world over.
     *
     * <p>This event is fired from inside the handling of the packet that asked for the sound, so an
     * exception thrown here does not merely lose the sound: it fails the packet and drops the player
     * out of the game. Which recording a gun uses is not worth that, so anything unexpected leaves
     * the sound exactly as the server asked for it and says so once.
     */
    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        try {
            substituteDefault(event);
        } catch (Exception exception) {
            if (FAILED.compareAndSet(false, true)) {
                AshVehicles.LOGGER.error("Cannot choose a firing sound; leaving it to the server's choice", exception);
            }
        }
    }

    private static void substituteDefault(PlaySoundEvent event) {
        SoundInstance sound = event.getSound();

        if (sound == null) {
            return;
        }

        ResourceLocation id = sound.getLocation();

        if (!AshVehicles.MODID.equals(id.getNamespace()) || !id.getPath().startsWith(WEAPON_PREFIX)
                || id.equals(DEFAULT_GUN) || id.equals(DEFAULT_LAUNCH)) {
            return;
        }

        SoundManager sounds = Minecraft.getInstance().getSoundManager();

        if (exists(sounds, id)) {
            return;
        }

        // Same place and the same figures the weapon's file asks for: only the recording changes,
        // and which recording depends on what kind of weapon it is.
        WeaponDefinition weapon = weaponFor(id);
        ResourceLocation fallback = weapon != null && weapon.type() != WeaponDefinition.Type.GUN
                ? DEFAULT_LAUNCH
                : DEFAULT_GUN;

        if (WARNED.add(id)) {
            AshVehicles.LOGGER.info("No resource pack provides {}; firing sounds fall back on {}", id, fallback);
        }

        WeaponDefinition.SoundSetup setup = weapon != null ? weapon.sound() : WeaponDefinition.SoundSetup.DEFAULT;
        event.setSound(new SimpleSoundInstance(SoundEvent.createVariableRangeEvent(fallback),
                sound.getSource(), setup.volume(), setup.pitch(), SoundInstance.createUnseededRandom(),
                sound.getX(), sound.getY(), sound.getZ()));
    }

    /**
     * Whichever weapon asks for this event, or null if none does.
     *
     * <p>Needed because a {@link PlaySoundEvent} arrives before the sound engine has resolved the
     * recording, so the instance cannot yet say how loud it is and asking would throw; the figures
     * have to come from the weapon instead. Every weapon is checked rather than only the one the
     * event is named after, so a weapon that names some other event by hand is still matched.
     */
    @Nullable
    private static WeaponDefinition weaponFor(ResourceLocation event) {
        for (Map.Entry<ResourceLocation, WeaponDefinition> entry : AircraftManager.allWeapons().entrySet()) {
            ResourceLocation fire = entry.getValue().sound().fire()
                    .orElseGet(() -> entry.getKey().withPath(WEAPON_PREFIX + entry.getKey().getPath()));

            if (fire.equals(event)) {
                return entry.getValue();
            }
        }

        return null;
    }

    /** True if the resource packs define this event and at least one of its files was found. */
    private static boolean exists(SoundManager sounds, ResourceLocation id) {
        WeighedSoundEvents weighed = sounds.getSoundEvent(id);

        return weighed != null && weighed.getWeight() > 0;
    }

    private WeaponSounds() {
    }
}
