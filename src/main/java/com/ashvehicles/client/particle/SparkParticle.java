package com.ashvehicles.client.particle;

import com.ashvehicles.particle.TintedParticleOption;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;

/**
 * 着弾から飛び散る小片。2種類ある。燃えていて自ら発光する火花と、被弾した物の破片で燃えても光りもしない方だ。
 *
 * <p>挙動が同一なので1つのクラスにしてある。どちらも外向きに飛ばされ、自重で引き下ろされ、地面をすり抜けず
 * 跳ね、フェードせず縮んで消える。ひと掴みのそれらが土埃ではなく破片に見えるのはそのためだ。
 */
public class SparkParticle extends WeaponParticle {
    private static final int LIFE = 10;
    private static final int LIFE_JITTER = 16;
    private static final float SIZE = 0.075F;

    private final boolean burning;

    private SparkParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd,
            TintedParticleOption options, SpriteSet sprites, boolean burning) {
        super(level, x, y, z, options);
        this.burning = burning;
        this.lifetime = LIFE + this.random.nextInt(LIFE_JITTER);
        this.quadSize = SIZE * options.scale() * (0.6F + this.random.nextFloat() * 0.8F);
        this.friction = 0.94F;
        this.gravity = burning ? 0.6F : 1.4F;
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        this.roll = this.random.nextFloat() * Mth.TWO_PI;
        this.oRoll = this.roll;
        this.setSprite(sprites.get(this.random));
    }

    @Override
    public void tick() {
        super.tick();

        // 火花は消えながら冷える。石の破片は冷えないので、単に取り除かれるだけ。
        if (this.burning) {
            float left = 1.0F - this.lived(0.0F);
            this.gCol = Math.min(this.gCol, 0.25F + left * 0.75F);
            this.bCol = Math.min(this.bCol, left * left * 0.9F);
        }
    }

    @Override
    public float getQuadSize(float partialTick) {
        return this.quadSize * (1.0F - this.lived(partialTick) * 0.8F);
    }

    /** 火花はどこにいても自前の光源。破片は周囲の光で照らされる。 */
    @Override
    protected int getLightColor(float partialTick) {
        return this.burning ? LightTexture.FULL_BRIGHT : super.getLightColor(partialTick);
    }

    private float lived(float partialTick) {
        return Mth.clamp(((float) this.age + partialTick) / (float) this.lifetime, 0.0F, 1.0F);
    }

    public static ParticleProvider<TintedParticleOption> provider(SpriteSet sprites, boolean burning) {
        return (options, level, x, y, z, xd, yd, zd) ->
                new SparkParticle(level, x, y, z, xd, yd, zd, options, sprites, burning);
    }
}
