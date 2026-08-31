package com.ashvehicles.weapon;

import javax.annotation.Nullable;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.BulletEntity;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.RocketEntity;
import com.ashvehicles.entity.VehicleHold;
import com.ashvehicles.entity.VehicleProjectile;
import com.ashvehicles.item.AmmoItem;
import com.ashvehicles.network.MissileTrackPayload;
import com.ashvehicles.registry.ModEntities;
import com.ashvehicles.vehicle.GroundVehicleDefinition;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 発射筒の中のミサイル。シーカー、弾倉、そして発射間隔。
 *
 * <p>{@link WeaponMounts} との関係は {@link BuiltInGun} と同じで、理由も同じ。パイロンは物を<em>吊る
 * </em>場所で、あのクラスの大半は「どのステーションが選択されているか」「誰が何を積んだか」「弾庫から
 * 積み直す」話だ。発射筒はそのどれでもない。組み込みで、全部同じ弾を持ち、問いは残数とシーカーが1発
 * 使う価値のある物を捉えているかだけ。共有するのは兵装ファイルの方で、威力・シーカー探知距離・旋回性能・
 * 何に騙されるかは機体と同じく {@code data/ashvehicles/weapon/} から読む。だからミサイルは、翼下に吊ら
 * れていようと筒に入っていようと1箇所で記述される。
 *
 * <p><b>ロックの取り方はコックピットと同じ。</b> 砲手がシーカーのキーを押している間だけ、まだ追尾して
 * いない目標を取る。掴んだ後は離してよく、保持はシーカー自身が行う。以前は砲塔が向いた先の物を無条件に
 * 掴んでいたが、それは「見ること」と「ロックすること」の間に何も置かないということであり、対空車両だけが
 * 機体と違う手順で戦う理由は無かった。{@link com.ashvehicles.entity.GroundVehicleInput#lock()} 参照。
 *
 * <p><b>ただしシーカーは発射筒が選択されているかに関わらず見ている。</b> これは意図的で、対空陣地の怖さ
 * の大半がそこにある。機体を追尾している発射機はパイロットの警戒受信機を鳴らす
 * （{@link com.ashvehicles.sensor.Sensors} 参照）ので、機体は何かが発射される遥か前に「見られている」と
 * 告げられる。それがパイロットに打つ手を与える警告になる。乗員がミサイルへ切り替えた時だけ起きるシーカー
 * では、機体は晴天からの発射を食らうことになる。掴むのに手が要るようになっただけで、掴んだ後の振る舞いは
 * 変わっていない。
 *
 * <p><b>ロック無しでは撃たない。</b> 誘導弾に狙う相手が無ければ捨てるのと同じで、乗員には筒を無駄にさせ
 * るより追尾を続けろと伝える方がよい。ロックをミサイルへ渡す方式は機体と同じ引き継ぎ——発射の瞬間の目標
 * を渡し、その後は何も渡さない。
 *
 * <p><b>状態の置き場。</b> 残弾・待ち時間・シーカーの捉えている相手は、ここのフィールドではなく車両の
 * 同期データにある。乗員の計器が3つとも必要とし、そのどれもサーバーだけが決めてよいから。
 */
public final class TurretLauncher {
    /** 発射炎の大きさ。砲の発砲ではなくブースターの点火。 */
    private static final float BOOST_BLAST = 2.0F;

    /**
     * 停止中の車両が空の発射筒を自分の弾庫から満たすのにかかる tick 数。砲の装填手の2倍。ロケットは
     * 吊り込む物で、砲弾は手渡しできるから。
     */
    private static final int RESUPPLY_TICKS = 400;

    /** この速度（1tickあたりブロック）未満なら停止中と見なし、装填できる。 */
    private static final float STANDING = 1.0E-4F;

    /** 架台が「立ち上がり切った」と数える、上限までの残り角度（度）。 */
    private static final float ERECT_MARGIN = 2.0F;

    /** 飛んでいる弾の位置を撃った乗員へ知らせる間隔（tick）。 */
    private static final int REPORT_TICKS = 5;

    private final GroundVehicleEntity vehicle;
    /**
     * この発射機が撃って、まだ飛んでいる弾のエンティティ番号。
     *
     * <p>撃った乗員の射撃指揮盤へ位置を送るために持つ。クライアントは自分の周りの弾しか知らないので、
     * 地平線の向こうへ飛んでいく弾を地図に出す道はこれしか無い。{@link MissileTrackPayload} 参照。
     *
     * <p>サーバー専用。消えた弾は次の報告で自然に落ちる。
     */
    private final IntList inFlight = new IntArrayList();
    /** 乗員がシーカーで捉えている相手。決めるのは常にサーバーだけ。 */
    private final TargetLock lock;
    /** 前 tick に引き金が引かれていたか。押しっぱなしで筒を空にしないため。 */
    private boolean triggerWasDown;

    public TurretLauncher(GroundVehicleEntity vehicle) {
        this.vehicle = vehicle;
        this.lock = new TargetLock(vehicle);
    }

    /** シーカーが捉えている相手。計器が読む。決めるのは常にサーバーだけ。 */
    public TargetLock lock() {
        return this.lock;
    }

    /** 発射筒に入っている弾の諸元。積んでいない車両では null。 */
    @Nullable
    public WeaponDefinition missile() {
        return this.vehicle.getStats().launcher().missile().map(Definitions::weapon).orElse(null);
    }

    /** 満載時の筒の数。ミサイル自身のファイルから。 */
    public int capacity() {
        return this.vehicle.getStats().launcher().missile()
                .map(id -> Definitions.weapon(id).ammo())
                .orElse(0);
    }

    /** 発射間隔（tick）。ミサイルの発射速度から。 */
    public int reloadTicks() {
        return this.vehicle.getStats().launcher().missile()
                .map(id -> ticksFor(Definitions.weapon(id).firing().roundsPerSecond()))
                .orElse(1);
    }

    /**
     * 装填手の1tick分。この tick に1発分の番が来ていて、入れる筒があれば、弾庫から1発を筒へ移す。
     *
     * <p><b>手作業で、1発ずつ、車両が停止している間だけ。</b> ロケットはトラックから吊り込む物で、実物
     * なら装填車と30分近くを要する。だから発射機は誰かが積んだ分しか撃てない。以前は車両を置いた瞬間に
     * 空中から自分を満たしており、それでは一斉射が無料になり弾庫は飾りだった。
     *
     * <p>砲より遅いのは意図的で、{@link #RESUPPLY_TICKS} は筒1セット分。24発積む発射機なら数秒に1発。
     * 筒を空にすることが形式ではなく判断になる程度には長い。
     *
     * <p>形は {@code BuiltInGun.resupply} と同じだが、数える物が違う（弾数と再装填 対 筒数と待ち時間、
     * それぞれ自分の同期フィールド）ので1階層離してある。共有しているのは弾庫・速度・停止条件という
     * 「取り決め」であってコードではなく、その取り決めの方が数行より価値がある。
     */
    private void resupply(WeaponDefinition missile) {
        if (Math.abs(this.vehicle.getSpeed()) >= STANDING || this.vehicle.getMissiles() >= missile.ammo()) {
            return;
        }

        int every = Math.max(1, Math.round((float) RESUPPLY_TICKS / missile.ammo()));

        if (this.vehicle.tickCount % every != 0 || !this.take(missile.ammoKind())) {
            return;
        }

        this.vehicle.setMissiles(this.vehicle.getMissiles() + 1);
    }

    /**
     * 弾庫からロケットを1本取る。取る順は積んだ者が並べた順。
     *
     * @return 取れる物があったか
     */
    private boolean take(AmmoKind kind) {
        VehicleHold hold = this.vehicle.getHold();

        for (int at = 0; at < hold.getContainerSize(); at++) {
            if (AmmoItem.isKind(hold.getItem(at), kind)) {
                hold.removeItem(at, 1);

                return true;
            }
        }

        return false;
    }

    private static int ticksFor(float roundsPerSecond) {
        return roundsPerSecond <= 0.0F ? 1 : Math.max(1, Math.round(20.0F / roundsPerSecond));
    }

    /**
     * サーバー側で毎tick。シーカーが見て、待ち時間が減り、押されていれば送る相手がいる場合に1発送り出す。
     *
     * @param trigger 乗員が引き金を引いており<em>かつ</em>発射筒を選択しているか。引き金がどの兵装を撃つ
     *                かは車両側の判断で、このクラスの管轄ではない
     * @param wantsLock 砲手がこの tick にシーカーのキーを押しているか。押していれば、まだ追尾していない
     *                  目標を新たに取ってよい。既に掴んでいる物の保持はこれに左右されない
     */
    public void tick(boolean trigger, boolean wantsLock) {
        int reload = this.vehicle.getMissileReload();

        if (reload > 0) {
            this.vehicle.setMissileReload(reload - 1);
        }

        GroundVehicleDefinition.Launcher tubes = this.vehicle.getStats().launcher();

        if (!tubes.exists()) {
            this.lock.clear();
            this.triggerWasDown = trigger;

            return;
        }

        ResourceLocation missileId = tubes.missile().orElseThrow();
        WeaponDefinition missile = Definitions.weapon(missileId);

        // シーカーより前、引き金より前。装填だけは誰が乗っているかに関わらず進む——それは地上要員の仕事
        // であって砲手の仕事ではないから。
        this.resupply(missile);

        // 無人の陣地は何も追尾していない。見たままにすれば、放置された発射機が数km四方の空を永遠に掃引
        // し、マップ中の警戒受信機を鳴らし続ける。レーダー自体が守っている規則と同じ——Sensors.tick 参照。
        // あちらも同じ2つの理由で無人の機体では停止する。
        if (this.vehicle.getControllingPassenger() == null) {
            this.lock.clear();
            this.triggerWasDown = false;

            return;
        }

        this.report();

        // 座標へ飛ぶ弾にはシーカーが無い。掃引もしなければ捕捉も進まず、追われている側の警戒受信機も鳴らない
        // ——狙われていることを相手が知る手段が無いのがこの種の兵器であり、それが弾道ミサイルの怖さだ。行き先は
        // 乗員が据えた点で、それは {@link GroundVehicleEntity#designate} が持っている。
        boolean laid = this.vehicle.laysPoint();

        if (laid) {
            this.lock.clear();
        } else {
            // シーカーは筒を選択している間だけでなく常に見ている。新しい目標を取るのは砲手がキーを押している
            // 間だけ。クラス冒頭の説明参照。
            this.lock.tick(missile.guidance().orElse(null), wantsLock);
        }

        boolean pressed = trigger && (missile.isAutomatic() || !this.triggerWasDown);
        this.triggerWasDown = trigger;

        if (!pressed || reload > 0 || this.vehicle.getMissiles() <= 0) {
            return;
        }

        // 追う相手が無ければ筒からは何も出ない。据える弾では「相手」が点であることだけが違う。
        if (missile.isGuided() && (laid ? this.vehicle.getDesignated() == null : !this.lock.isLocked())) {
            return;
        }

        // そして据える発射機は、架台が立ち上がるまで撃たない。弾は筒が向いている方向へ出ていくので
        // （{@link #fire} 参照）、寝たままの筒から出た弾は目の前の地面へ向かって飛ぶ。実物が発射前に必ず
        // 起立するのと同じ理由であり、その数秒は乗員から見ても「据えた」ことの手応えになる。
        if (laid && !this.erected()) {
            return;
        }

        if (this.vehicle.level() instanceof ServerLevel level) {
            this.fire(level, missileId, missile);
        }
    }

    /**
     * 飛んでいる弾の位置を、撃った乗員へ。
     *
     * <p>数tickに1度。弾は速いが地図の縮尺はもっと粗く、1秒に4回で線が引ける程度には滑らかだ。消えた弾は
     * ここで一覧から落ちるので、掃除の仕組みは別に要らない。
     *
     * <p>送るのは今その席に座っている者にだけ。撃った本人が降りていれば、続きを見る資格は次に座った者に
     * 移る——盤はその発射機の物であって、個人の記憶ではない。
     */
    private void report() {
        if (this.inFlight.isEmpty() || this.vehicle.tickCount % REPORT_TICKS != 0) {
            return;
        }

        List<MissileTrackPayload.Shot> shots = new ArrayList<>(this.inFlight.size());

        for (int at = this.inFlight.size() - 1; at >= 0; at--) {
            Entity shot = this.vehicle.level().getEntity(this.inFlight.getInt(at));

            if (shot == null || !shot.isAlive()) {
                this.inFlight.removeInt(at);

                continue;
            }

            shots.add(new MissileTrackPayload.Shot(shot.getId(), shot.getX(), shot.getY(), shot.getZ()));
        }

        if (this.vehicle.getControllingPassenger() instanceof ServerPlayer crew) {
            PacketDistributor.sendToPlayer(crew, new MissileTrackPayload(List.copyOf(shots)));
        }
    }

    /**
     * 架台が立ち上がり切っているか。座標へ据える発射機だけが問う。
     *
     * <p>据えた発射機の仰角は目標に関係なく架台の上限へ向かう——{@code GroundVehicleEntity.tickTurret} 参照——
     * ので、そこへ着いたかどうかだけを見ればよい。方位は問わない。回っている途中で撃っても弾は自分で目標へ
     * 向き直るが、下を向いた筒から出た弾には向き直る高度が無い。
     */
    private boolean erected() {
        GroundVehicleDefinition.Turret turret = this.vehicle.getStats().turret();

        return !turret.exists()
                || this.vehicle.getGunPitch(1.0F) >= turret.elevation() - ERECT_MARGIN;
    }

    /**
     * 1発を送り出す。
     *
     * <p>筒から出て<em>砲身方向</em>へ飛ぶ。ロックしている相手が居ようと居まいと、誘導だろうと無誘導
     * だろうと、出ていく方向は常に架台が向いている方向だ。両方積む車両ではそこが砲の指向方向でもある
     * ——砲身と筒は同じ架台に固定されているので、砲を目標に指向すれば筒も指向される。レール自体は砲塔上
     * の点でリング回りに一緒に回るが、砲身と一緒に俯仰はしない。筒は後座する物ではなく架台の側面の箱
     * だから。
     *
     * <p>一時期、ロック済みの弾だけは目標の方向へケージングして撃ち出していた。シーカーが砲塔の向きと
     * 無関係に目標を掴めたからで、砲身方向へ撃つと数十度外れた方向へ飛び出すことがあったためだ。ロックを
     * 取るのに砲手がシーカーのキーで目標を捉えに行くようになった今、掴んだ相手は砲塔が向いている先にいる。
     * 撃ち出す向きを弾に決めさせる必要はもう無く、決めさせない方が読める——砲身の向きが、そのまま弾の
     * 出ていく向きだ。
     */
    private void fire(ServerLevel level, ResourceLocation missileId, WeaponDefinition missile) {
        GroundVehicleDefinition.Launcher tubes = this.vehicle.getStats().launcher();
        Vec3 rail = this.vehicle.turretToWorld(tubes.rail(), 1.0F);
        Vec3 bore = this.vehicle.getAimDirection(1.0F);
        Vec3 right = BuiltInGun.across(bore);
        Vec3 up = right.cross(bore).normalize();
        LivingEntity crew = this.vehicle.getControllingPassenger();
        RandomSource random = this.vehicle.getRandom();
        // 弾が持っていく相手。シーカーが掴んだ物か、乗員が据えた点か。どちらも発射の瞬間に確定し、以後
        // 変わらない——撃った後に据え直しても、出て行った弾は出て行った時の点へ飛ぶ。
        Entity locked = !missile.isGuided() ? null
                : this.vehicle.laysPoint() ? this.vehicle.getDesignated()
                : this.lock.isLocked() ? this.lock.target()
                : null;

        double scatter = Math.tan(Math.toRadians(missile.firing().spread())) * 0.5;
        double spread = Math.tan(Math.toRadians(missile.firing().salvoSpread())) * 0.5;

        for (int i = 0; i < Math.max(1, missile.firing().salvo()); i++) {
            Vec3 direction = bore
                    .add(right.scale(random.nextGaussian() * (scatter + spread)))
                    .add(up.scale(random.nextGaussian() * (scatter + spread)))
                    .normalize();

            VehicleProjectile shot = missile.type() == WeaponDefinition.Type.GUN
                    ? new BulletEntity(ModEntities.BULLET.get(), level)
                    : new RocketEntity(ModEntities.ROCKET.get(), level);

            shot.setup(missileId, this.vehicle, crew);
            shot.setPos(rail);
            // setDeltaMovement ではなく launch。速度がクライアントへ届く必要があり、通常それを運ぶ
            // パケットではこの速さを表現できないから。VehicleProjectile 参照。
            shot.launch(direction.scale(missile.projectile().speed()));

            if (shot instanceof RocketEntity rocket && locked != null) {
                rocket.setTarget(locked);
            }

            level.addFreshEntity(shot);
            this.inFlight.add(shot.getId());
        }

        WeaponEffects.muzzleBlast(level, rail, bore, BOOST_BLAST, missile.projectile().tracer());
        this.playLaunchSound(missile, missileId);

        this.vehicle.setMissiles(this.vehicle.getMissiles() - 1);
        this.vehicle.setMissileReload(ticksFor(missile.firing().roundsPerSecond()));

        // 撃ったら架台を畳む。弾はもう座標を持っているので、発射機がそこを見続ける理由は無い——実物が
        // 撃った直後にすることであり（撃って走る）、次の座標は次に据える物だ。マーク自体は消さず、飛んで
        // いる弾へ持たせる。GroundVehicleEntity.releaseDesignation 参照。
        if (this.vehicle.laysPoint()) {
            this.vehicle.releaseDesignation();
        }
    }

    /**
     * 発射音。兵装ファイルが指定するイベント、無ければ兵装名から作った物。音量スロットには音量ではなく
     * 到達距離を入れて送る。理由は {@code WeaponMounts.playFireSound} に書いた通りで、そのスロットだけが
     * 「誰にこの音を知らせるか」を決めており、レールを離れるミサイルは撃った谷の向こうまで聞こえるから。
     */
    private void playLaunchSound(WeaponDefinition missile, ResourceLocation missileId) {
        ResourceLocation event = missile.sound().fire()
                .orElseGet(() -> missileId.withPath(WeaponMounts.SOUND_PREFIX + missileId.getPath()));

        this.vehicle.level().playSound(null, this.vehicle.getX(), this.vehicle.getY(), this.vehicle.getZ(),
                SoundEvent.createVariableRangeEvent(event), SoundSource.NEUTRAL,
                missile.sound().packetVolume(), missile.sound().pitch());
    }

    /** ロックの進捗。0から1まで。閉じていく間に計器が描く値。 */
    public float progress() {
        WeaponDefinition missile = this.missile();

        return missile == null || missile.guidance().isEmpty()
                ? 0.0F
                : Mth.clamp(this.lock.progress(missile.guidance().get()), 0.0F, 1.0F);
    }

    public void load(CompoundTag tag) {
        // 発射筒を持つ前にワールドへ書かれた車両は、空ではなく満載で戻ってくる。2つの推測のうち親切な
        // 方。
        this.vehicle.setMissiles(tag.contains("Missiles") ? tag.getInt("Missiles") : this.capacity());
        this.vehicle.setMissileReload(tag.getInt("MissileReload"));
    }

    public void save(CompoundTag tag) {
        tag.putInt("Missiles", this.vehicle.getMissiles());
        tag.putInt("MissileReload", this.vehicle.getMissileReload());
    }
}
