package com.ashvehicles.client.particle;

import com.ashvehicles.particle.TintedParticleOption;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;

/**
 * A lick of flame off something that is on fire and going to be for a while.
 *
 * <p>Deliberately not {@link BlastParticle}, which is the same texture doing the opposite job. A
 * detonation opens white-hot at nearly full size and is gone inside a second, and a row of those is
 * a string of small explosions rather than a fire. A flame is the other way round: it starts where
 * it started, climbs, narrows as it climbs, and goes out red — so what a burning wreck looks like is
 * a column of these leaving it one after another, each of them a little tongue on its way to
 * becoming the smoke above.
 *
 * <p>Fire is its own light, wherever it is. That matters twice here: a wreck lies on the ground,
 * where the night would otherwise take the flame with it, and one that came down beyond the loaded
 * world has no chunk to be lit by at all.
 */
public class FlameParticle extends WeaponParticle {
    private static final int LIFE = 14;
    private static final int LIFE_JITTER = 12;
    /** What a lick has left of its width by the time it goes out. Flames taper as they rise. */
    private static final float TAPERS_TO = 0.2F;
    /** How much of its life is spent yellow-white at the root before it starts to cool. */
    private static final float HOT_FOR = 0.35F;

    private final float tintRed;
    private final float tintGreen;
    private final float tintBlue;

    private FlameParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd,
            TintedParticleOption options, SpriteSet sprites) {
        super(level, x, y, z, options);
        this.tintRed = options.red();
        this.tintGreen = options.green();
        this.tintBlue = options.blue();
        this.lifetime = LIFE + this.random.nextInt(LIFE_JITTER);
        this.quadSize = options.scale() * (0.7F + this.random.nextFloat() * 0.6F);
        this.friction = 0.91F;
        // Negative, so it climbs. Fire is hot air and goes where hot air goes.
        this.gravity = -0.055F;
        // Flame is not made of anything, and one that bounced off a wing would sit on it.
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
        // White at the root and the fire's own colour a moment above it, then down through orange
        // into red as it climbs: a flame gives up its blue first and its red last, which is what
        // stops the top of the column reading as a second, smaller fire.
        float hot = 1.0F - Mth.clamp(lived / HOT_FOR, 0.0F, 1.0F);
        float cool = 1.0F - lived * 0.55F;

        this.rCol = Mth.lerp(hot, this.tintRed, 1.0F) * cool;
        this.gCol = Mth.lerp(hot, this.tintGreen, 1.0F) * cool * (1.0F - lived * 0.45F);
        this.bCol = Mth.lerp(hot, this.tintBlue, 1.0F) * cool * (1.0F - lived * 0.85F);
        this.alpha = 1.0F - lived * lived * lived;
    }

    @Override
    public float getQuadSize(float partialTick) {
        return this.quadSize * (1.0F - (1.0F - TAPERS_TO) * this.lived(partialTick));
    }

    @Override
    protected int getLightColor(float partialTick) {
        return LightTexture.FULL_BRIGHT;
    }

    /** How far through its life it is, from zero to one. */
    private float lived(float partialTick) {
        return Mth.clamp(((float) this.age + partialTick) / (float) this.lifetime, 0.0F, 1.0F);
    }

    public static ParticleProvider<TintedParticleOption> provider(SpriteSet sprites) {
        return (options, level, x, y, z, xd, yd, zd) ->
                new FlameParticle(level, x, y, z, xd, yd, zd, options, sprites);
    }
}
