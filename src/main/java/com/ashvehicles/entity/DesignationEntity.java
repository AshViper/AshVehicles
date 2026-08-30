package com.ashvehicles.entity;

import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * 照準ポッドが地面に当てている光点。位置以外に何も持たないエンティティ。
 *
 * <p>この MOD で何かへ向かう物は、全部<em>エンティティ</em>へ向かう。{@link RocketEntity} は目標に位置を
 * 訊き、{@code proximityTarget} はどれだけ近づいたかを測り、計器はその周りに枠を描く。指示された座標は
 * エンティティではないので、動かない目標のためにそれら全部をもう一度、並行して書く必要が出てしまう。だから
 * 代わりにエンティティを与える。パイロットが見ている場所へ置いたマーカーで、レーザー誘導兵装はそれを、機体
 * を追うのとまったく同じように追う。
 *
 * <p>それ以外の意味では世界の中の物ではない。誰にも見えず、撃てず、ぶつかれず、手も伸ばせない。描画も
 * 完全に無い。パイロットが見るべきなのは、地面の上に浮かぶ物体ではなく計器が地表に重ねて描くマークだから。
 * 落ちず、齢を重ねず、世界には「答えさせられてしまう問い」を一切投げない——立っている地面はたいてい誰も
 * ロードしていない遥か遠くにあり、普通のやり方で問えばその場で地形を生成することになる。
 *
 * <p><b>ロード範囲外に置かれたマークは、後から地面へ降りる。</b> ポッドは望遠鏡で、どのクライアントも
 * chunk を持たない距離で使われる。だからそこで行われた指示に付いてくるのは、パイロットの視線を仮定した
 * 地面高さへ落とした点だ。だいたい正しい地面の列で、高さはクライアントに見えていた最後の実地面のもの。
 * キーを押した瞬間にどちら側の誰が出せる答えとしてもそれが最善で、そして誰かがその地面をロードした瞬間に
 * 最善でなくなる——それはまさに、そこへ向かう爆弾が着弾の数秒前にやることだ。飛翔中の弾はこれから飛ぶ
 * chunk を確保するので。だからマークは自分の列が現れるのを見張り、現れたらパイロットの視線に沿って実地表
 * まで歩いて降りる。chunk を要求することは決してなく、既にある物だけを使う——推定である意味の全部は、
 * 双眼鏡の先の土地を生成する代金を誰も払わないことにある。
 *
 * <p><b>寿命は指示の寿命。</b> 1機が同時に持つのは最大1つ。別の場所を指示すれば2つ目を作らず移動し、指示を
 * 解除すれば破棄される。{@link AircraftEntity#designate} 参照。機体が消えたマーカーは自分から諦めるので、
 * 機体が黙って消えても空に何も残らない。
 */
public class DesignationEntity extends Entity {
    /**
     * 誰にも保持されないまま留まり、諦めるまでの tick 数。
     *
     * <p>機体は指示している間、毎tick自分のマーカーを更新する。これは、tick の間に機体が消えた場合——撃墜、
     * アンロード、解体——のマーカーを片付ける仕組み。野原の上に目標がワールドの寿命いっぱい浮かび続けない
     * ように。
     */
    private static final int ORPHAN_TICKS = 40;

    /** 実地表にどれだけ近ければ「乗っている」と数えるか（ブロック）。以後マークは探すのをやめる。 */
    private static final double SETTLED = 0.5;

    /**
     * マークが地面を探して視線に沿って歩く最大距離（ブロック）。
     *
     * <p>1歩ごとの上限ではなく予算で、移動した分だけ消費し、指示し直した時にだけ補充される。買っている物は
     * 「歩行の終わり」だ。大きく外れた仮定地面と、ほぼ水平な視線の組み合わせは、わずかな高さ補正のために
     * マークを遠くまで動かす。後退し続ける斜面を追いかけるマークは、パイロットが置いた場所に残るマークより
     * 悪い。200ブロックは通常の推定誤差の数倍——仮定地面は数百ブロック手前の地面であり、その距離で土地が
     * 100ブロック上がることは普通は無い——で、10〜20ブロックの真っ当な補正なら最初の tick で決まり、予算は
     * 残る。
     */
    private static final double MOST_TRAVEL = 200.0;

    /**
     * マークを視線に沿って歩かせるために、視線が最低限どれだけ下向きである必要があるか。
     *
     * <p>これで割ることが高さの誤差を視線方向の距離へ変換しており、水平な視線は数ブロックの誤差を800m へ
     * 変える。そこまで水平を向いているポッドはどのみち地面を見ていないので、そのマークは置かれた場所に留
     * まる。
     */
    private static final double LEAST_DIP = 0.15;

    private int sinceHeld;

    /**
     * マークを置いた時のパイロットの視線。機体から離れる下向きのベクトル。降りる必要がもう無いマークでは
     * null——クライアントに実際に見えていた地面を指示した物か、既に実地表を見つけた物。
     */
    @Nullable
    private Vec3 sight;
    /** この指示に残っている {@link #MOST_TRAVEL} の量。 */
    private double travel;

    public DesignationEntity(EntityType<? extends DesignationEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /**
     * 指定した点へ新しいマーカーを置き、レベルへ追加できる状態にする。
     *
     * @param sight その点を推定した視線。計算ではなくブロック上に見えた点なら null
     */
    public static DesignationEntity at(Level level, Vec3 point, @Nullable Vec3 sight) {
        DesignationEntity marker = new DesignationEntity(
                com.ashvehicles.registry.ModEntities.DESIGNATION.get(), level);
        marker.hold(point, sight);

        return marker;
    }

    /**
     * マークを動かし、まだ誰かが保持していると伝える。
     *
     * @param sight その点を推定した視線。計算ではなくブロック上に見えた点なら null
     */
    public void hold(Vec3 point, @Nullable Vec3 sight) {
        this.setPos(point);
        this.sinceHeld = 0;
        this.sight = sight != null && sight.y <= -LEAST_DIP ? sight.normalize() : null;
        this.travel = MOST_TRAVEL;
    }

    /** マークを動かさずに「まだ保持されている」と伝える。保持中は毎tick呼ばれる。 */
    public void held() {
        this.sinceHeld = 0;
    }

    /** マークの位置。追う兵装が訊く値。 */
    public Vec3 middle() {
        return this.position();
    }

    /**
     * 時間を数え、まだ地面を探しているなら地面へ降りる。意図的に {@code super.tick()} を呼ばない。理由は
     * {@link CountermeasureEntity} が呼ばないのと同じで、あれが時間を使うのは火・流体・ポータル・エン
     * ティティが立っている物についてであり、そのどれを調べるにも「たいていロードされていないブロック」を
     * 読むことになるから。
     */
    @Override
    public void tick() {
        if (this.level().isClientSide) {
            return;
        }

        if (++this.sinceHeld > ORPHAN_TICKS) {
            this.discard();

            return;
        }

        this.settle();
    }

    /**
     * 推定で置かれたマークが実地面へ降りる1歩分。ただし、置かれてから自分の下の地面が現れていた場合に限る。
     *
     * <p>その列は、ロード済みで完成している場合だけ取る。それ以外は触らない。要求することがまさに、この
     * 仕組み全体が避けるために存在する地形生成であり、まだ構築中の chunk には読む価値のある高さマップも
     * 無いから。取れた場合、マークは<em>視線に沿って</em>、その線が実地表を通る位置まで動かす。垂直方向
     * だけでなく全方向の補正になる——30度で降りる視線上で20ブロック高すぎる推定は、同時に目標より40ブロック
     * 手前でもある。
     *
     * <p>1tickに1歩、その時いる列から。だから上り坂を横切るマークは動かなくなるまで歩き続ける。十分近づいた
     * 最初の tick、{@link #MOST_TRAVEL} を使い切った最初の tick、そして指示し直された瞬間に諦める。
     */
    private void settle() {
        if (this.sight == null || !(this.level() instanceof ServerLevel level)) {
            return;
        }

        int x = this.getBlockX();
        int z = this.getBlockZ();
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);

        if (chunk == null) {
            return;
        }

        // 最上部ブロックの上面。レーザーの光点が乗る面。
        double drop = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1.0 - this.getY();

        if (Math.abs(drop) <= SETTLED) {
            this.sight = null;

            return;
        }

        double step = Mth.clamp(drop / this.sight.y, -this.travel, this.travel);

        this.travel -= Math.abs(step);
        this.setPos(this.position().add(this.sight.scale(step)));

        if (this.travel <= 0.0) {
            this.sight = null;
        }
    }

    /**
     * どこにいても数え続ける。これは誰もいない地面の上に立つ物で、そこで tick が止まったマーカーは「機体が
     * 消えたことに永遠に気付けないマーカー」になる。
     */
    @Override
    public boolean isAlwaysTicking() {
        return true;
    }

    /** 誰も見ず、撃たず、ぶつからず、手を伸ばさない。光点なので。 */
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

    @Override
    public boolean canBeHitByProjectile() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    /**
     * 追う兵装が飛ぶ距離まで送る。900m 上空から投下された爆弾は、着弾時には通常の追跡距離の外におり、
     * マーカーを忘れたクライアントは「何も無い所へ落ちていく爆弾」を見ることになる。
     */
    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    /**
     * ディスクへは決して書かない。指示とは今まさに誰かがコックピットで行っている行為であり、再起動を生き
     * 延びた指示は「誰も保持していない目標」になる。
     */
    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    /** そのエンティティが保持しているマーカー。無ければ null。呼び出し側向けの便宜。 */
    @Nullable
    public static DesignationEntity of(@Nullable Entity entity) {
        return entity instanceof DesignationEntity marker && marker.isAlive() ? marker : null;
    }
}
