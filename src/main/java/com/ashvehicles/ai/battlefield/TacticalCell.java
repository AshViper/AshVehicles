package com.ashvehicles.ai.battlefield;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 戦術格子の1マス（4×4 ブロック）。
 *
 * <p><b>ブロック座標と行き来できる。</b> マスの番号は {@code floor(ブロック座標 / 4)} で、中心は
 * {@link #centre}、代表のブロックは {@link #blockPos}。経路探索（{@code navigation/RoutePlanner}）の格子と同じ
 * 目なので、道の1マスと戦術の1マスは同じ物を指す。
 *
 * <p>コストと価値はどれも 0〜1 に揃えてある（地形のコストだけは水と崖で1を超える）。重みを掛けて足すのは読む側。
 *
 * @param cellX          マスの番号
 * @param cellZ          マスの番号
 * @param ground         地面の高さ。知らないマスなら NaN
 * @param kind           代表の地形の読み
 * @param walkable       車両が入れそうか（見積もり。実際の道は {@code navigation/Obstacles} の物差しで決める）
 * @param known          ロードされていて読めた
 * @param terrainCost    起伏・水・屋根・未知の代償
 * @param threatCost     陣営の脅威マップの危険
 * @param exposureCost   今見えている敵から見通されている度合い。知らないマスは設定の未知の値
 * @param coverValue     脅威の方向に、車体を隠す高さの物があるか
 * @param heightValue    周りより高いか
 * @param buildingValue  建物（屋根か人工物）か
 * @param strategicValue 拠点の円の中や近く
 */
public record TacticalCell(int cellX, int cellZ, double ground, TerrainKind kind, boolean walkable, boolean known,
        float terrainCost, float threatCost, float exposureCost, float coverValue, float heightValue,
        float buildingValue, float strategicValue) {

    public double centreX() {
        return TerrainCache.centreOf(this.cellX);
    }

    public double centreZ() {
        return TerrainCache.centreOf(this.cellZ);
    }

    /** マスの中心。高さは地面、知らなければ0。 */
    public Vec3 centre() {
        return new Vec3(this.centreX(), Double.isNaN(this.ground) ? 0.0 : this.ground, this.centreZ());
    }

    /** マスの中心のブロック。 */
    public BlockPos blockPos() {
        return BlockPos.containing(this.centreX(), Double.isNaN(this.ground) ? 0.0 : this.ground, this.centreZ());
    }
}
