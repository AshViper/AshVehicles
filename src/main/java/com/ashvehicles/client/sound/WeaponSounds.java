package com.ashvehicles.client.sound;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.aircraft.AircraftManager;
import com.ashvehicles.weapon.Dispenser;
import com.ashvehicles.weapon.WeaponDefinition;
import com.ashvehicles.weapon.WeaponMounts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;

/**
 * Finds something to play for any of the mod's one-shot weapon sounds that a resource pack has not
 * provided.
 *
 * <p>The server decides what to play and names a sound event, because that is all it can do: sounds
 * live in resource packs, which the server has never seen. So a weapon with no recording would
 * simply be silent, and only the client is in a position to notice. This catches any of the mod's
 * {@code weapon.*} events that the resource packs cannot resolve and puts the nearest thing that
 * does exist in its place, at the same position.
 *
 * <p>What the nearest thing is depends on the event:
 *
 * <ul>
 * <li>An event named after a weapon, {@code weapon.<name>}, falls back on the mod's default for that
 *     sort of weapon: {@code weapon.gun} for a gun, {@code weapon.launch} for anything with a motor,
 *     and {@code weapon.release} for a bomb, which is let go rather than fired and sounds like a rack
 *     banging open rather than like anything going off.
 * <li>{@code weapon.release} itself falls back on {@code weapon.launch}, which the mod does ship. So
 *     a bomb sounds like something leaving the aeroplane until somebody records the clunk it should
 *     be, rather than sounding like nothing.
 * <li>{@code weapon.load}, the ground crew at work, falls back on the game's own metal-on-metal.
 * <li>{@code weapon.gun} and {@code weapon.launch} fall back on nothing: the mod ships both, and a
 *     pack that has taken them away has said what it wants.
 * </ul>
 *
 * <p>Giving a weapon a sound of its own therefore needs nothing but the files: add
 * {@code weapon.<name>} to {@code sounds.json} with an {@code .ogg} beside it, and it is used
 * instead. The engine note works the same way; see {@link EngineSounds}. What a weapon sounds like
 * in the air rather than at the moment of firing is not here at all, because a loop cannot be
 * substituted for sensibly; see {@link ProjectileSounds}.
 *
 * <p>How loud and at what pitch comes from the weapon's own file rather than from the sound being
 * replaced. It has to: this event is fired before the sound engine has looked the recording up, so
 * the instance cannot yet say how loud it is and asking would throw.
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class WeaponSounds {
    /**
     * What ground crew fall back on: the game's own iron trapdoor, which is the closest it has to
     * something heavy being clamped onto something else.
     */
    private static final ResourceLocation LOAD_FALLBACK =
            ResourceLocation.withDefaultNamespace("block.iron_trapdoor.close");

    /**
     * And what the countermeasure dispenser falls back on: the game's own firework, which is the
     * nearest thing it has to something being thrown out of an aeroplane and set alight.
     */
    private static final ResourceLocation DECOY_FALLBACK =
            ResourceLocation.withDefaultNamespace("entity.firework_rocket.launch");

    /**
     * How loud the ground crew are. The same figures the server asked for, taken from the one place
     * that owns them, because they cannot be read back off the sound at this point. The pitch is the
     * one used for hanging a store: a stand-in for a sound the pack does not have is not worth
     * telling apart from the one used for taking one off.
     */
    private static final WeaponDefinition.SoundSetup LOAD_SETUP = new WeaponDefinition.SoundSetup(
            Optional.empty(), WeaponMounts.LOAD_VOLUME, WeaponMounts.LOAD_PITCH);

    /** The same, for the dispenser, whose figures live with the dispenser. */
    private static final WeaponDefinition.SoundSetup DECOY_SETUP = new WeaponDefinition.SoundSetup(
            Optional.empty(), Dispenser.RELEASE_VOLUME, Dispenser.RELEASE_PITCH);

    /**
     * How a report quietens with distance. Under one, so it drops away sharply at first and then
     * hangs on a long way out, which is both what loudness does to the ear and what makes the far
     * end of the carry worth having.
     */
    private static final float FALLOFF = 0.85F;
    /** And how much of its edge it loses over the whole carry: a crack up close is a thud a mile off. */
    private static final float DULLING = 0.45F;

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
                AshVehicles.LOGGER.error("Cannot choose a weapon sound; leaving it to the server's choice", exception);
            }
        }
    }

    private static void substituteDefault(PlaySoundEvent event) {
        SoundInstance sound = event.getSound();

        if (sound == null) {
            return;
        }

        ResourceLocation id = sound.getLocation();

        if (!AshVehicles.MODID.equals(id.getNamespace()) || !id.getPath().startsWith(ModSounds.WEAPON_PREFIX)) {
            return;
        }

        SoundManager sounds = Minecraft.getInstance().getSoundManager();
        WeaponDefinition firing = weaponFor(id);

        if (ModSounds.exists(sounds, id)) {
            // The recording is there; only how loud it should be at this distance is wrong, and only
            // for a weapon's report, which is the only thing sent further than the game would send it.
            if (firing != null) {
                event.setSound(instance(SoundEvent.createVariableRangeEvent(id), sound, firing.sound(), firing));
            }

            return;
        }

        WeaponDefinition weapon = firing;
        ResourceLocation fallback = fallbackFor(sounds, id, weapon);

        if (fallback == null) {
            return;
        }

        if (WARNED.add(id)) {
            AshVehicles.LOGGER.info("No resource pack provides {}; falling back on {}", id, fallback);
        }

        // Same place and the same figures whatever asked for it wanted: only the recording changes.
        event.setSound(instance(SoundEvent.createVariableRangeEvent(fallback), sound, setupFor(id, weapon), weapon));
    }

    /**
     * Puts a weapon's report where it belongs at the distance it is being heard from.
     *
     * <p>The sound arrived carrying a volume that is not a volume: the server had to put the reach in
     * that slot, because the reach is all that slot decides — see
     * {@link WeaponDefinition.SoundSetup#packetVolume()}. Played as sent, a cannon three hundred
     * blocks away would be as loud as one in the cockpit.
     *
     * <p>So the figure is thrown away and the real one worked out here, from the one thing only this
     * side knows: how far the listener is standing from where it went off. The shape is the one the
     * blast uses, for the same reasons — it quietens sharply at first and then carries a long way,
     * and it loses its edge as it goes, because air swallows the high frequencies first and a crack
     * across a valley is a thud. See {@link BlastSounds}.
     *
     * <p>Attenuation is switched off, since the distance is already in the volume, but the sound is
     * still placed where it happened so it comes from the right direction.
     */
    private static SimpleSoundInstance instance(SoundEvent recording, SoundInstance sound,
            WeaponDefinition.SoundSetup setup, @Nullable WeaponDefinition weapon) {
        if (weapon == null) {
            // Not a weapon firing: ground crew and the like, which are heard where they happen and
            // were never sent any further than that.
            return new SimpleSoundInstance(recording, sound.getSource(), setup.volume(), setup.pitch(),
                    SoundInstance.createUnseededRandom(), sound.getX(), sound.getY(), sound.getZ());
        }

        Vec3 at = new Vec3(sound.getX(), sound.getY(), sound.getZ());
        double away = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().distanceTo(at);
        float fade = (float) Mth.clamp(away / Math.max(setup.carry(), 1.0F), 0.0, 1.0);

        return new SimpleSoundInstance(recording.getLocation(), sound.getSource(),
                setup.volume() * (float) Math.pow(1.0F - fade, FALLOFF),
                setup.pitch() * (1.0F - fade * DULLING),
                SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE,
                at.x, at.y, at.z, false);
    }

    /**
     * The nearest thing to this event that a resource pack does provide, or null if there is nothing
     * worth putting in its place.
     */
    @Nullable
    private static ResourceLocation fallbackFor(SoundManager sounds, ResourceLocation id,
            @Nullable WeaponDefinition weapon) {
        if (id.equals(ModSounds.LOAD)) {
            return LOAD_FALLBACK;
        }

        if (id.equals(ModSounds.DECOY)) {
            return DECOY_FALLBACK;
        }

        if (id.equals(ModSounds.RELEASE)) {
            return ModSounds.firstPresent(sounds, ModSounds.LAUNCH);
        }

        // The two the mod ships. If neither is there, the pack has replaced the mod's sounds with
        // nothing at all, and putting one of the game's own in its place would be second-guessing it.
        if (id.equals(ModSounds.GUN) || id.equals(ModSounds.LAUNCH)) {
            return null;
        }

        // Anything else under weapon.* is a weapon's own name, which nothing answers to.
        return switch (weapon == null ? WeaponDefinition.Type.GUN : weapon.type()) {
            case GUN -> ModSounds.firstPresent(sounds, ModSounds.GUN);
            case ROCKET, MISSILE -> ModSounds.firstPresent(sounds, ModSounds.LAUNCH);
            case BOMB -> ModSounds.firstPresent(sounds, ModSounds.RELEASE, ModSounds.LAUNCH);
        };
    }

    /** How loud and at what pitch: the weapon's own figures, or the ones whoever asked for it used. */
    private static WeaponDefinition.SoundSetup setupFor(ResourceLocation id, @Nullable WeaponDefinition weapon) {
        if (weapon != null) {
            return weapon.sound();
        }

        if (id.equals(ModSounds.LOAD)) {
            return LOAD_SETUP;
        }

        return id.equals(ModSounds.DECOY) ? DECOY_SETUP : WeaponDefinition.SoundSetup.DEFAULT;
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
                    .orElseGet(() -> ModSounds.named(entry.getKey(), ModSounds.WEAPON_PREFIX));

            if (fire.equals(event)) {
                return entry.getValue();
            }
        }

        return null;
    }

    private WeaponSounds() {
    }
}
