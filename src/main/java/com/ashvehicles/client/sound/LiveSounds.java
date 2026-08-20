package com.ashvehicles.client.sound;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.world.entity.Entity;

/**
 * One live sound per entity, for the sounds that belong to a thing rather than to a moment.
 *
 * <p>An engine, a motor and an undercarriage are all the same problem: something that goes on for as
 * long as the thing making it is doing what it does, and which nobody sends a packet about. The
 * answer each time is to keep a list of the entities this client can see and make sure every one of
 * them that ought to be making a noise has a sound running.
 *
 * <p>Written as a list of entities rather than a sound tied to each entity for life because a sound
 * is a channel, and channels are few. Anything out of earshot or not doing the thing gives its
 * channel back, {@link #tick} notices the gap and starts another when that changes, and a sound lost
 * to a resource reload or a volume slider comes back on its own.
 *
 * <p>Starting is not attempted every tick. A sound that could not start — because the volume is
 * down, or there was no channel free — would otherwise be tried twenty times a second for as long as
 * the entity lived. The interval is measured against each entity's own age, so a sky full of them
 * spreads the attempts out rather than making them all together.
 */
public final class LiveSounds<T extends Entity> {
    /** What to play for this entity right now, or null if it should not be making a noise yet. */
    @FunctionalInterface
    public interface Starter<T> {
        @Nullable
        AbstractTickableSoundInstance start(T entity);
    }

    private final Class<T> kind;
    private final int retryTicks;
    private final Starter<T> starter;
    /** Entities in the current level, and the sound each one has, if it has one right now. */
    private final Map<T, AbstractTickableSoundInstance> sounds = new HashMap<>();

    public LiveSounds(Class<T> kind, int retryTicks, Starter<T> starter) {
        this.kind = kind;
        this.retryTicks = Math.max(1, retryTicks);
        this.starter = starter;
    }

    /** Takes note of an entity that has just come into the level, if it is one of ours. */
    public void offer(Entity entity) {
        if (this.kind.isInstance(entity)) {
            this.sounds.putIfAbsent(this.kind.cast(entity), null);
        }
    }

    /** Leaving the world takes the whole list with it; the next one is somebody else's sky. */
    public void forget() {
        this.sounds.clear();
    }

    public void tick(Minecraft minecraft) {
        if (this.sounds.isEmpty()) {
            return;
        }

        SoundManager manager = minecraft.getSoundManager();
        Iterator<Map.Entry<T, AbstractTickableSoundInstance>> entries = this.sounds.entrySet().iterator();

        while (entries.hasNext()) {
            Map.Entry<T, AbstractTickableSoundInstance> entry = entries.next();
            T entity = entry.getKey();

            if (entity.isRemoved() || entity.level() != minecraft.level) {
                entries.remove();

                continue;
            }

            AbstractTickableSoundInstance sound = entry.getValue();

            if (sound != null && !sound.isStopped() && manager.isActive(sound)) {
                continue;
            }

            if (entity.tickCount % this.retryTicks != 0) {
                continue;
            }

            AbstractTickableSoundInstance started = this.starter.start(entity);
            entry.setValue(started);

            if (started != null) {
                manager.play(started);
            }
        }
    }
}
