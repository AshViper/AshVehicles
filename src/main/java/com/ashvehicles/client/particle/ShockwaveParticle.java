package com.ashvehicles.client.particle;

import com.ashvehicles.particle.TintedParticleOption;
import com.ashvehicles.registry.ModParticles;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.util.Mth;

/**
 * The blast wave of something big going off: a ring racing outwards across the ground with a wall of
 * dust dragged along behind it.
 *
 * <p>Two decisions make it read as a wave rather than as a puff. It lies <b>flat</b>, turned into
 * the ground rather than turned to face the camera, because a ring that always faces you is a
 * smoke ring and a ring lying on the ground is a shockwave — and the whole of what a bomb's blast
 * looks like from the air is a circle spreading across the landscape. And it <b>slows down</b>: most
 * of the growth is in the first two or three ticks and it is barely moving by the end, which is what
 * an overpressure front actually does as it spends itself.
 *
 * <p>The dust is thrown from here rather than sent from the server, so one packet buys the whole
 * wave: the ring is the only thing that knows where its own edge is at any moment, and everything
 * else follows from that.
 */
public class ShockwaveParticle extends WeaponParticle {
    /**
     * Flat on the ground: the quad is built in the XY plane, so a quarter turn about X lays it into
     * XZ. It is a circle, so which way round it lies makes no difference.
     */
    private static final SingleQuadParticle.FacingCameraMode FLAT =
            (quaternion, camera, partialTick) -> quaternion.rotationX(-Mth.HALF_PI);

    /** Ticks it takes to spend itself, and how many more for every block it has to cover. */
    private static final int LIFE = 8;
    private static final float LIFE_PER_BLOCK = 0.5F;
    /** How white the ring is next to the dust it is raising: this is squeezed air, not the ground. */
    private static final float PALE = 0.75F;
    private static final float OPACITY = 0.55F;

    /** How much of its life the front is still tearing dust off the ground for. */
    private static final float RAISES_DUST = 0.6F;
    /** Puffs a tick, more of them the further round the ring has to go. */
    private static final float DUST_PER_BLOCK = 0.42F;
    private static final int FEWEST_PUFFS = 3;
    private static final int MOST_PUFFS = 10;
    /** How much of the front's own speed the dust behind it keeps. */
    private static final double DUST_DRAG = 0.35;
    /** And how hard it rolls upwards, which is what makes a wall of it rather than a smear. */
    private static final double DUST_LIFT = 0.06;
    private static final float DUST_SIZE = 1.4F;

    private final int dust;
    private final float radius;
    private final int raisingDust;

    private ShockwaveParticle(ClientLevel level, double x, double y, double z,
            TintedParticleOption options, SpriteSet sprites) {
        super(level, x, y, z, options);
        this.dust = options.colour();
        this.rCol = Mth.lerp(PALE, this.rCol, 1.0F);
        this.gCol = Mth.lerp(PALE, this.gCol, 1.0F);
        this.bCol = Mth.lerp(PALE, this.bCol, 1.0F);
        this.radius = Math.max(options.scale(), 1.0F);
        this.lifetime = LIFE + (int) (this.radius * LIFE_PER_BLOCK);
        this.raisingDust = (int) (this.lifetime * RAISES_DUST);
        this.quadSize = this.radius;
        // It neither moves nor falls: the front is drawn by growing, not by going anywhere.
        this.hasPhysics = false;
        this.setSprite(sprites.get(this.random));
    }

    @Override
    public void tick() {
        super.tick();

        float lived = this.lived(0.0F);
        this.alpha = OPACITY * (1.0F - lived) * (1.0F - lived);

        if (this.age <= this.raisingDust) {
            this.raiseDust(lived);
        }
    }

    /** A ragged wall of whatever the ground is made of, laid down just inside the front. */
    private void raiseDust(float lived) {
        double reach = this.radius * expansion(lived);
        double speed = this.frontSpeed(lived) * DUST_DRAG;
        int puffs = Mth.clamp((int) (this.radius * DUST_PER_BLOCK), FEWEST_PUFFS, MOST_PUFFS);
        TintedParticleOption puff = ModParticles.BLAST_SMOKE.get().of(this.dust, DUST_SIZE);

        for (int i = 0; i < puffs; i++) {
            double angle = this.random.nextDouble() * Mth.TWO_PI;
            double out = reach * (0.82 + this.random.nextDouble() * 0.18);
            double along = Math.cos(angle);
            double across = Math.sin(angle);

            this.level.addParticle(puff,
                    this.x + along * out, this.y + this.random.nextDouble() * 0.5, this.z + across * out,
                    along * speed, DUST_LIFT, across * speed);
        }
    }

    @Override
    public float getQuadSize(float partialTick) {
        return this.quadSize * expansion(this.lived(partialTick));
    }

    @Override
    public SingleQuadParticle.FacingCameraMode getFacingCameraMode() {
        return FLAT;
    }

    /** How far out the front is, from nothing to its whole reach. Fast at first and always slowing. */
    private static float expansion(float lived) {
        float left = 1.0F - lived;

        return 1.0F - left * left;
    }

    /** How fast it is travelling, in blocks a tick: the same curve, differentiated. */
    private double frontSpeed(float lived) {
        return 2.0 * this.radius * (1.0F - lived) / this.lifetime;
    }

    private float lived(float partialTick) {
        return Mth.clamp(((float) this.age + partialTick) / (float) this.lifetime, 0.0F, 1.0F);
    }

    public static ParticleProvider<TintedParticleOption> provider(SpriteSet sprites) {
        return (options, level, x, y, z, xd, yd, zd) ->
                new ShockwaveParticle(level, x, y, z, options, sprites);
    }
}
