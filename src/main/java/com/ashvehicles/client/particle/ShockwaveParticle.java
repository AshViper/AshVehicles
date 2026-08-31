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

    /**
     * 描かれる環が、土埃の届く距離の何倍まで広がるか。
     *
     * <p>環と土埃を同じ大きさにする理由は無い。過圧の前面は、それが剥がした土埃より<em>先</em>を走る——地面から
     * 埃が持ち上がるには一瞬かかり、持ち上がった頃には前面はもう向こうにいる。だから見える環は常に土煙の壁の
     * 外側にあり、そこが「壁が広がっている」ではなく「何かが壁を押し広げながら走っている」に見える理由になる。
     *
     * <p>見た目の大きさを変えたいならここを触る。土埃の量も環の寿命も
     * {@link com.ashvehicles.client.particle.BlastStageParticle} の {@code WAVE_REACH} が決めているので、
     * こちらは1枚のクアッドがどこまで開くかだけを動かす。
     */
    private static final float RING_LEADS = 1.7F;

    /** 前面が地面から土埃を剥がし続ける、寿命に対する割合。 */
    private static final float RAISES_DUST = 0.6F;
    /** 1tickあたりの粒数。環が回るべき距離が長いほど増える。 */
    private static final float DUST_PER_BLOCK = 0.42F;
    private static final int FEWEST_PUFFS = 3;
    private static final int MOST_PUFFS = 10;
    /**
     * 環1つが撒いてよい土埃の総数。
     *
     * <p>寿命は半径に比例して伸びるので、上の1tickあたりの数だけで抑えると、巨大な環は「上限の数 × 長い寿命」
     * で数百個を撒くことになる。爆発規模を255まで開けた以上、そこは天井が要る。通常規模の弾頭はここに届かない
     * ——最大級の弾頭でも300個ほどなので、効くのは試験棒を振り切った時だけだ。
     */
    private static final int DUST_BUDGET = 360;
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
        this.quadSize = this.radius * RING_LEADS;
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
        // 総量に収まる範囲で、環の長さに見合う数。長生きする環ほど1tickあたりを薄くして、撒き切らずに
        // 最後まで壁を引きずれるようにする。
        int spread = (int) Math.min(this.radius * DUST_PER_BLOCK, (double) DUST_BUDGET / this.raisingDust);
        int puffs = Mth.clamp(spread, FEWEST_PUFFS, MOST_PUFFS);
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
