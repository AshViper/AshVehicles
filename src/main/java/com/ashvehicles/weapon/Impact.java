package com.ashvehicles.weapon;

import java.util.Optional;

import com.ashvehicles.AshVehicles;

import net.minecraft.resources.ResourceLocation;

/**
 * A round going into one of the mod's own boxes instead of skidding off it.
 *
 * <p>The other half of {@link Ricochet}, and the half that had nothing to say. A round thrown off
 * armour has had a clang since there was armour to throw it off; a round that <em>went in</em> made
 * a scatter of sparks and no noise whatsoever, because {@code WeaponEffects.detonation} only reaches
 * for a sound when the round carries a blast to make one. Which meant that the two rounds a tank
 * actually fires — an armour-piercing shot and a burst of machine-gun fire, neither of which carries
 * anything that goes off — landed in silence, and the one piece of feedback a gunner most wants was
 * the one thing the mod did not give them.
 *
 * <p>So this is the noise of the hit itself: struck at the plate, heard from where the shot came
 * from, and deliberately not the same noise as a ricochet. That difference is the whole point of
 * having it — a hard flat clang means the round left again and a heavy thud means it did not, and a
 * gunner who can tell those apart by ear knows whether to shoot at the same place again without
 * waiting to see whether anything catches fire.
 *
 * <p>Only for a round with no blast of its own. Anything that goes off where it lands is already
 * heard going off — see {@code Effects.boom} — and a clang laid over the top of that is not a second
 * piece of information, it is the same one twice.
 *
 * <p><b>Which recording.</b> {@code <namespace>:weapon.<name>.impact} for a weapon that has one of
 * its own, else the mod's {@code ashvehicles:weapon.impact}, else the game's own anvil being set
 * down — which is the nearest thing it has to something heavy arriving in metal and staying there,
 * and is a duller noise than the anvil <em>landing</em> that a ricochet falls back on. See
 * {@link com.ashvehicles.client.sound.WeaponSounds}, which does the choosing and the distance.
 */
public final class Impact {
    /** The tail of the sound event's name: {@code weapon.<weapon>.impact}. */
    public static final String SOUND_ROLE = "impact";

    /** What any weapon with no strike of its own falls back on. Named by the server. */
    public static final ResourceLocation SOUND = ResourceLocation.fromNamespaceAndPath(
            AshVehicles.MODID, WeaponMounts.SOUND_PREFIX + SOUND_ROLE);

    /**
     * How far a hit carries, on the scale a weapon's report uses: the figure is a reach in
     * {@link WeaponDefinition.SoundSetup#carry()} rather than a loudness.
     *
     * <p>Further than a ricochet and well under the gun that fired. Tank gunnery is fought at ranges
     * a shout does not cross, and a hit nobody at the trigger can hear is a hit that may as well not
     * have been scored; but the shot itself is a much bigger noise than the arrival of it, and the
     * two should not come back at the same weight.
     */
    public static final float VOLUME = 1.5F;

    /**
     * Pitched down, which is what tells it apart from a ricochet by ear.
     *
     * <p>A skid off plate is a bright hard note and is pitched up to say so; a round that stopped in
     * the armour is the opposite — everything it was carrying went into the metal at once, and what
     * comes back is low and short.
     */
    public static final float PITCH = 0.85F;

    /**
     * The two together, as the one object both ends of the sound read them from. The server asks it
     * how far the hit should carry and the client asks it how loud that comes to where the listener
     * is standing; they have to be the same figures or the sound arrives at the wrong loudness or
     * not at all.
     */
    public static final WeaponDefinition.SoundSetup SOUND_SETUP =
            new WeaponDefinition.SoundSetup(Optional.empty(), VOLUME, PITCH);

    private Impact() {
    }

    /** The sound event for one weapon's hits, which a pack may record on its own. */
    public static ResourceLocation soundFor(ResourceLocation weapon) {
        return weapon.withPath(WeaponMounts.SOUND_PREFIX + weapon.getPath() + "." + SOUND_ROLE);
    }
}
