package com.ashvehicles.client.particle;

import com.ashvehicles.particle.TintedParticleOption;
import com.ashvehicles.registry.ModParticles;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.util.Mth;

/**
 * 大きな物が起爆したときの爆風。地面を外向きに走る環と、その後ろへ引きずられる土埃の壁。
 *
 * <p>塊ではなく波に見せているのは2つの判断だ。<b>水平に寝かせる</b>——カメラを向けず地面へ倒す。常にこちらを向く環
 * は煙の輪だが、地面に寝た環は衝撃波だからだ。上空から見た爆弾の爆風の姿は、風景を広がっていく円がその全てである。
 * そして<b>減速する</b>——成長の大半は最初の2〜3tickにあり、終盤ではほとんど動かない。過圧の前面が自らを使い果たす
 * ときに実際にやることだ。
 *
 * <p>土埃はサーバーからの送信ではなくここから撒くので、パケット1つで波全体を賄える。任意の瞬間に自分の縁がどこかを
 * 知っているのは環だけであり、他は全てそこから導かれる。
 */
public class ShockwaveParticle extends WeaponParticle {
    /**
     * 地面に水平に。クアッドは XY 平面で組まれるので、X 軸周りに90度回すと XZ 平面へ寝る。円なので、どちら向きに
     * 寝ても違いは無い。
     */
    private static final SingleQuadParticle.FacingCameraMode FLAT =
            (quaternion, camera, partialTick) -> quaternion.rotationX(-Mth.HALF_PI);

    /** 使い果たすまでのtick数と、覆うべき1ブロックあたりの追加tick数。 */
    private static final int LIFE = 8;
    private static final float LIFE_PER_BLOCK = 0.5F;
    /** 巻き上げる土埃に対して環がどれだけ白いか。これは圧縮された空気であって地面ではない。 */
    private static final float PALE = 0.75F;
    private static final float OPACITY = 0.55F;

    /** 前面が地面から土埃を剥がし続ける、寿命に対する割合。 */
    private static final float RAISES_DUST = 0.6F;
    /** 1tickあたりの粒数。環が回るべき距離が長いほど増える。 */
    private static final float DUST_PER_BLOCK = 0.42F;
    private static final int FEWEST_PUFFS = 3;
    private static final int MOST_PUFFS = 10;
    /** 後ろの土埃が前面の速度をどれだけ受け継ぐか。 */
    private static final double DUST_DRAG = 0.35;
    /** そしてどれだけ強く上へ巻き上がるか。染みではなく壁になるのはこれのおかげだ。 */
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
        // 移動も落下もしない。前面は「どこかへ行く」のではなく「成長する」ことで描かれる。
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

    /** 地面の材質でできた不揃いな壁。前面のすぐ内側に敷く。 */
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

    /** 前面がどこまで出たか。0から全到達距離まで。最初が速く、常に減速する。 */
    private static float expansion(float lived) {
        float left = 1.0F - lived;

        return 1.0F - left * left;
    }

    /** 進行速度（ブロック/tick）。同じ曲線を微分した物。 */
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
