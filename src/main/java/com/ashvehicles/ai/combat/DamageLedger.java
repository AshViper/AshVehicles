package com.ashvehicles.ai.combat;

import net.minecraft.world.entity.Entity;

/**
 * 自分の撃った弾が、実際に効いているか。
 *
 * <p><b>効かない相手を撃ち続けない。</b> 機関砲で主力戦車の正面を撃てば、弾は全部弾かれる
 * （{@code weapon/Ricochet}）。当たっているのに1点も減らない撃ち合いは、弾と時間を相手に配っているだけで、
 * 側面へ回るか別の相手を選ぶべき状況だ。ここはそれを「数発撃って1点も入らなかった」という事実で見付け、
 * 目標選び（{@link TargetSelector}）と方針（{@code decision/RuleBasedPolicy} の側面）に知らせる。
 *
 * <p>与えた打撃は被弾の出口（{@code VehicleEntityBase.hurt} → {@code log/BattleEvents}）から届く。
 */
public final class DamageLedger {
    /** これだけ撃って1点も入らなければ効いていない。 */
    private static final int INEFFECTIVE_SHOTS = 5;

    /** その判断に要る最短の時間（tick）。弾が飛んでいる間に「効かない」と言わないため。 */
    private static final int INEFFECTIVE_TICKS = 200;

    /** 効かないという判断を持ち続ける長さ（tick）。30秒経てば角度も距離も変わっている。 */
    private static final int VERDICT_TICKS = 600;

    /** 引き金を引き続ける兵装で、1発と数える tick の数。 */
    private static final int AUTOMATIC_TICKS_PER_SHOT = 10;

    private int target = -1;
    private int shots;
    private int automaticTicks;
    private long firstShot = Long.MIN_VALUE;
    private float dealtToTarget;

    private int ineffectiveTarget = -1;
    private long ineffectiveUntil = Long.MIN_VALUE;

    /**
     * 撃った。
     *
     * @param automatic 押している間撃ち続ける兵装か。そういう兵装は毎 tick 引き金が立つので、tick を発数に直す
     */
    public void shot(int targetId, boolean automatic, long now) {
        if (targetId != this.target) {
            this.target = targetId;
            this.shots = 0;
            this.automaticTicks = 0;
            this.firstShot = now;
            this.dealtToTarget = 0.0F;
        }

        if (!automatic || this.automaticTicks++ % AUTOMATIC_TICKS_PER_SHOT == 0) {
            this.shots++;
        }

        if (this.shots >= INEFFECTIVE_SHOTS && this.dealtToTarget <= 0.0F && now - this.firstShot >= INEFFECTIVE_TICKS) {
            this.ineffectiveTarget = this.target;
            this.ineffectiveUntil = now + VERDICT_TICKS;
            // 数え直す。判断は期限付きで、期限が来たらもう一度撃って確かめる。
            this.shots = 0;
            this.firstShot = now;
        }
    }

    /** 自分の弾が当たって、実際に耐久が減った。 */
    public void dealt(Entity victim, float amount, long now) {
        int id = victim.getId();
        int carrier = victim.getVehicle() == null ? -1 : victim.getVehicle().getId();

        if (id == this.target || carrier == this.target) {
            this.dealtToTarget += amount;
        }

        if (id == this.ineffectiveTarget || carrier == this.ineffectiveTarget) {
            this.ineffectiveTarget = -1;
        }
    }

    /** その相手に、今は効いていないと分かっているか。 */
    public boolean ineffective(int targetId, long now) {
        return targetId == this.ineffectiveTarget && now < this.ineffectiveUntil;
    }
}
