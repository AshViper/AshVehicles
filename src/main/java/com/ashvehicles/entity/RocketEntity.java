package com.ashvehicles.entity;

import javax.annotation.Nullable;

import com.ashvehicles.weapon.WeaponDefinition;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * ロケット。そして——兵装ファイルが誘導を与え、発射時にパイロットが何かをロックしていた場合——ミサイル。
 *
 * <p>2つが同じオブジェクトなのは、ほぼ同じ物だから。どちらもゆっくりレールを離れ、数秒間モーターに押され
 * る。だから機体は発射直後の一瞬自分のロケットを追い越すし、静止状態から撃っても加速していく。どちらも
 * その後は惰性で飛び、着弾点で炸裂する。どちらもモーターが燃えている間は発射時の線を保ち
 * （{@link #axis} 参照）、モーターが切れて機首を支える物が無くなれば弧を描き始める。違いは、ミサイルは
 * モーターが燃えている間その線を何かの方へ曲げる、それだけ。
 *
 * <p>モーターはレールから点いているが、推力の全部を一度に出す必要は無い。兵装ファイルは1秒程度かけて
 * 立ち上げさせられるので、ミサイルは3tickで最高速へ飛びつくのではなく速度を積み上げる。そういう立ち上がり
 * を指定しないファイルは最初の tick から全推力を得る。
 *
 * <p>ミサイルにできることは意図的に制限されている。旋回は1tickあたり固定の角度なので、それより強く曲がる
 * 目標には外れる。そして前方に見えている物しか追わないので、背後へ回った目標は失探しミサイルはロケットと
 * して飛び続ける。誘導するのは動力飛行中だけで、モーターが切れれば他の全部と同じく弾道飛行になる。無条件
 * に命中する物は1つも無く、それが要点だ。ミサイルは破られるべき物である。
 *
 * <p>そして何もロックせずに撃った物はレールを離れた時点でロケットだ。シーカーがそもそも動かないので、飛行
 * 中に自分で目標を探しに行くこともなければ、フレアが騙す相手も存在しない。それが最も効くのは撃った本人で、
 * ロックなしの射撃に最も近い対抗手段はたいてい自機が今放出した物だから。
 */
public class RocketEntity extends VehicleProjectile implements GeoEntity {
    private final AnimatableInstanceCache animatableCache = GeckoLibUtil.createInstanceCache(this);

    /**
     * 追っている相手。クライアントが「発射時の向き」ではなく「本当に向かっている方向」へミサイルを描ける
     * よう同期する。
     */
    /** デコイがミサイルを誘い得る最大距離（ブロック）。 */
    private static final double DECOY_REACH = 40.0;
    /** 範囲内のデコイ1つが毎tickミサイルを奪う確率。 */
    private static final float DECOY_CHANCE = 0.2F;

    private static final EntityDataAccessor<Integer> DATA_TARGET =
            SynchedEntityData.defineId(RocketEntity.class, EntityDataSerializers.INT);

    @Nullable
    private Entity target;
    /** シーカーが追っていた相手を見失ったら true。以後は探さない。 */
    private boolean lost;

    /**
     * 機首の向き。進行方向とは別物。
     *
     * <p>毎tick計算し直すのではなく保持する。その差が「ロケットが真っ直ぐ飛ぶかどうか」の全部だ。モーター
     * はロケットを自分の軸方向へ押し、フィンはその軸をその場に保つ。以前のように軸を速度から取ると、ロケッ
     * トはそれまでに受けた擾乱を全部返される。1tick分の重力が速度をわずかに下へ曲げ、次の tick はその曲がっ
     * た線を「ロケットの向き」と呼び、モーターは燃焼時間の全部をそちらへ押すのに使う。訂正する物が無いので
     * 誤差は平均されず累積し、モーターが強いほどロケットは間違った線に強く commit する。独立した値として
     * 保持すれば、軸は意図的にしか変わらない——シーカーが、ファイルの許す速度で回す時だけ——ので、シーカーの
     * 無いロケットはモーターが切れるまで発射時の線を保つ。
     *
     * <p>発射速度が分かるまでは0。{@link #launched} と {@link #axis()} 参照。
     */
    private Vec3 axis = Vec3.ZERO;

    public RocketEntity(EntityType<? extends RocketEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_TARGET, -1);
    }

    /**
     * 発射時の軸を取る。両側とも発射速度を伝えられており、まだどちらもそれを乱していないので同じ値になる。
     */
    @Override
    protected void launched(Vec3 velocity) {
        super.launched(velocity);
        this.axis = velocity.lengthSqr() < 1.0E-8 ? Vec3.ZERO : velocity.normalize();
    }

    /**
     * 機首の向き。
     *
     * <p>教えられていない物には進行方向で代用する。たいていはディスクから読み戻したロケットで、保存された
     * 軸は下で復元されるが、そのファイルが軸の導入より古いことがある。それも無ければ正直に答えられる物は
     * 残っていない。軸も速度も無いロケットはどこも向いておらず、方向を捏造すれば誰も選んでいない針路で世界
     * を横断させることになる。だから {@link #steer} は代わりに何もしない。
     */
    private Vec3 axis() {
        if (this.axis.lengthSqr() > 1.0E-8) {
            return this.axis;
        }

        Vec3 velocity = this.getDeltaMovement();

        return velocity.lengthSqr() < 1.0E-8 ? Vec3.ZERO : velocity.normalize();
    }

    /** パイロットがロックしていた相手をミサイルへ渡す。これが無ければ無誘導ロケットとして飛ぶ。 */
    public void setTarget(@Nullable Entity target) {
        this.target = target;
        this.entityData.set(DATA_TARGET, target == null ? -1 : target.getId());
    }

    @Nullable
    public Entity getTarget() {
        if (this.target == null) {
            int id = this.entityData.get(DATA_TARGET);
            this.target = id < 0 ? null : this.level().getEntity(id);
        }

        return this.target;
    }

    /**
     * サーバーが別のことを言った時、クライアントが追っていると思っていた相手を忘れる。
     *
     * <p>{@link #getTarget()} は見つけた物を保持する。ミサイルは飛行の毎tickと描画の毎フレームでそれを訊く
     * し、毎回 ID からエンティティを引くのは無料ではないからだ。しかし保持するということは他の誰もそれを
     * 変えられないということでもある。だからサーバーがミサイルをフレアへ向かわせた後や、目標を完全に諦めた
     * 後も、クライアントは機体へ向かって飛び続けた。両者は別々の物へ誘導し、差が「テレポート」と呼べるほど
     * 開いた時点でミサイルは一跳びでサーバーの経路へ戻された。ここで保持した答えを捨てることが、次の問い
     * 合わせに新しい答えを見せる。
     */
    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);

        if (DATA_TARGET.equals(key) && this.level().isClientSide) {
            this.target = null;
        }
    }

    /** まだ動力飛行中か。加速も誘導もできるのはその間。 */
    public boolean isBurning() {
        return this.age <= this.getRound().burnTicks();
    }

    /** 動力飛行中はノズルに噴煙があり、惰性飛行中は後ろの航跡だけ。 */
    @Override
    protected boolean underPower() {
        return this.getRound().hasMotor() && this.isBurning();
    }

    @Override
    protected void steer() {
        WeaponDefinition.Projectile round = this.getRound();
        Vec3 velocity = this.getDeltaMovement();
        Vec3 heading = this.axis();

        if (heading.lengthSqr() < 1.0E-8) {
            return;
        }

        boolean burning = this.isBurning() && round.hasMotor();
        // 「兵装が誘導できる種類か」ではなく「まだ追う相手がいるか」。何もロックせずに撃った物や、その後
        // 見失った物——{@link #lose} 参照。ここが判定するのと同じやり方で目標を消す——は、モーターが切れた
        // 時点で完全にロケットだ。機首を支える物が残っていないので、永遠に真っ直ぐな針路を保つのではなく
        // 重力で落ちていくべきで、兵装ファイルだけを見れば前者になってしまう。
        boolean guided = this.getWeapon().guidance().isPresent() && this.getTarget() != null;

        // 無誘導ロケットが向くのはモーターが残した方向だけ。モーターが切れれば機首を支える物は無く、重力
        // が勝手に軌道を弧にする。ロケットをロケットたらしめているのがそれだ。誘導弾はまったく別の機械で、
        // フィンはモーターが止まったずっと後までシーカーに応じ続ける。それこそが、無動力の惰性区間を「弾道
        // 落下」ではなく「迎撃」にしている。燃焼終了で誘導を切る——ミサイルを「数秒だけ操舵できるロケット」
        // として扱う——のが、実戦的な距離で撃った弾が燃焼中は真っ直ぐ飛び、その後まだ届いていない目標から
        // 落ちていった原因。
        if (!burning && !guided) {
            return;
        }

        // シーカーが機首を向けたい方向。追う相手のいない物にとっては今の機首方向そのもの。ロケットを回す
        // のはこれだけで、回す量はファイルの turn_rate（1tickあたり）を超えない。
        Vec3 wanted = guided ? this.guidedHeading(heading) : heading;

        // モーターは機首方向へ、保持できる速度まで押す。ロケットモーターは自分の機首を動かすのであって、
        // 機体を横滑りさせない。だからこの tick の速度は全部軸方向へ乗り、前 tick に重力が速度へ与えた分は
        // そのまま——針路の変化ではなく速さの変化として——残る。動力飛行中のロケットが発射時の線を飛ぶのは
        // それが理由。惰性飛行では足す推力が無く、速さは既にあった値そのもの。前 tick の fly() での重力に
        // よる落下分もそこに畳み込まれており、それを古い向きに残さず新しい向きへ乗せることが、フィンが
        // それに抗っている状態そのもの。実際の惰性区間のミサイルと同じ。
        double speed = burning
                ? Math.min(velocity.length() + this.thrustNow(round),
                        round.topSpeed() > 0.0F ? round.topSpeed() : Double.MAX_VALUE)
                : velocity.length();

        this.axis = wanted;
        this.setDeltaMovement(wanted.scale(speed));
    }

    /**
     * この tick にモーターが出している推力（1tick二乗あたりブロック）。
     *
     * <p>燃焼開始の最初の tick から全推力を出すわけではない。一度に全部渡されたモーターは数tickでミサイル
     * を最高速へ乗せてしまい、それは加速には見えない——最初からその速度で撃たれたように見える。代わりに
     * {@code spool_ticks} かけて立ち上げる。レール離脱時は少し、動き出せば全部、その間の1〜2秒でミサイルは
     * 目に見えて速度を積む。この値を書かないファイルは従来通り即座に全推力を得る。
     */
    private float thrustNow(WeaponDefinition.Projectile round) {
        int spool = round.spoolTicks();

        if (spool <= 0) {
            return round.thrust();
        }

        return round.thrust() * Mth.clamp((this.age + 1) / (float) spool, 0.0F, 1.0F);
    }

    /**
     * 目標が放出した物が、目標より魅力的かどうか。
     *
     * <p>誘導中は毎tick確認し、規則ではなく確率で決める。範囲内の各デコイがそれぞれ小さな確率でミサイルを
     * 奪うので、フレア1発は賭け、連続放出なら分の良い賭け、何も出さなければ確実な死になる。それがレバーを
     * 引くタイミングに価値を与えている。
     *
     * <p>数えるのは<em>この</em>シーカーを騙す種類だけ。そして一度奪われたら返らない。フレアへ向かった
     * ミサイルはフレアへ向かったのであり、その後することはそこへ飛び込んで空中で炸裂することだけ。
     */
    private void checkDecoys(WeaponDefinition.Guidance guidance) {
        if (this.getTarget() instanceof CountermeasureEntity) {
            return;
        }

        AABB box = this.getBoundingBox().inflate(DECOY_REACH);

        for (CountermeasureEntity decoy : this.level().getEntitiesOfClass(CountermeasureEntity.class, box,
                candidate -> candidate.fools(guidance.seeker()))) {
            if (this.random.nextFloat() < DECOY_CHANCE) {
                this.setTarget(decoy);

                return;
            }
        }
    }

    /**
     * この tick にミサイルが向くべき方向。予測点を追うのではなく真の比例航法で求める。実際のシーカーが
     * 打ち消すのは目標への視線の<em>回転</em>であって、その線上の特定の位置ではない。その線がまったく回ら
     * ない針路は、距離や接近速度が何であれ衝突針路だ。だから線を静止させることに成功したミサイルは衝突針路
     * に乗っている。その回転率の {@code nav_gain} 倍を、回っている方向へ回す——それが法則の全部。リード点も
     * 残存時間も無く、目標の進路について予測も仮定もしない。
     *
     * <p>追う相手の無い物——無誘導ロケット、何もロックせず撃たれたミサイル、追っていた相手を見失った物——は
     * 単に針路を保ち、以後ずっと保つ。
     */
    private Vec3 guidedHeading(Vec3 heading) {
        WeaponDefinition.Guidance guidance = this.getWeapon().guidance().orElse(null);
        Entity chasing = this.lost ? null : this.getTarget();

        // 追う相手が無いので何も追わない。これはロケットであり、残りの飛行の間ずっとロケットのままだ。
        // デコイ判定の後ではなく前に問うこと、それが規則の全部。何もロックせずレールを離れたミサイルは
        // シーカーが動いていないので、自分で目標を探しに行くこともなければ騙される相手にもならない——
        // 前方の空中へ投げ込まれた物はただの煙だ。既に見失った物も探し直さない。見失ったことこそが、それを
        // これにしたのだから。
        if (guidance == null || chasing == null || !chasing.isAlive()) {
            return heading;
        }

        if (!this.level().isClientSide) {
            this.checkDecoys(guidance);

            // 上の行でデコイに奪われた可能性がある。その場合は今それを追っている。
            chasing = this.getTarget();

            if (chasing == null || !chasing.isAlive()) {
                return heading;
            }
        }

        // 視線そのもの。目標の足元ではなく中心へ、そして予測点ではなく直線で——比例航法に予測点の出番は
        // 無い。
        Vec3 middle = chasing.position().add(0.0, chasing.getBbHeight() * 0.5, 0.0);
        Vec3 los = middle.subtract(this.position());
        double range = los.length();

        if (range < 1.0E-3) {
            return heading;
        }

        Vec3 losDirection = los.scale(1.0 / range);

        // 前方に見えなくなった物は永久に失われる。後ろを見られるシーカーはミサイルを振り切れない物にして
        // しまい、それは意図ではない。判定は視線そのものに対して行う。実際のジンバルが制限されるのはそこ
        // だから——シーカー視野の十分内側にあるリード点も、それを作った目標が視野の縁からはみ出していれば
        // 役に立たない。
        double off = Math.toDegrees(Math.acos(Mth.clamp(losDirection.dot(heading), -1.0, 1.0)));

        if (off > guidance.trackAngle()) {
            this.lose();

            return heading;
        }

        // 視線が回転する速さをベクトルで。向きが回転軸、長さが回転率そのもの（1tickあたりラジアン）。
        // (視線 × 相対速度) / |視線|^2 がその閉じた形——回転する位置ベクトルの角速度の標準的な恒等式——で、
        // 前 tick の記憶を必要としない。目標の運動について一度に1つの値しか渡されないクライアントでは
        // それが効く。
        Vec3 relativeVelocity = velocityOf(chasing).subtract(this.getDeltaMovement());
        Vec3 losRate = los.cross(relativeVelocity).scale(1.0 / (range * range));
        double rate = losRate.length();

        if (rate < 1.0E-9) {
            // 打ち消すべき回転が無い。既に一定方位の正面にいる＝そのままで衝突針路であり、何もする必要が
            // 無い。
            return heading;
        }

        // 至近距離では、この式が要求する回転率がファイルの許容値を遥かに超え得る。他の場所では特異点らしい
        // 特異点を持たない同じ閉形式が、距離0では特異点を持つ——そしてミサイルは設計上そこへ向かっている。
        // 従来通り turn_rate で頭打ちにする。実際のシーカーの操舵権限が尽きるのもそこなので、終末の数秒は
        // 「この式が要求し、現実には誰も作れない値」ではなく「ミサイルに残っている分」を引き出すことになる。
        double commanded = Math.min(guidance.navGain() * rate, Math.toRadians(guidance.turnRate()));

        return rotateAbout(heading, losRate.scale(1.0 / rate), commanded);
    }

    /** {@code vector} を {@code axis} 回りに {@code radians} だけ回す。軸は単位ベクトル前提。 */
    private static Vec3 rotateAbout(Vec3 vector, Vec3 axis, double radians) {
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);

        return vector.scale(cos)
                .add(axis.cross(vector).scale(sin))
                .add(axis.scale(axis.dot(vector) * (1.0 - cos)))
                .normalize();
    }

    /**
     * 目標を諦める——サーバー側で。それを決めてよいのはサーバーだけ。
     *
     * <p>クライアントがシーカー視野の縁に達する瞬間はサーバーとわずかにずれる。自分の針路と、1パケット古い
     * 数値で計算しているからだ。それに基づいて行動させると、サーバーがまだ誘導しているミサイルの操舵を
     * 止めてしまい、両者は差が「一跳びで直せる」ほど開くまで離れていく。だからクライアントはここで告げられ
     * た通りにしか従わない。サーバーが目標を消し、その消去は次のパケットで届く。
     */
    private void lose() {
        if (this.level().isClientSide) {
            return;
        }

        this.lost = true;
        this.setTarget(null);
    }

    /**
     * ミサイルが関心を持っている相手の実際の速度（1tickあたりブロック）。
     *
     * <p>意図的に deltaMovement を使わない。操縦者が乗っている機体はサーバーがまったく動かしていない——
     * 位置はパケットで届き、tick の間に適用される——ので、サーバー自身の tick の中から測れば「動いていない」
     * ことになり、サーバーはそこに意図的にゼロを保持している。真値を持っているのは飛ばしているクライアント
     * だ。deltaMovement を読めば両側は同じミサイルを別の数値で誘導する。クライアントは目標をリードし、
     * サーバーは追いかけ、描かれる物は位置パケットが届くたび2つの経路の間で引きずられる。
     * {@link VehicleEntityBase#getVelocity()} は両側で同じ値になる。
     */
    private static Vec3 velocityOf(Entity entity) {
        return entity instanceof VehicleEntityBase vehicle ? vehicle.getVelocity() : entity.getDeltaMovement();
    }

    /**
     * ミサイルは追っている相手に触れる必要が無い。これ以上近づけないところまで近づいた時点で炸裂する。
     *
     * <p>肝心なのは、判定をミサイルの到達点ではなくこの tick の飛行区間全体に対して行うこと。この種の物は
     * 1tickで30ブロック進むので、目標を綺麗に貫通したミサイルは何かが見る頃には100ブロック先におり、区間の
     * 端だけを測る信管は一度も作動しない。線分上の最近接点で測れば、tick のどこで通過したかに関わらず捉え
     * られる。
     */
    @Override
    @Nullable
    protected Vec3 earlyDetonation() {
        WeaponDefinition.Guidance guidance = this.getWeapon().guidance().orElse(null);
        Entity chasing = this.getTarget();

        if (guidance == null || chasing == null || !chasing.isAlive()) {
            return null;
        }

        // この tick の終わりに目標がいる位置。相手も動いているので。
        Vec3 middle = chasing.position().add(velocityOf(chasing))
                .add(0.0, chasing.getBbHeight() * 0.5, 0.0);
        Vec3 from = this.position();
        Vec3 to = from.add(this.getDeltaMovement());
        Vec3 nearest = nearestPointOn(from, to, middle);

        return nearest.distanceTo(middle) <= guidance.proximity() ? nearest : null;
    }

    /** 線分 {@code from}〜{@code to} 上で {@code target} に最も近い点。 */
    private static Vec3 nearestPointOn(Vec3 from, Vec3 to, Vec3 target) {
        Vec3 along = to.subtract(from);
        double lengthSqr = along.lengthSqr();

        if (lengthSqr < 1.0E-8) {
            return from;
        }

        double t = Mth.clamp(target.subtract(from).dot(along) / lengthSqr, 0.0, 1.0);

        return from.add(along.scale(t));
    }

    /** 寿命を迎えたロケットは静かに消えるのではなく炸裂する。 */
    @Override
    protected void expire() {
        if (this.getRound().explosion() > 0.0F && !this.level().isClientSide) {
            this.burst(this.position(), null);
        } else {
            this.discard();
        }
    }

    /**
     * コントローラーは無し。ミサイルに再生するアニメーションは無い。ジオメトリファイルから描かれ、
     * レンダラーが飛行経路に沿うよう回すだけで、自分で動く部分は何も無い。
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.animatableCache;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.entityData.set(DATA_TARGET, tag.contains("Target") ? tag.getInt("Target") : -1);
        this.lost = tag.getBoolean("Lost");
        this.axis = tag.contains("Axis")
                ? new Vec3(tag.getDouble("AxisX"), tag.getDouble("AxisY"), tag.getDouble("AxisZ"))
                : Vec3.ZERO;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Target", this.entityData.get(DATA_TARGET));
        tag.putBoolean("Lost", this.lost);
        // 保存するのは、速度がこれの安全な代用にならないから。翼から落ちて離れている最中に保存された物
        // は、地面を向いた状態で戻ってくる。
        tag.putBoolean("Axis", true);
        tag.putDouble("AxisX", this.axis.x);
        tag.putDouble("AxisY", this.axis.y);
        tag.putDouble("AxisZ", this.axis.z);
    }
}
