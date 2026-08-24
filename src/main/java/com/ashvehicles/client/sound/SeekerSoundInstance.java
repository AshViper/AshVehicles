package com.ashvehicles.client.sound;

import java.util.Objects;

import javax.annotation.Nullable;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * The crew's own seeker, sounding off for as long as it is holding something.
 *
 * <p>The twin of {@link WarningSoundInstance}, pointed the other way round. That one is the box
 * behind the seat saying somebody has taken <em>you</em>; this one is the missile on the rail saying
 * what <em>it</em> can see. Both are instruments rather than things in the world, so both are played
 * flat into the cockpit — no position, no attenuation, nothing falling off with distance.
 *
 * <p><b>The pitch climbs as the lock closes</b>, and that is the whole point of the search tone. The
 * box on the glass tightens to say how far along the seeker has got, which is worth nothing to a
 * pilot who is looking at the target rather than at the instruments — so the tone says the same
 * thing in the ear. It starts low the moment the seeker takes something and arrives at its full note
 * exactly when the lock does, at which point this instance gives way to the lock tone proper.
 *
 * <p>It ends itself rather than waiting to be told, for the same reason the warning receiver does: a
 * growl still running after the target has gone is worse than no growl at all. Every tick it asks
 * the seeker whether it still says what this was started for — the same machine, the same weapon,
 * the same stage — and gives its channel back the moment any of the three has changed.
 */
public class SeekerSoundInstance extends AbstractTickableSoundInstance {
    /** The machine whose seeker this is, by entity number: climbing into another one is a new sound. */
    private final int vehicle;
    /** The weapon whose seeker this is, so that selecting another gets that one's recording. */
    @Nullable
    private final ResourceLocation weapon;
    private final SeekerSounds.Stage stage;
    /** The note at the moment the seeker takes something, and how far it climbs by the lock. */
    private final float base;
    private final float climb;

    public SeekerSoundInstance(SoundEvent sound, SeekerSounds.Readout readout, float base, float climb,
            float volume) {
        super(sound, SoundSource.MASTER, SoundInstance.createUnseededRandom());
        this.vehicle = readout.vehicle();
        this.weapon = readout.weapon();
        this.stage = readout.stage();
        this.base = base;
        this.climb = climb;
        // Round again if the recording runs out while the seeker is still on it.
        this.looping = true;
        this.delay = 0;
        this.volume = volume;
        this.pitch = base + climb * readout.progress();
        this.relative = true;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.x = 0.0;
        this.y = 0.0;
        this.z = 0.0;
    }

    /** Which of the seeker's two voices this is, so that the other can take over from it. */
    public SeekerSounds.Stage stage() {
        return this.stage;
    }

    /** True if this reading is still the one the sound was started for. */
    public boolean matches(@Nullable SeekerSounds.Readout readout) {
        return readout != null && readout.vehicle() == this.vehicle && readout.stage() == this.stage
                && Objects.equals(readout.weapon(), this.weapon);
    }

    @Override
    public void tick() {
        SeekerSounds.Readout now = SeekerSounds.readout();

        if (!this.matches(now)) {
            this.stop();

            return;
        }

        this.pitch = this.base + this.climb * now.progress();
    }
}
