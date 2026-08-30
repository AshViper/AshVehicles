package com.ashvehicles.client.particle;

import com.ashvehicles.particle.TintedParticleOption;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;

/**
 * 起爆の火球。
 *
 * <p>爆発が爆発に見えるのは、起きたと思う間もなく終わるからだ。2〜3tickで白熱しほぼ最大まで開き、兵器固有の色
 * を通って冷え、1秒足らずで消えて、あとは煙に任せる。これより遅い物は焚き火に見える。
 *
 * <p>常にどこでも自ら発光する。炎は世界に照らされる物ではないし、ロード範囲の外ではどのみち照らす物が無い。
 */
public class BlastParticle extends WeaponParticle {
    private static final int LIFE = 9;
    private static final int LIFE_JITTER = 6;
    /** 出現時点で既にどれだけ開いているか。最大サイズに対する割合。 */
    private static final float OPENS_AT = 0.35F;

    private final float tintRed;
    private final float tintGreen;
    private final float tintBlue;

    private BlastParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd,
            TintedParticleOption options, SpriteSet sprites) {
        super(level, x, y, z, options);
        this.tintRed = options.red();
        this.tintGreen = options.green();
        this.tintBlue = options.blue();
        this.lifetime = LIFE + this.random.nextInt(LIFE_JITTER);
        this.quadSize = options.scale() * (0.85F + this.random.nextFloat() * 0.3F);
        this.friction = 0.82F;
        this.gravity = -0.05F;
        this.hasPhysics = false;
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
        float lived = this.lived(0.0F);

        // 起爆の瞬間は白、直後に兵器固有の色、消える前には暗くする。最後に残る物が「消えゆく炎」ではなく
        // 「煤」に見えるように。
        float white = (1.0F - lived) * (1.0F - lived);
        float cool = 1.0F - lived * 0.65F;
        this.rCol = Mth.lerp(white, this.tintRed * cool, 1.0F);
        this.gCol = Mth.lerp(white, this.tintGreen * cool, 1.0F);
        this.bCol = Mth.lerp(white, this.tintBlue * cool, 1.0F);
        this.alpha = 1.0F - lived * lived;
    }

    @Override
    public float getQuadSize(float partialTick) {
        // 速く開いてから保つ。平方根が、成長のほぼ全てを最初の2〜3tickへ寄せている。
        return this.quadSize * (OPENS_AT + (1.0F - OPENS_AT) * Mth.sqrt(this.lived(partialTick)));
    }

    /** 炎はどこにいても自前の光源。 */
    @Override
    protected int getLightColor(float partialTick) {
        return LightTexture.FULL_BRIGHT;
    }

    private float lived(float partialTick) {
        return Mth.clamp(((float) this.age + partialTick) / (float) this.lifetime, 0.0F, 1.0F);
    }

    public static ParticleProvider<TintedParticleOption> provider(SpriteSet sprites) {
        return (options, level, x, y, z, xd, yd, zd) ->
                new BlastParticle(level, x, y, z, xd, yd, zd, options, sprites);
    }
}
