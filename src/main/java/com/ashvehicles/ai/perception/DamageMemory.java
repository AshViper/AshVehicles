package com.ashvehicles.ai.perception;

import javax.annotation.Nullable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * この車両が最近、誰にどれだけ撃たれたか。
 *
 * <p><b>「自分を撃っている敵」を最優先にするための記憶。</b> 視線と距離だけでは、丘の向こうから撃ってくる
 * 自走砲と、こちらを向いていないだけの戦車の区別が付かない。撃たれたという事実は被弾の出口
 * （{@code VehicleEntityBase.hurt} → {@code log/BattleEvents}）から届く。
 *
 * <p><b>撃った者と、その者が乗っている物の両方を覚える。</b> 人の乗った戦車が撃てば、打撃の持ち主は人
 * （{@code Deathmatch.attackerOf}）だが、名簿に並んで見えているのは戦車の方だ
 * （{@code Bots.combatants}）。どちらで訊かれても当たるようにしておく。
 *
 * <p>固定長の輪。最近の {@value #SIZE} 件だけを持ち、オブジェクトは作らない。
 */
public final class DamageMemory {
    private static final int SIZE = 8;

    private final int[] attackers = new int[SIZE];
    private final int[] carriers = new int[SIZE];
    private final long[] times = new long[SIZE];
    private final float[] amounts = new float[SIZE];
    private int next;
    private long lastHurt = Long.MIN_VALUE;
    private int lastAttacker = -1;

    public DamageMemory() {
        java.util.Arrays.fill(this.attackers, -1);
        java.util.Arrays.fill(this.carriers, -1);
        java.util.Arrays.fill(this.times, Long.MIN_VALUE);
    }

    /** 1回の被弾。撃った者が分からない（墜落・自爆・爆風の出所不明）なら attacker は null。 */
    public void record(@Nullable Entity attacker, float amount, long now) {
        int at = this.next;

        this.next = (this.next + 1) % SIZE;
        this.attackers[at] = attacker == null ? -1 : attacker.getId();
        this.carriers[at] = attacker == null || attacker.getVehicle() == null ? -1 : attacker.getVehicle().getId();
        this.times[at] = now;
        this.amounts[at] = amount;
        this.lastHurt = now;

        if (attacker != null) {
            this.lastAttacker = attacker.getVehicle() != null ? attacker.getVehicle().getId() : attacker.getId();
        }
    }

    /** その相手に、窓の内に撃たれたか。 */
    public boolean hurtBy(Entity entity, long now, int window) {
        int id = entity.getId();

        for (int at = 0; at < SIZE; at++) {
            if (within(this.times[at], now, window) && (this.attackers[at] == id || this.carriers[at] == id)) {
                return true;
            }
        }

        return false;
    }

    /** 窓の内に撃たれたか。相手を問わない。 */
    public boolean underFire(long now, int window) {
        return within(this.lastHurt, now, window);
    }

    /**
     * その時刻が窓の内か。<b>空の欄の時刻（MIN_VALUE）は差を取る前に弾く</b>——{@code now - MIN_VALUE} は桁あふれ
     * して負になり、「ついさっき」に見える。
     */
    private static boolean within(long time, long now, int window) {
        return time != Long.MIN_VALUE && now - time <= window;
    }

    /** その時刻より後に受けた打撃の合計。 */
    public float damageSince(long since) {
        float total = 0.0F;

        for (int at = 0; at < SIZE; at++) {
            if (this.times[at] > since) {
                total += this.amounts[at];
            }
        }

        return total;
    }

    /** 窓の内に撃ってきた、別々の相手の数。 */
    public int attackerCount(long now, int window) {
        int counted = 0;

        for (int at = 0; at < SIZE; at++) {
            if (!within(this.times[at], now, window) || this.attackers[at] < 0) {
                continue;
            }

            boolean seen = false;

            for (int before = 0; before < at; before++) {
                if (within(this.times[before], now, window) && this.attackers[before] == this.attackers[at]) {
                    seen = true;

                    break;
                }
            }

            if (!seen) {
                counted++;
            }
        }

        return counted;
    }

    /** 最後に撃ってきた相手（乗っている物があればそちら）。窓を過ぎているか、もう世界に居なければ null。 */
    @Nullable
    public Entity lastAttacker(Level level, long now, int window) {
        if (this.lastAttacker < 0 || !this.underFire(now, window)) {
            return null;
        }

        Entity found = level.getEntity(this.lastAttacker);

        return found == null || found.isRemoved() ? null : found;
    }

    public long lastHurt() {
        return this.lastHurt;
    }
}
