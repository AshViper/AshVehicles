package com.ashvehicles.weapon;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

import javax.annotation.Nullable;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.AshVehicles;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.VehicleProjectile;
import com.ashvehicles.entity.BulletEntity;
import com.ashvehicles.entity.RocketEntity;
import com.ashvehicles.item.EquipmentItem;
import com.ashvehicles.item.RackItem;
import com.ashvehicles.item.WeaponItem;
import com.ashvehicles.entity.VehicleHold;
import com.ashvehicles.registry.ModEntities;
import com.ashvehicles.registry.ModItems;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * 機体がハードポイントに積んでいる物と、その発射処理。
 *
 * <p>機体ファイルのハードポイント1つにつき {@link Mount} が1つ、同じ順に並ぶ。何を保持できるかは
 * ハードポイントの種別で決まる。{@link AircraftDefinition.Hardpoint.Kind} 参照。
 *
 * <ul>
 * <li><b>fixed</b> は機体に内蔵された兵装。搭載枠1つで、常にファイルが指定した物が満載され、誰も変えら
 *     れない。
 * <li><b>weapon</b> パイロンは {@link RackDefinition ラック} を1つ載せ、その上に兵装を載せる。パイロン
 *     自体は何も受けない。裸のパイロンは翼の取付点でしかなく、レールや投下ラックは先に付ける別の金具。
 *     何発積めるか、各発がどこに吊られるか、どの種類を受けるかは全部ラックが決める。
 * <li><b>special</b> ステーションは {@link EquipmentDefinition ポッド} を1つ、ラックを挟まず直付けする。
 *     ここから何かが撃たれることは無い。
 * </ul>
 *
 * <p>パイロットが選ぶのはステーションではなく<em>兵装</em>だ。選択した兵装は、対のポッドや連装砲の2門の
 * ように、全搭載分が同時に撃つ。弾は各兵装のラック上の位置から機首方向へ出て、機体の速度が上乗せされる。
 *
 * <p>これら全部はサーバーの持ち物。発射も残弾管理も再武装もサーバーが行う。クライアントは計器表示と翼下の
 * 描画のために写しを持ち、エンティティの同期データで更新される。
 */
public final class WeaponMounts {
    /** 駐機中の機体が空の兵装を満たすのにかかる tick 数。地上要員を抽象化した値。 */
    private static final int REARM_TICKS = 200;
    /** 兵装名から音イベントを引く時の接頭辞。{@code weapon.<name>} の形。 */
    public static final String SOUND_PREFIX = "weapon.";

    /**
     * 地上作業の音。ステーションへ何かを付ける、あるいは外す音。
     *
     * <p>MOD の他の音と一緒ではなくここに置いてあるのは、これがサーバーから名前で要求される唯一の音であり、
     * サーバーはクライアント側の一覧を見られないから。この名前の音声を持たないクライアントはゲーム本体の
     * 何かにフォールバックする。{@link com.ashvehicles.client.sound.WeaponSounds} 参照。
     */
    public static final ResourceLocation LOAD_SOUND =
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, SOUND_PREFIX + "load");
    /** その音量。地上作業なので、機体のそばに立っている者に聞こえる程度。 */
    public static final float LOAD_VOLUME = 0.9F;
    /** 吊る時と外す時。同じ音を逆向きにした物。 */
    public static final float LOAD_PITCH = 1.0F;
    public static final float UNLOAD_PITCH = 0.85F;

    /** 搭載位置ゼロ。裸の兵装パイロンと、全ての special ステーション。 */
    private static final Load[] NOTHING = new Load[0];

    /** ラック1つの1箇所に載る兵装1発分。 */
    public static final class Load {
        @Nullable
        private ResourceLocation weapon;
        private int ammo;
        /** この兵装が次に撃てるまでの tick。端数で保持するので半端な発射速度も正しく出る。 */
        private float cooldown;

        public boolean isEmpty() {
            return this.weapon == null;
        }

        @Nullable
        public ResourceLocation weapon() {
            return this.weapon;
        }

        public int ammo() {
            return this.ammo;
        }

        private void set(@Nullable ResourceLocation weapon, int ammo) {
            this.weapon = weapon;
            this.ammo = weapon == null ? 0 : ammo;
            this.cooldown = 0.0F;
        }
    }

    /** ハードポイント1つ分。そこに取り付いている物と、そこにぶら下がっている物。 */
    public static final class Mount {
        /**
         * 砲座が振っているこの位置で、前 tick に引き金が引かれていたか。1押し1発の砲を押しっぱなしで
         * 連射させないための記録で、パイロットの選択側が {@code triggerHeld} で持っているのと同じ物。
         * 別に持つのは、砲座の引き金がパイロットの引き金とは限らないからだ。
         */
        private boolean held;

        /** weapon パイロンに付いたラック。裸なら null。他の種別では決して設定されない。 */
        @Nullable
        private ResourceLocation rack;
        /** special ステーションのポッド。裸なら null。他の種別では決して設定されない。 */
        @Nullable
        private ResourceLocation equipment;
        /** 付いているラックの位置ごとに1つ。fixed なら1つ、裸なら0。 */
        private Load[] loads = NOTHING;
        @Nullable
        private List<Load> loadsView;

        @Nullable
        public ResourceLocation rack() {
            return this.rack;
        }

        @Nullable
        public ResourceLocation equipment() {
            return this.equipment;
        }

        public boolean hasRack() {
            return this.rack != null;
        }

        public boolean hasEquipment() {
            return this.equipment != null;
        }

        /**
         * このステーションの全搭載位置。積載の有無に関わらず、ラックが列挙する順で。
         *
         * <p>{@link Load} は可変でその場に留まり、入れ替わるのは周りの配列だけ。入れ替わる時は
         * {@code ensureLayout} がこのビューを捨てる。だからリストは問い合わせごとではなくレイアウトごとに
         * 1回作られる——ここは計器から毎フレーム数回、レンダラーから毎フレーム1回訊かれる。
         */
        public List<Load> loads() {
            List<Load> view = this.loadsView;

            if (view == null) {
                view = List.of(this.loads);
                this.loadsView = view;
            }

            return view;
        }

        /** このステーションに何も無いか。ラックもポッドも吊り物も無い状態。 */
        public boolean isBare() {
            return this.rack == null && this.equipment == null && this.loaded() == 0;
        }

        /** 兵装が載っている位置の数。 */
        public int loaded() {
            int count = 0;

            for (Load load : this.loads) {
                if (!load.isEmpty()) {
                    count++;
                }
            }

            return count;
        }

        /** このステーション上の、指定兵装の総残弾。載っている全位置の合計。 */
        public int ammoOf(ResourceLocation weapon) {
            int total = 0;

            for (Load load : this.loads) {
                if (weapon.equals(load.weapon)) {
                    total += load.ammo;
                }
            }

            return total;
        }

        /** 何も載っていない最初の位置。満載なら -1。 */
        private int freePlace() {
            for (int place = 0; place < this.loads.length; place++) {
                if (this.loads[place].isEmpty()) {
                    return place;
                }
            }

            return -1;
        }

        /** 何かが載っている最後の位置。空なら -1。 */
        private int lastLoaded() {
            for (int place = this.loads.length - 1; place >= 0; place--) {
                if (!this.loads[place].isEmpty()) {
                    return place;
                }
            }

            return -1;
        }

        /** 搭載位置の数を変える。残る位置の載せ物は保つ。 */
        private void resize(int places) {
            if (this.loads.length == places) {
                return;
            }

            Load[] resized = places == 0 ? NOTHING : new Load[places];

            for (int place = 0; place < places; place++) {
                resized[place] = place < this.loads.length ? this.loads[place] : new Load();
            }

            this.loads = resized;
            this.loadsView = null;
        }

        private void strip() {
            this.rack = null;
            this.equipment = null;
            this.resize(0);
        }
    }

    private final AircraftEntity aircraft;
    /** 選択中の兵装のシーカーが捉えている相手。シーカーを持つ兵装のみ。 */
    private final TargetLock lock;
    private Mount[] mounts = new Mount[0];
    /** {@link #mounts} の不変リスト版。背後の配列が作り直された時だけ作り直す。 */
    @Nullable
    private List<Mount> mountsView;
    @Nullable
    private ResourceLocation selected;
    /** サーバー側の内容が、最後にクライアントへ送ってから変わったか。 */
    private boolean dirty;
    /** 前 tick の引き金状態。単発の兵装が「押した」と「押しっぱなし」を区別するため。 */
    private boolean triggerHeld;

    public WeaponMounts(AircraftEntity aircraft) {
        this.aircraft = aircraft;
        this.lock = new TargetLock(aircraft);
    }

    /** シーカーが捉えている相手。計器が読む。決めるのは常にサーバーだけ。 */
    public TargetLock lock() {
        return this.lock;
    }

    /** 選択中の兵装の諸元。何も選択していなければ null。 */
    @Nullable
    public WeaponDefinition selectedWeapon() {
        return this.selected == null ? null : Definitions.weapon(this.selected);
    }

    // ------------------------------------------------------------------
    // 読み取り
    // ------------------------------------------------------------------

    public List<Mount> mounts() {
        this.ensureLayout();

        List<Mount> view = this.mountsView;

        if (view == null) {
            // Mount は可変でその場に留まり、入れ替わるのは周りの配列だけ。入れ替わる時は ensureLayout が
            // これを捨てる。だからリストは問い合わせごとではなくレイアウトごとに1回作られる——ここは同じ
            // 数個のオブジェクトのために、計器から毎フレーム数回、レンダラーから毎フレーム1回、ゴーストから
            // 毎tick1回訊かれる。
            view = List.of(this.mounts);
            this.mountsView = view;
        }

        return view;
    }

    /** その Mount が属するハードポイント。ファイル側に該当が無くなっていれば null。 */
    @Nullable
    public AircraftDefinition.Hardpoint hardpoint(int slot) {
        List<AircraftDefinition.Hardpoint> hardpoints = this.aircraft.getStats().hardpoints();

        return slot >= 0 && slot < hardpoints.size() ? hardpoints.get(slot) : null;
    }

    /** ステーションに付いているラック。無ければフォールバック。null にはならないので安心して訊ける。 */
    private RackDefinition rackOf(Mount mount) {
        return mount.rack == null ? RackDefinition.FALLBACK : Definitions.rack(mount.rack);
    }

    /**
     * 兵装1発が吊られる位置。機体自身の軸で、パイロン位置＋ラックのその位置分のオフセット。
     *
     * <p>兵装を指す必要がある物すべてが訊く場所——レンダラー、ゴースト、照準、そして弾が出る砲口。ラックの
     * 無いステーションはパイロン位置をそのまま返し、そこが fixed 兵装の弾の出所になる。
     */
    public Vec3 placeOf(int slot, int place) {
        AircraftDefinition.Hardpoint hardpoint = this.hardpoint(slot);

        if (hardpoint == null) {
            return Vec3.ZERO;
        }

        Mount mount = slot < this.mounts.length ? this.mounts[slot] : null;

        if (mount == null || mount.rack == null) {
            return hardpoint.pos();
        }

        return hardpoint.pos().add(this.rackOf(mount).place(place));
    }

    /** 引き金が撃つ兵装。何も選択していなければ null。 */
    @Nullable
    public ResourceLocation selected() {
        return this.selected;
    }

    /** 選択中の兵装を積んでいる全搭載分の残弾合計。 */
    public int selectedAmmo() {
        return this.selected == null ? 0 : this.ammoOf(this.selected);
    }

    /** 指定した兵装を積んでいる全搭載分の残弾合計。 */
    public int ammoOf(ResourceLocation weapon) {
        int total = 0;

        for (Mount mount : this.mounts()) {
            total += mount.ammoOf(weapon);
        }

        return total;
    }

    /** プレイヤーが回収できる物を、どこかのステーションが積んでいるか。 */
    public boolean hasRemovable() {
        this.ensureLayout();

        for (int slot = 0; slot < this.mounts.length; slot++) {
            if (this.canStripAt(slot)) {
                return true;
            }
        }

        return false;
    }

    /**
     * レーダーから見える位置に積んでいる物の数。
     *
     * <p>重さではなく個数で数える。レーダーが嫌うのは外部搭載物と吊っている翼が作る角であり、小さなミサイル
     * も大型爆弾とほぼ同じくらい悪い角を作る。ポッドも同程度に悪く、何も載せずに残されたラックは平板と角の
     * 骨組みで大半より悪い——それが空のラックを外す理由であり、裸のラックをここで数える理由でもある。機体に
     * 内蔵された砲は形状の一部で、搭載物ではない。
     */
    public int externalStores() {
        this.ensureLayout();

        int outside = 0;

        for (int slot = 0; slot < this.mounts.length; slot++) {
            AircraftDefinition.Hardpoint hardpoint = this.hardpoint(slot);

            if (hardpoint == null || hardpoint.isFixed() || hardpoint.internal()) {
                continue;
            }

            Mount mount = this.mounts[slot];

            if (mount.hasEquipment()) {
                outside++;
            } else if (mount.hasRack()) {
                outside += Math.max(1, mount.loaded());
            }
        }

        return outside;
    }

    /**
     * 積んでいる兵装の種類一覧。ハードポイント順。
     *
     * <p>増槽は入らない。この一覧が答えているのは「引き金は何を選べるか」であって「何を吊っているか」では
     * なく、増槽を混ぜれば兵装切り替えが、撃てない物を一巡ごとに1回選ぶようになる。吊っている物の一覧が
     * 欲しい計器は {@link #carriedStores()} を見る。
     */
    public List<ResourceLocation> carried() {
        List<ResourceLocation> weapons = new ArrayList<>();

        for (int slot = 0; slot < this.mounts.length; slot++) {
            // 砲座が振っている位置の砲は、パイロットの選択には出てこない。撃つのはその砲座を持つ乗員で
            // あり、引き金も向きもそちらにある。同じ位置のミサイルや爆弾は従来通りここに並ぶ。
            boolean crewed = this.aircraft.getStations().stationForSlot(slot) != GunStations.NONE;

            for (Load load : this.mounts[slot].loads()) {
                if (load.weapon == null || weapons.contains(load.weapon)) {
                    continue;
                }

                WeaponDefinition weapon = Definitions.weapon(load.weapon);

                if (crewed && weapon.type() == WeaponDefinition.Type.GUN) {
                    continue;
                }

                if (weapon.type().isFired()) {
                    weapons.add(load.weapon);
                }
            }
        }

        return weapons;
    }

    /** その位置に載っている物。空なら null。複数積める位置では最初の1つ。 */
    @Nullable
    public ResourceLocation weaponAt(int slot) {
        if (slot < 0 || slot >= this.mounts.length) {
            return null;
        }

        for (Load load : this.mounts[slot].loads()) {
            if (load.weapon != null) {
                return load.weapon;
            }
        }

        return null;
    }

    /** その位置の残弾合計。 */
    public int ammoAt(int slot) {
        if (slot < 0 || slot >= this.mounts.length) {
            return 0;
        }

        int ammo = 0;

        for (Load load : this.mounts[slot].loads()) {
            ammo += load.ammo();
        }

        return ammo;
    }

    /** 特定の兵装を選択する。引き金1つが巡る一覧を砲座と共有しているので、外から据える手段が要る。 */
    public void select(ResourceLocation weapon) {
        this.selected = weapon;
        this.dirty = true;
    }

    /** 吊っている物の一覧。撃てるかどうかを問わないので増槽も入る。計器の搭載一覧向け。 */
    public List<ResourceLocation> carriedStores() {
        List<ResourceLocation> stores = new ArrayList<>();

        for (Mount mount : this.mounts()) {
            for (Load load : mount.loads()) {
                if (load.weapon != null && !stores.contains(load.weapon)) {
                    stores.add(load.weapon);
                }
            }
        }

        return stores;
    }

    // ------------------------------------------------------------------
    // 増槽
    // ------------------------------------------------------------------

    /**
     * 吊っている増槽から燃料を引く。要求した量と、実際に引けた量を返す。
     *
     * <p>外側から先に空にする。実機の順序であり、理由も同じだ——増槽は投棄するために積む物なので、投棄でき
     * る状態、つまり空の状態へ早く到達するほど良い。機体本体のタンクは増槽が尽きるまで満タンのまま残るので、
     * 「増槽を落とした瞬間に航続距離が尽きる」ことにはならない。
     *
     * <p>空になった増槽はパイロンに残る。落とすのはパイロットの判断だ——空でも吊っていれば抗力を払い続ける
     * ので、それが投棄キーに意味を与えている。
     *
     * @param wanted 引きたい量
     * @return 実際に引けた量。増槽を積んでいなければ0
     */
    public float drawFuel(float wanted) {
        if (wanted <= 0.0F) {
            return 0.0F;
        }

        float drawn = 0.0F;

        for (Mount mount : this.mounts()) {
            for (Load load : mount.loads()) {
                if (load.weapon == null || load.ammo <= 0
                        || Definitions.weapon(load.weapon).type() != WeaponDefinition.Type.TANK) {
                    continue;
                }

                // 燃料は整数単位で数える。ammo と同じ数値であり、同じ物だからだ。要求を切り上げずに切り捨て
                // るのは、入る場所より多く引けばその差が満タンで切り捨てられて消えるから。1tickの消費が1単位
                // に満たない機体では、空きが1単位溜まった tick に初めて1単位引かれる。
                int take = (int) Math.min(load.ammo, Math.floor(wanted - drawn));

                if (take <= 0) {
                    continue;
                }

                load.ammo -= take;
                drawn += take;
                this.dirty = true;

                if (drawn >= wanted) {
                    return drawn;
                }
            }
        }

        return drawn;
    }

    /**
     * 吊っている増槽へ燃料を入れる。入った量を返す。
     *
     * <p>内側から先に満たす。引くときと逆順なのは意図で、こうすると「半端に減った増槽が1本」ではなく
     * 「満タンの増槽と空の増槽」に寄る。落とすべき物がはっきりしている方が、投棄の判断は楽だ。
     *
     * @param units 入れたい量
     * @return 実際に入った量。増槽を積んでいないか、全部満タンなら0
     */
    public int fillTanks(int units) {
        if (units <= 0) {
            return 0;
        }

        int filled = 0;

        for (Mount mount : this.mounts()) {
            for (Load load : mount.loads()) {
                if (load.weapon == null) {
                    continue;
                }

                WeaponDefinition tank = Definitions.weapon(load.weapon);

                if (tank.type() != WeaponDefinition.Type.TANK || load.ammo >= tank.ammo()) {
                    continue;
                }

                int room = Math.min(tank.ammo() - load.ammo, units - filled);
                load.ammo += room;
                filled += room;
                this.dirty = true;

                if (filled >= units) {
                    return filled;
                }
            }
        }

        return filled;
    }

    /** 吊っている増槽の残量の合計。計器向け。 */
    public int tankFuel() {
        int total = 0;

        for (Mount mount : this.mounts()) {
            for (Load load : mount.loads()) {
                if (load.weapon != null
                        && Definitions.weapon(load.weapon).type() == WeaponDefinition.Type.TANK) {
                    total += load.ammo;
                }
            }
        }

        return total;
    }

    /** 増槽を1つでも吊っているか。投棄キーが何かをする余地があるかの判定。 */
    public boolean hasTank() {
        for (Mount mount : this.mounts()) {
            for (Load load : mount.loads()) {
                if (load.weapon != null
                        && Definitions.weapon(load.weapon).type() == WeaponDefinition.Type.TANK) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 吊っている増槽を全部切り離す。飛行中でもよく、むしろそのための機能だ。
     *
     * <p>全部同時に落とす。片側だけ落とせば機体は非対称になり、実機のパイロットが最も避けたい状態がそれだ。
     * 増槽は左右そろえて落とす物であり、それは選択肢ではなく手順だ。
     *
     * <p>落とした物は返らない。空へ捨てた増槽であって、外して持ち帰った増槽ではない——それはレンチの仕事で、
     * そちらは地上でしかできない代わりに物が返る。この非対称こそが2つの操作を別物にしている。
     *
     * @return 落とした数。0なら何も吊っていなかった
     */
    public int jettisonTanks() {
        int dropped = 0;

        for (Mount mount : this.mounts()) {
            for (Load load : mount.loads()) {
                if (load.weapon != null
                        && Definitions.weapon(load.weapon).type() == WeaponDefinition.Type.TANK) {
                    load.set(null, 0);
                    dropped++;
                }
            }
        }

        if (dropped > 0) {
            this.dirty = true;
            this.reselect();
        }

        return dropped;
    }

    /**
     * 吊っている物が加える寄生抗力の合計。
     *
     * <p>空気は何を吊っているかを気にしない。増槽も爆弾も、翼の下に垂れ下がっていれば同じように機体を遅く
     * する。だから増槽が「ただ航続距離が伸びるだけの選択」にならない——燃料と引き換えに速度と旋回を差し出す
     * ので、積むかどうかが判断になる。ファイルが {@code drag} を書かない兵装は0を足すので、これを書く前の
     * 挙動は完全にそのまま残る。
     */
    public float storeDrag() {
        float drag = 0.0F;

        for (Mount mount : this.mounts()) {
            for (Load load : mount.loads()) {
                if (load.weapon != null) {
                    drag += Definitions.weapon(load.weapon).drag();
                }
            }
        }

        return drag;
    }

    // ------------------------------------------------------------------
    // ポッドの効果
    // ------------------------------------------------------------------

    /**
     * 積んでいる全ポッドの、ある1つの数値の積。
     *
     * <p>加算ではなく乗算にすることで、2個目が1個目と同じ効果にならずに重ねられ、何も載っていない
     * ステーションは何も変えない。毎回歩き直すのは、ステーションが数個しか無く、ここが訊かれるのは
     * 多くても1tickに数回だから。
     */
    private float podGain(ToDoubleFunction<EquipmentDefinition> figure) {
        float gain = 1.0F;

        for (Mount mount : this.mounts()) {
            if (mount.equipment != null) {
                gain *= (float) figure.applyAsDouble(Definitions.equipment(mount.equipment));
            }
        }

        return gain;
    }

    /** その種別のポッドを、どこかの special ステーションに積んでいるか。 */
    public boolean hasPod(EquipmentDefinition.Kind kind) {
        for (Mount mount : this.mounts()) {
            if (mount.equipment != null && Definitions.equipment(mount.equipment).kind() == kind) {
                return true;
            }
        }

        return false;
    }

    /**
     * この兵装が必要としていて機体が積んでいないポッド。撃つのを妨げる物が無ければ null。
     *
     * <p>引き金が訊き、計器も訊く。パイロットが「何も起きないボタンを押す」のではなく、兵装が撃たれない
     * <em>理由</em>を知れるように。誰も指示器を当てていない状態のレーザー誘導爆弾こそ、これが存在する理由。
     * {@link WeaponDefinition#requires} 参照。
     */
    @Nullable
    public EquipmentDefinition.Kind missingPod(@Nullable WeaponDefinition weapon) {
        if (weapon == null) {
            return null;
        }

        EquipmentDefinition.Kind needed = weapon.requires().orElse(null);

        return needed != null && !this.hasPod(needed) ? needed : null;
    }

    /** 同じ物を兵装 ID で。ID しか持っていない呼び出し側向け。 */
    @Nullable
    public EquipmentDefinition.Kind missingPod(ResourceLocation weapon) {
        return this.missingPod(Definitions.weapon(weapon));
    }

    /** 何も付けていない状態に対し、シーカーがどれだけ遠くまで届くか。 */
    public float seekerRangeGain() {
        return this.podGain(EquipmentDefinition::seekerRange);
    }

    /** 何も付けていない状態に対し、ロックがどれだけ速く決まるか。 */
    public float lockRateGain() {
        return this.podGain(EquipmentDefinition::lockRate);
    }

    /** ポッドが機体のレーダー反射に与える影響。ジャマーなら1未満。 */
    public float radarGain() {
        return this.podGain(EquipmentDefinition::radarGain);
    }

    /** 熱源追尾ヘッドから見た場合の同じ物。 */
    public float heatGain() {
        return this.podGain(EquipmentDefinition::heatGain);
    }

    /**
     * この機体を狙っているレーダーシーカーが、ロックを決めるのにどれだけ余計に時間を要するか。ジャマーなら
     * 1を超える。
     *
     * <p>他のポッドと逆向きに働く唯一の数値だ——効くのは積んでいる機体ではなく、その機体を撃とうとして
     * いる者の側。だから訊くのは相手の {@link TargetLock} であって、この機体のtickではない。
     */
    public float lockDelay() {
        return this.podGain(EquipmentDefinition::lockDelay);
    }

    // ------------------------------------------------------------------
    // 搭載と取り外し
    // ------------------------------------------------------------------

    /** 今このステーションにラックを付けられるか。weapon パイロンであり、かつ裸であること。 */
    public boolean canFitRackAt(int slot) {
        this.ensureLayout();
        AircraftDefinition.Hardpoint hardpoint = this.hardpoint(slot);

        return hardpoint != null && hardpoint.isWeaponPylon() && slot < this.mounts.length
                && !this.mounts[slot].hasRack();
    }

    /** 指定したステーションにラックを付ける。 */
    public boolean fitRackAt(int slot, ResourceLocation rack) {
        if (!this.canFitRackAt(slot)) {
            return false;
        }

        Mount mount = this.mounts[slot];
        mount.rack = rack;
        mount.resize(Definitions.rack(rack).capacity());
        this.dirty = true;
        this.playLoadSound(true);

        return true;
    }

    /** ラックの付いていない最初の weapon パイロンにラックを付ける。 */
    public boolean fitRack(ResourceLocation rack) {
        this.ensureLayout();

        for (int slot = 0; slot < this.mounts.length; slot++) {
            if (this.fitRackAt(slot, rack)) {
                return true;
            }
        }

        return false;
    }

    /** 今このステーションにポッドを付けられるか。special ステーションであり、かつ裸であること。 */
    public boolean canFitEquipmentAt(int slot) {
        this.ensureLayout();
        AircraftDefinition.Hardpoint hardpoint = this.hardpoint(slot);

        return hardpoint != null && hardpoint.isSpecialPylon() && slot < this.mounts.length
                && !this.mounts[slot].hasEquipment();
    }

    /** 指定した special ステーションにポッドを付ける。 */
    public boolean fitEquipmentAt(int slot, ResourceLocation equipment) {
        if (!this.canFitEquipmentAt(slot)) {
            return false;
        }

        this.mounts[slot].equipment = equipment;
        this.dirty = true;
        this.playLoadSound(true);

        return true;
    }

    /** ポッドの付いていない最初の special ステーションにポッドを付ける。 */
    public boolean fitEquipment(ResourceLocation equipment) {
        this.ensureLayout();

        for (int slot = 0; slot < this.mounts.length; slot++) {
            if (this.fitEquipmentAt(slot, equipment)) {
                return true;
            }
        }

        return false;
    }

    /**
     * このステーションがその兵装を受けるか。ラックが付いており、空き位置があり、そのラックがその種類を
     * そもそも積めること。
     */
    public boolean canMountAt(int slot, ResourceLocation weapon) {
        this.ensureLayout();
        AircraftDefinition.Hardpoint hardpoint = this.hardpoint(slot);

        if (hardpoint == null || !hardpoint.isWeaponPylon() || slot >= this.mounts.length) {
            return false;
        }

        Mount mount = this.mounts[slot];

        return mount.hasRack() && mount.freePlace() >= 0
                && this.rackOf(mount).takes(Definitions.weapon(weapon));
    }

    /**
     * 指定したステーションの最初の空き位置に兵装を吊る。
     *
     * @param ammo 初期残弾。-1 なら満載
     * @return そのステーションが受けないなら false
     */
    public boolean mountAt(int slot, ResourceLocation weapon, int ammo) {
        if (!this.canMountAt(slot, weapon)) {
            return false;
        }

        Mount mount = this.mounts[slot];
        WeaponDefinition fitted = Definitions.weapon(weapon);
        int capacity = fitted.ammo();
        mount.loads[mount.freePlace()].set(weapon, ammo < 0 ? capacity : Math.min(ammo, capacity));

        // 増槽は選ばない。撃てない物が選択されていれば、引き金は何も起こさないまま押せてしまう。
        if (this.selected == null && fitted.type().isFired()) {
            this.selected = weapon;
        }

        this.dirty = true;
        this.playLoadSound(true);

        return true;
    }

    /**
     * 空きのある最初のラックに兵装を吊る。
     *
     * @param ammo 初期残弾。-1 なら満載
     * @return 受けられるラックが1つも無ければ false
     */
    public boolean mount(ResourceLocation weapon, int ammo) {
        this.ensureLayout();

        for (int slot = 0; slot < this.mounts.length; slot++) {
            if (this.mountAt(slot, weapon, ammo)) {
                return true;
            }
        }

        return false;
    }

    /**
     * どこかのラックがその兵装を受けるか。吊らずに判定だけする。
     *
     * <p>機体が右クリックの意味を決める前に訊く。どこにも載らない物を差し出した場合、静かに何も起きないの
     * ではなく、そのクリックが本来行うはずだった処理へ流れるように。
     */
    public boolean canMount(ResourceLocation weapon) {
        this.ensureLayout();

        for (int slot = 0; slot < this.mounts.length; slot++) {
            if (this.canMountAt(slot, weapon)) {
                return true;
            }
        }

        return false;
    }

    /** 裸でラックを受けられる weapon パイロンがあるか。 */
    public boolean hasBarePylon() {
        this.ensureLayout();

        for (int slot = 0; slot < this.mounts.length; slot++) {
            if (this.canFitRackAt(slot)) {
                return true;
            }
        }

        return false;
    }

    /** 裸でポッドを受けられる special ステーションがあるか。 */
    public boolean hasBareSpecial() {
        this.ensureLayout();

        for (int slot = 0; slot < this.mounts.length; slot++) {
            if (this.canFitEquipmentAt(slot)) {
                return true;
            }
        }

        return false;
    }

    /** このステーションにプレイヤーが回収できる物があるか。 */
    public boolean canStripAt(int slot) {
        this.ensureLayout();
        AircraftDefinition.Hardpoint hardpoint = this.hardpoint(slot);

        return hardpoint != null && hardpoint.isPylon() && slot < this.mounts.length
                && !this.mounts[slot].isBare();
    }

    /**
     * 指定ステーションから1つ外し、そのアイテムとして返す。
     *
     * <p>外側から順に。兵装はラックから1発ずつ、最後に載せた物から外れ、ラックが空になって初めてラック自体
     * が外れる。この順序は作法の問題ではない——爆弾4発を載せたまま外したラックは、その4発をどこかへ置か
     * ねばならず、置ける場所が無い。ポッドは先に外す物が無いのでそのまま外れる。
     */
    public ItemStack strip(int slot) {
        if (!this.canStripAt(slot)) {
            return ItemStack.EMPTY;
        }

        Mount mount = this.mounts[slot];
        ItemStack stack;
        int place = mount.lastLoaded();

        if (place >= 0) {
            Load load = mount.loads[place];
            stack = WeaponItem.stackOf(load.weapon, load.ammo);
            load.set(null, 0);
        } else if (mount.rack != null) {
            stack = RackItem.stackOf(mount.rack);
            mount.rack = null;
            mount.resize(0);
        } else {
            stack = EquipmentItem.stackOf(mount.equipment);
            mount.equipment = null;
        }

        this.dirty = true;
        this.reselect();
        this.playLoadSound(false);

        return stack;
    }

    /**
     * 最後に載せた物を、どこにあっても外してアイテムとして返す。
     *
     * <p>どのステーションかは、順に試すのではなく外す前に確定させる。空の戻り値は「何も起きなかった」と
     * 同義ではない。アイテムを持たないと書かれたラックは機体から外れるが何も返さないので、それを失敗と読む
     * ループは隣のステーションまで剥がしてしまう。
     */
    public ItemStack strip() {
        this.ensureLayout();

        for (int slot = this.mounts.length - 1; slot >= 0; slot--) {
            if (this.canStripAt(slot)) {
                return this.strip(slot);
            }
        }

        return ItemStack.EMPTY;
    }

    /**
     * 全ステーションを空にする。何も返さない。
     *
     * <p>撃墜された機体のための処理。翼下に吊っていた物はラックもポッドも一緒に燃えた。何も落とさず何も
     * 返さない——これは誰かが機体から降ろしているのではなく、機体が機体でなくなったということ。
     *
     * <p>機体に内蔵された砲は次のレイアウト確認で戻ってくる。それが正しい。fixed ステーションは吊り物では
     * なく機械の一部で、機体がどんな状態でもモデルは描き続ける。撃つことは無い。残骸はもう兵装を tick して
     * いないので。
     */
    public void clear() {
        this.ensureLayout();

        for (Mount mount : this.mounts) {
            mount.strip();
        }

        this.selected = null;
        this.lock.clear();
        this.dirty = true;
    }

    /** 積んでいる次の兵装を選ぶ。ハードポイント順で、末尾から先頭へ回る。 */
    public void selectNext() {
        List<ResourceLocation> weapons = this.carried();

        if (weapons.isEmpty()) {
            this.selected = null;
        } else {
            int index = weapons.indexOf(this.selected);
            this.selected = weapons.get((index + 1) % weapons.size());
        }

        this.dirty = true;
    }

    /** 積んでいる物があれば、何かが必ず選択されている状態にする。 */
    private void reselect() {
        List<ResourceLocation> weapons = this.carried();

        if (this.selected == null || !weapons.contains(this.selected)) {
            this.selected = weapons.isEmpty() ? null : weapons.get(0);
            this.dirty = true;
        }
    }

    /**
     * 搭載構成を機体ファイルと装着中のラックに合わせる。ハードポイント1つにつき Mount 1つ、fixed には
     * ファイルの指定物を満載、各ラックの位置数はラック自身のファイル通り。安い処理なので毎回の使用前に走る。
     * だからステーションを追加した {@code /reload} は即座に反映され、削除しても幽霊の Mount がどこからとも
     * なく撃つことは無く、ラックを短くすれば無くなった位置は落とされて端の外へ撃つことも無い。
     *
     * <p>リロードで<em>種別</em>が変わったステーションは空にする。爆弾を積んでいたパイロットが今はセンサー
     * ステーションになったなら爆弾を保持し続けられないし、黙って残せば「自分のファイルがポッドだと言って
     * いる場所から撃つ機体」になる。
     */
    private void ensureLayout() {
        List<AircraftDefinition.Hardpoint> hardpoints = this.aircraft.getStats().hardpoints();

        if (this.mounts.length != hardpoints.size()) {
            Mount[] resized = new Mount[hardpoints.size()];

            for (int slot = 0; slot < resized.length; slot++) {
                resized[slot] = slot < this.mounts.length ? this.mounts[slot] : new Mount();
            }

            this.mounts = resized;
            this.mountsView = null;
            this.dirty = true;
        }

        for (int slot = 0; slot < this.mounts.length; slot++) {
            AircraftDefinition.Hardpoint hardpoint = hardpoints.get(slot);
            Mount mount = this.mounts[slot];

            if (hardpoint.isFixed()) {
                ResourceLocation weapon = hardpoint.fixed().get();

                if (mount.rack != null || mount.equipment != null || mount.loads.length != 1) {
                    mount.rack = null;
                    mount.equipment = null;
                    mount.resize(1);
                    this.dirty = true;
                }

                if (!weapon.equals(mount.loads[0].weapon)) {
                    mount.loads[0].set(weapon, Definitions.weapon(weapon).ammo());
                    this.dirty = true;
                }

                continue;
            }

            if (hardpoint.isSpecialPylon()) {
                if (mount.rack != null || mount.loads.length != 0) {
                    mount.rack = null;
                    mount.resize(0);
                    this.dirty = true;
                }

                continue;
            }

            if (mount.equipment != null) {
                mount.equipment = null;
                this.dirty = true;
            }

            int places = mount.rack == null ? 0 : Definitions.rack(mount.rack).capacity();

            if (mount.loads.length != places) {
                mount.resize(places);
                this.dirty = true;
            }
        }
    }

    // ------------------------------------------------------------------
    // 発射（サーバー側）
    // ------------------------------------------------------------------

    /**
     * サーバー側の1tick分。引き金が引かれていれば撃ち、発射間隔を数え、駐機中なら再武装する。
     *
     * @param trigger この tick にパイロットが引き金を引いているか
     * @param wantsLock この tick にパイロットがシーカー用のキーを押しているか。押していれば、まだ追尾して
     *                  いない目標を取ってよい。{@link TargetLock#tick} 参照
     */
    public void tick(boolean trigger, boolean wantsLock) {
        this.ensureLayout();
        this.reselect();

        WeaponDefinition selectedWeapon = this.selected == null ? null : Definitions.weapon(this.selected);

        // 機体が使う装備を持たない兵装は、積まれ、描かれ、一覧に出るが、それ以外は何もしない。1回計算して
        // 2箇所で使う。下の引き金を止め、ここでシーカーを止める。指示器を積んでいないレーザー誘導爆弾には
        // ロックする相手が無く、画面に枠を描けば「撃てない射撃」を約束することになるから。
        boolean equipped = this.missingPod(selectedWeapon) == null;

        // シーカーが見るのは、ロックを使える物が選択されている間だけ。
        if (this.lock.tick(equipped ? seekerOf(selectedWeapon) : null, wantsLock)) {
            this.dirty = true;
        } else if (this.lock.isClosing()) {
            // 目標もロック状態も変わっていないが、変わった物がある——ロックの進捗だ。パイロットが機首を
            // 何かに乗せている間、シーカーの枠とトーンはその値だけでできているので、動いている数秒間は毎
            // tick 送る。TargetLock.isClosing 参照。
            this.dirty = true;
        }

        boolean fired = false;
        // パイロットが砲座を選んでいる間、パイロンの兵装は引き金に繋がっていない。引き金は1つで、選択も
        // 1つだからだ。GunStations.cycle 参照。
        boolean armed = trigger && selectedWeapon != null && equipped && !this.aircraft.isCrashing()
                && this.aircraft.getControllingPassenger() instanceof Player
                && !this.aircraft.getStations().pilotHoldsStation();

        // 自動兵装は引き金を引いている間撃ち続ける。それ以外は1押し1発なので、押しっぱなしでミサイル
        // レールが0.5秒で空になったりしない。どちらかは兵装が決める。WeaponDefinition.isAutomatic 参照。
        if (armed && !selectedWeapon.isAutomatic() && this.triggerHeld) {
            armed = false;
        }

        this.triggerHeld = trigger;

        GunStations stations = this.aircraft.getStations();

        pylons:
        for (int slot = 0; slot < this.mounts.length; slot++) {
            Mount mount = this.mounts[slot];

            // この位置を振っている砲座があるなら、そこに載る砲の引き金も向きもそちらが持っている。
            int station = stations.stationForSlot(slot);
            boolean crewed = station != GunStations.NONE;
            boolean pressed = crewed && stations.pulled(station);
            Vec3 laidAim = crewed ? stations.direction(station, 1.0F) : null;
            ResourceLocation crewFired = null;

            for (int place = 0; place < mount.loads.length; place++) {
                Load load = mount.loads[place];

                if (load.isEmpty()) {
                    continue;
                }

                WeaponDefinition weapon = Definitions.weapon(load.weapon);
                // 砲座が振れるのは砲だけだ。同じ位置のミサイルは発射時のロックへケージングされ、爆弾は
                // 落ちるだけで、どちらも「どこを向いているか」を持たない。従来通りパイロットが撃つ。
                boolean laid = crewed && weapon.type() == WeaponDefinition.Type.GUN;
                boolean pull = laid
                        ? pressed && (weapon.isAutomatic() || !mount.held)
                        : armed && load.weapon.equals(this.selected);

                if (!pull) {
                    load.cooldown = Math.max(0.0F, load.cooldown - 1.0F);

                    continue;
                }

                // 発射中は下限なしで減らす。1tickあたりの発数が整数でない発射速度でも、平均するとファイル
                // の値になるように。
                load.cooldown -= 1.0F;
                boolean single = false;

                while (load.cooldown <= 0.0F && load.ammo > 0) {
                    this.fireRound(slot, place, load.weapon, weapon, laid ? laidAim : null);
                    load.ammo--;
                    load.cooldown += weapon.firing().ticksPerRound();
                    this.dirty = true;

                    if (laid) {
                        crewFired = load.weapon;
                    } else {
                        fired = true;
                    }

                    if (weapon.type().isSingleShot()) {
                        // 1押し1発、1搭載位置から。全位置を同時に放てば1押しで機体が空になり、半分は
                        // 最初の1発が既に仕留めた目標に浪費される。
                        single = true;

                        break;
                    }
                }

                if (load.ammo <= 0) {
                    load.cooldown = 0.0F;
                    expend(mount, load);
                }

                if (single) {
                    break pylons;
                }
            }

            if (crewed) {
                mount.held = pressed;
            }

            // 砲座の発砲音はパイロットの選択とは別に鳴らす。同じ tick に別々の砲が撃っているのが普通の
            // 状態であり、片方の音でもう片方を代表させることはできない。
            if (crewFired != null) {
                this.playFireSound(Definitions.weapon(crewFired), crewFired);
            }
        }

        if (fired) {
            this.playFireSound(selectedWeapon, this.selected);
        }

        if (this.isParked()) {
            this.rearm();
        }
    }

    /**
     * 照準線が働くべきシーカー。使える物が無ければ null。
     *
     * <p>レーザー誘導兵装もシーカーを持つが、こちらは要らない。あれが向かうのは照準ポッドが当てている光点
     * で、パイロットがカメラを振ってキーを押して置いた物だ（{@link com.ashvehicles.entity.AircraftEntity#designate}
     * 参照）。照準線の先でロックできる物を探す計器とは別物である。動かせば、パイロットが橋を相手にしている
     * 最中に機体へ枠を描くことになる。
     */
    @Nullable
    private static WeaponDefinition.Guidance seekerOf(@Nullable WeaponDefinition weapon) {
        if (weapon == null) {
            return null;
        }

        WeaponDefinition.Guidance guidance = weapon.guidance().orElse(null);

        return guidance == null || guidance.seeker().laid()
                ? null
                : guidance;
    }

    /**
     * 1発がレールを離れる時に追う相手。特に狙わず撃つ場合は null。
     *
     * <p>計器は2つあり、どちらが答えるかは兵装が決める。レーザー誘導兵装は照準ポッドが保持している物を取り、
     * それ以外はシーカーが捉えている物を、しかもシーカーが実際にロックを固めた後にだけ取る。
     *
     * <p>どちらにせよ発射の瞬間に確定し、以後は変わらない。ミサイルは渡された物を持っていき、追い続けるのは
     * ミサイル自身の問題。爆弾が離れた後に指示を解除しても、爆弾は離れた時のマークを追い続ける——寛大な仕様
     * であり、代案は「誰かが目を離したせいで途中で馬鹿になる爆弾」。
     */
    @Nullable
    private Entity releaseTarget(WeaponDefinition weapon) {
        if (!weapon.isGuided()) {
            return null;
        }

        if (weapon.guidance().get().seeker().laid()) {
            return this.aircraft.getDesignated();
        }

        return this.lock.isLocked() ? this.lock.target() : null;
    }

    /**
     * 兵装1つから1発を送り出す。ロケットポッドのように一度に複数を放つ兵装なら一斉射分まとめて。
     */
    private void fireRound(int slot, int place, ResourceLocation weaponId, WeaponDefinition weapon,
            @Nullable Vec3 laid) {
        if (!(this.aircraft.level() instanceof ServerLevel level)) {
            return;
        }

        Vec3 muzzle = this.aircraft.toWorld(this.placeOf(slot, place), 1.0F);
        // 砲座に据えられた砲は自分の向きへ、それ以外は機首方向へ。散布の2軸も同じ線を基準に取るので、
        // 真横を向いた砲の散布は真横を向いた円錐になる。
        Vec3 nose = laid != null ? laid : this.aircraft.getNoseVector();
        Vec3 up = laid != null ? sideways(laid, this.aircraft.getLiftVector()) : this.aircraft.getLiftVector();
        Vec3 right = laid != null
                ? laid.cross(up).normalize()
                : Attitude.right(this.aircraft.getAttitude());
        LivingEntity pilot = this.aircraft.getControllingPassenger();

        // 盲撃ちせず拒否する。以下は全部が機首ベクトルの上に組まれており、長さゼロの機首ベクトルは、ここ
        // で唯一「大声ではなく静かに」壊れる入力だ。Vec3 の normalize は1万分の1より短い物に対して例外では
        // なく ZERO を返すので、姿勢が壊れていても何も上がらない——ただ全弾が速度ゼロで出て機体から零れ落ち、
        // 兵装のせいにされる。代わりに撃つべき妥当な方向も無いので、何も撃たず、壊れていた値を名指しする。
        if (nose.lengthSqr() < 1.0E-6) {
            AshVehicles.LOGGER.warn("{} not fired: {} has no attitude to fire along (nose={}, attitude={})",
                    weaponId, this.aircraft.getType(), nose, this.aircraft.getAttitude());

            return;
        }

        // ミサイルはレールを離れた時点でシーカーが持っていた物を取り、以後は何も受け取らない。追い続ける
        // のはミサイル自身の問題だ。だからロックがある時は素の機首方向へは出ない——レーダー指示のロックは
        // 今や弾の狭いヘッドではなくレーダー自身の走査範囲で保持される（TargetLock#bestCandidate 参照）
        // ので、機首方向にしか撃たないレールは、広角のロックに対して数十度の差を track_angle が諦める前に
        // 詰めろと要求することになる。代わりに目標へケージングする。実物のレールが、放つ前に弾を指定目標へ
        // ケージングするのと同じ。
        Entity locked = this.releaseTarget(weapon);
        Vec3 caged = cagedAim(locked, muzzle, nose);
        RandomSource random = this.aircraft.getRandom();

        // 機首を軸にした円錐。直交2方向のガウス分布で、ファイルの半頂角が「誰も届かない縁」ではなく
        // 「発射分の大半が収まる範囲」になるよう倍率を取る。一斉射はさらに円錐を開き、それがロケットの
        // 一斉射を1つの穴ではなく面にする。
        double scatter = Math.tan(Math.toRadians(weapon.firing().spread())) * 0.5;
        double spread = Math.tan(Math.toRadians(weapon.firing().salvoSpread())) * 0.5;

        for (int i = 0; i < Math.max(1, weapon.firing().salvo()); i++) {
            Vec3 direction = caged
                    .add(right.scale(random.nextGaussian() * (scatter + spread)))
                    .add(up.scale(random.nextGaussian() * (scatter + spread)))
                    .normalize();

            // 爆弾は撃つのではなく放す。持っていくのは機体の速度だけで、自前の数値はラックから下向きに
            // 押し出される強さ。それが、今離れたばかりの機体腹をこすらずに済ませている。
            //
            // レール上の物は話が別だ。レール発射に機体の速度を丸ごと足すことが、弾を機首からずれた角度で
            // 飛ばしていた原因だった。機体は決して向いている方向へ進んでいない——翼は揚力を作るために気流
            // に対する角度を必要とする——ので機体速度には機首を横切る成分があり、それを足すと全弾が迎え角の
            // 分だけ軸から曲がる。旋回中はさらに悪く、弾の初速が遅いほど悪い。実際のレールがやっているのは
            // 「離れるまで弾を自分に保持する」ことで、持ち出されるのはレール方向に既にあった速度だけ。これで
            // 弾は機首の指す方向へ行く。照準が示す通りの場所へ。
            Vec3 carried = direction.scale(Math.max(0.0, this.aircraft.getVelocity().dot(direction)));
            Vec3 velocity = weapon.isDropped()
                    ? this.aircraft.getVelocity().add(up.scale(-weapon.projectile().speed()))
                    : direction.scale(weapon.projectile().speed()).add(carried);

            // 一時的。速度ゼロで出る発射の調査用。解決したら削除すること。
            if (weapon.type() != WeaponDefinition.Type.GUN) {
                AshVehicles.LOGGER.info(
                        "[launch] {} dropped={} attitude={} nose={} up={} aircraftV={} |aircraftV|={} "
                                + "dir={} speed={} carried={} v={} |v|={}",
                        weaponId, weapon.isDropped(), this.aircraft.getAttitude(), nose, up,
                        this.aircraft.getVelocity(), this.aircraft.getVelocity().length(),
                        direction, weapon.projectile().speed(), carried, velocity, velocity.length());
            }

            VehicleProjectile shot = weapon.type() == WeaponDefinition.Type.GUN
                    ? new BulletEntity(ModEntities.BULLET.get(), level)
                    : new RocketEntity(ModEntities.ROCKET.get(), level);

            shot.setup(weaponId, this.aircraft, pilot);
            shot.setPos(muzzle);
            // setDeltaMovement ではなく launch。速度がクライアントへ届く必要があり、通常それを運ぶ
            // パケットには速すぎるから。VehicleProjectile 参照。
            shot.launch(velocity);

            if (shot instanceof RocketEntity rocket && locked != null) {
                rocket.setTarget(locked);
            }

            level.addFreshEntity(shot);
        }
    }

    /**
     * ケージングされた弾が出ていく方向。ロックがあればその相手へ、無ければ機首方向——無誘導の兵装すべてと、
     * 何も保持せず撃った誘導兵装がそれ。方向を作れない唯一の場合（目標がパイロン上にちょうど重なっている）
     * でも機首方向に戻す。
     */
    /** 砲腔線に直交する「上」。散布を撒く2軸のうちの1本で、もう1本はこれと砲腔線の外積になる。 */
    private static Vec3 sideways(Vec3 bore, Vec3 fallback) {
        Vec3 side = bore.cross(new Vec3(0.0, 1.0, 0.0));

        return side.lengthSqr() < 1.0E-6 ? fallback : side.cross(bore).normalize();
    }

    private static Vec3 cagedAim(@Nullable Entity locked, Vec3 muzzle, Vec3 nose) {
        if (locked == null) {
            return nose;
        }

        Vec3 toTarget = locked.position().add(0.0, locked.getBbHeight() * 0.5, 0.0).subtract(muzzle);

        return toTarget.lengthSqr() > 1.0E-6 ? toTarget.normalize() : nose;
    }

    /**
     * 発砲中は何発出ていても1tickに1回。兵装ファイルが指定するイベント、無ければ兵装名から作った物。
     * どちらも持たないクライアントは {@link com.ashvehicles.client.sound.WeaponSounds} の既定へ落ちる。
     */
    private void playFireSound(WeaponDefinition weapon, ResourceLocation weaponId) {
        ResourceLocation event = weapon.sound().fire()
                .orElseGet(() -> weaponId.withPath(SOUND_PREFIX + weaponId.getPath()));

        // 音量スロットには音量ではなく到達距離を入れて送る。そのスロットだけが「誰にこの音を知らせるか」
        // を決めており、兵装本来の音量で送れば答えは「32ブロック以内の全員」——空戦では誰もいない。届いた
        // 場所で実際どう聞こえるべきかはクライアントが決める。自分がどれだけ離れているかを知っているのは
        // そちらだけだから。WeaponSounds 参照。
        this.aircraft.level().playSound(null, this.aircraft.getX(), this.aircraft.getY(), this.aircraft.getZ(),
                SoundEvent.createVariableRangeEvent(event), SoundSource.NEUTRAL,
                weapon.sound().packetVolume(), weapon.sound().pitch());
    }

    /**
     * ステーションへ何かを付ける／外す音。機体の周りに立っている者に聞こえる。
     *
     * <p>兵装関連の他の音と違い通常の方法で鳴らす。これだけが「機体が地上にあり、誰かがその横にいる」状況
     * で起きることだからだ。16ブロック程度の到達距離がちょうどよく、それより遠い者が地上作業を聞く筋合いは
     * 無い。
     *
     * @param hanging 付けるなら true、外すなら false
     */
    private void playLoadSound(boolean hanging) {
        this.aircraft.level().playSound(null, this.aircraft.getX(), this.aircraft.getY(), this.aircraft.getZ(),
                SoundEvent.createVariableRangeEvent(LOAD_SOUND), SoundSource.NEUTRAL,
                LOAD_VOLUME, hanging ? LOAD_PITCH : UNLOAD_PITCH);
    }

    /** 接地・エンジン停止・滑走していない状態。地上要員が近づいて安全な状態。 */
    private boolean isParked() {
        return this.aircraft.onGround() && this.aircraft.getThrottle() <= 0.0F
                && this.aircraft.getVelocity().lengthSqr() < 1.0E-4;
    }

    /**
     * 撃ち尽くした兵装をラック上の位置から外す。
     *
     * <p>ミサイルや爆弾は、そこに吊られている物<em>そのもの</em>だ。放てば位置は空になり、それでも兵装名を
     * 保持し続ける搭載枠は「装填済みを自称する空のレール」になる。描画上はずっと空として描かれてきた
     * （{@code AircraftRenderer} 参照）ので、これはその残りを事実にする処理。位置が空き、その兵装はパイロ
     * ットが切り替える一覧から落ち、地上要員は次を吊る場所を得る。ラック自体は残る。ボルト留めであり、外す
     * のはレンチの仕事。
     *
     * <p>ポッドと内蔵砲は、どれだけ空になってもその場に残る。ガンポッドはラックに留められた容器で、砲は
     * 機体構造の一部。どちらも撃った物と一緒に去りはしないし、fixed ステーションにはそもそも外れる先の
     * ラックが無い。
     */
    private static void expend(Mount mount, Load load) {
        if (load.isEmpty() || !mount.hasRack() || !Definitions.weapon(load.weapon).leavesRail()) {
            return;
        }

        load.set(null, 0);
    }

    /**
     * 駐機中の機体で働く地上要員。既に付いているラックへ兵装を吊り、既に吊ってある物へ弾を戻す。すべて弾庫
     * から。
     *
     * <p><b>弾庫から、それ以外からは決して。</b> 以前は機体が10秒静止するたびに満載を空中から生み出して
     * おり、それでは翼下の全部が無料になりパイロンは形式になる。今や機体が撃てるのは誰かが積んだ分だけ
     * （{@link VehicleHold} 参照）で、空で出撃した機体は空で帰る。
     *
     * <p><b>ラックとポッドはプレイヤーだけの物。</b> 要員は兵装を吊り、弾を詰めるが、ラックをボルト留めは
     * しないしポッドも付けない。弾庫に何個転がっていようと。どの装備を積むかは「どんな出撃をするか」の判断
     * であり、出撃の合間に勝手に決められては選ぶ意味が消える。
     *
     * <p>速度は従来通り、大きさに関わらず {@link #REARM_TICKS} で満載1回分。これは両方向から言う必要が
     * ある。機関砲は数百発持つので毎tick数発乗るが、レールはミサイル1発なので tick 側が待つ。
     * {@code capacity / REARM_TICKS} を足すだけでは前者は正しく後者は0に切り捨てられ、「毎tick最低1発」を
     * 下限にすればミサイルが0.05秒で装填される。それは誰の作業速度よりずっと速い。
     */
    private void rearm() {
        boolean hung = false;

        for (int slot = 0; slot < this.mounts.length; slot++) {
            Mount mount = this.mounts[slot];

            for (int place = 0; place < mount.loads.length; place++) {
                Load load = mount.loads[place];

                if (load.isEmpty()) {
                    // 付いているラックの空き位置。発射した兵装がレールから外れるようになった今、出撃末期
                    // にはその大半がこれになる。1発ずつ処理するので、4つの空き位置は同時にではなく書かれた
                    // 順に埋まる——そして4回分の装填音が同じ tick に重ならない。
                    if (!hung && this.aircraft.tickCount % REARM_TICKS == 0) {
                        hung = this.hangFromHold(slot);
                    }

                    continue;
                }

                WeaponDefinition carried = Definitions.weapon(load.weapon);

                // 増槽はここでは補給しない。弾庫から引けるのは増槽そのもの1本であって燃料ではないので、
                // 素直に通せば「1本の増槽を溶かして数単位の燃料にする」処理になる。増槽を満たすのは燃料缶
                // で、空にした物を投棄して新しい1本を吊るのも変わらず正しい。
                if (carried.type() == WeaponDefinition.Type.TANK) {
                    continue;
                }

                int capacity = carried.ammo();

                if (load.ammo >= capacity) {
                    continue;
                }

                int perTick = capacity / REARM_TICKS;
                int wanted;

                if (perTick > 0) {
                    wanted = Math.min(perTick, capacity - load.ammo);
                } else if (this.aircraft.tickCount % Math.max(1, REARM_TICKS / capacity) == 0) {
                    wanted = 1;
                } else {
                    continue;
                }

                int loaded = this.draw(load.weapon, wanted);

                if (loaded > 0) {
                    load.ammo += loaded;
                    this.dirty = true;
                }
            }
        }
    }

    /**
     * 空きのあるステーションへ、弾庫から兵装を1つ吊る。選ぶのは弾庫の並び順で最初に見つかる、そのラックが
     * 積める物。だからどれがどこへ載るかは積んだ者が決めることになる。
     *
     * @return 何か吊れたか
     */
    private boolean hangFromHold(int slot) {
        VehicleHold hold = this.aircraft.getHold();

        for (int at = 0; at < hold.getContainerSize(); at++) {
            ItemStack stack = hold.getItem(at);

            // 空のポッドは持ち帰る価値はあるが、吊り直す価値は無い。
            if (!(stack.getItem() instanceof WeaponItem store) || WeaponItem.ammoOf(stack) == 0) {
                continue;
            }

            if (this.mountAt(slot, store.getWeaponId(), WeaponItem.ammoOf(stack))) {
                hold.removeItem(at, 1);

                return true;
            }
        }

        return false;
    }

    /**
     * 指定兵装の弾を弾庫から取り、取れた発数を返す。
     *
     * <p>通貨は「発」で、アイテムは財布。60発残ったガンポッドが弾庫にあれば、それは60発分の補給になる。
     * 要員が使った後の残りは、中身の残ったポッドとして元のスロットへ戻り、最後の1発を取られた兵装は消える。
     *
     * <p>自前のアイテムを持たない兵装だけは、これで課金できない。機体に内蔵された機関砲は誰も持ち運べず
     * 弾庫に入れられず尽きもしないので、従来通りの補給を受ける——帳簿外の、地上要員の手持ちから。弾庫に
     * <em>入れられる</em>物はすべて弾庫から出る。
     */
    private int draw(ResourceLocation weapon, int rounds) {
        if (!ModItems.weapons().containsKey(weapon)) {
            return rounds;
        }

        int capacity = Definitions.weapon(weapon).ammo();
        VehicleHold hold = this.aircraft.getHold();
        int taken = 0;

        for (int at = 0; at < hold.getContainerSize() && taken < rounds; at++) {
            ItemStack stack = hold.getItem(at);

            if (!(stack.getItem() instanceof WeaponItem store) || !store.getWeaponId().equals(weapon)) {
                continue;
            }

            // 残弾の記載が無ければ満載。そこから1発も撃たれていないということ。
            int held = WeaponItem.ammoOf(stack);
            int available = held < 0 ? capacity : held;
            int want = Math.min(rounds - taken, available);

            if (want <= 0) {
                continue;
            }

            taken += want;

            if (want >= available) {
                hold.removeItem(at, 1);
            } else {
                hold.setItem(at, WeaponItem.stackOf(weapon, available - want));
            }
        }

        return taken;
    }

    // ------------------------------------------------------------------
    // 保存と同期
    // ------------------------------------------------------------------

    /** 前回の呼び出し以降に変化があれば1度だけ true。クライアントへ写しを送る合図。 */
    public boolean consumeDirty() {
        boolean was = this.dirty;
        this.dirty = false;

        return was;
    }

    /** クライアントとセーブが必要とする物。各ハードポイントの中身と、選択中の兵装。 */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();

        for (Mount mount : this.mounts) {
            CompoundTag entry = new CompoundTag();

            if (mount.rack != null) {
                entry.putString("Rack", mount.rack.toString());
            }

            if (mount.equipment != null) {
                entry.putString("Equipment", mount.equipment.toString());
            }

            ListTag loads = new ListTag();

            for (Load load : mount.loads) {
                CompoundTag place = new CompoundTag();

                if (load.weapon != null) {
                    place.putString("Weapon", load.weapon.toString());
                    place.putInt("Ammo", load.ammo);
                }

                loads.add(place);
            }

            entry.put("Loads", loads);
            list.add(entry);
        }

        tag.put("Mounts", list);

        if (this.selected != null) {
            tag.putString("Selected", this.selected.toString());
        }

        return tag;
    }

    /**
     * 同じ内容に、シーカーが捉えている相手を足した物。計器が描けるようクライアントへ送る内容。
     *
     * <p>{@link #save()} と分けてあるのは、ロックが保存に値しないから。ロックはこのセッションでの ID で
     * エンティティを指しており、次のセッションでは無意味か、まったく別の物を指す。どのみちシーカーは読み込み
     * 後の最初の tick で自分の目標を見つけ直す。
     */
    public CompoundTag syncTag() {
        CompoundTag tag = this.save();
        this.lock.save(tag);

        return tag;
    }

    public void load(CompoundTag tag) {
        ListTag list = tag.getList("Mounts", Tag.TAG_COMPOUND);
        this.mounts = new Mount[list.size()];
        this.mountsView = null;

        for (int slot = 0; slot < list.size(); slot++) {
            CompoundTag entry = list.getCompound(slot);
            Mount mount = new Mount();
            this.mounts[slot] = mount;

            if (entry.contains("Rack")) {
                mount.rack = ResourceLocation.tryParse(entry.getString("Rack"));
            }

            if (entry.contains("Equipment")) {
                mount.equipment = ResourceLocation.tryParse(entry.getString("Equipment"));
            }

            ListTag loads = entry.getList("Loads", Tag.TAG_COMPOUND);
            mount.resize(loads.size());

            for (int place = 0; place < loads.size(); place++) {
                CompoundTag load = loads.getCompound(place);

                if (load.contains("Weapon")) {
                    mount.loads[place].set(ResourceLocation.tryParse(load.getString("Weapon")),
                            load.getInt("Ammo"));
                }
            }
        }

        this.selected = tag.contains("Selected") ? ResourceLocation.tryParse(tag.getString("Selected")) : null;
        // サーバーが送る内容には入っており、ディスクから読む内容には無い。無い場合、シーカーは何も持たず
        // に始まり、次の tick で自分の目標を見つける。
        this.lock.load(tag);
        this.dirty = true;
    }

    /** そのエンティティがこの機体自身かその一部か。自分の弾がすり抜けるべき相手。 */
    public static boolean isPartOf(Entity vehicle, Entity entity) {
        return entity == vehicle || entity.getRootVehicle() == vehicle
                || (entity instanceof com.ashvehicles.entity.VehiclePart part && part.getParent() == vehicle);
    }
}
