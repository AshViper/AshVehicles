package com.ashvehicles.client.sound;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.network.BlastSoundPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Explosions heard from a long way off.
 *
 * <p>Three things are wrong with the sound the game would otherwise make, and all three are what
 * makes a distant explosion sound distant:
 *
 * <ul>
 * <li><b>It arrives too early.</b> Sound crosses about seventeen blocks a tick, so a bomb three
 *     hundred blocks away should be heard the best part of a second after the flash. Seeing the
 *     flash and hearing the bang together is the single strongest cue that something is close, and
 *     the game gives that cue to everything.
 * <li><b>It is too quiet, then suddenly silent.</b> The sound engine fades a sound linearly to
 *     nothing over {@code max(volume, 1) * 16} blocks, which for an explosion is sixty-four. Past
 *     that there is no sound at all, at any size of blast.
 * <li><b>It is too sharp.</b> Air swallows the high frequencies first, so what is a crack up close
 *     is a low roll a long way off.
 * </ul>
 *
 * <p>So the arrival is timed, the volume is worked out here rather than left to the engine's
 * falloff, and the pitch is dropped with distance. The sound is played with attenuation switched
 * off — the distance is already accounted for — but at the blast's real position, so it still comes
 * from the right direction.
 *
 * <p>Vanilla's own explosion noise is kept out of the way at the other end; see
 * {@link com.ashvehicles.weapon.WeaponEffects}.
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class BlastSounds {
    /** Blocks a tick: three hundred and forty-three metres a second, at twenty ticks to the second. */
    private static final double SPEED_OF_SOUND = 17.15;
    /**
     * How it quietens with distance. Under one, so it drops away sharply at first and then hangs on
     * a long way out, which is both what loudness does to the ear and what makes the far end of the
     * carry worth having at all.
     */
    private static final double FALLOFF = 0.85;
    /** Pitch of a blast right on top of you, and how much of that is lost over the whole carry. */
    private static final float NEAR_PITCH = 1.05F;
    private static final float DULLING = 0.5F;
    /** Nothing is held longer than this, in case the player is somewhere else entirely by then. */
    private static final int LONGEST_WAIT = 200;

    private static final List<Pending> WAITING = new ArrayList<>();

    /** A blast has gone off. Whether it is heard, and when, is worked out from where it was. */
    public static void hear(Vec3 at, float power) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null) {
            return;
        }

        int wait = Math.min((int) (earTo(minecraft, at) / SPEED_OF_SOUND), LONGEST_WAIT);

        if (wait <= 0) {
            play(minecraft, at, power);
        } else {
            WAITING.add(new Pending(at, power, wait));
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (WAITING.isEmpty()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        // Leaving the world takes the pending sounds with it. A bang from the last one arriving in
        // the next would be a strange thing to hear.
        if (minecraft.level == null) {
            WAITING.clear();

            return;
        }

        Iterator<Pending> each = WAITING.iterator();

        while (each.hasNext()) {
            Pending boom = each.next();

            if (--boom.wait <= 0) {
                each.remove();
                play(minecraft, boom.at, boom.power);
            }
        }
    }

    /**
     * How much of the blast is left where the player is standing now.
     *
     * <p>Measured again on arrival rather than kept from when it was sent, because in an aeroplane a
     * second is a hundred blocks and the player has been flying for the whole of the sound's journey.
     */
    private static void play(Minecraft minecraft, Vec3 at, float power) {
        double fade = Mth.clamp(earTo(minecraft, at) / BlastSoundPayload.carry(power), 0.0, 1.0);
        float volume = (float) Math.pow(1.0 - fade, FALLOFF);

        if (volume <= 0.0F) {
            return;
        }

        minecraft.getSoundManager().play(new BlastSoundInstance(
                recording(minecraft), volume, NEAR_PITCH - (float) fade * DULLING, at));
    }

    private static double earTo(Minecraft minecraft, Vec3 at) {
        return minecraft.gameRenderer.getMainCamera().getPosition().distanceTo(at);
    }

    /** The mod's own boom if a resource pack provides one, else the game's. */
    private static ResourceLocation recording(Minecraft minecraft) {
        return ModSounds.exists(minecraft.getSoundManager(), ModSounds.BLAST)
                ? ModSounds.BLAST
                : SoundEvents.GENERIC_EXPLODE.value().getLocation();
    }

    /** A blast on its way, and how many ticks of flight it has left. */
    private static final class Pending {
        private final Vec3 at;
        private final float power;
        private int wait;

        private Pending(Vec3 at, float power, int wait) {
            this.at = at;
            this.power = power;
            this.wait = wait;
        }
    }

    /**
     * Positioned, so it comes from the right direction, but not attenuated: the engine's falloff
     * reaches nothing at sixty-four blocks and everything interesting here happens further away
     * than that. How far off it was is already in the volume and the pitch.
     */
    private static final class BlastSoundInstance extends AbstractSoundInstance {
        private BlastSoundInstance(ResourceLocation recording, float volume, float pitch, Vec3 at) {
            super(recording, SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
            this.volume = volume;
            this.pitch = pitch;
            this.x = at.x;
            this.y = at.y;
            this.z = at.z;
            this.attenuation = SoundInstance.Attenuation.NONE;
        }
    }

    private BlastSounds() {
    }
}
