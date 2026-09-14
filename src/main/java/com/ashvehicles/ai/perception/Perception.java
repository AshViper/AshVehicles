package com.ashvehicles.ai.perception;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.ai.AiConfig;
import com.ashvehicles.ai.BotPilot;
import com.ashvehicles.ai.battlefield.TeamIntel;
import com.ashvehicles.ai.objective.CaptureState;
import com.ashvehicles.ai.objective.ObjectiveState;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.entity.VehiclePart;
import com.ashvehicles.match.Bots;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * AI 1両の目。敵と味方を観測し、脅威を見積もり、見付けた敵を陣営へ知らせる。
 *
 * <p><b>世界を掃かない。</b> 候補は試合の名簿（{@link Bots#combatants}）だけで、{@code level.getEntities} で箱を
 * 掃くことはしない——あれの代償は箱の大きさで払う物で（[[entity-queries-cost-box-size]]）、40両が毎秒数回
 * 300 ブロックの箱を掃けば、それだけでこの MOD で最も高価な処理になる。試合の参加者は多くても数十で、しかも
 * 試合の側が全部知っている（元の {@code ai/BotTargets} の決定をそのまま引き継いでいる）。
 *
 * <p><b>ただし名簿から近い物を撃つだけではない。</b> 1体ずつ距離・視線（{@link LineOfSight}、双方向）・
 * 双方の射程・砲の向き・被弾の記憶・拠点と味方への関与を見て、脅威（{@link ThreatModel}）まで出す。
 *
 * <p><b>視線は近い数件だけ</b>（{@code perception.raysPerPass}）、しかも組ごとに覚えて1 tick の予算の内で引く。
 * 測れなかった相手は「分からない」のまま——安全の意味ではない。
 *
 * <p>周期は {@code timing.perception}（既定 5 tick＝4 Hz）で、個体ごとにずらす。
 */
public final class Perception {
    /** 名簿から消えた相手の観測を捨てるまで（tick）。 */
    private static final int FORGET_UNLISTED = 40;

    /** 拠点を攻めていると見なす、円の外側の余裕（ブロック）。 */
    private static final double OBJECTIVE_MARGIN = 12.0;

    /** 見る距離に対して、観測を持ち続ける距離の倍率。 */
    private static final double KEEP = 1.5;

    /** 歩いている人の視界（ブロック）。 */
    private static final double INFANTRY_SIGHT = 96.0;

    /** 脅威を感じ始める値。これより小さい脅威は重心にも「撃ち合っている」にも数えない。 */
    private static final double NOTICE = 0.05;

    /**
     * 空を撃てる車両が、飛んでいる機体を見る距離（視界の倍率）。地上の視界（既定160ブロック）では、対空車両は
     * 爆撃に来た機体をほぼ真上に来るまで見付けられない。
     */
    private static final double AIR_SIGHT = 8.0;

    /** 見上げる機体がこれだけ高ければ、視線を引かずに見えると数える（ブロック）。 */
    private static final double AIR_CLEAR = 30.0;

    /** 陣営にとって新しい敵を見付けたときに呼ばれる。記録（{@code EnemyDetected}）のため。 */
    @FunctionalInterface
    public interface Listener {
        void enemyDetected(EnemyObservation observation);
    }

    /** @param beyond 地上の視界の外にいて、空を撃てる車両だから持っている空の相手。脅威には数えない */
    private record Candidate(Entity entity, String team, double distance, boolean beyond) {
    }

    private final VehicleEntityBase self;
    private final DamageMemory damage;
    private final Int2ObjectOpenHashMap<EnemyObservation> enemyById = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<AllyObservation> allyById = new Int2ObjectOpenHashMap<>();
    private final List<EnemyObservation> enemies = new ArrayList<>();
    private final List<AllyObservation> allies = new ArrayList<>();
    private final List<Candidate> candidates = new ArrayList<>();

    private double threat;
    private int engaging;

    @Nullable
    private Vec3 threatCentroid;

    @Nullable
    private Listener listener;

    public Perception(VehicleEntityBase self, DamageMemory damage) {
        this.self = self;
        this.damage = damage;
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    /**
     * 観測し直す。
     *
     * @param team       自分の陣営
     * @param intel      陣営の敵情。見付けた敵をここへ知らせ、自分に見えない敵の位置をここから引く
     * @param objectives 陣営から見た拠点。「拠点を攻めている敵」を見分けるため
     */
    public void observe(long now, String team, TeamIntel intel, List<ObjectiveState> objectives) {
        MinecraftServer server = this.self.getServer();

        if (server == null || team.isEmpty()) {
            return;
        }

        AiConfig.Sight settings = AiConfig.sight();
        double sight = settings.range();
        int window = settings.attackedWindowTicks();
        Vec3 eye = LineOfSight.sightOf(this.self);
        WeaponReach mine = WeaponReach.of(this.self);

        // 空を撃てる車両だけが空を遠くまで見る。撃てない車両が遠くの機体を見ても、することが無い。
        double airSight = mine.rangeAgainst(true) > 0.0 ? sight * AIR_SIGHT : sight;

        this.candidates.clear();

        for (Bots.Fighter fighter : Bots.combatants(server)) {
            Entity entity = fighter.entity();

            if (entity == this.self || entity.level() != this.self.level() || fighter.team().isEmpty()) {
                continue;
            }

            if (team.equals(fighter.team())) {
                this.observeAlly(entity, eye, now);

                continue;
            }

            if (!worthWatching(entity)) {
                continue;
            }

            double distance = LineOfSight.sightOf(entity).distanceTo(eye);
            // 遠い相手は、陣営の誰かが見ているか、自分を撃ってきたときだけ持つ。空を撃てる車両は、それに加えて遠くの
            // 空の相手を持つ——撃つために。
            boolean beyond = distance > sight * KEEP && intel.spotted(entity, now) == null
                    && !this.damage.hurtBy(entity, now, window);

            if (beyond && !(aloft(entity) && distance <= airSight * KEEP)) {
                continue;
            }

            this.candidates.add(new Candidate(entity, fighter.team(), distance, beyond));
        }

        this.candidates.sort(Comparator.comparingDouble(Candidate::distance));

        double combined = 0.0;
        int engaged = 0;
        double weightX = 0.0;
        double weightY = 0.0;
        double weightZ = 0.0;
        double weights = 0.0;

        for (int at = 0; at < this.candidates.size(); at++) {
            Candidate candidate = this.candidates.get(at);
            Entity entity = candidate.entity();
            double distance = candidate.distance();
            EnemyObservation observation = this.enemyById.get(entity.getId());

            if (observation == null) {
                observation = new EnemyObservation(entity);
                this.enemyById.put(entity.getId(), observation);
                this.enemies.add(observation);
            }

            boolean flying = aloft(entity);
            double watch = flying ? airSight : sight;
            Vec3 theirEye = LineOfSight.sightOf(entity);
            // 視線を引くのは近い数件だけ。線1本の代償はブロックを歩くことで、候補の数だけ引く物ではない。
            // 空を撃てる車両から見上げる十分高い機体は、引かずに見えると数える——遠い空は近い地上の候補の後ろに並ぶので
            // 線が1本も回ってこない。撃つ前には引き金が線を確かめる（{@code combat/FireControl}）。
            LineOfSight.Result line = flying && watch > sight && theirEye.y - eye.y >= AIR_CLEAR
                    ? LineOfSight.Result.CLEAR
                    : at < settings.raysPerPass() && distance <= watch * KEEP
                            ? LineOfSight.between(this.self, entity) : LineOfSight.Result.UNKNOWN;
            WeaponReach theirs = WeaponReach.of(entity);
            boolean armoured = entity instanceof VehicleEntityBase machine && machine.isArmoured();
            float armour = entity instanceof VehicleEntityBase machine ? machine.armour() : 0.0F;
            Vec3 towards = theirEye.subtract(eye);
            Vec3 direction = towards.lengthSqr() > 1.0E-6 ? towards.normalize() : Vec3.ZERO;
            Vec3 velocity = entity instanceof VehicleEntityBase machine
                    ? machine.getVelocity() : entity.getDeltaMovement();
            boolean clear = line == LineOfSight.Result.CLEAR;
            boolean known = line != LineOfSight.Result.UNKNOWN;
            boolean visible = clear && distance <= watch;
            // 見られているかは、相手の目の届く距離で測る。線は同じでも、歩兵の視界と戦車の視界は違う。
            double theirSight = entity instanceof Player player && player.getVehicle() == null ? INFANTRY_SIGHT
                    : flying ? sight * 2.0 : sight;
            boolean seenBy = clear && distance <= theirSight;
            double theirRange = theirs.rangeAgainst(false);
            boolean inTheirRange = distance <= theirRange;
            boolean inMyRange = distance <= mine.rangeAgainst(flying);
            boolean attackingMe = this.damage.hurtBy(entity, now, window);
            boolean attackingAlly = intel.attackedAlly(entity, now, window);
            boolean attackingObjective = attacksObjective(entity, objectives, team);
            boolean aiming = ThreatModel.aimingAt(entity, eye);
            double capability = ThreatModel.capability(entity, theirs, this.self);
            // 地上の視界の外の空の相手は、撃つ相手であって身を守る相手ではない。脅威に数えれば、遮蔽と後退と脅威の
            // 重心が空の遠くの1機に引かれる。
            double threatLevel = candidate.beyond() ? 0.0
                    : ThreatModel.threat(capability, distance, theirRange, seenBy, known, aiming, attackingMe);

            observation.setTeam(candidate.team());
            observation.setGeometry(distance, direction, velocity);
            observation.setSight(clear, known, visible, seenBy);
            observation.setRanges(inMyRange, inTheirRange);
            observation.setIntent(attackingMe, attackingAlly, attackingObjective, aiming);
            observation.setBody(flying, armoured, healthOf(entity), theirs);
            observation.setThreat(threatLevel, capability, mine.effectiveness(armoured, armour));

            if (visible) {
                observation.markSeen(now);
                observation.setLastKnownPosition(entity.position());
                observation.setSpottedByTeam(false);

                // 遠くの空の相手は陣営へは知らせない。脅威マップは相手の目に見える地面を全部危ないと塗るので、空の1機が
                // 戦場の広くを塗り、地上の道が空の下を避け始める。
                if ((!flying || distance <= sight)
                        && intel.report(entity, theirEye, velocity, capability, theirRange, flying, now)
                        && this.listener != null) {
                    this.listener.enemyDetected(observation);
                }
            } else {
                TeamIntel.Spotted spotted = intel.spotted(entity, now);

                observation.setSpottedByTeam(spotted != null && spotted.current(now));

                if (spotted != null && spotted.seenTick() > observation.lastSeenTick()) {
                    observation.setLastKnownPosition(spotted.position());
                }
            }

            observation.markUpdated(now);
            combined = ThreatModel.combine(combined, threatLevel);

            if (seenBy && inTheirRange && threatLevel > 0.15) {
                engaged++;
            }

            if (threatLevel > NOTICE) {
                weightX += theirEye.x * threatLevel;
                weightY += theirEye.y * threatLevel;
                weightZ += theirEye.z * threatLevel;
                weights += threatLevel;
            }
        }

        this.enemies.removeIf(observation -> {
            boolean drop = !observation.alive() || now - observation.updatedTick() > FORGET_UNLISTED;

            if (drop) {
                this.enemyById.remove(observation.target().getId());
            }

            return drop;
        });
        this.allies.removeIf(ally -> {
            boolean drop = ally.entity().isRemoved() || now - ally.updatedTick() > FORGET_UNLISTED;

            if (drop) {
                this.allyById.remove(ally.entity().getId());
            }

            return drop;
        });
        this.enemies.sort(Comparator.comparingDouble(EnemyObservation::distance));
        this.allies.sort(Comparator.comparingDouble(AllyObservation::distance));

        this.threat = combined;
        this.engaging = engaged;
        this.threatCentroid = weights > 0.0 ? new Vec3(weightX / weights, weightY / weights, weightZ / weights)
                : intel.threatCentroid(eye, sight * KEEP, now);
    }

    /** 観測した敵。近い順。見失った敵も記憶の間は残る。 */
    public List<EnemyObservation> enemies() {
        return this.enemies;
    }

    /** 観測した味方。近い順。 */
    public List<AllyObservation> allies() {
        return this.allies;
    }

    /** 全部の敵の脅威を合わせた物（0〜1）。 */
    public double threat() {
        return this.threat;
    }

    /** こちらを見ていて、射程に入れている、脅威のある敵の数。 */
    public int engaging() {
        return this.engaging;
    }

    /** 脅威の重心。撃たれる向きの目安。脅威が無ければ陣営の知っている敵の重心、それも無ければ null。 */
    @Nullable
    public Vec3 threatCentroid() {
        return this.threatCentroid;
    }

    /** 撃たれているか。 */
    public boolean underFire(long now) {
        return this.damage.underFire(now, AiConfig.sight().attackedWindowTicks());
    }

    /** その半径の内にいる味方の数。 */
    public int alliesWithin(double radius) {
        int counted = 0;

        for (AllyObservation ally : this.allies) {
            if (ally.distance() <= radius) {
                counted++;
            }
        }

        return counted;
    }

    private void observeAlly(Entity entity, Vec3 eye, long now) {
        AllyObservation observation = this.allyById.get(entity.getId());

        if (observation == null) {
            observation = new AllyObservation(entity);
            this.allyById.put(entity.getId(), observation);
            this.allies.add(observation);
        }

        Vec3 towards = LineOfSight.sightOf(entity).subtract(eye);
        double distance = towards.length();
        BotPilot pilot = entity instanceof VehicleEntityBase machine ? machine.getPilot() : null;
        Entity engaging = pilot == null ? null : pilot.target();

        observation.update(distance, distance > 1.0E-6 ? towards.scale(1.0 / distance) : Vec3.ZERO,
                healthOf(entity), pilot != null, pilot != null && pilot.underFire(now),
                pilot == null ? null : pilot.role(), pilot == null ? null : pilot.action(),
                pilot == null ? -1 : pilot.attackerId(now), engaging == null ? -1 : engaging.getId(), now);
    }

    /** 飛んでいる機体か。 */
    private static boolean aloft(Entity entity) {
        return entity instanceof AircraftEntity aircraft && !aircraft.onGround();
    }

    /** 観測する価値のある敵か。残骸・積荷・当たり判定の部品・乗っている人は数えない。 */
    private static boolean worthWatching(Entity entity) {
        if (entity.isRemoved() || !entity.isAlive() || entity instanceof VehiclePart) {
            return false;
        }

        if (entity instanceof VehicleEntityBase machine) {
            return !machine.isWrecked() && !machine.isCargo();
        }

        // 乗っている者は乗り物の側で名簿に載っている。
        return entity.getVehicle() == null;
    }

    private static float healthOf(Entity entity) {
        if (entity instanceof VehicleEntityBase machine) {
            return machine.getHealthFraction();
        }

        if (entity instanceof LivingEntity living) {
            return living.getHealth() / Math.max(living.getMaxHealth(), 1.0F);
        }

        return 1.0F;
    }

    /** その敵が、自陣の拠点を取りに来ているか。 */
    private static boolean attacksObjective(Entity enemy, List<ObjectiveState> objectives, String team) {
        for (ObjectiveState objective : objectives) {
            boolean ours = objective.state() == CaptureState.FRIENDLY
                    || (objective.state() == CaptureState.CONTESTED && team.equals(objective.owner()));

            if (!ours) {
                continue;
            }

            double dx = enemy.getX() - objective.centre().x;
            double dz = enemy.getZ() - objective.centre().z;
            double reach = objective.radius() + OBJECTIVE_MARGIN;

            if (dx * dx + dz * dz <= reach * reach) {
                return true;
            }
        }

        return false;
    }
}
