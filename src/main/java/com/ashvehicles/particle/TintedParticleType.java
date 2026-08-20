package com.ashvehicles.particle;

import com.mojang.serialization.MapCodec;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * One of the mod's particle types. There is nothing to it but its codecs and a shorthand for making
 * an option out of it.
 *
 * <p>Every one of them overrides the limiter, which is not the small decision it looks like. A
 * particle that does not is thrown away twice over: the server will not send it to anyone more than
 * thirty-two blocks off, and a client will not draw one further away than that even if it makes it
 * itself. Thirty-two blocks is a reasonable distance for a candle flame and a useless one for a
 * missile, which is worth watching from as far away as it can be seen and which is most interesting
 * exactly when it is a long way off.
 */
public class TintedParticleType extends ParticleType<TintedParticleOption> {
    private final MapCodec<TintedParticleOption> codec = TintedParticleOption.codec(this);
    private final StreamCodec<ByteBuf, TintedParticleOption> streamCodec = TintedParticleOption.streamCodec(this);

    public TintedParticleType() {
        super(true);
    }

    /** One of these particles, in a colour and a size. */
    public TintedParticleOption of(int colour, float scale) {
        return new TintedParticleOption(this, colour, scale);
    }

    @Override
    public MapCodec<TintedParticleOption> codec() {
        return this.codec;
    }

    @Override
    public StreamCodec<? super RegistryFriendlyByteBuf, TintedParticleOption> streamCodec() {
        return this.streamCodec;
    }
}
