package com.ashvehicles.client.sound;

import java.util.Objects;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.weapon.TargetLock;
import com.ashvehicles.weapon.WeaponDefinition;
import com.ashvehicles.weapon.WeaponMounts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * The noise a seeker makes, which is how the crew know they have a shot without looking down.
 *
 * <p>{@link WarningSounds} is the other half of the same afternoon and it is worth saying which is
 * which. That one is the receiver behind the seat: somebody else's seeker has taken <em>you</em>,
 * and the tone is bad news. This one is the missile on your own rail, and the tone is the shot
 * arriving — a growl while the seeker is working on something and a steady note once it has it.
 *
 * <p><b>Three things to hear, because a lock is three moments rather than one.</b> The seeker takes
 * something and starts working on it; the lock closes, which takes seconds the crew have to hold the
 * boresight through; and then either it takes, or the target gets out of the cone and everything
 * starts again. So there is a search tone that climbs as the lock closes, a lock tone that runs for
 * as long as the lock does, and one short falling note for a lock that was had and is now gone.
 *
 * <p>That middle one is the part a HUD cannot do. The box on the glass already tightens as the lock
 * closes — but a pilot flying a target down is looking at the target, not at the box, and a tone
 * that climbs says the same thing without asking them to look away. See {@link SeekerSoundInstance},
 * which does the climbing.
 *
 * <p><b>How far it climbs depends on whose recording is playing</b>, and that is the whole of the
 * arrangement below. The mod's own search tone was cut for this, at the note it is meant to be
 * heard at, so it is played as cut and the climb is a hand's breadth either side — enough to hear
 * the lock closing, not enough to turn the recording into something else. Anything borrowed —
 * another stage's tone, or the game's own — was cut for something else entirely, so it is shifted
 * well down and climbs the whole way: there is nothing to spoil and everything to distinguish.
 *
 * <p><b>Both kinds of machine, one instrument.</b> An aeroplane keeps its seeker with its pylons and
 * a launcher keeps it with its tubes, and the two report it to their clients quite differently — the
 * first sends the whole of {@link TargetLock}, the second sends the target and how far along it is.
 * Neither difference is audible, so both are read into one {@link Readout} here and everything below
 * works the same either way.
 *
 * <p>Heard by everyone aboard rather than by the crew alone, which is what the instruments already
 * do: a passenger watching the seeker box close on the glass and hearing nothing would be the odd
 * thing, not the other way round.
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class SeekerSounds {
    /** What the seeker is doing, as far as anybody listening can tell. */
    public enum Stage {
        /** Armed with something that can lock, and looking at nothing. Silent. */
        IDLE,
        /** On something, and working on it. The growl, climbing as the lock closes. */
        SEEK,
        /** It has it. The steady note, for as long as it lasts. */
        LOCK
    }

    /**
     * What the seeker says this tick, on whichever machine the player is riding.
     *
     * @param vehicle which machine, by entity number, so that climbing into another starts afresh
     * @param weapon  the weapon whose seeker this is, or null for a launcher's single round
     * @param stage   what it is doing
     * @param progress how far along the lock is, from 0 to 1
     */
    public record Readout(int vehicle, @Nullable ResourceLocation weapon, Stage stage, float progress) {
    }

    /** A tone, and what to do with it: the note it starts on, where it climbs to, and how loud. */
    private record Voice(SoundEvent sound, float base, float climb, float volume) {
    }

    /**
     * The search tone as it was cut, and the little it moves. Sixteen hundredths across the whole
     * lock is under three semitones — plainly audible as a tightening, and nowhere near enough to
     * make a recorded growl sound like it is being played at the wrong speed.
     */
    private static final float SEEK_PITCH = 0.92F;
    private static final float SEEK_CLIMB = 0.16F;
    /** And a tone borrowed from elsewhere, which has nothing to spoil: low, and climbing the lot. */
    private static final float BORROWED_SEEK_PITCH = 0.7F;
    private static final float BORROWED_SEEK_CLIMB = 0.5F;

    /** The lock tone as it was cut, and a borrowed one shifted up to say it is the good news. */
    private static final float LOCK_PITCH = 1.0F;
    private static final float BORROWED_LOCK_PITCH = 1.3F;
    /** A lock falling away, when the game's own note is standing in for one nobody has recorded. */
    private static final float LOST_PITCH = 0.7F;
    /** Neither the lock tone nor the note a lock goes out on climbs anywhere. */
    private static final float NO_CLIMB = 0.0F;

    /** Under the engine and under the warning receiver: this is the good news, not the bad. */
    private static final float SEEK_VOLUME = 0.4F;
    private static final float LOCK_VOLUME = 0.55F;
    private static final float LOST_VOLUME = 0.55F;

    /**
     * What the game has that sounds most like a seeker working, until a pack has better: a drone for
     * the growl and a clear note for the lock. Deliberately not the note the receiver falls back on
     * — the one thing these two instruments must never do is sound like each other.
     */
    private static final ResourceLocation SEARCH_FALLBACK =
            SoundEvents.NOTE_BLOCK_DIDGERIDOO.value().getLocation();
    private static final ResourceLocation LOCK_FALLBACK = SoundEvents.NOTE_BLOCK_PLING.value().getLocation();

    /** What the seeker was last saying, so that a lock going away is heard and a held one is not. */
    @Nullable
    private static Readout sounding;
    /** The tone running now, if the seeker has anything to say. */
    @Nullable
    private static SeekerSoundInstance tone;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.isPaused()) {
            return;
        }

        Readout now = readout();

        // Out of the machine, or onto a weapon with no seeker at all: nothing to say, and nothing to
        // say it about. A tone still running notices the same thing for itself and stops.
        if (now == null) {
            sounding = null;

            return;
        }

        Readout was = sounding;
        sounding = now;

        // A lock that was had and is now gone. Worth one note: it is the difference between a shot
        // and no shot, and it happens at exactly the moment the crew have stopped watching the box.
        // Only counted against the same seeker on the same machine, so selecting another weapon or
        // climbing out is the silence it ought to be rather than a lock breaking.
        if (was != null && was.stage() == Stage.LOCK && now.stage() != Stage.LOCK
                && was.vehicle() == now.vehicle() && Objects.equals(was.weapon(), now.weapon())) {
            chirp(minecraft, now.weapon());
        }

        if (now.stage() == Stage.IDLE) {
            return;
        }

        // Started when it changes, and started again if the sound engine has dropped it — for a full
        // channel, a resource reload, or a volume slider that has come back up since.
        SoundManager sounds = minecraft.getSoundManager();

        if (tone == null || !tone.matches(now) || tone.isStopped() || !sounds.isActive(tone)) {
            Voice voice = voice(sounds, now);

            tone = new SeekerSoundInstance(voice.sound(), now, voice.base(), voice.climb(), voice.volume());
            sounds.play(tone);
        }
    }

    /**
     * What the seeker on the machine the player is riding says this tick, or null if there is no
     * seeker in front of them at all.
     *
     * <p>Null and {@link Stage#IDLE} are different answers and the difference matters. Idle is a
     * seeker that is armed and looking at nothing, which is what a lock breaking leaves behind; null
     * is no seeker, which is what selecting the gun leaves behind. Only the first of the two is a
     * lost lock.
     */
    @Nullable
    public static Readout readout() {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null || minecraft.player == null) {
            return null;
        }

        Entity riding = minecraft.player.getVehicle();

        if (riding instanceof AircraftEntity aircraft) {
            return aboard(aircraft);
        }

        if (riding instanceof GroundVehicleEntity vehicle) {
            return aboard(vehicle);
        }

        return null;
    }

    /**
     * An aeroplane's seeker, which is the selected weapon's own and is sent whole: the client has
     * the target, whether it has taken and how long it has been held, so how far along the lock is
     * can be worked out here exactly as the instruments work it out.
     */
    @Nullable
    private static Readout aboard(AircraftEntity aircraft) {
        WeaponMounts weapons = aircraft.getWeapons();
        WeaponDefinition weapon = weapons.selectedWeapon();

        if (weapon == null || weapon.guidance().isEmpty()) {
            return null;
        }

        TargetLock lock = weapons.lock();
        Entity target = lock.target();

        if (target == null || target.isRemoved()) {
            return new Readout(aircraft.getId(), weapons.selected(), Stage.IDLE, 0.0F);
        }

        return new Readout(aircraft.getId(), weapons.selected(),
                lock.isLocked() ? Stage.LOCK : Stage.SEEK, lock.progress(weapon.guidance().get()));
    }

    /**
     * A launcher's seeker, which is the one round its tubes hold. The lock itself lives on the
     * server; every other side reads the target and the progress out of the synched data, the same
     * two figures the sight is drawn from.
     *
     * <p>Silent while the gun is selected, which is what the sight does: a crew laying the main
     * armament are not being offered a missile shot, whatever the seeker happens to be holding.
     */
    @Nullable
    private static Readout aboard(GroundVehicleEntity vehicle) {
        if (!vehicle.isMissileMode()) {
            return null;
        }

        ResourceLocation missileId = vehicle.getStats().launcher().missile().orElse(null);
        WeaponDefinition missile = missileId == null ? null : Definitions.weapon(missileId);

        if (missile == null || missile.guidance().isEmpty()) {
            return null;
        }

        Entity target = vehicle.getSeekerTarget();

        if (target == null || target.isRemoved()) {
            return new Readout(vehicle.getId(), missileId, Stage.IDLE, 0.0F);
        }

        return new Readout(vehicle.getId(), missileId,
                vehicle.isSeekerLocked() ? Stage.LOCK : Stage.SEEK, vehicle.getSeekerProgress());
    }

    /**
     * One short sound, played and forgotten: a lock that has fallen away.
     *
     * <p><b>This one borrows from nothing.</b> The other two tones are loops — the mod's own run four
     * seconds and a resource pack's may run twenty — and a loop played once is not a short sound, it
     * is the same drone going on over the growl that has already started again underneath it. So
     * either there is a recording cut for a lock breaking or the game's own note is used, and the
     * lock tone is left where it belongs. The same reasoning the other way round as
     * {@link ModSounds}, which will not loop a recording that was not cut to loop.
     */
    private static void chirp(Minecraft minecraft, @Nullable ResourceLocation weapon) {
        SoundManager sounds = minecraft.getSoundManager();
        ResourceLocation playing = ModSounds.firstPresent(sounds, cutFor(Stage.IDLE, weapon));
        SoundEvent recording = SoundEvent.createVariableRangeEvent(
                playing == null ? LOCK_FALLBACK : playing);

        // A recording cut for a lock breaking is already the right note. The game's note is not, and
        // is dropped: what it has to say is that something has gone, and a note that falls says so.
        sounds.play(SimpleSoundInstance.forUI(recording, playing == null ? LOST_PITCH : 1.0F, LOST_VOLUME));
    }

    /**
     * The tone to run for this stage: its own recording if there is one, else the nearest to hand,
     * else the game's own — and, either way, what to do with whichever of the three answered.
     */
    private static Voice voice(SoundManager sounds, Readout readout) {
        Stage stage = readout.stage();
        ResourceLocation own = ModSounds.firstPresent(sounds, cutFor(stage, readout.weapon()));

        // Cut for this stage: played at the note it was cut at.
        if (own != null) {
            return new Voice(SoundEvent.createVariableRangeEvent(own),
                    stage == Stage.SEEK ? SEEK_PITCH : LOCK_PITCH,
                    stage == Stage.SEEK ? SEEK_CLIMB : NO_CLIMB,
                    stage == Stage.SEEK ? SEEK_VOLUME : LOCK_VOLUME);
        }

        // Cut for the other stage, or not cut for a seeker at all: shifted, so that the two stages
        // are still told apart by ear even when one recording is doing the work of both.
        ResourceLocation borrowed = ModSounds.firstPresent(sounds, cutFor(other(stage), readout.weapon()));
        SoundEvent recording = SoundEvent.createVariableRangeEvent(borrowed != null
                ? borrowed
                : stage == Stage.SEEK ? SEARCH_FALLBACK : LOCK_FALLBACK);

        return new Voice(recording,
                stage == Stage.SEEK ? BORROWED_SEEK_PITCH : BORROWED_LOCK_PITCH,
                stage == Stage.SEEK ? BORROWED_SEEK_CLIMB : NO_CLIMB,
                stage == Stage.SEEK ? SEEK_VOLUME : LOCK_VOLUME);
    }

    /** The other of the two tones, which is what a stage with no recording of its own borrows. */
    private static Stage other(Stage stage) {
        return stage == Stage.SEEK ? Stage.LOCK : Stage.SEEK;
    }

    /**
     * Everything that counts as cut for this stage, this weapon's own first: {@code
     * weapon.<weapon>.<role>}, then the mod's {@code seeker.<role>}.
     *
     * <p>Which is what lets one weapon growl differently from the rest without anybody recording a
     * whole set for it — a heat-seeking head and a radar one do not sound alike, and giving one of
     * them {@code weapon.<weapon>.seek} and nothing else leaves it borrowing the mod's own for
     * everything else it has to say.
     */
    private static ResourceLocation[] cutFor(Stage stage, @Nullable ResourceLocation weapon) {
        String role = switch (stage) {
            case SEEK -> ModSounds.SEEK_ROLE;
            case LOCK -> ModSounds.LOCK_ROLE;
            case IDLE -> ModSounds.LOST_ROLE;
        };
        ResourceLocation mod = switch (stage) {
            case SEEK -> ModSounds.SEEKER_SEARCH;
            case LOCK -> ModSounds.SEEKER_LOCK;
            case IDLE -> ModSounds.SEEKER_LOST;
        };

        if (weapon == null) {
            return new ResourceLocation[] {mod};
        }

        return new ResourceLocation[] {ModSounds.named(weapon, ModSounds.WEAPON_PREFIX, role), mod};
    }

    private SeekerSounds() {
    }
}
