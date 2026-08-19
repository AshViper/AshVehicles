package com.ashvehicles.entity;

import java.util.Objects;

import com.ashvehicles.AshVehicles;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;

/**
 * Keeps an aircraft in the air once it has flown off the edge of everyone's chunks.
 *
 * <p>Without this the long-range work upstream has nothing to work with. Minecraft only keeps chunks
 * loaded around players, and an entity in an unloaded chunk does not exist: it stops flying, and
 * there is nothing for the tracker to report or the client to draw. An aircraft therefore holds a
 * chunk ticket on whatever chunk it is over, handing it on as it goes, so it carries on flying and
 * stays visible however far it gets from anyone.
 *
 * <p>One aircraft holds one chunk, and only while it is actually going somewhere. A parked one lets
 * go, and an abandoned one lets go as soon as the engine winds down and it meets the ground, so the
 * cost is bounded by the number of aircraft in the air rather than the number ever built.
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class AircraftChunkLoader {
    private static final TicketController CONTROLLER = new TicketController(
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "aircraft"),
            (level, helper) -> {
                // Tickets outlive a restart, but the aircraft that wanted them may not have. Drop the
                // lot: anything still flying asks again on its next tick, and anything that is gone
                // does not leave a chunk loaded forever.
                helper.getEntityTickets().keySet().forEach(helper::removeAllTickets);
            });

    @SubscribeEvent
    public static void onRegisterTicketControllers(RegisterTicketControllersEvent event) {
        event.register(CONTROLLER);
    }

    /**
     * Moves the aircraft's ticket to the chunk it is over now, or releases it if the aircraft has no
     * business being loaded. Cheap to call every tick: it only does anything when the chunk changes.
     *
     * <p>Only call this from the aircraft's own tick. Taking a ticket loads the chunk on the spot,
     * which runs the chunk system's update loop; called from inside that loop (which is where entity
     * load and unload callbacks fire from) it re-enters the loop mid-iteration and crashes the
     * server. Callbacks should {@link #release} instead.
     *
     * @param held the chunk the aircraft currently holds, or null
     * @return the chunk it holds after this call, to be handed back next tick
     */
    public static ChunkPos update(AircraftEntity aircraft, ChunkPos held) {
        if (!(aircraft.level() instanceof ServerLevel level)) {
            return held;
        }

        ChunkPos wanted = shouldStayLoaded(aircraft) ? aircraft.chunkPosition() : null;

        if (Objects.equals(wanted, held)) {
            return held;
        }

        if (held != null) {
            CONTROLLER.forceChunk(level, aircraft, held.x, held.z, false, true);
        }

        if (wanted != null) {
            CONTROLLER.forceChunk(level, aircraft, wanted.x, wanted.z, true, true);
        }

        return wanted;
    }

    /**
     * Lets go of whatever chunk the aircraft holds, without asking for another. Safe to call from
     * anywhere on the server thread, including from inside the chunk system's own callbacks: dropping
     * a ticket only queues a level change, it never loads anything.
     *
     * @param held the chunk the aircraft currently holds, or null
     * @return null, the chunk it holds afterwards
     */
    public static ChunkPos release(AircraftEntity aircraft, ChunkPos held) {
        if (held != null && aircraft.level() instanceof ServerLevel level) {
            CONTROLLER.forceChunk(level, aircraft, held.x, held.z, false, true);
        }
        return null;
    }

    /** Flying, or at least trying to: a parked aircraft can unload with everything else. */
    private static boolean shouldStayLoaded(AircraftEntity aircraft) {
        return !aircraft.isRemoved() && (!aircraft.onGround() || aircraft.getThrottle() > 0.0F);
    }

    private AircraftChunkLoader() {
    }
}
