package com.ashvehicles.entity;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

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
 * aim at. Chunks exist around players and nowhere else; an aircraft carries the corridor it is
 * flying down and no more, which anything it fires leaves within a tick or two of the rail, and a
 * tank fires at a hillside its own claim never reaches. {@link VehicleProjectile} keeps flying out
 * there — see {@code isAlwaysTicking} — but it will not ask about blocks nobody has loaded, because
 * asking generates the terrain on the spot and on the main thread, which for one missile crossing
 * empty sky is a corridor of freshly made world thirty blocks a tick wide.
 *
 * <p>So the round asks the proper way instead, with a ticket, and holds open the ground it is about
 * to fly through. By the time a bomb released from altitude gets down there the ground it is going
 * to hit exists, and it cracks it open instead of falling through it; a burst aimed at a ridge four
 * hundred blocks off arrives at the ridge, rather than at the empty air where the ridge has not been
 * made yet.
 *
 * <p><b>Everything fired claims ground.</b> Only what was <em>dropped</em> used to, because a claim
 * was one ticket per round and a gun is twenty rounds a second. What that bought was a weapon that
 * plainly did not work: rounds passed through hillsides and expired in mid-air behind them, and
 * nothing about that reads to a pilot as a chunk that is not loaded. It is the cost of a claim that
 * had to go rather than the range of the guns — see below — and a weapon file can still say
 * {@code chunk_loading} outright either way.
 *
 * <p><b>What one ticket buys.</b> More than one chunk: the claim propagates outwards, so the ground
 * within two chunks of the claimed one is loaded too, which is the difference between this working
 * and not — a round crosses a chunk boundary constantly and every step of its flight is tested
 * against the whole span it covers. The claim is put half a tick ahead of the round rather than a
 * whole one so that it straddles the step instead of sitting at the end of it: a tank round covers
 * thirty-five blocks in a tick, and it is the whole of that line that is measured against the world.
 *
 * <p><b>Two claims, not one.</b> Only the chunk actually named is there when the claim returns; the
 * ring around it is asked for and made in the background. That ring is what the round will be flying
 * through a tick later, and a round that gets there first does not fly through a hole — the block
 * lookup waits for the ground to be made, on the tick thread, which is the stall this whole file
 * exists to avoid. So each round claims the step after next as well as the next one, a tick early,
 * and the chunk system has that tick to make it in its own time. It costs nothing to do: the far
 * claim of one tick is the near claim of the following one, and finding it already held is a number
 * going up rather than a chunk being made. A bomb, which does not cross a chunk in a tick, names the
 * same chunk twice and holds one.
 *
 * <p><b>What keeps it affordable.</b> Three things, because the plain version of this — a ticket per
 * round, moved every tick — is a way of asking a server to generate the world:
 *
 * <ul>
 * <li><b>Rounds share.</b> Claims are counted per chunk for the whole world rather than held per
 * round, so a burst going down one line pays for that line once however many rounds are strung out
 * along it, and the second round through a chunk costs a number going up by one.
 * <li><b>The ground is loaded, not ticked.</b> A round ticks itself wherever it is, so nothing here
 * needs the chunk to tick: no random ticks, no spawning, no fire spreading through country nobody is
 * standing in. Loaded is all a round has ever wanted out there — something to hit.
 * <li><b>New ground is rationed.</b> Claiming a chunk somebody already has loaded is nearly free;
 * claiming one nobody has reads it off the disk or makes it from nothing, there and then, before the
 * call returns. Only so many of those are done in a tick. A round refused one flies blind for that
 * tick and asks again on the next, which costs the occasional round to a hillside it should have hit
 * and never costs the tick everyone else is standing in.
 * </ul>
 *
 * <p>And a ceiling over the lot, in case some pack ships a weapon that fires a hundred rounds a
 * second to a range of two miles: past {@link #MOST_CHUNKS} the world stops handing claims out, and
 * whatever is left in the air flies as it did before any of this, which is to say through the
 * scenery.
 *
 * <p>Written alongside {@link AircraftChunkLoader} rather than shared with it, because the two want
 * different things — an aircraft holds a corridor of ticking ground, and holds it as one machine
 * rather than as a crowd — but the two rules about <em>when</em> to call which method are the same
 * for both, and are set out on the methods below.
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class WeaponChunkLoader {
    /**
     * How far ahead of the round the near claim is placed, in ticks of flight.
     *
     * <p>Half a tick, so that the claim sits in the middle of the step rather than at the end of it
     * and the loaded ground reaches equally far either side of the line being tested. A whole tick
     * puts it at the far end, which is right for a bomb and marginal for a tank round travelling
     * thirty-five blocks between one tick and the next: the back half of its own flight path then
     * lies at the edge of what the claim reaches.
     */
    private static final double NEAR = 0.5;

    /**
     * And the far one, in ticks of flight: the middle of the step after next.
     *
     * <p>Asked for a tick before it is flown through, so that the ground around a claimed chunk — the
     * part of it that is made in the background rather than before the claim returns — is there when
     * the round arrives rather than something the round waits for. See the note on two claims above.
     */
    private static final double FAR = 1.5;

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

    /**
     * The most chunks the weapons of one world may hold open between them.
     *
     * <p>Counted in claims rather than in rounds, which is the point of counting them together: a
     * gun firing down one line holds the chunks that line passes through, whether that is one round
     * in the air or forty. Five dozen is a squadron's work in the air at once, and it is a ceiling
     * rather than a target — the ordinary number, with one aeroplane strafing one hillside, is under
     * ten.
     */
    private static final int MOST_CHUNKS = 64;

    /**
     * How many chunks nobody has loaded the weapons of one world may ask for in a single tick.
     *
     * <p>Each of those is a chunk read off the disk, or made from nothing, while everything else
     * waits. Eight is enough for several rounds to be crossing fresh country at once and few enough
     * that a salvo cannot stop the clock; the rest ask again next tick, by which point the ground
     * their neighbours claimed is already there and free to take.
     */
    private static final int LOADS_PER_TICK = 8;

    /**
     * What each world's weapons are holding open. Server thread only, which is where every round's
     * tick and every unload callback runs.
     *
     * <p>Held weakly so that a world that goes away takes its book-keeping with it. There is nothing
     * to tidy up when one does: the tickets belong to the level, and they go where it goes.
     */
    private static final Map<ServerLevel, Claims> CLAIMS = new WeakHashMap<>();

    private static final TicketController CONTROLLER = new TicketController(
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "weapon"),
            (level, helper) -> {
                // Tickets outlive a restart; nothing in the air does. Anything that somehow did asks
                // again on its next tick, and everything else stops holding ground open forever.
                // Both sorts of owner: a claim belongs to the chunk it is for these days, and a world
                // saved before that will still have the per-round ones it used to keep.
                helper.getBlockTickets().keySet().forEach(helper::removeAllTickets);
                helper.getEntityTickets().keySet().forEach(helper::removeAllTickets);
            });

    @SubscribeEvent
    public static void onRegisterTicketControllers(RegisterTicketControllersEvent event) {
        event.register(CONTROLLER);
    }

    /**
     * Moves the round's claims onto the ground it is about to fly through, or releases them if this
     * is not a round that claims ground. Only does anything when the chunks change — which for
     * something dropped is a few times in a long fall, and for anything fired is nearly every tick,
     * at which point it is one number in a map going down and another going up unless the round is
     * the first thing to want that piece of ground.
     *
     * <p>Only call this from the round's own tick. Taking a ticket loads the chunk on the spot, which
     * runs the chunk system's update loop; called from inside that loop — which is where entity load
     * and unload callbacks fire from — it re-enters the loop mid-iteration and brings the server
     * down. Callbacks should {@link #release} instead.
     *
     * @param hold what the round is holding, updated in place to what it holds afterwards
     */
    public static void update(VehicleProjectile shot, Hold hold) {
        if (!(shot.level() instanceof ServerLevel level)) {
            return;
        }

        ChunkPos near = null;
        ChunkPos far = null;

        if (shouldStayLoaded(shot)) {
            near = along(shot, NEAR);
            far = along(shot, FAR);

            // Anything slower than a chunk a tick names the same ground twice. One claim is then the
            // whole of what it wanted, and the far slot is left empty rather than counted twice.
            if (far.equals(near)) {
                far = null;
            }
        }

        if (Objects.equals(near, hold.near) && Objects.equals(far, hold.far)) {
            return;
        }

        Claims claims = CLAIMS.computeIfAbsent(level, ignored -> new Claims());
        ChunkPos wasNear = hold.near;
        ChunkPos wasFar = hold.far;

        // Let go first, and only of ground this round has stopped wanting altogether: what it held
        // ahead of itself last tick is usually what it is flying through now, and dropping that only
        // to ask for it again is the ticket taken twice and the chunk let go in between.
        drop(claims, level, wasNear, near, far);
        drop(claims, level, wasFar, near, far);

        hold.near = keep(claims, level, near, wasNear, wasFar);
        hold.far = keep(claims, level, far, wasNear, wasFar);
    }

    /**
     * Lets go of whatever ground the round holds, without asking for any more. Safe to call from
     * anywhere on the server thread, including from inside the chunk system's own callbacks:
     * dropping a ticket only queues a level change, it never loads anything.
     *
     * @param hold what the round is holding, left empty
     */
    public static void release(VehicleProjectile shot, Hold hold) {
        if (hold.near == null && hold.far == null) {
            return;
        }

        if (shot.level() instanceof ServerLevel level) {
            Claims claims = CLAIMS.get(level);

            if (claims != null) {
                drop(claims, level, hold.near, null, null);
                drop(claims, level, hold.far, null, null);
            }
        }

        hold.near = null;
        hold.far = null;
    }

    /** Drops a chunk the round used to hold, unless it is one of the two it now wants. */
    private static void drop(Claims claims, ServerLevel level, @Nullable ChunkPos had,
            @Nullable ChunkPos near, @Nullable ChunkPos far) {
        if (had != null && !had.equals(near) && !had.equals(far)) {
            claims.drop(level, had);
        }
    }

    /**
     * Takes a chunk the round now wants, unless it was already holding it.
     *
     * @return the chunk, or null if it is not wanted or the world would not go that far this tick,
     *         in which case nothing is held and the round asks again on its next
     */
    @Nullable
    private static ChunkPos keep(Claims claims, ServerLevel level, @Nullable ChunkPos wanted,
            @Nullable ChunkPos wasNear, @Nullable ChunkPos wasFar) {
        if (wanted == null) {
            return null;
        }

        if (wanted.equals(wasNear) || wanted.equals(wasFar)) {
            return wanted;
        }

        return claims.take(level, wanted) ? wanted : null;
    }

    /** The chunk the round is over that many ticks of flight from now. */
    private static ChunkPos along(VehicleProjectile shot, double ticks) {
        Vec3 at = shot.position().add(shot.getDeltaMovement().scale(ticks));

        return new ChunkPos(BlockPos.containing(at));
    }

    private static boolean shouldStayLoaded(VehicleProjectile shot) {
        return !shot.isRemoved() && shot.age <= LONGEST_HOLD && shot.getWeapon().loadsChunks();
    }

    /**
     * What one round is holding open: the ground under the step it is about to take, and the ground
     * under the one after that.
     *
     * <p>Two named slots rather than a set, because there are two of them and there will never be
     * more, and because the far one of this tick is nearly always the near one of the next — which
     * is a claim to leave alone rather than a claim to make again. Kept by the round, since the round
     * is the only thing that knows when it has stopped wanting them.
     */
    public static final class Hold {
        @Nullable
        private ChunkPos near;
        @Nullable
        private ChunkPos far;
    }

    /**
     * The ground one world's weapons are holding open, and how many rounds are asking for each piece
     * of it.
     *
     * <p>Counted rather than owned, which is what makes a gun affordable. The ticket belongs to the
     * chunk — the chunk's own corner block is the owner the chunk system is given — so however many
     * rounds are strung out along a line of fire, each chunk of that line is claimed once and let go
     * when the last round through it has gone.
     */
    private static final class Claims {
        /** Chunks held, against the number of rounds wanting each. Never holds a count of zero. */
        private final Map<ChunkPos, Integer> held = new HashMap<>();

        /** The tick {@link #loads} is counted against, and how much of that tick has been spent. */
        private long tick = Long.MIN_VALUE;
        private int loads;

        /**
         * Adds a round's interest in a chunk, taking a ticket for it if it is the first.
         *
         * @return whether the chunk is now held. False if the world will not go that far, in which
         *         case nothing has been claimed and the round has nothing to let go of
         */
        boolean take(ServerLevel level, ChunkPos pos) {
            Integer wanting = this.held.get(pos);

            if (wanting != null) {
                this.held.put(pos, wanting + 1);

                return true;
            }

            if (this.held.size() >= MOST_CHUNKS || !this.affordable(level, pos)) {
                return false;
            }

            // Loaded, not ticking: a round ticks itself wherever it is, and what it wants out here is
            // ground to hit rather than a countryside running at full speed with nobody in it.
            CONTROLLER.forceChunk(level, pos.getWorldPosition(), pos.x, pos.z, true, false);
            this.held.put(pos, 1);

            return true;
        }

        /** Takes a round's interest back out, and lets the chunk go if it was the last. */
        void drop(ServerLevel level, ChunkPos pos) {
            Integer wanting = this.held.get(pos);

            if (wanting == null) {
                return;
            }

            if (wanting > 1) {
                this.held.put(pos, wanting - 1);

                return;
            }

            this.held.remove(pos);
            CONTROLLER.forceChunk(level, pos.getWorldPosition(), pos.x, pos.z, false, false);
        }

        /**
         * Whether this world can afford to be asked for that chunk this tick.
         *
         * <p>Ground somebody already has loaded is always affordable: the ticket then only keeps it,
         * and keeping it is book-keeping. Ground nobody has is read or made before the call returns,
         * so only so many of those are done between one tick and the next.
         */
        private boolean affordable(ServerLevel level, ChunkPos pos) {
            if (level.getChunkSource().hasChunk(pos.x, pos.z)) {
                return true;
            }

            long now = level.getGameTime();

            if (now != this.tick) {
                this.tick = now;
                this.loads = 0;
            }

            if (this.loads >= LOADS_PER_TICK) {
                return false;
            }

            this.loads++;

            return true;
        }
    }

    private WeaponChunkLoader() {
    }
}
