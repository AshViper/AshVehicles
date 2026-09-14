package com.ashvehicles.ai;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.ashvehicles.ai.air.AirLoadout;
import com.ashvehicles.ai.air.Delivery;
import com.ashvehicles.ai.air.FlightControl;
import com.ashvehicles.ai.air.Ground;
import com.ashvehicles.ai.air.KillScore;
import com.ashvehicles.ai.combat.Ballistics;
import com.ashvehicles.ai.core.AiBudget;
import com.ashvehicles.ai.core.AiDirector;
import com.ashvehicles.ai.debug.PilotSnapshot;
import com.ashvehicles.ai.decision.TacticalAction;
import com.ashvehicles.ai.learning.AiVersion;
import com.ashvehicles.ai.log.PilotRecorder;
import com.ashvehicles.ai.objective.CaptureState;
import com.ashvehicles.ai.objective.ObjectiveState;
import com.ashvehicles.ai.perception.DamageMemory;
import com.ashvehicles.ai.perception.LineOfSight;
import com.ashvehicles.ai.perception.WeaponReach;
import com.ashvehicles.ai.role.Roles;
import com.ashvehicles.ai.role.TacticalProfile;
import com.ashvehicles.ai.role.VehicleRole;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.AircraftInput;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.RocketEntity;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.match.Bots;
import com.ashvehicles.match.MatchState;
import com.ashvehicles.match.MatchTeam;
import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.weapon.TargetLock;
import com.ashvehicles.weapon.WeaponDefinition;
import com.ashvehicles.weapon.WeaponMounts;
import com.google.gson.JsonObject;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * 航空機1機の AI。地上車両の {@link GroundPilot} と同じく機体に付く操縦役で、出すのは人のパイロットが毎 tick 送って
 * くるはずだった操縦入力（{@link AircraftInput}）1つだけ。飛行モデルも兵装もシーカーも、人が飛ばしている時と同じ
 * 経路を通る（[[bots-are-a-pilot-object-on-the-vehicle]]）。
 *
 * <p><b>拠点は取らない。</b> 拠点は地上の円で、飛んでいる AI は数えない（{@code Deathmatch.capture}）。仕事は地上の
 * 戦いを空から助けること——拠点の近くの敵を、内蔵の機関砲と自由落下爆弾で撃つ（武装は {@link AirLoadout}）。持ち場の
 * 枠（{@code ObjectiveBoard}）にも入らず、見る場所は自分で選ぶ（{@link #focus}）。
 *
 * <p><b>狙う相手は撃破を稼げる順</b>（{@link KillScore}、2026-09-13 の指示）。残り耐久・機首からの角度と距離・手持ちの
 * 兵装の効き・弾の残り・相手の周りの防空・同じ相手を狙う僚機から1分あたりの見込み撃破を出し、10 tick ごとに選び直す。
 * 倒したら次の tick に選び直し、次の相手が前にいれば抜け切るのを待たずに向かう。撃ちに入っている間は少し良いだけの相手へ
 * 乗り換えない。見込みはその相手に実際に与えた打撃で直す（{@link #learned}）。
 *
 * <p><b>固定翼機は航過で撃つ。</b> 撃つ高さで目標へ向かい（{@link Phase#TRANSIT} → {@link Phase#RUN}）、兵装ごとの
 * やり方で撃ち、抜けて距離を取り（{@link Phase#EXTEND}）、また戻る。<b>回転翼機は離れて浮いて撃つ。</b> 機関砲の届く
 * 距離に止まり、機首を向けて撃つ。舵は人のマウス操縦と同じ式（{@link FlightControl}）。
 *
 * <p><b>空に敵の機体がいれば、空対空ミサイルがあるうちはそれを先に落とす</b>（2026-09-13 の指示）。空対空ミサイルは
 * シーカーに掴ませてから、機関砲は見越しの点へ機首を置いて撃ち（{@link #intercept}）、すれ違ったら少し抜けて向き直る。
 * ミサイルを使い切って機関砲と爆弾だけになれば地上を先にする。使った物は出撃ごとに数えて残す（{@link #fired}、飛行の記録
 * {@link #describeFlight}）。<b>追ってくるミサイルは、囮を撒き、視線に直角に逃げて避ける</b>（{@link #evade}）。
 *
 * <p><b>地面に当たらないことが何より先。</b> 毎 tick、進む先の地面（読める chunk だけ、{@link Ground}）と降りている
 * 速さから引き起こしを決め、そのときは撃つのをやめて上る。撃つ物が尽きたら自陣の方へ抜け、撃った物が着くのを
 * 待って自爆し、出直す（{@link Phase#SPENT}、2026-09-13 の指示）——弾薬箱を運んでくる者はいない。
 */
public final class AirPilot extends BotPilot {
    // ------------------------------------------------------------------
    // 固定翼機
    // ------------------------------------------------------------------

    /** 待つ・移る高さ（その先の一番高い地面から、ブロック）。 */
    private static final double CRUISE_HEIGHT = 260.0;

    /** 機関砲で撃ちに入る高さ（目標の地面から、ブロック）。 */
    private static final double RUN_HEIGHT = 320.0;

    /** 爆弾を放す高さ（目標の地面から、ブロック）。 */
    private static final double BOMB_HEIGHT = 150.0;

    /** 持ち場の上空で回る円の半径（ブロック）。 */
    private static final double LOITER = 700.0;

    /** 目標を探す距離と、航過に入る距離（ブロック）。 */
    private static final double SEARCH = 5000.0;
    private static final double ENGAGE = 4000.0;

    /** 機関砲で、届く距離より手前から降り始める余裕（ブロック）。 */
    private static final double DIVE_LEAD = 700.0;

    /** 機関砲で地上を撃ってよい距離の上限と、そこまで詰めたら抜ける距離（ブロック）と、機首のずれ（度）。 */
    private static final double GUN_REACH = 1100.0;
    private static final double GUN_CLOSE = 320.0;
    private static final double GUN_CONE = 1.5;

    /** 電波誘導弾のロックを押す、シーカーの視野に対する割合と、押し直すまでの間（tick）。 */
    private static final double LOCK_SHARE = 0.7;
    private static final int LOCK_GAP = 12;

    /** 爆弾が横へ外れてよい、爆風の半径に対する割合。 */
    private static final double BLAST_SHARE = 0.8;

    /** 爆弾を放してよい高さの下限（ブロック）とバンクの上限（度）。 */
    private static final double BOMB_FLOOR = 60.0;
    private static final float LEVEL_BANK = 12.0F;

    /** 抜けてから次の航過に入るまでに取る距離（ブロック）と、最短の長さ（tick）と、そのときの速さの倍率。 */
    private static final double EXTEND = 1800.0;
    private static final int EXTEND_TICKS = 160;
    private static final double EXTEND_SPEED = 1.15;

    /**
     * 地上の相手を撃つのに要る距離（ブロック）。これより近い相手は一度抜けて向き直る（{@link KillScore#reachTicks}）。
     * 抜けている途中で、次の相手がこれより遠くの前方にいれば、抜け切るのを待たずに向かう。
     */
    private static final double SETUP = 900.0;

    /** 抜け切るのを待たずに次の相手へ向かうまでの最短の長さ（tick）と、次の相手が前にあると見なす角（度）。 */
    private static final int CHAIN_TICKS = 40;
    private static final double CHAIN_CONE = 60.0;

    /** 地面との余裕（ブロック）と、降りている速さを余裕へ足す長さ（tick）。 */
    private static final double SAFE_HEIGHT = 60.0;
    private static final double SINK_TICKS = 35.0;

    /** 進む先の地面を見る長さ（tick ぶんの距離）、その上下限、見る刻み、移る先を見る長さ（ブロック）。 */
    private static final double LOOK_TICKS = 60.0;
    private static final double LOOK_LEAST = 200.0;
    private static final double LOOK_MOST = 900.0;
    private static final double LOOK_STEP = 32.0;
    private static final double SCAN = 1200.0;

    /** 地面が近いときに上る角（度）と、そのときのバンクの上限（度）。 */
    private static final double CLIMB_OUT = 25.0;
    private static final float CAREFUL_BANK = 20.0F;

    /** 普段のバンクの上限（度）。人のマウス操縦と同じ。 */
    private static final float BANK = 75.0F;

    /** 高さの差1ブロックあたりの上り下りの角（度）と、その上限。 */
    private static final double CLIMB_PER_BLOCK = 0.15;
    private static final double MOST_CLIMB = 20.0;

    /** 失速の手前と見なす速さ（失速速度の倍率）と迎え角（失速角の割合）、そのときの降下角（度）とバンク（度）。 */
    private static final double STALL_MARGIN = 1.3;
    private static final double AOA_SHARE = 0.85;
    private static final double STALL_DIVE = 3.0;
    private static final float STALL_BANK = 30.0F;

    /** 求める速さをこれだけ超えたらエアブレーキ。 */
    private static final double AIRBRAKE = 1.35;

    /**
     * 巡航の速さ。失速速度の倍率を、下限・上限（ブロック/tick）と最高速度の割合で切る。
     *
     * <p>速さは実スペックそのもの（[[aircraft-speeds-are-the-real-specs]]）なので、最高速度で飛べば旋回の半径は
     * km 単位になり、会場の上に留まれない。巡航を失速の3倍弱に抑えるのはそのため。
     */
    private static final double CRUISE_STALL = 2.8;
    private static final double CRUISE_LEAST = 3.5;
    private static final double CRUISE_MOST = 6.0;
    private static final double CRUISE_SHARE = 0.7;

    // ------------------------------------------------------------------
    // 回転翼機
    // ------------------------------------------------------------------

    /** 浮く高さ（その先の一番高い地面から、ブロック）。 */
    private static final double ROTOR_HEIGHT = 45.0;

    /** 撃ちに行く距離（ブロック）。 */
    private static final double ROTOR_ENGAGE = 3000.0;

    /** 地上の目標から離れて止まる距離（ブロック）と、止まったと見なす幅。 */
    private static final double ROTOR_STANDOFF = 550.0;
    private static final double ROTOR_BAND = 150.0;

    /** 持ち場の中心からこれだけ離れて待つ（ブロック）と、着いたと見なす距離。 */
    private static final double ROTOR_LOITER = 400.0;
    private static final double ROTOR_SETTLE = 60.0;

    /** 行き先の手前で速さを落とし始める距離（ブロック）と、這う速さ・巡航の速さの上限（ブロック/tick）。 */
    private static final double ROTOR_SLOWING = 70.0;
    private static final double ROTOR_CRAWL = 0.15;
    private static final double ROTOR_CRUISE = 1.6;

    /** 前の地面を見る長さと刻み（ブロック）、傾けてよい角（度）。 */
    private static final double ROTOR_LOOK = 160.0;
    private static final double ROTOR_STEP = 16.0;
    private static final float ROTOR_TILT = 15.0F;

    /** ホバリングで止めてよい速さ（ブロック/tick）。 */
    private static final double HOVER_SPEED = 0.5;

    // ------------------------------------------------------------------
    // 共通
    // ------------------------------------------------------------------

    /** 目標を選び直す間隔（tick）。倒したか見失ったときは待たない。 */
    private static final int RETARGET = 10;

    /** 戦域の縁からこれだけ出たら、持ち場より先に会場へ戻る（ブロック）。出撃は旗の後方1000ブロックから。 */
    private static final double LEASH = 1500.0;

    /** 拠点の近くの敵と数える、円の外側の余裕（ブロック）。 */
    private static final double OBJECTIVE_MARGIN = 48.0;

    /** 撃つ物が尽きてから自爆するまで（tick）。撃ったミサイルと爆弾が、撃った機体のいるうちに着くように。 */
    private static final int SCUTTLE_AFTER = 200;

    /** 追ってくるミサイルに対抗手段を撒き、避け始める距離と、撃ちに行くのをやめる距離（ブロック）。 */
    private static final double WARNED = 1500.0;
    private static final double BREAK = 1000.0;
    private static final int MOST_INBOUND = 8;

    /** 固定翼機がミサイルを避けるときに降りる角（度）。 */
    private static final double EVADE_DIVE = 10.0;

    /** 回転翼機がミサイルを避けて横へ逃げる先の距離（ブロック）と、そのときの高さ（浮く高さの割合）。 */
    private static final double ROTOR_EVADE = 200.0;
    private static final double ROTOR_EVADE_HEIGHT = 0.6;

    /** 最後の視線を覚える長さと、予算切れのときに使ってよい齢（tick）。 */
    private static final int CLEAR_TICKS = 5;
    private static final int CLEAR_STALE = 20;

    /** 可視化に出す行き先を、機首の向きのこの先に置く（ブロック）。 */
    private static final double STEERING_REACH = 200.0;

    /** 撃ちに入っていると見なす、爆撃の相手までの水平の距離（ブロック）と、相手が機首か進む向きの前にある角（度）。 */
    private static final double BOMB_COMMIT = 1500.0;
    private static final double COMMIT_CONE = 25.0;

    /** 相手ごとの当たりを覚えている長さ（tick）。 */
    private static final long TALLY_TICKS = 2400L;

    // ------------------------------------------------------------------
    // 空の相手
    // ------------------------------------------------------------------

    /** 空の相手を探す距離（ブロック）と、近くの敵機を自分への脅威と数える距離。 */
    private static final double AIR_SEARCH = 6000.0;
    private static final double AIR_THREAT = 3000.0;

    /** 機関砲で空の相手を撃ってよい距離（ブロック）と、機首のずれ（度）。 */
    private static final double AIR_GUN_REACH = 900.0;
    private static final double AIR_GUN_CONE = 2.5;

    /** 空対空ミサイルを撃ってよい最短の距離（ブロック）と、次の1発までの間（tick）。 */
    private static final double AAM_CLOSE = 250.0;
    private static final int AAM_GAP = 60;

    /** すれ違ったと見なす距離（ブロック）と、すれ違った後に真っ直ぐ抜ける長さ（tick）。 */
    private static final double MERGE = 150.0;
    private static final int MERGE_TICKS = 60;

    /** 空の相手を追うときに、相手の速さへ足す速さ（ブロック/tick）。 */
    private static final double CHASE_MARGIN = 1.0;

    /** 浮いている回転翼機が、空の相手へ機首を上げ下げしてよい角（度）。 */
    private static final double ROTOR_AIR_PITCH = 20.0;

    /** 何をしているか。 */
    private enum Phase {
        /** 持ち場へ飛ぶか、その上空で待つ。 */
        TRANSIT,
        /** 目標へ向かって撃つ。 */
        RUN,
        /** 撃ち終えて抜ける。距離を取ってから次の航過に入る。 */
        EXTEND,
        /** 撃つ物が尽きた。自陣の方へ抜け、撃った物が着いたら自爆する。 */
        SPENT
    }

    /** 航過1回で使う兵装。 */
    private record Arms(ResourceLocation id, WeaponDefinition weapon, AirLoadout.Store store) {
    }

    /** 1つの相手に自分が与えた打撃と、撃った航過の数。見込みを実際の当たりで直すため（{@link #learned}）。 */
    private static final class Tally {
        double dealt;
        int passes;
        long touched;
    }

    /** この tick の操作。各段階が書き、最後に入力1つへ畳む。 */
    private static final class Orders {
        Vec3 heading = new Vec3(0.0, 0.0, 1.0);
        double speed;
        float bank = BANK;
        boolean hold;
        boolean pulse;
        boolean lock;
        boolean flare;
        boolean chaff;

        /** 回転翼機が機首で狙う線。無ければ速さと向きで浮く。 */
        @Nullable
        Vec3 aim;
    }

    private final AircraftEntity aircraft;
    private final AiVersion version;
    private final TacticalProfile profile;
    private final KillScore.Weights weights;
    private final DamageMemory damage = new DamageMemory();
    private final PilotRecorder recorder;
    private final FlightControl flight = new FlightControl();
    private final boolean rotorcraft;
    private final double cruise;
    private final List<RocketEntity> inbound = new ArrayList<>();
    private final Int2ObjectOpenHashMap<Tally> tallies = new Int2ObjectOpenHashMap<>();

    /** この出撃で撃った数。分類ごと（機関砲は発数、爆弾とミサイルは本数）。 */
    private final EnumMap<AirLoadout.Store, Integer> fired = new EnumMap<>(AirLoadout.Store.class);

    /** 追ってくるミサイルのうち、警告の距離に入っている一番近い1本。{@link #dodge} が毎 tick 書く。 */
    @Nullable
    private RocketEntity chaser;

    private Phase phase = Phase.TRANSIT;
    private int phaseTicks;
    private int retarget;

    @Nullable
    private Entity target;

    /** 今の相手の点数（{@link KillScore#score}）。記録のため。 */
    private double targetScore;

    /** 空対空ミサイルが残っているか。 */
    private boolean missiles;

    @Nullable
    private Arms arms;

    @Nullable
    private ObjectiveState focus;

    @Nullable
    private Vec3 steering;

    /** この航過で出た弾の数（残弾の減りで数える）と、最後に減った tick、前の tick に見た残弾。 */
    private int shots;
    private int shotAt = -AAM_GAP;
    private int ammoSeen;

    /** 今の航過の相手と、最後に撃ち終えた航過の相手（エンティティ番号、無ければ -1）。 */
    private int runTarget = -1;
    private int passTarget = -1;

    private boolean triggerWas;
    private boolean lockWas;
    private int lockAt = -LOCK_GAP;
    private boolean gearChecked;
    private double lastGround = Double.NaN;

    private int clearTick;
    private int clearTarget = -1;
    private boolean clearResult;

    AirPilot(AircraftEntity aircraft, String team, AiVersion version) {
        super(aircraft, team);
        this.aircraft = aircraft;
        this.version = version;
        this.profile = TacticalProfile.tuned(VehicleRole.SUPPORT, version.parameters());
        this.weights = KillScore.Weights.of(version.parameters());
        this.recorder = new PilotRecorder(this);
        this.rotorcraft = aircraft.isRotorcraft();
        this.cruise = this.rotorcraft
                ? Math.min(aircraft.getMaxSpeed() * CRUISE_SHARE, ROTOR_CRUISE)
                : Mth.clamp(aircraft.getStallSpeed() * CRUISE_STALL, CRUISE_LEAST,
                        Math.max(CRUISE_LEAST, Math.min(CRUISE_MOST, aircraft.getMaxSpeed() * CRUISE_SHARE)));
        // 目標を選ぶ tick を個体ごとにずらす。
        this.retarget = Math.floorMod(aircraft.getId(), RETARGET);
    }

    @Override
    protected void think() {
        MinecraftServer server = this.aircraft.getServer();

        if (server == null) {
            return;
        }

        long now = this.aircraft.level().getGameTime();
        MatchState match = MatchState.of(server);
        Quaternionf attitude = this.aircraft.getAttitude(1.0F);

        this.flight.sense(attitude);
        this.phaseTicks++;

        // 空から出た機体は脚が出たまま。抗力で遅いだけなので最初に畳む。畳めない脚は機体の側が断る。
        if (!this.gearChecked && !this.aircraft.onGround()) {
            this.gearChecked = true;

            if (this.aircraft.isGearDown()) {
                this.aircraft.toggleGear();
            }
        }

        List<Arms> ready = this.ready();

        this.missiles = false;

        for (Arms candidate : ready) {
            this.missiles |= candidate.store() == AirLoadout.Store.AAM;
        }

        // 倒したか見失った。次の相手を待たずにこの tick で選ぶ——撃破を続けて稼ぐ。
        if (this.target != null && !this.alive(this.target)) {
            this.target = null;
            this.retarget = 0;
        }

        if (--this.retarget <= 0) {
            this.retarget = RETARGET;
            this.focus = this.focus();
            this.target = this.pick(server, match, now, ready);
        }

        boolean armed = this.armed(ready);

        if (!armed && this.phase != Phase.SPENT) {
            this.enter(Phase.SPENT);
        } else if (armed && this.phase == Phase.SPENT) {
            // 空対空ミサイルだけが残っていた機体の前に、空の相手が現れた。
            this.enter(Phase.TRANSIT);
        }

        if (this.phase == Phase.SPENT && this.phaseTicks >= SCUTTLE_AFTER) {
            Bots.scuttle(this.aircraft);
        }

        Orders orders = new Orders();

        this.dodge(orders);
        this.aircraft.setInput(this.rotorcraft ? this.hover(server, match, attitude, ready, orders)
                : this.fly(server, match, attitude, ready, orders));
    }

    @Override
    protected void idle() {
        this.aircraft.setInput(AircraftInput.NONE);
    }

    // ------------------------------------------------------------------
    // 固定翼機
    // ------------------------------------------------------------------

    private AircraftInput fly(MinecraftServer server, MatchState match, Quaternionf attitude, List<Arms> ready,
            Orders orders) {
        Vec3 here = this.aircraft.position();
        Vec3 motion = this.aircraft.getDeltaMovement();
        double speed = motion.length();

        orders.heading = this.horizontal(motion);
        orders.speed = this.cruise;

        switch (this.phase) {
            case TRANSIT -> this.transit(orders, server, match, ready);
            case RUN -> this.run(orders, attitude, ready);
            case EXTEND -> this.extend(orders);
            case SPENT -> this.withdraw(orders, server, match);
        }

        this.evade(orders, here, motion);
        this.keepAloft(orders, here, motion, speed);

        FlightControl.Stick stick = this.flight.point(attitude, orders.heading, false, orders.bank);
        float pitch = stick.pitch();

        // 失速角の手前では引かない。引けば主翼が飛ぶのをやめ、機首はそのまま地面へ向く。
        if (pitch > 0.0F
                && this.aircraft.getAngleOfAttack() > this.aircraft.getStats().wing().stallAngle() * AOA_SHARE) {
            pitch = 0.0F;
        }

        boolean stalling = speed < this.aircraft.getStallSpeed() * STALL_MARGIN;
        float throttle = FlightControl.throttle(speed, stalling ? this.aircraft.getMaxSpeed() : orders.speed,
                this.aircraft.getThrottle());

        this.steering = here.add(orders.heading.scale(STEERING_REACH));

        return new AircraftInput(pitch, stick.roll(), stick.yaw(), throttle,
                !stalling && speed > orders.speed * AIRBRAKE, this.trigger(orders), orders.flare, orders.chaff,
                this.press(orders), false);
    }

    /** 目標があればそこへ、無ければ持ち場の上空を回る。目標が近ければ航過に入る。 */
    private void transit(Orders orders, MinecraftServer server, MatchState match, List<Arms> ready) {
        Vec3 here = this.aircraft.position();

        this.openBay(false);

        if (this.target != null) {
            Vec3 at = this.target.position();
            Arms chosen = flatDistance(here, at) <= (aloft(this.target) ? AIR_SEARCH : ENGAGE)
                    ? this.choose(ready, this.target) : null;

            orders.heading = this.toward(at, this.clearance(here, at) + CRUISE_HEIGHT);

            if (chosen != null) {
                this.arms = chosen;
                this.enter(Phase.RUN);
            }

            return;
        }

        Vec3 centre = this.watchPoint(server, match);

        orders.heading = this.orbit(here, centre, this.clearance(here, centre) + CRUISE_HEIGHT);
    }

    /** 航過。兵装ごとのやり方で目標を撃つ。空の相手には {@link #intercept}。 */
    private void run(Orders orders, Quaternionf attitude, List<Arms> ready) {
        Vec3 here = this.aircraft.position();
        Vec3 motion = this.aircraft.getDeltaMovement();
        Entity quarry = this.target;
        WeaponMounts mounts = this.aircraft.getWeapons();

        if (quarry == null) {
            this.enter(Phase.EXTEND);

            return;
        }

        boolean airborne = aloft(quarry);
        boolean switched = quarry.getId() != this.runTarget;

        // 相手を乗り換えたか、空の相手に使っていた物が尽きるか合わなくなったら（空へ上がった相手、ミサイルを撃ち尽くした
        // 後の機関砲）、航過を切らずに選び直す。地上の同じ相手は1回の航過で1種類——尽きたら抜けて回り直す。
        if (switched || (airborne && (this.arms == null || mounts.ammoOf(this.arms.id()) <= 0
                || !this.suits(this.arms, quarry)))) {
            Arms next = this.choose(ready, quarry);

            if (next != null) {
                this.arms = next;
                this.enter(Phase.RUN);
            }
        }

        Arms using = this.arms;

        if (using == null || mounts.ammoOf(using.id()) <= 0 || !this.suits(using, quarry)) {
            this.enter(Phase.EXTEND);

            return;
        }

        this.countShots(using, mounts.ammoOf(using.id()));

        if (!using.id().equals(mounts.selected())) {
            mounts.select(using.id());
        }

        // 倉の中の物は、扉が開ききるまで撃てない（{@code WeaponMounts.shutIn}）。航過に入った時点で開けておく。
        this.openBay(true);

        Vec3 at = quarry.getBoundingBox().getCenter();
        Vec3 nose = Attitude.nose(attitude);
        double floor = quarry.getY();
        WeaponDefinition.Projectile round = Definitions.round(using.weapon(), mounts.selectedAmmunition());

        if (airborne) {
            this.intercept(orders, using, quarry, here, motion, nose, at, round);
        } else if (using.store() == AirLoadout.Store.BOMB) {
            this.bomb(orders, quarry, here, motion, attitude, at, floor, round);
        } else {
            this.strafe(orders, quarry, here, motion, nose, at, floor, round);
        }
    }

    /**
     * 機関砲。撃つ高さで寄り、届く距離の手前から弾道の上へ機首を置いて降り、揃ったら撃つ。
     *
     * <p>引き起こしは降りている速さの分だけ早くする——速く降りている機体ほど、機首を上げてから沈むのが止まるまでに
     * 高さを失う。
     */
    private void strafe(Orders orders, Entity quarry, Vec3 here, Vec3 motion, Vec3 nose, Vec3 at, double floor,
            WeaponDefinition.Projectile round) {
        double slant = here.distanceTo(at);
        double reach = Math.min(round.range() > 0.0F ? round.range() : Double.MAX_VALUE, GUN_REACH);

        if (flatDistance(here, at) > reach + DIVE_LEAD) {
            orders.heading = this.toward(at, floor + RUN_HEIGHT);

            return;
        }

        if (here.y - floor < SAFE_HEIGHT + Math.max(0.0, -motion.y) * SINK_TICKS || slant < GUN_CLOSE) {
            this.enter(Phase.EXTEND);

            return;
        }

        // 砲の弾は機首方向の機体の速度を持って出る（{@code WeaponMounts.fireRound}）。
        Vec3 point = Ballistics.aim(here, at, drift(quarry), round, round.speed() + Math.max(0.0, motion.dot(nose)));

        orders.heading = (point == null ? at : point).subtract(here).normalize();
        orders.hold = point != null && slant <= reach && degrees(nose, orders.heading) <= GUN_CONE
                && this.clearShot(here, quarry);
    }

    /**
     * 自由落下爆弾。目標の地面から {@value #BOMB_HEIGHT} の高さで水平に目標へ向かい、今放せば落ちる点（{@link Delivery}）
     * が目標に届いた tick に放す。横へ外れてよいのは爆風の半径の {@value #BLAST_SHARE} 倍まで。1回の航過で
     * {@value KillScore#BOMBS_PER_PASS} 発。
     */
    private void bomb(Orders orders, Entity quarry, Vec3 here, Vec3 motion, Quaternionf attitude, Vec3 at,
            double floor, WeaponDefinition.Projectile round) {
        if (this.shots >= KillScore.BOMBS_PER_PASS) {
            this.enter(Phase.EXTEND);

            return;
        }

        // 放すのはこの tick の移動の後なので、その位置から落とす。
        Delivery.Impact impact = Delivery.drop(here.add(motion),
                Delivery.release(motion, Attitude.up(attitude), round), round, floor);
        Vec3 aim = impact == null ? at : at.add(drift(quarry).scale(impact.ticks()));

        orders.heading = this.toward(aim, floor + BOMB_HEIGHT);

        if (impact == null) {
            return;
        }

        Delivery.Miss miss = Delivery.miss(impact.at(), aim, motion);

        // 放す点を越えた。回り直す。
        if (miss.along() < -Math.max(motion.horizontalDistance() * 4.0, 8.0)) {
            this.enter(Phase.EXTEND);

            return;
        }

        orders.pulse = miss.along() <= 0.0 && Math.abs(miss.across()) <= Math.max(4.0, round.explosion() * BLAST_SHARE)
                && here.y - floor >= BOMB_FLOOR && Math.abs(Attitude.bank(attitude)) <= LEVEL_BANK;
    }

    /**
     * 空の相手。空対空ミサイルは機首を向けてシーカーに掴ませ、掴んで固まったら撃つ。機関砲は見越しの点へ機首を置き、
     * 揃ったら撃つ。相手より少し速く飛び、すれ違ったら少し真っ直ぐ抜けて向き直る。
     *
     * <p>地面は見ない——{@link #keepAloft} が毎 tick この後に通り、低く飛ぶ相手を追って降りすぎた機体を引き起こす。
     */
    private void intercept(Orders orders, Arms using, Entity quarry, Vec3 here, Vec3 motion, Vec3 nose, Vec3 at,
            WeaponDefinition.Projectile round) {
        Vec3 theirs = drift(quarry);
        double slant = here.distanceTo(at);

        orders.speed = Mth.clamp(theirs.length() + CHASE_MARGIN, this.cruise, this.aircraft.getMaxSpeed());

        if (slant < MERGE) {
            this.enter(Phase.EXTEND);

            return;
        }

        if (using.store() == AirLoadout.Store.GUN) {
            Vec3 point = Ballistics.aim(here, at, theirs, round, round.speed() + Math.max(0.0, motion.dot(nose)));
            double reach = Math.min(round.range() > 0.0F ? round.range() : Double.MAX_VALUE, AIR_GUN_REACH);

            orders.heading = (point == null ? at : point).subtract(here).normalize();
            orders.hold = point != null && slant <= reach && degrees(nose, orders.heading) <= AIR_GUN_CONE
                    && this.clearShot(here, quarry);

            return;
        }

        WeaponDefinition.Guidance guidance = using.weapon().guidance().orElseThrow();

        orders.heading = at.subtract(here).normalize();

        if (slant <= this.seekerReach(guidance, quarry) && slant >= AAM_CLOSE && this.age - this.shotAt >= AAM_GAP) {
            this.seek(orders, guidance, quarry, degrees(nose, orders.heading));
        }
    }

    /**
     * 抜ける。真っ直ぐ進みながら上り、目標から十分に離れたら次の航過へ。空の相手とすれ違った後は短い間だけ。
     *
     * <p><b>次の相手が前にいれば抜け切るのを待たない</b>——撃ち終えた相手と別の相手が、進む向きから {@value #CHAIN_CONE}
     * 度の内に {@value #SETUP} ブロックより遠くいれば、そのまま向かう。倒した後に1800ブロック離れて回り直していては、
     * 1回の出撃で稼げる撃破がその往復の分だけ減る。
     */
    private void extend(Orders orders) {
        Vec3 here = this.aircraft.position();
        Vec3 point = here.add(this.horizontal(this.aircraft.getDeltaMovement()).scale(1000.0));

        this.openBay(false);
        orders.heading = this.toward(point, this.clearance(here, point) + CRUISE_HEIGHT);
        orders.speed = this.cruise * EXTEND_SPEED;

        boolean airborne = this.target != null && aloft(this.target);
        boolean chained = !airborne && this.target != null && this.target.getId() != this.passTarget
                && this.phaseTicks >= CHAIN_TICKS && flatDistance(here, this.target.position()) >= SETUP
                && degrees(this.horizontal(this.aircraft.getDeltaMovement()),
                        this.horizontal(this.target.position().subtract(here))) <= CHAIN_CONE;

        // 空の相手は待ってくれない。すれ違った後は距離ではなく短い間だけ抜けて、向き直る。
        if (chained || (airborne ? this.phaseTicks >= MERGE_TICKS
                : this.phaseTicks >= EXTEND_TICKS
                        && (this.target == null || flatDistance(here, this.target.position()) >= EXTEND))) {
            this.enter(Phase.TRANSIT);
        }
    }

    /** 撃つ物が尽きた。自陣の旗の上空へ戻る。自爆は {@link #think} が、撃った物が着くのを待ってから。 */
    private void withdraw(Orders orders, MinecraftServer server, MatchState match) {
        Vec3 here = this.aircraft.position();
        Vec3 home = this.home(server, match);

        this.openBay(false);
        orders.heading = this.orbit(here, home, this.clearance(here, home) + CRUISE_HEIGHT);
    }

    /**
     * 追ってくるミサイルを避ける（2026-09-13 の指示）。ミサイルの視線に直角な向きのうち今の進む向きに近い側へ、全速・
     * 最大バンクで回り、少し降りる。視線に直角に飛ぶ相手が、比例航法の弾に一番大きく舵を切らせる——正面や真後ろへ
     * 逃げると、弾はほとんど曲がらずに追い付く。避けている間は撃たない。囮は {@link #dodge} が撒き、地面はこの後に
     * {@link #keepAloft} が見る。
     */
    private void evade(Orders orders, Vec3 here, Vec3 motion) {
        if (this.chaser == null) {
            return;
        }

        Vec3 beam = this.beam(here, motion, this.chaser);
        double radians = Math.toRadians(-EVADE_DIVE);

        orders.heading = new Vec3(beam.x * Math.cos(radians), Math.sin(radians), beam.z * Math.cos(radians));
        orders.bank = BANK;
        orders.speed = this.aircraft.getMaxSpeed();
        orders.hold = false;
        orders.pulse = false;
        orders.lock = false;
    }

    /** そのミサイルの視線に直角な水平の向きのうち、今の進む向きに近い側。 */
    private Vec3 beam(Vec3 here, Vec3 motion, RocketEntity missile) {
        Vec3 away = this.horizontal(here.subtract(missile.position()));
        Vec3 side = new Vec3(away.z, 0.0, -away.x);

        return side.dot(this.horizontal(motion)) >= 0.0 ? side : side.scale(-1.0);
    }

    /**
     * 地面と失速から機体を守る。どの段階の後にも通し、段階の決めた舵を上書きする。
     *
     * <p>見るのは進む先の一番高い地面と、降りている速さ。沈みながら地面へ寄っている機体は、今の高さが十分に見えても
     * 引き起こしが間に合わない。
     */
    private void keepAloft(Orders orders, Vec3 here, Vec3 motion, double speed) {
        Vec3 track = this.horizontal(motion);
        double reach = Mth.clamp(speed * LOOK_TICKS, LOOK_LEAST, LOOK_MOST);
        double ground = Math.max(this.below(here),
                Ground.highest(this.aircraft.level(), here, track, reach, LOOK_STEP, this.unknownGround()));

        if (here.y - Math.max(0.0, -motion.y) * SINK_TICKS < ground + SAFE_HEIGHT) {
            double radians = Math.toRadians(CLIMB_OUT);

            orders.heading = new Vec3(track.x * Math.cos(radians), Math.sin(radians), track.z * Math.cos(radians));
            orders.bank = CAREFUL_BANK;
            orders.speed = Math.max(orders.speed, this.cruise);
            orders.hold = false;
            orders.pulse = false;
            orders.lock = false;

            if (this.phase == Phase.RUN) {
                this.enter(Phase.EXTEND);
            }

            return;
        }

        if (speed < this.aircraft.getStallSpeed() * STALL_MARGIN) {
            Vec3 flat = this.horizontal(orders.heading);
            double radians = Math.toRadians(-STALL_DIVE);

            orders.heading = new Vec3(flat.x * Math.cos(radians), Math.sin(radians), flat.z * Math.cos(radians));
            orders.bank = Math.min(orders.bank, STALL_BANK);
        }
    }

    // ------------------------------------------------------------------
    // 回転翼機
    // ------------------------------------------------------------------

    private AircraftInput hover(MinecraftServer server, MatchState match, Quaternionf attitude, List<Arms> ready,
            Orders orders) {
        Vec3 here = this.aircraft.position();
        Vec3 motion = this.aircraft.getDeltaMovement();
        Vec3 goal;
        Vec3 face = null;
        boolean settle = false;

        if (this.phase == Phase.SPENT) {
            goal = this.home(server, match);
        } else {
            Arms chosen = this.target == null || flatDistance(here, this.target.position()) > ROTOR_ENGAGE ? null
                    : this.choose(ready, this.target);

            if (chosen != null) {
                if (this.phase != Phase.RUN || !chosen.equals(this.arms) || this.target.getId() != this.runTarget) {
                    this.arms = chosen;
                    this.enter(Phase.RUN);
                }

                Vec3 at = this.target.getBoundingBox().getCenter();
                Vec3 toward = this.horizontal(at.subtract(here));

                if (aloft(this.target)) {
                    // 空の相手には寄らない。追い付けないし、寄れば的になる。その場に浮いたまま機首を向けて撃つ。
                    goal = here;
                    face = toward;
                    settle = true;
                    this.aimRotor(orders, chosen, this.target, here, motion, attitude, at, here.distanceTo(at));
                } else {
                    double distance = flatDistance(here, at);

                    goal = new Vec3(at.x - toward.x * ROTOR_STANDOFF, here.y, at.z - toward.z * ROTOR_STANDOFF);
                    face = toward;
                    settle = Math.abs(distance - ROTOR_STANDOFF) <= ROTOR_BAND;

                    if (settle) {
                        this.aimRotor(orders, chosen, this.target, here, motion, attitude, at, distance);
                    }
                }
            } else {
                if (this.phase == Phase.RUN) {
                    this.enter(Phase.TRANSIT);
                }

                // 持ち場の真上ではなく縁で待つ。撃ち合っている戦場の真ん中に浮かべば、ただの的だ。
                Vec3 centre = this.watchPoint(server, match);

                goal = centre.add(this.horizontal(here.subtract(centre)).scale(ROTOR_LOITER));
                face = this.horizontal(centre.subtract(here));
                settle = flatDistance(here, goal) <= ROTOR_SETTLE;
            }
        }

        boolean evading = this.chaser != null;

        // 追ってくるミサイルは、視線に直角へ全速で横に逃げ、低く降りて避ける（{@link #evade} と同じ理由）。撃つのはやめる。
        if (evading) {
            goal = here.add(this.beam(here, motion, this.chaser).scale(ROTOR_EVADE));
            face = null;
            settle = false;
            orders.aim = null;
            orders.hold = false;
            orders.pulse = false;
            orders.lock = false;
        }

        double gap = flatDistance(here, goal);
        Vec3 velocity = settle || gap < 1.0 ? Vec3.ZERO
                : this.horizontal(goal.subtract(here)).scale(Math.min(this.cruise, Math.max(ROTOR_CRAWL, gap / ROTOR_SLOWING)));
        Vec3 track = velocity.lengthSqr() > 1.0E-6 ? velocity : this.horizontal(motion);
        double ground = Math.max(this.below(here),
                Ground.highest(this.aircraft.level(), here, track, ROTOR_LOOK, ROTOR_STEP, this.unknownGround()));

        // 前の地面が今の高さに迫っているなら、上り切るまで前へ出ない。
        if (here.y < ground + ROTOR_HEIGHT * 0.5) {
            velocity = velocity.scale(0.2);
        }

        FlightControl.Stick stick = orders.aim != null ? this.flight.point(attitude, orders.aim, true, 0.0F)
                : this.flight.rotor(attitude, motion, velocity, face, ROTOR_TILT);
        boolean hold = orders.aim == null && velocity.lengthSqr() < 1.0E-6 && motion.horizontalDistance() < HOVER_SPEED;

        this.steering = goal;

        return new AircraftInput(stick.pitch(), stick.roll(), stick.yaw(),
                FlightControl.collective(here.y, ground + ROTOR_HEIGHT * (evading ? ROTOR_EVADE_HEIGHT : 1.0)), false,
                this.trigger(orders), orders.flare, orders.chaff, this.press(orders), hold);
    }

    /** 浮いたまま撃つ。空対空ミサイルは機首を向けてシーカーに掴ませ、機関砲は弾道の上へ機首を置いて。 */
    private void aimRotor(Orders orders, Arms using, Entity quarry, Vec3 here, Vec3 motion, Quaternionf attitude,
            Vec3 at, double distance) {
        WeaponMounts mounts = this.aircraft.getWeapons();

        this.countShots(using, mounts.ammoOf(using.id()));

        if (!using.id().equals(mounts.selected())) {
            mounts.select(using.id());
        }

        this.openBay(true);

        Vec3 nose = Attitude.nose(attitude);

        if (using.store() == AirLoadout.Store.AAM) {
            WeaponDefinition.Guidance guidance = using.weapon().guidance().orElseThrow();

            // シーカーは機首の先の円錐で掴む。高い相手へは機首を上げるが、浮いていられる角まで。
            orders.aim = this.lookUp(at.subtract(here));

            if (distance <= this.seekerReach(guidance, quarry) && distance >= AAM_CLOSE
                    && this.age - this.shotAt >= AAM_GAP) {
                this.seek(orders, guidance, quarry, degrees(nose, at.subtract(here)));
            }

            return;
        }

        boolean airborne = aloft(quarry);
        WeaponDefinition.Projectile round = Definitions.round(using.weapon(), mounts.selectedAmmunition());
        double reach = Math.min(round.range() > 0.0F ? round.range() : Double.MAX_VALUE,
                airborne ? AIR_GUN_REACH : GUN_REACH);
        Vec3 point = Ballistics.aim(here, at, drift(quarry), round, round.speed() + Math.max(0.0, motion.dot(nose)));

        if (point == null || distance > reach) {
            return;
        }

        orders.aim = point.subtract(here).normalize();
        orders.hold = degrees(nose, orders.aim) <= (airborne ? AIR_GUN_CONE : GUN_CONE) && this.clearShot(here, quarry);
    }

    // ------------------------------------------------------------------
    // 目標と兵装
    // ------------------------------------------------------------------

    /**
     * 狙う相手。撃破を稼げる順（{@link KillScore#score}）——名簿の敵ごとに、使う兵装（{@link #choose}）での1分あたりの
     * 見込み撃破を出し、空の敵（空対空ミサイルがあるうち）には重みを掛け、自分を撃っている・空を撃てて自分に届く・
     * 拠点の近く・今の相手（撃ちに入っている間は大きく）を足して、一番高い物。
     *
     * <p>撃つ物の無い相手（{@link #choose} が何も返さない——装甲に弾かれる機銃しか無い戦車など）と、戦域の外の相手は
     * 選ばない。空の相手は戦域の少し外（{@value #LEASH}）まで追う。その間でも、戦闘空域（戦域の円の上空）から離れた相手ほど
     * 点数を下げ、内側の相手ほど上げる（{@link KillScore#airspace}）。押されている味方が支援を要請していれば、その味方を撃っている
     * 相手を上げる（航空支援、{@code team/SupportCalls}）。
     */
    @Nullable
    private Entity pick(MinecraftServer server, MatchState match, long now, List<Arms> ready) {
        if (this.team().isEmpty()) {
            return null;
        }

        Vec3 here = this.aircraft.position();
        Vec3 middle = Bots.centre(server, match);
        double arena = Bots.arenaRadius(server, match);
        List<ObjectiveState> objectives = AiDirector.brain(this.team()).board().states();
        int window = AiConfig.sight().attackedWindowTicks();
        List<Bots.Fighter> roster = Bots.combatants(server);
        List<GroundVehicleEntity> defences = this.airDefences(roster);
        Int2IntOpenHashMap wingmen = this.wingmen();
        KillScore.Flight course = this.course();
        boolean committed = this.committed();
        Entity best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        this.forget(now);

        for (Bots.Fighter fighter : roster) {
            Entity entity = fighter.entity();

            if (fighter.team().isEmpty() || this.team().equals(fighter.team()) || !this.alive(entity)) {
                continue;
            }

            boolean airborne = aloft(entity);
            Arms using = this.choose(ready, entity);

            if (using == null) {
                continue;
            }

            double search = airborne ? this.rotorcraft ? ROTOR_ENGAGE : AIR_SEARCH
                    : this.rotorcraft ? ROTOR_ENGAGE * 1.5 : SEARCH;
            double distance = flatDistance(here, entity.position());

            if (distance > search || (arena > 0.0 && flatDistance(middle, entity.position())
                    > arena + (airborne ? LEASH : OBJECTIVE_MARGIN))) {
                continue;
            }

            boolean current = entity == this.target;
            double score = KillScore.score(this.prospect(entity, using, airborne, distance, now, window, objectives,
                    defences, wingmen, course, middle, arena), course, this.weights, current, current && committed);

            if (score > bestScore) {
                bestScore = score;
                best = entity;
            }
        }

        this.targetScore = best == null ? 0.0 : bestScore;

        return best;
    }

    /**
     * その相手の見込み（{@link KillScore.Prospect}）を、名簿と兵装ファイルと自分の当たりから組む。
     *
     * @param middle 戦域の中心（{@code Bots.centre}）
     * @param arena  戦域の半径（{@code Bots.arenaRadius}）。0以下は無制限
     */
    private KillScore.Prospect prospect(Entity entity, Arms using, boolean airborne, double distance, long now,
            int window, List<ObjectiveState> objectives, List<GroundVehicleEntity> defences, Int2IntOpenHashMap wingmen,
            KillScore.Flight course, Vec3 middle, double arena) {
        Vec3 here = this.aircraft.position();
        double outside = arena > 0.0 ? flatDistance(middle, entity.position()) - arena : 0.0;
        // 押されている味方の支援の要請で、その味方を撃っている相手（航空支援、team/SupportCalls）。
        double support = AiDirector.brain(this.team()).support().airSupport(entity.getId(), now);
        int ammo = this.aircraft.getWeapons().ammoOf(using.id());
        KillScore.Round round = roundOf(using);
        KillScore.Mark mark = markOf(entity);
        double offTrack = Math.toRadians(degrees(this.horizontal(this.aircraft.getDeltaMovement()),
                this.horizontal(entity.position().subtract(here))));
        double defence = 0.0;
        boolean threatensMe = airborne && course.missiles() && distance <= AIR_THREAT;

        for (GroundVehicleEntity guard : defences) {
            double reach = WeaponReach.of(guard).rangeAgainst(true);

            if (guard == entity) {
                threatensMe = guard.distanceTo(this.aircraft) <= reach;
            } else if (guard.distanceTo(entity) <= reach) {
                defence += Roles.of(guard) == VehicleRole.AA ? 1.0 : 0.5;
            }
        }

        return new KillScore.Prospect(airborne, distance, offTrack, healthOf(entity),
                this.learned(entity, KillScore.damagePerPass(round, mark, ammo)), KillScore.passesLeft(round, mark, ammo),
                this.damage.hurtBy(entity, now, window) || this.chasedBy(entity), threatensMe, near(entity, objectives),
                wingmen.get(entity.getId()), defence, outside, arena, support);
    }

    /** 名簿の敵のうち、空を撃てる地上車両（{@code Roles.defendsAir}）。相手の周りの防空と、自分に届く脅威を数えるため。 */
    private List<GroundVehicleEntity> airDefences(List<Bots.Fighter> roster) {
        List<GroundVehicleEntity> found = new ArrayList<>();

        for (Bots.Fighter fighter : roster) {
            if (!fighter.team().isEmpty() && !this.team().equals(fighter.team())
                    && fighter.entity() instanceof GroundVehicleEntity ground && !ground.isWrecked()
                    && Roles.defendsAir(ground.getStats())) {
                found.add(ground);
            }
        }

        return found;
    }

    /** 僚機（同じ陣営の AI の航空機）が今狙っている相手ごとの数。自分を除く。 */
    private Int2IntOpenHashMap wingmen() {
        Int2IntOpenHashMap counts = new Int2IntOpenHashMap();

        for (BotPilot pilot : Bots.pilotsOf(this.team())) {
            Entity aim = pilot instanceof AirPilot && pilot != this ? pilot.target() : null;

            if (aim != null) {
                counts.addTo(aim.getId(), 1);
            }
        }

        return counts;
    }

    /** 自分の飛び方（{@link KillScore.Flight}）。速さは今の速さと巡航の大きい方。 */
    private KillScore.Flight course() {
        double speed = Math.max(this.aircraft.getDeltaMovement().length(), this.cruise);

        return new KillScore.Flight(this.rotorcraft, speed, KillScore.turnRadius(speed), EXTEND, SETUP, ROTOR_STANDOFF,
                this.missiles);
    }

    /**
     * 今の相手へ撃ちに入っているか。入っている間は、少し良いだけの相手へ乗り換えない（{@code air.target.committed}）
     * ——降り始めた機関砲の航過、投下点へ向かう爆撃、掴みかけたシーカーを捨てれば、それまでの時間が丸ごと無駄になる。
     */
    private boolean committed() {
        if (this.phase != Phase.RUN || this.target == null || this.arms == null) {
            return false;
        }

        Vec3 here = this.aircraft.position();
        Vec3 at = this.target.getBoundingBox().getCenter();

        return switch (this.arms.store()) {
            case AAM -> this.aircraft.lock().target() == this.target;
            case BOMB -> flatDistance(here, at) <= BOMB_COMMIT && degrees(this.horizontal(this.aircraft.getDeltaMovement()),
                    this.horizontal(at.subtract(here))) <= COMMIT_CONE;
            case GUN -> here.distanceTo(at) <= (aloft(this.target) ? AIR_GUN_REACH : GUN_REACH + DIVE_LEAD)
                    && degrees(this.aircraft.getNoseVector(), at.subtract(here)) <= COMMIT_CONE;
        };
    }

    /** 見込みを、その相手に実際に与えた打撃で直す。見込みを1回ぶんの航過と数えた平均。 */
    private double learned(Entity entity, double prior) {
        Tally tally = this.tallies.get(entity.getId());

        return tally == null || tally.passes <= 0 || prior <= 0.0 ? prior
                : (prior + tally.dealt) / (1.0 + tally.passes);
    }

    private Tally tally(int id) {
        Tally tally = this.tallies.get(id);

        if (tally == null) {
            tally = new Tally();
            this.tallies.put(id, tally);
        }

        return tally;
    }

    /** 古い当たりを忘れる。 */
    private void forget(long now) {
        this.tallies.values().removeIf(tally -> now - tally.touched > TALLY_TICKS);
    }

    /** その相手が、今自分を追っているミサイルを撃った物か。 */
    private boolean chasedBy(Entity entity) {
        return this.chaser != null && this.chaser.firedFrom() == entity;
    }

    /** 撃てる状態の兵装。AI が使う物（{@link AirLoadout#storeOf}）で、弾があり、要るポッドを積んでいる物。 */
    private List<Arms> ready() {
        WeaponMounts mounts = this.aircraft.getWeapons();
        List<Arms> ready = new ArrayList<>();

        for (ResourceLocation id : mounts.carried()) {
            WeaponDefinition weapon = Definitions.weapon(id);
            AirLoadout.Store store = AirLoadout.storeOf(weapon);

            if (store == null || mounts.ammoOf(id) <= 0 || mounts.missingPod(weapon) != null
                    || (this.rotorcraft && store == AirLoadout.Store.BOMB)) {
                continue;
            }

            ready.add(new Arms(id, weapon, store));
        }

        return ready;
    }

    /** まだ仕事ができるか。地上へ使う物（機関砲・爆弾）が残っているか、空の相手を追っていて空対空ミサイルが残っているか。 */
    private boolean armed(List<Arms> ready) {
        boolean hunting = this.target != null && aloft(this.target);

        for (Arms candidate : ready) {
            if (candidate.store() != AirLoadout.Store.AAM || hunting) {
                return true;
            }
        }

        return false;
    }

    /**
     * その相手に使う兵装。分類の順は {@link #order}、その相手に効かない物（{@link KillScore#damagePerPass} が0——装甲に
     * 弾かれる機銃）は飛ばし、同じ分類なら吊りたい順（{@link AirLoadout#worth}）。
     */
    @Nullable
    private Arms choose(List<Arms> ready, Entity quarry) {
        KillScore.Mark mark = markOf(quarry);
        WeaponMounts mounts = this.aircraft.getWeapons();

        for (AirLoadout.Store store : this.order(quarry)) {
            Arms best = null;

            for (Arms candidate : ready) {
                if (candidate.store() == store
                        && KillScore.damagePerPass(roundOf(candidate), mark, mounts.ammoOf(candidate.id())) > 0.0
                        && (best == null || AirLoadout.worth(candidate.weapon()) > AirLoadout.worth(best.weapon()))) {
                    best = candidate;
                }
            }

            if (best != null) {
                return best;
            }
        }

        return null;
    }

    /**
     * その相手に使う分類の順。空の相手には空対空ミサイル、次に機関砲（回転翼機の機関砲は回転翼機にだけ——速い固定翼機は
     * 追えない）。装甲と防空車両には爆弾から、柔らかい相手には機関砲から。
     */
    private AirLoadout.Store[] order(Entity quarry) {
        if (aloft(quarry)) {
            boolean slow = quarry instanceof AircraftEntity aircraft && aircraft.isRotorcraft();

            return this.rotorcraft && !slow ? new AirLoadout.Store[] {AirLoadout.Store.AAM}
                    : new AirLoadout.Store[] {AirLoadout.Store.AAM, AirLoadout.Store.GUN};
        }

        boolean hard = (quarry instanceof VehicleEntityBase machine && machine.isArmoured())
                || (quarry instanceof GroundVehicleEntity ground && ground.getStats().radar().fitted());

        return hard ? new AirLoadout.Store[] {AirLoadout.Store.BOMB, AirLoadout.Store.GUN}
                : new AirLoadout.Store[] {AirLoadout.Store.GUN, AirLoadout.Store.BOMB};
    }

    /** その兵装がその相手に使える物か。空対空ミサイルを地上へ、爆弾を空へ、効かない物を相手へ向けない。 */
    private boolean suits(Arms using, Entity quarry) {
        if (KillScore.damagePerPass(roundOf(using), markOf(quarry), this.aircraft.getWeapons().ammoOf(using.id()))
                <= 0.0) {
            return false;
        }

        for (AirLoadout.Store store : this.order(quarry)) {
            if (store == using.store()) {
                return true;
            }
        }

        return false;
    }

    /** 兵装の数字（{@link KillScore.Round}）。兵装ファイルの値。 */
    private static KillScore.Round roundOf(Arms arms) {
        WeaponDefinition.Projectile round = arms.weapon().projectile();
        WeaponDefinition.Firing firing = arms.weapon().firing();

        return new KillScore.Round(arms.store(), round.damage(), round.explosion(), round.blast(),
                firing.roundsPerSecond() * Math.max(firing.salvo(), 1), round.ricochet(), round.drag(),
                arms.weapon().cluster().isPresent());
    }

    /** 相手の姿（{@link KillScore.Mark}）。 */
    private static KillScore.Mark markOf(Entity entity) {
        VehicleEntityBase machine = entity instanceof VehicleEntityBase vehicle ? vehicle : null;

        return new KillScore.Mark(aloft(entity), machine == null, machine != null && machine.isArmoured(),
                machine == null ? 0.0 : machine.armour(),
                entity instanceof GroundVehicleEntity ground ? ground.getStats().hull().damageTaken() : 1.0,
                drift(entity).length());
    }

    /** 残り耐久（点）。 */
    private static double healthOf(Entity entity) {
        if (entity instanceof VehicleEntityBase machine) {
            return machine.getHealth();
        }

        return entity instanceof LivingEntity living ? living.getHealth() : 1.0;
    }

    /** シーカーを相手に掴ませる（電波は押して、熱は機首の先で勝手に掴む）。掴んで固まったら撃つ。 */
    private void seek(Orders orders, WeaponDefinition.Guidance guidance, Entity quarry, double off) {
        TargetLock lock = this.aircraft.lock();
        Entity held = lock.target();

        if (held == quarry) {
            orders.pulse = lock.isLocked();

            return;
        }

        // 別の物を掴んでいれば放す——そのまま撃てば狙っていない物へ飛ぶ（シーカーは味方も掴む、
        // [[iff-is-display-only-by-choice]]）。何も掴んでいなければ、相手が視野に入ったときに押す。
        if ((held != null || off <= guidance.lockAngle() * LOCK_SHARE) && this.age - this.lockAt >= LOCK_GAP) {
            orders.lock = true;
            this.lockAt = this.age;
        }
    }

    /** シーカーがその相手に届く距離。電波は相手の反射の小ささで、熱は排気の冷たさで縮む（{@code TargetLock.reachAgainst}）。 */
    private double seekerReach(WeaponDefinition.Guidance guidance, Entity quarry) {
        double seen = switch (guidance.seeker()) {
            case RADAR -> AircraftEntity.visibility(quarry);
            case HEAT -> AircraftEntity.heatVisibility(quarry);
            default -> 1.0;
        };

        return guidance.lockRange() * this.aircraft.seekerRangeGain() * seen;
    }

    /** 持ち場の中で空から見る所。取られかけている・拮抗している・取り返したい拠点を先に。 */
    @Nullable
    private ObjectiveState focus() {
        if (this.team().isEmpty()) {
            return null;
        }

        ObjectiveState best = null;
        double bestValue = Double.NEGATIVE_INFINITY;

        for (ObjectiveState state : AiDirector.brain(this.team()).board().states()) {
            double value = state.score() + (state.beingTaken() ? 1.0 : 0.0)
                    + (state.state() == CaptureState.CONTESTED ? 0.6 : 0.0)
                    + (state.recaptureRequired() ? 0.5 : 0.0) + (state.wantsTaking() ? 0.3 : 0.0)
                    + Math.min(state.enemiesNear(), 4) * 0.15;

            if (value > bestValue) {
                bestValue = value;
                best = state;
            }
        }

        return best;
    }

    /** 待つ場所の中心。戦域から遠く出ていれば会場の中心、そうでなければ見る拠点。 */
    private Vec3 watchPoint(MinecraftServer server, MatchState match) {
        Vec3 middle = Bots.centre(server, match);
        double arena = Bots.arenaRadius(server, match);

        if (arena > 0.0 && flatDistance(this.aircraft.position(), middle) > arena + LEASH) {
            return middle;
        }

        return this.focus != null ? this.focus.centre() : middle;
    }

    /** 自陣の旗。無ければ会場の中心。 */
    private Vec3 home(MinecraftServer server, MatchState match) {
        MatchTeam team = match.team(this.team());

        return team == null || team.spawns().isEmpty() ? Bots.centre(server, match)
                : Vec3.atCenterOf(team.spawns().get(0));
    }

    private void enter(Phase next) {
        // 撃った航過を相手ごとに数える。見込みを実際の当たりで直すため（{@link #learned}）。
        if (this.phase == Phase.RUN && this.shots > 0 && this.runTarget >= 0) {
            Tally tally = this.tally(this.runTarget);

            tally.passes++;
            tally.touched = this.aircraft.level().getGameTime();
            this.passTarget = this.runTarget;
        }

        this.phase = next;
        this.phaseTicks = 0;
        this.shots = 0;
        this.runTarget = next == Phase.RUN && this.target != null ? this.target.getId() : -1;
        this.ammoSeen = this.arms == null ? 0 : this.aircraft.getWeapons().ammoOf(this.arms.id());
    }

    /**
     * 残弾の減りから、この航過で出た弾を数え、この出撃で撃った数（{@link #fired}）に足し、記録へ知らせる
     * （{@code PilotRecorder.released}、ビューアが撃った場所を描く）。引き金を引いても出ない tick（装填中・扉が開ききる
     * 前）があるので、引き金ではなく残弾で数える。
     */
    private void countShots(Arms using, int ammo) {
        if (ammo < this.ammoSeen) {
            this.recorder.released(using.id(), this.target, this.ammoSeen - ammo, this.aircraft.level().getGameTime());
            this.fired.merge(using.store(), this.ammoSeen - ammo, Integer::sum);
            this.shots += this.ammoSeen - ammo;
            this.shotAt = this.age;
        }

        this.ammoSeen = ammo;
    }

    /** 兵装倉を開けるか閉じる。扉を持つ機体だけ。閉じるのは、開いた倉がレーダーに映るから。 */
    private void openBay(boolean open) {
        if (this.aircraft.hasBay() && this.aircraft.isBayOpen() != open) {
            this.aircraft.toggleBay();
        }
    }

    /** この tick の引き金。単発の兵装は1 tick おきに引き直す——引きっぱなしでは2発目が出ない（{@code WeaponMounts.tick}）。 */
    private boolean trigger(Orders orders) {
        boolean pulled = orders.hold || (orders.pulse && !this.triggerWas);

        this.triggerWas = pulled;

        return pulled;
    }

    /** この tick のロックのキー。押した瞬間だけが効く（{@code TargetLock.tick}）。 */
    private boolean press(Orders orders) {
        boolean pressed = orders.lock && !this.lockWas;

        this.lockWas = pressed;

        return pressed;
    }

    /**
     * 追ってくるミサイルに、その目が見ている物の囮を撒き、一番近い1本を覚える（{@link #evade} が避ける）。近ければ
     * 撃ちに行くのをやめる。
     */
    private void dodge(Orders orders) {
        double closest = Double.MAX_VALUE;

        this.chaser = null;

        for (int at = this.inbound.size() - 1; at >= 0; at--) {
            RocketEntity missile = this.inbound.get(at);

            if (!missile.isAlive() || missile.getTarget() != this.aircraft) {
                this.inbound.remove(at);

                continue;
            }

            double distance = missile.distanceTo(this.aircraft);

            if (distance > WARNED) {
                continue;
            }

            WeaponDefinition.Guidance.Seeker seeker = missile.getWeapon().guidance()
                    .map(WeaponDefinition.Guidance::seeker).orElse(null);

            if (distance < closest) {
                closest = distance;
                this.chaser = missile;
            }

            orders.flare |= seeker == WeaponDefinition.Guidance.Seeker.HEAT;
            orders.chaff |= seeker == WeaponDefinition.Guidance.Seeker.RADAR;
        }

        if (closest <= BREAK && this.phase == Phase.RUN) {
            this.enter(this.rotorcraft ? Phase.TRANSIT : Phase.EXTEND);
        }
    }

    /** 相手がまだ撃つ価値のある姿でいるか。残骸・積荷・乗っている人は外す。空か地上かは {@link #pick} が分ける。 */
    private boolean alive(Entity entity) {
        if (entity.isRemoved() || !entity.isAlive() || entity.level() != this.aircraft.level()) {
            return false;
        }

        if (entity instanceof VehicleEntityBase machine) {
            return !machine.isWrecked() && !machine.isCargo();
        }

        return entity.getVehicle() == null;
    }

    /** 飛んでいる機体か。 */
    private static boolean aloft(Entity entity) {
        return entity instanceof AircraftEntity aircraft && !aircraft.onGround();
    }

    /** 機首から相手が見えているか。同じ相手には数 tick 覚え、引く本数は予算の内（{@code combat/FireControl} と同じ）。 */
    private boolean clearShot(Vec3 from, Entity quarry) {
        if (quarry.getId() == this.clearTarget && this.age - this.clearTick <= CLEAR_TICKS) {
            return this.clearResult;
        }

        MinecraftServer server = this.aircraft.getServer();

        if (server == null || !AiBudget.spend(server, AiBudget.Kind.RAY)) {
            return quarry.getId() == this.clearTarget && this.age - this.clearTick <= CLEAR_STALE && this.clearResult;
        }

        this.clearTarget = quarry.getId();
        this.clearTick = this.age;
        this.clearResult = LineOfSight.trace(this.aircraft.level(), from, quarry.getBoundingBox().getCenter(),
                this.aircraft).clear();

        return this.clearResult;
    }

    // ------------------------------------------------------------------
    // 飛ぶ向き
    // ------------------------------------------------------------------

    /** その点へ水平に向かい、高さの差だけ上り下りする方向。 */
    private Vec3 toward(Vec3 point, double height) {
        Vec3 here = this.aircraft.position();
        Vec3 flat = this.horizontal(point.subtract(here));
        double radians = Math.toRadians(Mth.clamp((height - here.y) * CLIMB_PER_BLOCK, -MOST_CLIMB, MOST_CLIMB));

        return new Vec3(flat.x * Math.cos(radians), Math.sin(radians), flat.z * Math.cos(radians));
    }

    /**
     * 中心の周りを {@value #LOITER} ブロックで回る方向。遠ければ中心へ向かう。回る向きは個体で分ける——全機が同じ向きに
     * 回ると、同じ円の上で追い付き合う。
     */
    private Vec3 orbit(Vec3 here, Vec3 centre, double height) {
        double dx = here.x - centre.x;
        double dz = here.z - centre.z;
        double distance = Math.sqrt(dx * dx + dz * dz);

        if (distance > LOITER * 1.5 || distance < 1.0) {
            return this.toward(centre, height);
        }

        double side = Math.floorMod(this.aircraft.getId(), 2) == 0 ? 1.0 : -1.0;
        double ux = dx / distance;
        double uz = dz / distance;
        double pull = (distance - LOITER) / LOITER;
        Vec3 along = new Vec3(-uz * side - ux * pull, 0.0, ux * side - uz * pull);

        return this.toward(here.add(along.scale(LOITER)), height);
    }

    /** そこへ向かう線の上の一番高い地面。 */
    private double clearance(Vec3 here, Vec3 towards) {
        Vec3 along = towards.subtract(here);
        double length = Math.min(Math.sqrt(along.x * along.x + along.z * along.z), SCAN);

        return Math.max(this.below(here),
                Ground.highest(this.aircraft.level(), here, along, length, LOOK_STEP, this.unknownGround()));
    }

    /** 真下の地面。読めなければ分かっている高さ。 */
    private double below(Vec3 here) {
        double ground = Ground.at(this.aircraft.level(), here.x, here.z);

        if (Double.isNaN(ground)) {
            return this.unknownGround();
        }

        this.lastGround = ground;

        return ground;
    }

    /** 読めない地面を数える高さ。最後に読めた地面と海面の高い方。 */
    private double unknownGround() {
        double sea = this.aircraft.level().getSeaLevel();

        return Double.isNaN(this.lastGround) ? sea : Math.max(this.lastGround, sea);
    }

    /** 水平の単位ベクトル。ほぼ真上か真下なら機首の水平の向き。 */
    private Vec3 horizontal(Vec3 direction) {
        double flat = Math.sqrt(direction.x * direction.x + direction.z * direction.z);

        if (flat > 1.0E-3) {
            return new Vec3(direction.x / flat, 0.0, direction.z / flat);
        }

        Vec3 nose = this.aircraft.getNoseVector();
        double noseFlat = Math.sqrt(nose.x * nose.x + nose.z * nose.z);

        return noseFlat > 1.0E-3 ? new Vec3(nose.x / noseFlat, 0.0, nose.z / noseFlat) : new Vec3(0.0, 0.0, 1.0);
    }

    /** その向きの上下を {@value #ROTOR_AIR_PITCH} 度までに切った向き。浮いている回転翼機が機首を向けてよい範囲。 */
    private Vec3 lookUp(Vec3 direction) {
        Vec3 flat = this.horizontal(direction);
        double limit = Math.toRadians(ROTOR_AIR_PITCH);
        double rise = Mth.clamp(Math.atan2(direction.y, Math.sqrt(direction.x * direction.x + direction.z * direction.z)),
                -limit, limit);

        return new Vec3(flat.x * Math.cos(rise), Math.sin(rise), flat.z * Math.cos(rise));
    }

    private static Vec3 drift(Entity entity) {
        return entity instanceof VehicleEntityBase machine ? machine.getVelocity() : entity.getDeltaMovement();
    }

    private static boolean near(Entity entity, List<ObjectiveState> objectives) {
        for (ObjectiveState objective : objectives) {
            double dx = entity.getX() - objective.centre().x;
            double dz = entity.getZ() - objective.centre().z;
            double reach = objective.radius() + OBJECTIVE_MARGIN;

            if (dx * dx + dz * dz <= reach * reach) {
                return true;
            }
        }

        return false;
    }

    private static double flatDistance(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;

        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 2つの向きの間の角（度）。 */
    private static double degrees(Vec3 first, Vec3 second) {
        double lengths = first.length() * second.length();

        return lengths < 1.0E-9 ? 180.0 : Math.toDegrees(Math.acos(Mth.clamp(first.dot(second) / lengths, -1.0, 1.0)));
    }

    // ------------------------------------------------------------------
    // 外から訊かれること
    // ------------------------------------------------------------------

    @Nullable
    @Override
    public Entity target() {
        return this.target;
    }

    @Nullable
    @Override
    public TacticalAction action() {
        return switch (this.phase) {
            case RUN -> TacticalAction.ATTACK;
            case SPENT -> TacticalAction.RETREAT;
            case EXTEND -> TacticalAction.ADVANCE;
            case TRANSIT -> this.target != null ? TacticalAction.SEARCH_ENEMY : TacticalAction.ADVANCE;
        };
    }

    /**
     * 役割は支援。航空機の役割を足すと、判断の特徴量の並び（{@code BattleState.FEATURES}、役割ごとに1つ）が変わり、
     * 学習用の記録の版を分けることになる——航空機は方針を通らないので、そこへ出る理由が無い。
     */
    @Override
    public VehicleRole role() {
        return VehicleRole.SUPPORT;
    }

    @Override
    public TacticalProfile profile() {
        return this.profile;
    }

    @Override
    public String version() {
        return this.version.id();
    }

    /** 地上の車両の道案内にはならない。地形を越えて飛ぶ物の後ろを走っても、同じ所で詰まるだけだ。 */
    @Override
    public boolean leads() {
        return false;
    }

    @Override
    public boolean underFire(long now) {
        return this.damage.underFire(now, AiConfig.sight().attackedWindowTicks());
    }

    @Override
    public int attackerId(long now) {
        Entity attacker = this.damage.lastAttacker(this.aircraft.level(), now,
                AiConfig.sight().attackedWindowTicks());

        return attacker == null ? -1 : attacker.getId();
    }

    @Override
    public void onHurt(@Nullable Entity attacker, float amount, long now) {
        this.damage.record(attacker, amount, now);
    }

    /** 与えた打撃。相手ごとの当たり（{@link #learned}）にも足す——乗っている人に当たった分は乗り物に。 */
    @Override
    public void onDamageDealt(Entity victim, float amount, long now) {
        Entity struck = victim.getVehicle() instanceof VehicleEntityBase carrier ? carrier : victim;
        Tally tally = this.tally(struck.getId());

        tally.dealt += amount;
        tally.touched = now;
        this.recorder.damageDealt(victim, amount, now);
    }

    @Override
    public void onMissileInbound(Entity missile) {
        if (missile instanceof RocketEntity rocket && !this.inbound.contains(rocket)
                && this.inbound.size() < MOST_INBOUND) {
            this.inbound.add(rocket);
        }
    }

    /**
     * 記録の飛行の行（{@code log/FlightLog}）へ、判断の中にあって外から見えない物を書き足す。ビューアが段階と兵装を描く。
     * 今の相手の点数と、この出撃で撃った数（機関砲の発数・爆弾とミサイルの本数）も残す。
     */
    @Override
    public void describeFlight(JsonObject into) {
        into.addProperty("phase", this.phase.name());
        into.addProperty("phase_ticks", this.phaseTicks);
        into.addProperty("evading", this.chaser != null);
        into.addProperty("rotorcraft", this.rotorcraft);
        into.addProperty("weapon", this.arms == null ? null : this.arms.id().toString());
        into.addProperty("focus", this.focus == null ? null : this.focus.id());
        into.addProperty("score", this.targetScore);
        into.addProperty("missiles", this.missiles);
        into.addProperty("fired_gun", this.fired.getOrDefault(AirLoadout.Store.GUN, 0));
        into.addProperty("fired_bomb", this.fired.getOrDefault(AirLoadout.Store.BOMB, 0));
        into.addProperty("fired_aam", this.fired.getOrDefault(AirLoadout.Store.AAM, 0));
    }

    @Override
    public PilotRecorder recorder() {
        return this.recorder;
    }

    @Override
    public PilotSnapshot snapshot() {
        return new PilotSnapshot(this.aircraft.getId(), this.aircraft.getAircraftId().getPath(), this.team(),
                this.version.id(), VehicleRole.SUPPORT, this.action(), this.focus == null ? null : this.focus.id(),
                this.target, 0.0, 0.0, this.aircraft.getHealthFraction(), this.aircraft.position(), this.steering,
                null, List.of(), Map.of());
    }
}
