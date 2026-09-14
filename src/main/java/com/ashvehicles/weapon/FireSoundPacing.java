package com.ashvehicles.weapon;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.resources.ResourceLocation;

/**
 * 撃ち続けている兵装の発砲音を、{@link WeaponDefinition.SoundSetup#interval()} の間隔へ間引く。
 *
 * <p>発砲音を鳴らす物——機体の {@link WeaponMounts}、地上車両の {@link BuiltInGun} と {@link TurretLauncher}——が
 * 1つずつ持つ。覚えるのは兵装ごとに2つの時刻だけで、最後に撃った tick と最後に音を出した tick。
 *
 * <p>「撃ち続けている」とは、前に撃ってから発射速度の1発ぶんより長く空いていないこと。それより空いたら引き金を
 * 引き直したのと同じで、間隔の途中でもその tick に鳴らす。
 */
final class FireSoundPacing {
    /** まだ一度も撃っていない兵装の時刻。引き算の前に必ずこれと比べる。 */
    private static final long NEVER = Long.MIN_VALUE;

    private final Map<ResourceLocation, long[]> times = new HashMap<>();

    /**
     * この tick にその兵装の発砲音を鳴らすか。撃った tick に呼ぶ。同じ tick に同じ兵装で2度呼んでも、鳴るのは
     * 1度だけ——機体では砲座とパイロットが同じ砲を同じ tick に撃てる。
     *
     * @param now 今のゲーム時刻（tick）
     */
    boolean due(ResourceLocation weaponId, WeaponDefinition weapon, long now) {
        int interval = weapon.sound().interval();

        if (interval <= 1) {
            return true;
        }

        long[] at = this.times.computeIfAbsent(weaponId, id -> new long[] {NEVER, NEVER});
        long firedAt = at[0];
        long soundedAt = at[1];

        at[0] = now;

        // 発射速度から出る、次の1発までの tick 数。これより空いていなければ同じ連射の続き。
        long cadence = Math.max(1L, (long) Math.ceil(20.0 / Math.max(weapon.firing().roundsPerSecond(), 1.0E-3F)));
        boolean continuing = firedAt != NEVER && now - firedAt <= cadence;

        if (continuing && soundedAt != NEVER && now - soundedAt < interval) {
            return false;
        }

        at[1] = now;

        return true;
    }
}
