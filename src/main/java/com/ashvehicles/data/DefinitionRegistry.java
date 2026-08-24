package com.ashvehicles.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.ashvehicles.AshVehicles;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * One directory of JSON files that describe something, read out of the mod at start-up and again out
 * of the data packs on every {@code /reload}.
 *
 * <p>Every kind of file in the mod wants exactly this and nothing more: aircraft, ground vehicles,
 * and the weapons both of them fire. Each used to have its own loader, its own manager, its own
 * version counter and its own sync packet — three copies of the same forty lines, and a fourth
 * waiting for whatever gets added next. This is that code once, with the directory, the codec and
 * the fallback handed to it.
 *
 * <p><b>Why a reload listener rather than a data pack registry.</b> A registry is read once while the
 * world is opening and {@code /reload} never touches it again, so retuning anything would mean
 * quitting to the title screen every time. What is loaded here is pushed straight back out to the
 * players, so an edited file takes effect on the machines already in the world.
 *
 * <p><b>Why the files are also read out of the mod.</b> An entity type's size is fixed the moment it
 * is registered, and registration is over long before any data pack is touched. {@link #builtIn} is
 * that early read; it is also what anything falls back on when a pack has removed a file or a client
 * has not been sent the data yet.
 */
public final class DefinitionRegistry<T> {
    private static final Gson GSON = new Gson();

    /**
     * Every registry there is, in the order they were declared.
     *
     * <p>Kept so that the reload listeners and the sync packet can be built by walking the list
     * rather than by naming each registry twice more. Copy-on-write because it is written once at
     * class-init and read from the network thread.
     */
    private static final List<DefinitionRegistry<?>> ALL = new CopyOnWriteArrayList<>();

    /**
     * Which set of files is loaded, as a number that changes whenever any of them does.
     *
     * <p>One counter for all the registries rather than one each. Everything that holds a definition
     * rather than looking it up afresh — and everything does, because an aircraft asks for its own
     * figures dozens of times a tick — holds this beside it and throws its copy away when the number
     * moves. Sharing one counter costs a few needless re-reads after a reload that only touched one
     * directory, and saves every caller from having to know which directory its answer came from.
     */
    private static volatile int version;

    private final String directory;
    private final Codec<T> codec;
    private final StreamCodec<ByteBuf, T> streamCodec;
    private final T fallback;
    private final String what;

    private Map<ResourceLocation, T> builtIn;
    private Map<ResourceLocation, T> active = Map.of();

    /**
     * @param directory the folder under {@code data/<namespace>/}
     * @param fallback what {@link #get} answers when nobody has a file at all: something harmless,
     *                 so that the game keeps running and the log says what went wrong
     * @param what a word for the log — "aircraft", "weapons"
     */
    public static <T> DefinitionRegistry<T> of(String directory, Codec<T> codec, T fallback, String what) {
        DefinitionRegistry<T> registry = new DefinitionRegistry<>(directory, codec, fallback, what);
        ALL.add(registry);

        return registry;
    }

    private DefinitionRegistry(String directory, Codec<T> codec, T fallback, String what) {
        this.directory = directory;
        this.codec = codec;
        this.streamCodec = ByteBufCodecs.fromCodec(codec);
        this.fallback = fallback;
        this.what = what;
    }

    public static int version() {
        return version;
    }

    public static List<DefinitionRegistry<?>> registries() {
        return Collections.unmodifiableList(ALL);
    }

    /** The registry that reads a given directory, or null if nothing does. */
    public static DefinitionRegistry<?> byDirectory(String directory) {
        for (DefinitionRegistry<?> registry : ALL) {
            if (registry.directory.equals(directory)) {
                return registry;
            }
        }

        return null;
    }

    public String directory() {
        return this.directory;
    }

    public StreamCodec<ByteBuf, T> streamCodec() {
        return this.streamCodec;
    }

    /** Everything shipped inside the mod, by id. Read once, on first use. */
    public synchronized Map<ResourceLocation, T> builtIn() {
        if (this.builtIn == null) {
            this.builtIn = BuiltInFiles.read(this.directory, this.codec, this.what);
        }

        return this.builtIn;
    }

    /** What the packs have loaded right now, which on a client is what the server last sent. */
    public Map<ResourceLocation, T> all() {
        return this.active;
    }

    /**
     * One entry's figures: the loaded copy, else the mod's own, else the fallback.
     *
     * <p>The middle step matters more than it looks. Boxes are built in an entity's constructor and
     * the level records them as it joins, so one built at a moment when no shape was known has none
     * and can never be given any afterwards — a silent failure of exactly the wrong kind.
     */
    public T get(ResourceLocation id) {
        T loaded = this.active.get(id);

        if (loaded != null) {
            return loaded;
        }

        return this.builtIn().getOrDefault(id, this.fallback);
    }

    /** Whether any file, from a pack or the mod itself, describes this. */
    public boolean has(ResourceLocation id) {
        return this.active.containsKey(id) || this.builtIn().containsKey(id);
    }

    /** Applied on the server by a reload, and on a client by the sync. Bump the version afterwards. */
    void accept(Map<ResourceLocation, T> values) {
        this.active = Collections.unmodifiableMap(values);
    }

    /** Reads a directory's worth of JSON, logging and skipping whatever will not parse. */
    private void load(Map<ResourceLocation, JsonElement> files) {
        Map<ResourceLocation, T> loaded = new LinkedHashMap<>();

        files.forEach((id, json) -> this.codec.parse(JsonOps.INSTANCE, json)
                .resultOrPartial(error -> AshVehicles.LOGGER.error("Cannot read {} {}: {}", this.what, id, error))
                .ifPresent(value -> loaded.put(id, value)));

        AshVehicles.LOGGER.info("Loaded {} {}: {}", loaded.size(), this.what, loaded.keySet());

        this.accept(loaded);
    }

    /** Everything a registry holds, as one value: what the sync packet is made of. */
    public record Snapshot<T>(DefinitionRegistry<T> registry, Map<ResourceLocation, T> values) {
        public static Snapshot<?> of(DefinitionRegistry<?> registry) {
            return snapshot(registry);
        }

        private static <T> Snapshot<T> snapshot(DefinitionRegistry<T> registry) {
            return new Snapshot<>(registry, registry.all());
        }

        void apply() {
            this.registry.accept(this.values);
        }
    }

    /** Takes a whole sync in, and moves the version once for the lot rather than once each. */
    public static void acceptAll(List<Snapshot<?>> snapshots) {
        snapshots.forEach(Snapshot::apply);
        version++;
    }

    /** The reload listener for one registry. One of these is added per directory. */
    public PreparableReloadListener reloadListener() {
        return new SimpleJsonResourceReloadListener(GSON, this.directory) {
            @Override
            protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resources,
                    ProfilerFiller profiler) {
                DefinitionRegistry.this.load(files);
                version++;
            }
        };
    }
}
