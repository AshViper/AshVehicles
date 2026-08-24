package com.ashvehicles.client.model;

import javax.annotation.Nullable;

import com.ashvehicles.entity.AircraftEntity;

import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.Animation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.loading.object.BakedAnimations;

/**
 * The undercarriage cycle, which is the one part of an aircraft that is animated rather than posed.
 *
 * <p>Everything else that moves follows the flight continuously and is set from code in
 * {@link AircraftModel}: an aileron is simply wherever the roll rate says it is, and there is no
 * sequence to it. The gear is not like that. It is a sequence, and the whole of what makes it look
 * like an aeroplane's undercarriage is the <i>order</i> — the bay doors go first and the leg follows
 * it out, and on the way back in the leg goes first and the doors shut over it. That belongs in a
 * file someone can open in Blockbench, not in a pair of numbers interpolated in Java.
 *
 * <p>An aircraft whose file has no gear cycle in it is not an error. {@link AircraftModel} swings
 * the legs from code instead, exactly as everything did before, so a new aeroplane flies the day it
 * is drawn and gets its animation whenever someone gets round to drawing that too.
 */
public final class AircraftAnimations {
    public static final String GEAR_DOWN = "gear_down";
    public static final String GEAR_UP = "gear_up";

    /** Blend into the gear cycle, for a pilot who changes their mind partway through one. */
    public static final int TRANSITION_TICKS = 4;

    private static final RawAnimation LOWERING = RawAnimation.begin().thenPlayAndHold(GEAR_DOWN);
    private static final RawAnimation RAISING = RawAnimation.begin().thenPlayAndHold(GEAR_UP);

    /**
     * What the controller should be playing.
     *
     * <p>Only the two ends are named. Which way the gear is travelling right now is not asked about
     * at all: the aircraft either wants it down or wants it up, and the animation that ends in that
     * state is the one to be playing.
     */
    public static PlayState gearCycle(AnimationState<AircraftEntity> state) {
        AircraftEntity aircraft = state.getAnimatable();

        if (!hasGearCycle(aircraft)) {
            return PlayState.STOP;
        }

        return state.setAndContinue(cycleFor(aircraft.isGearDown()));
    }

    /**
     * The half of the cycle that ends with the gear where it is wanted, for a caller with no
     * aircraft to ask — a ghost, drawn from a snapshot of one.
     */
    public static RawAnimation cycleFor(boolean gearDown) {
        return gearDown ? LOWERING : RAISING;
    }

    /**
     * How fast to play it.
     *
     * <p>Two jobs. Ordinarily the animation is stretched or squeezed to whatever the aircraft's file
     * says a gear cycle takes, so there is one figure deciding both how long the legs take to come
     * out and when the drag of having them out arrives, rather than two that have to be kept in step
     * by hand.
     *
     * <p>The other job is what happens when the gear is already where it wants to be. An aircraft
     * that comes into view with its wheels down should be sitting there with its wheels down, not
     * lowering them again for the benefit of whoever just looked. Winding the animation forward at
     * an absurd speed puts it past its last keyframe within a frame, and
     * {@code hold_on_last_frame} does the rest: what is drawn is the end of the cycle, which is
     * exactly the pose that is wanted. The moment the pilot moves the lever the gear stops being
     * settled and the cycle plays at its proper speed.
     */
    public static double gearSpeed(AircraftEntity aircraft) {
        return gearSpeed(AircraftModel.animationFile(aircraft), aircraft.isGearDown(),
                aircraft.getGearCycleTicks(), aircraft.isGearSettled());
    }

    /** The same, from the four things it depends on, for a ghost drawn without its aircraft. */
    public static double gearSpeed(@Nullable ResourceLocation animationFile, boolean gearDown, int cycleTicks,
            boolean settled) {
        if (settled) {
            return SETTLED;
        }

        Animation animation = gearAnimation(animationFile, gearDown ? GEAR_DOWN : GEAR_UP);

        return animation == null ? 1.0 : animation.length() / Math.max(cycleTicks, 1);
    }

    /** Whether this aircraft's animation file has both halves of the cycle in it. */
    public static boolean hasGearCycle(AircraftEntity aircraft) {
        return hasGearCycle(AircraftModel.animationFile(aircraft));
    }

    /** The same, by file: what a ghost knows about the aircraft it stands for. */
    public static boolean hasGearCycle(@Nullable ResourceLocation animationFile) {
        return gearAnimation(animationFile, GEAR_DOWN) != null
                && gearAnimation(animationFile, GEAR_UP) != null;
    }

    /**
     * Fast enough that one frame of it is over any animation's last keyframe. Nothing is being
     * played at this speed; it is how the cycle is held at its end.
     */
    private static final double SETTLED = 1.0E4;

    @Nullable
    private static Animation gearAnimation(@Nullable ResourceLocation animationFile, String name) {
        if (animationFile == null) {
            return null;
        }

        BakedAnimations file = GeckoLibCache.getBakedAnimations().get(animationFile);

        return file == null ? null : file.getAnimation(name);
    }

    private AircraftAnimations() {
    }
}
