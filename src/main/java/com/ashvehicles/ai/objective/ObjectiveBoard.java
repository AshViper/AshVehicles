package com.ashvehicles.ai.objective;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.ai.BotPilot;
import com.ashvehicles.ai.battlefield.TeamIntel;
import com.ashvehicles.ai.decision.ParameterSet;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.match.Bots;
import com.ashvehicles.match.MatchPoint;
import com.ashvehicles.match.MatchState;
import com.ashvehicles.match.MatchTeam;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 陣営1つの持ち場の一覧と、誰がどこへ行くか。
 *
 * <p><b>拠点の状況を周期ごとに読み直し、点数を付け、陣営の AI に配る。</b> 読むのは帳簿
 * （{@link MatchState}）の拠点と、名簿（{@link Bots#combatants}）の敵味方の位置と、陣営の脅威マップ
 * （{@link TeamIntel}）だけ。周期は {@code timing.objective}（既定 40 tick）で、1陣営に1回——40両がめいめい
 * 全拠点を評価し直すことはしない。
 *
 * <p><b>奪われたら考え直させる。</b> 自陣の拠点を失った瞬間と、取られかけ始めた瞬間に {@link #revision} を
 * 進める。各 AI は判断の周期を待たずにそれを見て判断を前倒しする——「C を奪われた」のに1秒前の「B を取る」を
 * 続ける車両はいない。
 *
 * <p><b>配り方は「点数に比例した枠」。</b> 一番点の高い拠点へ全員が行くと、20両が1箇所で団子になり、残りの拠点は
 * 誰も踏まない（元の {@code Bots.duties} が枠の数で解いていた問題）。枠は点数に比例して配り、合計を AI の数に揃える
 * （{@link #slots}）。脅威の無い自陣の拠点は、取るべき拠点に2両ずつ回してまだ余るときだけ1枠。各 AI は「その AI にとっての
 * 点数」（距離・経路の危険・役割を入れた物）で空いている枠から選び、枠が埋まっていれば取るべき拠点のうち一番良い物へ行く。
 *
 * <p><b>一度決めた持ち場は簡単に変えない。</b> 今の持ち場の点数が一番良い物より {@code objective.sticky} 以上
 * 悪くならない限り、そのまま。<b>そして配る順はエンティティ ID</b>——名簿の順で配ると、1両失うたびに生き残り
 * 全員の行き先がずれる（[[bots-are-a-pilot-object-on-the-vehicle]] の分担の項）。
 */
public final class ObjectiveBoard {
    /** 敵味方を拠点の近くと数える、円の外側の余裕（ブロック）。 */
    private static final double NEAR_MARGIN = 24.0;

    /** 拠点の無い試合で、会場の真ん中を持ち場にするときの半径（ブロック）。 */
    private static final double CENTRE_RADIUS = 24.0;

    /** 敵陣の旗を持ち場にするときの半径（ブロック）。 */
    private static final double BASE_RADIUS = 16.0;

    /** 持ち場の変わった AI 1両。記録（{@code ObjectiveSelected}）のため。 */
    public record Change(BotPilot pilot, ObjectiveState objective) {
    }

    /** 拠点1つの、この陣営にとっての履歴。 */
    private static final class History {
        boolean held;
        long lostTick = Long.MIN_VALUE;
        boolean beingTaken;

        @Nullable
        String lastOwner;
    }

    private final String team;
    private final Long2ObjectOpenHashMap<History> history = new Long2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<String> assignments = new Int2ObjectOpenHashMap<>();
    private final List<Change> changes = new ArrayList<>();

    private List<ObjectiveState> states = List.of();
    private ObjectiveScoring.Weights weights = ObjectiveScoring.Weights.of(ParameterSet.EMPTY);
    private double arena = 500.0;
    private int revision;

    public ObjectiveBoard(String team) {
        this.team = team;
    }

    /** 今の持ち場の一覧。 */
    public List<ObjectiveState> states() {
        return this.states;
    }

    /** 考え直させる合図の番号。持ち場の状況が変わるたびに進む。 */
    public int revision() {
        return this.revision;
    }

    /** 前回の配り直しで持ち場の変わった AI。読んだら空になる。 */
    public List<Change> drainChanges() {
        List<Change> drained = List.copyOf(this.changes);

        this.changes.clear();

        return drained;
    }

    /** まだ持ち場を配られていない AI がいるか。出たばかりの車両を周期まで待たせないため。 */
    public boolean hasUnassigned(List<BotPilot> pilots) {
        for (BotPilot pilot : pilots) {
            if (!this.assignments.containsKey(pilot.vehicle().getId())) {
                return true;
            }
        }

        return false;
    }

    /**
     * その AI の持ち場。配られていなければ、その場で一番良い物を選ぶ（枠は数えない——次の配り直しで正す）。
     */
    @Nullable
    public ObjectiveState assignmentOrBest(BotPilot pilot, TeamIntel intel) {
        String id = this.assignments.get(pilot.vehicle().getId());

        if (id != null) {
            for (ObjectiveState state : this.states) {
                if (state.id().equals(id)) {
                    return state;
                }
            }
        }

        ObjectiveState best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (ObjectiveState state : this.states) {
            double score = this.botScore(state, pilot, intel);

            if (score > bestScore) {
                bestScore = score;
                best = state;
            }
        }

        return best;
    }

    /**
     * 読み直して、配り直す。
     *
     * @param pilots     この陣営の生きた AI
     * @param parameters 陣営の版のパラメータ（{@code objective.*}）
     * @param now        ゲーム時刻
     */
    public void refresh(MinecraftServer server, MatchState state, TeamIntel intel, List<BotPilot> pilots,
            ParameterSet parameters, long now) {
        this.weights = ObjectiveScoring.Weights.of(parameters);
        double radius = Bots.arenaRadius(server, state);

        this.arena = radius > 0.0 ? radius : 500.0;

        Vec3 middle = Bots.centre(server, state);
        List<Bots.Fighter> fighters = Bots.combatants(server);
        List<ObjectiveState> built = new ArrayList<>();

        for (MatchPoint point : state.points().values()) {
            History past = this.history.computeIfAbsent(point.pos().asLong(), key -> new History());
            String owner = point.owner();

            // 初期配置がこの陣営なら、握っていた拠点として数える。そこを奪われたら奪還だ。
            if (this.team.equals(owner) || this.team.equals(point.home())) {
                past.held = true;
            }

            if (past.lastOwner != null && past.lastOwner.equals(this.team) && !this.team.equals(owner)) {
                past.lostTick = now;
                this.revision++;
            }

            boolean beingTaken = this.team.equals(owner) && point.taking() != null
                    && !this.team.equals(point.taking()) && point.progress() > 0;

            if (beingTaken && !past.beingTaken) {
                this.revision++;
            }

            past.beingTaken = beingTaken;
            past.lastOwner = owner;

            Vec3 centre = Vec3.atCenterOf(point.pos());

            built.add(this.scored(new ObjectiveState(point.name(), point.pos(), centre, point.radius(),
                    CaptureState.of(point, this.team), owner, point.taking(), point.fraction(), false, beingTaken,
                    past.held && owner != null && !this.team.equals(owner), past.lostTick,
                    this.count(fighters, centre, point.radius(), true), this.count(fighters, centre, point.radius(),
                            false),
                    this.threatAround(intel, centre, point.radius()), this.strategic(centre, middle), 0.0), now));
        }

        // 拠点の無い試合。AI は敵陣の旗へ向かって前へ出る——止まったまま撃ち合う試合にしないため。
        if (built.isEmpty()) {
            for (MatchTeam other : state.teams().values()) {
                if (other.id().equals(this.team)) {
                    continue;
                }

                for (BlockPos spawn : other.spawns()) {
                    Vec3 centre = Vec3.atCenterOf(spawn);

                    built.add(this.scored(new ObjectiveState("base:" + other.id(), spawn, centre, BASE_RADIUS,
                            CaptureState.ENEMY, other.id(), null, 0.0F, true, false, false, Long.MIN_VALUE,
                            this.count(fighters, centre, BASE_RADIUS, true),
                            this.count(fighters, centre, BASE_RADIUS, false),
                            this.threatAround(intel, centre, BASE_RADIUS), this.strategic(centre, middle), 0.0), now));
                }
            }
        }

        if (built.isEmpty()) {
            built.add(this.scored(new ObjectiveState("centre", BlockPos.containing(middle), middle, CENTRE_RADIUS,
                    CaptureState.NEUTRAL, null, null, 0.0F, false, false, false, Long.MIN_VALUE, 0, 0,
                    this.threatAround(intel, middle, CENTRE_RADIUS), 1.0, 0.0), now));
        }

        this.states = List.copyOf(built);
        this.assign(pilots, intel);
    }

    private ObjectiveState scored(ObjectiveState state, long now) {
        return state.withScore(ObjectiveScoring.teamScore(state, this.weights, now));
    }

    /** 枠を配る。 */
    private void assign(List<BotPilot> pilots, TeamIntel intel) {
        int size = this.states.size();
        double[] scores = new double[size];
        boolean[] quiet = new boolean[size];
        int[] used = new int[size];

        for (int at = 0; at < size; at++) {
            scores[at] = this.states.get(at).score();
            quiet[at] = quiet(this.states.get(at));
        }

        int[] slots = slots(scores, quiet, pilots.size());
        List<BotPilot> ordered = new ArrayList<>(pilots);

        ordered.sort(Comparator.comparingInt(pilot -> pilot.vehicle().getId()));

        Int2ObjectOpenHashMap<String> next = new Int2ObjectOpenHashMap<>();

        // 1周目: 今の持ち場がまだ十分に良ければ、そのまま。
        for (BotPilot pilot : ordered) {
            String current = this.assignments.get(pilot.vehicle().getId());
            int index = this.indexOf(current);

            if (index < 0 || used[index] >= slots[index]) {
                continue;
            }

            double kept = this.botScore(this.states.get(index), pilot, intel);
            double best = Double.NEGATIVE_INFINITY;

            for (ObjectiveState state : this.states) {
                best = Math.max(best, this.botScore(state, pilot, intel));
            }

            if (kept >= best - this.weights.sticky()) {
                next.put(pilot.vehicle().getId(), current);
                used[index]++;
            }
        }

        // 2周目: 残りを、空いている枠のうち自分にとって一番良い物へ。枠が全部埋まっていれば一番良い物へ。
        for (BotPilot pilot : ordered) {
            int id = pilot.vehicle().getId();

            if (next.containsKey(id)) {
                continue;
            }

            int chosen = -1;
            int fallback = -1;
            int anywhere = -1;
            double chosenScore = Double.NEGATIVE_INFINITY;
            double fallbackScore = Double.NEGATIVE_INFINITY;
            double anywhereScore = Double.NEGATIVE_INFINITY;

            for (int at = 0; at < size; at++) {
                double score = this.botScore(this.states.get(at), pilot, intel);

                if (score > anywhereScore) {
                    anywhereScore = score;
                    anywhere = at;
                }

                // 枠が埋まっていたら、脅威の無い自陣の拠点ではなく、取るべき拠点か守るべき拠点へ。
                if (!quiet[at] && score > fallbackScore) {
                    fallbackScore = score;
                    fallback = at;
                }

                if (used[at] < slots[at] && score > chosenScore) {
                    chosenScore = score;
                    chosen = at;
                }
            }

            int index = chosen >= 0 ? chosen : fallback >= 0 ? fallback : anywhere;

            if (index < 0) {
                continue;
            }

            ObjectiveState state = this.states.get(index);

            next.put(id, state.id());
            used[index]++;

            if (!state.id().equals(this.assignments.get(id))) {
                this.changes.add(new Change(pilot, state));
            }
        }

        this.assignments.clear();
        this.assignments.putAll(next);
    }

    /** 脅威の無い自陣の拠点。取られかけてもおらず、近くに敵もいない。 */
    private static boolean quiet(ObjectiveState state) {
        return state.state() == CaptureState.FRIENDLY && !state.beingTaken() && state.enemiesNear() == 0;
    }

    /**
     * 拠点ごとの枠（2026-09-14 に直した）。脅威の無い自陣の拠点（quiet）は、それ以外の拠点1つに2両ずつ回してまだ AI が余るときだけ、
     * 点数の高い物から1枠。残りの AI をそれ以外の拠点へ点数に比べて配り、端数の大きい順に1ずつ足して合計を揃える。取るべき・守るべき
     * 拠点には、AI が足りなくても1枠はある。全部が脅威の無い自陣の拠点なら1枠ずつ。
     *
     * <p>以前は「AI の数 × 点数の割合の切り捨て」で、7両・3拠点では枠が5つしか無く、あふれた2両は枠を見ずに自分にとって一番点の高い
     * 拠点へ行った——近くの取った拠点に。そこでは守る物も取る物も無く、AI は探索と前進を繰り返していた（2026-09-13〜14 の8戦闘の記録で、
     * 戦車が拠点の仕事をしていない時間の9割近く、IFV の9割が取った拠点の持ち場で、戦車のその6割強と IFV のほぼ全部は、まだ取れて
     * いない拠点がある間だった）。
     */
    public static int[] slots(double[] scores, boolean[] quiet, int pilots) {
        int size = scores.length;
        int[] slots = new int[size];
        int active = 0;
        int guards = 0;
        double total = 0.0;

        for (int at = 0; at < size; at++) {
            if (quiet[at]) {
                guards++;
            } else {
                active++;
                total += Math.max(scores[at], 0.05);
            }
        }

        if (active == 0) {
            Arrays.fill(slots, 1);

            return slots;
        }

        int guarded = Math.max(0, Math.min(guards, pilots - 2 * active));

        for (int left = guarded; left > 0; left--) {
            int best = -1;

            for (int at = 0; at < size; at++) {
                if (quiet[at] && slots[at] == 0 && (best < 0 || scores[at] > scores[best])) {
                    best = at;
                }
            }

            slots[best] = 1;
        }

        int share = pilots - guarded;
        double[] remainders = new double[size];
        int given = 0;

        for (int at = 0; at < size; at++) {
            if (quiet[at]) {
                continue;
            }

            double exact = share * Math.max(scores[at], 0.05) / total;

            slots[at] = Math.max(1, (int) Math.floor(exact));
            remainders[at] = exact - Math.floor(exact);
            given += slots[at];
        }

        while (given < share) {
            int best = -1;

            for (int at = 0; at < size; at++) {
                if (!quiet[at] && (best < 0 || remainders[at] > remainders[best])) {
                    best = at;
                }
            }

            slots[best]++;
            remainders[best] = -1.0;
            given++;
        }

        return slots;
    }

    private int indexOf(@Nullable String id) {
        if (id == null) {
            return -1;
        }

        for (int at = 0; at < this.states.size(); at++) {
            if (this.states.get(at).id().equals(id)) {
                return at;
            }
        }

        return -1;
    }

    private double botScore(ObjectiveState state, BotPilot pilot, TeamIntel intel) {
        Vec3 from = pilot.vehicle().position();
        double risk = intel.threats().riskAlong(from, state.centre(), 6);

        return ObjectiveScoring.botScore(state, from, risk, this.arena, this.weights, pilot.role(), pilot.profile());
    }

    /** 円の近くにいる味方か敵の数。 */
    private int count(List<Bots.Fighter> fighters, Vec3 centre, double radius, boolean friends) {
        double reach = radius + NEAR_MARGIN;
        int counted = 0;

        for (Bots.Fighter fighter : fighters) {
            Entity entity = fighter.entity();

            if (this.team.equals(fighter.team()) != friends || fighter.team().isEmpty()) {
                continue;
            }

            // 上空を回る AI の航空機は拠点を取らない（{@code Deathmatch.capture}）ので、守りの薄さにも脅威にも数えない。
            if (entity instanceof AircraftEntity aircraft && aircraft.isBot()) {
                continue;
            }

            double dx = entity.getX() - centre.x;
            double dz = entity.getZ() - centre.z;

            if (dx * dx + dz * dz <= reach * reach) {
                counted++;
            }
        }

        return counted;
    }

    /** 円の中心と4方の縁の危険の平均。 */
    private double threatAround(TeamIntel intel, Vec3 centre, double radius) {
        double total = intel.threats().danger(centre.x, centre.z);

        total += intel.threats().danger(centre.x + radius, centre.z);
        total += intel.threats().danger(centre.x - radius, centre.z);
        total += intel.threats().danger(centre.x, centre.z + radius);
        total += intel.threats().danger(centre.x, centre.z - radius);

        return total / 5.0;
    }

    /** 会場の中心に近いほど高い（0〜1）。真ん中の拠点は両陣営の前線で、握れば押し上げの足場になる。 */
    private double strategic(Vec3 at, Vec3 middle) {
        double dx = at.x - middle.x;
        double dz = at.z - middle.z;

        return 1.0 - Math.min(Math.sqrt(dx * dx + dz * dz) / Math.max(this.arena, 1.0), 1.0);
    }
}
