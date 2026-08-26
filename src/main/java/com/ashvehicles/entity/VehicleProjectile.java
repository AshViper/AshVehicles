package com.ashvehicles.entity;

import javax.annotation.Nullable;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.AshVehicles;
import com.ashvehicles.network.HitReportPayload;
import com.ashvehicles.particle.TintedParticleOption;
import com.ashvehicles.registry.ModParticles;
import com.ashvehicles.vehicle.Hitbox;
import com.ashvehicles.weapon.Impact;
import com.ashvehicles.weapon.Ricochet;
import com.ashvehicles.weapon.WeaponDefinition;
import com.ashvehicles.weapon.WeaponEffects;
import com.ashvehicles.weapon.WeaponMounts;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import net.neoforged.neoforge.event.EventHooks;

/**
 * Anything an aircraft's weapons send on their way: a cannon round, a rocket, a missile.
 *
 * <p>What they have in common is everything except how they fly. Each knows which weapon fired it
 * and looks its figures up by name every tick, so a retuned file changes what is already in the air;
 * each is owned by the pilot, so what it kills is credited to them; each passes through the aircraft
 * that fired it, parts and passengers included, since it leaves already overlapping the wing; and
 * each does the same thing where it lands.
 *
 * <p>Movement is checked as a line rather than a point. At the speeds these travel they would
 * otherwise skip clean through anything thinner than a tick's flight.
 *
 * <p>Subclasses provide the flying: {@link #steer()} is their one tick of it, called before the
 * move, and whatever they leave in the delta movement is where this goes next.
 */
public abstract class VehicleProjectile extends Projectile implements IEntityWithComplexSpawn {
    public static final ResourceKey<DamageType> DAMAGE_TYPE =
            ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "bullet"));

    /** The weapon that fired it, so a client knows what to draw and how it behaves. */
    private static final EntityDataAccessor<String> DATA_WEAPON =
            SynchedEntityData.defineId(VehicleProjectile.class, EntityDataSerializers.STRING);

    /**
     * The speed it left at, at full precision.
     *
     * <p>Sent as synched data because none of the packets that carry an entity's velocity can carry
     * this one: both the packet that spawns an entity and the one that corrects its motion clamp
     * every axis to 3.9 blocks a tick. That is fast for a snowball and a tenth of what leaves the
     * muzzle of a cannon, so a client is told 3.9, crawls forward that far, and is then dragged the
     * other forty by the next position packet — which is what a round warping across the sky is,
     * twenty times a second.
     *
     * <p>Told the truth instead, the client flies the round itself, at the right speed, and the
     * server's positions arrive to find it already there. See {@link #lerpTo}.
     */
    private static final EntityDataAccessor<Vector3f> DATA_LAUNCH =
            SynchedEntityData.defineId(VehicleProjectile.class, EntityDataSerializers.VECTOR3);

    /**
     * How far the server's idea of where a round is may differ from the client's before the client
     * is moved, in blocks.
     *
     * <p>There is normally nothing to correct: both sides fly the same round from the same figures
     * and arrive at the same place. What a tolerance buys is that the arithmetic never has to be
     * exactly identical — a round a metre out of place at forty blocks a tick is not something
     * anybody can see, and snapping it there is.
     */
    private static final double CORRECTION = 2.0;

    /**
     * How much of what is left of a correction is worked off each tick, as a fraction.
     *
     * <p>Worked off rather than jumped. What a correction usually says is that the client's round is
     * a tick or two of flight behind the server's, which is nobody's mistake -- the packet took that
     * long to arrive. Set straight in one move that is fifty blocks of teleport, several times a
     * second, and it is the whole of what a round warping across the sky looks like. Spread over the
     * next few ticks it is a few per cent on the speed for a quarter of a second, which is nothing
     * anyone can see, and the two copies end up in the same place either way.
     */
    private static final double CORRECTION_RATE = 0.25;

    /**
     * How much of a gap is put down to the packet being late rather than to anything being wrong, in
     * ticks of the round's own flight.
     *
     * <p>The plain figure above cannot answer this on its own. A position packet is written a tick or
     * more before it arrives and the round flies on in between, so the gap it appears to report is
     * mostly that delay: at thirty blocks a tick, one tick of it is thirty blocks, and a tolerance
     * measured in blocks alone calls that a disagreement and drags the round forward every time a
     * packet lands. Measured in ticks of flight it is what it is -- a round that is exactly where it
     * should be, slightly late -- and nothing is done about it. Which is the whole of what this
     * design wants: both sides fly the same round, and the server's positions are a check on that
     * rather than the thing the client is drawing.
     */
    private static final double CORRECTION_TICKS = 2.0;

    /**
     * A gap too big to be a late packet, in blocks: past this the client is simply somewhere else.
     *
     * <p>A missile that took a different turn, or one whose client copy lost track of its target,
     * does not want easing back onto the path -- it wants putting where it actually is. Working a gap
     * of that size off gently would draw a missile flying through the air sideways for a second.
     *
     * <p>The floor a slow round never needs to grow past, but a guided one does: a missile chasing a
     * target it only ever knows the reported, slightly-lagged position of steers a little differently
     * on each side every single tick, and that ordinary jitter -- nobody having actually taken a
     * different turn -- covers more ground the faster the round is going and the longer between the
     * packets that would otherwise pull it back in line (see {@code ROCKET}'s {@code updateInterval}
     * in {@code ModEntities}). Scaled by the round's own speed for the same reason the tolerance in
     * {@link #lerpTo} is, so a round that would have been well inside the old fixed figure at the
     * speeds this design was first tuned against goes on reading the same way once it is flying
     * several times as fast.
     */
    private static final double LOST_TICKS = 16.0;

    private static final double LOST_FLOOR = 96.0;

    /**
     * How far a round may be carried to put it back on the muzzle it left, in blocks.
     *
     * <p>The figure is a sanity limit rather than a tuning knob: see {@link #anchorToMuzzle}, where
     * the distance is the machine's own travel over one round trip and is a few tens of blocks for
     * an aeroplane and a few for a tank. Anything past this is not lag — it is a machine that has
     * been teleported, or a round paired to a client a long while after it was fired — and a round
     * is better left where the server put it than thrown a quarter of a mile to meet it.
     */
    private static final double ANCHOR_LIMIT = 256.0;

    /**
     * How old a round may be, in ticks, and still be put back on its muzzle.
     *
     * <p>Everyone who can already see the machine is paired to what it fires on the tracker's next
     * pass, which is the top of the tick after it left — see {@code ServerLevel.tick}, where the
     * chunk source runs before the entities do. So the clients this is for meet the round at an age
     * of nought or one. A client that a round flies <em>into</em> range of is paired to it whenever
     * that happens, and for that one the machine's position at the muzzle is ancient history that
     * has nothing to do with where the round is now. Two ticks is the case that matters and a tick
     * of slack, and is nowhere near the tens of ticks the other case arrives with.
     */
    private static final int ANCHOR_AGE = 2;

    /**
     * How much of the muzzle offset is worked off each tick, as a fraction of what is left.
     *
     * <p>Worked off at all because the offset is only wanted at one end of the flight. Leaving the
     * gun it is the whole point — see {@link #anchorToMuzzle} — but carried the whole way it puts
     * the round ahead of the one the server is flying, and it is the server's that decides where the
     * blast goes: a bomb held forward the whole way down would vanish a good distance short of its
     * own explosion. Eased off over the second or so after it leaves, the round starts where the
     * crew fired it and ends where it really lands, and neither end is wrong.
     */
    private static final double ANCHOR_RATE = 0.15;

    /**
     * And the most of the round's own step that may go into working it off, as a fraction of it.
     *
     * <p>Without a cap the first tick of a fast round pays off most of the offset at once, which
     * takes that much off the distance it covers: a tracer that stalls for a tick on its way out of
     * the barrel is a worse thing to look at than the offset it is paying for. A fifth of the step
     * is a round flying a fifth slow for as long as it takes, which is not something anybody can
     * see on a tracer.
     */
    private static final double ANCHOR_MOST = 0.2;

    /**
     * The least it is worked off by in a tick, in blocks. A share of what is left is a share that
     * never arrives; this is what finishes the last of it and lets the offset be put down for good.
     */
    private static final double ANCHOR_LEAST = 0.05;

    /**
     * How much a target's box is grown by before a round is measured against it, in blocks.
     *
     * <p>Not a figure of our own. It is what {@code ProjectileUtil.getEntityHitResult} inflates every
     * box by before clipping, and the tilted-box test in {@link #canHitEntity} has to use the same
     * one or the two disagree at the edges — a graze the game was about to count would be thrown out
     * by a test that measured a slightly smaller wing. If a Minecraft update moves vanilla's figure,
     * this one moves with it.
     */
    private static final double PICK_INFLATION = 0.3;

    /** How far a round is drawn, in blocks. Past the furthest any of them is tracked. */
    private static final double RENDER_RANGE = 640.0;
    /** As many puffs as one tick of flight will ever be worth, however fast it is going. */
    private static final int MAX_PUFFS = 8;
    /** How much of the missile's speed a puff of trail is left holding. */
    private static final double TRAIL_DRIFT = 0.06;
    /** How far off the flight path a puff of trail is laid, in blocks. */
    private static final double TRAIL_SCATTER = 0.06;
    /** Puffs of plume a tick while the motor burns. */
    /** TEMPORARY: how many ticks of a store's flight the trace above covers. */
    private static final int TRACE_TICKS = 8;
    private static final int EXHAUST_PUFFS = 3;
    /** How far behind the missile the plume reaches, as a fraction of one tick's flight. */
    private static final double EXHAUST_REACH = 0.45;
    /** How hard the plume is blown backwards out of the nozzle, in blocks a tick. */
    private static final double EXHAUST_BLOW = 0.22;
    /** And how far off the nozzle it is laid, in blocks. */
    private static final double EXHAUST_SCATTER = 0.04;

    /**
     * What fired this, which is the one thing it must not hit. Held as a plain entity rather than as
     * an aircraft: a round is the same round whether a wing or a turret sent it, and the only
     * question ever asked of this is whether the thing in front of the round is the thing behind it.
     */
    @Nullable
    private Entity firedFrom;
    private int firedFromId = -1;
    /**
     * Where the machine that fired this was standing at the moment it did.
     *
     * <p>Sent with the spawn and used for one thing only: putting the round back on the muzzle it
     * came out of, on whichever side is drawing that muzzle somewhere else. See
     * {@link #anchorToMuzzle}.
     */
    private Vec3 firedFromAt = Vec3.ZERO;
    /** Ticks since it left, which is what its lifetime is measured against. */
    protected int age;
    /**
     * How many times armour has thrown this round off. Server only: it is what is left of the
     * round's energy, and nothing on a client asks. See {@link Ricochet}.
     */
    private int deflections;
    /** The ground this round is holding open ahead of itself, if any. See {@link WeaponChunkLoader}. */
    private final WeaponChunkLoader.Hold hold = new WeaponChunkLoader.Hold();
    /**
     * The step the round last took, which is the one it is being drawn along right now.
     *
     * <p>Kept because a round is drawn somewhere between where it was and where it is, and the step
     * that carries it between those two is the one that has already been taken. Pointing the model
     * along the step it is <em>about</em> to take instead puts its nose a tick ahead of its body,
     * which on anything that turns reads as a wobble. See {@link #travel}.
     */
    private Vec3 lastTravel = Vec3.ZERO;

    /** What is left of the last correction to work off, in blocks. Client only; see {@link #settle}. */
    private Vec3 owed = Vec3.ZERO;

    /**
     * How far forward of the server's own copy this one is being flown, in blocks. Client only.
     *
     * <p>Laid on once, at the muzzle, and worked back off over the second or so afterwards: it is
     * the offset between the machine as <em>this</em> side is drawing it and the machine as the
     * server had it when the trigger went. See {@link #anchorToMuzzle} for why a round wants
     * carrying by it, {@link #mergeAnchor} for why it does not want carrying by it for long, and
     * {@link #lerpTo}, which has to take it off before it can measure anything the server says.
     */
    private Vec3 anchor = Vec3.ZERO;

    protected VehicleProjectile(EntityType<? extends VehicleProjectile> type, Level level) {
        super(type, level);
    }

    /**
     * @param weapon the weapon this came out of
     * @param vehicle what it was fired from, and so what it must not hit
     * @param crew who pulled the trigger, if anyone
     */
    public void setup(ResourceLocation weapon, Entity vehicle, @Nullable Entity crew) {
        this.entityData.set(DATA_WEAPON, weapon.toString());
        this.firedFrom = vehicle;
        this.firedFromId = vehicle.getId();
        this.firedFromAt = vehicle.position();
        this.setOwner(crew);
    }

    /**
     * The weapon's name and figures as last worked out, and what they were worked out from.
     *
     * <p>Both are held rather than found again every time, and it is worth a field each. The name
     * arrives over the wire as a string, so every ask was a string parsed and validated character by
     * character, and the figures behind it another name hashed and a map searched — and a round asks
     * for both on every tick of flight and again on every frame it is drawn. A cannon puts twenty
     * rounds a second into the air and each of them lives for seconds; that is thousands of parses a
     * second for an answer that cannot have changed.
     *
     * <p>Thrown away when it can have: the name whenever the synched string is set (see
     * {@link #onSyncedDataUpdated}), and the figures whenever a reload or a sync replaces the files.
     */
    @Nullable
    private ResourceLocation weaponId;
    @Nullable
    private WeaponDefinition weapon;
    private String weaponIdFrom = "";
    private int weaponVersion = -1;

    public ResourceLocation getWeaponId() {
        String raw = this.entityData.get(DATA_WEAPON);
        ResourceLocation cached = this.weaponId;

        if (cached != null && this.weaponIdFrom.equals(raw)) {
            return cached;
        }

        ResourceLocation id = ResourceLocation.tryParse(raw);

        if (id == null) {
            id = ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "unknown");
        }

        this.weaponId = id;
        this.weaponIdFrom = raw;
        this.weapon = null;

        return id;
    }

    public WeaponDefinition getWeapon() {
        ResourceLocation id = this.getWeaponId();
        WeaponDefinition current = this.weapon;

        if (current == null || this.weaponVersion != Definitions.version()) {
            current = Definitions.weapon(id);
            this.weapon = current;
            this.weaponVersion = Definitions.version();
        }

        return current;
    }

    public WeaponDefinition.Projectile getRound() {
        return this.getWeapon().projectile();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_WEAPON, "");
        builder.define(DATA_LAUNCH, new Vector3f());
    }

    /**
     * Sends the round on its way at the speed it leaves with.
     *
     * <p>Use this rather than setting the movement directly. It is the only thing that tells a
     * client how fast the round is really going; see {@link #DATA_LAUNCH}.
     */
    public void launch(Vec3 velocity) {
        this.setDeltaMovement(velocity);
        this.entityData.set(DATA_LAUNCH, velocity.toVector3f());
        this.launched(velocity);
    }

    /**
     * The moment the speed it left at is known, on whichever side has just learned it: on the server
     * when it is fired, on a client when the figure arrives with the spawn.
     *
     * <p>A hook rather than a constructor because a constructor runs long before there is anything to
     * read — the game builds the entity first and tells it what it is afterwards, and on a client it
     * does so from a packet. Anything a subclass has to work out from the direction it was sent in
     * belongs here, and is then worked out identically on both sides.
     */
    protected void launched(Vec3 velocity) {
    }

    /** A client learns the real speed here, with the rest of the round's spawn data. */
    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);

        if (DATA_LAUNCH.equals(key) && this.level().isClientSide) {
            Vector3f launch = this.entityData.get(DATA_LAUNCH);

            this.setDeltaMovement(launch.x(), launch.y(), launch.z());
            // So the very first frame draws it lying along its flight rather than along nothing.
            this.lastTravel = this.getDeltaMovement();
            this.launched(this.lastTravel);
        }
    }

    /**
     * How old the round already is when a client is first told about it.
     *
     * <p>Its age is what every part of a round's flight is measured against — how long the motor has
     * been unlit, how far up its thrust has worked, how heavy it is this tick — and both sides work
     * all of that out for themselves rather than being told each figure. That only holds while the
     * two agree about the age, and a client counting from nought does not agree: the spawn packet is
     * written before it lands, and the round flies on in between. So the client has the missile in
     * one part of its launch while the server has it in the next — the motor lights late, the thrust
     * works up late, and the gap between the two copies grows with every tick of the burn instead of
     * staying the fixed lag it ought to be.
     *
     * <p>Sent once, with the spawn, because that is the only moment at which the figure is not
     * already known. From there each side counts its own ticks and neither needs telling again.
     */
    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(this.age);
        // And what fired it, and where that was standing at the time. Neither reaches a client any
        // other way -- the owner is the crew rather than the machine, and nothing sends a machine's
        // position as of some earlier tick -- and between them they are the whole of what puts the
        // round back on its muzzle. See anchorToMuzzle.
        buffer.writeInt(this.firedFromId);
        buffer.writeDouble(this.firedFromAt.x);
        buffer.writeDouble(this.firedFromAt.y);
        buffer.writeDouble(this.firedFromAt.z);
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf buffer) {
        this.age = buffer.readVarInt();
        this.firedFromId = buffer.readInt();
        this.firedFromAt = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        // Read on the client with the entity already in the world and standing where the server put
        // it, which is the one moment at which this can be done at all.
        this.anchorToMuzzle();
    }

    /**
     * Puts the round back on the muzzle it left, as this side is drawing that muzzle.
     *
     * <p>Nothing is fired on a client. A round is spawned on the server, from the machine's position
     * <em>there</em>, and the client is told about it a round trip later — so by the time one appears
     * it is standing where the machine was when the trigger went, and the client has been drawing
     * that machine somewhere further on ever since. For the crew firing it that gap is the whole of
     * their own travel over the round trip: a tank a few blocks, an aeroplane at three hundred knots
     * the better part of a hundred. The round therefore appeared behind the machine and flew off
     * from there, which from the cockpit reads as a stream leaving at an angle to the nose, and for
     * a bomb — which leaves with the aircraft's speed and nothing else — as one thrown backwards out
     * of the bay.
     *
     * <p>The fix is to carry the round by exactly that gap: the machine's position now, less the
     * position it was fired from. The offset is measured on each side for itself and needs no figure
     * from anywhere. On the crew's own client it is the round trip's travel and the round leaves the
     * gun; on everyone else's it comes out at very nearly nothing, because an onlooker is drawing the
     * machine from the same packets that carried the round and the two are late by the same amount.
     * Nothing has to know whose round it is looking at.
     *
     * <p><b>And then given back.</b> The offset is wanted at the muzzle and nowhere else: it is the
     * server's copy that decides where the round goes off, so one held forward the whole way would
     * end its flight some distance short of its own explosion. {@link #mergeAnchor} eases it off
     * over the second or so after the round leaves, by which time the departure everybody was
     * looking at is over. While any of it is still on, {@link #lerpTo} takes it back off before
     * measuring anything, so the correction machinery goes on comparing like with like.
     */
    private void anchorToMuzzle() {
        Entity vehicle = this.firedFrom();

        if (vehicle == null || this.age > ANCHOR_AGE) {
            return;
        }

        Vec3 moved = vehicle.position().subtract(this.firedFromAt);
        double far = moved.length();

        // Nothing to do for an onlooker, and nothing sensible to do for a round whose machine has
        // been put somewhere else entirely since. See ANCHOR_LIMIT.
        if (far < 1.0E-4 || far > ANCHOR_LIMIT) {
            return;
        }

        Vec3 at = this.position().add(moved);

        this.anchor = moved;
        this.setPos(at.x, at.y, at.z);
        // Or the first frame draws it as a streak from where the server put it to where it has just
        // been carried, which is the whole of the gap in one line.
        this.setOldPosAndRot();
    }

    /**
     * What to make of the server's idea of where this round is.
     *
     * <p>Not simply moved there, which is what an entity ordinarily does. A round crosses up to
     * forty blocks in a tick, so being set to a position that is one tick stale is a forty-block
     * jump, and one that arrives a frame late is a forty-block jump back. The client is flying the
     * same round from the same figures and is already in the right place; a correction is only worth
     * taking when it says something the client did not already know.
     *
     * <p>So there are three answers rather than one, and which it is depends on the size of the gap.
     * Inside a tick or two of the round's own flight, nothing: that gap is how long the packet took
     * to arrive and not a disagreement about anything. Beyond that, the gap is worked off over the
     * ticks that follow rather than jumped -- see {@link #settle}, which is the whole of the fix for
     * a round that used to lurch forward every time one of these landed. Beyond {@link #LOST_TICKS}
     * it is put there outright, because at that distance the two are no longer flying the same round.
     *
     * <p>The rotation is left alone entirely: it is worked out afresh from the flight path every
     * tick, on both sides, so there is nothing the server can usefully say about it.
     */
    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        // Measured against where this round would be had it not been carried onto its muzzle, which
        // is the only position the server has ever heard of. Measured against the carried one, the
        // offset would read as a disagreement on every packet for the whole flight, and the round
        // would be dragged straight back off the muzzle it was just put on. See anchorToMuzzle.
        Vec3 gap = new Vec3(x, y, z).subtract(this.position().subtract(this.anchor));
        double off = gap.length();
        double lost = Math.max(LOST_FLOOR, this.getDeltaMovement().length() * LOST_TICKS);

        if (off > lost) {
            // Not a late packet: somewhere else entirely, so go there and have done with it. Carried
            // by the muzzle offset like everything else, or the next packet would read the offset
            // itself as another disagreement of exactly the same size.
            Vec3 at = new Vec3(x, y, z).add(this.anchor);

            this.setPos(at.x, at.y, at.z);
            this.owed = Vec3.ZERO;

            return;
        }

        // Anything inside a tick or two of flight is the packet's own age rather than a disagreement,
        // and is left alone. See CORRECTION_TICKS.
        double slack = Math.max(CORRECTION, this.getDeltaMovement().length() * CORRECTION_TICKS);

        this.owed = off > slack ? gap : Vec3.ZERO;
    }

    /**
     * Works off whatever is left of the last correction, a share of it a tick.
     *
     * <p>Only ever the position: the round goes on flying its own flight at its own speed, and this
     * is laid on top of it. So a round being eased back into place is drawn travelling a few per
     * cent faster than it really is for as long as it takes -- which is the whole point of doing it
     * this way rather than moving it there outright.
     *
     * <p>The step is counted into the round's travel as well, and has to be. A round is drawn lying
     * along the line from where it was to where it is, and once a correction has been laid on top
     * that line is no longer the flight alone -- so a nose pointed along the flight alone is pointed
     * off the line the body is being carried down, and the missile is drawn crabbing sideways
     * through the air for as long as the correction lasts. Counted in, the nose stays on the drawn
     * path whatever is being worked off underneath it. See {@link #travel}.
     */
    private void settle() {
        if (this.owed.lengthSqr() < 1.0E-6) {
            this.owed = Vec3.ZERO;

            return;
        }

        Vec3 step = this.owed.scale(CORRECTION_RATE);
        Vec3 at = this.position().add(step);

        this.setPos(at.x, at.y, at.z);
        this.lastTravel = this.lastTravel.add(step);
        this.owed = this.owed.subtract(step);
    }

    /**
     * Hands back a share of whatever the round is still being carried forward by, a share of it a
     * tick.
     *
     * <p>The other half of {@link #anchorToMuzzle}. The round was put on the muzzle it came out of
     * because that is where a crew watch one leave from; the server is flying the same round from
     * where it really left, and everything the server decides — what it hits, where the blast goes —
     * happens there. So the offset is given back over the ticks that follow and the two end up
     * flying the same round again.
     *
     * <p>Never at more than a fraction of the round's own step, which is what keeps this out of
     * sight: see {@link #ANCHOR_MOST}. The step is counted into the travel for the same reason
     * {@link #settle} counts its own in — the round is drawn along the line it is actually being
     * carried down, and a nose pointed anywhere else is a round drawn crabbing through the air.
     */
    private void mergeAnchor() {
        double far = this.anchor.length();

        if (far < 1.0E-4) {
            this.anchor = Vec3.ZERO;

            return;
        }

        // A share of what is left, capped by the round's own speed and floored so that the last of
        // it is actually paid off rather than halved for ever.
        double pace = Math.min(far * ANCHOR_RATE, this.getDeltaMovement().length() * ANCHOR_MOST);
        double take = Math.min(far, Math.max(pace, ANCHOR_LEAST));
        Vec3 step = this.anchor.scale(take / far);
        Vec3 at = this.position().subtract(step);

        this.setPos(at.x, at.y, at.z);
        this.lastTravel = this.lastTravel.subtract(step);
        this.anchor = this.anchor.subtract(step);
    }

    /** One tick of flying, before the move. Whatever this leaves in the delta movement is taken. */
    protected abstract void steer();

    /**
     * A last look before the move, for anything that goes off without touching what it was aimed at.
     *
     * @return where it should go off, or null to fly on
     */
    @Nullable
    protected Vec3 earlyDetonation() {
        return null;
    }

    /**
     * Keeps flying whether or not there is a world underneath.
     *
     * <p>An aircraft carries only the corridor it is flying down, so anything it fires is out over
     * ground nobody has loaded within a tick or two of leaving the rail. Vanilla stops ticking an
     * entity the moment its chunk stops ticking, and beyond the loaded world that is immediately:
     * without this a missile fired at altitude never moves again, and the weapons simply do not work
     * out there. Saying so here costs nothing — these are small, few and short-lived, and none of
     * them asks the world for anything it cannot answer.
     *
     * <p>Flying is one thing and hitting is another: a round out there also holds the ground in front
     * of it open, so that there is something to hit when it arrives. See {@link WeaponChunkLoader}.
     */
    @Override
    public boolean isAlwaysTicking() {
        return true;
    }

    @Override
    public void tick() {
        // Whether there is loaded ground under this tick's flight decides nearly everything below.
        // Out beyond it there is nothing to hit and nothing worth asking, and asking anyway is the
        // expensive mistake: on the server every block or fluid lookup out there generates the chunk
        // on the spot, on the main thread, so one missile crossing empty sky would carve a corridor
        // of new terrain thirty blocks a tick as it went.
        boolean overTheWorld = this.level().hasChunkAt(this.blockPosition());

        if (overTheWorld) {
            super.tick();
        } else {
            this.tickBeyondTheWorld();
        }

        WeaponDefinition.Projectile round = this.getRound();

        // Counted on both sides. It is not only the server's business how long a round has been
        // flying: how long a motor burns is measured against it, and the client is the one drawing
        // the plume and playing the note that stop when the motor does.
        this.age++;

        // Given up on once it has flown its range. What that means is the weapon's business: a round
        // simply stops existing, a rocket goes off. Only the server can make anything of it.
        if (!this.level().isClientSide && this.age > round.lifetime()) {
            this.expire();

            return;
        }

        // Steered on both sides, from the same figures, and before anything below is measured
        // against this tick's step. The server owns the flight, but a client that only coasts is
        // drawing a rocket that does not accelerate and a missile that does not turn, and is then
        // dragged back onto the real path by every position packet that arrives. Running the same
        // steering here leaves nothing to drag: the two agree between packets, and the round goes
        // where it is drawn going.
        this.steer();

        if (!this.level().isClientSide) {
            Vec3 fuse = this.earlyDetonation();

            if (fuse != null) {
                this.burst(fuse, null);

                return;
            }

            HitResult hit = this.strike();
            this.trace(hit);

            if (hit != null && hit.getType() != HitResult.Type.MISS && !EventHooks.onProjectileImpact(this, hit)) {
                this.onHit(hit);

                if (this.isRemoved()) {
                    return;
                }
            }
        } else {
            this.trace(null);
            this.spawnTrail();
        }

        this.fly();

        // Anything the server has said about where this really is, eased in behind the flight rather
        // than jumped to. See settle. And a share back of whatever the round was carried by to put
        // it on its muzzle in the first place, which is wanted at the muzzle and nowhere after it.
        if (this.level().isClientSide) {
            this.settle();
            this.mergeAnchor();
        }

        // Last, so the claim is made from where the round has got to rather than from where it was.
        // Only from here: taking a ticket re-enters the chunk system, which is safe from a tick and
        // is not safe from the callbacks it makes. See WeaponChunkLoader.
        WeaponChunkLoader.update(this, this.hold);
    }

    /**
     * TEMPORARY, for the report that a store dropped from an aircraft loses its speed on the way
     * off the rail. The first few ticks of anything that is not a gun round, from both sides: where
     * it is, how fast it is going, and what this tick's flight ran into — including whether what it
     * ran into was the aeroplane it came off, which is what {@code canHitEntity} is supposed to make
     * impossible. Remove once settled.
     */
    private void trace(@Nullable HitResult hit) {
        if (this.age > TRACE_TICKS || this.getWeapon().type() == WeaponDefinition.Type.GUN) {
            return;
        }

        Entity struck = hit instanceof EntityHitResult entityHit ? entityHit.getEntity() : null;
        Entity vehicle = this.firedFrom();

        AshVehicles.LOGGER.info("[flight] {} {} age={} at={} v={} |v|={} hit={} struck={} own={}",
                this.level().isClientSide ? "client" : "server", this.getWeaponId(), this.age,
                this.position(), this.getDeltaMovement(), this.getDeltaMovement().length(),
                hit == null ? "none" : hit.getType(),
                struck == null ? "-" : struck.getType().toShortString() + "#" + struck.getId(),
                struck != null && vehicle != null && WeaponMounts.isPartOf(vehicle, struck));
    }

    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        // Let go whether the round went off, ran out of life or was merely unloaded with the rest of
        // its chunk. Only let go: this also fires from inside the chunk system's own update loop,
        // and asking for a chunk in there re-enters that loop mid-iteration.
        WeaponChunkLoader.release(this, this.hold);
    }

    /**
     * One tick of flight: where this tick's speed takes it, and the drop taken off for the next.
     *
     * <p>Its own method because it is the round's flight and nothing else: no collision, no trail,
     * and nothing asked of the world. What a client lays on top of it afterwards -- see
     * {@link #settle} -- is then plainly a correction rather than part of the flight.
     */
    private void fly() {
        Vec3 velocity = this.getDeltaMovement();
        Vec3 next = this.position().add(velocity);

        this.setPos(next.x, next.y, next.z);
        this.lastTravel = velocity;
        this.setDeltaMovement(velocity.subtract(0.0, this.gravityNow(), 0.0));
        this.updateRotation();
    }

    /**
     * The little of an entity's ordinary tick that still means anything where there is no world.
     *
     * <p>What the base class spends its tick on is portals, fluids, fire and what it is standing in,
     * none of which exists out here, and all of which it finds out about by reading blocks at the
     * projectile's own position — which out here is not a cheap question but an expensive one, see
     * {@link #tick}. So it is not called, and the two or three lines that do still matter are done
     * by hand instead.
     */
    private void tickBeyondTheWorld() {
        this.xRotO = this.getXRot();
        this.yRotO = this.getYRot();
        this.firstTick = false;
        this.checkBelowWorld();
    }

    /**
     * What this tick's flight runs into, as a line rather than a point: at these speeds a projectile
     * would otherwise skip clean through anything thinner than a tick's travel.
     *
     * <p>Blocks are only asked about when the whole of the step is over loaded ground. Past that
     * edge the terrain has not necessarily been generated, so there is nothing there to hit that
     * anyone has ever seen, and the asking would generate it. Entities are still checked either way:
     * an aircraft out there is loaded, whatever the ground under it is doing, and is exactly what
     * something fired at long range is aimed at.
     */
    @Nullable
    private HitResult strike() {
        Vec3 from = this.position();
        Vec3 to = from.add(this.getDeltaMovement());

        if (this.spanIsLoaded(from, to)) {
            return ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
        }

        return ProjectileUtil.getEntityHitResult(this.level(), this, from, to,
                this.getBoundingBox().expandTowards(this.getDeltaMovement()).inflate(1.0),
                this::canHitEntity);
    }

    /** Whether every chunk the given step passes over is loaded. */
    private boolean spanIsLoaded(Vec3 from, Vec3 to) {
        AABB span = new AABB(from, to).inflate(1.0);

        return this.level().hasChunksAt(Mth.floor(span.minX), Mth.floor(span.minZ),
                Mth.ceil(span.maxX), Mth.ceil(span.maxZ));
    }

    /**
     * Which way the round is pointing at this instant between two ticks.
     *
     * <p>Not simply its velocity. A round is drawn along the line from where it was to where it is,
     * and what carried it along that line is the step it took last tick, not the one it will take
     * next. Blending from one to the other across the frame keeps the nose on the line the body is
     * travelling and turns it smoothly rather than in twenty steps a second.
     *
     * @param partialTick how far through the current tick the frame is
     */
    public Vec3 travel(float partialTick) {
        Vec3 next = this.getDeltaMovement();

        if (this.lastTravel.lengthSqr() < 1.0E-8) {
            return next;
        }

        return this.lastTravel.add(next.subtract(this.lastTravel).scale(partialTick));
    }

    /** What happens when it simply runs out of life. A round vanishes; a missile need not. */
    protected void expire() {
        this.discard();
    }

    /** Whether the motor is still pushing, which is when there is a plume as well as a trail. */
    protected boolean underPower() {
        return false;
    }

    /**
     * How hard this is pulled down this tick, in blocks per tick squared.
     *
     * <p>The weapon's own figure. Worked out afresh each tick and on both sides, from the age and
     * the file, so the two never disagree about where it has got to.
     */
    protected float gravityNow() {
        return this.getRound().gravity();
    }

    /**
     * Smoke behind a motor, drawn by each client for itself rather than sent one puff at a time.
     *
     * <p>It is laid along the path actually flown rather than dropped one puff a tick. A missile
     * covers twenty or thirty blocks between ticks, so a puff a tick is not a trail at all — it is a
     * row of dots with the whole of the interesting part in the gaps. Spacing them by distance
     * instead means the trail is the same density whether the missile is off the rail at walking
     * pace or running at its top speed, which is also the only way the trail behind an accelerating
     * one looks like anything.
     */
    protected void spawnTrail() {
        WeaponDefinition.Trail trail = this.getRound().trail().orElse(null);
        Vec3 step = this.getDeltaMovement();
        double flown = step.length();

        if (trail == null || flown < 1.0E-4) {
            return;
        }

        Vec3 head = this.position();
        RandomSource random = this.random;
        int puffs = Mth.clamp(Mth.ceil(flown * trail.density()), 1, MAX_PUFFS);
        // Once that cap bites — and at thirty blocks a tick it always does — what is left has to
        // cover more ground each, so it is made bigger. Which is what fast smoke does anyway: the
        // quicker it is laid down the more of it ends up in one place.
        float spread = (float) Mth.clamp(flown / puffs * 0.5, 1.0, 3.0);
        TintedParticleOption smoke = ModParticles.CONTRAIL.get().of(trail.colour(), trail.size() * spread);

        for (int i = 0; i < puffs; i++) {
            // Spread over the step with a little jitter, so successive ticks do not lay their puffs
            // down in the same places and turn the trail into a picket fence.
            Vec3 at = head.subtract(step.scale((i + random.nextDouble()) / puffs));
            // Each puff keeps a trace of the missile's own speed and then stops, which is what makes
            // the trail look drawn out behind it rather than printed there.
            Vec3 drift = step.scale(TRAIL_DRIFT / flown);

            this.level().addParticle(smoke,
                    at.x + random.nextGaussian() * TRAIL_SCATTER,
                    at.y + random.nextGaussian() * TRAIL_SCATTER,
                    at.z + random.nextGaussian() * TRAIL_SCATTER,
                    drift.x, drift.y, drift.z);
        }

        if (!this.underPower()) {
            return;
        }

        // And while the motor is burning there is the plume as well: what is coming out of the
        // nozzle this instant, close behind and still going the way the missile is.
        TintedParticleOption exhaust = ModParticles.MOTOR_SMOKE.get().of(trail.exhaust(), trail.size());

        for (int i = 0; i < EXHAUST_PUFFS; i++) {
            Vec3 at = head.subtract(step.scale(random.nextDouble() * EXHAUST_REACH));
            Vec3 blown = step.scale(-EXHAUST_BLOW / flown);

            this.level().addParticle(exhaust,
                    at.x + random.nextGaussian() * EXHAUST_SCATTER,
                    at.y + random.nextGaussian() * EXHAUST_SCATTER,
                    at.z + random.nextGaussian() * EXHAUST_SCATTER,
                    blown.x, blown.y, blown.z);
        }
    }

    /**
     * Not the aircraft it left, nor anything aboard it. The pilot is excluded by the owner check
     * already, but a passenger or a wing box is not, and this starts life inside the wing.
     *
     * <p>And not a box of an aeroplane this tick's flight does not actually pass through. What the
     * game offers as a target is the upright box each of an aircraft's boxes is carried around in,
     * and for a wing with sweep on it that box is very nearly square while the wing is a thin plate
     * across the diagonal — so half of what the game would count as the wing is empty sky in front
     * of it and behind it. Asked here, before anything is measured, the round is simply told that
     * the box it was going to hit is not where the aeroplane is.
     */
    @Override
    protected boolean canHitEntity(Entity target) {
        Entity vehicle = this.firedFrom();

        if (vehicle != null && WeaponMounts.isPartOf(vehicle, target)) {
            return false;
        }

        if (!super.canHitEntity(target)) {
            return false;
        }

        if (target instanceof VehiclePart part) {
            Vec3 from = this.position();

            return part.clip(from, from.add(this.getDeltaMovement()), PICK_INFLATION).isPresent();
        }

        return true;
    }

    @Nullable
    protected Entity firedFrom() {
        if (this.firedFrom == null && this.firedFromId >= 0) {
            this.firedFrom = this.level().getEntity(this.firedFromId);
        }

        return this.firedFrom;
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        if (this.thrownOff(hit)) {
            // Not a hit at all: it skidded off and is still in the air. Nothing is hurt and nothing
            // goes off, and the round goes on to whatever is next in front of it.
            return;
        }

        super.onHitEntity(hit);
        DamageSource source = new DamageSource(
                this.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(DAMAGE_TYPE),
                this, this.getOwner());

        // What it is still worth rather than what it left the barrel worth. The two are the same for
        // every round that has come straight here, which is nearly all of them.
        float damage = this.getRound().damage() * Ricochet.energy(this.deflections);

        hit.getEntity().hurt(source, damage);
        // Told to whoever fired it, and to nobody else. At the range these are used at, this is the
        // only way the gunner learns whereabouts on the target the round went. See HitReportPayload.
        HitReportPayload.report(this.getOwner(), hit.getEntity(), hit.getLocation(),
                this.getDeltaMovement(), damage, false);
        this.struck(hit);
        this.burst(hit.getLocation(), null, hit.getEntity());
    }

    /**
     * The noise a round makes going into one of the mod's own boxes.
     *
     * <p>Played here rather than in {@link #burst}, which is the other end of every round's life and
     * cannot tell what it arrived in: a shot into a hillside and a shot into a turret front are the
     * same call there, and only one of them is a hit. So this asks what was struck, and asks it at
     * the one moment the answer is still to hand.
     *
     * <p>Only what has no blast of its own, and only against a machine. Anything that goes off where
     * it lands is already heard going off, and a clang over the top of that says nothing the bang
     * did not; and a round into the ground has the block's own debris rather than a strike on plate.
     * What is left is exactly the case that used to be silent — an armour-piercing shot or a burst
     * of machine-gun fire arriving on a tank. See {@link Impact}.
     */
    private void struck(EntityHitResult hit) {
        if (!(this.level() instanceof ServerLevel level)
                || this.getRound().explosion() > 0.0F
                || !isMachine(hit.getEntity())) {
            return;
        }

        Vec3 at = hit.getLocation();

        // Named after the weapon, so a pack can record one gun's strike without recording them all;
        // a client with neither falls back on the mod's. The reach goes in the volume slot for the
        // reason it does everywhere here: that slot is the only thing deciding who is told about the
        // sound at all, and a hit is worth hearing from further off than thirty-two blocks.
        level.playSound(null, at.x, at.y, at.z,
                SoundEvent.createVariableRangeEvent(Impact.soundFor(this.getWeaponId())),
                SoundSource.NEUTRAL, Impact.SOUND_SETUP.packetVolume(), Impact.SOUND_SETUP.pitch());
    }

    /** Whether this is one of the mod's machines, struck either on a box of its own or on itself. */
    private static boolean isMachine(Entity target) {
        return target instanceof VehicleEntityBase
                || target instanceof VehiclePart part && part.getParent() instanceof VehicleEntityBase;
    }

    /**
     * Whether the armour throws this round off instead of letting it in — and if it does, sends it
     * on its way.
     *
     * <p>Only armour throws anything off, and only a machine says whether it is any: a wing is not,
     * however thick, and neither is a player nor a cow. See {@link VehicleEntityBase#isArmoured}.
     *
     * <p>The angle it struck at is not read from a file anywhere. It is measured against the plate
     * as the plate is actually lying, which is the whole reason the boxes are the mod's own rather
     * than the game's: a hull turned to meet the fire really is presenting a shallower face, and a
     * crew who angle theirs are rewarded by the geometry rather than by a rule about angling.
     *
     * <p>Thrown off, the round is put on the plate it struck, given the new line, and left flying.
     * {@link #launch} rather than a plain change of speed, because every client is flying this round
     * for itself from the figure it was given at the muzzle and that figure has just stopped being
     * true; setting it again is the one thing that reaches them. See {@link #DATA_LAUNCH}.
     */
    private boolean thrownOff(EntityHitResult hit) {
        WeaponDefinition.Projectile round = this.getRound();

        if (!round.canRicochet() || this.deflections >= Ricochet.MOST
                || !(this.level() instanceof ServerLevel level)
                || !(hit.getEntity() instanceof VehiclePart part)
                || !(part.getParent() instanceof VehicleEntityBase machine)
                || !machine.isArmoured()) {
            return false;
        }

        Hitbox plate = part.hitbox();

        if (plate == null) {
            return false;
        }

        Vec3 at = hit.getLocation();
        // Measured against the same box the hit was found on, margin and all. A face named from a
        // box a third of a block smaller is the neighbouring face anywhere near an edge, and the
        // neighbouring face of a hull is at right angles to the one the round actually struck.
        Vec3 normal = plate.grow(PICK_INFLATION).normalAt(at);
        Vec3 velocity = this.getDeltaMovement();

        if (!Ricochet.thrownOff(velocity, normal, round, machine.armour(), this.random)) {
            return false;
        }

        Vec3 away = Ricochet.away(velocity, normal, this.random);

        // Reported before the round is turned round, so that the mark is drawn against the line it
        // came in on rather than the one it left on. A ricochet is worth telling the gunner about
        // for its own sake: from behind the sight it looks exactly like a miss, and the answer to
        // one of those is to fire again at the same place.
        HitReportPayload.report(this.getOwner(), hit.getEntity(), at, velocity, 0.0F, true);
        this.deflections++;
        // Clear of every margin a hit is allowed, or the round is thrown off the same plate from the
        // same place next tick and every tick after. See Ricochet.CLEARANCE.
        this.setPos(at.add(normal.scale(PICK_INFLATION + Ricochet.CLEARANCE)));
        this.launch(away);

        WeaponEffects.ricochet(level, at, away, round);
        // Named after the weapon, so a pack can record one gun's clang without recording them all;
        // a client with neither falls back on the mod's. See com.ashvehicles.client.sound.ModSounds.
        level.playSound(null, at.x, at.y, at.z,
                SoundEvent.createVariableRangeEvent(Ricochet.soundFor(this.getWeaponId())),
                SoundSource.NEUTRAL, Ricochet.SOUND_SETUP.packetVolume(), Ricochet.SOUND_SETUP.pitch());

        return true;
    }

    @Override
    protected void onHitBlock(BlockHitResult hit) {
        super.onHitBlock(hit);
        this.burst(hit.getLocation(), this.level().getBlockState(hit.getBlockPos()));
    }

    /**
     * What it does where it lands: the fire and the smoke and whatever it knocked off, and a blast
     * if it carries one.
     *
     * <p>The two are separated on purpose. Out beyond the loaded world the blast itself does not
     * happen — it would be an explosion nobody asked for, tearing up ground that has not been
     * generated yet, and generating it on the server thread in order to do so. But it is still seen:
     * the effect goes out to everyone within range whether or not there is a world under it, because
     * a pilot who has just put a missile into something four hundred blocks away has every right to
     * watch it go off, and a bang with nothing to show for it is the same as a miss.
     */
    protected void burst(Vec3 where, @Nullable BlockState struck) {
        this.burst(where, struck, null);
    }

    /**
     * The same, told what it went into.
     *
     * <p>Which matters to exactly one thing: a round with no blast of its own arriving on armour
     * gets a flash and a spray of sparks off the plate rather than the handful it would get going
     * into a hillside — see {@link WeaponEffects#strike}. It is the same case, and the same
     * reasoning, as the noise in {@link #struck}: the two rounds a tank actually fires carry nothing
     * that goes off, so without this the hit that decides the fight is the quietest and dimmest
     * thing on the screen.
     *
     * @param into what it arrived in, or null for a round that met the ground or ran out of fuse
     */
    protected void burst(Vec3 where, @Nullable BlockState struck, @Nullable Entity into) {
        WeaponDefinition.Projectile round = this.getRound();

        if (this.level() instanceof ServerLevel level) {
            WeaponEffects.detonation(level, where, this.getDeltaMovement(), round, struck, isMachine(into));

            if (round.explosion() > 0.0F && level.hasChunkAt(BlockPos.containing(where))) {
                WeaponEffects.blast(level, this, where, round);
            }
        }

        this.discard();
    }

    /**
     * Drawn as far out as anything is ever sent. There is no point being stricter here than the
     * server is: it stops reporting a round at its own tracking range, and everything that does
     * arrive is worth drawing. A tracer or a missile is meant to be seen from a long way off.
     */
    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < RENDER_RANGE * RENDER_RANGE;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.entityData.set(DATA_WEAPON, tag.getString("Weapon"));
        this.age = tag.getInt("Age");
        this.deflections = tag.getInt("Deflections");
        this.firedFromId = tag.contains("FiredFrom") ? tag.getInt("FiredFrom") : -1;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("Weapon", this.entityData.get(DATA_WEAPON));
        tag.putInt("Age", this.age);
        tag.putInt("Deflections", this.deflections);
        tag.putInt("FiredFrom", this.firedFromId);
    }
}
