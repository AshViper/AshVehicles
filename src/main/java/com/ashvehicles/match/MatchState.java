package com.ashvehicles.match;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * チームデスマッチ1つぶんの状態。ワールドに保存される。
 *
 * <p><b>ワールドに付ける。</b> 試合の途中でサーバーが落ちたときに、陣営も所属も得点も消えていては
 * 運営が成り立たない。だから静的な変数ではなくオーバーワールドの {@link SavedData} に置く——次元が
 * 増えても試合は1つで、参加者がどの次元に居ても同じ帳簿を見る。
 *
 * <p><b>時間は tick で持つ。</b> 秒でも分でもなく、サーバーが実際に数えている単位そのもの。表示の
 * ために割るのは画面の仕事で、ここでは割らない。
 *
 * <p><b>勝敗はチケットで決まる。</b> 陣営ごとに同じ枚数から始め、機体か人を1つ失うごとに1枚減る。
 * 0 になった陣営が負け、時間切れなら残りの多い方が勝つ。拠点（{@link MatchPoint}）を1つでも置けば、
 * 握られている側は撃たれなくても減っていく——それがこの MOD の「拠点制圧」で、種目を選ぶ設定は無い。
 *
 * <p>ここにあるのは帳簿だけだ。機体を湧かせる・チケットを減らす・試合を終わらせるといった行いは
 * {@link Deathmatch} が持つ。
 */
public class MatchState extends SavedData {
    /** ワールドの data フォルダに置かれるファイル名。 */
    public static final String FILE = "ashvehicles_deathmatch";

    /** 撃破されてから次に出撃できるまでの既定（tick）。10秒。 */
    public static final int DEFAULT_RESPAWN = 200;

    /** 各陣営に配るチケットの既定。 */
    public static final int DEFAULT_TICKETS = 100;

    /**
     * 拠点差1つにつきチケット1枚が減る間隔の既定（tick）。5秒。
     *
     * <p>2026-09-12 に10秒から半分にした。20分の試合で100枚だと、1拠点差の流血が毎分6枚では時間切れ
     * の方が先に来る——押している側が勝ちを<em>詰める</em>ための仕掛けが、時計に負けていた。
     */
    public static final int DEFAULT_BLEED = 100;

    /** 出撃ポイントの初期配布。 */
    public static final int DEFAULT_POINTS = 500;

    /** 1撃破で入る出撃ポイント。 */
    public static final int DEFAULT_KILL_AWARD = 150;

    /** 拠点1つを制圧したときに、中にいた者へ入る出撃ポイント。 */
    public static final int DEFAULT_CAPTURE_AWARD = 200;

    /** 機体1つを失うごとに減るチケットの既定。 */
    public static final int DEFAULT_LOSS = 1;

    /** 試合の進み方。 */
    public enum Phase {
        /** 陣営を組み、旗を立て、人を振り分けている最中。出撃はできない。 */
        SETUP,
        /** 試合中。出撃・得点・時間が動く唯一の相。 */
        RUNNING,
        /** 決着済み。結果が残っていて、次の {@code start} か {@code reset} まで動かない。 */
        ENDED
    }

    private final Map<String, MatchTeam> teams = new LinkedHashMap<>();

    /** 誰がどの陣営か。陣営側の名簿と同じ事実を逆から引くためだけの索引。 */
    private final Map<UUID, String> membership = new LinkedHashMap<>();

    /** 撃破された者が次に出撃できるゲーム時刻。載っていない者はいつでも出られる。 */
    private final Map<UUID, Long> readyAt = new LinkedHashMap<>();

    /**
     * 誰が今どの機体で出ているか。
     *
     * <p><b>出撃のたびに前の1機を消すために要る。</b> これが無いと、旗の前に立って盤を10回開いた者が
     * 戦場に10機を置き去りにできる。置き去りの機体は敵の的でも味方の壁でもあり、どちらも試合を壊す。
     */
    private final Map<UUID, UUID> machines = new LinkedHashMap<>();

    private Phase phase = Phase.SETUP;

    /** 拠点。1つも無ければ撃ち合うだけの試合になる。鍵は旗竿の座標。 */
    private final Map<BlockPos, MatchPoint> pointsHeld = new LinkedHashMap<>();

    /** 試合開始時に各陣営へ配るチケット。計器のバーの満タンでもある。 */
    private int startTickets = DEFAULT_TICKETS;

    /** 拠点差1つにつきチケット1枚が減る間隔（tick）。 */
    private int bleedInterval = DEFAULT_BLEED;

    /** 機体か人を1つ失うごとに減るチケット。 */
    private int lossCost = DEFAULT_LOSS;

    /**
     * 出撃ポイントの財布。試合に参加している者ごとに1つ。
     *
     * <p><b>陣営ではなく個人が持つ。</b> 稼いだ者がいい機体を出せる仕組みであり、共有財布にすると
     * 「誰かが稼いだポイントで誰かが墜ちる」になる。
     */
    private final Map<UUID, Integer> points = new LinkedHashMap<>();

    private int startPoints = DEFAULT_POINTS;
    private int killAward = DEFAULT_KILL_AWARD;
    private int captureAward = DEFAULT_CAPTURE_AWARD;

    /** 残り時間（tick）。0 以下なら時間では終わらない。 */
    private int ticksLeft;

    /** 制限時間の長さ。{@code start} のたびに {@link #ticksLeft} をここへ戻す。 */
    private int duration;

    private int respawnTicks = DEFAULT_RESPAWN;

    /** 同じ陣営を撃てるか。既定は撃てない。 */
    private boolean friendlyFire;

    /**
     * 試合中に爆発が地形を壊すか。既定は壊さない。
     *
     * <p>会場は運営が作った物で、1試合で穴だらけになってよい理由が無い。滑走路に開いた穴は次の出撃を
     * 潰すし、拠点の足場が消えれば制圧そのものが成り立たなくなる。壊させたい運営は
     * {@code /tdm set terrain true}。
     */
    private boolean terrainDamage;

    /**
     * AI が出てよい範囲（ブロック）。旗と拠点の重心から測る。0 以下で無制限。
     *
     * <p><b>これは難易度ではなく負荷の設定だ。</b> 敵を追って会場を離れた AI は、誰も見ていない土地で
     * チャンクを開き続ける——20両ぶんとなれば試合ではなくワールド生成そのものになる。
     * {@code /tdm set arena}。
     */
    private double arenaRadius = Bots.DEFAULT_ARENA;

    /**
     * AI に持たせてよい車両。空なら「撃てる地上車両の全部」。
     *
     * <p>運営が機種を絞りたくなったときの一覧で、絞らないのが既定。{@code /tdm bot pool}。
     */
    private final List<ResourceLocation> botPool = new ArrayList<>();

    /**
     * 試合の行われている世界。
     *
     * <p><b>旗そのものは次元を持たない</b>——制圧の判定は「その者の世界にその旗竿が建っているか」で閉じて
     * いるし、それは人が必ずどこかの世界に立っているから成り立つ。だが<b>人のいない側の陣地を開けておく
     * には、どの世界かを誰かが知っていなければならない</b>。1人で試合を回すと、敵陣は誰の視界にも入らず
     * ——つまりロードされず——AI の出撃がそこで地面を確かめられずに毎回失敗していた。
     *
     * <p>覚えるのは旗を立てた者の世界（{@code /tdm spawn}・{@code /tdm point add}・{@code /tdm start}）。
     * 1つの試合が2つの世界にまたがることは無い。
     */
    @Nullable
    private ResourceKey<Level> world;

    /** 決着した陣営。引き分けなら null。 */
    @Nullable
    private String winner;

    public MatchState() {
    }

    /** この鯖の帳簿。無ければ作る。 */
    public static MatchState of(MinecraftServer server) {
        ServerLevel overworld = server.overworld();

        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(MatchState::new, MatchState::load, null), FILE);
    }

    // ------------------------------------------------------------------
    // 陣営
    // ------------------------------------------------------------------

    public Map<String, MatchTeam> teams() {
        return this.teams;
    }

    @Nullable
    public MatchTeam team(String id) {
        return this.teams.get(id);
    }

    public void addTeam(MatchTeam team) {
        // 試合中に足された陣営には、その場でチケットを配る。0枚のまま入れると、次の流血でその陣営が
        // 「尽きた」と読まれて試合が終わる。
        if (this.phase == Phase.RUNNING && team.tickets() <= 0) {
            team.setTickets(this.startTickets);
        }

        this.teams.put(team.id(), team);
        this.setDirty();
    }

    /** 陣営を1つ消す。所属していた者は無所属に戻る。旗のブロックは残るが、誰の物でもなくなる。 */
    public void removeTeam(String id) {
        MatchTeam team = this.teams.remove(id);

        if (team == null) {
            return;
        }

        this.membership.entrySet().removeIf(entry -> entry.getValue().equals(id));
        this.setDirty();
    }

    /** その者の陣営。無所属なら null。 */
    @Nullable
    public MatchTeam teamOf(UUID player) {
        String id = this.membership.get(player);

        return id == null ? null : this.teams.get(id);
    }

    /** その座標に旗を持っている陣営。どこの旗でもなければ null。 */
    @Nullable
    public MatchTeam teamAt(BlockPos pos) {
        for (MatchTeam team : this.teams.values()) {
            if (team.spawns().contains(pos)) {
                return team;
            }
        }

        return null;
    }

    public void join(UUID player, MatchTeam team) {
        this.leave(player);
        team.members().add(player);
        this.membership.put(player, team.id());
        this.setDirty();
    }

    public void leave(UUID player) {
        String id = this.membership.remove(player);

        if (id != null) {
            MatchTeam team = this.teams.get(id);

            if (team != null) {
                team.members().remove(player);
            }
        }

        this.readyAt.remove(player);
        this.setDirty();
    }

    /**
     * 人数の少ない陣営。同数なら先に作られた方。自動振り分けの答えであり、これが無いと運営は毎回
     * 人数を数えることになる。
     */
    @Nullable
    public MatchTeam smallestTeam() {
        MatchTeam smallest = null;

        for (MatchTeam team : this.teams.values()) {
            if (smallest == null || team.members().size() < smallest.members().size()) {
                smallest = team;
            }
        }

        return smallest;
    }

    // ------------------------------------------------------------------
    // 試合の進み
    // ------------------------------------------------------------------

    public Phase phase() {
        return this.phase;
    }

    public boolean isRunning() {
        return this.phase == Phase.RUNNING;
    }

    public int startTickets() {
        return this.startTickets;
    }

    public void setStartTickets(int tickets) {
        this.startTickets = Math.max(tickets, 1);
        this.setDirty();
    }

    public int bleedInterval() {
        return this.bleedInterval;
    }

    public int lossCost() {
        return this.lossCost;
    }

    public void setLossCost(int tickets) {
        this.lossCost = Math.max(tickets, 0);
        this.setDirty();
    }

    // ------------------------------------------------------------------
    // 出撃ポイント
    // ------------------------------------------------------------------

    public int startPoints() {
        return this.startPoints;
    }

    public void setStartPoints(int points) {
        this.startPoints = Math.max(points, 0);
        this.setDirty();
    }

    public int killAward() {
        return this.killAward;
    }

    public void setKillAward(int points) {
        this.killAward = Math.max(points, 0);
        this.setDirty();
    }

    public int captureAward() {
        return this.captureAward;
    }

    public void setCaptureAward(int points) {
        this.captureAward = Math.max(points, 0);
        this.setDirty();
    }

    /** その者の財布。まだ配られていなければ0。 */
    public int pointsOf(UUID player) {
        return this.points.getOrDefault(player, 0);
    }

    public void setPoints(UUID player, int amount) {
        this.points.put(player, Math.max(amount, 0));
        this.setDirty();
    }

    public void award(UUID player, int amount) {
        if (amount != 0) {
            this.setPoints(player, this.pointsOf(player) + amount);
        }
    }

    /**
     * 払えるなら払う。
     *
     * @return 足りていて引けたなら true
     */
    public boolean spend(UUID player, int amount) {
        if (this.pointsOf(player) < amount) {
            return false;
        }

        this.setPoints(player, this.pointsOf(player) - amount);

        return true;
    }

    public void setBleedInterval(int ticks) {
        this.bleedInterval = Math.max(ticks, 20);
        this.setDirty();
    }

    // ------------------------------------------------------------------
    // 拠点
    // ------------------------------------------------------------------

    public Map<BlockPos, MatchPoint> points() {
        return this.pointsHeld;
    }

    @Nullable
    public MatchPoint pointAt(BlockPos pos) {
        return this.pointsHeld.get(pos);
    }

    public void addPoint(MatchPoint point) {
        this.pointsHeld.put(point.pos(), point);
        this.setDirty();
    }

    public void removePoint(BlockPos pos) {
        if (this.pointsHeld.remove(pos) != null) {
            this.setDirty();
        }
    }

    /** その陣営が今握っている拠点の数。 */
    public int pointsHeldBy(String team) {
        int held = 0;

        for (MatchPoint point : this.pointsHeld.values()) {
            if (team.equals(point.owner())) {
                held++;
            }
        }

        return held;
    }

    /**
     * そこから出撃してよい陣営。出撃地点ブロックなら登録した陣営、拠点なら今の持ち主。どちらでも
     * なければ null。
     *
     * <p><b>拠点が前線の出撃地点になる。</b> 押し上げた側がそこから出られないなら、拠点を取る理由は
     * 数字が減ることだけになる。
     */
    @Nullable
    public MatchTeam deployOwner(BlockPos pos) {
        MatchTeam spawn = this.teamAt(pos);

        if (spawn != null) {
            return spawn;
        }

        MatchPoint point = this.pointsHeld.get(pos);

        return point == null || point.owner() == null ? null : this.teams.get(point.owner());
    }

    public int ticksLeft() {
        return this.ticksLeft;
    }

    public void setTicksLeft(int ticks) {
        this.ticksLeft = ticks;
        this.setDirty();
    }

    public int duration() {
        return this.duration;
    }

    public void setDuration(int ticks) {
        this.duration = Math.max(ticks, 0);
        this.setDirty();
    }

    public int respawnTicks() {
        return this.respawnTicks;
    }

    public void setRespawnTicks(int ticks) {
        this.respawnTicks = Math.max(ticks, 0);
        this.setDirty();
    }

    public boolean friendlyFire() {
        return this.friendlyFire;
    }

    public void setFriendlyFire(boolean allowed) {
        this.friendlyFire = allowed;
        this.setDirty();
    }

    /** 試合の世界。まだ旗が1つも立っていなければ null。 */
    @Nullable
    public ResourceKey<Level> world() {
        return this.world;
    }

    public void setWorld(ResourceKey<Level> world) {
        this.world = world;
        this.setDirty();
    }

    /** 試合の世界そのもの。覚えていないか、もう存在しなければ null。 */
    @Nullable
    public ServerLevel level(MinecraftServer server) {
        return this.world == null ? null : server.getLevel(this.world);
    }

    /** AI が出てよい範囲の半径（ブロック）。0 以下で無制限。 */
    public double arenaRadius() {
        return this.arenaRadius;
    }

    public void setArenaRadius(double radius) {
        this.arenaRadius = Math.max(radius, 0.0);
        this.setDirty();
    }

    /** AI に持たせてよい車両の一覧。空なら制限なし。 */
    public List<ResourceLocation> botPool() {
        return this.botPool;
    }

    public boolean terrainDamage() {
        return this.terrainDamage;
    }

    public void setTerrainDamage(boolean allowed) {
        this.terrainDamage = allowed;
        this.setDirty();
    }

    @Nullable
    public String winner() {
        return this.winner;
    }

    public void setPhase(Phase phase) {
        this.phase = phase;
        this.setDirty();
    }

    public void setWinner(@Nullable String team) {
        this.winner = team;
        this.setDirty();
    }

    /**
     * チケットを配り直し、拠点を初期配置へ戻し、記録と待ち時間を捨てる。試合の開始と、運営が仕切り
     * 直したときに通る。
     *
     * <p><b>拠点が戻る先は中立ではなく初期配置</b>（{@link MatchPoint#home}）。運営が試合前に「ここは
     * 赤、ここは青」と据えたなら次の試合もそこから始まり、据えていない拠点は中立に戻る。前の試合で
     * 押し込んで取った旗が、次の試合の開始時に相手陣の奥で光っていてはいけない。
     */
    public void newRound() {
        for (MatchTeam team : this.teams.values()) {
            team.setTickets(this.startTickets);
            team.setKills(0);
        }

        for (MatchPoint point : this.pointsHeld.values()) {
            point.reset();
        }

        // 財布は試合ごとに配り直す。前の試合で貯め込んだ者が、開始と同時に最上位の機体で出てくるのは
        // 試合の始まりではない。
        for (UUID member : this.membership.keySet()) {
            this.setPoints(member, this.startPoints);
        }

        this.readyAt.clear();
        this.machines.clear();
        this.winner = null;
        this.setDirty();
    }

    /** 陣営も旗も含めて全部捨てる。 */
    public void reset() {
        this.teams.clear();
        this.pointsHeld.clear();
        this.points.clear();
        this.membership.clear();
        this.readyAt.clear();
        this.machines.clear();
        this.phase = Phase.SETUP;
        this.ticksLeft = 0;
        this.winner = null;
        // 世界も忘れる。旗を1つも持たない帳簿が「試合はあちらの世界だ」と言い続けると、次の会場を
        // 別の世界に作った運営の陣地が開かない。
        this.world = null;
        this.setDirty();
    }

    // ------------------------------------------------------------------
    // 出撃待ち
    // ------------------------------------------------------------------

    /** その者が次に出撃できるゲーム時刻。制限が無ければ 0。 */
    public long readyAt(UUID player) {
        return this.readyAt.getOrDefault(player, 0L);
    }

    public void setReadyAt(UUID player, long time) {
        this.readyAt.put(player, time);
        this.setDirty();
    }

    public void clearReady(UUID player) {
        this.readyAt.remove(player);
        this.setDirty();
    }

    /** その者が今出ている機体。まだ出ていなければ null。 */
    @Nullable
    public UUID machineOf(UUID player) {
        return this.machines.get(player);
    }

    public void setMachine(UUID player, UUID vehicle) {
        this.machines.put(player, vehicle);
        this.setDirty();
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag teams = new ListTag();

        for (MatchTeam team : this.teams.values()) {
            teams.add(team.save());
        }

        tag.put("Teams", teams);
        ListTag points = new ListTag();

        for (MatchPoint point : this.pointsHeld.values()) {
            points.add(point.save());
        }

        tag.put("Points", points);
        tag.putInt("StartPoints", this.startPoints);
        tag.putInt("KillAward", this.killAward);
        tag.putInt("CaptureAward", this.captureAward);
        tag.putInt("LossCost", this.lossCost);

        ListTag purses = new ListTag();

        for (Map.Entry<UUID, Integer> entry : this.points.entrySet()) {
            CompoundTag one = new CompoundTag();

            one.putUUID("Id", entry.getKey());
            one.putInt("Points", entry.getValue());
            purses.add(one);
        }

        tag.put("Purses", purses);
        tag.putString("Phase", this.phase.name());
        tag.putInt("StartTickets", this.startTickets);
        tag.putInt("Bleed", this.bleedInterval);
        tag.putInt("TicksLeft", this.ticksLeft);
        tag.putInt("Duration", this.duration);
        tag.putInt("Respawn", this.respawnTicks);
        tag.putBoolean("FriendlyFire", this.friendlyFire);
        tag.putBoolean("TerrainDamage", this.terrainDamage);
        tag.putDouble("Arena", this.arenaRadius);

        if (this.world != null) {
            tag.putString("World", this.world.location().toString());
        }


        ListTag pool = new ListTag();

        for (ResourceLocation vehicle : this.botPool) {
            pool.add(StringTag.valueOf(vehicle.toString()));
        }

        tag.put("BotPool", pool);

        if (this.winner != null) {
            tag.putString("Winner", this.winner);
        }

        ListTag waiting = new ListTag();

        for (Map.Entry<UUID, Long> entry : this.readyAt.entrySet()) {
            CompoundTag one = new CompoundTag();

            one.putUUID("Id", entry.getKey());
            one.putLong("At", entry.getValue());
            waiting.add(one);
        }

        tag.put("Waiting", waiting);

        ListTag machines = new ListTag();

        for (Map.Entry<UUID, UUID> entry : this.machines.entrySet()) {
            CompoundTag one = new CompoundTag();

            one.putUUID("Id", entry.getKey());
            one.putUUID("Machine", entry.getValue());
            machines.add(one);
        }

        tag.put("Machines", machines);

        return tag;
    }

    public static MatchState load(CompoundTag tag, HolderLookup.Provider registries) {
        MatchState state = new MatchState();
        ListTag teams = tag.getList("Teams", Tag.TAG_COMPOUND);

        for (int at = 0; at < teams.size(); at++) {
            MatchTeam team = MatchTeam.load(teams.getCompound(at));

            state.teams.put(team.id(), team);

            for (UUID member : team.members()) {
                state.membership.put(member, team.id());
            }
        }

        try {
            state.phase = Phase.valueOf(tag.getString("Phase"));
        } catch (IllegalArgumentException ignored) {
            state.phase = Phase.SETUP;
        }

        ListTag points = tag.getList("Points", Tag.TAG_COMPOUND);

        for (int at = 0; at < points.size(); at++) {
            MatchPoint point = MatchPoint.load(points.getCompound(at));

            state.pointsHeld.put(point.pos(), point);
        }

        state.startPoints = tag.contains("StartPoints") ? tag.getInt("StartPoints") : DEFAULT_POINTS;
        state.killAward = tag.contains("KillAward") ? tag.getInt("KillAward") : DEFAULT_KILL_AWARD;
        state.captureAward = tag.contains("CaptureAward") ? tag.getInt("CaptureAward") : DEFAULT_CAPTURE_AWARD;
        state.lossCost = tag.contains("LossCost") ? tag.getInt("LossCost") : DEFAULT_LOSS;

        ListTag purses = tag.getList("Purses", Tag.TAG_COMPOUND);

        for (int at = 0; at < purses.size(); at++) {
            CompoundTag one = purses.getCompound(at);

            state.points.put(one.getUUID("Id"), one.getInt("Points"));
        }

        state.startTickets = tag.contains("StartTickets") ? tag.getInt("StartTickets") : DEFAULT_TICKETS;
        state.bleedInterval = tag.contains("Bleed") ? tag.getInt("Bleed") : DEFAULT_BLEED;
        state.ticksLeft = tag.getInt("TicksLeft");
        state.duration = tag.getInt("Duration");
        state.respawnTicks = tag.contains("Respawn") ? tag.getInt("Respawn") : DEFAULT_RESPAWN;
        state.friendlyFire = tag.getBoolean("FriendlyFire");
        state.terrainDamage = tag.getBoolean("TerrainDamage");
        state.arenaRadius = tag.contains("Arena") ? tag.getDouble("Arena") : Bots.DEFAULT_ARENA;

        ResourceLocation world = tag.contains("World") ? ResourceLocation.tryParse(tag.getString("World")) : null;

        state.world = world == null ? null : ResourceKey.create(Registries.DIMENSION, world);


        ListTag pool = tag.getList("BotPool", Tag.TAG_STRING);

        for (int at = 0; at < pool.size(); at++) {
            ResourceLocation vehicle = ResourceLocation.tryParse(pool.getString(at));

            if (vehicle != null) {
                state.botPool.add(vehicle);
            }
        }
        state.winner = tag.contains("Winner") ? tag.getString("Winner") : null;

        ListTag waiting = tag.getList("Waiting", Tag.TAG_COMPOUND);

        for (int at = 0; at < waiting.size(); at++) {
            CompoundTag one = waiting.getCompound(at);

            state.readyAt.put(one.getUUID("Id"), one.getLong("At"));
        }

        ListTag machines = tag.getList("Machines", Tag.TAG_COMPOUND);

        for (int at = 0; at < machines.size(); at++) {
            CompoundTag one = machines.getCompound(at);

            state.machines.put(one.getUUID("Id"), one.getUUID("Machine"));
        }

        return state;
    }

    /**
     * 残りチケットの多い順。<b>結果発表と {@code /tdm status} 専用。</b>
     *
     * <p>計器へ流用しないこと。あちらは左右に1つずつ並べるので、順が入れ替わると赤と青が入れ替わる。
     */
    public List<MatchTeam> ranked() {
        List<MatchTeam> ranked = new ArrayList<>(this.teams.values());

        ranked.sort((left, right) -> Integer.compare(right.tickets(), left.tickets()));

        return ranked;
    }

    /** チケットが尽きた陣営。まだ誰も尽きていなければ null。 */
    @Nullable
    public MatchTeam exhausted() {
        for (MatchTeam team : this.teams.values()) {
            if (team.isOut()) {
                return team;
            }
        }

        return null;
    }
}
