package com.ashvehicles.sensor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.CountermeasureEntity;
import com.ashvehicles.entity.DesignationEntity;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.LateWorld;
import com.ashvehicles.entity.RocketEntity;
import com.ashvehicles.entity.TargetDroneEntity;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.entity.VehiclePart;
import com.ashvehicles.entity.VehicleProjectile;
import com.ashvehicles.network.SensorPayload;
import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.weapon.TargetLock;
import com.ashvehicles.weapon.WeaponDefinition;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
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
 * <p><b>地形は遮る。</b> 尾根の向こうの目標は目標ではない。判定は近い順に、スコープが載せられる件数
 * （{@link #MOST_CONTACTS}）が埋まるまでしか撃たない——見えている物が十数件見つかれば、その後ろに何が
 * あるかを訊く理由がそもそも無いからだ。{@link #inSight} 参照。
 */
public final class Sensors {
    /** スコープに描く価値のある上限であり、送る価値のある上限。 */
    private static final int MOST_CONTACTS = 16;
    private static final int MOST_THREATS = 8;

    /**
     * 走査が作った接触1件と、それが誰だったか。
     *
     * <p>視線判定は近い順に、枠が埋まるまでしか撃たない（{@link #sweep}）。だから接触を作った後も
     * 相手を手放せない——判定を撃つ相手がその物だからだ。送るのは {@link Contact} だけで、こちらは
     * 掃引の内側にしか存在しない。
     */
    private record Seen(Entity entity, Contact contact) {
    }

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
     * 徒歩のプレイヤーはスコープに載るだけ、飛翔中のミサイルはスコープに載った上で、こちらへ向かって
     * いるなら警告にもなる。
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

        List<Seen> seen = new ArrayList<>();
        List<Threat> warnings = new ArrayList<>();
        AABB box = this.vehicle.getBoundingBox().inflate(reach);

        for (Entity other : level.getEntities(this.vehicle, box, this::worthLookingAt)) {
            Vec3 gap = other.position().subtract(from);
            double distance = gap.length();

            if (distance > reach || distance < 1.0E-3) {
                continue;
            }

            float bearing = bearing(gap, along, right);

            // 飛翔中の誘導弾は警報にもなる。スコープには他の全部と同じ資格で載っている（{@link
            // #worthLookingAt}）ので、ここで足すのは「こちらへ向かっている」という、位置からは読めない
            // 1つだけだ。
            if (other instanceof RocketEntity missile && missile.getTarget() == this.vehicle
                    && distance <= radar.warningRange()) {
                warnings.add(new Threat(bearing, Threat.Kind.MISSILE));
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
                // 標的ドローンは空中の的なので、スコープでは航空機の記号で出す。飛翔中のミサイルも同じ
                // ——地上目標の記号で出れば、高度を持って向かってくる物が地面を這う物に見える。
                seen.add(new Seen(other, new Contact(other.getId(), bearing, (float) distance,
                        (float) (other.getY() - this.vehicle.getY()),
                        other == seeking,
                        other instanceof AircraftEntity || other instanceof TargetDroneEntity
                                || other instanceof RocketEntity,
                        identity,
                        // 対空陣地か。この1つだけは接触の中で「危険の種類」を語る欄で、HMD が世界に
                        // 印を置く相手を決める（{@link Contact#emitter}）。押せるのはここだけだ
                        // ——相手を実体として持っているのはサーバーのこちら側しかない。
                        other instanceof GroundVehicleEntity ground && ground.radar().fitted())));
            }
        }

        // 近い順に視線を通し、枠が埋まったらそこで止める。
        //
        // <p>並べ替えが先なのは、スコープに載るのが「見つけた中で近い順に十数件」だからで、それは
        // 判定を撃つ順でもある。遠くの尾根の裏に何百体いようと、手前が埋まっていれば1本も撃たない
        // ——この掃引で最も高くつく処理を、意味のある件数に縛る唯一の方法だ。
        seen.sort(Comparator.comparingDouble(entry -> entry.contact().range()));

        List<Contact> found = new ArrayList<>(Math.min(seen.size(), MOST_CONTACTS));

        for (Seen entry : seen) {
            if (found.size() >= MOST_CONTACTS) {
                break;
            }

            if (this.inSight(level, entry.entity())) {
                found.add(entry.contact());
            }
        }

        warnings.sort(Comparator.comparingInt((Threat threat) -> threat.kind().ordinal()).reversed());

        this.contacts = List.copyOf(found);
        this.threats = List.copyOf(warnings.subList(0, Math.min(warnings.size(), MOST_THREATS)));
    }

    /**
     * その相手との間に、地面や建物が挟まっていないか。
     *
     * <p><b>中心から中心へ引く。</b> 機体の原点は胴体の腹にあり、車両のそれは車体の底にある——地面
     * すれすれの点から撃った線は、自分の乗っている地面をかすめて自分自身の足元で止まりうる。箱の中心
     * どうしなら、両端は必ず物体の内側にある。
     *
     * <p><b>まだ届いていない chunk は空として読む。</b> {@link LateWorld} の窓を開けるのがそれで、
     * 開けなければ {@code Level.getBlockState} は無い chunk を<em>生成して</em>答える——数千ブロック
     * の線を1本引くたびに、tick スレッドの上でワールド生成が走ることになる（{@code LateWorldMixin} が
     * 避けている当のもの）。持っていない地面は遮らない。実際、そこに何があるかをこの側は知らない。
     *
     * <p>止めるのは当たり判定を持つブロックだけ。草も炎も看板も電波を止めない。流体も同じで、水面下の
     * 目標は水に守られない——{@code ClipContext.Fluid.NONE} がそれを言っている。
     */
    private boolean inSight(ServerLevel level, Entity target) {
        Vec3 from = this.vehicle.getBoundingBox().getCenter();
        Vec3 to = target.getBoundingBox().getCenter();
        boolean was = LateWorld.enter();

        try {
            return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, this.vehicle)).getType() == HitResult.Type.MISS;
        } finally {
            LateWorld.restore(was);
        }
    }

    /**
     * 他機がこの機体に対して何をしているか。気付いていなければ null。
     *
     * <p>相手のシーカーはレーダーより重い。スコープに載っているのは今日の午後の話だが、シーカーに入って
     * いるのは次の数秒の話。
     *
     * <p><b>ただし熱源シーカーは聞こえない。</b> 警戒受信機は「見る」のではなく「聞く」装置であり、聞ける
     * のは相手が出している物だけだ。レーダーシーカーは電波を出して返りを待つので、照射された側にはそれが
     * 届く。赤外線ヘッドは受動素子で、何も出さずにこちらの排気を眺めているだけなので、出ていない物を聞き
     * 取る方法は無い。実機の警戒受信機が IR ミサイルのロックを教えないのはこれが理由であり、赤外線誘導弾
     * が怖いのもこれが理由だ。
     *
     * <p><b>飛んできた物は別。</b> 発射されたミサイルは {@link #sweep} が直接見つけており、こちらの経路を
     * 通らない。撃たれたことは撃たれた瞬間に分かる——分からないのは撃たれる前の数秒だけだ。フレアを撒く
     * 判断はそこから始まる。
     */
    @Nullable
    private Threat.Kind attentionFrom(VehicleEntityBase other) {
        TargetLock lock = other.lock();

        if (lock != null && lock.target() == this.vehicle && audible(lock.seeker())) {
            return lock.isLocked() ? Threat.Kind.LOCK : Threat.Kind.SEARCH;
        }

        return other.getSensors().paints(this.vehicle) ? Threat.Kind.SEARCH : null;
    }

    /**
     * そのシーカーが、捉えている相手に自分の存在を知らせてしまうか。
     *
     * <p>電波を出す物だけ。熱を見る物は何も出さない。人が据える3つ——光点・座標・視線——はそもそもここへ
     * 来ない（{@code WeaponMounts.seekerOf} がシーカーとして扱わない）が、来たとしても答えは同じだ。
     */
    private static boolean audible(@Nullable WeaponDefinition.Guidance.Seeker seeker) {
        return seeker == WeaponDefinition.Guidance.Seeker.RADAR;
    }

    /**
     * 世界にある物。ほぼ全部。
     *
     * <p><b>型の名簿を持たない。</b> 以前はここが「機体・地上車両・標的ドローン・徒歩の人間・飛翔中の
     * ミサイル」という列挙で、それ以外——牛も、ボートも、トロッコも、他 MOD が足した何かも——レーダーに
     * とって存在しなかった。名簿が要ると、撃たれ得る物を1つ足すたびにこことは別に5か所を巡ることに
     * なる（{@code targetable-entities-are-name-listed}）。レーダーが答えるのは「そこに物があるか」で
     * あって「それが何か」ではないので、既定を<em>載せる</em>側に置いてある。
     *
     * <p>落とすのは、<b>スコープに1行を割く意味が無い物</b>だけ:
     *
     * <ul>
     *   <li><b>機体自身の当たり判定箱</b>（{@link VehiclePart}）。それは機体そのものであり、機体は既に
     *       1件として載っている。落とさなければ空母1隻が接触80件になる</li>
     *   <li><b>何かに乗っている物</b>。乗員も積み荷も、運んでいる機体として既に載っている</li>
     *   <li><b>ミサイル以外の飛翔物</b>。機関砲弾・無誘導ロケット・撒いたフレアと金属箔は、数の暴力で
     *       枠を埋める——毎秒100発出る機関砲は、引き金を引いている間じゅうスコープを自分の弾で真っ白に
     *       する。そして<em>本当の脅威はその中の1本</em>、こちらへ向かって曲がってくるミサイルだ。
     *       だから飛んでいる物のうち載せるのは {@link RocketEntity#isInterceptable() ミサイル}
     *       だけにする。{@code TargetLock} がロック候補を選ぶ判定とわざと同じ物を使っている</li>
     *   <li><b>自分が撃ったミサイル</b>。同上。自分の弾に照準を渡す装置に用は無い</li>
     *   <li><b>残骸</b>。そこにあるし金属でできてもいるが、撃墜された物を全部映し続けるスコープは
     *       戦えない目標で埋まり、戦える1つがその中に紛れる</li>
     *   <li><b>指示点のマーカー</b>（{@link DesignationEntity}）。地面に当たっている光の点であって
     *       物体ではない——見えず、撃てず、ぶつかれない。レーダーに映る面を持たない</li>
     * </ul>
     *
     * <p>スペクテイターは世界に居ない。それ以外——動物も、ボートも、トロッコも、他 MOD の何かも——は
     * 全部載る。
     */
    private boolean worthLookingAt(Entity candidate) {
        if (!candidate.isAlive() || candidate instanceof VehiclePart
                || candidate instanceof DesignationEntity || candidate.isPassenger()) {
            return false;
        }

        // 飛んでいる物で載るのはミサイルだけ。しかも自分が撃った物は載らない。
        if (candidate instanceof VehicleProjectile shot) {
            return shot instanceof RocketEntity missile && missile.isInterceptable()
                    && !missile.wasFiredBy(this.vehicle);
        }

        // 撒いた物も同じ理由で落とす。デコイが仕事をするのはスコープの上ではなくシーカーの中だ
        // （{@code RocketEntity.checkDecoys} と {@code TargetLock.screened}）。
        if (candidate instanceof CountermeasureEntity) {
            return false;
        }

        if (candidate instanceof VehicleEntityBase machine) {
            return !machine.isWrecked();
        }

        return !(candidate instanceof Player player) || !player.isSpectator();
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
        return this.vehicle.getAviator() instanceof ServerPlayer player ? player : null;
    }
}
