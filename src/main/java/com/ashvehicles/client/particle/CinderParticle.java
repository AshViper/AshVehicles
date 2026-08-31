package com.ashvehicles.client.particle;

import com.ashvehicles.particle.Effects;
import com.ashvehicles.particle.TintedParticleOption;
import com.ashvehicles.registry.ModParticles;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;

/**
 * 爆発から放り出され、尾を引きながら弧を描いて落ちる塊。燃えている物と、ただの地面だった物の2種類。
 *
 * <p>{@link SparkParticle} とは仕事が違う。火花は爆心の周りに散る「点の群れ」で、伝えるのは<em>そこで何かが
 * 当たった</em>ことだけだ。近くで見れば効くが、爆発が本当に面白い距離——数百m先、既に離脱しながら振り返る高度
 * ——では、点の群れは1フレームで消える細かい塵にしかならない。そして寿命が短く、上がりきる前に消える。
 *
 * <p>こちらが伝えるのは<em>線</em>だ。放り出された塊が弧を描き、その軌跡を煙が残す。爆心から外へ伸びる十数本の
 * 曲線は、どんな距離でも読めるし、火球が消えた後も1秒以上残る。落ちきるまで生きるので、見ている者は上がる物と
 * 降ってくる物の両方を見る——そこが「爆発が終わった」ではなく「爆発の後」に見える理由になる。
 *
 * <p>尾はサーバーから送るのではなく自分で置く。1個につき数個の煙で、それを十数個分——パケットで賄える量ではない
 * し、賄う必要も無い。位置を知っているのは飛んでいる本人だけだ。{@link ShockwaveParticle} の土埃と同じ考え方。
 */
public class CinderParticle extends WeaponParticle {
    /** 置いた尾がその場で漂う速さ。 */
    private static final double TRAIL_DRIFT = 0.012;
    /** 消える時点で残る大きさの割合。フェードではなく縮小なのは、これが塵ではなく物だから。 */
    private static final float SHRINKS_TO = 0.3F;

    /**
     * 燃えかす。炸薬そのものが吹き飛ばした熱い破片で、冷えながら赤へ落ち、黒い煙を引く。
     *
     * <p>軽いので遠くまで飛び、高く上がる。
     */
    public static final Kind EMBER =
            new Kind(20, 18, 0.16F, 0.55F, 0.97F, 2, 0.22F, 0.70F, Effects.SOOT, true);
    /**
     * 吹き飛んだ地面。燃えていないので光らず、重いので早く落ちて地面で跳ねる。
     *
     * <p>寿命が長いのは意図だ。上がって落ちるまでを1つの弧として見せるためのもので、頂点で消える破片は
     * 「吹き飛んだ」を伝えても「降ってきた」を伝えない。
     */
    public static final Kind RUBBLE =
            new Kind(26, 20, 0.20F, 0.90F, 0.96F, 3, 0.30F, 0.85F, Effects.DUST, false);

    private final Kind kind;

    private CinderParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd,
            TintedParticleOption options, SpriteSet sprites, Kind kind) {
        super(level, x, y, z, options);
        this.kind = kind;
        this.lifetime = kind.life() + this.random.nextInt(Math.max(kind.lifeJitter(), 1));
        this.quadSize = kind.size() * options.scale() * (0.7F + this.random.nextFloat() * 0.6F);
        this.friction = kind.drag();
        this.gravity = kind.gravity();
        // 地面をすり抜けない。跳ねて転がって止まる物であり、そこが煙や炎と違う点だ。
        this.hasPhysics = true;
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

        float lived = this.lived();

        // 燃えている物は冷えながら赤へ落ちる。青を先に、緑を次に手放す。最後まで残るのが赤なのは、実際に
        // 冷えていく物がそう見えるからだ。土くれは冷える物を持たないので、色は最後まで土の色。
        if (this.kind.burning()) {
            this.gCol = Math.min(this.gCol, 0.30F + (1.0F - lived) * 0.70F);
            this.bCol = Math.min(this.bCol, (1.0F - lived) * (1.0F - lived) * 0.8F);
        }

        if (lived < this.kind.trailsFor() && this.age % this.kind.trailEvery() == 0) {
            this.trail();
        }
    }

    /**
     * 今いる場所に、細い尾をひとつ置いていく。
     *
     * <p>尾の色は塊自身の色ではない。土くれは地面の色をしているが、そこから立つ土煙は地面の色をしていない
     * ——正確には、していてはいけない。{@link Effects#DUST} が述べている通りで、地面の色を継ぐと黒い地面の上
     * では煙が煙として読めなくなる（黒曜石の上に落ちた爆弾が煙を出さなくなる）。土煙の壁は土煙の壁として
     * 読めれば十分で、材質はそこまでの価値が無い。
     */
    private void trail() {
        // 塊の速度は継がない。煙は運ばれる物ではなく、置き去りにされる物だからだ。
        this.level.addParticle(
                ModParticles.BLAST_SMOKE.get().of(this.kind.trailColour(), this.kind.trailSize()),
                this.x, this.y, this.z,
                this.random.nextGaussian() * TRAIL_DRIFT, TRAIL_DRIFT,
                this.random.nextGaussian() * TRAIL_DRIFT);
    }

    @Override
    public float getQuadSize(float partialTick) {
        return this.quadSize * (1.0F - this.lived() * (1.0F - SHRINKS_TO));
    }

    /** 燃えている物はどこにいても自前の光源。土くれは周囲の光で照らされる。 */
    @Override
    protected int getLightColor(float partialTick) {
        return this.kind.burning() ? LightTexture.FULL_BRIGHT : super.getLightColor(partialTick);
    }

    /** 寿命のどこまで来たか。0〜1。 */
    private float lived() {
        return Mth.clamp((float) this.age / (float) this.lifetime, 0.0F, 1.0F);
    }

    /**
     * @param life 最低限もつtick数。落ちきるまで生きる長さであること
     * @param lifeJitter さらに何tickもちうるか。全部が同時に消えないための値
     * @param size 塊の大きさ（ブロック）
     * @param gravity 落ちる速さ。重い物ほど大きく、弧が低く短くなる
     * @param drag 毎tick残る速度の割合
     * @param trailEvery 尾を1つ置く間隔（tick）。詰めると線、空けると点線
     * @param trailSize 尾ひとつの大きさ
     * @param trailsFor 尾が出続ける、寿命に対する割合。冷えた塊はもう何も出さない
     * @param trailColour 尾の色。塊自身の色ではないことに注意。{@link #trail} 参照
     * @param burning 燃えているか。光るかどうかと、冷えるかどうかを決める
     */
    public record Kind(int life, int lifeJitter, float size, float gravity, float drag,
            int trailEvery, float trailSize, float trailsFor, int trailColour, boolean burning) {
    }

    public static ParticleProvider<TintedParticleOption> provider(SpriteSet sprites, Kind kind) {
        return (options, level, x, y, z, xd, yd, zd) ->
                new CinderParticle(level, x, y, z, xd, yd, zd, options, sprites, kind);
    }
}
