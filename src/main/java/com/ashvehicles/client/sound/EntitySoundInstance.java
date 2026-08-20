package com.ashvehicles.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * A sound that belongs to a moving thing, and is over when the thing stops doing whatever makes it.
 *
 * <p>Everything the mod loops — an engine, a rocket motor, the air past a falling bomb, an
 * undercarriage winding up — is the same sound in this respect: it follows an entity, it is louder
 * or quieter depending on what that entity is doing, and it has to end itself, because nothing else
 * knows when it should.
 *
 * <p>Distance is worked out here rather than left to the sound engine. The engine fades a sound to
 * nothing over {@code max(volume, 1) * 16} blocks, which is sixty-four at best; an aeroplane is
 * heard much further off than that and a missile crosses sixty-four blocks in two seconds. So these
 * are played with attenuation switched off, at the entity's real position so they still come from
 * the right direction, and with the falloff put into the volume against a range this side chooses.
 *
 * <p>Ending itself matters as much. A sound is a channel and channels are few, so a sound with
 * nothing left to say — out of earshot, or faded out — gives its channel back rather than looping
 * silently for the rest of the entity's life. {@link LiveSounds} starts another if the entity comes
 * back into earshot.
 */
public abstract class EntitySoundInstance<T extends Entity> extends AbstractTickableSoundInstance {
    /** Below this a fade-out is treated as finished. */
    protected static final float SILENCE = 0.004F;

    private final T entity;
    /** How long it must have had nothing to say before it gives up its channel. */
    private final int quietTicksBeforeStop;
    private int quietTicks;

    protected EntitySoundInstance(T entity, SoundEvent sound, SoundSource source, int quietTicksBeforeStop) {
        super(sound, source, SoundInstance.createUnseededRandom());
        this.entity = entity;
        this.quietTicksBeforeStop = quietTicksBeforeStop;
        this.looping = true;
        this.delay = 0;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.volume = 0.0F;
        this.follow();
    }

    /** How much of full volume is left at this distance from the listener, in [0, 1]. */
    public static float falloff(Entity entity, double range) {
        Vec3 listener = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();

        return (float) Mth.clamp(1.0 - listener.distanceTo(entity.position()) / Math.max(range, 1.0E-3), 0.0, 1.0);
    }

    /** Closes part of the gap between where a figure is and where it should be, once. */
    protected static float approach(float current, float target, float rate) {
        return current + (target - current) * rate;
    }

    protected T entity() {
        return this.entity;
    }

    protected float falloff(double range) {
        return falloff(this.entity, range);
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public boolean canPlaySound() {
        return !this.entity.isSilent();
    }

    @Override
    public void tick() {
        // Both worth checking: an entity that has gone leaves nothing to follow, and one in another
        // level is one this client is no longer in earshot of by any measure.
        if (this.entity.isRemoved() || this.entity.level() != Minecraft.getInstance().level) {
            this.stop();

            return;
        }

        this.follow();
        this.update();
    }

    /** One tick of whatever this sound is: set {@link #volume} and {@link #pitch}, then say {@link #heard}. */
    protected abstract void update();

    /** Whether there was anything to hear this tick. Enough of a run of nothing ends the sound. */
    protected void heard(boolean audible) {
        this.quietTicks = audible ? 0 : this.quietTicks + 1;

        if (this.quietTicks > this.quietTicksBeforeStop) {
            this.stop();
        }
    }

    private void follow() {
        this.x = this.entity.getX();
        this.y = this.entity.getY();
        this.z = this.entity.getZ();
    }
}
