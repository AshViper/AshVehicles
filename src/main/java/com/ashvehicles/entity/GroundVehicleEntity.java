package com.ashvehicles.entity;

import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.vehicle.VehicleShape;
import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.item.AmmoItem;
import com.ashvehicles.item.AmmunitionItem;
import com.ashvehicles.item.FuelItem;
import com.ashvehicles.item.WrenchItem;
import com.ashvehicles.vehicle.GroundVehicleDefinition;
import com.ashvehicles.vehicle.Ride;
import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.weapon.AmmoKind;
import com.ashvehicles.weapon.BuiltInGun;
import com.ashvehicles.weapon.Magazine;
import com.ashvehicles.weapon.TargetLock;
import com.ashvehicles.weapon.TurretLauncher;
import com.ashvehicles.weapon.WeaponDefinition;
import com.ashvehicles.weapon.WeaponMounts;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.FluidTags;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * MOD 内の全地上車両に共通する挙動。単純な駆動系、乗っている地面に沿って寝る車体、その両方と独立に照準
 * される砲塔、そして戦車を炎上させるダメージ処理。
 *
 * <p><b>移動処理が走る場所。</b>航空機やバニラのボートと同じく、車両は「操作している側」がシミュレートする。
 * プレイヤーが座っている間は運転クライアント、それ以外はサーバー。判定は {@link #isControlledByLocalInstance()}。
 * 位置・ヨー・ピッチはバニラの ServerboundMoveVehiclePacket でサーバーへ届く。車体のロール、速度、砲塔の指向
 * にはバニラの対応物が無いのでクライアントが
 * {@link com.ashvehicles.network.GroundVehicleInputPayload} で送り、サーバーが同期データへ複製して他全員へ渡す。
 *
 * <p><b>航空機との違い。</b>航空機は自分で姿勢を決め、世界に口出しの余地は無い。戦車は逆で、運転手が決めるのは
 * 進行方向だけ、車体のピッチとロールは毎tick履帯の下の地面から読み取る。つまりここでの姿勢は積分ではなく
 * 組み立てだ——運転手が操る方位＋地形が決める2角——だから累積回転のドリフトも正規化も存在しない。
 *
 * <p>砲塔だけはその全てに逆らって照準される。車体座標系で保持し、車体が回った分だけ巻き戻すので、目標に
 * 据えた砲塔は車体が下で溝を越えても据わったまま。それがスタビライザーの仕事であり、戦車と自走砲の違いだ。
 */
public class GroundVehicleEntity extends VehicleEntityBase implements GeoEntity {
    /**
     * 車体がどう寝ているかを回転として保持。Minecraft はエンティティに方位と仰角しか与えずロールが無く、
     * 斜面を横切る戦車を表現できない。よって本当の姿勢はここが持ち、バニラの角度は後ろで追随させる。
     */
    private static final EntityDataAccessor<Quaternionf> DATA_ATTITUDE =
            SynchedEntityData.defineId(GroundVehicleEntity.class, EntityDataSerializers.QUATERNION);
    /**
     * 自機の前方向への速度（ブロック/tick）。運転している側から送られる。
     *
     * <p>移動を回すのは1台だけで、他のコピーは位置の流れから車両を描くしかない。その流れから速度を逆算すると
     * 同期していない3つの時計のずれを速度として読んでしまう。送るコストは1tickあたりfloat 1個で、これが
     * 転輪の回転速度でありエンジン音のピッチ元。
     */
    private static final EntityDataAccessor<Float> DATA_SPEED =
            SynchedEntityData.defineId(GroundVehicleEntity.class, EntityDataSerializers.FLOAT);
    /** 砲塔の指向。車体正面からの角度（度）、右が正。 */
    private static final EntityDataAccessor<Float> DATA_TURRET_YAW =
            SynchedEntityData.defineId(GroundVehicleEntity.class, EntityDataSerializers.FLOAT);
    /** 砲塔上面線からの砲の仰角（度）。負が俯角。 */
    private static final EntityDataAccessor<Float> DATA_GUN_PITCH =
            SynchedEntityData.defineId(GroundVehicleEntity.class, EntityDataSerializers.FLOAT);
    /** 主兵装の残弾。 */
    private static final EntityDataAccessor<Integer> DATA_ROUNDS =
            SynchedEntityData.defineId(GroundVehicleEntity.class, EntityDataSerializers.INT);
    /**
     * 装填完了までのtick数。カウントダウン。
     *
     * <p>全員が必要とし、誰も自力では算出できないので送る。乗員が発砲判断に使う計器であり、砲身の後座を描く
     * 元でもある——そして0から跳ね上がったカウンタは「発砲した」という報せそのものなので、イベントでないまま
     * イベントの仕事もこなす。{@link com.ashvehicles.weapon.BuiltInGun} 参照。
     */
    private static final EntityDataAccessor<Integer> DATA_RELOAD =
            SynchedEntityData.defineId(GroundVehicleEntity.class, EntityDataSerializers.INT);
    /**
     * 同軸機銃のベルト残量と、その独自の発射間隔。
     *
     * <p>1つ目を分け合うのではなく2つ目の組を持つ。2つの銃身は別々に装填され、別々に撃たれ、別々に尽きる。
     * 装填手が砲弾に掛かりきりで黙る機関銃など誰も当てにできない。
     */
    private static final EntityDataAccessor<Integer> DATA_COAX_ROUNDS =
            SynchedEntityData.defineId(GroundVehicleEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_COAX_RELOAD =
            SynchedEntityData.defineId(GroundVehicleEntity.class, EntityDataSerializers.INT);
    /** 発射筒に残るミサイル数と、次弾までの待ち時間。 */
    private static final EntityDataAccessor<Integer> DATA_MISSILES =
            SynchedEntityData.defineId(GroundVehicleEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_MISSILE_RELOAD =
            SynchedEntityData.defineId(GroundVehicleEntity.class, EntityDataSerializers.INT);
    /**
     * 架台ごとの弾種別の残弾と、今どの弾種を選んでいるか。
     *
     * <p>弾種を並べた車両でだけ中身を持つ。並べていない車両——MOD 内の大半——では空のまま一度も書かれず、
     * 上の残弾カウンタが従来通り唯一の値になる。{@link com.ashvehicles.weapon.Magazine} 参照。
     *
     * <p>数値ではなくタグで送るのは、数える対象が車両ファイル次第で1個から任意個になるから。同期データの
     * 枠は宣言時に固定なので、可変長の物はタグ1つに畳むしかない。中身は架台ごとに小さな整数が数個で、
     * 書き込みは装填・切り替え・発砲のときだけだ。
     */
    private static final EntityDataAccessor<CompoundTag> DATA_MAGAZINE =
            SynchedEntityData.defineId(GroundVehicleEntity.class, EntityDataSerializers.COMPOUND_TAG);
    /**
     * トリガーがどちらの兵装を撃つか。
     *
     * <p>計器がこれを元に描かれ、決めるのがサーバーなので同期する。キー入力はパケットで届き、他の全クライアント
     * は乗員が見ているのと同じ照準を描かねばならない。
     */
    private static final EntityDataAccessor<Integer> DATA_ARMAMENT =
            SynchedEntityData.defineId(GroundVehicleEntity.class, EntityDataSerializers.INT);
    /**
     * シーカーが捉えている対象のエンティティ番号と、ロックの進行度。
     *
     * <p>シーカー関連が全部そうであるように、算出ではなく送信する。兵器が何を向いているかの決定はクライアント
     * の仕事ではない。計器に必要なのはこの2つの数値だけ——どれに枠を描くか、どこまで閉じたか——で、進行度は
     * 既にミサイル自身の lock_ticks で正規化済み。だからクライアントは筒の中の弾について何も知らずに描ける。
     */
    private static final EntityDataAccessor<Integer> DATA_LOCK_TARGET =
            SynchedEntityData.defineId(GroundVehicleEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_LOCK_PROGRESS =
            SynchedEntityData.defineId(GroundVehicleEntity.class, EntityDataSerializers.FLOAT);
    /**
     * 発射機が据えている点に立つ {@link DesignationEntity} のエンティティ番号。据えていなければ -1。
     *
     * <p>シーカーの捕捉と同じ理由で同期する。何を狙っているかを決めるのはサーバーで、乗員の計器も、同じ車両を
     * 見ている他の全クライアントも、その1つの数から描く。
     */
    private static final EntityDataAccessor<Integer> DATA_DESIGNATED =
            SynchedEntityData.defineId(GroundVehicleEntity.class, EntityDataSerializers.INT);
    /**
     * 座標を入れてから撃てるようになるまでの tick 数。
     *
     * <p>短いが 0 ではない。実物が座標を入力してから発射準備が整うまでの間であり、ここでは同じボタンで据えて
     * 撃つ操作（{@code GroundVehicleInputHandler} 参照）が、クリック2回で発射にならないための間でもある。
     */
    private static final int LAY_SETTLE_TICKS = 20;
    /**
     * ハンドルの切れ量。-1〜1。
     *
     * <p>速度と同じ理由で送る。運転を回すのは1台だけで、他は位置の流れから描くしかない。しかもこれは流れから
     * 逆算もできない——装輪車は停止中に旋回できないので、停止中に全舵を当てても誰にも測れる変化は起きないのに、
     * 前輪は明らかに切れている。車輪が追うのは車体ではなく運転手だ。
     */
    private static final EntityDataAccessor<Float> DATA_STEER =
            SynchedEntityData.defineId(GroundVehicleEntity.class, EntityDataSerializers.FLOAT);


    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    /** ブロック/tick^2。バニラの値だが、車両を自前で動かすので自前で適用する。 */
    private static final double GRAVITY = 0.08;
    /** 終端速度。崖から落ちた車両が世界を突き抜けないように。 */
    private static final double MAX_FALL = 3.0;

    /**
     * 車体の上下どこまで地面を探すか（ブロック）。
     *
     * <p>{@code PROBE_ABOVE} は逆方向へ引っ張る2つの要求の妥協点。登坂可能な最急斜面での車両の角に届かないと
     * 読み値が頭打ちになり、車体が坂より寝てしまう——5ブロック履帯の半分が35度斜面なら角は中心より1.75ブロック
     * 上になる。一方で、車両が普通に入りうる物の天井より<em>下</em>に収める必要もある。トンネル天井の内側から
     * 始まったプローブは天井を地面として報告し、車両は進入を拒む。2ブロックなら段差2を見て、高さ3のトンネルに
     * 収まる。
     *
     * <p>頭打ちは安全側に外れる。これで抑えられた読み値は登りを実際より小さく見せるので、何も無い所で止まるより
     * 車両を通す方に転ぶ。
     */
    private static final double PROBE_ABOVE = 2.0;
    private static final double PROBE_BELOW = 3.0;

    /**
     * 読み値を「地表」ではなく「プローブ自身の上限」と見なすための、上端からの許容幅（ブロック）。
     *
     * <p>ブロック内部から始めたトレースは開始点より自身の長さの1/1000だけ下を返す——{@code VoxelShape.clip} が
     * 内部判定の前に開始点をずらすため——ので読み値は {@link #PROBE_ABOVE} ちょうどではなく僅かに下に来る。
     * 1cm の余裕でそれを吸収でき、地形の測定単位よりはるかに小さい。
     */
    private static final double PROBE_CEILING_SLACK = 0.05;

    /** これ未満の速度は0扱い。車両は停止しており、停止させたままにすべき。 */
    private static final float STANDSTILL = 1.0E-4F;

    /**
     * 操舵輪が1tickで振れる量を、全舵角に対する割合で。
     *
     * <p>度/tick ではなく割合にしてあるので、舵角の大きい車両も小さい車両も全舵まで同じtick数で届く——実際に
     * 運転手がハンドルを回す様子はそれだし、車両ごとに個別調整したい類の値でもない。1tickあたり1/5なら、直進
     * から全舵まで0.25秒強。
     */
    private static final float STEER_SWING = 0.22F;

    /**
     * トレースする価値が生じる隙間の高さ（ブロック）。
     *
     * <p>全高が自身の段差高に収まる車両には、止めうる頭上物が存在しない。長さ0のトレースは行わない方がよい。
     */
    private static final double HEADROOM_MARGIN = 0.1;

    /**
     * 登坂判定に持たせる僅かな遊び（ブロック）。
     *
     * <p>地面の読み値はレイがブロック面に当たった位置をレイ上で算出した物なので、ちょうど1ブロック差の面に立つ
     * 2つのプローブが double の最下位ビット分だけ食い違いうる。厳密に取ると、戦車が1ブロックの縁石を越えられる
     * か——{@code climb_height: 1.0} が明らかに越えると言っており、45度斜面の全ての段差でもある——が、その1
     * ビットの転び方次第になってしまう。
     */
    private static final double CLIMB_SLACK = 1.0E-3;

    /**
     * 破砕の開始高さを、段差より更にどれだけ車体上方へ上げるか（ブロック）。
     *
     * <p>車両が押し破る領域の床は、自身のサスペンションが沿っている平面より段差1つ分上にある——登坂判定が引く
     * のと同じ線なので、車両を止める物と乗り越える物が同一のブロック集合になる。ただしその平面は地面から直接
     * 取るのではなく地面へ<em>なめらかに追従</em>するので、斜面に当たったばかりの車体は1〜2tick斜面に遅れる。
     * その間平面は斜面の下に潜り、厳密に取ると車両はこれから乗り越える段差を壊してしまう。半ブロックあれば遅れ
     * を吸収でき、実害は無い——壊せる壁は1段目ではなく2段目から壊れ、1段目は縁石同様に乗り越えられる。
     */
    private static final double CRUSH_CLEARANCE = 0.5;

    /**
     * 破砕領域を車体平面に沿わせる際、その平面の傾きとして許す最大値（度）。垂直を越えると平面の傾きは数値で
     * なくなるし、被弾で横倒しになった車体が地中1マイルまで届いてもらっては困る。
     */
    private static final float CRUSH_SLOPE_CAP = 60.0F;

    /** 降車者を車両からどれだけ離して降ろすか（ブロック）。 */
    private static final double DISMOUNT_MARGIN = 0.2;

    /**
     * 地面が船の喫水線よりどれだけせり上がったら「船を止める岸」と見なすか（ブロック）。これ未満なら深い水の
     * 下の海底で、船はその上を素通りする。超えれば水面を破る陸で、船は座礁する。
     */
    private static final double SHORE_CLEARANCE = 0.5;

    /** 運転していないクライアントで、サーバーからの補正を何tickかけて馴らすか。 */
    private static final int LERP_TICKS = 3;

    private final AnimatableInstanceCache animatableCache = GeckoLibUtil.createInstanceCache(this);

    /** 砲塔の主砲（あれば）。発砲はサーバーのみ。 */
    private final BuiltInGun gun = new BuiltInGun(this, BuiltInGun.Mount.MAIN);
    /**
     * それに固定された機関銃（あれば）。上の主砲と同じクラス・同じ条件——同じクリックで装填し、サーバーで発砲
     * ——だが独自のトリガーを持つ。同軸機銃は<em>選択</em>される物ではなく、主兵装が何をしていようとそこに
     * あるからだ。
     */
    private final BuiltInGun coax = new BuiltInGun(this, BuiltInGun.Mount.COAXIAL);
    /** 発射筒のミサイル（あれば）。常時捜索するシーカーもここ。 */
    private final TurretLauncher launcher = new TurretLauncher(this);
    /** 据えた点に立たせているマーカー。サーバー側だけが持つ。{@link #designate} 参照。 */
    @Nullable
    private DesignationEntity marker;
    /**
     * 運転側での前tickの装填カウンタ値。これが増えたtickが発砲したtickであり、発砲の反動が速度を所有する側へ
     * 届く手段になる——クライアントが運転中の車両にサーバーが反動を加えても、直後のクライアント報告が速度を
     * 上書きしてしまうため。
     */

    /** 運転手が操る方位。Minecraft 流の度数で、0 が +Z 方向。 */
    private float heading;
    /** 車首が車尾よりどれだけ上か（度）。履帯下の地面が決める。 */
    private float hullPitch;
    /** 右側が左側よりどれだけ下か（度）。同じ読み値から。 */
    private float hullBank;

    /** 車首方向の速度（ブロック/tick）。負は後進。 */
    private float speed;

    /**
     * 落下速度（ブロック/tick）。それ以外の意味は持たない。地上車両は何かの上に立っているか、何かへ落ちている
     * かのどちらかで、高さは衝突ではなく {@link #rest} が決める。
     */
    private double fallSpeed;

    /**
     * 船が今水に浮いているか。{@link #settleOnWater} が最後に判定した値。
     *
     * <p>船は水中でしか進めない。スクリューは水の外で噛む物が無いので、浜に乗り上げたり陸に置かれたりした船は
     * その場から自力で動けない。{@link #steer} と {@link #accelerate} が駆動系を動かすか決めるのに読む。船で
     * ない車両には無意味で、浮かないし参照もしない。設定の1tick後に読まれるが、体感できない差であり、駆動系の
     * 前に接地処理を並べ直す手間を省ける。
     */
    private boolean afloat;

    /** 車体座標系での砲塔の指向と、その前tick値。 */
    private float turretYaw;
    private float turretYawO;
    private float gunPitch;
    private float gunPitchO;

    /**
     * 乗員の視線を自分の目線からどれだけ下へ倒したか（度）。砲もそれに合わせて下げられる。
     * {@link #setSightTilt} 参照。
     */
    private float sightTilt;

    /**
     * 車両の総走行距離（ブロック）。転輪の回転はこれから求める。長距離走行で精度を失わないよう1回転内に
     * 丸め込んである。
     */
    /**
     * 左右の履帯が送った距離。別々に持つ。
     *
     * <p>1つで足りていたのは、車体が真っ直ぐ走る間だけ両側が同じだけ回るからだ。超信地旋回では車体は1
     * ブロックも進まないまま両履帯が逆向きに回っており、そこが装軌車両の見どころでもある。1つの距離では
     * その場面が「履帯の止まった戦車がその場で回る」になる。
     */
    private float trackLeft;
    private float trackLeftO;
    private float trackRight;
    private float trackRightO;
    /**
     * この tick に操舵が車体を回した角度（度）。右回りが正。
     *
     * <p>履帯の送りを求めるのに要る。旋回中の外側履帯は車体より速く、内側は遅く——超信地旋回では逆向きに
     * ——回る。{@link #steer} が求めた値をそのまま使う。方位の差分から取り直すと、地面が与える向きの変化
     * まで拾ってしまう。
     */
    private float turned;

    /** 操舵輪の切れ角（度）と、その前tick値。 */
    private float steerAngle;
    private float steerAngleO;

    /**
     * バネの上の車体。路面と、駆動系と運転手の操作によってどれだけ揺すられたか。
     *
     * <p>送信せず各側で回す。これを駆動する物——速度、方位、高さ、車体の寝方——は全側が既に持っているから。
     * {@link Ride} 参照。当たり判定や砲の照準からこれを除外する理由もそこに書いてある。
     */
    private final Ride.Springs springs = new Ride.Springs();


    private GroundVehicleInput input = GroundVehicleInput.NONE;

    // 自分でこの車両をシミュレートしていない側のための補間状態。
    private int lerpSteps;
    private double lerpX;
    private double lerpY;
    private double lerpZ;
    private double lerpYRot;

    /** その名前の定義値と、どのファイル群から来たか。 */
    @Nullable
    private GroundVehicleDefinition stats;
    private int statsVersion = -1;

    public GroundVehicleEntity(EntityType<? extends GroundVehicleEntity> type, Level level) {
        super(type, level);
        this.blocksBuilding = true;
        // 最初のtickではなくここで構築する。レベルはエンティティ追加時にそのパーツを記録するので、まだ
        // パーツを持たないエンティティは「無し」として覚えられてしまう。
        this.buildParts();
        // 新造車両は無傷であり、かつ空である。砲も発射筒も誰かが弾薬を持って右クリックしない限り満たされ
        // ないので、置かれたばかりの車両は撃つ弾を持たない。BuiltInGun と TurretLauncher 参照。
        // ワールドから読み戻した個体はタグでこれを上書きし、クライアントには他の同期データと共に実値が届く。
        this.setHealth(this.getMaxHealth());
        // 弾は空でもタンクは満タン。置いた場所から動かせない車両を配りたい者はいない。
        this.setFuel(this.fuelSetup().capacity());
        // 重力は driveTick() で自前で適用しており、それが下り坂へ車体を押し付ける力にもなっている。
        // サーバーへそう伝えることで、浮遊エンティティ扱いされずに済む。
        this.setNoGravity(true);
    }

    public GroundVehicleDefinition getStats() {
        GroundVehicleDefinition current = this.stats;

        if (current == null || this.statsVersion != Definitions.version()) {
            current = Definitions.VEHICLES.get(this.getVehicleId());
            this.stats = current;
            this.statsVersion = Definitions.version();
        }

        return current;
    }

    // ------------------------------------------------------------------
    // 状態
    // ------------------------------------------------------------------

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ATTITUDE, new Quaternionf());
        builder.define(DATA_SPEED, 0.0F);
        builder.define(DATA_TURRET_YAW, 0.0F);
        builder.define(DATA_GUN_PITCH, 0.0F);
        builder.define(DATA_ROUNDS, 0);
        builder.define(DATA_RELOAD, 0);
        builder.define(DATA_COAX_ROUNDS, 0);
        builder.define(DATA_COAX_RELOAD, 0);
        builder.define(DATA_MISSILES, 0);
        builder.define(DATA_MISSILE_RELOAD, 0);
        builder.define(DATA_MAGAZINE, new CompoundTag());
        builder.define(DATA_ARMAMENT, Armament.MAIN.ordinal());
        builder.define(DATA_LOCK_TARGET, -1);
        builder.define(DATA_LOCK_PROGRESS, 0.0F);
        builder.define(DATA_DESIGNATED, -1);
        builder.define(DATA_STEER, 0.0F);
    }

    /** 主兵装の残弾。 */
    public int getRounds() {
        return this.entityData.get(DATA_ROUNDS);
    }

    public void setRounds(int rounds) {
        this.entityData.set(DATA_ROUNDS, Math.max(rounds, 0));
    }

    /** 装填完了までのtick数。0なら発砲可能。 */
    public int getReload() {
        return this.entityData.get(DATA_RELOAD);
    }

    public void setReload(int ticks) {
        this.entityData.set(DATA_RELOAD, Math.max(ticks, 0));
    }

    /** 薬室に弾があり乗員が撃てる状態か。 */
    public boolean isLoaded() {
        return this.getReload() <= 0 && this.getRounds() > 0;
    }

    /** 装填に要する全体時間（tick）。{@link #getReload} はここから数え下がる。 */
    public int getReloadTicks() {
        return this.gun.reloadTicks();
    }

    /** 満載時の主砲弾数。 */
    public int getRoundCapacity() {
        return this.gun.capacity();
    }

    /**
     * トリガーが向けられる兵装。切り替えはこの順に回る。
     *
     * <p>並び順が切り替えの順序そのものになる。砲、機関銃、ミサイル——砲手が実際に手を伸ばす順で、
     * 積んでいない物は飛ばされる。
     *
     * <p><b>機関銃がここに居るのは新しい。</b> 以前は選択の対象ではなく、専用の引き金だけを持っていた
     * ——「主兵装が何をしていようとそこにある」のが同軸機銃だという理由で。その引き金は今も残っている。
     * ここへ加えたのは選択肢としての機関銃で、専用の引き金を置き換えた訳ではない。
     */
    public enum Armament {
        /** 主砲。 */
        MAIN,
        /** 機関銃。専用の引き金も別に持つ。 */
        COAX,
        /** 発射筒のミサイル。 */
        MISSILE;

        /** 添字で引くための不変の配列。{@code values()} は呼ぶたびに複製を作る。 */
        public static final Armament[] VALUES = values();

        /** この車両がその兵装を積んでいるか。 */
        boolean exists(GroundVehicleEntity vehicle) {
            return switch (this) {
                case MAIN -> vehicle.getStats().armament().exists();
                case COAX -> vehicle.hasCoaxial();
                case MISSILE -> vehicle.hasMissiles();
            };
        }

        /** 同期用の添字から。範囲外なら主砲。 */
        static Armament byIndex(int index) {
            return index >= 0 && index < VALUES.length ? VALUES[index] : MAIN;
        }

        /** セーブされた名前から。読めなければ主砲。 */
        static Armament byName(String name) {
            for (Armament armament : VALUES) {
                if (armament.name().equals(name)) {
                    return armament;
                }
            }

            return MAIN;
        }
    }

    /** この車両が機関銃を積んでいるか。 */
    public boolean hasCoaxial() {
        return this.coax.exists();
    }

    /** 同軸機銃のベルト残弾。 */
    public int getCoaxRounds() {
        return this.entityData.get(DATA_COAX_ROUNDS);
    }

    public void setCoaxRounds(int rounds) {
        this.entityData.set(DATA_COAX_ROUNDS, Math.max(rounds, 0));
    }

    /** 同軸機銃が次に撃てるまでのtick数。0なら準備完了。 */
    public int getCoaxReload() {
        return this.entityData.get(DATA_COAX_RELOAD);
    }

    public void setCoaxReload(int ticks) {
        this.entityData.set(DATA_COAX_RELOAD, Math.max(ticks, 0));
    }

    /** 機関銃にベルト残弾があり発砲可能か。 */
    public boolean isCoaxLoaded() {
        return this.getCoaxReload() <= 0 && this.getCoaxRounds() > 0;
    }

    /** ベルト満載時の弾数。 */
    public int getCoaxCapacity() {
        return this.coax.capacity();
    }

    /** 発射筒の残ミサイル数。 */
    public int getMissiles() {
        return this.entityData.get(DATA_MISSILES);
    }

    public void setMissiles(int missiles) {
        this.entityData.set(DATA_MISSILES, Math.max(missiles, 0));
    }

    /** 次のミサイルが出るまでのtick数。0なら準備完了。 */
    public int getMissileReload() {
        return this.entityData.get(DATA_MISSILE_RELOAD);
    }

    public void setMissileReload(int ticks) {
        this.entityData.set(DATA_MISSILE_RELOAD, Math.max(ticks, 0));
    }

    /** 満載時の発射筒数と、発射間隔にかかる時間。 */
    public int getMissileCapacity() {
        return this.launcher.capacity();
    }

    public int getMissileReloadTicks() {
        return this.launcher.reloadTicks();
    }

    /** この車両がミサイルを積んでいるか。 */
    public boolean hasMissiles() {
        return this.getStats().launcher().exists();
    }

    /**
     * トリガーがどちらの兵装を撃つか。
     *
     * <p>単に読むのではなく算出するので、片方しか積まない車両にどちらを選択中か教える必要が無い。発射筒が
     * 無ければフラグが何を言おうと答えは砲、砲が無ければミサイル。両方積む車両——対空車両がそれだ——だけが
     * 切り替える物を持ち、そこでだけフラグに意味がある。
     */
    public boolean isMissileMode() {
        return this.selected() == Armament.MISSILE;
    }

    /** トリガーが機関銃を撃つか。同軸機銃はこれとは別に専用の引き金も持ち続ける。 */
    public boolean isCoaxMode() {
        return this.selected() == Armament.COAX;
    }

    /**
     * 弾種別の残弾を持つタグ。書き換えるのは {@link com.ashvehicles.weapon.Magazine} だけで、それ以外は
     * 読むだけ。
     */
    public CompoundTag getMagazineTag() {
        return this.entityData.get(DATA_MAGAZINE);
    }

    public void setMagazineTag(CompoundTag tag) {
        this.entityData.set(DATA_MAGAZINE, tag);
    }

    /**
     * どれか1つの架台の残弾。3組ある同じ形のカウンタを、架台を引数に取る1組にまとめた物。
     *
     * <p>弾倉が弾種を持つ車両では、この値は「選択中の弾種の残弾」であって積んでいる全部ではない。
     * 弾種を知らずに残弾だけを読む側——計器も、装填完了の判定も、発砲の可否も——がそのまま正しくいられる
     * のはそのためだ。{@link com.ashvehicles.weapon.Magazine} 参照。
     */
    public int getRounds(Armament station) {
        return switch (station) {
            case MAIN -> this.getRounds();
            case COAX -> this.getCoaxRounds();
            case MISSILE -> this.getMissiles();
        };
    }

    public void setRounds(Armament station, int rounds) {
        switch (station) {
            case MAIN -> this.setRounds(rounds);
            case COAX -> this.setCoaxRounds(rounds);
            case MISSILE -> this.setMissiles(rounds);
        }
    }

    /**
     * 今トリガーが向いている架台に装填されている弾種。弾種を持たない架台では null で、そのときは兵装
     * ファイル自身が書いた弾が出る。
     *
     * <p>撃つ側と照準器と計器が同じ物を必要とするので、問い合わせ先は1つ。
     */
    @Nullable
    public ResourceLocation getSelectedAmmunition() {
        return Magazine.selected(this, this.selected());
    }

    /**
     * 今トリガーが向いている兵装。
     *
     * <p>保存された値をそのまま信じない。車両が積んでいない物が選ばれていることがあるからだ——別の車両で
     * 使った番号がそのまま入ってきた時、パックが兵装を書き換えた時、そして「1種しか積まないので選択に
     * 意味が無い」時。積んでいなければ、積んでいる最初の物へ落とす。だから片方しか持たない車両に
     * 「どちらを選択中か」を教える必要がない。
     */
    public Armament selected() {
        Armament stored = Armament.byIndex(this.entityData.get(DATA_ARMAMENT));

        if (stored.exists(this)) {
            return stored;
        }

        for (Armament armament : Armament.VALUES) {
            if (armament.exists(this)) {
                return armament;
            }
        }

        return Armament.MAIN;
    }

    /**
     * 積んでいる兵装を順に切り替える。積んでいない物は飛ばす。
     *
     * <p>1種しか積まない車両では1周して同じ物に戻るので、何も起きない。
     */
    public void cycleWeapon() {
        Armament now = this.selected();
        // 同じ架台にまだ次の弾種があるなら、切り替えはそこへ行く。実際の砲手が最も頻繁に行う切り替えは
        // 「主砲から機銃へ」ではなく「徹甲弾から榴弾へ」であり、どちらも同じ1つのキーで済むべきだ。
        // 弾種を並べていない車両では next が常に null になり、以下は以前とまったく同じ処理になる。
        ResourceLocation nextRound = Magazine.next(this, now);

        if (nextRound != null) {
            Magazine.select(this, now, nextRound);

            return;
        }

        for (int step = 1; step <= Armament.VALUES.length; step++) {
            Armament next = Armament.VALUES[(now.ordinal() + step) % Armament.VALUES.length];

            if (next.exists(this)) {
                this.entityData.set(DATA_ARMAMENT, next.ordinal());
                // 架台へ入り直したら先頭の弾種から。そうしないと、一度末尾まで送った架台は二度と先頭を
                // 通らず、切り替えが一巡しても戻ってこない弾種が残る。
                Magazine.rewind(this, next);

                return;
            }
        }
    }

    /** シーカーが捉えている対象。全側から見た値で、無ければ null。 */
    @Nullable
    public Entity getSeekerTarget() {
        int id = this.entityData.get(DATA_LOCK_TARGET);

        return id < 0 ? null : this.level().getEntity(id);
    }

    /** ロックの進行度。0〜1で、1がロック成立、それ未満は捕捉中。 */
    public float getSeekerProgress() {
        return this.entityData.get(DATA_LOCK_PROGRESS);
    }

    /** シーカーが目標を捕捉済みか（誘導弾に行き先があるか）。 */
    public boolean isSeekerLocked() {
        return this.getSeekerProgress() >= 1.0F && this.entityData.get(DATA_LOCK_TARGET) >= 0;
    }

    // ------------------------------------------------------------------
    // 座標へ据える発射機
    // ------------------------------------------------------------------

    /**
     * この車両の筒に入っているのが、シーカーではなく<em>座標</em>へ飛ぶ弾か。
     *
     * <p>兵装ファイルが答える（{@link WeaponDefinition.Guidance.Seeker#POINT}）。乗員の操作も計器も発射条件も
     * この1つの問いで分かれるので、その分岐は全部ここを読む。積んでいる弾で戦い方が変わるのであって、車両の
     * 名前で変わるのではない——同じ筒に別の弾を入れたパックの車両も、書き足す物なしにこちらの手順で動く。
     */
    public boolean laysPoint() {
        return this.seeker() == WeaponDefinition.Guidance.Seeker.POINT;
    }

    /**
     * この車両の発射機が、シーカーの捕捉ではなく<em>据えた点</em>へ弾を送るか。
     *
     * <p>{@link #laysPoint} より広い問い。座標を打ち込む物（POINT）と、照準線を追わせる物（BEAM）の両方が
     * ここに入る。どちらも {@link #designate} が置いたマークを弾に渡すので、目標の持ち方は同じだ——違うのは
     * 誰がその点を決めるか（乗員が盤で／照準そのものが毎tick）だけである。
     *
     * <p><b>{@code LASER} は入らない。</b>{@link WeaponDefinition.Guidance.Seeker#laid()} には入るが、あちらは
     * 機体の話だ——光点を当て続けるのは照準ポッドであり、それを積んでいる車両はこの MOD に1台も無い。だから
     * 車両に載ったレーザー誘導弾は従来どおりシーカーで捕捉する。ここで {@code laid()} をそのまま使うと、
     * 誰も当てていない光点を待って永遠に撃てない発射機ができる。
     */
    public boolean aimsAtPoint() {
        WeaponDefinition.Guidance.Seeker seeker = this.seeker();

        return seeker == WeaponDefinition.Guidance.Seeker.POINT
                || seeker == WeaponDefinition.Guidance.Seeker.BEAM;
    }

    /** 発射筒に入っている弾のシーカー種別。積んでいないか誘導を持たなければ null。 */
    @Nullable
    private WeaponDefinition.Guidance.Seeker seeker() {
        WeaponDefinition missile = this.getStats().launcher().missile()
                .map(Definitions::weapon).orElse(null);

        return missile == null ? null
                : missile.guidance().map(WeaponDefinition.Guidance::seeker).orElse(null);
    }

    /**
     * 発射機が据えている物。無ければ null。
     *
     * <p>機体の照準ポッドが保持する物とまったく同じで、実体も同じ {@link DesignationEntity} だ。この MOD で
     * 誘導される物は全てエンティティへ向かうので、座標を狙うという操作も「そこにマーカーを1つ置く」ことに
     * なる。だからミサイル側に足す物は何も無い——渡された物へ飛ぶ、いつもの弾のままである。
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

    /**
     * 発射機が座標を受け付ける水平距離（ブロック）。
     *
     * <p>ミサイルファイルの {@code guidance.lock_range} を読む。シーカーを持つ弾ではあれが「どこまで掴めるか」
     * だが、掴む物を持たないこの弾では「どこまで送れるか」になる——どちらにせよ、その弾が交戦できる距離を1つの
     * 数で言っている場所だ。盤も同じ数を見て、押す前に射程外だと言う（{@code LaunchPoint.withinReach}）。
     */
    public double launcherReach() {
        return this.getStats().launcher().missile()
                .map(id -> (double) Definitions.weapon(id).guidance()
                        .map(WeaponDefinition.Guidance::lockRange).orElse(0.0F))
                .orElse(0.0);
    }

    /** 据えた点。無ければ null。 */
    @Nullable
    public Vec3 getDesignatedPoint() {
        Entity held = this.getDesignated();

        return held == null ? null : held.position().add(0.0, held.getBbHeight() * 0.5, 0.0);
    }

    /**
     * 地上の一点へ発射機を据える。
     *
     * <p>座標へ飛ぶ弾を積んだ車両だけ。それ以外の発射機はシーカーで目標を取るので、据える点は持たないし、
     * 持たせれば「ロックしていないのに撃てる筒」になる。
     *
     * <p><b>据え直しは待ち時間を作る。</b>点を受け取るたび短い装填待ちを置くので、クリック2回が「据えて即
     * 撃つ」にはならない。実物が座標を入れてから撃てるようになるまでの間であり、ここでは誤射の防波堤でも
     * ある——弾道ミサイルは引き金の震え1回で出ていってよい物ではない。
     *
     * @param point 地上のどこか
     * @param estimated 点がブロック上で見えた物ではなく、クライアントの世界の外で算出された物か
     *                  （{@link com.ashvehicles.client.Terrain} 参照）
     */
    public boolean designate(@Nullable Vec3 point, boolean estimated) {
        if (point == null || this.isWrecked() || !this.aimsAtPoint()) {
            this.clearDesignation();

            return false;
        }

        // 射程の検査は打ち込まれた座標にだけ掛ける。照準線の先に置く点（BEAM）は弾が届く距離に置いた物で
        // あって乗員が選んだ場所ではないし、そもそも弾はそこへ行き着く前に何かへ当たる。
        if (this.laysPoint()
                && point.subtract(this.position()).horizontalDistance() > this.launcherReach()) {
            this.clearDesignation();

            return false;
        }

        // 高さがまだ分からない座標には、真下を向いた視線を持たせる。機体の指示が使う斜めの視線ではない
        // ——あちらはポッドが実際に地面を追った線なので、降ろすと同時に手前へ引き戻すのが正しい補正だが、
        // こちらは乗員が盤に打ち込んだ2つの数であり、その X と Z は動かしてはならない。真下なら、マークは
        // 自分の下に地面が現れたとき垂直にだけ降りる。DesignationEntity#settle 参照。
        Vec3 sight = estimated ? new Vec3(0.0, -1.0, 0.0) : null;

        if (this.marker == null || !this.marker.isAlive()) {
            this.marker = DesignationEntity.at(this.level(), point, sight);
            this.level().addFreshEntity(this.marker);
        } else {
            this.marker.hold(point, sight);
        }

        this.entityData.set(DATA_DESIGNATED, this.marker.getId());

        // 据え付けの間も、打ち込まれた座標にだけ。照準線は毎tick置き直される物なので、そのたびに待ちを
        // 入れれば発射機は永遠に撃てない。
        if (this.laysPoint()) {
            this.setMissileReload(Math.max(this.getMissileReload(), LAY_SETTLE_TICKS));
        }

        return true;
    }

    /** 据えていた点を捨てる。 */
    public void clearDesignation() {
        if (this.marker != null) {
            this.marker.discard();
            this.marker = null;
        }

        this.entityData.set(DATA_DESIGNATED, -1);
    }

    /**
     * 据えていた点から手を離す。ただし点そのものは消さない。
     *
     * <p>撃った直後の発射機がすること。弾はもう座標を持って飛んでいるので、発射機の側に据え続ける理由は無く
     * ——架台を畳んで走り去るのが正しい——それでも弾の行き先を消してよいわけではない。だから保持を降りるだけ
     * にして、マーク自体は飛んでいる弾に持たせる（{@code RocketEntity.holdMark}）。
     *
     * <p>{@link #clearDesignation} との違いはそこだけだ。あちらは乗員が「やめた」と言う経路なので、行き先ごと
     * 消える。
     */
    public void releaseDesignation() {
        this.marker = null;
        this.entityData.set(DATA_DESIGNATED, -1);
    }

    /**
     * 保持1tick分。マーカーへ「まだ保持者がいる」と伝え、保持できなくなった点は捨てる。
     *
     * <p>これが無いとマーカーは数秒で自ら諦める——tickの合間に車両が破壊された場合の後始末だ。
     * {@link DesignationEntity} 参照。
     */
    private void tickDesignation() {
        if (this.entityData.get(DATA_DESIGNATED) < 0) {
            return;
        }

        if (this.isWrecked() || !this.aimsAtPoint() || this.getDesignated() == null) {
            this.clearDesignation();

            return;
        }

        if (this.marker != null) {
            this.marker.held();
        }
    }

    /**
     * シーカー本体。探す対象は発射筒の中身が決めるので発射筒と同居する。意味を持つのはサーバーのみで、
     * 他の側は上の3つの値を読む。
     */
    @Override
    public TargetLock lock() {
        return this.launcher.lock();
    }

    /**
     * tick間の任意時点における砲身の後座量（0〜1）。
     *
     * <p>送信ではなく装填カウンタから算出する。カウンタが全てを語っているからだ——発砲tickに最大値を取り
     * そこから下がるので、「いつ撃ったか」という1つの値から両方が作れる。弾が出た瞬間に後座し、後座時間を
     * かけて復座する。それが後座の見え方であり、復座が遅い側の半分だ。
     */
    public float getRecoil(float partialTick) {
        GroundVehicleDefinition.Armament armament = this.getStats().armament();
        int ticks = Math.max(armament.recoilTicks(), 1);
        int loading = this.gun.reloadTicks();
        float since = loading - (this.getReload() - partialTick);

        if (since < 0.0F || since >= ticks) {
            return 0.0F;
        }

        return 1.0F - since / ticks;
    }

    public GroundVehicleInput getInput() {
        return this.input;
    }

    public void setInput(GroundVehicleInput input) {
        this.input = input;
    }

    /** 車首方向の速度（ブロック/tick）。どの側から問われても答える。 */
    public float getSpeed() {
        return this.isControlledByLocalInstance() ? this.speed : this.entityData.get(DATA_SPEED);
    }

    /**
     * ハンドルの切れ量（-1〜1）。どの側から問われても答える。
     *
     * <p>運転側は自分の入力を他より1tick早く知る。他の側へは送られるので、推測ではなく1tick遅れの同じ値だ。
     * 速度と同じ仕組みで、同じ理由。
     */
    public float getSteerInput() {
        return this.isControlledByLocalInstance() ? this.input.steer() : this.entityData.get(DATA_STEER);
    }

    /**
     * tick間の任意時点における操舵輪の切れ角（度）。右が正。ファイルで操舵輪を指定していない車両——つまり
     * 全ての装軌車両——では常に0。
     */
    public float getSteerAngle(float partialTick) {
        return Mth.lerp(partialTick, this.steerAngleO, this.steerAngle);
    }

    /**
     * tick間の任意時点における、バネ上の車体の変位。
     *
     * <p>読むのは車両を描く側と乗員の視点だけ。当たり判定・照準・接地位置がいずれも剛体車体から求められ
     * これを見ない理由は {@link Ride} 参照。
     */
    public Ride getRide(float partialTick) {
        Ride ride = this.springs.at(partialTick);
        float planted = this.planted();

        // 架台を立てている間、車体はバネの上で揺れない。実物が発射前に張り出すジャッキであり、それを踏まえて
        // 見れば、ここでの「揺れる」は絵として間違っている——バネの動きは車体の中心・地面の高さを支点に模型
        // 全体を傾けるので（{@code GroundVehicleRenderer.applyBodyMotion}）、7ブロックの筒を垂直に立てると
        // その先端は数度の傾きで大きく横へ振れる。車体が揺れているのではなく、筒が横滑りしているように見える。
        //
        // 起き上がる分だけ滑らかに止める。据えた瞬間に固まるのではなく、架台が立つのに合わせて車体が落ち着く
        // ——ジャッキが降りていく絵として読める。
        return this.rock(planted <= 0.0F ? ride : Ride.between(ride, Ride.LEVEL, planted), partialTick);
    }

    /**
     * 発砲の反動で車体をバネの上に沈める。
     *
     * <p><b>車両は動かない。</b> 60トンの装軌車体は砲を撃っても目に見えて動かないし、撃つたび後ろへ滑る
     * 車体は重量感ではなく不具合に見える。だが車体はトーションバーの上で確かに<em>座る</em>——それがこれだ。
     *
     * <p>地面が与える揺れと同じ変位に足す。だから走行装置は {@code GroundVehicleModel.plant} と
     * {@link com.ashvehicles.client.model.TrackBelt} が地面へ戻す分だけ元の位置に留まり、その上で車体だけが
     * 傾く。段差を越えるときの動きと同じ経路を通っており、実物の反動もまさに同じバネが受ける。
     *
     * <p>砲の向いている方へ倒れる。前を向いた砲は車首を上げ、真横を向いた砲は車体を反対側へ傾ける。
     * 斜め向きの砲はその両方を、成分の分だけ。
     *
     * <p>状態を持たない。装填カウンタが発砲した瞬間を既に知っており（{@link #getRecoil} 参照）、砲身の
     * 後座を描いているのと同じ値だ。だから揺れは後座と正確に同じ時間だけ続き、同時に収まる。
     */
    private Ride rock(Ride ride, float partialTick) {
        float recoil = this.getRecoil(partialTick);
        float rock = this.getStats().armament().rockDegrees();

        if (recoil <= 0.0F || rock <= 0.0F) {
            return ride;
        }

        Vec3 aim = this.getAimDirection(partialTick);
        Vec3 nose = this.headingVector();
        // 車体の右手。方位を4分の1回した向きで、砲が真横を向いているかを測るのに要る。
        Vec3 right = new Vec3(-nose.z, 0.0, nose.x);
        float swing = rock * recoil;

        // 車首を上げるのは正のピッチ。右へ撃った車体は左へ傾き、それは負のリーンだ。どちらの符号も
        // GroundVehicleRenderer.applyBodyMotion が読む向きに合わせてある。
        return new Ride(ride.heave(),
                ride.pitch() + swing * (float) aim.dot(nose),
                ride.lean() - swing * (float) aim.dot(right));
    }

    /**
     * 架台がどれだけ立っているか。0で寝ており、1で立ち切っている。起立する発射機を持たない車両では常に0。
     *
     * <p>{@link #getRide} が「車体を揺らさない度合い」として読む。仰角そのものから出すので、状態を持たずに
     * 済むし、据えた・解除したの両方向で自動的に正しい向きへ動く。
     */
    private float planted() {
        // 安い問いから順に。{@link #laysPoint} は兵装ファイルを引くので、そこまで来るのは実際に発射筒を
        // 積んでいて、しかも架台が上がっている車両だけにする。ここは毎フレーム、車両ごとに数回通る。
        float pitch = this.getGunPitch(1.0F);

        if (pitch <= 0.0F || !this.getStats().launcher().exists() || !this.laysPoint()) {
            return 0.0F;
        }

        float upright = this.getStats().turret().elevation();

        return upright <= 0.0F ? 0.0F : Mth.clamp(pitch / upright, 0.0F, 1.0F);
    }

    /** エンジンの負荷（0〜1）。エンジン音のピッチはここから。 */
    public float getEngineNote() {
        // 乾いたタンクは音を止める。燃料の消費もこの値から出るので、空の車両が消し続けることも無くなる。
        if (this.isOutOfFuel()) {
            return 0.0F;
        }

        float max = Math.max(this.getStats().powertrain().maxSpeed(), STANDSTILL);

        return Mth.clamp(Math.abs(this.getSpeed()) / max, 0.0F, 1.0F);
    }

    @Override
    public VehicleChassis.Fuel fuelSetup() {
        return this.getStats().powertrain().fuel();
    }

    /** 補間せず車両を向ける。設置時や読み戻し時用。 */
    public void snapAttitude(float heading, float pitch, float bank) {
        this.heading = heading;
        this.hullPitch = pitch;
        this.hullBank = bank;
        this.attitude = this.buildAttitude();
        this.attitudeO = new Quaternionf(this.attitude);
        this.entityData.set(DATA_ATTITUDE, new Quaternionf(this.attitude));
        this.setYRot(heading);
        this.setXRot(-pitch);
        this.yRotO = heading;
        this.xRotO = -pitch;
    }

    /** 運転クライアントの報告を元にサーバーで適用する。 */
    public void reportState(Quaternionf hull, float travelSpeed, float turret, float gun) {
        if (this.level().isClientSide) {
            return;
        }

        this.attitude = new Quaternionf(hull).normalize();
        this.entityData.set(DATA_ATTITUDE, new Quaternionf(this.attitude));
        this.heading = Attitude.heading(this.attitude);
        this.hullPitch = -Attitude.elevation(this.attitude);
        this.hullBank = Attitude.bank(this.attitude);
        this.speed = travelSpeed;
        this.entityData.set(DATA_SPEED, travelSpeed);
        this.setTurret(turret, gun);
    }

    /** 同上に砲塔と砲を加えた版。戦車で見える物の大半はこれ。 */
    @Override
    public void poseForDrawing(Quaternionf hull, float turret, float gun) {
        super.poseForDrawing(hull, turret, gun);

        this.turretYaw = Mth.wrapDegrees(turret);
        this.turretYawO = this.turretYaw;
        this.gunPitch = gun;
        this.gunPitchO = gun;
    }

    public float getTurretYaw(float partialTick) {
        return Mth.rotLerp(partialTick, this.turretYawO, this.turretYaw);
    }

    public float getGunPitch(float partialTick) {
        return Mth.lerp(partialTick, this.gunPitchO, this.gunPitch);
    }

    /**
     * ワールド座標での砲身の指向。
     *
     * <p>組み付け順に3つの回転を重ねる。車体は地面が決める姿勢で寝て、砲塔は車体上で旋回し、砲は砲塔内で
     * 俯仰する。互いに右から掛けるので、各回転は1つ前が残した座標系で作用する——だから車首から左20度に据えた
     * 砲は、車体が斜面を横切っても左20度のままで、ワールド内で振り回されない。
     */
    @Override
    public Vec3 getAimDirection(float partialTick) {
        return Attitude.nose(this.aim(partialTick));
    }

    /**
     * 同じ物を、方向ではなく回転として。
     *
     * <p>{@link #getAimDirection} が答えるのと同じ向きだが、こちらは水平線の傾きも持っている。砲腔線に沿って
     * 覗く視界——砲手照準——に要るのは3角なので、方向だけでは足りない。{@code TurretSight} 参照。
     */
    public Quaternionf getAimAttitude(float partialTick) {
        return this.aim(partialTick);
    }

    private Quaternionf aim(float partialTick) {
        return new Quaternionf(this.getAttitude(partialTick))
                .rotateY(-this.getTurretYaw(partialTick) * DEG_TO_RAD)
                .rotateX(-this.getGunPitch(partialTick) * DEG_TO_RAD);
    }

    /**
     * 乗員の仰角を、砲が実際に取れる範囲へ収めた値。
     *
     * <p>砲手照準を覗いている間、乗員には自分の頭がどこを向いているか映らない。映っているのは砲だ。だから
     * 可動端の外まで見下ろせるようにしておくと、俯角の尽きた砲の前で入力だけが溜まり、戻す時に同じ角度分の
     * 空振りが要る——操作が遅れているようにしか感じられない不感帯だ。頭を砲の範囲に縛れば、マウスを動かした
     * 分は必ず砲が動いた分になる。
     *
     * <p>{@link #tickTurret} が砲を据えるのと同じ式を逆に解いた物。両者が違う角を「範囲内」と呼べば、砲手の
     * 画面と実際に撃つ方向が食い違う。照準を覗いている間は視界が倒されていない（{@code sightTilt} は0）ので、
     * ここでもそれは勘定に入れない。
     */
    public float clampSightPitch(float xRot) {
        GroundVehicleDefinition.Turret turret = this.getStats().turret();

        if (!turret.exists()) {
            return xRot;
        }

        return Mth.clamp(xRot, -turret.elevation() - this.hullPitch, turret.depression() - this.hullPitch);
    }

    /**
     * ワールド座標での銃口位置。点が1つで足りる用途のために第1砲身を返す——とりわけ砲手照準は1本の砲腔に
     * 沿って据えるもので、2本には据えられない。
     */
    public Vec3 getMuzzle(float partialTick) {
        return this.getMuzzle(0, partialTick);
    }

    /**
     * ワールド座標での、この砲架の銃口の1つ。
     *
     * <p>固定点ではなく耳軸＋長さから組む。砲身は振れるからだ。耳軸は砲塔上の一点で砲塔と共に回り、銃口は
     * そこから現在の砲の指向へ砲身長だけ進んだ位置になる。固定点1つでは、ある俯仰角で正しく他の全てで狂う。
     *
     * <p>1つの砲架の全砲身は同じ方向に据えられる——それが「1つの砲架」の意味だ——ので、違うのは起点と、その
     * 線上でどこまで伸びるかだけ。{@link GroundVehicleDefinition.Barrel} 参照。以下2つの構成に対応する。
     *
     * <p>1つの砲架に2本の砲身がある場合、それらは俯仰の耳軸を挟んで両側に位置し、耳軸と共に上下する——だから
     * ここで同軸機銃と全く同様に耳軸周りに揺らす。砲腔の上にある砲身は、砲架が上がってもずり落ちず、どの
     * 俯仰角でも上にあり続ける。
     *
     * <p>同じ射撃指揮下の第2砲架——軍艦の後部砲塔——は自前の耳軸で俯仰し、記述もその耳軸で行う。揺らす相手が
     * 無く、前部砲塔の耳軸周りに揺らせば船の全長分振り回してしまう。よって自前の旋回輪を指定した砲身はその場に
     * 置かれ、その旋回輪の周りを回るだけになる。
     */
    public Vec3 getMuzzle(int barrel, float partialTick) {
        GroundVehicleDefinition.Armament armament = this.getStats().armament();
        GroundVehicleDefinition.Barrel one = armament.barrel(barrel);
        Vec3 seat = one.ring().isPresent() ? one.trunnion() : this.onGun(one.trunnion(), partialTick);
        Vec3 ring = one.ringOr(this.getStats().turret().ring());
        Vec3 trunnion = this.position().add(Attitude.toWorld(this.getAttitude(partialTick),
                this.onRing(seat, ring, partialTick)));

        return trunnion.add(this.getAimDirection(partialTick).scale(one.lengthOr(armament.barrelLength())));
    }

    /** 主兵装が持つ砲身数。1本ずつ順に撃つ。 */
    public int getBarrelCount() {
        return this.getStats().armament().barrelCount();
    }

    /**
     * 砲塔上の一点を車両自身の座標系で表し、砲塔の旋回量だけ旋回輪周りに回した位置。
     *
     * <p>車両座標系（x が右、z が車首方向）で測るので、砲塔を右へ回すと旋回輪の前方にあった点は右側へ運ばれる。
     */
    private Vec3 onTurret(Vec3 offset, float partialTick) {
        return this.onRing(offset, this.getStats().turret().ring(), partialTick);
    }

    /**
     * 同じ処理を、車両自身ではなく呼び出し側が指定した旋回輪の周りで行う版。
     *
     * <p>1つを除く全ての砲架は車両自身の旋回輪を使う。別物が要るのは同じ射撃指揮で据えられる第2砲架だ——
     * 軍艦の後部砲塔は前部と同じ方位へ回るが、回るのは自分のバーベット周り。前部砲塔の旋回輪周りに回すと、
     * 船の反対端に取り付けた砲身を描いてしまう。
     */
    private Vec3 onRing(Vec3 offset, Vec3 ring, float partialTick) {
        Vec3 local = offset.subtract(ring);
        float radians = this.getTurretYaw(partialTick) * DEG_TO_RAD;
        double sin = Mth.sin(radians);
        double cos = Mth.cos(radians);

        return ring.add(new Vec3(
                local.x * cos + local.z * sin,
                local.y,
                -local.x * sin + local.z * cos));
    }

    /**
     * 砲上の一点を車両座標系で表し、砲の俯仰量だけ耳軸周りに揺らした位置——{@link #onTurret} と同じ考え方だが、
     * 旋回輪・水平面ではなく耳軸・垂直面で行う。
     *
     * <p>戻り値は砲塔の旋回前の座標系。砲は砲塔と共に回るので、砲塔が運ぶ他の点と同様、この後 {@link #onTurret}
     * で旋回輪周りに回す必要がある。
     */
    private Vec3 onGun(Vec3 offset, float partialTick) {
        Vec3 trunnion = this.getStats().armament().trunnion();
        Vec3 local = offset.subtract(trunnion);
        float radians = -this.getGunPitch(partialTick) * DEG_TO_RAD;
        double sin = Mth.sin(radians);
        double cos = Mth.cos(radians);

        return trunnion.add(new Vec3(
                local.x,
                local.y * cos - local.z * sin,
                local.y * sin + local.z * cos));
    }

    /**
     * 砲塔上の一点をワールド座標へ。旋回量だけ旋回輪周りに回し、次に車体姿勢を通す。車体に固定ではなく砲塔と
     * 共に回る物のため——とりわけ車長視点は砲塔上面のハッチから覗き、砲塔の行く所へ行く。砲塔の無い車両は
     * 回す物が無いので、点は単に車体上に乗る。
     */
    public Vec3 turretToWorld(Vec3 offset, float partialTick) {
        return this.toWorld(this.getStats().turret().exists() ? this.onTurret(offset, partialTick) : offset,
                partialTick);
    }

    /**
     * 砲上の一点をワールド座標へ。俯仰量だけ耳軸周りに揺らし、旋回量だけ旋回輪周りに回し、車体姿勢を通す。
     * 砲塔固定物に対する {@link #turretToWorld} と同じ役割を、砲身固定物に対して果たす——同軸機銃の銃口が
     * まさにそれで、「同軸」である理由そのものだ。
     */
    public Vec3 gunToWorld(Vec3 offset, float partialTick) {
        return this.turretToWorld(this.onGun(offset, partialTick), partialTick);
    }

    /**
     * tick間の任意時点における転輪の回転角（度）。
     *
     * <p>送信ではなく走行距離から算出する。全側が既に車速を知っており、車輪の位置を伝えるためだけに1tickに
     * 1パケット使うのは無駄だからだ。補間は2つの角度のブレンドではなく距離を進めて行う。全速の戦車の転輪は
     * 1tickでほぼ1回転するうえ、角度は折り返されるためだ。
     */
    public float getWheelAngle(float partialTick, boolean right) {
        float radius = Math.max(this.getStats().suspension().wheelRadius(), 0.05F);
        float distance = right
                ? Mth.lerp(partialTick, this.trackRightO, this.trackRight)
                : Mth.lerp(partialTick, this.trackLeftO, this.trackLeft);

        return distance / (float) (2.0 * Math.PI * radius) * 360.0F;
    }

    /**
     * シーカーの捕捉結果を同期データへ複製し、全側が描けるようにする。
     *
     * <p>毎tick無条件に行う。エンティティデータは実際に変化した物しか送らないので、同じ目標を捉え続ける
     * シーカーはコスト0であり、目標を失った側は次の走査を待たずそのtickで通知できる。
     */
    private void reportSeeker() {
        Entity target = this.launcher.lock().target();

        this.entityData.set(DATA_LOCK_TARGET, target == null ? -1 : target.getId());
        this.entityData.set(DATA_LOCK_PROGRESS, this.launcher.progress());
    }

    private void setTurret(float yaw, float pitch) {
        this.turretYaw = Mth.wrapDegrees(yaw);
        this.gunPitch = pitch;
        this.entityData.set(DATA_TURRET_YAW, this.turretYaw);
        this.entityData.set(DATA_GUN_PITCH, this.gunPitch);
    }

    // ------------------------------------------------------------------
    // 基底クラスが地上車両に要求する物
    // ------------------------------------------------------------------

    @Override
    public VehicleChassis.Hitbox hitbox() {
        return this.getStats().hitbox();
    }

    @Override
    public VehicleChassis.Sound soundSetup() {
        return this.getStats().sound();
    }

    /**
     * 車両の索敵手段。ほぼ全ての車両では無し。
     *
     * <p>戦車にレーダーは要らない。相手は地上の木立の中にいて、見つける手段は目視だ。このフィールドが存在する
     * のは、それ無しでは戦えない車両——数km上空を目視で追えない速度で飛ぶ目標を相手にする発射機——のため。
     */
    @Override
    public VehicleChassis.Radar radar() {
        return this.getStats().radar();
    }

    /**
     * 実際の速度ベクトル。車首方向のみ。装軌車両は向いている方へ進み、横滑りは1〜2tickで消える程度しかない。
     */
    @Override
    public Vec3 getVelocity() {
        return this.headingVector().scale(this.getSpeed());
    }

    @Override
    protected float health() {
        return this.getStats().hull().health();
    }

    /**
     * 戦車は装甲であり、その全ての箱もそうだ。車体、砲塔、スカート、走行装置いずれも厚みのある板であり、
     * どこに飛来した弾も弾かれうる——だから戦車は、撃ってくる相手へ正対させるのではなく、被弾方向へ向けて
     * 戦うのだ。
     */
    @Override
    public boolean isArmoured() {
        return true;
    }

    @Override
    public float armour() {
        return this.getStats().hull().armour();
    }

    @Override
    protected int declaredSalvage() {
        return this.getStats().hull().salvage();
    }

    @Override
    protected float explosionPower() {
        return this.getStats().hull().explosionPower();
    }

    @Override
    protected List<VehicleChassis.Seat> seats() {
        return this.getStats().hull().seats();
    }

    @Override
    protected VehicleChassis.CameraMount cameraMount() {
        return this.getStats().camera();
    }

    /**
     * 座席が別途指定しない限り、戦車の視点は砲塔上にある。砲塔上面から覗き砲と共に回るので、片側へ砲を据えた
     * 乗員はその側の地面を見る。船は逆で——艦橋は船体に固定され、砲はその下から旋回していく——砲塔が回っても
     * 視界は動かない。
     */
    @Override
    protected VehicleShape.Mount defaultEyeMount() {
        return this.getStats().isShip() ? VehicleShape.Mount.HULL : VehicleShape.Mount.TURRET;
    }

    /**
     * 乗員が世界を見る視点。そして、バネ上の車体変位が描画の外へ出ることを許される唯一の場所。
     *
     * <p>頭は走行装置ではなく車体に固定されているので、車体の行く所へ行く。制動で車首が沈めば下がり、コーナー
     * で車体が傾けば横へ振れ、段差ごとに上下する。ただし回転ではなく平行移動として——つまり視界は揺れるが乗員の
     * <em>照準</em>は揺れない。意図的だ。砲は乗員の視線で据えられるので、地形で照準が振られたら敵ではなく
     * サスペンションと戦うことになる。見える物は動き、狙っている物は動かない。
     */
    @Override
    protected Vec3 eyeToWorld(int seat, VehicleShape.Mount mount, Vec3 eye, float partialTick) {
        Vec3 seated = mount == VehicleShape.Mount.TURRET && this.getStats().turret().exists()
                ? this.onTurret(eye, partialTick)
                : eye;

        return this.toWorld(this.getRide(partialTick).carry(seated), partialTick);
    }

    /**
     * 箱の中心位置。ファイルが置いた場所を、砲塔上の箱なら旋回輪周りに回し、車体姿勢を通してワールドへ出す。
     */
    @Override
    protected Vec3 boxCentre(VehicleShape.Box box) {
        return this.position().add(Attitude.toWorld(this.attitude, this.mountOffset(box)));
    }

    /**
     * 箱が寝ている姿勢。車体姿勢、砲塔上なら旋回、さらに砲上にも乗るなら俯仰、最後に担い手の中での箱自身の
     * 角度、の順に重ねる。
     */
    @Override
    protected Quaternionf boxRotation(VehicleShape.Box box) {
        Quaternionf rotation = new Quaternionf(this.attitude);

        if (box.mount() == VehicleShape.Mount.TURRET || box.mount() == VehicleShape.Mount.GUN) {
            rotation.rotateY(-this.turretYaw * DEG_TO_RAD);
        }

        if (box.mount() == VehicleShape.Mount.GUN) {
            rotation.rotateX(-this.gunPitch * DEG_TO_RAD);
        }

        return rotation.mul(box.orientation());
    }

    /** 設置面積は箱から算出するので、鮮度は箱と同じ。 */
    @Override
    protected void onShapeChanged() {
        this.footprint = null;
    }

    /**
     * 現時点での車両座標系における箱の位置。車体上の箱はファイル通りの位置、砲塔上の箱は旋回量だけ旋回輪
     * 周りに回した位置、砲上の箱はまず俯仰量だけ耳軸周りに揺らし、その後は他の砲塔箱と同様に砲塔と共に回す。
     */
    private Vec3 mountOffset(VehicleShape.Box box) {
        return switch (box.mount()) {
            case GUN -> this.onTurret(this.onGun(box.offset(), 1.0F), 1.0F);
            case TURRET -> this.onTurret(box.offset(), 1.0F);
            case HULL -> box.offset();
        };
    }

    /**
     * 全ての箱を、車体と（乗っていれば）砲塔が運んだ位置へ、両者が残した姿勢のまま配置する。車両が何に
     * 止められ、何の上に立ち、何に当たるかは全てこの箱で決まる——{@code Hitbox} 参照。これは MOD 自前の形状で
     * あって Minecraft の物ではない。
     */
    private void tickParts() {
        List<VehicleShape.Box> shape = this.getShape().boxes();

        for (int i = 0; i < this.parts.length; i++) {
            VehiclePart part = this.parts[i];

            if (i >= shape.size()) {
                // リロードで定義が短くなり、ファイルがもう記述しなくなった箱。元の位置に空中で残さず、
                // 車体内部へ畳み込む。
                part.fold(this.position());

                continue;
            }

            VehicleShape.Box box = shape.get(i);


            part.place(this.hitbox(box));
        }

        this.notePlacement();
        this.carryStanders();
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
        this.turretYawO = this.turretYaw;
        this.gunPitchO = this.gunPitch;
        this.trackLeftO = this.trackLeft;
        this.trackRightO = this.trackRight;

        if (this.isControlledByLocalInstance()) {
            // 座席が空: ブレーキが掛かり惰性で停止する。運転手が降りた時点の動きを引き継ぎ、足元で急停止
            // したりはしない。残骸も同じ扱いが恒久化した物で——座る席がもう無い——被弾地点ではなく自分の
            // 履帯の上で止まる。
            if (this.isWrecked() || !(this.getControllingPassenger() instanceof Player)) {
                this.input = GroundVehicleInput.PARKED;
            }

            this.holdPosition();
            this.driveTick();

            if (!this.level().isClientSide) {
                this.entityData.set(DATA_SPEED, this.speed);
            }
        } else {
            this.tickLerp();
            this.attitude = new Quaternionf(this.entityData.get(DATA_ATTITUDE));
            this.heading = Attitude.heading(this.attitude);
            this.hullPitch = -Attitude.elevation(this.attitude);
            this.hullBank = Attitude.bank(this.attitude);
            this.speed = this.entityData.get(DATA_SPEED);
            this.turretYaw = this.entityData.get(DATA_TURRET_YAW);
            this.gunPitch = this.entityData.get(DATA_GUN_PITCH);
            // この側へ通知された速度から進める。運転側が自分の車輪を回しているのと同じ値だ。
            this.windTrack(this.speed);
        }

        // 砲はサーバー専任。航空機の弾と同じ理由で、発砲できるクライアントは弾を無から作り命中を主張できて
        // しまう。クライアントが送るのはトリガー、返ってくるのは装填カウンタ。
        if (!this.level().isClientSide) {
            // 無人車両はトリガーを引き続けない。入力は最後の運転手の報告値のまま届かなくなるので、押しっ放し
            // で降りた者がいると、そのままでは戦車が置かれている限り自動で撃ち続けてしまう。
            if (this.getControllingPassenger() == null || this.isWrecked()) {
                this.input = GroundVehicleInput.PARKED;
            }

            // 焼け落ちた車体には装填する砲も、捜索するシーカーも、それらを向ける対象も無い。
            if (!this.isWrecked()) {
                // トリガーは乗員が選択中の兵装だけに届く。もう一方も tick は回り続ける。砲は選択の有無に
                // かかわらず装填を進めるし、シーカーは発射筒が選択されていなくても捜索を続ける——それが
                // 航空機へ「追尾されている」と警告する仕組みだ。TurretLauncher 参照。
                Armament selected = this.selected();

                this.gun.tick(this.input.fire() && selected == Armament.MAIN);
                this.launcher.tick(this.input.fire() && selected == Armament.MISSILE, this.input.lock());
                // 同軸機銃は2択のどちらでもない。独自のトリガーを持ち同じ砲架で据えられるので、既に目標へ
                // 照準している砲手は主兵装を仕舞わずに掃射できる——同軸機銃の存在理由そのものだ。
                // 選択されていれば主トリガーでも撃つ。専用の引き金は残したままなので、機関銃を選んで
                // いない砲手も今まで通り掃射できる——同軸機銃の存在理由はそのままにして、切り替えでも
                // 選べるようにした形。
                this.coax.tick(this.input.coax()
                        || (this.input.fire() && selected == Armament.COAX));
                this.reportSeeker();
                this.tickDesignation();
                this.getSensors().tick();
            }
        }

        // 全側で、車体ではなく運転手の入力から。DATA_STEER 参照。サーバーは誰かが読む前に受け取った値を
        // 公開するので、全側が同一の値で動く。
        if (!this.level().isClientSide) {
            this.entityData.set(DATA_STEER, this.input.steer());
        }

        this.windSteering();
        // 車体を動かしうる全処理の後、全側で実行する。バネの挙動は車体が今どう変化したかから求めるので、
        // 前tickの車体を読む側はまだ来ていない段差への反応を描いてしまう。
        this.springs.tick(this.getStats(), this.speed, this.heading, this.getY(), this.onGround());

        // 車体を動かしうる全処理の後に置くことで、壊すのは1tick前ではなく今めり込んでいる物になる。
        this.crushBlocks();
        this.tickParts();
        this.checkInsideBlocks();
    }

    /**
     * 走行1tick分。操舵→駆動→下にある物への接地、の順。
     *
     * <p>順序が重要。操舵が方位を決め、駆動系がその方向の速度を決め、その後で初めて地面から車体姿勢を読む——
     * 車体の四隅の位置は、今どちらを向いていてどこまで進んだかで決まるからだ。
     */
    private void driveTick() {
        GroundVehicleDefinition definition = this.getStats();

        this.steer(definition.powertrain());
        this.accelerate(definition.powertrain(), definition.suspension());
        this.travel();

        // 船が戦車と異なるのは車体の支えられ方だけで、違いはそれが全てだ。戦車は履帯下の地面に沿って寝、
        // 船は下の海が決める喫水線で浮く。それより上——操舵、駆動系、後座、砲塔——は全て同じ仕組みが同じように
        // 動く。
        if (definition.isShip()) {
            this.settleOnWater(definition.buoyancy());
        } else {
            this.settleOnGround(definition.suspension());
        }

        this.attitude = this.buildAttitude();
        this.tickTurret(definition.turret());
        this.windTrack(this.speed);

        if (!this.level().isClientSide) {
            this.entityData.set(DATA_ATTITUDE, new Quaternionf(this.attitude));
        }
    }

    /**
     * 車体を旋回させる。
     *
     * <p>装軌車両は片側の履帯を強く駆動して曲がるので、停止時に最も速く曲がり高速時に最も鈍い——操舵輪とは
     * 逆であり、旋回率が1つの値ではなく2値の補間である理由だ。信地旋回できない車両は停止側の値が0なので、
     * 転がり出すまで曲がれない。装輪車両として正しい挙動だ。
     *
     * <p><b>どちらへ回るかは進行方向にもよる</b>。運転手の操舵方向だけでは決まらない。舵を右に当てて後進すると
     * 車尾が右へ振れ、したがって車首は<em>左</em>を向く——前進時と逆で、車庫入れの経験がある者なら想像通りだ。
     * これは履帯でもタイヤでも同じ。右を要求することは右側を左側より短く進ませることであり、それがどちら端を
     * 振り回すかは履帯の進行方向が変わった瞬間に入れ替わる。
     *
     * <p>停止中はどちらでもなく、運転手の入力に素直に従う。その場で信地旋回する車両には符号を取る進行方向が
     * 無いし、右へ倒したレバーは右へ回すべきだ。速度を参照するのは進行方向が存在してからでよい。
     */
    private void steer(GroundVehicleDefinition.Powertrain powertrain) {
        this.turned = 0.0F;

        // 水から出た船は走れないのと同様に曲がれない。船体の下に舵もスクリューも噛む物が無い。
        if (this.getStats().isShip() && !this.afloat) {
            return;
        }

        if (this.input.steer() == 0.0F) {
            return;
        }

        float top = Math.max(powertrain.maxSpeed(), STANDSTILL);
        float fraction = Mth.clamp(Math.abs(this.speed) / top, 0.0F, 1.0F);
        float rate = Mth.lerp(fraction, powertrain.pivotRate(), powertrain.steerRate());
        // 大きさは速度から、符号は進行方向から取る。大きさだけを取ると——上の abs が残すのがそれだ——後進中の
        // 車両が前進時と同じ向きに曲がってしまい、後退でコーナーを回ると舵が逆になる。
        float astern = this.speed < -STANDSTILL ? -1.0F : 1.0F;
        float turn = this.input.steer() * rate * astern;

        this.turned = turn;
        this.heading = Mth.wrapDegrees(this.heading + turn);
        this.setYRot(this.heading);
        // 砲塔が照準するのは車体ではなくワールドなので、車体が今行った回転を——どちら向きであれ——巻き戻す。
        // これがスタビライザーの全てだ。無ければ車体が曲がるたび砲が目標から外れ、砲手は戦闘中ずっと据え直す
        // ことになる。
        this.turretYaw = Mth.wrapDegrees(this.turretYaw - turn);
    }

    /**
     * 駆動系・制動・斜面に沿った重力を1つの速度にまとめる。
     *
     * <p>{@code drive} はスロットルレバーではなくペダル。要求速度を示し、離せば要求0となって惰行停止する。
     * 加速はエンジンの、減速はブレーキの仕事なので、どちらの値を消費するかは現在より速く走れと言われているか
     * 遅く走れと言われているかで決まる。
     */
    private void accelerate(GroundVehicleDefinition.Powertrain powertrain,
            GroundVehicleDefinition.Suspension suspension) {
        // 水から出た船は自力では動けない。スクリューが噛む物が無いので駆動系は何も与えず、残っていた惰性は
        // 乗り上げた地面で船体が擦れて止まる形で失われる。
        if (this.getStats().isShip() && !this.afloat) {
            this.speed = approach(this.speed, 0.0F, powertrain.braking());

            if (Math.abs(this.speed) < STANDSTILL) {
                this.speed = 0.0F;
            }

            return;
        }

        float target;
        float step;

        if (this.isOutOfFuel()) {
            // タンクが空。駆動系に渡す物が無いので、車両は転がり抵抗だけで惰行して止まる。操舵は残る
            // ——舵を効かせているのは車輪であってエンジンではない——ので、坂を下りながら向きは選べる。
            target = 0.0F;
            step = powertrain.rollingResistance();
        } else if (this.input.brake()) {
            target = 0.0F;
            step = powertrain.braking();
        } else if (this.input.drive() > 0.0F) {
            target = this.input.drive() * powertrain.maxSpeed();
            step = this.speed < target ? powertrain.acceleration() : powertrain.braking();
        } else if (this.input.drive() < 0.0F) {
            target = this.input.drive() * powertrain.reverseSpeed();
            step = this.speed > target ? powertrain.acceleration() : powertrain.braking();
        } else {
            target = 0.0F;
            step = powertrain.rollingResistance();
        }

        // 登坂限界超え。駆動系に坂へ回す余力が無いので牽引をやめ、下の重力処理が次を決める——この傾斜なら
        // ずり落ちる。
        float limit = holdableSlope(suspension);

        if (target > 0.0F && this.hullPitch > limit) {
            target = 0.0F;
        }

        if (target < 0.0F && this.hullPitch < -limit) {
            target = 0.0F;
        }

        this.speed = approach(this.speed, target, step);

        // 斜面に沿った重力。坂を登るのにコストを払わせ、下りでは返す。効くのは車両が立っている物だけ。空中に
        // 転がり落ちる斜面は無く、落下は鉛直処理側で扱う。船は平らな水面で水平なので、そもそも転がり落ちる
        // 斜面が無く対象外。
        if (this.onGround() && !this.getStats().isShip()) {
            double along = -Attitude.nose(this.attitude).y;
            this.speed += (float) (along * GRAVITY * powertrain.gradeResistance());
        }

        if (Math.abs(this.speed) < STANDSTILL) {
            this.speed = 0.0F;
        }
    }

    /**
     * 走行を許す車体傾斜の上限（度）。車両の登坂限界に、傾斜読み取り誤差の最大値を足した値。
     *
     * <p>この世界の斜面は階段であり、4本のプローブは段差の置く場所に着地する。5ブロックの接地長では、45度の
     * 階段は車両が渡る間 {@code atan(4/5)} から {@code atan(6/5)}——39度から50度——まで振れる。車体をなめらか
     * に降ろしてもこの波は狭まるだけで消えない。度単位で厳密に比較すると、車両自身の数値が登れると言っている
     * まさにその斜面で半分近くのtickで出力が切られ、途中で立ち往生する。誤差は接地長あたり1ブロック分が全て
     * なので、それを見込むことで {@code slope_limit} が書いてある通りの傾斜を意味するようになる。
     */
    private static float holdableSlope(GroundVehicleDefinition.Suspension suspension) {
        double span = Math.max(suspension.contactLength(), 1.0);
        double rise = Math.tan(Math.toRadians(Mth.clamp(suspension.slopeLimit(), 0.0F, 89.0F))) * span;

        return (float) Math.toDegrees(Math.atan2(rise + 1.0, span));
    }


    /**
     * 車両を地図上で動かす。車首方向へは駆動系が決めた速度で、横方向へはまだ止まりきっていない滑り分だけ。
     *
     * <p>水平方向のみ。地上車両の高さは落下と衝突の結果ではまったくない——何かの<em>上に</em>立っており、その
     * 何かがどこにあるかは {@link #settleOnGround} が自前のプローブで測る。だから両者は分離されている。ここは
     * 地面のどこにいるかを決め、あちらが高さと角度を決める。ここでは素の直方体を一切参照しないし、その必要も
     * ない。
     *
     * <p>tick をまたぐ運動量は横方向成分だけ。車首方向は全て駆動系の管轄で毎回決め直すが、旋回したばかりの
     * 車体はまだ少し前の方向へ進んでおり、それがどれだけ速く消えるかが「履帯が地面を噛む」ということだ。
     */
    private void travel() {
        Vec3 forward = this.headingVector();
        Vec3 sideways = new Vec3(-forward.z, 0.0, forward.x);
        Vec3 was = this.getDeltaMovement();
        double drift = new Vec3(was.x, 0.0, was.z).dot(sideways)
                * (1.0 - Mth.clamp(this.getStats().suspension().grip(), 0.0F, 1.0F));

        Vec3 asked = forward.scale(this.speed).add(sideways.scale(drift));
        Vec3 allowed = this.getStats().isShip() ? this.limitAfloat(asked) : this.limitToShape(asked);

        this.horizontalCollision = allowed.x != asked.x || allowed.z != asked.z;
        this.setDeltaMovement(allowed.x, this.fallSpeed, allowed.z);
        this.setPos(this.getX() + allowed.x, this.getY(), this.getZ() + allowed.z);

        // 世界が実際に許した移動量。駆動系の要求と世界の承認の間で、斜面は1tick分の移動をかなり削りうる。
        // 壁に押し付けられているのに速度が30のままの車両は永久に空転し、エンジン音が全開に張り付く。
        double covered = new Vec3(allowed.x, 0.0, allowed.z).dot(forward);

        if (Math.abs(covered) < Math.abs(this.speed)) {
            this.speed = (float) covered;
        }
    }

    /**
     * 何かにぶつかるまでに車両がどれだけ動けるか。
     *
     * <p>素の直方体は参照しないし、結局のところ航空機が使うスイープも使わない。どちらも地上車両にとっては誤った
     * 問いに答えている。Minecraft はエンティティを正方形底面の直立直方体でしか記述できず、7m の戦車にとって
     * それは小屋だ。代わりに当たり判定の箱をスイープする案は、もっと微妙な理由で失敗する——箱はそれを囲む
     * <em>直立</em>直方体で運ばれるので、斜面へ傾いた8ブロックの車体を囲む直立直方体は履帯より2ブロック下へ
     * 潜り、前方の斜面の楔を丸ごと含んでしまう。それでスイープすると戦車はどんな斜面も登れない。立っている坂
     * と永久に衝突し続けるからだ。
     *
     * <p>そこで代わりに、運転手が問うであろう問いを、車両が実際に持つ形状の四隅で問う。<em>この隅の下の地面は
     * 車両の登坂能力より速くせり上がっているか？</em> 斜面は毎tick少しずつ持ち上げるので登れる。縁石は段差
     * 1つ分持ち上げるので乗り越えられる——それが {@code climb_height} の意味だ。壁は車両の限界を超えて持ち上げる
     * ので止められる。逆向きの崖の縁も同様。車体がどう寝ているかには一切依存しない。スイープ方式に欠けていた
     * のはまさにその性質だ。
     *
     * <p>2軸は別々に問うので、壁へ斜めに突っ込んだ車両は、壁に沿う分の移動量を失わずに残せる。
     */
    private Vec3 limitToShape(Vec3 movement) {
        if (movement.lengthSqr() == 0.0
                || this.level().isClientSide && !this.isControlledByLocalInstance()) {
            return movement;
        }

        Footprint footprint = this.footprint();

        if (footprint.isEmpty()) {
            return movement;
        }

        Vec3[] corners = footprint.corners(this.position(), this.headingVector());
        double[] before = new double[corners.length];

        for (int i = 0; i < corners.length; i++) {
            before[i] = this.groundUnder(corners[i]);
        }

        double climb = this.getStats().suspension().climbHeight();
        double x = this.canStep(new Vec3(movement.x, 0.0, 0.0), corners, before, footprint, climb)
                ? movement.x : 0.0;
        double z = this.canStep(new Vec3(0.0, 0.0, movement.z), corners, before, footprint, climb)
                ? movement.z : 0.0;

        return new Vec3(x, movement.y, z);
    }

    /**
     * 車両が1歩進めるか。どの隅でも地面が登坂能力を超えて上がっておらず、どの隅でもその地面の上に覆い被さる物
     * が無いこと。
     *
     * <p>2つ目の判定は1つ目にできないことをやる。壁は「上がった地面」として現れる——外側で落としたプローブが
     * 天面に着地するからだ。だが頭上の橋桁はまったく現れない。その下の地面は車両が今立っているのと同じ地面
     * だからだ。よって頭上空間は別途、局所地面の段差1つ上から車両上端までを見て、その隙間にある物は車体が
     * ぶつかる物として扱う。
     */
    private boolean canStep(Vec3 step, Vec3[] corners, double[] before, Footprint footprint, double climb) {
        if (step.lengthSqr() == 0.0) {
            return true;
        }

        for (int i = 0; i < corners.length; i++) {
            Vec3 to = corners[i].add(step);
            double ground = this.groundUnder(to);

            if (Double.isNaN(ground)) {
                // その隅の下に何も無い。溝か崖の縁で、車両は自由に乗り出せるし、踏み切れば落ちる。
                continue;
            }

            if (!Double.isNaN(before[i]) && ground - before[i] > climb + CLIMB_SLACK) {
                return this.crushesThrough(step);
            }

            // 頭上空間を問う価値があるのは、プローブが実際に地表を見つけた場所だけ。PROBE_ABOVE で頭打ちに
            // なった読み値はそうではない。それは「ここの地面は少なくともこの高さ」としか言っておらず、本当は
            // 数ブロック上にある床から上へ隙間を測ると、斜面そのものを覗き込んで「車両に覆い被さる物」と報告
            // してしまう。1ブロック段差の連なりの前で戦車が急停止していたのはこれが原因だ——しかも平地で、
            // 最初の段差の数ブロック手前で止まる。坂を読むのは車首側に張り出した隅だからだ。除外しても失う物は
            // 無い。その高さの地面は、隅が最初に触れたtickで上の登坂判定が既に拒否している。
            if (!this.isProbeCeiling(ground) && !this.hasHeadroom(to, ground, footprint.top(), climb)) {
                return this.crushesThrough(step);
            }
        }

        return true;
    }

    /**
     * ここから1歩先で行く手を塞ぐ物が全て、押し通れるほど柔らかいか。
     *
     * <p>何かに既に拒否された1歩に対してのみ問い、その拒否に対する最終判断となる。土の壁や木立は装軌車両を
     * 止められないので、車両はそこへ進入を許される。ここでは何も壊さない。壊すのはサーバー側の
     * {@link #crushBlocks} で、車両が実際に到達した位置から行う——同じ領域に同じ問いを投げるので、これを根拠に
     * 許された1歩の障害物はそのtick内に消える。両者の実装は {@link BlockCrusher} にある。
     *
     * <p>判定領域は異議を唱えた隅ではなく車体全体。安全側だ。永久に片付けられない物が1つある空間へ車両を通せば、
     * その車両は壁の中に永久駐車される。
     */
    private boolean crushesThrough(Vec3 step) {
        return BlockCrusher.opens(this.level(), this.body(this.position().add(step)),
                this.getStats().crush().resistance());
    }

    /**
     * ある位置で車体が占める領域。{@link BlockCrusher} が投げる2つの問いのために使う。
     *
     * <p>水平化せず車体が沿っている平面に沿わせる。これがこの機構を掘削機にしない全てだ。ここでのピッチとロール
     * はサスペンションが履帯下の地面から読んだ値なので、斜面ではこの領域の床が斜面そのものになり、中に壊す物は
     * 無い。平地では床も平らで、段差を超える高さの土手は領域内に入り、消える。
     */
    private BlockCrusher.Body body(Vec3 at) {
        Footprint footprint = this.footprint();
        double climb = this.getStats().suspension().climbHeight();
        double rise = Math.tan(Math.toRadians(Mth.clamp(this.hullPitch, -CRUSH_SLOPE_CAP, CRUSH_SLOPE_CAP)));
        // ロールは左側が高いときを正として記述し、横軸は右向き。
        double tilt = -Math.tan(Math.toRadians(Mth.clamp(this.hullBank, -CRUSH_SLOPE_CAP, CRUSH_SLOPE_CAP)));

        return new BlockCrusher.Body(at, this.headingVector(), footprint.halfWidth(), footprint.front(),
                footprint.back(), rise, tilt, climb + CRUSH_CLEARANCE, footprint.top());
    }

    /**
     * 車体が突っ込んだ物を破壊する。サーバー限定。
     *
     * <p>車両が動いている間は毎tick、行き先ではなく現在位置から実行する。これが運転手が乗った車両で機能する
     * 理由であり、そこが肝心な場合だ。そうした車両は運転手自身のクライアントでシミュレートされ、サーバーは移動
     * をまったく走らせないので、ここで問うべき1歩は存在しない——あるのは届いた位置と、クライアントが「進入
     * できる」と判断した物の中に立つ車体だけだ。その車体でスイープすればまさにそのブロックが壊れる。クライアント
     * に判断を委ねることなく。
     *
     * <p>何も押さずに地上で停止している車両は対象外。コストのためではなく（無料ではないが）、何かに寄せて駐車
     * した車両はそこに留まるべきで、じわじわ食い荒らすべきではないからだ。スロットルを踏み込んでいる運転手は別
     * 問題で、車両がどれだけ動けていなくても対象外にはしない。移動が止められ、破壊が片付けたはずの物は常に
     * 1tick後に自ら片付くのであり、速度だけを見ていると車両はそこで擦って止まったまま動かなくなる。今tick何も
     * 進めなかった車両とは、地面が速度を奪ったばかりの車両だからだ。
     */
    private void crushBlocks() {
        if (this.level().isClientSide) {
            return;
        }

        if (Math.abs(this.speed) < STANDSTILL && this.input.drive() == 0.0F && this.onGround()) {
            return;
        }

        Footprint footprint = this.footprint();

        if (footprint.isEmpty()) {
            return;
        }

        GroundVehicleDefinition.Crush crush = this.getStats().crush();

        BlockCrusher.crush(this.level(), this, this.body(this.position()), crush.resistance(), crush.drops());
    }

    /**
     * 地面の読み値が、実際に見つけた地表ではなくプローブの上限かどうか。
     *
     * <p>{@link #groundUnder} は車両の {@link #PROBE_ABOVE} 上からトレースを始め、ブロック内部から始めた
     * トレースは開始点をそのまま返す——だからそれより高い地面は、実際にどれだけ上にあってもちょうどその高さと
     * 読まれる。登坂判定にとっては安全側だが、地表の位置を知りたい用途にはまったく無価値だ。
     */
    private boolean isProbeCeiling(double ground) {
        return ground >= this.getY() + PROBE_ABOVE - PROBE_CEILING_SLACK;
    }

    /** ある地点の地面上の隙間が、車両が通れる高さあるか。 */
    private boolean hasHeadroom(Vec3 where, double ground, double top, double climb) {
        Vec3 from = new Vec3(where.x, ground + climb, where.z);
        Vec3 to = new Vec3(where.x, ground + top, where.z);

        if (to.y - from.y < HEADROOM_MARGIN) {
            return true;
        }

        return this.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, this)).getType() == HitResult.Type.MISS;
    }

    /**
     * 車両座標系での形状の張り出し量。車幅、前後の張り出し、車高。
     *
     * <p>素の直方体ではなく当たり判定の箱から取るので、移動を測る隅は実体の隅になる。ただし形を決めるのは
     * <em>車体</em>上の箱だけで、高さには全ての箱が寄与する。真横へ据えた砲で止められるのは運転手の想定外だし、
     * 地形に引っ掛かる砲身があると、壁際で旋回するたび戦車が嵌まってしまう。
     *
     * <p>これは砲塔だけでなく砲身にも当てはまり、以前はそうなっていなかった。砲身は砲塔ではなく砲に取り付いた
     * 独立の箱なので、砲塔の箱を除外しても砲身は残り、移動判定の隅が、全長4.5ブロックの車体の前方9ブロックまで
     * 伸びた形状の隅になっていた——レオパルトが何にでも4ブロック手前で止まり、登坂判定の地面を次の丘の中腹で
     * 読んでいたわけだ。
     *
     * @param front 車首方向への張り出し。戦車では砲口ではなく車体前面
     * @param back 同じく後方への張り出し。負値
     */
    private record Footprint(double halfWidth, double front, double back, double top) {
        static Footprint of(VehicleShape shape) {
            double halfWidth = 0.0;
            double front = 0.0;
            double back = 0.0;
            double top = 0.0;

            for (VehicleShape.Box box : shape.boxes()) {
                Vec3 half = box.size().scale(0.5);
                top = Math.max(top, box.offset().y + half.y);

                if (box.mount() != VehicleShape.Mount.HULL) {
                    continue;
                }

                halfWidth = Math.max(halfWidth, Math.abs(box.offset().x) + half.x);
                front = Math.max(front, box.offset().z + half.z);
                back = Math.min(back, box.offset().z - half.z);
            }

            return new Footprint(halfWidth, front, back, top);
        }

        boolean isEmpty() {
            return this.halfWidth <= 0.0 || this.top <= 0.0;
        }

        /** ある位置・方位における設置面積の四隅（ワールド座標）。 */
        Vec3[] corners(Vec3 at, Vec3 forward) {
            Vec3 right = new Vec3(-forward.z, 0.0, forward.x);
            Vec3 nose = forward.scale(this.front);
            Vec3 tail = forward.scale(this.back);
            Vec3 side = right.scale(this.halfWidth);

            return new Vec3[] {
                    at.add(nose).subtract(side), at.add(nose).add(side),
                    at.add(tail).subtract(side), at.add(tail).add(side)};
        }
    }

    /**
     * 最後に読んだ形状の設置面積と、その出所のファイル群。形状と共に再構築する。全箱に対する比較を数回行う
     * 処理であり、移動処理が1tickに2回要求するからだ。
     */
    @Nullable
    private Footprint footprint;

    private Footprint footprint() {
        VehicleShape shape = this.getShape();
        Footprint current = this.footprint;

        if (current == null) {
            current = Footprint.of(shape);
            this.footprint = current;
        }

        return current;
    }

    /**
     * バニラを含め、何もこの車両を素の直方体で動かさない。戦車を押す物は全て、駆動系と同様に実際の構成箱に
     * 対して解決される。
     *
     * <p>例外が1つあり、それが重要。{@link MoverType#PLAYER} の移動は運転クライアントが<em>既に到達した</em>
     * 位置の報告で、バニラの vehicle パケットで届く。サーバーはこれに異を唱える立場にない。1tick遅れており、
     * 地面プローブは旧位置の地形を読み、拒否した分はバニラに「クライアントが不正に動いた」と解釈される——
     * その時点でバニラは車両を元に戻しクライアントへ補正を送る。斜面へ突っ込んだ戦車が静かに後ろへ引き戻される
     * わけだ。運転側は実際に見えている地面に対してこの判定を既に済ませている。情報の少ないここで再実行しても
     * 移動を削る効果しかない。
     *
     * <p>これはバニラがその報告に対して行うことの半分にすぎない。もう半分は素の直方体が空中に立っているかを
     * 問うが、この形状で構成されていない車両にとってそれは同じ誤りを別の形で問うだけだ——
     * {@code VehicleMoveCheckMixin} がそれを無効化している。
     */
    @Override
    public void move(MoverType type, Vec3 movement) {
        Vec3 allowed = type == MoverType.SELF
                ? (this.getStats().isShip() ? this.limitAfloat(movement) : this.limitToShape(movement))
                : movement;

        this.setPos(this.getX() + allowed.x, this.getY() + allowed.y, this.getZ() + allowed.z);
        this.horizontalCollision = allowed.x != movement.x || allowed.z != movement.z;
    }

    /**
     * 履帯の四隅の下の地面を読み、その上に車体を寝かせる。
     *
     * <p>1本ではなく4本のプローブを使う。1本では地面がどちらへ傾いているか分からないからだ。前2本と後2本の差が
     * ピッチ、左2本と右2本の差がロール。穴の上に張り出した隅は「無し」と読まれ両方から除外されるので、溝に車首
     * を出した戦車は水平のまま固まらず前のめりになる。
     *
     * <p>結果は直接採用せず、なめらかに追従させる。隅が段差を越えた瞬間に車両下の地面は丸1ブロック変化するので、
     * 毎回の読み値をそのまま採る車体は耕地を渡るだけでバラバラに揺れる。
     */
    private void settleOnGround(GroundVehicleDefinition.Suspension suspension) {
        Vec3 forward = this.headingVector();
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x);
        double halfLength = suspension.contactLength() * 0.5;
        double halfWidth = suspension.contactWidth() * 0.5;

        Vec3 centre = this.position();
        double frontLeft = this.groundUnder(centre.add(forward.scale(halfLength)).subtract(right.scale(halfWidth)));
        double frontRight = this.groundUnder(centre.add(forward.scale(halfLength)).add(right.scale(halfWidth)));
        double rearLeft = this.groundUnder(centre.subtract(forward.scale(halfLength)).subtract(right.scale(halfWidth)));
        double rearRight = this.groundUnder(centre.subtract(forward.scale(halfLength)).add(right.scale(halfWidth)));

        // 下に何も無ければ水平と読む。空中の車両が向かうべき姿勢はそれだ。今離れた斜面の角度を保つ理由は
        // 無い。
        float targetPitch = slopeAngle(mean(frontLeft, frontRight), mean(rearLeft, rearRight), halfLength * 2.0);
        float targetBank = slopeAngle(mean(frontLeft, rearLeft), mean(frontRight, rearRight), halfWidth * 2.0);

        float rate = Mth.clamp(suspension.settleRate(), 0.0F, 1.0F);
        this.hullPitch = Mth.lerp(rate, this.hullPitch, targetPitch);
        this.hullBank = Mth.lerp(rate, this.hullBank, targetBank);
        this.setXRot(-this.hullPitch);

        // 接地面の中心。車体が同じ平面に沿って寝ている以上、車両の原点はここに属する。
        this.rest(mean(mean(frontLeft, frontRight), mean(rearLeft, rearRight)), suspension);
    }

    /**
     * 履帯下で測ったばかりの地面から、車両の立つ高さを決める。
     *
     * <p>地上車両がこの MOD の他の全てと最も異なる点。航空機は落ち、ぶつかった物に止められる。戦車は何かの
     * <em>上に立って</em>おり、その高さは衝突の残りかすではなく答えのある問いだ。だから高さは接地面下の地面から
     * 取り、何も衝突しない——素の直方体を移動処理から完全に外せるのもこのためだ。素の直方体は車両を持ち上げる
     * 以外の仕事をしていなかった。
     *
     * <p>段差高で3つの場合に分かれる。車両と同じか上の地面は縁石で、乗り上げる。少し下の地面は坂で、追従して
     * 下る——重力任せだと一瞬浮いてから叩きつけられる。全速の戦車は、最初の数tickの落下よりはるかに速く降りる
     * からだ。ずっと下、あるいは何も無ければ落差で、そこでは他と同様に落ちる。
     */
    private void rest(double support, GroundVehicleDefinition.Suspension suspension) {
        double climb = suspension.climbHeight();
        double rise = support - this.getY();

        if (Double.isNaN(support) || rise < -climb) {
            this.fall();

            return;
        }

        // 段差へ乗り上げるか、坂を下る。登坂能力を超える高さは、ここへ来る前に水平方向で止められている。
        // 上限は、読み違えが車両を崖に沿って打ち上げるのを防ぐためのもの——本来は崖で止まるべきなのだ。
        this.setPos(this.getX(), this.getY() + Math.min(rise, climb), this.getZ());
        this.fallSpeed = 0.0;
        this.setOnGround(true);
    }

    /** 落下1tick分。終端速度で頭打ちにし、崖から落ちた車両が何も追い越さないようにする。 */
    private void fall() {
        this.fallSpeed = Math.max(this.fallSpeed - GRAVITY, -MAX_FALL);
        this.setPos(this.getX(), this.getY() + this.fallSpeed, this.getZ());
        this.setOnGround(false);
    }

    /**
     * ある地点の下の地面の高さ。届く範囲に無ければ {@link Double#NaN}。
     *
     * <p>ハイトマップではなくトレースで求める。ハイトマップは洞窟の中も橋も切通しも知らないが、戦車はその全て
     * を走るからだ。トレースは短く——車体の上下数ブロック——車両自身の設置面積内に収まる。車両がそこに立って
     * いる以上、その範囲は定義上ロード済みだ。
     *
     * <p>落下が速いほど下方向へ長く伸ばす。さもないと終端速度で崖から落ちた車両は1tickでプローブの視程より
     * 長く進み、着地するはずだった床をプローブが一度も触れないまま突き抜ける。
     */
    private double groundUnder(Vec3 where) {
        double below = PROBE_BELOW + Math.abs(this.fallSpeed);
        Vec3 from = new Vec3(where.x, this.getY() + PROBE_ABOVE, where.z);
        Vec3 to = new Vec3(where.x, this.getY() - below, where.z);
        BlockHitResult hit = this.level().clip(
                new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));

        return hit.getType() == HitResult.Type.MISS ? Double.NaN : hit.getLocation().y;
    }

    // ------------------------------------------------------------------
    // 船である場合
    // ------------------------------------------------------------------

    /**
     * 車体を地面に寝かせる代わりに喫水線で浮かせる。船が戦車と袂を分かつ唯一の箇所。
     *
     * <p>船体を支えるのは排除した水であり、その全体は1つの釣り合いだ。浮く深さから押しのけられるほど水は強く
     * 押し返し、そこで残る上下動は減衰が取り除くので、振動せず落ち着く。だからここでの高さは戦車のように何かから
     * 読むのではなく、航空機のように積分する——ただし重力ではなく静止喫水線へ向けて。平らな水面では船体も水平
     * なので、戦車と違って沿うべき斜面が無く、姿勢は水平へ戻されるだけだ。
     *
     * <p>水の外では浮く物が無い。空中に落とされた船、浜へ乗り上げた船は、戦車とまったく同じように落ちて地面で
     * 止まる——それが砂浜を走らせず岸で座礁させる仕組みだ。
     */
    private void settleOnWater(GroundVehicleDefinition.Buoyancy buoyancy) {
        // 平らな水面に傾斜は無いので、船は傾けた物の角度を保たず常に水平へ戻る。
        float trim = Mth.clamp(buoyancy.trimRate(), 0.0F, 1.0F);
        this.hullPitch = Mth.lerp(trim, this.hullPitch, 0.0F);
        this.hullBank = Mth.lerp(trim, this.hullBank, 0.0F);
        this.setXRot(-this.hullPitch);

        double surface = this.waterSurfaceUnder(this.position());

        if (Double.isNaN(surface)) {
            // 水上ですらない。座礁したか空中に落とされたか。他の車体同様に落ち、降りた場所で地面に止められる
            // ——大抵は岸で、陸へ突っ込んだ船の行き着く先だ。水の外では進めない。フィールドの説明参照。
            this.afloat = false;
            this.rest(this.groundUnder(this.position()), this.getStats().suspension());

            return;
        }

        this.afloat = true;

        // 静止喫水へ向かうバネ。減衰を効かせて1〜2秒で落ち着かせる。
        double restY = surface - buoyancy.draught();
        double error = restY - this.getY();
        this.fallSpeed += error * buoyancy.buoyancy();
        this.fallSpeed *= 1.0 - Mth.clamp(buoyancy.damping(), 0.0F, 1.0F);
        this.fallSpeed = Mth.clamp(this.fallSpeed, -MAX_FALL, MAX_FALL);

        this.setPos(this.getX(), this.getY() + this.fallSpeed, this.getZ());
        // 水に支えられているので、他の全処理からは接地扱いになる。落下していないので、全損させる落下距離を
        // 溜めることも、空中扱いされることもない。
        this.setOnGround(true);
    }

    /**
     * ある地点の下の水面高さ。届く範囲に無ければ {@link Double#NaN}。
     *
     * <p>船体のすぐ上から落下しうる距離まで下方へトレースし、上から最初に出会った水を水面とする——そのブロック
     * 自身の充填高さを使うので、船は下のブロック境界ではなく水の上面に浮く。水以外は数えない。船は海にだけ浮く。
     */
    private double waterSurfaceUnder(Vec3 where) {
        int x = Mth.floor(where.x);
        int z = Mth.floor(where.z);
        int top = Mth.floor(this.getY() + PROBE_ABOVE);
        int bottom = Mth.floor(this.getY() - (PROBE_BELOW + Math.abs(this.fallSpeed)));
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int y = top; y >= bottom; y--) {
            pos.set(x, y, z);
            FluidState fluid = this.level().getFluidState(pos);

            if (fluid.is(FluidTags.WATER)) {
                return y + fluid.getHeight(this.level(), pos);
            }
        }

        return Double.NaN;
    }

    /**
     * 座礁するまでに船がどれだけ動けるか。戦車の移動制限と同じ枠組みだが、問いを船の流儀で立てる。
     *
     * <p>戦車は登坂能力を超えて上がる地面に止められる。船を止めるのは浮いている水面を破る陸だけで、海底が
     * どれだけ近くても素通りする。よって判定は地面の上がる速さではなく高さだ。前方で自艦の喫水線を超える物は
     * 岸、それ以下は本来その上を通る深みである。2軸は別々に問うので、海岸へ斜めに突っ込んだ船は岸に沿う分の
     * 進路を残せる。
     */
    private Vec3 limitAfloat(Vec3 movement) {
        if (movement.lengthSqr() == 0.0
                || this.level().isClientSide && !this.isControlledByLocalInstance()) {
            return movement;
        }

        Footprint footprint = this.footprint();

        if (footprint.isEmpty()) {
            return movement;
        }

        Vec3[] corners = footprint.corners(this.position(), this.headingVector());
        double x = this.clearAfloat(new Vec3(movement.x, 0.0, 0.0), corners) ? movement.x : 0.0;
        double z = this.clearAfloat(new Vec3(0.0, 0.0, movement.z), corners) ? movement.z : 0.0;

        return new Vec3(x, movement.y, z);
    }

    /** {@code step} だけ進めた船のどの隅も、喫水線より上の陸に当たらないか。 */
    private boolean clearAfloat(Vec3 step, Vec3[] corners) {
        if (step.lengthSqr() == 0.0) {
            return true;
        }

        double waterline = this.getY();

        for (Vec3 corner : corners) {
            double ground = this.groundUnder(corner.add(step));

            if (!Double.isNaN(ground) && ground > waterline + SHORE_CLEARANCE) {
                return false;
            }
        }

        return true;
    }

    /**
     * 乗員の視界が自分の目線からどれだけ下へ倒されているか（度）。
     *
     * <p>運転クライアントが毎tick設定し、他では0。三人称視点は車両の {@code camera.tilt} だけ下へ回され、
     * 空ではなく地面が画面に入る——{@code GroundVehicleCameraHandler} 参照——ので、砲も同じ量だけ下げて画面中央
     * が砲の線であり続けるようにする。これが無いと両者は傾き分そのままずれ、この種の車両では5〜12度になる。
     * 乗員が目標に十字線を合わせても砲はその遥か上を向いており、弾はそちらへ飛ぶ。
     *
     * <p>ここで算出せず通知を受ける。乗員がどの視点を使い、それがどう傾いているかは本人のクライアントにしか
     * 答えられないからだ。信頼しても失う物は無い。砲塔は既にこのクライアント上で据えられており
     * （{@link #tickTurret} 参照）、サーバーへ伝わるのは角度であって、その根拠ではない。
     */
    public void setSightTilt(float degrees) {
        this.sightTilt = degrees;
    }

    /**
     * 乗員が見ている方向へ、可能な最大速度で砲塔を回す。
     *
     * <p>2つの角度は車体座標系で保持されワールド座標系で照準されるので、ここで求めるのはその差——砲手の視線
     * から車体の指向を引いた値だ。機械的な限界は車体基準なので（砲は自分の車体上面へそこまでしか俯角を取れない）、
     * 車体自身のピッチを除いた後に適用する。前ではない。
     *
     * <p>据える先は乗員の視線ではなく画面中央。三人称視点ではこの2つは別方向だ。{@link #setSightTilt} 参照。
     */
    private void tickTurret(GroundVehicleDefinition.Turret turret) {
        if (!turret.exists()) {
            return;
        }

        if (!(this.getControllingPassenger() instanceof LivingEntity crew)) {
            return;
        }

        float wantYaw = Mth.wrapDegrees(crew.getYHeadRot() - this.heading);
        float wantPitch = Mth.clamp(-(crew.getXRot() + this.sightTilt) - this.hullPitch,
                -turret.depression(), turret.elevation());

        // 座標へ飛ぶ弾の発射機は、乗員の視線をまったく見ない。狙いは盤で決まっており
        // （{@code LaunchConsoleScreen}）、弾は出た後に自分で目標へ向き直るので、架台に残っている仕事は
        // 「立つ」ことだけだ。だから振らない——方位を合わせる意味が無いし、実物の弾道弾発射機も真上へ向けて
        // 撃つ。据えれば起立し、解除すれば寝る。それが車両の外から見える唯一の状態表示でもある。
        if (this.laysPoint()) {
            wantYaw = 0.0F;
            wantPitch = this.getDesignatedPoint() != null ? turret.elevation() : 0.0F;
        }

        float yaw = approachAngle(this.turretYaw, wantYaw, turret.traverseRate());
        float pitch = approach(this.gunPitch, wantPitch, turret.elevationRate());

        this.setTurret(yaw, pitch);
    }

    /**
     * 操舵輪を運転手の要求方向へ毎tick少しずつ振る。
     *
     * <p>「合わせる」ではなく「向かわせる」。1tickで全舵から全舵へ跳ねる車輪は操舵ではなくグリッチに見えるし、
     * この振れこそが「滑っているのではなく運転されている」ことを示す大半だ。追うのは車体ではなく<em>運転手</em>
     * なので、停止中に舵を当てたままの車両はそれを表示する——装輪車両ではそうせざるを得ない。停止中は曲がれない
     * ので、車体は運転手が何をしているか何も語らないからだ。
     */
    private void windSteering() {
        VehicleChassis.Model model = this.getStats().model();

        this.steerAngleO = this.steerAngle;

        if (!model.isSteered()) {
            this.steerAngle = 0.0F;

            return;
        }

        float lock = model.steerLock();

        this.steerAngle = approach(this.steerAngle, this.getSteerInput() * lock, lock * STEER_SWING);
    }

    /**
     * 1tick分の走行距離だけ転輪を回す。1回転内に収める。
     *
     * <p><b>左右で違う距離を送る。</b> 旋回中、外側の履帯は車体の中心より長い弧を、内側は短い弧を描く。
     * 速度0でその場で回る超信地旋回では、車体は1ブロックも進まないまま両履帯が逆向きに同じだけ回っており
     * ——装軌車両がその場で向きを変えられる理由がそれだ——車体の速度だけを見ていると履帯が止まって見える。
     */
    private void windTrack(float travelled) {
        float circumference = (float) (2.0 * Math.PI * Math.max(this.getStats().suspension().wheelRadius(), 0.05F));
        // 片側が中心から離れている距離は車体半幅とした。実際の輪距はそれより少し狭いが、この値が決めるのは
        // 「旋回中に履帯がどれだけ速く流れるか」だけで、当たり判定にも走行にも一切影響しない。
        float swing = (float) Math.toRadians(this.turned) * this.getStats().hitbox().width() * 0.5F;

        this.trackLeft = (this.trackLeft + travelled + swing) % circumference;
        this.trackRight = (this.trackRight + travelled - swing) % circumference;

        // 継ぎ目の両側で折り返す。0をまたいで後進したとき、前回距離が丸1回転ぶん離れてしまい、1フレームだけ
        // 車輪が逆回転するのを防ぐ。
        if (Math.abs(this.trackLeft - this.trackLeftO) > circumference * 0.5F) {
            this.trackLeftO = this.trackLeft;
        }

        if (Math.abs(this.trackRight - this.trackRightO) > circumference * 0.5F) {
            this.trackRightO = this.trackRight;
        }
    }

    /** 車体の回転。運転手が操る方位＋地面が与える2角から組む。 */
    private Quaternionf buildAttitude() {
        return Attitude.of(this.heading, -this.hullPitch).rotateZ(this.hullBank * DEG_TO_RAD);
    }

    /** 車体の指向を水平面上の単位ベクトルで。 */
    private Vec3 headingVector() {
        float radians = this.heading * DEG_TO_RAD;

        return new Vec3(-Mth.sin(radians), 0.0, Mth.cos(radians));
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

        if (Double.isNaN(b)) {
            return a;
        }

        return (a + b) * 0.5;
    }

    private static float approach(float current, float target, float step) {
        if (step <= 0.0F) {
            return target;
        }

        return current < target
                ? Math.min(current + step, target)
                : Math.max(current - step, target);
    }

    /** 同じ処理を、円周上の近い側を通って行う版。 */
    private static float approachAngle(float current, float target, float step) {
        float difference = Mth.wrapDegrees(target - current);

        if (step <= 0.0F || Math.abs(difference) <= step) {
            return Mth.wrapDegrees(target);
        }

        return Mth.wrapDegrees(current + Math.signum(difference) * step);
    }

    // ------------------------------------------------------------------
    // 他者が運転している場合
    // ------------------------------------------------------------------

    /**
     * 運転手が降りてこの側が運転をやめるtickに備え、引き継ぎ状態を維持する。
     *
     * <p>この側で運転される車両は2つの物を置き去りにし、どちらも運転手が<em>乗り込んだ</em>時点の位置を指した
     * まま止まる。1つ目は下の lerp で、運転中は単に走らないので最後に通知された値を保つ。2つ目はもっと厄介だ。
     * 追跡エンティティの位置は前回位置からの<em>差分</em>として届き、両端がその基準を各自保持するのだが、バニラ
     * はローカルプレイヤーが運転中の車両についてはそうしたパケットを全て捨てる——デコード前に捨てるので、
     * クライアント側の基準は決して更新されない。更新されるのは絶対位置が来たときだけで、サーバーがそれを送るのは
     * 400tickに1回。
     *
     * <p>運転中は何も表面化しない。この側は自前の物理で車両を描いており、パケットは無視されているからだ。表面化
     * するのは運転手が降りた瞬間だ。ようやく次の差分がデコードされ、その基準は運転20秒分古いことすらありうる。
     * そして車両はその差分の先へ戻される——乗員には、今降りたばかりの戦車が来た道を走り去っていくように見える。
     * ここで毎tick基準を進めるコストはベクトル1つで、引き継ぎは最悪でも1tick前の位置から始まる。
     */
    private void holdPosition() {
        this.lerpSteps = 0;
        this.syncPacketPositionCodec(this.getX(), this.getY(), this.getZ());
    }

    /**
     * サーバーが最後に送った位置へ、瞬間移動ではなくなめらかに寄せる。
     *
     * <p>この側で誰も運転していない車両は1tickに1つの位置として届き、そのまま採用すればいずれも階段状の跳躍に
     * なる。数tickかけて広げれば走行している車両になる。実際そうなのだから。
     */
    private void tickLerp() {
        if (this.lerpSteps <= 0) {
            return;
        }

        double x = this.getX() + (this.lerpX - this.getX()) / this.lerpSteps;
        double y = this.getY() + (this.lerpY - this.getY()) / this.lerpSteps;
        double z = this.getZ() + (this.lerpZ - this.getZ()) / this.lerpSteps;
        float yaw = this.getYRot() + Mth.wrapDegrees((float) this.lerpYRot - this.getYRot()) / this.lerpSteps;

        this.lerpSteps--;
        this.setPos(x, y, z);
        this.setYRot(yaw);
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        this.lerpX = x;
        this.lerpY = y;
        this.lerpZ = z;
        this.lerpYRot = yRot;
        this.lerpSteps = LERP_TICKS;
    }

    @Override
    public double lerpTargetX() {
        return this.lerpSteps > 0 ? this.lerpX : this.getX();
    }

    @Override
    public double lerpTargetY() {
        return this.lerpSteps > 0 ? this.lerpY : this.getY();
    }

    @Override
    public double lerpTargetZ() {
        return this.lerpSteps > 0 ? this.lerpZ : this.getZ();
    }

    @Override
    public float lerpTargetYRot() {
        return this.lerpSteps > 0 ? (float) this.lerpYRot : this.getYRot();
    }

    /**
     * 乗員の体を車体の向きに合わせ、頭はそのままにする。
     *
     * <p>航空機とは意図的に異なる。航空機は搭乗者の視界を機体と共に回す。キー操作で飛ばす以上、視界も進行方向を
     * 向いていた方がよいからだ。戦車は視界で照準する。車体と共に視界を回せば、運転手が操縦桿に触れるたび砲が
     * 目標から引き剥がされる——{@link #steer} のスタビライザーがまさに防いでいる事態だ。
     */
    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction moveFunction) {
        super.positionRider(passenger, moveFunction);
        passenger.setYBodyRot(this.getYRot());
    }

    @Override
    public void onPassengerTurned(Entity passenger) {
        passenger.setYBodyRot(this.getYRot());
    }

    /**
     * 降車者を車両の脇の地面、車体から離れた場所へ降ろす。
     *
     * <p>Minecraft の既定は素の直方体の天面中央へ落とすことで、この車両にとってそれは最悪の場所だ。素の直方体
     * には何も衝突しないので、その天面は砲塔の30cmほど内側にある。そこへ置かれた乗員は固体のパーツ箱の中に立つ
     * ことになる——そして箱は内側からクリックできない。ピックは線が箱へ<em>入る</em>位置を問うが、内側から
     * 始まる線は入らないからだ。つまり戦車へ乗り直せない。降りたことへの報いとしてはひどい。
     *
     * <p>実際に人が降りる順で4か所を試す。左側面、右側面、後方、前方。各所は世界に対しても<em>車両自身の箱</em>
     * に対しても空いている必要があり、それは {@code noCollision} が検査してくれる。降車済みの者にとってパーツは
     * 通常の固体エンティティだからだ。4か所とも駄目なら天面へ——最も高い箱の内部ではなく上に置く。少なくとも
     * 立てる場所ではある。
     */
    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        Footprint footprint = this.footprint();

        if (footprint.isEmpty()) {
            return super.getDismountLocationForPassenger(passenger);
        }

        Vec3 forward = this.headingVector();
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x);
        double clearance = passenger.getBbWidth() * 0.5 + DISMOUNT_MARGIN;
        Vec3[] beside = {
                right.scale(-(footprint.halfWidth() + clearance)),
                right.scale(footprint.halfWidth() + clearance),
                forward.scale(footprint.back() - clearance),
                forward.scale(footprint.front() + clearance)};

        for (Vec3 offset : beside) {
            Vec3 spot = this.position().add(offset);
            double ground = this.groundUnder(spot);

            if (Double.isNaN(ground)) {
                continue;
            }

            Vec3 candidate = new Vec3(spot.x, ground, spot.z);
            AABB room = passenger.getDimensions(passenger.getPose()).makeBoundingBox(candidate);

            if (this.level().noCollision(passenger, room)) {
                return candidate;
            }
        }

        return this.position().add(0.0, footprint.top(), 0.0);
    }

    // ------------------------------------------------------------------
    // 操作を受ける
    // ------------------------------------------------------------------

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        // クライアントにこれを判断する立場は無く、試みてもならない。AircraftEntity.interact の同じ注記参照。
        // 全てに yes を返し、何が起きたかはサーバーが決める。
        if (this.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }

        ItemStack held = player.getItemInHand(hand);

        // 弾薬箱は搭乗より先に見る。砲弾を抱えて戦車へ歩み寄る者は装填したいのであって、乗り込みたいので
        // はない。入る場所が無ければクリックは素通りして通常どおり乗車になる——満載の車両を撫でて弾薬を
        // 1個失うことは起きない。
        // 名前の付いた1弾種は、汎用の弾薬箱より先に見る。徹甲弾を抱えて歩み寄る者は、その弾を積みたい
        // のであって「砲弾なら何でも」ではない。
        if (held.getItem() instanceof AmmunitionItem round) {
            InteractionResult loaded = this.loadRound(player, held, round.getAmmunitionId());

            if (loaded.consumesAction()) {
                return loaded;
            }
        }

        if (held.getItem() instanceof AmmoItem ammo) {
            InteractionResult loaded = this.loadAmmo(player, held, ammo.getKind());

            if (loaded.consumesAction()) {
                return loaded;
            }
        }

        // 燃料缶は搭乗より先に見る。缶を持って戦車へ歩み寄る者は給油したいのであって、乗り込みたいのでは
        // ない。満タンなら缶は減らず、クリックは素通りして通常どおり乗車になる。
        if (held.getItem() instanceof FuelItem && FuelItem.refuel(this, player, held)) {
            return InteractionResult.CONSUME;
        }

        if (held.getItem() instanceof WrenchItem) {
            return this.dismantle(player);
        }

        if (!this.canAddPassenger(player)) {
            return InteractionResult.PASS;
        }

        // force 指定。ここでの意味は、ボートを降りた後にバニラが課す3秒待ちを適用しないというだけ。あの遅延は
        // 歩いて乗り込んでしまう乗り物のための物で、意図的にクリックが要る乗り物では邪魔でしかない。何か確認
        // しようと降りた乗員はすぐ乗り直したいのだ。force が飛ばす2つの検査は上で行っている——スニーク中は
        // 乗らない、満員の車両にも乗らない。
        return player.startRiding(this, true) ? InteractionResult.CONSUME : InteractionResult.PASS;
    }

    /**
     * 手にある1弾種を、それを受け付ける架台へ積み込む。
     *
     * <p>汎用の弾薬箱（{@link #loadAmmo}）と対になる道。あちらは「砲弾なら入る」で、こちらは
     * 「この弾種を並べている架台にだけ入る」。だから 120mm 徹甲弾を 125mm の戦車へ差し出しても何も
     * 起きず、クリックは素通りして乗車になる。
     *
     * <p>探す順は主砲・同軸機銃・発射筒で、そこも汎用の箱と同じ。同じ弾種を2箇所が並べている車両では
     * 主砲が先に満ちる。
     *
     * @return 何か入ったなら CONSUME。入らなければ PASS
     */
    private InteractionResult loadRound(Player player, ItemStack held, ResourceLocation round) {
        if (this.isWrecked() || Math.abs(this.speed) > STANDSTILL) {
            return InteractionResult.PASS;
        }

        int offered = held.getCount();

        for (Armament station : Armament.VALUES) {
            int capacity = switch (station) {
                case MAIN -> this.gun.capacity();
                case COAX -> this.coax.capacity();
                case MISSILE -> this.launcher.capacity();
            };
            int taken = Magazine.load(this, station, round, capacity, offered);

            if (taken == 0) {
                continue;
            }

            held.consume(taken, player);
            WeaponMounts.playLoadSound(this, true);
            // 何がどこへ入ったかを言う。弾種を積む車両では「どの架台か」だけでは足りない——同じ架台が
            // 3種類を持っているので、入ったのがどれかこそ知りたいことだ。
            player.displayClientMessage(Component.translatable("message.ashvehicles.loaded_round",
                    Component.translatable("item." + round.getNamespace() + "." + round.getPath()),
                    Magazine.rounds(this, station, round), Magazine.total(this, station), capacity), true);

            return InteractionResult.CONSUME;
        }

        return InteractionResult.PASS;
    }

    /**
     * 手にある弾薬箱を、それが入る弾倉へ押し込む。かつて車両が持っていた弾庫と装填手の仕事は、全部この1
     * クリックになった。
     *
     * <p><b>1回のクリックで、入るだけ入る。</b> 砲弾40発の戦車に1発ずつ40回クリックさせる仕組みは装填では
     * なく作業だ。手にあるスタックのうち弾倉に収まる分を取り、残りは手に残る。
     *
     * <p>探す順は主砲・同軸機銃・発射筒。同じ種類を2箇所が受け付ける車両——主砲も機関砲で同軸も機関砲の
     * 場合——では主砲が先に満ちる。主兵装だからだ。1回のクリックが満たすのは1箇所だけで、それは
     * 「何が装填されたか」を1行で言えるようにするためでもある。
     *
     * <p><b>停止している車両だけ。</b> 揺れている車体に砲弾を手渡す者はいない。以前この規則は装填の側に
     * 書かれていたが、今は装填する者が車外に立っているので、車両が答える。
     *
     * @return 何か入ったなら CONSUME。入らなければ PASS で、クリックは乗車などの本来の意味へ流れる
     */
    private InteractionResult loadAmmo(Player player, ItemStack held, AmmoKind kind) {
        if (this.isWrecked() || Math.abs(this.speed) > STANDSTILL) {
            return InteractionResult.PASS;
        }

        int offered = held.getCount();
        int taken = this.gun.load(kind, offered);
        String station = "main";
        int rounds = this.gun.rounds();
        int capacity = this.gun.capacity();

        if (taken == 0) {
            taken = this.coax.load(kind, offered);
            station = "coaxial";
            rounds = this.getCoaxRounds();
            capacity = this.coax.capacity();
        }

        if (taken == 0) {
            taken = this.launcher.load(kind, offered);
            station = "launcher";
            rounds = this.getMissiles();
            capacity = this.launcher.capacity();
        }

        if (taken == 0) {
            return InteractionResult.PASS;
        }

        held.consume(taken, player);
        WeaponMounts.playLoadSound(this, true);
        // 弾倉は車両の同期データにあるので、乗っていない者の画面には何も出ない。装填したことが分かる手段は
        // 音とこの1行しかなく、行為の結果——どこへ何発入り、満載まであとどれだけか——はここでしか言えない。
        player.displayClientMessage(Component.translatable("message.ashvehicles.loaded",
                Component.translatable("station.ashvehicles." + station), rounds, capacity), true);

        return InteractionResult.CONSUME;
    }

    /** 車両をアイテムへ畳み戻す。地上作業であり、誰か乗っている間は不可。 */
    private InteractionResult dismantle(Player player) {
        // 残骸は先に、別の答えを返す。畳む車両はもう無く、片付けるべき残骸とその中の金属があるだけだ。
        if (this.isWrecked()) {
            return this.salvage();
        }

        if (!this.getPassengers().isEmpty() || Math.abs(this.speed) > STANDSTILL) {
            return InteractionResult.PASS;
        }

        this.destroy(this.getDropItem());

        return InteractionResult.CONSUME;
    }

    /**
     * 自前の箱を持つ車両自身は固体ではない。固体なのは箱の方だ。
     *
     * <p>Minecraft はエンティティに正方形底面の直立直方体を1つ与えるが、7m の戦車にとってそれは小屋だ——車体が
     * 何をしていようと正方形で、斜面を横切れば履帯からかけ離れる。車両自身のファイルにある箱こそ本当の形状なので、
     * 箱が1つでもあればそちらが仕事をし、素の直方体はふりをやめる。
     *
     * <p>降板しても失う物は無い。{@link VehiclePart} が被弾・クリック・ピック結果を車両へそのまま渡すので、
     * 撃たれることも乗り込まれることも上に立たれることも従来通りここへ届く。自前の箱を持たない車両は素の直方体を
     * 保つ。さもないと一切触れられなくなるからだ。
     *
     * <p>{@link #limitToShape} と {@link #rest} により、素の直方体にはもう仕事が無い。障害物でもなく、標的でも
     * なく、車両がぶつかる相手でもなく、車両を支える物でもない。残るのは帳簿上の役割——どのチャンクセクションに
     * 登録されるか、範囲クエリで拾われるか——だけであり、描画判定に使われないことは
     * {@link #getBoundingBoxForCulling} が保証している。
     */
    @Override
    public boolean isPickable() {
        return !this.isRemoved() && this.parts.length == 0;
    }

    @Override
    public boolean canBeCollidedWith() {
        return !this.isRemoved() && this.parts.length == 0;
    }

    // ------------------------------------------------------------------
    // 永続化と GeckoLib
    // ------------------------------------------------------------------

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);

        float storedHeading = tag.contains("Heading") ? tag.getFloat("Heading") : this.getYRot();
        this.snapAttitude(storedHeading, tag.getFloat("HullPitch"), tag.getFloat("HullBank"));
        this.setHealth(tag.contains("Health") ? tag.getFloat("Health") : this.getMaxHealth());
        this.setTurret(tag.getFloat("TurretYaw"), tag.getFloat("GunPitch"));
        this.turretYawO = this.turretYaw;
        this.gunPitchO = this.gunPitch;
        this.gun.load(tag);
        this.coax.load(tag);
        this.launcher.load(tag);
        // 弾種の内訳は、上の3つが残弾カウンタを埋めた後で。弾種を持つ車両では内訳の方が正しく、
        // カウンタはそこから改めて書き直される。内訳を持たない古いセーブの分もここで振り分ける。
        Magazine.restore(this, tag);
        // 古いセーブは真偽値1つしか持っていない。その頃の「真」はミサイル、「偽」は主砲だった。
        this.entityData.set(DATA_ARMAMENT, tag.contains("Armament")
                ? Armament.byName(tag.getString("Armament")).ordinal()
                : (tag.getBoolean("MissileMode") ? Armament.MISSILE : Armament.MAIN).ordinal());
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("Heading", this.heading);
        tag.putFloat("HullPitch", this.hullPitch);
        tag.putFloat("HullBank", this.hullBank);
        tag.putFloat("Health", this.getHealth());
        tag.putFloat("TurretYaw", this.turretYaw);
        tag.putFloat("GunPitch", this.gunPitch);
        this.gun.save(tag);
        this.coax.save(tag);
        this.launcher.save(tag);
        Magazine.save(this, tag);
        tag.putString("Armament", this.selected().name());
    }

    /**
     * アニメーションファイルからは何も再生しない。地上車両が自身に対して行うこと——砲塔、砲、転輪——は全て
     * 車両が既に把握している値に毎瞬追従する物で、
     * {@link com.ashvehicles.client.model.GroundVehicleModel#setCustomAnimations} がコードでポーズを付ける。
     * ハッチのように角度ではなく手順である物を足すなら、ここにコントローラが要る。
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.animatableCache;
    }
}
