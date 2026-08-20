package com.ashvehicles.client.particle;

import com.ashvehicles.particle.TintedParticleOption;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.util.Mth;

/**
 * A puff of smoke: the exhaust at a motor's nozzle, the trail it leaves behind, or the cloud a blast
 * rolls up into. All three are the same thing at different speeds, so they are the same class told
 * different numbers.
 *
 * <p>Smoke that only faded would look like a photograph being turned down. Real smoke expands as it
 * cools and thins as it expands, so each puff grows through its life and gives up its opacity as it
 * does, turning slowly the while; and the four frames of its texture take it from a solid puff to a
 * wisp, so a trail is denser at the missile than it is behind it without anything having to say so.
 */
public class SmokeParticle extends WeaponParticle {
    /** The exhaust itself: dense, hot, and gone almost as soon as it is made. */
    public static final Shape MOTOR = new Shape(10, 8, 0.30F, 1.9F, 0.80F, -0.10F, 1.0F);
    /** What hangs in the air behind, which is the trail anyone actually sees. */
    public static final Shape CONTRAIL = new Shape(40, 30, 0.34F, 2.8F, 0.93F, -0.03F, 0.62F);
    /** The cloud a detonation rolls up into: big, slow, and there long enough to be looked at. */
    public static final Shape BLAST = new Shape(36, 24, 0.55F, 3.0F, 0.86F, -0.14F, 0.95F);
    /**
     * Condensation off a wing: thin, torn apart almost at once, and gone. It is water in the air
     * rather than anything burning, so it neither rises nor lingers.
     */
    public static final Shape VAPOUR = new Shape(14, 10, 0.30F, 2.2F, 0.90F, 0.0F, 0.45F);

    private final SpriteSet sprites;
    private final Shape shape;
    private final float spin;

    private SmokeParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd,
            TintedParticleOption options, SpriteSet sprites, Shape shape) {
        super(level, x, y, z, options);
        this.sprites = sprites;
        this.shape = shape;
        this.lifetime = shape.life() + this.random.nextInt(Math.max(shape.lifeJitter(), 1));
        this.quadSize = shape.size() * options.scale() * (0.8F + this.random.nextFloat() * 0.4F);
        this.friction = shape.friction();
        this.gravity = shape.gravity();
        // Smoke has no business bouncing off the terrain: it is not made of anything.
        this.hasPhysics = false;
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        this.spin = (this.random.nextFloat() - 0.5F) * 0.08F;
        this.roll = this.random.nextFloat() * Mth.TWO_PI;
        this.oRoll = this.roll;
        this.setSpriteFromAge(sprites);
    }

    @Override
    public void tick() {
        super.tick();
        this.setSpriteFromAge(this.sprites);
        this.oRoll = this.roll;
        this.roll += this.spin;

        // Holds its opacity while it is still a puff and lets go of it as it becomes a wisp, rather
        // than fading evenly, which reads as the whole trail dimming at once.
        float lived = this.lived(0.0F);
        this.alpha = this.shape.opacity() * (1.0F - lived * lived * lived);
    }

    @Override
    public float getQuadSize(float partialTick) {
        return this.quadSize * (1.0F + (this.shape.growth() - 1.0F) * this.lived(partialTick));
    }

    /** How far through its life it is, from zero to one. */
    private float lived(float partialTick) {
        return Mth.clamp(((float) this.age + partialTick) / (float) this.lifetime, 0.0F, 1.0F);
    }

    /**
     * @param life ticks it lasts at least
     * @param lifeJitter how many more it may last, so a cloud does not vanish all at once
     * @param size how big a puff starts, in blocks
     * @param growth how much bigger it is by the end than it was at the start
     * @param friction what is left of its speed each tick
     * @param gravity how fast it falls. Negative, for smoke, which rises
     * @param opacity how solid it is at its densest
     */
    public record Shape(int life, int lifeJitter, float size, float growth,
            float friction, float gravity, float opacity) {
    }

    public static ParticleProvider<TintedParticleOption> provider(SpriteSet sprites, Shape shape) {
        return (options, level, x, y, z, xd, yd, zd) ->
                new SmokeParticle(level, x, y, z, xd, yd, zd, options, sprites, shape);
    }
}
