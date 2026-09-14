package com.ashvehicles.ai.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

import com.ashvehicles.ai.battlefield.TacticalCell;
import com.ashvehicles.ai.battlefield.TacticalMap;
import com.ashvehicles.ai.decision.ParameterSet;
import com.ashvehicles.entity.GroundVehicleEntity;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 目的地まで<b>実際に通れて、なるべく危なくない道</b>を探す。粗い格子の上の A*。
 *
 * <p><b>最短の道は最適の道ではない。</b> 真っ直ぐ行けば敵から丸見えになる道より、建物の裏と高台を回る道の方が
 * 早く着く——着く前に撃破されなければ。だから1歩の代償は距離だけでなく、戦術格子（{@link TacticalMap}）の
 * 危険・露出・地形と、学習した地図（{@link CellCost}）を足し、遮蔽・高所・拠点を引く:
 *
 * <pre>
 * cost = distance × max(1 + threat + exposure + terrain + unknown + learned − min((cover + height) × danger + strategic, 上限), 下限)
 *      + dig + distance × water
 * </pre>
 *
 * <p><b>遮蔽と高所で引くのは、危ない所だけ</b>（{@code danger} = そのマスの危険と露出）。撃たれない所で建物の脇と
 * 高い所を割り引くと、道は壁沿いと屋根の上を縫い、まっすぐ行ける野原で蛇行し、屋根に登って降りられなくなった
 * （2026-09-13）。
 *
 * <p><b>引く分には上限がある</b>（{@value #BONUS_CAP}）。A* は1歩の代償が負になると壊れる。見積もり
 * （{@link #heuristic}）は残り距離をそのまま使う——遮蔽で割り引かれたマスでは本当の代償を超えうるが、その分だけ
 * 探索は目的地へ向かって真っ直ぐ伸び、同じ予算で遠くまで届く。拠点へ向かう戦車に要るのはそちらだ。
 *
 * <p><b>通れるかの物差しは触角と同じ</b>（{@link Obstacles#probe}）——登坂能力、車体の幅、破壊力。触角と道が
 * 別の基準で地形を読むと、「道はあると言ったのに1歩目で壁だった」が起きる（[[bots-are-a-pilot-object-on-the-vehicle]]
 * の道の探索の項）。<b>1歩は掃いて確かめる</b>——マスの中心から次のマスの中心まで、1ブロックおきに、車体の幅を
 * 1ブロック未満の刻みで。以前はマスの中心と左右2点だけを読んでいたので、中心と中心の間に立つ1ブロックの塀や
 * 柱の列を「通れる」と言い、車両はそこで止まった（2026-09-13）。同じ列を何度も読むので、1回の探索の間は読みを
 * 覚えておく（{@link #PROBES}）。
 *
 * <p><b>深い水は通れるが、とても高い</b>（{@code route.water}）。水の底を走る車両は撃たれても逃げられず、岸が
 * 急なら上がれない。他に道が無いときだけ渡る。
 *
 * <p><b>引いた道は真っ直ぐに均す</b>（{@link #smooth}）。8方向の格子の上の道は、斜め45度でない直線を「横・斜め・
 * 横・斜め」の階段で表すので、そのまま追うと車両は通過点ごとに首を振る。車体の幅で掃いて通れて、危険も水も
 * 学習した代償も増えないなら、間の通過点を飛ばす。
 *
 * <p><b>危険をどれだけ嫌うかは呼び手が決める</b>（{@code riskWeight}）。拠点へ向かう戦車は 1、撤退する車両は
 * 2——同じ地図から違う道が出る。
 *
 * <p><b>予算で打ち切る</b>（{@code budget.planExpansions}）。届かなければ<em>一番近くまで行けたマス</em>までの
 * 道を返す。完全な道が無いときに何も返さないより、近付いてから考え直す方が戦車として正しい。ただしその行き止まりに
 * 水の底と、代償を高く付けたマスは選ばない——そこで考え直すことになるので。
 */
public final class RoutePlanner {
    /** 格子の目（ブロック）。戦術格子と同じ。 */
    private static final int CELL = TacticalMap.CELL;

    /** 出発点から離れてよいマス数。これを超える目的地へは「その方向へ行けるところまで」になる。 */
    private static final int RANGE = 40;

    /** 斜め移動の代償。まっすぐ2手より斜め1手を選ばせるための値。 */
    private static final double DIAGONAL = 1.41;

    /** 遮蔽・高所・拠点で引いてよい代償の上限（1歩の距離に対する割合）。 */
    private static final double BONUS_CAP = 0.4;

    /** 1歩の代償の下限（1歩の距離に対する割合）。 */
    private static final double LEAST = 1.0 - BONUS_CAP;

    /** 1歩を掃くときの、進む向きの刻み（ブロック）。1以下なら進む向きに厚み1ブロックの塀を必ず踏む。 */
    private static final double SWEEP_STEP = 1.0;

    /** 浅い水（車体が沈まない深さ）に付ける、深い水の代償の割合。 */
    private static final double SHALLOW = 0.25;

    /** 途中で打ち切った道の行き止まりに選ばない、追加の代償（{@link CellCost}）。 */
    private static final double DEAD_END_EXTRA = 3.0;

    /** 真っ直ぐに均すとき、一度に飛ばしてよい通過点の数。 */
    private static final int SMOOTH_REACH = 6;

    /** 均した線が、飛ばした通過点より危なくなってよい量。 */
    private static final double SMOOTH_DANGER = 0.05;

    /** 8方向。 */
    private static final int[] STEP_X = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] STEP_Z = {0, 0, 1, -1, 1, -1, 1, -1};

    /**
     * 1回の探索の間だけ使う、列の読みの覚え。鍵は列と、そこへ入る床の高さ（1/8 ブロック刻み。半ブロック刻みでは
     * ハーフブロックの上の床と下の床が同じ鍵になり、登れる段差と登れない段差を取り違える）。
     *
     * <p>探索はサーバースレッドで1本ずつ走るので使い回す。隣り合うマスの1歩は同じ列を何度も掃くので、覚えて
     * おかないと読む量が数倍になる。
     */
    private static final Long2ObjectOpenHashMap<Obstacles.Ground> PROBES = new Long2ObjectOpenHashMap<>();

    /**
     * マスへ入る1歩に足す代償（1歩の距離に対する倍率）。0なら何も足さない。負なら通りやすい所として引く
     * （引いた後の1歩は下限で止まる）。
     *
     * <p>足すのは地形の読みでは分からない物——<b>その車両が抜け出せなかったマス</b>と、<b>試合を重ねて覚えた
     * 地図</b>（{@code learning/MapMemory}: 詰まった所、水に落ちた所、倒された所、すんなり抜けた所）。
     */
    @FunctionalInterface
    public interface CellCost {
        CellCost NONE = (cellX, cellZ) -> 0.0;

        double extra(int cellX, int cellZ);
    }

    /**
     * 代償の重み。版のパラメータ（{@code route.*}）から読む。
     *
     * @param threat    脅威マップの危険
     * @param exposure  今見えている敵からの露出
     * @param terrain   起伏・水・屋根
     * @param unknown   ロードされていない土地
     * @param cover     遮蔽（危ない所で引く）
     * @param height    高所（危ない所で引く）
     * @param strategic 拠点の近く（引く）
     * @param dig       薙ぎ倒して進むマス
     * @param water     車体が沈む深さの水を渡る1歩（1歩の距離に対する倍率）
     */
    public record Weights(double threat, double exposure, double terrain, double unknown, double cover,
            double height, double strategic, double dig, double water) {
        public static Weights of(ParameterSet parameters) {
            return new Weights(
                    parameters.get("route.threat", 2.0),
                    parameters.get("route.exposure", 1.5),
                    parameters.get("route.terrain", 0.5),
                    parameters.get("route.unknown", 0.5),
                    parameters.get("route.cover", 0.3),
                    parameters.get("route.height", 0.1),
                    parameters.get("route.strategic", 0.05),
                    parameters.get("route.dig", 2.0),
                    parameters.get("route.water", 8.0));
        }
    }

    private RoutePlanner() {
    }

    /** 探索中の1マス。 */
    private record Cell(long key, int x, int z, double floor, double cost, double score) {
    }

    /** 1歩を掃いた結果。薙ぎ倒す物があったか、一番深い水。 */
    private static final class Pass {
        boolean dug;
        int water;

        void reset() {
            this.dug = false;
            this.water = 0;
        }
    }

    /** 1回の探索で同じ物差し。 */
    private record Sweep(Level level, double climb, float crush, double half) {
        /** 覚えてある読み。無ければ読んで覚える。 */
        Obstacles.Ground at(double x, double z, double ground) {
            long key = ((long) (Mth.floor(x) & 0x3FFFFFF) << 38) | ((long) (Mth.floor(z) & 0x3FFFFFF) << 12)
                    | (Mth.floor((ground + 64.0) * 8.0) & 0xFFF);
            Obstacles.Ground known = PROBES.get(key);

            if (known == null) {
                known = Obstacles.probe(this.level, x, z, ground, this.climb, this.crush);
                PROBES.put(key, known);
            }

            return known;
        }

        /**
         * from から to までを車体の幅で掃く。通れれば終わりの床の高さ、通れなければ {@link Double#NaN}。
         * 薙ぎ倒す物と水の深さは {@code pass} に足していく。
         */
        double run(double fromX, double fromZ, double floor, double toX, double toZ, Pass pass) {
            double dx = toX - fromX;
            double dz = toZ - fromZ;
            double length = Math.sqrt(dx * dx + dz * dz);

            if (length < 1.0E-6) {
                return floor;
            }

            double unitX = dx / length;
            double unitZ = dz / length;
            double sideX = -unitZ;
            double sideZ = unitX;
            int steps = (int) Math.ceil(length / SWEEP_STEP);
            int lanes = Math.max(2, (int) Math.ceil(2.0 * this.half / Obstacles.LANE));
            double ground = floor;

            for (int step = 1; step <= steps; step++) {
                double along = Math.min(length, step * SWEEP_STEP);
                double x = fromX + unitX * along;
                double z = fromZ + unitZ * along;
                Obstacles.Ground centre = this.at(x, z, ground);

                if (centre.blocked()) {
                    return Double.NaN;
                }

                pass.dug |= centre.dug();
                pass.water = Math.max(pass.water, centre.water());

                for (int lane = 0; lane <= lanes; lane++) {
                    double offset = -this.half + lane * (2.0 * this.half / lanes);

                    if (Math.abs(offset) < 1.0E-3) {
                        continue;
                    }

                    Obstacles.Ground side = this.at(x + sideX * offset, z + sideZ * offset, centre.floor());

                    if (side.blocked()) {
                        return Double.NaN;
                    }

                    pass.dug |= side.dug();
                }

                ground = centre.floor();
            }

            return ground;
        }
    }

    /**
     * そこまでの道。
     *
     * @param map        戦術格子。準備されていなければ代償は距離と掘削と水と追加の代償だけになる
     * @param riskWeight 危険と露出の嫌い方。1 が標準
     * @param budget     展開してよいマスの数
     * @param extra      マスごとの追加の代償（{@link CellCost}）
     */
    public static Route plan(GroundVehicleEntity vehicle, Vec3 goal, TacticalMap map, double riskWeight,
            Weights weights, int budget, CellCost extra) {
        Level level = vehicle.level();
        double climb = vehicle.getStats().suspension().climbHeight();
        float crush = vehicle.getStats().crush().resistance();
        // <b>自分の幅で道を探す。</b> マスの中心だけを見た道は、車体が入らない隙間を「通れる」と言う。
        double half = Math.min(vehicle.getBbWidth() * 0.5 + Obstacles.MARGIN, CELL);
        Sweep sweep = new Sweep(level, climb, crush, half);
        int fromX = Mth.floor(vehicle.getX() / CELL);
        int fromZ = Mth.floor(vehicle.getZ() / CELL);
        int toX = Mth.floor(goal.x / CELL);
        int toZ = Mth.floor(goal.z / CELL);
        boolean tactical = map.ready();

        if (fromX == toX && fromZ == toZ) {
            return Route.none(riskWeight);
        }

        PROBES.clear();

        Long2LongOpenHashMap cameFrom = new Long2LongOpenHashMap();
        Long2DoubleOpenHashMap best = new Long2DoubleOpenHashMap();
        Long2DoubleOpenHashMap floors = new Long2DoubleOpenHashMap();
        Long2IntOpenHashMap waters = new Long2IntOpenHashMap();
        PriorityQueue<Cell> open = new PriorityQueue<>((left, right) ->
                Double.compare(left.score(), right.score()));

        best.defaultReturnValue(Double.MAX_VALUE);

        long start = cellKey(fromX, fromZ);

        open.add(new Cell(start, fromX, fromZ, vehicle.getY(), 0.0, heuristic(fromX, fromZ, toX, toZ)));
        best.put(start, 0.0);
        floors.put(start, vehicle.getY());

        // 途中で打ち切ったときの行き先の候補。乾いていて出発点より近いマス、乾いていれば遠くてもよいマス、水の底でも
        // 出発点より近いマス。どれも出発点そのものは除く。
        double startGap = heuristic(fromX, fromZ, toX, toZ);
        long closest = start;
        double closestGap = startGap;
        long driest = start;
        double driestGap = Double.MAX_VALUE;
        long closestAny = start;
        double closestAnyGap = startGap;
        boolean startWet = sweep.at(vehicle.getX(), vehicle.getZ(), vehicle.getY()).deep();
        int spent = 0;
        double threatWeight = weights.threat() * riskWeight;
        double exposureWeight = weights.exposure() * riskWeight;
        Pass pass = new Pass();

        while (!open.isEmpty() && spent++ < budget) {
            Cell cell = open.poll();

            // 同じマスがより安い代償で既に取り出されていれば、古い方は捨てる。
            if (cell.cost() > best.get(cell.key())) {
                continue;
            }

            if (cell.x() == toX && cell.z() == toZ) {
                return finish(vehicle, sweep, cameFrom, floors, waters, cell.key(), start, cell.cost(), true, spent,
                        map, tactical, riskWeight, extra);
            }

            double gap = heuristic(cell.x(), cell.z(), toX, toZ);

            if (cell.key() != start) {
                boolean dry = waters.get(cell.key()) < Obstacles.DEEP
                        && extra.extra(cell.x(), cell.z()) < DEAD_END_EXTRA;

                if (gap < closestAnyGap) {
                    closestAnyGap = gap;
                    closestAny = cell.key();
                }

                if (dry && gap < closestGap) {
                    closestGap = gap;
                    closest = cell.key();
                }

                if (dry && gap < driestGap) {
                    driestGap = gap;
                    driest = cell.key();
                }
            }

            double centreX = cell.x() * CELL + CELL / 2.0;
            double centreZ = cell.z() * CELL + CELL / 2.0;

            for (int way = 0; way < STEP_X.length; way++) {
                int nextX = cell.x() + STEP_X[way];
                int nextZ = cell.z() + STEP_Z[way];

                if (Math.abs(nextX - fromX) > RANGE || Math.abs(nextZ - fromZ) > RANGE) {
                    continue;
                }

                double middleX = nextX * CELL + CELL / 2.0;
                double middleZ = nextZ * CELL + CELL / 2.0;

                // <b>斜めは角を抜けない。</b> 両隣のマスのどちらかが壁なら、その斜めは建物の角を対角線ですり抜ける
                // 道だ——格子の上では通っていても、幅3ブロックの車体は必ずそこで擦る。
                if (way >= 4 && (sweep.at(middleX, centreZ, cell.floor()).blocked()
                        || sweep.at(centreX, middleZ, cell.floor()).blocked())) {
                    continue;
                }

                // 中心から中心までを車体の幅で掃く。前のマスの床から測るので、段々に上がる坂は通り、1歩で登れない
                // 壁と落ちる崖はここで閉じる。
                pass.reset();

                double floor = sweep.run(centreX, centreZ, cell.floor(), middleX, middleZ, pass);

                if (Double.isNaN(floor)) {
                    continue;
                }

                double stepLength = way < 4 ? 1.0 : DIAGONAL;
                double more = extra.extra(nextX, nextZ);
                double step;
                long next = cellKey(nextX, nextZ);

                if (tactical) {
                    TacticalCell tile = map.cell(nextX, nextZ);
                    double danger = Math.min(1.0, tile.threatCost() + tile.exposureCost());
                    double penalty = tile.threatCost() * threatWeight + tile.exposureCost() * exposureWeight
                            + tile.terrainCost() * weights.terrain() + (tile.known() ? 0.0 : weights.unknown());
                    double bonus = Math.min((tile.coverValue() * weights.cover()
                            + tile.heightValue() * weights.height()) * danger
                            + tile.strategicValue() * weights.strategic(), BONUS_CAP);

                    step = stepLength * (1.0 + penalty - bonus + more);
                } else {
                    step = stepLength * (1.0 + more);
                }

                // 薙ぎ倒して進むマスには代償を付ける。通れることと、そこが道であることは違う——林や土手は
                // 抜けられるが、開けた地面が少しの遠回りで済むならそちらを行く。水も同じで、ずっと高い。
                step = Math.max(step, stepLength * LEAST) + (pass.dug ? weights.dig() : 0.0)
                        + stepLength * weights.water() * wetness(pass.water);

                double cost = cell.cost() + step;

                if (cost >= best.get(next)) {
                    continue;
                }

                best.put(next, cost);
                cameFrom.put(next, cell.key());
                floors.put(next, floor);
                waters.put(next, pass.water);
                open.add(new Cell(next, nextX, nextZ, floor, cost, cost + heuristic(nextX, nextZ, toX, toZ)));
            }
        }

        // 届かなかった。<b>一番近くまで行けたマスまでの道を返す。</b> 完全な道が無いことと、そちらへ
        // 進めないことは違う——回り込めるかどうかは、回り込んでから探し直せば分かる。
        //
        // <b>乾いた所から、打ち切った道の先を水の底にしない。</b> 以前は乾いた所で近付けなければ水の底でも近付く方を
        // 取っていたので、予算の内に湖の回り道が見付からないだけで AI は湖へ入り、底を何分も走った（2026-09-13 の
        // 記録で1両が5分半）。乾いた所の中で一番近いマスへ——出発点より遠くても——行って、そこから探し直す。
        // 水の中から探すときだけ、水の底でも近付く方を取る。
        if (closest == start) {
            closest = startWet && closestAny != start ? closestAny : driest;
        }

        if (closest == start) {
            return new Route(List.of(), 0.0, 0.0, false, spent, riskWeight, new boolean[0]);
        }

        return finish(vehicle, sweep, cameFrom, floors, waters, closest, start, best.get(closest), false, spent, map,
                tactical, riskWeight, extra);
    }

    /** マスの鍵。 */
    public static long cellKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** 水の深さを、深い水の代償に対する割合へ。 */
    private static double wetness(int water) {
        return water >= Obstacles.DEEP ? 1.0 : water > 0 ? SHALLOW : 0.0;
    }

    /** 辿り着いたマスから出発点まで遡り、順に並べ直し、道の上の危険を測り、真っ直ぐに均す。 */
    private static Route finish(GroundVehicleEntity vehicle, Sweep sweep, Long2LongOpenHashMap cameFrom,
            Long2DoubleOpenHashMap floors, Long2IntOpenHashMap waters, long end, long start, double cost,
            boolean complete, int spent, TacticalMap map, boolean tactical, double riskWeight, CellCost extra) {
        List<Long> keys = new ArrayList<>();

        for (long at = end; at != start; ) {
            if (!cameFrom.containsKey(at)) {
                break;
            }

            keys.add(at);
            at = cameFrom.get(at);
        }

        // 遡った順は目的地から手前へ。走る側は手前から要る。
        Collections.reverse(keys);

        List<Vec3> points = new ArrayList<>(keys.size());
        double[] pointFloors = new double[keys.size()];
        int[] pointWaters = new int[keys.size()];
        double risk = 0.0;

        for (int index = 0; index < keys.size(); index++) {
            long key = keys.get(index);
            int x = (int) (key >> 32);
            int z = (int) key;

            points.add(new Vec3(x * CELL + CELL / 2.0, 0.0, z * CELL + CELL / 2.0));
            pointFloors[index] = floors.get(key);
            pointWaters[index] = waters.get(key);

            if (tactical) {
                TacticalCell tile = map.cell(x, z);

                risk += Math.min(1.0, tile.threatCost() + 0.5 * tile.exposureCost());
            }
        }

        Smoothed smoothed = smooth(vehicle, sweep, keys, points, pointFloors, pointWaters, map, tactical, extra);

        return new Route(smoothed.points(), cost, points.isEmpty() ? 0.0 : risk / points.size(), complete, spent,
                riskWeight, smoothed.wetLegs());
    }

    /** 均した通過点と、それぞれへ向かう区間が深い水を渡るか。 */
    private record Smoothed(List<Vec3> points, boolean[] wetLegs) {
    }

    /**
     * 格子の階段を真っ直ぐにする。今の点から、車体の幅で掃いて通れて、薙ぎ倒す物も無く、飛ばす通過点より深い水も
     * 危険も追加の代償も無い一番先の通過点まで、間を飛ばす。
     */
    private static Smoothed smooth(GroundVehicleEntity vehicle, Sweep sweep, List<Long> keys, List<Vec3> points,
            double[] floors, int[] waters, TacticalMap map, boolean tactical, CellCost extra) {
        List<Vec3> out = new ArrayList<>();
        List<Boolean> wet = new ArrayList<>();
        double atX = vehicle.getX();
        double atZ = vehicle.getZ();
        double atFloor = vehicle.getY();
        Pass pass = new Pass();
        int from = 0;

        while (from < points.size()) {
            int chosen = from;
            double chosenFloor = floors[from];
            int chosenWater = waters[from];
            double worstDanger = tactical ? danger(map, keys.get(from)) : 0.0;
            double worstExtra = extraAt(extra, keys.get(from));
            int worstWater = waters[from];

            for (int to = from + 1; to < points.size() && to <= from + SMOOTH_REACH; to++) {
                Vec3 target = points.get(to);

                worstDanger = Math.max(worstDanger, tactical ? danger(map, keys.get(to)) : 0.0);
                worstExtra = Math.max(worstExtra, extraAt(extra, keys.get(to)));
                worstWater = Math.max(worstWater, waters[to]);
                pass.reset();

                double floor = sweep.run(atX, atZ, atFloor, target.x, target.z, pass);

                if (Double.isNaN(floor) || pass.dug || pass.water > worstWater
                        || !safeLine(map, tactical, extra, atX, atZ, target.x, target.z, worstDanger, worstExtra)) {
                    break;
                }

                chosen = to;
                chosenFloor = floor;
                chosenWater = pass.water;
            }

            Vec3 point = points.get(chosen);

            out.add(point);
            wet.add(chosenWater >= Obstacles.DEEP);
            atX = point.x;
            atZ = point.z;
            atFloor = chosenFloor;
            from = chosen + 1;
        }

        boolean[] wetLegs = new boolean[wet.size()];

        for (int index = 0; index < wetLegs.length; index++) {
            wetLegs[index] = wet.get(index);
        }

        return new Smoothed(out, wetLegs);
    }

    /** 均した線が、飛ばす通過点より危ないマスや、代償を高く付けたマスを通らないか。 */
    private static boolean safeLine(TacticalMap map, boolean tactical, CellCost extra, double fromX, double fromZ,
            double toX, double toZ, double worstDanger, double worstExtra) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        int samples = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dz * dz) / (CELL * 0.5)));

        for (int sample = 1; sample <= samples; sample++) {
            double along = (double) sample / samples;
            int cellX = Mth.floor((fromX + dx * along) / CELL);
            int cellZ = Mth.floor((fromZ + dz * along) / CELL);

            if (extra.extra(cellX, cellZ) > worstExtra + SMOOTH_DANGER) {
                return false;
            }

            if (tactical) {
                TacticalCell tile = map.cell(cellX, cellZ);

                if (Math.min(1.0, tile.threatCost() + tile.exposureCost()) > worstDanger + SMOOTH_DANGER) {
                    return false;
                }
            }
        }

        return true;
    }

    private static double danger(TacticalMap map, long key) {
        TacticalCell tile = map.cell((int) (key >> 32), (int) key);

        return Math.min(1.0, tile.threatCost() + tile.exposureCost());
    }

    private static double extraAt(CellCost extra, long key) {
        return extra.extra((int) (key >> 32), (int) key);
    }

    /** 残り距離の見積もり。斜めを1歩と数える格子で。 */
    private static double heuristic(int x, int z, int toX, int toZ) {
        int dx = Math.abs(toX - x);
        int dz = Math.abs(toZ - z);

        return Math.max(dx, dz) + (DIAGONAL - 1.0) * Math.min(dx, dz);
    }
}
