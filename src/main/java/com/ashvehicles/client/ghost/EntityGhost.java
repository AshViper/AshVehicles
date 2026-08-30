package com.ashvehicles.client.ghost;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * ゴーストシステムから見た1エンティティ。直近2つのスナップショットと、このフレームで描くか・どう描くかを決める
 * 帳簿。
 *
 * <p>これはクライアント側の描画記録であり、それ以上の物ではない。AI も物理もアニメーションコントローラも持たず、
 * tick もしない。これを変更するのはゲームスレッド上でスナップショットを差し替えるマネージャだけで、読むのは
 * 描画パスだけだ。
 *
 * <p>スナップショット参照は volatile にしてある。レンダースレッドが常に半端ではない完全なスナップショットを見る
 * ためだ——描画とtickが今日は同じスレッドを共有していても明日はそうでないかもしれないゲームでの、安い保険。
 */
public final class EntityGhost {
    private final UUID uuid;
    private final GhostAdapter<?> adapter;

    private volatile GhostSnapshot current;
    private volatile GhostSnapshot previous;

    /** クライアントがまだ保持している実体。レベルを離れた瞬間にクリアする。 */
    @Nullable
    private Entity entity;

    /** このゴーストを実体から最後に更新したゲームtick。 */
    private long lastSeenTick;
    /** 実体が消えたゲームtick。まだ在るなら -1。 */
    private long orphanedAt = -1L;

    // 遮蔽判定。数tickごとにゲームスレッドで開始し、ワーカースレッドで完了することもあり（GhostOcclusion
    // 参照）、描画パスが読む。volatile なのはそのため。
    private volatile boolean occluded;
    private volatile boolean occlusionPending;
    /** 最初の判定が即座に期限を迎え、かつ {@code now - this} が溢れない程度に古い値。 */
    private long occlusionCheckedAt = Long.MIN_VALUE / 2;

    // 前フレームの判定結果。デバッグオーバーレイ用に保持する。
    private GhostLOD lod = GhostLOD.HIDDEN;
    private double distanceSq = Double.MAX_VALUE;
    private GhostVerdict verdict = GhostVerdict.HIDDEN;
    private int lastLight;
    private boolean lastInWorld;

    EntityGhost(UUID uuid, GhostAdapter<?> adapter, Entity entity, GhostSnapshot first) {
        this.uuid = uuid;
        this.adapter = adapter;
        this.entity = entity;
        this.current = first;
        this.previous = first;
        this.lastSeenTick = first.gameTime();
    }

    // ------------------------------------------------------------------
    // 同一性
    // ------------------------------------------------------------------

    public UUID uuid() {
        return this.uuid;
    }

    public GhostAdapter<?> adapter() {
        return this.adapter;
    }

    // ------------------------------------------------------------------
    // スナップショット
    // ------------------------------------------------------------------

    public GhostSnapshot current() {
        return this.current;
    }

    public GhostSnapshot previous() {
        return this.previous;
    }

    /** スナップショットを差し替える。現在が前回になる。ゲームスレッド限定。 */
    void update(GhostSnapshot next) {
        this.previous = this.current;
        this.current = next;
        this.lastSeenTick = next.gameTime();
    }

    /** このフレームでゴーストを描く位置。直近2tickの間を補間する。 */
    public Vec3 position(float partialTick) {
        GhostSnapshot now = this.current;
        GhostSnapshot then = this.previous;

        if (then == now) {
            return now.position();
        }

        return new Vec3(
                Mth.lerp(partialTick, then.position().x, now.position().x),
                Mth.lerp(partialTick, then.position().y, now.position().y),
                Mth.lerp(partialTick, then.position().z, now.position().z));
    }

    // ------------------------------------------------------------------
    // 実体
    // ------------------------------------------------------------------

    /** このゴーストが代役を務めるエンティティ。クライアントが失った後は {@code null}。 */
    @Nullable
    public Entity entity() {
        return this.entity;
    }

    void attach(Entity entity) {
        this.entity = entity;
        this.orphanedAt = -1L;
    }

    void orphan(long now) {
        this.entity = null;
        this.orphanedAt = now;
    }

    public boolean isOrphaned() {
        return this.entity == null;
    }

    public long orphanedAt() {
        return this.orphanedAt;
    }

    public long lastSeenTick() {
        return this.lastSeenTick;
    }

    // ------------------------------------------------------------------
    // 遮蔽
    // ------------------------------------------------------------------

    public boolean isOccluded() {
        return this.occluded;
    }

    /** 判定を開始してまだ答えが出ていないか。 */
    boolean isOcclusionPending() {
        return this.occlusionPending;
    }

    /** このtickで判定を開始したと記録する。ゲームスレッド。 */
    void beginOcclusion(long now) {
        this.occlusionCheckedAt = now;
        this.occlusionPending = true;
    }

    /** 答えを記録する。ゲームスレッドまたはワーカースレッド。 */
    void finishOcclusion(boolean occluded) {
        this.occluded = occluded;
        this.occlusionPending = false;
    }

    long occlusionCheckedAt() {
        return this.occlusionCheckedAt;
    }

    // ------------------------------------------------------------------
    // 前フレームの判定
    // ------------------------------------------------------------------

    public GhostLOD lod() {
        return this.lod;
    }

    public double distanceSq() {
        return this.distanceSq;
    }

    public boolean wasDrawnLastFrame() {
        return this.verdict == GhostVerdict.DRAWN;
    }

    /** 前フレームで描かれた／描かれなかった理由。 */
    public GhostVerdict verdict() {
        return this.verdict;
    }

    void record(GhostLOD lod, double distanceSq, GhostVerdict verdict) {
        this.lod = lod;
        this.distanceSq = distanceSq;
        this.verdict = verdict;

        if (verdict != GhostVerdict.DRAWN) {
            // 何も描かれなかったので、最後の描画の光量は別フレームの物だ。そのまま報告すると事実のように
            // 読まれてしまい、何も報告しないより悪い。
            this.lastLight = 0;
            this.lastInWorld = false;
        }
    }

    /** 最後の描画が使ったパック済み光量と、それが世界由来か無由来か。 */
    public int lastLight() {
        return this.lastLight;
    }

    public boolean wasInWorld() {
        return this.lastInWorld;
    }

    void recordLight(int light, boolean inWorld) {
        this.lastLight = light;
        this.lastInWorld = inWorld;
    }
}
