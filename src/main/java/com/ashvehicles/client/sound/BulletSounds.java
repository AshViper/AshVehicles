package com.ashvehicles.client.sound;

import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import com.ashvehicles.entity.BulletEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The crack of a round going past.
 *
 * <p>Everything else in the air here is given a loop that follows it — see {@link ProjectileSounds}
 * — and a gun's round is deliberately not, for two reasons that both still hold. There are a great
 * many of them, and a channel each is not something the sound engine has to give; and a round is
 * only in the air for a moment. A tank shell crosses three hundred blocks in seven ticks, so a loop
 * that faded in over a quarter of a second would spend most of its life fading in and the rest of it
 * out of earshot.
 *
 * <p>But that is an argument against a loop, not against a sound. What a round going past actually
 * sounds like is one short crack, at the moment it passes and at the place it passes — and that is
 * cheap: one distance a tick for each round the client can see, and a one-shot for the few that come
 * near enough to be worth hearing.
 *
 * <p><b>Where it is played matters more than what it is.</b> A round covers forty blocks in a tick,
 * so the place it was at the start of the tick and the place it is at the end are both a long way
 * from wherever it went past — playing at either would put the crack behind the listener or well
 * ahead of them. What is worked out instead is the nearest the round's <em>path</em> across that
 * tick came to the listener, and the crack is played there, so it arrives from the direction the
 * round really came from.
 *
 * <p>Not on the first tick of a round's life, which is the one that starts at the muzzle. A gun
 * fired from the machine the listener is aboard would otherwise crack in their ear on top of its own
 * report, twenty times a second on a machine gun; a tick later the round is well downrange and the
 * question stops being about the gun and starts being about the round.
 *
 * <p><b>Which recording.</b> {@code <namespace>:weapon.<name>.crack} for a weapon that has one of
 * its own, else the mod's {@code ashvehicles:weapon.crack}, else the game's own sweep — which is the
 * nearest thing it has to something small going through the air fast. So this is audible with no
 * recording at all, and better with one. See {@link ModSounds}.
 */
public final class BulletSounds {
    /**
     * How near a round has to pass to be worth a crack, in blocks.
     *
     * <p>Rather less than the sound engine would carry it, which is deliberate: this is the noise of
     * a round going past you and not the noise of one going past somebody else. Beyond it there is
     * nothing to hear, which is also what keeps a firefight two hundred blocks away from being a
     * wall of cracks.
     */
    private static final double CRACK_DISTANCE = 16.0;

    /**
     * Most cracks played in one tick, however many rounds went by.
     *
     * <p>An autocannon puts a round in the air every tick and a burst arrives as a burst, so some
     * cap is needed or a single gun aimed at the listener is twenty one-shots a second and every
     * other sound in the game loses its channel. Three is enough for a burst to read as a burst.
     */
    private static final int MOST_PER_TICK = 3;

    /** The game's own sweep, for a weapon and a pack that have both recorded nothing. */
    private static final ResourceLocation CRACK_FALLBACK =
            ResourceLocation.withDefaultNamespace("entity.player.attack.sweep");

    private static final float LOW_PITCH = 0.9F;
    private static final float HIGH_PITCH = 1.15F;

    /**
     * Every round in the air this client can see and has not yet cracked for.
     *
     * <p>Weakly, so that a round the level forgets about takes its entry with it whatever happens to
     * the tick that would have pruned it. One crack each: a bullet flies straight, so once it has
     * passed it is going away and will never be nearer than it was.
     */
    private static final Set<BulletEntity> LIVE = Collections.newSetFromMap(new WeakHashMap<>());

    private BulletSounds() {
    }

    /** Takes note of a round that has just come into the level, if it is one. */
    public static void offer(Entity entity) {
        if (entity instanceof BulletEntity round) {
            LIVE.add(round);
        }
    }

    /** Leaving the world takes the whole list with it; the next one is somebody else's sky. */
    public static void forget() {
        LIVE.clear();
    }

    public static void tick(Minecraft minecraft) {
        if (LIVE.isEmpty() || minecraft.level == null) {
            return;
        }

        // The camera and not the crew's own eyes: it is the camera the sound engine listens from, so
        // it is the camera that has to decide what came near enough to be heard. On a tank they are
        // a dozen blocks apart, which is most of this distance.
        Vec3 ear = minecraft.gameRenderer.getMainCamera().getPosition();
        SoundManager sounds = minecraft.getSoundManager();
        Iterator<BulletEntity> rounds = LIVE.iterator();
        int left = MOST_PER_TICK;

        while (rounds.hasNext()) {
            BulletEntity round = rounds.next();

            if (round.isRemoved() || round.level() != minecraft.level) {
                rounds.remove();

                continue;
            }

            // The tick that starts at the muzzle is the gun's, not the round's.
            if (round.tickCount <= 1) {
                continue;
            }

            Vec3 passed = nearestApproach(round, ear);

            if (passed == null || passed.distanceToSqr(ear) > CRACK_DISTANCE * CRACK_DISTANCE) {
                continue;
            }

            // Removed whether or not there is a channel for it: it has gone past, and the crack it
            // did not get is not owed to it next tick from further away.
            rounds.remove();

            if (left <= 0) {
                continue;
            }

            SoundEvent crack = crackOf(sounds, round);

            if (crack == null) {
                continue;
            }

            float pitch = Mth.lerp(minecraft.level.getRandom().nextFloat(), LOW_PITCH, HIGH_PITCH);

            minecraft.level.playLocalSound(passed.x, passed.y, passed.z, crack, SoundSource.NEUTRAL,
                    1.0F, pitch, false);
            left--;
        }
    }

    /**
     * The nearest the round's path across this tick came to the listener, or null if it has not
     * moved yet.
     *
     * <p>The path and not the round: at forty blocks a tick the two ends of it are both a long way
     * from wherever it actually went by, and a crack played at either is a crack from the wrong
     * direction.
     */
    @Nullable
    private static Vec3 nearestApproach(BulletEntity round, Vec3 ear) {
        Vec3 from = new Vec3(round.xOld, round.yOld, round.zOld);
        Vec3 step = round.position().subtract(from);
        double flown = step.lengthSqr();

        if (flown < 1.0E-6) {
            return null;
        }

        // Clamped to the step, so a round that has not reached the listener yet is measured at the
        // near end of its path rather than at a point it has not been to.
        double along = Mth.clamp(ear.subtract(from).dot(step) / flown, 0.0, 1.0);

        return from.add(step.scale(along));
    }

    @Nullable
    private static SoundEvent crackOf(SoundManager sounds, BulletEntity round) {
        ResourceLocation recording = ModSounds.firstPresent(sounds,
                ModSounds.named(round.getWeaponId(), ModSounds.WEAPON_PREFIX, ModSounds.CRACK_ROLE),
                ModSounds.CRACK, CRACK_FALLBACK);

        return recording == null ? null : SoundEvent.createVariableRangeEvent(recording);
    }
}
