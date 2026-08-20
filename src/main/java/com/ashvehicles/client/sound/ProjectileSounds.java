package com.ashvehicles.client.sound;

import javax.annotation.Nullable;

import com.ashvehicles.entity.RocketEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/**
 * Gives everything in the air that a gun did not fire the noise it makes on the way.
 *
 * <p>Only {@link RocketEntity} is watched, which is exactly that: a rocket, a missile and a bomb are
 * all the one entity, and which of them any particular one is comes from the weapon it left. A gun's
 * rounds are the other entity and there are a great many of them a second, so not tracking them at
 * all is worth more than deciding one at a time that they are silent.
 *
 * <p><b>Which recording.</b> The one named after the weapon and after what it is doing,
 * {@code <namespace>:weapon.<name>.flight} or {@code weapon.<name>.fall}; else the mod's
 * {@code ashvehicles:weapon.flight} or {@code ashvehicles:weapon.fall}. <b>Neither of those is
 * shipped</b>, and a weapon with nothing under either name flies silently: both are loops, and a
 * loop that was not cut to loop is worse than nothing. See {@link ModSounds}.
 */
public final class ProjectileSounds {
    /**
     * How often a round with no live sound is looked at again. Short, because these do not live long,
     * and because one fired well out of earshot can be on top of the listener a second later.
     */
    private static final int RETRY_TICKS = 5;

    /** Every round in the air this client can see, and the noise it is making. */
    public static final LiveSounds<RocketEntity> SOUNDS =
            new LiveSounds<>(RocketEntity.class, RETRY_TICKS, ProjectileSounds::start);

    @Nullable
    private static ProjectileSoundInstance start(RocketEntity projectile) {
        ProjectileSoundInstance.Kind kind = ProjectileSoundInstance.Kind.of(projectile.getWeapon());

        // A motor that has already burnt out is not going to start making a noise again. Being out of
        // earshot is worth asking about again, on the other hand, since these move faster than
        // anything else in the sky.
        if (kind == null
                || (kind == ProjectileSoundInstance.Kind.MOTOR && !projectile.isBurning())
                || EntitySoundInstance.falloff(projectile, kind.range) <= 0.0F) {
            return null;
        }

        SoundManager sounds = Minecraft.getInstance().getSoundManager();
        ResourceLocation recording = ModSounds.firstPresent(sounds,
                ModSounds.named(projectile.getWeaponId(), ModSounds.WEAPON_PREFIX, kind.role), kind.fallback);

        return recording == null
                ? null
                : new ProjectileSoundInstance(projectile, SoundEvent.createVariableRangeEvent(recording), kind);
    }

    private ProjectileSounds() {
    }
}
