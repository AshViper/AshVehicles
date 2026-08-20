package com.ashvehicles.network;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.sound.BlastSounds;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * A blast, and where and how big it was.
 *
 * <p>Sent instead of playing the sound the ordinary way, because the ordinary way cannot carry it.
 * A sound asked for on the server reaches nobody beyond {@code volume * 16} blocks, and the sound
 * engine's own falloff has run to nothing by the same distance, so an explosion is inaudible past
 * sixty-four blocks however loud it is said to be. That is a reasonable answer for a chest opening
 * and a useless one for half a tonne of high explosive, which in life is heard for miles.
 *
 * <p>So the server says only that a blast happened, and the client works out the rest: how long the
 * sound takes to get there, how much of it is left when it arrives, and how dull it has become on
 * the way. See {@link BlastSounds}.
 */
public record BlastSoundPayload(double x, double y, double z, float power) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BlastSoundPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "blast_sound"));

    public static final StreamCodec<FriendlyByteBuf, BlastSoundPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeDouble(payload.x());
                buf.writeDouble(payload.y());
                buf.writeDouble(payload.z());
                buf.writeFloat(payload.power());
            },
            buf -> new BlastSoundPayload(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat()));

    /** How far the blast is worth sending, in blocks. Nobody further off is told about it at all. */
    private static final double CARRY = 128.0;
    private static final double CARRY_PER_POWER = 56.0;

    /**
     * How far a blast of this size is heard, in blocks.
     *
     * <p>Both ends use it: the server to decide who is sent one at all, the client to decide how
     * much of it is left by the time it arrives. They have to agree, or a player at the edge is sent
     * a sound and then told it is silent.
     */
    public static double carry(float power) {
        return CARRY + power * CARRY_PER_POWER;
    }

    public Vec3 at() {
        return new Vec3(this.x, this.y, this.z);
    }

    @Override
    public CustomPacketPayload.Type<BlastSoundPayload> type() {
        return TYPE;
    }

    /**
     * Registered as client-bound only, so this runs on a client and nowhere else; a dedicated server
     * never resolves {@link BlastSounds}.
     */
    public static void handle(BlastSoundPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> BlastSounds.hear(payload.at(), payload.power()));
    }
}
