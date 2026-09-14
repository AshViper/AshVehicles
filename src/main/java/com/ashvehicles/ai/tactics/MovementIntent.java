package com.ashvehicles.ai.tactics;

import javax.annotation.Nullable;

import com.ashvehicles.ai.decision.TacticalAction;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 行動を移動の言葉に直した物。{@link Tactics} が作り、経路を追う側（{@code navigation/Navigator}）と
 * 撃つ側（{@code combat/CombatController}）が読む。
 *
 * <p><b>ここから下は行動の名前を知らない。</b> Navigator が知っているのは「どこへ・どれだけ危険を嫌って・
 * 着いたら止まるか」だけで、それが拠点を取るためなのか下がるためなのかは知らない。行動の意味を解くのは
 * {@link Tactics} 1箇所だけにしておく——同じ「撃ちながら下がる」を2箇所で書けば、片方だけが直る。
 *
 * @param action          元になった行動。記録と可視化のためだけに持つ
 * @param mode            着いた後に留まるか、そもそも動かないか
 * @param destination     行き先。{@link Mode#HOLD} では無くてよい
 * @param arriveRadius    着いたと見なす距離（ブロック）
 * @param throttle        走る速さの上限（0〜1）
 * @param riskWeight      経路で脅威と露出をどれだけ嫌うか。1 が標準、撤退は大きい
 * @param stopToFireRange この距離まで詰めてきた相手には、足を止めて撃ち合う（ブロック）。0 なら止まらない
 * @param face            止まっている間に車体を向ける先。無ければ目標か行き先
 * @param preferredTarget 撃ってほしい相手。支援・側面の対象。無ければ目標選びに任せる
 * @param position        行き先を探した結果。遮蔽・側面・撤退先。可視化のために持つ
 */
public record MovementIntent(TacticalAction action, Mode mode, @Nullable Vec3 destination, double arriveRadius,
        float throttle, double riskWeight, double stopToFireRange, @Nullable Vec3 face,
        @Nullable Entity preferredTarget, @Nullable TacticalPosition position) {

    /** 着いたと見なす既定の距離（ブロック）。拠点の半径（既定16）より十分内側。 */
    public static final double ARRIVED = 6.0;

    /** 動き方。 */
    public enum Mode {
        /** その場に留まる。車体は {@code face} へ向ける。 */
        HOLD,
        /** 行き先へ向かい、着いたら留まる。 */
        MOVE,
        /**
         * その場から動かない。車体を向けるのは信地旋回できる車両だけで、向き直るために転がさない。
         *
         * <p>{@link #HOLD} は車体が大きく外れていると転がして向き直る。拠点の円の中でそれをやると、車両は円の縁で
         * 輪を描いて進みを取りこぼした（2026-09-13「拠点奪取する際に、付近でぐるぐる旋回する」）。
         */
        ANCHOR
    }

    /** その場に留まる意図。 */
    public static MovementIntent hold(TacticalAction action, @Nullable Vec3 face, double stopToFireRange) {
        return new MovementIntent(action, Mode.HOLD, null, ARRIVED, 0.0F, 1.0, stopToFireRange, face, null, null);
    }

    /** その場から動かない意図。拠点の円の中に入った車両。 */
    public static MovementIntent anchor(TacticalAction action, @Nullable Vec3 face, double stopToFireRange) {
        return new MovementIntent(action, Mode.ANCHOR, null, ARRIVED, 0.0F, 1.0, stopToFireRange, face, null, null);
    }

    /** 行き先へ向かう意図。 */
    public static MovementIntent move(TacticalAction action, Vec3 destination, double riskWeight,
            double stopToFireRange) {
        return new MovementIntent(action, Mode.MOVE, destination, ARRIVED, 1.0F, riskWeight, stopToFireRange, null,
                null, null);
    }

    public MovementIntent withPosition(@Nullable TacticalPosition found) {
        return new MovementIntent(this.action, this.mode, this.destination, this.arriveRadius, this.throttle,
                this.riskWeight, this.stopToFireRange, this.face, this.preferredTarget, found);
    }

    public MovementIntent withTarget(@Nullable Entity target) {
        return new MovementIntent(this.action, this.mode, this.destination, this.arriveRadius, this.throttle,
                this.riskWeight, this.stopToFireRange, this.face, target, this.position);
    }

    public MovementIntent withFace(@Nullable Vec3 towards) {
        return new MovementIntent(this.action, this.mode, this.destination, this.arriveRadius, this.throttle,
                this.riskWeight, this.stopToFireRange, towards, this.preferredTarget, this.position);
    }

    public MovementIntent withThrottle(float limit) {
        return new MovementIntent(this.action, this.mode, this.destination, this.arriveRadius, limit,
                this.riskWeight, this.stopToFireRange, this.face, this.preferredTarget, this.position);
    }
}
