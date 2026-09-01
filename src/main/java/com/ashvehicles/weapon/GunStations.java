package com.ashvehicles.weapon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.vehicle.Attitude;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * 振れる砲座。パイロンの上の砲を、そのパイロンを持つ乗員の視線で照準する。
 *
 * <p><b>このクラスは弾を持たない。</b>撃つ物・残弾・装填・投棄・抗力はすべて {@link WeaponMounts} の
 * ままだ——砲座が名指しするのはハードポイントであって兵装ではないので、機体に組み込まれた機関砲も、
 * プレイヤーが吊ったガンポッドも同じ砲座に載る。ここが答えるのは3つの問いだけ。<b>誰が撃つのか、どこを
 * 向いているのか、そして今その引き金は引かれているのか。</b>発砲そのものは弾を持っている側——
 * {@link WeaponMounts#tick} ——がこの3つを訊きに来て行う。
 *
 * <p><b>砲を持つのは席であって人ではない。</b>各砲座は機体ファイルで席番号を指しており、その席に座って
 * いる者がその砲を撃つ。空席ならパイロットへ戻る。1人で飛べば全部の砲が1人の物になり、砲手が座れば座った
 * 砲から順に手が離れていく。乗り降りに伴う特別な処理は無い——毎tick誰がどこにいるかを見るだけであり、席を
 * 移るのも降りるのも撃たれて消えるのも、同じ1つの問いの答えが変わっただけだ。
 *
 * <p><b>照準は機体座標系で行う。</b>射手の視線をワールドから機体の軸へ引き戻し、そこで砲の可動範囲へ収め、
 * 撃つ瞬間にまたワールドへ戻す。だから範囲は機体に対する範囲であり続ける。ヘリコプターが機首を目標へ向け
 * ずに撃てるのはこれであり、左舷へ向いた砲を持つ機体が目標の周りを傾いたまま回り続けるのも同じ理由だ。
 *
 * <p><b>引き金は1つ、選択も1つ。</b>パイロットは兵装切り替えキーで、パイロンの兵装と自分が持っている砲座
 * を一続きに巡る（{@link #cycle}）。だからミサイルを選んでいる間は砲が黙り、砲を選んでいる間はミサイルが
 * 出ない。砲手席の乗員は自分の砲座しか持たないので選ぶ物が無く、引けば自分の砲が撃つ。
 */
public final class GunStations {
    /** 引き金を引いた砲手の1押しが、次のパケットが来る前に消えないための猶予（tick）。 */
    private static final int TRIGGER_HOLD = 5;

    /** どの砲座も選択していない状態。パイロンの兵装が引き金に繋がっている。 */
    public static final int NONE = -1;

    private final AircraftEntity aircraft;

    /** 砲座ごとの向き。機体ファイルの砲座リストと同じ順・同じ長さ。 */
    private float[] yaw = new float[0];
    private float[] pitch = new float[0];

    /**
     * 引き金を引いている乗員それぞれと、その報告が最後に届いた tick。パイロット以外はここからしか分から
     * ない——操縦入力のパケットは操縦している者しか送らないので、砲手の引き金は自前の小さなパケットで届く。
     *
     * <p>時刻を人ごとに持つのは、砲手が2人いる場合に1つでは足りないからだ。1つしか持たなければ、撃ち続けて
     * いる砲手の報告が、既に落ちた砲手の古い引き金をいつまでも生かしてしまう。
     */
    private final Map<UUID, Long> firing = new HashMap<>();

    /** パイロットが今どの砲座を選んでいるか。{@link #NONE} ならパイロンの兵装側。 */
    private int selected = NONE;

    private boolean dirty;

    public GunStations(AircraftEntity aircraft) {
        this.aircraft = aircraft;
    }

    private List<AircraftDefinition.Station> stations() {
        return this.aircraft.getStats().stations();
    }

    /** そもそも砲座を持つ機体か。持たない機体ではこのクラス全体が毎tick何もしない。 */
    public boolean exists() {
        return !this.stations().isEmpty();
    }

    public int count() {
        return this.stations().size();
    }

    public AircraftDefinition.Station station(int index) {
        return this.stations().get(index);
    }

    // ------------------------------------------------------------------
    // 砲座とハードポイントの対応
    // ------------------------------------------------------------------

    /** その砲座が振るハードポイントの番号。機体ファイルが存在しない名前を書いていれば空。 */
    public List<Integer> slotsOf(int index) {
        List<AircraftDefinition.Hardpoint> hardpoints = this.aircraft.getStats().hardpoints();
        List<Integer> slots = new ArrayList<>();

        for (String name : this.station(index).pylons()) {
            for (int slot = 0; slot < hardpoints.size(); slot++) {
                if (hardpoints.get(slot).name().equals(name)) {
                    slots.add(slot);
                }
            }
        }

        return slots;
    }

    /**
     * その席が受け持つ砲座。無ければ {@link #NONE}。
     *
     * <p>{@link #stationsOf} とは別物で、あちらは「今この人が撃つ砲」——空席の砲がパイロットへ回った分を
     * 含む——を答える。こちらが答えるのは機体ファイルに書かれた対応そのもので、誰が乗っているかを見ない。
     * 席に据え付けられている物、たとえばその席の照準具や視点は、砲手が降りたからといって操縦席へ移っては
     * ならない。
     */
    public int stationForSeat(int seat) {
        for (int index = 0; index < this.count(); index++) {
            if (this.station(index).seat() == seat) {
                return index;
            }
        }

        return NONE;
    }

    /** そのハードポイントを振る砲座。無ければ {@link #NONE}。 */
    public int stationForSlot(int slot) {
        if (!this.exists()) {
            return NONE;
        }

        List<AircraftDefinition.Hardpoint> hardpoints = this.aircraft.getStats().hardpoints();

        if (slot < 0 || slot >= hardpoints.size()) {
            return NONE;
        }

        String name = hardpoints.get(slot).name();

        for (int index = 0; index < this.count(); index++) {
            if (this.station(index).pylons().contains(name)) {
                return index;
            }
        }

        return NONE;
    }

    /**
     * その砲座に今載っている砲。無ければ null——空のパイロンを振っている砲座であり、照準も残弾も出す物が
     * 無い。
     */
    @Nullable
    public ResourceLocation weaponOf(int index) {
        for (int slot : this.slotsOf(index)) {
            ResourceLocation weapon = this.aircraft.getWeapons().weaponAt(slot);

            if (weapon != null && Definitions.weapon(weapon).type() == WeaponDefinition.Type.GUN) {
                return weapon;
            }
        }

        return null;
    }

    /** その砲座が振る全パイロンの残弾合計。計器向け。 */
    public int rounds(int index) {
        int rounds = 0;

        for (int slot : this.slotsOf(index)) {
            rounds += this.aircraft.getWeapons().ammoAt(slot);
        }

        return rounds;
    }

    // ------------------------------------------------------------------
    // 誰がどの砲を持っているか
    // ------------------------------------------------------------------

    /**
     * その砲座を撃つ者。指定された席の乗員、空席ならパイロット。誰も乗っていなければ null。
     *
     * <p>席が範囲外を指す機体ファイルでもパイロットへ落ちるだけで、砲が沈黙したりはしない。
     */
    @Nullable
    public LivingEntity operatorOf(int index) {
        AircraftDefinition.Station station = this.station(index);

        for (Entity rider : this.aircraft.getPassengers()) {
            if (rider instanceof LivingEntity crew && this.aircraft.getSeatIndex(rider) == station.seat()) {
                return crew;
            }
        }

        return this.aircraft.getAviator();
    }

    /** その乗員が持っている砲座。持っていなければ空。 */
    public List<Integer> stationsOf(@Nullable Entity crew) {
        List<Integer> mine = new ArrayList<>();

        if (crew == null) {
            return mine;
        }

        for (int index = 0; index < this.count(); index++) {
            if (this.operatorOf(index) == crew) {
                mine.add(index);
            }
        }

        return mine;
    }

    /**
     * その乗員が今引き金で撃つ砲座。持っていなければ、あるいはパイロットがパイロン側を選んでいれば
     * {@link #NONE}。
     *
     * <p>砲手席の乗員には選択が無い。自分の席が持つ砲座がそのまま答えであり、パイロットが何を選んでいようと
     * 関係が無い——2人が同じtickに別々の物を撃つのが普通の状態だからだ。選択が要るのはパイロットだけで、
     * 引き金1つでパイロンの兵装と砲座の両方を持っているのはその1人しかいない。
     */
    public int liveStationOf(@Nullable Entity crew) {
        List<Integer> mine = this.stationsOf(crew);

        if (mine.isEmpty()) {
            return NONE;
        }

        if (crew != this.aircraft.getAviator()) {
            return mine.contains(this.selected) ? this.selected : mine.get(0);
        }

        if (mine.contains(this.selected)) {
            return this.selected;
        }

        // パイロン側に撃てる物が1つも無いなら、選ぶまでもなく引き金は砲座の物だ。翼の下に何も吊っていない
        // 機体——組み込みの砲しか持たない機体はいつもそれだ——で、飛ぶ前に切り替えキーを1回押させないため。
        return this.aircraft.getWeapons().selected() == null ? mine.get(0) : NONE;
    }

    /** パイロットの引き金が今、パイロンの兵装ではなく砲座に繋がっているか。 */
    public boolean pilotHoldsStation() {
        return this.liveStationOf(this.aircraft.getAviator()) != NONE;
    }

    /**
     * 引き金1つが選ぶ物を1つ進める。パイロンの兵装と、パイロットが持っている砲座を一続きに巡る。
     *
     * @return ここで処理したか。砲座を1つも持たないパイロットでは false を返し、キーは従来通りパイロンの
     *         兵装選択だけを進める
     */
    public boolean cycle() {
        List<Integer> mine = this.stationsOf(this.aircraft.getAviator());

        if (mine.isEmpty()) {
            return false;
        }

        WeaponMounts weapons = this.aircraft.getWeapons();
        List<ResourceLocation> carried = weapons.carried();
        int at = this.selected != NONE && mine.contains(this.selected)
                ? carried.size() + mine.indexOf(this.selected)
                : Math.max(0, carried.indexOf(weapons.selected()));
        int next = (at + 1) % (carried.size() + mine.size());

        if (next < carried.size()) {
            this.selected = NONE;
            weapons.select(carried.get(next));
        } else {
            this.selected = mine.get(next - carried.size());
        }

        this.dirty = true;

        return true;
    }

    /**
     * 砲手が引き金を引いている、あるいは離したという報告。パイロット以外の乗員はこれでしか撃てない。
     *
     * <p>押している間の状態であって1回の発砲ではないので、引いている間は毎tick届く。報告が途切れれば数tick
     * で消える——切断した砲手の砲が撃ち続けないためだ。{@link #TRIGGER_HOLD} 参照。
     */
    public void setTrigger(Player crew, boolean pressed) {
        if (pressed) {
            this.firing.put(crew.getUUID(), this.aircraft.level().getGameTime());
        } else {
            this.firing.remove(crew.getUUID());
        }
    }

    /**
     * その砲座の引き金が今引かれているか。弾を持っている側が発砲の直前に訊きに来る。
     *
     * <p>撃つのは、その砲座を持っている者が、それを自分の生きた選択として引いている場合だけ。墜落中の機体
     * では誰の引き金も繋がらない。
     */
    public boolean pulled(int index) {
        if (this.aircraft.isCrashing()) {
            return false;
        }

        LivingEntity crew = this.operatorOf(index);

        return crew != null && this.liveStationOf(crew) == index && this.isFiring(crew);
    }

    /** その乗員が今撃っているか。パイロットは操縦入力から、それ以外は自前の報告から。 */
    private boolean isFiring(LivingEntity crew) {
        if (crew == this.aircraft.getAviator()) {
            return this.aircraft.getInput().fire();
        }

        Long since = this.firing.get(crew.getUUID());

        return since != null && this.aircraft.level().getGameTime() - since <= TRIGGER_HOLD;
    }

    // ------------------------------------------------------------------
    // 照準（サーバー側）
    // ------------------------------------------------------------------

    /** サーバー側の1tick分。各砲座を射手の視線へ向ける。発砲は弾を持っている側の仕事だ。 */
    public void tick() {
        if (!this.exists()) {
            return;
        }

        this.ensureLayout();

        for (int index = 0; index < this.count(); index++) {
            this.aim(index, this.station(index), this.operatorOf(index));
        }
    }

    /**
     * 砲を射手が見ている方向へ、自分の旋回速度で向ける。
     *
     * <p>視線はワールド座標、砲の可動範囲は機体座標なので、まず視線を機体の軸へ引き戻す。射手がいない砲座は
     * 今の向きのまま留まる——手を離した砲が正面へ戻る理由は無い。
     */
    private void aim(int index, AircraftDefinition.Station station, @Nullable LivingEntity crew) {
        if (crew == null) {
            return;
        }

        Vec3 look = Attitude.toBody(this.aircraft.getAttitude(), crew.getLookAngle());

        if (look.lengthSqr() < 1.0E-6) {
            return;
        }

        // 機体座標系は x が右・z が機首方向なので、方位は x と z の間の角、仰角は上向き成分そのもの。
        float wantYaw = station.clampYaw((float) Math.toDegrees(Math.atan2(look.x, look.z)));
        float wantPitch = station.clampPitch(
                (float) Math.toDegrees(Math.asin(Mth.clamp(look.normalize().y, -1.0, 1.0))));

        float turned = approachAngle(this.yaw[index], wantYaw, station.traverseRate());
        float raised = approach(this.pitch[index], wantPitch, station.elevationRate());

        // 描く物がある側にとっては向きの変化そのものが知らせなので、目に見える分だけ動いたら送る。1tickに
        // 0.1度の追従で毎tick同期タグを組み直すのは、その1機だけのために払う値段ではない。
        if (Math.abs(Mth.degreesDifference(turned, this.yaw[index])) > 0.25F
                || Math.abs(raised - this.pitch[index]) > 0.25F) {
            this.dirty = true;
        }

        this.yaw[index] = turned;
        this.pitch[index] = raised;
    }

    /**
     * 砲が今向いている方向（ワールド座標の単位ベクトル）。射手の画面が弾道を組むのにも使うので、tick間の
     * 任意の瞬間で答える——機体の姿勢は補間されるが、砲の向き自体は1tickに1つの値だ。
     */
    public Vec3 direction(int index, float partialTick) {
        if (index < 0 || index >= this.yaw.length) {
            return this.aircraft.getNoseVector();
        }

        double yawRad = Math.toRadians(this.yaw[index]);
        double pitchRad = Math.toRadians(this.pitch[index]);
        double flat = Math.cos(pitchRad);

        // 機体の軸で組んでからワールドへ。x が右、y が上、z が機首方向。
        Vec3 body = new Vec3(Math.sin(yawRad) * flat, Math.sin(pitchRad), Math.cos(yawRad) * flat);

        return Attitude.toWorld(this.aircraft.getAttitude(partialTick), body);
    }

    /**
     * その砲座の向きが分かっているか。
     *
     * <p>クライアントは配列を同期タグから受け取るので、機体が見えてからそれが届くまでの数フレームは、どの
     * 砲座も「まだ何も向いていない」。{@link #direction} はそこで機首方向を返す——描く物にとっては無害な
     * 当て推量だが、その1回きりの値を元に何かを据え付ける側にとっては違う。訊けるようにしてある。
     */
    public boolean isLaid(int index) {
        return index >= 0 && index < this.yaw.length;
    }

    /** 砲座の方位（度）。機体に対する角で、正が右。吊っている物をその向きへ描くために描画側が読む。 */
    public float yawOf(int index) {
        return index >= 0 && index < this.yaw.length ? this.yaw[index] : 0.0F;
    }

    /** 同じく仰角（度）。正が上。 */
    public float pitchOf(int index) {
        return index >= 0 && index < this.pitch.length ? this.pitch[index] : 0.0F;
    }

    /** その砲座の砲口（ワールド座標）。振っているパイロンのうち最初の1つの位置。 */
    public Vec3 muzzle(int index, float partialTick) {
        return this.aircraft.toWorld(this.trunnion(index), partialTick);
    }

    /**
     * 砲座が振れる中心。機体座標系で、振っているパイロンのうち最初の1つの位置。
     *
     * <p>砲を描く側が振る中心もここだ（{@code AircraftRenderer.draw} は取り付け点で回す）ので、砲と一緒に
     * 動く物はここを支点に置けば砲身と離れない。
     */
    public Vec3 trunnion(int index) {
        List<Integer> slots = this.slotsOf(index);

        return slots.isEmpty() ? Vec3.ZERO : this.aircraft.getWeapons().placeOf(slots.get(0), 0);
    }

    /**
     * 機体座標系の一点を、砲が運んだ先へ。砲身に付いている物——ガンカメラ——のためのもの。
     *
     * <p>点は砲が正面を向いている（方位も仰角も0の）状態で書く。戦車の砲塔上の点とまったく同じ約束で、
     * 砲座の {@code bearing} を書き手が織り込む必要は無い。順序も描画側と揃える：先に仰角、次に方位。
     * 逆にすると、横を向いた砲が上を向いた時に点が砲身から外れる。
     */
    public Vec3 carry(int index, Vec3 point) {
        return this.carry(index, point, this.yawOf(index), this.pitchOf(index));
    }

    /**
     * 同じ物を、砲座が今持っている角ではなく渡された角で。
     *
     * <p>砲の向きは1tickに1つの値なので、それを直に使う物は毎秒20回だけ動く。描く物にはそれで足りるが、
     * 視界そのものを預けている物——{@code GunCamera}——には足りない。あちらが2tickの間を補間した角を持って
     * いるので、その角で同じ計算をする口を開けてある。
     */
    public Vec3 carry(int index, Vec3 point, float yawDegrees, float pitchDegrees) {
        Vec3 trunnion = this.trunnion(index);
        Vec3 arm = point.subtract(trunnion);
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);

        // 機体座標系は x が右・y が上・z が機首方向。仰角は x 軸回り（上げが正）、方位は y 軸回り（右が正）。
        double y = arm.y * Math.cos(pitch) + arm.z * Math.sin(pitch);
        double z = arm.z * Math.cos(pitch) - arm.y * Math.sin(pitch);

        return trunnion.add(arm.x * Math.cos(yaw) + z * Math.sin(yaw), y,
                z * Math.cos(yaw) - arm.x * Math.sin(yaw));
    }

    // ------------------------------------------------------------------
    // 状態
    // ------------------------------------------------------------------

    /** 配列を機体ファイルの砲座数に合わせる。新しく現れた砲座は自分の正面を向いて始まる。 */
    private void ensureLayout() {
        int wanted = this.count();

        if (this.yaw.length == wanted) {
            return;
        }

        float[] yaw = new float[wanted];
        float[] pitch = new float[wanted];

        for (int index = 0; index < wanted; index++) {
            if (index < this.yaw.length) {
                yaw[index] = this.yaw[index];
                pitch[index] = this.pitch[index];
            } else {
                yaw[index] = this.station(index).bearing();
            }
        }

        this.yaw = yaw;
        this.pitch = pitch;
        this.dirty = true;
    }

    /** 前回の呼び出し以降に変化があれば1度だけ true。クライアントへ写しを送る合図。 */
    public boolean consumeDirty() {
        boolean was = this.dirty;
        this.dirty = false;

        return was;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();

        for (int index = 0; index < this.yaw.length; index++) {
            CompoundTag entry = new CompoundTag();
            entry.putFloat("Yaw", this.yaw[index]);
            entry.putFloat("Pitch", this.pitch[index]);
            list.add(entry);
        }

        tag.put("Stations", list);
        tag.putInt("Selected", this.selected);

        return tag;
    }

    public void load(CompoundTag tag) {
        ListTag list = tag.getList("Stations", Tag.TAG_COMPOUND);
        this.yaw = new float[list.size()];
        this.pitch = new float[list.size()];

        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompound(index);
            this.yaw[index] = entry.getFloat("Yaw");
            this.pitch[index] = entry.getFloat("Pitch");
        }

        this.selected = tag.contains("Selected") ? tag.getInt("Selected") : NONE;
    }

    private static float approach(float from, float to, float step) {
        float delta = to - from;

        return Math.abs(delta) <= step ? to : from + Math.signum(delta) * step;
    }

    private static float approachAngle(float from, float to, float step) {
        float delta = Mth.degreesDifference(from, to);

        return Math.abs(delta) <= step ? to : Mth.wrapDegrees(from + Math.signum(delta) * step);
    }
}
