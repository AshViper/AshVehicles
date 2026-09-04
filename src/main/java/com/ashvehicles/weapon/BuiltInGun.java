package com.ashvehicles.weapon;

import java.util.Optional;

import javax.annotation.Nullable;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.VehicleProjectile;
import com.ashvehicles.entity.BulletEntity;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.RocketEntity;
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
 * <p><b>2門を1クラスで扱う理由。</b> 以下の処理はどちらでも同じだ。弾は同じ手から装填され、射撃間隔は同じ
 * ファイルから読んだ同じ値で、弾は同じように出て同じ円錐に散る。違うのは5つ——どの兵装か、どの2つの
 * カウンタを使うか、砲口はどこか、砲口は何本か、セーブで弾数を何と呼ぶか——で、その5つが
 * {@link Mount} の全部。2回書いていれば、どちらかを直した最初の瞬間に食い違い始めていた。
 *
 * <p><b>1押し1発かどうかは兵装が決める。</b> 戦車砲は引き金の立ち上がりで読む。装填手は数秒かかるし、
 * 終わった瞬間に離す撃ち方をする者はおらず、それでは照準の要素が完全に消える。機銃や機関砲は逆で、押し
 * っぱなしにする物であり、連射こそが照準の方法だ。どちらもこのクラスで、どちらであるかは兵装ファイルから
 * 読む {@link WeaponDefinition#isAutomatic()}。
 *
 * <p><b>撃てるのは誰かが積んだ分だけ。</b> 弾倉が満たされるのは、誰かが弾薬箱を持って車両を右クリック
 * した時だけだ（{@link #load} 参照）。無料装填も自動装填も無い。クリエイティブタブから出した戦車は、
 * 誰かが弾薬を押し込むまで砲が空のまま。機体のパイロンがずっとそうであったのと同じ取り決め。
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

            @Override
            public GroundVehicleEntity.Armament station() {
                return GroundVehicleEntity.Armament.MAIN;
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

            @Override
            public GroundVehicleEntity.Armament station() {
                return GroundVehicleEntity.Armament.COAX;
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

        /**
         * この砲が車両のどの架台か。弾種と選択はそちらの言葉で数えられている——弾倉が種類ごとに分かれる
         * のも、切り替えが順に回るのも、砲ではなく架台の性質だ。{@link Magazine} 参照。
         */
        public abstract GroundVehicleEntity.Armament station();
    }

    /**
     * 自動火器で発射炎を出す間隔（何発ごとか）。
     *
     * <p>発射炎1回はパーティクルの4連射で、それぞれが車両を見られる全員へのパケットになる。毎秒20発で
     * 1発1回なら毎秒80パケット、引き金を引いている限り数百個のパーティクル。3発に1回でも連続射撃に見える
     * ——1回の発光は間隔より長く続く——うえコストは1/3になる。単発の砲は1発ごとに光る。
     */
    private static final int FLASH_EVERY = 3;

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
     * サーバー側で毎tick。引き金と待ち時間だけ。装填はここでは起きない——車両の外に立っている誰かの
     * クリックで起きる。{@link #load} 参照。
     */
    public void tick(boolean trigger) {
        // 引き金を見るのは、減らした<em>後</em>の待ち時間。減らす前の値で判定すると、待ち1tickの砲——
        // {@link #ticksFor} が1を返す毎秒20発以上は全部そうだ——が「撃つ、休む、撃つ」になり、ファイルに
        // 書いた発射速度がちょうど半分で出る。M61 の毎分6000発が3000発になっていたのはこれで、待ちの長い
        // 砲も一律に1tickずつ遅かった。
        int reload = this.mount.reload(this.vehicle);

        if (reload > 0) {
            reload--;
            this.mount.reload(this.vehicle, reload);
        }

        boolean wasDown = this.triggerWasDown;
        this.triggerWasDown = trigger;

        Optional<ResourceLocation> fitted = this.mount.weapon(this.vehicle);

        if (fitted.isEmpty() || !(this.vehicle.level() instanceof ServerLevel level)) {
            return;
        }

        ResourceLocation weaponId = fitted.get();
        WeaponDefinition weapon = Definitions.weapon(weaponId);

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
     * 今この砲の薬室にある弾種。弾種を並べていない架台では null で、そのときは兵装ファイル自身が書いた
     * 弾が出る——MOD 内の大半の砲がそれだ。
     */
    @Nullable
    private ResourceLocation ammunition() {
        return Magazine.selected(this.vehicle, this.mount.station());
    }

    /**
     * この砲に入る弾薬の種類。砲を積んでいなければ null。手に持った物がこの砲のための物かを、車両が
     * クリックの意味を決めるときに訊く。
     */
    @Nullable
    public AmmoKind ammoKind() {
        if (Magazine.typed(this.vehicle, this.mount.station())) {
            return null;
        }

        return this.mount.weapon(this.vehicle)
                .flatMap(id -> Definitions.weapon(id).ammoKind())
                .orElse(null);
    }

    /**
     * 差し出された弾薬アイテムを弾倉へ押し込み、実際に受け取った<em>個数</em>を返す。装填は誰かが手で
     * 行う作業になったので、時間で刻む必要はもう無い——1回のクリックが1回の積み込みだ。
     *
     * <p><b>丸ごとか、無しか。</b> 弾倉は発数で、弾薬箱はアイテム数で数える。誰もベルトを半分に切らない
     * ので、1個分に満たない空きしか無い弾倉はもう満載扱い。コストはほぼ無く——ここの砲は1つを除き全部
     * アイテム個数がちょうど整数で、パーンツィリの1400発も46.05本のベルトになる——引き換えに、弾薬アイテム
     * は「自分の残量を覚える物」ではなく素朴にスタックできる箱でいられる。
     *
     * @param offered 手にある個数
     * @return 弾倉が受け取った個数。0 なら満載か、そもそも入らない種類
     */
    public int load(AmmoKind kind, int offered) {
        WeaponDefinition weapon = this.mount.weapon(this.vehicle).map(Definitions::weapon).orElse(null);

        // 弾種を並べた架台は、並べた物しか受け取らない。徹甲弾と榴弾を積み分ける戦車の弾庫へ「砲弾」と
        // だけ書かれた箱を押し込めば、それがどちらとして入ったのか誰にも言えなくなる。そこは
        // Magazine.load が名指しで受け取る道になっている。
        if (weapon == null || weapon.ammoKind().orElse(null) != kind || offered <= 0
                || Magazine.typed(this.vehicle, this.mount.station())) {
            return 0;
        }

        int perItem = kind.roundsPerItem();
        int room = (weapon.ammo() - this.mount.rounds(this.vehicle)) / perItem;
        int taken = Math.min(offered, room);

        if (taken <= 0) {
            return 0;
        }

        this.mount.rounds(this.vehicle, this.mount.rounds(this.vehicle) + taken * perItem);

        return taken;
    }

    /** 今この弾倉にある発数。 */
    public int rounds() {
        return this.mount.rounds(this.vehicle);
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
        // 砲口を出た後の全部は弾種が決める。弾種の無い砲では兵装ファイル自身の値で、そこは以前と1つも
        // 変わらない。Definitions.round 参照。
        ResourceLocation ammunition = this.ammunition();
        WeaponDefinition.Projectile round = Definitions.round(weapon, ammunition);
        // 砲身を順繰りに、1本1発ずつ。架台のどの砲身も同じ方向へ指向され、同じ弾倉から同じ速度で装填
        // される——連装架台は2門の砲ではなく弾が出る穴が2つあるだけ——ので、砲身であることの全部は
        // 「この弾がどの砲口から出るか」に尽きる。
        int barrels = Math.max(this.mount.barrels(this.vehicle), 1);
        // 待ち時間は1tick未満にできないので、毎秒20発を超える砲はその分を1tickの中で撃つ。ガトリングは
        // それに当たる——M61 の毎分6000発は毎tick5発だ。書いてある発射速度が届かないまま黙って毎秒20発に
        // 頭打ちになるより、tickの粒度で撃つ方が近い。撃てるのは弾倉に残っている分まで。
        int perTick = Math.min(roundsPerTick(weapon.firing().roundsPerSecond()),
                this.mount.rounds(this.vehicle));
        // 1発の引き金で複数発を同時に放つのは salvo。1tickに複数発なのは発射速度で、別の話だ——
        // 前者は同じ瞬間に散らばって出る弾、後者は順に出る弾。
        int salvo = Math.max(1, weapon.firing().salvo());

        // 発射炎と音はこのtickの1発目の砲口から。5発ぶんの炎を重ねても明るくなるだけで、5つには見えない。
        Vec3 muzzle = this.mount.muzzle(this.vehicle, this.barrel % barrels);
        Vec3 bore = this.vehicle.getAimDirection(1.0F);
        Vec3 right = across(bore);
        Vec3 up = right.cross(bore).normalize();
        LivingEntity crew = this.vehicle.getAviator();
        RandomSource random = this.vehicle.getRandom();

        double scatter = Math.tan(Math.toRadians(weapon.firing().spread())) * 0.5;
        double spread = Math.tan(Math.toRadians(weapon.firing().salvoSpread())) * 0.5;

        for (int fired = 0; fired < perTick; fired++) {
            int firing = this.barrel % barrels;

            this.barrel = (firing + 1) % barrels;

            Vec3 from = this.mount.muzzle(this.vehicle, firing);

            for (int i = 0; i < salvo; i++) {
                this.launch(level, weaponId, ammunition, weapon, round, from, bore, right, up, crew,
                        random, scatter, spread);
            }
        }

        if (--this.untilFlash <= 0) {
            WeaponEffects.muzzleBlast(level, muzzle, bore, blastPower(round), round.tracer());
            this.untilFlash = weapon.isAutomatic() ? FLASH_EVERY : 1;
        }

        this.playFireSound(weapon, weaponId);

        // 減るのは撃った弾種の分。弾種を持たない架台では残弾カウンタそのもので、そこも以前と同じ。
        Magazine.spend(this.vehicle, this.mount.station(), perTick);
        this.mount.reload(this.vehicle, ticksFor(weapon.firing().roundsPerSecond()));
    }

    /** 弾1発。散布は砲身を軸にした円錐なので、真上に向けた砲も水平の砲と同じように散る。 */
    private void launch(ServerLevel level, ResourceLocation weaponId, @Nullable ResourceLocation ammunition,
            WeaponDefinition weapon, WeaponDefinition.Projectile round, Vec3 muzzle,
            Vec3 bore, Vec3 right, Vec3 up, LivingEntity crew, RandomSource random, double scatter,
            double spread) {
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

        shot.setup(weaponId, ammunition, this.vehicle, crew);
        shot.setPos(muzzle);
        // setDeltaMovement ではなく launch。速度がクライアントへ届く必要があり、通常それを運ぶ
        // パケットには速すぎるから。VehicleProjectile 参照。
        shot.launch(direction.scale(round.speed()).add(carried));

        level.addFreshEntity(shot);
    }

    /**
     * その発射速度で1tickに出る発数。1発未満にはならない。
     *
     * <p>{@link #ticksFor} と対になっている。あちらは毎秒20発までの砲が何tick待つかを答え、こちらはそれを
     * 超える砲が1tickで何発出すかを答える。どちらか片方だけでは、書いてある発射速度の半分が消える。
     */
    private static int roundsPerTick(float roundsPerSecond) {
        return Math.max(1, Math.round(roundsPerSecond / 20.0F));
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
    private static float blastPower(WeaponDefinition.Projectile round) {
        return Mth.clamp(round.explosion(), 1.5F, 6.0F);
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
