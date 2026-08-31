package com.ashvehicles.weapon;

import javax.annotation.Nullable;

import com.ashvehicles.particle.Effects;
import com.ashvehicles.registry.ModParticles;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 兵装が着弾地点でどう見え、どう聞こえるか。
 *
 * <p>炎・煙・衝撃波・爆発音はここに無い。それらは出所を問わず同じ物で、燃える残骸もまったく同じ物を作る
 * ので {@link Effects} にある。ここに残るのは本当に兵装固有の部分——弾が当たったブロックに何をするか、
 * 弾が出ていく時に砲身が何をするか。
 */
public final class WeaponEffects {
    /** 発射炎を砲口のどれだけ前に置くか。威力1あたり。砲身と重ならない位置。 */
    private static final double MUZZLE_STANDOFF = 0.22;

    /** 跳弾が装甲板で散らす火花の大きさ。{@link Effects#sparks} の尺度で。 */
    private static final float RICOCHET_SPARKS = 1.6F;
    /** そのうち、散らすのではなく新しい進行方向へ投げる本数。 */
    private static final int RICOCHET_STREAKS = 6;
    /** それを投げる速さ（1tickあたりブロック）。噴霧ではなく筋に見える速さ。 */
    private static final double RICOCHET_THROW = 0.9;
    /** その線からどれだけ広がるか。6本が6本に見えるように。 */
    private static final double RICOCHET_FAN = 0.18;
    /** 装甲板から出る少量の煙。一緒に削れた金属の分。 */
    private static final int RICOCHET_SMOKE = 4;
    private static final float RICOCHET_SMOKE_SIZE = 0.5F;

    /** 弾が<em>入った</em>時に散らす火花の大きさ。{@link Effects#sparks} の尺度で。 */
    private static final float STRIKE_SPARKS = 2.4F;
    /** そのうち、周囲に散らすのではなく穴から吹き返す本数。 */
    private static final int STRIKE_SPATTER = 10;
    /**
     * 吹き返す速さ（1tickあたりブロック）。跳弾より遅く、その差は意図的だ。弾かれた弾は到着時の勢いの
     * 大半を保ってどこかへ行くが、こちらは行かなかった弾から少しだけ戻ってきた分。
     */
    private static final double STRIKE_THROW = 0.5;
    /** 吹き返す円錐の広さ。広い。ここには狙いという物が無いので。 */
    private static final double STRIKE_FAN = 0.4;
    /** わずかに上向きも加える。噴出が板に貼り付かず弧を描くように。 */
    private static final double STRIKE_LIFT = 0.14;
    /** 装甲板そのものの閃光。爆発の描画と同じ尺度で。 */
    private static final float STRIKE_FLASH = 0.6F;
    private static final int STRIKE_SMOKE = 5;
    private static final float STRIKE_SMOKE_SIZE = 0.45F;

    /**
     * 着弾地点で起きること全部。炸薬があればその爆発、どちらにせよ火花、そして当たった物の破片。
     *
     * @param at 炸裂位置
     * @param along 到達してきた線
     * @param round それを起こした弾。大きさと色のため
     * @param struck 当たったブロック。当たっていれば
     * @param onPlate 入った先が地面や通行人ではなく装甲だったか。専用の閃光に値する唯一の場合。
     *                {@link #strike} 参照
     */
    public static void detonation(ServerLevel level, Vec3 at, Vec3 along,
            WeaponDefinition.Projectile round, @Nullable BlockState struck, boolean onPlate) {
        float power = Mth.clamp(round.explosion(), 0.0F, Effects.BIGGEST);

        if (power > 0.0F) {
            Effects.detonate(level, at, power, round.tracer());
        } else if (onPlate) {
            strike(level, at, along, round);
        } else {
            Effects.sparks(level, at, round.tracer(), 1.0F);
        }

        if (struck != null && !struck.isAir()) {
            debris(level, at, struck, Math.max(power, 1.0F));
        }
    }

    /** 爆発本体。大きさと色は運んできた弾が決める。{@link Effects#blast} 参照。 */
    public static void blast(ServerLevel level, Entity source, Vec3 at, WeaponDefinition.Projectile round) {
        Effects.blast(level, source, at, round.explosion(), round.tracer());
    }

    /**
     * 弾が出ていく時に砲が作る発射炎と煙。
     *
     * <p>散らすのではなく前方へ投げる。砲口爆風とはそういう物だからだ。弾の後ろのガスが弾より速く後を
     * 追って出てきて、一瞬、砲身の前に炎の円錐ができる。その価値は「戦車がどちらを向いていて、今撃った」
     * ことを視界内の全員に伝えること。戦車について知るべきことのほとんどがそれ。
     *
     * @param at 砲口
     * @param along 砲身の向き。単位ベクトルで
     * @param power 砲の大きさ。爆発と同じ尺度で、戦車の主砲なら数、機銃なら1未満
     * @param tracer 発射炎の色。弾自身の色
     */
    public static void muzzleBlast(ServerLevel level, Vec3 at, Vec3 along, float power, int tracer) {
        Vec3 ahead = at.add(along.scale(MUZZLE_STANDOFF * power));

        Effects.send(level, ahead, ModParticles.BLAST.get().of(tracer, power * 0.3F),
                2 + (int) (power * 1.2F), power * 0.06, power * 0.02);
        Effects.send(level, ahead, ModParticles.BLAST_SMOKE.get().of(Effects.SOOT, power * 0.36F),
                4 + (int) (power * 2.0F), power * 0.12, power * 0.03);
        // 発射炎の位置に残すのではなく砲身方向へ吹き出させる。煙が「砲口の前に元からあった物」ではなく
        // 「砲から押し出された物」に見えるように。
        Effects.send(level, ahead.add(along.scale(power * 0.35)),
                ModParticles.BLAST_SMOKE.get().of(Effects.SOOT, power * 0.22F),
                2 + (int) power, power * 0.08, power * 0.05);
        Effects.sparks(level, ahead, Effects.EMBER, power * 0.5F);
    }

    /**
     * 装甲を滑った弾。去っていく線に沿って投げられる硬い火花の噴出。
     *
     * <p>散らさず狙って投げる。言いたいことはそれが全部だ。入った当たりも入らなかった当たりも、装甲板の
     * 閃光としては似たように見える。どちらだったかを砲手に伝えるのは、火花が<em>どこかへ</em>行ったこと
     * ——傾斜に沿って、戦車から離れて、弾が今進んでいる線の上を。砲塔前面から返ってくるその筋は、砲手へ
     * の「別の場所を狙え」という通知になる。
     *
     * @param at 装甲板に当たった位置
     * @param away 去っていく方向
     * @param round それを起こした弾。色のため
     */
    public static void ricochet(ServerLevel level, Vec3 at, Vec3 away, WeaponDefinition.Projectile round) {
        Vec3 along = away.lengthSqr() < 1.0E-8 ? Vec3.ZERO : away.normalize();
        RandomSource random = level.getRandom();

        Effects.sparks(level, at, round.tracer(), RICOCHET_SPARKS);

        for (int i = 0; i < RICOCHET_STREAKS; i++) {
            // 1本ずつ少しずらして投げる。同じ点から同じ速度を与えた6個のパーティクルは、1個を6回描いた
            // のと同じになってしまうので。
            Vec3 thrown = along.scale(RICOCHET_THROW).add(
                    random.nextGaussian() * RICOCHET_FAN, random.nextGaussian() * RICOCHET_FAN,
                    random.nextGaussian() * RICOCHET_FAN);

            Effects.aimed(level, at, ModParticles.SPARK.get().of(Effects.EMBER, 1.0F), thrown);
        }

        Effects.send(level, at, ModParticles.BLAST_SMOKE.get().of(Effects.SOOT, RICOCHET_SMOKE_SIZE),
                RICOCHET_SMOKE, 0.08, 0.04);
    }

    /**
     * 装甲へ入った弾。装甲板の白い閃光と、そこから吹き返す火花。
     *
     * <p>{@link #ricochet} のもう半分で、今までほとんど見せる物が無かった方。入った当たりは、斜面に撃ち
     * 込んだ時と同じ数個の火花しか出さなかった。この距離で戦う以上、それは砲手に見えない命中だ。伝えるべ
     * きは「<em>戦車の上で</em>明るい何かが起きた」ことで、区別すべき跳弾と同じくらい目に大きく訴えたい。
     *
     * <p>だから形は逆にしてある。跳弾は弾が去った線に沿って火花を投げる——遠くへ、一方向へ、筋として。
     * こちらは弾が来た方向を軸にした広い円錐へ<em>吹き返す</em>。何も去らなかったからだ。穴から戻ってくる
     * のは板が受け止めきれなかった僅かな分で、射線の脇ではなく射線へ向かって出てくる。火花がこちらへ来る
     * のを見ている砲手は当てており、どこかへ行くのを見ている砲手は当てていない。
     *
     * @param at 装甲板に当たった位置
     * @param along 弾が到達してきた線
     * @param round それを起こした弾。色のため
     */
    public static void strike(ServerLevel level, Vec3 at, Vec3 along, WeaponDefinition.Projectile round) {
        Vec3 back = along.lengthSqr() < 1.0E-8 ? Vec3.ZERO : along.normalize().reverse();
        RandomSource random = level.getRandom();

        // 閃光は弾自身の色。装甲板で光っているのは弾なので。
        Effects.send(level, at, ModParticles.BLAST.get().of(round.tracer(), STRIKE_FLASH),
                2, 0.05, 0.02);
        Effects.sparks(level, at, Effects.EMBER, STRIKE_SPARKS);

        for (int i = 0; i < STRIKE_SPATTER; i++) {
            // 1個ずつ少しずらして投げる。同じ点から同じ速度を与えた10個は、1個を10回描いたのと同じに
            // なってしまうので。
            Vec3 thrown = back.scale(STRIKE_THROW).add(
                    random.nextGaussian() * STRIKE_FAN,
                    random.nextGaussian() * STRIKE_FAN + STRIKE_LIFT,
                    random.nextGaussian() * STRIKE_FAN);

            Effects.aimed(level, at, ModParticles.SPARK.get().of(Effects.EMBER, 1.0F), thrown);
        }

        Effects.send(level, at, ModParticles.BLAST_SMOKE.get().of(Effects.SOOT, STRIKE_SMOKE_SIZE),
                STRIKE_SMOKE, 0.08, 0.05);
    }

    /** 当たった物の欠片。元のブロックの色で。 */
    private static void debris(ServerLevel level, Vec3 at, BlockState struck, float power) {
        int colour = struck.getMapColor(level, BlockPos.containing(at)).col;

        Effects.send(level, at, ModParticles.DEBRIS.get().of(colour, 1.0F),
                5 + (int) (power * 2.5F), 0.08, 0.08 + power * 0.03);
    }

    private WeaponEffects() {
    }
}
