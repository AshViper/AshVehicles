package com.ashvehicles.client.sound;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.RadarReadout;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.sensor.Threat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * The noise the warning receiver makes, which is most of what a warning receiver is.
 *
 * <p>The scope in {@link com.ashvehicles.client.RadarDisplay} says which way the trouble is coming
 * from, and a pilot in trouble is not looking at it. What tells them there is trouble at all is the
 * tone.
 *
 * <p><b>Two shapes, because a receiver makes two kinds of noise.</b> Being found by somebody's radar
 * is news: it happens once, and after that it is simply the state of the afternoon, so it gets one
 * chirp and then silence. Being locked, or being shot at, is a condition rather than an event, and
 * gets a continuous alarm that runs for exactly as long as the condition does. See
 * {@link WarningSoundInstance}.
 *
 * <p>That second part is the whole of the design, and it is worth saying why. The obvious way to
 * build an alarm out of a recording is to play it again every so often — but the recording is not a
 * beep. What anybody records for this is an alarm: a tone with its own rhythm already in it, running
 * for as long as the file lasts. Restarting one of those on a timer stacks copy on copy — a
 * twenty-second lock tone restarted twice a second is forty of it playing at once, which is not a
 * warning but a wall of noise. So one is started and left to run.
 *
 * <p>Nothing is heard by anyone but the pilot, because nobody else is sent a radar picture at all.
 * The tone is played flat into the cockpit rather than positioned anywhere: it is coming from a box
 * behind the seat, not from the aeroplane threatening you.
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class WarningSounds {
    /** Playback speed for a recording that was cut for the warning it is being used for. */
    private static final float AS_RECORDED = 1.0F;
    /** And for one standing in for another warning: a search is lower, trouble climbs. */
    private static final float SEARCH_PITCH = 0.8F;
    private static final float LOCK_PITCH = 1.0F;
    private static final float MISSILE_PITCH = 1.45F;
    private static final float VOLUME = 0.6F;

    /** What the game has that sounds most like an instrument warning, until a pack has better. */
    private static final ResourceLocation FALLBACK = SoundEvents.NOTE_BLOCK_BIT.value().getLocation();

    /** What the receiver was last saying, so that a change is heard and a repeat is not. */
    @Nullable
    private static Threat.Kind sounding;
    /** The alarm running now, if the trouble is the sort that gets one. */
    @Nullable
    private static WarningSoundInstance alarm;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();

        // Only the pilot has a receiver in front of them. A passenger is sent no picture at all, and
        // somebody who has climbed out is no longer being warned about anything. An alarm already
        // running notices the same thing for itself and stops.
        if (minecraft.level == null || minecraft.player == null || minecraft.isPaused()
                || !(minecraft.player.getVehicle() instanceof AircraftEntity)) {
            sounding = null;

            return;
        }

        Threat.Kind worst = RadarReadout.worst();

        if (worst == null) {
            sounding = null;

            return;
        }

        boolean changed = sounding != worst;
        sounding = worst;

        // News, once. Somebody having you on their scope is worth knowing when it starts; it is not
        // worth being told again for as long as the two of you are in the same sky.
        if (worst == Threat.Kind.SEARCH) {
            if (changed) {
                chirp(minecraft, worst);
            }

            return;
        }

        // A condition, held. Started when it changes, and started again if the sound engine has
        // dropped it — for a full channel, a resource reload, or a volume slider that has come back
        // up since.
        SoundManager sounds = minecraft.getSoundManager();

        if (changed || alarm == null || alarm.isStopped() || !sounds.isActive(alarm)) {
            alarm = new WarningSoundInstance(recording(sounds, worst), worst, pitch(sounds, worst), VOLUME);
            sounds.play(alarm);
        }
    }

    /** One short sound, played and forgotten. */
    private static void chirp(Minecraft minecraft, Threat.Kind kind) {
        SoundManager sounds = minecraft.getSoundManager();

        sounds.play(SimpleSoundInstance.forUI(recording(sounds, kind), pitch(sounds, kind), VOLUME));
    }

    /** The recording to use for this warning: its own if there is one, else the nearest to hand. */
    private static SoundEvent recording(SoundManager sounds, Threat.Kind kind) {
        ResourceLocation playing = ModSounds.firstPresent(sounds, borrowing(kind));

        return SoundEvent.createVariableRangeEvent(playing == null ? FALLBACK : playing);
    }

    /**
     * A recording made for this warning is already the right note and is played as it was cut. One
     * borrowed from another warning, or the game's own, is shifted to say which of the three it is
     * standing in for.
     */
    private static float pitch(SoundManager sounds, Threat.Kind kind) {
        if (recordingFor(kind).equals(ModSounds.firstPresent(sounds, borrowing(kind)))) {
            return AS_RECORDED;
        }

        return switch (kind) {
            case SEARCH -> SEARCH_PITCH;
            case LOCK -> LOCK_PITCH;
            case MISSILE -> MISSILE_PITCH;
        };
    }

    /** The recording cut for this warning and no other. */
    private static ResourceLocation recordingFor(Threat.Kind kind) {
        return switch (kind) {
            case SEARCH -> ModSounds.RWR_CONTACT;
            case LOCK -> ModSounds.RWR_LOCK;
            case MISSILE -> ModSounds.RWR_MISSILE;
        };
    }

    /**
     * What to reach for, in order: this warning's own recording, then the others, nearest in meaning
     * first. A pack that provides one name gets a receiver that works for all three.
     */
    private static ResourceLocation[] borrowing(Threat.Kind kind) {
        return switch (kind) {
            case SEARCH -> new ResourceLocation[] {ModSounds.RWR_CONTACT, ModSounds.RWR_LOCK};
            case LOCK -> new ResourceLocation[] {ModSounds.RWR_LOCK, ModSounds.RWR_CONTACT};
            case MISSILE -> new ResourceLocation[] {
                    ModSounds.RWR_MISSILE, ModSounds.RWR_LOCK, ModSounds.RWR_CONTACT};
        };
    }

    private WarningSounds() {
    }
}
