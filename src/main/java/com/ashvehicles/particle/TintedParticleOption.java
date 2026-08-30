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
 * この MOD が描くパーティクルが生成時に受け取る情報。色と大きさ。
 *
 * <p>1つのオプションクラスで全種類をまかない、所属する型はクラスで固定せずオプションが持つ。だから煙も
 * 炎も砂粒も「別のテクスチャを指した同じ2つの数値」になる。これが、色や大きさを兵装ファイルで決められる
 * 理由（あるモーターの煙と別のモーターの煙は色違いの同じパーティクル）であり、ブロックから削れた欠片を
 * 元のブロックの色にできる理由でもある。
 *
 * @param type この MOD のどのパーティクルか
 * @param colour {@code RRGGBB}。テクスチャに乗算する。そのためテクスチャは白で描かれている
 * @param scale 大きさ。そのパーティクル本来の寸法に対する倍率
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
     * 型は一緒に書き出さない。読んでいるコーデックの持ち主がそのままオプションの型になる。これが1つの
     * オプションクラスで複数の型をまかなえる仕組み。
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
