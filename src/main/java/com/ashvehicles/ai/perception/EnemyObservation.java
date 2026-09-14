package com.ashvehicles.ai.perception;

import com.ashvehicles.entity.VehicleEntityBase;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 敵1つの観測。{@link Perception} が数 tick ごとに書き直し、目標選び・方針・戦術が読む。
 *
 * <p><b>見えているか（{@link #visible}）と、見られているか（{@link #seenBy}）を分けて持つ。</b> 視線そのものは
 * 同じ線分でも、答えは両者の距離の感覚で違う——180ブロック先の自走砲はこちらの視界の外にいても、こちらは
 * あちらの射程の中にいる。「撃てるか」と「撃たれうるか」は別の問いで、遮蔽を探す判断が要るのは後者だ。
 *
 * <p><b>見えなくなっても消さない。</b> 視線が切れた敵は、最後に見た位置と時刻を持ったまま記憶の長さ
 * （{@code perception.memoryTicks}）だけ残る。味方の誰かが見ていれば位置は陣営の情報で更新される
 * （{@link #spottedByTeam}）。「さっきあそこに居た」は判断の材料であって、忘れてよい事実ではない。
 *
 * <p>使い回す。観測は毎秒数回、敵の数だけ作り直されるので、1つの敵に1つのオブジェクトを持ち続け中身を
 * 書き換える。<b>書き換えは {@link Perception} だけ</b>で、優先度だけは目標選び
 * （{@code combat/TargetSelector}）が書く。
 */
public final class EnemyObservation {
    private final Entity target;
    private String team = "";

    private double distance;
    private Vec3 direction = Vec3.ZERO;
    private Vec3 lastKnownPosition;
    private Vec3 velocity = Vec3.ZERO;

    private boolean visible;
    private boolean inWeaponRange;
    private boolean hasLineOfSight;
    private boolean lineKnown;
    private boolean seenBy;
    private boolean inTheirRange;
    private boolean attackingMe;
    private boolean attackingAlly;
    private boolean attackingObjective;
    private boolean spottedByTeam;
    private boolean flying;
    private boolean armoured;
    private boolean aimingAtMe;

    private double threatLevel;
    private double targetPriority;
    private double capability;
    private double effectiveness;
    private float healthFraction = 1.0F;

    private long lastSeenTick = Long.MIN_VALUE;
    private long updatedTick = Long.MIN_VALUE;

    private WeaponReach reach = WeaponReach.NONE;

    EnemyObservation(Entity target) {
        this.target = target;
        this.lastKnownPosition = target.position();
    }

    public Entity target() {
        return this.target;
    }

    /** 相手の陣営 ID。 */
    public String team() {
        return this.team;
    }

    /** 自分の視点から相手の視点までの距離（ブロック）。見えていない間は最後に知っていた位置まで。 */
    public double distance() {
        return this.distance;
    }

    /** 自分から相手への単位ベクトル。 */
    public Vec3 direction() {
        return this.direction;
    }

    /** 最後に見た（か、味方が見た）位置。 */
    public Vec3 lastKnownPosition() {
        return this.lastKnownPosition;
    }

    /** 相手の速度（ブロック/tick）。 */
    public Vec3 velocity() {
        return this.velocity;
    }

    /** 視界の中で、視線が通っている。 */
    public boolean visible() {
        return this.visible;
    }

    /** 自分の兵装のどれかが届く。 */
    public boolean inWeaponRange() {
        return this.inWeaponRange;
    }

    /** 視線が通っている（距離は問わない）。 */
    public boolean hasLineOfSight() {
        return this.hasLineOfSight;
    }

    /** 視線を実際に測れたか。予算切れか、線の下にロードされていない土地があれば偽——安全の意味ではない。 */
    public boolean lineKnown() {
        return this.lineKnown;
    }

    /** 相手から見られている。視線が通り、こちらが相手の視界の中にいる。 */
    public boolean seenBy() {
        return this.seenBy;
    }

    /** こちらが相手の兵装の届く所にいる。 */
    public boolean inTheirRange() {
        return this.inTheirRange;
    }

    /** 最近この相手に撃たれた。 */
    public boolean attackingMe() {
        return this.attackingMe;
    }

    /** 最近この相手が味方を撃った。 */
    public boolean attackingAlly() {
        return this.attackingAlly;
    }

    /** この相手が自陣の拠点を取りに来ている。 */
    public boolean attackingObjective() {
        return this.attackingObjective;
    }

    /** 自分では見えていないが、味方の誰かが見ている。 */
    public boolean spottedByTeam() {
        return this.spottedByTeam;
    }

    /** 空を飛んでいる。 */
    public boolean flying() {
        return this.flying;
    }

    /** 装甲を持つ。 */
    public boolean armoured() {
        return this.armoured;
    }

    /** 相手の砲（か視線）がこちらを向いている。 */
    public boolean aimingAtMe() {
        return this.aimingAtMe;
    }

    /** こちらにとっての脅威（0〜1）。{@link ThreatModel}。 */
    public double threatLevel() {
        return this.threatLevel;
    }

    /** 狙う価値（0〜）。{@code combat/TargetSelector} が付ける。 */
    public double targetPriority() {
        return this.targetPriority;
    }

    /** 相手がこちらを壊す力（0〜1）。距離と視線を掛ける前の値。 */
    public double capability() {
        return this.capability;
    }

    /** こちらの兵装が相手に効く度合い（0〜1）。 */
    public double effectiveness() {
        return this.effectiveness;
    }

    public float healthFraction() {
        return this.healthFraction;
    }

    public long lastSeenTick() {
        return this.lastSeenTick;
    }

    public long updatedTick() {
        return this.updatedTick;
    }

    public WeaponReach reach() {
        return this.reach;
    }

    /** 見失ってからの tick。一度も見ていなければ最大値。 */
    public long unseenFor(long now) {
        return this.lastSeenTick == Long.MIN_VALUE ? Long.MAX_VALUE : now - this.lastSeenTick;
    }

    /** 相手はまだ世界にいて、戦力として数えられるか。 */
    public boolean alive() {
        if (this.target.isRemoved() || !this.target.isAlive()) {
            return false;
        }

        return !(this.target instanceof VehicleEntityBase machine) || !machine.isWrecked();
    }

    public void setTargetPriority(double priority) {
        this.targetPriority = priority;
    }

    // ------------------------------------------------------------------
    // Perception だけが書く
    // ------------------------------------------------------------------

    void setTeam(String team) {
        this.team = team;
    }

    void setGeometry(double distance, Vec3 direction, Vec3 velocity) {
        this.distance = distance;
        this.direction = direction;
        this.velocity = velocity;
    }

    void setLastKnownPosition(Vec3 position) {
        this.lastKnownPosition = position;
    }

    void setSight(boolean lineOfSight, boolean known, boolean visible, boolean seenBy) {
        this.hasLineOfSight = lineOfSight;
        this.lineKnown = known;
        this.visible = visible;
        this.seenBy = seenBy;
    }

    void setRanges(boolean inWeaponRange, boolean inTheirRange) {
        this.inWeaponRange = inWeaponRange;
        this.inTheirRange = inTheirRange;
    }

    void setIntent(boolean attackingMe, boolean attackingAlly, boolean attackingObjective, boolean aimingAtMe) {
        this.attackingMe = attackingMe;
        this.attackingAlly = attackingAlly;
        this.attackingObjective = attackingObjective;
        this.aimingAtMe = aimingAtMe;
    }

    void setBody(boolean flying, boolean armoured, float healthFraction, WeaponReach reach) {
        this.flying = flying;
        this.armoured = armoured;
        this.healthFraction = healthFraction;
        this.reach = reach;
    }

    void setThreat(double threat, double capability, double effectiveness) {
        this.threatLevel = threat;
        this.capability = capability;
        this.effectiveness = effectiveness;
    }

    void setSpottedByTeam(boolean spotted) {
        this.spottedByTeam = spotted;
    }

    void markSeen(long now) {
        this.lastSeenTick = now;
    }

    void markUpdated(long now) {
        this.updatedTick = now;
    }
}
