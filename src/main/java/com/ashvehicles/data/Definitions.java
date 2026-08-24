package com.ashvehicles.data;

import java.util.ArrayList;
import java.util.List;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.network.DefinitionSyncPayload;
import com.ashvehicles.vehicle.GroundVehicleDefinition;
import com.ashvehicles.vehicle.VehicleShape;
import com.ashvehicles.weapon.WeaponDefinition;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Every kind of file the mod reads, and the one place they are all listed.
 *
 * <p>Three directories, three registries, and nothing else: {@link DefinitionRegistry} does the
 * reading, the reloading and the falling back for all of them. Adding a fourth kind of file is a
 * line here and a codec, with no loader, no manager and no packet to write.
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class Definitions {
    /** Aircraft performance files live in {@code data/<namespace>/aircraft/}. */
    public static final DefinitionRegistry<AircraftDefinition> AIRCRAFT = DefinitionRegistry.of(
            "aircraft", AircraftDefinition.CODEC, AircraftDefinition.FALLBACK, "aircraft");
    /** Ground vehicles, in {@code vehicle/}. */
    public static final DefinitionRegistry<GroundVehicleDefinition> VEHICLES = DefinitionRegistry.of(
            "vehicle", GroundVehicleDefinition.CODEC, GroundVehicleDefinition.FALLBACK, "ground vehicles");
    /** What any of them fires, in {@code weapon/}. */
    public static final DefinitionRegistry<WeaponDefinition> WEAPONS = DefinitionRegistry.of(
            "weapon", WeaponDefinition.CODEC, WeaponDefinition.FALLBACK, "weapons");

    /** Which set of files is loaded, as a number that changes whenever any of them does. */
    public static int version() {
        return DefinitionRegistry.version();
    }

    /**
     * The boxes a machine is made of, whichever kind of machine it is.
     *
     * <p>Out of its own file, because that is where they live now. Which of the two files to look in
     * is not something the caller has to know or could always say: everything with a shape is either
     * a vehicle or an aircraft, an id belongs to exactly one of the two, and asking both costs a
     * lookup in a map that was already read.
     */
    public static VehicleShape shape(ResourceLocation id) {
        if (VEHICLES.has(id)) {
            return VEHICLES.get(id).hitbox().shape();
        }

        if (AIRCRAFT.has(id)) {
            return AIRCRAFT.get(id).hitbox().shape();
        }

        return VehicleShape.NONE;
    }

    public static WeaponDefinition weapon(ResourceLocation id) {
        return WEAPONS.get(id);
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        DefinitionRegistry.registries().forEach(registry -> event.addListener(registry.reloadListener()));
    }

    /**
     * Fired on login and again after every {@code /reload}, which is exactly when this has to go out.
     *
     * <p>One packet with everything in it rather than one per kind. The client runs the physics
     * itself — for an aircraft and for a ground vehicle both — so if the two sides disagreed about
     * any of these figures the server would spend its time dragging the machine back to where it
     * thought it should be.
     */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        List<DefinitionRegistry.Snapshot<?>> snapshots = new ArrayList<>();

        DefinitionRegistry.registries().forEach(registry ->
                snapshots.add(DefinitionRegistry.Snapshot.of(registry)));

        DefinitionSyncPayload payload = new DefinitionSyncPayload(snapshots);

        event.getRelevantPlayers().forEach(player -> PacketDistributor.sendToPlayer(player, payload));
    }

    private Definitions() {
    }
}
