package com.ashvehicles.client.particle;

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
    public static final Shape MOTOR = new Shape(10, 8, 0.30F, 1.9F, 0.80F, -0.10F, 1.0F);
    /** 後方の空中に漂う物。人が実際に見る航跡はこちらだ。 */
    public static final Shape CONTRAIL = new Shape(40, 30, 0.34F, 2.8F, 0.93F, -0.03F, 0.62F);
    /** 起爆が巻き上げる雲。大きく、遅く、眺められる程度に長く残る。 */
    public static final Shape BLAST = new Shape(36, 24, 0.55F, 3.0F, 0.86F, -0.14F, 0.95F);
    /**
     * 主翼から出る凝結。薄く、ほぼ即座に引き裂かれ、消える。燃えている物ではなく空気中の水なので、昇りも留まりも
     * しない。
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
        // 煙が地形で跳ね返る筋合いは無い。物質ではないからだ。
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

        // 塊である間は不透明度を保ち、細い筋になるにつれて手放す。均等にフェードさせると、航跡全体が一斉に暗く
        // なるように見えてしまう。
        float lived = this.lived(0.0F);
        this.alpha = this.shape.opacity() * (1.0F - lived * lived * lived);
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
     */
    public record Shape(int life, int lifeJitter, float size, float growth,
            float friction, float gravity, float opacity) {
    }

    public static ParticleProvider<TintedParticleOption> provider(SpriteSet sprites, Shape shape) {
        return (options, level, x, y, z, xd, yd, zd) ->
                new SmokeParticle(level, x, y, z, xd, yd, zd, options, sprites, shape);
    }
}
