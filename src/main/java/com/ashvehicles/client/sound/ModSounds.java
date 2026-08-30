package com.ashvehicles.client.sound;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.weapon.Dispenser;
import com.ashvehicles.weapon.Impact;
import com.ashvehicles.weapon.Ricochet;
import com.ashvehicles.weapon.WeaponMounts;

import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;

/**
 * MOD が名前で要求する全ての録音と、そのどれについても問う価値のある唯一の問い。
 *
 * <p>音はコードではない。ここの各名前はどこかのリソースパックの {@code sounds.json} にある、{@code .ogg} を指す
 * エントリだ。MOD 自身が同梱するのは3つ——エンジン音、機関砲、発射——だけで、残りは録音したい人のために開けてある
 * 名前だ。名前が埋まらないままでも何も壊れない。どれも要求前に検索され、応える物の無い名前は、応える名前に差し替え
 * られるか単に鳴らされないかのどちらかだからだ。
 *
 * <p>そのどちらになるかは音の種類による。発砲音・打撃音・炸裂音は短い単発の音であり、無音より認識できる音の方が
 * ましなので、実在する録音——あれば MOD 自身の、無ければゲームの物——へ落ちる。ループ物、つまりモーターと降着装置
 * にはフォールバックが一切無い。ループ用に切っていない録音をループさせるのは、鳴らさないよりはるかに悪いし、ゲーム内
 * にこの2つとしてループ用に切られた音は存在しない。
 *
 * <p><b>追加の仕方。</b>{@code .ogg} を {@code assets/ashvehicles/sounds/} に置き、
 * {@code assets/ashvehicles/sounds.json} でイベントのパス——たとえば {@code weapon.fall}——の下に名前を書く。それで
 * 全部だ。ここを変更する必要は無いし、MOD 自身以外のリソースパックでも同じようにできる。
 *
 * <p>これらにはいずれも「個別版」がある。ある兵装や機体だけ他と違う音にしたいときに録音する物だ。機体には
 * {@code engine.<aircraft>} と {@code gear.<aircraft>}、発砲音には {@code weapon.<weapon>}、兵装のその他の動作には
 * {@code weapon.<weapon>.<role>}。これらが先に探され、機体や兵装のファイルに何も書く必要は無い。{@link #named} 参照。
 */
public final class ModSounds {
    /** {@code engine.<aircraft>}: 機体固有のエンジン音。 */
    public static final String ENGINE_PREFIX = VehicleEntityBase.SOUND_PREFIX;
    /** {@code engine.<aircraft>.afterburner}: バーナー点火音（搭載機のみ）。 */
    public static final String AFTERBURNER_ROLE = AircraftEntity.AFTERBURNER_ROLE;
    /** {@code gear.<aircraft>}: 機体固有の降着装置音。 */
    public static final String GEAR_PREFIX = "gear.";
    /** {@code weapon.<weapon>}、および発砲音以外の全てに使う {@code weapon.<weapon>.<role>}。 */
    public static final String WEAPON_PREFIX = WeaponMounts.SOUND_PREFIX;
    /** {@code rwr.<role>}: 警戒受信機。特定の兵装には属さない。 */
    public static final String RWR_PREFIX = "rwr.";
    /** {@code seeker.<role>}: 乗員自身のシーカー。専用録音を持たない兵装用。 */
    public static final String SEEKER_PREFIX = "seeker.";

    /** 兵装が発砲以外で出す音の役割。名前の末尾として使う。 */
    public static final String FLIGHT_ROLE = "flight";
    public static final String FALL_ROLE = "fall";
    /** {@code weapon.<weapon>.crack}: 弾が通り過ぎる音。{@link BulletSounds} 参照。 */
    public static final String CRACK_ROLE = "crack";
    public static final String RICOCHET_ROLE = Ricochet.SOUND_ROLE;
    /** {@code weapon.<weapon>.impact}: 弾が機体へ命中する音。{@link Impact} 参照。 */
    public static final String IMPACT_ROLE = Impact.SOUND_ROLE;
    /** 兵装自身のシーカーが伝えること。捕捉作業中、保持中、そして喪失。 */
    public static final String SEEK_ROLE = "seek";
    public static final String LOCK_ROLE = "lock";
    public static final String LOST_ROLE = "lost";

    /** 全機体のフォールバック用エンジン音。同梱済み。 */
    public static final ResourceLocation ENGINE = id(ENGINE_PREFIX + "default");
    /**
     * 専用録音を持たない機体のバーナー点火音。同梱していない。エンジン音と違いループではなく短い一発の破裂音なので、
     * 誰かが録音するまでゲーム内にまともなフォールバックがある。{@link AfterburnerSounds} 参照。
     */
    public static final ResourceLocation AFTERBURNER = id(ENGINE_PREFIX + AFTERBURNER_ROLE);
    /**
     * 全機体のフォールバック用降着装置音。ループであり同梱していない。これか専用音のどちらかが提供されるまで、機体は
     * 脚レバー操作時に無音だ。
     */
    public static final ResourceLocation GEAR = id(GEAR_PREFIX + "default");

    /** 銃の発砲音のフォールバック。同梱済み。 */
    public static final ResourceLocation GUN = id(WEAPON_PREFIX + "gun");
    /** モーターを持つ物のフォールバック。ロケットは機関砲のような音ではない。同梱済み。 */
    public static final ResourceLocation LAUNCH = id(WEAPON_PREFIX + "launch");
    /**
     * 兵装がラックを離れる音。爆弾は発射ではなく投下であり、コックピットから聞こえるのは何かの炸裂音ではなくラックが
     * 跳ね上がる音だ。専用録音ができるまでは {@link #LAUNCH} へ落ちる。
     */
    public static final ResourceLocation RELEASE = id(WEAPON_PREFIX + "release");
    /** 地上員がパイロンへ兵装を吊る／外す音。名前はサーバーが指定する。 */
    public static final ResourceLocation LOAD = WeaponMounts.LOAD_SOUND;
    /** 対抗手段ディスペンサーの放出音。名前はサーバーが指定する。 */
    public static final ResourceLocation DECOY = Dispenser.RELEASE_SOUND;

    /** 燃焼中のモーターを外から聞いた音。燃焼している間ずっと。ループであり同梱していない。 */
    public static final ResourceLocation FLIGHT = id(WEAPON_PREFIX + FLIGHT_ROLE);
    /** 重力だけで落下する物が出す、高まっていく風切り音。ループであり同梱していない。 */
    public static final ResourceLocation FALL = id(WEAPON_PREFIX + FALL_ROLE);
    /**
     * 弾が通り過ぎる音。ここで空中にある物のうち唯一ループでない物だ。通過の瞬間の短い破裂音1つなので、まともな
     * フォールバックがある。同梱していない。それまではゲーム自身の空振り音が代役を務める。{@link BulletSounds} 参照。
     */
    public static final ResourceLocation CRACK = id(WEAPON_PREFIX + CRACK_ROLE);

    /** MOD 自身の炸裂音。距離に応じて時刻と形を整える {@link BlastSounds} 参照。 */
    public static final ResourceLocation BLAST = id(WEAPON_PREFIX + "blast");

    /**
     * 弾が装甲へ食い込まず滑って跳ねる音。専用の金属音を録音していない全兵装用。名前はサーバーが指定する。同梱して
     * いないので、誰かが録音するまではゲーム自身の金属衝突音へ落ちる。{@link Ricochet} 参照。
     */
    public static final ResourceLocation RICOCHET = Ricochet.SOUND;

    /**
     * 弾が装甲を跳ねずに食い込む音。専用の命中音を録音していない全兵装用。名前はサーバーが指定する。同梱していない
     * ので、誰かが録音するまではゲーム自身の金床設置音へ落ちる。{@link Impact} 参照。
     */
    public static final ResourceLocation IMPACT = Impact.SOUND;

    /**
     * 警戒受信機のトーン。短いビープ1つを、事態の深刻さに応じた速さで繰り返す。
     *
     * <p>意図的にループではなくビープにしてある。パイロットに危険度を伝えるのは<em>頻度</em>だ——同じ音が毎秒2回なら
     * 何かがこちらを見ており、毎秒5回ならそれが向かってきている——ので、録音すべきは短い音1つだけでよい。おかげで
     * 録音ができるまでのまともなフォールバックも用意できる。{@link com.ashvehicles.client.sound.WarningSounds} 参照。
     *
     * <p>受信機が伝えるべき事柄1つにつき名前1つ。パックが各々に専用の音を与えられるようにするためだ。録音されなかった
     * 物は録音済みの物から借りるし、何も録音していない受信機でもビープは鳴る——ゲーム自身の音符ブロックを、危険度に
     * 応じたピッチで鳴らす。
     *
     * <p>これは誰かのレーダーがこちらを見つけた状態。一鳴りの後、もっと悪い事態になるまで沈黙する。
     */
    public static final ResourceLocation RWR_CONTACT = id(RWR_PREFIX + "contact");
    /** 誰かのシーカーがこちらを捉えた。毎秒2回鳴らす。 */
    public static final ResourceLocation RWR_LOCK = id(RWR_PREFIX + "lock");
    /** 何かがレールを離れてこちらへ向かっている。毎秒5回鳴らす。 */
    public static final ResourceLocation RWR_MISSILE = id(RWR_PREFIX + "missile");

    /**
     * 乗員自身のシーカー。受信機を逆向きにした物だ。誰かがこちらを捉えたのではなく、自分のミサイルが見えている物を
     * 告げる。{@link SeekerSounds} 参照。
     *
     * <p>名前が3つあるのは、ロックが3つの瞬間から成るからだ。これはシーカーが何かに取り組んでいてまだ捉えきって
     * いない段階。うなり音で、作業中ずっとループし、<b>ここで唯一、切られた音程ちょうどでは再生されない録音</b>だ
     * ——ロックが閉じるにつれて少し上がる。計器ではなく目標を見ているパイロットに進行度を伝えるのがそれだ。録音値の
     * 上下に手のひら幅程度なので、聞かせたい音程で切っておけば上昇は自ずと収まる。4秒ループとして同梱済み。
     */
    public static final ResourceLocation SEEKER_SEARCH = id(SEEKER_PREFIX + "search");
    /** シーカーが捉えた。ロックが続く間だけ正確に鳴り続ける定常音。同梱済み。 */
    public static final ResourceLocation SEEKER_LOCK = id(SEEKER_PREFIX + LOCK_ROLE);
    /**
     * 得ていたロックが外れた。短い音1つの後、再びうなりか沈黙になる。
     *
     * <p>同梱しておらず、3つのうち他からフォールバックできない唯一の物だ。上の2つはループであり、また始まったうなり
     * の上にループを1回重ねても短い音にはならない。だから誰かが録音するまではゲーム自身の音を使う。
     * {@link SeekerSounds} 参照。
     */
    public static final ResourceLocation SEEKER_LOST = id(SEEKER_PREFIX + LOST_ROLE);

    /** 特定の機体や兵装の名を冠したイベント。{@code <namespace>:<prefix><name>}。 */
    public static ResourceLocation named(ResourceLocation subject, String prefix) {
        return subject.withPath(prefix + subject.getPath());
    }

    /** 同じ物を、兵装が出す複数の音のうち1つに対して。{@code weapon.<name>.<role>}。 */
    public static ResourceLocation named(ResourceLocation subject, String prefix, String role) {
        return subject.withPath(prefix + subject.getPath() + "." + role);
    }

    /**
     * これらのうちリソースパックが実際に提供する最初の物。どれも提供していなければ null。
     *
     * <p>両半分とも重要だ。定義はあるがファイルが見つからないイベントは「無い」と数える。それが、綴りを誤ったパスを
     * 無音ではなく聞こえる音へ落とす仕組みだ。そして null は失敗ではなく答えである。ループにはまともなフォールバック
     * が無く、鳴らさないことが正しい対処だからだ。
     */
    @Nullable
    public static ResourceLocation firstPresent(SoundManager sounds, ResourceLocation... chain) {
        for (ResourceLocation id : chain) {
            if (exists(sounds, id)) {
                return id;
            }
        }

        return null;
    }

    /** リソースパックがこのイベントを定義し、そのファイルが1つ以上見つかったら true。 */
    public static boolean exists(SoundManager sounds, ResourceLocation id) {
        WeighedSoundEvents weighed = sounds.getSoundEvent(id);

        return weighed != null && weighed.getWeight() > 0;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, path);
    }

    private ModSounds() {
    }
}
