package com.ashvehicles.sensor;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import io.netty.buffer.ByteBuf;

/**
 * One thing the radar has found, as the scope needs it rather than as the world holds it.
 *
 * <p>Everything here is worked out on the server and sent as figures, not as an entity to look up.
 * That is deliberate: a radar reaches several hundred blocks and a player on the ground is sent to
 * other clients over a much shorter distance than that, so half the contacts on a scope are things
 * the client has never been told about and could not measure for itself. A bearing and a range it
 * can draw.
 *
 * @param id the entity, for matching a contact against the one the seeker is on. Not looked up
 * @param bearing degrees off the nose, positive to the right
 * @param range how far away, in blocks
 * @param altitude how much higher than this aircraft it is, in blocks. Negative for below
 * @param locked whether this is what the seeker has taken
 * @param aircraft whether it is an aeroplane rather than somebody on foot
 */
public record Contact(int id, float bearing, float range, float altitude, boolean locked, boolean aircraft) {
    public static final StreamCodec<ByteBuf, Contact> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Contact::id,
            ByteBufCodecs.FLOAT, Contact::bearing,
            ByteBufCodecs.FLOAT, Contact::range,
            ByteBufCodecs.FLOAT, Contact::altitude,
            ByteBufCodecs.BOOL, Contact::locked,
            ByteBufCodecs.BOOL, Contact::aircraft,
            Contact::new);
}
