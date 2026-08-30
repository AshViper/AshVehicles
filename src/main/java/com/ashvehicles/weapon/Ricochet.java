package com.ashvehicles.weapon;

import java.util.Optional;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.vehicle.Hitbox;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * 装甲に入らず弾かれた弾。
 *
 * <p>決めるのは1つの角度だけ。装甲板の法線から測って、弾がどれだけ直角から外れて当たったか。正面から
 * 来た弾は速度の全部が金属へ向いているので入る。同じ弾が表面に沿うように来れば、何かへ向いた成分はほぼ
 * 無く、滑って去る。その2つの間には数度幅の帯があり、そこではどちらも起こり得る。実物がそうだから——
 * 同じ砲が同じ角度で、ある弾は食い込み次の弾は弾かれる。
 *
 * <p><b>傾斜角はどこにも数値として無い。</b> 必要ないからだ。機体の箱は機体が寝ている角度のまま寝ている
 * （{@link Hitbox} 参照）ので、弾が出会う装甲板は本当にそこにある板になる。射線に向けて振った車体、
 * 土手で機首を上げた戦車、射線から旋回して外れた砲塔。それら全部が幾何計算済みの角度としてここへ届き、
 * 車体を傾ける乗員は「傾斜についての規則を発動させる」のではなく「実際に効く唯一のこと」をしている。
 * ファイルが持つのは導出できない2つの半分だけ——弾の食い込みやすさ（弾側の {@code ricochet}）と装甲板の
 * 良さ（車両側の {@code armour}）。
 *
 * <p>弾かれた弾も弾のままだ。速度の大半とともに価値の大半を失いつつ（{@link #energy} 参照）新しい線上を
 * 飛び続け、弾いた物の後ろや横にある物に当たり得る。弾いた物自身も含めて——前面装甲を滑って砲塔リングへ
 * 入る一撃こそ、戦車を狙った者全員が願っている当たり方。
 */
public final class Ricochet {
    /** 音イベント名の末尾。{@code weapon.<weapon>.ricochet} の形。 */
    public static final String SOUND_ROLE = "ricochet";

    /** 専用の跳弾音を持たない兵装のフォールバック。サーバーが指定する。 */
    public static final ResourceLocation SOUND = ResourceLocation.fromNamespaceAndPath(
            AshVehicles.MODID, WeaponMounts.SOUND_PREFIX + SOUND_ROLE);

    /**
     * 跳弾音の大きさ。兵装の発砲音と同じ尺度で、この数値は音量ではなく到達距離。発砲音より小さいのは意図
     * 的で、砲塔を滑る弾は硬い音ではあっても発砲そのものではないし、最も聞く必要があるのは遠く離れた発射
     * 側だから。
     */
    public static final float VOLUME = 0.9F;
    /** 高めのピッチ。中で何かが炸裂したのではなく装甲板を叩いた音なので。 */
    public static final float PITCH = 1.35F;

    /**
     * 上の2つを、音の送受信両側が読む1つのオブジェクトにまとめた物。
     *
     * <p>サーバーは「どこまで届くか」を、クライアントは「聴き手の位置でどれだけの音量か」を訊く。同じ
     * 数値でなければ、音は間違った音量で届くか、まったく届かない。
     * {@link WeaponDefinition.SoundSetup#packetVolume()} 参照。
     */
    public static final WeaponDefinition.SoundSetup SOUND_SETUP =
            new WeaponDefinition.SoundSetup(Optional.empty(), VOLUME, PITCH);

    /**
     * 1発の弾が弾かれてよい回数。
     *
     * <p>エネルギーの規則ではなく上限であり、計算では終わらせられない唯一の場合を終わらせるためにある——
     * 向かい合った2枚の板の間を滑る弾は速度がある限り行き先があり、各跳ね返りが次を生む分の速度を残す。
     * 2回は誰も気付かない範囲を超えており、走行装置に住み着く弾には遠く及ばない。
     */
    public static final int MOST = 2;

    /** 弾かれる際に弾が保持する速度の割合。 */
    public static final double SPEED_KEPT = 0.55;

    /**
     * 弾かれた弾を飛ばし直す前に、命中判定の余裕からさらにどれだけ外へ出すか（ブロック）。
     *
     * <p>その余裕に「足す」値であり、そうでなければならない。命中を探す判定はどれもまず箱を膨らませる
     * （バニラは1/3ブロック、この MOD もそれに合わせている）ので、表面に置いた弾やその余裕の内側に置いた
     * 弾は、次の tick から見てまだ箱の中にいる。箱の内側から始まる線分は開始点を返すので、弾は同じ板から
     * 同じ場所で、跳ね返り回数を使い切るまで弾かれ続ける。余裕の外へ出せば弾かれる相手が無く、そのまま
     * 去る。
     */
    public static final double CLEARANCE = 0.05;

    /**
     * 「絶対に弾かれない」と「必ず弾かれる」の間の帯の幅（度）。
     *
     * <p>弾自身の角度ちょうどでは確率0、そこからこの幅だけ超えれば確率1、間は線形。両端より帯そのものが
     * 重要だ。帯が無ければ角度は壁になり、そこから1度超えた場所を見つけた砲手はもう戦車ではなく数値と
     * 戦っている。
     */
    private static final double BAND = 12.0;

    /** 跳弾が幾何的な線からどれだけ外れるか。速度に対する割合で。 */
    private static final double SCATTER = 0.06;

    /**
     * 板へ押し込んだ分のうち、跳ね返って出てくる割合。
     *
     * <p>半分をかなり下回る値にして、跳弾が鏡面反射ではなく板に沿うようにする。装甲に弾かれた弾は壁に
     * 当たったボールではない。硬い塊が斜面を滑り、金属へ向いていた速度成分を捨て、板に沿った成分を保つ
     * ——出ていく物はほぼ板の走る方向へ進む。前面装甲からの跳弾がしばしば砲塔へ入る理由でもある。
     */
    private static final double REBOUND = 0.25;

    /** これ未満は方向として扱わず、法線で代用する。 */
    private static final double NOTHING = 1.0E-9;

    private Ricochet() {
    }

    /** 1兵装分の跳弾音イベント。パック側が独自に用意してもよい。 */
    public static ResourceLocation soundFor(ResourceLocation weapon) {
        return weapon.withPath(WeaponMounts.SOUND_PREFIX + weapon.getPath() + "." + SOUND_ROLE);
    }

    /**
     * 弾が板に対してどれだけ直角から外れて当たったか。板自身の法線からの角度（度）。
     *
     * @param velocity 弾の進行方向。単位ベクトルでなくてよい
     * @param normal 板の外向き単位法線。{@link Hitbox#normalAt} から得る
     * @return 直角命中で0、表面に沿う命中で90。何らかの理由で既に離れつつある弾も90（最も食い込まない
     *         角度）
     */
    public static double angle(Vec3 velocity, Vec3 normal) {
        double speed = velocity.length();

        if (speed < NOTHING) {
            return 90.0;
        }

        // 板へ入る向きは法線と逆なので、内積 -1 が直角命中になる。
        double square = -velocity.dot(normal) / speed;

        return Math.toDegrees(Math.acos(Mth.clamp(square, 0.0, 1.0)));
    }

    /**
     * 板がこの弾を、通すのではなく弾くか。
     *
     * <p>2つの値をあらかじめ1つの数にせず別々に受け取るのは、両者の意味が違い、「絶対に無い」と言える方
     * が片方だけだから。ファイルが角度をまったく与えていない弾は、板がどれだけ良くても決して弾かれない
     * ——それは成形炸薬か爆弾で、接触時にすることは炸裂だ。一方の装甲は角度を動かすだけで、角度を0まで
     * 削るほど良い装甲でも、真正面から来た弾は弾けない。その当たりには滑るべき角度が残っていないから。
     *
     * @param round 当たった弾。弾かれるために必要な角度を持っている
     * @param armour 板の価値。その角度から引く度数
     */
    public static boolean thrownOff(Vec3 velocity, Vec3 normal, WeaponDefinition.Projectile round,
            float armour, RandomSource random) {
        if (!round.canRicochet()) {
            return false;
        }

        double off = angle(velocity, normal) - Math.max(round.ricochet() - armour, 0.0);

        return off >= BAND || (off > 0.0 && random.nextDouble() < off / BAND);
    }

    /**
     * 弾かれた弾がどこへ行くか。
     *
     * <p>板に沿っていた速度成分は保ち、板へ向いていた成分は大半が失われ、戻ってくるのはその
     * {@link #REBOUND} 倍。その全体をさらに {@link #SPEED_KEPT} 倍に削る。前面装甲を端から端まで滑った
     * 弾は、到着した時の弾とは別物だから。
     */
    public static Vec3 away(Vec3 velocity, Vec3 normal, RandomSource random) {
        double speed = velocity.length();
        Vec3 thrown = velocity.subtract(normal.scale(velocity.dot(normal) * (1.0 + REBOUND)));
        double scatter = speed * SCATTER;
        Vec3 wandered = thrown.add(random.nextGaussian() * scatter,
                random.nextGaussian() * scatter, random.nextGaussian() * scatter);

        // 板の中へ戻すことは決してしない。散らばりは小さく跳ね返りは板の外を向くので、ここに引っ掛かる
        // のは表面にほぼ完全に沿って弾かれた弾だけ——そしてその弾が行くべき先は表面に沿った方向。
        if (wandered.dot(normal) < 0.0) {
            wandered = wandered.subtract(normal.scale(wandered.dot(normal)));
        }

        if (wandered.lengthSqr() < NOTHING) {
            wandered = normal;
        }

        return wandered.normalize().scale(speed * SPEED_KEPT);
    }

    /**
     * 何度か弾かれた後の弾の価値。砲口を出た時の価値に対する割合。
     *
     * <p>保持した速度だけで決まる。弾は威力を速度として持ち運ぶので、砲塔上面を滑ってからトラックへ入った
     * 弾が、砲から直接来たのと同じ威力でトラックに届いてはいけない。
     */
    public static float energy(int deflections) {
        return (float) Math.pow(SPEED_KEPT, Math.max(deflections, 0));
    }
}
