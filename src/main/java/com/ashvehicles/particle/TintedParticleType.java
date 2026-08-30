package com.ashvehicles.particle;

import com.mojang.serialization.MapCodec;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * この MOD のパーティクル型。中身はコーデックと、オプションを作る簡易メソッドだけ。
 *
 * <p>全型が limiter を上書きしているが、これは見た目ほど小さな判断ではない。上書きしないパーティクル
 * は二重に捨てられる。サーバーは32ブロック以上離れた相手に送らず、クライアントは自分で生成した物さえ
 * それ以上遠ければ描かない。32ブロックはロウソクの炎には妥当でミサイルには無意味な距離で、ミサイルは
 * 見える限り遠くから見る価値があり、しかも遠い時こそ一番面白い。
 */
public class TintedParticleType extends ParticleType<TintedParticleOption> {
    private final MapCodec<TintedParticleOption> codec = TintedParticleOption.codec(this);
    private final StreamCodec<ByteBuf, TintedParticleOption> streamCodec = TintedParticleOption.streamCodec(this);

    public TintedParticleType() {
        super(true);
    }

    /** このパーティクルを色と大きさ指定で1つ。 */
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
