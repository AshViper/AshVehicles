package com.ashvehicles.ai.core;

/**
 * 「N tick に1回」を個体ごとにずらして数える。
 *
 * <p><b>40両が同じ tick に同じ重い処理を始めないため。</b> 位相はエンティティ ID から決めるので、同じ車両は
 * いつも同じ tick に考え、試合全体では処理が均される（{@code GroundPilot} の触角と道の探索が元からそうして
 * いた——[[bots-are-a-pilot-object-on-the-vehicle]]）。山を均すのはこれではなく {@link AiBudget} の仕事。
 *
 * <p><b>間隔は呼ぶたびに渡す。</b> 設定を読み直して周期が変わっても、作り直さずに次の1周から新しい間隔になる。
 */
public final class Cadence {
    private int left;

    /**
     * @param seed     位相の種。エンティティ ID に系ごとの素数を掛けた物を渡す——同じ ID から作った2つの周期が
     *                 同じ tick に揃わないように
     * @param interval 最初の1周の長さ
     */
    public Cadence(int seed, int interval) {
        this.left = Math.floorMod(seed, Math.max(interval, 1)) + 1;
    }

    /** 1 tick 進める。この tick が当番なら true。 */
    public boolean tick(int interval) {
        if (--this.left > 0) {
            return false;
        }

        this.left = Math.max(interval, 1);

        return true;
    }

    /** 次の {@link #tick} を当番にする。出来事で前倒ししたいときに。 */
    public void force() {
        this.left = 0;
    }

    /** 次の当番まで、少なくともこれだけ待つ。前倒しを連打させないための物。 */
    public void holdFor(int ticks) {
        this.left = Math.max(this.left, ticks);
    }
}
