package com.ashvehicles.entity;

import com.ashvehicles.particle.TintedParticleOption;
import com.ashvehicles.registry.ModParticles;
import com.ashvehicles.weapon.WeaponDefinition;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * フレア、またはチャフの雲。機体の代わりに撃たれるよう後方へ放出される物。
 *
 * <p>両方を1クラスで扱うのは、違いがほとんど無いから。素材、持続時間、どの種類のシーカーを騙すか、それ
 * だけだ。<em>すること</em>は同一——機体の後ろへ落ちていき、数秒間だけ機体より魅力的でいる。
 *
 * <p>パーティクルの塊ではなく本物のエンティティなのは、ミサイルが追えなければならないから。ミサイルが
 * 追い方を知っている物は全部エンティティなので、エンティティでないデコイは「何も騙せないデコイ」になる。
 * 追われる側については {@link RocketEntity}、まだ発射されていないロックを妨害する話は
 * {@link com.ashvehicles.weapon.TargetLock} を参照。
 *
 * <p><b>世界には何も訊かない。</b> ブロックも流体も衝突も。高高度で、たいてい誰もロードしていない地面の
 * 上で放出されるので、そこで投げうるどの問いも「その場で地形を生成する」ことで答えられてしまう。だから
 * 通常のエンティティ tick は一切走らせない——落ち、齢を重ね、消える。
 */
public class CountermeasureEntity extends Entity {
    /** どちらの種類か。クライアントが2つをかなり違う描き方をするので送信する。 */
    private static final EntityDataAccessor<Boolean> DATA_FLARE =
            SynchedEntityData.defineId(CountermeasureEntity.class, EntityDataSerializers.BOOLEAN);

    /** 各種類が追う価値を保つ時間（tick）。フレアは燃え尽き、チャフは漂って薄れる。 */
    private static final int FLARE_LIFE = 80;
    private static final int CHAFF_LIFE = 120;

    /** 放出された速度をどれだけ速く失うか、そしてどれだけ速く落ちるか。 */
    private static final double DRAG = 0.88;
    private static final double GRAVITY = 0.03;

    /** フレアの火。芯が白で縁がオレンジ。 */
    private static final int FLAME = 0xFFF0C0;
    private static final int EMBER = 0xFF9A2E;
    /** そしてチャフの金属箔。ごく小さく明るい「何でもない物」の大群。 */
    private static final int FOIL = 0xD8DCE0;

    /** 1tickあたりの粒数。フレアは1つの明るい物、チャフは雲で、雲には数が要る。 */
    private static final int FLARE_PUFFS = 2;
    private static final int CHAFF_PUFFS = 5;
    /** 1tick分のチャフを雲の中心からどれだけ散らすか（ブロック）。 */
    private static final double CHAFF_SPREAD = 1.6;

    private int age;

    public CountermeasureEntity(EntityType<? extends CountermeasureEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /** 種類を設定する。レベルへ追加する前に呼ぶ。 */
    public void setFlare(boolean flare) {
        this.entityData.set(DATA_FLARE, flare);
    }

    public boolean isFlare() {
        return this.entityData.get(DATA_FLARE);
    }

    /** その種類のシーカーが目標の代わりに追う物かどうか。 */
    public boolean fools(WeaponDefinition.Guidance.Seeker seeker) {
        return seeker.fooledBy(this.isFlare());
    }

    /** 寿命の残り。1から0まで。描画のフェードに使う。 */
    public float remaining() {
        return 1.0F - Math.min(1.0F, (float) this.age / this.life());
    }

    private int life() {
        return this.isFlare() ? FLARE_LIFE : CHAFF_LIFE;
    }

    /**
     * 落ち、齢を重ね、消える。意図的に {@code super.tick()} を呼ばない。あれが時間を使うのは火・流体・
     * ポータル・エンティティが立っている物についてで、900m 上空で燃えているフレアにはどれも用が無く、
     * しかもどれも「たいていロードされていないブロックを読む」ことで調べられる。
     */
    @Override
    public void tick() {
        Vec3 velocity = this.getDeltaMovement();

        this.xOld = this.getX();
        this.yOld = this.getY();
        this.zOld = this.getZ();
        this.setPos(this.getX() + velocity.x, this.getY() + velocity.y, this.getZ() + velocity.z);
        this.setDeltaMovement(velocity.scale(DRAG).subtract(0.0, GRAVITY, 0.0));

        if (this.level().isClientSide) {
            this.spawnSmoke();
        } else if (++this.age > this.life()) {
            this.discard();
        }
    }

    /** 燃焼、または雲。クライアント側で出すので、エンティティ1個で演出全部がまかなえる。 */
    private void spawnSmoke() {
        RandomSource random = this.random;
        float left = this.remaining();

        if (this.isFlare()) {
            // フレアは明るく小さく、シーカーが追っているのはその明るさ。
            send(ModParticles.BLAST.get().of(FLAME, 0.5F + left * 0.5F), FLARE_PUFFS, 0.12, random);
            send(ModParticles.SPARK.get().of(EMBER, 1.0F), FLARE_PUFFS, 0.25, random);

            return;
        }

        // チャフは逆。明るい物は一切なく、可能な限り広い空へ広がる。
        send(ModParticles.DEBRIS.get().of(FOIL, 0.6F), CHAFF_PUFFS, CHAFF_SPREAD * (1.0 - left) + 0.4, random);
    }

    private void send(TintedParticleOption particle, int count, double spread, RandomSource random) {
        for (int i = 0; i < count; i++) {
            this.level().addParticle(particle,
                    this.getX() + random.nextGaussian() * spread,
                    this.getY() + random.nextGaussian() * spread,
                    this.getZ() + random.nextGaussian() * spread,
                    0.0, 0.0, 0.0);
        }
    }

    /**
     * どこにいても落ち続ける。これは高高度で、誰からも遠く離れた場所へ放出される。そこで tick が止まった
     * エンティティは「空に永遠に浮かぶデコイ」になる。
     */
    @Override
    public boolean isAlwaysTicking() {
        return true;
    }

    /** 誰も撃たず、ぶつからず、手を伸ばさない。煙と火なので。 */
    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /** 送られる距離まで描く。要点は「ミサイルがそちらへ行くのを見る」ことなので。 */
    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_FLARE, true);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.setFlare(tag.getBoolean("Flare"));
        this.age = tag.getInt("Age");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putBoolean("Flare", this.isFlare());
        tag.putInt("Age", this.age);
    }

    /** 誰も乗らないし、何も乗せない。 */
    @Override
    public boolean canBeHitByProjectile() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    /** 位置。追うかどうかを判断するミサイル向け。 */
    public Vec3 middle() {
        return this.position();
    }

    /** 他のエンティティが必要とする情報全部を、世界に何も訊かずに。 */
    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean displayFireAnimation() {
        return false;
    }

    @Override
    public boolean isIgnoringBlockTriggers() {
        return true;
    }

    /** 何の邪魔にもならない。放出した機体に対しても。 */
    @Override
    public boolean canCollideWith(Entity other) {
        return false;
    }
}
