package com.ashvehicles.entity;

import javax.annotation.Nullable;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.AshVehicles;
import com.ashvehicles.network.HitReportPayload;
import com.ashvehicles.particle.TintedParticleOption;
import com.ashvehicles.registry.ModParticles;
import com.ashvehicles.vehicle.Hitbox;
import com.ashvehicles.weapon.Impact;
import com.ashvehicles.weapon.Ricochet;
import com.ashvehicles.weapon.WeaponDefinition;
import com.ashvehicles.weapon.WeaponEffects;
import com.ashvehicles.weapon.WeaponMounts;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import net.neoforged.neoforge.event.EventHooks;

/**
 * 機体の兵装が送り出す物すべて。機関砲弾、ロケット、ミサイル。
 *
 * <p>共通しているのは「飛び方」以外の全部だ。どれも自分を撃った兵装を知っており、その諸元を毎tick名前で
 * 引くので、調整し直したファイルは既に空にある物にも効く。どれもパイロットの持ち物なので、倒した相手は
 * その人の戦果になる。どれも撃った機体を——パーツも搭乗者も含めて——すり抜ける。翼と重なった状態で出て
 * いくから。そしてどれも着弾点で同じことをする。
 *
 * <p>移動は点ではなく線として判定する。この速度域では、そうしないと1tick分の飛行より薄い物を綺麗に
 * すり抜けてしまう。
 *
 * <p>飛び方はサブクラスが与える。{@link #steer()} がその1tick分で、移動の前に呼ばれ、そこで
 * deltaMovement に残された物が次の行き先になる。
 */
public abstract class VehicleProjectile extends Projectile implements IEntityWithComplexSpawn {
    public static final ResourceKey<DamageType> DAMAGE_TYPE =
            ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "bullet"));

    /** 自分を撃った兵装。クライアントが何を描き、どう振る舞うかを知るため。 */
    private static final EntityDataAccessor<String> DATA_WEAPON =
            SynchedEntityData.defineId(VehicleProjectile.class, EntityDataSerializers.STRING);

    /**
     * 発射時の速度。完全な精度で。
     *
     * <p>同期データとして送るのは、エンティティの速度を運ぶパケットのどれもこの値を運べないから。生成
     * パケットも運動補正パケットも、各軸を1tickあたり3.9ブロックに丸める。それは雪玉には速いが、機関砲の
     * 砲口から出る速度の1/10だ。だからクライアントは3.9と告げられ、その分だけ這って進み、次の位置パケット
     * で残り40ブロック引きずられる——それが「空を跳び回る弾」の正体で、毎秒20回起きる。
     *
     * <p>真値を伝えれば、クライアントは自分で正しい速度で弾を飛ばし、サーバーの位置は「既にそこにいる弾」
     * を見つけることになる。{@link #lerpTo} 参照。
     */
    private static final EntityDataAccessor<Vector3f> DATA_LAUNCH =
            SynchedEntityData.defineId(VehicleProjectile.class, EntityDataSerializers.VECTOR3);

    /**
     * サーバーとクライアントの「弾の位置」の食い違いが、クライアントを動かすまでに許される量（ブロック）。
     *
     * <p>通常は補正すべき物が無い。両側が同じ数値から同じ弾を飛ばし、同じ場所に着く。許容差が買っているの
     * は「計算が完全に一致している必要が無い」ことだ。1tick40ブロックの弾が1m ずれていても誰にも見えないが、
     * そこへ瞬間移動させれば見える。
     */
    private static final double CORRECTION = 2.0;

    /**
     * 残っている補正量のうち毎tick処理する割合。
     *
     * <p>跳ばずに少しずつ消化する。補正がたいてい言っているのは「クライアントの弾がサーバーの弾より1〜2
     * tick分後ろにいる」ことで、それは誰の失敗でもない——パケットがそれだけかかっただけだ。1回の移動で
     * 正せば50ブロックのテレポートを毎秒数回やることになり、それが「空を跳び回る弾」の見た目そのものになる。
     * 続く数tickへ分散させれば0.25秒間だけ速度が数%変わるだけで、誰にも見えないし、どちらにせよ2つの複製
     * は同じ場所に行き着く。
     */
    private static final double CORRECTION_RATE = 0.25;

    /**
     * 食い違いのうち「何かが間違っている」ではなく「パケットが遅れた」せいにする量。弾自身の飛行 tick 数で。
     *
     * <p>上のブロック単位の値だけではこれに答えられない。位置パケットは届く1tick以上前に書かれ、その間も
     * 弾は飛び続けるので、報告されているように見える差の大半はその遅延だ。1tick30ブロックなら1tick分で
     * 30ブロックになり、ブロックだけで測る許容差はそれを「不一致」と呼んでパケットが届くたび弾を前へ引っ張
     * る。飛行 tick 数で測ればそれはあるがままの物——「正しい場所にいるが少し遅れている弾」——として扱われ、
     * 何もしない。この設計が求めているのはまさにそれだ。両側が同じ弾を飛ばし、サーバーの位置はその答え合わ
     * せであってクライアントが描く物ではない。
     */
    private static final double CORRECTION_TICKS = 2.0;

    /**
     * パケット遅延では説明できないほど大きい差（ブロック）。これを超えたらクライアントは単に別の場所にいる。
     *
     * <p>別の方向へ曲がったミサイルや、クライアント側の複製が目標を見失ったミサイルは、経路へ緩やかに戻す
     * のではなく実際の位置へ置いてほしい。その大きさの差を穏やかに消化すれば、1秒間横向きに飛ぶミサイルを
     * 描くことになる。
     *
     * <p>遅い弾が超える必要の無い下限だが、誘導弾は超える。報告された（わずかに遅れた）位置しか知らない
     * 目標を追うミサイルは、毎tick両側でわずかに違う操舵をする。その普通の揺らぎ——誰も実際に別方向へ曲がって
     * いない——は、弾が速いほど、そして本来引き戻すはずのパケットの間隔が長いほど大きな距離を覆う
     * （{@code ModEntities} の {@code ROCKET} の {@code updateInterval} 参照）。{@link #lerpTo} の許容差と
     * 同じ理由で弾自身の速度に比例させてあるので、この設計を最初に調整した速度域なら固定値の十分内側だった
     * 弾は、数倍の速度で飛んでも同じように読まれる。
     */
    private static final double LOST_TICKS = 16.0;

    private static final double LOST_FLOOR = 96.0;

    /**
     * 弾を出た砲口へ戻すために運んでよい距離（ブロック）。
     *
     * <p>調整用の値ではなく正気度チェック。{@link #anchorToMuzzle} 参照。そこでの距離は機体が1往復分に
     * 進んだ量で、機体なら数十ブロック、戦車なら数ブロック。これを超えるのは遅延ではない——テレポートされた
     * 機体か、発射からずっと後にクライアントへ紐づけられた弾だ。400m 放り投げて合流させるより、サーバーが
     * 置いた場所に残す方がよい。
     */
    private static final double ANCHOR_LIMIT = 256.0;

    /**
     * 砲口へ戻してよい弾の最大年齢（tick）。
     *
     * <p>既に機体を見ている者は、追跡機構の次の巡回——弾が出た次の tick の先頭。{@code ServerLevel.tick}
     * を見ると chunk ソースはエンティティより先に走る——で発射物と紐づけられる。だからこの処理の対象となる
     * クライアントは年齢0か1の弾に出会う。弾が飛んで<em>近づいてきた</em>クライアントは、その時点で紐づけ
     * られる。そちらにとって砲口での機体の位置は、今の弾の位置と何の関係も無い大昔の話だ。2tickは「本題の
     * 場合＋1tickの余裕」であり、もう一方の場合が持ち込む数十tickには遠く及ばない。
     */
    private static final int ANCHOR_AGE = 2;

    /**
     * 砲口オフセットを毎tick消化する割合（残量に対する）。
     *
     * <p>そもそも消化するのは、このオフセットが飛行の片端でしか要らないから。砲を離れる瞬間はそれが要点だ
     * が（{@link #anchorToMuzzle} 参照）、最後まで持ち続ければサーバーが飛ばしている弾より前に出てしまう。
     * そして爆発の位置を決めるのはサーバーの弾だ。前へ持ったまま落ちた爆弾は、自分の爆発よりかなり手前で
     * 消えることになる。発射後1秒ほどで解消させれば、弾は乗員が撃った場所から始まり実際の着弾点で終わり、
     * どちらの端も間違っていない。
     */
    private static final double ANCHOR_RATE = 0.15;

    /**
     * その消化に、弾自身の1歩のうち最大どれだけを使ってよいか（1歩に対する割合）。
     *
     * <p>上限が無いと、速い弾の最初の tick でオフセットの大半を一度に払い、その分だけ進む距離が減る。砲身
     * から出る途中で1tick止まる曳光弾は、払っているオフセットより見た目が悪い。1歩の1/5なら、必要な間だけ
     * 2割遅く飛ぶ弾になるだけで、曳光弾でそれに気付ける者はいない。
     */
    private static final double ANCHOR_MOST = 0.2;

    /**
     * 1tickあたり最低これだけは消化する量（ブロック）。残量の割合だけでは決して到達しないので、最後の分を
     * 終わらせてオフセットを完全に捨てさせるのがこれ。
     */
    private static final double ANCHOR_LEAST = 0.05;

    /**
     * 弾を判定する前に目標の箱をどれだけ膨らませるか（ブロック）。
     *
     * <p>こちらで決めた値ではない。{@code ProjectileUtil.getEntityHitResult} が切り出し前に全箱を膨らませ
     * る量であり、{@link #canHitEntity} の傾いた箱の判定も同じ値を使わなければ縁で食い違う——ゲームが数えよ
     * うとしていた掠りが、少し小さい主翼を測った判定に弾かれる。Minecraft の更新でバニラ側の値が変われば、
     * これも一緒に変える。
     */
    private static final double PICK_INFLATION = 0.3;

    /** 弾を描く距離（ブロック）。どれの追跡距離よりも遠い値。 */
    private static final double RENDER_RANGE = 768.0;

    /**
     * 1歩が跨ぐ chunk を何個まで歩くか。{@link #spanIsLoaded} の打ち切り。
     *
     * <p>最も速い物でも1tickに40ブロック少々——斜めでも 4 chunk——なので、これは判定ではなく保険。
     */
    private static final int SPAN_CHUNKS = 16;
    /**
     * どれだけ速くても、1tick分の飛行に出す粒の上限。
     *
     * <p>この値が航跡を途切れさせる。1tickに68ブロック進むミサイルに8粒しか許さなければ、粒の間隔は8ブロック
     * 開き、粒はそれを埋められる大きさではない——出来上がるのは煙ではなく点線だ。ここで払うのは1発あたりの
     * 粒数（この値×{@link com.ashvehicles.client.particle.SmokeParticle#CONTRAIL} の寿命）だけなので、
     * 飛翔中のミサイルが数発という前提では、途切れない航跡の方が明らかに安い。
     */
    private static final int MAX_PUFFS = 20;
    /** 航跡の粒に残すミサイル速度の割合。 */
    private static final double TRAIL_DRIFT = 0.06;
    /** 航跡の粒を飛行経路からどれだけ外して置くか（ブロック）。 */
    private static final double TRAIL_SCATTER = 0.10;
    /**
     * 粒の大きさを粒の間隔の何倍にするか。{@link #spawnTrail} の {@code spread} 参照。
     *
     * <p>粒は半径で描かれるので、間隔と同じ幅を覆うには半径が間隔の半分要る。1.0 が「置いた瞬間に隣と接する」
     * で、それより上は重なる。重ねるのが正しい——煙は連続した柱であって、数珠つなぎの球ではない。
     */
    private static final double TRAIL_SPREAD = 2.0;
    /** ただし限度はある。近距離で発射した直後の物が視界を埋めないための下限と上限。 */
    private static final double TRAIL_SPREAD_LEAST = 1.0;
    private static final double TRAIL_SPREAD_MOST = 6.0;
    /** 一時的。上のトレースが兵装の飛行の何 tick 分を対象にするか。 */
    private static final int TRACE_TICKS = 8;
    /** モーター燃焼中、1tickあたりの噴煙の粒数。 */
    private static final int EXHAUST_PUFFS = 8;
    /** 噴煙がミサイルの後方どこまで届くか。1tick分の飛行に対する割合で。 */
    private static final double EXHAUST_REACH = 0.45;
    /** 噴煙がノズルから後方へ吹き出される強さ（1tickあたりブロック）。 */
    private static final double EXHAUST_BLOW = 0.22;
    /** そしてノズルからどれだけ外して置くか（ブロック）。 */
    private static final double EXHAUST_SCATTER = 0.08;

    /**
     * これを撃った物。唯一当ててはいけない相手。機体型ではなく素の Entity として持つ。翼から出ようと砲塔
     * から出ようと弾は同じ弾で、これに投げられる問いは「弾の前にある物が弾の後ろにある物と同じか」だけ
     * だから。
     */
    @Nullable
    private Entity firedFrom;
    private int firedFromId = -1;
    /**
     * これを撃った機体が、撃った瞬間に立っていた位置。
     *
     * <p>生成データと一緒に送られ、用途は1つだけ——その砲口を別の場所に描いている側で、弾を出た砲口へ戻す
     * こと。{@link #anchorToMuzzle} 参照。
     */
    private Vec3 firedFromAt = Vec3.ZERO;
    /** 発射からの tick 数。寿命はこれに対して測られる。 */
    protected int age;
    /**
     * 装甲がこの弾を弾いた回数。サーバー専用。弾に残っているエネルギーを表す値で、クライアント側で訊く物は
     * 無い。{@link Ricochet} 参照。
     */
    private int deflections;
    /** この弾が自分の前方に開いたまま保持している地面。あれば。{@link WeaponChunkLoader} 参照。 */
    private final WeaponChunkLoader.Hold hold = new WeaponChunkLoader.Hold();
    /**
     * 弾が直前に踏んだ1歩。今それに沿って描かれている歩でもある。
     *
     * <p>保持しているのは、弾が「前の位置」と「今の位置」の間のどこかに描かれ、その2点を結ぶ歩が既に踏まれ
     * た歩だから。代わりに<em>これから</em>踏む歩に沿ってモデルを向けると、機首が胴体より1tick先に行き、
     * 曲がる物ではそれが揺れとして見える。{@link #travel} 参照。
     */
    private Vec3 lastTravel = Vec3.ZERO;

    /** 直近の補正で消化し残している量（ブロック）。クライアント専用。{@link #settle} 参照。 */
    private Vec3 owed = Vec3.ZERO;

    /**
     * この複製がサーバー側の複製よりどれだけ前を飛んでいるか（ブロック）。クライアント専用。
     *
     * <p>砲口で一度だけ乗せ、その後1秒ほどかけて外していく。値は「<em>この</em>側が描いている機体」と
     * 「引き金が引かれた時点でサーバーが持っていた機体」のずれだ。弾がこれで運ばれたい理由は
     * {@link #anchorToMuzzle}、長く運ばれたくない理由は {@link #mergeAnchor}、そしてサーバーの言い分を
     * 測る前にこれを外す必要があるのは {@link #lerpTo}。
     */
    private Vec3 anchor = Vec3.ZERO;

    protected VehicleProjectile(EntityType<? extends VehicleProjectile> type, Level level) {
        super(type, level);
    }

    /**
     * @param weapon これが出てきた兵装
     * @param vehicle 発射元。つまり当ててはいけない相手
     * @param crew 引き金を引いた者。いれば
     */
    public void setup(ResourceLocation weapon, Entity vehicle, @Nullable Entity crew) {
        this.entityData.set(DATA_WEAPON, weapon.toString());
        this.firedFrom = vehicle;
        this.firedFromId = vehicle.getId();
        this.firedFromAt = vehicle.position();
        this.setOwner(crew);
    }

    /**
     * 最後に求めた兵装名と諸元、そしてそれを何から求めたか。
     *
     * <p>どちらも毎回引き直さず保持する。フィールド1つずつの価値はある。名前は通信上は文字列で届くので、
     * 問い合わせのたびに1文字ずつ解析・検証していたし、その先の諸元は別の名前をハッシュしてマップを引いて
     * いた——そして弾は飛行の毎tickと描画の毎フレームに両方を訊く。機関砲は毎秒20発を空へ送り、各発が数秒
     * 生きる。変わりようのない答えのために毎秒数千回の解析をしていたことになる。
     *
     * <p>変わり得るタイミングで捨てる。名前は同期文字列が設定された時（{@link #onSyncedDataUpdated}
     * 参照）、諸元はリロードや同期がファイルを差し替えた時。
     */
    @Nullable
    private ResourceLocation weaponId;
    @Nullable
    private WeaponDefinition weapon;
    private String weaponIdFrom = "";
    private int weaponVersion = -1;

    public ResourceLocation getWeaponId() {
        String raw = this.entityData.get(DATA_WEAPON);
        ResourceLocation cached = this.weaponId;

        if (cached != null && this.weaponIdFrom.equals(raw)) {
            return cached;
        }

        ResourceLocation id = ResourceLocation.tryParse(raw);

        if (id == null) {
            id = ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "unknown");
        }

        this.weaponId = id;
        this.weaponIdFrom = raw;
        this.weapon = null;

        return id;
    }

    public WeaponDefinition getWeapon() {
        ResourceLocation id = this.getWeaponId();
        WeaponDefinition current = this.weapon;

        if (current == null || this.weaponVersion != Definitions.version()) {
            current = Definitions.weapon(id);
            this.weapon = current;
            this.weaponVersion = Definitions.version();
        }

        return current;
    }

    public WeaponDefinition.Projectile getRound() {
        return this.getWeapon().projectile();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_WEAPON, "");
        builder.define(DATA_LAUNCH, new Vector3f());
    }

    /**
     * 発射時の速度で弾を送り出す。
     *
     * <p>移動量を直接設定せずこちらを使うこと。弾の本当の速度をクライアントへ伝える唯一の手段だから。
     * {@link #DATA_LAUNCH} 参照。
     */
    public void launch(Vec3 velocity) {
        this.setDeltaMovement(velocity);
        this.entityData.set(DATA_LAUNCH, velocity.toVector3f());
        this.launched(velocity);
    }

    /**
     * 発射速度が判明した瞬間に、それを今知った側で呼ばれる。サーバーでは発射時、クライアントでは生成データ
     * で値が届いた時。
     *
     * <p>コンストラクタではなくフックなのは、コンストラクタが「読む物が存在するずっと前」に走るから。ゲーム
     * はまずエンティティを作り、それが何であるかは後から伝える。クライアントではそれをパケットから行う。
     * サブクラスが「送り出された方向」から求める必要のある物はここに置き、そうすれば両側で同一に求まる。
     */
    protected void launched(Vec3 velocity) {
    }

    /** クライアントは弾の他の生成データと一緒に、ここで本当の速度を知る。 */
    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);

        if (DATA_LAUNCH.equals(key) && this.level().isClientSide) {
            Vector3f launch = this.entityData.get(DATA_LAUNCH);

            this.setDeltaMovement(launch.x(), launch.y(), launch.z());
            // 最初のフレームが、何も無い方向ではなく飛行方向に沿って描くように。
            this.lastTravel = this.getDeltaMovement();
            this.launched(this.lastTravel);
        }
    }

    /**
     * クライアントが初めて知らされた時点で、弾が既に何 tick 生きているか。
     *
     * <p>年齢は弾の飛行のあらゆる部分が測られる基準だ——モーターが切れてどれだけ経ったか、推力がどこまで
     * 立ち上がったか、この tick の重さはいくつか——そして両側は各値を教えられるのではなく自分で求める。それ
     * が成立するのは両者が年齢について一致している間だけで、0から数え始めるクライアントは一致しない。生成
     * パケットは届く前に書かれ、その間も弾は飛ぶ。結果、クライアントはミサイルを発射の第1段階に置き、
     * サーバーは第2段階に置く——モーターの点火が遅れ、推力の立ち上がりが遅れ、2つの複製の差は「固定の遅延」
     * であるべきなのに燃焼の tick ごとに広がっていく。
     *
     * <p>生成時に一度だけ送る。この値が既知でない唯一の瞬間がそこだから。以後は各側が自分の tick を数え、
     * どちらも再通知を必要としない。
     */
    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(this.age);
        // 加えて、何が撃ったかと、その時それがどこに立っていたか。どちらも他の経路ではクライアントへ届か
        // ない——所有者は機体ではなく乗員だし、機体の「過去の tick の位置」を送る物は無い——し、2つ合わせて
        // 弾を砲口へ戻す仕組みの全部になる。anchorToMuzzle 参照。
        buffer.writeInt(this.firedFromId);
        buffer.writeDouble(this.firedFromAt.x);
        buffer.writeDouble(this.firedFromAt.y);
        buffer.writeDouble(this.firedFromAt.z);
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf buffer) {
        this.age = buffer.readVarInt();
        this.firedFromId = buffer.readInt();
        this.firedFromAt = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        // クライアント側で、エンティティが既にワールドにありサーバーの置いた場所に立っている状態で読む。
        // これができる唯一の瞬間がそこ。
        this.anchorToMuzzle();
    }

    /**
     * この側が描いている砲口の位置へ、弾を戻す。
     *
     * <p>クライアントでは何も発射されない。弾はサーバーで、<em>そちらの</em>機体位置から生成され、
     * クライアントは1往復後にそれを知らされる。だから弾が現れる時点で、それは「引き金が引かれた瞬間に機体が
     * いた場所」に立っており、クライアントはその間ずっと機体をもっと先へ描いてきている。撃った乗員にとって
     * その差は往復の間に自分が進んだ距離そのものだ。戦車なら数ブロック、300ノットの機体なら100ブロック近く。
     * よって弾は機体の後方に現れそこから飛んでいく。コックピットから見ればそれは機首から角度の付いた射線に
     * 見えるし、爆弾——機体の速度だけを持って離れる物——にとっては後ろ向きに投げ出されたように見える。
     *
     * <p>直し方は、弾をちょうどその差だけ運ぶこと。今の機体位置から発射時の位置を引いた値だ。このずれは各側
     * が自分で測れ、どこからも数値をもらわない。撃った乗員のクライアントではそれが往復分の移動になり、弾は
     * 砲から出る。他の全員のクライアントではほぼゼロになる。傍観者は弾を運んだのと同じパケットから機体を
     * 描いており、両者は同じだけ遅れているから。「これが誰の弾か」を知る必要はどこにも無い。
     *
     * <p><b>そして返す。</b> このずれが要るのは砲口だけで、他のどこでもない。弾がどこで炸裂するかを決めるの
     * はサーバーの複製なので、最後まで前に保ったままでは自分の爆発よりかなり手前で飛行を終えることになる。
     * {@link #mergeAnchor} が発射後1秒ほどかけて外し、その頃には皆が見ていた「出ていく瞬間」は終わっている。
     * 少しでも残っている間、{@link #lerpTo} は何かを測る前にこれを外すので、補正機構は同じ土俵で比較し続け
     * られる。
     */
    private void anchorToMuzzle() {
        Entity vehicle = this.firedFrom();

        if (vehicle == null || this.age > ANCHOR_AGE) {
            return;
        }

        Vec3 moved = vehicle.position().subtract(this.firedFromAt);
        double far = moved.length();

        // 傍観者に対してやることは無いし、その後まったく別の場所へ移された機体の弾に対して妥当な処理も
        // 無い。ANCHOR_LIMIT 参照。
        if (far < 1.0E-4 || far > ANCHOR_LIMIT) {
            return;
        }

        Vec3 at = this.position().add(moved);

        this.anchor = moved;
        this.setPos(at.x, at.y, at.z);
        // そうしないと最初のフレームが「サーバーが置いた場所から今運ばれた場所まで」の筋として描き、
        // 差の全部が1本の線になる。
        this.setOldPosAndRot();
    }

    /**
     * サーバーが言う「この弾の位置」をどう扱うか。
     *
     * <p>通常のエンティティのようにそこへ移動させはしない。弾は1tickに最大40ブロック進むので、1tick古い位置
     * に置かれれば40ブロックの跳びだし、1フレーム遅れて届けば40ブロック戻る跳びになる。クライアントは同じ
     * 数値から同じ弾を飛ばしており、既に正しい場所にいる。補正を受け取る価値があるのは、クライアントがまだ
     * 知らないことを言っている時だけ。
     *
     * <p>だから答えは1つではなく3つあり、どれになるかは差の大きさで決まる。弾自身の飛行1〜2tick分以内なら
     * 何もしない。その差はパケットの所要時間であって、何かについての不一致ではない。それを超えたら、跳ばずに
     * 続く数tickで消化する（{@link #settle} 参照。補正が届くたび弾が前へつんのめっていた問題の対処の全部）。
     * {@link #LOST_TICKS} を超えたらそのまま置く。その距離では両者はもう同じ弾を飛ばしていない。
     *
     * <p>回転には一切触れない。飛行経路から毎tick両側で求め直されるので、サーバーがそれについて有用に言える
     * ことは何も無い。
     */
    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        // 砲口へ運ばれていなかった場合の位置に対して測る。サーバーが知っている唯一の位置がそれだから。
        // 運んだ後の位置に対して測れば、そのオフセットは飛行中の全パケットで不一致として読まれ、弾は今
        // 置かれたばかりの砲口から即座に引き戻される。anchorToMuzzle 参照。
        Vec3 gap = new Vec3(x, y, z).subtract(this.position().subtract(this.anchor));
        double off = gap.length();
        double lost = Math.max(LOST_FLOOR, this.getDeltaMovement().length() * LOST_TICKS);

        if (off > lost) {
            // 遅延パケットではなく完全に別の場所。そこへ行って終わりにする。他と同じく砲口オフセットの分
            // だけ運ぶ。さもないと次のパケットが、そのオフセット自体をまったく同じ大きさの不一致として読む。
            Vec3 at = new Vec3(x, y, z).add(this.anchor);

            this.setPos(at.x, at.y, at.z);
            this.owed = Vec3.ZERO;

            return;
        }

        // 飛行1〜2tick分以内は不一致ではなくパケット自身の古さなので放置する。CORRECTION_TICKS 参照。
        double slack = Math.max(CORRECTION, this.getDeltaMovement().length() * CORRECTION_TICKS);

        this.owed = off > slack ? gap : Vec3.ZERO;
    }

    /**
     * 直近の補正の残りを、1tickにその一部ずつ消化する。
     *
     * <p>触るのは位置だけ。弾は自分の速度で自分の飛行を続け、これはその上に乗る。だから位置へ戻されつつある
     * 弾は、必要な間だけ実際より数%速く進むように描かれる——そこへ直接移動させるのではなくこの方式にする
     * 意味がそれ。
     *
     * <p>この歩は弾の移動量にも算入する。しなければならない。弾は「前の位置から今の位置まで」の線に沿って
     * 描かれ、補正が上に乗った時点でその線は飛行だけの線ではなくなる。飛行だけに沿って機首を向ければ、胴体
     * が運ばれている線から機首が外れ、補正が続く間ミサイルは横向きに滑って飛ぶように描かれる。算入すれば、
     * 下で何を消化していようと機首は描画経路上に留まる。{@link #travel} 参照。
     */
    private void settle() {
        if (this.owed.lengthSqr() < 1.0E-6) {
            this.owed = Vec3.ZERO;

            return;
        }

        Vec3 step = this.owed.scale(CORRECTION_RATE);
        Vec3 at = this.position().add(step);

        this.setPos(at.x, at.y, at.z);
        this.lastTravel = this.lastTravel.add(step);
        this.owed = this.owed.subtract(step);
    }

    /**
     * 弾がまだ前へ運ばれている分を、1tickに一部ずつ返す。
     *
     * <p>{@link #anchorToMuzzle} のもう半分。弾を出てきた砲口へ置いたのは、乗員がそこから出ていくのを見る
     * からだ。サーバーは同じ弾を「本当に出た場所」から飛ばしており、サーバーが決めること——何に当たるか、
     * 爆発がどこで起きるか——は全部そちらで起きる。だからずれは続く tick で返し、両者は再び同じ弾を飛ばす
     * ようになる。
     *
     * <p>1tickに使うのは弾自身の1歩の一部まで。それがこれを見えなくしている（{@link #ANCHOR_MOST} 参照）。
     * この歩を移動量へ算入するのは {@link #settle} が自分の分を算入するのと同じ理由——弾は実際に運ばれている
     * 線に沿って描かれ、それ以外へ機首を向ければ横滑りして飛ぶ弾になる。
     */
    private void mergeAnchor() {
        double far = this.anchor.length();

        if (far < 1.0E-4) {
            this.anchor = Vec3.ZERO;

            return;
        }

        // 残量の一部を、弾自身の速度で上限を掛け、下限も設ける。最後の分が永遠に半分ずつ減るのではなく
        // ちゃんと払い終わるように。
        double pace = Math.min(far * ANCHOR_RATE, this.getDeltaMovement().length() * ANCHOR_MOST);
        double take = Math.min(far, Math.max(pace, ANCHOR_LEAST));
        Vec3 step = this.anchor.scale(take / far);
        Vec3 at = this.position().subtract(step);

        this.setPos(at.x, at.y, at.z);
        this.lastTravel = this.lastTravel.subtract(step);
        this.anchor = this.anchor.subtract(step);
    }

    /** 移動前の1tick分の飛行。ここが deltaMovement に残した物がそのまま採用される。 */
    protected abstract void steer();

    /**
     * 移動前の最終確認。狙った相手に触れずに炸裂する物のため。
     *
     * @return 炸裂すべき位置。飛び続けるなら null
     */
    @Nullable
    protected Vec3 earlyDetonation() {
        return null;
    }

    /**
     * 下に世界があるかどうかに関わらず飛び続ける。
     *
     * <p>機体が持っているのは自分が飛ぶ回廊だけなので、そこから撃った物はレールを離れて1〜2tickで「誰も
     * ロードしていない地面の上」へ出る。バニラは chunk が tick しなくなった瞬間にエンティティの tick も
     * 止め、ロード済みの世界の外ではそれが即座に起きる。これが無ければ高高度で撃ったミサイルは二度と動かず、
     * 兵装はそこでは端的に動作しない。ここでそう宣言するコストは無い——小さく、数が少なく、短命で、どれも
     * 世界に「答えられない問い」を投げないから。
     *
     * <p>飛ぶことと当たることは別で、外にいる弾は前方の地面も開いたまま保持する。到着した時に当たる物がある
     * ように。{@link WeaponChunkLoader} 参照。
     */
    @Override
    public boolean isAlwaysTicking() {
        return true;
    }

    @Override
    public void tick() {
        // この tick の飛行の下にロード済みの地面があるかどうかが、以下のほぼ全部を決める。その外には当たる
        // 物も訊く価値のある物も無く、それでも訊くのが高くつく間違いだ。サーバーではその外のブロックや流体
        // を1回引くごとにその場・メインスレッドで chunk が生成されるので、空を横切るミサイル1発が1tickに
        // 30ブロック幅で新しい地形の回廊を刻んでいくことになる。訊くのは「チケットが在るか」ではなく
        // 「chunk が在るか」。先読みはチケットを先に置くので、両者は同じ物ではない。spanIsLoaded 参照。
        boolean overTheWorld = this.chunkIsThere(this.getX(), this.getZ());

        if (overTheWorld) {
            super.tick();
        } else {
            this.tickBeyondTheWorld();
        }

        WeaponDefinition.Projectile round = this.getRound();

        // 両側で数える。弾がどれだけ飛んでいるかはサーバーだけの関心事ではない。モーターの燃焼時間はこれ
        // に対して測られるし、モーターが止まると同時に止まる噴煙と音を描き鳴らすのはクライアントだ。
        this.age++;

        // 射程を飛び切ったら見捨てる。それが何を意味するかは兵装次第で、弾はただ消え、ロケットは炸裂する。
        // その判断ができるのはサーバーだけ。
        if (!this.level().isClientSide && this.age > round.lifetime()) {
            this.expire();

            return;
        }

        // 両側で、同じ数値から、そして以下の処理がこの tick の歩に対して測られる前に誘導する。飛行の
        // 持ち主はサーバーだが、惰性で進むだけのクライアントは「加速しないロケット」と「曲がらないミサイル」
        // を描き、届く位置パケットのたびに本当の経路へ引き戻される。ここで同じ誘導を回せば引っ張る物が無く
        // なる。パケットの間も両者は一致し、弾は描かれている方向へ進む。
        this.steer();

        if (!this.level().isClientSide) {
            Vec3 fuse = this.earlyDetonation();

            if (fuse != null) {
                this.detonate(fuse);

                return;
            }

            HitResult hit = this.strike();
            this.trace(hit);

            if (hit != null && hit.getType() != HitResult.Type.MISS && !EventHooks.onProjectileImpact(this, hit)) {
                this.onHit(hit);

                if (this.isRemoved()) {
                    return;
                }
            }
        } else {
            this.trace(null);
            this.spawnTrail();
        }

        this.fly();

        // サーバーが言った「本当の位置」を、跳ばずに飛行の後ろへ滑り込ませる。settle 参照。そして最初に
        // 弾を砲口へ置くために運んだ分を一部返す。あれが要るのは砲口だけで、その後はどこでも要らない。
        if (this.level().isClientSide) {
            this.settle();
            this.mergeAnchor();
        }

        // 最後に置く。確保が「弾がいた場所」ではなく「弾が到達した場所」から行われるように。ここからだけ
        // 呼ぶこと。チケットの取得は chunk システムへ再入するので、tick からは安全でもそのコールバックから
        // は安全でない。WeaponChunkLoader 参照。
        WeaponChunkLoader.update(this, this.hold);
    }

    /**
     * 一時的。「機体から投下した物がレールを離れる途中で速度を失う」という報告の調査用。gun 以外の物の最初
     * の数tickを両側から記録する。位置、速度、そしてこの tick の飛行が何にぶつかったか——ぶつかった相手が
     * 出てきた機体そのものだったかも含めて。{@code canHitEntity} はそれを不可能にしているはずの物。解決し
     * たら削除すること。
     */
    private void trace(@Nullable HitResult hit) {
        if (this.age > TRACE_TICKS || this.getWeapon().type() == WeaponDefinition.Type.GUN) {
            return;
        }

        Entity struck = hit instanceof EntityHitResult entityHit ? entityHit.getEntity() : null;
        Entity vehicle = this.firedFrom();

        AshVehicles.LOGGER.info("[flight] {} {} age={} at={} v={} |v|={} hit={} struck={} own={}",
                this.level().isClientSide ? "client" : "server", this.getWeaponId(), this.age,
                this.position(), this.getDeltaMovement(), this.getDeltaMovement().length(),
                hit == null ? "none" : hit.getType(),
                struck == null ? "-" : struck.getType().toShortString() + "#" + struck.getId(),
                struck != null && vehicle != null && WeaponMounts.isPartOf(vehicle, struck));
    }

    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        // 炸裂しようと寿命切れだろうと chunk ごとアンロードされただけだろうと、手放す。手放すだけにする
        // こと。これは chunk システム自身の更新ループの中からも飛ぶし、そこで chunk を要求すれば反復の途中
        // でそのループへ再入する。
        WeaponChunkLoader.release(this, this.hold);
    }

    /**
     * 1tick分の飛行。この tick の速度が運ぶ先と、次のために引く落下分。
     *
     * <p>独立したメソッドなのは、これが弾の飛行そのものであってそれ以外を含まないから。衝突も航跡も無く、
     * 世界に何も訊かない。クライアントがその上に後から乗せる物（{@link #settle} 参照）は、そうすることで
     * 明確に「飛行の一部」ではなく「補正」になる。
     */
    private void fly() {
        Vec3 velocity = this.getDeltaMovement();
        Vec3 next = this.position().add(velocity);

        this.setPos(next.x, next.y, next.z);
        this.lastTravel = velocity;
        this.setDeltaMovement(velocity.subtract(0.0, this.gravityNow(), 0.0));
        this.updateRotation();
    }

    /**
     * 世界の無い場所でもまだ意味を持つ、通常のエンティティ tick のごく一部。
     *
     * <p>基底クラスが tick を費やすのはポータル・流体・火・立っている物についてで、そのどれもここには存在
     * しない。そしてどれも「弾自身の位置のブロックを読む」ことで調べられる——ここではそれは安い問いではなく
     * 高い問いだ（{@link #tick} 参照）。だから呼ばず、まだ意味のある2〜3行だけを手で書く。
     */
    private void tickBeyondTheWorld() {
        this.xRotO = this.getXRot();
        this.yRotO = this.getYRot();
        this.firstTick = false;
        this.checkBelowWorld();
    }

    /**
     * この tick の飛行が何にぶつかるか。点ではなく線として判定する。この速度域では、そうしないと1tick分の
     * 移動より薄い物を綺麗にすり抜けてしまうから。
     *
     * <p>ブロックへ問い合わせるのは、1歩が通る線の下の地面が今そこに在る時だけ。その縁の外では地形が生成
     * されているとは限らず、そこには誰も見たことのない「当たる物」も無いし、問い合わせればそれを生成して
     * しまう。エンティティはどちらの場合も判定する。外にいる機体は下の地面がどうであろうとロードされており、
     * 長距離射撃が狙っているのはまさにそれだから。
     */
    @Nullable
    private HitResult strike() {
        Vec3 from = this.position();
        Vec3 to = from.add(this.getDeltaMovement());

        if (this.spanIsLoaded(from, to)) {
            return ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
        }

        return ProjectileUtil.getEntityHitResult(this.level(), this, from, to,
                this.getBoundingBox().expandTowards(this.getDeltaMovement()).inflate(1.0),
                this::canHitEntity);
    }

    /**
     * その1歩の下のブロックが、今そこに在って待たずに読めるか。
     *
     * <p>「歩を囲む箱の chunk が全部ロード済みか」ではない。2か所で違う。
     *
     * <p><b>箱ではなく線。</b> このあと読まれるのは {@code Level.clip} が歩の線分に沿って踏むブロック
     * だけで、その線が通るのは斜めの歩を囲む長方形のごく一部だ。26ブロックの斜めの歩を囲む箱は 4 chunk に
     * またがるが、線が通るのは対角の 2 chunk だけ。残りの2つを誰もロードしない——確保も先読みも、どちらも
     * 弾の<em>経路</em>を指定するものだから——ので、箱で訊けばロード済みの世界の外での答えは常に「未ロー
     * ド」になり、弾はブロックに一度も問い合わせないまま斜面を突き抜けていく。狙って撃った物が地形をすり
     * 抜けていた仕組みはこれだ。だから線が実際に跨ぐ chunk だけを歩いて訊く。斜めに撃つほど、箱と線の差は
     * 開く。
     *
     * <p><b>チケットではなくブロック。</b> {@code hasChunkAt} が答えるのは「その chunk のチケット水準が
     * 足りているか」であって「その chunk が在るか」ではない（{@code ServerChunkCache.chunkAbsent} 参照）。
     * {@link WeaponChunkLoader} の先読みはチケットを置いた瞬間にこれを true にするが、地形はまだ生成器の
     * スレッドで作られている最中だ。そこでブロックを引けば、このファイルと先読みが避けるために存在する物
     * ——tick スレッド上でのワールド生成——がそのまま起きる。{@code getChunkNow} はロード済みの chunk か
     * null しか返さないので、答えは「待たずに読めるか」になる。読めない chunk の下は、地形がまだ無いのと
     * 同じに扱う。次の tick には在る。
     */
    private boolean spanIsLoaded(Vec3 from, Vec3 to) {
        ChunkSource chunks = this.level().getChunkSource();
        int x = SectionPos.blockToSectionCoord(Mth.floor(from.x));
        int z = SectionPos.blockToSectionCoord(Mth.floor(from.z));
        int lastX = SectionPos.blockToSectionCoord(Mth.floor(to.x));
        int lastZ = SectionPos.blockToSectionCoord(Mth.floor(to.z));
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        // 次の境界までの進み具合と、そこから先の1 chunk あたりの進み具合。どちらも線分全体を1とする。
        // 常に「先に境界へ着く方」の軸を跨ぐので、線が実際に通る順に chunk が並ぶ。
        double edgeX = nextEdge(from.x, dx);
        double edgeZ = nextEdge(from.z, dz);
        double perX = dx == 0.0 ? Double.POSITIVE_INFINITY : Math.abs(16.0 / dx);
        double perZ = dz == 0.0 ? Double.POSITIVE_INFINITY : Math.abs(16.0 / dz);
        int stepX = dx < 0.0 ? -1 : 1;
        int stepZ = dz < 0.0 ? -1 : 1;

        // 上限は保険。最も速い物でも1歩は 3 chunk 程度で、終端に着けば下で抜ける。浮動小数の縁で終端を
        // 踏み損ねた歩に、tick スレッドを回させないためだけの数値。
        for (int walked = 0; walked < SPAN_CHUNKS; walked++) {
            if (chunks.getChunkNow(x, z) == null) {
                return false;
            }

            if (x == lastX && z == lastZ) {
                return true;
            }

            if (edgeX < edgeZ) {
                x += stepX;
                edgeX += perX;
            } else {
                z += stepZ;
                edgeZ += perZ;
            }
        }

        return false;
    }

    /**
     * 線分の始点から、その軸で次の chunk 境界を跨ぐまでの進み具合。線分全体を1とする。その軸に動かない歩
     * は境界を跨がないので、無限遠に置いてもう一方の軸に選ばせる。
     */
    private static double nextEdge(double at, double delta) {
        if (delta == 0.0) {
            return Double.POSITIVE_INFINITY;
        }

        int chunk = SectionPos.blockToSectionCoord(Mth.floor(at));
        double edge = SectionPos.sectionToBlockCoord(delta > 0.0 ? chunk + 1 : chunk);

        return (edge - at) / delta;
    }

    /**
     * その位置の chunk が今そこに在るか。チケットが在るかではない。{@link #spanIsLoaded} の後半参照。
     */
    private boolean chunkIsThere(double x, double z) {
        return this.level().getChunkSource().getChunkNow(
                SectionPos.blockToSectionCoord(Mth.floor(x)),
                SectionPos.blockToSectionCoord(Mth.floor(z))) != null;
    }

    /**
     * 2つの tick の間のこの瞬間に、弾がどちらを向いているか。
     *
     * <p>単なる速度ではない。弾は「いた位置から今の位置まで」の線に沿って描かれ、その線上を運んだのは前
     * tick に踏んだ歩であって、次に踏む歩ではない。フレームをまたいで一方から他方へ混ぜれば、機首は胴体が
     * 進んでいる線の上に留まり、毎秒20段階ではなく滑らかに回る。
     *
     * @param partialTick 現在の tick のどこまでフレームが進んでいるか
     */
    public Vec3 travel(float partialTick) {
        Vec3 next = this.getDeltaMovement();

        if (this.lastTravel.lengthSqr() < 1.0E-8) {
            return next;
        }

        return this.lastTravel.add(next.subtract(this.lastTravel).scale(partialTick));
    }

    /** 単に寿命が尽きた時に何が起きるか。弾は消えるが、ミサイルはその限りでない。 */
    protected void expire() {
        this.discard();
    }

    /** モーターがまだ押しているか。押している間は航跡に加えて噴煙も出る。 */
    protected boolean underPower() {
        return false;
    }

    /**
     * この tick にどれだけ下へ引かれるか（1tick二乗あたりブロック）。
     *
     * <p>兵装自身の値。年齢とファイルから毎tick両側で求め直すので、両者が「どこまで来たか」で食い違うことは
     * 無い。
     */
    protected float gravityNow() {
        return this.getRound().gravity();
    }

    /**
     * モーターの後ろの煙。1粒ずつ送るのではなく各クライアントが自分で描く。
     *
     * <p>1tickに1粒落とすのではなく、実際に飛んだ経路に沿って置く。ミサイルは tick 間に20〜30ブロック進む
     * ので、1tick1粒では航跡にならない——点の列になり、面白い部分は全部その隙間に入る。距離で間隔を決めれば、
     * レールを離れたばかりの歩く速度でも最高速でも同じ密度の航跡になる。加速中のミサイルの後ろの航跡が何か
     * らしく見える唯一の方法でもある。
     */
    protected void spawnTrail() {
        WeaponDefinition.Trail trail = this.getRound().trail().orElse(null);
        Vec3 step = this.getDeltaMovement();
        double flown = step.length();

        if (trail == null || flown < 1.0E-4) {
            return;
        }

        Vec3 head = this.position();
        RandomSource random = this.random;
        int puffs = Mth.clamp(Mth.ceil(flown * trail.density()), 1, MAX_PUFFS);
        // 上限が効き始めたら——1tick30ブロックでは常に効く——残った粒が1つあたりより広い範囲を覆う必要が
        // あるので大きくする。速い煙は実際そうなる。素早く置かれるほど多くが1箇所に溜まる。
        //
        // 倍率は粒の間隔そのものから引く。そうすれば航跡の見え方が速度に依らない——レールを離れた直後の
        // 1tick1ブロックでも、燃焼終わりの1tick68ブロックでも、粒は隣に届く大きさで置かれ、航跡は同じ
        // 一本の柱になる。速度と共に細るのは、そこが唯一の途切れる場所だからだ。
        float spread = (float) Mth.clamp(flown / puffs * TRAIL_SPREAD,
                TRAIL_SPREAD_LEAST, TRAIL_SPREAD_MOST);
        TintedParticleOption smoke = ModParticles.CONTRAIL.get().of(trail.colour(), trail.size() * spread);

        for (int i = 0; i < puffs; i++) {
            // 1歩の範囲に少し揺らぎを付けて散らす。連続する tick が同じ場所に粒を置いて航跡が杭垣になら
            // ないように。
            Vec3 at = head.subtract(step.scale((i + random.nextDouble()) / puffs));
            // 各粒はミサイル自身の速度をわずかに保ってから止まる。それが航跡を「そこに印刷された物」では
            // なく「後ろへ引き出された物」に見せる。
            Vec3 drift = step.scale(TRAIL_DRIFT / flown);

            this.level().addParticle(smoke,
                    at.x + random.nextGaussian() * TRAIL_SCATTER,
                    at.y + random.nextGaussian() * TRAIL_SCATTER,
                    at.z + random.nextGaussian() * TRAIL_SCATTER,
                    drift.x, drift.y, drift.z);
        }

        if (!this.underPower()) {
            return;
        }

        // そしてモーターが燃えている間は噴煙もある。今この瞬間にノズルから出ている物で、すぐ後ろにあり、
        // まだミサイルと同じ方向へ進んでいる。
        //
        // 大きさは航跡と同じ倍率で測る。噴煙もこの1tickの飛行に沿って撒かれる以上、間隔が開く事情は
        // 航跡と全く同じで、素の大きさのままでは高速時に点の列になる。
        TintedParticleOption exhaust = ModParticles.MOTOR_SMOKE.get().of(trail.exhaust(), trail.size() * spread);

        for (int i = 0; i < EXHAUST_PUFFS; i++) {
            Vec3 at = head.subtract(step.scale(random.nextDouble() * EXHAUST_REACH));
            Vec3 blown = step.scale(-EXHAUST_BLOW / flown);

            this.level().addParticle(exhaust,
                    at.x + random.nextGaussian() * EXHAUST_SCATTER,
                    at.y + random.nextGaussian() * EXHAUST_SCATTER,
                    at.z + random.nextGaussian() * EXHAUST_SCATTER,
                    blown.x, blown.y, blown.z);
        }
    }

    /**
     * 出てきた機体も、それに乗っている物も対象外。パイロットは所有者判定で既に除かれているが、搭乗者や主翼
     * の箱は除かれていないし、この弾は主翼の中で生を受ける。
     *
     * <p>そして、この tick の飛行が実際には通らない機体の箱も対象外。ゲームが目標として差し出すのは、機体の
     * 各箱を運ぶ直立した箱だ。後退角の付いた主翼ではその箱はほぼ正方形なのに対し、主翼は対角線に沿った薄い
     * 板でしかない——だからゲームが主翼と数える領域の半分は、その前後の空っぽの空だ。何かを測る前にここで
     * 問えば、弾は単に「当たろうとしていた箱は機体のある場所ではない」と告げられる。
     */
    @Override
    protected boolean canHitEntity(Entity target) {
        Entity vehicle = this.firedFrom();

        if (vehicle != null && WeaponMounts.isPartOf(vehicle, target)) {
            return false;
        }

        if (!super.canHitEntity(target)) {
            return false;
        }

        if (target instanceof VehiclePart part) {
            Vec3 from = this.position();

            return part.clip(from, from.add(this.getDeltaMovement()), PICK_INFLATION).isPresent();
        }

        return true;
    }

    @Nullable
    protected Entity firedFrom() {
        if (this.firedFrom == null && this.firedFromId >= 0) {
            this.firedFrom = this.level().getEntity(this.firedFromId);
        }

        return this.firedFrom;
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        if (this.thrownOff(hit)) {
            // そもそも命中ではない。滑って逸れ、まだ空中にいる。誰も傷つかず何も炸裂せず、弾は次に前方に
            // ある物へ向かって進む。
            return;
        }

        super.onHitEntity(hit);

        // 砲身を出た時の威力ではなく今残っている威力。ここまで真っ直ぐ来た弾——ほぼ全部——では両者は同じ。
        float damage = this.getRound().damage() * Ricochet.energy(this.deflections);

        hit.getEntity().hurt(this.damageSource(), damage);
        // 撃った者だけに伝える。この兵装が使われる距離では、弾が目標のどこへ行ったかを砲手が知る唯一の
        // 手段がこれ。HitReportPayload 参照。
        HitReportPayload.report(this.getOwner(), hit.getEntity(), hit.getLocation(),
                this.getDeltaMovement(), damage, false);
        this.struck(hit);
        this.burst(hit.getLocation(), null, hit.getEntity());
    }

    /**
     * この弾が与える打撃の出所。撃った者と、飛んできた物そのもの。
     *
     * <p>1つの弾が2度傷つけることがある——弾頭が目標に、続いて爆風が周囲に——ので、両方が同じ形で作られる
     * 必要がある。機体側は同一 tick の同一ソースを1度しか数えないので（{@code VehicleEntityBase.hurt}
     * 参照）、ここを共有することが「どちらも数えられる」ことと「箱の数だけ数えられない」ことの両方を
     * 保証している。
     */
    protected DamageSource damageSource() {
        return new DamageSource(
                this.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(DAMAGE_TYPE),
                this, this.getOwner());
    }

    /**
     * 信管が作動した位置で終わる。
     *
     * <p>既定では炸裂するだけ。何に対して作動したのかを知っている弾——つまり目標を追っていたミサイル——は
     * ここで弾頭をその相手へ渡す。{@code RocketEntity.detonate} 参照。
     */
    protected void detonate(Vec3 where) {
        this.burst(where, null);
    }

    /**
     * 弾がこの MOD の箱へ入る時の音。
     *
     * <p>{@link #burst} ではなくここで鳴らす。あちらは全ての弾の生涯のもう一方の端で、何に着いたかを判別
     * できない。斜面への射撃と砲塔前面への射撃はあちらでは同じ呼び出しになり、命中なのは片方だけだ。だから
     * ここで「何に当たったか」を、その答えがまだ手元にある唯一の瞬間に問う。
     *
     * <p>自前の炸薬を持たない弾で、かつ相手が機体の場合だけ。着弾点で炸裂する物は既にその音で聞こえており、
     * その上に金属音を重ねても爆発音が言っていないことは何も言わない。地面への弾はブロック自身の破片が出る
     * のであって装甲板への打撃ではない。残るのはまさに以前無音だった場合——戦車に着弾する徹甲弾や機銃の
     * 連射。{@link Impact} 参照。
     */
    private void struck(EntityHitResult hit) {
        if (!(this.level() instanceof ServerLevel level)
                || this.getRound().explosion() > 0.0F
                || !isMachine(hit.getEntity())) {
            return;
        }

        Vec3 at = hit.getLocation();

        // 兵装名から引くので、パックは全部を用意せず1つの砲の命中音だけ収録できる。どちらも無いクライア
        // ントは MOD の既定へ落ちる。到達距離を音量スロットに入れるのはここの他と同じ理由——そのスロット
        // だけが「誰にこの音を知らせるか」を決めており、命中は32ブロックより遠くから聞く価値がある。
        level.playSound(null, at.x, at.y, at.z,
                SoundEvent.createVariableRangeEvent(Impact.soundFor(this.getWeaponId())),
                SoundSource.NEUTRAL, Impact.SOUND_SETUP.packetVolume(), Impact.SOUND_SETUP.pitch());
    }

    /** それがこの MOD の機体か。自分の箱に当たった場合も機体本体に当たった場合も含む。 */
    private static boolean isMachine(Entity target) {
        return target instanceof VehicleEntityBase
                || target instanceof VehiclePart part && part.getParent() instanceof VehicleEntityBase;
    }

    /**
     * 装甲がこの弾を通さず弾くか。弾く場合はそのまま送り出す。
     *
     * <p>弾けるのは装甲だけで、装甲かどうかを言えるのは機体だけだ。主翼はどれだけ厚くても装甲ではないし、
     * プレイヤーも牛も違う。{@link VehicleEntityBase#isArmoured} 参照。
     *
     * <p>入射角はどのファイルからも読まない。装甲板が実際に寝ている姿勢に対して測る。箱をゲームの物ではなく
     * この MOD の物にしている理由の全部がそれだ。射線に向けて振った車体は本当に浅い面を差し出しており、
     * 車体を傾ける乗員は「傾斜についての規則」ではなく幾何によって報われる。
     *
     * <p>弾かれた弾は当たった装甲板の上へ置かれ、新しい進路を与えられ、飛行を続ける。単なる速度変更ではなく
     * {@link #launch} を使うのは、各クライアントが砲口で与えられた値からこの弾を自分で飛ばしており、その値
     * が今まさに真でなくなったから。再設定だけがクライアントへ届く。{@link #DATA_LAUNCH} 参照。
     */
    private boolean thrownOff(EntityHitResult hit) {
        WeaponDefinition.Projectile round = this.getRound();

        if (!round.canRicochet() || this.deflections >= Ricochet.MOST
                || !(this.level() instanceof ServerLevel level)
                || !(hit.getEntity() instanceof VehiclePart part)
                || !(part.getParent() instanceof VehicleEntityBase machine)
                || !machine.isArmoured()) {
            return false;
        }

        Hitbox plate = part.hitbox();

        if (plate == null) {
            return false;
        }

        Vec3 at = hit.getLocation();
        // 命中を見つけたのと同じ箱に対して、余裕も含めて測る。1/3ブロック小さい箱から求めた面は、稜線付近
        // では隣の面になるし、車体の隣の面は弾が実際に当たった面と直角を成す。
        Vec3 normal = plate.grow(PICK_INFLATION).normalAt(at);
        Vec3 velocity = this.getDeltaMovement();

        if (!Ricochet.thrownOff(velocity, normal, round, machine.armour(), this.random)) {
            return false;
        }

        Vec3 away = Ricochet.away(velocity, normal, this.random);

        // 弾の向きを変える前に報告する。マークが「去った線」ではなく「入ってきた線」に対して描かれるように。
        // 跳弾はそれ自体を砲手へ伝える価値がある。照準の後ろから見れば外れとまったく同じに見えるし、外れへの
        // 答えは「同じ場所をもう一度撃つ」ことだから。
        HitReportPayload.report(this.getOwner(), hit.getEntity(), at, velocity, 0.0F, true);
        this.deflections++;
        // 命中判定に許されている全ての余裕の外へ出す。さもないと次の tick も、その後の毎tickも、同じ装甲板
        // の同じ場所から弾かれ続ける。Ricochet.CLEARANCE 参照。
        this.setPos(at.add(normal.scale(PICK_INFLATION + Ricochet.CLEARANCE)));
        this.launch(away);

        WeaponEffects.ricochet(level, at, away, round);
        // 兵装名から引くので、パックは全部を用意せず1つの砲の跳弾音だけ収録できる。どちらも無いクライア
        // ントは MOD の既定へ落ちる。com.ashvehicles.client.sound.ModSounds 参照。
        level.playSound(null, at.x, at.y, at.z,
                SoundEvent.createVariableRangeEvent(Ricochet.soundFor(this.getWeaponId())),
                SoundSource.NEUTRAL, Ricochet.SOUND_SETUP.packetVolume(), Ricochet.SOUND_SETUP.pitch());

        return true;
    }

    @Override
    protected void onHitBlock(BlockHitResult hit) {
        super.onHitBlock(hit);
        this.burst(hit.getLocation(), this.level().getBlockState(hit.getBlockPos()));
    }

    /**
     * 着弾点で何をするか。炎と煙と削れた破片、そして炸薬があれば爆発。
     *
     * <p>2つを分けているのは意図的だ。ロード済みの世界の外では爆発自体を起こさない——それは誰も頼んでいない
     * 爆発で、まだ生成されていない地面を掘り返し、そのためにサーバースレッドで生成することになる。ただし
     * 見えることは見える。演出は下に世界があろうと無かろうと範囲内の全員へ送られる。400ブロック先の何かへ
     * ミサイルを当てたパイロットには、それが炸裂するのを見る権利が完全にあるし、見せる物の無い爆発音は
     * 外れと同じだから。
     */
    protected void burst(Vec3 where, @Nullable BlockState struck) {
        this.burst(where, struck, null);
    }

    /**
     * 同じ処理に「何に入ったか」を伝えた版。
     *
     * <p>これが効くのは1点だけ。自前の炸薬を持たない弾が装甲へ着いた時、斜面へ撃ち込んだ時の数個ではなく、
     * 装甲板からの閃光と火花の噴出を得る（{@link WeaponEffects#strike} 参照）。{@link #struck} の音とまったく
     * 同じ場合であり同じ理屈でもある。戦車が実際に撃つ2種類の弾はどちらも炸裂物を持たないので、これが無いと
     * 戦闘を決める命中が画面上で最も静かで最も暗い出来事になる。
     *
     * @param into 入った相手。地面に着いた弾や信管切れの弾では null
     */
    protected void burst(Vec3 where, @Nullable BlockState struck, @Nullable Entity into) {
        WeaponDefinition.Projectile round = this.getRound();

        if (this.level() instanceof ServerLevel level) {
            WeaponEffects.detonation(level, where, this.getDeltaMovement(), round, struck, isMachine(into));

            if (round.explosion() > 0.0F && level.hasChunkAt(BlockPos.containing(where))) {
                WeaponEffects.blast(level, this, where, round);
            }
        }

        this.discard();
    }

    /**
     * 送られてくる限りの距離まで描く。ここでサーバーより厳しくする意味は無い。サーバーは自分の追跡距離で
     * 弾の報告をやめるし、届いた物は全部描く価値がある。曳光弾やミサイルは遠くから見えるべき物だから。
     */
    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < RENDER_RANGE * RENDER_RANGE;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.entityData.set(DATA_WEAPON, tag.getString("Weapon"));
        this.age = tag.getInt("Age");
        this.deflections = tag.getInt("Deflections");
        this.firedFromId = tag.contains("FiredFrom") ? tag.getInt("FiredFrom") : -1;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("Weapon", this.entityData.get(DATA_WEAPON));
        tag.putInt("Age", this.age);
        tag.putInt("Deflections", this.deflections);
        tag.putInt("FiredFrom", this.firedFromId);
    }
}
