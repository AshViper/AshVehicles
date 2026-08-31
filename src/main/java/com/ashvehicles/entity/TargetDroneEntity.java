package com.ashvehicles.entity;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.ashvehicles.particle.Effects;
import com.ashvehicles.registry.ModParticles;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.joml.Vector3f;

import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 標的ドローン。展開した地点の上空を、撃たれるためだけに周回し続ける的。
 *
 * <p>存在する理由はソロテストだ。ミサイルのシーカーもレーダーも近接信管も、追う相手がいなければ何一つ
 * 試せないのに、追う相手は普通もう1人のパイロットを意味する。これはその代役——ロックでき、レーダーに
 * 載り、当たれば落ちる、動く空中目標——で、それ以上ではない。撃ち返さず、回避もせず、フレアも出さない。
 * 試験場の的が針路を変えないのと同じ理由で、予測可能であることこそ職務だから。
 *
 * <p><b>飛行は座標のばね2本だけ。</b> 中心からの距離を {@link #RADIUS} へ、高度を中心の高さへ引き戻し
 * ながら、常に接線方向へ {@link #SPEED} で進む。どこに置かれても——地面でも、飛行中の機体からでも——
 * 自力で登って輪に乗る。両側（サーバーとクライアント）が同じ式を同じ同期データから計算するので、
 * クライアントは位置パケットの合間も正しい場所を自分で描け、たまに届く位置は補正ではなく答え合わせに
 * なる。{@link VehicleProjectile} と同じ理屈。
 *
 * <p><b>世界には何も訊かない。</b> ブロックも衝突も重力も。輪の半径は数百ブロックあり、その大半は誰も
 * ロードしていない空の上だ。訊けばその場で地形が生成される（{@link CountermeasureEntity} 参照）。
 * 代償として、輪の内側に山があればドローンは山を通り抜ける。的をどこに置くかは展開する者の仕事。
 *
 * <p><b>シーカー・レーダー・追跡には名指しで加わっている。</b> {@link com.ashvehicles.weapon.TargetLock}
 * のロック対象は生き物と機体、{@link com.ashvehicles.sensor.Sensors} の走査対象は機体とプレイヤーと
 * ミサイルで、これはそのどれでもない。それぞれの判定と
 * {@link com.ashvehicles.mixin.EntityTrackingMixin} に1行ずつ足してある。
 */
public class TargetDroneEntity extends Entity implements GeoEntity {
    /** 残り耐久。半分を切ると煙を、四半分を切ると火を引くようクライアントも知る必要がある。 */
    private static final EntityDataAccessor<Float> DATA_HEALTH =
            SynchedEntityData.defineId(TargetDroneEntity.class, EntityDataSerializers.FLOAT);

    /**
     * 周回の中心。クライアントが自分で同じ円を計算するために送る。未設定の目印はゼロベクトル——本物の
     * (0,0,0) を中心にしたい者はいないし、なったところで害も無い。
     */
    private static final EntityDataAccessor<Vector3f> DATA_CENTER =
            SynchedEntityData.defineId(TargetDroneEntity.class, EntityDataSerializers.VECTOR3);

    /**
     * 撃ち落とすのに要る量。機銃なら数連射、ミサイルの弾頭なら1発で終わる値。的は「当てられたか」を
     * 教える物であって、耐える物ではない。
     */
    public static final float MAX_HEALTH = 60.0F;

    /** 巡航速度（1tickあたりブロック）。約52m/s——追うのは簡単で、見越し射撃は練習になる速さ。 */
    private static final double SPEED = 2.6;

    /**
     * 周回半径（ブロック）。この速度でおよそ2G・バンク60度の定常旋回になる値で、回避機動ではなく
     * 「素直に旋回し続ける目標」の範囲に収まる。輪の直径300ブロックは、展開した本人の周りのロード済み
     * chunk にほぼ収まる大きさでもある。
     */
    private static final double RADIUS = 150.0;

    /** 展開地点からどれだけ上を回るか（ブロック）。地上から使えば対空試験の高さになる。 */
    public static final double DEPLOY_CLIMB = 60.0;

    /** 半径のずれを内外方向の速度成分へ変える係数と、その上限（1tickあたりブロック）。 */
    private static final double RADIAL_GAIN = 0.02;
    private static final double MOST_RADIAL = 1.0;

    /** 高度のずれを上下方向の速度成分へ変える係数と、その上限（1tickあたりブロック）。 */
    private static final double VERTICAL_GAIN = 0.04;
    private static final double MOST_CLIMB = 0.7;

    /**
     * 1tickに速度をどれだけ曲げられるか（1tickあたりブロック毎tick）。周回の維持に要るのは
     * {@code SPEED²/RADIUS}≒0.045 で、その2倍強。展開直後の加速と輪への合流もこれで行う。
     */
    private static final double ACCEL = 0.1;

    /** バンク角の計算に使う重力（1tick²あたりブロック）。9.8m/s² をこの単位にした値。 */
    private static final double GRAVITY = 0.0245;

    /** 表示バンク角が目標へ寄る速さ。1で即座、0で動かない。 */
    private static final float ROLL_EASE = 0.08F;

    /** 煙を引き始める残り耐久の割合と、火も付く割合。 */
    private static final float SMOKING = 0.5F;
    private static final float BURNING = 0.25F;

    /** 撃墜された時の爆発の大きさ。{@link Effects} の尺度で。 */
    private static final float BLAST = 2.5F;

    /** 描く距離（ブロック）。3ブロックの機体はこの先ではどのみち点にもならない。 */
    private static final double RENDER_RANGE = 1024.0;

    private final AnimatableInstanceCache animatableCache = GeckoLibUtil.createInstanceCache(this);

    /** 前 tick の速度。レンダラーが機首の向きをフレーム間で混ぜるための記憶。 */
    private Vec3 lastTravel = Vec3.ZERO;

    /** 表示用のバンク角（度）。正で右翼下げ。物理には関与しない、見た目の全部。 */
    private float roll;
    private float rollO;

    /** 展開した者。アイテムのスニーク使用が「自分の物だけ」を回収するための記録。 */
    @Nullable
    private UUID owner;

    /**
     * 開いたまま保持している chunk。これが tick を買っている——確保の無いドローンはシミュレーション距離の
     * 縁で凍る（{@link TargetDroneChunkLoader} 参照）。保存・復元するのは、再起動後に戻ったドローンが
     * 保存済みチケットと突き合わせて要らない物を手放すため。
     */
    private Set<ChunkPos> held = Set.of();

    public TargetDroneEntity(EntityType<? extends TargetDroneEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /** 周回の中心を決め、持ち主を覚える。レベルへ追加する前に呼ぶ。 */
    public void deploy(Vec3 centre, @Nullable UUID owner) {
        this.entityData.set(DATA_CENTER, centre.toVector3f());
        this.owner = owner;
    }

    /** この者が展開した物か。 */
    public boolean ownedBy(Player player) {
        return player.getUUID().equals(this.owner);
    }

    public float getHealthFraction() {
        return Mth.clamp(this.entityData.get(DATA_HEALTH) / MAX_HEALTH, 0.0F, 1.0F);
    }

    private Vec3 centre() {
        Vector3f centre = this.entityData.get(DATA_CENTER);

        return new Vec3(centre.x(), centre.y(), centre.z());
    }

    /**
     * 円を描き、傷を煙で見せる。意図的に {@code super.tick()} を呼ばない。理由は
     * {@link CountermeasureEntity} と同じ——あれが調べる火・流体・足元は全部「たいていロードされていない
     * ブロック」を読むことで答えられてしまう。
     */
    @Override
    public void tick() {
        // アイテムを経ずに湧いた物（/summon）にも輪を与える。自分のいる場所の上空がその者の望んだ場所。
        if (!this.level().isClientSide && this.entityData.get(DATA_CENTER).lengthSquared() < 1.0E-6) {
            this.entityData.set(DATA_CENTER, this.position().add(0.0, DEPLOY_CLIMB, 0.0).toVector3f());
        }

        this.lastTravel = this.getDeltaMovement();
        this.rollO = this.roll;
        this.xOld = this.getX();
        this.yOld = this.getY();
        this.zOld = this.getZ();

        this.steer();

        Vec3 velocity = this.getDeltaMovement();

        this.setPos(this.getX() + velocity.x, this.getY() + velocity.y, this.getZ() + velocity.z);
        this.face(velocity);
        this.bank(velocity);

        if (this.level().isClientSide) {
            this.trail(velocity);
        } else {
            // 飛んだ後、この tick が知った最新の位置で。チケットの取得は chunk システムへ再入するので、
            // 呼んでよいのはこの tick の中だけ（TargetDroneChunkLoader 参照）。
            this.held = TargetDroneChunkLoader.update(this, this.held);
        }
    }

    /**
     * 今の輪の上を指定 tick 数ぶん先へ進んだ地点。chunk の確保と先読みが「これから来る地面」を名指しする
     * ために使う。輪に乗る前——展開直後の上昇・合流中——は弧の外挿に意味が無いので直線で答える。
     */
    public Vec3 alongOrbit(double ticks) {
        Vec3 centre = this.centre();
        double dx = this.getX() - centre.x;
        double dz = this.getZ() - centre.z;
        double distance = Math.sqrt(dx * dx + dz * dz);

        if (distance < 1.0E-3 || Math.abs(distance - RADIUS) > 32.0) {
            return this.position().add(this.getDeltaMovement().scale(ticks));
        }

        // steer() の接線は θ が増える向き（tangent = (-sinθ, cosθ)）なので、先は常に + 方向。
        double theta = Math.atan2(dz, dx) + SPEED * ticks / distance;

        return new Vec3(centre.x + Math.cos(theta) * distance, this.getY(),
                centre.z + Math.sin(theta) * distance);
    }

    /**
     * 1tick分の操縦。今いる場所から輪の上の「あるべき速度」を求め、そこへ {@link #ACCEL} の範囲で寄せる。
     *
     * <p>角度を積分するのではなく毎tick位置から導く。だからサーバーとクライアントがどれだけ別々に走って
     * も、両者は同じ円へ収束する——どこかで食い違った側は、次の tick に同じ式が輪へ引き戻す。
     */
    private void steer() {
        Vec3 centre = this.centre();
        double dx = this.getX() - centre.x;
        double dz = this.getZ() - centre.z;
        double distance = Math.sqrt(dx * dx + dz * dz);

        // 中心の真上に置かれた最初の tick だけ向きが無い。どちらでもよいので東を選ぶ。
        Vec3 outward = distance < 1.0E-3 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(dx / distance, 0.0, dz / distance);
        Vec3 tangent = new Vec3(-outward.z, 0.0, outward.x);

        // 輪までのずれを内外方向の成分へ。近ければ弱く、遠ければ上限まで。
        double radial = Mth.clamp((RADIUS - distance) * RADIAL_GAIN, -MOST_RADIAL, MOST_RADIAL);
        Vec3 flat = tangent.scale(SPEED).add(outward.scale(radial));
        double flatLength = flat.length();

        if (flatLength > 1.0E-6) {
            flat = flat.scale(SPEED / flatLength);
        }

        double climb = Mth.clamp((centre.y - this.getY()) * VERTICAL_GAIN, -MOST_CLIMB, MOST_CLIMB);
        Vec3 wanted = new Vec3(flat.x, climb, flat.z);
        Vec3 velocity = this.getDeltaMovement();
        Vec3 change = wanted.subtract(velocity);
        double gap = change.length();

        if (gap > ACCEL) {
            change = change.scale(ACCEL / gap);
        }

        this.setDeltaMovement(velocity.add(change));
    }

    /** 機首を進行方向へ。描画はレンダラーが速度から直接やるが、F3 や観戦視点にも正しい向きを見せる。 */
    private void face(Vec3 velocity) {
        if (velocity.horizontalDistanceSqr() < 1.0E-8) {
            return;
        }

        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
        this.setYRot((float) Math.toDegrees(Math.atan2(-velocity.x, velocity.z)));
        this.setXRot((float) -Math.toDegrees(Math.atan2(velocity.y, velocity.horizontalDistance())));
    }

    /**
     * 進路の曲がりから見た目のバンク角を求める。定常旋回なら物理と同じ「横加速度と重力の比」で、
     * この輪なら60度前後に落ち着く。左右の符号は {@code TargetDroneRenderer} の回転順に合わせてある
     * ——正が右翼下げ、右旋回で正。
     */
    private void bank(Vec3 velocity) {
        double wasFlat = this.lastTravel.horizontalDistance();
        double nowFlat = velocity.horizontalDistance();
        float target = 0.0F;

        if (wasFlat > 1.0E-4 && nowFlat > 1.0E-4) {
            // 正で左旋回になる外積の縦成分。
            double cross = this.lastTravel.z * velocity.x - this.lastTravel.x * velocity.z;
            double dot = this.lastTravel.x * velocity.x + this.lastTravel.z * velocity.z;
            double turn = Math.atan2(cross, dot);
            double lateral = nowFlat * Math.abs(turn);

            target = (float) (-Math.signum(turn) * Math.toDegrees(Math.atan2(lateral, GRAVITY)));
        }

        this.roll += (target - this.roll) * ROLL_EASE;
    }

    /** 傷んだ機体が引く煙と火。クライアント側だけで出すので、同期しているのは耐久の数字1つで済む。 */
    private void trail(Vec3 velocity) {
        float health = this.getHealthFraction();

        if (health > SMOKING || velocity.lengthSqr() < 1.0E-6) {
            return;
        }

        Vec3 back = velocity.normalize();
        Vec3 middle = this.position().add(0.0, this.getBbHeight() * 0.5, 0.0);

        // 1tickで機体は数ブロック進む。1点ずつ置くと点線になるので、歩の中に2つ置く。
        for (int i = 0; i < 2; i++) {
            Vec3 at = middle.subtract(back.scale(1.2 + i * 1.3));

            this.level().addParticle(ModParticles.MOTOR_SMOKE.get().of(Effects.SOOT, 0.8F),
                    at.x + this.random.nextGaussian() * 0.2, at.y + this.random.nextGaussian() * 0.2,
                    at.z + this.random.nextGaussian() * 0.2, 0.0, 0.0, 0.0);
        }

        if (health <= BURNING) {
            this.level().addParticle(ModParticles.FIRE.get().of(Effects.EMBER, 1.0F),
                    middle.x, middle.y, middle.z, 0.0, 0.0, 0.0);
        }
    }

    /**
     * 何に撃たれても同じ帳簿。機銃の1発も、近接信管の弾頭も、爆風も、全部ここへ届く
     * （{@link VehicleProjectile#onHitEntity}、{@link RocketEntity} の弾頭渡し参照）。
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide || this.isRemoved()) {
            return true;
        }

        if (this.isInvulnerableTo(source)) {
            return false;
        }

        this.markHurt();

        float health = this.entityData.get(DATA_HEALTH) - amount;

        this.entityData.set(DATA_HEALTH, health);

        if (health <= 0.0F) {
            this.destroy();
        }

        return true;
    }

    /** 撃墜。空中で炸裂して消える。残骸を降らせない——的は片付けまで含めて的だ。 */
    private void destroy() {
        if (this.level() instanceof ServerLevel level) {
            Vec3 at = this.position().add(0.0, this.getBbHeight() * 0.5, 0.0);

            Effects.detonate(level, at, BLAST, Effects.EMBER);
        }

        this.discard();
    }

    /** 静かに消える。アイテムのスニーク使用（回収）が使う側で、爆発はしない。 */
    public void recall() {
        this.discard();
    }

    /**
     * 撃墜・回収で消える時だけチケットを返す。アンロード（{@code setRemoved} 経由なのでここは通らない）
     * では残す——保存されたチケットが、再起動後にこのドローンを連れ戻す唯一の手段だから。
     */
    @Override
    public void remove(RemovalReason reason) {
        if (!this.level().isClientSide && reason.shouldDestroy()) {
            this.held = TargetDroneChunkLoader.release(this, this.held);
        }

        super.remove(reason);
    }

    /**
     * 世界から外されないため。これだけでは回り続け<em>ない</em>——tick は {@code ServerLevel} が
     * {@code inEntityTickingRange} で門前払いし、そこを通しているのは自分のチケットの方だ
     * （{@link TargetDroneChunkLoader} 参照）。これが買うのは、セクションごとアンロード・保存されて
     * 消えないこと、そして確保が一瞬切れてもシーカーの走査や追跡から外れないこと。
     */
    @Override
    public boolean isAlwaysTicking() {
        return true;
    }

    /** 撃たれるための物なので、弾にも照準にも見える。 */
    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < RENDER_RANGE * RENDER_RANGE;
    }

    /**
     * 描画のカリング用の箱は主翼のぶん広めに。素の当たり判定は胴体と翼根を覆うだけなので、それでカリング
     * すると「翼端がまだ画面内にあるのに機体が消える」瞬間ができる。ゴーストのスナップショットもこの箱を
     * 使い、ビルボードの大きさの元にもなる。
     */
    @Override
    public AABB getBoundingBoxForCulling() {
        return this.getBoundingBox().inflate(0.8, 0.3, 0.8);
    }

    /**
     * 2つの tick の間のこの瞬間の進行方向。{@link VehicleProjectile#travel} と同じ考え方で、
     * レンダラーが機首を滑らかに回すために使う。
     */
    public Vec3 travel(float partialTick) {
        Vec3 next = this.getDeltaMovement();

        if (this.lastTravel.lengthSqr() < 1.0E-8) {
            return next;
        }

        return this.lastTravel.add(next.subtract(this.lastTravel).scale(partialTick));
    }

    /** 表示バンク角（度）。正で右翼下げ。 */
    public float roll(float partialTick) {
        return Mth.lerp(partialTick, this.rollO, this.roll);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_HEALTH, MAX_HEALTH);
        builder.define(DATA_CENTER, new Vector3f());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.entityData.set(DATA_HEALTH, tag.contains("Health") ? tag.getFloat("Health") : MAX_HEALTH);

        if (tag.contains("CentreX")) {
            this.entityData.set(DATA_CENTER, new Vector3f(
                    tag.getFloat("CentreX"), tag.getFloat("CentreY"), tag.getFloat("CentreZ")));
        }

        this.owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;

        // 停止時に保持していた chunk。持ち主のチケットは保存されて先に戻っているので、これと突き合わせる
        // ことが「飛行より長生きするチケット」を防ぐ（TargetDroneChunkLoader 参照）。
        Set<ChunkPos> chunks = new LinkedHashSet<>();

        for (long packed : tag.getLongArray("HeldChunks")) {
            chunks.add(new ChunkPos(packed));
        }

        this.held = chunks.isEmpty() ? Set.of() : chunks;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        Vector3f centre = this.entityData.get(DATA_CENTER);

        tag.putFloat("Health", this.entityData.get(DATA_HEALTH));
        tag.putFloat("CentreX", centre.x());
        tag.putFloat("CentreY", centre.y());
        tag.putFloat("CentreZ", centre.z());

        if (this.owner != null) {
            tag.putUUID("Owner", this.owner);
        }

        if (!this.held.isEmpty()) {
            tag.putLongArray("HeldChunks", this.held.stream().mapToLong(ChunkPos::toLong).toArray());
        }
    }

    /** 動く部分は無い。ジオメトリから描かれ、レンダラーが進行方向へ向けるだけ。 */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.animatableCache;
    }
}
