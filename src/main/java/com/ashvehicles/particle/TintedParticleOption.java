package com.ashvehicles.particle;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What every particle the mod draws is told when it is made: what colour it is and how big.
 *
 * <p>One option class serves all of them, and the type it belongs to is carried in the option rather
 * than fixed by the class, so smoke and fire and grit are the same two numbers pointed at different
 * textures. That is what lets any of it be decided by a weapon's file — the smoke of one motor and
 * the smoke of another are the same particle in different colours — and what lets a chip knocked off
 * a block be the colour of the block it came off.
 *
 * @param type which of the mod's particles this is
 * @param colour {@code RRGGBB}, multiplied into the texture, which is drawn white for the purpose
 * @param scale how big, as a multiplier on whatever that particle's ordinary size is
 */
public record TintedParticleOption(ParticleType<TintedParticleOption> type, int colour, float scale)
        implements ParticleOptions {

    @Override
    public ParticleType<?> getType() {
        return this.type;
    }

    public float red() {
        return ((this.colour >> 16) & 0xFF) / 255.0F;
    }

    public float green() {
        return ((this.colour >> 8) & 0xFF) / 255.0F;
    }

    public float blue() {
        return (this.colour & 0xFF) / 255.0F;
    }

    /**
     * The type is not written out with the rest: whichever type's codec is doing the reading is the
     * type the option belongs to, which is how one option class can serve several of them.
     */
    static MapCodec<TintedParticleOption> codec(ParticleType<TintedParticleOption> type) {
        return RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.INT.fieldOf("colour").forGetter(TintedParticleOption::colour),
                Codec.FLOAT.fieldOf("scale").forGetter(TintedParticleOption::scale)
        ).apply(instance, (colour, scale) -> new TintedParticleOption(type, colour, scale)));
    }

    static StreamCodec<ByteBuf, TintedParticleOption> streamCodec(ParticleType<TintedParticleOption> type) {
        return StreamCodec.composite(
                ByteBufCodecs.INT, TintedParticleOption::colour,
                ByteBufCodecs.FLOAT, TintedParticleOption::scale,
                (colour, scale) -> new TintedParticleOption(type, colour, scale));
    }
}
