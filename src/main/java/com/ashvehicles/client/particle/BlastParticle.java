package com.ashvehicles.client.particle;

import com.ashvehicles.particle.TintedParticleOption;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;

/**
 * The fireball of a detonation.
 *
 * <p>What makes a blast read as a blast is that it is over almost before it has happened. It opens
 * white-hot and at nearly its full size within two or three ticks, cools through the weapon's own
 * colour, and is gone in under a second, leaving the smoke to do the rest. Anything slower looks
 * like a bonfire.
 *
 * <p>It lights itself, everywhere, always: fire is not lit by the world, and out beyond the loaded
 * world there would be nothing to light it anyway.
 */
public class BlastParticle extends WeaponParticle {
    private static final int LIFE = 9;
    private static final int LIFE_JITTER = 6;
    /** How far open it already is when it appears, as a fraction of full size. */
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

        // White at the instant it goes off, the weapon's own colour a moment later, and dark before
        // it disappears, so what is left behind reads as soot rather than as the flame fading out.
        float white = (1.0F - lived) * (1.0F - lived);
        float cool = 1.0F - lived * 0.65F;
        this.rCol = Mth.lerp(white, this.tintRed * cool, 1.0F);
        this.gCol = Mth.lerp(white, this.tintGreen * cool, 1.0F);
        this.bCol = Mth.lerp(white, this.tintBlue * cool, 1.0F);
        this.alpha = 1.0F - lived * lived;
    }

    @Override
    public float getQuadSize(float partialTick) {
        // Opens fast and then holds: the square root is what puts nearly all of the growth into the
        // first couple of ticks.
        return this.quadSize * (OPENS_AT + (1.0F - OPENS_AT) * Mth.sqrt(this.lived(partialTick)));
    }

    /** Fire is its own light, wherever it is. */
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
