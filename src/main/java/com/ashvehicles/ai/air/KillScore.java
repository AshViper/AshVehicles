package com.ashvehicles.ai.air;

import com.ashvehicles.ai.decision.ParameterSet;
import com.ashvehicles.weapon.WeaponDefinition;

/**
 * AI の航空機が次に狙う相手の点数——撃破を稼げる順（2026-09-13 の指示「状況を見て戦闘中に合わせて、ターゲットを
 * 切り替えて kill 数を稼げるようなスコア式」）。
 *
 * <pre>
 * score = 空域 × (kill × value × 1分あたりの見込み撃破 × e^(−defence × 相手の周りの防空) ÷ (1 + crowd × 同じ相手を狙う僚機)
 *                + support × 支援の急ぎ（支援を要請した味方を撃っている） + objective（拠点の近く）
 *                + sticky（今の相手）、撃ちに入っている間は committed)
 *       + attackingMe（自分を撃っている） + threat（空を撃てて自分に届く／近くの敵機）
 *
 * 1分あたりの見込み撃破 = 倒せる見込み × 1200 ÷ (着くまでの tick + (要る航過 − 1) × 航過1回の tick)
 * 要る航過 = ⌈残り耐久 ÷ 1回の航過で削れる見込み⌉
 * 空域 = 戦闘空域の外なら e^(−outside × (縁から出た距離 ÷ 600)²)、内なら 1 + inside × 縁から入った深さ ÷ 半径
 * </pre>
 *
 * <p><b>一番近い相手でも、一番硬い相手でもない。</b> 残り耐久が少なく、機首の前にいて、手持ちの兵装がよく効く相手ほど
 * 早く倒せる。弾が足りなければ倒せる見込みが落ち、効かない兵装（装甲に弾かれる機銃）しか無ければ0。{@code value} は
 * 地上の車両が1、空の敵は空対空ミサイルがあるうち 0.6、使い切って機関砲と爆弾だけなら 0.3——車両を倒す方が点が高く、
 * 航空機を落としても少しは点になる（2026-09-14 の指示「航空機は車両を撃破できるほうが高くスコアを得られるように。航空機も
 * 撃破しても多少はスコアは得られる」。それまでは前日の指示「敵の航空機がいる場合は優先して撃墜」で 2.5 だった）。近くの敵機には
 * 空対空ミサイルがあるうち threat の足し点が付くので、身を守る撃ち合いは残る。
 *
 * <p><b>支援を要請した味方を撃っている相手には support を足す</b>（同日の指示「味方にＣＡＳのできる航空勢力がいれば航空支援を
 * 要求」、{@code ai/team/SupportCalls}）。地上を撃てない機体はその相手を選べないので、応えるのは CAS のできる機体だけ。
 *
 * <p><b>戦闘空域から離れた相手ほど下げ、内側の相手ほど上げる</b>（{@link #airspace}、同日の指示「航空機は戦闘空域から離れすぎると
 * スコアが下がるようにして、その逆はスコアが上がるように」）。戦闘空域は戦域の円（{@code match/Bots.arenaRadius}、旗と拠点を
 * 必ず含む）の上空。外側は出た距離の2乗で下がるので、縁の少し外（200ブロックで 0.89 倍）はほとんど変えず、離れすぎ（600で 0.37 倍、
 * 900で 0.11 倍）を強く下げる——航過の旋回と抜けは縁をまたぐのが普通で、そこまで嫌うと縁の近くの敵を撃てない。<b>身を守る分
 * （自分を撃っている・届く脅威）には掛けない</b>——外から撃ってくる相手に背を向けて戻れば、後ろから落とされる。
 *
 * <p><b>削れる見込みは兵装ファイルとゲームの式から出す</b>（{@link #damagePerPass}）: 機銃は弾数 × 当たる割合 × 装甲に
 * 入る割合（{@code weapon/Ricochet} の角度）× 空気で落ちた威力、爆弾はバニラの爆発の式（半径は威力の2倍）に狙いの
 * ずれを入れた物——{@code projectile.blast} を持つ爆弾はその爆風の式（{@code WeaponDefinition.Projectile.blastAt}）——、
 * 空対空ミサイルは弾頭 × 当たる割合。{@code AirPilot} はこれを、その相手に実際に与えた打撃で直す。
 *
 * <p><b>世界に触らない。</b> 相手ごとの事実（{@link Prospect}）は {@code AirPilot} が名簿と兵装ファイルから組む。
 * 重みは版のパラメータ（{@code air.target.*}）。
 */
public final class KillScore {
    /** 1回の航過で放す爆弾の数。{@code AirPilot} もこれだけ放す。 */
    public static final int BOMBS_PER_PASS = 2;

    /** 空の相手1機との交戦で撃つ空対空ミサイルの数。 */
    public static final int AAM_PER_ENGAGEMENT = 2;

    /** 1分（tick）。点数の目盛り。 */
    private static final double MINUTE = 1200.0;

    /** 見込みの tick の下限。真上の相手で割り算が膨らまないように。 */
    private static final double LEAST_TICKS = 40.0;

    /** 数える航過の上限。効きの薄い兵装で整数があふれないように。 */
    private static final double MOST_PASSES = 100.0;

    /** 弾が足りずに倒し切れない相手の見込みに掛ける割合。削った分は味方が拾うかもしれないので0にはしない。 */
    private static final double PARTIAL = 0.5;

    /** 旋回半径の見積もりに使う重力（ブロック/tick²）とバンク（度）と、半径の下限（ブロック）。 */
    private static final double GRAVITY = 0.0245;
    private static final double TURN_BANK = 60.0;
    private static final double LEAST_RADIUS = 120.0;

    /** 空の相手とすれ違って向き直るまでの、旋回を除いた長さ（tick）。{@code AirPilot.MERGE_TICKS} と同じ。 */
    private static final double MERGE_TICKS = 60.0;

    /** 回転翼機が止まって狙い直すまでの長さ（tick）。 */
    private static final double HOVER_TICKS = 60.0;

    /** 戦闘空域の外で点数が e 分の1（{@code air.target.outside} が1のとき）になる、縁から出た距離（ブロック）。 */
    private static final double AIRSPACE_SCALE = 600.0;

    // ------------------------------------------------------------------
    // 機銃
    // ------------------------------------------------------------------

    /** 1回の航過で撃ち続けられる長さ（秒）。地上へは降りながら、空へはすれ違う前に。 */
    private static final double GROUND_BURST = 3.0;
    private static final double AIR_BURST = 2.0;

    /** 撃った弾が当たる割合。車両、歩いている人、飛んでいる機体。 */
    private static final double HIT_VEHICLE = 0.3;
    private static final double HIT_PERSON = 0.02;
    private static final double HIT_AIRCRAFT = 0.06;

    /** 弾が飛ぶ距離の目安（ブロック）。空気が威力を削る分（{@code Projectile.energyAfter}）。 */
    private static final double GROUND_RANGE = 600.0;
    private static final double AIR_RANGE = 500.0;

    /**
     * 空から撃った弾のうち、装甲の立った面に当たる割合と、その面への入射角の目安（度）と、入る・入らないの幅（度）。
     * 残りは寝た面（上面）に浅く当たって弾かれる（{@code weapon/Ricochet}: 入射角が「弾の角度 − 装甲」を超えると弾く）。
     */
    private static final double FACE_SHARE = 0.5;
    private static final double FACE_INCIDENCE = 15.0;
    private static final double INCIDENCE_SPREAD = 30.0;

    // ------------------------------------------------------------------
    // 爆弾・空対空ミサイル
    // ------------------------------------------------------------------

    /** 爆弾の狙いのずれ（ブロック）と、動く相手の読み違い（落ちる間に動く距離に対する割合）と、落ちる長さの目安（tick）。 */
    private static final double BOMB_MISS = 4.0;
    private static final double DRIFT_MISS = 0.3;
    private static final double FALL_TICKS = 130.0;

    /** 爆弾が相手に直に当たる割合。 */
    private static final double DIRECT_SHARE = 0.2;

    /** クラスター爆弾1発が、車両と人に与える見込み（点）。子弾が面に散るので爆風の式では数えない。 */
    private static final double CLUSTER_VEHICLE = 120.0;
    private static final double CLUSTER_PERSON = 40.0;

    /** 空対空ミサイルが当たる割合（囮と回避の分を引いた物）。 */
    private static final double AAM_HIT = 0.55;

    private KillScore() {
    }

    /** 重み。{@link #of} が版のパラメータ（{@code air.target.*}）から読む。 */
    public record Weights(double air, double gunAir, double kill, double attackingMe, double threat,
            double objective, double sticky, double committed, double crowd, double defence, double outside,
            double inside, double support) {
        public static Weights of(ParameterSet parameters) {
            return new Weights(
                    parameters.get("air.target.air", 0.6),
                    parameters.get("air.target.gun_air", 0.3),
                    parameters.get("air.target.kill", 1.0),
                    parameters.get("air.target.attacking_me", 1.5),
                    parameters.get("air.target.threat", 1.0),
                    parameters.get("air.target.objective", 0.5),
                    parameters.get("air.target.sticky", 0.3),
                    parameters.get("air.target.committed", 2.0),
                    parameters.get("air.target.crowd", 0.5),
                    parameters.get("air.target.defence", 0.35),
                    parameters.get("air.target.outside", 1.0),
                    parameters.get("air.target.inside", 0.3),
                    parameters.get("air.target.support", 1.5));
        }
    }

    /**
     * 兵装1つの数字。兵装ファイルから。
     *
     * @param roundsPerSecond 毎秒の発数（一斉射の数を掛けた物）
     * @param ricochet        弾かれずに入れる角（度）。0 は決して弾かれない
     * @param drag            空気の抗力（{@code Projectile.drag}）
     * @param blast           爆風が機体に与える打撃（{@code Projectile.blast}）。0 ならバニラの爆発の式
     * @param cluster         子弾を撒く爆弾か
     */
    public record Round(AirLoadout.Store store, double damage, double explosion, double blast, double roundsPerSecond,
            double ricochet, double drag, boolean cluster) {
    }

    /**
     * 相手の姿。
     *
     * @param person 車両でも機体でもない（歩いている人）
     * @param armour 装甲の値（弾の角度から引く度数）
     * @param taken  届いた打撃のうち受け取る割合（{@code hull.damage_taken}）
     * @param speed  動く速さ（ブロック/tick）
     */
    public record Mark(boolean airborne, boolean person, boolean armoured, double armour, double taken,
            double speed) {
    }

    /**
     * 相手1つぶんの見込み。
     *
     * @param distance      水平の距離（ブロック）
     * @param offTrack      自分の進む向きから相手までの角（ラジアン）
     * @param health        残り耐久（点）
     * @param damagePerPass 1回の航過で削れる見込み（点）
     * @param passes        手持ちの弾であと何回航過できるか
     * @param allies        同じ相手を狙っている僚機の数
     * @param defence       相手の周りの防空（防空車両1、空を撃てる他の車両0.5）
     * @param outside       相手が戦闘空域の縁から外へ出ている水平の距離（ブロック）。内側は負で、縁から入った深さ
     * @param airspace      戦闘空域の半径（ブロック）。0以下は空域の無い試合で、場所を問わない
     * @param support       その相手が撃っている味方の支援の要請の急ぎ（0〜1、{@code ai/team/SupportCalls#airSupport}）。無ければ0
     */
    public record Prospect(boolean airborne, double distance, double offTrack, double health, double damagePerPass,
            int passes, boolean attackingMe, boolean threatensMe, boolean onObjective, int allies, double defence,
            double outside, double airspace, double support) {
    }

    /**
     * 自分の飛び方。
     *
     * @param turnRadius 旋回半径（ブロック）
     * @param extend     航過の後に取る距離（ブロック）
     * @param setup      地上の相手を撃つのに要る距離（これより近い相手は一度抜けて向き直る）
     * @param standoff   回転翼機が離れて止まる距離
     * @param missiles   空対空ミサイルが残っているか。尽きていれば空の相手より地上を先にする
     */
    public record Flight(boolean rotorcraft, double speed, double turnRadius, double extend, double setup,
            double standoff, boolean missiles) {
    }

    /** 点数。高いほど先に狙う。 */
    public static double score(Prospect prospect, Flight flight, Weights weights, boolean current, boolean committed) {
        double rate = killsPerMinute(prospect, flight) * Math.exp(-weights.defence() * prospect.defence())
                / (1.0 + weights.crowd() * prospect.allies());
        // 地上の車両を先にし、空の敵は少しだけ。空対空ミサイルを使い切った機体ではさらに下げる（2026-09-14 の指示）。
        double value = !prospect.airborne() ? 1.0 : flight.missiles() ? weights.air() : weights.gunAir();
        double score = weights.kill() * value * rate + weights.support() * prospect.support();

        if (prospect.onObjective()) {
            score += weights.objective();
        }

        if (current) {
            score += committed ? weights.committed() : weights.sticky();
        }

        // 戦闘空域から離れた相手ほど下げる（同日の指示）。身を守る分には掛けない。
        score *= airspace(prospect, weights);

        if (prospect.attackingMe()) {
            score += weights.attackingMe();
        }

        if (prospect.threatensMe()) {
            score += weights.threat();
        }

        return score;
    }

    /**
     * 戦闘空域から見た相手の場所の倍率。縁の外は e^(−outside × (出た距離 ÷ {@value #AIRSPACE_SCALE})²)、内側は
     * 1 + inside × 縁から入った深さ ÷ 半径（真ん中で最大）。空域の無い試合は1。
     */
    public static double airspace(Prospect prospect, Weights weights) {
        if (prospect.airspace() <= 0.0) {
            return 1.0;
        }

        if (prospect.outside() > 0.0) {
            double beyond = prospect.outside() / AIRSPACE_SCALE;

            return Math.exp(-weights.outside() * beyond * beyond);
        }

        return 1.0 + weights.inside() * clamp(-prospect.outside() / prospect.airspace(), 0.0, 1.0);
    }

    /** その相手を、今の兵装で1分あたり何機倒せるかの見込み。 */
    public static double killsPerMinute(Prospect prospect, Flight flight) {
        if (prospect.damagePerPass() <= 0.0 || prospect.passes() <= 0 || prospect.health() <= 0.0) {
            return 0.0;
        }

        int needed = (int) Math.min(Math.ceil(prospect.health() / prospect.damagePerPass()), MOST_PASSES);
        int flown = Math.min(needed, prospect.passes());
        double chance = flown >= needed ? 1.0 : PARTIAL * Math.pow((double) flown / needed, 2.0);
        double ticks = reachTicks(prospect, flight) + (flown - 1) * cycleTicks(prospect, flight);

        return chance * MINUTE / Math.max(ticks, LEAST_TICKS);
    }

    /**
     * 最初に撃てるまでの tick。固定翼機は旋回の弧と距離を今の速さで割り、地上の相手が近すぎれば一度抜けて向き直る分を
     * 足す。回転翼機は離れて止まる距離まで寄って狙う分。
     */
    public static double reachTicks(Prospect prospect, Flight flight) {
        double speed = Math.max(flight.speed(), 0.5);

        if (flight.rotorcraft()) {
            return Math.max(0.0, prospect.distance() - flight.standoff()) / speed + HOVER_TICKS;
        }

        double ticks = (prospect.distance() + flight.turnRadius() * prospect.offTrack()) / speed;

        if (!prospect.airborne() && prospect.distance() < flight.setup()) {
            ticks += 2.0 * (flight.setup() - prospect.distance()) / speed;
        }

        return ticks;
    }

    /** 次の航過までの tick。地上の相手は抜けて戻る往復と半周、空の相手はすれ違いと半周。 */
    public static double cycleTicks(Prospect prospect, Flight flight) {
        double speed = Math.max(flight.speed(), 0.5);

        if (flight.rotorcraft()) {
            return HOVER_TICKS;
        }

        double turn = Math.PI * flight.turnRadius() / speed;

        return prospect.airborne() ? MERGE_TICKS + turn : 2.0 * flight.extend() / speed + turn;
    }

    /** その速さでの旋回半径の目安（ブロック）。60度バンクの水平旋回。 */
    public static double turnRadius(double speed) {
        return Math.max(LEAST_RADIUS, speed * speed / (GRAVITY * Math.tan(Math.toRadians(TURN_BANK))));
    }

    /** 1回の航過（空の相手なら1回の交戦）で、その兵装がその相手の耐久を削れる見込み（点）。効かなければ0。 */
    public static double damagePerPass(Round round, Mark mark, int ammo) {
        if (ammo <= 0) {
            return 0.0;
        }

        return switch (round.store()) {
            case GUN -> gun(round, mark, ammo);
            case BOMB -> mark.airborne() ? 0.0 : bomb(round, mark, ammo);
            case AAM -> mark.airborne()
                    ? Math.min(ammo, AAM_PER_ENGAGEMENT) * AAM_HIT * round.damage() * mark.taken()
                    : 0.0;
        };
    }

    /** 手持ちの弾で、あと何回航過できるか。 */
    public static int passesLeft(Round round, Mark mark, int ammo) {
        if (ammo <= 0) {
            return 0;
        }

        return switch (round.store()) {
            case GUN -> (int) Math.ceil(ammo / Math.max(burst(round, mark), 1.0));
            case BOMB -> (int) Math.ceil(ammo / (double) BOMBS_PER_PASS);
            case AAM -> (int) Math.ceil(ammo / (double) AAM_PER_ENGAGEMENT);
        };
    }

    /**
     * 装甲に入る割合（0〜1）。装甲を持たない相手と、弾かれない弾（角度を持たない物）は1。
     *
     * <p>空から撃つ弾の {@value #FACE_SHARE} は寝た面に浅く当たって弾かれ、残りは立った面に {@value #FACE_INCIDENCE}
     * 度前後で当たる。そこで入るのは「弾の角度 − 装甲」がその入射角を超える分——M61（45度）は戦車（装甲5）の立った面へ
     * 大抵入り、12.7mm（14度）はどこにも入らない。
     */
    public static double penetration(Round round, Mark mark) {
        if (!mark.armoured() || round.ricochet() <= 0.0) {
            return 1.0;
        }

        double margin = Math.max(round.ricochet() - mark.armour(), 0.0);

        return FACE_SHARE * clamp((margin - FACE_INCIDENCE) / INCIDENCE_SPREAD, 0.0, 1.0);
    }

    /**
     * バニラの爆発がその距離の相手に与える点（遮られていない相手に）。{@code ExplosionDamageCalculator} の式で、届くのは
     * 威力の2倍まで。
     */
    public static double blast(double power, double distance) {
        if (power <= 0.0) {
            return 0.0;
        }

        double reach = power * 2.0;
        double impact = 1.0 - distance / reach;

        if (impact <= 0.0) {
            return 0.0;
        }

        return (impact * impact + impact) / 2.0 * 7.0 * reach + 1.0;
    }

    private static double gun(Round round, Mark mark, int ammo) {
        double rounds = Math.min(ammo, burst(round, mark));
        double hit = mark.airborne() ? HIT_AIRCRAFT : mark.person() ? HIT_PERSON : HIT_VEHICLE;
        double energy = round.explosion() > 0.0 ? 1.0
                : Math.exp(-2.0 * round.drag() * (mark.airborne() ? AIR_RANGE : GROUND_RANGE));

        return rounds * hit * penetration(round, mark) * round.damage() * energy * mark.taken();
    }

    private static double burst(Round round, Mark mark) {
        return round.roundsPerSecond() * (mark.airborne() ? AIR_BURST : GROUND_BURST);
    }

    private static double bomb(Round round, Mark mark, int ammo) {
        int bombs = Math.min(ammo, BOMBS_PER_PASS);

        if (round.cluster()) {
            return bombs * (mark.person() ? CLUSTER_PERSON : CLUSTER_VEHICLE) * mark.taken();
        }

        double miss = BOMB_MISS + mark.speed() * FALL_TICKS * DRIFT_MISS;

        if (round.blast() > 0.0) {
            // 自前の爆風を持つ爆弾。直撃した相手には爆風との差分しか足さない（{@code VehicleProjectile.blastMachines}）
            // ので、見込みも「爆風 + 直撃の割合 × 差分」——直撃すれば damage、しなければ爆風。
            double near = WeaponDefinition.Projectile.blastAt(round.blast(), round.explosion(), miss);

            return bombs * (near + DIRECT_SHARE * Math.max(0.0, round.damage() - near)) * mark.taken();
        }

        return bombs * (blast(round.explosion(), miss) + DIRECT_SHARE * round.damage()) * mark.taken();
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }
}
