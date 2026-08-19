package com.ashvehicles.aircraft;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.network.AircraftSyncPayload;
import com.ashvehicles.weapon.WeaponDefinition;
import com.ashvehicles.weapon.WeaponLoader;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Keeps the aircraft, shape and weapon files loaded, and reloadable.
 *
 * <p>This is a plain resource reload listener rather than a data pack registry, and that is the
 * whole point of it: a data pack registry is read once while the world is being opened and
 * {@code /reload} never touches it again, so tuning an aircraft would mean quitting to the title
 * screen every time. A reload listener is re-run on every {@code /reload}, and the results are
 * pushed straight back out to the players, so an edited file takes effect on the aircraft already in
 * the air.
 *
 * <p>Sending them matters because the piloting client runs the flight model itself: if the two sides
 * disagreed about how hard an aircraft accelerates, the server would spend its time dragging the
 * aircraft back to where it thought it should be.
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class AircraftManager extends SimpleJsonResourceReloadListener {
    /** Performance files live in {@code data/<namespace>/aircraft/}. */
    public static final String DIRECTORY = "aircraft";
    /** Collision shapes live in {@code data/<namespace>/collision/}, named after their aircraft. */
    public static final String SHAPE_DIRECTORY = "collision";
    /** Weapons live in {@code data/<namespace>/weapon/}. */
    public static final String WEAPON_DIRECTORY = "weapon";
    private static final Gson GSON = new Gson();

    /** What is loaded right now. Written by a reload on the server, by the sync on a client. */
    private static Map<ResourceLocation, AircraftDefinition> active = Map.of();
    private static Map<ResourceLocation, AircraftShape> shapes = Map.of();
    private static Map<ResourceLocation, WeaponDefinition> weapons = Map.of();

    private final String directory;

    public AircraftManager(String directory) {
        super(GSON, directory);
        this.directory = directory;
    }

    /**
     * One aircraft's figures. Falls back to the copy read out of the mod at start-up, so an aircraft
     * still flies if a pack removed its file or a client has not been sent the data yet.
     */
    public static AircraftDefinition get(ResourceLocation id) {
        AircraftDefinition definition = active.get(id);

        return definition != null ? definition : AircraftLoader.builtIn(id);
    }

    public static Map<ResourceLocation, AircraftDefinition> all() {
        return active;
    }

    /**
     * The boxes one aircraft is made of, or none if it has no shape file at all.
     *
     * <p>Falls back to the copy read out of the mod at start-up, exactly as the figures do. That
     * matters more here than it does there: an aircraft builds its boxes once, in its constructor,
     * and the level records them as it joins, so one built at a moment when no shape was known has
     * no boxes and can never be given any afterwards. It would go on flying perfectly, silently back
     * on Minecraft's plain hitbox, which is a poor thing to discover by being shot through a wing.
     */
    public static AircraftShape shape(ResourceLocation id) {
        AircraftShape shape = shapes.get(id);

        return shape != null ? shape : AircraftLoader.builtInShape(id);
    }

    public static Map<ResourceLocation, AircraftShape> allShapes() {
        return shapes;
    }

    /** One weapon's figures, with the same fallback to the mod's own copy as an aircraft. */
    public static WeaponDefinition weapon(ResourceLocation id) {
        WeaponDefinition definition = weapons.get(id);

        return definition != null ? definition : WeaponLoader.builtIn(id);
    }

    /** Whether any file, from a pack or the mod itself, describes this weapon. */
    public static boolean hasWeapon(ResourceLocation id) {
        return weapons.containsKey(id) || WeaponLoader.builtIn().containsKey(id);
    }

    public static Map<ResourceLocation, WeaponDefinition> allWeapons() {
        return weapons;
    }

    /** Applied on the client when the server sends its copy. */
    public static void accept(Map<ResourceLocation, AircraftDefinition> definitions,
            Map<ResourceLocation, AircraftShape> collisionShapes,
            Map<ResourceLocation, WeaponDefinition> weaponDefinitions) {
        active = Collections.unmodifiableMap(definitions);
        shapes = Collections.unmodifiableMap(collisionShapes);
        weapons = Collections.unmodifiableMap(weaponDefinitions);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resources, ProfilerFiller profiler) {
        switch (this.directory) {
            case SHAPE_DIRECTORY -> shapes = parse(files, AircraftShape.CODEC, "aircraft shapes");
            case WEAPON_DIRECTORY -> weapons = parse(files, WeaponDefinition.CODEC, "weapons");
            default -> active = parse(files, AircraftDefinition.CODEC, "aircraft");
        }
    }

    private static <T> Map<ResourceLocation, T> parse(Map<ResourceLocation, JsonElement> files, Codec<T> codec, String what) {
        Map<ResourceLocation, T> loaded = new LinkedHashMap<>();

        files.forEach((id, json) -> codec.parse(JsonOps.INSTANCE, json)
                .resultOrPartial(error -> AshVehicles.LOGGER.error("Cannot read {} {}: {}", what, id, error))
                .ifPresent(value -> loaded.put(id, value)));

        AshVehicles.LOGGER.info("Loaded {} {}: {}", loaded.size(), what, loaded.keySet());

        return Collections.unmodifiableMap(loaded);
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new AircraftManager(DIRECTORY));
        event.addListener(new AircraftManager(SHAPE_DIRECTORY));
        event.addListener(new AircraftManager(WEAPON_DIRECTORY));
    }

    /** Fired on login and again after every {@code /reload}, which is exactly when this has to go out. */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        AircraftSyncPayload payload = new AircraftSyncPayload(active, shapes, weapons);

        event.getRelevantPlayers().forEach(player -> PacketDistributor.sendToPlayer(player, payload));
    }
}
