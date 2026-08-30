package com.ashvehicles.weapon;

import java.util.Optional;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.VehicleProjectile;
import com.ashvehicles.entity.BulletEntity;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.RocketEntity;
import com.ashvehicles.entity.VehicleHold;
import com.ashvehicles.item.AmmoItem;
import com.ashvehicles.registry.ModEntities;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * 車両に吊るのではなく組み込まれた火砲。砲身、弾倉、そして射撃間隔。戦車には2門ある——砲塔の主砲と、その
 * 脇に固定された機銃——ので、これはその両方。
 *
 * <p>意図的に {@link WeaponMounts} を使わない。パイロンは物を吊っては外す場所で、あのクラスの大半は
 * 「どのステーションが選択されているか」「何が載っているか」「シーカーが何を捉えているか」の話だ。戦車の
 * 砲はそのどれでもない。組み込みで、各1門ずつあり、問いは「装填されているか」と「どこを向いているか」
 * だけ。共有するのは兵装ファイルの方で、威力・初速・発射速度は機体と同じく
 * {@code data/ashvehicles/weapon/} から読む。だから火砲は、砲塔に埋め込まれていようと翼下に吊られていよう
 * と1箇所で記述される。
 *
 * <p><b>2門を1クラスで扱う理由。</b> 以下の処理はどちらでも同じだ。弾は同じ弾庫から出て、射撃間隔は同じ
 * ファイルから読んだ同じ値で、弾は同じように出て同じ円錐に散る。違うのは5つ——どの兵装か、どの2つの
 * カウンタを使うか、砲口はどこか、砲口は何本か、セーブで弾数を何と呼ぶか——で、その5つが
 * {@link Mount} の全部。2回書いていれば、どちらかを直した最初の瞬間に食い違い始めていた。
 *
 * <p><b>1押し1発かどうかは兵装が決める。</b> 戦車砲は引き金の立ち上がりで読む。装填手は数秒かかるし、
 * 終わった瞬間に離す撃ち方をする者はおらず、それでは照準の要素が完全に消える。機銃や機関砲は逆で、押し
 * っぱなしにする物であり、連射こそが照準の方法だ。どちらもこのクラスで、どちらであるかは兵装ファイルから
 * 読む {@link WeaponDefinition#isAutomatic()}。
 *
 * <p><b>撃てるのは誰かが積んだ分だけ。</b> 弾倉は車両自身の弾庫から、砲弾1発かベルト1本ずつ、しかも車両
 * が停止している間だけ満たされる（{@link #resupply} 参照）。無料装填は無い。クリエイティブタブから出した
 * 戦車は、誰かが弾薬を入れるまで砲が空のまま。機体のパイロンがずっとそうであったのと同じ取り決め。
 *
 * <p><b>状態の置き場。</b> 残弾と再装填カウンタは、ここのフィールドではなく車両の同期データにある。
 * クライアントが両方を必要とするから。主砲の再装填カウンタは砲身の後座を描く元であり、それだけで足りる
 * ——最大値へ跳ね上がったカウンタ<em>そのもの</em>が「発砲した」という知らせなので、他に何も送らなくてよい。
 */
public final class BuiltInGun {
    /**
     * 車両の2門のうちどちらか。何を撃ち、どこに残弾を持ち、弾がどこから出るか。
     *
     * <p>火砲が「すること」は砲によらず同じ。火砲が「<em>何であるか</em>」がここにあり、それは5つの問いで
     * 尽きる。
     */
    public enum Mount {
        /**
         * 主砲。砲塔がその周りに組まれている砲であり、後座して車体を揺らす砲であり、乗員がミサイルへ
         * 切り替える時にしまう砲。
         */
        MAIN {
            @Override
            Optional<ResourceLocation> weapon(GroundVehicleEntity vehicle) {
                return vehicle.getStats().armament().main();
            }

            @Override
            int rounds(GroundVehicleEntity vehicle) {
                return vehicle.getRounds();
            }

            @Override
            void rounds(GroundVehicleEntity vehicle, int rounds) {
                vehicle.setRounds(rounds);
            }

            @Override
            int reload(GroundVehicleEntity vehicle) {
                return vehicle.getReload();
            }

            @Override
            void reload(GroundVehicleEntity vehicle, int ticks) {
                vehicle.setReload(ticks);
            }

            @Override
            Vec3 muzzle(GroundVehicleEntity vehicle, int barrel) {
                return vehicle.getMuzzle(barrel, 1.0F);
            }

            @Override
            int barrels(GroundVehicleEntity vehicle) {
                return vehicle.getBarrelCount();
            }

            @Override
            String tag() {
                return "";
            }
        },
        /**
         * 主砲に固定された機銃。主砲が指向された方向へ指向され、独立した引き金で撃つ。砲口は「砲身に
         * 沿った長さ」ではなく砲上の固定点。後座で下がることも砲身長を必要とすることも無いので、弾が出る
         * 位置はファイルの記述そのままになる。
         * {@link com.ashvehicles.vehicle.GroundVehicleDefinition.Coaxial} 参照。
         */
        COAXIAL {
            @Override
            Optional<ResourceLocation> weapon(GroundVehicleEntity vehicle) {
                return vehicle.getStats().coaxial().gun();
            }

            @Override
            int rounds(GroundVehicleEntity vehicle) {
                return vehicle.getCoaxRounds();
            }

            @Override
            void rounds(GroundVehicleEntity vehicle, int rounds) {
                vehicle.setCoaxRounds(rounds);
            }

            @Override
            int reload(GroundVehicleEntity vehicle) {
                return vehicle.getCoaxReload();
            }

            @Override
            void reload(GroundVehicleEntity vehicle, int ticks) {
                vehicle.setCoaxReload(ticks);
            }

            @Override
            Vec3 muzzle(GroundVehicleEntity vehicle, int barrel) {
                return vehicle.gunToWorld(vehicle.getStats().coaxial().muzzle(), 1.0F);
            }

            @Override
            String tag() {
                return "Coax";
            }
        };

        /** この砲がどの兵装ファイルか。積んでいない車両では空。 */
        abstract Optional<ResourceLocation> weapon(GroundVehicleEntity vehicle);

        abstract int rounds(GroundVehicleEntity vehicle);

        abstract void rounds(GroundVehicleEntity vehicle, int rounds);

        abstract int reload(GroundVehicleEntity vehicle);

        abstract void reload(GroundVehicleEntity vehicle, int ticks);

        /** この砲の弾が出る世界座標。この tick 時点で。 */
        abstract Vec3 muzzle(GroundVehicleEntity vehicle, int barrel);

        /**
         * 順番に撃つ砲身の本数。ファイルが別を言わなければ1で、防盾に固定された機銃が2本だったことは
         * 一度も無い。
         */
        int barrels(GroundVehicleEntity vehicle) {
            return 1;
        }

        /**
         * この砲のカウンタが車両のタグ内で何と呼ばれるか。主砲は空文字。主砲のキーは2門目が存在する前に
         * 書かれた物で、そのまま残してある。古いワールドで保存された戦車が砲弾を持って戻ってくるように。
         */
        abstract String tag();
    }

    /**
     * 自動火器で発射炎を出す間隔（何発ごとか）。
     *
     * <p>発射炎1回はパーティクルの4連射で、それぞれが車両を見られる全員へのパケットになる。毎秒20発で
     * 1発1回なら毎秒80パケット、引き金を引いている限り数百個のパーティクル。3発に1回でも連続射撃に見える
     * ——1回の発光は間隔より長く続く——うえコストは1/3になる。単発の砲は1発ごとに光る。
     */
    private static final int FLASH_EVERY = 3;

    /**
     * 停止中の車両が空の弾倉を自分の弾庫から満たすのにかかる tick 数。装填手を抽象化した値で、機体の
     * 地上要員がパイロン1本にかける10秒と同じ。
     */
    private static final int RESUPPLY_TICKS = 200;

    /** この速度（1tickあたりブロック）未満なら停止中と見なし、装填できる。 */
    private static final float STANDING = 1.0E-4F;

    private final GroundVehicleEntity vehicle;
    private final Mount mount;
    /** 前 tick に引き金が引かれていたか。押しっぱなしで弾倉を空にしないため。 */
    private boolean triggerWasDown;
    /**
     * 複数砲身の架台で、次の弾がどの砲身から出るか。車両ではなくここに持つのは、これを知る必要があるのが
     * このクラスだけだから。発射炎も弾もサーバーが正しい位置へ置くし、クライアントが架台について描く物
     * （後座）は砲身1本ではなく全体の動き。セーブから戻って1本目の砲身に戻る砲は、中断されたことを誰にも
     * 気付かれない。
     */
    private int barrel;
    /** 次の発射炎までの残り発数。{@link #FLASH_EVERY} 参照。 */
    private int untilFlash;

    public BuiltInGun(GroundVehicleEntity vehicle, Mount mount) {
        this.vehicle = vehicle;
        this.mount = mount;
    }

    /**
     * サーバー側で毎tick。引き金は乗員の物、装填は装填手の物で、誰が乗っているかに関わらず進む。
     */
    public void tick(boolean trigger) {
        int reload = this.mount.reload(this.vehicle);

        if (reload > 0) {
            this.mount.reload(this.vehicle, reload - 1);
        }

        boolean wasDown = this.triggerWasDown;
        this.triggerWasDown = trigger;

        Optional<ResourceLocation> fitted = this.mount.weapon(this.vehicle);

        if (fitted.isEmpty() || !(this.vehicle.level() instanceof ServerLevel level)) {
            return;
        }

        ResourceLocation weaponId = fitted.get();
        WeaponDefinition weapon = Definitions.weapon(weaponId);

        // 停止中の車両で乗員が働く。砲弾やベルトを弾庫から弾倉へ。走行中はやらない。車体が揺れている
        // 最中に砲弾を手渡す者はいないから。そして空中からも湧かせない——以前はそうだった。
        if (Math.abs(this.vehicle.getSpeed()) < STANDING) {
            this.resupply(weapon);
        }

        // 引き金を押し続けて撃ち続けられるかは、このクラスではなく兵装が決める。戦車砲は「押す」物だ
        // ——装填手は数秒かかるし、終わった瞬間に離す撃ち方をする者はいない——ので、フィールドを省略して
        // 毎秒1発未満の発射速度を書いたファイルは今もそうなる。同じ防盾の機銃は同じ種類の物であり、
        // 「押しっぱなしにする」物。WeaponDefinition.isAutomatic 参照。
        boolean pressed = trigger && (weapon.isAutomatic() || !wasDown);

        if (!pressed || reload > 0 || this.mount.rounds(this.vehicle) <= 0) {
            return;
        }

        this.fire(level, weaponId, weapon);
    }

    /**
     * 装填手の1tick分。この tick に番が来ていて空きがあれば、砲弾1発かベルト1本を丸ごと弾庫から弾倉へ。
     *
     * <p><b>丸ごとか、無しか。</b> 弾倉は発数で、弾庫はアイテム数で数える。乗員はベルトを半分に切らない。
     * アイテム1個分に満たない空きしか無い弾倉はもう満載扱い。コストはほぼ無く——ここの砲は1つを除き全部
     * アイテム個数がちょうど整数で、パーンツィリの1400発も46.05本のベルトになる——引き換えに、弾薬アイテム
     * は「自分の残量を覚える物」ではなく素朴にスタックできる箱でいられる。
     *
     * <p>速度は大きさに関わらず「{@link #RESUPPLY_TICKS} で弾倉1つ分」。砲弾40発の戦車なら4tickに1発、
     * ベルト46本の機関砲でもほぼ同じ。つまり大きい砲ほど速く装填されることはなく、1tickで満載になる物も
     * 無い。
     */
    private void resupply(WeaponDefinition weapon) {
        AmmoKind kind = weapon.ammoKind();
        int capacity = weapon.ammo();
        int perItem = kind.roundsPerItem();

        if (capacity - this.mount.rounds(this.vehicle) < perItem) {
            return;
        }

        int every = Math.max(1, Math.round((float) RESUPPLY_TICKS * perItem / capacity));

        if (this.vehicle.tickCount % every != 0 || !this.take(kind)) {
            return;
        }

        this.mount.rounds(this.vehicle, this.mount.rounds(this.vehicle) + perItem);
    }

    /**
     * 弾庫から弾薬アイテムを1個取る。取る順は積んだ者が並べた順。
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

    /** この砲がそもそも搭載されているか。 */
    public boolean exists() {
        return this.mount.weapon(this.vehicle).isPresent();
    }

    /** 満載時の弾倉の発数。兵装自身のファイルから。 */
    public int capacity() {
        return this.mount.weapon(this.vehicle)
                .map(id -> Definitions.weapon(id).ammo())
                .orElse(0);
    }

    /** 装填手の所要時間（tick）。兵装の発射速度から。 */
    public int reloadTicks() {
        return this.mount.weapon(this.vehicle)
                .map(id -> ticksFor(Definitions.weapon(id).firing().roundsPerSecond()))
                .orElse(1);
    }

    private static int ticksFor(float roundsPerSecond) {
        return roundsPerSecond <= 0.0F ? 1 : Math.max(1, Math.round(20.0F / roundsPerSecond));
    }

    /**
     * 弾を送り出し、発砲が車両に与える影響を与える。
     *
     * <p>弾は車体方向ではなく砲身方向へ出る。戦車がどこを向いているかと砲がどこを向いているかは別の問い
     * で、後者こそ砲塔が存在する理由の全部。同軸機銃はその同じ砲に固定されているので同じ線へ出る——
     * それが「同軸」であることの全部。散布は砲身を軸にした円錐で、世界基準ではなく砲身基準に作るので、
     * 真上に向けた砲も水平の砲と同じように散る。
     */
    private void fire(ServerLevel level, ResourceLocation weaponId, WeaponDefinition weapon) {
        // 砲身を順繰りに、1本1発ずつ。架台のどの砲身も同じ方向へ指向され、同じ弾倉から同じ速度で装填
        // される——連装架台は2門の砲ではなく弾が出る穴が2つあるだけ——ので、砲身であることの全部は
        // 「この弾がどの砲口から出るか」に尽きる。一度に2発撃ちたいファイルが使うのは兵装自身の salvo
        // で、それは数行下。
        int barrels = Math.max(this.mount.barrels(this.vehicle), 1);
        int firing = this.barrel % barrels;

        this.barrel = (firing + 1) % barrels;

        Vec3 muzzle = this.mount.muzzle(this.vehicle, firing);
        Vec3 bore = this.vehicle.getAimDirection(1.0F);
        Vec3 right = across(bore);
        Vec3 up = right.cross(bore).normalize();
        LivingEntity crew = this.vehicle.getControllingPassenger();
        RandomSource random = this.vehicle.getRandom();

        double scatter = Math.tan(Math.toRadians(weapon.firing().spread())) * 0.5;
        double spread = Math.tan(Math.toRadians(weapon.firing().salvoSpread())) * 0.5;

        for (int i = 0; i < Math.max(1, weapon.firing().salvo()); i++) {
            Vec3 direction = bore
                    .add(right.scale(random.nextGaussian() * (scatter + spread)))
                    .add(up.scale(random.nextGaussian() * (scatter + spread)))
                    .normalize();

            // 砲が与える速度に、車両が既にその方向へ持っていた速度を足す。足すのは砲身方向の成分だけで
            // 速度全体ではない。斜面を横滑りしている車体だと、滑っている角度の分だけ全弾が砲身から曲がって
            // しまうから。パイロンが従う規則と同じ（WeaponMounts.fireRound 参照）で、GunSight が弾道を
            // 飛ばす時の規則とも同じ。それが画面上の照準を真実にしている。
            Vec3 carried = direction.scale(Math.max(0.0, this.vehicle.getVelocity().dot(direction)));

            VehicleProjectile shot = weapon.type() == WeaponDefinition.Type.GUN
                    ? new BulletEntity(ModEntities.BULLET.get(), level)
                    : new RocketEntity(ModEntities.ROCKET.get(), level);

            shot.setup(weaponId, this.vehicle, crew);
            shot.setPos(muzzle);
            // setDeltaMovement ではなく launch。速度がクライアントへ届く必要があり、通常それを運ぶ
            // パケットには速すぎるから。VehicleProjectile 参照。
            shot.launch(direction.scale(weapon.projectile().speed()).add(carried));

            level.addFreshEntity(shot);
        }

        if (--this.untilFlash <= 0) {
            WeaponEffects.muzzleBlast(level, muzzle, bore, blastPower(weapon), weapon.projectile().tracer());
            this.untilFlash = weapon.isAutomatic() ? FLASH_EVERY : 1;
        }

        this.playFireSound(weapon, weaponId);

        this.mount.rounds(this.vehicle, this.mount.rounds(this.vehicle) - 1);
        this.mount.reload(this.vehicle, ticksFor(weapon.firing().roundsPerSecond()));
    }

    /**
     * 散布円錐を作るための、砲身に直交する単位ベクトル。
     *
     * <p>世界の垂直軸を基準に取るので、真上ちょうどへ向けた砲でだけ破綻する。戦車は到達できず、対空架台
     * はほぼ到達できるので、0除算に任せず答えを返す。天頂では砲身に直交するどの方向も等価なので、どれでも
     * よい。
     *
     * <p>{@link TurretLauncher} と共有。あちらの発射筒は同じ架台で指向され、同じ線を軸に散る。
     */
    static Vec3 across(Vec3 bore) {
        Vec3 right = bore.cross(new Vec3(0.0, 1.0, 0.0));

        return right.lengthSqr() < 1.0E-8 ? new Vec3(1.0, 0.0, 0.0) : right.normalize();
    }

    /** 発射炎の大きさ。弾が持つ炸薬から決める。0にはならない。どの砲にも砲口はある。 */
    private static float blastPower(WeaponDefinition weapon) {
        return Mth.clamp(weapon.projectile().explosion(), 1.5F, 6.0F);
    }

    /**
     * 発砲音。兵装ファイルが指定するイベント、無ければ兵装名から作った物。音量スロットには音量ではなく
     * 到達距離を入れて送る。理由は {@code WeaponMounts.playFireSound} に書いた通りで、そのスロットだけが
     * 「誰にこの音を知らせるか」を決めており、戦車砲は32ブロックよりはるかに遠くまで聞こえる。
     */
    private void playFireSound(WeaponDefinition weapon, ResourceLocation weaponId) {
        ResourceLocation event = weapon.sound().fire()
                .orElseGet(() -> weaponId.withPath(WeaponMounts.SOUND_PREFIX + weaponId.getPath()));

        this.vehicle.level().playSound(null, this.vehicle.getX(), this.vehicle.getY(), this.vehicle.getZ(),
                SoundEvent.createVariableRangeEvent(event), SoundSource.NEUTRAL,
                weapon.sound().packetVolume(), weapon.sound().pitch());
    }

    public void load(CompoundTag tag) {
        String rounds = this.mount.tag() + "Rounds";

        // この砲が存在する前にワールドへ書かれた車両は、空ではなく満載の弾倉で戻ってくる。2つの推測の
        // うち親切な方。
        this.mount.rounds(this.vehicle, tag.contains(rounds) ? tag.getInt(rounds) : this.capacity());
        this.mount.reload(this.vehicle, tag.getInt(this.mount.tag() + "Reload"));
    }

    public void save(CompoundTag tag) {
        tag.putInt(this.mount.tag() + "Rounds", this.mount.rounds(this.vehicle));
        tag.putInt(this.mount.tag() + "Reload", this.mount.reload(this.vehicle));
    }

}
