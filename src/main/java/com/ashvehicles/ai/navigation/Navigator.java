package com.ashvehicles.ai.navigation;

import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.ashvehicles.ai.AiConfig;
import com.ashvehicles.ai.BotChunkLoader;
import com.ashvehicles.ai.BotPilot;
import com.ashvehicles.ai.battlefield.TacticalMap;
import com.ashvehicles.ai.control.DriveCommand;
import com.ashvehicles.ai.core.AiBudget;
import com.ashvehicles.ai.learning.MapKnowledge;
import com.ashvehicles.ai.learning.MapMemory;
import com.ashvehicles.ai.learning.MapTrace;
import com.ashvehicles.ai.tactics.MovementIntent;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.match.Bots;
import com.ashvehicles.match.MatchState;
import com.ashvehicles.vehicle.GroundVehicleDefinition;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * 移動の意図（{@link MovementIntent}）を、1 tick 分の運転（{@link DriveCommand}）に直す。
 *
 * <p><b>行動の名前を知らない。</b> 知っているのは「どこへ・どれだけ危険を嫌って・着いたら止まるか」だけで、
 * それが拠点を取るためなのか下がるためなのかは知らない。意味を解くのは {@code tactics/Tactics} の仕事。
 *
 * <p>中身は元の {@code GroundPilot} の走行部分を引き継いでいる——道を引き（{@link RoutePlanner}）、
 * 通過点へ向けて触角で1 tick を決め（{@link Obstacles}）、詰まれば後退し、2度続けばわざと外し、味方をよけ、
 * 迷ったら進めている味方に付いて行く。どれも試合で踏んで直した物で、理由は
 * [[bots-are-a-pilot-object-on-the-vehicle]] に1つずつ残っている。<b>削らないこと。</b> 道は戦術格子の危険を避け
 * （{@link RoutePlanner}）、道を引く回数にはサーバー全体の上限がある（{@link AiBudget}）。断られた tick は古い道を
 * 走り続ける。
 *
 * <p><b>町で立ち往生し、野原で蛇行した</b>（2026-09-13）ので、次を足した:
 * <ul>
 * <li><b>向きは毎 tick 通過点を追う。</b> 触角が測るのは数 tick おきで、以前はその間の針路を固定していた——車両が
 *     横へずれても針路は古いままで、通過点の横を抜けてから戻る蛇行になった。今は触角が曲げた分だけを覚え、向き
 *     そのものは毎 tick 通過点から取り直す。行きたい向きが大きく変われば、周期を待たずに測り直す。
 * <li><b>詰まりは1枚の壁で1回。</b> 4 tick ごとに測る触角が同じ壁を見るたびに数えていたので、1枚の壁で迷子になり、
 *     針路をずらし続けた。後退中と、詰まったばかりの {@value #STUCK_EVERY} tick は数えず、走れたら忘れる。
 * <li><b>抜け出せなければ、来た道を戻る</b>（{@link Breadcrumbs}）。{@value #ESCAPE_JAMS} 回続けて詰まるか、道が
 *     1歩も引けないことが続けば、そこと進もうとした先のマスを避けると決め、走ってきた跡を逆に辿る——向きを変える
 *     余地が無ければ後ろ向きのまま。屋根から降りられない車両には普段より深い落差（{@link Obstacles#ESCAPE_DROP}）を
 *     許す。
 * <li><b>水の中には居ない。</b> 沈んだら道を引き直し（水の1歩は高いので、一番近い岸へ向かう道になる）、撃ち合いにも
 *     居座りにも足を止めない。触角は、渡ると決めた道の上か既に水の中でなければ、深い水を壁と呼ぶ。
 * </ul>
 *
 * <p><b>経験は地図に残す</b>（{@link MapTrace} → {@link MapMemory}）。どこで詰まり、どこで沈み、どこをすんなり
 * 抜けたか。道を引くときにその地図の代償を読むので、試合を重ねるほど同じ所で詰まらなくなる。
 */
public final class Navigator {
    /** 操舵をいっぱいに切る方位差（度）。これ以下では比例して当てる。 */
    private static final float FULL_STEER = 25.0F;

    /** この方位差より大きければ、速度を落として向きを合わせることを優先する（度）。 */
    private static final float TURN_FIRST = 55.0F;

    /** 居座っている車両が、これより小さな方位差なら車体を回さない（度）。 */
    private static final float ANCHOR_SLACK = 10.0F;

    /** 立ち往生を疑う間隔（tick）。詰まりを続けて数えない間隔でもある。 */
    private static final int STUCK_EVERY = 20;

    /** 地形を測り直す間隔（tick）。前が詰まっているときは短い方。 */
    private static final int LOOK_EVERY = 10;
    private static final int LOOK_SHORT = 4;

    /** 行きたい向きがこれだけ変わったら、周期を待たずに測り直す（度）。 */
    private static final float LOOK_TURN = 25.0F;

    /**
     * 2度続けて詰まったときに、しばらく針路をずらす角度（度）と長さ（tick）。
     *
     * <p>同じ場所で後退と前進を繰り返す車両は、毎回まったく同じ判断をしているから同じ場所へ戻る。<b>2度目からは
     * わざと外す</b>ことでその対称性を壊す——迷路を解くのではなく、迷路から出る。
     *
     * <p>以前は 65 度を「測り直し60回ぶん」（数百 tick）続けていて、開けた所へ出てからも斜めに走り続けた。今は
     * tick で数える。
     */
    private static final float DETOUR_ANGLE = 40.0F;
    private static final int DETOUR_TICKS = 40;

    /** 味方をどこまで避けるか。距離（ブロック）、進路からの角（度）、よける角（度）。 */
    private static final double MATE_REACH = 10.0;
    private static final double MATE_ANGLE = 30.0;
    private static final float MATE_TURN = 35.0F;

    /** 前の味方がこの割合より遅ければ、追い付いていると見なす（自分の速さに対して）。 */
    private static final double MATE_SLOWER = 0.7;

    /** 止まっていると見なす速さ（ブロック/tick）。 */
    private static final double STOPPED = 0.05;

    /** 立ち往生から抜けるために後退する長さ（tick）。 */
    private static final int BACKING_TICKS = 30;

    /** 下がり始めるのに要る、車体の後ろ端から後ろの余裕（ブロック）と、下がるのをやめる余裕。 */
    private static final double BACKING_ROOM = 2.5;
    private static final double BACKING_STOP = 1.0;

    /** 後ろも塞がっているときに、その場で向きを変える長さ（tick）と、向き終えたと見なす角（度）。 */
    private static final int PIVOT_TICKS = 30;
    private static final float PIVOT_DONE = 12.0F;

    /** 通過点に着いたと見なす距離（ブロック）。 */
    private static final double REACHED = 5.0;

    /**
     * 迷ったと見なすまで（tick）。この間、目的地への残り距離が {@link #PROGRESS_STEP} も縮まらなければ迷子。
     *
     * <p><b>立ち往生（{@link #tickStuck}）とは別の物差し。</b> あちらは「動いていない」を見るので、袋小路の口で
     * 前後に往復し続ける車両は一度も詰まったことにならない。動いてはいるのに近付いていない——それが迷子だ。
     */
    private static final int LOST_AFTER = 400;
    private static final double PROGRESS_STEP = 3.0;

    /** 詰まりがこの回数続いたら、残り距離を待たずに迷子と見なす。 */
    private static final int LOST_JAMS = 4;

    /** 「問題なく進めている」と名乗れる、最後に近付いてからの tick。道案内に選ばれる側の条件。 */
    private static final int PROGRESSING = 100;

    /** 道案内を探す距離、付いて行く長さ（tick）、案内役にこれ以上寄らない距離（ブロック）。 */
    private static final double FOLLOW_REACH = 160.0;
    private static final int FOLLOW_TICKS = 300;
    private static final double FOLLOW_GAP = 12.0;

    /** 案内役に求める、自分より目的地に近い距離（ブロック）。 */
    private static final double GUIDE_AHEAD = 24.0;

    /** 目的地がこれだけ動いたら道を引き直す（ブロック）。 */
    private static final double REGOAL = 8.0;

    /** 危険の嫌い方がこれだけ変わったら道を引き直す。撤退に移ったのに、拠点へ向かう道を走り続けないように。 */
    private static final double RISK_CHANGE = 0.3;

    /** 道が引けなかったとき、次に試すまで（tick）。空の道を毎 tick 引き直して予算を食い潰さないように。 */
    private static final int EMPTY_RETRY = 20;

    /** 続けてこれだけ詰まったら、来た道を戻って抜け出す。 */
    private static final int ESCAPE_JAMS = 3;

    /** 道が1歩も引けないことがこれだけ続いたら、抜け出す。 */
    private static final int ESCAPE_EMPTY = 3;

    /** 抜け出しに使ってよい長さ（tick）、戻る距離（ブロック）、次に抜け出してよいまで（tick）。 */
    private static final int ESCAPE_TICKS = 240;
    private static final double ESCAPE_BACK = 16.0;
    private static final int ESCAPE_COOLDOWN = 200;

    /** 抜け出している間に、動けているかを確かめる間隔（tick）、動けたと見なす距離（ブロック）、前後を入れ替える回数の上限。 */
    private static final int ESCAPE_CHECK = 40;
    private static final double ESCAPE_MOVED = 1.0;
    private static final int ESCAPE_FLIPS = 3;

    /** 戻る先がこの角より後ろにあれば、向きを変えずに後ろ向きのまま戻る（度）。 */
    private static final float REVERSE_ANGLE = 110.0F;

    /** 抜け出せなかったマスを避ける長さ（tick）と、そのマスへ入る1歩に足す代償（1歩の距離に対する倍率）。 */
    private static final int AVOID_TICKS = 1200;
    private static final double AVOID = 6.0;

    /** 水の深さを測り直す間隔（tick）。 */
    private static final int WATER_EVERY = 5;

    private final GroundVehicleEntity ground;
    private final String team;
    private final RoutePlanner.Weights weights;
    private final MapKnowledge.Weights mapWeights;

    /** 走ってきた跡と、抜け出せなかったマス。 */
    private final Breadcrumbs crumbs = new Breadcrumbs();

    /** 走った経験を地図へ残す係。 */
    private final MapTrace trace;

    private int age;

    /** 最後の tick に戦域の外にいたか。記録のため。 */
    private boolean leashedNow;

    /** 命じた前進に対して、実際に進めているか（{@link #tickStuck}）。 */
    private final StallWatch stall = new StallWatch();

    /** 後退して抜け出している残り tick。0 なら普通に走っている。 */
    private int backing;

    /** 後ろも塞がっていて、その場で向きを変えている残り tick と、向ける方角。 */
    private int pivoting;
    private float pivotYaw;

    /** 立ち往生の判定に使う、前回見たときの位置。 */
    private Vec3 wasAt;

    /** 最後に触角で測ったときに行きたかった方角と、触角がそこから曲げた分（度）。 */
    private float lookedFor;
    private float deflection;

    /** 次に地形を測り直すまでの tick。 */
    private int recheck;

    private List<Vec3> path = List.of();
    private int leg;
    private int replan;
    private Vec3 pathGoal = Vec3.ZERO;
    private double plannedRisk = 1.0;
    private boolean forced;
    private int retryEmptyAt;

    /** 道が1歩も引けなかったことが続いた回数。 */
    private int emptyPlans;

    @Nullable
    private Route route;

    @Nullable
    private Vec3 announcedGoal;

    /** 続けて詰まった回数、最後に詰まった tick、わざと針路をずらしている残り tick、そしてずらす側。 */
    private int jams;
    private int jammedAt;
    private int detour;
    private int detourSide;

    /** 抜け出している残り tick、抜け出し始めた位置、跡が尽きたときの行き先、次に抜け出してよい tick。 */
    private int escaping;
    private Vec3 escapeFrom = Vec3.ZERO;

    @Nullable
    private Vec3 escapeTo;

    private int escapeReadyAt;

    /** 抜け出している間の確かめ。前回の位置と時刻、前後を入れ替えているか、入れ替えた回数。 */
    private Vec3 escapeCheck = Vec3.ZERO;
    private int escapeCheckAt;
    private boolean escapeFlipped;
    private int escapeFlips;

    /** 車両が浸かっている水の深さ（ブロック）。 */
    private int water;

    /** 迷子の判定。目的地へ一番近付けた距離、それから経った tick、実際に近付いたことがあるか、測っている目的地。 */
    private double bestGap = Double.MAX_VALUE;
    private int sinceProgress;
    private boolean proven;
    private Vec3 measuredFor = Vec3.ZERO;

    /** 目的地に着いて留まっているか。道案内の資格の1つ。 */
    private boolean settled;

    /** 付いて行っている相手と、その残り tick。 */
    @Nullable
    private Entity leader;
    private int following;

    @Nullable
    private Consumer<Route> onRoute;

    public Navigator(GroundVehicleEntity ground, String team, RoutePlanner.Weights weights,
            MapKnowledge.Weights mapWeights) {
        this.ground = ground;
        this.team = team;
        this.weights = weights;
        this.mapWeights = mapWeights;
        this.trace = new MapTrace(ground);
        this.wasAt = ground.position();
        this.lookedFor = ground.getYRot();
        this.jammedAt = -STUCK_EVERY;
        // 測る tick を個体ごとにずらす。40両が同じ tick に前方を読むと、その1 tick だけが重い。
        this.recheck = Math.floorMod(ground.getId(), LOOK_EVERY);
        this.replan = Math.floorMod(ground.getId() * 7, 120);
    }

    /** 新しい行き先へ道を引いたときに呼ぶ。記録（{@code RouteSelected}）のため。 */
    public void setRouteListener(@Nullable Consumer<Route> listener) {
        this.onRoute = listener;
    }

    /**
     * 1 tick 分の運転。
     *
     * @param quarry    今撃っている相手。止まって撃つときに車体をそちらへ向ける
     * @param fightHere 足を止めて撃ち合う（{@code combat/CombatController#wantsToHold}）
     */
    public DriveCommand tick(MovementIntent intent, @Nullable Entity quarry, boolean fightHere, TacticalMap map) {
        this.age++;

        Vec3 here = this.ground.position();

        // 戦域の外へ出ていたら、撃ち合っていても帰る。<b>撃つのはやめない</b>——砲は据わったまま、車体だけが
        // 会場へ戻る。追われて釣り出された 20 両が地平線の向こうでチャンクを開き続ける、という負け方をしない
        // ためだけの規則だ。
        boolean leashed = Bots.beyondArena(this.ground);

        this.leashedNow = leashed;
        this.sense(here);

        // 抜け出している最中は、何よりそれを先に済ませる。撃ち合いに足を止めれば、また同じ所に嵌まる。砲は
        // 移動と無関係に撃ち続ける。
        if (this.escaping > 0) {
            return this.escape(here);
        }

        boolean submerged = this.submerged();
        boolean anchored = !leashed && intent.mode() == MovementIntent.Mode.ANCHOR;

        // 水の中では足を止めない。撃ち合うのは岸へ上がってから——沈んだ車両は逃げられない的だ。
        if (fightHere && quarry != null && !leashed && !submerged) {
            if (anchored) {
                return this.anchor(quarry.position());
            }

            // 詰められた。止まって、車体を相手へ向ける。撃ち合いで止まっている間を迷子として数えてはいけない。
            this.settled = false;
            this.forgetProgress();

            return this.drive(quarry.position(), true, intent.throttle(), intent, map);
        }

        Vec3 goal = leashed ? this.homeward(intent) : intent.destination();
        boolean holding = !leashed && intent.mode() != MovementIntent.Mode.MOVE;

        // 居座る行動でも、水の中には居座らない。最後に乾いた地面を走っていた所へ戻る。
        if (submerged && (goal == null || holding) && this.crumbs.newest() != null) {
            goal = this.crumbs.newest();
            holding = false;
        }

        if (goal == null || holding) {
            Vec3 face = intent.face() != null ? intent.face() : quarry != null ? quarry.position() : null;

            if (holding && anchored) {
                return this.anchor(face);
            }

            this.settled = true;
            this.release();

            return face == null ? DriveCommand.PARKED : this.drive(face, true, intent.throttle(), intent, map);
        }

        // 着いたかどうかは<b>目的地</b>で測る。通過点で測ると、道の途中の1点に着くたびに車両が止まる。水の底は
        // 着いたことにしない。
        boolean arrived = !submerged && goal.subtract(here).horizontalDistance() <= intent.arriveRadius();

        this.settled = arrived && !leashed;

        if (arrived) {
            // <b>着いた車両はそこに居座る。</b> 拠点は「立っている時間」で取る物であり、遠くの敵を追って円から
            // 出れば、進みはその場で止まる。敵が見えていれば車体をそちらへ向けるが、足は動かさない。
            this.release();

            Vec3 face = quarry != null ? quarry.position() : intent.face() != null ? intent.face() : goal;

            return this.drive(face, true, intent.throttle(), intent, map);
        }

        // 戦域の外では案内に付いて行かない。首輪は迷子より優先する。水の中でも、まず自分で岸へ上がる。
        Entity guide = leashed || submerged ? null : this.guide(goal, here);

        if (guide != null) {
            Vec3 at = guide.position();

            if (at.subtract(here).horizontalDistance() <= FOLLOW_GAP) {
                // 追い付いた。押し合わないように後ろで待つ。
                return this.drive(at, true, intent.throttle(), intent, map);
            }

            return this.drive(this.waypoint(new Vec3(at.x, here.y, at.z), here, intent, map), false,
                    intent.throttle(), intent, map);
        }

        return this.drive(this.waypoint(goal, here, intent, map), false, intent.throttle(), intent, map);
    }

    /** 次の tick に道を引き直す。行動が変わったとき。 */
    public void forceReplan() {
        this.forced = true;
    }

    /** 迷っているか。近付かないまま時間が経ったか、迂回しても詰まり続けているか。 */
    public boolean lost() {
        return this.sinceProgress >= LOST_AFTER || this.jams >= LOST_JAMS;
    }

    /** 今走っている道。まだ引いていなければ null。 */
    @Nullable
    public Route route() {
        return this.route;
    }

    /** 今走っている道の危険（0〜1）。 */
    public double routeRisk() {
        return this.route == null ? 0.0 : this.route.risk();
    }

    /** 車両が浸かっている水の深さ（ブロック）。{@value #WATER_EVERY} tick おきに測り直す。 */
    public int waterDepth() {
        return this.water;
    }

    /** 車体が沈む深さの水の中にいるか。 */
    public boolean submerged() {
        return this.water >= Obstacles.DEEP;
    }

    /**
     * 記録のための今の走り方。
     *
     * @param leashed       戦域の外から戻っている
     * @param lost          迷っている（{@link #lost}）
     * @param jams          続けて詰まった回数
     * @param backing       後退して抜け出している最中
     * @param following     道案内に付いて行っている
     * @param remaining     まだ通っていない通過点の数
     * @param routeComplete 今の道が目的地まで届いている
     * @param water         浸かっている水の深さ（ブロック）
     * @param escaping      来た道を戻って抜け出している最中
     */
    public record Status(boolean leashed, boolean lost, int jams, boolean backing, boolean following,
            int remaining, boolean routeComplete, int water, boolean escaping) {
    }

    public Status status() {
        return new Status(this.leashedNow, this.lost(), this.jams, this.backing > 0, this.following > 0,
                Math.max(0, this.path.size() - this.leg), this.route != null && this.route.complete(), this.water,
                this.escaping > 0);
    }

    /** まだ通っていない通過点。 */
    public List<Vec3> remaining() {
        return this.leg >= this.path.size() ? List.of() : this.path.subList(this.leg, this.path.size());
    }

    /**
     * 道案内を任せられるか。<b>迷っておらず、誰にも付いて行っておらず、最近ちゃんと目的地へ近付いた</b>——
     * あるいは着いて居座っている。
     *
     * <p>付いて行っている者は案内役にしない。迷子同士が互いを案内役に選べば、2両で輪を描いて永久に回る。抜け出して
     * いる者と水の中の者も、付いて行く先ではない。
     */
    public boolean leads() {
        if (this.following > 0 || this.lost() || this.backing > 0 || this.escaping > 0 || this.submerged()) {
            return false;
        }

        return this.settled || (this.proven && this.sinceProgress < PROGRESSING);
    }

    // ------------------------------------------------------------------
    // 周りを知る
    // ------------------------------------------------------------------

    /** 毎 tick。水の深さ、走ってきた跡、地図の経験。 */
    private void sense(Vec3 here) {
        if (this.detour > 0) {
            this.detour--;
        }

        if (this.age % WATER_EVERY == 0) {
            boolean was = this.submerged();

            this.water = Obstacles.waterDepth(this.ground.level(), here.x, here.y, here.z);

            if (!was && this.submerged()) {
                // 沈んだ。道を引き直す——水の1歩は高いので、新しい道は一番近い岸へ向かう。
                this.replanNow();
            }
        }

        // 跡を置くのは乾いた地面を前へ走っている間だけ。後退中や戻っている最中の位置は「通れた道」ではない。
        if (this.escaping <= 0 && this.backing <= 0 && !this.submerged()) {
            this.crumbs.drop(here);
        }

        this.trace.tick(here, this.age, this.submerged(), this.backing > 0 || this.pivoting > 0 || this.escaping > 0);
    }

    // ------------------------------------------------------------------
    // 走る
    // ------------------------------------------------------------------

    /**
     * 目的地へ向けて1 tick 分の運転。
     *
     * <p>止まるときも車体は目標へ向ける。装甲の厚い面を向けるためであり、砲塔の旋回角を使い切らないため
     * でもある。
     *
     * @param hold 止まるべきか。目標を撃っている間と、目的地に着いた後
     */
    private DriveCommand drive(Vec3 goal, boolean hold, float throttle, MovementIntent intent, TacticalMap map) {
        float wanted = yawTo(this.ground, goal);

        // 止まって撃つ間は避けない。そこでの車体の向きは「どこへ行けるか」ではなく「どちらへ装甲を
        // 向けるか」の答えだからだ。走り出す時だけ地形を読む。
        if (!hold) {
            // 行きたい向きが大きく変わった（通過点を1つ越えた、道を引き直した）なら、周期を待たずに測り直す。
            // 古い向きのまま走り続けると、次の点の横を通り過ぎてから戻ってくる。
            if (--this.recheck <= 0 || Math.abs(Mth.wrapDegrees(wanted - this.lookedFor)) > LOOK_TURN) {
                this.look(goal, wanted, Obstacles.DROP, true);
            }

            // 触角が曲げなかったなら、向きは毎 tick 通過点をそのまま追う。曲げたなら、曲げた分を保ったまま追う。
            wanted = Mth.wrapDegrees(wanted + this.deflection);
        }

        float error = Mth.wrapDegrees(wanted - this.ground.getYRot());
        float steer = Mth.clamp(error / FULL_STEER, -1.0F, 1.0F);

        if (this.pivoting > 0) {
            float turn = Mth.wrapDegrees(this.pivotYaw - this.ground.getYRot());

            if (--this.pivoting == 0 || Math.abs(turn) < PIVOT_DONE) {
                this.pivoting = 0;
                this.recheck = 0;
                this.wasAt = this.ground.position();
                this.stall.release();
            }

            // その場で回る。進まないので、前の水にも後ろの水にも入らない。
            return new DriveCommand(0.0F, Mth.clamp(turn / FULL_STEER, -1.0F, 1.0F), false);
        }

        if (this.backing > 0) {
            // 下がりながら後ろを見続ける。浜の傾きは、下がり始めた所からは見えないことがある。
            boolean blocked = this.backing % LOOK_SHORT == 0 && this.rearRoom() < BACKING_STOP;

            if (--this.backing == 0 || blocked) {
                // 後退で動いた分を、次の立ち往生の判定に持ち込まない。
                this.backing = 0;
                this.wasAt = this.ground.position();
                this.stall.release();

                if (blocked) {
                    return new DriveCommand(0.0F, 0.0F, true);
                }
            }

            // 抜け出す向きは方位差の反対側。塞がれた方へ後退して、もう一度同じ壁に当たらないように。
            return new DriveCommand(-0.6F, -steer, false);
        }

        float drive;

        if (hold) {
            // 止まって撃つ。車体が大きく外れている間だけ、向き直るために転がす。
            drive = Math.abs(error) > TURN_FIRST ? 0.25F : 0.0F;
        } else {
            drive = (Math.abs(error) > TURN_FIRST ? 0.45F : 0.9F) * Math.max(throttle, 0.1F);
        }

        // 止まって撃っている間は詰まりを数えない。わざと止まっている車両を後退させれば、据えた位置を自分で捨てる。
        this.tickStuck(hold ? 0.0F : drive);

        return new DriveCommand(drive, steer, drive == 0.0F);
    }

    /**
     * 1ブロックも動かずに、向けられるなら車体を向ける。
     *
     * <p><b>向き直るために転がさない。</b> {@link #drive} の止まり方は車体が {@value #TURN_FIRST} 度より外れていれば
     * 転がして向き直り、拠点の円の中ではそれが車両に輪を描かせた。その場で向きを変えるのは信地旋回できる車両
     * （{@code powertrain.pivot_rate} が正）だけで、できない装輪車は向きを変えない——砲塔は車体と別に回る。
     */
    private DriveCommand anchor(@Nullable Vec3 face) {
        // 止まっていることを詰まりの物差しにも伝え、やりかけの後退と旋回を捨てる。{@link #drive} を通らないので、
        // さもないと円を出て走り出した最初の窓で「進めたはず」が膨らみ、詰まりと読まれて後退する。
        this.stall.release();
        this.wasAt = this.ground.position();
        this.backing = 0;
        this.pivoting = 0;
        this.settled = true;
        this.release();

        if (face == null || this.ground.getStats().powertrain().pivotRate() <= 0.0F) {
            return DriveCommand.PARKED;
        }

        float error = Mth.wrapDegrees(yawTo(this.ground, face) - this.ground.getYRot());

        if (Math.abs(error) <= ANCHOR_SLACK) {
            return DriveCommand.PARKED;
        }

        return new DriveCommand(0.0F, Mth.clamp(error / FULL_STEER, -1.0F, 1.0F), true);
    }

    /**
     * 触角で前を測り、曲げる分を決める。
     *
     * @param drop     降りてよい落差
     * @param counting 走っている最中か。抜け出している最中は、迂回も味方よけも詰まりの勘定もしない
     */
    private void look(Vec3 goal, float wanted, double drop, boolean counting) {
        // 迷いを壊す迂回と、味方をよける分を先に乗せる。地形に最後の言葉を持たせるため、
        // 触角はその後に通す——よけた先が壁では意味が無い。
        float asked = !counting ? wanted
                : this.dodge(this.detour > 0 ? Mth.wrapDegrees(wanted + this.detourSide * DETOUR_ANGLE) : wanted);
        Obstacles.Heading heading = Obstacles.around(this.ground, asked,
                goal.subtract(this.ground.position()).horizontalDistance(), drop, this.wading());

        this.lookedFor = wanted;
        this.deflection = Mth.wrapDegrees(heading.yaw() - wanted);
        // 次に測るのは、確かめた距離を走り切る前。以前は「真っ直ぐが開いている」だけで10 tick 測らなかったが、真っ直ぐは
        // 向かう点まで（数ブロック）しか確かめていないので、0.9 ブロック/tick の戦車は確かめていない所まで走り、浜から
        // 湖へ入った（2026-09-13）。擦りながら10 tick 走るのが「引っかかる」の正体でもある。
        double speed = Math.max(Math.abs(this.ground.getSpeed()), 0.1);

        this.recheck = Mth.clamp((int) ((heading.clearance() - Obstacles.DEAD_END) / speed), LOOK_SHORT / 2, LOOK_EVERY);

        // どの方角も開いていない。後退で口を開け、道も引き直す。<b>後退中と、詰まったばかりの間は数えない</b>——
        // 4 tick ごとに測り直す触角が同じ壁を見るたびに数えると、1枚の壁で迷子になる。
        if (counting && heading.stuck() && this.backing <= 0 && this.age - this.jammedAt >= STUCK_EVERY) {
            this.jam();
        }

        // 走っていく先の地面を頼んでおく。着く頃には在る、という状態を作るためだけの予約。
        BotChunkLoader.lookAhead(this.ground, heading.yaw());
    }

    /**
     * 深い水へ入ってよいか。既に水の中にいるか、<b>今向かっている区間</b>が水を渡ると決めているとき。道のどこかで水を
     * 渡るからといって、岸沿いの乾いた区間で水へ入ってよいことにはならない（{@link Route#wetLeg}）。
     */
    private boolean wading() {
        return this.submerged() || (this.route != null && this.leg < this.path.size() && this.route.wetLeg(this.leg));
    }

    /**
     * 壁や溝に嵌まっていないか。命じた通りに進んでいなければ、しばらく後退する。
     *
     * <p><b>「命じた通り」はその車両の加速で測る</b>（{@link StallWatch}）。以前は「{@value #STUCK_EVERY} tick で
     * 1 ブロック」の固定の物差しで、加速 0.004 ブロック/tick² の戦車は止まった所から走り出すと 20 tick で
     * 0.84 ブロックしか進めない——走り出すたびに詰まったと読まれて後退し、後退から走り出してまた詰まり、重い
     * 車両は試合の間ずっと陣地の前で行き来していた（2026-09-13。加速 0.006 の BTR-80 だけが抜けていた）。
     *
     * @param drive この tick に命じた前進。止まっている間は0
     */
    private void tickStuck(float drive) {
        GroundVehicleDefinition.Powertrain powertrain = this.ground.getStats().powertrain();

        this.stall.command(drive, this.ground.getSpeed(), powertrain.maxSpeed(), powertrain.acceleration(),
                powertrain.braking());

        if (this.age % STUCK_EVERY != 0) {
            return;
        }

        Vec3 here = this.ground.position();
        double moved = here.subtract(this.wasAt).horizontalDistance();

        if (this.stall.stalled(moved)) {
            if (this.age - this.jammedAt >= STUCK_EVERY) {
                this.jam();
            }
        } else if (this.stall.progressing(moved)) {
            // 気持ちよく走れている。迷った回数は忘れる——さもないと、序盤に1度詰まった車両が試合の終わりまで
            // 針路をずらし続け、次に1度詰まっただけで逃げ出す。
            this.jams = 0;
        }

        this.wasAt = here;
        this.stall.reset();
    }

    /** 前が抜けられないと分かったときの手当て。後退し、道を引き直し、続けて詰まるなら針路をずらし、それでも駄目なら戻る。 */
    private void jam() {
        this.jammedAt = this.age;
        this.jams++;
        this.trace.stalled();
        this.stall.release();
        this.replanNow();

        // 何度続けても抜けられない。跡を辿って戻り、ここを避けて道を引き直す。
        if (this.jams >= ESCAPE_JAMS && this.age >= this.escapeReadyAt) {
            this.startEscape();

            return;
        }

        if (this.backing <= 0 && this.pivoting <= 0) {
            if (this.rearRoom() >= BACKING_ROOM) {
                this.backing = BACKING_TICKS;
            } else {
                // 後ろも塞がっている（か、後ろが水か崖）。下がらずに、その場で開いている方へ向きを変える。
                Obstacles.Heading open = Obstacles.around(this.ground, this.lookedFor, Obstacles.look(), Obstacles.DROP,
                        this.wading());
                float side = Math.floorMod(this.ground.getId() + this.jams, 2) == 0 ? 90.0F : -90.0F;

                this.pivoting = PIVOT_TICKS;
                this.pivotYaw = open.stuck() ? Mth.wrapDegrees(this.lookedFor + side) : open.yaw();
            }
        }

        // 2度目からは、抜けた後にわざと別の方へ振る。振る側は個体で決める——同じ壁に並んだ2両が同じ側へ
        // 避けると、今度は互いが壁になる。
        if (this.jams >= 2 && this.detour <= 0) {
            this.detour = DETOUR_TICKS;
            this.detourSide = Math.floorMod(this.ground.getId() + this.jams, 2) == 0 ? 1 : -1;
        }
    }

    /**
     * 後ろへ下がれる距離（ブロック、車体の後ろ端から）。<b>後退は目隠しだった</b>——壁の前で詰まった戦車が、後ろの浜から
     * 湖へ下がって入り、岸へ上がれなくなった（2026-09-13）。後ろ向きに触角を1本伸ばし、車体が乗っている分を引く。
     */
    private double rearRoom() {
        float behind = Mth.wrapDegrees(this.ground.getYRot() + 180.0F);
        double reach = this.ground.getRearReach();

        return Obstacles.clearance(this.ground, behind, reach + BACKING_ROOM + 1.5, Obstacles.DROP, this.submerged())
                - reach;
    }

    private void replanNow() {
        this.forced = true;
        this.replan = 0;
        this.retryEmptyAt = 0;
        this.path = List.of();
        this.recheck = 0;
    }

    /**
     * 進路の先に、自分より遅い味方がいれば、その反対側へ振る。
     *
     * <p><b>触角は地形しか見ない。</b> だから味方の車体は「開いている方角」に見え、5両が同じ持ち場へ向かえば
     * 先頭の後ろで押し合いが始まる——押し合いは立ち往生として検出され、後退し、また同じ列に戻る。
     *
     * <p><b>追い付いているときだけよける。</b> 同じくらいの速さで前を走る味方は道を開けているのであって、塞いで
     * いるのではない——それをよけると、隊列の後ろの車両が全員ジグザグに走る。
     */
    private float dodge(float course) {
        MinecraftServer server = this.ground.getServer();

        if (server == null) {
            return course;
        }

        double radians = Math.toRadians(course);
        double dx = -Math.sin(radians);
        double dz = Math.cos(radians);
        // 右手。yaw が増える側であり、{@code course + 90} の向きそのもの。
        double rightX = -Math.cos(radians);
        double rightZ = -Math.sin(radians);
        Vec3 here = this.ground.position();
        double slower = Math.max(Math.abs(this.ground.getSpeed()) * MATE_SLOWER, STOPPED);

        for (Bots.Fighter fighter : Bots.combatants(server)) {
            Entity mate = fighter.entity();

            if (mate == this.ground || !this.team.equals(fighter.team())) {
                continue;
            }

            Vec3 to = mate.position().subtract(here);
            double range = to.horizontalDistance();

            if (range > MATE_REACH || range < 1.0E-3) {
                continue;
            }

            if ((to.x * dx + to.z * dz) / range < Math.cos(Math.toRadians(MATE_ANGLE))) {
                continue;
            }

            if (speedOf(mate) >= slower) {
                continue;
            }

            return Mth.wrapDegrees(course + (to.x * rightX + to.z * rightZ > 0.0 ? -MATE_TURN : MATE_TURN));
        }

        return course;
    }

    private static double speedOf(Entity entity) {
        return entity instanceof GroundVehicleEntity vehicle ? Math.abs(vehicle.getSpeed())
                : entity.getDeltaMovement().horizontalDistance();
    }

    /**
     * 次に向かう通過点。道が無ければ目的地そのもの。
     *
     * <p>道は数秒に1回しか引き直さない（{@code timing.replan}）。探索は触角より桁で高く、そして建物は動かない。
     * 引き直すのは周期のほかに、目的地が動いたとき、危険の嫌い方が変わったとき、詰まったとき、行動が変わったとき、
     * 水に沈んだとき。
     */
    private Vec3 waypoint(Vec3 goal, Vec3 here, MovementIntent intent, TacticalMap map) {
        MinecraftServer server = this.ground.getServer();
        boolean due = --this.replan <= 0 || this.forced
                || (this.path.isEmpty() && this.age >= this.retryEmptyAt)
                || goal.subtract(this.pathGoal).horizontalDistance() > REGOAL
                || Math.abs(intent.riskWeight() - this.plannedRisk) > RISK_CHANGE;

        if (due && server != null) {
            if (AiBudget.spend(server, AiBudget.Kind.PLAN)) {
                Route planned = RoutePlanner.plan(this.ground, goal, map, intent.riskWeight(), this.weights,
                        AiConfig.budget().planExpansions(), this.cellCost(intent.riskWeight()));
                boolean announce = this.announcedGoal == null
                        || goal.subtract(this.announcedGoal).horizontalDistance() > REGOAL;

                this.route = planned;
                this.path = planned.points();
                this.leg = 0;
                this.pathGoal = goal;
                this.plannedRisk = intent.riskWeight();
                this.forced = false;
                this.replan = AiConfig.timing().replan();
                this.retryEmptyAt = this.age + EMPTY_RETRY;

                if (announce) {
                    this.announcedGoal = goal;

                    if (this.onRoute != null) {
                        this.onRoute.accept(planned);
                    }
                }

                // 道が1歩も引けない——出発点の周りのどのマスへも入れない。屋根の上か、建物の中か、穴の底だ。
                // 触角も同じ物差しで塞がっているので、前へ出ても詰まるだけ。続けば、来た道を戻る。
                if (planned.points().isEmpty() && goal.subtract(here).horizontalDistance() > TacticalMap.CELL * 2) {
                    if (++this.emptyPlans >= ESCAPE_EMPTY && this.age >= this.escapeReadyAt) {
                        this.startEscape();
                    }
                } else {
                    this.emptyPlans = 0;
                }
            } else {
                // 予算切れ。古い道を走り続け、次の tick にまた頼む。
                this.replan = 1;
            }
        }

        // 通り過ぎた点は捨てる。高さは見ない——道は地図であって、坂の登り方は車両が知っている。
        int passed = this.leg;

        while (this.leg < this.path.size()
                && this.path.get(this.leg).subtract(here).horizontalDistance() < REACHED) {
            this.leg++;
        }

        // 次の点へ向かう区間は、まだ触角で確かめていない。
        if (this.leg != passed) {
            this.recheck = 0;
        }

        if (this.leg >= this.path.size()) {
            return goal;
        }

        Vec3 point = this.path.get(this.leg);

        return new Vec3(point.x, here.y, point.z);
    }

    /** 道の探索がマスごとに足す代償。抜け出せなかったマスと、試合を重ねて覚えた地図。 */
    private RoutePlanner.CellCost cellCost(double riskWeight) {
        MapMemory memory = MapMemory.of(this.ground.level());
        int now = this.age;

        return (cellX, cellZ) -> (this.crumbs.avoided(cellX, cellZ, now) ? AVOID : 0.0)
                + (memory == null ? 0.0 : memory.cost(cellX, cellZ, this.mapWeights, riskWeight));
    }

    // ------------------------------------------------------------------
    // 抜け出す
    // ------------------------------------------------------------------

    /**
     * 抜け出し始める。嵌まったマスと、進もうとしていた先のマスを避けると決め、地図に「ここは駄目だ」と残し、
     * 跡を辿って戻る準備をする。
     */
    private void startEscape() {
        Vec3 here = this.ground.position();
        double radians = Math.toRadians(this.ground.getYRot());
        int until = this.age + AVOID_TICKS;

        // 道の探索は「通れる」と言い続けるので、ここは通れても高いと教える。
        this.crumbs.avoid(TacticalMap.cellOf(here.x), TacticalMap.cellOf(here.z), 1, until);
        this.crumbs.avoid(TacticalMap.cellOf(here.x - Math.sin(radians) * TacticalMap.CELL),
                TacticalMap.cellOf(here.z + Math.cos(radians) * TacticalMap.CELL), 1, until);
        this.trace.trapped();

        // 跡が無い（出てすぐ嵌まった）ときの行き先。車尾の側から、一番開いている方角へ。
        Obstacles.Heading open = Obstacles.around(this.ground, Mth.wrapDegrees(this.ground.getYRot() + 180.0F),
                Obstacles.look(), Obstacles.ESCAPE_DROP, this.submerged());
        double away = Math.toRadians(open.yaw());

        this.escapeTo = here.add(-Math.sin(away) * ESCAPE_BACK, 0.0, Math.cos(away) * ESCAPE_BACK);
        this.escaping = ESCAPE_TICKS;
        this.escapeFrom = here;
        this.escapeCheck = here;
        this.escapeCheckAt = this.age + ESCAPE_CHECK;
        this.escapeFlipped = false;
        this.escapeFlips = 0;
        this.emptyPlans = 0;
        this.backing = 0;
        this.pivoting = 0;
        this.detour = 0;
        this.deflection = 0.0F;
        this.recheck = 0;
    }

    /**
     * 抜け出している1 tick。跡を新しい方から辿り、{@value #ESCAPE_BACK} ブロック戻れたら終わる。
     *
     * <p><b>戻る先が後ろにあれば、後ろ向きのまま戻る。</b> 入ってきた道は入った向きのまま戻るのが一番確かだ——建物の
     * 中で向きを変える余地は無いかもしれない。後進では舵が逆に効く（{@code GroundVehicleEntity.steer}）ので、車尾を
     * 点へ向けるには舵を反対に当てる。動けなければ前後を入れ替えて試し、それも駄目なら諦めて、味方に付いて行く方へ
     * 回す。
     */
    private DriveCommand escape(Vec3 here) {
        this.escaping--;

        Vec3 target = this.crumbs.retreat(here, REACHED);

        if (target == null) {
            target = this.escapeTo;
        }

        if (this.escaping <= 0 || target == null || here.subtract(this.escapeFrom).horizontalDistance() >= ESCAPE_BACK
                || target.subtract(here).horizontalDistance() < REACHED) {
            this.endEscape();

            return new DriveCommand(0.0F, 0.0F, false);
        }

        if (this.age >= this.escapeCheckAt) {
            if (here.subtract(this.escapeCheck).horizontalDistance() < ESCAPE_MOVED) {
                if (++this.escapeFlips > ESCAPE_FLIPS) {
                    this.jams = Math.max(this.jams, LOST_JAMS);
                    this.endEscape();

                    return new DriveCommand(0.0F, 0.0F, false);
                }

                this.escapeFlipped = !this.escapeFlipped;
            }

            this.escapeCheck = here;
            this.escapeCheckAt = this.age + ESCAPE_CHECK;
        }

        float toward = yawTo(this.ground, target);
        float error = Mth.wrapDegrees(toward - this.ground.getYRot());

        boolean reverse = (Math.abs(error) > REVERSE_ANGLE) != this.escapeFlipped;

        // 後ろ向きに戻る間も後ろを見る。跡と跡の間を真っ直ぐ下がると、浜の縁を掠めることがある。
        if (reverse && this.age % LOOK_SHORT == 0 && this.rearRoom() < BACKING_STOP) {
            this.escapeFlipped = !this.escapeFlipped;
            reverse = false;
        }

        if (reverse) {
            float rear = Mth.wrapDegrees(toward - this.ground.getYRot() - 180.0F);

            return new DriveCommand(-0.6F, -Mth.clamp(rear / FULL_STEER, -1.0F, 1.0F), false);
        }

        // 前向きに戻る。屋根から降りる車両のために、普段より深い落差を許す。
        if (--this.recheck <= 0 || Math.abs(Mth.wrapDegrees(toward - this.lookedFor)) > LOOK_TURN) {
            this.look(target, toward, Obstacles.ESCAPE_DROP, false);
        }

        float steering = Mth.wrapDegrees(toward + this.deflection - this.ground.getYRot());

        return new DriveCommand(Math.abs(steering) > TURN_FIRST ? 0.3F : 0.6F,
                Mth.clamp(steering / FULL_STEER, -1.0F, 1.0F), false);
    }

    private void endEscape() {
        this.escaping = 0;
        this.escapeTo = null;
        this.escapeReadyAt = this.age + ESCAPE_COOLDOWN;
        this.emptyPlans = 0;
        this.deflection = 0.0F;
        this.wasAt = this.ground.position();
        this.stall.release();
        this.replanNow();
    }

    // ------------------------------------------------------------------
    // 迷ったら付いて行く
    // ------------------------------------------------------------------

    /**
     * 迷っているなら、付いて行く相手。迷っていなければ null（目的地を自分で目指す）。
     *
     * <p><b>触角と道の探索が両方とも出口を見付けられない場所はある</b>——探索の予算を超える回り道、4ブロック
     * 格子より細い通路。そういう場所でも、<em>既に抜けた者</em>はそこにいる。付いて行くのは
     * {@link #FOLLOW_TICKS} の間だけで、終われば自分の道を引き直す。
     */
    @Nullable
    private Entity guide(Vec3 objective, Vec3 here) {
        if (this.following > 0) {
            this.following--;

            if (this.following > 0 && this.stillGuiding(this.leader, here)) {
                return this.leader;
            }

            this.release();
        }

        this.trackProgress(objective, here);

        if (!this.lost() || this.age % STUCK_EVERY != 0) {
            return null;
        }

        Entity found = this.findGuide(here, objective);

        if (found != null) {
            this.leader = found;
            this.following = FOLLOW_TICKS;
            this.path = List.of();
        }

        return found;
    }

    private void trackProgress(Vec3 objective, Vec3 here) {
        if (this.age % STUCK_EVERY != 0) {
            return;
        }

        // 目的地が変わったなら、前の距離は比べる物ではない。
        if (objective.subtract(this.measuredFor).horizontalDistance() > REGOAL) {
            this.forgetProgress();
            this.measuredFor = objective;
        }

        double gap = objective.subtract(here).horizontalDistance();

        if (this.bestGap == Double.MAX_VALUE) {
            this.bestGap = gap;
        } else if (gap < this.bestGap - PROGRESS_STEP) {
            this.bestGap = gap;
            this.sinceProgress = 0;
            this.proven = true;
        } else {
            this.sinceProgress += STUCK_EVERY;
        }
    }

    /**
     * 一番近い案内役。味方の人（歩いていても乗っていても）か、{@link BotPilot#leads} を満たす味方の AI。
     *
     * <p>名簿（{@link Bots#combatants}）から引くので世界を掃かない。空を飛んでいる機体は選ばない——地形を無視して
     * 進む物の後ろを戦車が走っても、同じ所で詰まるだけだ。
     */
    @Nullable
    private Entity findGuide(Vec3 here, Vec3 objective) {
        MinecraftServer server = this.ground.getServer();

        if (server == null) {
            return null;
        }

        Entity best = null;
        double bestRange = FOLLOW_REACH;
        double mine = objective.subtract(here).horizontalDistance();

        for (Bots.Fighter fighter : Bots.combatants(server)) {
            Entity mate = fighter.entity();

            if (mate == this.ground || !this.team.equals(fighter.team()) || !this.canGuide(mate)) {
                continue;
            }

            // <b>案内役は自分より目的地に近い者だけ。</b> 陣地で待っている人に付いて行けば、迷子は持ち場から
            // 陣地へ連れ戻され、着いた先でまた迷う。
            if (objective.subtract(mate.position()).horizontalDistance() > mine - GUIDE_AHEAD) {
                continue;
            }

            double range = mate.position().subtract(here).horizontalDistance();

            if (range <= FOLLOW_GAP || range >= bestRange) {
                continue;
            }

            best = mate;
            bestRange = range;
        }

        return best;
    }

    private boolean stillGuiding(@Nullable Entity mate, Vec3 here) {
        return mate != null && this.canGuide(mate)
                && mate.position().subtract(here).horizontalDistance() <= FOLLOW_REACH * 1.5;
    }

    private boolean canGuide(Entity mate) {
        if (mate.isRemoved() || !mate.isAlive() || mate.level() != this.ground.level()) {
            return false;
        }

        if (!(mate instanceof VehicleEntityBase machine)) {
            return mate instanceof Player;
        }

        if (machine.isWrecked() || (machine instanceof AircraftEntity && !machine.onGround())) {
            return false;
        }

        // 人が運転している車両は人として扱う。AI の車両は、ちゃんと進めている物だけ。
        if (machine.getControllingPassenger() instanceof Player) {
            return true;
        }

        BotPilot pilot = machine.getPilot();

        return pilot != null && pilot.leads();
    }

    private void release() {
        if (this.leader == null && this.following <= 0) {
            return;
        }

        this.leader = null;
        this.following = 0;
        this.path = List.of();
        this.jams = 0;
        this.forgetProgress();
    }

    private void forgetProgress() {
        this.bestGap = Double.MAX_VALUE;
        this.sinceProgress = 0;
        this.proven = false;
    }

    /**
     * 戦域の外から戻る先。行き先が戦域の中ならそこへ、そうでなければ会場の中心へ。
     *
     * <p>持ち場を捨てて中心へ向かう理由は無い——首輪が止めたいのは外へ出て行くことで、戦域の中にある持ち場へ
     * 帰ることではない。
     */
    private Vec3 homeward(MovementIntent intent) {
        MinecraftServer server = this.ground.getServer();

        if (server == null) {
            return this.ground.position();
        }

        MatchState state = MatchState.of(server);
        Vec3 centre = Bots.centre(server, state);
        Vec3 destination = intent.destination();

        return destination != null
                && destination.subtract(centre).horizontalDistance() <= Bots.arenaRadius(server, state)
                ? destination : centre;
    }

    /** その点を向く方位（度、{@code yRot} と同じ取り方）。 */
    public static float yawTo(Entity from, Vec3 goal) {
        Vec3 step = goal.subtract(from.position());

        return (float) (Mth.atan2(step.z, step.x) * (180.0 / Math.PI)) - 90.0F;
    }
}
