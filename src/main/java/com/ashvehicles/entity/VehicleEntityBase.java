package com.ashvehicles.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.particle.Effects;
import com.ashvehicles.sensor.Sensors;
import com.ashvehicles.weapon.TargetLock;
import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.vehicle.Hitbox;
import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.vehicle.VehicleShape;
import com.ashvehicles.vehicle.WreckEffects;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaternionf;

/**
 * 飛ぶ物でも走る物でも、この MOD の全機体に共通する部分。
 *
 * <p>物理は含まない。機体は自分で姿勢を選び自分を支え、戦車は地面に寝てその上を押される。動き方について
 * 両者が互いに言うことはほとんど無い。共有しているのはその<em>周り</em>の全部だ。どちらも1つの正方形の
 * 当たり判定ではなく複数の箱でできており、どちらもボートの4点ではなく数百点の価値があり、どちらも乗り込ま
 * れて座られ、どちらも自分の名前で見つかるファイルに記述される。
 *
 * <p>ここには4つの物がある。
 *
 * <p><b>箱。</b> Minecraft がエンティティに与えるのは底面が正方形の直立した箱1つで、それは機体の形でも
 * 戦車の形でもなく、歩ける面でもない。代わりに両者ともファイルの箱定義から配置した {@link VehiclePart}
 * でできている。各箱がどこにあり、どちらを向いているかだけが違う——機体の箱は構造に固定され、戦車の箱は
 * 砲塔に運ばれ得る——のでそこはフック2つにし、残りは共有する。
 *
 * <p><b>ダメージ。</b> 耐久と、いくつの箱を経由して届いても1回だけ数える打撃。
 *
 * <p><b>座席。</b> 乗員がどこに座るか（機体自身の軸で）と、何人乗れるか。
 *
 * <p><b>名前。</b> 機体の ID はエンティティ型の ID であり、ファイル・モデル・形状・設置アイテムはすべて
 * その名前で見つかる。
 */
public abstract class VehicleEntityBase extends VehicleEntity implements PartHost {
    /** この機体の残り耐久（ヒットポイント）。0 なら煙を上げる穴。 */
    private static final EntityDataAccessor<Float> DATA_HEALTH =
            SynchedEntityData.defineId(VehicleEntityBase.class, EntityDataSerializers.FLOAT);

    /**
     * 機体が全損しているか。
     *
     * <p>同期するのは、残骸であることが見た目の大半を占めるから。クライアントは燃え尽きた機体を炭化した姿
     * で、生きている機体を本来の色で描くが、それを見分ける手段が他に無い。フラグの持ち主はサーバーで、
     * {@link #wreck()} 以外がこれを設定することは無い。
     */
    private static final EntityDataAccessor<Boolean> DATA_WRECKED =
            SynchedEntityData.defineId(VehicleEntityBase.class, EntityDataSerializers.BOOLEAN);

    /**
     * どの乗員がどの座席にいるか。両者を切り離すための情報。
     *
     * <p>バニラにはこの概念が無い。搭乗者の席は「搭乗者リストの中の位置」＝乗り込んだ順でしかなく、降りて
     * 誰かを先に乗せない限り変えられない。ここではその2つを切り離す——座席ごとに1つ、空席なら空欄の、同期
     * された乗員 ID の列——ので、乗員は誰も降りずに別の席へ、運転席を含めて移れる。持ち主はサーバー。読むの
     * は両側だ。座席は搭乗者の描画位置（{@link #getPassengerAttachmentPoint}）と操縦者
     * （{@link #getControllingPassenger}）を決め、それらは両側で問われるから。{@link #switchToNextSeat}
     * 参照。
     *
     * <p>通信形式は乗員の {@link UUID} を座席順にカンマ区切りで並べた物。空席は空欄になる。
     * {@code "u0,,u2"} なら座席0に運転手、座席1は空、座席2に搭乗者。
     */
    private static final EntityDataAccessor<String> DATA_SEATS =
            SynchedEntityData.defineId(VehicleEntityBase.class, EntityDataSerializers.STRING);

    /**
     * タンクに残っている燃料。
     *
     * <p>同期する理由は2つあり、どちらも「持ち主はサーバーだが、必要とするのはクライアント」だ。飛行モデル
     * を回しているのは操縦しているクライアントなので、乾いたタンクが実際に推力を切るにはそちら側がそれを
     * 知っていなければならない。そして計器はそれを表示する。数値の持ち主はサーバーで、燃やすのも給油する
     * のもあちら側だけだ。
     */
    private static final EntityDataAccessor<Float> DATA_FUEL =
            SynchedEntityData.defineId(VehicleEntityBase.class, EntityDataSerializers.FLOAT);

    /** ファイルが1つも無い機体の耐久。最初の擦り傷で終わらないように。 */
    public static final float DEFAULT_HEALTH = 300.0F;

    /**
     * 自分のスクラップ量をファイルに書いていない機体で、鉄インゴット1個が相当する耐久値。300点の機体なら
     * 13個で、機体1台分の金属としては妥当だが「壊すために機体を作る」理由にはならない量。
     */
    private static final float HEALTH_PER_INGOT = 24.0F;

    /**
     * 外皮を拳で叩いた時の音量と高さ。
     *
     * <p>静かで、素材を借りてきた両方の金属音より高い。欲しいのは「これが鋼でできていると気付いた音」で
     * あって、鍛冶場の音ではない。
     */
    private static final float KNUCKLE_VOLUME = 0.45F;
    private static final float KNUCKLE_PITCH = 1.5F;

    /** この機体を構成する箱。コンストラクタで一度だけ組む。{@link #buildParts} 参照。 */
    /**
     * 1tickでどれだけ動いても「そこへ置かれた」ではなく「そこまで進んだ」と見なすか。これを超えたら、甲板
     * の上に立っている者はその場に留まる。
     */
    private static final double CARRY_LIMIT = 32.0;

    protected VehiclePart[] parts = new VehiclePart[0];
    /** 前回配置した時点で全ての箱がどこにあったか。{@link #placedBounds} 参照。 */
    @Nullable
    private AABB placed;
    /** その時点で機体自身がどこにいて、どちらを向いていたか。{@link #carryStanders} 参照。 */
    @Nullable
    private Vec3 carriedFrom;
    private float carriedHeading;

    /** 直近に受けた打撃。複数の箱を同時に経由して届いた1発が1回だけ効くように。 */
    @Nullable
    private DamageSource lastHurtSource;
    private long lastHurtTime = Long.MIN_VALUE;

    /**
     * 残骸になってからの tick 数と、前 tick にまだどれだけ動いていたか。
     *
     * <p>年齢は炎が燃え尽きていく基準で、他の物と一緒にワールドへ書き込まれる。一晩置かれた残骸は朝には
     * 燃えたてではなく冷えている。他の2つは tick と tick の間でしか要らない——落下中の残骸が到着した瞬間を
     * 検出する手段であり、それは「速度が速度でなくなる tick」だ。
     */
    private int wreckAge;
    private boolean wasFalling;
    private double fallSpeed;

    /** 名前。変わることは無い。エンティティの型は生成時に決まるので。 */
    @Nullable
    private ResourceLocation vehicleId;
    /** その名前に対応する箱と、それがどのファイル群から来たか。 */
    @Nullable
    private VehicleShape shape;
    private int shapeVersion = -1;

    /**
     * 弾庫が存在した頃のセーブデータから読み戻した積荷。次のサーバー tick で地面へ出て、この参照は消える。
     *
     * <p>機械はもう内部に物を積まない——弾は手から直接入る——が、弾庫が消えたバージョンで初めて開かれた
     * ワールドには、まだ物の入った弾庫を持つ機械が駐まっている。それはプレイヤーの物であり、更新した瞬間に
     * 黙って消える理由は無い。読む時点では世界がまだアイテムを受け取れないので、置くのは tick まで待つ。
     */
    @Nullable
    private List<ItemStack> spilledHold;

    protected VehicleEntityBase(EntityType<?> type, Level level) {
        super(type, level);
    }

    // ------------------------------------------------------------------
    // 機体が何であるか
    // ------------------------------------------------------------------

    /**
     * この機体の ID＝エンティティ型の ID。ファイルからモデルから設置アイテムまで、この機体に関する他の全部
     * が同じ名前で見つかる。
     */
    public ResourceLocation getVehicleId() {
        ResourceLocation id = this.vehicleId;

        if (id == null) {
            id = BuiltInRegistries.ENTITY_TYPE.getKey(this.getType());
            this.vehicleId = id;
        }

        return id;
    }

    /**
     * この機体を構成する箱。
     *
     * <p>毎回引き直さず保持するが、それはファイルが動かない間だけ。{@link Definitions} が別のバージョンを
     * 報告した瞬間に写しを捨てるので、{@code /reload} は既に世界にいる機体にも効く。
     */
    public VehicleShape getShape() {
        VehicleShape current = this.shape;

        if (current == null || this.shapeVersion != Definitions.version()) {
            current = Definitions.shape(this.getVehicleId());
            this.shape = current;
            this.shapeVersion = Definitions.version();
            this.onShapeChanged();
        }

        return current;
    }

    /** リロードで機体に別の箱一式が渡された時に呼ばれる。 */
    protected void onShapeChanged() {
    }

    /**
     * エンジンの音。機体自身のファイルから。
     *
     * <p>ここで宣言しているのは、{@code EngineSounds} に「機体」を渡せるようにするため（「飛行機」ではなく）。
     * 戦車のエンジンとジェットのエンジンについて言うべきことはまったく同じ3つ——どの音声か、どれだけの音量
     * か、どこまで届くか——であり、鳴らす側がどちらを持っているか知る理由は元々無かった。
     */
    /**
     * 種類を問わず全機体が持つ車体諸元。素の直方体の大きさ、追跡距離、そしてロード済みの世界を越えた後も
     * 報告され続ける距離。
     *
     * <p>訊いてくるのは {@code EntityTrackingMixin} で、あちらは判断対象が飛ぶか走るかを知る必要が無い
     * ——「機体は牛より遠くから知らせる価値がある」ということだけ分かればよい。
     */
    /**
     * どこにいても tick する。ただしクライアントでのみ。
     *
     * <p>プレイヤーがロードした世界の外にいる機体も送られ続け、ゴーストとして描かれ続ける。しかしクライアン
     * トは chunk を持っていない物の tick を止める——tick されない物は位置パケットが供給する補間を回さないの
     * で、そこで描かれるのは「縁を越えた瞬間に凍り付いた目標」になる。常に tick すると宣言することがそれを
     * 動かし続ける。戦車でも機体でも答えは同じだ。凍ったゴーストの砲塔は画面上で最も誤解を招く物になる。
     *
     * <p>サーバーでは断じてやらない。あちらでは何かが chunk を開いているから機体が tick するのであって、
     * それでも tick させれば、サーバーがロードしていない地面の上で物理を回し、問い合わせた全ブロックがその
     * 場で生成されることになる。
     */
    @Override
    public boolean isAlwaysTicking() {
        return this.level().isClientSide;
    }

    /**
     * 他に何をしていようと全機体がやる唯一のこと。残骸なら燃える。
     *
     * <p>各機体の tick ではなくここに置いてあるのは、どちらでも同じ火だからで、両者ともここへ上がってくる
     * から。サーバー側のみ。この演出は1つ残らずパーティクルパケットか音であり、クライアントが自前で作れば
     * 見える者には火が二重に描かれる。
     *
     * <p>この tick に機体が動く前に走らせる。それが、残骸の残す煙を前方ではなく後方に置く。
     */
    @Override
    public void tick() {
        super.tick();

        // 残骸の判定より前に。燃料はどの機械も、飛んでいようが走っていようが同じように消す物であり、以下の
        // 残骸専用の分岐に入る資格とは無関係だ。中で自分の条件を見る。
        if (!this.level().isClientSide) {
            this.tickFuel();
            this.spillOldHold();
        }

        if (!this.isWrecked() || !(this.level() instanceof ServerLevel level)) {
            return;
        }

        this.wreckAge++;

        // getVelocity() ではなく deltaMovement を使う。これはこの tick に機体が動く前に走り、物理を回して
        // いる側の getVelocity() は「どこまで進んだか」から測るので、tick のこの時点では毎回ゼロになる。
        // ここで delta が持っているのは前 tick が残した値で、それがまさに残骸の落ち方。
        Vec3 velocity = this.getDeltaMovement();
        // まだ落ちているかどうかは、まだ動いているかどうかとは別。全損機は速度を地面へ持ち込んでそのまま
        // 滑るので、畑の上端で接地した残骸が下端でもまだ動いていることがある——そして噴煙が属するのは最初に
        // 当たった場所であって、滑走が止まった場所ではない。
        boolean falling = velocity.lengthSqr() > WreckEffects.FALLING && !this.onGround();
        double reach = this.reach();

        if (falling) {
            // 落下中に出した最大速度であって、止まる時にたまたま持っていた速度ではない。残骸は着地後も
            // 数tick滑るし、衝撃は滑走の終わりではなく落ちてきた高さに見合うべきだから。
            this.fallSpeed = Math.max(this.fallSpeed, velocity.length());
        } else if (this.wasFalling) {
            WreckEffects.impact(level, this.position(), this.fallSpeed, reach);
            this.fallSpeed = 0.0;
        }

        this.wasFalling = falling;

        WreckEffects.burn(level, this.position(), this.getAttitude(), this.wreckAge, velocity, reach);
    }

    public abstract VehicleChassis.Hitbox hitbox();

    public abstract VehicleChassis.Sound soundSetup();

    /**
     * {@code engine.<vehicle>}。機体のエンジン音が収録される名前の根。
     *
     * <p>他の音と一緒ではなくこちら側で名付けているのは、サーバーがこれを要求できる必要があり、サーバーは
     * リソースパックを一度も見たことが無いから。同じ名前のクライアント側は {@code ModSounds} にある。
     */
    public static final String SOUND_PREFIX = "engine.";

    /** エンジンの負荷（[0,1]）。音の高さと音量をここから決める。 */
    public abstract float getEngineNote();

    // ------------------------------------------------------------------
    // 燃料
    // ------------------------------------------------------------------

    /**
     * この機械のタンクの仕様。機体はエンジンの、地上車両は駆動系の一部として書く。
     *
     * <p>抽象のままにしてあるのは、燃料が「どこに書かれているか」だけが種類ごとに違い、それ以外は全部同じ
     * だからだ。燃料を持たない機械は {@link VehicleChassis.Fuel#NONE} を返せばよく、そうすれば以下は
     * 何もしない。
     */
    public abstract VehicleChassis.Fuel fuelSetup();

    /** タンクに残っている量。両側で同じ値を返す。 */
    public float getFuel() {
        return this.entityData.get(DATA_FUEL);
    }

    /** 0を下回らず、タンクの容量を超えない。サーバーのみ。 */
    public void setFuel(float left) {
        if (!this.level().isClientSide) {
            this.entityData.set(DATA_FUEL, Mth.clamp(left, 0.0F, this.fuelSetup().capacity()));
        }
    }

    /**
     * タンクへ入れて、実際に入った量を返す。溢れる分は受け取らない——燃料アイテムは満タンの機械に対して
     * 消費されずに済むべきで、それを判断できるのは入れてみた結果だけだ。
     *
     * @return 実際に入った量。満タンなら0
     */
    public float addFuel(float amount) {
        if (this.level().isClientSide || amount <= 0.0F) {
            return 0.0F;
        }

        float before = this.getFuel();
        this.setFuel(before + amount);

        return this.getFuel() - before;
    }

    /** 満タンに対する残量の割合（[0,1]）。燃料を持たない機械は常に満タンと答える。 */
    public float getFuelFraction() {
        VehicleChassis.Fuel fuel = this.fuelSetup();

        return fuel.fitted() ? Mth.clamp(this.getFuel() / fuel.capacity(), 0.0F, 1.0F) : 1.0F;
    }

    /**
     * エンジンを回せるだけの燃料があるか。
     *
     * <p>タンクを持たない機械——ファイルが容量0と書いた物——は常に真だ。燃料の概念そのものを持たない機械が
     * 燃料切れで止まることはない。
     */
    public boolean hasFuel() {
        return !this.fuelSetup().fitted() || this.getFuel() > 0.0F;
    }

    /** 燃料を積む機械が、それを切らしているか。計器と警報が問う向き。 */
    public boolean isOutOfFuel() {
        return this.fuelSetup().fitted() && this.getFuel() <= 0.0F;
    }

    /**
     * 今この機械を動かしている者。
     *
     * <p>普通は運転席の乗員そのものだが、<em>席に座っていることが操縦の条件ではない</em>機械が1種類ある
     * ——無人機だ。操作者は席にいないどころか席が存在せず、それでも計器を読み、引き金を引き、目標を指示
     * するのはその人物である。{@link com.ashvehicles.entity.AircraftEntity#getOperator} 参照。
     *
     * <p><b>「操縦権」とは別の問いだ。</b>{@link #getControllingPassenger} が答えるのは「誰のクライアント
     * が物理を回すか」で、無人機ではそれが誰でもない（サーバーが回す）。こちらが答えるのは「誰が操って
     * いるか」。有人機では同じ人物を指すが、同じ問いではない。
     *
     * <p>操縦席を条件にしていた物——レーダーの報告先、引き金、指示の受理、撃った弾の持ち主、燃料を燃やす
     * かどうか——は全部こちらを見るべきだ。どれも「誰のクライアントが物理を回すか」とは無関係だった。
     */
    @Nullable
    public LivingEntity getAviator() {
        return this.getControllingPassenger();
    }

    /**
     * エンジンが今かかっているか。燃料を消すかどうかの判断であって、出力の大小ではない。
     *
     * <p>誰かが乗っているか、あるいはレバーが入っているか。放置された機械のエンジンは止まっていると見なす。
     * 野原に置いた戦車が翌週には空タンクになっている——それは誰も望まない現実味だ。
     */
    protected boolean isEngineRunning() {
        return this.getAviator() != null || this.getEngineNote() > 0.0F;
    }

    /**
     * この tick 分の燃料を燃やす。サーバーのみ。残骸は燃やさない——燃える物はもう燃えた。
     *
     * <p>消費はエンジン負荷に従う。速度でも距離でもない。垂直に上っている機体は最も速く消費するし、それは
     * どこへも進んでいない。{@link VehicleChassis.Fuel#burn} 参照。
     */
    private void tickFuel() {
        VehicleChassis.Fuel fuel = this.fuelSetup();

        if (!fuel.fitted() || this.isWrecked() || !this.isEngineRunning()) {
            return;
        }

        float burnt = fuel.burn(this.getEngineNote(), this.getAfterburner());

        if (burnt > 0.0F) {
            this.setFuel(this.getFuel() - burnt);
        }

        // 燃やした分を外部タンクから補う。実機と同じで、増槽は直接エンジンへ送るのではなく本体タンクへ
        // 移送する。だから増槽が先に空になり、本体は満タンのまま残る——投棄した瞬間に航続距離が尽きること
        // にはならない。増槽を持たない機械では下の呼び出しが0を返して終わる。
        // 空きが1単位に満たないうちは引かない。外部タンクは整数単位で数えるので、入る場所が0.08単位しか
        // 無いところへ1単位を引けば、差の0.92単位は満タンで切り捨てられて消える——1tickあたり十数倍の速さで
        // 増槽が空になる。溜まって1単位ぶんの空きができてから引けば、1滴も失われない。
        float room = fuel.capacity() - this.getFuel();

        if (room >= 1.0F) {
            this.setFuel(this.getFuel() + this.drawExternalFuel(room));
        }
    }

    /**
     * 外部タンクから燃料を引く。既定では持っていないので0。
     *
     * <p>{@code AircraftEntity} だけが増槽を吊れるので、そちらが上書きする。基底に置いてあるのは、燃料を
     * 燃やしているのがここだからで、「どこから補うか」は「いつ補うか」と同じ場所で決まるべきだ。
     *
     * @param wanted 本体タンクの空き。これ以上引いても入れる場所が無い
     * @return 実際に引けた量
     */
    protected float drawExternalFuel(float wanted) {
        return 0.0F;
    }

    /**
     * エンジンが出している再燃焼の量（[0,1]）。
     *
     * <p>ほとんどの物では常に0だ。戦車にアフターバーナーは無いしヘリコプターにも無い。機体側ではなくここで
     * 訊くのは、エンジン音がここで訊かれるからで、バーナーがその音に何をするかは「エンジンの負荷」と同じ
     * 問いだから。{@code EngineSoundInstance} 参照。
     */
    public float getAfterburner() {
        return 0.0F;
    }

    /** 機体の本当の速度（1tickあたりブロック）。訊いている側がどちらでも同じ値。 */
    public abstract Vec3 getVelocity();

    /** レーダーと警戒受信機。サーバー側で、操縦席にいる者へ画面が送られる。 */
    private final Sensors sensors = new Sensors(this);

    /**
     * この機体が他の全部について見える物と、この機体を見ている相手。
     *
     * <p>機体側ではなく基底に置いてあるのは、両方の種類が同じ計器を欲しがるからで、しかも<em>互いについて
     * </em>欲しがるから。機体の警戒受信機は、地上の発射機が自分を見ていることを聞き取れなければならず、それ
     * ができるのは地上の発射機が同種のレーダーを持っている場合だけだ。ファイルがレーダーも受信機も与えて
     * いない機体は何も掃引せず、コストもゼロ。
     */
    public Sensors getSensors() {
        return this.sensors;
    }

    /**
     * この機体の兵装がどちらを向いているか。
     *
     * <p>機体が<em>どう寝ているか</em>とは別の問い。機体は自分を向けて狙うのでこれは機首になり、砲塔付きの
     * 機体は旋回して狙うのでこれは砲身方向であり車体とは無関係だ。兵装の指向方向を知る必要がある物——シーカー、
     * レーダーの視野、照準——は、2つのどちらかを選ぶのではなくここへ訊く。それが3つとも両方の機体種別で動く
     * 理由。
     */
    public abstract Vec3 getAimDirection(float partialTick);

    /** 機体ファイルが言う探知手段。大半は {@link VehicleChassis.Radar#NONE}。 */
    public abstract VehicleChassis.Radar radar();

    /**
     * 機体に取り付けた物のおかげで、シーカーが兵装ファイルの値よりどれだけ遠くまで届くか、そしてロックが
     * どれだけ速く決まるか。
     *
     * <p>役立つ物を積んでいない機体では1。全地上車両と、センサーステーションが空の全機体がそれ。照準ポッド
     * を積んだ機体はそれ以上を返す（{@link com.ashvehicles.weapon.EquipmentDefinition} 参照）。訊くのは
     * {@link com.ashvehicles.weapon.TargetLock} が見る tick で、ポッドの価値がシーカーの中に散らばらず
     * 1箇所で決まるようにしてある。
     */
    public float seekerRangeGain() {
        return 1.0F;
    }

    /** ロックが決まる速さについての同じ物。{@link #seekerRangeGain} 参照。 */
    public float lockRateGain() {
        return 1.0F;
    }

    /**
     * シーカーが捉えている物。シーカーを持たない機体では null。
     *
     * <p>兵装を何個積んでいても機体につき最大1つ。シーカーは撃たれる物ではなく見る物で、乗員の目は1対しか
     * 無い。実際にどこに置かれているかは各機体の都合——機体はパイロンと一緒に、発射機は発射筒と一緒に持つ
     * ——で、ここではどちらかを知る必要が無い。
     */
    @Nullable
    public TargetLock lock() {
        return null;
    }

    /**
     * 機体がどう寝ているか。3つの角度ではなく回転として。
     *
     * <p>方位・仰角・バンクは Minecraft が mob の向きを表す方法で、上下が保たれる物には十分だ。ここの機体は
     * どちらもそうではない。宙返りの頂点の機体は背面かつ後ろ向きだし、斜面を横切る戦車には Minecraft に
     * フィールドすら無いロールがある。回転にはその継ぎ目が無い。
     *
     * <p>{@code attitudeO} は前 tick 終了時の値で、2つの tick の間に描かれる物を補間できるようにする。
     * 姿勢がどう値を得るかは各機体の都合——一方は操作から積分し、他方は地面から組み立てる。
     */
    protected Quaternionf attitude = new Quaternionf();
    protected Quaternionf attitudeO = new Quaternionf();

    /**
     * 誰も操縦していない機体を指定の姿勢にする。それを描く計器のため。
     *
     * <p>この MOD の全機体には「描かれるためだけに存在する複製」がある。命中表示は弾が着いた相手の絵を必要
     * とするが、この距離で撃たれる以上その相手はたいていクライアント自身の世界の外にいる。だからエンティティ
     * 型から1つ作り、どこにも追加せず、サーバーが言った通りの姿勢にする——それがこれ。
     *
     * <p>現在の姿勢と前回の姿勢の両方を設定する。tick されない機体には前回が無く、レンダラーは2つを補間する
     * から。片方だけ設定すれば、生成時の姿勢へ半分戻った状態で描かれる。
     *
     * @param turret 砲塔の指向。持たない物では無視される
     * @param gun 砲の仰角。持たない物では無視される
     */
    public void poseForDrawing(Quaternionf hull, float turret, float gun) {
        this.attitude = new Quaternionf(hull);
        this.attitudeO = new Quaternionf(hull);
    }

    public Quaternionf getAttitude() {
        return this.attitude;
    }

    /** 2つの tick の間の任意の瞬間における同じ物。描画側のため。 */
    public Quaternionf getAttitude(float partialTick) {
        return new Quaternionf(this.attitudeO).slerp(this.attitude, partialTick);
    }

    // ------------------------------------------------------------------
    // ダメージ
    // ------------------------------------------------------------------

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        // この機体自身の最大値ではなく定数を使う。これはエンティティのコンストラクタの中から走り、その
        // 時点で訊ける機体がまだ無いから。本当の値はコンストラクタが埋める。
        builder.define(DATA_HEALTH, DEFAULT_HEALTH);
        builder.define(DATA_WRECKED, false);
        builder.define(DATA_SEATS, "");
        // 耐久と同じ理由で0から始める。これはコンストラクタの中から走るのでまだ機体に訊けない。本当の量は
        // コンストラクタが満タンで埋める。
        builder.define(DATA_FUEL, 0.0F);
    }

    /**
     * これが機体ではなく残骸か。同じ場所に同じ形であるが、焼け抜けており、中の金属以外に用途が無い。
     *
     * <p>あちこちで、しかも両側から訊かれる。残骸では何も動かない——エンジンもレーダーも引き金も無く、誰も
     * 乗っていない——し、これを持つレンダラーは炭化した姿で描く。
     */
    public boolean isWrecked() {
        return this.entityData.get(DATA_WRECKED);
    }

    protected void setWrecked(boolean wrecked) {
        this.entityData.set(DATA_WRECKED, wrecked);
    }

    /** この種類の機体1台分の価値。ファイルから。 */
    protected abstract float health();

    /**
     * この種類の機体1台分の耐久値。ファイルが何と言おうと0にはしない。0の機体は最初の擦り傷で破壊され、
     * それは誰も書くつもりのない値だし、機体が消えたという結果から原因を突き止めるのは極めて難しい。
     */
    public float getMaxHealth() {
        return Math.max(this.health(), 1.0F);
    }

    public float getHealth() {
        return this.entityData.get(DATA_HEALTH);
    }

    /**
     * 残り耐久を設定する。0未満にも、機体の最大値超にもしない。
     *
     * <p>上限は下限と同じくらい重要だ。駐機してからファイルの値を下げられた機体は、そうしないと持てる量を
     * 超えた状態でワールドから戻ってくる。
     */
    public void setHealth(float health) {
        this.entityData.set(DATA_HEALTH, Mth.clamp(health, 0.0F, this.getMaxHealth()));
    }

    /** 機体1台分に対する残り耐久の割合（[0,1]）。 */
    public float getHealthFraction() {
        float max = this.getMaxHealth();

        return max <= 0.0F ? 0.0F : Mth.clamp(this.getHealth() / max, 0.0F, 1.0F);
    }

    /**
     * この機体の箱に当たった弾が「装甲に当たった」ことになるか。つまり中へ入らず弾かれ得るか。
     * {@link com.ashvehicles.weapon.Ricochet} 参照。
     *
     * <p>既定は false で、機体には正直な答えだ。主翼はリブの上に張った外皮であり、機関砲弾はどんな角度で
     * 出会っても通り抜ける。装甲であることと厚いことは別だし、弾かれることと被弾に耐えることも別なので、
     * 耐久が高いからといってこれが得られることは無い。
     */
    public boolean isArmoured() {
        return false;
    }

    /**
     * 貫通に対するこの機体の装甲の価値。装甲板が弾を弾くのに本来必要な角度から引く度数で表す。
     *
     * <p>{@link #isArmoured} が true を返す物にしか訊かない。角度自体はここに無いし、必要も無い——箱は機体
     * が寝ている通りに寝ているので、射線へ向けて振った車体は既に浅い当たりになっている。
     */
    public float armour() {
        return 0.0F;
    }

    /**
     * 打撃を受ける。機体のいくつの箱を経由して届いても1回だけ。
     *
     * <p>範囲にダメージを与える物——とりわけ爆発——はレベルへ範囲内の全部を問い合わせ、順に傷つける。そして
     * 機体の箱は全部そのリストに入っている。素通しにすれば、1回の爆風が機体の記述に使われた箱の数だけ命中
     * する。Su-25 なら11回、レオパルトなら7回。それでは機体の頑丈さが「形をどれだけ細かく描いたか」で決まる
     * ことになり、完全に逆だ。
     *
     * <p>だから同じ打撃は1回だけ数える。同一性の基準はダメージソースそのもの。1回の爆発はそれを1つ作って
     * 触れた全部へ渡すが、同じ tick に届いた2発の砲弾はそれぞれ1つずつ持ってくるので両方数えられる。
     *
     * <p>全部が耐久を通り、機体を早期に退場させる物は無い。ボートはクリエイティブの誰かの1発の殴打で即座に
     * 除去され、この2種類はどちらもそれを継承していた。矢1本、振り間違えた一撃、試し撃ち——それで、耐久計に
     * 300点残ったまま機体1台が消えた。処分したい者はレンチを使う。あちらは地面に撒き散らすのではなくポケット
     * へ戻す。
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide || this.isRemoved()) {
            return true;
        }

        // 素手は機体に対して音を出す以外に何もしない。残骸判定より前に置いてあるので、焼け残った船体も
        // 生きている機体と同じように拳の下で鳴る。
        Player fist = knuckles(source);

        if (fist != null) {
            this.clank(fist);

            return true;
        }

        if (this.isInvulnerableTo(source)) {
            return false;
        }

        // 残骸には起こり得ることが既に全部起きている。それ以上進む物は無い。使う耐久が残っていないし、
        // もう一周すれば自分の爆発を2度目に起こすことになる——その爆風が残骸自身の箱へ届けばまさにそうなる。
        if (this.isWrecked()) {
            return true;
        }

        long now = this.level().getGameTime();

        if (source == this.lastHurtSource && now == this.lastHurtTime) {
            return true;
        }

        this.lastHurtSource = source;
        this.lastHurtTime = now;

        // 意図的に markHurt() を呼ばない。あれがやるのは tick の終わりにこの機体の速度を送信させることで、
        // ボートにとってはノックバックだが、この機体にとってはそれは自分の物理が持つ値だ。
        this.setHurtDir(-this.getHurtDir());
        this.setHurtTime(10);
        this.gameEvent(GameEvent.ENTITY_DAMAGE, source.getEntity());

        if (this.wound(amount)) {
            this.destroy(source);
        }

        return true;
    }

    /**
     * その打撃が素手だったか。この大きさの機体は素手を感じない。
     *
     * <p>人ではなく手を見る。問うのはダメージの直接原因であって背後にいる者ではないので、プレイヤー自身の
     * 弾も爆弾も爆風も、それぞれの価値通りに数えられる。それらはどれも間に何かを挟んで届く。
     *
     * <p>空手のみ。何かを持っていれば、それは MOD が既に答えを持っている行為だ——レンチは機体を回収し、
     * それ以外は兵装であり兵装として扱われる。
     */
    @Nullable
    static Player knuckles(DamageSource source) {
        return source.getDirectEntity() instanceof Player player && player.getMainHandItem().isEmpty()
                ? player
                : null;
    }

    /**
     * 数トンの機体を手で叩いた時の音。
     *
     * <p>ゲーム本体の音声を2つ使うので、どのクライアントも既に両方持っており、同梱する物は無い。打撃には
     * 金属ブロックのノック音、そこへ響きを乗せるために金床の音をぐっと下げて高くした物を重ねる。単体ではどち
     * らも足りない——ノックだけでは足音、金床だけでは鍛冶場になる。
     *
     * <p>鳴らす位置は機体ではなく拳。機体の原点は車輪の間に沈んでおり、実際に叩かれたパネルから30m 離れて
     * いることもあるから。
     */
    private void clank(Player player) {
        Vec3 at = player.getEyePosition();
        float jitter = (this.random.nextFloat() - 0.5F) * 0.2F;

        this.level().playSound(null, at.x, at.y, at.z, SoundEvents.METAL_HIT, SoundSource.NEUTRAL,
                KNUCKLE_VOLUME, KNUCKLE_PITCH + jitter);
        this.level().playSound(null, at.x, at.y, at.z, SoundEvents.ANVIL_LAND, SoundSource.NEUTRAL,
                KNUCKLE_VOLUME * 0.3F, KNUCKLE_PITCH + 0.3F + jitter);
    }

    /**
     * どこから来た物であれ耐久を引く。
     *
     * <p>点数はそのまま。兵装ファイルが言う値がここでの値であり、ボートが掛けるような換算は一切無い。機体は
     * 数百点、プレイヤーは20点の価値なので、人からハート2個分を奪う同じ弾が機体からは300点中の4点を奪う。
     * その2つの数値が意味するのはそれで全部。
     *
     * @return これで尽きたなら true
     */
    protected boolean wound(float amount) {
        if (amount <= 0.0F) {
            return false;
        }

        this.setHealth(this.getHealth() - amount);

        return this.getHealth() <= 0.0F;
    }

    /** 撃破された機体は、使える機体を落とすのではなくバラバラになる。 */
    @Override
    protected void destroy(DamageSource source) {
        this.wreck();
    }

    /** これが残す穴の大きさ。 */
    protected abstract float explosionPower();

    /**
     * 終わり。全員を降ろし、爆発させ、機体があった場所に焼け残った船体を立てる。
     *
     * <p>機体は除去しない。破壊された機体がただ存在しなくなるのは、撃墜された事実がまったく伝わらない唯一の
     * 形だ——空も地面も空っぽになる。代わりに残すのは、同じ場所の同じ形で、黒く、動かず、中の金属以外に価値
     * の無い物。レンチを持った誰かがそれを片付けてスクラップを持ち帰る。{@link #salvage} 参照。
     *
     * <p>全損フラグは爆発の後ではなく前に立てる。爆発は起きた瞬間に届く範囲の全部を傷つけ、この機体自身の
     * 当たり判定の箱も届く範囲にある。箱はその打撃を通し、機体は再び破壊され、再び爆発する。先にフラグを
     * 立てれば {@link #hurt} が何もしなくなり、それが以前は除去がやっていた仕事になる。
     */
    protected void wreck() {
        if (!(this.level() instanceof ServerLevel level) || this.isRemoved() || this.isWrecked()) {
            return;
        }

        this.setWrecked(true);
        this.ejectPassengers();
        this.onWrecked();

        // 車輪や履帯にある原点から少しずらす。爆発するのは機体であって、その下の地面ではない。
        double reach = this.reach();
        Vec3 pos = this.position().add(0.0, reach * 0.15, 0.0);
        float power = this.explosionPower();

        // バニラではなく MOD 自前の爆発を使う。バニラのそれは60ブロック以遠の誰にも聞こえない爆発音と、
        // 32ブロックで捨てられる煙を持ち歩く。高高度で分解する機体にとってそれは「全部が誰にも見えない場所
        // で起きる」ことを意味する。兵装が既にそれについてやっていること全部を、機体も同じ理由で欲しがる。
        Effects.blast(level, this, pos, power, Effects.EMBER);
        WreckEffects.destroyed(level, pos, this.getAttitude(), power, reach);
    }

    /**
     * 機体が残骸になった瞬間にサーバーで呼ばれる。種類ごとに止めるべき物——エンジン、レーダー、翼下に吊って
     * いた物——のために。
     */
    protected void onWrecked() {
    }

    // ------------------------------------------------------------------
    // 残骸の片付け
    // ------------------------------------------------------------------

    /**
     * 機体ファイルが言う残骸の価値（鉄インゴット）。書かれていなければ0。訊く側が呼ぶべきなのは
     * {@link #getSalvage()} の方。
     */
    protected abstract int declaredSalvage();

    /**
     * この機体の残骸に残っている金属の量（鉄インゴット）。
     *
     * <p>ファイルが意見を持っていればそれを使い、無ければ耐久値から求める。全機体が既に持っている値の中で
     * 「どれだけの量があるか」に最も近いのが頑丈さなので、誰も2つ目の数値を書かなくても戦車は機体より重く
     * なる。
     */
    public int getSalvage() {
        int declared = this.declaredSalvage();

        return declared > 0 ? declared : Math.max(1, Math.round(this.getMaxHealth() / HEALTH_PER_INGOT));
    }

    /**
     * レンチで残骸を片付け、金属を地面に残す。
     *
     * <p>破壊された機体に対してまだできる唯一のこと。残骸を即座に消さず立たせておく価値がある理由でもある。
     * 使える機体を畳む道具と同じ工具にしてあるのは意図的だ。機体をばらすのは、飛べる物が残っているかどうかに
     * 関わらずレンチの仕事だから。
     *
     * <p>戻ってくるのはスクラップであって機体ではない。機体を取り戻したい者は、失わないようにするしかない。
     */
    public InteractionResult salvage() {
        if (!this.isWrecked()) {
            return InteractionResult.PASS;
        }

        if (this.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }

        // バニラの destroy() が従うのと同じゲームルール。エンティティのドロップを切ったワールドが、これ
        // から黙って金属を得ることのないように。
        if (this.level().getGameRules().getBoolean(GameRules.RULE_DOENTITYDROPS)) {
            int stack = Math.max(Items.IRON_INGOT.getDefaultMaxStackSize(), 1);

            for (int left = this.getSalvage(); left > 0; left -= stack) {
                this.spawnAtLocation(new ItemStack(Items.IRON_INGOT, Math.min(left, stack)));
            }
        }

        this.discard();

        return InteractionResult.CONSUME;
    }

    // ------------------------------------------------------------------
    // 座席
    // ------------------------------------------------------------------

    /**
     * 乗員位置。機体自身の軸（x 右、y 上、z 前）でブロック単位。最初の1つが操縦する席。
     */
    protected abstract List<VehicleChassis.Seat> seats();

    /** 自前の目を持たない乗員のカメラ位置と、追従視点の吊り方。 */
    protected abstract VehicleChassis.CameraMount cameraMount();

    /**
     * 座席が指定していない場合に一人称視点の目が取り付く先。
     *
     * <p>砲塔を持つ物では砲塔。戦車のハッチはそこにあるからだ。砲を横に向ければ視界も車体の側面へ回り込む。
     * 実際のキューポラと同じ。艦と機体には振れる物が無いので船体になる。
     */
    protected VehicleShape.Mount defaultEyeMount() {
        return VehicleShape.Mount.HULL;
    }

    public int getMaxPassengers() {
        return Math.max(this.seats().size(), 1);
    }

    /** その添字の座席。範囲外なら最後の座席。null にはならないので確認不要。 */
    private VehicleChassis.Seat seatAt(int index) {
        List<VehicleChassis.Seat> seats = this.seats();

        return seats.isEmpty()
                ? VehicleChassis.Seat.at(Vec3.ZERO)
                : seats.get(Mth.clamp(index, 0, seats.size() - 1));
    }

    /** エンティティ原点からの座席位置。機体の姿勢を適用する前の値。 */
    public Vec3 getSeatOffset(int index) {
        return this.seatAt(index).pos();
    }

    /**
     * その座席の乗員が世界を見る位置。機体自身の軸で。
     *
     * <p>座席が自前の目を持っていればそれ。無ければ機体唯一の {@code camera.cockpit}——座席が目を持てる
     * 以前は全座席がそれを使っていたので、手を加えていないファイルは今もそう動く。
     */
    public Vec3 getSeatEye(int index) {
        return this.seatAt(index).eyeOr(this.cameraMount().cockpit());
    }

    /** その目の取り付け先。座席自身の答え、無ければ機体の答え。 */
    public VehicleShape.Mount getSeatEyeMount(int index) {
        return this.seatAt(index).mountOr(this.defaultEyeMount());
    }

    /**
     * 搭乗者が世界を見る位置を世界座標で。自分の座席の目を、その目が取り付いている機体の部位が運んだ結果。
     */
    public Vec3 eyeOf(Entity rider, float partialTick) {
        int seat = this.getSeatIndex(rider);

        return this.eyeToWorld(seat, this.getSeatEyeMount(seat), this.getSeatEye(seat), partialTick);
    }

    /**
     * 世界座標での一人称視点の目。振れる物に取り付いた目以外にこれを {@link #toWorld} 以上の物にする要素は
     * 無いので、そういう物を持つ機械だけがこれを上書きする。
     *
     * <p>席番号を渡すのは、何が目を運ぶかが席によって変わりうるからだ。戦車の砲塔は1つなので取り付け先の
     * 種別だけで足りるが、機体の砲座は席ごとに別の物であり、どの砲が運ぶかはその席が受け持つ砲座で決まる。
     */
    protected Vec3 eyeToWorld(int seat, VehicleShape.Mount mount, Vec3 eye, float partialTick) {
        return this.toWorld(eye, partialTick);
    }

    /**
     * 搭乗者がどの座席にいるか。
     *
     * <p>{@link #DATA_SEATS} による割り当て席であって、搭乗者リストの中の位置ではない。以前は同じ物だったが、
     * 今は乗員が降りずに席を移れるので、リスト順はもう誰がどこに座っているかを語らない。割り当てがまだ追い
     * 付いていない搭乗者——サーバーが席を配る前の tick に乗っている者——はリスト順へフォールバックする。
     * どのみち最初の1人が座ったであろう席がそこだ。
     */
    public int getSeatIndex(Entity passenger) {
        UUID id = passenger.getUUID();
        UUID[] seated = this.seatOccupants();

        for (int i = 0; i < seated.length; i++) {
            if (id.equals(seated[i])) {
                return i;
            }
        }

        return Math.max(this.getPassengers().indexOf(passenger), 0);
    }

    /** 残骸には誰も乗り込まない。座席は残っておらず、そこでできることも無い。 */
    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return !this.isWrecked() && this.getPassengers().size() < this.getMaxPassengers();
    }

    /**
     * 最初に乗り込んだ者ではなく、運転席——座席0、操縦装置のある席——にいる者。たいていは同じ乗員だが、誰か
     * が席を移った時点で別れる。操縦装置は席に従うので、座席0に着いた者が機体を得るし、そこを離れた者は誰か
     * が再び座るまで機体をサーバーへ返すことになる。
     */
    @Override
    public LivingEntity getControllingPassenger() {
        UUID[] seated = this.seatOccupants();

        if (seated.length > 0 && seated[0] != null) {
            for (Entity passenger : this.getPassengers()) {
                if (seated[0].equals(passenger.getUUID())) {
                    return passenger instanceof LivingEntity crew ? crew : super.getControllingPassenger();
                }
            }

            // その席が、もう乗っていない者を指している——サーバーがまだ片付けていない古い記述だ。片付く
            // までは誰も運転していない。
            return super.getControllingPassenger();
        }

        // 最初の割り当てが届く前は旧規則へフォールバックする。乗り込んだ機体が、次の tick ではなく乗り
        // 込んだ tick から運転できるように。
        return seated.length == 0 && this.getFirstPassenger() instanceof LivingEntity crew
                ? crew
                : super.getControllingPassenger();
    }

    // ------------------------------------------------------------------
    // 座席間の移動
    // ------------------------------------------------------------------

    /** {@link #seatOccupants} の裏のキャッシュ。同期文字列が変わった時だけ解析し直す。 */
    @Nullable
    private String seatLine;
    private UUID[] seatCache = new UUID[0];

    /**
     * 座席ごとの乗員を座席番号順に。空席は null。両側とも {@link #DATA_SEATS} から直接読み、キャッシュする。
     * 頻繁に呼ぶ側——描画される全座席と、運転者の確認全部——が既に見た文字列を解析し直さないように。
     */
    private UUID[] seatOccupants() {
        String line = this.entityData.get(DATA_SEATS);

        if (!line.equals(this.seatLine)) {
            this.seatLine = line;
            this.seatCache = parseSeats(line);
        }

        return this.seatCache;
    }

    private static UUID[] parseSeats(String line) {
        if (line.isEmpty()) {
            return new UUID[0];
        }

        String[] fields = line.split(",", -1);
        UUID[] seated = new UUID[fields.length];

        for (int i = 0; i < fields.length; i++) {
            if (!fields[i].isEmpty()) {
                try {
                    seated[i] = UUID.fromString(fields[i]);
                } catch (IllegalArgumentException ignored) {
                    // 壊れた項目は単に空席として扱う。ここにクラッシュに値する物は無い。
                }
            }
        }

        return seated;
    }

    /**
     * 現在の座席割りを、機体の定員分の枠として読み出し、リストがまだ名指ししている「もう乗っていない者」を
     * 落とす。サーバー側の作業用の写しで、呼び出し側が変更して {@link #writeSeats} で書き戻す。
     */
    private UUID[] currentSeating() {
        int max = this.getMaxPassengers();
        UUID[] seated = new UUID[max];
        UUID[] stored = this.seatOccupants();

        for (int i = 0; i < max && i < stored.length; i++) {
            seated[i] = stored[i];
        }

        // その後降りた者の ID を追い出し、その席が空席として読まれるようにする。
        for (int i = 0; i < max; i++) {
            if (seated[i] != null && !this.isAboard(seated[i])) {
                seated[i] = null;
            }
        }

        return seated;
    }

    private boolean isAboard(UUID id) {
        for (Entity passenger : this.getPassengers()) {
            if (id.equals(passenger.getUUID())) {
                return true;
            }
        }

        return false;
    }

    private void writeSeats(UUID[] seated) {
        StringBuilder line = new StringBuilder();

        for (int i = 0; i < seated.length; i++) {
            if (i > 0) {
                line.append(',');
            }

            if (seated[i] != null) {
                line.append(seated[i]);
            }
        }

        this.entityData.set(DATA_SEATS, line.toString());
    }

    /** 乗り込んできた者を最も若い空席に座らせる。最初の1人にとってそれは運転席。 */
    private void seatBoarding(Entity passenger) {
        UUID[] seated = this.currentSeating();
        UUID id = passenger.getUUID();

        // 既に着席済み——新規搭乗ではなく並べ替え——なら何もしなくてよい。
        for (UUID occupant : seated) {
            if (id.equals(occupant)) {
                return;
            }
        }

        for (int i = 0; i < seated.length; i++) {
            if (seated[i] == null) {
                seated[i] = id;
                this.writeSeats(seated);

                return;
            }
        }
    }

    /** 降りる者の席を空ける。次の者のために。 */
    private void seatLeaving(Entity passenger) {
        UUID[] seated = this.currentSeating();
        UUID id = passenger.getUUID();
        boolean changed = false;

        for (int i = 0; i < seated.length; i++) {
            if (id.equals(seated[i])) {
                seated[i] = null;
                changed = true;
            }
        }

        if (changed) {
            this.writeSeats(seated);
        }
    }

    /**
     * 搭乗者を次の空席へ移す。最後の席からは最初へ回る。乗員が降りずに配置を変える方法だ。1人だけ乗っている
     * なら運転席を含めて全席を1つずつ巡れるし、複数人なら誰かが空けるまで各自が自分の席に留まる。使用中の席
     * をその乗員の下から奪うことは決してない——空きが無い状態で押しても何も起きない。
     *
     * <p>サーバー側、座席切替キーから。実際に移動したかを返す。
     */
    public boolean switchToNextSeat(Entity passenger) {
        if (this.level().isClientSide || !this.hasPassenger(passenger)) {
            return false;
        }

        UUID[] seated = this.currentSeating();
        int max = seated.length;

        if (max < 2) {
            return false;
        }

        int from = this.getSeatIndex(passenger);

        for (int step = 1; step <= max; step++) {
            int to = (from + step) % max;

            if (seated[to] == null) {
                seated[from] = null;
                seated[to] = passenger.getUUID();
                this.writeSeats(seated);

                return true;
            }
        }

        return false;
    }

    @Override
    protected void addPassenger(Entity passenger) {
        super.addPassenger(passenger);

        if (!this.level().isClientSide) {
            this.seatBoarding(passenger);
        }
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);

        if (!this.level().isClientSide) {
            this.seatLeaving(passenger);
        }
    }

    /**
     * ヨーとピッチだけからではなく機体自身の軸から組む。だから座席は翼と一緒にバンクし、車体と一緒に傾く。
     * オイラー角でオフセットを回すと、ロールした機体の中で乗員だけが直立し、モデルが描くコックピットから
     * 浮いてしまう。
     */
    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
        return Attitude.toWorld(this.getAttitude(), this.getSeatOffset(this.getSeatIndex(passenger)));
    }

    /**
     * 機体自身の軸（x 右、y 上、z 前）で書かれたオフセットを、2つの tick の間の任意の瞬間の世界座標へ変換
     * する。座席・砲口・一人称視点の目に使うので、そのどれもが機体の動きに乗る。
     */
    public Vec3 toWorld(Vec3 offset, float partialTick) {
        return this.getPosition(partialTick).add(Attitude.toWorld(this.getAttitude(partialTick), offset));
    }

    // ------------------------------------------------------------------
    // 機体を構成する箱
    // ------------------------------------------------------------------

    /**
     * 箱を組む。コンストラクタからだけ呼ばれる。レベルはエンティティが追加された瞬間にそのパーツを記録し、
     * その時点でパーツを持たない物は「持たない物」として覚えられ、後から与えることはできないから。
     */
    protected final void buildParts() {
        List<VehicleShape.Box> shape = this.getShape().boxes();
        List<VehiclePart> extra = this.extraParts();
        this.parts = new VehiclePart[shape.size() + extra.size()];

        for (int i = 0; i < shape.size(); i++) {
            // ファイルの何番目の箱かを伝える。機体が毎tick正しい箱を正しい場所へ置けるように。以後その
            // パーツが判定される相手は、配置に使った Hitbox であって、運搬用の直立した箱では決してない。
            this.parts[i] = VehiclePart.airframe(this, shape.get(i).name(), i);
        }

        for (int i = 0; i < extra.size(); i++) {
            this.parts[shape.size() + i] = extra.get(i);
        }

        // エンティティカウンタが配った番号をそのまま使わず、機体自身の ID から番号を振る。どの箱がどれか
        // について両側が一致するように。setId 参照。
        this.setId(this.getId());
    }

    /**
     * ファイルに列挙されていない箱。機体のパイロンで、機体の一部ではなく機体上の「場所」だ。他の物は持た
     * ない。
     */
    protected List<VehiclePart> extraParts() {
        return List.of();
    }

    /**
     * 機体の箱に、機体自身の ID から派生した番号を振る。両側が同じ箱を同じ名前で呼べるように。
     *
     * <p>箱は ID を持つエンティティで、ID は各側が自分で持つカウンタから出る。サーバーが機体を作ると、その
     * 箱が次の数個の番号を取る。クライアントはその後で機体の ID を伝えられ、機体だけを黙って番号付け直す
     * ——箱は自分のカウンタが到達していた番号のまま残る。結果、両側は全ての箱について食い違う。
     *
     * <p>プレイヤーが箱をクリックするまで誰も気付かない。被弾はサーバーが自分の箱に対して決めるので境界を
     * 越えないが、クリックは「クライアントが当たった物を名指ししてサーバーに処理を頼む」行為だ。サーバーの
     * 知らない番号で名指しされたクリックは何にも届かず、搭乗は単に起きない。
     *
     * <p>各箱の ID を機体の ID から導けば、両側は構造的に一致する。バニラがエンダードラゴンに対して同じ理由
     * でやっていることでもある。
     */
    @Override
    public void setId(int id) {
        super.setId(id);

        // エンティティ自身のコンストラクタから1回、番号を振る箱がまだ無い時点で呼ばれる。
        if (this.parts == null) {
            return;
        }

        for (int i = 0; i < this.parts.length; i++) {
            this.parts[i].setId(id + i + 1);
        }
    }

    @Override
    public boolean isMultipartEntity() {
        return this.parts.length > 0;
    }

    @Override
    public VehiclePart[] getParts() {
        return this.parts;
    }

    /** 箱の中心の世界座標。それを運んでいる物が置いた場所。 */
    protected abstract Vec3 boxCentre(VehicleShape.Box box);

    /** 箱が世界で取っている回転。機体内での自分の角度も含む。 */
    protected abstract Quaternionf boxRotation(VehicleShape.Box box);

    /**
     * 前回の配置時点で、機体の全ての箱が収まっていた世界の領域。
     *
     * <p>全部を囲む箱1つ。1回の判定で「この機体を問い合わせる価値があるか」を決めるため。80個の箱で記述され
     * た空母の近くを動く物は、80個の何かに触れずに「近くにいない」と分かるべきで、その手段がこれ。
     *
     * <p>機体が一度も箱を配置していなければ null。
     */
    @Nullable
    public AABB placedBounds() {
        return this.placed;
    }

    /**
     * 機体の上に立っている物を、前回の箱配置以降に機体が進んだ距離と回った角度の分だけ一緒に運ぶ。
     *
     * <p>箱を配置した処理が、その直後に呼ぶ。箱が今の機体位置に来ていなければ誰かがその上に立っていることを
     * 見つけられないし、この tick の移動を終えていなければ運ぶべき距離が存在しない。
     *
     * <p>別の場所へそのまま置かれた機体——生成、読み込み、テレポート——はその tick に誰も運ばない。その距離は
     * 機体が進んだ距離ではないから。
     */
    protected final void carryStanders() {
        Vec3 now = this.position();
        float heading = Attitude.heading(this.attitude);
        Vec3 before = this.carriedFrom;
        float pointed = this.carriedHeading;

        this.carriedFrom = now;
        this.carriedHeading = heading;

        if (before == null) {
            return;
        }

        Vec3 shift = now.subtract(before);
        float turn = Mth.wrapDegrees(heading - pointed);

        if (shift.lengthSqr() > CARRY_LIMIT * CARRY_LIMIT) {
            return;
        }

        if (shift.lengthSqr() > 1.0E-12 || Math.abs(turn) > 1.0E-4F) {
            Hitboxes.carry(this, before, shift, turn);
        }
    }

    /**
     * それを箱の配置の最後に1回だけ計算する。配置した処理が呼ぶ。全ての箱が正しい場所にあると分かっている
     * 唯一の瞬間がそこだから。
     */
    protected final void notePlacement() {
        AABB union = null;

        for (VehiclePart part : this.parts) {
            Hitbox box = part.hitbox();

            if (box != null) {
                union = union == null ? box.reach() : union.minmax(box.reach());
            }
        }

        this.placed = union;
    }

    /**
     * 機体の箱1つを、世界で実際に寝ている姿で。機体が運んだ位置に、ファイルが与える大きさで、寝ている角度
     * のまま。
     *
     * <p>直立した箱ではないし、直立した箱から組んでもいない。{@link Hitbox} 参照。あれがこの MOD 自前の形状
     * であり、この機体に関する物が判定される唯一の相手。
     */
    protected Hitbox hitbox(VehicleShape.Box box) {
        return new Hitbox(this.boxCentre(box), box.size(), this.boxRotation(box));
    }

    /**
     * 機体の本当の形状が今いる場所に収まる余地があるか。余裕を差し引いて判定する。
     *
     * <p>設置時に使う。箱は世界に対して止まるので、主翼や履帯が斜面に埋まった状態で置かれた機体はそこで
     * 嵌まって動けなくなる。中央だけでなく形状全体が空いている必要がある。
     *
     * @param margin 各箱が世界とどれだけ重なっても「空いている」と数えるか。翼端が斜面へごくわずかに食い
     *               込んでいるだけで設置不能にならないようにする値
     */
    public boolean hasRoomHere(double margin) {
        return this.hasRoomHere(margin, Hitboxes.UNDERSIDE_NONE);
    }

    /**
     * 同じ処理に、「これ以下のブロックは機体が埋まっている物ではなく床である」高さを与えた版。
     *
     * <p>地面に立っている、あるいは降下してくる機体に対して「世界が閉じてきたか」を問うため。車輪より下の
     * 物はその問いの答えにならない。{@code Hitboxes.clearOfBlocks} 参照。
     *
     * @param underside これ以下のブロックを床と見なす高さ。全部を数えるなら
     *                  {@link Hitboxes#UNDERSIDE_NONE}
     */
    public boolean hasRoomHere(double margin, double underside) {
        List<VehicleShape.Box> shape = this.getShape().boxes();

        if (shape.isEmpty()) {
            return this.level().noCollision(this, this.getBoundingBox().deflate(margin));
        }

        for (VehicleShape.Box box : shape) {
            // 実際に寝ている姿の箱を、実際にあるブロックに対して判定する。斜面に置かれた機体を囲む直立
            // した箱は、機体が決して触れない斜面をかなり含んでしまう。
            if (!Hitboxes.clearOfBlocks(this, this.hitbox(box), margin, underside)) {
                return false;
            }
        }

        return true;
    }

    /**
     * レンダラーが可視判定に使う箱。機体が衝突に使う箱とは意図的に別物。
     *
     * <p>素の当たり判定は意図的に小さく保ってある——胴体や車体だけを覆うので、はみ出した翼端や砲身が機体を
     * 設置不能にしたり戸口ごとに引っ掛かったりしない。衝突には正しい大きさだが、描画判定にはまるで正しくな
     * い。6m の箱が画面から外れたばかりの15m の機体はまだ十分に画面上にいるのに、消えてしまう。だからカリング
     * には実際に占めている形状を渡す。
     */
    @Override
    public AABB getBoundingBoxForCulling() {
        double reach = this.shapeReach();

        return reach > 0.0 ? this.getBoundingBox().inflate(reach) : this.getBoundingBox();
    }

    /**
     * 機体が中心からどれだけ届くか（ブロック）。
     *
     * <p>実際に構成されている形状の最長差し渡しの半分であって、素の直方体の半分ではない。15m の機体にとって
     * あれは間違った場所で測った小屋だ。これは「爆発の尺度ではなく機体の尺度で描かれる物」の大きさを決める。
     * 燃える機体に沿って炎をどこまで並べるか、破片がどこまで飛ぶか。
     *
     * <p>箱を1つも持たない機体は素の直方体へフォールバックする。大きさについて誰かが言ったのはそれだけだから。
     */
    public double reach() {
        double reach = this.shapeReach();

        return reach > 0.0 ? reach : Math.max(this.getBbWidth(), this.getBbHeight()) * 0.5;
    }

    /** 当たり判定の箱だけから求めた同じ値。箱を持たない機体では0。 */
    private double shapeReach() {
        double reach = 0.0;

        for (VehicleShape.Box box : this.getShape().boxes()) {
            for (int axis = 0; axis < 3; axis++) {
                double corner = Math.abs(component(box.offset(), axis)) + component(box.size(), axis) / 2.0;
                reach = Math.max(reach, corner);
            }
        }

        return reach;
    }

    protected static double component(Vec3 v, int axis) {
        return axis == 0 ? v.x : axis == 1 ? v.y : v.z;
    }

    /**
     * 機体は自分自身と衝突しない。自分の箱は他の全員にとって固体であり、それが箱の存在意義だが、機体にとって
     * それは単に「自分がいる場所」だ。これが無いと機体は毎tick自分の主翼を押しのけて進むことになり、決して
     * 速度が乗らない。
     */
    @Override
    public boolean canCollideWith(Entity other) {
        return !(other instanceof VehiclePart part && part.getParent() == this) && super.canCollideWith(other);
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /**
     * 弾庫と一緒に消えるはずだった積荷を、1度だけ地面へ出す。
     *
     * <p>バニラの {@code destroy} が従うのと同じゲームルールの下で行う。エンティティのドロップを切った
     * ワールドが、これから機体の積荷を得ることのないように。
     */
    private void spillOldHold() {
        List<ItemStack> cargo = this.spilledHold;

        if (cargo == null) {
            return;
        }

        this.spilledHold = null;

        if (!this.level().getGameRules().getBoolean(GameRules.RULE_DOENTITYDROPS)) {
            return;
        }

        for (ItemStack stack : cargo) {
            this.spawnAtLocation(stack);
        }
    }

    /**
     * 弾庫を持っていたバージョンのセーブデータから積荷を拾い出す。書き戻すことは二度と無いので、次の tick
     * で地面へ出すためだけに読む。この鍵を持たない機械——このバージョン以降に保存された全部——では何もしない。
     */
    private void readOldHold(CompoundTag tag) {
        ListTag list = tag.getList("Hold", Tag.TAG_COMPOUND);

        if (list.isEmpty()) {
            return;
        }

        List<ItemStack> cargo = new ArrayList<>(list.size());

        for (int at = 0; at < list.size(); at++) {
            ItemStack.parse(this.registryAccess(), list.getCompound(at)).ifPresent(cargo::add);
        }

        this.spilledHold = cargo;
    }

    /**
     * 他に何を保持していようと全機体が書き出す唯一の物であり、ワールドを閉じられても生き延びねばならない
     * 唯一の物。野原に残された残骸は明日も残骸だ。
     *
     * <p>{@link Entity} のように抽象のままにせずここで実装してあるので、各機体の実装はまずこちらを呼ぶ必要
     * がある。
     */
    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.setWrecked(tag.getBoolean("Wrecked"));
        this.wreckAge = tag.getInt("WreckAge");
        this.readOldHold(tag);
        // 燃料システムが存在する前にワールドへ書き出された機械には読む値が無いので、空ではなく満タンで戻る。
        // 耐久と同じ判断だ。空で戻せば、更新した瞬間に世界中の機械が一斉に動かなくなる。
        this.setFuel(tag.contains("Fuel") ? tag.getFloat("Fuel") : this.fuelSetup().capacity());
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putBoolean("Wrecked", this.isWrecked());
        tag.putInt("WreckAge", this.wreckAge);
        tag.putFloat("Fuel", this.getFuel());
    }

    @Override
    protected Item getDropItem() {
        return BuiltInRegistries.ITEM.get(this.getVehicleId());
    }

    @Override
    public ItemStack getPickResult() {
        return new ItemStack(this.getDropItem());
    }
}
