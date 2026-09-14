package com.ashvehicles.ai.team;

import java.util.ArrayList;
import java.util.List;

import com.ashvehicles.ai.AiConfig;
import com.ashvehicles.ai.BotPilot;
import com.ashvehicles.ai.GroundPilot;
import com.ashvehicles.ai.battlefield.TeamIntel;
import com.ashvehicles.ai.core.Cadence;
import com.ashvehicles.ai.learning.AiVersions;
import com.ashvehicles.ai.log.BattleEventType;
import com.ashvehicles.ai.log.BattleEvents;
import com.ashvehicles.ai.objective.ObjectiveBoard;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.match.Bots;
import com.ashvehicles.match.MatchState;
import com.google.gson.JsonObject;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 陣営1つの頭。敵情（{@link TeamIntel}）と持ち場の割り当て（{@link ObjectiveBoard}）を持ち、陣営単位の重い仕事を
 * 周期ごとに1回だけ回す。
 *
 * <p><b>各 AI ではなく陣営で1回。</b> 脅威マップを塗り直すのも、全拠点に点数を付けて配るのも、20両がめいめいに
 * やれば20倍の値段になる。見ている戦場は同じなので、結論も1つで足りる。各 AI はここを読むだけ。
 *
 * <p><b>部隊の連携はここから始まる。</b> 偵察車が見付けた敵は陣営の敵情に載って戦車の砲が向き、誰かが正面で
 * 撃ち合っている相手は側面へ回る車両の判断に入る。役割ごとの分担（偵察は遠くの取るべき拠点へ、砲兵は前に
 * 出ない、防空は自陣の拠点に残る）は割り当ての点数に入っている（{@code ObjectiveScoring.botScore}）。押されている AI の支援の
 * 要請に、手の空いている近くの味方を割り当て、要請の主を撃っている敵を航空機に知らせるのもここ（{@link SupportCalls}）。
 *
 * <p>持ち主は {@code core/AiDirector}。試合が動いていない間は作り直される。
 */
public final class TeamBrain {
    /** 持ち場を配られていない AI を探す間隔（tick）。 */
    private static final int UNASSIGNED_EVERY = 10;

    /** 支援の要請に味方を割り当て直す間隔（tick）。 */
    private static final int SUPPORT_EVERY = 20;

    private final String team;
    private final TeamIntel intel;
    private final ObjectiveBoard board;
    private final SupportCalls support = new SupportCalls();
    private final Cadence threatCadence;
    private final Cadence objectiveCadence;
    private int age;

    public TeamBrain(String team) {
        this.team = team;
        this.intel = new TeamIntel(team);
        this.board = new ObjectiveBoard(team);
        // 陣営ごとに位相をずらす。赤と青が同じ tick に脅威マップを塗れば、その1 tick だけが2倍重い。
        this.threatCadence = new Cadence(team.hashCode(), AiConfig.timing().threatMap());
        this.objectiveCadence = new Cadence(team.hashCode() * 31, AiConfig.timing().objective());
    }

    public String team() {
        return this.team;
    }

    public TeamIntel intel() {
        return this.intel;
    }

    public ObjectiveBoard board() {
        return this.board;
    }

    /** 支援の要請。 */
    public SupportCalls support() {
        return this.support;
    }

    /** 1 tick 分。中で間引く。 */
    public void tick(MinecraftServer server, MatchState state) {
        ServerLevel level = state.level(server);

        if (level == null) {
            level = server.overworld();
        }

        long now = level.getGameTime();
        AiConfig.Timing timing = AiConfig.timing();

        this.age++;

        if (this.threatCadence.tick(timing.threatMap())) {
            this.intel.update(level, now);
        }

        if (this.age % SUPPORT_EVERY == 0) {
            this.answerCalls(now);
        }

        boolean due = this.objectiveCadence.tick(timing.objective());
        List<BotPilot> pilots = null;

        if (!due && this.age % UNASSIGNED_EVERY == 0) {
            pilots = grounded(Bots.pilotsOf(this.team));
            due = this.board.hasUnassigned(pilots);
        }

        if (!due) {
            return;
        }

        if (pilots == null) {
            pilots = grounded(Bots.pilotsOf(this.team));
        }

        this.board.refresh(server, state, this.intel, pilots, AiVersions.forTeam(server, this.team).parameters(),
                now);

        for (ObjectiveBoard.Change change : this.board.drainChanges()) {
            BattleEvents.objectiveSelected(change.pilot(), change.objective());
        }
    }

    /** 支援の要請に、手の空いている近くの味方を割り当てる（{@link SupportCalls}）。倒された AI の要請はここで捨てる。 */
    private void answerCalls(long now) {
        List<BotPilot> pilots = Bots.pilotsOf(this.team);
        IntOpenHashSet alive = new IntOpenHashSet(pilots.size());
        Int2ObjectOpenHashMap<BotPilot> byId = new Int2ObjectOpenHashMap<>(pilots.size());
        List<SupportCalls.Candidate> candidates = new ArrayList<>();

        for (BotPilot pilot : pilots) {
            int id = pilot.vehicle().getId();

            alive.add(id);
            byId.put(id, pilot);

            if (pilot instanceof GroundPilot ground && ground.canAnswer(now)) {
                candidates.add(new SupportCalls.Candidate(id, pilot.vehicle().position()));
            }
        }

        this.support.retain(alive);

        for (SupportCalls.Answer answer : this.support.assign(candidates, now)) {
            BotPilot pilot = byId.get(answer.responder());

            if (pilot == null) {
                continue;
            }

            JsonObject detail = new JsonObject();

            detail.addProperty("caller", answer.call().caller());
            detail.addProperty("distance", Math.round(answer.distance()));
            detail.addProperty("urgency", Math.round(answer.call().urgency() * 100.0) / 100.0);
            BattleEvents.pilotEvent(pilot, BattleEventType.SUPPORT_ANSWERED, detail);
        }
    }

    /**
     * 持ち場の枠に数える AI。<b>航空機は外す</b>——拠点は地上でしか取れない（{@code Deathmatch.capture}）ので、
     * 空の AI が枠を1つ取れば、その拠点へ行く車両が1両減るだけになる。航空機は自分で空から見る場所を選ぶ
     * （{@code AirPilot}）。
     */
    private static List<BotPilot> grounded(List<BotPilot> pilots) {
        pilots.removeIf(pilot -> pilot.vehicle() instanceof AircraftEntity);

        return pilots;
    }
}
