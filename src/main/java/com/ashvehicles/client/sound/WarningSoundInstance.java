package com.ashvehicles.client.sound;

import com.ashvehicles.client.RadarReadout;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.sensor.Threat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * The warning receiver sounding off, held for as long as the trouble lasts.
 *
 * <p>One sound, running, rather than a short one started again and again. What a receiver's alarm
 * actually is, and what anybody recording one actually records, is a continuous tone with its own
 * rhythm already in it — so the mod's job is to start it, keep it going while it is deserved, and
 * stop it the moment it is not.
 *
 * <p>Played flat into the cockpit: no position, no attenuation, no falling off with distance. It is
 * a box behind the pilot's seat, not the aeroplane threatening them.
 *
 * <p>It ends itself rather than waiting to be told. The one thing this must never do is outlast what
 * it is warning about — a missile alarm still going after the missile has gone past is worse than no
 * alarm at all — so every tick it asks whether the receiver still says what it was started for, and
 * gives its channel back the moment the answer changes.
 */
public class WarningSoundInstance extends AbstractTickableSoundInstance {
    private final Threat.Kind kind;

    public WarningSoundInstance(SoundEvent sound, Threat.Kind kind, float pitch, float volume) {
        super(sound, SoundSource.MASTER, SoundInstance.createUnseededRandom());
        this.kind = kind;
        // Round again if the recording runs out while the trouble has not.
        this.looping = true;
        this.delay = 0;
        this.volume = volume;
        this.pitch = pitch;
        this.relative = true;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.x = 0.0;
        this.y = 0.0;
        this.z = 0.0;
    }

    /** Which warning this is sounding, so that a worse one can take over from it. */
    public Threat.Kind kind() {
        return this.kind;
    }

    @Override
    public void tick() {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null || !(minecraft.player.getVehicle() instanceof AircraftEntity)
                || RadarReadout.worst() != this.kind) {
            this.stop();
        }
    }
}
