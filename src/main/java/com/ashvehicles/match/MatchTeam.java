package com.ashvehicles.match;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 陣営1つ。名前と色、旗（スポーン地点）、所属、そして残りチケット。
 *
 * <p>バニラの {@code scoreboard team} は使わない。あちらは「表示上の色分け」であって、試合が終わった
 * ときに片付く物ではないし、1つのワールドで同時に別の用途に使われている。ここで持つのは試合の持ち物
 * であり、{@link MatchState#reset} 1回で全部消える。
 *
 * <p>旗の位置を陣営が持ち、同じ座標のブロック（{@code TeamSpawnBlockEntity}）も自分の陣営を持つ。
 * 二重帳簿に見えるが役割が違う——陣営側の一覧は「どこへ湧かせるか」の答えで、ブロック側は触った者に
 * 色を見せ、壊されたときに自分の登録を取り下げるために持っている。
 */
public final class MatchTeam {
    private final String id;
    private String name;
    private ChatFormatting color;
    private final List<BlockPos> spawns = new ArrayList<>();
    private final Set<UUID> members = new LinkedHashSet<>();

    /**
     * 残りチケット。<b>これが尽きた陣営が負ける。</b>
     *
     * <p>得点ではなく残機だ。機体を1機失えば1枚減り、減らすのは撃った側ではなく失った側——墜落も
     * 自爆も味方撃ちも同じ1枚で、誰のせいかは勘定に関係しない。拠点を握られていれば、撃たれなくても
     * 減っていく（{@link Deathmatch#bleed}）。
     */
    private int tickets;

    /** 倒した数。勝敗には関わらない、読み上げと {@code /tdm status} のための記録。 */
    private int kills;

    /**
     * この陣営に立てる AI の数。
     *
     * <p><b>陣営ごとに持つ。</b> 人が3人いる側と1人しかいない側を同じ数で埋めては、人数を合わせたことに
     * ならない——運営が数字で釣り合いを取れるように、赤と青で別々に据えられる（{@code /tdm bot}）。
     * 生きた AI の数はここには書かない。あれは世界の側の事実で、{@link Bots#count} が数える。
     */
    private int bots = Bots.DEFAULT_COUNT;

    /**
     * この陣営の AI の版。空なら既定の版（{@code ai/learning/AiVersions#defaultId}）。
     *
     * <p><b>陣営ごとに持つ。</b> 版を比べるとは、赤と青に別の版を乗せて同じ試合を回すことだ
     * （{@code ai/learning/SelfPlay}）。版は車両が出た時に決まるので、試合の途中で変えれば次に出る車両から効く。
     */
    private String aiVersion = "";

    public MatchTeam(String id, String name, ChatFormatting color) {
        this.id = id;
        this.name = name;
        this.color = color;
    }

    /** コマンドが指す名前。ワールドの中で一意。 */
    public String id() {
        return this.id;
    }

    /** 人に見せる名前。既定では ID そのもの。 */
    public String name() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ChatFormatting color() {
        return this.color;
    }

    public void setColor(ChatFormatting color) {
        this.color = color;
    }

    /** 陣営色の付いた表示名。読み上げも記録もこれ1つで揃える。 */
    public Component display() {
        return Component.literal(this.name).withStyle(this.color);
    }

    public List<BlockPos> spawns() {
        return this.spawns;
    }

    public void addSpawn(BlockPos pos) {
        if (!this.spawns.contains(pos)) {
            this.spawns.add(pos.immutable());
        }
    }

    public boolean removeSpawn(BlockPos pos) {
        return this.spawns.remove(pos);
    }

    public Set<UUID> members() {
        return this.members;
    }

    public int tickets() {
        return this.tickets;
    }

    public void setTickets(int tickets) {
        this.tickets = Math.max(tickets, 0);
    }

    /** チケットを減らす。0 を下回らない——負けは1度きりだ。 */
    public void spendTickets(int count) {
        this.tickets = Math.max(this.tickets - count, 0);
    }

    public boolean isOut() {
        return this.tickets <= 0;
    }

    public int kills() {
        return this.kills;
    }

    /** この陣営を埋める AI の数。 */
    public int bots() {
        return this.bots;
    }

    public void setBots(int bots) {
        this.bots = Mth.clamp(bots, 0, Bots.MOST);
    }

    /** この陣営の AI の版の名前。空なら既定。 */
    public String aiVersion() {
        return this.aiVersion;
    }

    public void setAiVersion(String version) {
        this.aiVersion = version == null ? "" : version;
    }

    public void addKill() {
        this.kills++;
    }

    public void setKills(int kills) {
        this.kills = Math.max(kills, 0);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();

        tag.putString("Id", this.id);
        tag.putString("Name", this.name);
        tag.putString("Color", this.color.getName());
        tag.putInt("Tickets", this.tickets);
        tag.putInt("Kills", this.kills);
        tag.putInt("Bots", this.bots);
        tag.putString("AiVersion", this.aiVersion);

        // 座標は long 1つに畳む。ブロック位置の書き方は版によって形が変わってきたが、この畳み方は
        // ブロック位置そのものの定義であり、変わりようがない。
        long[] spawns = new long[this.spawns.size()];

        for (int at = 0; at < spawns.length; at++) {
            spawns[at] = this.spawns.get(at).asLong();
        }

        tag.putLongArray("Spawns", spawns);

        ListTag members = new ListTag();

        for (UUID member : this.members) {
            CompoundTag entry = new CompoundTag();

            entry.putUUID("Id", member);
            members.add(entry);
        }

        tag.put("Members", members);

        return tag;
    }

    public static MatchTeam load(CompoundTag tag) {
        ChatFormatting color = ChatFormatting.getByName(tag.getString("Color"));
        MatchTeam team = new MatchTeam(tag.getString("Id"), tag.getString("Name"),
                color == null ? ChatFormatting.WHITE : color);

        team.tickets = tag.getInt("Tickets");
        team.kills = tag.getInt("Kills");
        team.bots = tag.contains("Bots") ? tag.getInt("Bots") : Bots.DEFAULT_COUNT;
        team.aiVersion = tag.getString("AiVersion");

        for (long packed : tag.getLongArray("Spawns")) {
            team.spawns.add(BlockPos.of(packed));
        }

        ListTag members = tag.getList("Members", Tag.TAG_COMPOUND);

        for (int at = 0; at < members.size(); at++) {
            team.members.add(members.getCompound(at).getUUID("Id"));
        }

        return team;
    }
}
