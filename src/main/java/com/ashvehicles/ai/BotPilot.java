package com.ashvehicles.ai;

import javax.annotation.Nullable;

import com.ashvehicles.ai.debug.PilotSnapshot;
import com.ashvehicles.ai.decision.TacticalAction;
import com.ashvehicles.ai.learning.AiVersion;
import com.ashvehicles.ai.learning.AiVersions;
import com.ashvehicles.ai.log.PilotRecorder;
import com.ashvehicles.ai.role.TacticalProfile;
import com.ashvehicles.ai.role.VehicleRole;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.match.Deathmatch;
import com.google.gson.JsonObject;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;

/**
 * 人の代わりに1両を動かす者。サーバーだけに存在し、保存されない。
 *
 * <p><b>これはエンティティではない。</b> 席に座る人形でも、透明なモブでもなく、車両が持つ1個のフィールド
 * だ。そうしたのは、この MOD が既に「乗員のいない車両をサーバーが走らせる」形を持っていたから——
 * {@code isControlledByLocalInstance} は運転手のいない車両をサーバー側へ落とし、そこから先の走行・砲・
 * シーカー・燃料は全部サーバーの仕事になっている（[[a-drone-is-an-aircraft-with-no-seats]] が無人機で
 * 通った道と同じ）。人形を置いても、その人形が押すのは結局 {@link GroundVehicleEntity#setInput} 1つで
 * あり、代わりに attribute の登録・描画・落下・窒息・他 MOD の敵対判定を全部背負うことになる。
 *
 * <p><b>AI の出口は運転手が送ってくるはずだった入力1つと、狙い点1つ。</b> 飛行モデルも駆動系も砲塔の
 * 制動も当たり判定も、人が運転している時とまったく同じ経路を通る。AI のために変えた挙動は1つも無い。
 *
 * <p><b>中は系に分かれている</b>——観測・戦場の解析・拠点・意思決定・戦術・経路・射撃・車両への出口
 * （{@link GroundPilot} が組み立てる）。この基底が外に見せるのは、車両と試合と可視化が訊く答えだけ。
 *
 * <p><b>陣営は機体が名乗る。</b> AI 自身は名簿を持たない。撃ってよい相手・味方撃ちの門・撃破の勘定は
 * 全部、車両の {@code getPersistentData} に書かれた陣営タグ（{@link Deathmatch#TEAM_KEY}）から出る——
 * 人が出撃させた機体とまったく同じ事実だ（[[team-deathmatch-shape]]）。
 */
public abstract class BotPilot {
    protected final VehicleEntityBase vehicle;

    /** この車両の陣営 ID。空なら試合の外にいる AI で、誰も撃たない。 */
    private final String team;

    /** この AI が動いた tick 数。 */
    protected int age;

    protected BotPilot(VehicleEntityBase vehicle, String team) {
        this.vehicle = vehicle;
        this.team = team;
    }

    /**
     * その車両を動かせる AI。動かせない物には null。
     *
     * <p>地上車両は {@link GroundPilot}、座席を持つ航空機は {@link AirPilot}。<b>艦と牽引砲と無人機は外す。</b> 艦は
     * 水面を進む物で、この AI が持っている「地面の上を目標へ向かって走る」という考え方がそのまま外れる。牽引砲
     * （{@code hull.crewed}）は乗る物ですらなく、車外に立った者がハンドルを回す
     * （[[crew-served-guns-are-worked-from-outside]]）。無人機は端末から飛ばす物で、出撃の対象でもない
     * （[[a-drone-is-an-aircraft-with-no-seats]]）。
     *
     * <p>どの版の AI にするかは陣営が決める（{@link AiVersions#forTeam}）。版は車両が出た時に決まり、倒されるまで
     * 変わらない——試合の途中で陣営の版を変えても、次に出る車両から効く。航空機の AI は版の方針を読まない
     * （判断は {@link AirPilot} の中で閉じている）が、記録のために版の名前は持つ。
     */
    @Nullable
    public static BotPilot of(VehicleEntityBase vehicle) {
        String team = vehicle.getPersistentData().getString(Deathmatch.TEAM_KEY);
        MinecraftServer server = vehicle.getServer();
        AiVersion version = server == null ? AiVersions.builtIn() : AiVersions.forTeam(server, team);

        if (vehicle instanceof AircraftEntity aircraft) {
            return aircraft.isUnmanned() ? null : new AirPilot(aircraft, team, version);
        }

        if (!(vehicle instanceof GroundVehicleEntity ground)
                || ground.getStats().isShip() || ground.getStats().hull().crewed()) {
            return null;
        }

        return new GroundPilot(ground, team, version);
    }

    /** この AI の陣営 ID。 */
    public String team() {
        return this.team;
    }

    /** 動かしている車両。 */
    public VehicleEntityBase vehicle() {
        return this.vehicle;
    }

    /**
     * 1 tick 分。車両の tick から、走る前・撃つ前に呼ばれる。
     *
     * <p>全損した車両の AI は何もしない。残骸は走らないし、残骸の砲は撃たない——人の運転席が空になった
     * 時とまったく同じ扱いで、ここで手を離すのは「撃たれた戦車が惰性で走り続けない」ためでもある。
     */
    public final void tick() {
        this.age++;

        // 足元を開けておく。撃破された残骸も数秒は開けておく——燃えている戦車が「誰も見ていないから」
        // という理由で空中の一点に凍り付いては、片付けの tick も回らない。
        if (this.age % BotChunkLoader.EVERY == 0) {
            BotChunkLoader.hold(this.vehicle);
        }

        if (this.vehicle.isWrecked()) {
            this.idle();

            return;
        }

        this.think();
    }

    /** 考えて、操作を1 tick 分置く。 */
    protected abstract void think();

    /** 全損した車両で毎 tick。手を離す。 */
    protected abstract void idle();

    /** 今狙っている相手。 */
    @Nullable
    public abstract Entity target();

    /** 今の行動。まだ一度も決めていなければ null。 */
    @Nullable
    public abstract TacticalAction action();

    public abstract VehicleRole role();

    public abstract TacticalProfile profile();

    /** この AI の版の ID。 */
    public abstract String version();

    /** 味方の道案内を任せられるか（{@code navigation/Navigator#leads}）。 */
    public abstract boolean leads();

    /** 最近撃たれたか。 */
    public abstract boolean underFire(long now);

    /** 最近撃ってきた相手の ID。いなければ -1。 */
    public abstract int attackerId(long now);

    /** 撃たれた。被弾の出口（{@code log/BattleEvents}）から呼ばれる。 */
    public abstract void onHurt(@Nullable Entity attacker, float amount, long now);

    /** 自分の弾が効いた。 */
    public abstract void onDamageDealt(Entity victim, float amount, long now);

    /**
     * 誘導弾がこの車両を追い始めた（{@code RocketEntity.setTarget}）。
     *
     * <p>人の乗った機体はこれを警戒受信機（{@code sensor/Sensors}）で知るが、あちらは乗員のいない機体では走査
     * しない。AI にはここから直接知らせる。
     */
    public void onMissileInbound(Entity missile) {
    }

    /**
     * 飛んでいる物の今の様子を、記録の1行（{@code log/FlightLog} → {@code flights.jsonl}）へ書き足す。段階・避けているか・
     * 使っている兵装など、判断の中にあって外からは見えない物。既定では何も書かない。
     *
     * <p>書くのは航空機の操縦役（{@link AirPilot}）。地上の車両は判断の行（{@code decisions.jsonl}）に位置と走り方が載る。
     */
    public void describeFlight(JsonObject into) {
    }

    /** この AI の戦闘記録の係。 */
    public abstract PilotRecorder recorder();

    /** 今の姿。可視化と {@code /tdm ai inspect} のため。 */
    public abstract PilotSnapshot snapshot();
}
