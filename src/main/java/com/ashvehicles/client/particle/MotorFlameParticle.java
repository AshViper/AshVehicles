package com.ashvehicles.client.particle;

import com.ashvehicles.particle.TintedParticleOption;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;

/**
 * 燃えているモーターの火。ノズルのすぐ後ろにある、煙になる前の物。
 *
 * <p>この MOD の他の火と、どちらとも別物だ。{@link BlastParticle} は開いて消える一度きりの火球で、
 * {@link FlameParticle} は燃えている残骸から立ち昇る舌。こちらはどちらでもない——<b>押し出され続けている</b>
 * 火であり、形を作っているのは浮力ではなくノズルだ。だから昇らないし、広がらないし、待たない。
 *
 * <p>寿命が極端に短いのはそのためである。実物のノズルの炎が数ブロックで終わって見えるのは、そこで火が
 * 消えるからではなく、そこまで来たガスが暗くなるからだ。1秒近く生きる粒でそれを描こうとすると、火はミサイル
 * の後ろに何十ブロックも取り残された明るい柱になる。3〜5tickで落とせば、ミサイルがどれだけ速く飛んでも
 * 炎はノズルに付いて回る。
 *
 * <p>そして常に自分で光っている。火であること自体がその理由の全部だが、ここではもう一つある——この粒が
 * 撒かれるのはたいてい高空か、ロード範囲の外だ。どちらも世界に明るさを訊いても答えが返ってこない場所である。
 *
 * <p><b>色は白だ。</b> 固体モーターの炎は白熱していて、兵装ファイルの {@code flame.colour} が言っている
 * のは炎の色ではなく、その白熱が消える途中で通る色である。だから粒は寿命の大半を白で過ごし、最後の数分の
 * 1でその色へ落ちる。ここを逆にすると、ノズルから出た瞬間にオレンジのガスが出てくることになり、燃えて
 * いる物ではなく燻っている物に見える。
 */
public class MotorFlameParticle extends WeaponParticle {
    /** 生きている tick 数。ここを伸ばすと炎はミサイルではなく航跡に付く。 */
    private static final int LIFE = 3;
    private static final int LIFE_JITTER = 3;
    /**
     * 白熱している、寿命に対する割合。
     *
     * <p>大半だ。固体モーターの炎は<b>白い</b>——兵装ファイルの色は炎の色ではなく、炎が消える途中で通る色
     * である。ここを短くすると、ノズルから出たガスが即座にオレンジになり、燃えている物ではなく燻っている物
     * に見える。
     */
    private static final float WHITE_FOR = 0.72F;
    /** 消える時点で残る大きさの割合。炎は縮みながら消える。煙と逆だ。 */
    private static final float SHRINKS_TO = 0.45F;
    /** 出た瞬間の大きさの割合。1tickで開ききる。 */
    private static final float OPENS_AT = 0.7F;
    /** 毎tick残す速度の割合。ガスはすぐ空気に負ける。 */
    private static final float FRICTION = 0.75F;
    /**
     * 粒1つの素の大きさ（ブロック）。兵装ファイルの倍率はここに掛かる。
     *
     * <p>{@link SmokeParticle} の各 {@code Shape} が持っているのと同じ役の値だ。あちらと揃えてあるので、
     * 同じ倍率で撒いた炎と噴煙は同じ太さの柱になる——炎は煙の内側にあるべきで、煙より太い炎はノズルではなく
     * 爆発に見える。
     */
    private static final float BASE = 0.28F;

    private final float tintRed;
    private final float tintGreen;
    private final float tintBlue;

    private MotorFlameParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd,
            TintedParticleOption options, SpriteSet sprites) {
        super(level, x, y, z, options);
        this.tintRed = options.red();
        this.tintGreen = options.green();
        this.tintBlue = options.blue();
        this.lifetime = LIFE + this.random.nextInt(LIFE_JITTER);
        this.quadSize = BASE * options.scale() * (0.8F + this.random.nextFloat() * 0.4F);
        this.friction = FRICTION;
        // 昇らない。押し出されている物なので、行き先を決めるのは熱気ではなくノズルの向きだ。
        this.gravity = 0.0F;
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
        // ほとんどの間は白熱していて、終わりの数分の1で兵装ファイルの色へ落ちる。青を最初に手放すので、
        // 末端は赤へ向かう。炎が「消えた」ではなく「冷めた」に見えるのはその順序による。
        float white = 1.0F - Mth.clamp(lived / WHITE_FOR, 0.0F, 1.0F);
        float cool = 1.0F - lived * 0.35F;

        this.rCol = Mth.lerp(white, this.tintRed, 1.0F) * cool;
        this.gCol = Mth.lerp(white, this.tintGreen, 1.0F) * cool * (1.0F - lived * 0.30F);
        this.bCol = Mth.lerp(white, this.tintBlue, 1.0F) * cool * (1.0F - lived * 0.65F);
        this.alpha = 1.0F - lived * lived;
    }

    @Override
    public float getQuadSize(float partialTick) {
        float lived = this.lived(partialTick);

        // 1tickで開ききってから縮む。開く途中を長く見せる必要は無い——ノズルから出てくるガスは既に開いて
        // いる。
        if (lived < 0.25F) {
            return this.quadSize * Mth.lerp(lived / 0.25F, OPENS_AT, 1.0F);
        }

        return this.quadSize * (1.0F - (1.0F - SHRINKS_TO) * (lived - 0.25F) / 0.75F);
    }

    /** 火は世界に照らされる物ではない。 */
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
                new MotorFlameParticle(level, x, y, z, xd, yd, zd, options, sprites);
    }
}
