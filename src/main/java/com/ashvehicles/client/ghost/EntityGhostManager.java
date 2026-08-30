package com.ashvehicles.client.ghost;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.ghost.dh.DHIntegration;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

/**
 * ゴーストの保持者。クライアントが知る登録済みエンティティ1つにつき1つ持ち、実体が在る間は毎tick更新し、消えた
 * 後もしばらく保持する。
 *
 * <p>マネージャはゲームスレッドに居る。エンティティのことはレベルを検索してではなく参加・離脱イベントから知る
 * ——レベルはどのスレッドからも走査しない——し、仕事は毎tick 1回だ。各エンティティのスナップショットを撮り、
 * 各ゴーストの階層を決め、少額の遮蔽判定予算を消費する。描画パスは結果を読むだけで、追加も削除もしない。
 *
 * <p>ゴーストのキーは UUID。エンティティIDは再利用されるし、ゴーストは自分が受け取ったIDのエンティティより長生き
 * しうる。UUID は永続だ。
 *
 * <p>ここでは何も漏らさない。ゴーストは、エンティティが死んだとき、クライアントが受信をやめたとき（即座に。ただし
 * アダプタが保持を求めた場合は、そのアダプタの {@link GhostAdapter#orphanTicks()} 後）、そしてレベルが変わった
 * ときに全部、削除される。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class EntityGhostManager {
    /** ゲームスレッドが書いている間にレンダースレッドが読めるよう並行コレクションにしてある。 */
    private static final Map<UUID, EntityGhost> GHOSTS = new ConcurrentHashMap<>();
    /** 同じ物を {@link #ghosts()} が渡す読み取り専用ビューとして。毎フレームではなく1度だけラップする。 */
    private static final Collection<EntityGhost> VIEW = Collections.unmodifiableCollection(GHOSTS.values());
    /** このtickの作業リスト。毎回作らずtickをまたいで保持する。ゲームスレッド限定。 */
    private static final List<EntityGhost> ORDERED = new ArrayList<>();
    /** 近い順。描画予算も Distant Horizons の予算も近い物を優先する。 */
    private static final Comparator<EntityGhost> NEAREST_FIRST =
            Comparator.comparingDouble(EntityGhost::distanceSq);

    @Nullable
    private static ClientLevel level;
    private static int occlusionRaysThisTick;

    // 前tickの数値。デバッグオーバーレイ用。
    private static int countGhost;
    private static int countBillboard;
    private static int countOccluded;
    private static int countOrphaned;

    private EntityGhostManager() {
    }

    // ------------------------------------------------------------------
    // 描画パスが読む物
    // ------------------------------------------------------------------

    public static Collection<EntityGhost> ghosts() {
        return VIEW;
    }

    @Nullable
    public static EntityGhost ghostOf(Entity entity) {
        return GHOSTS.get(entity.getUUID());
    }

    public static int size() {
        return GHOSTS.size();
    }

    // ------------------------------------------------------------------
    // エンティティの参加と離脱
    // ------------------------------------------------------------------

    @SubscribeEvent
    static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ClientLevel joined)) {
            return;
        }

        Entity entity = event.getEntity();
        GhostAdapter<Entity> adapter = EntityGhostRegistry.adapterFor(entity);

        if (adapter == null) {
            return;
        }

        // レベル変更はtickだけでなくここでも検出する。そうせざるを得ない。エンティティはパケット処理時、つまり
        // tickの合間に届くので、新しいワールドの最初の機体は「ワールドが変わった」と気付くtickより先に参加する
        // ——そしてそのtickが、古いワールドの物と一緒にそれらを消してしまう。そのゴーストが作り直されることは
        // 二度と無い。マネージャはこのイベントからしかエンティティを知らず、レベルを走査しないからだ。代役先の
        // 機体は引き継ぎ距離から先で誰にも描かれなくなる。
        levelChanged(joined);

        long now = joined.getGameTime();
        EntityGhost ghost = GHOSTS.get(entity.getUUID());

        if (ghost != null) {
            // 同じエンティティが戻ってきた。ゴーストの続きから再開する。
            ghost.attach(entity);
            ghost.update(adapter.snapshot(entity, ghost.current(), now));
            return;
        }

        GhostSnapshot first = adapter.snapshot(entity, null, now);
        GHOSTS.put(entity.getUUID(), new EntityGhost(entity.getUUID(), adapter, entity, first));
    }

    @SubscribeEvent
    static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }

        Entity entity = event.getEntity();
        EntityGhost ghost = GHOSTS.get(entity.getUUID());

        if (ghost != null && ghost.entity() == entity) {
            entityGone(ghost, entity, event.getLevel().getGameTime());
        }
    }

    /** クライアントがこのエンティティを失った。ゴーストをしばらく残すか今消すかを決める。 */
    @SuppressWarnings("unchecked")
    private static void entityGone(EntityGhost ghost, Entity entity, long now) {
        GhostAdapter<Entity> adapter = (GhostAdapter<Entity>) ghost.adapter();

        if (adapter.isDead(entity) || !adapter.keepAfterLeave(entity) || adapter.orphanTicks() <= 0) {
            remove(ghost);
        } else {
            ghost.orphan(now);
        }
    }

    private static void remove(EntityGhost ghost) {
        GHOSTS.remove(ghost.uuid());
    }

    /**
     * レベル変更を検出し、前のレベルを忘れる。tickと参加イベントの両方から呼ばれる。新しいレベルを最初に見るのは
     * どちらでもありうるからだ。ゲームスレッド。
     */
    private static void levelChanged(@Nullable ClientLevel current) {
        if (current == level) {
            return;
        }

        clear();
        level = current;
    }

    /** 全て忘れる。レベル変更、ログアウト、あるいはシステムの停止時。 */
    public static void clear() {
        GHOSTS.clear();
        ORDERED.clear();
        GhostOcclusion.reset();
        DHIntegration.onLevelChanged();
        countGhost = countBillboard = countOccluded = countOrphaned = 0;
    }

    // ------------------------------------------------------------------
    // tick 処理
    // ------------------------------------------------------------------

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel current = minecraft.level;

        levelChanged(current);

        if (current == null) {
            return;
        }

        if (!GhostConfig.enabled()) {
            if (!GHOSTS.isEmpty()) {
                clear();
            }

            return;
        }

        long now = current.getGameTime();
        Vec3 eye = minecraft.gameRenderer.getMainCamera().getPosition();
        int interval = GhostConfig.occlusionInterval();
        occlusionRaysThisTick = 0;

        // 実体からの更新とタイムアウト処理を1回のパスで行う。
        List<EntityGhost> ordered = ORDERED;
        ordered.clear();

        for (Iterator<EntityGhost> it = GHOSTS.values().iterator(); it.hasNext();) {
            EntityGhost ghost = it.next();
            Entity entity = ghost.entity();

            if (entity != null) {
                if (entity.isRemoved() || entity.level() != current) {
                    // 念のための二重防御。通常は離脱イベントが先に到達する。
                    entityGone(ghost, entity, now);

                    if (!GHOSTS.containsKey(ghost.uuid())) {
                        continue;
                    }
                } else {
                    refresh(ghost, entity, now);
                }
            } else if (now - ghost.orphanedAt() > ghost.adapter().orphanTicks()) {
                // 寿命はアダプタが型ごとに決める。範囲の縁の弾は数秒、駐機した機体は既定でセッションの間ずっと。
                it.remove();
                continue;
            }

            double distanceSq = ghost.current().position().distanceToSqr(eye);
            ghost.record(GhostLOD.of(distanceSq), distanceSq, ghost.verdict());
            ordered.add(ghost);
        }

        ordered.sort(NEAREST_FIRST);

        int budget = GhostConfig.maxGhosts();
        int maxRays = GhostConfig.maxOcclusionRays();
        countGhost = countBillboard = countOccluded = countOrphaned = 0;

        for (int i = 0; i < ordered.size(); i++) {
            EntityGhost ghost = ordered.get(i);
            GhostLOD lod = ghost.lod();
            boolean inBudget = i < budget;

            // アダプタが「レイ予算に値しない」と言ったゴーストは予算を一切取らない。
            if (lod.isGhost() && inBudget && ghost.adapter().needsOcclusionCheck()
                    && !ghost.isOcclusionPending()
                    && now - ghost.occlusionCheckedAt() >= interval && occlusionRaysThisTick < maxRays) {
                // 間隔でずらす。このtickで判定したゴーストはしばらく再判定しないので、スケジューリング無しに
                // コストがtick間へ分散する。構築済みワールド内のゴーストはレイ無しで答え予算を取らないので、
                // ワールドの外でそれを必要とする物へ予算が丸ごと残る。
                if (GhostOcclusion.check(current, eye, ghost, now)) {
                    occlusionRaysThisTick++;
                }
            }

            switch (lod) {
                case GHOST -> countGhost++;
                case BILLBOARD -> countBillboard++;
                default -> {
                }
            }

            if (ghost.isOccluded()) {
                countOccluded++;
            }

            if (ghost.isOrphaned()) {
                countOrphaned++;
            }
        }

        // tickをまたいで何も保持しない。ここに残したゴーストは自身の削除より長生きしてしまう。
        ordered.clear();
    }

    @SuppressWarnings("unchecked")
    private static void refresh(EntityGhost ghost, Entity entity, long now) {
        GhostAdapter<Entity> adapter = (GhostAdapter<Entity>) ghost.adapter();
        ghost.update(adapter.snapshot(entity, ghost.current(), now));
    }

    // ------------------------------------------------------------------
    // デバッグ用の数値
    // ------------------------------------------------------------------

    public static int countGhost() {
        return countGhost;
    }

    public static int countBillboard() {
        return countBillboard;
    }

    public static int countOccluded() {
        return countOccluded;
    }

    public static int countOrphaned() {
        return countOrphaned;
    }

}
