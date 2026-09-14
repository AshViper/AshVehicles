package com.ashvehicles.weapon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.vehicle.GroundVehicleDefinition;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * 主砲塔以外の砲塔。1つ1つが自分の席・自分の砲・自分の旋回輪を持ち、その席に座っている者の視線で据わる。
 *
 * <p><b>砲を持つのは席であって人ではない。</b>各砲塔は車両ファイルで席番号を指しており、その席に座っている
 * 者がその砲塔を回して撃つ。空席なら運転手へ戻る。1人で走らせれば全砲塔が1人の物になり、砲手が乗れば乗った
 * 砲塔から順に手が離れていく。乗り降りに伴う特別な処理は無い——毎tick誰がどこに座っているかを見るだけで
 * あり、席を移るのも降りるのも撃たれて消えるのも、同じ1つの問いの答えが変わっただけだ。機体の砲座
 * （{@link GunStations}）と同じ取り決めで、あちらに倣って組んである。
 *
 * <p><b>主砲塔はここに来ない。</b>運転手が据える砲塔は今までどおり車両自身の {@code turret} /
 * {@code armament} で、{@code GroundVehicleEntity.tickTurret} が回し、{@link BuiltInGun.Fixed} が撃つ。
 * ここに並ぶのはそれ以外の砲塔だけであり、砲塔を1つも書かない車両——同梱のほぼ全部——ではこのクラス全体が
 * 毎tick何もしない。
 *
 * <p><b>据えるのはサーバー。</b>主砲塔と違い、砲手のクライアントは車両をシミュレートしていない（運転して
 * いるのは別人だ）ので、砲手の視線を読んで角度を決められるのはサーバーしかない。全クライアントは報告された
 * 角へ寄せる（{@link #clientTick}）——他人の戦車の砲塔と同じで、理由も同じだ。
 *
 * <p><b>弾は撃つ砲塔ごとに数える。</b>車両の3つの弾倉（{@link Magazine}）は主砲・同軸・発射筒の物であり、
 * 独立砲塔はそこに入らない。残弾と装填カウンタはここの配列にあり、同期タグで角度と一緒にクライアントへ渡る。
 */
public final class TurretStations {
    /** どの砲塔でもない。 */
    public static final int NONE = -1;

    /** 引き金を引いた砲手の1押しが、次のパケットが来る前に消えないための猶予（tick）。 */
    private static final int TRIGGER_HOLD = 5;

    /**
     * 報告された角へ毎tick詰める割合。{@code GroundVehicleEntity.TURRET_CATCH_UP} と同じ値・同じ理由で、
     * 同期データは値が変わった時にまとめて届くので、代入すると砲塔は跳ぶ。
     */
    private static final float CATCH_UP = 0.3F;

    /** 送るに値する角の変化（度）。これ以下の追従で同期タグを組み直さない。 */
    private static final float WORTH_SENDING = 0.25F;

    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    private final GroundVehicleEntity vehicle;

    /** 砲塔ごとの向き。車両ファイルの砲塔リストと同じ順・同じ長さ。据えた側が決めた値。 */
    private float[] yaw = new float[0];
    private float[] pitch = new float[0];
    /** 描くための向き。上の値へ寄せていく。 */
    private float[] shownYaw = new float[0];
    private float[] shownPitch = new float[0];
    /** 前tick終端の同じ値。フレーム間の補間に要る。 */
    private float[] shownYawO = new float[0];
    private float[] shownPitchO = new float[0];

    private int[] rounds = new int[0];
    private int[] reload = new int[0];
    /**
     * 砲塔ごとに今どの弾種が入っているか。空文字なら弾種を持たない砲塔——兵装ファイル自身の弾を撃つ。
     *
     * <p><b>一度に1種類だけ。</b>車両の3つの架台は種類ごとの内訳を持つ（{@link Magazine}）が、砲塔は
     * 持たない。砲塔は乗員1人が受け持つ1門であり、その者が積んだ物が薬室にある物だ——弾倉を空にすれば
     * 別の種類を積める。
     */
    private String[] loaded = new String[0];

    /** 砲塔ごとの砲。撃ち方は車両の主砲とまったく同じで、違うのは向きと弾倉の置き場だけ。 */
    private BuiltInGun[] guns = new BuiltInGun[0];

    /**
     * 引き金を引いている砲手それぞれと、その報告が最後に届いた tick。運転手以外はここからしか分からない
     * ——操縦入力のパケットは運転している者しか送らないので、砲手の引き金は自前の小さなパケットで届く。
     */
    private final Map<UUID, Long> firing = new HashMap<>();

    private boolean dirty;

    public TurretStations(GroundVehicleEntity vehicle) {
        this.vehicle = vehicle;
    }

    private List<GroundVehicleDefinition.Station> stations() {
        return this.vehicle.getStats().turrets();
    }

    /** そもそも独立砲塔を持つ車両か。持たない車両ではこのクラス全体が毎tick何もしない。 */
    public boolean exists() {
        return !this.stations().isEmpty();
    }

    public int count() {
        return this.stations().size();
    }

    public GroundVehicleDefinition.Station station(int index) {
        return this.stations().get(index);
    }

    // ------------------------------------------------------------------
    // 誰がどの砲塔を持っているか
    // ------------------------------------------------------------------

    /**
     * その砲塔を回す者。指定された席の乗員、空席なら運転手。誰も乗っていなければ null。
     *
     * <p>席が範囲外を指すファイルでも運転手へ落ちるだけで、砲塔が沈黙したりはしない。
     */
    @Nullable
    public LivingEntity operatorOf(int index) {
        int seat = this.station(index).seat();

        for (Entity rider : this.vehicle.getPassengers()) {
            if (rider instanceof LivingEntity crew && this.vehicle.getSeatIndex(rider) == seat) {
                return crew;
            }
        }

        return this.vehicle.getAviator();
    }

    /** その乗員が今持っている砲塔。持っていなければ空。 */
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
     * その乗員が計器に出す砲塔。持っている中の最初の1つで、持っていなければ {@link #NONE}。
     *
     * <p>1人で全砲塔を持っている運転手には、自分の主砲の計器が既にある。だから最初の1つで足りる
     * ——砲手席の乗員は1つしか持たないし、持っている数そのものは {@link #stationsOf} が答える。
     */
    public int liveStationOf(@Nullable Entity crew) {
        List<Integer> mine = this.stationsOf(crew);

        return mine.isEmpty() ? NONE : mine.get(0);
    }

    /** その席が受け持つ砲塔。誰が乗っているかを見ない、ファイルに書かれた対応そのもの。 */
    public int stationForSeat(int seat) {
        for (int index = 0; index < this.count(); index++) {
            if (this.station(index).seat() == seat) {
                return index;
            }
        }

        return NONE;
    }

    /**
     * 砲手が引き金を引いている、あるいは離したという報告。運転手以外の乗員はこれでしか撃てない。
     *
     * <p>押している間の状態であって1回の発砲ではないので、引いている間は毎tick届く。報告が途切れれば数tick
     * で消える——切断した砲手の砲が撃ち続けないためだ。{@link #TRIGGER_HOLD} 参照。
     */
    public void setTrigger(Player crew, boolean pressed) {
        if (pressed) {
            this.firing.put(crew.getUUID(), this.vehicle.level().getGameTime());
        } else {
            this.firing.remove(crew.getUUID());
        }
    }

    /**
     * その砲塔の引き金が今引かれているか。
     *
     * <p>撃つのは、その砲塔を持っている者が引いている場合だけ。焼けた車体では誰の引き金も繋がらない。
     *
     * <p><b>運転手の引き金は主砲と共用だ。</b>選択（主砲／同軸／ミサイル）は運転手が自分の砲塔について
     * 巡る物で、独立砲塔はそこに入らない——1人で乗っている者が引き金を引けば、その者が持っている物は全部
     * 撃つ。それが「1人なら全部を管理できる」ということの射撃側の答えであり、砲手が乗ればその砲塔だけが
     * 運転手の引き金から外れる。
     */
    public boolean pulled(int index) {
        if (this.vehicle.isWrecked()) {
            return false;
        }

        LivingEntity crew = this.operatorOf(index);

        return crew != null && this.isFiring(crew);
    }

    /** その乗員が今撃っているか。運転手は操縦入力から、それ以外は自前の報告から。 */
    private boolean isFiring(LivingEntity crew) {
        if (crew == this.vehicle.getAviator()) {
            return this.vehicle.getInput().fire();
        }

        Long since = this.firing.get(crew.getUUID());

        return since != null && this.vehicle.level().getGameTime() - since <= TRIGGER_HOLD;
    }

    // ------------------------------------------------------------------
    // tick
    // ------------------------------------------------------------------

    /** サーバー側の1tick分。各砲塔を射手の視線へ向け、引かれている引き金を砲へ渡す。 */
    public void tick() {
        if (!this.exists()) {
            return;
        }

        this.ensureLayout();

        for (int index = 0; index < this.count(); index++) {
            GroundVehicleDefinition.Station station = this.station(index);

            this.aim(index, station, this.operatorOf(index));
            // 据えた側では、描くための角は据えた角そのものだ。寄せる処理はこの値が<em>報告として</em>
            // 届く側のためにある。ここで写しておかないと、砲口も砲身の線も1tick古い角から組まれる
            // ——弾が出るのはこの後だ。
            this.shownYawO[index] = this.shownYaw[index];
            this.shownPitchO[index] = this.shownPitch[index];
            this.shownYaw[index] = this.yaw[index];
            this.shownPitch[index] = this.pitch[index];

            if (!this.vehicle.isWrecked()) {
                this.guns[index].tick(this.pulled(index));
            }
        }
    }

    /**
     * 全クライアントの1tick分。報告された角へ寄せる。
     *
     * <p>代入しないのは主砲塔と同じ理由だ（{@code GroundVehicleEntity.tick} 参照）。同期データが届くのは
     * 値が変わった時、しかもエンティティの更新間隔ごとにまとめてなので、代入すると砲塔は「何tickも静止、
     * 届いたtickに一気に跳ぶ」を繰り返す。
     */
    public void clientTick() {
        if (!this.exists()) {
            return;
        }

        this.ensureLayout();

        for (int index = 0; index < this.shownYaw.length; index++) {
            this.shownYawO[index] = this.shownYaw[index];
            this.shownPitchO[index] = this.shownPitch[index];
            this.shownYaw[index] = Mth.wrapDegrees(this.shownYaw[index]
                    + Mth.degreesDifference(this.shownYaw[index], this.yaw[index]) * CATCH_UP);
            this.shownPitch[index] += (this.pitch[index] - this.shownPitch[index]) * CATCH_UP;
        }
    }

    /**
     * 砲塔を射手が見ている方向へ、自分の旋回速度で向ける。
     *
     * <p>視線はワールド座標、砲塔の可動範囲は車体座標なので、まず視線を車体の軸へ引き戻す。射手がいない
     * 砲塔は今の向きのまま留まる——手を離した砲塔が正面へ戻る理由は無い。
     */
    private void aim(int index, GroundVehicleDefinition.Station station, @Nullable LivingEntity crew) {
        if (crew == null) {
            return;
        }

        Vec3 look = Attitude.toBody(this.vehicle.getAttitude(), crew.getLookAngle());

        if (look.lengthSqr() < 1.0E-6) {
            return;
        }

        look = look.normalize();

        // 車体座標系は x が右・z が車首方向なので、方位は x と z の間の角、仰角は上向き成分そのもの。
        float wantYaw = station.clampYaw((float) Math.toDegrees(Math.atan2(look.x, look.z)));
        // 運転手が三人称で覗いている間、画面中央は目線より下にある。主砲塔が同じ量だけ下げている
        // （{@code GroundVehicleEntity.tickTurret}）ので、運転手が持っている砲塔も同じ所を狙う。
        // 砲手席の乗員には倒し角が無い——あの視点は倒されていない。
        float tilt = crew == this.vehicle.getAviator() ? this.vehicle.getSightTilt() : 0.0F;
        float wantPitch = station.clampPitch(
                (float) Math.toDegrees(Math.asin(Mth.clamp(look.y, -1.0, 1.0))) - tilt);

        float turned = approachAngle(this.yaw[index], wantYaw, station.traverseRate());
        float raised = approach(this.pitch[index], wantPitch, station.elevationRate());

        if (Math.abs(Mth.degreesDifference(turned, this.yaw[index])) > WORTH_SENDING
                || Math.abs(raised - this.pitch[index]) > WORTH_SENDING) {
            this.dirty = true;
        }

        this.yaw[index] = turned;
        this.pitch[index] = raised;
    }

    // ------------------------------------------------------------------
    // どこを向き、どこから弾が出るか
    // ------------------------------------------------------------------

    /** その砲塔の方位（度）。車体に対する角で、正が右。 */
    public float yawOf(int index, float partialTick) {
        if (index < 0 || index >= this.shownYaw.length) {
            return 0.0F;
        }

        return Mth.rotLerp(partialTick, this.shownYawO[index], this.shownYaw[index]);
    }

    /** 同じく仰角（度）。正が上。 */
    public float pitchOf(int index, float partialTick) {
        if (index < 0 || index >= this.shownPitch.length) {
            return 0.0F;
        }

        return Mth.lerp(partialTick, this.shownPitchO[index], this.shownPitch[index]);
    }

    /** その砲塔の向きが分かっているか。届く前のクライアントでは、まだどの砲塔も何も向いていない。 */
    public boolean isLaid(int index) {
        return index >= 0 && index < this.shownYaw.length;
    }

    /**
     * ワールド座標での、その砲塔の砲身の指向。
     *
     * <p>組み付け順に3つの回転を重ねる。車体は地面が決める姿勢で寝て、砲塔は車体上で旋回し、砲は砲塔内で
     * 俯仰する。車両自身の砲塔が {@code GroundVehicleEntity.getAimDirection} で行っているのと同じ手順で、
     * 違うのは角度がどこから来るかだけだ。
     */
    public Vec3 direction(int index, float partialTick) {
        return Attitude.nose(new Quaternionf(this.vehicle.getAttitude(partialTick))
                .rotateY(-this.yawOf(index, partialTick) * DEG_TO_RAD)
                .rotateX(-this.pitchOf(index, partialTick) * DEG_TO_RAD));
    }

    /**
     * ワールド座標での、その砲塔の砲口の1つ。
     *
     * <p>固定点ではなく耳軸＋長さから組む。理由は主砲とまったく同じで、砲身は振れるからだ
     * （{@code GroundVehicleEntity.getMuzzle} 参照）。
     */
    public Vec3 muzzle(int index, int barrel, float partialTick) {
        GroundVehicleDefinition.Station station = this.station(index);
        GroundVehicleDefinition.Barrel one = station.barrel(barrel);
        Vec3 trunnion = this.vehicle.toWorld(this.carry(index, one.trunnion(), partialTick), partialTick);

        return trunnion.add(this.direction(index, partialTick)
                .scale(one.lengthOr(station.barrelLength())));
    }

    /**
     * 砲塔上の一点を、その砲塔が運んだ先へ。車両座標系のまま返す。
     *
     * <p>砲塔上の当たり判定箱と、砲塔に据え付けられた物のためのもの。車両自身の砲塔に対する
     * {@code GroundVehicleEntity.onTurret} と同じ処理を、その砲塔自身の旋回輪の周りで行う。
     */
    public Vec3 carry(int index, Vec3 offset, float partialTick) {
        Vec3 ring = this.station(index).ring();
        Vec3 local = offset.subtract(ring);
        float radians = this.yawOf(index, partialTick) * DEG_TO_RAD;
        double sin = Mth.sin(radians);
        double cos = Mth.cos(radians);

        return ring.add(new Vec3(
                local.x * cos + local.z * sin,
                local.y,
                -local.x * sin + local.z * cos));
    }

    /**
     * 同じ物を、砲身に付いている点に対して。先に耳軸周りで俯仰させ、それから旋回輪の周りに回す。
     *
     * @param trunnion この点が乗っている耳軸。砲身ごとに違うので呼ぶ側が渡す
     */
    public Vec3 carryGun(int index, Vec3 offset, Vec3 trunnion, float partialTick) {
        Vec3 local = offset.subtract(trunnion);
        float radians = -this.pitchOf(index, partialTick) * DEG_TO_RAD;
        double sin = Mth.sin(radians);
        double cos = Mth.cos(radians);
        Vec3 rocked = trunnion.add(new Vec3(
                local.x,
                local.y * cos - local.z * sin,
                local.y * sin + local.z * cos));

        return this.carry(index, rocked, partialTick);
    }

    /** その砲塔の耳軸。砲身を並べていない砲塔ではファイルの {@code trunnion} そのもの。 */
    public Vec3 trunnionOf(int index) {
        return this.station(index).barrel(0).trunnion();
    }

    // ------------------------------------------------------------------
    // 弾
    // ------------------------------------------------------------------

    public int roundsOf(int index) {
        return index >= 0 && index < this.rounds.length ? this.rounds[index] : 0;
    }

    public int reloadOf(int index) {
        return index >= 0 && index < this.reload.length ? this.reload[index] : 0;
    }

    /** その砲塔の弾倉の大きさ。兵装ファイルから。 */
    public int capacityOf(int index) {
        this.ensureLayout();

        return index >= 0 && index < this.guns.length ? this.guns[index].capacity() : 0;
    }

    /** その砲塔の薬室にある弾種。何も積んでいなければ null。 */
    @Nullable
    public ResourceLocation ammunitionOf(int index) {
        if (index < 0 || index >= this.loaded.length || this.loaded[index].isEmpty()) {
            return null;
        }

        return ResourceLocation.tryParse(this.loaded[index]);
    }

    /**
     * 差し出された1弾種を、それを受け取る砲塔へ押し込む。主砲・同軸・発射筒がどれも受け取らなかった後で
     * 訊かれる（{@code GroundVehicleEntity.loadRound} 参照）ので、ここへ来た弾はどの固定架台の物でもない。
     *
     * <p><b>1つの砲塔には1種類だけ。</b>既に別の種類が入っている砲塔は受け取らない——撃ち尽くせば
     * 空になり、そこで初めて別の種類が入る。砲塔は乗員1人が受け持つ1門であって、種類ごとに仕切られた
     * 弾庫ではない。
     *
     * @return どの砲塔が何個受け取ったか。どこにも入らなければ砲塔番号が {@link #NONE}
     */
    public Loaded load(ResourceLocation round, int offered) {
        this.ensureLayout();

        for (int index = 0; index < this.guns.length; index++) {
            if (!this.station(index).ammunition().contains(round) || offered <= 0) {
                continue;
            }

            // 既に入っている物と違う種類は入らない。空になっていれば何でも入る。
            if (this.rounds[index] > 0 && !this.loaded[index].isEmpty()
                    && !this.loaded[index].equals(round.toString())) {
                continue;
            }

            int perItem = Math.max(1, Definitions.ammunition(round).perItem());
            int taken = Math.min(offered, (this.capacityOf(index) - this.rounds[index]) / perItem);

            if (taken <= 0) {
                continue;
            }

            this.loaded[index] = round.toString();
            this.rounds[index] += taken * perItem;
            this.dirty = true;

            return new Loaded(index, taken);
        }

        return new Loaded(NONE, 0);
    }

    /** 1回の装填の結果。どの砲塔へ、弾薬アイテム何個ぶんが入ったか。 */
    public record Loaded(int station, int taken) {
        public boolean happened() {
            return this.station != NONE && this.taken > 0;
        }
    }

    // ------------------------------------------------------------------
    // 状態
    // ------------------------------------------------------------------

    /** 配列を車両ファイルの砲塔数に合わせる。新しく現れた砲塔は自分の正面を向いて始まる。 */
    private void ensureLayout() {
        int wanted = this.count();

        if (this.yaw.length == wanted) {
            return;
        }

        float[] yaw = new float[wanted];
        float[] pitch = new float[wanted];
        int[] rounds = new int[wanted];
        int[] reload = new int[wanted];
        String[] loaded = new String[wanted];
        BuiltInGun[] guns = new BuiltInGun[wanted];

        for (int index = 0; index < wanted; index++) {
            loaded[index] = "";

            if (index < this.yaw.length) {
                yaw[index] = this.yaw[index];
                pitch[index] = this.pitch[index];
                rounds[index] = this.rounds[index];
                reload[index] = this.reload[index];
                loaded[index] = this.loaded[index];
            } else {
                yaw[index] = this.station(index).bearing();
            }

            guns[index] = new BuiltInGun(this.vehicle, new StationMount(index));
        }

        this.yaw = yaw;
        this.pitch = pitch;
        this.rounds = rounds;
        this.reload = reload;
        this.loaded = loaded;
        this.guns = guns;
        this.shownYaw = yaw.clone();
        this.shownPitch = pitch.clone();
        this.shownYawO = yaw.clone();
        this.shownPitchO = pitch.clone();
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
            entry.putInt("Rounds", this.rounds[index]);
            entry.putInt("Reload", this.reload[index]);
            entry.putString("Ammunition", this.loaded[index]);
            list.add(entry);
        }

        tag.put("Turrets", list);

        return tag;
    }

    public void load(CompoundTag tag) {
        ListTag list = tag.getList("Turrets", Tag.TAG_COMPOUND);

        if (list.isEmpty()) {
            return;
        }

        boolean fresh = this.yaw.length != list.size();

        this.yaw = new float[list.size()];
        this.pitch = new float[list.size()];
        this.rounds = new int[list.size()];
        this.reload = new int[list.size()];
        this.loaded = new String[list.size()];

        if (fresh) {
            this.guns = new BuiltInGun[list.size()];

            for (int index = 0; index < list.size(); index++) {
                this.guns[index] = new BuiltInGun(this.vehicle, new StationMount(index));
            }
        }

        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompound(index);
            this.yaw[index] = entry.getFloat("Yaw");
            this.pitch[index] = entry.getFloat("Pitch");
            this.rounds[index] = entry.getInt("Rounds");
            this.reload[index] = entry.getInt("Reload");
            this.loaded[index] = entry.getString("Ammunition");
        }

        // 初めて届いた分は寄せる先ではなく今いる所でもある。寄せさせると、視界に入った瞬間の砲塔が
        // 全部正面から回り出す。
        if (fresh) {
            this.shownYaw = this.yaw.clone();
            this.shownPitch = this.pitch.clone();
            this.shownYawO = this.yaw.clone();
            this.shownPitchO = this.pitch.clone();
        }
    }

    private static float approach(float from, float to, float step) {
        float delta = to - from;

        return Math.abs(delta) <= step ? to : from + Math.signum(delta) * step;
    }

    private static float approachAngle(float from, float to, float step) {
        float delta = Mth.degreesDifference(from, to);

        return Math.abs(delta) <= step ? to : Mth.wrapDegrees(from + Math.signum(delta) * step);
    }

    /**
     * 1つの砲塔を、車両の主砲とまったく同じ砲として扱うための口。
     *
     * <p>{@link BuiltInGun} が訊くのは6つだけ——何を撃つか、残弾と装填はどこか、砲口はどこで何本か、どの線へ
     * 飛ぶか、誰が撃っているか。独立砲塔ではそのどれもが砲塔ごとの答えを持つ、というのがこのクラスの全部だ。
     * 弾種（{@link Magazine}）は持たない。あれは車両の3つの架台を数える仕組みで、砲塔はそこに入らない。
     */
    private final class StationMount implements BuiltInGun.Mount {
        private final int index;

        private StationMount(int index) {
            this.index = index;
        }

        private GroundVehicleDefinition.Station station() {
            List<GroundVehicleDefinition.Station> all = TurretStations.this.stations();

            return this.index < all.size() ? all.get(this.index) : null;
        }

        @Override
        public Optional<ResourceLocation> weapon(GroundVehicleEntity vehicle) {
            GroundVehicleDefinition.Station station = this.station();

            return station == null ? Optional.empty() : station.weapon();
        }

        @Override
        public int rounds(GroundVehicleEntity vehicle) {
            return TurretStations.this.roundsOf(this.index);
        }

        @Override
        public void rounds(GroundVehicleEntity vehicle, int rounds) {
            if (this.index < TurretStations.this.rounds.length) {
                TurretStations.this.rounds[this.index] = Math.max(rounds, 0);
                TurretStations.this.dirty = true;
            }
        }

        @Override
        public int reload(GroundVehicleEntity vehicle) {
            return TurretStations.this.reloadOf(this.index);
        }

        /**
         * 装填カウンタは変化を知らせない。
         *
         * <p>あれは毎tick1ずつ減る値なので、知らせにすると砲塔を持つ車両は装填中ずっと毎tickタグを
         * 組み直して全員へ送ることになる——機関砲の待ちは2tickなので、撃っている間は永久にそうなる。
         * 残弾と角が動いたときに一緒に運ばれるので、計器の「装填中」は最大でも数tick古いだけで済む。
         */
        @Override
        public void reload(GroundVehicleEntity vehicle, int ticks) {
            if (this.index < TurretStations.this.reload.length) {
                TurretStations.this.reload[this.index] = Math.max(ticks, 0);
            }
        }

        @Override
        public Vec3 muzzle(GroundVehicleEntity vehicle, int barrel) {
            return TurretStations.this.muzzle(this.index, barrel, 1.0F);
        }

        @Override
        public int barrels(GroundVehicleEntity vehicle) {
            GroundVehicleDefinition.Station station = this.station();

            return station == null ? 1 : station.barrelCount();
        }

        @Override
        public String tag() {
            return "Turret" + this.index;
        }

        @Override
        public Vec3 bore(GroundVehicleEntity vehicle) {
            return TurretStations.this.direction(this.index, 1.0F);
        }

        @Override
        @Nullable
        public LivingEntity crew(GroundVehicleEntity vehicle) {
            return TurretStations.this.operatorOf(this.index);
        }

        @Override
        @Nullable
        public ResourceLocation ammunition(GroundVehicleEntity vehicle) {
            return TurretStations.this.ammunitionOf(this.index);
        }

        @Override
        public boolean typed(GroundVehicleEntity vehicle) {
            return false;
        }

        @Override
        public void spend(GroundVehicleEntity vehicle, int rounds) {
            this.rounds(vehicle, this.rounds(vehicle) - rounds);

            // 撃ち尽くした薬室は空になる。次に積む者は別の種類を選べる。
            if (this.rounds(vehicle) <= 0 && this.index < TurretStations.this.loaded.length) {
                TurretStations.this.loaded[this.index] = "";
            }
        }

        @Override
        public List<ResourceLocation> types(GroundVehicleEntity vehicle) {
            GroundVehicleDefinition.Station station = this.station();

            return station == null ? List.of() : station.ammunition();
        }
    }
}
