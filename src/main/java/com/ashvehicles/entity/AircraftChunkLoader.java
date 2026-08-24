package com.ashvehicles.entity;

import java.util.LinkedHashSet;
import java.util.Set;

import com.ashvehicles.AshVehicles;

import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
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
    /** How far along the flight path the claim reaches, in ticks of flight. */
    private static final double LEAD_TICKS = 30.0;
    /** How often the path is sampled, in blocks. Half a chunk, so no chunk on it is stepped over. */
    private static final double SAMPLE = 8.0;
    /**
     * The most chunks one aircraft will ever hold. Each carries a five-by-five island of loaded
     * ground with it, so a dozen strung along a flight path is a corridor rather than a region, and
     * the whole of it is let go the moment the aeroplane stops.
     */
    private static final int MOST_CHUNKS = 12;

    /** Speed, squared, below which a wreck has finished arriving and is simply lying there. */
    private static final double STOPPED = 1.0E-4;

    /** The set {@link #update} works in. Reused; never held on to past the call that fills it. */
    private static final Set<ChunkPos> SCRATCH = new LinkedHashSet<>();

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
     * Moves the aircraft's tickets onto the ground it is about to be over, or releases them if the
     * aircraft has no business being loaded. Cheap to call every tick: it only does anything when the
     * set of chunks changes.
     *
     * <p>Only call this from the aircraft's own tick. Taking a ticket loads the chunk on the spot,
     * which runs the chunk system's update loop; called from inside that loop (which is where entity
     * load and unload callbacks fire from) it re-enters the loop mid-iteration and crashes the
     * server. Callbacks should {@link #release} instead.
     *
     * @param held the chunks the aircraft currently holds
     * @return the chunks it holds after this call, to be handed back next tick
     */
    public static Set<ChunkPos> update(AircraftEntity aircraft, Set<ChunkPos> held) {
        if (!(aircraft.level() instanceof ServerLevel level)) {
            return held;
        }

        // Worked out into a set that is kept and reused, because the answer is nearly always the
        // same as last tick's and a set built to be thrown away is the whole cost of the call. The
        // copy is only made once it is known that the claim has actually moved, and it is made
        // before a single ticket is touched, so nothing reached from inside the chunk system can
        // find this half-built. Server thread only, which is where the aircraft tick runs.
        Set<ChunkPos> scratch = SCRATCH;
        scratch.clear();

        if (shouldStayLoaded(aircraft)) {
            ahead(aircraft, scratch);
        }

        if (scratch.equals(held)) {
            scratch.clear();

            return held;
        }

        Set<ChunkPos> wanted = scratch.isEmpty() ? Set.of() : Set.copyOf(scratch);
        scratch.clear();

        for (ChunkPos pos : held) {
            if (!wanted.contains(pos)) {
                CONTROLLER.forceChunk(level, aircraft, pos.x, pos.z, false, true);
            }
        }

        for (ChunkPos pos : wanted) {
            if (!held.contains(pos)) {
                CONTROLLER.forceChunk(level, aircraft, pos.x, pos.z, true, true);
            }
        }

        return wanted;
    }

    /**
     * The ground the aircraft is over, and the ground it is about to be over.
     *
     * <p>One chunk is enough for an aeroplane idling along and nowhere near enough for one that is
     * not. A ticket does not put a chunk there; it asks for one, and the chunk system has to read it
     * off the disk or make it from nothing, which takes a good deal longer than the two ticks a fast
     * aircraft spends crossing the single chunk it was standing in. What the pilot sees when it
     * cannot keep up is a hillside arriving around them.
     *
     * <p>So the claim runs along the flight path rather than sitting under the aeroplane: far enough
     * ahead to be a warning rather than a surprise, sampled closely enough that no chunk on the way
     * is stepped over, and capped, because a claim that grows without limit with speed is a way of
     * asking a server to generate the world.
     *
     * <p>Standing still costs nothing. A parked aircraft samples one chunk, which is the one it is
     * parked in.
     */
    private static void ahead(AircraftEntity aircraft, Set<ChunkPos> chunks) {
        chunks.add(aircraft.chunkPosition());

        Vec3 velocity = aircraft.getVelocity();
        double speed = velocity.length();

        if (speed < 1.0E-3) {
            return;
        }

        // Half a chunk at a time, so nothing on the path is missed however the flight path happens
        // to lie across the grid. Walked as two numbers rather than as points along it: the path is
        // sampled a dozen times every tick of every aircraft in the air, and a Vec3 and a BlockPos
        // per sample is a great deal of rubbish for an answer two integers wide.
        double stepX = velocity.x * SAMPLE / speed;
        double stepZ = velocity.z * SAMPLE / speed;
        double x = aircraft.getX();
        double z = aircraft.getZ();
        double samples = Math.min(speed * LEAD_TICKS, MOST_CHUNKS * 16.0) / SAMPLE;

        for (int i = 0; i < samples && chunks.size() < MOST_CHUNKS; i++) {
            x += stepX;
            z += stepZ;
            chunks.add(new ChunkPos(SectionPos.blockToSectionCoord(Mth.floor(x)),
                    SectionPos.blockToSectionCoord(Mth.floor(z))));
        }
    }

    /**
     * Lets go of whatever chunk the aircraft holds, without asking for another. Safe to call from
     * anywhere on the server thread, including from inside the chunk system's own callbacks: dropping
     * a ticket only queues a level change, it never loads anything.
     *
     * @param held the chunks the aircraft currently holds
     * @return nothing, which is what it holds afterwards
     */
    public static Set<ChunkPos> release(AircraftEntity aircraft, Set<ChunkPos> held) {
        if (!held.isEmpty() && aircraft.level() instanceof ServerLevel level) {
            for (ChunkPos pos : held) {
                CONTROLLER.forceChunk(level, aircraft, pos.x, pos.z, false, true);
            }
        }

        return Set.of();
    }

    /** Flying, or at least trying to: a parked aircraft can unload with everything else. */
    private static boolean shouldStayLoaded(AircraftEntity aircraft) {
        if (aircraft.isRemoved()) {
            return false;
        }

        // A write-off holds its chunk open only for as long as it is still coming down, and lets go
        // the moment it stops moving. Asked the ordinary question it would often never let go at all:
        // a wreck lodged against a hillside, or lying on a wing rather than on its wheels, never
        // reports itself on the ground, and one aeroplane shot down over open country would force a
        // chunk open for the rest of the world's life.
        if (aircraft.isWrecked()) {
            return aircraft.getVelocity().lengthSqr() > STOPPED;
        }

        return !aircraft.onGround() || aircraft.getThrottle() > 0.0F;
    }

    private AircraftChunkLoader() {
    }
}
