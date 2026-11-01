package com.ashvehicles.client.particle;

import com.ashvehicles.particle.TintedParticleOption;
import com.ashvehicles.registry.ModParticles;
import com.ashvehicles.weapon.WeaponDefinition;

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

    /**
     * 核の環（{@link #nuclear}）で、クアッドを到達距離の何倍に開くか。
     *
     * <p>テクスチャの明るい帯は四角の中心から縁までの 0.81 の所にある。核の環は打撃の波の前面そのもの
     * （{@code weapon/NuclearBlast}）なので、土埃より先を走らせる {@link #RING_LEADS} ではなく、帯がちょうど前面に乗る倍率にする。
     */
    private static final float STEADY_RING = 1.0F / 0.81F;
    /** 核の環が到達距離に着いてから消えるまでの tick 数。 */
    private static final int STEADY_FADES = 30;
    /** 核の環の、走っている間の不透明度。 */
    private static final float STEADY_OPACITY = 0.45F;
    /**
     * 核の環が引きずる土煙の壁。1tickあたりの粒数、総数、粒の大きさ（到達距離に対する割合）。
     *
     * <p>普通の環の土埃は {@link #MOST_PUFFS} と {@link #DUST_BUDGET} で抑えてあり、核の環の周長（4km 近く）に
     * 撒くと点線にしかならない。核の映像の衝撃波は、地平線へ向かって走る<b>土煙の壁</b>で、建物も林もそこに
     * 呑まれる。色はサーバーが渡す環の色のまま（白に近い）——壁の頭は巻き上がった塵と凝結で白っぽく見える。粒を大きく長生きする雲（{@code ModParticles.CLOUD}）にして、数も上げる。
     */
    private static final int STEADY_PUFFS = 40;
    private static final int STEADY_BUDGET = 1400;
    private static final float STEADY_DUST = 0.018F;
    /** その壁が立ち上がる速さ。普通の環より高く、土煙が「壁」として見える高さまで。 */
    private static final double STEADY_LIFT = 0.22;

    private final int dust;
    private final float radius;
    private final int raisingDust;
    /** 音速で一定に走る核の環か。{@link #nuclear} 参照。 */
    private final boolean steady;
    /** 核の環が到達距離に着くまでの tick 数。普通の環では0。 */
    private final int travels;

    private ShockwaveParticle(ClientLevel level, double x, double y, double z,
            TintedParticleOption options, SpriteSet sprites, boolean steady) {
        super(level, x, y, z, options);
        this.steady = steady;
        this.dust = options.colour();
        this.rCol = Mth.lerp(PALE, this.rCol, 1.0F);
        this.gCol = Mth.lerp(PALE, this.gCol, 1.0F);
        this.bCol = Mth.lerp(PALE, this.bCol, 1.0F);
        this.radius = Math.max(options.scale(), 1.0F);
        this.travels = steady ? Math.max(1, Mth.ceil(this.radius / WeaponDefinition.Nuclear.WAVE_SPEED)) : 0;
        this.lifetime = steady ? this.travels + STEADY_FADES : LIFE + (int) (this.radius * LIFE_PER_BLOCK);
        this.raisingDust = steady ? this.travels : (int) (this.lifetime * RAISES_DUST);
        this.quadSize = this.radius * (steady ? STEADY_RING : RING_LEADS);
        // 移動も落下もしない。前面は「どこかへ行く」のではなく「成長する」ことで描かれる。
        this.hasPhysics = false;
        this.setSprite(sprites.get(this.random));
    }

    @Override
    public void tick() {
        super.tick();

        float lived = this.lived(0.0F);

        if (this.steady) {
            // 走っている間は濃いまま、着いてから消える。打撃の前面なので、走っている間に薄れて見失われては困る。
            float fading = Mth.clamp((float) (this.age - this.travels) / STEADY_FADES, 0.0F, 1.0F);

            this.alpha = STEADY_OPACITY * (1.0F - fading);
        } else {
            this.alpha = OPACITY * (1.0F - lived) * (1.0F - lived);
        }

        if (this.age <= this.raisingDust) {
            this.raiseDust(lived);
        }
    }

    /** 地面の材質でできた不揃いな壁。前面のすぐ内側に敷く。 */
    private void raiseDust(float lived) {
        double reach = this.radius * this.reached(0.0F);
        double speed = (this.steady ? WeaponDefinition.Nuclear.WAVE_SPEED : this.frontSpeed(lived)) * DUST_DRAG;
        // 総量に収まる範囲で、環の長さに見合う数。長生きする環ほど1tickあたりを薄くして、撒き切らずに
        // 最後まで壁を引きずれるようにする。
        int spread = (int) Math.min(this.radius * DUST_PER_BLOCK,
                (double) (this.steady ? STEADY_BUDGET : DUST_BUDGET) / this.raisingDust);
        int puffs = Mth.clamp(spread, FEWEST_PUFFS, this.steady ? STEADY_PUFFS : MOST_PUFFS);
        TintedParticleOption puff = this.steady
                ? ModParticles.CLOUD.get().of(this.dust, this.radius * STEADY_DUST)
                : ModParticles.BLAST_SMOKE.get().of(this.dust, DUST_SIZE);
        double lift = this.steady ? STEADY_LIFT : DUST_LIFT;

        for (int i = 0; i < puffs; i++) {
            double angle = this.random.nextDouble() * Mth.TWO_PI;
            double out = reach * (0.82 + this.random.nextDouble() * 0.18);
            double along = Math.cos(angle);
            double across = Math.sin(angle);

            this.level.addParticle(puff,
                    this.x + along * out, this.y + this.random.nextDouble() * 0.5, this.z + across * out,
                    along * speed, lift, across * speed);
        }
    }

    @Override
    public float getQuadSize(float partialTick) {
        return this.quadSize * this.reached(partialTick);
    }

    @Override
    public SingleQuadParticle.FacingCameraMode getFacingCameraMode() {
        return FLAT;
    }

    /** 前面が到達距離のどこまで出たか（0〜1）。核の環は音速で一定に、それ以外は {@link #expansion} の曲線で。 */
    private float reached(float partialTick) {
        if (this.steady) {
            return Mth.clamp(((float) this.age + partialTick) / this.travels, 0.0F, 1.0F);
        }

        return expansion(this.lived(partialTick));
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
                new ShockwaveParticle(level, x, y, z, options, sprites, false);
    }

    /**
     * 核の環。音速で一定に走り、打撃の波の前面に重なる（{@code weapon/NuclearBlast}）。{@code scale} は到達距離（ブロック）。
     *
     * <p>普通の環は最初の数 tick で開いて減速する——爆風の見た目としては正しいが、600ブロック先まで打撃を運ぶ前面と並べると
     * 環が先に止まり、止まった環の外で機体が砕ける。
     */
    public static ParticleProvider<TintedParticleOption> nuclear(SpriteSet sprites) {
        return (options, level, x, y, z, xd, yd, zd) ->
                new ShockwaveParticle(level, x, y, z, options, sprites, true);
    }
}
