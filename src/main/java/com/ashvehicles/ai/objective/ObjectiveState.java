package com.ashvehicles.ai.objective;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * ある陣営から見た、持ち場1つの今の姿。拠点か、拠点の無い試合での敵陣の旗。
 *
 * <p>陣営の頭（{@code team/TeamBrain}）が {@link ObjectiveBoard} で周期ごとに作り直す、変わらない値の束。
 * 方針（{@code decision/RuleBasedPolicy}）と戦術（{@code tactics/Tactics}）はこれだけを読み、帳簿
 * （{@code match/MatchState}）には触らない。
 *
 * @param id                記録に書く名前。拠点はその名前、敵陣の旗は {@code base:<陣営>}
 * @param pos               旗竿（か出撃地点ブロック）の座標
 * @param centre            円の中心
 * @param radius            円の半径（ブロック）
 * @param state             見ている陣営から見た状態
 * @param owner             持ち主。中立なら null
 * @param taking            制圧を進めている陣営。無ければ null
 * @param progress          制圧の進み（0〜1）
 * @param base              拠点ではなく敵陣の旗
 * @param beingTaken        自陣の拠点を敵が取りかけている
 * @param recaptureRequired この試合で自陣が握っていたのに、今は敵の物
 * @param lostTick          自陣が最後に失った tick。失ったことが無ければ {@link Long#MIN_VALUE}
 * @param alliesNear        円の近くにいる味方の数
 * @param enemiesNear       円の近くにいる敵の数
 * @param threat            円の周りの危険（0〜1）
 * @param strategic         戦略上の価値（0〜1）。会場の中心に近いほど高い
 * @param score             陣営としての点数（{@link ObjectiveScoring#teamScore}）
 */
public record ObjectiveState(String id, BlockPos pos, Vec3 centre, double radius, CaptureState state,
        @Nullable String owner, @Nullable String taking, float progress, boolean base, boolean beingTaken,
        boolean recaptureRequired, long lostTick, int alliesNear, int enemiesNear, double threat, double strategic,
        double score) {

    /** 点数だけを差し替えた物。 */
    public ObjectiveState withScore(double value) {
        return new ObjectiveState(this.id, this.pos, this.centre, this.radius, this.state, this.owner, this.taking,
                this.progress, this.base, this.beingTaken, this.recaptureRequired, this.lostTick, this.alliesNear,
                this.enemiesNear, this.threat, this.strategic, value);
    }

    /** その点が円の中か。高さは見ない——拠点は柱ではなく地面の円だ（{@code Deathmatch.inside}）。 */
    public boolean contains(Vec3 at) {
        double dx = at.x - this.centre.x;
        double dz = at.z - this.centre.z;

        return dx * dx + dz * dz <= this.radius * this.radius;
    }

    /** 取りに行く物か。自陣が握っていない拠点と、敵陣の旗。 */
    public boolean wantsTaking() {
        return this.state != CaptureState.FRIENDLY;
    }
}
