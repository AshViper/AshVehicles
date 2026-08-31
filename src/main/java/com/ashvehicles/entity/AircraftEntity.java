package com.ashvehicles.entity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Optional;

import javax.annotation.Nullable;

import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.vehicle.Hitbox;

import com.ashvehicles.vehicle.VehicleShape;
import com.ashvehicles.client.model.AircraftAnimations;
import com.ashvehicles.particle.TintedParticleOption;
import com.ashvehicles.sensor.Sensors;
import com.ashvehicles.registry.ModParticles;
import com.ashvehicles.item.EquipmentItem;
import com.ashvehicles.item.FuelItem;
import com.ashvehicles.item.RackItem;
import com.ashvehicles.item.WeaponItem;
import com.ashvehicles.item.WrenchItem;
import com.ashvehicles.weapon.Dispenser;
import com.ashvehicles.weapon.TargetLock;
import com.ashvehicles.weapon.EquipmentDefinition;
import com.ashvehicles.weapon.GunStations;
import com.ashvehicles.weapon.WeaponMounts;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * MOD 内の全固定翼機に共通する挙動。簡略化した飛行モデル、座席、そして機体を黒煙の穴に変えるダメージ処理。
 *
 * <p><b>物理が走る場所。</b>バニラのボート同様、航空機は「操作している側」がシミュレートする。プレイヤーが
 * 操縦桿を握っている間は操縦クライアント、それ以外はサーバー。判定は {@link #isControlledByLocalInstance()}。
 * 操縦クライアントの位置はバニラの ServerboundMoveVehiclePacket でサーバーへ送られ、ヨーとピッチも同梱される。
 * バンク角とスロットルにはバニラの対応物が無いのでクライアントが
 * {@link com.ashvehicles.network.AircraftInputPayload} で送り、サーバーが同期データへ複製して他全員へ渡す。
 */
public class AircraftEntity extends VehicleEntityBase implements GeoEntity {
    /** エンジン出力設定（0〜1）。他クライアントがエンジンのアニメーションを回せるよう同期する。 */
    private static final EntityDataAccessor<Float> DATA_THROTTLE =
            SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.FLOAT);
    /**
     * アフターバーナーの出力（0〜1）。操縦している側から送られる。
     *
     * <p>float 1つとしては珍しく3つの理由で同期している。機体が見える全クライアントはノズルからのプルームを
     * 描きエンジン音のピッチを上げるが、どちらも他の送信データからは算出できない。さらにサーバーは誰が見ていよう
     * と必要とする。バーナーの本当の代償は熱であり、シーカーがこの機体をどこまで見えるかはサーバー側で決まる。
     * {@link #reportAfterburner} 参照。
     */
    private static final EntityDataAccessor<Float> DATA_AFTERBURNER =
            SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.FLOAT);
    /**
     * 機体の指向を回転として保持。Minecraft はエンティティに方位と仰角しか与えず、宙返り頂点で背面になった機体を
     * 表現できない。よって本当の姿勢はここが持ち、バニラの角度は後ろで追随させる。
     */
    private static final EntityDataAccessor<Quaternionf> DATA_ATTITUDE =
            SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.QUATERNION);
    /**
     * 機体の実速度（ブロック/tick）。操縦している側から送られる。
     *
     * <p>飛行モデルを回すのは1台だけで、他のコピーは位置の流れから機体を描くしかない。その流れから速度を綺麗に
     * 逆算することはできない——更新は平均で1tickに1回届くが、正確に1tickごとではないので、2つの差は同期していない
     * 3つの時計のずれを速度として読んでしまう。値の送信コストは1tickあたりfloat 3個で、推測を完全に排除できる。
     * {@link AircraftInterpolation} 参照。
     */
    private static final EntityDataAccessor<Vector3f> DATA_VELOCITY =
            SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.VECTOR3);
    /**
     * 自機軸周りの角速度（ラジアン/tick）。同じ理由・同じずれ対策。3つの角度ではなくスケール済み軸ベクトルで
     * 書くことで加法性を保ち、独自の継ぎ目を持たない——{@link Attitude#rotationVector} 参照。
     */
    private static final EntityDataAccessor<Vector3f> DATA_BODY_RATE =
            SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.VECTOR3);
    /** 降着装置のセレクタ。脚は {@link #getGearCycleTicks()} tick かけて動く。 */
    private static final EntityDataAccessor<Boolean> DATA_GEAR_DOWN =
            SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.BOOLEAN);
    /**
     * 揚力系のセレクタ（装備機のみ）。ノズルはファイルが指定する転換時間をかけて動く。
     */
    private static final EntityDataAccessor<Boolean> DATA_VTOL =
            SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.BOOLEAN);
    /** フラップのセレクタ。下げれば低速で揚力を得るが、どの速度でも抗力を払う。 */
    private static final EntityDataAccessor<Boolean> DATA_FLAPS_DOWN =
            SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.BOOLEAN);
    /**
     * ハードポイントの搭載物と、トリガーの選択先。機体の保存に使うのと同じタグで送る。計器が必要とし、搭載済み
     * パイロンにポッドを描くレンダラーも必要とするからだ。
     */
    private static final EntityDataAccessor<CompoundTag> DATA_WEAPONS =
            SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.COMPOUND_TAG);
    /**
     * 砲座の残弾と、砲が今向いている方向。パイロンと同じ扱いで、同じ理由による——射手の計器が両方を必要と
     * する。弾がどこへ行くかを描くのに向きが要り、撃つ前に知りたい数値が残弾だ。{@link GunStations} 参照。
     */
    private static final EntityDataAccessor<CompoundTag> DATA_STATIONS =
            SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.COMPOUND_TAG);
    /**
     * ターゲティングポッドが捉えている対象のエンティティID。指示された地点に立つ
     * {@link DesignationEntity マーカー}か、パイロットが車両を指示したならその車両自身。何も無ければ {@code -1}。
     *
     * <p>両側が必要とし、どちらも算出できないので同期する。所有者はサーバー——レーザー誘導兵器がレールを離れる
     * ときに渡される値だ——で、パイロットのクライアントは地上にマークを描きポッドカメラをそこへ向ける。
     * {@link #designate} 参照。
     */
    private static final EntityDataAccessor<Integer> DATA_DESIGNATED =
            SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.INT);
    /**
     * 機体の残存耐久力（HP）。
     *
     * <p>サーバーだけの問題ではないので同期する。パイロットの計器が表示するし、撃たれた機体は見ている全員に
     * とって同じ機体だ。値の所有者はサーバーで、クライアントは最後に通知された値を読む。
     */
    /**
     * 機上に残るフレアとチャフの数。専用パケット無しで計器が読めるよう同期する。変更するのはサーバーだけ。
     * {@link Dispenser} 参照。
     */
    private static final EntityDataAccessor<Integer> DATA_FLARES =
            SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_CHAFF =
            SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.INT);

    /**
     * 下向き加速度（ブロック/tick^2）。1ブロック=1m、1tick=1/20秒なので、これは 9.81 m/s^2 ——Minecraft の重力
     * ではなく実際の重力だ。機体は実機の諸元で作ってあり、その諸元は本物の重力を前提にしている。
     */
    protected static final double GRAVITY = 0.02453;
    /**
     * ホバリング減衰を失速速度のどれだけの範囲に広げるか。
     *
     * <p>1/4——歩く程度の速度——で、この値は見た目より重要だ。減衰はホバリング中の機体が置かれた場所から流される
     * のを防ぐためにあり、どこへも行かせないためではない。失速速度全域に広げると後者もやってしまい、失速速度に
     * 達せない機体は決して主翼へ荷重を渡せない——燃料が尽きるまで、この場合は永久にホバリングし続ける。ここまで
     * 下げておけば、流れは収まり、意図的な加速は歩行速度に達した時点で干渉を受けなくなる。
     */
    private static final double HOVER_BAND = 0.25;
    /** これを超えると主翼が機体を支えていることにならないノズル角（度）。 */
    private static final float HOVERING_ANGLE = 30.0F;
    /**
     * ローター全速・コレクティブ0のヘリの音量を、作動中のそれと比べた比率。
     *
     * <p>意図的に高くしてある。ヘリの騒音の大半は回っているローターであり、コレクティブが生む差はその下で
     * エンジンが荷重を引き受ける分だ。フライトアイドルの機体は静かなのではなく、単に引いていないだけ。
     */
    private static final float ROTOR_IDLE_NOTE = 0.75F;

    /**
     * 当たり判定の箱がどれだけブロック内部に入ったら機体が埋没扱いになるか（ブロック）。翼端が斜面へ手のひら幅
     * 分入っているだけの機体を「山に埋められた機体」と誤判定しないよう、余裕を持たせてある。
     */
    private static final double EMBEDDED_MARGIN = 0.25;

    /** 機体の設計耐G を超えた1G あたり、1tick に受ける機体損傷。 */
    private static final float OVER_G_DAMAGE = 4.0F;
    /** これを超えると翼端が蒸気を曳き始める荷重倍数。 */
    private static final float VORTEX_LOAD = 2.5F;
    /** 機体原点から主翼までの高さ。パーティクル用の概算値。 */
    private static final double WING_HEIGHT = 1.5;
    /** 凝結は水と光なので、翼のどこにできても同じ淡い塊になる。 */
    private static final int VAPOUR_COLOUR = 0xF2F5F7;
    /** ベイパーコーンが発生する最高速度に対する割合。 */
    private static final double VAPOUR_SPEED = 0.88;
    private static final double VAPOUR_RADIUS = 3.0;
    private static final double VAPOUR_AHEAD = 2.0;

    /**
     * レバーが既にストッパーに当たった状態でスロットルを開き続け、バーナーが点火するまでのtick数。
     *
     * <p>これがデテントであり、この操作の全てだ。実機のスロットルクアドラントにはミリタリー推力全開の位置に
     * 物理的なゲートがあり、パイロットはレバーをそこで持ち上げねばならない。前へ押しただけでアフターバーナーに
     * 入り後から気付く、という事態を防ぐためだ。ここでは指の下に段差を作れないので、代わりに時間を段差にする。
     * エンジンの持ち分以上を要求し続ける0.75秒——パイロットが全開まで動かす一瞬よりはるかに長く、耐え忍ぶ対象に
     * なるほどは長くない。
     */
    private static final int GATE_TICKS = 15;
    /** これを超えるとバーナー点火と見なすアフターバーナー出力。計器・プルーム・音の判定用。 */
    private static final float LIT = 0.05F;
    /** 炎そのもの。白で始まりこの色に落ち着く。 */
    private static final int PLUME_COLOUR = 0xFFA33C;
    /** その後ろの熱気。プルームの長さを作る。 */
    private static final int EXHAUST_COLOUR = 0x8C8478;
    /** ノズルの後方どこまでプルームを描くか（全開時のブロック数）。 */
    private static final double PLUME_LENGTH = 4.0;
    /** 排気管からの噴出速度。機体自身の速度に上乗せするブロック/tick。 */
    private static final double PLUME_SPEED = 0.9;
    /** ノズル出口での炎の幅（ブロック）。 */
    private static final float PLUME_SIZE = 0.85F;
    /** 全開時、ノズル1つ・1tickあたりに出す2層それぞれの粒数。 */
    private static final int PLUME_PUFFS = 2;
    /** 粒が軸からどれだけ外れて出てよいか。柱が線に見えるのを防ぐ。 */
    private static final double PLUME_SCATTER = 0.12;
    /**
     * バーナー点火音の、発生地点での音量とピッチ。
     *
     * <p>兵器の発射音と違い、volume 欄を到達距離として使うのではなく本来の音量として使う。これは機体が出す音で
     * あり、機体には既にファイルが指定する距離まで届くエンジン音がある。クライアントが代替録音を同じ音量で鳴らす
     * ために値を知る必要があるので public。{@code AfterburnerSounds} 参照。
     */
    public static final float AFTERBURNER_VOLUME = 1.0F;
    public static final float AFTERBURNER_LIGHT_PITCH = 1.0F;
    /**
     * {@code engine.<aircraft>.afterburner}: バーナーの点火音。
     *
     * <p>発生を判断するのはサーバーなのでこちら側で名前を決める。ただしサーバーにできるのは名前を指すことだけだ
     * ——リソースパックはクライアントが持つ物で、サーバーは見たことがない。実際にどの録音が鳴るかは向こうで
     * 決まる。{@code AfterburnerSounds} 参照。
     */
    public static final String AFTERBURNER_ROLE = "afterburner";

    /** 迎角リミッターが効き始める点。保持する限界値に対する割合。 */
    private static final float ALPHA_LIMITER_BITE = 0.6F;
    /**
     * 昇降舵が機首を押さえる力がフェードインし始める、ローテーション速度に対する割合。滑走の終盤で機体が軽く
     * なる形にし、操縦桿が段階的に急に効き出さないようにする。
     */
    private static final double ROTATION_FADE = 0.25;
    /**
     * 車輪が機体の下にあると言えるための、水平姿勢への近さ。揚力方向の鉛直成分なので、1が完全水平、0が横倒し。
     */
    private static final double UPRIGHT = 0.5;
    /**
     * 地上速度に関わらず降着装置が吸収できる降下率。許容接地速度に対する割合。着陸ではなく滑走の判定基準になる。
     * これを超えると機体は車輪で接地できるだけの低速でなければならず、<em>それ</em>も超えると脚が出ていても
     * 意味は無い——着陸ではなく、脚を出したまま地面に衝突しているだけだ。
     */
    private static final double TOUCHDOWN_SINK = 0.25;
    /** 尾部が滑走路を擦る前に車輪が許す機首上げ姿勢。 */
    private static final float GROUND_PITCH_LIMIT = 15.0F;
    /** 降着装置が機体を地面の線へ引き戻す強さ（1tickあたり）。 */
    private static final float GROUND_LEVELLING = 0.25F;
    /**
     * 降着装置が沿う地面の傾きの上限（度）。これを超える斜面は車輪が寝る相手ではなく、機体が突っ込む相手だ。
     * 上限があることで、プローブの読み違え1つが機体を機首で立たせることもない。
     */
    private static final float GROUND_SLOPE_LIMIT = 30.0F;
    /** 車輪下の地面を探すトレースの、機体原点からの上下の伸び（ブロック）。原点は車輪の位置にある。 */
    private static final double GROUND_PROBE_ABOVE = 1.5;
    private static final double GROUND_PROBE_BELOW = 3.0;
    /**
     * 気流がパイロットに渡しうる操舵権限の上限。失速速度での値の倍数で表す。権限は速度ではなく動圧に追従する
     * ようになったので——舵面が実際に相手にしているのはそれだ——これは旧上限の2乗であり、失速速度の1.5倍で
     * ちょうど一致する。
     */
    private static final double AUTHORITY_CEILING = 2.25;

    /**
     * パイロットの舵に必ず残る効き。ファイルの角速度に対する割合。
     *
     * <p>操舵権限は動圧に比例するので、速度が落ちれば0へ向かう。空力としては正しく、遊びとしては壊れている
     * ——落ちていく機体で操縦桿を引いても何も起きないのは、パイロットには「機体が言うことを聞かない」では
     * なく「ゲームが反応しない」と読める。半分を床にすれば、どれだけ遅くても機体を向け直す手段は残る。
     *
     * <p>これが効くのは低速側だけだ。失速速度の付近で既に1を超え、コーナー速度では2倍あるので、ここが仕事を
     * するのは失速速度のおよそ7割より下——つまり、そもそも主翼が飛んでいない領域に限られる。
     */
    private static final float TURN_RATE_FLOOR = 0.5F;

    /**
     * 全機体の操舵効きに掛かる倍率。機動性そのものを1つの数値で上下させるための物。
     *
     * <p>ファイル側の {@code pitch_rate} などを13機分書き換えるのではなくここに置いてあるのは、これが
     * 「この機体は他よりよく曲がる」ではなく「この MOD の機体はどれくらい曲がる物か」という問いだからだ。
     * 機種同士の相対関係——重い迎撃機と軽い戦闘機の差——はファイルが持ち、その全体の水準をここが決める。
     * 片方をもう片方で表そうとすると、全体を動かすたびに機種間の釣り合いを組み直す羽目になる。
     *
     * <p>1.0 が調整前の値。0.7 はそこから3割落とした値で、旋回にはっきり時間がかかるようになるが、
     * 機首が動かないとは感じない範囲。速度域を問わず一律に効く。
     */
    private static final float CONTROL_SCALE = 0.7F;
    /**
     * どれだけ高く上げても空気がこれより薄くならない下限。海面値に対する倍数。推力も揚力も空気に追従するので、
     * 下限が無いと十分高く上げた機体は両方を失い、上昇限度であるべき物が落とし穴になる。
     */
    private static final double THINNEST_AIR = 0.35;
    /** 空気密度の基準高度。ファイルの諸元が示されている高度でもある。 */
    private static final double DENSITY_DATUM = 64.0;
    /**
     * 空気密度が半減する高度差。
     *
     * <p>実大気を Minecraft の世界にスケールした場合より意図的に緩くしてある。空気が薄くなる意義は、機体が無限に
     * 上昇せず上昇限度を持つことだ。通常の飛行で到達するほど低い限度にすると、得る物より失う物が多い。しかも
     * それが最も響くのは最も反論できない機体——ホバリング中の揚力系は差を埋める速度を持たないので、急な勾配は
     * F-35B が梢より上でホバリングすること自体を禁じてしまう。
     */
    private static final double DENSITY_SCALE = 512.0;
    /**
     * パイロットのクライアントが自機速度を報告したとき信用する上限（ブロック/tick）。機体が到達しうる値から
     * 十分離してあり、この値を使って機関砲弾を世界の果てまで投げられないようにするためにある。
     */
    private static final double MAX_PILOT_SPEED = 40.0;
    /** パイロンの箱の大きさ（ブロック）。狙える程度に大きく、隣と区別できる程度に小さく。 */
    public static final double PYLON_BOX = 1.2;
    /** ステーションが密な機体で、どこまで小さくなることを許すか。 */
    private static final double SMALLEST_PYLON_BOX = 0.5;
    /** これ未満なら停止扱いとする速度の2乗。 */
    private static final double PARKED_SPEED = 1.0E-4;
    /**
     * 残骸が落下中、1tickごとに速度をどれだけ保つか。焼け落ちた機体は空気を切って飛ぶ翼ではなく空気中を落ちる
     * 塊なので、この1つの値が空力全体の代役を務める。全損機が対気速度を地面まで持ち込むのを止める程度には効き、
     * 支える程には効かない。
     */
    private static final double WRECK_DRAG = 0.99;
    /**
     * 接地後にどれだけ保つか。
     *
     * <p>高い値。飛行速度で到達した機体は接地点で止まらず、地面を掘り進むからだ。距離を決めるのがこの値で、
     * 残骸はおおよそ接地速度の {@code 1 / (1 - this)} 倍だけ滑る。水平に降りた高速ジェットは静止までに野原を
     * かなり進み、真下に落ちたヘリは持ち込む速度が無いので一切進まない。大抵は計算より先に地形が決着させる。
     */
    private static final double WRECK_FRICTION = 0.92;

    private final AnimatableInstanceCache animatableCache = GeckoLibUtil.createInstanceCache(this);
    /** 機体の搭載物。サーバー側が正であり、クライアント側はタグの複製。 */
    private final WeaponMounts weapons = new WeaponMounts(this);
    /** 乗員が撃つ砲。持たない機体では毎tick何もしない。 */
    private final GunStations stations = new GunStations(this);
    /**
     * この機体のポッドが地上に置いたマーカー（置いている間のみ）。サーバー限定で、クライアントは他と同様
     * {@link #DATA_DESIGNATED} 経由で知る。
     */
    @Nullable
    private DesignationEntity marker;
    /** フレアとチャフ、および次弾を放出できるようになる時刻。 */
    private final Dispenser dispenser = new Dispenser(this);

    private AircraftInput input = AircraftInput.NONE;
    private float throttle;
    /**
     * レバーの要求値に対し、エンジンが実際に出している出力。一致させず追従させる。エンジンはスプールするからだ。
     * レバーを一気に前へ倒して始めた離陸滑走は、飛び出すのではなくゆっくり始まって伸びるべきだ。
     */
    private float thrustLevel;
    /**
     * パイロットがレバーをゲートの向こうへ押し込んだか。押しっ放しのキーではなくラッチ。スロットルは置いた位置に
     * 留まり、アフターバーナーから抜けるには引き戻す。
     */
    private boolean reheatCommanded;
    /** バーナーの実出力（0〜1）。点火は速いが、段階的に一気には点かない。 */
    private float reheat;
    /** レバーをストッパーに当て続けたtick数。{@link #GATE_TICKS} へのカウント。 */
    private int gateHeld;
    /**
     * 機体重量のうち車輪がまだ支えている割合。1なら全て車輪、0なら全て主翼。地面摩擦をこれで拡縮するので、
     * 離陸滑走の終盤は浮上の瞬間まで食い付き続けるのではなく、主翼が引き受けるにつれて軽くなる。
     */
    private float weightOnWheels = 1.0F;
    /** 直前1tickの方位変化。パイロットの視界が機体と共に回るよう渡す。 */
    private float deltaRotation;
    // 角速度（度/tick）。tickをまたいで保持し、操縦に重みを持たせる。
    private float pitchVelocity;
    private float rollVelocity;
    private float yawVelocity;
    /** 主翼が気流に対して成す角（度）。失速角を超えると厄介なことになる。 */
    private float angleOfAttack;
    /** 機体が高速で何かに当たったとき、物理を回した側が設定する。 */
    private boolean crashing;
    /**
     * 前回の自己移動で機体が実際に進んだ距離（要求距離ではなく）。
     *
     * <p>読むのは残骸だけで、残骸が運動量を地面へ持ち込めるのはこれが全てだ。デルタ移動では代用できない。
     * {@code move} は阻まれた軸を取り除くので、400ノットで野原へ突っ込んだ機体もtick終了時の速度は0——エプロンに
     * 駐機していた機体と同じ値になる。両者で違うのは実際にどれだけ進んだかであり、それをここで測る。
     */
    private Vec3 lastTravel = Vec3.ZERO;
    /** 飛行中この機体が開いたまま保持しているチャンク（あれば）。 */
    private Set<ChunkPos> heldChunks = Set.of();
    /** 操縦していないクライアントでこの機体をどう描くか。 */
    private final AircraftInterpolation interpolation = new AircraftInterpolation();
    /** パイロットのクライアントが申告する速度。飛行中、サーバーが持つ唯一の正直な答え。 */
    private Vec3 pilotVelocity = Vec3.ZERO;
    /**
     * サーバー側: 最後の更新が届いた時点の姿勢。以降の旋回量を、サーバー自身の1tickではなく操縦側の丸1tickに
     * 対して測れるようにする。
     */
    private final Quaternionf ratedAttitude = new Quaternionf();
    private boolean hasRatedAttitude;
    // クライアント限定: 世界が行く手を遮っているかの最後の判定結果と、その算出時刻。線のトレースには
    // コストがあり、1tick内で答えはほとんど変わらない。
    /** 降着装置の展開度。0が上げロック、1が下げロック。 */
    private float gearProgress = 1.0F;
    private float gearProgressO = 1.0F;
    /** ノズルの振れ量。0が格納、1が完全下向き。 */
    private float vtolProgress;
    private float vtolProgressO;
    /** フラップの作動量。0が格納、1が全下げ。 */
    private float flapsProgress;
    private float flapsProgressO;
    /**
     * 可変翼の後退量。0が全開前進、1が全開後退。可変翼を持たない機体では常に0。
     *
     * <p>ローターと同じく送信せず各側で算出する。依存するのは対気速度1つだけで、それは既に全側が持っている
     * ——{@link #getVelocity()} は操縦側でも、サーバーでも、見物人のクライアントでも正直な答えを返す。全員が
     * 同じ数値に同じ関数を当てるのだから、翼の位置を伝えるためにパケットを使う理由が無い。
     */
    private float sweepProgress;
    private float sweepProgressO;
    /**
     * ローターの回転数。定格に対する割合で、ローターを持たない機体では0。
     *
     * <p>送信せず各側で算出する。依存する物を全側が既に知っているからだ。ローターは誰かが操縦席にいる間に回転を
     * 上げ、無人になれば下がる。誰が乗っているかは搭乗者情報として同期済み。車輪の回転速度を伝えるためだけに
     * 1tickに1パケット使うのは無駄だ。
     */
    private float rotorSpeed;
    private float rotorSpeedO;
    /**
     * ローターの現在角（度）。描画専用で、描画する側で積分する。
     *
     * <p>テールローターはメインローターからの拡大ではなく別々に数える。数倍速く回り、両者は都合のよい角度で同期
     * し直したりしないので、メインから算出したテール角はメインが1回転内に折り返されるたび跳ねてしまう。
     */
    private float rotorAngle;
    private float rotorAngleO;
    private float tailAngle;
    private float tailAngleO;

    // 自分でこの機体をシミュレートしていない側のための補間状態。
    private int lerpSteps;
    private double lerpX;
    private double lerpY;
    private double lerpZ;
    private double lerpYRot;
    private double lerpXRot;

    public AircraftEntity(EntityType<? extends AircraftEntity> type, Level level) {
        super(type, level);
        // 他と同様に視錐台でカリングする。ゴースト開始距離を超えるとゲームのエンティティループ自体が降板し、
        // ゴーストパスがスナップショットから機体を描くので、視錐台の遠方面が捨てる物はもう無い。
        // com.ashvehicles.client.ghost.GhostRenderDispatcher 参照。
        this.blocksBuilding = true;
        // 最初のtickではなくここで構築する。レベルはエンティティ追加時にそのパーツを記録するので、まだ
        // パーツを持たないエンティティは「無し」として覚えられてしまう。
        this.buildParts();
        // 新造機体は無傷。ワールドから読み戻した機体はタグでこれを上書きし、クライアントには他の同期データと
        // 共に実値が届く。
        this.setHealth(this.getMaxHealth());
        // 新造機は無傷の機体と同様、弾倉も満載で出てくる。
        this.setCountermeasures(true, this.getStats().countermeasures().flares());
        this.setCountermeasures(false, this.getStats().countermeasures().chaff());
        // タンクも同様。工場から空で出てくる機体は、置いた場所から動かせない置物だ。
        this.setFuel(this.fuelSetup().capacity());
        // 重力は flightTick() で自前に積分している。サーバーへそう伝えることで、飛行中の機体が浮遊エンティティ
        // 扱いされるのも防げる。さもないと離陸4秒後に「flying is not enabled on this server」でキックされる。
        this.setNoGravity(true);
    }

    // ------------------------------------------------------------------
    // 飛行特性。機体のデータパックファイルから読む。
    //
    // 毎回引かずに保持するが、ファイルが変わらない間だけ。Definitions が別バージョンを報告した瞬間に複製を捨てる
    // ので、既に飛行中の機体にも /reload が効く。フィールド2つ分の価値はある。機体は1tickに数十回自分の諸元を
    // 問い合わせる——飛行モデルだけで十数レコードを読む——うえ、1回ごとにエンティティレジストリの逆引きで名前を
    // 組み、その名前のハッシュとマップ検索を行っていた。1tickに1回で十分だ。
    // ------------------------------------------------------------------

    /** その名前の諸元と形状、およびその出所のファイル群。 */
    @Nullable
    private AircraftDefinition stats;
    @Nullable
    private int statsVersion = -1;

    /**
     * この機体のID（エンティティタイプのID）。ファイル・モデル・設置アイテムまで、機体に関する他の全てが同じ
     * 名前で見つかる。
     */
    public ResourceLocation getAircraftId() {
        return this.getVehicleId();
    }

    public AircraftDefinition getStats() {
        AircraftDefinition current = this.stats;

        if (current == null || this.statsVersion != Definitions.version()) {
            current = Definitions.AIRCRAFT.get(this.getAircraftId());
            this.stats = current;
            this.statsVersion = Definitions.version();
        }

        return current;
    }

    /** スロットル全開時の機首方向加速度（ブロック/tick^2）。 */
    public float getMaxThrust() {
        return this.getStats().engine().maxThrust();
    }

    /** スロットルキー押下中の1tickあたりのスロットル変化量。 */
    public float getThrottleRate() {
        return this.getStats().engine().throttleRate();
    }

    /** この機体がアフターバーナーを持つか。大半は持たない。 */
    public boolean hasAfterburner() {
        return this.getStats().engine().afterburner().isPresent();
    }

    /** アフターバーナーの出力（0〜1）。 */
    @Override
    public float getAfterburner() {
        return this.reheat;
    }

    /** バーナーが点火しているか。計器・プルーム・音が全てこれを読む。 */
    public boolean isAfterburning() {
        return this.reheat > LIT;
    }

    /** 速度の絶対上限（ブロック/tick）。 */
    public float getMaxSpeed() {
        return this.getStats().wing().maxSpeed();
    }

    /** これを下回ると主翼が食い付かなくなり操縦が甘くなる対気速度。 */
    public float getStallSpeed() {
        return this.getStats().wing().stallSpeed();
    }

    /** 対気速度 (ブロック/tick)^2 あたりの揚力。これが重力と釣り合う所で水平飛行になる。 */
    public float getLiftCoefficient() {
        return this.getStats().wing().lift();
    }

    /** 毎tick失う現在速度の割合。 */
    public float getDragCoefficient() {
        return this.getStats().wing().drag();
    }

    /** 舵一杯・操舵権限全開時のピッチ角速度（度/tick）。 */
    public float getPitchRate() {
        return this.getStats().handling().pitchRate();
    }

    /** 舵一杯・操舵権限全開時のロール角速度（度/tick）。 */
    public float getRollRate() {
        return this.getStats().handling().rollRate();
    }

    /** 方向舵のみによるヨー角速度（度/tick）。 */
    public float getYawRate() {
        return this.getStats().handling().yawRate();
    }

    /** これを超えて何かに当たると機体が全損する衝突速度（ブロック/tick）。 */
    protected float getCrashSpeed() {
        return this.getStats().airframe().crashSpeed();
    }

    /**
     * どう飛んできたかに関わらず車輪での接地が生存可能な速度（ブロック/tick）。ファイル指定が無ければ固定翼機
     * 200km/h、ヘリ 50km/h。
     */
    protected float getLandingSpeed() {
        return this.getStats().landingSpeed();
    }

    @Override
    protected float explosionPower() {
        return this.getStats().airframe().explosionPower();
    }

    /** 降着装置が上げロックから下げロックまで動くのに要するtick数。 */
    public int getGearCycleTicks() {
        return this.getStats().landingGear().cycleTicks();
    }

    /** 脚を出したときの追加抗力。クリーン形態の値に対する割合。 */
    protected float getGearDragPenalty() {
        return this.getStats().landingGear().dragPenalty();
    }

    protected int getFlapsCycleTicks() {
        return this.getStats().flaps().cycleTicks();
    }

    /** フラップ全下げによる追加揚力。クリーン翼の値に対する割合。 */
    protected float getFlapsLiftBonus() {
        return this.getStats().flaps().liftBonus();
    }

    protected float getFlapsDragPenalty() {
        return this.getStats().flaps().dragPenalty();
    }

    // ------------------------------------------------------------------
    // 基底クラスが航空機に要求する物
    // ------------------------------------------------------------------

    @Override
    public VehicleChassis.Hitbox hitbox() {
        return this.getStats().hitbox();
    }

    @Override
    public VehicleChassis.Sound soundSetup() {
        return this.getStats().sound();
    }

    @Override
    protected float health() {
        return this.getStats().airframe().health();
    }

    @Override
    protected int declaredSalvage() {
        return this.getStats().airframe().salvage();
    }

    @Override
    protected List<VehicleChassis.Seat> seats() {
        return this.getStats().airframe().seats();
    }

    @Override
    protected VehicleChassis.CameraMount cameraMount() {
        return this.getStats().camera();
    }

    /** 機体上の物は全て機体構造に固定されており、ファイルが示す位置にある。 */
    @Override
    protected Vec3 boxCentre(VehicleShape.Box box) {
        return this.position().add(Attitude.toWorld(this.attitude, box.offset()));
    }

    /** 機体内での箱自身の角度、次にワールド内での機体の角度。 */
    @Override
    protected Quaternionf boxRotation(VehicleShape.Box box) {
        return new Quaternionf(this.attitude).mul(box.orientation());
    }

    /**
     * 乗員が世界を見る視点。砲に取り付いた目——ガンカメラ——だけが機体構造から離れて動く。
     *
     * <p>{@code "mount": "gun"} と書かれた席の目は、その席が受け持つ砲座の耳軸周りに運ばれる。撃つ物に
     * 固定された箱であり、砲が振れれば一緒に振れ、俯角を取れば一緒に下を向く。AC-130 の砲手のように、
     * 席が機体の反対側にあって胴体しか見えない乗員のためのもの——見るべき物は自分の砲の向こうにある。
     *
     * <p>その席が砲座を持たない機体ファイルでは、書かれた点をそのまま使う。砲を降ろした機体でも、砲座の
     * 番号が席と食い違っている機体でも、視点が原点へ飛ぶよりは席に留まる方がよい。
     */
    @Override
    protected Vec3 eyeToWorld(int seat, VehicleShape.Mount mount, Vec3 eye, float partialTick) {
        if (mount == VehicleShape.Mount.HULL) {
            return this.toWorld(eye, partialTick);
        }

        int station = this.stations.stationForSeat(seat);

        return this.toWorld(station == GunStations.NONE ? eye : this.stations.carry(station, eye),
                partialTick);
    }

    /**
     * パイロン。当たり判定の箱には含めない。兵装を吊る場所は機体の一部というより機体上の位置であり、単独で
     * クリックできる価値がある。
     */
    @Override
    protected List<VehiclePart> extraParts() {
        List<AircraftDefinition.Hardpoint> hardpoints = this.getStats().hardpoints();
        List<VehiclePart> pylons = new java.util.ArrayList<>(hardpoints.size());

        for (int i = 0; i < hardpoints.size(); i++) {
            pylons.add(VehiclePart.pylon(this, hardpoints.get(i).name(), i));
        }

        return pylons;
    }

    // ------------------------------------------------------------------
    // 状態
    // ------------------------------------------------------------------

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_THROTTLE, 0.0F);
        builder.define(DATA_AFTERBURNER, 0.0F);
        builder.define(DATA_ATTITUDE, new Quaternionf());
        builder.define(DATA_VELOCITY, new Vector3f());
        builder.define(DATA_BODY_RATE, new Vector3f());
        builder.define(DATA_GEAR_DOWN, true);
        builder.define(DATA_FLAPS_DOWN, false);
        builder.define(DATA_VTOL, false);
        builder.define(DATA_WEAPONS, new CompoundTag());
        builder.define(DATA_STATIONS, new CompoundTag());
        builder.define(DATA_FLARES, 0);
        builder.define(DATA_CHAFF, 0);
        builder.define(DATA_DESIGNATED, -1);
    }

    /**
     * ハードポイントの搭載物。実体はサーバー側の複製で、クライアントはサーバーが最後に送った値を読む。計器と
     * ポッドの描画にはそれで足りる。
     */
    /**
     * レーダーと警戒受信機。
     *
     * <p>問い合わせはサーバー限定。この機体自身のtickと、「この機体のレーダーが自分を捉えているか」を知りたい
     * 他機の受信機から呼ばれる。クライアントには画を送るのであって、算出を許すのではない。
     */
    /**
     * 機上に残るフレア数、またはチャフ量。
     *
     * @param flare フレアなら true、チャフなら false
     */
    public int getCountermeasures(boolean flare) {
        return this.entityData.get(flare ? DATA_FLARES : DATA_CHAFF);
    }

    /** 0を下回らず、機体の搭載量を超えない。 */
    public void setCountermeasures(boolean flare, int left) {
        int held = Mth.clamp(left, 0, this.getStats().countermeasures().capacity(flare));

        this.entityData.set(flare ? DATA_FLARES : DATA_CHAFF, held);
    }

    /**
     * この機体がレーダーにどれだけ大きく映るか。クリーン形態の機体＋外部に取り付けた物。
     *
     * <p>ウェポンベイ内の兵装は数えない。それがベイの存在理由の全てであり、ステルス機のパイロットが自機の
     * シグネチャについて実際に下せる唯一の判断だ。それ以外は形状を引いた者が既に決めてしまっている。
     */
    public float radarCrossSection() {
        AircraftDefinition.Signature signature = this.getStats().signature();

        return (signature.radar() + signature.store() * this.weapons.externalStores())
                * this.weapons.radarGain();
    }

    /**
     * そのエンティティをレーダーがどこまで見えるか。同じレーダーが通常の戦闘機に対して出す距離に対する割合。
     * ここでは航空機以外は全て通常の戦闘機扱い——つまり何も決めていない、ということだ。
     */
    public static float visibility(Entity entity) {
        return entity instanceof AircraftEntity aircraft
                ? AircraftDefinition.Signature.reach(aircraft.radarCrossSection())
                : 1.0F;
    }

    /**
     * そのエンティティに向けたレーダーロックが、何も積んでいない相手に対して要する時間の何倍かかるか。
     * ジャマーを吊っている機体では1を超え、それ以外は全て1。
     *
     * <p>反射断面積とは別物で、両方を同時に持つ。断面積は「どこまで遠くで見つかるか」、こちらは「見つけた
     * 後どれだけ長く照準線に乗せ続けねばならないか」を決める。ジャマーの本領は後者だ——射程の外へ逃がすの
     * ではなく、相手が旋回で失うより長く保持することを要求して、間に合わなくさせる。
     *
     * <p>両側で同じ値になる。搭載内容は機体の同期データに乗っており、クライアントは目標の機体を追跡して
     * いれば読める。だから画面上の枠は実際のロック速度で閉じる。
     */
    public static float lockDelay(Entity entity) {
        return entity instanceof AircraftEntity aircraft ? aircraft.weapons.lockDelay() : 1.0F;
    }

    public WeaponMounts getWeapons() {
        return this.weapons;
    }

    /** 乗員が撃つ砲。機体が1門も持たなければ空のまま何もしない。 */
    public GunStations getStations() {
        return this.stations;
    }

    // ------------------------------------------------------------------
    // ターゲティングポッド
    // ------------------------------------------------------------------

    /**
     * ポッドが捉えている対象。無ければ null。
     *
     * <p>指示地点に立つ {@link DesignationEntity} か、パイロットが十字線を合わせた車両のいずれか。両側から問える。
     * サーバーはレーザー誘導兵器がレールを離れる際に渡し、パイロットのクライアントはマークを描きカメラで追う。
     */
    @Nullable
    public Entity getDesignated() {
        int id = this.entityData.get(DATA_DESIGNATED);

        if (id < 0) {
            return null;
        }

        Entity held = this.level().getEntity(id);

        return held != null && held.isAlive() ? held : null;
    }

    /** ポッドが捉えている位置。無ければ null。爆弾を誘導していく先の点。 */
    @Nullable
    public Vec3 getDesignatedPoint() {
        Entity held = this.getDesignated();

        return held == null ? null : held.position().add(0.0, held.getBbHeight() * 0.5, 0.0);
    }

    /**
     * ポッドを地上の一点、または動く物へ据える。
     *
     * <p>両者が1つの呼び出しなのは、1つのキーの1回の押下だからだ。パイロットは十字線を何かに合わせて指示する
     * のであって、その下にあったのが戦車か斜面かを2度判断させられる筋合いは無い。車両はそれ自身として保持する
     * ので、動く物への指示は追従する。地点はそこへ置いたマーカーで保持する。この MOD で誘導する物は全て
     * エンティティへ向かうからだ。{@link DesignationEntity} 参照。
     *
     * <p>再指示は2つ目を残さずマークを移動させ、可能な限り同じマーカーを再利用する。保持役のポッドが搭載されて
     * いなければ即座に拒否する——この仕組み全体がポッドの仕事であり、素のセンサーステーションしか無い機体には
     * 指示する手段が無い。
     *
     * <p><b>地点はパイロットが見た物ではなく計算結果でありうる。</b>ポッドはクライアントがチャンクを持つ範囲より
     * はるかに遠くまで届くので、その外で行った指示は仮定した床の上に立ち、実際の地面との差だけずれる。そうした
     * 地点もそのまま受け入れる——機体が飛んで行って見ることのできた地面であり、指示とはそれが全てだからだ——
     * うえでマーカーへ算出に使った視線を渡し、その地面がロードされ次第、実際の地表へ自力で歩み寄れるようにする。
     * {@link DesignationEntity} と {@link com.ashvehicles.client.Terrain} 参照。
     *
     * @param point 地上のどこか。{@code entity} を指示するなら null
     * @param entity 保持する対象。地点を保持するなら null
     * @param estimated 地点がブロック上で見えた物ではなく、クライアントの世界の外で算出された物か
     * @return 現在何かが指示されているか
     */
    public boolean designate(@Nullable Vec3 point, @Nullable Entity entity, boolean estimated) {
        if (!this.weapons.hasPod(EquipmentDefinition.Kind.TARGETING)) {
            this.clearDesignation();

            return false;
        }

        if (entity != null && entity.isAlive() && !WeaponMounts.isPartOf(this, entity)) {
            this.dropMarker();
            this.entityData.set(DATA_DESIGNATED, entity.getId());

            return true;
        }

        if (point == null) {
            this.clearDesignation();

            return false;
        }

        // 後で地面を探す必要があるマークのために、マークを見つけた視線を保持する。ポッドのレンズではなく機体
        // から取る。レンズは機体から数ブロック横にあるが、この種の指示が行われる数百ブロックの距離では差は1度
        // 未満であり、用途はその遠端での数十ブロックの補正だからだ。
        Vec3 sight = null;

        if (estimated) {
            Vec3 line = point.subtract(this.position());
            sight = line.lengthSqr() > 1.0E-6 ? line.normalize() : null;
        }

        if (this.marker == null || !this.marker.isAlive()) {
            this.marker = DesignationEntity.at(this.level(), point, sight);
            this.level().addFreshEntity(this.marker);
        } else {
            this.marker.hold(point, sight);
        }

        this.entityData.set(DATA_DESIGNATED, this.marker.getId());

        return true;
    }

    /** ポッドが捉えていた物を解放し、マーカーも片付ける。 */
    public void clearDesignation() {
        this.dropMarker();
        this.entityData.set(DATA_DESIGNATED, -1);
    }

    private void dropMarker() {
        if (this.marker != null) {
            this.marker.discard();
            this.marker = null;
        }
    }

    /**
     * 保持1tick分。マーカーへ「まだ保持者がいる」と伝え、保持対象を失った指示は解放する。
     *
     * <p>これが無いとマーカーは数秒で自ら諦める。tickの合間に機体が撃墜された場合のマーカーはそれで消える。
     * {@link DesignationEntity} 参照。指示を終わらせうる他の要因——ポッドの脱落、目標の破壊、機体の全損——は
     * すべてここで処理する。
     */
    private void tickDesignation() {
        if (this.entityData.get(DATA_DESIGNATED) < 0) {
            return;
        }

        if (this.isWrecked() || !this.weapons.hasPod(EquipmentDefinition.Kind.TARGETING)
                || this.getDesignated() == null) {
            this.clearDesignation();

            return;
        }

        if (this.marker != null) {
            // 伝えるのは「まだ保持者がいる」ことだけ。位置はマーカー自身の管轄だ。実際の地面を探している最中
            // のマーカーは自力でそこへ歩み寄っており、毎tick元の位置へ戻せば機体がそれを台無しにしてしまう。
            this.marker.held();
        }
    }

    /** 特殊ステーションのポッドがシーカーにとって持つ価値。{@link WeaponMounts} 参照。 */
    @Override
    public float seekerRangeGain() {
        return this.weapons.seekerRangeGain();
    }

    @Override
    public float lockRateGain() {
        return this.weapons.lockRateGain();
    }

    /** 航空機は機体を向けて照準するので、兵装が見るのは機首の方向。 */
    @Override
    public Vec3 getAimDirection(float partialTick) {
        return Attitude.nose(this.getAttitude(partialTick));
    }

    @Override
    public VehicleChassis.Radar radar() {
        return this.getStats().radar();
    }

    /** シーカーはパイロンと同居する。探す対象はそこに吊られている物が決めるからだ。 */
    @Override
    public TargetLock lock() {
        return this.weapons.lock();
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);

        if (DATA_WEAPONS.equals(key) && this.level().isClientSide) {
            this.weapons.load(this.entityData.get(DATA_WEAPONS));
        }

        if (DATA_STATIONS.equals(key) && this.level().isClientSide) {
            this.stations.load(this.entityData.get(DATA_STATIONS));
        }
    }

    public float getThrottle() {
        return this.throttle;
    }

    public void setThrottle(float throttle) {
        this.throttle = Mth.clamp(throttle, 0.0F, 1.0F);

        if (!this.level().isClientSide) {
            this.entityData.set(DATA_THROTTLE, this.throttle);
        }
    }

    /**
     * 機体を指向させる。Minecraft が把握している方位と仰角も追随させる。バニラはそれらを他へ送り、搭乗者の配置
     * にも使うからだ。
     */
    public void setAttitude(Quaternionf attitude) {
        this.attitude = attitude.normalize(new Quaternionf());
        this.setYRot(Attitude.heading(this.attitude));
        this.setXRot(Attitude.elevation(this.attitude));

        if (!this.level().isClientSide) {
            this.entityData.set(DATA_ATTITUDE, this.attitude);
        }
    }

    /**
     * 姿勢を即座に設定し、レンダラーに補間する物を残さない。tickせず設置と同時に描く代替機体用。
     */
    public void snapAttitude(Quaternionf attitude) {
        this.setAttitude(attitude);
        this.attitudeO = new Quaternionf(this.attitude);
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
        // ここでは飛行ではなく設置なので旋回しておらず、旋回として外挿されてはならない。次tickの角速度をここ
        // から測ることで、設置自体が旋回として読まれるのも防げる。代替機体ではそれがほぼ1回転になりうる。
        this.ratedAttitude.set(this.attitude);
        this.hasRatedAttitude = true;

        if (!this.level().isClientSide) {
            this.entityData.set(DATA_BODY_RATE, new Vector3f());
        }
    }

    /**
     * 操縦中のクライアントから姿勢を受け取る。1回につき向こうの1tick分。
     *
     * <p>{@link #setAttitude} と分けてあるのは処理内容ではなくタイミングのため。これ1回がパイロットの飛行モデル
     * の丸1tickであり、だからこそその間の旋回量が機体の本当の角速度になる。サーバー自身のtickは測定に適さない
     * ——パイロットの時計とサーバーの時計は互いにずれるので、サーバーの1tickにこの更新が2回入ることも0回のことも
     * あり、それを基準に取った角速度はそのずれを「痙攣するようにロールする機体」として報告してしまう。
     */
    public void reportAttitude(Quaternionf attitude) {
        this.setAttitude(attitude);
        this.recordTurnRate();
    }

    /**
     * 前回呼び出しからの旋回量を求め、更新間の姿勢外挿に使う角速度として全視聴者へ伝える。
     *
     * <p>操縦側から更新1回につき1度呼ぶ。有人機ならパケットごと、サーバーが飛ばしているならtickごと。同じ回転に
     * ついて2度呼んではならない——飛行モデルは1tick内で機体を数段階に分けて回すので、その各段階は角速度ではなく
     * 旋回の一部でしかない。
     */
    private void recordTurnRate() {
        if (this.level().isClientSide) {
            return;
        }

        if (this.hasRatedAttitude) {
            this.entityData.set(DATA_BODY_RATE, Attitude.rotationVector(
                    new Quaternionf(this.ratedAttitude).conjugate().mul(this.attitude).normalize()));
        }

        this.ratedAttitude.set(this.attitude);
        this.hasRatedAttitude = true;
    }

    /**
     * サーバーから毎tick、機体速度を全視聴者へ伝える。
     *
     * <p>飛行モデルを回している側以外に正直に答えられる者はおらず、他の全コピーはカクつかせずに描くためこれを
     * 必要とする。参照先は {@link #getVelocity()} が既に把握している——操縦者がいる間はパイロット自身の値、
     * それ以外は今tickの移動量——ので、ここは中継するだけ。
     */
    private void publishVelocity() {
        Vec3 velocity = this.getVelocity();

        this.entityData.set(DATA_VELOCITY,
                new Vector3f((float) velocity.x, (float) velocity.y, (float) velocity.z));
    }

    /** 描画用の姿勢。直近2tick間を近い側の経路で補間する。 */
    public Quaternionf getAttitude(float partialTick) {
        return new Quaternionf(this.attitudeO).slerp(this.attitude, partialTick).normalize();
    }

    /** バンク角。右翼が下がる方向を正とする。 */
    public float getRoll() {
        return Attitude.bank(this.attitude);
    }

    public float getRoll(float partialTick) {
        return Attitude.bank(this.getAttitude(partialTick));
    }

    /**
     * 直前1tickの自機軸周りの旋回量（度）。レンダラーはこれで舵面を偏向させる。パイロットの生入力と違い同期
     * 状態から来るので、全クライアントで機能する。
     */
    public float getRollDelta() {
        return this.bodyRate(2);
    }

    public float getPitchDelta() {
        return this.bodyRate(0);
    }

    public float getYawDelta() {
        return this.bodyRate(1);
    }

    /** 前tickから今tickまでの回転の1軸成分。機体座標系で測る。 */
    private float bodyRate(int axis) {
        Quaternionf change = new Quaternionf(this.attitudeO).conjugate().mul(this.attitude).normalize();
        float sine = (float) Math.sqrt(Math.max(0.0, 1.0 - change.w * change.w));

        if (sine < 1.0E-5F) {
            return 0.0F;
        }

        float angle = 2.0F * (float) Math.acos(Mth.clamp(change.w, -1.0F, 1.0F));
        float component = switch (axis) {
            case 0 -> -change.x;
            case 1 -> -change.y;
            default -> change.z;
        };

        return (float) Math.toDegrees(angle * component / sine);
    }

    /** この機体が揚力系（推力偏向）を持つか。 */
    public boolean isVtolCapable() {
        return this.getStats().vtol().isPresent();
    }

    /** パイロットがノズル下げを要求しているか。 */
    public boolean isVtolSelected() {
        return this.entityData.get(DATA_VTOL);
    }

    /** ノズルの実際の振れ量（0〜1）。描画用に補間済み。 */
    public float getVtolProgress(float partialTick) {
        return Mth.lerp(partialTick, this.vtolProgressO, this.vtolProgress);
    }

    /** 同じ物を角度で。尾部方向からの度数で、計器が表示する値。 */
    public float getNozzleAngle() {
        return this.vtolProgress * this.getStats().vtol().map(AircraftDefinition.Vtol::maxAngle).orElse(0.0F);
    }

    /**
     * ノズルを反対側へ振る。
     *
     * <p>機体の転換速度を超えている場合、下げ方向は拒否する。500ノットで気流を横切るよう向けたエンジンは揚力系
     * ではなく事故だ。戻す方向は常に許可され、それが転換を可逆にしている。
     */
    public void toggleVtol() {
        if (this.level().isClientSide || !this.isVtolCapable()) {
            return;
        }

        AircraftDefinition.Vtol vtol = this.getStats().vtol().get();

        if (!this.isVtolSelected() && this.getVelocity().length() > vtol.conversionSpeed()) {
            return;
        }

        this.entityData.set(DATA_VTOL, !this.isVtolSelected());
    }

    /** この機体が主翼ではなくローターで支えられているか。 */
    public boolean isRotorcraft() {
        return this.getStats().rotor().isPresent();
    }

    /** ローターの回転数。0が停止、1が定格。ローターを持たない機体では0。 */
    public float getRotorSpeed() {
        return this.rotorSpeed;
    }

    /**
     * エンジン音が追従すべき値（0〜1）。
     *
     * <p>固定翼機ではスロットル。レバーと音は同じ物だ。ヘリのローターはコレクティブが何をしていようと一定回転で
     * 回り、パイロットが何か要求するずっと前から機体で最も大きい音源になっている——なのでヘリでは音はローターの
     * 回転上昇に追従し、パイロットが引いたときエンジンが受ける負荷分としてコレクティブを少し上乗せする。
     */
    public float getEngineNote() {
        // 乾いたタンクは音を止める。エンジンが止まった機体は、レバーがどこにあろうと無音で滑空する——
        // そしてこの値は燃料消費そのものでもあるので、空のタンクが燃料を消し続けることも無くなる。
        if (this.isOutOfFuel()) {
            return 0.0F;
        }

        if (!this.isRotorcraft()) {
            return this.getThrottle();
        }

        return this.rotorSpeed * Mth.lerp(this.getThrottle(), ROTOR_IDLE_NOTE, 1.0F);
    }

    @Override
    public VehicleChassis.Fuel fuelSetup() {
        return this.getStats().engine().fuel();
    }

    /**
     * 増槽から本体タンクへ移送する。パイロンに吊った増槽が航続距離を伸ばす仕組みの全部がこれだ。
     *
     * <p>燃やした直後に呼ばれ、空いた分だけを引く。だから外側が先に空になり、本体タンクは増槽が尽きるまで
     * 満タンのまま残る。{@code WeaponMounts.drawFuel} 参照。
     */
    @Override
    protected float drawExternalFuel(float wanted) {
        float drawn = this.weapons.drawFuel(wanted);

        // 引けば残量が変わり、残量は計器に出る。搭載構成の同期はここでしか起きないので、変わったことを
        // 伝えるのはこの場だ。
        if (drawn > 0.0F && this.weapons.consumeDirty()) {
            this.entityData.set(DATA_WEAPONS, this.weapons.syncTag());
        }

        return drawn;
    }

    /** 吊っている増槽に残っている燃料の合計。計器向け。 */
    public int getTankFuel() {
        return this.weapons.tankFuel();
    }

    /**
     * 吊っている増槽へ燃料を入れる。給油から。
     *
     * @return 実際に入った量。増槽を積んでいないか、全部満タンなら0
     */
    public int fillTanks(int units) {
        if (this.level().isClientSide) {
            return 0;
        }

        int filled = this.weapons.fillTanks(units);

        if (filled > 0) {
            this.entityData.set(DATA_WEAPONS, this.weapons.syncTag());
        }

        return filled;
    }

    /**
     * 吊っている増槽を全部切り離す。パイロットの操作から。
     *
     * <p>飛行中でも構わない。むしろそれが目的で、空になった増槽を抱えたまま戦闘に入る理由はどこにも無い。
     * 落とした物は返らない——それは投棄であって取り外しではない。持ち帰りたければ地上でレンチを使う。
     */
    public void jettisonTanks() {
        if (this.level().isClientSide) {
            return;
        }

        if (this.weapons.jettisonTanks() > 0) {
            this.entityData.set(DATA_WEAPONS, this.weapons.syncTag());
            this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                    SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.NEUTRAL, 0.9F, 0.8F);
        }
    }

    /**
     * タンクが空なのでエンジンが止まっている。止まっているならレバーを引き戻し、真を返す。
     *
     * <p>推力を直接0にせずレバーを戻すのは、そこから先を既存のスプールが引き受けるからだ。推力は瞬時に
     * 消えるのではなく数秒かけて落ちていき、実機のフレームアウトもそう感じられる。バーナーは即座に落とす。
     * そちらは燃料を吹き込んで点火する装置であって、吹き込む物が無ければ何も残らない。
     *
     * <p><b>主翼と舵は生きている。</b> ここが切るのはエンジンだけだ。動圧が舵面を動かし、主翼は揚力を作り
     * 続けるので、燃料切れの機体は落ちるのではなく滑空する。降りる場所を選ぶのはパイロットの仕事として
     * 残る——それが「燃料切れ」を、事故ではなく判断の結果にしている。
     */
    private boolean flameout() {
        if (!this.isOutOfFuel()) {
            return false;
        }

        this.setThrottle(0.0F);
        this.reheatCommanded = false;
        this.gateHeld = 0;
        this.reheat = 0.0F;

        if (!this.level().isClientSide) {
            this.entityData.set(DATA_AFTERBURNER, 0.0F);
        }

        return true;
    }

    /** メインローターの現在角（度）。描画用に補間済み。 */
    public float getRotorAngle(float partialTick) {
        return Mth.lerp(partialTick, this.rotorAngleO, this.rotorAngle);
    }

    /** テールローターの同じ値。数倍速く回るので別々に数える。 */
    public float getTailRotorAngle(float partialTick) {
        return Mth.lerp(partialTick, this.tailAngleO, this.tailAngle);
    }

    public boolean isGearDown() {
        return this.entityData.get(DATA_GEAR_DOWN);
    }

    /**
     * 降着装置を上げ下げする。車輪で接地している間は拒否する——実機の重量感知スイッチの仕事だ——し、脚が
     * 引き込まない機体では常に拒否する。
     */
    public void toggleGear() {
        if (this.level().isClientSide || !this.getStats().landingGear().retractable()
                || (this.isGearDown() && this.onGround())) {
            return;
        }

        this.entityData.set(DATA_GEAR_DOWN, !this.isGearDown());
    }

    /**
     * 降着装置が行き先まで動き終えたか。true の間は作動アニメーションを再生せず最終フレームで保持するので、
     * 既に接地している機体が改めて脚を出すように見えることはない。
     */
    public boolean isGearSettled() {
        return this.gearProgress == (this.isGearDown() ? 1.0F : 0.0F);
    }

    /** 描画用の降着装置作動量。0が完全格納、1が完全展開。 */
    public float getGearProgress(float partialTick) {
        return Mth.lerp(partialTick, this.gearProgressO, this.gearProgress);
    }

    public boolean isFlapsDown() {
        return this.entityData.get(DATA_FLAPS_DOWN);
    }

    public void toggleFlaps() {
        if (!this.level().isClientSide) {
            this.entityData.set(DATA_FLAPS_DOWN, !this.isFlapsDown());
        }
    }

    /** 描画用のフラップ作動量。0が格納、1が全下げ。 */
    public float getFlapsProgress(float partialTick) {
        return Mth.lerp(partialTick, this.flapsProgressO, this.flapsProgress);
    }

    /** 主翼が速度に合わせて動く機体か。 */
    public boolean hasSweepWing() {
        return this.getStats().wing().sweep().isPresent();
    }

    /**
     * 主翼の後退角（度）。ジオメトリが翼を置いた位置からの移動量で、モデルも計器もこれを読む。可変翼を
     * 持たない機体では0。
     */
    public float getWingSweep(float partialTick) {
        return this.getStats().wing().sweep()
                .map(sweep -> sweep.angle(Mth.lerp(partialTick, this.sweepProgressO, this.sweepProgress)))
                .orElse(0.0F);
    }

    public AircraftInput getInput() {
        return this.input;
    }

    public void setInput(AircraftInput input) {
        this.input = input;
    }

    /**
     * 地上員が作業できる程度に機体が静止しているか。
     *
     * <p>原則は「車輪が接地していること」。「まったく動いていない」を併記してあるのは、その判定が常に起きている
     * とは限らないからだ。脚に沈み込みつつある機体や、サーバーではなくパイロットのクライアントが移動を回して
     * いる機体は、明らかにエプロンに駐機していても1〜2tickの間「空中」と申告しうる。ステーションへの兵装搭載は
     * 1クリックであり、そのtickに当たったクリックは黙って何もしない——機体が兵装を拒否したのと区別が付かない。
     */
    public boolean isParked() {
        return this.onGround() || this.getVelocity().lengthSqr() < PARKED_SPEED;
    }

    /** 機体が全損するほど強く何かに当たったら true。 */
    public boolean isCrashing() {
        return this.crashing;
    }

    /** 主翼が気流に対して成す角（度）。 */
    public float getAngleOfAttack() {
        return this.angleOfAttack;
    }

    /** 主翼が飛ばなくなったら true。速度ではなく角度の問題。 */
    public boolean isStalled() {
        return !this.onGround() && !this.isHovering()
                && Math.abs(this.angleOfAttack) > this.getStats().wing().stallAngle();
    }

    /**
     * 揚力系が機体を十分に支えており、主翼の状態が問題にならないか。
     *
     * <p>「主翼が飛んでいない」ことに警告を出すはずの箇所から問われる。実際飛んでいないが、そのためのノズルだ。
     * ヘリは常に yes を返す。そもそも主翼が支えていたことなど無いからだ。
     */
    public boolean isHovering() {
        if (this.isRotorcraft()) {
            return true;
        }

        return this.getStats().vtol()
                .map(vtol -> this.vtolProgress * vtol.maxAngle() > HOVERING_ANGLE)
                .orElse(false);
    }

    // ------------------------------------------------------------------
    // tick 処理
    // ------------------------------------------------------------------

    @Override
    public void tick() {
        if (this.getHurtTime() > 0) {
            this.setHurtTime(this.getHurtTime() - 1);
        }

        super.tick();
        this.attitudeO = new Quaternionf(this.attitude);
        this.tickGear();
        this.tickVtol();
        this.tickRotor();
        this.tickLerp();

        if (this.isControlledByLocalInstance()) {
            if (!(this.getControllingPassenger() instanceof Player)) {
                // 操縦者不在: 舵は中立へ戻るが、スロットルは置かれたまま。機体は何かに落とされるまで飛び
                // 続ける。
                this.input = AircraftInput.NONE;
            }

            // 残骸は飛ばさない。落ちて、落ちた場所に横たわる。
            if (this.isWrecked()) {
                this.wreckTick();
            } else {
                this.flightTick();
            }

            Vec3 impactVelocity = this.getDeltaMovement();

            // 機体の周りに後から現れた地面は、機体が突っ込んだ地面ではない。
            //
            // チャンクは飛行経路に沿って要求され別スレッドで生成されるが、数百ノットでは要求が競争に負けうる。
            // 機体はまだ確定していない空間を横切り、次の瞬間、元々そこにあるはずだった斜面が機体を内包した形で
            // 生成される。通常の衝突として扱えばそれは墜落であり、パイロットは見たことも避けようも無い地形に
            // 破壊される。
            //
            // よって、既に世界の内側にいる機体はそこへ突っ込むのではなく外へ飛び出す。他は何も変わらない。外側
            // から出会った斜面は従来通り表面で機体を止める。move() のスイープ判定はすり抜けられないからだ。ここで
            // 何かの内側にいられる唯一の道は、それが後から現れたことだ。
            //
            // ただし残骸は除く。この違いが重要だ。機体を斜面から押し出すのは自身の対気速度だが、残骸のそれは
            // ゆっくりした降下だけ。それで世界を押し進み、止める物も無い状態で——この分岐は何とも衝突しない——
            // 斜面に降りた全損機は地面を沈み続けてしまう。斜面に埋まった残骸はそこで止まる方がよく、それが残骸の
            // あるべき姿だ。
            //
            // 外へ、決して奥へは進めない。この分岐は何とも衝突しないので、与えられた距離は機体が岩盤を貫いて進む
            // 距離になる。横と上へならそれは脱出だが、地面でできた世界では下へはただ深くなるだけだ。降下率を
            // 持たせたままにすると、低い位置で何かの内側に入った機体は毎tick少しずつ床の下へ送り込まれ、止める
            // 物には決して行き当たらない。水平に保てば対気速度で脱出する。元々それが脱出させるはずの物だ。
            if (!this.isWrecked() && this.insideTerrain()) {
                this.setPos(this.getX() + impactVelocity.x,
                        this.getY() + Math.max(impactVelocity.y, 0.0),
                        this.getZ() + impactVelocity.z);
            } else {
                this.move(MoverType.SELF, impactVelocity);

                // 残骸が斜面に当たっても、それは残骸が斜面に当たっただけ。全損させる機体はもう残っておらず、
                // どれだけ強く当たったかを判定する必要も無い。
                if (!this.isWrecked()) {
                    this.detectCrash(impactVelocity);
                }
            }

            if (!this.level().isClientSide) {
                // ここで飛ばしているのでこの側が知っている。移動後に測る。機体が実際に進んだ距離こそ他が
                // 描くべき物であり、斜面は飛行モデルの要求と世界の承認の間でそれをかなり削りうるからだ。
                this.recordTurnRate();
                this.publishVelocity();
            }
        } else {
            // この側が実際に見た移動量であって、パイロットの申告速度では意図的にない。航空機は速度更新の対象
            // に登録されているので、これが変わるたびサーバーは motion パケットをブロードキャストする——そして
            // パイロット自身のクライアントもその宛先に含まれる。ここに本当の速度を入れると毎tickパイロットへ
            // パケットが飛び、そのクライアントは自分の飛行モデルが今算出した速度の上からそれを適用して、毎tick
            // 1tick分の旋回を捨ててしまう。この側から見える差分のままにしておけばサーバーでは常に0で、何も
            // ブロードキャストされない。
            //
            // 過G判定にも同じ値を渡すので、サーバーではその0となり、有人機では何もしない。従来からそうであり、
            // 有効化するのは修理ではなく別種の変更だ。Su-25 の現在の数値では、きつい旋回で約12G に達する一方
            // 限界は6.5G で、0.5秒足らずで機体が壊れてしまう。効かせる前に限界値と損傷量を揃えて再調整したい。
            Vec3 travelled = this.travelled();
            this.setDeltaMovement(travelled);
            this.throttle = this.entityData.get(DATA_THROTTLE);
            // この側では一切算出しない。保持すべきゲートもラッチも無いので、バーナーの状態は操縦側が最後に
            // 申告した通りになる。
            this.reheat = this.entityData.get(DATA_AFTERBURNER);
            // ここでエンジンをスプールさせる物は無いが、この側はいつ機体を引き渡されるか分からない——パイロット
            // の降機や、クライアントの操縦引き継ぎ——ので、既に飛行中の機体で冷えたエンジンから始めると空から
            // 落としてしまう。
            this.thrustLevel = this.throttle;

            Quaternionf reported = this.entityData.get(DATA_ATTITUDE);

            if (this.level().isClientSide) {
                // 描画されるので、届く姿勢の間も機体を回し続ける価値がある。止めておいて跳ばせるよりよい。
                // AircraftInterpolation 参照。
                Vector3f rate = this.entityData.get(DATA_BODY_RATE);

                // 姿勢より前に行う。補正を、補正自体から逆算するのではなく機体の現在の挙動を知った上で
                // 取り込めるようにするため。
                this.interpolation.receiveBodyRate(rate.x(), rate.y(), rate.z());

                if (this.interpolation.isNewAttitude(reported)) {
                    this.interpolation.receiveAttitude(reported);
                }
                this.interpolation.advanceAttitude(this.attitude);
                this.setYRot(Attitude.heading(this.attitude));
                this.setXRot(Attitude.elevation(this.attitude));
            } else {
                // サーバーは何も描かないし、この値が正であるのはここだけだ。ここで外挿しても、推測を
                // ブロードキャスト内容へ差し戻すだけになる。
                this.attitude = new Quaternionf(reported);
            }

            if (!this.level().isClientSide) {
                this.checkStructuralLoad(travelled);
                // クライアントが飛ばしているので、出て行くのはそのクライアントの申告値。角速度はここでは
                // なく各更新の到着時に記録する。reportAttitude 参照。
                this.publishVelocity();
            }
        }

        // 可変翼は他の tick* と違い、移動の「後」で動かす。目標後退角を決めるのは対気速度だが、操縦側での
        // それは travelled() ——位置と旧位置の差——であり、tick は旧位置を現在位置へ揃えた直後に始まる。
        // 移動前に測れば実速度にかかわらず必ず0で、翼は全開前進に貼り付いたまま二度と動かない。サーバーと
        // 非操縦クライアントは申告値を読むので順序を問わないが、測る側に全側を合わせる。recordTurnRate が
        // 移動後に測るのと同じ理由。
        this.tickSweep();

        if (this.crashing) {
            this.crash();
        }

        this.tickParts();

        if (this.level().isClientSide) {
            this.spawnFlightEffects();
        } else if (!this.isWrecked()) {
            // 焼け落ちた機体には引くトリガーも、走査するレーダーも、放出するディスペンサーも無い。
            this.tickWeapons();
            this.getSensors().tick();
            this.dispenser.tick(this.input.flare(), this.input.chaff());
        }

        // 直下のチャンクを開いたまま保持する。全員の描画距離の外へ飛んだだけで機体が存在しなくなることを
        // 防ぐ。
        this.heldChunks = AircraftChunkLoader.update(this, this.heldChunks);

        this.checkInsideBlocks();
    }

    /**
     * トリガーが選択している物を発射し、残数をクライアントへ伝える。サーバー限定。飛行モデルは操縦側が回すが、
     * 弾はサーバーの管轄であり、クライアントが弾を生み出したり命中を主張したりはできない。
     */
    private void tickWeapons() {
        this.tickDesignation();

        // 照準が先、発砲が後。砲座は自分では撃たず、パイロンの側が「その砲座は今どこを向いていて、引き金は
        // 引かれているか」を訊きに来る——だから向きはその問いより前に、この tick の物になっていないといけ
        // ない。GunStations 参照。
        this.stations.tick();
        this.weapons.tick(this.input.fire(), this.input.lock());

        if (this.weapons.consumeDirty()) {
            this.entityData.set(DATA_WEAPONS, this.weapons.syncTag());
        }

        if (this.stations.consumeDirty()) {
            this.entityData.set(DATA_STATIONS, this.stations.save());
        }
    }

    /** 次の搭載兵装を選択する。パイロットの入力パケットから呼ばれるのでサーバー側。 */
    public void cycleWeapon() {
        if (this.level().isClientSide) {
            return;
        }

        // 砲座を持っているパイロットにとって、このキーが巡るのはパイロンの兵装と砲座の両方だ。砲座を
        // 持たない機体では選ぶ物が変わらないので、キーは従来通りパイロンだけを進める。
        if (this.stations.cycle()) {
            this.entityData.set(DATA_STATIONS, this.stations.save());

            return;
        }

        this.weapons.selectNext();
    }

    /**
     * 機体が世界の手前ではなく内側にいるか。
     *
     * <p>移動のたびに問い、true になるのは地面が後から来た場合だけ。斜面へ飛び込んだ機体はその表面で止まるので、
     * <em>内側</em>にいるということは地面が後から現れたということだ。接地中は問わない。地面に立っている機体は
     * 立っているだけで、車輪がブロックの縁に載っているのは緊急事態ではない。
     *
     * <p>ただし滑走路の上にいる方法は接地だけではなく、そのために床の線がある。進入の最後の数フィートは機首上げ
     * で飛び、機体は降着装置の高さより長い。フレアは尾部を車輪より半ブロック以上下げ、バンクは翼端に同じことを
     * するが、その間ずっと車輪は空中にある。全ブロックを一律に測ると、機体はこれから着陸する滑走路の<em>内側</em>
     * にいることになり、この分岐が機体を世界の外へ——進行方向が下だったので下へ——飛ばし、そのまま床を抜けて
     * 落ち続ける。よって車輪より高くない地面は世界ではなく床とする。{@link #floorLine} であり、{@link #move} が
     * 既に擦り抜けている線と同じ物だ。
     */
    private boolean insideTerrain() {
        return !this.onGround() && !this.hasRoomHere(EMBEDDED_MARGIN, this.floorLine());
    }

    /**
     * 機体を止めた衝突が生存可能だったかを判定する。基準になるのは {@link #move} 前の速度だ。move() は阻まれた軸
     * を0にするので、戻ってきた時点では測る物が残っていない。
     */
    private void detectCrash(Vec3 impactVelocity) {
        if (!this.horizontalCollision && !this.verticalCollision) {
            return;
        }

        float limit = this.getCrashSpeed();
        double safe = this.getLandingSpeed();

        // 今起きたのが「到達」ではなく「着陸」だったか。2点は議論の余地が無い。脚が出ていること、その上で機体
        // が正立していること。そのうえで機体が地面に対して行ったことは、2通りのいずれかで着陸と認められる。
        //
        // 1つ目は降着装置が吸収できる降下率で、これが機体に滑走路を使わせている。離陸滑走は毎tick地面と衝突する
        // ので、「野原を転がっている」のと「野原へ突っ込んでいる」のを区別できるのは速度ではなく沈下率だけだ。
        // 浅い降下は鉛直軸を阻まれ、滑走とまったく同じように滑る。
        //
        // 2つ目は十分ゆっくり到達すること。パイロットが実際に狙って飛べるのはこちらだ。降着装置の定格速度以下
        // ——本人の計器に出ている値で、固定翼機 200km/h、ヘリ 50km/h——なら、最後にどれだけ強く降りようと、
        // どう飛んだかで機体が全損することはない。ヘリの着陸はこれが全てだ。停止してから降下するので沈下判定を
        // 通れる浅い進入が無く、固定翼機の墜落速度の何分の一かに縛っていた頃は、這うような速度以外での接地が
        // 機体を破壊していた。
        boolean landing = this.gearProgress > 0.5F
                && this.getLiftVector().y > UPRIGHT
                && (impactVelocity.y > -safe * TOUCHDOWN_SINK || impactVelocity.length() <= safe);

        if (landing) {
            // 無事に降りた。だがその後に何かへ突っ込むことは依然としてありうる。衝突が実際に奪った速度で測る
            // ——move() は阻まれた軸を既に0にしている——ので、翼端が滑走路灯を掠めたり、幅6ブロックの箱の角が
            // ブロックの縁に引っ掛かったりしても、本来通り何も起きない。
            Vec3 surviving = this.getDeltaMovement();
            double before = Math.sqrt(impactVelocity.x * impactVelocity.x + impactVelocity.z * impactVelocity.z);
            double after = Math.sqrt(surviving.x * surviving.x + surviving.z * surviving.z);

            // 墜落速度が何と言おうと、接地を許される速度を下回らせない。ヘリは 50km/h で接地してよいので、
            // 進入中にスキッドがブロックの縁を引っ掛けたことが全損の原因になってはならない——実際そうなって
            // いた。墜落速度がその半分未満だったからだ。
            if (this.horizontalCollision && before - after > Math.max(limit, safe)) {
                this.crashing = true;
            }

            return;
        }

        // それ以外で世界に出会った場合は、3軸まとめた突入速度で測る。鉛直軸を阻まれた後に地面を滑ったからと
        // いって衝突が生存可能になるわけではないし、たまたま止まった軸だけを読んでいたせいで地形への急降下から
        // 無傷で歩き去れていた。
        if (impactVelocity.length() > limit) {
            this.crashing = true;
        }
    }

    /**
     * アフターバーナーのゲートと、その先のバーナー。操縦側が毎tick、スロットルレバーの移動を許す前に1度実行する。
     *
     * <p><b>専用キーが無い理由。</b>アフターバーナーはパネルのスイッチではなく、スロットル自身の可動域の頂点に
     * あり、パイロットがレバーを押し通すべきストッパーの向こうにある。よってスロットルで操作する。エンジンが既に
     * 全力を出している状態でレバーを開き続け、{@link #GATE_TICKS} 後にゲートを通過する。そこでラッチされる。
     * スロットルは置いた位置に留まる物だし、超音速ダッシュをキー押しっ放しで飛ぶ者はいない。
     *
     * <p>抜けるときも同じゲートを逆から通る。スロットルを引く最初の操作はレバーをアフターバーナーから外すだけで
     * それ以上は動かない——このメソッドが返しうる {@code true} はそのためにある——ので、ミリタリー推力が欲しかった
     * パイロットは通り過ぎずにミリタリー推力を得る。2度目以降の操作は従来通りレバーを動かす。
     *
     * <p>バーナーの実出力はラッチに一致させず追従させる。エンジンのスプールより速い——アフターバーナーの点火は
     * タービンの回転上昇ではなくマッチの火だ——が瞬時ではない。プルームも音も推力の押しも、届くには一瞬要る。
     *
     * @return このtickのスロットル入力が、レバー移動ではなくゲートからの脱出に消費されたなら true
     */
    private boolean tickAfterburner(AircraftDefinition definition) {
        AircraftDefinition.Afterburner burner = definition.engine().afterburner().orElse(null);

        if (burner == null) {
            this.reheatCommanded = false;
            this.gateHeld = 0;
            this.reheat = 0.0F;

            return false;
        }

        // 揚力系を展開中はパイロットが何を要求しても不可。排気はノズルで下へ向けられ、エンジン出力のかなりが
        // 天井のファンを回している。アフターバーナーを入れる場所は無いし、ホバリング中に点火した機体は地面へ
        // 向いたロケットになる。ここから見た転換動作は「スロットル全開の保持」に見えるので、これが無いとゲートが
        // 毎回開いてしまう。
        boolean converted = this.vtolProgress > 0.0F;
        boolean swallowed = false;

        if (converted) {
            this.reheatCommanded = false;
            this.gateHeld = 0;
        } else if (this.input.throttle() < 0.0F) {
            this.gateHeld = 0;
            swallowed = this.reheatCommanded;
            this.reheatCommanded = false;
        } else if (this.reheatCommanded) {
            // ラッチ済み。カウントする対象も、パイロットが続けるべき操作も無い。
            this.gateHeld = GATE_TICKS;
        } else if (this.throttle >= 1.0F && this.input.throttle() > 0.0F) {
            this.reheatCommanded = ++this.gateHeld >= GATE_TICKS;
        } else {
            this.gateHeld = 0;
        }

        // ラッチが何と言おうと、レバーがストッパーから離れた瞬間に消える。バーナーは前段のエンジンから供給を
        // 受けており、部分スロットルのエンジンに燃やす余剰は無い。
        float commanded = this.reheatCommanded && this.throttle >= 1.0F ? 1.0F : 0.0F;
        this.reheat += (commanded - this.reheat) * Mth.clamp(burner.lightRate(), 0.01F, 1.0F);

        if (this.reheat < 1.0E-3F) {
            this.reheat = 0.0F;
        }

        if (!this.level().isClientSide) {
            // この側が飛ばしているので、公開するのもこの側。操縦中のクライアントは代わりに自分の値を上げて
            // 送る。reportAfterburner 参照。
            this.entityData.set(DATA_AFTERBURNER, this.reheat);
        }

        return swallowed;
    }

    /** バーナーがエンジン推力を何倍しているか。バーナー無し、または未点火なら1。 */
    private double reheatThrust() {
        if (this.reheat <= 0.0F) {
            return 1.0;
        }

        AircraftDefinition.Afterburner burner = this.getStats().engine().afterburner().orElse(null);

        return burner == null ? 1.0 : burner.thrustFactor(this.reheat);
    }

    /**
     * 操縦中のクライアントからのアフターバーナー設定値。信用して受け取り、他全員へ複製する——隣のスロットルと
     * 同じ仕組み、同じ理由。
     *
     * <p>クランプし、ファイルにバーナーが無い機体では即座に拒否するので、届く値は必ず機体が自力で出せた値になる。
     * スロットルよりここでの重要度は高い。推力はどちらにせよクライアントの管轄だが、シーカーがこの機体をどこまで
     * 見えるかはこの側で、この数値から決まるからだ。
     */
    public void reportAfterburner(float level) {
        float delivered = this.hasAfterburner() ? Mth.clamp(level, 0.0F, 1.0F) : 0.0F;
        boolean was = this.reheat > LIT;

        this.reheat = delivered;
        this.entityData.set(DATA_AFTERBURNER, delivered);

        if (!was && delivered > LIT) {
            this.playAfterburnerLight();
        }
    }

    /**
     * バーナー点火の破裂音。発生地点で聞こえる。
     *
     * <p>兵器の発射音と違い、volume 欄を到達距離として使わず本来の音量で送る。これは機体が出す音であり、機体には
     * 既にファイルが指定する距離まで届く音がある。点火が加えるのはパイロットと、今頭上を通過された者のための音だ。
     *
     * <p>録音はこの機体自身の名前で探し、クライアント側で解決する。リソースパックを見たことがあるのはそちらだけ
     * だからだ。ここには何も同梱していない。代わりを見つける {@code AfterburnerSounds} 参照。
     */
    private void playAfterburnerLight() {
        ResourceLocation id = this.getAircraftId();
        ResourceLocation event = id.withPath(
                VehicleEntityBase.SOUND_PREFIX + id.getPath() + "." + AFTERBURNER_ROLE);

        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvent.createVariableRangeEvent(event), SoundSource.NEUTRAL,
                AFTERBURNER_VOLUME, AFTERBURNER_LIGHT_PITCH);
    }

    /**
     * 赤外線誘導にとってこの機体がどれだけ熱く見えるか。冷えた状態の機体の値と、点火中はその上に乗るバーナー分。
     *
     * <p>{@link #radarCrossSection} の対になる値で、意図的に同じ数字ではない。ステルス機が形状で買ったのは小さな
     * レーダー反射であり、その形状は排気を冷たくしない。どのみち点火すれば差は帳消しになる。
     */
    public float infraredSignature() {
        AircraftDefinition.Afterburner burner = this.getStats().engine().afterburner().orElse(null);
        float clean = this.getStats().signature().heat();

        float lit = burner == null ? clean : clean * burner.heatFactor(this.reheat);

        return lit * this.weapons.heatGain();
    }

    /**
     * 赤外線シーカーがそのエンティティをどこまで見えるか。同じシーカーが最も熱い目標に対して出す距離に対する
     * 割合。ここでは航空機以外は全てその最も熱い目標扱い——つまり何も決めていない、ということだ。
     */
    public static float heatVisibility(Entity entity) {
        return entity instanceof AircraftEntity aircraft
                ? AircraftDefinition.Signature.heatReach(aircraft.infraredSignature())
                : 1.0F;
    }

    /**
     * 飛行1tick分。この機体が従うモデルで実行する。
     *
     * <p>モデルは2つあり、切り替えスイッチ付きの1機ではなく本当に別種の機体だ。固定翼機は前へ投げ出され、通過する
     * 空気に支えられる。ヘリは自分で気流を作り、静止したままそれに支えられる。どちらを使うかはファイルが決める。
     * {@code rotor} ブロックがあればヘリ、それ以外に判定材料は無い。
     */
    private void flightTick() {
        AircraftDefinition definition = this.getStats();
        AircraftDefinition.Rotor rotor = definition.rotor().orElse(null);

        if (rotor == null) {
            this.wingFlightTick(definition);
        } else {
            this.rotorFlightTick(definition, rotor);
        }
    }

    /**
     * 主翼で飛ぶ1tick分。
     *
     * <p>機体は機首方向へ押し出されるのではない。推力は機首方向に、重力は下向きに働き、主翼は気流に対して直角に、
     * 気流と成す角に比例した揚力を生む。「航空機らしさ」の全てはそこから出てくる。離陸には機首上げが要り、バンクは
     * 揚力が主翼と共に傾くから旋回になり、失速角を超えて機首を引き上げれば落ち、きつい旋回は揚力がただではない
     * ので速度を失う。
     */
    private void wingFlightTick(AircraftDefinition definition) {
        AircraftDefinition.Wing wing = definition.wing();
        AircraftDefinition.Handling handling = definition.handling();
        AircraftDefinition.Undercarriage gear = definition.landingGear();
        boolean rolling = this.onGround();

        Vec3 motion = this.getDeltaMovement();
        double speed = motion.length();

        // ゲートはレバー移動より先に処理する。ゲートはレバーの脇ではなくレバーの可動域の中にあるからだ。
        // スロットルを引いたとき最初に起きるのはアフターバーナーからの離脱であり、そのtickではそれだけが起きる。
        // tickAfterburner 参照。
        if (this.flameout()) {
            // タンクが空。レバーがどこにあろうとエンジンには入れる物が無い。
        } else if (!this.tickAfterburner(definition)) {
            this.setThrottle(this.throttle + this.input.throttle() * definition.engine().throttleRate());
        }

        // レバーは即座に動くがエンジンは動かない。全開を要求されたターボファンが応えるまでには数秒かかり、
        // 離陸滑走の感触の大半はその待ち時間だ。燃料が尽きた場合もこれが働き、推力は瞬時に消えるのではなく
        // 数秒かけて落ちる。実機のフレームアウトもそう感じられる。
        float spool = Mth.clamp(definition.engine().spoolRate(), 0.01F, 1.0F);
        this.thrustLevel += (this.throttle - this.thrustLevel) * spool;

        // 空気は高度と共に薄くなり、エンジンも主翼もそれを相手にしている。機体に上昇限度がある理由はそれが
        // 全てだ。十分上れば、パイロットが機首をどうしようと重量に必要な揚力を作る空気が無くなる。
        double density = this.airDensity();

        // ノズルの振れ量。エンジン出力のうち、前へ押すのではなく機体を支えている割合として扱う。転換能力の
        // 無い機体では0。
        AircraftDefinition.Vtol vtol = definition.vtol().orElse(null);
        double lifting = vtol == null
                ? 0.0
                : Math.sin(Math.toRadians(this.vtolProgress * vtol.maxAngle()));

        // 舵面は空気が流れている間しか効かず、その相手は速度そのものではなく動圧——空気密度×速度の2乗——だ。
        // 海面高度の失速速度で1になるので、ファイルの角速度はこれまで通りの意味を保つ。それ未満では旧値より小さく、
        // それ以上では大きい。これが「失速近くで甘い機体」と「単に鈍く感じる機体」の違いになる。
        double reference = Math.max(wing.stallSpeed(), 1.0E-4F);
        double pressure = density * speed * speed / (reference * reference);
        // 動圧が尾翼と舵面に与える分。下で VTOL の噴流がパイロットの分だけを引き上げるので、区別して保持する。
        float aeroAuthority = (float) Math.min(pressure, AUTHORITY_CEILING);
        float authority = aeroAuthority;

        // そしてパイロットに権限を与えるのと同じ舵面が、生じた回転を減衰させる。これが無いと権限だけが速度と
        // 共に上がり、それを収める物が伴わないので、高速機は実機のように硬くなる代わりにふらつく。
        //
        // 減衰も権限と同じ飽和値から取る。同じ舵面なのだから、与える力が頭打ちなら奪う力も頭打ちだ。以前は
        // 権限だけが {@link #AUTHORITY_CEILING} で止まり、減衰は動圧のまま速度の2乗で伸び続けたので、旋回率は
        // その商として高速域で崩壊した——1000km/h でコーナー速度の1/4、1500km/h で1/8。マッハで飛ぶ機体が
        // 泥の中を旋回することになり、それは「硬い」ではなくただの故障だ。両方が同じ所で止まれば、コーナー
        // 速度で得た旋回率を機体はそのまま持ち続ける。それ以上の味付けはファイルの aero_damping の仕事だ。
        float damping = 1.0F + handling.aeroDamping() * aeroAuthority;

        // 揚力系は主翼を気にしない。ホバリング中の機体を飛ばすのは自前の噴流であり、それが無ければ主翼が働かなく
        // なった瞬間からパイロットはレンガの操縦桿を握ることになる。だがその噴流もエンジンの物で、アイドルまで
        // 絞られたエンジンは偏向させる推力を供給していない——なので下の力と同じスプール済み推力で拡縮する。
        // スロットルを下限に置いたままではホバリングの維持どころか、その場旋回もできない。
        if (vtol != null) {
            authority = Math.max(authority, (float) (lifting * vtol.authority()) * this.thrustLevel);
        }

        // パイロットの操縦桿に残る効き。ファイルの角速度に対する倍率であり、下限を持つ。
        //
        // 下限が要る理由は、この商が速度と共に0へ落ちるからだ。低速では動圧が小さく、権限はそれに比例する
        // ので、失速に近づくにつれ操縦桿は何にも繋がっていない棒になる。空力としては正しいが、遊びとしては
        // 「機体が壊れた」としか読めない——落ちていく機体で操縦桿を引いても何も起きない、という状態だ。
        // 半分を残せば、パイロットは常に機体を向け直す手段を持つ。実機の低速域より効くが、実機のパイロット
        // は画面越しではなく座席から機体を感じている。
        //
        // 尾翼には掛けない。あちらは下の weathervane が別に扱う。風見に下限を与えると、動圧の無いホバリング
        // 中の機体が「飛行経路」——垂直上昇なら真上——へ機首を叩き込まれて背面へ裏返る。パイロットの舵と
        // 尾翼の空力は別物であり、下限を持ってよいのは前者だけだ。
        // 倍率は下限より内側に掛ける。下限は「遅くても機体を向け直せる」という遊びの保証であって機動性の設定
        // ではないので、機体を鈍くする調整に巻き込んで一緒に下げてはならない。順序を逆にすると、鈍くするほど
        // 低速域の救済まで薄くなり、最も操縦を必要とする場面が最も効かなくなる。
        float control = Math.max(authority / damping * CONTROL_SCALE, TURN_RATE_FLOOR);

        float previousYRot = this.getYRot();
        float weathervaneYaw = 0.0F;
        float weathervanePitch = 0.0F;

        // 指令角速度。即座にではなく数tickかけて到達する。舵面は機体の質量を相手にするからだ。これらはワールド
        // 軸ではなく機体自身の軸周りの角速度だ。昇降舵は機首をキャノピー天頂の向きへ振るので、バンクした機体は
        // 上昇せず旋回へ引き込まれる。
        float lag = Mth.clamp(handling.controlLag(), 0.02F, 1.0F);

        float commandedPitch = this.limitToWing(
                this.input.pitch() * handling.pitchRate() * control, lifting);

        // 接地中は、水平尾翼を持ち上げるだけの空気が流れるまで機首は上がらない。その速度こそ離陸を離陸たらしめ
        // る。機体は走り、操縦桿はどれだけ引いても何も起きず、そして数ノットのうちに機首が軽くなって上がる——その
        // 頃には主翼はもう飛べる状態に近く、機体は自力で地面を離れる。このゲートが無いとパイロットはその場で機首
        // を上げ、尾部を滑走路に擦りながら主翼が追い付くのを待つ姿勢で座り込むことになる。
        float wheels = rolling ? this.rotationAuthority(wing, speed) : 1.0F;

        // 符号を問わず掛ける。以前は機首上げ側だけを絞っていたが、滑走路上の機首下げに押す相手は無い——前輪は
        // もう地面に着いている——ので、通してよい理由は初めから無かった。そしてマウスで飛ばす機体の舵はちょうど
        // 0になることがほぼ無いので、地上の機体は毎tick幾らかの機首下げを指令され続け、車輪が機体を地面の線へ
        // 戻す力と釣り合う角度で座り込む。機首はその角度ぶん滑走路の中へ沈み、駐機した機体が地面にめり込んで
        // 見えていた。
        if (rolling) {
            commandedPitch *= wheels;
        }

        float commandedRoll = this.input.roll() * handling.rollRate() * control;

        // 補助翼も同じ理由で同様に。滑走路上で機体の翼を水平に保っているのは操縦系ではなく降着装置だ。ゲートを
        // 設けないと補助翼がじわじわ勝つ——車輪は毎tick機体を1/4だけ水平へ戻し、補助翼は加え続けるので、車輪が
        // しっかり接地したまま舵一杯で数度のバンクに落ち着いてしまう。主翼が荷重を受け持つにつれて効き始める。
        // 実機で本当に効き始めるのもそこだ。
        if (rolling) {
            commandedRoll *= wheels;
        }

        this.pitchVelocity += (commandedPitch - this.pitchVelocity) * lag;
        this.rollVelocity += (commandedRoll - this.rollVelocity) * lag;
        this.yawVelocity += (this.input.yaw() * handling.yawRate() * control - this.yawVelocity) * lag;

        // 前輪操舵。これはまったく別物で、機体が駐機場から自走で向きを変えられる理由だ。地上の車輪は垂直尾翼を
        // 流れる空気の速さを気にしないので、気流が与える操舵権限を迂回し、操縦ラグを通さず即座に応える。方向舵が
        // 引き継ぐにつれて手放す。高速でも噛む前輪は、機体を滑走路に沿わせるどころか外へ放り出してしまうからだ。
        //
        // ただしエンジンは要る。前輪を動かすのは他の全てと同じエンジンから取る油圧か電力であり、スロットルを下限に
        // 置いた冷えきった機体には回す余裕が無い——なので飛行操縦系と同じスプール済み推力で拡縮する。何もして
        // いないスロットルでは、駐機中の機体はその場で向きを変えられない。
        float nosewheel = 0.0F;

        if (rolling) {
            float grip = (float) Mth.clamp(1.0 - speed / Math.max(gear.steerFade(), 1.0E-3F), 0.0, 1.0);

            nosewheel = this.input.yaw() * gear.steerRate() * grip * this.thrustLevel;
        }

        Vec3 nose = this.getNoseVector();
        Vec3 up = this.getLiftVector();
        Vec3 right = Attitude.right(this.attitude);

        // 推力は機首方向、機体真下、あるいはその中間へ。ノズルを下げることは同じ押しの向きを変えるだけでは
        // ない。揚力系は取り付け元の巡航エンジンより大きな値を持つし、そうでなければならない。停止状態で機体を
        // 支える物はエンジンしか無く、エンジンはそれで重力に勝たねばならないからだ。
        Vec3 thrustAxis = lifting <= 0.0 ? nose : nose.scale(Math.cos(Math.asin(lifting))).add(up.scale(lifting));
        double thrust = vtol == null
                ? definition.engine().maxThrust()
                : Mth.lerp(lifting, definition.engine().maxThrust(), vtol.liftThrust());

        // 吸い込んでいる空気に対して、レバーの要求値ではなくエンジンの実出力に対して、さらにその上へバーナー
        // を乗せて計算する。
        Vec3 forces = new Vec3(0.0, -GRAVITY, 0.0)
                .add(thrustAxis.scale(thrust * this.thrustLevel * this.reheatThrust() * density));

        // そしてホバリングは滑走ではない。歩行速度では空力は何も効かない——抗力は2乗則で、小さい数の2乗は無に
        // 等しい——ので、ホバリング中に横へ押された機体は何かに当たるまで横へ流れ続ける。これは揚力系による
        // 位置保持であり、ホバリングと「ただゆっくり落ちている」ことの違いだ。
        //
        // 主翼が引き継ぐにつれて手放す。保持することより、この解放の方が重要だ。全速度域で効かせたままだと位置
        // 保持ではなくパーキングブレーキになる。ホバリングから加速しようとする機体は失速速度の何分の一かで止まり、
        // 主翼は飛び始めず、転換自体が不可能になる。
        if (lifting > 0.0 && speed > 1.0E-4) {
            double band = Math.max(wing.stallSpeed() * HOVER_BAND, 1.0E-4);
            double slow = Mth.clamp(1.0 - speed / band, 0.0, 1.0);

            forces = forces.add(motion.scale(-vtol.hoverDrag() * lifting * slow));
        }

        double lift = 0.0;

        if (speed > 1.0E-4) {
            Vec3 flow = motion.scale(1.0 / speed);

            // 迎角。空気が主翼のどれだけ下から来ているか。
            this.angleOfAttack = (float) Math.toDegrees(Math.asin(Mth.clamp(-flow.dot(up), -1.0, 1.0)));
            double liftCoefficient = wing.liftCoefficient(this.angleOfAttack)
                    * (1.0 + this.flapsProgress * this.getFlapsLiftBonus())
                    * this.sweepLift()
                    * this.groundEffect();

            // 揚力は気流に直角に働き、主翼と共に傾く。その傾きが機体を旋回させる。バンクすれば、支えていた
            // のと同じ力が機体を回し始める。
            Vec3 liftAxis = up.subtract(flow.scale(up.dot(flow)));

            lift = wing.lift() * liftCoefficient * speed * speed * density;

            if (liftAxis.lengthSqr() > 1.0E-8) {
                forces = forces.add(liftAxis.normalize().scale(lift));
            }

            // 抗力。形状のコストと揚力のコストの和。きつい旋回で速度が落ちるのは後者のせいだ。
            // 吊っている物の抗力は主翼の抗力へ足す。空気は何が垂れ下がっているかを気にしないし、増槽が
            // 「ただ航続距離が伸びるだけの選択」にならないのはこれのおかげだ——燃料と引き換えに速度と旋回を
            // 差し出すので、積むかどうかが判断になる。抗力を書いていない兵装は0を足す。
            // 可変翼は形状抗力そのものに掛かる。翼を後退させて減るのは翼が支払っている分であって、外に吊った
            // 物が支払っている分ではない——増槽の抵抗は翼がどこにあろうと変わらない。だから括弧の中、兵装の
            // 抗力を足す前に掛ける。
            double parasitic = (wing.drag() * this.sweepDrag() * (1.0
                    + this.gearProgress * this.getGearDragPenalty()
                    + this.flapsProgress * this.getFlapsDragPenalty())
                    + this.weapons.storeDrag())
                    * (this.input.brake() ? wing.airBrakeDrag() : 1.0);
            double drag = parasitic + wing.inducedDrag() * liftCoefficient * liftCoefficient;
            forces = forces.add(flow.scale(-drag * speed * speed * density));
            this.checkStructuralLoad(motion);

            // 垂直尾翼は機首を飛行経路へ引き戻す。方向舵同様、機体自身の垂直軸周りに働くので、背面では機体
            // に対しては同じ向き、ワールドに対しては逆向きに引く。実際の垂直尾翼と同じだ。ただし脚が出ている
            // 間は働かせない。滑走路上で機首の向きを決めるのは降着装置であり、垂直尾翼がそれと争うと機体は
            // センターラインを蛇行することになる。
            if (!rolling) {
                // 風見は尾翼の仕事であり、尾翼の権限は空気力学の分——動圧の分——だけだ。VTOL の噴流権限
                // （上の authority に入っている）をここへ貸してはならない。垂直に浮き上がった機体の「飛行経路」
                // は真上であり、推力いっぱいの風見は機首をそこへ叩き込んで機体を背面へひっくり返す。降下中なら
                // 同じ物が機首を真下へ叩き込む。ホバリングの漂いは気流ではないので、尾翼は黙っているのが正しい。
                // パイロットの操縦だけが噴流の権限を受け取る——ノズルの反力操縦はまさにそれ用の装備だ。
                weathervaneYaw = (float) (flow.dot(right) * handling.weathervane() * aeroAuthority / damping);

                // 水平尾翼は機体自身の横軸周りに同じことをし、垂直尾翼が横滑りから引き戻すのと同様に、高迎角
                // から機首を引き下げる。これは迎角リミッターを完全に迂回する——これはリミッターではなく、
                // リミッターが前に立っている当のものだ——ので、失速の深部でも機首を気流へ引き戻し続ける。そこでは
                // {@link #limitToWing} が既にパイロットの操縦桿を無効まで落としている。これが無いと失速機は最後に
                // 操縦桿が残した向きを指したまま、重力だけが実際の行き先を決めることになる。待つ以外に抜け道の
                // 無いテールスライドだ。
                weathervanePitch = (float) (flow.dot(up) * handling.weathervane() * aeroAuthority / damping);

                // そして胴体は横向きに飛ぶことを拒む。
                motion = motion.subtract(right.scale(motion.dot(right) * wing.lateralDrag()));
            }
        } else {
            this.angleOfAttack = 0.0F;
        }

        // 荷重のうちタイヤに残っている割合。下の地上操作用に保持する。離陸滑走の終盤が最後の瞬間まで食い付か
        // ずに軽くなるのはこれのおかげだ。
        this.weightOnWheels = (float) Mth.clamp(1.0 - lift / GRAVITY, 0.0, 1.0);

        this.applyBodyRotation(this.rollVelocity, this.pitchVelocity + weathervanePitch,
                this.yawVelocity + weathervaneYaw + nosewheel);
        this.deltaRotation = Mth.wrapDegrees(this.getYRot() - previousYRot);
        motion = motion.add(forces);

        if (rolling) {
            motion = this.groundTick(motion);
        }

        // 暴走を止める最後の砦であり、ファイルが要求した場合のみ。最高速度は抗力が自ずと決める。
        if (wing.maxSpeed() > 0.0F && motion.length() > wing.maxSpeed()) {
            motion = motion.normalize().scale(wing.maxSpeed());
        }

        this.setDeltaMovement(motion);
    }

    /**
     * ローターで飛ぶ1tick分。
     *
     * <p>ヘリの全ては1つの力だ。ローターは自身のディスクに直角に引き、ディスクは下にぶら下がる機体に直角で、
     * だから機体を向けることが唯一の操縦になる。機首を下げれば前進し、バンクすれば横へ動き、水平なら留まる。
     * 以下のどこにも機首方向の推力は無い。ヘリはそれを持たないからだ。前へ運ぶのは支えているのと同じ力の一部で
     * あり、まさにそれが巡航中に機首下げになる理由であり、コレクティブを急に引くと加速ではなく上昇する理由だ。
     *
     * <p>よってサイクリックはディスクを動かしてそのまま置き、キーを離した瞬間に水平へ戻したりしない。現代のヘリ
     * が全て備える姿勢保持装置の働きであり、キーボードで巡航を要求する唯一の方法でもある。キーは押し切りか非押下
     * のどちらかなので、水平へ戻る操縦桿では機体はホバリングと全力前進の2設定しか持てず、その中間がまったく無く
     * なる。水平に戻すのはパイロットの仕事だ。実機とまったく同じ。機体が自分で譲らないのは傾斜限界だけで、それを
     * 超えるとディスクを戻す——だからここのヘリは、パイロットにも爆風にも丘への激突にもひっくり返されない。
     *
     * <p>空力は依然として全て存在し、固定翼機と同じ意味を持つ——抗力、垂直尾翼、ガンシップの持つスタブウイング
     * ——が、いずれも速度の2乗に比例し、ヘリは小さい数の2乗が無に等しい速度域で生涯を過ごす。それが要点だ。
     * この機体を飛ばすのはローターであり、ローターはどこかへ行こうとしているかどうかを気にしない。
     */
    private void rotorFlightTick(AircraftDefinition definition, AircraftDefinition.Rotor rotor) {
        AircraftDefinition.Wing wing = definition.wing();
        AircraftDefinition.Handling handling = definition.handling();
        AircraftDefinition.Undercarriage gear = definition.landingGear();
        boolean rolling = this.onGround();

        Vec3 motion = this.getDeltaMovement();
        double speed = motion.length();

        // コレクティブ。固定翼機のスロットルと同じレバー・同じキーだが、仕事はまるで違う。機体の速さではなく
        // ローターの引きの強さ、つまり上がるか下がるかを決める。
        //
        // 燃料が尽きればレバーは何にも繋がっていない。ローターは tickRotor で回転を落としていき、揚力は
        // 回転数の2乗で消えるので、機体はオートローテーション——制御された降下——に入る。ローターが回って
        // いる間は操縦できるので、降りる場所を選ぶ余地は残る。
        if (!this.flameout()) {
            this.setThrottle(this.throttle + this.input.throttle() * definition.engine().throttleRate());
        }

        // ブレードピッチは即座に応える。ヘリで時間がかかるのはローター自体であり、それはここではなく
        // tickRotor で回転を上げる。
        float spool = Mth.clamp(definition.engine().spoolRate(), 0.01F, 1.0F);
        this.thrustLevel += (this.throttle - this.thrustLevel) * spool;

        double density = this.airDensity();
        float collective = Mth.clamp(this.thrustLevel, 0.0F, 1.0F);

        // 揚力はブレードの回転速度の2乗に比例するので、半速のローターは揚力1/4しか出せず機体はどこへも行けない。
        // 乗り込んだ後の待ち時間が実際に買っているのはこれだ。
        double turning = (double) this.rotorSpeed * this.rotorSpeed;

        // 垂直尾翼とスタブウイング。固定翼機の構成であり同じように振る舞う。動圧に依存し、機体が静止している
        // 間はまったく効かない。
        double reference = Math.max(wing.stallSpeed(), 1.0E-4F);
        double pressure = density * speed * speed / (reference * reference);
        float damping = 1.0F + handling.aeroDamping() * (float) pressure;

        // そして実際にヘリを飛ばしている物、つまりローター。上のいずれにも影響されない。停止中の機体が完全な
        // 操縦性を持ち、高速の機体もそれ以上は持たない。固定翼機と正反対であり、ヘリが林間の空き地へ降りられる
        // 理由の全てだ。
        float bite = Math.min(this.rotorSpeed * this.rotorSpeed * rotor.authority(), 1.0F);

        float previousYRot = this.getYRot();
        float weathervaneYaw = 0.0F;
        float lag = Mth.clamp(handling.controlLag(), 0.02F, 1.0F);
        float tilt = Mth.clamp(rotor.maxTilt(), 1.0F, 89.0F);
        float trim = Mth.clamp(rotor.trim(), 0.01F, 1.0F);

        // サイクリックはディスクを動かして置いた場所に残す。姿勢保持装置の働きであり、キーボードで巡航を要求
        // できる唯一の方法だ。キーは押し切りか非押下しかないので、水平へ戻る操縦桿では機体はホバリングと全力
        // 前進の2設定しか持てず、その中間がまったく無くなる。
        //
        // 差し引いているのはリミッターで、機体が限界内にいる間——生涯の大半——はまったくの0だ。限界を超えると
        // ディスクを戻すので、ヘリはパイロットにも爆風にも丘への激突にもひっくり返されない。Minecraft の仰角は
        // 機首下げが正なので、このクラスの他の部分で使う機首上げ表記へ符号を反転している。バンク角は右翼下げが
        // 既に正。
        float commandedPitch = Mth.clamp(
                this.input.pitch() * handling.pitchRate() * trim
                        - overTilt(-this.getXRot(), tilt) * rotor.stability(),
                -handling.pitchRate(), handling.pitchRate()) * bite;
        float commandedRoll = Mth.clamp(
                this.input.roll() * handling.rollRate() * trim
                        - overTilt(this.getRoll(), tilt) * rotor.stability(),
                -handling.rollRate(), handling.rollRate()) * bite;

        // 接地中、サイクリックは降着装置と争って負ける。それでよい。駐機中のヘリを水平に保つのは車輪だ。
        // ローターが荷重を受け持つのと同時に効き始める。実機がスキッド上で軽くなり、飛ばされ始める瞬間だ。
        if (rolling) {
            float airborne = 1.0F - this.weightOnWheels;

            commandedPitch *= airborne;
            commandedRoll *= airborne;
        }

        // ペダル。角度ではなく角速度だ。テールローターは機首を振ってそのまま置き、機体はどこへも動かない。
        // 固定翼機の方向舵にはまったくできない芸当で、ヘリが進行方向と別の向きを向けられる理由だ。
        float commandedYaw = this.input.yaw() * handling.yawRate() * bite / damping;

        this.pitchVelocity += (commandedPitch - this.pitchVelocity) * lag;
        this.rollVelocity += (commandedRoll - this.rollVelocity) * lag;
        this.yawVelocity += (commandedYaw - this.yawVelocity) * lag;

        // 操向可能な尾輪。前輪操舵と同じ物で、ここにある理由も同じだ。地上を転がっている間、ペダルはローター
        // ではなく車輪を回す。エンジンを必要とする理由も同じ。コレクティブを下限に置き出力がどこへも行っていない
        // 状態では何も操向しない。
        float nosewheel = 0.0F;

        if (rolling) {
            float grip = (float) Mth.clamp(1.0 - speed / Math.max(gear.steerFade(), 1.0E-3F), 0.0, 1.0);

            nosewheel = this.input.yaw() * gear.steerRate() * grip * this.thrustLevel;
        }

        Vec3 up = this.getLiftVector();
        Vec3 right = Attitude.right(this.attitude);
        Vec3 nose = this.getNoseVector();

        double disc = this.rotorLift(rotor, speed);
        Vec3 forces = new Vec3(0.0, -GRAVITY, 0.0).add(up.scale(disc));

        // 上下から来る空気はローターとその下に吊られた全てに横腹から当たり、前から来る空気は胴体に当たる。
        // 差は1桁近くあり、ヘリの上昇率がフィート/分で、隣の速度がノットで示される理由でもある——胴体自身の
        // 抗力では両方の数字を説明し始めることすらできない。コレクティブを下げたとき機体が落下に抗う相手でも
        // あり、ローターと連動するので、ローターが止まった機体は今やそうである金属の塊として落ちる。
        double sink = motion.y;

        forces = forces.add(new Vec3(0.0,
                -rotor.discDrag() * sink * Math.abs(sink) * turning * density, 0.0));

        // 胴体はローターにぶら下がっており、一方向に回るローターは取り付け先を逆方向へ押す。テールローターが
        // その答えだが、これはコレクティブに追従するので、上昇を要求するたび機首が振れる——手動でヘリを飛ばす
        // 作業の大半はそれだ。
        float torqueYaw = (float) (rotor.torque() * collective * turning);

        double lift = 0.0;

        if (speed > 1.0E-4) {
            Vec3 flow = motion.scale(1.0 / speed);

            this.angleOfAttack = (float) Math.toDegrees(Math.asin(Mth.clamp(-flow.dot(up), -1.0, 1.0)));

            // スタブウイング。ガンシップでは本物の主翼であり、高速時には機体の相応の割合を支える——兵装を
            // 吊る場所でもあり、そちらが本来の目的だ。ここでは失速しない。臨界角を超えると主翼は単に助けるのを
            // やめるだけで、どのみち仕事をしていたのはローターだ。
            double liftCoefficient = wing.liftCoefficient(this.angleOfAttack);
            Vec3 liftAxis = up.subtract(flow.scale(up.dot(flow)));

            lift = wing.lift() * liftCoefficient * speed * speed * density;

            if (liftAxis.lengthSqr() > 1.0E-8) {
                forces = forces.add(liftAxis.normalize().scale(lift));
            }

            // 抗力。しかも相当な量だ。ヘリは誰も速く飛ぶために選んでいない形状であり、最高速度を決めるのは
            // これに対してディスクの傾斜が尽きる点だ。
            //
            // どちらを向いているかがここでは効き、固定翼機では効かない。ヘリは好きな方向へ飛べるが固定翼機は
            // 飛べないからだ。胴体は前進のための形状で、後ろ向きにすれば納屋の扉になる。明記しなければ、機体は
            // 後ろ向きに飛んで前進最高速度に達してしまう。
            double bluff = Mth.lerp((1.0 - Mth.clamp(flow.dot(nose), -1.0, 1.0)) * 0.5,
                    1.0, Math.max(rotor.bluffDrag(), 1.0F));
            double drag = wing.drag() * bluff * (this.input.brake() ? wing.airBrakeDrag() : 1.0)
                    + wing.inducedDrag() * liftCoefficient * liftCoefficient;

            forces = forces.add(flow.scale(-drag * speed * speed * density));
            this.checkStructuralLoad(motion);

            if (!rolling) {
                // 垂直尾翼。ここの空力全般と同じ動圧で弱まるので、ホバリング中の機体が、ほとんど存在しない
                // 飛行経路へ引き回されることはない。
                double aerodynamic = Math.min(pressure, AUTHORITY_CEILING);

                weathervaneYaw = (float) (flow.dot(right) * handling.weathervane() * aerodynamic / damping);

                // そして胴体の横飛び嫌い。これも同様に薄れていく。固定翼機では決して薄れない。横に飛べと
                // 要求されないからだ。ヘリは常時それを要求されるし、拒めばヘリの価値の半分が失われる。
                motion = motion.subtract(right.scale(motion.dot(right) * wing.lateralDrag()
                        * Mth.clamp(pressure, 0.0, 1.0)));
            }

            // ホバリングは滑走ではない。歩行速度では空力は何も効かないので、これが無いと横へ押された機体は
            // 何かに当たるまで流れ続ける。速度が乗るにつれて手放す。さもなければホバリングではなくパーキング
            // ブレーキになる。
            //
            // 見た目より弱く設定する必要があり、その理由は記しておく価値のある罠だ。速度と共に増えてから薄れる
            // ので、帯域の途中——hover_drag の1/4×帯域——で頂点を持つ。その頂点は、機体がどこかへ行く前に越え
            // させられる壁になる。大きく取ると位置保持ではなく閾値になり、穏やかな前進操作ではまったく何も起き
            // ず、ある点を超えた途端ヘリが飛び出す。頂点を、誰かが意図的に使う最小の傾斜より下に保てば、壁は
            // 床下という本来あるべき場所に収まる。
            double band = Math.max(rotor.translationalSpeed() * HOVER_BAND, 1.0E-4);
            double slow = Mth.clamp(1.0 - speed / band, 0.0, 1.0);

            forces = forces.add(motion.scale(-rotor.hoverDrag() * turning * slow));
        } else {
            this.angleOfAttack = 0.0F;
        }

        // 車輪がまだ支えている機体の割合。上のサイクリックゲートは1tick遅れでこれを読むが、誰にも見えない
        // 1tickであり、ローターを2度計算せずに済む。
        this.weightOnWheels = (float) Mth.clamp(1.0 - (disc + lift) / GRAVITY, 0.0, 1.0);

        this.applyBodyRotation(this.rollVelocity, this.pitchVelocity,
                this.yawVelocity + weathervaneYaw + nosewheel + torqueYaw);
        this.deltaRotation = Mth.wrapDegrees(this.getYRot() - previousYRot);
        motion = motion.add(forces);

        if (rolling) {
            motion = this.groundTick(motion);
        }

        if (wing.maxSpeed() > 0.0F && motion.length() > wing.maxSpeed()) {
            motion = motion.normalize().scale(wing.maxSpeed());
        }

        this.setDeltaMovement(motion);
    }

    /**
     * ローターの引き（ブロック/tick^2）。ヘリの揚力の全て。
     *
     * <p>インラインではなくここで算出するのは、計器が飛行モデルと同じ値を問えるようにするため。必要な物は全て
     * 同期済みか全側で同一に算出されるので、答えは物理を回しているかどうかに依存しない。
     *
     * <p>中の並進項は、ホバリング中のローターが既に下へ叩いて使い終わった空気を打っている状態を表す。機体を
     * 前進させれば各ブレードが手つかずの空気に届き、同じコレクティブでより多くの揚力が出る。垂直に浮けないほど
     * 重い機体でも地上滑走からなら飛び立てることが多い理由であり、離陸最初の数秒が「足場を見つけている」ように
     * 感じられる理由だ。
     */
    private double rotorLift(AircraftDefinition.Rotor rotor, double speed) {
        double translational = 1.0 + rotor.translationalLift()
                * Mth.clamp(speed / Math.max(rotor.translationalSpeed(), 1.0E-4F), 0.0, 1.0);

        return rotor.lift() * Mth.clamp(this.thrustLevel, 0.0F, 1.0F)
                * this.rotorSpeed * this.rotorSpeed
                * this.airDensity() * translational * this.groundEffect();
    }

    /**
     * 機体が姿勢を保持してよい角度を、どれだけ超えたか（度）。その角度内——ヘリが生涯を過ごす範囲——では0。
     * 戻り値は符号付きなので、指令角速度から引けばディスクは近い側の経路で戻る。
     */
    private static float overTilt(float angle, float limit) {
        return angle - Mth.clamp(angle, -limit, limit);
    }

    /**
     * 脚が出ている間、昇降舵が機首上げ指令をどれだけ実際に出せるか。
     *
     * <p>ローテーション速度の少し手前まではまったく0で、そこから滑走の終盤にかけてフェードインする。閾値を
     * 越えた瞬間に跳ね上がるのではなく機首が軽くなる形にするためだ。結果はパイロットが期待する離陸になる。
     * 加速し、機体が軽くなるのを感じ、機首をそっと上げ、飛び立つ——歩行速度で尾部を接地させて機体を立てるのでは
     * なく。
     */
    private float rotationAuthority(AircraftDefinition.Wing wing, double speed) {
        float rotate = wing.effectiveRotateSpeed();

        if (rotate <= 0.0F) {
            return 1.0F;
        }

        double band = rotate * ROTATION_FADE;

        return (float) Mth.clamp((speed - (rotate - band)) / band, 0.0, 1.0);
    }

    /**
     * 機体が地面に接するはずの部位が車輪かどうか。
     *
     * <p>脚が出ていて、その上で機体が正立していること。既に接地しているかどうかは一切問わない。それが降着装置が
     * 機体を保持している形態であり、車輪があることから導かれる3つの事柄——形状が当たらず擦り抜ける物、箱が
     * ぶつからず乗り上げる物、機体が滑走路へ押さえ付けられているか——すべての背後にある唯一の問いだ。3つは同じ
     * 問いでなければならない。形状は通れて箱は通れない縁石は、何も無い所に建った壁になる。
     *
     * <p>{@code onGround} を意図的に使わない。あのフラグは直前の衝突の残りかすで、滑走路上で true になるのは
     * {@link #groundTick} が車輪を地面へ押し込んでいるからにすぎない。ここで参照すると降着装置がそれに合わせて
     * 出たり消えたりしてしまう。
     */
    private boolean onWheels() {
        return this.gearProgress > 0.5F && this.getLiftVector().y > UPRIGHT;
    }

    /**
     * 機体がぶつからず乗り越える段差の高さ。
     *
     * <p>脚が出ているときのみ。脚を上げた空中では何も乗り越えないし、胴体着陸には乗り越える車輪が無い。脚が出て
     * いれば、これが降着装置と壁の違いになる——当たり判定は差し渡し6ブロックの正方形の箱1つなので、これが無いと
     * その下のどこかにある1ブロックの縁が正面衝突になる。しかも機体は飛ぶために自身の墜落速度を超えねばならない
     * ので、完全に平坦でない地面からの離陸は毎回その衝突で致命的になっていた。
     *
     * <p>機体形状が擦り抜けるのと同じ線であり、理由も同じ——{@link #onWheels} 参照。車輪が何かに接地しているかを
     * 問わなくても失う物は無い。バニラがこれを参照するのは移動が止められ、機体が地上に立っているか降りてきている
     * ときだけなので、これが加えるのは「脚を出した接地時に、当たった縁石で急停止せず乗り上げる」ことだけだ。段差
     * より高い物はここへ来る前に {@link #limitToShape} が移動から取り除いている。
     */
    @Override
    public float maxUpStep() {
        return this.onWheels() ? this.getStats().landingGear().climbHeight() : 0.0F;
    }

    /**
     * 機体を自機軸周りに回す。姿勢が2つの角度ではなく回転なので、ここにクランプすべき物は無く、機体が向けない
     * 方向も無い。宙返りも背面ロールも、垂直越えも、折り返しを起こさずにこなす。
     *
     * @param roll 機首軸周りの角速度。右が正
     * @param pitch 翼軸周りの角速度。機首上げが正
     * @param yaw 機体垂直軸周りの角速度。機首右が正
     */
    private void applyBodyRotation(float roll, float pitch, float yaw) {
        this.setAttitude(Attitude.rotate(new Quaternionf(this.attitude), roll, pitch, yaw));
    }

    /**
     * 主翼が最も強く引く角度で機体を保持し、パイロットがそこを通り越して引き切るのを防ぐ。
     *
     * <p>昇降舵は、主翼が飛行経路を追えるより数倍速く機首を振れる。放置すると、強く引いたパイロットは0.3秒で
     * 失速角に達し、旋回ではなく落下することになる。きつい旋回を要求したことへの報いとしてはひどい。よって迎角が
     * 限界に近づくにつれ操縦桿をフェードアウトさせる。悪化させる方向のみで、荷重を抜く操作は常に許可され、逆へ
     * 引く操作も許可される。
     *
     * <p>結果は限界のすぐ下に落ち着く。そこがまさに最小旋回半径の点だ。
     */
    private float limitToWing(float commanded, double lifting) {
        AircraftDefinition.Handling handling = this.getStats().handling();
        AircraftDefinition.Wing wing = this.getStats().wing();

        // 滑走路上では働かせない。地上を転がっている間、迎角は単に機体が座っている角度であり、離陸に必要な
        // 機首上げは失速角の大半を占める。それを失速の予兆と読むリミッターは、パイロットが機体に必要な唯一の
        // ことを要求したまさにその瞬間に操縦桿を殺し、離陸は「上げることを許されなかった機首に主翼が追い付くの
        // を延々と待つ作業」になる。
        if (this.onGround()) {
            return commanded;
        }

        // 主翼が失速する角度。ファイルがそこで保持したい場合に使う。未設定——{@code alpha_limit} が1——なら、
        // 他に制限の無い機体は従来通り失速を越えて飛べる。
        float limit = handling.alphaLimit() < 1.0F
                ? wing.stallAngle() * handling.alphaLimit()
                : Float.MAX_VALUE;

        // ここには荷重によるリミッターも居た。速いほど許される迎角を下げ、{@code max_g} に達する角度で操縦桿を
        // フェードアウトさせる物で、意図的に外してある。
        //
        // 理屈は正しかったが、操縦席では「引いたのに機体が戻される」としか読めなかった。速度が上がるほど許され
        // る角度は下がるので、パイロットが最も強く引きたい瞬間——速い機体できつい旋回に入る瞬間——にちょうど
        // 操縦桿が抜ける。しかも抜ける理由は画面のどこにも出ない。荷重計だけが理由を知っていて、それは数字が
        // 動くだけの1行だ。守られていることが分からない保護は、故障と区別が付かない。
        //
        // 過荷重そのものは今も存在する。{@link #checkStructuralLoad} が機体を歪ませるので、引き切れば主翼は
        // 外れる。違いは、それがパイロットの選択の結果になったことだ——リミッターは選ばせずに防いでいた。
        // 迎角リミッター（上の {@code alpha_limit}）は残してある。あちらが防ぐのは失速であって荷重ではなく、
        // 失速は「引いたのに曲がらない」であって「引いたのに戻される」ではない。
        if (limit >= Float.MAX_VALUE || limit <= 0.0F || commanded * this.angleOfAttack <= 0.0F) {
            return commanded;
        }

        float bite = limit * ALPHA_LIMITER_BITE;
        float over = (Math.abs(this.angleOfAttack) - bite) / Math.max(limit - bite, 1.0E-3F);
        float limited = commanded * Mth.clamp(1.0F - over, 0.0F, 1.0F);

        // そしてリミッターは、揚力系が引き受けた荷重の割合に応じて道を譲る。真上へ上がる機体は自分の気流を真上
        // から受けるので主翼は迎角90度を読み、主翼の失速を防ぐために存在するリミッターは機首下げを一切許さなく
        // なる。垂直上昇で唯一意味のある操作がそれなのに、だ。ホバリングは主翼で飛んでいるわけではなく、そこに
        // 守るべき物は何も無い。
        return (float) Mth.lerp(lifting, limited, commanded);
    }

    /**
     * パイロットが設計耐Gを超えて引いた場合に機体を歪ませる。見ている側で適用する。操縦クライアントは自分が
     * 回している空力から算出し、サーバーは姿勢と移動距離から改めて算出するので、損傷は通知に依存しない。
     */
    private void checkStructuralLoad(Vec3 velocity) {
        float limit = this.getStats().airframe().maxG();

        if (limit <= 0.0F || this.level().isClientSide) {
            return;
        }

        float load = this.getLoadFactor(velocity);

        // 十分強く十分長く引けば、撃ち落とされるのを待たずに空中で主翼が外れる。
        if (load > limit && this.wound((load - limit) * OVER_G_DAMAGE)) {
            this.crash();
        }
    }

    /**
     * 接地中の処理。機体前後方向の転がり摩擦、横方向のスクラブ、ブレーキ、そして降着装置が許す姿勢。
     *
     * <p>タイヤは一方向へ転がり、直交方向へは擦れる。その差こそが、機体が滑走路上を滑り回らずに真っ直ぐ走る理由
     * の全てだ。以前のように地上速度を両方向へ均等に減衰させると、車輪の上の機体は氷上の機体のような振る舞いに
     * なる。
     *
     * <p>両方の値をタイヤに残る荷重で拡縮する。摩擦はそれを支える荷重を通して働くので、主翼が機体重量を受け持つ
     * につれ車輪はグリップを失い、離陸滑走は空中へ踏み出す瞬間まで食い付くのではなく終盤で軽くなる。
     *
     * <p>意図的に<em>入れていない</em>のが主脚周りの回転だ。実機は主脚を軸に回転し、機首が上がるにつれ重心が
     * 上がる。だがエンティティの箱は軸整列で傾かないので、その回転が滑走路へ押し込む物も、補正すべき物も無い。
     * それでも上昇分を足すと、入れないより悪くなる——主翼が作っていない鉛直速度なので、機首上げ動作自体が機体を
     * 持ち上げ、重力が地面へ落とし戻す。回転ではなくバウンドに見える。1秒ほどかけて機首が上がり、それに伴って
     * 主翼が荷重を受け持つ、というのが持つ価値のある効果の全てで、その両方はここで本物だ。
     */
    private Vec3 groundTick(Vec3 motion) {
        // 姿勢を決めるのはパイロットではなく車輪であり、車輪が載っているのは地面だ。翼は地面に沿い、機首はその
        // 線から尾部が許す最大の機首上げまでのどこかに収まる。
        Slope slope = this.groundSlope();
        float surface = -slope.pitch();

        // パイロットが保持している機首上げ。地面そのものの傾きではなく、そこからどれだけ引き起こしているかだ。
        // 差を取らないと、上り坂に立っているだけの機体が「もう引き起こし済み」と読まれ、坂の上では機首が上がら
        // なくなる。
        float rotation = Mth.clamp(this.getXRot() - surface, -GROUND_PITCH_LIMIT, 0.0F);

        // 機首上げを保持するのは、パイロットが実際に引いている間だけ。「入力がちょうど0か」ではなく「引いて
        // いるか」を問う。マウスで飛ばす機体の舵はちょうど0になることがほぼ無く、0との比較では一度上がった機首が
        // 滑走の残り全部にわたって上がったままになる。
        if (this.input.pitch() <= 0.0F) {
            rotation = approach(rotation, 0.0F, 2.0F);
        }

        this.setAttitude(new Quaternionf(this.attitude).slerp(
                Attitude.of(this.getYRot(), surface + rotation)
                        .rotateZ((float) Math.toRadians(slope.bank())),
                GROUND_LEVELLING));

        AircraftDefinition.Undercarriage gear = this.getStats().landingGear();

        double along = this.input.brake() ? gear.brakeFriction() : gear.rollingFriction();
        double across = gear.lateralFriction();

        // タイヤに残る荷重を通してのみ働く。車輪に荷重が無ければ擦る物も無い。主翼が機体全体を支えている状態
        // では、行き先について地面に発言権は無い。
        along = Mth.lerp(this.weightOnWheels, 1.0, along);
        across = Mth.lerp(this.weightOnWheels, 1.0, across);

        Vec3 heading = this.getNoseVector();
        Vec3 forwards = new Vec3(heading.x, 0.0, heading.z);
        Vec3 ground = new Vec3(motion.x, 0.0, motion.z);

        if (forwards.lengthSqr() > 1.0E-8) {
            forwards = forwards.normalize();

            Vec3 sideways = new Vec3(-forwards.z, 0.0, forwards.x);

            ground = forwards.scale(ground.dot(forwards) * along)
                    .add(sideways.scale(ground.dot(sideways) * across));
        } else {
            // 真上か真下を向いている。車輪が意見を持つ状況ではない。0で割らず、均等に減速させる方へ倒す。
            ground = ground.scale(along);
        }

        // そして車輪は滑走路へ押し付け続ける。ちょうど0に落ち着かせると——沈下中の機体を0でクランプするとそう
        // なる——機体は完全に水平な線に沿って移動しようとし、下に何も出会わず、バニラは「何の上にも立っていない」
        // と結論する。onGround は次のtickで消え、その次のtickで重力が機体を地面へ落とすと戻り、離陸滑走の間ずっと
        // その明滅を続ける。そこにぶら下がる全て——タイヤのグリップ、水平化、とりわけ降着装置が乗り越えてよい
        // 段差——も一緒に明滅するので、本来なら真っ直ぐ乗り越える縁石が1tickおきに壁になる。それだけで1ブロック
        // が機体を急停止させうる。これはそれを防ぐためにある。
        //
        // 車輪が支える荷重の1tick分だけで、それ以上ではない。床がそれを受け止めればそれで足りる。これより重く
        // すると降下率として読まれ始め、detectCrash が滑走を滑走と呼ばなくなる——TOUCHDOWN_SINK 参照。
        //
        // 接している物が車輪である間のみ。胴体着陸では押し付ける先が無いし、そこでの接地は依然として「到達」だ。
        double onto = this.onWheels() ? -GRAVITY : 0.0;

        return new Vec3(ground.x, Math.max(motion.y, onto), ground.z);
    }

    /** 車輪の下の地面が機体へ与える傾き（度）。機首上げが正、右翼下げが正。 */
    private record Slope(float pitch, float bank) {
    }

    /**
     * 降着装置の四隅の下の地面を読み、機体が寝るべき傾きを求める。
     *
     * <p>1本ではなく4本のプローブを使う。1本では地面がどちらへ傾いているか分からないからだ。前2本と後2本の差が
     * ピッチ、左2本と右2本の差がバンク。穴の上に張り出した隅は「無し」と読まれ両方から除外されるので、溝に車輪を
     * 出した機体は水平のまま固まらず前のめりになる。{@code GroundVehicleEntity} が履帯に対して行っているのと同じ
     * 処理であり、傾斜地に置かれた機体が水平に浮いて片側を地面へ埋めるのをやめさせるのが目的だ。
     *
     * <p>接地面は素の直方体の底面——降着装置が置かれている当の場所——を正方形として取る。ファイルに輪距や軸距は
     * 書かれておらず、そもそも読み値から求まるのは傾斜「角」なので、接地面の大きさが変えるのは角度そのものではなく
     * どれだけ広い範囲を均すかだけだ。大きい機体ほど広く読み、細かい凸凹を無視する。それは望ましい方向でもある。
     *
     * <p>読み値はそのまま採らない。呼び出し側の slerp が姿勢を毎tick一部だけ寄せるので、隅が段差を越えた瞬間に
     * 丸1ブロック変化する読み値も、機体の上では滑らかな動きになる。
     */
    private Slope groundSlope() {
        double half = Math.max(this.getStats().hitbox().width(), 1.0F) * 0.5;
        float radians = (float) Math.toRadians(this.getYRot());
        Vec3 forward = new Vec3(-Mth.sin(radians), 0.0, Mth.cos(radians));
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x);

        Vec3 centre = this.position();
        double frontLeft = this.groundUnder(centre.add(forward.scale(half)).subtract(right.scale(half)));
        double frontRight = this.groundUnder(centre.add(forward.scale(half)).add(right.scale(half)));
        double rearLeft = this.groundUnder(centre.subtract(forward.scale(half)).subtract(right.scale(half)));
        double rearRight = this.groundUnder(centre.subtract(forward.scale(half)).add(right.scale(half)));

        // 下に何も無ければ水平と読む。車輪が何にも載っていない機体が向かうべき姿勢はそれだ。
        float pitch = slopeAngle(mean(frontLeft, frontRight), mean(rearLeft, rearRight), half * 2.0);
        float bank = slopeAngle(mean(frontLeft, rearLeft), mean(frontRight, rearRight), half * 2.0);

        // 上限を設けるのは、読み違え1つが機体を機首で立たせないため。これを超える地形は降着装置が沿う相手では
        // なく、機体が突っ込む相手だ。
        return new Slope(Mth.clamp(pitch, -GROUND_SLOPE_LIMIT, GROUND_SLOPE_LIMIT),
                Mth.clamp(bank, -GROUND_SLOPE_LIMIT, GROUND_SLOPE_LIMIT));
    }

    /**
     * ある地点の下の地面の高さ。届く範囲に無ければ {@link Double#NaN}。
     *
     * <p>ハイトマップではなくトレースで求める。ハイトマップは格納庫の中も橋の上も知らないが、機体はその両方に
     * 立つからだ。トレースは短く——機体の上下数ブロック——接地面の内側に収まる。機体がそこに立っている以上、
     * その範囲は定義上ロード済みだ。接地中にしか呼ばれないので、{@link #heightAboveGround} が避けている「未ロード
     * の地形をtickスレッド上で生成してしまう」問題はここには無い。
     *
     * <p>車輪が乗り越えられる高さより上の読み値は捨てる。プローブは素の直方体の隅ちょうどに立つので、機体の脇に
     * 壁があればトレースはその境界面上を降りることになり、壁の天端を「車輪の下の地面」として持ち帰りうる。車輪が
     * 立てない高さの物は、機体が寝るべき地面ではない。
     */
    private double groundUnder(Vec3 where) {
        Vec3 from = new Vec3(where.x, this.getY() + GROUND_PROBE_ABOVE, where.z);
        Vec3 to = new Vec3(where.x, this.getY() - GROUND_PROBE_BELOW, where.z);
        BlockHitResult hit = this.level().clip(
                new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));

        if (hit.getType() == HitResult.Type.MISS) {
            return Double.NaN;
        }

        double ground = hit.getLocation().y;

        return ground > this.getY() + this.getStats().landingGear().climbHeight() ? Double.NaN : ground;
    }

    /** ある区間の高低差から傾斜角（度）を求める。 */
    private static float slopeAngle(double high, double low, double span) {
        if (Double.isNaN(high) || Double.isNaN(low) || span <= 0.0) {
            return 0.0F;
        }

        return (float) (Math.atan2(high - low, span) * (180.0 / Math.PI));
    }

    /** 2つの読み値の平均。片方しか有効でなければその値。 */
    private static double mean(double a, double b) {
        if (Double.isNaN(a)) {
            return b;
        }

        return Double.isNaN(b) ? a : (a + b) * 0.5;
    }

    /**
     * 自由大気中の同じ速度・同じ角度と比べて、主翼が今どれだけの揚力を出しているか。
     *
     * <p>地面近くでは主翼は自身の鏡像に対して働き、同じ角度でより多くの揚力を出す。機体が滑走路から浮き上がる
     * のに使う物であり、着陸最後の数フィートで「到達」ではなく浮くようにしている物だ。翼幅ぶんの高度で完全に
     * 消える。
     */
    private double groundEffect() {
        AircraftDefinition.Wing wing = this.getStats().wing();

        if (wing.groundEffect() <= 0.0F) {
            return 1.0;
        }

        double reach = Math.max(wing.span(), 1.0);
        double height = this.heightAboveGround();

        if (height >= reach) {
            return 1.0;
        }

        return 1.0 + wing.groundEffect() * Mth.clamp(1.0 - height / reach, 0.0, 1.0);
    }

    /**
     * 直下の物からの高度（ブロック）。
     *
     * <p>トレースせずハイトマップから読む。毎tick必要であり、地面効果が成立するにはブロック単位の精度で足りる
     * うえ、未ロードの地面の上でトレースを下ろすとトレース相手の地形を生成してしまうからだ。
     *
     * <p>チャンクはロードも生成も許さずに要求する——{@code false} の意味であり、生成途中のチャンクが null として
     * 返ることも意味する——そして高さはチャンク自身から読む。代わりにレベルへ問うのが罠だ。
     * {@code Level#getHeight} はロードを許してチャンクを取得するので、まだ無い物をtickスレッド上で黙って生成し、
     * その間サーバー全体を止める。{@code hasChunkAt} も防げない。より早い段階で存在するだけのチャンクにも true
     * を返すからだ。この機体は設計上常にtickするので、誰も訪れていない地面の上を自律飛行する機体は、高度を問う
     * ためだけに新規地形の回廊を掘ることになる。どのみちその外では答えに意味が無い。チャンクが無ければ地面効果も
     * 無く、それが高高度での真実だ。
     */
    private double heightAboveGround() {
        if (this.onGround()) {
            return 0.0;
        }

        BlockPos at = this.blockPosition();
        ChunkAccess chunk = this.level().getChunkSource().getChunk(at.getX() >> 4, at.getZ() >> 4, false);

        if (chunk == null) {
            return Double.MAX_VALUE;
        }

        return Math.max(0.0,
                this.getY() - chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()));
    }

    /**
     * ここでの空気の濃さ。ファイルの諸元が前提とする値に対する倍数。
     *
     * <p>推力も揚力もこれに対して計算されるので、上昇する機体はエンジンと主翼を同時に使い切り、無限に上らず
     * 上昇限度に落ち着く。下限を設けてあるのは、0に達する空気が上昇限度を落とし穴に変えてしまうからだ。
     */
    private double airDensity() {
        double sea = this.getStats().engine().seaLevelDensity();
        double thinning = Math.pow(2.0, -(this.getY() - DENSITY_DATUM) / DENSITY_SCALE);
        // ファイルが空気について妙な値を指定していても、下限が上限を超えてはならない。
        double floor = Math.min(THINNEST_AIR, sea);

        return Mth.clamp(sea * thinning, floor, sea);
    }

    /**
     * 機体の実速度（ブロック/tick）。どの側から問われても答える。
     *
     * <p>デルタ移動ではなくこちらを読むこと。飛行モデルを回すのは1台だけで、他のコピーが持つデルタ移動は無意味
     * だからだ。操縦側が測定し、他の側へは送られる。推測ではなく1tick遅れの同じ値だ。機体の速度を知る必要がある
     * 物——計器、兵器の初速、描画用の予測——は全てここへ来るべきだ。
     */
    public Vec3 getVelocity() {
        // サーバーでは、パイロットが操縦中の機体をここで動かす物は無い。位置はパケットで届き、それらは旧位置が
        // 記録された後、tickの合間に適用される。よってここから測るとこのtickではまったく動いておらず、実際の速度に
        // かかわらず差は完全に0になる——それが、そこから発射される全兵器の初速を黙って奪っていた。この側で正直な
        // 答えはパイロット自身の申告値だけだ。
        if (this.isControlledByLocalInstance()) {
            return this.travelled();
        }

        if (!this.level().isClientSide) {
            return this.pilotVelocity;
        }

        // 操縦していないクライアントへも、サーバーと同じ理由で送る。そこから見える動きは平滑化・予測された
        // 描画上の近似であり、計器は描画ではなく機体そのものを読むべきだからだ。
        Vector3f reported = this.entityData.get(DATA_VELOCITY);

        return new Vec3(reported.x(), reported.y(), reported.z());
    }

    /**
     * <em>この</em>側から見えた直前1tickの移動量。他者が操縦中のサーバーでは完全に0になる。
     *
     * <p>これを必要とする物はほぼ無い。正直な答えは {@link #getVelocity()} であり、参照すべきはそちらだ。これが
     * あるのは真実を伝えてはならない唯一の相手——有人機についてサーバーが保持するデルタ移動——のためだ。それは
     * パイロットへブロードキャストされ、実値を持てば本人の飛行モデルと衝突してしまう。
     */
    private Vec3 travelled() {
        return this.position().subtract(this.xOld, this.yOld, this.zOld);
    }

    /**
     * パイロットのクライアントから毎tick、実速度の通知を受ける。
     *
     * <p>クランプする。クライアントから届き、物を投げるのに使われるからだ。制限が無ければ、送信者の好きな速度で
     * 機関砲弾を撃つ手段になってしまう。
     */
    public void setPilotVelocity(Vec3 velocity) {
        double speed = velocity.length();

        this.pilotVelocity = speed > MAX_PILOT_SPEED ? velocity.scale(MAX_PILOT_SPEED / speed) : velocity;
    }

    /**
     * 機体が現在何G を引いているか。1が水平飛行、0が無重量。設計耐Gを超えると機体が歪み始める。
     */
    public float getLoadFactor(Vec3 velocity) {
        double speed = velocity.length();

        AircraftDefinition.Rotor rotor = this.getStats().rotor().orElse(null);

        // ヘリにはこれを読む主翼が無いし、必要も無い。引いているのはローターが引いている分であり、ホバリング中
        // も旋回中も同じ値だ。下の主翼の値と違って全側で正直でもある——ここには飛行モデルの実行に依存する物が
        // 無いので、サーバーも見物人もパイロットと同じ答えを得る。
        if (rotor != null) {
            return (float) (this.rotorLift(rotor, speed) / GRAVITY);
        }

        if (speed < 1.0E-4) {
            return 0.0F;
        }

        AircraftDefinition.Wing wing = this.getStats().wing();
        Vec3 flow = velocity.scale(1.0 / speed);
        float angle = (float) Math.toDegrees(Math.asin(Mth.clamp(-flow.dot(this.getLiftVector()), -1.0, 1.0)));
        double coefficient = wing.liftCoefficient(angle) * (1.0 + this.flapsProgress * this.getFlapsLiftBonus())
                * this.sweepLift();

        return (float) Math.abs(wing.lift() * coefficient * speed * speed / GRAVITY);
    }

    /** 揚力の作用方向。キャノピーを貫いて上向き。その向きがどこであれ。 */
    public Vec3 getLiftVector() {
        return Attitude.up(this.attitude);
    }

    /** 機首の指向。 */
    public Vec3 getNoseVector() {
        return Attitude.nose(this.attitude);
    }

    private static float approach(float current, float target, float step) {
        return current > target ? Math.max(current - step, target) : Math.min(current + step, target);
    }

    /**
     * 可変翼の1tick分。
     *
     * <p>翼が行くべき位置を決めるのは対気速度だけであり、それは操縦席の誰かではなく空気が決めている。だから
     * ここには入力が1つも現れない。機体が加速すれば翼は下がり、減速すれば戻る。パイロットが着陸のために翼を
     * 前へ出し忘れることは、そもそも起こり得ない。{@link AircraftDefinition.Sweep} 参照。
     *
     * <p>目標へ一息には行かず、機体ファイルが書いた作動時間で追い掛ける。翼を動かしているのは油圧であって
     * 空気ではないので、急加速した機体の翼はしばらく前進位置に取り残される——その間、機体は後退位置で得られる
     * はずの抗力を払っていない。
     *
     * <p>残骸も動かす。翼を途中で止める理由が無く、落ちていく残骸は減速するので、翼は自然に前へ戻っていく。
     *
     * <p>呼ぶのは移動の後。操縦側の対気速度は位置と旧位置の差であり、移動前にはまだ0だからだ。
     * {@link #tick()} の呼び出し箇所を参照。
     */
    private void tickSweep() {
        this.sweepProgressO = this.sweepProgress;

        AircraftDefinition.Sweep sweep = this.getStats().wing().sweep().orElse(null);

        if (sweep == null) {
            return;
        }

        this.sweepProgress = approach(this.sweepProgress, sweep.progressAt(this.getVelocity().length()),
                1.0F / Math.max(sweep.cycleTicks(), 1));
    }

    /**
     * 今の後退角における揚力倍率。後退した翼は気流を斜めに受けるので、同じ迎え角でも作る揚力が減る。可変翼を
     * 持たない機体では1。
     */
    private double sweepLift() {
        AircraftDefinition.Sweep sweep = this.getStats().wing().sweep().orElse(null);

        return sweep == null ? 1.0 : sweep.liftFactor(this.sweepProgress);
    }

    /** 同じ物を形状抗力について。翼を後退させる目的がこちらで、高速域では1未満になる。 */
    private double sweepDrag() {
        AircraftDefinition.Sweep sweep = this.getStats().wing().sweep().orElse(null);

        return sweep == null ? 1.0 : sweep.dragFactor(this.sweepProgress);
    }

    /**
     * ノズル作動の1tick分。ここから動くのはノズル自体だけで、扉の動きはアニメーションファイルの管轄。
     */
    private void tickVtol() {
        this.vtolProgressO = this.vtolProgress;

        AircraftDefinition.Vtol vtol = this.getStats().vtol().orElse(null);

        if (vtol == null) {
            this.vtolProgress = 0.0F;

            return;
        }

        this.vtolProgress = approach(this.vtolProgress, this.isVtolSelected() ? 1.0F : 0.0F,
                1.0F / Math.max(vtol.cycleTicks(), 1));
    }

    /**
     * ローター回転の1tick分。
     *
     * <p>意図的に全側で実行する。依存するのは誰が座席にいるかで、それはどのみち全側へ通知されるので送る物が無い。
     * つまり誰もシミュレートしていないヘリでもローターは回り続ける——谷の向こうから見ているクライアントは、両者の
     * 間にパケット1つ通さずパイロットのクライアントとまったく同じに描く。
     *
     * <p>乗り込みがスターターで、降機がシャットダウン。ヘリは走って乗り込んで即離陸できる機体ではない。まず
     * ローターの回転を上げねばならず、その待ち時間こそが「浮かぶレンガの運転」ではなく「ヘリの操縦」らしさの
     * 大半を作っている。降りれば回転が落ちていくので、駐機場に残された機体は急停止せず静まっていく。
     */
    private void tickRotor() {
        this.rotorSpeedO = this.rotorSpeed;
        this.rotorAngleO = this.rotorAngle;
        this.tailAngleO = this.tailAngle;

        AircraftDefinition.Rotor rotor = this.getStats().rotor().orElse(null);

        if (rotor == null) {
            this.tickPropellers();

            return;
        }

        boolean running = this.getControllingPassenger() != null;

        this.rotorSpeed = approach(this.rotorSpeed, running ? 1.0F : 0.0F,
                1.0F / Math.max(rotor.spoolTicks(), 1));
        this.rotorAngle += this.rotorSpeed * rotor.degreesPerTick();
        this.tailAngle += this.rotorSpeed * rotor.tailDegreesPerTick();

        // float が大きくならないよう1回転内に収める。前回角も一緒に折り返す。継ぎ目をまたぐ 359→1 の補間は
        // 1フレームだけローターを逆回転に描くし、毎秒つっかえるローターは、まったく回らないローターより悪い。
        while (this.rotorAngle >= 360.0F) {
            this.rotorAngle -= 360.0F;
            this.rotorAngleO -= 360.0F;
        }

        while (this.tailAngle >= 360.0F) {
            this.tailAngle -= 360.0F;
            this.tailAngleO -= 360.0F;
        }
    }

    /**
     * プロペラ。ローターを持たない機体が回す物であり、同じ角度の変数を使う——1機がローターとプロペラの両方を
     * 持つことはないので、2つ目を持ち回る意味が無い。
     *
     * <p><b>回転数はエンジンの働きに従う。</b>ローターと違い自分の慣性を持たない。実機の定速プロペラは出力に
     * 関わらず一定回転で回るが、画面の上でそれをやると、止まっているのに羽根だけ全速で回る駐機中の機体になる。
     * ここでの回転は「エンジンが回っている」ことの表示であって、回転計ではない。燃料が尽きれば
     * {@link #getEngineNote()} が0を返すので、羽根もそこで止まる。
     */
    private void tickPropellers() {
        float degrees = this.getStats().engine().propellerDegreesPerTick();

        if (degrees <= 0.0F) {
            this.rotorSpeed = 0.0F;
            this.rotorAngle = 0.0F;
            this.rotorAngleO = 0.0F;
            this.tailAngle = 0.0F;
            this.tailAngleO = 0.0F;

            return;
        }

        this.rotorSpeed = this.getEngineNote();
        this.rotorAngle += this.rotorSpeed * degrees;

        // ローターと同じく1回転内へ折り返す。理由も同じで、継ぎ目をまたぐ補間は1フレームだけ逆回転に見える。
        while (this.rotorAngle >= 360.0F) {
            this.rotorAngle -= 360.0F;
            this.rotorAngleO -= 360.0F;
        }
    }

    /**
     * 降着装置とフラップの作動1tick分。加えて、地面に降りた機体は自分で脚を出す。
     *
     * <p>脚を出すのは離陸前と着陸前の1回ずつだが、忘れられるのは常に後者だ。しかも忘れた結果が現れるのは接地の
     * 瞬間——取り返しの付かない場所——であり、脚が上がったまま滑走路に触れた機体は、パイロットが操作を1つ落とした
     * ことに気付く前に胴体で滑っている。よって地面に立っている機体は脚が出ている、という形を機体側で保証する。
     * 実際、降りている機体で車輪が要らない場面は無い。
     *
     * <p>これがパイロットと争うことはない。{@link #toggleGear} は接地中の格納を既に拒んでいるので、脚が上がった
     * まま地面に居ることを誰かが要求している状態というのが存在しない。離陸して地面を離れれば、空中では従来通り
     * 好きに上げ下げできる。
     *
     * <p>脚が上がっている間は {@link #groundTick} が機体を滑走路へ押し付けないので、接地フラグは1tickおきに
     * 明滅する——{@code onGround} は結局のところ直前の衝突の残りかすだ。ここではそれで足りる。展開は一方向の
     * ラッチであり、必要なのは true が1度立つことだけだから。
     *
     * <p>全損機は除く。残骸に降着装置は無いし、脚を伸ばしながら滑っていく残骸は誰も望んでいない。
     */
    private void tickGear() {
        if (!this.level().isClientSide && !this.isGearDown() && !this.isWrecked() && this.onGround()) {
            this.entityData.set(DATA_GEAR_DOWN, true);
        }

        this.gearProgressO = this.gearProgress;
        this.gearProgress = approach(this.gearProgress, this.isGearDown() ? 1.0F : 0.0F,
                1.0F / Math.max(this.getGearCycleTicks(), 1));
        this.flapsProgressO = this.flapsProgress;
        this.flapsProgress = approach(this.flapsProgress, this.isFlapsDown() ? 1.0F : 0.0F,
                1.0F / Math.max(this.getFlapsCycleTicks(), 1));
    }


    /**
     * 操縦していないクライアントが描くべき位置へ機体を置く。
     *
     * <p>バニラ自身の乗り物補間は意図的に使わない——高速機に対して何をするかは {@link AircraftInterpolation}
     * 参照。実際の位置よりほぼ1チャンク後ろに描いてしまう。下のフォールバックへ来るのは、予測が起点となる位置を
     * まだ持たない間か、待つのを諦めた後だけだ。
     */
    private void tickLerp() {
        if (this.isControlledByLocalInstance()) {
            this.lerpSteps = 0;
            this.interpolation.release();
            this.syncPacketPositionCodec(this.getX(), this.getY(), this.getZ());

            return;
        }

        if (!this.level().isClientSide) {
            // サーバーは何も描かないし、有人機の位置はパイロットの移動パケットで丸ごと届く。その上から予測
            // しても衝突するだけだ。
            return;
        }

        AircraftDefinition.Sync sync = this.getStats().sync();

        this.interpolation.tune(sync.correctionTicks(), sync.snapDistance(), sync.maxPredictionTicks());

        // 直近2回の位置更新から見える値ではなく、操縦側の申告値を使う。予測が動く前に渡すので、このtickから
        // 既に反映される。
        Vector3f velocity = this.entityData.get(DATA_VELOCITY);

        this.interpolation.receiveVelocity(velocity.x(), velocity.y(), velocity.z());

        if (this.interpolation.advance()) {
            this.lerpSteps = 0;
            this.setPos(this.interpolation.renderX(), this.interpolation.renderY(), this.interpolation.renderZ());

            return;
        }

        if (this.lerpSteps > 0) {
            this.lerpPositionAndRotationStep(this.lerpSteps, this.lerpX, this.lerpY, this.lerpZ, this.lerpYRot, this.lerpXRot);
            this.lerpSteps--;
        }
    }

    /**
     * サーバーが考えるこの機体の速度。操縦側は無視しなければならない。
     *
     * <p>有人機はパイロットのクライアントが飛ばし、サーバーは意図的にデルタ移動を0のまま保つ——{@link #tick()}
     * の注記参照。この0は通常送られない。ゲームは変化した速度しかブロードキャストせず、これは変化しないからだ。
     * ただし1つ、しかも厄介な例外がある。ダメージを受けたエンティティは、見ている全員がノックバックを見られる
     * よう、そのtickの終わりに速度を<em>強制</em>ブロードキャストされる。機体にノックバックは無いので、出て
     * 行ったのはその0だ——追跡中の全クライアントへ、パイロットのクライアントを含めて。そして本人の飛行モデルが
     * 完全停止で上書きされた。
     *
     * <p>結果、300ノットの機体が何かに触れられた瞬間に空中で停止していた。機関砲弾1発、爆風の破片1つで速度が0
     * になる。速度についてサーバーから聞く価値のある情報は何も無い。知っているのは操縦している側だ。
     */
    @Override
    public void lerpMotion(double x, double y, double z) {
        if (this.isControlledByLocalInstance()) {
            return;
        }

        super.lerpMotion(x, y, z);
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        this.lerpX = x;
        this.lerpY = y;
        this.lerpZ = z;
        this.lerpYRot = yRot;
        this.lerpXRot = xRot;
        this.lerpSteps = 10;

        // この機体を実際に描くのは予測であり、上のフィールドは予測が走る前のフォールバックにすぎない。本来の
        // 位置だけでなく現在描かれている位置も渡している点に注意。最初の補正を跳ばずに描画位置から始められる。
        if (this.level().isClientSide && !this.isControlledByLocalInstance()) {
            this.interpolation.receivePosition(x, y, z, this.getX(), this.getY(), this.getZ());

            if (this.interpolation.consumeSnap()) {
                this.lerpSteps = 0;
                this.setPos(this.interpolation.renderX(), this.interpolation.renderY(),
                        this.interpolation.renderZ());
            }
        }
    }

    @Override
    public double lerpTargetX() {
        return this.interpolation.isSeeded() ? this.interpolation.targetX()
                : (this.lerpSteps > 0 ? this.lerpX : this.getX());
    }

    @Override
    public double lerpTargetY() {
        return this.interpolation.isSeeded() ? this.interpolation.targetY()
                : (this.lerpSteps > 0 ? this.lerpY : this.getY());
    }

    @Override
    public double lerpTargetZ() {
        return this.interpolation.isSeeded() ? this.interpolation.targetZ()
                : (this.lerpSteps > 0 ? this.lerpZ : this.getZ());
    }

    @Override
    public float lerpTargetXRot() {
        return this.lerpSteps > 0 ? (float) this.lerpXRot : this.getXRot();
    }

    @Override
    public float lerpTargetYRot() {
        return this.lerpSteps > 0 ? (float) this.lerpYRot : this.getYRot();
    }

    // ------------------------------------------------------------------
    // ダメージ
    // ------------------------------------------------------------------

    /**
     * 機体の箱を、姿勢が運んだ位置へ移動させる。
     *
     * <p>各箱は回転した箱を囲む直立の箱だ。ゲームが衝突できるのは直立の箱だけだからだ。よって機体がロールすると
     * 寸法が変わる。主翼は水平飛行では薄い板、横倒しでは背の高い板になる。実際に主翼があるのはその位置だ。
     *
     * <p><b>箱の個数は一度決まったら変わらない。</b>レベルはエンティティ参加時にその箱を教えられ、後から別の
     * 集合を教える手段が無い。だからここで新しい集合を作ると、レベルは古い方を持ち続け——最後の位置で固まり、
     * 依然として固体で、依然として撃てる——新しい方は何からも見えなくなる。素の当たり判定も標的だった頃は
     * それでも耐えられた。今や機体を撃つ手段が箱だけになった以上、それは機体を黙って被弾不能にしてしまうので、
     * 個数は生涯固定にしてある。
     *
     * <p>よって形状を変える {@code /reload} は、既存機体の箱を移動させるだけで、完全な反映は次に設置される機体
     * からになる。ファイルがもう記述しなくなった箱は、空中に残さず機体内部へ畳み込む。
     */
    private void tickParts() {
        List<VehicleShape.Box> shape = this.getShape().boxes();

        List<AircraftDefinition.Hardpoint> hardpoints = this.getStats().hardpoints();

        for (int i = 0; i < this.parts.length; i++) {
            VehiclePart part = this.parts[i];

            if (part.isPylon()) {
                this.placePylon(part, hardpoints);

                continue;
            }

            if (i >= shape.size()) {
                part.fold(this.position());

                continue;
            }

            part.place(this.hitbox(shape.get(i)));
        }

        this.notePlacement();
        this.carryStanders();
    }

    /**
     * パイロンの箱をハードポイントの位置に、プレイヤーが無理なく届く大きさで置く。
     *
     * <p>吊っている物の形に合わせず正方形にする。パイロンは物体ではなく機体上の位置であり、何も吊っていないとき
     * こそ届く必要がある。まさにそのとき誰かが何かを吊りたがるのだから。
     */
    private void placePylon(VehiclePart part, List<AircraftDefinition.Hardpoint> hardpoints) {
        int slot = part.getPylon();

        if (slot >= hardpoints.size()) {
            // ファイルがもうこれを列挙していない。消せないので畳み込む。
            part.fold(this.position());

            return;
        }

        Vec3 where = hardpoints.get(slot).pos();
        Vec3 centre = this.position().add(Attitude.toWorld(this.attitude, where));
        part.place(centre, pylonBox(where, hardpoints, slot));
    }

    /**
     * パイロンの箱の大きさ。無理なく届き、かつ隣のパイロンまで届いてしまわない大きさ。
     *
     * <p>1m 間隔で5つのステーションを持つ主翼では、さもないと5つの箱が重なり合い、1つを狙ったクリックが周囲3つ
     * のどれに当たってもおかしくない。どのパイロンを指しているかがクリックの意味の全てなので、ステーションが密な
     * 場所では箱もそれに合わせて縮み、各箱が自分の兵装の上に立つようにする。
     */
    private static double pylonBox(Vec3 where, List<AircraftDefinition.Hardpoint> hardpoints, int slot) {
        double room = PYLON_BOX;

        for (int i = 0; i < hardpoints.size(); i++) {
            if (i != slot) {
                room = Math.min(room, where.distanceTo(hardpoints.get(i).pos()));
            }
        }

        return Math.max(room, SMALLEST_PYLON_BOX);
    }

    /**
     * 空気を可視化する。主翼が強く働いているときの翼端ベイパーと、最高速度に近づいたときに機体を包む
     * ベイパーコーン。
     *
     * <p>どちらも同じ物理現象だ。主翼の周りを引き回された空気や、高速機の前方で圧縮された空気は圧力が下がり、
     * それに伴って温度も下がって、中の水分が凝結する。だから翼端ベイパーは速度ではなく引いているGに結び付いて
     * おり、きつい旋回で現れ、荷重を抜くと消える理由もそれだ。
     *
     * <p>バニラの雲パーティクルではなく MOD 自前のパーティクルで描く。兵器と同じ2つの理由からだ。バニラは32
     * ブロックでパーティクルを捨てるし、光レベルを読むチャンクが下に無いと残った分を真っ黒に描く。きつい旋回は
     * 機体が行う最も目を引く動作であり、それより遠くから見える価値がある。
     */
    private void spawnFlightEffects() {
        // 残骸のすることは何一つ飛行ではない。落下中の焼けた機体の翼端から出るベイパーは、まだGを引いて
        // いるように見えてしまう。
        if (this.isWrecked()) {
            return;
        }

        Vec3 velocity = this.getVelocity();
        double speed = velocity.length();

        // 下の空力処理より前に置く。空力はこれに当てはまらないからだ。バーナーは空中と同じくらい滑走路上でも
        // 点火されるし、機体が速くなるのを待つプルームは、パイロットが点火した当の瞬間に限って欠けてしまう。
        this.spawnAfterburnerPlume(velocity);

        if (speed < 0.5) {
            return;
        }

        Vec3 position = this.position();
        Vec3 up = this.getLiftVector();
        Vec3 right = Attitude.right(this.attitude);
        Vec3 nose = this.getNoseVector();
        Vec3 drift = velocity.scale(0.5);
        RandomSource random = this.level().random;
        // 定数として持たずここで求める。このクラスはレジストリ構築中に読み込まれるので、その時点では問い合わせ
        // 可能なパーティクルタイプがまだ存在しない。
        TintedParticleOption vapour = ModParticles.VAPOUR.get().of(VAPOUR_COLOUR, 1.0F);

        // 翼端。主翼が強く引いているほど見える量が増える。
        float load = this.getLoadFactor(velocity);

        if (load > VORTEX_LOAD) {
            double span = this.getWingSpan();
            int puffs = Math.min((int) ((load - VORTEX_LOAD) * 2.0F) + 1, 4);

            for (int side = -1; side <= 1; side += 2) {
                Vec3 tip = position.add(right.scale(span * side)).add(up.scale(WING_HEIGHT));

                for (int i = 0; i < puffs; i++) {
                    this.level().addParticle(vapour,
                            tip.x + random.nextGaussian() * 0.2,
                            tip.y + random.nextGaussian() * 0.2,
                            tip.z + random.nextGaussian() * 0.2,
                            drift.x, drift.y, drift.z);
                }
            }
        }

        // そしてコーン。機体が前方の空気を、その空気が逃げるより速く押し始めたら発生する。
        double onset = this.getStats().wing().maxSpeed() > 0.0F
                ? this.getStats().wing().maxSpeed() * VAPOUR_SPEED
                : this.topSpeed() * VAPOUR_SPEED;

        if (speed > onset) {
            double thickness = Math.min((speed - onset) / Math.max(onset * 0.15, 1.0E-3), 1.0);
            Vec3 centre = position.add(up.scale(WING_HEIGHT)).add(nose.scale(VAPOUR_AHEAD));

            for (int i = 0; i < 1 + (int) (thickness * 6); i++) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                double radius = VAPOUR_RADIUS * (0.7 + random.nextDouble() * 0.3);
                Vec3 rim = centre.add(right.scale(Math.cos(angle) * radius))
                        .add(up.scale(Math.sin(angle) * radius));

                this.level().addParticle(vapour, rim.x, rim.y, rim.z, drift.x, drift.y, drift.z);
            }
        }
    }

    /**
     * バーナー点火中に排気管から出る炎。
     *
     * <p>プルームは2つの物なので2層で描く。前方は炎で、出口で白く開き後方でオレンジに落ち着く。その周りと後方に
     * あるのが残された熱気で、プルームの長さを作り、遠距離から見えるものの大半を占める。
     *
     * <p>飛行経路ではなく機体自身の機首方向へ噴出させる。差はきつい旋回のたびに現れる。管から出た物は管が向いて
     * いる方へ行くので、旋回中の機体はプルームを自分の尾部方向ではなく片側へ引く。排気を運ぶのは機体速度から
     * 噴出速度を引いた分——なので低速では炎が機体の後ろに静止し、高速では機体に対してほとんど動かない筋になる。
     * プルームの振る舞いとはそういうものだ。
     *
     * <p>バニラの炎ではなく MOD 自前のパーティクルで描く。翼端ベイパーと同じ理由だ。32ブロックより遠くから見え
     * てほしいし、自前の明るさを持ってほしい。さもないとロード範囲外のバーナーは真っ黒に描かれるが、それだけは
     * 確実に違う色だ。
     */
    private void spawnAfterburnerPlume(Vec3 velocity) {
        AircraftDefinition.Afterburner burner = this.getStats().engine().afterburner().orElse(null);

        if (burner == null || this.reheat <= LIT) {
            return;
        }

        Vec3 nose = this.getNoseVector();
        Vec3 blown = velocity.subtract(nose.scale(PLUME_SPEED * this.reheat));
        RandomSource random = this.level().random;
        TintedParticleOption flame = ModParticles.BLAST.get().of(PLUME_COLOUR, PLUME_SIZE * this.reheat);
        TintedParticleOption exhaust = ModParticles.MOTOR_SMOKE.get().of(EXHAUST_COLOUR, this.reheat);
        double length = PLUME_LENGTH * this.reheat;
        int puffs = Math.max(1, Math.round(PLUME_PUFFS * this.reheat));

        for (Vec3 nozzle : this.nozzles(burner)) {
            Vec3 lip = this.position().add(Attitude.toWorld(this.attitude, nozzle));

            for (int i = 0; i < puffs; i++) {
                // 出口に固めず、プルームに沿って後方へ並べる。この速度では1tickの距離は長く、1tickに1粒では
                // 炎の柱ではなく点線に見えてしまう。
                this.puff(flame, lip.subtract(nose.scale(random.nextDouble() * length * 0.5)), blown, random);
                this.puff(exhaust, lip.subtract(nose.scale(random.nextDouble() * length)), blown, random);
            }
        }
    }

    /** プルームの1粒。柱に幅を持たせるため軸から少しばらけさせる。 */
    private void puff(TintedParticleOption particle, Vec3 at, Vec3 blown, RandomSource random) {
        this.level().addParticle(particle,
                at.x + random.nextGaussian() * PLUME_SCATTER,
                at.y + random.nextGaussian() * PLUME_SCATTER,
                at.z + random.nextGaussian() * PLUME_SCATTER,
                blown.x, blown.y, blown.z);
    }

    /**
     * プルームの噴出位置。機体座標系。
     *
     * <p>ファイルの指定値。指定が無ければ当たり判定形状の後端から1本。このフォールバックは胴体内の単発エンジン
     * には正しく、離れて置かれた双発には誤りだ。だから気にする機体は自分で指定する。
     */
    private List<Vec3> nozzles(AircraftDefinition.Afterburner burner) {
        if (!burner.nozzles().isEmpty()) {
            return burner.nozzles();
        }

        double tail = -this.getBbWidth() / 2.0;

        for (VehicleShape.Box box : this.getShape().boxes()) {
            tail = Math.min(tail, box.offset().z - box.size().z / 2.0);
        }

        return List.of(new Vec3(0.0, WING_HEIGHT, tail));
    }

    /** 機体最大幅の半分。当たり判定形状から取る。 */
    private double getWingSpan() {
        double span = this.getBbWidth() / 2.0;

        for (VehicleShape.Box box : this.getShape().boxes()) {
            span = Math.max(span, Math.abs(box.offset().x) + box.size().x / 2.0);
        }

        return span;
    }

    /** 推力と抗力が釣り合う点。ファイルが上限を指定していない機体用。 */
    private double topSpeed() {
        AircraftDefinition.Wing wing = this.getStats().wing();

        return wing.drag() > 0.0F ? Math.sqrt(this.getStats().engine().maxThrust() / wing.drag()) : 1.0;
    }

    /**
     * 操縦クライアントが検出した衝突を報告する。サーバーは機体が本当に何かに接している場合のみ受理するので、
     * 迷子のパケットが何も無い所に爆発を生み出すことはできない。
     */
    public void reportCrash() {
        if (!this.level().noCollision(this, this.getBoundingBox().inflate(0.5))) {
            this.crashing = true;
        }
    }

    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        // 機体が完全に消えたとき——破壊、破棄、別ディメンションへの移動——に回廊を手放す。手放すだけだ。これは
        // チャンクシステム自身の更新ループの内側から発火するので、そこでチケットを取るとチャンクがロードされ、
        // 反復の途中で同じループへ再入してしまう（DistanceManager.runAllUpdates の ConcurrentModificationException）。
        //
        // チャンクへアンロードされるだけのときは断じて手放さない。そこで解放するのは、機体を空中で永久に凍り
        // 付かせるデッドロックだった。誰も開いていないチャンクへ書き出された機体に次のtickは無いので、「次のtick
        // でまた要求する」は誰も守れない約束だった。そのチャンクを——ひいては機体を——ロードし戻す唯一の物が
        // そのチケットだったのだ。だからアンロードされた機体はチケットを保持する。チケットはワールドと共に保存
        // され、再起動後に回廊を復元し、機体は目覚めたとき自身の NBT に保存した集合と突き合わせる。
        if (this.getRemovalReason() != RemovalReason.UNLOADED_TO_CHUNK) {
            this.heldChunks = AircraftChunkLoader.release(this, this.heldChunks);
        }
    }

    /**
     * 機体の最期。飛行モデル側での呼び名で置いてある。処理は {@link VehicleEntityBase#wreck} の担当で、撃墜
     * でも斜面への激突でも同じだ。
     */
    protected void crash() {
        this.wreck();
    }

    /**
     * 機体が機体でなくなった瞬間に停止処理を行う。
     *
     * <p>3つあり、いずれも放置すれば焼けた機体で起こり続けることだ。エンジンは停止するので音量は0。主翼の下に
     * 吊っていた物は機体と共に吹き飛んだのでパイロンは空——誰にも降ろさせない無傷のミサイルを積んだ黒焦げの残骸
     * は、何も積んでいない残骸より悪い。
     *
     * <p>そして機体はもう回転していない。これはここよりクライアント側で効く。クライアントは姿勢更新の間、最後の
     * 角速度を継続して機体を描くので、きついバンク中に全損した残骸は放置すると永久にロールし続ける。姿勢を現在値
     * へスナップすれば、何も動かさずに角速度0を公開できる。
     */
    @Override
    protected void onWrecked() {
        this.setThrottle(0.0F);
        this.thrustLevel = 0.0F;
        this.reheatCommanded = false;
        this.gateHeld = 0;
        this.reheat = 0.0F;
        this.entityData.set(DATA_AFTERBURNER, 0.0F);
        this.input = AircraftInput.NONE;
        // 既に処理済み。放置すると落下の毎tickで crash() が再び呼ばれ、その都度 wreck() が「やることが無い」と
        // 判断する羽目になる。
        this.crashing = false;
        this.weapons.clear();
        this.clearDesignation();
        this.entityData.set(DATA_WEAPONS, this.weapons.syncTag());
        this.snapAttitude(this.attitude);
    }

    /**
     * 残骸としての1tick、つまり落下の1tick。
     *
     * <p>ここでは何も飛ばさない。推力も揚力も操縦も無く、機体は全損した時点の姿勢を保つ。落下中に自分で翼を水平
     * に戻す残骸は残骸ではなく機体だ。あとは重力がやり、残った対気速度は途中で失われ、最後は地面が受け止める。
     *
     * <p><b>要求した移動量ではなく、世界が許した移動量から続ける。</b>この2つが分かれるのは、まさに運動量が残る
     * かどうかを決める瞬間だ。野原へ突っ込んだ機体は、進入時に {@code move} が阻まれた軸をデルタ移動から取り
     * 除いている——そちらから読むと、飛行速度の浅い降下も駐機中の機体も、1tick後には同じ「立っている残骸」に
     * なる。実際に進んだ距離から読めば、走っていた方は走り続ける。地面は地面へ向かった分の速度を奪い、地面に
     * 沿った分を残した。それが掘り進むということだ。
     *
     * <p>停止も同じ理由で起きる。斜面に食い込んだ残骸は前tickどこへも進めなかったので、引き継ぐ物も溜まる物も
     * 無い。平地に横たわる残骸は下方向へ進めなかったので、下の重力がその鉛直速度の全てになる——{@code onGround}
     * が真であり続けるには、それを毎tick床へ押し込む必要がある。
     */
    private void wreckTick() {
        Vec3 carried = this.lastTravel;
        // 何かの上に落ちている。判定は onGround だけではない。あのフラグは素の直方体の物で、素の直方体は車輪
        // の位置にある——背面で横たわった機体や翼端で支えられた機体は、車輪の線を空中に残したまま自分の箱で
        // 支えられている。摩擦こそが掘り進むということだ。これが無いと背面の残骸は滑走してしまう。
        double slide = this.onGround() || this.verticalCollision ? WRECK_FRICTION : WRECK_DRAG;

        this.setDeltaMovement(carried.x * slide, carried.y * WRECK_DRAG - GRAVITY, carried.z * slide);
    }

    // ------------------------------------------------------------------
    // 搭乗
    // ------------------------------------------------------------------

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        // クライアントにこれを判断する立場は無く、試みてもならない。誰も操縦していない機体の移動を回すことは
        // 無いので、機体がどれだけしっかり車輪で座っていても向こうでは onGround() が false であり、そこから導く
        // 答えは全て誤りだ。
        //
        // 誤り以上に厄介なのは、クリックを消費しない答えが、同じ操作をもう一方の手で最初からやり直させることだ。
        // その時点でゲームは既に1回目をサーバーへ送っている。メインハンドで搭載したパイロンが、直後に空のオフ
        // ハンドで降ろされていた——傍目には機体が兵装を受け付けていないようにしか見えない。だからクライアントは
        // 全てに yes を返し、実際に何が起きたかはサーバーが決める。
        if (this.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }

        // スニーク中は弾庫を意味する。手に何を持っていても、機体のどの箱にクリックが当たっても同じ。
        //
        // パイロットや兵装より前に置く必要がある。機体の前でスニークしている者が最も持っていそうな物がそれだ
        // からだ——空のパイロンへミサイルを差し出せばパイロンに載る。スニークしていないクリックには正しく、
        // スニーク中のクリックにはまったく誤りだ。スニークすれば代わりに弾庫へ入る。
        //
        // これが置き換えたのは、スニーク中のクリックが「今はやめておく」を意味し、本来のクリック動作へ素通り
        // していた挙動だ。それは今もクリックにできることだが、スニーク無しで綴られるようになった。
        if (player.isSecondaryUseActive()) {
            this.openHold(player);

            return InteractionResult.CONSUME;
        }

        // 視線がパイロンを貫いたクリックは、ゲームがどの箱へ渡したかに関わらずそのパイロンへのクリックだ。
        //
        // ピック任せにせずここで決める必要がある。パイロンの箱は大抵主翼の箱の内側にあるからだ。ゲームは視線が
        // 最初に入った箱へクリックを渡すが、大半の角度ではそれが主翼になる。放置すると、主翼下のパイロンへ手を
        // 伸ばしたつもりがコックピットへ乗り込むことになる。意図と正反対で、起きてしまうと反論しにくい。
        //
        // 差し出す物が無いパイロン——空のパイロンに素手——は PASS を返し、クリックはそのまま下へ流れて通常の
        // 意味を持つ。
        VehiclePart pylon = this.pylonInSight(player);

        if (pylon != null) {
            InteractionResult reached = this.interactPylon(player, hand, pylon.getPylon());

            if (reached.consumesAction()) {
                return reached;
            }
        }

        ItemStack held = player.getItemInHand(hand);

        // 燃料缶を持って機体へ歩み寄る者は給油したいのであって、乗り込みたいのではない。兵装と同じ理由で
        // 搭乗より先に置く。満タンなら缶は減らず、クリックは素通りして通常の意味を持つ——それが「満タンの
        // 機体をうっかり撫でて缶を1個失う」を防いでいる。
        if (held.getItem() instanceof FuelItem && FuelItem.refuel(this, player, held)) {
            return InteractionResult.CONSUME;
        }

        // パイロンに載せられる物は、クリックが持ちえた他のどの意味よりも優先してパイロンに載る。ミサイルを
        // 持って駐機中の機体へ歩み寄る者は武装させたいのであって、乗り込みたいのでも機体をポケットへ畳みたいの
        // でもない。
        //
        // 載せられるかどうかは、試した後ではなくクリックを消費する前に判定する。さもないと空きパイロンが無い
        // 機体はクリックを飲み込んで何もせず、ゲームがプレイヤーを無視しているように見える。収まる場所の無い
        // 兵装を差し出せば、クリックは素通りして通常の意味を持つ。
        if (this.canBeArmedWith(held)) {
            if (this.fitAnywhere(held)) {
                held.consume(1, player);
                this.entityData.set(DATA_WEAPONS, this.weapons.syncTag());
            }

            return InteractionResult.CONSUME;
        }

        if (held.getItem() instanceof WrenchItem) {
            return this.dismantle(player);
        }

        return player.startRiding(this) ? InteractionResult.CONSUME : InteractionResult.PASS;
    }

    /**
     * レンチで機体を解体する。まず兵装を1つずつ、それが済んでから機体本体。
     *
     * <p>この順序こそ、1か所でまとめて行う理由の全てだ。搭載したまま畳んだ機体はアイテムへ戻り、吊っていた物を
     * 黙って全部持って行ってしまう。だから機体が畳まれる前にパイロンは空でなければならない。
     *
     * <p>どの兵装が外れるかは大抵ここへ来る前に決まっている。特定のパイロンへ向けたレンチは、そのパイロンへの
     * クリックとして処理されるからだ。ここが答えるのは機体全体へ向けたレンチであり、最後に搭載したステーション
     * から遡って外していく。
     */
    private InteractionResult dismantle(Player player) {
        // 残骸は先に、別の答えを返す。外す兵装も畳む機体ももう無く、片付けるべき残骸とその中の金属があるだけ
        // だ。駐機していることも求めない。落下中の全損機も全損機であり、着地を待たせて得る物は何も無い。
        if (this.isWrecked()) {
            return this.salvage();
        }

        // 地上作業であり、誰か座っている間は不可。
        if (!this.getPassengers().isEmpty() || !this.isParked()) {
            return InteractionResult.PASS;
        }

        if (this.weapons.hasRemovable()) {
            ItemStack removed = this.weapons.strip();
            this.entityData.set(DATA_WEAPONS, this.weapons.syncTag());

            if (!player.addItem(removed)) {
                player.drop(removed, false);
            }

            return InteractionResult.CONSUME;
        }

        // ここへ来る頃にはパイロンは空だが弾庫は空ではない。積んだまま畳めば積荷ごと持って行かれてしまう。
        this.spillHold();
        this.destroy(this.getDropItem());

        return InteractionResult.CONSUME;
    }

    /**
     * 特定のステーション1つへのクリック。そのステーションだけを意味し、他は意味しない。
     *
     * <p>受け取れる物を差し出せば受け取り、レンチを差し出せば最も外側に保持している物を返す。どちらも搭乗へは
     * 素通りしない。ステーションへ手を伸ばした者はコックピットへ手を伸ばしたのではないし、満載のステーションを
     * 「乗ってよい」の合図と解釈するのは、意図的な照準への答えとしてひどい。
     *
     * <p><b>ステーションが受け取る物は、その種類と現在の搭載状況で決まる。</b>兵装パイロンは空のときラックを
     * 受け取り、ラックが載れば兵装を受け取る——空のパイロンへミサイルを差し出しても何も起きない。空のパイロンには
     * ミサイルを吊る物が無いからだ。特殊ステーションはポッドを1つ受け取り、ラックは決して受け取らない。
     * {@link WeaponMounts} 参照。
     *
     * <p>それ以外——とりわけ素手——は背後の機体へそのまま通す。ステーションの箱は主翼の箱の内側にあるので、
     * クリックにとっては主翼の下面全体がステーションだ。素手で外せるようにしたら、武装した機体へ歩み寄って乗り
     * 込むだけで、通りすがりに兵装をエプロン中へ撒き散らすことになる。
     */
    public InteractionResult interactPylon(Player player, InteractionHand hand, int slot) {
        if (this.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }

        // 武装は地上作業であり、ステーションはそれ以外何もしない。搭乗もさせないし機体も畳まない。ステーション
        // は物を吊るための場所で、吊ることも外すこともできないクリックは単に何もしない。乗りたい者には機体の
        // 残り全部がクリック対象として残っている。
        //
        // 残骸にステーションは存在しない。パイロンは空で兵装を吊る物も無いので、クリックは背後の機体へ流れ、
        // そこでレンチが残骸を片付ける。
        if (this.isWrecked() || !this.isParked()) {
            return InteractionResult.PASS;
        }

        ItemStack held = player.getItemInHand(hand);

        if (this.fitAt(slot, held)) {
            held.consume(1, player);
            this.entityData.set(DATA_WEAPONS, this.weapons.syncTag());

            return InteractionResult.CONSUME;
        }

        // 「決して受け取れない物」ではなく「今は受け取れない物」を差し出した場合——空のパイロンへミサイル、
        // 既にラックのあるパイロンへラック。クリックは消費され何も起きない。明らかにこのステーションで作業中の
        // 者への答えとしてはそれが正しい——素通りさせると、持ち物を間違えただけでコックピットへ入ってしまう。
        if (isStore(held)) {
            return InteractionResult.CONSUME;
        }

        // 取り外しには工具が要る。機体本体の解体と同じだ。兵装を持ったままの2度目のクリックは、次を搭載しよう
        // としている者であって、前のを取り消そうとしている者ではない。
        if (held.getItem() instanceof WrenchItem && this.weapons.canStripAt(slot)) {
            ItemStack removed = this.weapons.strip(slot);
            this.entityData.set(DATA_WEAPONS, this.weapons.syncTag());

            if (!player.addItem(removed)) {
                player.drop(removed, false);
            }

            return InteractionResult.CONSUME;
        }

        return InteractionResult.PASS;
    }

    /**
     * プレイヤーの視線が通る最も手前のパイロン。どれにも当たらなければ null。
     *
     * <p>パイロンの箱と主翼の箱が同じ空間を占めるので必要になる。ゲームは視線が最初に入った箱へクリックを渡す
     * が、主翼の下ではそれは主翼だ。だから視線上で最手前でないパイロンは決して届かなくなる。パイロンだけに対して
     * 視線を通せば、プレイヤーが実際に問うていた問いに答えられる。
     *
     * <p>「機体に最も近い」ではなく「視線上で最も手前」。同じ主翼の内側と外側に兵装を積む機体は両方が同時に視界
     * に入るので、手前にある方が指されている物だ。
     */
    @Nullable
    private VehiclePart pylonInSight(Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 reach = eye.add(player.getViewVector(1.0F).scale(player.entityInteractionRange()));
        VehiclePart nearest = null;
        double closest = Double.MAX_VALUE;

        for (VehiclePart part : this.parts) {
            if (!part.isPylon() || !part.isPickable()) {
                continue;
            }

            Optional<Vec3> hit = part.clip(eye, reach, 0.0);

            if (hit.isEmpty()) {
                continue;
            }

            double distance = eye.distanceToSqr(hit.get());

            if (distance < closest) {
                closest = distance;
                nearest = part;
            }
        }

        return nearest;
    }

    /**
     * このハードポイントが、今あるいは後でプレイヤーが物を吊れる物か。機体に固定された機銃ではなくパイロンか。
     *
     * <p>パイロン自身の箱が「そもそも届く価値があるか」を判断するために問う。残骸には1つも無い。ステーションは
     * 機体と共に吹き飛び、できることは何も無いので、全ての箱が身を引いてクリックを背後の機体へ届かせる——そこで
     * レンチが残骸を片付ける。両側に届く状態から両側で算出するので、どのクリックが何を狙っていたかについて
     * クライアントとサーバーの見解が一致する。
     */
    public boolean isLoadablePylon(int slot) {
        if (this.isWrecked()) {
            return false;
        }

        List<AircraftDefinition.Hardpoint> hardpoints = this.getStats().hardpoints();

        return slot >= 0 && slot < hardpoints.size() && !hardpoints.get(slot).isFixed();
    }

    /**
     * このスタックが今この機体に取り付けられる物か。種類が正しく、受け取れる場所が機上にあり、機体が車輪の上で
     * 静止していること。武装は地上作業だ。
     *
     * <p>両側に届く状態から両側が自力で算出するので、通知無しでもクリックの意味についてクライアントとサーバーの
     * 見解が一致する。
     */
    private boolean canBeArmedWith(ItemStack held) {
        if (!this.isParked()) {
            return false;
        }

        if (held.getItem() instanceof RackItem) {
            return this.weapons.hasBarePylon();
        }

        if (held.getItem() instanceof EquipmentItem) {
            return this.weapons.hasBareSpecial();
        }

        return held.getItem() instanceof WeaponItem weapon && this.weapons.canMount(weapon.getWeaponId());
    }

    /** この機体が受け取れるかどうかに関わらず、ステーションに載る3種のいずれかか。 */
    private static boolean isStore(ItemStack held) {
        return held.getItem() instanceof WeaponItem || held.getItem() instanceof RackItem
                || held.getItem() instanceof EquipmentItem;
    }

    /**
     * 手持ちの物を指定ステーションへ1つ載せる（そのステーションが受け取るなら）。
     *
     * <p>どのアイテムが搭載機構の3つの入口のどれへ行くかを知っている唯一の場所。ステーションへのクリックと機体
     * 全体へのクリックが同じ規則で武装するようにするためだ。
     *
     * @return 何か載ったか。スタックから消費すべきかどうかも兼ねる
     */
    private boolean fitAt(int slot, ItemStack held) {
        if (held.getItem() instanceof RackItem rack) {
            return this.weapons.fitRackAt(slot, rack.getRackId());
        }

        if (held.getItem() instanceof EquipmentItem pod) {
            return this.weapons.fitEquipmentAt(slot, pod.getEquipmentId());
        }

        return held.getItem() instanceof WeaponItem weapon
                && this.weapons.mountAt(slot, weapon.getWeaponId(), WeaponItem.ammoOf(held));
    }

    /** 同じ処理を、受け取れる最初のステーションに対して行う版。機体へのクリックの意味。 */
    private boolean fitAnywhere(ItemStack held) {
        if (held.getItem() instanceof RackItem rack) {
            return this.weapons.fitRack(rack.getRackId());
        }

        if (held.getItem() instanceof EquipmentItem pod) {
            return this.weapons.fitEquipment(pod.getEquipmentId());
        }

        return held.getItem() instanceof WeaponItem weapon
                && this.weapons.mount(weapon.getWeaponId(), WeaponItem.ammoOf(held));
    }

    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction moveFunction) {
        super.positionRider(passenger, moveFunction);

        // 搭乗者は全員、パイロットを含めて旋回で運ばれる。機体はキー操作で飛ばすので視界は好きな向きでよく、
        // それなら進行方向を向いていた方がよい。
        passenger.setYRot(passenger.getYRot() + this.deltaRotation);
        passenger.setYHeadRot(passenger.getYHeadRot() + this.deltaRotation);
        this.clampRotation(passenger);
    }

    @Override
    public void onPassengerTurned(Entity passenger) {
        this.clampRotation(passenger);
    }

    /**
     * 搭乗者を機体の向きに合わせて座らせる。
     *
     * <p>体だけ。以前は<em>視線</em>もここで左右135度にクランプしていた。頭はそれ以上回らないから、という理屈
     * だ。頭については正しいが、カメラについては正しくない。機外視点にはそもそも頭が関与しないし、外部視点の
     * 唯一の存在意義は後ろを見ることであって——その制限はそれを不可能にしていた。コックピット視点は今も首の
     * 限界で止まるが、止めるのは {@code CockpitView} であり、方位ではなく機体に対して測る。正しくできるのは
     * 2つのうちそちらだけだ。
     */
    protected void clampRotation(Entity passenger) {
        passenger.setYBodyRot(this.getYRot());
    }

    /**
     * 自前の箱を持つ機体自身は固体ではない。固体なのは箱の方だ。
     *
     * <p>Minecraft はエンティティに正方形底面の直立直方体を1つ与えるが、15m の機体にとってそれは小屋だ——主翼が
     * 何をしていようと差し渡し6.5ブロックで、バンクすれば主翼からかけ離れる。機体自身のファイルにある箱こそ本当
     * の形状なので、箱が1つでもあればそちらが仕事をし、素の直方体はふりをやめる。
     *
     * <p>降板しても失う物は無い。{@link VehiclePart} が被弾・クリック・ピック結果を機体へそのまま渡すので、
     * 撃たれることも乗り込まれることも上に立たれることも従来通りここへ届く。自前の箱を持たない機体は素の直方体を
     * 保つ。さもないと一切触れられなくなるからだ。
     *
     * <p>素の直方体は依然として存在し、依然として役目を果たす。機体自身の移動が衝突する相手であり、接地判定であり、
     * ゲームが機体の所属チャンクや描画価値を決めるのに使う物だ。譲り渡すのは障害物と標的の役目だけ。
     */
    @Override
    public boolean canBeCollidedWith() {
        return !this.isRemoved() && !this.hasAirframeBoxes();
    }

    @Override
    public boolean isPickable() {
        return !this.isRemoved() && !this.hasAirframeBoxes();
    }

    /**
     * 機体の箱のうち、パイロンではなく機体構造である物が1つでもあるか。
     *
     * <p>パイロンだけでは素の直方体を降板させるのに足りない。ハードポイントはあるが自前の箱を持たない機体は、
     * さもないとクリックも被弾も主翼下の小さな箱5つでしか受けられず、乗り込む手段がまったく無くなる。
     */
    private boolean hasAirframeBoxes() {
        for (VehiclePart part : this.parts) {
            if (!part.isPylon()) {
                return true;
            }
        }

        return false;
    }

    /**
     * 機体を移動させる。止めるのは Minecraft が与える箱ではなく、実際に持っている形状だ。
     *
     * <p>エンティティは直立の箱1つで衝突し、機体ではその箱を小さく保たねばならない——ワールド軸に整列した15m の
     * 箱は何にでも引っ掛かり、機体を設置不能にする。だから機体はゲームの各種処理向けに小さい箱を保持したまま、
     * 移動はまず本物の箱に対して測る。最も早く止められる箱が、機体がどこまで進めるかを決める。斜面に当たりそうに
     * 見える主翼が、実際に当たるようになった。
     *
     * <p>この方式で判定するのはブロックだけ。エンティティは従来通り素の直方体に任せる。それが機体が自分の箱と
     * 衝突するのを防いでもいる。
     *
     * <p>ただし対象は飛行モデル自身の移動だけ。それ以外で機体を動かす物は、<em>既に到達した</em>位置を伝えている
     * ——とりわけバニラの vehicle パケットは、サーバーへ {@link MoverType#PLAYER} の移動として届き、ここで何を
     * 決めようと数行後の {@code absMoveTo} に上書きされる。その報告を形状に対して判定するのは無益を通り越して
     * 有害だ。答えは捨てられるうえ、算出には機体を構成する全ての箱を飛行経路の丸1tick分——最速で17ブロック——
     * にわたって十数回スイープする必要がある。サーバースレッド上で、誰かが飛ばしている全機体について、だ。
     * サーバー全員が感じるヒッチであり、得る物はまったく無い。操縦側は実際に見えている地面に対してこの判定を
     * 既に済ませている。{@code GroundVehicleEntity} が報告移動に対してこれを走らせたことが無いのも同じ理由だ。
     */
    @Override
    public void move(MoverType type, Vec3 movement) {
        if (type != MoverType.SELF) {
            super.move(type, movement);

            // パイロットの移動報告は、サーバー側のtickが走っているかに関わらず機体を運ぶ——そして tick が
            // 止まるのはまさに、機体が生成器を追い越してまだ作られていないチャンクへ入ったときだ。それが、まだ
            // 動いている機体の後ろに回廊を置き去りにしていた。だから報告による移動も回廊を引きずる。普通の
            // サーバースレッド上のパケット処理であり、update() を呼んでよい2か所のうちの1つ。tick でも実行された
            // 場合の2回目の呼び出しは equals() で短絡しコストは無い。
            if (type == MoverType.PLAYER && !this.level().isClientSide) {
                this.heldChunks = AircraftChunkLoader.update(this, this.heldChunks);
            }

            return;
        }

        Vec3 allowed = this.limitToShape(movement);
        Vec3 before = this.position();

        super.move(type, allowed);
        // 世界が実際に許した移動量。要求値でもなく、事後のデルタ移動が保持する値でもない。残骸はこれだけを頼り
        // に飛ぶ。wreckTick 参照。
        this.lastTravel = this.position().subtract(before);

        // super.move が知っているのは素の直方体が止められたかどうかだけ。自前の形状で止まったならフラグに
        // そう書かねばならない。さもないと主翼が崖に折り畳まれても誰も気付かない——衝突検出が読むのはまさに
        // これらだ。
        if (allowed.x != movement.x || allowed.z != movement.z) {
            this.horizontalCollision = true;
        }

        if (allowed.y != movement.y) {
            this.verticalCollision = true;
        }

        // setOnGround を意図的に呼ばない。接地しているかは車輪についての問いであり、素の直方体は車輪の位置に
        // ある。さもないと飛行中に翼端が斜面を掠めただけで機体は着陸したと判断し、水平に戻って空中で速度を失う。
    }

    /**
     * 自分の箱のどれかが世界に当たるまで、機体がどれだけ動けるか。
     *
     * <p>箱ごとに個別に問い、各軸で最も停止に近い答えを採って統合する。連結形状の完全なスイープではない——ある箱
     * が既に止められたことを他の箱は知らない——が、箱を壁に通さない点については厳密であり、目に見えるのはその部分
     * だ。
     */
    private Vec3 limitToShape(Vec3 movement) {
        if (movement.lengthSqr() == 0.0 || this.level().isClientSide && !this.isControlledByLocalInstance()) {
            return movement;
        }

        List<VehicleShape.Box> shape = this.getShape().boxes();

        if (shape.isEmpty()) {
            return movement;
        }

        Vec3 allowed = movement;
        double underside = this.scrapeLine();

        for (VehicleShape.Box box : shape) {
            // 実際に寝ている姿の箱をブロックに対してスイープする。バンクした主翼は斜めの薄板を占めるが、その
            // 薄板を囲む直立の板で止めると、旋回でロールした機体は何も無い空気に急停止させられる。
            Vec3 stopped = Hitboxes.throughBlocks(this, this.hitbox(box), movement, underside);

            allowed = new Vec3(
                    nearerToZero(allowed.x, stopped.x),
                    nearerToZero(allowed.y, stopped.y),
                    nearerToZero(allowed.z, stopped.z));
        }

        return allowed;
    }

    /** 2つのうち移動量の小さい方。パイロットが要求した符号は保つ。 */
    private static double nearerToZero(double a, double b) {
        return Math.abs(a) <= Math.abs(b) ? a : b;
    }

    /**
     * これより下の地面は、機体が突っ込む相手ではなく擦る相手になる、という高さ。
     *
     * <p>降着装置は機体が地面に触れることを想定された最も低い部位だが、機体が<em>持っている</em>最も低い部位では
     * ない。離陸のための機首上げは尾部を車輪より下、滑走路の中へ振り下ろす。フレアを取った着陸も進入中に同じこと
     * をする。他と同様にブロックへスイープすると、その尾部は機体が飛行速度で突っ込む壁になる——離陸滑走は急停止
     * し、衝突が奪った速度がその全てになり、{@link #detectCrash} は「1tickで80ノット失った機体は何かに当たった」
     * とまったく正しく結論する。実際当たっている。走っていた滑走路に、だ。
     *
     * <p>よって、車輪が地面に触れる形態にある間——脚が出ていて翼がほぼ水平——は、車輪と車輪が乗り越える段差より
     * 高くない物は機体を止めない。その線より上は従来通り全て止める。それが「滑走路を擦る」ことと「その先の丘へ
     * 突っ込む」ことの違いだ。脚を上げた空中や、翼端が車輪より低くなるまでロールした状態では、機体は再び全てに
     * 当たる。
     *
     * <p>機体を床下へ通すことはできない。素の直方体は車輪の位置にあり、従来通り {@code move} が世界に対して決着
     * させるので、車輪より下の地面は依然として機体を支え、そこへの降下は到達速度が何であれ依然として「到達」だ。
     */
    private double scrapeLine() {
        if (!this.onWheels()) {
            return Hitboxes.UNDERSIDE_NONE;
        }

        // 車輪だけでなく降着装置が乗り越える段差も含める。車輪が登る縁石は、機体が止められるべき縁石ではない。
        // maxUpStep 参照。
        return this.getBoundingBox().minY + this.getStats().landingGear().climbHeight();
    }

    /**
     * これより下のブロックは、機体が埋まっている世界ではなく機体が乗っている床である、という高さ。
     *
     * <p>{@link #scrapeLine} の対で、移動ではなく {@link #insideTerrain} 用。2つを同じメソッドにしていない理由は
     * 脚を上げた機体だ。そのとき擦る動作は存在しない——胴体には転がる車輪が無いので機体は全てに当たり、到達は
     * 到達だ——が、機体が何の<em>内側</em>にいるかという問いの答えはどちらでも同じになる。素の直方体は車輪の位置
     * にあり通常通り {@code move} が世界に対して決着させるので、その底面より高くない物が機体を支えている物だ。
     * それと重なっているのは、下から見た「地面に立っている」状態にすぎない。世界が機体の上に閉じたわけではないし、
     * 飛び出すことがその答えでもない。
     *
     * <p>本当に機体の周りに現れた地形はこれに影響されない。飛行中の機体の位置に現れた斜面は車輪を越えてさらに上
     * まで届く——それが斜面の内側にいるということだ——ので、この線より上のブロックは全て従来通り数えられる。
     */
    private double floorLine() {
        double scrape = this.scrapeLine();

        // 脚が出て正立しているなら、降着装置が乗り越える段差も床のうち。
        return scrape == Hitboxes.UNDERSIDE_NONE ? this.getBoundingBox().minY : scrape;
    }

    /**
     * 機体は送られてくる距離まで描く価値がある。
     *
     * <p>Minecraft はエンティティの描画価値のある距離を大きさから算出し、機体では数百ブロックになるが、まるで
     * 足りない。サーバーは {@code ghost_range} まで機体を報告するし、報告されて描かれない物は空に空いた穴に
     * すぎない。これは外側の上限だけを定める。ゲーム自身のレンダラーがどこで止まりゴーストパスが引き継ぐかは
     * クライアント側の {@code AircraftRenderer.shouldRender} が決める。
     */
    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        VehicleChassis.Hitbox hitbox = this.getStats().hitbox();

        if (!hitbox.hasGhostLimit()) {
            return true;
        }

        double range = hitbox.ghostRange();

        return distance < range * range;
    }

    /**
     * コントローラは降着装置用の1つだけ。機体が自身に対して行う他の全ては飛行状態に毎瞬追従する物で、
     * {@link com.ashvehicles.client.model.AircraftModel#setCustomAnimations} がコードでポーズを付ける。脚は手順
     * なので、代わりに機体のアニメーションファイルから再生する。
     *
     * <p>その両半分は {@link com.ashvehicles.client.model.AircraftAnimations} で算出される。クライアントコードだ。
     * ここから届く物は何も無い。コントローラは何かが描画されている間しか処理されないので、サーバーはこれを登録
     * した後は二度と見ない。
     *
     * <p>トランジションは、作動途中で気が変わったパイロットに対応する。GeckoLib はアニメーションを逆再生できない
     * ので、代わりに脚が到達した位置からもう一方のアニメーションの先頭へブレンドする。数tickのそれは、脚のテレ
     * ポートではなく「ためらい」に見える。
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "gear", AircraftAnimations.TRANSITION_TICKS,
                AircraftAnimations::gearCycle).setAnimationSpeedHandler(AircraftAnimations::gearSpeed));
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.setThrottle(tag.getFloat("Throttle"));

        if (tag.contains("Attitude")) {
            ListTag stored = tag.getList("Attitude", Tag.TAG_FLOAT);
            this.snapAttitude(new Quaternionf(stored.getFloat(0), stored.getFloat(1),
                    stored.getFloat(2), stored.getFloat(3)));
        } else {
            this.snapAttitude(Attitude.of(this.getYRot(), this.getXRot()));
        }
        // 耐久システムが存在する前にワールドへ書き出された機体には読む値が無いので、残量0ではなく無傷で戻る。
        this.setHealth(tag.contains("Health") ? tag.getFloat("Health") : this.getMaxHealth());
        this.setCountermeasures(true, tag.contains("Flares")
                ? tag.getInt("Flares") : this.getStats().countermeasures().flares());
        this.setCountermeasures(false, tag.contains("Chaff")
                ? tag.getInt("Chaff") : this.getStats().countermeasures().chaff());
        this.entityData.set(DATA_GEAR_DOWN, !tag.contains("GearDown") || tag.getBoolean("GearDown"));
        this.gearProgress = this.isGearDown() ? 1.0F : 0.0F;
        this.gearProgressO = this.gearProgress;
        this.entityData.set(DATA_VTOL, tag.getBoolean("Vtol"));
        this.vtolProgress = this.isVtolSelected() ? 1.0F : 0.0F;
        this.vtolProgressO = this.vtolProgress;
        this.entityData.set(DATA_FLAPS_DOWN, tag.getBoolean("FlapsDown"));
        this.flapsProgress = this.isFlapsDown() ? 1.0F : 0.0F;
        this.flapsProgressO = this.flapsProgress;
        this.weapons.load(tag.getCompound("Weapons"));
        this.stations.load(tag.getCompound("Stations"));
        this.entityData.set(DATA_WEAPONS, this.weapons.syncTag());

        // 書き出し時にこの機体が開いたまま保持していた回廊。チケット自体はワールドと共に保存される——このチャンク
        // を、ひいてはこの機体をロードし戻したのがそれだ——が、キーは自分の UUID であり、どれが自分の物かを他は
        // 知らない。読み戻した後、次の update() が突き合わせる。まだ必要な物は二重取得せずに保持し、飛行が必要と
        // しなくなった物は解放して、地面を永久に開いたままにしない。
        long[] corridor = tag.getLongArray("HeldChunks");

        if (corridor.length > 0) {
            Set<ChunkPos> held = new HashSet<>(corridor.length);

            for (long packed : corridor) {
                held.add(new ChunkPos(packed));
            }

            this.heldChunks = Set.copyOf(held);
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("Throttle", this.getThrottle());

        ListTag stored = new ListTag();
        stored.add(FloatTag.valueOf(this.attitude.x));
        stored.add(FloatTag.valueOf(this.attitude.y));
        stored.add(FloatTag.valueOf(this.attitude.z));
        stored.add(FloatTag.valueOf(this.attitude.w));
        tag.put("Attitude", stored);
        tag.putFloat("Health", this.getHealth());
        tag.putInt("Flares", this.getCountermeasures(true));
        tag.putInt("Chaff", this.getCountermeasures(false));
        tag.putBoolean("GearDown", this.isGearDown());
        tag.putBoolean("FlapsDown", this.isFlapsDown());
        tag.putBoolean("Vtol", this.isVtolSelected());
        tag.put("Weapons", this.weapons.save());
        tag.put("Stations", this.stations.save());

        // この機体の保存済みチケットがどのチャンク向けか。readAdditionalSaveData 参照。
        long[] corridor = new long[this.heldChunks.size()];
        int at = 0;

        for (ChunkPos pos : this.heldChunks) {
            corridor[at++] = pos.toLong();
        }

        tag.putLongArray("HeldChunks", corridor);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.animatableCache;
    }
}
