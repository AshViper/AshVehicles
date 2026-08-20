package com.ashvehicles.entity;

import java.util.Objects;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;

/**
 * Gives a round in flight somewhere to land.
 *
 * <p>A weapon is aimed at the ground, and past the edge of the loaded world there is no ground to
 * aim at. Chunks exist around players and nowhere else; the aircraft holds open the one it is over
 * and no more, so anything it fires is out over nothing within a tick or two of leaving the rail.
 * {@link AircraftProjectile} keeps flying out there — see {@code isAlwaysTicking} — but it will not
 * ask about blocks, because asking generates the terrain on the spot and on the main thread, which
 * for one missile crossing empty sky is a corridor of freshly made world thirty blocks a tick wide.
 *
 * <p>So the round asks the proper way instead, with a ticket, and the chunk system loads what it
 * needs on its own threads and in its own time. By the time a bomb released from altitude gets down
 * there, the ground it is going to hit exists, and it cracks it open instead of falling through it.
 *
 * <p><b>What one ticket buys.</b> More than one chunk: a ticking ticket propagates outwards, so the
 * chunks within a couple of the claimed one are loaded too, which is the difference between this
 * working and not — a round crosses a chunk boundary constantly and every step of its flight is
 * tested against the whole span it covers. The claim is put one tick of flight ahead of the round
 * for the same reason, since a chunk that has to be read off the disk or made from scratch is not
 * there the instant it is asked for.
 *
 * <p><b>What it does not buy.</b> Something moving thirty blocks a tick outruns its own claim: the
 * chunks it is asking for are still being made when it is already past them, and it goes back to
 * flying blind. This is worth having for a bomb, which spends the best part of ten seconds coming
 * down through the same two chunks, and for anything else slow enough to stay inside what it has
 * asked for. A missile is aimed at an aeroplane, and an aeroplane is loaded wherever it is.
 *
 * <p>Which rounds claim anything at all is the weapon's own business; see
 * {@link com.ashvehicles.weapon.WeaponDefinition.Projectile#loadsChunks()}. Cannon rounds do not, and
 * must not: twenty a second, each holding a chunk open, is a different mod's performance problem.
 *
 * <p>Written alongside {@link AircraftChunkLoader} rather than shared with it, because the two want
 * different things — an aircraft holds the chunk it is standing on and only while it is flying —
 * but the two rules about <em>when</em> to call which method are the same for both, and are set out
 * on the methods below.
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class WeaponChunkLoader {
    /**
     * How far ahead of the round the claim is placed, in ticks of flight. Enough that a chunk has a
     * moment to arrive before the round does; not so much that the claim is somewhere the round is
     * not, which for a bomb would be the wrong chunk entirely.
     */
    private static final int LEAD_TICKS = 1;

    /**
     * The longest a round may hold ground open, in ticks.
     *
     * <p>Half a minute, which is several times over what the slowest thing here needs: a bomb from
     * the top of the world is down inside a hundred and fifty ticks. It is a backstop rather than a
     * rule, because a weapon's life is worked out from its range and its speed, and a bomb's speed is
     * the shove it gets off the rack rather than the speed it falls at — which makes the arithmetic
     * say twenty minutes. Nothing should ever reach this; something that does was going to hold a
     * chunk open until somebody noticed.
     */
    private static final int LONGEST_HOLD = 600;

    private static final TicketController CONTROLLER = new TicketController(
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "weapon"),
            (level, helper) -> {
                // Tickets outlive a restart; nothing in the air does. Anything that somehow did asks
                // again on its next tick, and everything else stops holding ground open forever.
                helper.getEntityTickets().keySet().forEach(helper::removeAllTickets);
            });

    @SubscribeEvent
    public static void onRegisterTicketControllers(RegisterTicketControllersEvent event) {
        event.register(CONTROLLER);
    }

    /**
     * Moves the round's claim to the chunk it is about to be over, or releases it if this is not a
     * round that claims ground. Cheap to call every tick: it only does anything when the chunk
     * changes, which even at these speeds is not most ticks.
     *
     * <p>Only call this from the round's own tick. Taking a ticket loads the chunk on the spot, which
     * runs the chunk system's update loop; called from inside that loop — which is where entity load
     * and unload callbacks fire from — it re-enters the loop mid-iteration and brings the server
     * down. Callbacks should {@link #release} instead.
     *
     * @param held the chunk the round currently holds, or null
     * @return the chunk it holds after this call, to be handed back next tick
     */
    @Nullable
    public static ChunkPos update(AircraftProjectile shot, @Nullable ChunkPos held) {
        if (!(shot.level() instanceof ServerLevel level)) {
            return held;
        }

        ChunkPos wanted = shouldStayLoaded(shot) ? ahead(shot) : null;

        if (Objects.equals(wanted, held)) {
            return held;
        }

        if (held != null) {
            CONTROLLER.forceChunk(level, shot, held.x, held.z, false, true);
        }

        if (wanted != null) {
            CONTROLLER.forceChunk(level, shot, wanted.x, wanted.z, true, true);
        }

        return wanted;
    }

    /**
     * Lets go of whatever chunk the round holds, without asking for another. Safe to call from
     * anywhere on the server thread, including from inside the chunk system's own callbacks:
     * dropping a ticket only queues a level change, it never loads anything.
     *
     * @param held the chunk the round currently holds, or null
     * @return null, the chunk it holds afterwards
     */
    @Nullable
    public static ChunkPos release(AircraftProjectile shot, @Nullable ChunkPos held) {
        if (held != null && shot.level() instanceof ServerLevel level) {
            CONTROLLER.forceChunk(level, shot, held.x, held.z, false, true);
        }

        return null;
    }

    /** The chunk this tick's flight is taking the round into. */
    private static ChunkPos ahead(AircraftProjectile shot) {
        Vec3 at = shot.position().add(shot.getDeltaMovement().scale(LEAD_TICKS));

        return new ChunkPos(BlockPos.containing(at));
    }

    private static boolean shouldStayLoaded(AircraftProjectile shot) {
        return !shot.isRemoved() && shot.age <= LONGEST_HOLD && shot.getRound().loadsChunks();
    }

    private WeaponChunkLoader() {
    }
}
