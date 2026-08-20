package com.ashvehicles.client.ghost;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * Which entity types are ghosted, and by which adapter.
 *
 * <p>Keyed by entity type, which is both the cheapest thing to look up — one identity hash on the
 * entity's type — and the natural unit: every Su-25 is a ghost or none is. Registration happens
 * during client setup; after that the map is read-only and read from both the game thread and the
 * render thread.
 */
public final class EntityGhostRegistry {
    private static final Map<EntityType<?>, GhostAdapter<?>> ADAPTERS = new IdentityHashMap<>();
    private static volatile Map<EntityType<?>, GhostAdapter<?>> view = Collections.emptyMap();

    private EntityGhostRegistry() {
    }

    /** Registers an adapter for an entity type. Client setup only. */
    public static synchronized <T extends Entity> void register(EntityType<? extends T> type, GhostAdapter<T> adapter) {
        ADAPTERS.put(type, adapter);
        view = Collections.unmodifiableMap(new IdentityHashMap<>(ADAPTERS));
    }

    /** The adapter for an entity's type, or {@code null} if the type is not ghosted. */
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
