package com.ashvehicles.ai.battlefield;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.ai.AiConfig;
import com.ashvehicles.entity.VehicleEntityBase;

import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 陣営1つが共有する敵情。誰がどこで見られたか、誰が味方を撃ったか、誰が誰と撃ち合っているか、そして脅威マップ。
 *
 * <p><b>見付けた1両が、陣営全員の目になる。</b> 偵察車が丘の向こうに敵を見付ければ、見えていない戦車もその位置を
 * 知って砲を向け、側面へ回る車両はその敵の正面を避ける道を引く。無線で位置を知らせるのと同じで、共有するのは
 * 「どこにいたか」であって視線ではない——撃つ前の射線は各自が自分で引く（{@code perception/LineOfSight}）。
 *
 * <p><b>見失っても忘れない。</b> 最後に見た位置は記憶の長さ（{@code perception.memoryTicks}）だけ残り、脅威マップの
 * 危険の層にも薄れながら残る（{@link ThreatMap}）。
 *
 * <p>持ち主は陣営の頭（{@code team/TeamBrain}）で、書くのは各 AI の観測（{@code perception/Perception}）と
 * 被弾の出口（{@code log/BattleEvents}）。サーバースレッド専用。
 */
public final class TeamIntel {
    /** 撃ち合いを「今も続いている」と見なす長さ（tick）。 */
    private static final int ENGAGEMENT_TICKS = 40;

    /** 「今見えている」と見なす長さ（tick）。観測の周期より少し長い。 */
    private static final int CURRENT_TICKS = 20;

    /** 味方を撃った記録を捨てるまで（tick）。 */
    private static final int ATTACK_MEMORY = 1200;

    /** 見付かった敵1つ。 */
    public static final class Spotted {
        private final Entity entity;
        private Vec3 position;
        private Vec3 sight;
        private Vec3 velocity = Vec3.ZERO;
        private long seenTick = Long.MIN_VALUE;
        private double capability;
        private double reach;
        private boolean flying;

        Spotted(Entity entity) {
            this.entity = entity;
            this.position = entity.position();
            this.sight = this.position;
        }

        public Entity entity() {
            return this.entity;
        }

        public Vec3 position() {
            return this.position;
        }

        public Vec3 sight() {
            return this.sight;
        }

        public Vec3 velocity() {
            return this.velocity;
        }

        public long seenTick() {
            return this.seenTick;
        }

        public double capability() {
            return this.capability;
        }

        public double reach() {
            return this.reach;
        }

        public boolean flying() {
            return this.flying;
        }

        /** 今も誰かが見ているか。 */
        public boolean current(long now) {
            return now - this.seenTick <= CURRENT_TICKS;
        }

        boolean gone() {
            if (this.entity.isRemoved() || !this.entity.isAlive()) {
                return true;
            }

            return this.entity instanceof VehicleEntityBase machine && machine.isWrecked();
        }
    }

    private final String team;
    private final ThreatMap threats = new ThreatMap();
    private final Int2ObjectOpenHashMap<Spotted> spotted = new Int2ObjectOpenHashMap<>();
    private final Int2LongOpenHashMap allyAttackers = new Int2LongOpenHashMap();

    /** 撃たれている相手 → (撃っている AI → 最後に撃った tick)。 */
    private final Int2ObjectOpenHashMap<Int2LongOpenHashMap> engagements = new Int2ObjectOpenHashMap<>();

    private final List<ThreatSource> sources = new ArrayList<>();

    public TeamIntel(String team) {
        this.team = team;
    }

    public String team() {
        return this.team;
    }

    public ThreatMap threats() {
        return this.threats;
    }

    /**
     * 味方の誰かが敵を見た。
     *
     * @return 陣営にとって新しい敵（初めて見たか、記憶が切れた後に見直した）なら true。{@code EnemyDetected} の合図
     */
    public boolean report(Entity enemy, Vec3 sight, Vec3 velocity, double capability, double reach, boolean flying,
            long now) {
        Spotted known = this.spotted.get(enemy.getId());
        boolean fresh = known == null || now - known.seenTick > AiConfig.sight().memoryTicks();

        if (known == null) {
            known = new Spotted(enemy);
            this.spotted.put(enemy.getId(), known);
        }

        // 壊す力は見た者ごとに違う（相手が戦車か装甲車かで答えが変わる）。今の窓で一番大きい物を持つ。
        known.capability = now - known.seenTick > CURRENT_TICKS ? capability
                : Math.max(known.capability, capability);
        known.position = enemy.position();
        known.sight = sight;
        known.velocity = velocity;
        known.reach = reach;
        known.flying = flying;
        known.seenTick = now;

        return fresh;
    }

    /** その敵の最後に知っている位置。記憶が切れていれば null。 */
    @Nullable
    public Spotted spotted(Entity enemy, long now) {
        Spotted known = this.spotted.get(enemy.getId());

        return known == null || now - known.seenTick > AiConfig.sight().memoryTicks() ? null : known;
    }

    public Collection<Spotted> spotted() {
        return this.spotted.values();
    }

    /** 覚えている敵の数。 */
    public int knownEnemies(long now) {
        int memory = AiConfig.sight().memoryTicks();
        int counted = 0;

        for (Spotted known : this.spotted.values()) {
            if (now - known.seenTick <= memory && !known.gone()) {
                counted++;
            }
        }

        return counted;
    }

    /** 味方の誰かが撃たれた。撃った者（と乗っている物）を覚える。 */
    public void allyHurt(@Nullable Entity attacker, long now) {
        if (attacker == null) {
            return;
        }

        this.allyAttackers.put(attacker.getId(), now);

        if (attacker.getVehicle() != null) {
            this.allyAttackers.put(attacker.getVehicle().getId(), now);
        }
    }

    /** その相手が、窓の内に味方を撃ったか。 */
    public boolean attackedAlly(Entity enemy, long now, int window) {
        long at = this.allyAttackers.getOrDefault(enemy.getId(), Long.MIN_VALUE);

        return at != Long.MIN_VALUE && now - at <= window;
    }

    /** AI が相手を撃った。側面へ回る判断が「誰かが正面を受け持っているか」を訊くため。 */
    public void engage(int bot, int target, long now) {
        Int2LongOpenHashMap shooters = this.engagements.get(target);

        if (shooters == null) {
            shooters = new Int2LongOpenHashMap();
            this.engagements.put(target, shooters);
        }

        shooters.put(bot, now);
    }

    /** その相手を今撃っている味方の AI の数。自分を除く。 */
    public int engagedBy(int target, int except, long now) {
        Int2LongOpenHashMap shooters = this.engagements.get(target);

        if (shooters == null) {
            return 0;
        }

        int counted = 0;

        for (Int2LongMap.Entry entry : shooters.int2LongEntrySet()) {
            if (entry.getIntKey() != except && now - entry.getLongValue() <= ENGAGEMENT_TICKS) {
                counted++;
            }
        }

        return counted;
    }

    /**
     * 近くの脅威の重心。遮蔽や撤退の「どちらから撃たれるか」の答え。覚えている敵が近くにいなければ null。
     */
    @Nullable
    public Vec3 threatCentroid(Vec3 near, double radius, long now) {
        int memory = AiConfig.sight().memoryTicks();
        double x = 0.0;
        double y = 0.0;
        double z = 0.0;
        double weight = 0.0;

        for (Spotted known : this.spotted.values()) {
            if (now - known.seenTick > memory || known.gone()
                    || known.position.distanceToSqr(near) > radius * radius) {
                continue;
            }

            double w = Math.max(known.capability, 0.05) * (known.current(now) ? 1.0 : 0.5);

            x += known.sight.x * w;
            y += known.sight.y * w;
            z += known.sight.z * w;
            weight += w;
        }

        return weight <= 0.0 ? null : new Vec3(x / weight, y / weight, z / weight);
    }

    /**
     * 記憶の切れた敵を落とし、脅威マップを塗り直す。陣営の頭が数十 tick に1回呼ぶ。
     */
    public void update(Level level, long now) {
        AiConfig.Threats settings = AiConfig.threats();
        int memory = AiConfig.sight().memoryTicks();
        ObjectIterator<Int2ObjectMap.Entry<Spotted>> walk = this.spotted.int2ObjectEntrySet().fastIterator();

        this.sources.clear();

        while (walk.hasNext()) {
            Spotted known = walk.next().getValue();

            if (known.gone() || now - known.seenTick > memory || known.entity.level() != level) {
                walk.remove();

                continue;
            }

            this.sources.add(new ThreatSource(known.entity.getId(), known.sight, known.capability,
                    Math.min(known.reach, settings.radius()), known.seenTick, known.current(now)));
        }

        // 今見えている物が先、その中で強い物が先。多すぎる分はここで切る——弱い敵の視界で強い敵の視界を
        // 押し出さないように。
        this.sources.sort((left, right) -> {
            if (left.current() != right.current()) {
                return left.current() ? -1 : 1;
            }

            return Double.compare(right.capability(), left.capability());
        });

        while (this.sources.size() > settings.sources()) {
            this.sources.remove(this.sources.size() - 1);
        }

        this.threats.update(HeightField.of(level), this.sources, now, memory, settings.decay(),
                settings.unknownExposure());

        this.allyAttackers.int2LongEntrySet().removeIf(entry -> now - entry.getLongValue() > ATTACK_MEMORY);
        this.engagements.int2ObjectEntrySet().removeIf(entry -> {
            entry.getValue().int2LongEntrySet().removeIf(shot -> now - shot.getLongValue() > ENGAGEMENT_TICKS * 4);

            return entry.getValue().isEmpty();
        });
    }
}
