package com.ashvehicles.client.ghost;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * どのエンティティタイプをゴースト化し、どのアダプタで扱うか。
 *
 * <p>キーはエンティティタイプ。最も安価な検索キー——タイプの identity hash 1回——であると同時に自然な単位でも
 * ある。Su-25 は全機がゴーストになるか1機もならないかのどちらかだ。登録はクライアント初期化時に行い、以降は
 * 読み取り専用で、ゲームスレッドとレンダースレッドの双方から読まれる。
 */
public final class EntityGhostRegistry {
    private static final Map<EntityType<?>, GhostAdapter<?>> ADAPTERS = new IdentityHashMap<>();
    private static volatile Map<EntityType<?>, GhostAdapter<?>> view = Collections.emptyMap();

    private EntityGhostRegistry() {
    }

    /** エンティティタイプにアダプタを登録する。クライアント初期化時のみ。 */
    public static synchronized <T extends Entity> void register(EntityType<? extends T> type, GhostAdapter<T> adapter) {
        ADAPTERS.put(type, adapter);
        view = Collections.unmodifiableMap(new IdentityHashMap<>(ADAPTERS));
    }

    /** そのエンティティのタイプ用アダプタ。ゴースト対象外なら {@code null}。 */
    @Nullable
    @SuppressWarnings("unchecked")
    public static <T extends Entity> GhostAdapter<T> adapterFor(Entity entity) {
        return (GhostAdapter<T>) view.get(entity.getType());
    }

    public static boolean isRegistered(Entity entity) {
        return view.containsKey(entity.getType());
    }

    public static int size() {
        return view.size();
    }
}
