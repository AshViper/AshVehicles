package com.ashvehicles.ai.log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.ai.AiConfig;
import com.ashvehicles.ai.BotPilot;
import com.ashvehicles.ai.core.AiDirector;
import com.ashvehicles.ai.learning.BattleStatistics;
import com.ashvehicles.ai.learning.MapMemory;
import com.ashvehicles.ai.learning.SelfPlay;
import com.ashvehicles.ai.objective.ObjectiveState;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.match.Bots;
import com.ashvehicles.match.Deathmatch;
import com.ashvehicles.match.MatchPoint;
import com.ashvehicles.match.MatchState;
import com.ashvehicles.match.MatchTeam;
import com.google.gson.JsonObject;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

/**
 * 試合と車両で起きたことを、戦闘 AI へ届ける1箇所。被弾の記憶・与ダメージ・撃破・拠点・報酬・戦闘記録。
 *
 * <p><b>勘定の出口には1行ずつしか足していない。</b> 被弾は {@code VehicleEntityBase.hurt}、撃破は
 * {@code Deathmatch.onVehicleDestroyed} と {@code onDeath}、拠点は {@code Deathmatch.capture}、試合の始まりと
 * 終わりは {@code Deathmatch.start} と {@code finish}——どれも試合の勘定が既に1本に絞ってある場所で
 * （[[team-deathmatch-shape]]）、ここはそれを読むだけで、チケットにも得点にも手を出さない。
 *
 * <p><b>毎 tick は何も書かない。</b> 書くのは出来事が起きたときと、判断が変わったとき
 * （{@link PilotRecorder}）だけ。
 *
 * <p><b>報酬は稼いだ車両に付ける</b>（値は設定の {@code [rewards]}）。受け取った報酬は、その車両の一生の合計と、
 * 受け取っている最中の判断（{@link BattleDecisionLog}）の両方に足す——「どの判断の後にどれだけ得をしたか」が
 * 学習の材料になる。
 *
 * <p>サーバースレッド専用。記録を切ってある設定（{@code logging.battles = false}）でも、被弾の記憶と敵情の更新は
 * AI の判断に要るので必ず行う。
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class BattleEvents {
    /** 同じ拠点の防衛成功を2度数えない長さ（tick）。 */
    private static final int DEFENDED_QUIET = 600;

    @Nullable
    private static BattleRecorder battle;

    /** この戦闘で、どの陣営がどの拠点を一度でも握ったか。奪還の判定のため。 */
    private static final Set<String> HELD = new HashSet<>();

    /** 拠点ごとの、最後に防衛成功を数えた時刻。 */
    private static final Map<Long, Long> DEFENDED = new HashMap<>();

    private BattleEvents() {
    }

    /** 戦闘を記録しているか。 */
    public static boolean recording() {
        return battle != null;
    }

    // ------------------------------------------------------------------
    // 試合の始まりと終わり
    // ------------------------------------------------------------------

    public static void matchStarted(MinecraftServer server, MatchState state) {
        long now = server.overworld().getGameTime();

        AiDirector.reset();
        HELD.clear();
        DEFENDED.clear();

        if (battle != null) {
            battle.end(null, now, true);
            battle = null;
        }

        for (MatchPoint point : state.points().values()) {
            if (point.home() != null) {
                HELD.add(point.home() + "@" + point.pos().asLong());
            }
        }

        // 覚えた地図は記録と無関係に要る。学習は記録のためではなく、次の試合で走るためにある。
        MapMemory.battleStarted(server, state);

        if (!AiConfig.get().logging().battles()) {
            return;
        }

        battle = BattleRecorder.begin(server, state, now);
        battle.event(now, BattleEventType.BATTLE_STARTED, null, null, null, 0.0);
    }

    public static void matchEnded(MinecraftServer server, MatchState state, @Nullable MatchTeam winner) {
        long now = server.overworld().getGameTime();
        BattleSummary summary = null;

        if (battle != null) {
            JsonObject detail = new JsonObject();

            // 撃っている最中のまとまりを書き切る。試合が終われば、次に書く機会は来ない。
            for (BotPilot pilot : Bots.pilots()) {
                pilot.recorder().flushReleases(Long.MAX_VALUE);
            }

            detail.addProperty("winner", winner == null ? null : winner.id());

            for (MatchTeam team : state.teams().values()) {
                detail.addProperty("tickets_" + team.id(), team.tickets());
            }

            battle.event(now, BattleEventType.BATTLE_ENDED, null, winner == null ? null : winner.id(), detail, 0.0);
            summary = battle.end(winner == null ? null : winner.id(), now, false);
            battle = null;
            BattleStatistics.record(summary);
        }

        MapMemory.battleEnded();
        SelfPlay.battleEnded(server, state, winner);
    }

    /** サーバーが止まる。決着していない戦闘は中断として書き、統計には入れない。 */
    public static void serverStopping(MinecraftServer server) {
        if (battle != null) {
            battle.end(null, server.overworld().getGameTime(), true);
            battle = null;
        }

        HELD.clear();
        DEFENDED.clear();
    }

    /**
     * 毎 tick（{@code core/AiDirector}）。記録している間だけ、{@value FlightLog#EVERY} tick ごとに航空機の AI の飛び方を1行ずつ
     * 書き（{@link FlightLog}）、撃ち止んだ弾のまとまりを書く（{@link PilotRecorder#flushReleases}）。
     */
    public static void tick(MinecraftServer server) {
        if (battle == null || server.getTickCount() % FlightLog.EVERY != 0) {
            return;
        }

        long now = server.overworld().getGameTime();

        for (BotPilot pilot : Bots.pilots()) {
            pilot.recorder().flushReleases(now);

            if (pilot.vehicle() instanceof AircraftEntity && !pilot.vehicle().isWrecked()) {
                battle.flight(FlightLog.row(battle.battleId, now, pilot));
            }
        }
    }

    // ------------------------------------------------------------------
    // 被弾と撃破
    // ------------------------------------------------------------------

    /**
     * 車両が打撃を受けた。{@code VehicleEntityBase.hurt} から、実際に減った耐久を渡される。
     */
    public static void vehicleHurt(VehicleEntityBase victim, DamageSource source, float dealt) {
        if (dealt <= 0.0F || victim.level().isClientSide) {
            return;
        }

        long now = victim.level().getGameTime();
        Entity attacker = Deathmatch.attackerOf(source);
        BotPilot hurt = victim.getPilot();

        if (hurt != null) {
            hurt.onHurt(attacker, dealt, now);
        }

        noteAllyHurt(victim, attacker, now);

        BotPilot shooter = pilotOf(attacker);

        if (shooter != null && attacker != victim) {
            shooter.onDamageDealt(victim, dealt, now);
        }

        if (battle != null) {
            if (hurt != null) {
                battle.life(hurt, now).damageTaken += dealt;
            }

            if (shooter != null && attacker != victim) {
                battle.life(shooter, now).damageDealt += dealt;
            }
        }
    }

    /** 人が AI に撃たれた。AI の与ダメージと、その人の陣営の敵情のため。 */
    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getNewDamage() <= 0.0F) {
            return;
        }

        long now = player.level().getGameTime();
        Entity attacker = Deathmatch.attackerOf(event.getSource());

        noteAllyHurt(player, attacker, now);

        BotPilot shooter = pilotOf(attacker);

        if (shooter == null) {
            return;
        }

        shooter.onDamageDealt(player, event.getNewDamage(), now);

        if (battle != null) {
            battle.life(shooter, now).damageDealt += event.getNewDamage();
        }
    }

    /** 車両が撃破された。{@code Deathmatch.onVehicleDestroyed} から。 */
    public static void vehicleDestroyed(VehicleEntityBase victim, DamageSource source, @Nullable Entity killer) {
        long now = victim.level().getGameTime();
        BotPilot fallen = victim.getPilot();
        BotPilot scorer = pilotOf(killer);

        // 覚えた地図は地上の車両の経験だけで数える。空で落とされた機体を真下のマスの「倒される所」に数えると、地上の車両が
        // 1両も入っていないマスまで危ない所になる（入った回数は地上の車両しか数えていない——learning/MapTrace）。
        if (fallen != null && victim instanceof GroundVehicleEntity) {
            MapMemory.died(victim);
        }

        if (battle == null) {
            return;
        }

        if (fallen != null) {
            fallen.recorder().flushReleases(Long.MAX_VALUE);

            LifeRecord life = battle.life(fallen, now);
            JsonObject detail = new JsonObject();

            detail.addProperty("killer", killer == null ? null : PilotRecorder.describe(killer));
            reward(fallen, BattleEventType.VEHICLE_DESTROYED, detail);

            if (fallen.recorder().unnecessaryDeath(now)) {
                reward(fallen, BattleEventType.UNNECESSARY_DEATH, null);
            }

            battle.closeLife(life, now, true);
        }

        if (scorer != null && killer != victim && !sameTeam(scorer, victim)) {
            credit(scorer, victim, now);
        }
    }

    /** 人が撃破された。{@code Deathmatch.onDeath} から。 */
    public static void playerKilled(ServerPlayer victim, @Nullable Entity killer) {
        BotPilot scorer = pilotOf(killer);

        if (battle == null || scorer == null) {
            return;
        }

        MatchTeam team = MatchState.of(victim.server).teamOf(victim.getUUID());

        if (team != null && team.id().equals(scorer.team())) {
            return;
        }

        credit(scorer, victim, victim.level().getGameTime());
    }

    private static void credit(BotPilot scorer, Entity victim, long now) {
        LifeRecord life = battle.life(scorer, now);
        JsonObject detail = new JsonObject();

        life.kills++;
        detail.addProperty("victim", PilotRecorder.describe(victim));
        reward(scorer, BattleEventType.ENEMY_DESTROYED, detail);

        // 味方を撃っていた相手を倒した。
        if (AiDirector.brain(scorer.team()).intel().attackedAlly(victim, now,
                AiConfig.sight().attackedWindowTicks())) {
            reward(scorer, BattleEventType.ALLY_SUPPORTED, detail);
        }
    }

    // ------------------------------------------------------------------
    // 拠点
    // ------------------------------------------------------------------

    /**
     * 拠点の持ち主が替わった。{@code Deathmatch.capture} から。
     *
     * @param inside 円の中にいた AI の車両（陣営を問わない）
     */
    public static void pointCaptured(MinecraftServer server, MatchState state, MatchPoint point,
            @Nullable String previousOwner, String newOwner, List<VehicleEntityBase> inside) {
        String key = newOwner + "@" + point.pos().asLong();
        boolean recaptured = previousOwner != null && !previousOwner.equals(newOwner) && HELD.contains(key);
        long now = server.overworld().getGameTime();

        HELD.add(key);

        if (battle == null) {
            return;
        }

        JsonObject detail = new JsonObject();

        detail.addProperty("point", point.name());
        detail.addProperty("from", previousOwner);
        detail.addProperty("to", newOwner);

        BattleEventType earned = recaptured ? BattleEventType.OBJECTIVE_RECAPTURED
                : BattleEventType.OBJECTIVE_CAPTURED;
        boolean anyone = false;

        for (VehicleEntityBase machine : inside) {
            BotPilot pilot = machine.getPilot();

            if (pilot == null || !newOwner.equals(pilot.team())) {
                continue;
            }

            LifeRecord life = battle.life(pilot, now);

            if (recaptured) {
                life.recaptures++;
            } else {
                life.captures++;
            }

            reward(pilot, earned, detail);
            anyone = true;
        }

        if (!anyone) {
            battle.event(now, earned, null, newOwner, detail, 0.0);
        }

        if (previousOwner == null || previousOwner.equals(newOwner)) {
            return;
        }

        // 失った側。その拠点を持ち場にしていた AI と、円の近くにいた AI が報酬を払う。
        battle.event(now, BattleEventType.OBJECTIVE_LOST, null, previousOwner, detail, 0.0);

        for (BotPilot pilot : Bots.pilotsOf(previousOwner)) {
            ObjectiveState duty = AiDirector.brain(previousOwner).board()
                    .assignmentOrBest(pilot, AiDirector.brain(previousOwner).intel());
            double dx = pilot.vehicle().getX() - (point.pos().getX() + 0.5);
            double dz = pilot.vehicle().getZ() - (point.pos().getZ() + 0.5);
            boolean near = dx * dx + dz * dz <= point.radius() * point.radius() * 4.0;

            if ((duty != null && duty.id().equals(point.name())) || near) {
                reward(pilot, BattleEventType.OBJECTIVE_LOST, detail);
            }
        }
    }

    /**
     * 取られかけていた自陣の拠点の進みを、中にいた者が消し切った。{@code Deathmatch.capture} から。
     */
    public static void pointDefended(MinecraftServer server, MatchState state, MatchPoint point, String owner,
            List<VehicleEntityBase> inside) {
        long now = server.overworld().getGameTime();
        Long last = DEFENDED.get(point.pos().asLong());

        if (battle == null || (last != null && now - last < DEFENDED_QUIET)) {
            return;
        }

        DEFENDED.put(point.pos().asLong(), now);

        JsonObject detail = new JsonObject();

        detail.addProperty("point", point.name());

        for (VehicleEntityBase machine : inside) {
            BotPilot pilot = machine.getPilot();

            if (pilot == null || !owner.equals(pilot.team())) {
                continue;
            }

            battle.life(pilot, now).defenses++;
            reward(pilot, BattleEventType.OBJECTIVE_DEFENDED, detail);
        }
    }

    // ------------------------------------------------------------------
    // AI からの記録
    // ------------------------------------------------------------------

    /** 持ち場が変わった。陣営の頭から。 */
    public static void objectiveSelected(BotPilot pilot, ObjectiveState objective) {
        if (battle == null) {
            return;
        }

        JsonObject detail = new JsonObject();

        detail.addProperty("objective", objective.id());
        detail.addProperty("state", objective.state().name());
        detail.addProperty("score", BattleDecisionLog.round(objective.score()));
        pilotEvent(pilot, BattleEventType.OBJECTIVE_SELECTED, detail);
    }

    /** 報酬の付かない出来事。 */
    public static void pilotEvent(BotPilot pilot, BattleEventType type, @Nullable JsonObject detail) {
        if (battle == null) {
            return;
        }

        long now = pilot.vehicle().level().getGameTime();

        battle.event(now, type, battle.life(pilot, now), null, detail, 0.0, pilot.vehicle().position());
    }

    /** 報酬の付かない出来事を、起きた時刻と位置で。起きてから少し後に書く物（撃った弾のまとまり）のため。 */
    public static void pilotEvent(BotPilot pilot, BattleEventType type, @Nullable JsonObject detail, Vec3 at, long when) {
        if (battle == null) {
            return;
        }

        battle.event(when, type, battle.life(pilot, when), null, detail, 0.0, at);
    }

    /** 報酬の付く出来事。報酬はその車両の一生と、受け取っている最中の判断に足す。 */
    public static void reward(BotPilot pilot, BattleEventType type, @Nullable JsonObject detail) {
        if (battle == null) {
            return;
        }

        long now = pilot.vehicle().level().getGameTime();
        LifeRecord life = battle.life(pilot, now);
        double value = type.reward(AiConfig.get().rewards());

        life.reward += value;

        if (life.open != null) {
            life.open.addReward(value);
        }

        battle.event(now, type, life, null, detail, value, pilot.vehicle().position());
    }

    /** 判断を1つ記録する。 */
    public static void decision(BotPilot pilot, BattleDecisionLog log) {
        if (battle == null) {
            return;
        }

        battle.decision(battle.life(pilot, log.timestamp()), log);
    }

    // ------------------------------------------------------------------

    @Nullable
    private static BotPilot pilotOf(@Nullable Entity entity) {
        return entity instanceof VehicleEntityBase machine ? machine.getPilot() : null;
    }

    private static boolean sameTeam(BotPilot pilot, Entity other) {
        MinecraftServer server = pilot.vehicle().getServer();

        if (server == null) {
            return false;
        }

        return pilot.team().equals(Deathmatch.teamIdOf(MatchState.of(server), other));
    }

    /** 撃たれた者の陣営の敵情に、撃った者を載せる。 */
    private static void noteAllyHurt(Entity victim, @Nullable Entity attacker, long now) {
        MinecraftServer server = victim.getServer();

        if (attacker == null || server == null) {
            return;
        }

        MatchState state = MatchState.of(server);

        if (!state.isRunning()) {
            return;
        }

        String team = Deathmatch.teamIdOf(state, victim);

        if (team != null && !team.equals(Deathmatch.teamIdOf(state, attacker))) {
            AiDirector.brain(team).intel().allyHurt(attacker, now);
        }
    }
}
