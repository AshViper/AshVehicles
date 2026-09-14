package com.ashvehicles.ai.battlefield;

import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.ai.AiConfig;
import com.ashvehicles.ai.objective.ObjectiveState;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * AI 1両の周りの戦術格子。地形（{@link TerrainCache}）と脅威（{@link ThreatMap}）と拠点を、1マスずつ車両の目で
 * 読み直した物。
 *
 * <p><b>格子を丸ごと作らない。</b> 256×256 ブロックを毎回全部読むのは、使われないマスの方が遥かに多い仕事だ。
 * マスは訊かれたときに初めて計算し、周期の間だけ覚える（{@link #refresh} で忘れる）。訊くのは経路探索の展開した
 * マスと、遮蔽・側面・撤退の候補と、可視化だけ。
 *
 * <p><b>車両ごとに持つ理由</b>は2つの値が車両ごとに違うからだ——遮蔽の価値は「その車両の背丈を隠せるか」で、
 * 向きは「その車両を撃ってくる脅威の方向」で決まる。地形そのものの読みは全員で共有している
 * （{@link TerrainCache}）ので、ここで持つのは車両の目で見た結論だけ。
 *
 * <p>サーバースレッド専用。
 */
public final class TacticalMap {
    /** マスの一辺（ブロック）。 */
    public static final int CELL = TerrainCache.CELL;

    /** 未知のマスの地形コスト。通れるが、道に選ぶ理由は無い。 */
    private static final float UNKNOWN_TERRAIN = 1.0F;

    /** 1周期に覚えるマスの上限。経路探索1回の展開（既定240）の数倍。 */
    private static final int MEMO = 4096;

    /** 高さを比べる輪のマス（2マス先の8方向）。 */
    private static final int[][] RING = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 2}, {2, -2}, {-2, 2}, {-2, -2}};

    private final Long2ObjectOpenHashMap<TacticalCell> memo = new Long2ObjectOpenHashMap<>();

    @Nullable
    private TerrainCache terrain;

    @Nullable
    private TeamIntel intel;

    private List<ObjectiveState> objectives = List.of();

    @Nullable
    private Vec3 threatFrom;

    private Vec3 centre = Vec3.ZERO;
    private double vehicleHeight = 3.0;
    private double climb = 1.0;
    private int now;

    /**
     * 読み直す準備。覚えていたマスを全部忘れ、今の脅威の向きと拠点で読み直せるようにする。
     *
     * @param threatFrom    撃たれる向きの目安（脅威の重心）。無ければ全方向の遮蔽を半分の価値で見る
     * @param vehicleHeight 隠したい車体の高さ（ブロック）
     * @param climb         車両が登れる段差（ブロック）
     */
    public void refresh(Level level, Vec3 centre, TeamIntel intel, List<ObjectiveState> objectives,
            @Nullable Vec3 threatFrom, double vehicleHeight, double climb, int now) {
        this.memo.clear();
        this.terrain = TerrainCache.of(level);
        this.intel = intel;
        this.objectives = objectives;
        this.threatFrom = threatFrom;
        this.centre = centre;
        this.vehicleHeight = Math.max(vehicleHeight, 1.0);
        this.climb = climb;
        this.now = now;
    }

    /** 一度でも準備したか。 */
    public boolean ready() {
        return this.terrain != null && this.intel != null;
    }

    public Vec3 centre() {
        return this.centre;
    }

    public static int cellOf(double coord) {
        return Mth.floor(coord / CELL);
    }

    /** そのマス。準備前なら未知のマス。 */
    public TacticalCell cell(int cellX, int cellZ) {
        long key = ChunkPos.asLong(cellX, cellZ);
        TacticalCell known = this.memo.get(key);

        if (known != null) {
            return known;
        }

        TacticalCell computed = this.compute(cellX, cellZ);

        if (this.memo.size() < MEMO) {
            this.memo.put(key, computed);
        }

        return computed;
    }

    /** そのブロック座標を含むマス。 */
    public TacticalCell cellAt(double x, double z) {
        return this.cell(cellOf(x), cellOf(z));
    }

    private TacticalCell compute(int cellX, int cellZ) {
        double x = TerrainCache.centreOf(cellX);
        double z = TerrainCache.centreOf(cellZ);

        if (this.terrain == null || this.intel == null) {
            return new TacticalCell(cellX, cellZ, Double.NaN, TerrainKind.UNKNOWN, true, false, UNKNOWN_TERRAIN,
                    0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F);
        }

        TerrainCache.Column column = this.terrain.column(cellX, cellZ, this.now);
        ThreatMap threats = this.intel.threats();
        float threat = threats.danger(x, z);
        float exposure = threats.exposure(x, z);
        float strategic = this.strategic(x, z);

        if (!column.known()) {
            // 知らない土地。通れることにはするが、安全とは言わない——露出は設定の未知の値を下回らない。
            return new TacticalCell(cellX, cellZ, Double.NaN, TerrainKind.UNKNOWN, true, false, UNKNOWN_TERRAIN,
                    threat, Math.max(exposure, (float) AiConfig.threats().unknownExposure()), 0.0F, 0.0F, 0.0F,
                    strategic);
        }

        double rise = column.rise();
        boolean wall = rise > this.climb + 1.0;
        boolean walkable = !column.water() && !wall && !column.roof();
        float terrainCost = (float) (Math.min(rise / CELL, 2.0) * 0.5 + (column.water() ? 2.0 : 0.0)
                + (column.roof() ? 0.5 : 0.0));
        float height = this.height(cellX, cellZ, column);
        float cover = this.cover(cellX, cellZ, column);
        float building = column.roof() ? 1.0F : column.manmade() ? 0.6F : 0.0F;
        boolean narrow = walkable && ((this.wallBetween(column, cellX - 1, cellZ)
                && this.wallBetween(column, cellX + 1, cellZ))
                || (this.wallBetween(column, cellX, cellZ - 1) && this.wallBetween(column, cellX, cellZ + 1)));
        TerrainKind kind;

        if (column.water()) {
            kind = TerrainKind.WATER;
        } else if (column.roof()) {
            kind = TerrainKind.BUILDING;
        } else if (wall) {
            kind = TerrainKind.WALL;
        } else if (narrow) {
            kind = TerrainKind.NARROW;
        } else if (height > 0.5F) {
            kind = TerrainKind.HIGH_GROUND;
        } else if (rise / CELL > 0.25) {
            kind = TerrainKind.SLOPE;
        } else if (cover <= 0.0F) {
            kind = TerrainKind.OPEN;
        } else {
            kind = TerrainKind.FLAT;
        }

        // 狭い通り道は抜ければ便利だが、そこで止まれば的になる。拠点の近くなら通り道にも価値がある。
        if (narrow) {
            strategic = Math.max(strategic, 0.2F);
        }

        return new TacticalCell(cellX, cellZ, column.ground(), kind, walkable, true, terrainCost, threat, exposure,
                cover, height, building, strategic);
    }

    /** 周りの輪より高い分。6ブロック高ければ最大。 */
    private float height(int cellX, int cellZ, TerrainCache.Column column) {
        double sum = 0.0;
        int counted = 0;

        for (int[] offset : RING) {
            TerrainCache.Column around = this.terrain.column(cellX + offset[0], cellZ + offset[1], this.now);

            if (around.known()) {
                sum += around.ground();
                counted++;
            }
        }

        if (counted == 0) {
            return 0.0F;
        }

        return (float) Mth.clamp((column.ground() - sum / counted) / 6.0, 0.0, 1.0);
    }

    /**
     * 脅威の方向の隣2マスに、車体を隠す高さの物があるか。
     *
     * <p>隠したい高さは車体の8割——砲塔の天板まで隠す必要は無い（撃つには出す）が、車体は隠したい。1ブロック
     * までの起伏は遮蔽に数えない。
     */
    private float cover(int cellX, int cellZ, TerrainCache.Column column) {
        double hide = this.vehicleHeight * 0.8;

        if (this.threatFrom != null) {
            double dx = this.threatFrom.x - TerrainCache.centreOf(cellX);
            double dz = this.threatFrom.z - TerrainCache.centreOf(cellZ);
            double length = Math.sqrt(dx * dx + dz * dz);

            if (length < CELL) {
                return 0.0F;
            }

            int stepX = (int) Math.round(dx / length);
            int stepZ = (int) Math.round(dz / length);
            double obstacle = 0.0;

            for (int step = 1; step <= 2; step++) {
                TerrainCache.Column towards = this.terrain.column(cellX + stepX * step, cellZ + stepZ * step,
                        this.now);

                if (towards.known()) {
                    obstacle = Math.max(obstacle, towards.maxSight() - column.ground());
                }
            }

            return (float) Mth.clamp((obstacle - 1.0) / hide, 0.0, 1.0);
        }

        double best = 0.0;

        for (int[] offset : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            TerrainCache.Column beside = this.terrain.column(cellX + offset[0], cellZ + offset[1], this.now);

            if (beside.known()) {
                best = Math.max(best, beside.maxSight() - column.ground());
            }
        }

        // どちらから撃たれるか分からない遮蔽は、半分しか当てにならない。
        return (float) (Mth.clamp((best - 1.0) / hide, 0.0, 1.0) * 0.5);
    }

    /** 隣のマスとの間が壁か。水・屋根・崖、あるいは登れない段差。 */
    private boolean wallBetween(TerrainCache.Column column, int cellX, int cellZ) {
        TerrainCache.Column beside = this.terrain.column(cellX, cellZ, this.now);

        if (!beside.known()) {
            return false;
        }

        return beside.water() || beside.roof() || beside.rise() > this.climb + 1.0
                || beside.ground() - column.ground() > this.climb + 1.0;
    }

    /** 拠点の円の中なら1、円の倍の距離までなら0.4。 */
    private float strategic(double x, double z) {
        float best = 0.0F;

        for (ObjectiveState objective : this.objectives) {
            double dx = x - objective.centre().x;
            double dz = z - objective.centre().z;
            double distance = Math.sqrt(dx * dx + dz * dz);

            if (distance <= objective.radius()) {
                return 1.0F;
            }

            if (distance <= objective.radius() * 2.0) {
                best = 0.4F;
            }
        }

        return best;
    }
}
