package com.ashvehicles.entity;

import java.util.Optional;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.PartEntity;
import org.joml.Quaternionf;

import com.ashvehicles.vehicle.Hitbox;

/**
 * 機体形状を構成する箱1つ。主翼、尾部、甲板の一区画、前面装甲板、履帯など。
 *
 * <p>Minecraft がエンティティに与えるのは底面が正方形の直立した箱1つで、それは飛行機の形でも戦車の形でも
 * なく、歩ける面でもない。だから機体はこれを複数持ち、それぞれが機体ファイルの箱定義から位置と大きさを
 * もらい、それぞれが本物の障害物になる。弾が当たり、世界と衝突し、上に立った物は立ったままでいられる。
 * 最後の点こそ甲板に必要な物。
 *
 * <p><b>パーツはその箱ではない。</b> 本体は {@link Hitbox}——機体が残した角度のまま寝ている、この MOD 製の
 * 箱で、Minecraft 側からは決して見えない。旋回中の主翼は斜めの薄板であり、45度に構えた砲は砲身だ。何かを
 * 決める処理は全部——弾が何に当たるか、プレイヤーが何にぶつかり何の上に立つか、世界が機体をどこで止めるか
 * ——それに対して {@link Hitboxes} で測られる。
 *
 * <p>ゲームがパーツを運ぶ直立した箱は検索用でしかない。それは {@code Hitbox} が「機体として扱うのをやめ
 * る」ために存在している空気そのものなので、パーツは意図的にゲームの衝突対象にしていない
 * （{@link #canBeCollidedWith} が false）。衝突するはずだった箱は MOD 自身が上から被せる。
 *
 * <p>親は2通りで保持する——ゲームが必要とする {@code Entity} として、そして形状について答える
 * {@link PartHost} として。箱は飛行機にも戦車にも等しく属し、その2つはこれ以外に共通点の無い別クラスだ
 * から。
 */
public class VehiclePart extends PartEntity<Entity> {
    private final PartHost host;
    private final String name;
    /** ファイル内の何番目の箱か、あるいは何番目のハードポイントか。 */
    private final int slot;
    private final boolean pylon;
    private EntityDimensions dimensions;
    /** パーツの実体。機体が初めて配置するまでは null。 */
    private Hitbox hitbox;

    /** 機体の一部。{@code slot} はファイル内の何番目の箱か。 */
    public static <T extends Entity & PartHost> VehiclePart airframe(T parent, String name, int slot) {
        return new VehiclePart(parent, name, slot, false);
    }

    /** 兵装を吊る場所。{@code slot} は機体ファイルの何番目のハードポイントか。 */
    public static <T extends Entity & PartHost> VehiclePart pylon(T parent, String name, int slot) {
        return new VehiclePart(parent, name, slot, true);
    }

    private <T extends Entity & PartHost> VehiclePart(T parent, String name, int slot, boolean pylon) {
        super(parent);
        this.host = parent;
        this.name = name;
        this.slot = slot;
        this.pylon = pylon;
        this.dimensions = EntityDimensions.scalable(1.0F, 1.0F);
        this.refreshDimensions();
    }

    public String getPartName() {
        return this.name;
    }

    /**
     * 線分が<em>実際に寝ている姿勢の</em>この箱へ最初に入る点。外れていれば空。
     *
     * @param margin 先に箱をどれだけ膨らませるか。判定する側はゲームが使うはずだった値と同じ物を渡す必要
     *               がある。さもないとゲームなら数えた掠りが弾かれる
     */
    public Optional<Vec3> clip(Vec3 from, Vec3 to, double margin) {
        return this.hitbox == null ? Optional.empty() : this.hitbox.grow(margin).clip(from, to);
    }

    /** 実際に寝ている姿勢の箱。機体が一度も配置していなければ null。 */
    public Hitbox hitbox() {
        return this.hitbox;
    }

    /**
     * この箱が機体の一部ではなくパイロンか。
     *
     * <p>パイロンを区別する価値があるのは、単独でクリックする価値があるから。そこに吊られている物は隣の
     * パイロンに吊られている物とは別で、手を伸ばしたプレイヤーはその1本を指している。
     */
    public boolean isPylon() {
        return this.pylon;
    }

    /** 何番目のハードポイントか。機体ファイルの列挙順で数える。 */
    public int getPylon() {
        return this.slot;
    }

    /**
     * 機体自身の何番目の箱か。ファイルの列挙順で数える。
     *
     * <p>{@link #getPylon} と同じ数値でありながら全く別の物なので、別々に問い合わせる。パイロンなら
     * ハードポイントを数え、機体の一部なら箱を数える。先に {@link #isPylon} を訊くこと。さもないと答えは
     * 間違ったリストへの添字になる。
     */
    public int getBox() {
        return this.slot;
    }

    /** 指定サイズの立方体。パイロン用で、あれは金属ではなく機体上の「場所」なので。 */
    public void place(Vec3 centre, double size) {
        this.place(new Hitbox(centre, new Vec3(size, size, size), new Quaternionf()));
    }

    /**
     * パーツを機体の中へ畳み込む。リロードで短くなったファイルがもう記述していないパーツ用。個数は機体の
     * 生涯を通じて固定なので消せない。だからぶつかることも当たることもできない大きさにする。
     */
    public void fold(Vec3 inside) {
        this.place(inside, 1.0E-3);
    }

    /**
     * 機体が計算した位置へ、機体が寝ているのと同じ姿勢でパーツを置く。
     *
     * <p>その後ゲームへ渡す直立した箱は {@code Hitbox} が収まる箱で、用途はパーツを見つけることだけ。
     * それに衝突する物も、それに当たる物も無い。
     */
    public void place(Hitbox box) {
        // パーツはレベルの tick リストに入っていないので、前回位置を進めてくれる物が他に無い。放っておく
        // とパーツが生まれた場所に留まり、2点間を補間する物は全部——当たり判定の表示も含めて——そこから
        // 尾を引いてくるパーツを描く。
        this.setOldPosAndRot();

        AABB reach = box.reach();

        this.hitbox = box;
        this.dimensions = EntityDimensions.scalable(
                (float) Math.max(reach.getXsize(), reach.getZsize()), (float) reach.getYsize());
        this.setPos(reach.getCenter().x, reach.minY, reach.getCenter().z);
        // 最後に設定する。setPos は箱をエンティティ自身の幅で正方形に均してしまい、一方向に長いパーツ
        // では「見つけるための箱」として正しくなくなる。
        this.setBoundingBox(reach);
    }

    /**
     * 機体のどこを右クリックしても機体への右クリックになる。主翼からでも尾部からでも乗り込めるのであって、
     * Minecraft が本来提供する1つの箱からだけではない。
     *
     * <p>例外はパイロン。機体の中で唯一、<em>どの</em>部位に手を伸ばしたかが意味の全部になる部位だから。
     * クリックすればそのパイロンだけを積み下ろしする。
     *
     * <p>ただししゃがんでいる場合は別で、それはステーションではなく機体を指す——機体のどこをクリックしても
     * 弾庫が開く。パイロンの箱は主翼の箱の内側にあるので、クリックの観点では主翼下面の大半がパイロンだ。
     * そこでミサイルを持ってしゃがみクリックすると、しまうつもりだった主翼にミサイルを吊ってしまう。
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        return this.isPylon() && !player.isSecondaryUseActive()
                ? this.host.interactPylon(player, hand, this.slot)
                : this.getParent().interact(player, hand);
    }

    @Override
    public ItemStack getPickResult() {
        return this.getParent().getPickResult();
    }

    /**
     * <em>機体</em>のどこへの命中も機体への命中になる。その意味でパイロンは機体の一部ではない。物を吊る
     * 場所であって、ダメージを受けず、渡しもしない。渡させれば盾になってしまう——パイロンに当たった弾が
     * そこで止まり、後ろの主翼へ届かなくなる。
     *
     * <p>素手だけはパイロンも通す。射撃ではないし、止められる理由が何も無いから。値打ちは機体から返る音
     * だけで、翼下のラックを叩いた拳は主翼を叩いた拳と同じ音を得るべきだ。
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.isInvulnerableTo(source) || (this.isPylon() && VehicleEntityBase.knuckles(source) == null)) {
            return false;
        }

        return this.getParent().hurt(source, amount);
    }

    /**
     * 機体に向けて撃たれた物はパイロンに当たらない。弾はその後ろの機体構造へ抜ける。
     *
     * <p>これが2つの役割を分けている。発射物はこれを訊き、パイロンには false を得る。プレイヤーの十字線は
     * 代わりに {@link #isPickable()} を訊き、何かを吊れるパイロンには true を得る——つまりパイロンは手を
     * 伸ばして積める一方、撃たれることも盾にされることも無い。
     */
    @Override
    public boolean canBeHitByProjectile() {
        return !this.isPylon() && super.canBeHitByProjectile();
    }

    @Override
    public boolean is(Entity entity) {
        return this == entity || this.getParent() == entity;
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return this.dimensions;
    }

    /** 同じ機体のパーツ同士も衝突しない。 */
    @Override
    public boolean canCollideWith(Entity other) {
        if (other == this.getParent()
                || (other instanceof VehiclePart part && part.getParent() == this.getParent())) {
            return false;
        }

        return super.canCollideWith(other);
    }

    /**
     * false を返す。これは未完成な部分ではなく、この仕組みの要点そのもの。
     *
     * <p>ここで true を返すと、ゲームはパーツを運ぶ直立した箱を受け取ってそれと衝突する。角度の付いた物に
     * とってそれは実形状の上に被さった空気の蓋だ——傾いた甲板の30cm 上に立つプレイヤー、周りに描かれた板が
     * 邪魔で横を通れない旋回中の主翼。false を返せばパーツはゲームの衝突から完全に外れ、{@link Hitboxes}
     * が本物の箱を戻す。両者が出会う場所は {@code EntityCollisionMixin}。
     */
    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    /**
     * パイロンに手を伸ばせるのは、そこで何かできる場合だけ。機体に内蔵された兵装のパイロンは積み下ろしが
     * 一切できないので、その箱は脇へ退いてクリックを後ろの機体へ通す——さもないと機首の上に見えない箱が
     * 居座り、乗り込もうとする操作を全部飲み込む。
     */
    @Override
    public boolean isPickable() {
        if (this.getParent().isRemoved()) {
            return false;
        }

        return !this.isPylon() || this.host.isLoadablePylon(this.slot);
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
