package com.ashvehicles.sensor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.RocketEntity;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.network.SensorPayload;
import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.weapon.TargetLock;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 1台の機体が周囲について知り得ること。兵装と同じ向きを見るレーダーと、全方位を同時に聞く警戒受信機。
 *
 * <p>両者が同じクラスなのは走査が1回だから。同じ空域に同じ問い（何がどこにいるか）を投げるので、2回
 * 訊けば2回分払うことになる。答えの使い方は逆で、レーダーは<em>この</em>機体が前方に見える物を、
 * 受信機は<em>この</em>機体を見ている相手を報告する。
 *
 * <p><b>「航空機の」計器ではなく「機体の」計器。</b> 地上の発射機は航空機を探すのに航空機と全く同じ
 * 仕組みを使う。両者が同種の物でなければ互いに警告し合えない——対空陣地の上を飛ぶ怖さの正体はパイロット
 * の受信機が鳴ることであり、それは自分と同じ条件で存在するレーダーに対してしか鳴らない。よってこれは
 * {@link VehicleEntityBase} に対して動き、種類を知らない。知る必要がある唯一の点は装置がどちらを向いて
 * いるかで、それは {@link VehicleEntityBase#getAimDirection} が両方について答える。
 *
 * <p><b>全部サーバー側で走る。</b> {@link TargetLock} のシーカーと同じで、乗員が何を知っているかを
 * 決めるのはクライアントの仕事ではない。結果は操縦席の1人にだけ送る。レーダー画面は計器であって放送
 * ではなく、しかも量が多い。{@link SensorPayload} 参照。
 *
 * <p>無人の間は何も動かない。駐機中の機体のレーダーは切れており、動作コストが無いと同時に誰も照射しな
 * い。だからエプロンに置きっぱなしの機体がマップ中の警戒受信機を鳴らすことはない。
 *
 * <p><b>敵味方の判定は走査の副産物としてここで押す。</b> {@link Iff} 参照。判定はクライアントでも出せる
 * が、レーダーの届く距離で見つかる物の大半はそのクライアントに存在すら知らされていないので、押せるのは
 * 相手を実体として持っているここだけになる。判定は接触に乗って計器へ渡り、味方の照射に対しては警戒受信機
 * を鳴らさない。
 *
 * <p><b>やらないこと。</b> 地形は一切考慮しない。山の陰の目標も目標のまま。現実のレーダーはそこまで
 * 甘くないが、代案は数百ブロック先まで1走査ごと1目標ごとに視線判定を撃つことで、しかもその地面はたい
 * ていロードすらされていない。
 */
public final class Sensors {
    /** スコープに描く価値のある上限であり、送る価値のある上限。 */
    private static final int MOST_CONTACTS = 16;
    private static final int MOST_THREATS = 8;

    private final VehicleEntityBase vehicle;
    private List<Contact> contacts = List.of();
    private List<Threat> threats = List.of();
    private int sinceSweep;

    public Sensors(VehicleEntityBase vehicle) {
        this.vehicle = vehicle;
    }

    /** 直前の走査で見つけた物。無人の機体では空。 */
    public List<Contact> contacts() {
        return this.contacts;
    }

    /** この機体を見ている相手。深刻な順。 */
    public List<Threat> threats() {
        return this.threats;
    }

    /**
     * このレーダーが今そのエンティティを捉えているか。
     *
     * <p>訊いてくるのは他機の警戒受信機。目標一覧を送るだけでなく保持しているのはこのため。照射されて
     * いるという事実は相手のレーダーが教えてくれるものなので、相手のレーダーが実体として存在していなけ
     * ればならない。
     */
    public boolean paints(Entity entity) {
        for (Contact contact : this.contacts) {
            if (contact.id() == entity.getId()) {
                return true;
            }
        }

        return false;
    }

    /** 1tick分。周期が来たら走査し、結果を乗員に伝える。 */
    public void tick() {
        if (!(this.vehicle.level() instanceof ServerLevel level)) {
            return;
        }

        ServerPlayer crew = this.crew();

        if (crew == null) {
            this.clear();

            return;
        }

        VehicleChassis.Radar radar = this.vehicle.radar();

        // レーダーも受信機も無い機体には走査する理由が無い。
        if (!radar.exists()) {
            this.clear();

            return;
        }

        if (++this.sinceSweep < Math.max(radar.sweepTicks(), 1)) {
            return;
        }

        this.sinceSweep = 0;
        this.sweep(level, radar);
        PacketDistributor.sendToPlayer(crew, new SensorPayload(this.contacts, this.threats));
    }

    private void clear() {
        this.contacts = List.of();
        this.threats = List.of();
    }

    /**
     * アンテナ1掃引分。近傍の全エンティティを1度歩くだけで済ませる。
     *
     * <p>機体には両方（前方にいるか／こちらに関心があるか）を訊く。両方に該当し得るのは機体だけだから。
     * 徒歩のプレイヤーはスコープに載るだけ、飛翔中のミサイルは警告になるだけ。
     */
    private void sweep(ServerLevel level, VehicleChassis.Radar radar) {
        Vec3 from = this.vehicle.position();
        // 装置が向いている方向＝兵装が向いている方向（機体なら機首、砲塔なら砲身）。水平に潰し、
        // ビームは機体の姿勢ではなくこの水平方向を基準に取る。旋回中に読むスコープで世界が傾いては
        // 困るし、斜面に乗った車体から読む場合も同じ。
        Vec3 along = flat(this.vehicle.getAimDirection(1.0F));
        Vec3 right = new Vec3(-along.z, 0.0, along.x);
        double reach = radar.reach();
        double widest = Math.cos(Math.toRadians(radar.arc()));
        TargetLock lock = this.vehicle.lock();
        Entity seeking = lock == null ? null : lock.target();

        List<Contact> found = new ArrayList<>();
        List<Threat> warnings = new ArrayList<>();
        AABB box = this.vehicle.getBoundingBox().inflate(reach);

        for (Entity other : level.getEntities(this.vehicle, box, Sensors::worthLookingAt)) {
            Vec3 gap = other.position().subtract(from);
            double distance = gap.length();

            if (distance > reach || distance < 1.0E-3) {
                continue;
            }

            float bearing = bearing(gap, along, right);

            if (other instanceof RocketEntity missile) {
                if (missile.getTarget() == this.vehicle && distance <= radar.warningRange()) {
                    warnings.add(new Threat(bearing, Threat.Kind.MISSILE));
                }

                continue;
            }

            Iff identity = Iff.between(this.vehicle, other);

            // 味方のレーダーもシーカーもこちらを照らしてはいるが、受信機を鳴らす理由が無い。鳴らせば
            // 編隊を組んで飛ぶこと自体が不可能になり——僚機は常に隣にいて常にこちらを見ている——受信機が
            // 鳴り続ければ、本当に鳴った1回を聞き分けられなくなる。
            //
            // ミサイルは別扱いで下のまま。誰が撃った物であれ、こちらへ向かっている弾はこちらへ向かって
            // いる。誤射の警告を消す装置に価値は無い。
            if (other instanceof VehicleEntityBase hostile && distance <= radar.warningRange()
                    && identity != Iff.FRIEND) {
                Threat.Kind attention = this.attentionFrom(hostile);

                if (attention != null) {
                    warnings.add(new Threat(bearing, attention));
                }
            }

            // 「このレーダーの探知距離」ではなく「この相手に対する探知距離」。反射を返さないよう
            // 作られた形状は至近でしか見つからないか全く見つからないが、ミサイルを外部搭載した
            // ステルス機はもうその形状ではない。
            if (radar.fitted() && distance <= radar.range() * AircraftEntity.visibility(other)
                    && gap.scale(1.0 / distance).dot(along) > widest) {
                found.add(new Contact(other.getId(), bearing, (float) distance,
                        (float) (other.getY() - this.vehicle.getY()),
                        other == seeking,
                        other instanceof AircraftEntity,
                        identity));
            }
        }

        found.sort(Comparator.comparingDouble(Contact::range));
        warnings.sort(Comparator.comparingInt((Threat threat) -> threat.kind().ordinal()).reversed());

        this.contacts = List.copyOf(found.subList(0, Math.min(found.size(), MOST_CONTACTS)));
        this.threats = List.copyOf(warnings.subList(0, Math.min(warnings.size(), MOST_THREATS)));
    }

    /**
     * 他機がこの機体に対して何をしているか。気付いていなければ null。
     *
     * <p>相手のシーカーはレーダーより重い。スコープに載っているのは今日の午後の話だが、シーカーに入って
     * いるのは次の数秒の話。
     */
    @Nullable
    private Threat.Kind attentionFrom(VehicleEntityBase other) {
        TargetLock lock = other.lock();

        if (lock != null && lock.target() == this.vehicle) {
            return lock.isLocked() ? Threat.Kind.LOCK : Threat.Kind.SEARCH;
        }

        return other.getSensors().paints(this.vehicle) ? Threat.Kind.SEARCH : null;
    }

    /**
     * 機体、徒歩の人間、そして既にこちらへ向かっている物。
     *
     * <p>航空機だけでなく地上車両も含める。理由は2つとも共通で、車列を攻撃しに行く機体はそれをスコープ
     * に載せたいし、地上の対空陣地は機体の受信機が聞き取れる存在でなければならない。
     */
    private static boolean worthLookingAt(Entity candidate) {
        if (!candidate.isAlive()) {
            return false;
        }

        if (candidate instanceof RocketEntity) {
            return true;
        }

        if (candidate instanceof VehicleEntityBase machine) {
            // 残骸は風景。そこにあるし金属でできてもいるが、撃墜された物を全部映し続けるスコープは
            // 戦えない目標で埋まり、戦える1つがその中に紛れてしまう。
            return !machine.isWrecked();
        }

        // 機体に乗っている者は乗員であって目標ではない。スペクテイターはそもそも居ない。
        return candidate instanceof Player player && !player.isSpectator() && player.getVehicle() == null;
    }

    /** 照準線からの角度（度）。右が正、水平面で測る。 */
    private static float bearing(Vec3 gap, Vec3 along, Vec3 right) {
        return (float) Mth.wrapDegrees(Math.toDegrees(
                Math.atan2(gap.x * right.x + gap.z * right.z, gap.x * along.x + gap.z * along.z)));
    }

    /** 上下成分を抜いた進行方位だけ。 */
    private static Vec3 flat(Vec3 direction) {
        Vec3 level = new Vec3(direction.x, 0.0, direction.z);

        return level.lengthSqr() < 1.0E-8 ? new Vec3(0.0, 0.0, 1.0) : level.normalize();
    }

    @Nullable
    private ServerPlayer crew() {
        return this.vehicle.getControllingPassenger() instanceof ServerPlayer player ? player : null;
    }
}
