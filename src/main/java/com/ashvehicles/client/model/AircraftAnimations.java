package com.ashvehicles.client.model;

import javax.annotation.Nullable;

import com.ashvehicles.entity.AircraftEntity;

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

        return state.setAndContinue(aircraft.isGearDown() ? LOWERING : RAISING);
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
        if (aircraft.isGearSettled()) {
            return SETTLED;
        }

        Animation animation = gearAnimation(aircraft, aircraft.isGearDown() ? GEAR_DOWN : GEAR_UP);

        return animation == null ? 1.0 : animation.length() / Math.max(aircraft.getGearCycleTicks(), 1);
    }

    /** Whether this aircraft's animation file has both halves of the cycle in it. */
    public static boolean hasGearCycle(AircraftEntity aircraft) {
        return gearAnimation(aircraft, GEAR_DOWN) != null && gearAnimation(aircraft, GEAR_UP) != null;
    }

    /**
     * Fast enough that one frame of it is over any animation's last keyframe. Nothing is being
     * played at this speed; it is how the cycle is held at its end.
     */
    private static final double SETTLED = 1.0E4;

    @Nullable
    private static Animation gearAnimation(AircraftEntity aircraft, String name) {
        BakedAnimations file = GeckoLibCache.getBakedAnimations().get(AircraftModel.animationFile(aircraft));

        return file == null ? null : file.getAnimation(name);
    }

    private AircraftAnimations() {
    }
}
