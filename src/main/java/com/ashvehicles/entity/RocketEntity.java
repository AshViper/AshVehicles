package com.ashvehicles.entity;

import javax.annotation.Nullable;

import com.ashvehicles.network.HitReportPayload;
import com.ashvehicles.registry.ModEntities;
import com.ashvehicles.weapon.WeaponDefinition;
import com.ashvehicles.weapon.WeaponMounts;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
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
 * 目標には外れる。そして前方に見えている物しか追わないので、背後へ回った目標は失探する。失探したミサイルは
 * しばらく視野内を探し直し（{@link #searchAgain} 参照）、取り戻せなければ自爆する。無条件に命中する物は
 * 1つも無く、それが要点だ。ミサイルは破られるべき物である。
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
    /**
     * これ以内をかすめた弾頭は直撃扱い（ブロック）。信管の効く距離がこれより短い弾では
     * {@link #CONTACT_SHARE} の方が効く。
     */
    private static final double CONTACT = 1.5;

    /** 信管の効く距離のうち、直撃扱いになる割合。近接信管の短い弾で {@link #CONTACT} の代わりに使う。 */
    private static final double CONTACT_SHARE = 0.4;

    private static final double DECOY_REACH = 40.0;

    /**
     * 失探後の捜索でシーカーが手を伸ばす最大距離（ブロック）。ファイルの {@code lock_range} がこれより
     * 短ければそちらに従う。
     *
     * <p>上限があるのは、この捜索が毎tick走る箱の問い合わせだから。箱の問い合わせのコストは中身ではなく
     * 箱の大きさで決まり（{@link com.ashvehicles.weapon.TargetLock} の掃引参照）、空対空ミサイルの
     * {@code lock_range} は km 単位だ。失探は終末機動の至近距離で起きる物なので、捜索がその距離まで
     * 届く必要はそもそも無い。
     */
    private static final double SEARCH_REACH = 160.0;

    /**
     * 目標の熱量／反射がどれだけ小さくても、デコイの魅力をこれ以上は増やさない下限。
     * {@link #checkDecoys} 参照。
     */
    private static final float FAINTEST_TARGET = 0.25F;

    /**
     * 視線誘導が残差のどれだけを1tickで詰めようとするか。
     *
     * <p>1にすると残差をその tick で消しに行く——舵一杯で突っ込み、線に乗った瞬間に行き過ぎる。小さいほど
     * 滑らかで、遅い。0.2 は残差が5tickで概ね1/3になる速さで、照準の振りには十分付いてくる。
     */
    private static final double BEAM_GAIN = 0.2;

    /**
     * 旋回速度が命令に追い付く速さ（1tickあたりの割合）。フィンの効きの立ち上がりで、軌跡の角を落とす。
     */
    private static final double BEAM_SMOOTH = 0.25;

    /**
     * 狙っている点がこれより後ろにあれば、もう追わない（ラジアン）。
     *
     * <p>誘導を手放された弾が点を追い越したときの振る舞いを決める。振り返らせると点の周りを回り続けるので、
     * そのまま飛ばす。90度より少し広く取ってあるのは、照準を大きく振った瞬間の一時的な大角度で誘導を
     * 捨てないため。
     */
    private static final double BEAM_ABANDON = Math.toRadians(110.0);

    private static final EntityDataAccessor<Integer> DATA_TARGET =
            SynchedEntityData.defineId(RocketEntity.class, EntityDataSerializers.INT);

    @Nullable
    private Entity target;
    /**
     * シーカーが追っていた相手を見失ったら true。以後しばらくは捜索モードで、ファイルの
     * {@code reacquire_ticks} の間に視野へ戻ってきた物を捉え直せなければ自爆する。サーバー専用。
     * {@link #searchAgain} 参照。
     */
    private boolean lost;
    /** 見失った相手。捜索中、視野内の他の何かより優先して取り戻しに行く。 */
    private int lostId = -1;
    /** 失探してからの tick 数。{@code reacquire_ticks} と比べる。 */
    private int searching;

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

    /**
     * 視線誘導の弾が今出している旋回速度（1tickあたりラジアン）。{@link #follow} 参照。
     *
     * <p>命令ではなく<em>効き</em>を持つ。フィンは命令の跳ねをそのまま出さないので、ここが1次遅れで命令を
     * 追い、軌跡が折れるのを防ぐ。同期しない——両側が同じ命令から同じ値へ収束するし、ずれても絵の話だ。
     */
    private double beamRate;

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

    /**
     * 追っているのが地上の光点なら、それをまだ保持していると伝える。
     *
     * <p>{@link DesignationEntity} は誰も保持しなくなると数秒で自ら消える。撃った側が撃った直後に手を離す
     * 兵器——座標を渡して架台を畳む弾道弾発射機（{@code TurretLauncher}）——では、その数秒の後に弾の目標が
     * 消えることになる。だから飛んでいる弾自身が保持者になる。渡された物を最後まで持っていくのは弾の仕事
     * であり、渡した側がまだそれを見ているかどうかとは無関係だ。
     *
     * <p>解除がマークを<em>破棄</em>する経路（機体の照準ポッド、{@code AircraftEntity.clearDesignation}）は
     * これに影響されない。破棄されたマークは死んでおり、保持を主張する相手も残っていない。
     */
    private void holdMark() {
        if (this.getTarget() instanceof DesignationEntity mark) {
            mark.held();
        }
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

        // 失探中のシーカーは黙って弾道飛行に落ちるのではなく、まず視野内を探し直す。目標を決めてよいのは
        // サーバーだけなので、こちらだけで回す。捉え直せばこの tick からもう誘導が戻っている。
        if (!this.level().isClientSide) {
            this.holdMark();

            if (this.lost) {
                this.searchAgain(heading);
            }
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

        // 空気が持っていく分。この tick に舵を何度切ったかを渡すので、真っ直ぐ飛ぶミサイルと曲がっている
        // ミサイルでは削られ方が違う。
        double turned = Math.acos(Mth.clamp(heading.dot(wanted), -1.0, 1.0));

        speed = Math.max(0.0, speed - this.drag(round, speed, turned, burning));

        this.axis = wanted;
        this.setDeltaMovement(wanted.scale(speed));
    }

    /**
     * この tick に空気が奪う速さ（1tickあたりブロック）。
     *
     * <p>2つの足し算。<em>素の抗力</em>は速さの2乗に比例し、燃焼が終わってから効く——燃え尽きたミサイルが
     * 目標まで同じ速さで滑っていくのではなく、確実に遅くなっていく理由。<em>誘導抗力</em>は舵を切った分
     * だけ余分に奪い、こちらは燃焼中も効く——急旋回したミサイルが速度を失い、失った速度が戻らないという、
     * 回避が成立する仕組みそのもの。
     *
     * <p>係数はどちらも兵装ファイルの物で、既定値の狙いは
     * {@link WeaponDefinition.Projectile#DEFAULT_DRAG} と
     * {@link WeaponDefinition.Projectile#DEFAULT_TURN_DRAG} にある。0 を書けば従来通り、空気の無い世界を
     * 飛ぶ。
     *
     * @param turned この tick に機首が回った角（ラジアン）
     */
    private double drag(WeaponDefinition.Projectile round, double speed, double turned, boolean burning) {
        double air = burning ? 0.0 : round.drag() * speed * speed;
        double induced = round.turnDrag() * speed * turned;

        return air + induced;
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
     * <p>確率は一律ではない。シーカーは2つの光源を<em>比べて</em>いるので、賭けの分は両者の明るさで動く。
     * デコイ側は残り寿命——放出直後のフレアが最も明るく、燃え尽きかけの物はほぼ何でもない。目標側は自分の
     * 被探知性——熱源追尾に対してはアフターバーナーの熱、レーダー追尾に対しては反射断面積。バーナーを
     * 焚いたまま逃げる機体はフレアを出しても分が悪く、絞ってから出せば同じフレアがずっと良く効く。それが
     * 「レバーを引くだけ」を「レバーを引き、スロットルも絞る」にしている。
     *
     * <p>そして視野の外のデコイは存在しないのと同じ。シーカーに見えない物はシーカーを騙せない。真後ろへ
     * 抜けたフレアに賭け続けるミサイルは、フレアではなく乱数に負けている。
     *
     * <p>数えるのは<em>この</em>シーカーを騙す種類だけ。そして一度奪われたら返らない——正確には、フレアが
     * 燃え尽きた時に「追う物が消えた」として失探扱いになり、そこからは他の失探と同じ捜索が走る。
     */
    private void checkDecoys(WeaponDefinition.Guidance guidance) {
        Entity chasing = this.getTarget();

        if (chasing instanceof CountermeasureEntity) {
            return;
        }

        Vec3 heading = this.axis();

        if (heading.lengthSqr() < 1.0E-8) {
            return;
        }

        double narrowest = Math.cos(Math.toRadians(guidance.trackAngle()));
        // 目標がどれだけ目立つか。シーカーの種類ごとに見ている物が違う——TargetLock.reachAgainst と同じ
        // 区別。暗い目標ほどデコイが際立つが、下限は設ける。完全なステルスがフレア1発で必中で振り切る
        // 世界にはしない。
        float glare = Math.max(FAINTEST_TARGET, switch (guidance.seeker()) {
            case HEAT -> AircraftEntity.heatVisibility(chasing);
            case RADAR -> AircraftEntity.visibility(chasing);
            case LASER, POINT, BEAM -> 1.0F;
        });
        AABB box = this.getBoundingBox().inflate(DECOY_REACH);

        for (CountermeasureEntity decoy : this.level().getEntitiesOfClass(CountermeasureEntity.class, box,
                candidate -> candidate.fools(guidance.seeker()))) {
            Vec3 gap = decoy.middle().subtract(this.position());
            double distance = gap.length();

            // シーカー視野の外にある物は比較の土俵に載らない。
            if (distance < 1.0E-3 || gap.scale(1.0 / distance).dot(heading) < narrowest) {
                continue;
            }

            if (this.random.nextFloat() < guidance.seduction() * decoy.remaining() / glare) {
                this.setTarget(decoy);

                return;
            }
        }
    }

    /**
     * 失探中の1tick分の捜索。見失った相手が視野へ戻れば取り戻し、代わりに別の有効目標が視野に入れば
     * それを取り、どちらも起きないまま {@code reacquire_ticks} が尽きれば——{@link #earlyDetonation} が
     * 自爆させる。
     *
     * <p>TRACK LOST → SEARCH → REACQUIRE。失探を永久にしないのは、それが「振り切られたミサイルは無害」
     * を意味してしまうから。旋回で視野から抜けるのは正しい対抗機動のままだが、抜けた後もミサイルの視野を
     * 横切って戻るのは間違いになる。
     *
     * <p>捜索範囲は意図的に狭い。{@link #SEARCH_REACH} 参照。箱の問い合わせは箱の大きさで払うので、
     * ファイルの {@code lock_range}——km 単位になり得る——をそのまま毎tick歩かせはしない。
     */
    private void searchAgain(Vec3 heading) {
        WeaponDefinition.Guidance guidance = this.getWeapon().guidance().orElse(null);

        if (guidance == null || guidance.reacquireTicks() <= 0) {
            return;
        }

        this.searching++;

        double reach = Math.min(guidance.lockRange(), SEARCH_REACH);
        double narrowest = Math.cos(Math.toRadians(guidance.trackAngle()));
        Vec3 from = this.position();
        AABB box = this.getBoundingBox().inflate(reach);
        Entity best = null;
        double bestAlignment = narrowest;

        for (Entity candidate : this.level().getEntities(this, box, this::couldReacquire)) {
            Vec3 middle = candidate.position().add(0.0, candidate.getBbHeight() * 0.5, 0.0);
            Vec3 gap = middle.subtract(from);
            double distance = gap.length();

            if (distance > reach || distance < 1.0E-3) {
                continue;
            }

            double alignment = gap.scale(1.0 / distance).dot(heading);

            if (alignment < narrowest) {
                continue;
            }

            // 見失った当人が視野内にいるなら、より中央の別人がいてもそちらへ戻る。シーカーが覚えている
            // のはその相手の信号だから。
            if (candidate.getId() == this.lostId) {
                best = candidate;

                break;
            }

            if (alignment > bestAlignment) {
                bestAlignment = alignment;
                best = candidate;
            }
        }

        if (best != null) {
            this.lost = false;
            this.searching = 0;
            this.setTarget(best);
        }
    }

    /**
     * 失探からの捜索が拾ってよい相手。{@link com.ashvehicles.weapon.TargetLock} の couldTarget と同じ
     * 名簿で、デコイだけを除いた物。捜索中のシーカーがフレアを「再取得」するなら、フレア1発が確実に
     * ミサイルを空へ捨てさせることになり、確率で決めている {@link #checkDecoys} の意味が無くなる。
     *
     * <p>撃てる相手の種類を増やす時はここも1行増える。名簿を持つ場所は既に5つあり、これが6つ目。
     */
    private boolean couldReacquire(Entity candidate) {
        Entity vehicle = this.firedFrom();

        // 他人のミサイルは有効目標。自分と同じレールから出た物は違う——並走する僚弾を「再取得」した
        // 迎撃弾は、撃たれた理由だった相手を見捨てて味方を食う。
        if (candidate instanceof RocketEntity missile) {
            return missile.isInterceptable() && !missile.wasFiredBy(vehicle);
        }

        if (candidate instanceof VehicleProjectile || candidate instanceof CountermeasureEntity) {
            return false;
        }

        if (vehicle != null && (candidate == vehicle || WeaponMounts.isPartOf(vehicle, candidate))) {
            return false;
        }

        if (!candidate.isAlive() || candidate.isSpectator()) {
            return false;
        }

        if (candidate.getVehicle() instanceof VehicleEntityBase) {
            return false;
        }

        if (candidate instanceof VehicleEntityBase machine) {
            return !machine.isWrecked();
        }

        if (candidate instanceof TargetDroneEntity) {
            return true;
        }

        return candidate instanceof LivingEntity;
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

        // 追う相手が無いので何も追わない。何もロックせずレールを離れたミサイルはシーカーが動いていない
        // ので、自分で目標を探しに行くこともなければ騙される相手にもならない——前方の空中へ投げ込まれた物
        // はただの煙だ。失探した物の探し直しはここではなく searchAgain の仕事で、取り戻した tick には
        // lost が false に戻っているので、この行はまた通らなくなる。
        if (guidance == null || chasing == null) {
            return heading;
        }

        // 追っていた物が消えた。撃墜された機体、そして燃え尽きたフレア——奪われたミサイルの信号は本当に
        // そこで消える。どちらも「視野から外れた」のと同じ失探として扱い、捜索と自爆の時計が回り始める。
        if (!chasing.isAlive()) {
            this.lose();

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

        // 視線誘導は見越さない。狙っている点そのものへ機首を向ける。
        //
        // <p>比例航法は<em>動く物に当てる</em>ための式で、視線の回転率を打ち消すことで衝突針路を作る。追って
        // いるのが照準線上に置かれた一点では、打ち消すべき回転が最初からほとんど無い——点は動かないし、その
        // うえ射手が線を振ると位置が飛ぶだけで速度を持たないので、式に渡る回転率は距離で割られてほぼ0になる。
        // 3750ブロック先の点に対しては、要求される旋回が実際に持っている舵の1%にもならなかった。
        //
        // <p>だからこの弾は追尾ではなく追従で飛ぶ。狙われている線へ機首を向け、舵の許す速さでそこへ寄せる。
        // 有線誘導のミサイルが実際にすることであり、射手が照準を振った分だけ弾が付いてくるという操作感も、
        // 見越しではなくこちらから出る。
        if (guidance.seeker() == WeaponDefinition.Guidance.Seeker.BEAM) {
            return this.follow(heading, losDirection, guidance);
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

    /**
     * 信管が作動した。爆風の前に、弾頭を追っていた相手へ渡す。
     *
     * <p><b>これが無いと兵装ファイルの {@code damage} が誘導弾では一度も使われない。</b> 近接信管を持つ
     * ミサイルは相手に触れる前に炸裂する——それが近接信管だ——ので、{@code onHitEntity} の直撃経路には
     * 一度も入らない。残るのはバニラの爆発だけで、あれは炸薬の大きさしか見ない。200点の弾頭を積んだ
     * ミサイルが、爆風の10点だけを置いて消えていたのはそれが理由。
     *
     * <p>渡す量は外した距離で減る。破片は球状に広がるので、機体を貫く距離を通ったミサイルは弾頭全部を、
     * 信管の効く縁をかすめたミサイルは何も渡さない——縁の側にも爆風はあり、そちらは従来通り届く。
     */
    @Override
    protected void detonate(Vec3 where) {
        Entity chasing = this.getTarget();
        WeaponDefinition.Guidance guidance = this.getWeapon().guidance().orElse(null);

        if (guidance != null && chasing != null && chasing.isAlive()) {
            this.warhead(chasing, where, guidance.proximity());
        }

        this.scatter(where);
        super.detonate(where);
    }

    /**
     * クラスター弾頭を開く。炸裂点から子弾を撒く。
     *
     * <p>撒くだけで、ここは何も壊さない。破壊は子弾が地面に着いた時に、子弾自身のファイルが書いている規模で
     * 起こる。だから親の {@code explosion} は開傘の合図であるべきで、0でも構わない——弾頭の重さは数×規模の
     * 方に入っている。{@link WeaponDefinition.Cluster} 参照。
     *
     * <p><b>子弾は普通の弾だ。</b>誘導を持たないので落ちるに任せ、触れた物で炸裂し、着弾は他の弾とまったく
     * 同じ経路を通る。撃った者も撃った機体も親から受け継ぐので、当てた責任の行き先も変わらない。
     *
     * <p>撒く速度は3つの合成だ。親の速度の一部（{@code inherit}）で前方向の勢いを残し、横向きのばらつき
     * （{@code spread}）で撒布界を作り、鉛直成分は与えない——落下は重力の仕事であって、撒く側の仕事ではない。
     */
    private void scatter(Vec3 where) {
        WeaponDefinition.Cluster cluster = this.getWeapon().cluster().orElse(null);

        if (cluster == null || !(this.level() instanceof ServerLevel level)) {
            return;
        }

        Entity vehicle = this.firedFrom();
        Vec3 inherited = this.getDeltaMovement().scale(cluster.inherit());

        for (int i = 0; i < cluster.count(); i++) {
            RocketEntity bomblet = new RocketEntity(ModEntities.ROCKET.get(), level);

            bomblet.setup(cluster.submunition(), vehicle == null ? this : vehicle, this.getOwner());
            bomblet.setPos(where);
            bomblet.launch(inherited.add(
                    this.random.nextGaussian() * cluster.spread(), 0.0,
                    this.random.nextGaussian() * cluster.spread()));

            level.addFreshEntity(bomblet);
        }
    }

    /** 弾頭1発分を、外した距離で目減りさせて相手へ。 */
    private void warhead(Entity target, Vec3 where, float reach) {
        Vec3 middle = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
        float share = fragments(where.distanceTo(middle), reach);
        float damage = this.getRound().damage() * share;

        if (damage <= 0.0F) {
            return;
        }

        target.hurt(this.damageSource(), damage);
        // 直撃と同じく撃った者だけに伝える。近接信管で終わった弾は目標に触れていないので、これが無いと
        // 命中は画面上のどこにも現れない。
        HitReportPayload.report(this.getOwner(), target, where, this.getDeltaMovement(), damage, false);
    }

    /**
     * その外し方で弾頭のどれだけが届くか。0から1まで。
     *
     * <p>信管の効く距離の内側でも、近ければ近いほど多く届く。ただし「ほぼ触れている」範囲では全部届く
     * ——実際の弾頭も、機体の直近で炸裂すればその機体を貫く破片を出すだけの密度を持っている。
     */
    private static float fragments(double miss, float reach) {
        if (reach <= 0.0F) {
            return 1.0F;
        }

        double contact = Math.min(CONTACT, reach * CONTACT_SHARE);

        if (miss <= contact) {
            return 1.0F;
        }

        return (float) Mth.clamp(1.0 - (miss - contact) / (reach - contact), 0.0, 1.0);
    }

    /** {@code vector} を {@code axis} 回りに {@code radians} だけ回す。軸は単位ベクトル前提。 */
    /**
     * 機首を、狙っている方向へ舵の許す分だけ寄せる。見越しは無い。
     *
     * <p>{@link #guidedHeading} の比例航法と対になる、単純な追従。ただし<em>単純に舵一杯</em>ではない。
     * 命じる旋回は残差に比例させ（{@link #BEAM_GAIN}）、そのうえで実際の旋回速度を1次遅れで追わせる
     * （{@link #BEAM_SMOOTH}）。理由は2つとも同じで、角の立った軌跡を出さないためだ:
     *
     * <ul>
     * <li><b>比例</b>——残差が小さいときまで舵一杯を切れば、線に乗った瞬間に行き過ぎる。残差に比例させれば
     *     指数的に寄って、乗ったところで止まる。</li>
     * <li><b>1次遅れ</b>——舵は瞬時に効かない。命令が跳ねてもフィンの効きは数tickかけて立ち上がるので、
     *     照準を急に振っても軌跡は折れずに曲がる。</li>
     * </ul>
     *
     * <p>上限は {@code turn_rate} のまま。だから照準を速く振れば弾は遅れて付いてくるし、振り切れば置いて
     * いかれる。
     *
     * <p><b>後ろにある点は追わない。</b>照準が別の兵装へ移れば点は更新されなくなり、弾はやがてその点を追い
     * 越す。そこで振り返らせると、弾は点の周りを永遠に回る——空に輪を描く。追い越したらそのまま飛ばす方が、
     * 誘導を手放された弾のすることとして正しい。
     */
    private Vec3 follow(Vec3 heading, Vec3 wanted, WeaponDefinition.Guidance guidance) {
        double away = Math.acos(Mth.clamp(heading.dot(wanted), -1.0, 1.0));

        if (away > BEAM_ABANDON) {
            this.beamRate = 0.0;

            return heading;
        }

        Vec3 axis = heading.cross(wanted);

        // ちょうど正面か正反対。回す軸が決まらないので、この tick は舵を戻すだけにする。
        if (away < 1.0E-6 || axis.lengthSqr() < 1.0E-12) {
            this.beamRate *= 1.0 - BEAM_SMOOTH;

            return heading;
        }

        double demand = Math.min(away * BEAM_GAIN, Math.toRadians(guidance.turnRate()));

        this.beamRate += (demand - this.beamRate) * BEAM_SMOOTH;

        return this.beamRate < 1.0E-7 ? heading
                : rotateAbout(heading, axis.normalize(), Math.min(this.beamRate, away));
    }

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

        // 誰を見失ったかを覚えてから手放す。捜索はその相手を優先して取り戻しに行く。searchAgain 参照。
        Entity was = this.getTarget();

        this.lost = true;
        this.lostId = was == null ? -1 : was.getId();
        this.searching = 0;
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

        if (guidance == null) {
            return null;
        }

        // 捜索が空振りのまま尽きた。振り切られたミサイルの終わり方は「どこか遠くの地面に落ちる」ではなく
        // 自爆で、それは律儀に近接信管の経路を通る——弾頭は目標がいないので渡らず、爆発だけが空に置かれる。
        if (this.lost && guidance.reacquireTicks() > 0 && this.searching > guidance.reacquireTicks()) {
            return this.position();
        }

        // 信管はまだ寝ている。発射直後のミサイルはレールの横の自機や隣を飛ぶ僚機の鼻先を必ず通るので、
        // その数tickは目標の横を通ろうと炸裂しない。直撃は別——触れた物には触れた時に当たる。
        if (this.age < guidance.armTicks()) {
            return null;
        }

        Entity chasing = this.getTarget();

        if (chasing == null || !chasing.isAlive()) {
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

    /**
     * 迎撃の対象になり得るか。飛行中の誘導ミサイルだけ。
     *
     * <p>ミサイルだけなのは名簿の各所（{@link com.ashvehicles.weapon.TargetLock} など）がシーカーに
     * 見せる物を選ぶ問いだから。機関砲の1発や無誘導ロケットの雨をロック候補に載せれば、スコープは
     * 撃ち合いの間じゅう弾で埋まり、本当の脅威——こちらへ向かって曲がってくる1本——がその中に紛れる。
     */
    public boolean isInterceptable() {
        return this.isAlive() && this.getWeapon().type() == WeaponDefinition.Type.MISSILE;
    }

    /** この弾を撃ったのがその機体か。自分の撃った物をロックしないための問い。 */
    public boolean wasFiredBy(@Nullable Entity vehicle) {
        return vehicle != null && this.firedFrom() == vehicle;
    }

    /**
     * 撃たれたミサイルはそこで終わる。骨組みとモーターと弾頭を薄皮で包んだ物に、破片を受けて飛び続ける
     * 余地は無い。
     *
     * <p>届く経路は2つ。迎撃弾の弾頭渡し（{@link #warhead}——近接信管はここへ来る）と、至近で起きた爆発の
     * 爆風。どちらも量は見ない。1点でも届けば炸裂して落ちる。
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide || this.isRemoved()) {
            return true;
        }

        if (this.isInvulnerableTo(source) || amount <= 0.0F) {
            return false;
        }

        this.markHurt();
        this.burst(this.position().add(0.0, this.getBbHeight() * 0.5, 0.0), null);

        return true;
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
        this.lostId = tag.contains("LostId") ? tag.getInt("LostId") : -1;
        this.searching = tag.getInt("Searching");
        this.axis = tag.contains("Axis")
                ? new Vec3(tag.getDouble("AxisX"), tag.getDouble("AxisY"), tag.getDouble("AxisZ"))
                : Vec3.ZERO;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Target", this.entityData.get(DATA_TARGET));
        tag.putBoolean("Lost", this.lost);
        tag.putInt("LostId", this.lostId);
        tag.putInt("Searching", this.searching);
        // 保存するのは、速度がこれの安全な代用にならないから。翼から落ちて離れている最中に保存された物
        // は、地面を向いた状態で戻ってくる。
        tag.putBoolean("Axis", true);
        tag.putDouble("AxisX", this.axis.x);
        tag.putDouble("AxisY", this.axis.y);
        tag.putDouble("AxisZ", this.axis.z);
    }
}
