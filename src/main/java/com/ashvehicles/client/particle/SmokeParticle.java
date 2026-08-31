package com.ashvehicles.client.particle;

import com.ashvehicles.particle.Effects;
import com.ashvehicles.particle.TintedParticleOption;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.util.Mth;

/**
 * 煙の塊。モーターのノズルから出る排気、その後に残る航跡、爆発が巻き上げる雲。3つとも同じ物を違う速度で見せている
 * だけなので、同じクラスに違う数値を渡す形にしてある。
 *
 * <p>フェードするだけの煙は、写真の明るさを絞っているように見える。本物の煙は冷えながら広がり、広がりながら薄く
 * なる。だから各粒は生涯を通じて成長し、その分だけ不透明度を手放し、その間ゆっくり回る。テクスチャの4フレームが
 * 濃い塊から細い筋へ移すので、航跡は何も指定せずともミサイル寄りが濃く後方が薄くなる。
 */
public class SmokeParticle extends WeaponParticle {

    /** 排気そのもの。濃く、熱く、生まれた直後に消える。 */
    public static final Shape MOTOR = new Shape(10, 8, 0.30F, 1.9F, 0.80F, -0.10F, 1.0F, NEVER_GLOWS, 0.0F);
    /** 後方の空中に漂う物。人が実際に見る航跡はこちらだ。 */
    public static final Shape CONTRAIL = new Shape(40, 30, 0.34F, 2.8F, 0.93F, -0.03F, 0.62F, NEVER_GLOWS, 0.0F);
    /** 起爆が巻き上げる雲。大きく、遅く、眺められる程度に長く残る。 */
    public static final Shape BLAST = new Shape(36, 24, 0.55F, 3.0F, 0.86F, -0.14F, 0.95F, NEVER_GLOWS, 0.0F);
    /**
     * 主翼から出る凝結。薄く、ほぼ即座に引き裂かれ、消える。燃えている物ではなく空気中の水なので、昇りも留まりも
     * しない。
     */
    public static final Shape VAPOUR = new Shape(14, 10, 0.30F, 2.2F, 0.90F, 0.0F, 0.45F, NEVER_GLOWS, 0.0F);
    /**
     * キノコ雲を作る煙。
     *
     * <p>{@link #BLAST} と別なのは寿命だけが理由ではない。爆発の煙は3秒で風景に戻るべき物だが、こちらは柱が
     * 立ち上がり傘が開ききるまでに10秒近くかかる。同じ寿命では、傘が開く頃には柱の根元が消えていて、雲が地面と
     * 繋がらない——キノコ雲がキノコ雲に見えるのは、柱と傘が同時にそこにあるからだ。
     *
     * <p>ほとんど動かず（摩擦が高い）、ほとんど昇らない。柱を押し上げるのは煙自身の浮力ではなく、下から次々に
     * 湧く新しい煙の方だからだ。位置は撒く側が決める。
     */
    public static final Shape CLOUD = new Shape(150, 90, 0.90F, 2.2F, 0.94F, -0.015F, 0.88F, NEVER_GLOWS, 0.0F);
    /**
     * 核のキノコ雲を作る煙。
     *
     * <p>{@link #CLOUD} と違うのは寿命だけで、そしてそれが全てだ。核の演出は柱が立ち上がるだけで10秒近く、
     * 傘が開ききるまで含めると20秒かかる。通常のキノコ雲の煙（最長12秒）では、傘が完成する頃に柱の根元が
     * 消えていて、雲が地面と繋がらない。ここは20〜30秒もつので、最も見応えのある瞬間——柱と傘が同時にそこに
     * ある瞬間——に全部が揃っている。
     */
    public static final Shape NUCLEAR = new Shape(400, 220, 0.90F, 2.4F, 0.95F, -0.010F, 0.90F,
            Effects.FURNACE, 0.45F);

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
        // 煙が地形で跳ね返る筋合いは無い。物質ではないからだ。
        this.hasPhysics = false;
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        this.spin = (this.random.nextFloat() - 0.5F) * 0.08F;
        this.roll = this.random.nextFloat() * Mth.TWO_PI;
        this.oRoll = this.roll;
        this.glowsWhileHot(shape.hot(), shape.cools());
        this.setSpriteFromAge(sprites);
    }

    @Override
    public void tick() {
        super.tick();
        this.setSpriteFromAge(this.sprites);
        this.oRoll = this.roll;
        this.roll += this.spin;

        // 塊である間は不透明度を保ち、細い筋になるにつれて手放す。均等にフェードさせると、航跡全体が一斉に暗く
        // なるように見えてしまう。
        float lived = this.lived(0.0F);
        this.alpha = this.shape.opacity() * (1.0F - lived * lived * lived);

        this.coolTowards(lived);
    }

    @Override
    protected int getLightColor(float partialTick) {
        return this.litWhileHot(super.getLightColor(partialTick), this.lived(partialTick));
    }

    @Override
    public float getQuadSize(float partialTick) {
        return this.quadSize * (1.0F + (this.shape.growth() - 1.0F) * this.lived(partialTick));
    }

    /** 寿命のどこまで来たか。0〜1。 */
    private float lived(float partialTick) {
        return Mth.clamp(((float) this.age + partialTick) / (float) this.lifetime, 0.0F, 1.0F);
    }

    /**
     * @param life 最低限もつtick数
     * @param lifeJitter さらに何tickもちうるか。雲が一斉に消えないようにする値
     * @param size 粒の初期サイズ（ブロック）
     * @param growth 終端で初期の何倍の大きさになるか
     * @param friction 毎tick残る速度の割合
     * @param gravity 落下の速さ。昇る煙では負値
     * @param opacity 最も濃いときの不透明度
     * @param hot 生まれた時点の色。{@link WeaponParticle#NEVER_GLOWS} なら光らず、最初から自分の色をしている
     * @param cools 生まれた時の色から本来の色へ移りきる、寿命に対する割合。0なら移らない
     */
    public record Shape(int life, int lifeJitter, float size, float growth,
            float friction, float gravity, float opacity, int hot, float cools) {
    }

    public static ParticleProvider<TintedParticleOption> provider(SpriteSet sprites, Shape shape) {
        return (options, level, x, y, z, xd, yd, zd) ->
                new SmokeParticle(level, x, y, z, xd, yd, zd, options, sprites, shape);
    }
}
