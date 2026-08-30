package com.ashvehicles.client.particle;

import com.ashvehicles.particle.TintedParticleOption;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;

/**
 * しばらく燃え続ける物から立つ炎の舌。
 *
 * <p>{@link BlastParticle} とは意図的に別物だ。あちらは同じテクスチャで正反対の仕事をする。起爆はほぼ最大サイズ
 * で白熱して開き1秒以内に消えるので、それを並べても火事ではなく小爆発の連なりになる。炎は逆だ。出た場所から
 * 始まり、昇り、昇りながら細くなり、赤くなって消える——燃える残骸の見た目は、そこから次々に立つこれらの柱であり、
 * 各々が上の煙になる途中の小さな舌だ。
 *
 * <p>炎はどこにいても自前の光源。ここではそれが二重に効く。残骸は地上に横たわるので、さもないと夜が炎ごと闇に
 * 沈める。そしてロード範囲外に落ちた残骸には、照らすチャンクがそもそも無い。
 */
public class FlameParticle extends WeaponParticle {
    private static final int LIFE = 14;
    private static final int LIFE_JITTER = 12;
    /** 消える時点で残る幅の割合。炎は昇りながら細くなる。 */
    private static final float TAPERS_TO = 0.2F;
    /** 冷え始める前、根元が黄白色でいる時間の割合。 */
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
        // 負値なので昇る。炎は熱気であり、熱気の行く所へ行く。
        this.gravity = -0.055F;
        // 炎は物質ではない。主翼で跳ね返る炎は主翼の上に乗ってしまう。
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
        // 根元は白、その少し上は炎固有の色、昇るにつれてオレンジから赤へ落ちる。炎は青を最初に、赤を最後に
        // 手放す。それが柱の頂点を「2つ目の小さな火」に見せないための要素だ。
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

    /** 寿命のどこまで来たか。0〜1。 */
    private float lived(float partialTick) {
        return Mth.clamp(((float) this.age + partialTick) / (float) this.lifetime, 0.0F, 1.0F);
    }

    public static ParticleProvider<TintedParticleOption> provider(SpriteSet sprites) {
        return (options, level, x, y, z, xd, yd, zd) ->
                new FlameParticle(level, x, y, z, xd, yd, zd, options, sprites);
    }
}
