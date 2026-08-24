package com.ashvehicles.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.vehicle.Hitbox;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

/**
 * Where the mod's machines are, and everything the world asks of the boxes they are made of.
 *
 * <p>The game is not asked and is not told. Its own collision works in upright boxes and cannot be
 * given anything else, so the boxes are kept here instead, in a list of the machines in each level,
 * and the two questions that matter are answered against them directly: how far something moving
 * gets before it runs into a machine ({@link #limit}), and what a line aimed through a machine hits
 * ({@link #pick}). The mixins that ask are the whole of the join between this and Minecraft.
 *
 * <p><b>Why a list of machines rather than asking the level.</b> Both questions are asked of
 * everything that moves, every tick, and everything that is aimed, every frame. Asking the level for
 * nearby entities each time would double the work the game already does to answer the same question
 * about its own collision. There are never many machines in a level and they are kept in hand, so
 * the usual answer — there is not one anywhere near you — costs a walk down a list of three.
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class Hitboxes {
    /**
     * How much of a step up onto a machine is worth trying, as vanilla would: nothing at all unless
     * the mover is standing on something, so that nobody climbs a hull in mid-air.
     */
    private static final double NOTHING = 1.0E-7;

    /**
     * How near a machine's box something's feet have to be for it to be standing on it, in blocks.
     *
     * <p>A tenth, which is a little more than a tick of falling. Less than that and somebody standing
     * perfectly still is dropped by the ship every time the arithmetic rounds the other way; much
     * more and they are carried along by a deck they are hovering above.
     */
    private static final double CONTACT = 0.1;

    /**
     * No ground to scrape over: every block stops the shape, which is what being in the air means.
     * See {@link #throughBlocks(Entity, Hitbox, Vec3, double)}.
     */
    public static final double UNDERSIDE_NONE = Double.NEGATIVE_INFINITY;

    /**
     * The machines in each level.
     *
     * <p>Weakly by level, so that a level that has gone takes its list with it. Each list belongs to
     * the thread its level is ticked on — a client's and a server's are different levels and
     * different lists — and only the map they hang in is shared, which is what is guarded.
     */
    private static final Map<Level, Set<VehicleEntityBase>> MACHINES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private Hitboxes() {
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof VehicleEntityBase machine) {
            in(event.getLevel()).add(machine);
        }
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof VehicleEntityBase machine) {
            in(event.getLevel()).remove(machine);
        }
    }

    private static Set<VehicleEntityBase> in(Level level) {
        return MACHINES.computeIfAbsent(level, ignored -> Collections.newSetFromMap(new WeakHashMap<>()));
    }

    // ------------------------------------------------------------------
    // Being run into
    // ------------------------------------------------------------------

    /**
     * How much of a move the world's own collision has already allowed is left once the mod's
     * machines have had their say.
     *
     * <p>Given the move Minecraft has settled on rather than the one that was asked for, because
     * they are two separate obstacles and neither may hand back movement the other has taken away.
     * What comes out is what a player standing on a sloping deck is held up by and what stops one
     * walking into a hull.
     *
     * @param mover whatever is moving. Its own machine is not an obstacle to it, nor to anything
     *              riding in it, or a passenger would be shoved out of the seat they are sitting in
     * @param box where it is now
     * @param wanted the move as the world has already limited it
     */
    public static Vec3 limit(Entity mover, AABB box, Vec3 wanted) {
        if (wanted.lengthSqr() == 0.0) {
            return wanted;
        }

        // A machine is stopped by the shape it really has rather than by the plain box it is filed
        // under — an aeroplane setting down on a deck touches it with its wheels, not with the shed
        // Minecraft thinks it is. Anything else is the box it is, which for a player is the truth.
        List<Hitbox> mine = own(mover);
        AABB area = (mine.isEmpty() ? box : union(mine)).expandTowards(wanted);
        List<Hitbox> theirs = near(mover, area);

        if (theirs.isEmpty()) {
            return wanted;
        }

        if (!mine.isEmpty()) {
            return resolve(theirs, mine, wanted);
        }

        Vec3 allowed = resolve(theirs, box, wanted);

        if (allowed.x == wanted.x && allowed.z == wanted.z) {
            return allowed;
        }

        return step(mover, theirs, box, wanted, allowed);
    }

    /** The boxes the mover is itself made of, or none for anything that is not one of the machines. */
    private static List<Hitbox> own(Entity mover) {
        if (!(mover instanceof VehicleEntityBase machine)) {
            return List.of();
        }

        List<Hitbox> found = null;

        for (VehiclePart part : machine.getParts()) {
            Hitbox box = part.hitbox();

            if (box == null || part.isPylon()) {
                continue;
            }

            if (found == null) {
                found = new ArrayList<>();
            }

            found.add(box);
        }

        return found == null ? List.of() : found;
    }

    private static AABB union(List<Hitbox> boxes) {
        AABB all = boxes.get(0).reach();

        for (int i = 1; i < boxes.size(); i++) {
            all = all.minmax(boxes.get(i).reach());
        }

        return all;
    }

    /**
     * The boxes of every machine near enough to be worth testing.
     *
     * <p>Each machine's boxes are where they were last put, which is where the machine's own tick
     * left them. That is what the game does with its own multipart entities and it is what the
     * player sees drawn, so a shot and a footstep land on the same box.
     */
    private static List<Hitbox> near(Entity mover, AABB area) {
        Set<VehicleEntityBase> machines = MACHINES.get(mover.level());

        if (machines == null || machines.isEmpty()) {
            return List.of();
        }

        Entity riding = mover.getRootVehicle();
        List<Hitbox> found = null;

        for (VehicleEntityBase machine : machines) {
            AABB bounds = machine.placedBounds();

            if (machine == mover || machine == riding || machine.isRemoved()
                    || bounds == null || !bounds.intersects(area)) {
                continue;
            }

            for (VehiclePart part : machine.getParts()) {
                Hitbox box = part.hitbox();

                if (box == null || part.isPylon() || !box.reach().intersects(area)) {
                    continue;
                }

                if (found == null) {
                    found = new ArrayList<>();
                }

                found.add(box);
            }
        }

        return found == null ? List.of() : found;
    }

    /**
     * A move worked out one axis at a time, in the order Minecraft works its own out in — the
     * upright first, then whichever of the two flat ones is the shorter.
     *
     * <p>Which is not arbitrary. Settling the fall first is what lets something land on a surface
     * and then walk along it in the same tick, and taking the shorter of the two flat axes first is
     * what lets something slide along a wall it is pressed against rather than catching on it.
     */
    private static Vec3 resolve(List<Hitbox> boxes, AABB box, Vec3 wanted) {
        double x = wanted.x;
        double y = wanted.y;
        double z = wanted.z;

        if (y != 0.0) {
            y = along(boxes, box, y, 1);

            if (y != 0.0) {
                box = box.move(0.0, y, 0.0);
            }
        }

        boolean acrossFirst = Math.abs(x) < Math.abs(z);

        if (acrossFirst && z != 0.0) {
            z = along(boxes, box, z, 2);

            if (z != 0.0) {
                box = box.move(0.0, 0.0, z);
            }
        }

        if (x != 0.0) {
            x = along(boxes, box, x, 0);

            if (x != 0.0) {
                box = box.move(x, 0.0, 0.0);
            }
        }

        if (!acrossFirst && z != 0.0) {
            z = along(boxes, box, z, 2);
        }

        return new Vec3(x, y, z);
    }

    /**
     * The same for a mover that is itself made of boxes, which is every pair of shapes in the mod
     * meeting: an aeroplane against a deck, a deck against an aeroplane.
     *
     * <p>Every box of the one against every box of the other, which is as many tests as it sounds
     * and is why each pair is thrown out first if the two are nowhere near each other along the move.
     */
    private static Vec3 resolve(List<Hitbox> theirs, List<Hitbox> mine, Vec3 wanted) {
        double x = wanted.x;
        double y = wanted.y;
        double z = wanted.z;

        if (y != 0.0) {
            y = along(theirs, mine, y, 1);

            if (y != 0.0) {
                mine = shifted(mine, new Vec3(0.0, y, 0.0));
            }
        }

        boolean acrossFirst = Math.abs(x) < Math.abs(z);

        if (acrossFirst && z != 0.0) {
            z = along(theirs, mine, z, 2);

            if (z != 0.0) {
                mine = shifted(mine, new Vec3(0.0, 0.0, z));
            }
        }

        if (x != 0.0) {
            x = along(theirs, mine, x, 0);

            if (x != 0.0) {
                mine = shifted(mine, new Vec3(x, 0.0, 0.0));
            }
        }

        if (!acrossFirst && z != 0.0) {
            z = along(theirs, mine, z, 2);
        }

        return new Vec3(x, y, z);
    }

    private static double along(List<Hitbox> theirs, List<Hitbox> mine, double distance, int axis) {
        Vec3 motion = motion(distance, axis);
        double least = 1.0;

        for (Hitbox ours : mine) {
            AABB swept = ours.reach().expandTowards(motion);

            for (Hitbox hitbox : theirs) {
                if (!hitbox.reach().intersects(swept)) {
                    continue;
                }

                least = Math.min(least, hitbox.sweep(ours, motion));

                if (least == 0.0) {
                    return 0.0;
                }
            }
        }

        return distance * least;
    }

    private static List<Hitbox> shifted(List<Hitbox> boxes, Vec3 offset) {
        List<Hitbox> moved = new ArrayList<>(boxes.size());

        for (Hitbox box : boxes) {
            moved.add(box.move(offset));
        }

        return moved;
    }

    /** How much of a move along one axis is left once every box has been swept against. */
    private static double along(List<Hitbox> boxes, AABB box, double distance, int axis) {
        Vec3 motion = motion(distance, axis);
        double least = 1.0;

        for (Hitbox hitbox : boxes) {
            least = Math.min(least, hitbox.sweep(box, motion));

            if (least == 0.0) {
                return 0.0;
            }
        }

        return distance * least;
    }

    /**
     * One step up onto a machine, for something that was stopped by one and is standing on the
     * ground.
     *
     * <p>Minecraft does this for its own obstacles inside the move that has already happened by the
     * time any of this is asked, so a machine's boxes have to do it for themselves or a player would
     * be brought up short by the lip of a track they could have walked straight over. It is the same
     * move: lift by as much of the step as there is room for, try the flat move again from up there,
     * and settle back down as far as the way is clear.
     *
     * <p>The world is asked whether the lifted position is clear before it is taken, because nothing
     * here knows about blocks and stepping onto a track with a wall behind it must not put anybody
     * inside the wall.
     */
    private static Vec3 step(Entity mover, List<Hitbox> boxes, AABB box, Vec3 wanted, Vec3 allowed) {
        double reach = mover.maxUpStep();

        if (reach <= 0.0 || !(mover.onGround() || wanted.y < 0.0 && allowed.y != wanted.y)) {
            return allowed;
        }

        AABB from = box.move(0.0, allowed.y, 0.0);
        double lift = along(boxes, from, reach, 1);

        if (lift <= NOTHING) {
            return allowed;
        }

        AABB raised = from.move(0.0, lift, 0.0);
        Vec3 over = resolve(boxes, raised, new Vec3(wanted.x, 0.0, wanted.z));

        if (over.horizontalDistanceSqr() <= allowed.horizontalDistanceSqr()) {
            return allowed;
        }

        double settle = along(boxes, raised.move(over.x, 0.0, over.z), -lift, 1);
        Vec3 stepped = new Vec3(over.x, allowed.y + lift + settle, over.z);

        // Nothing above knows what the blocks are doing, and a step that ends inside one is worse
        // than a step that never happened.
        return mover.level().noCollision(mover, box.move(stepped)) ? stepped : allowed;
    }

    // ------------------------------------------------------------------
    // Being stood on while it moves
    // ------------------------------------------------------------------

    /**
     * Takes everything standing on a machine along with it.
     *
     * <p>Minecraft carries what is <em>sitting in</em> a vehicle and nothing else. Standing on one is
     * not a thing it has a notion of: a player on a deck is a player standing in mid-air as far as
     * the game is concerned, and the deck sliding out from under them is exactly what the game
     * expects to happen. For a carrier under way that is the whole of the problem, so it is done
     * here — every tick, whatever is resting on one of the machine's boxes is moved by however far
     * that machine has moved, and turned by however far it has turned.
     *
     * <p>Turned as well as moved, and turned <em>about the machine's middle</em>: a deck swinging
     * through ten degrees carries somebody standing at the bow a good deal further than somebody
     * amidships. Their own heading is brought round with it too, so that a player who was facing
     * along the deck is still facing along the deck afterwards rather than watching the ship rotate
     * away from under their feet.
     *
     * <p><b>Whose job it is.</b> Each side moves only what it is in charge of, which is what keeps
     * the two from fighting: a player's own client carries them and reports where they ended up, and
     * the server carries everything else and tells the clients. Both sides running it for everybody
     * is what makes a moving platform stutter and shove people about — the server's idea of the deck
     * and the client's are never quite the same, and whoever loses gets corrected twenty times a
     * second.
     *
     * <p><b>What is not a fall.</b> Being carried downwards is not falling, and both sides are told
     * so however the carrying itself is divided up. A deck that descends moves out from under
     * whoever is on it and is then put back, and to Minecraft's reckoning that is a tick of free
     * fall every tick: an aircraft letting down from cruise banks a hundred blocks of it under a man
     * who has not moved a muscle, and the moment the descent levels off it is all paid at once. The
     * distance has to be dropped on the server as well as on the client that owns the mover —
     * whether it kills anybody is the server's arithmetic, and it runs off the moves the client
     * reports rather than off the client's own tally.
     *
     * @param from where the machine's middle was when its boxes were last placed
     * @param shift how far it has come since
     * @param turn how far it has come round since, in degrees
     */
    static void carry(VehicleEntityBase machine, Vec3 from, Vec3 shift, float turn) {
        AABB bounds = machine.placedBounds();

        if (bounds == null) {
            return;
        }

        Vec3 now = machine.position();

        for (Entity rider : machine.level().getEntities(machine, bounds.inflate(1.0), Hitboxes::carriable)) {
            if (rider.getRootVehicle() == machine || !resting(machine, rider)) {
                continue;
            }

            rider.resetFallDistance();

            if (!owns(rider)) {
                continue;
            }

            // Where they were relative to the machine, brought round and put down again against
            // where the machine is now. One move rather than a turn and then a shift, so that
            // whatever is in the way stops them once instead of twice.
            Vec3 at = rider.position();
            Vec3 want = now.add(turned(at.subtract(from), turn));

            rider.move(MoverType.SELF, want.subtract(at));

            if (turn != 0.0F) {
                bringRound(rider, turn);
            }
        }
    }

    /**
     * Whether something is up against one of the mod's machines: standing on it, leaning on it, or
     * being shoved along by it.
     *
     * <p>Asked when somebody is about to be hurt by something a machine could have done to them.
     * A machine is a moving wall the game does not know is there, and everything that follows from
     * being pressed against a moving wall — being carried down and landing, being nudged into the
     * side of a hill, being crowded — arrives as ordinary damage with nothing on it to say where it
     * came from. Standing next to the thing at the moment it lands is as near an answer as there is,
     * and it is the right one often enough that the alternative — crews quietly dying of the
     * aeroplane they are riding on — is not worth keeping for the sake of it.
     *
     * <p>The whole of the mover's box, grown by the same whisker its feet are judged by, rather than
     * the feet alone: whoever a wing has just swept off the deck was never standing on anything.
     */
    public static boolean touching(Entity entity) {
        Set<VehicleEntityBase> machines = MACHINES.get(entity.level());

        if (machines == null || machines.isEmpty()) {
            return false;
        }

        AABB box = entity.getBoundingBox().inflate(CONTACT);
        Entity riding = entity.getRootVehicle();

        for (VehicleEntityBase machine : machines) {
            AABB bounds = machine.placedBounds();

            if (machine.isRemoved() || bounds == null || !bounds.intersects(box)) {
                continue;
            }

            if (machine == riding) {
                return true;
            }

            for (VehiclePart part : machine.getParts()) {
                Hitbox hitbox = part.hitbox();

                if (hitbox != null && !part.isPylon() && hitbox.overlaps(box)) {
                    return true;
                }
            }
        }

        return false;
    }

    /** Parts are the machine; passengers are the seat's business; the rest is worth asking about. */
    private static boolean carriable(Entity rider) {
        return !(rider instanceof VehiclePart) && !rider.isPassenger() && !rider.isRemoved();
    }

    /**
     * Which side moves this one.
     *
     * <p>A player is moved by their own client and nowhere else — the server takes their word for
     * where they are, and a server that also moved them would be arguing with the client that has
     * already done it. Everything else is moved by whichever side is in charge of it, which for an
     * aeroplane parked on the deck is its pilot's client if it has one and the server if it has not.
     */
    private static boolean owns(Entity rider) {
        if (rider instanceof Player player) {
            return player.level().isClientSide && player.isLocalPlayer();
        }

        return rider.isControlledByLocalInstance();
    }

    /**
     * Whether something has its feet on one of the machine's boxes.
     *
     * <p>Only the sliver of world its feet are in is asked about, rather than the whole of it.
     * Anything else would carry off whoever happened to be leaning against the hull from the
     * quayside, which is a different thing from standing on it.
     */
    private static boolean resting(VehicleEntityBase machine, Entity rider) {
        AABB box = rider.getBoundingBox();
        AABB feet = new AABB(box.minX, box.minY - CONTACT, box.minZ, box.maxX, box.minY + CONTACT, box.maxZ);

        for (VehiclePart part : machine.getParts()) {
            Hitbox hitbox = part.hitbox();

            if (hitbox != null && !part.isPylon() && hitbox.overlaps(feet)) {
                return true;
            }
        }

        return false;
    }

    /** Brings something's own heading round with the deck under it, and its head with it. */
    private static void bringRound(Entity rider, float turn) {
        rider.setYRot(rider.getYRot() + turn);
        rider.yRotO += turn;

        if (rider instanceof LivingEntity living) {
            living.yHeadRot += turn;
            living.yHeadRotO += turn;
            living.yBodyRot += turn;
            living.yBodyRotO += turn;
        }
    }

    /**
     * An offset swung about the upright, which is the only way a deck turns under anybody.
     *
     * <p>The way Minecraft's headings go round, which is not the way the arithmetic goes round if it
     * is written out without thinking: a heading winding on takes the nose from +Z towards −X, so a
     * man standing at the bow goes that way too and not the other.
     */
    static Vec3 turned(Vec3 offset, float degrees) {
        if (degrees == 0.0F) {
            return offset;
        }

        double angle = Math.toRadians(degrees);
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);

        return new Vec3(offset.x * cos - offset.z * sin, offset.y, offset.z * cos + offset.x * sin);
    }

    // ------------------------------------------------------------------
    // Being aimed at
    // ------------------------------------------------------------------

    /**
     * What a line aimed through the world hits first among the mod's machines, or null for one that
     * misses all of them.
     *
     * <p>Every shot, every crosshair. The machines' own boxes are not offered to the game's own
     * search at all — see {@link VehiclePart#isPickable} — so this is not a second opinion about the
     * same hit, it is the only one there is.
     *
     * @param looker whatever is aiming. Neither the machine it is riding in nor that machine's own
     *               boxes are in the way of it
     * @param margin how much to allow round the box, matching whatever the caller allows round
     *               everything else it is testing
     * @param filter the caller's own view of what is worth hitting
     */
    public static EntityHitResult pick(Level level, Entity looker, Vec3 from, Vec3 to, double margin,
            Predicate<Entity> filter) {
        Set<VehicleEntityBase> machines = MACHINES.get(level);

        if (machines == null || machines.isEmpty()) {
            return null;
        }

        AABB along = new AABB(from, to).inflate(margin);
        Entity riding = looker == null ? null : looker.getRootVehicle();
        VehiclePart nearest = null;
        Vec3 where = null;
        double closest = Double.MAX_VALUE;

        for (VehicleEntityBase machine : machines) {
            if (machine == looker || machine == riding || machine.isRemoved()) {
                continue;
            }

            for (VehiclePart part : machine.getParts()) {
                Hitbox box = part.hitbox();

                if (box == null || !box.reach().inflate(margin).intersects(along)
                        || !filter.test(part)) {
                    continue;
                }

                Vec3 hit = box.grow(margin).clip(from, to).orElse(null);

                if (hit == null) {
                    continue;
                }

                double distance = from.distanceToSqr(hit);

                if (distance < closest) {
                    closest = distance;
                    nearest = part;
                    where = hit;
                }
            }
        }

        return nearest == null ? null : new EntityHitResult(nearest, where);
    }

    // ------------------------------------------------------------------
    // Running into the world
    // ------------------------------------------------------------------

    /**
     * How far a machine may move before one of its own boxes runs into a block.
     *
     * <p>The same sweep as everything else, with the roles the other way round: the box is what is
     * moving and the block is what is standing still, which to the arithmetic is the block coming
     * the other way. Blocks are still asked of the world as blocks — they are the world, and they
     * really are upright boxes.
     */
    public static Vec3 throughBlocks(Entity machine, Hitbox hitbox, Vec3 motion) {
        return throughBlocks(machine, hitbox, motion, UNDERSIDE_NONE);
    }

    /**
     * The same, with a height below which the ground is scraped rather than run into.
     *
     * <p>For a machine that is standing on its wheels, the ground it is standing on is not an
     * obstacle — it is the floor, and the floor is already holding the machine up. Its own shape,
     * though, does not all sit above the wheels: an aeroplane rotating for takeoff puts its tail
     * below them, and swept against the runway in the ordinary way that tail is a wall. The
     * aeroplane is stopped dead by ground it is rolling along, which reads as flying into a
     * hillside and is treated as one.
     *
     * <p>So blocks that reach no higher than the wheels do not stop the shape at all. Nothing is
     * given away by it: the machine's own upright box sits on those same wheels and is settled
     * against the world by {@code move} in the usual way, so the floor still holds it up and a
     * descent onto it is still an arrival. What stops being possible is being brought up short by
     * ground that is underneath the undercarriage, which is the one thing the undercarriage is for.
     *
     * @param underside the height at or below which blocks are scraped over instead of hit, or
     *                  {@link #UNDERSIDE_NONE} for a machine in the air, which hits everything
     */
    public static Vec3 throughBlocks(Entity machine, Hitbox hitbox, Vec3 motion, double underside) {
        if (motion.lengthSqr() == 0.0) {
            return motion;
        }

        List<AABB> blocks = above(blocksAround(machine, hitbox.reach().expandTowards(motion)), underside);

        if (blocks.isEmpty()) {
            return motion;
        }

        double x = motion.x;
        double y = motion.y;
        double z = motion.z;

        if (y != 0.0) {
            y = through(blocks, hitbox, y, 1);
            hitbox = hitbox.move(new Vec3(0.0, y, 0.0));
        }

        boolean acrossFirst = Math.abs(x) < Math.abs(z);

        if (acrossFirst && z != 0.0) {
            z = through(blocks, hitbox, z, 2);
            hitbox = hitbox.move(new Vec3(0.0, 0.0, z));
        }

        if (x != 0.0) {
            x = through(blocks, hitbox, x, 0);
            hitbox = hitbox.move(new Vec3(x, 0.0, 0.0));
        }

        if (!acrossFirst && z != 0.0) {
            z = through(blocks, hitbox, z, 2);
        }

        return new Vec3(x, y, z);
    }

    private static double through(List<AABB> blocks, Hitbox hitbox, double distance, int axis) {
        Vec3 motion = motion(-distance, axis);
        double least = 1.0;

        for (AABB block : blocks) {
            least = Math.min(least, hitbox.sweep(block, motion));

            if (least == 0.0) {
                return 0.0;
            }
        }

        return distance * least;
    }

    /** Whether a box has room where it is standing, give or take a margin it may overlap by. */
    public static boolean clearOfBlocks(Entity machine, Hitbox hitbox, double margin) {
        return clearOfBlocks(machine, hitbox, margin, UNDERSIDE_NONE);
    }

    /**
     * The same, with a height below which blocks are the floor the machine is standing on rather
     * than world that has closed around it.
     *
     * <p>The same line {@link #throughBlocks(Entity, Hitbox, Vec3, double)} scrapes over, asked the
     * other question. A machine's shape does not all sit above its wheels: an aeroplane flaring for
     * a touchdown has its tail well below them, and a banked one has a wingtip there, so half a
     * metre of airframe is inside the runway on the way in to every landing. That is what standing
     * on the ground looks like from underneath, and it is not a machine the world has buried.
     *
     * @param underside the height at or below which blocks are floor, or {@link #UNDERSIDE_NONE} for
     *                  a machine in the air, which is inside anything it overlaps
     */
    public static boolean clearOfBlocks(Entity machine, Hitbox hitbox, double margin, double underside) {
        Hitbox room = hitbox.grow(-margin);

        for (AABB block : blocksAround(machine, room.reach())) {
            if (block.maxY > underside && room.overlaps(block)) {
                return false;
            }
        }

        return true;
    }

    /** The blocks of those that stand high enough to be worth stopping a machine's shape. */
    private static List<AABB> above(List<AABB> blocks, double height) {
        if (height == UNDERSIDE_NONE || blocks.isEmpty()) {
            return blocks;
        }

        List<AABB> found = new ArrayList<>(blocks.size());

        for (AABB block : blocks) {
            if (block.maxY > height) {
                found.add(block);
            }
        }

        return found;
    }

    /** Every block face worth testing against in a stretch of world, as plain boxes. */
    private static List<AABB> blocksAround(Entity machine, AABB area) {
        List<AABB> found = new ArrayList<>();

        for (VoxelShape shape : machine.level().getBlockCollisions(machine, area)) {
            found.addAll(shape.toAabbs());
        }

        return found;
    }

    private static Vec3 motion(double distance, int axis) {
        return switch (axis) {
            case 0 -> new Vec3(distance, 0.0, 0.0);
            case 1 -> new Vec3(0.0, distance, 0.0);
            default -> new Vec3(0.0, 0.0, distance);
        };
    }
}
