package com.ashvehicles.sensor;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import io.netty.buffer.ByteBuf;

/**
 * Somebody paying this aircraft the wrong sort of attention, and how much of it.
 *
 * <p>A warning receiver does not see anything; it hears. So there is no range here — only which way
 * it is coming from and how bad it is, which is all a receiver can honestly tell you and all a pilot
 * has time to read.
 *
 * @param bearing degrees off the nose, positive to the right
 * @param kind how far along the attention has got
 */
public record Threat(float bearing, Kind kind) {
    public static final StreamCodec<ByteBuf, Threat> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, Threat::bearing,
            ByteBufCodecs.idMapper(Kind::byId, Kind::ordinal), Threat::kind,
            Threat::new);

    /** The three things worth being told, in the order they usually happen. */
    public enum Kind {
        /** Somebody's radar has you on their scope. They know you are there. */
        SEARCH,
        /** Somebody's seeker has taken you. The next thing that happens is a launch. */
        LOCK,
        /** Something is in the air and coming for you. */
        MISSILE;

        private static final Kind[] BY_ID = values();

        public static Kind byId(int id) {
            return BY_ID[Math.floorMod(id, BY_ID.length)];
        }

        /** Whether this is worse than that, so that one threat can stand for one aircraft. */
        public boolean worseThan(Kind other) {
            return this.ordinal() > other.ordinal();
        }
    }
}
