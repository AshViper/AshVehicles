package com.ashvehicles.vehicle;

import com.ashvehicles.particle.Effects;
import com.ashvehicles.registry.ModParticles;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaternionf;

/**
 * 撃破された時の見た目と音。被弾から最後の煙まで。
 *
 * <p>4つの場面があり、順に見られることを想定している。
 *
 * <p><b>被弾。</b> 火球は1つではなく複数を、中心に積むのではなく機体に沿って並べる。機体は15mあり、
 * 中心の一点で爆発させると「機体の<em>近く</em>で何かが起きた」に見える。全長から火が出れば機体そのものに
 * 見える。ちぎれた機体構造も一緒に、強く全方向へ飛ぶ。
 *
 * <p><b>落下。</b> 空中で撃墜された機体はそれまでの動きを保ったまま、炎と黒煙を引いて落ちる。ここが本題
 * で、1km 先の撃墜は明るい点と長く黒い線になり、どちらも地面に着くまで見え続ける。
 *
 * <p><b>着地。</b> 落ちた場所に炎と土煙と重い音。ただしクレーターは作らない。撃墜した爆発が既に開ける
 * べき穴は開けており、1機で2つ開けるのは誰も頼んでいない。
 *
 * <p><b>燃焼。</b> その後はそこで燃える。最初の10秒は激しく、続く1分で細り、最後は冷える。永遠に煙を出す
 * 残骸は「これまで撃墜された全機分のパーティクルの柱」になり、世界がそれで埋まる。
 *
 * <p>全部サーバーから {@link Effects} 経由で送るので、パーティクルパケットの既定32ブロックではなく、
 * 出せる限界の512ブロックを運ぶ。空から見えない火事に意味は無い。
 */
public final class WreckEffects {
    /** 炎そのもの。爆発が撒く残り火より熱く、よりオレンジ寄り。 */
    private static final int FLAME = 0xFF8A2A;
    /** ちぎれた機体構造。今バラバラになった機体から飛び出す物。 */
    private static final int SCRAP = 0x7A736B;

    /** 残骸が最も激しく燃え、消え始めるまでの時間（tick）。 */
    private static final int FIERCE_TICKS = 200;
    /** そして冷えて煙も止まるまでの時間。全体で1分強。 */
    public static final int BURN_OUT_TICKS = 1400;

    /**
     * これを超えていれば残骸はまだ落下中で、着地して転がっているのではない、という速度の二乗。
     *
     * <p>public なのは機体側も同じ値を見ているから。これを下回った瞬間が残骸の着地であり、
     * {@link #impact} を描く価値があるのもその時。
     */
    public static final double FALLING = 0.04;

    /** 静止した残骸が煙を吐く間隔（tick）。最盛期はこの間隔、末期はこの間隔。 */
    private static final int FAST_PUFF = 2;
    private static final int SLOW_PUFF = 9;

    /** 燃える残骸のパチパチ音の間隔（tick）。 */
    private static final int CRACKLE_TICKS = 19;
    /**
     * 火の音量。音は送受信の両側で {@code max(volume, 1) * 16} ブロック届くので、これは40ブロック先から
     * 聞こえる残骸ということ。歩いて行ける近さで、それを頼りに見つけられる遠さ。
     */
    private static final float FIRE_VOLUME = 2.5F;

    /** 炎を機体の全長のどれだけに広げるか。 */
    private static final double SPREAD = 0.45;
    /** その広がりの扁平さ。残骸は細長く低い物であって球ではない。 */
    private static final double FLATNESS = 0.3;

    /**
     * 着地演出を描く価値がある落下速度（1tickあたりブロック）。これ未満なら残骸は地面に「ぶつかった」の
     * ではなく「落ち着いた」だけで、見るべき物は無い。
     */
    private static final double HARD_ARRIVAL = 0.35;

    /**
     * 着地の音を、同じ規模の爆発に対してどれだけ小さくするか。
     *
     * <p>土煙と炎は落ちてきた機体の大きさで立ってよい。だが音まで同じにすると、墜落が数km先まで炸薬として
     * 聞こえる。落ちたのは機体であって弾頭ではない。
     */
    private static final float HEARD_AS_A_FALL = 0.6F;

    // ------------------------------------------------------------------
    // 被弾
    // ------------------------------------------------------------------

    /**
     * 機体が機体でなくなる瞬間。全長から出る炎、衝撃波、飛散物、そして遠くまで届く爆発音。
     *
     * <p>爆発そのもの（ダメージと穴）は {@link Effects#blast} の担当で、ここが呼ばれる時点で既に済んで
     * いる。ここにあるのは見た目だけ。
     *
     * @param power 機体自身の爆発規模。全ての大きさの基準
     * @param reach 機体が中心から何ブロック伸びているか。戦車の炎は戦車の大きさで、機体の炎は機体の
     *              大きさで出るように
     * @param attitude どの向きで寝ているか。炎が北向きではなく機体に沿って走るように
     */
    public static void destroyed(ServerLevel level, Vec3 at, Quaternionf attitude, float power, double reach) {
        // 意図的に、開ける穴より大きく描く。機体ファイルの爆発値は「地面に何をするか」の値で、撃墜され
        // た機体が村を平らにする道理は無いので控えめな数字にしてある。一方 *見た目* は機体の大きさで
        // あるべきなので、描画には機体寸法も渡し、クレーターの方は触らない。
        float sized = Mth.clamp(power + (float) reach * 0.4F, 1.0F, Effects.BIGGEST);
        Vec3 along = Attitude.toWorld(attitude, new Vec3(0.0, 0.0, 1.0));

        // 機体に沿って並べる。機首・中央・尾部の3つ。炎が機体の形を持つには十分で、3回の爆発に見えない
        // 程度に少ない。
        Effects.detonate(level, at, sized, Effects.EMBER);
        Effects.fireball(level, at.add(along.scale(reach * 0.55)), sized * 0.6F, Effects.EMBER);
        Effects.fireball(level, at.subtract(along.scale(reach * 0.55)), sized * 0.6F, Effects.EMBER);
        wreckage(level, at, sized, reach);
    }

    /** ちぎれた機体構造。強く全方向へ飛び、飛びながら燃える。 */
    private static void wreckage(ServerLevel level, Vec3 at, float power, double reach) {
        int pieces = 12 + (int) (reach * 3.0);

        Effects.send(level, at, ModParticles.DEBRIS.get().of(SCRAP, 1.4F),
                pieces, reach * 0.3, 0.25 + power * 0.05);
        Effects.send(level, at, ModParticles.SPARK.get().of(Effects.EMBER, 1.2F),
                pieces, reach * 0.25, 0.3 + power * 0.06);
        Effects.send(level, at, ModParticles.FIRE.get().of(FLAME, 1.6F),
                6 + (int) reach, reach * 0.35, 0.08);
    }

    // ------------------------------------------------------------------
    // 落下と、その後の燃焼
    // ------------------------------------------------------------------

    /**
     * 残骸としての1tick分。サーバー側で、見るべき物が残っている限り毎tick。
     *
     * @param age 残骸になってからの経過 tick。燃え方の全てがこれで決まる
     * @param velocity 動き。落下中か着地済みかの判別に使い、落下中なら煙をどちらへ流すかも決める
     */
    public static void burn(ServerLevel level, Vec3 at, Quaternionf attitude, int age, Vec3 velocity,
            double reach) {
        if (age > BURN_OUT_TICKS) {
            return;
        }

        float heat = heat(age);
        Vec3 middle = at.add(Attitude.toWorld(attitude, new Vec3(0.0, reach * 0.15, 0.0)));

        if (velocity.lengthSqr() > FALLING) {
            trail(level, middle, velocity, heat, reach);

            return;
        }

        smoulder(level, middle, age, heat, reach);
    }

    /**
     * その経過時間の残骸がどれだけ激しく燃えているか。1からゼロまで。
     *
     * <p>最初の10秒は一定、その後1分かけて直線的に落ちる。一定部分が、撃墜直後を「もう消えかけの何か」
     * ではなく火事として見せる。傾斜部分が、世界を永久の煙柱で埋めるのを防ぐ。
     */
    private static float heat(int age) {
        if (age <= FIERCE_TICKS) {
            return 1.0F;
        }

        return 1.0F - (float) (age - FIERCE_TICKS) / (BURN_OUT_TICKS - FIERCE_TICKS);
    }

    /**
     * 燃えながら落ちる機体。機体位置に炎と濃い煙、そして後ろに本来の航跡。
     *
     * <p>毎tick、遠慮なく出す。続くのは落下している間（数秒）だけで、撃墜を見ている者が実際に目にするの
     * はこれだから。
     *
     * <p>航跡は現在位置ではなく機体が<em>いた</em>位置へ置く。そこが肝で、煙は機体と一緒に進むのではなく
     * 機体が置いていく物だ。毎tick 機首に出せば、残骸を追う線ではなく残骸を先導する線が描かれてしまう。
     */
    private static void trail(ServerLevel level, Vec3 at, Vec3 velocity, float heat, double reach) {
        float size = (float) Math.max(reach * 0.25, 1.0);

        Effects.send(level, at, ModParticles.FIRE.get().of(FLAME, size * (0.6F + heat)),
                1 + (int) (heat * 3.0F), reach * 0.2, 0.04);
        Effects.send(level, at, ModParticles.MOTOR_SMOKE.get().of(Effects.SOOT, size * 1.1F),
                2 + (int) (heat * 2.0F), reach * 0.15, 0.03);
        // 後方へ、機体自身の速度を持たせて。描いているのは残骸が既に通り過ぎた空気で、そこにはまだ機体
        // から出た物が満ちている。
        Effects.aimed(level, at.subtract(velocity), ModParticles.CONTRAIL.get().of(Effects.SOOT, size * 1.4F),
                velocity.scale(0.25));

        if (heat > 0.5F) {
            Effects.sparks(level, at, Effects.EMBER, 1.0F);
        }
    }

    /**
     * 着地した場所で燃え尽きていく残骸。機体から立つ炎と、そこから上がる黒煙の柱。
     *
     * <p>閾値で切るのではなく残り火の量で薄めるので、火が消えるように消える——先に炎、次に煙、最後は一筋
     * ——し、目に見えて止まる tick が存在しない。
     */
    private static void smoulder(ServerLevel level, Vec3 at, int age, float heat, double reach) {
        int every = Math.round(Mth.lerp(heat, SLOW_PUFF, FAST_PUFF));

        if (age % Math.max(every, 1) != 0) {
            return;
        }

        float size = (float) Math.max(reach * 0.22, 0.8);
        Vec3 spread = new Vec3(reach * SPREAD, reach * SPREAD * FLATNESS, reach * SPREAD);

        if (heat > 0.1F) {
            Effects.send(level, at, ModParticles.FIRE.get().of(FLAME, size * (0.5F + heat)),
                    1 + (int) (heat * 2.0F), spread, 0.02);
        }

        // 煙柱。漂わせるのではなく上向きの初速を与え、残骸の上に居座らず立ち上がるようにする。1km 先の
        // 誰かに「あそこに残骸がある」と告げるのは木立から立つ煙で、広がるだけの煙はそこまで届かない。
        Effects.send(level, at, ModParticles.BLAST_SMOKE.get().of(Effects.SOOT, size * (0.9F + heat * 0.8F)),
                1 + (int) (heat * 2.0F), spread, 0.02);
        Effects.aimed(level, at, ModParticles.BLAST_SMOKE.get().of(Effects.SOOT, size * 1.3F),
                new Vec3(0.0, 0.06 + heat * 0.10, 0.0));

        if (heat > 0.35F) {
            Effects.sparks(level, at, Effects.EMBER, heat * 0.5F);
        }

        // パチパチ音。全クライアントが既に持っているバニラの火の音を、焚き火より大きく、火が爆ぜる程度
        // の頻度で鳴らす。
        if (heat > 0.2F && age % CRACKLE_TICKS == 0) {
            RandomSource random = level.getRandom();

            level.playSound(null, at.x, at.y, at.z, SoundEvents.FIRE_AMBIENT, SoundSource.BLOCKS,
                    FIRE_VOLUME * heat, 0.6F + random.nextFloat() * 0.3F);
        }
    }

    // ------------------------------------------------------------------
    // 着地
    // ------------------------------------------------------------------

    /**
     * 残骸が地面に到達する瞬間。炎、土煙の壁、重い音。
     *
     * <p>意図的に爆発は起こさない。ここではブロックも壊さず誰も傷つけない——撃墜した爆発が既に開けるべき
     * 穴は開けており、着地でもう1つ開けさせればこの MOD のクレーターが全部2倍になる。この瞬間に欲しいの
     * は音と土煙で、そちらは無料。
     *
     * @param speed 着地時の速度（1tickあたりブロック）。全ての大きさの基準
     */
    public static void impact(ServerLevel level, Vec3 at, double speed, double reach) {
        if (speed < HARD_ARRIVAL) {
            return;
        }

        float force = (float) Mth.clamp(speed * reach * 0.5, 1.0, Effects.BIGGEST);

        Effects.detonate(level, at, force, Effects.EMBER, force * HEARD_AS_A_FALL);
        Effects.send(level, at, ModParticles.DEBRIS.get().of(SCRAP, 1.2F),
                8 + (int) (force * 2.0F), reach * 0.2, 0.12 + speed * 0.1);
    }

    private WreckEffects() {
    }
}
