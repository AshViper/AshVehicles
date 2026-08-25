package com.ashvehicles.entity;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.particle.Effects;
import com.ashvehicles.sensor.Sensors;
import com.ashvehicles.weapon.TargetLock;
import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.vehicle.Hitbox;
import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.vehicle.VehicleShape;
import com.ashvehicles.vehicle.WreckEffects;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaternionf;

/**
 * What every machine in the mod has in common, whether it flies or drives.
 *
 * <p>Not the physics. An aeroplane chooses its attitude and holds itself up; a tank lies on the
 * ground and is pushed along it, and the two have almost nothing to say to each other about how they
 * move. What they share is everything <em>around</em> that: they are both made of boxes rather than
 * of one square hitbox, both worth a few hundred points rather than a boat's four, both climbed into
 * and sat in, and both described by a file found under their own name.
 *
 * <p>Four things live here.
 *
 * <p><b>The boxes.</b> Minecraft gives an entity a single upright box with a square footprint, which
 * is no shape for an aeroplane and no shape for a tank, and no surface to walk on either. Both are
 * instead made of {@link VehiclePart}s placed from the boxes in their file. Where each box is and which
 * way it is turned is the one part of this that differs — an aircraft's boxes are bolted to the
 * airframe, a tank's may be carried round by its turret — so that is a pair of hooks and the rest is
 * shared.
 *
 * <p><b>The damage.</b> Health, and a blow counted once however many boxes it arrived through.
 *
 * <p><b>The seats.</b> Where the crew sit, in the machine's own axes, and how many of them fit.
 *
 * <p><b>The name.</b> A machine's id is its entity type's id, and its file, its model, its shape and
 * the item that places it are all found under it.
 */
public abstract class VehicleEntityBase extends VehicleEntity implements PartHost {
    /** What this machine has left, in hit points. Zero is a smoking hole. */
    private static final EntityDataAccessor<Float> DATA_HEALTH =
            SynchedEntityData.defineId(VehicleEntityBase.class, EntityDataSerializers.FLOAT);

    /**
     * Whether the machine has been written off.
     *
     * <p>Synched because being a wreck is most of what the thing looks like: a client draws a
     * burnt-out machine charred and a live one in its colours, and it has no other way of telling
     * them apart. The server owns the flag, and nothing but {@link #wreck()} ever sets it.
     */
    private static final EntityDataAccessor<Boolean> DATA_WRECKED =
            SynchedEntityData.defineId(VehicleEntityBase.class, EntityDataSerializers.BOOLEAN);

    /**
     * Which crew member is in which seat, so the two can come apart.
     *
     * <p>Vanilla has no such notion: a rider's seat is only their place in the passenger list, the
     * order they climbed in, and there is no way to change it without getting out and letting
     * somebody else in first. This decouples the two — a synched line of occupant ids, one per
     * seat, empty where a seat is free — so a crew member can move to another seat, the driver's
     * among them, without anybody leaving. The server owns it; both sides read it, because a seat
     * decides where a rider is drawn ({@link #getPassengerAttachmentPoint}) and who is at the
     * controls ({@link #getControllingPassenger}), and those are asked on both. See
     * {@link #switchToNextSeat}.
     *
     * <p>The wire form is the occupants' {@link UUID}s in seat order, comma-separated, with an empty
     * field for an empty seat: {@code "u0,,u2"} is a driver in seat 0, seat 1 free, a rider in
     * seat 2.
     */
    private static final EntityDataAccessor<String> DATA_SEATS =
            SynchedEntityData.defineId(VehicleEntityBase.class, EntityDataSerializers.STRING);

    /** What a machine with no file at all is worth, so that the first scratch does not finish it. */
    public static final float DEFAULT_HEALTH = 300.0F;

    /**
     * Hit points a single iron ingot stands for, for a machine whose file does not price its own
     * scrap. A three-hundred-point airframe comes to thirteen ingots, which is a machine's worth of
     * metal without being a reason to build aeroplanes in order to break them.
     */
    private static final float HEALTH_PER_INGOT = 24.0F;

    /**
     * How loud a fist on the skin is, and how high it rings.
     *
     * <p>Quiet, and higher than the metal both halves of it are borrowed from. What is wanted is the
     * sound of somebody finding out the thing is made of steel, not the sound of a forge.
     */
    private static final float KNUCKLE_VOLUME = 0.45F;
    private static final float KNUCKLE_PITCH = 1.5F;

    /** The boxes this machine is made of, built once in the constructor. See {@link #buildParts}. */
    /**
     * How far a machine may have moved in one tick and still be taken to have travelled there rather
     * than to have been put there. Beyond it, whoever is standing on the deck stays where they are.
     */
    private static final double CARRY_LIMIT = 32.0;

    protected VehiclePart[] parts = new VehiclePart[0];
    /** Where all of the boxes were, last time they were placed. See {@link #placedBounds}. */
    @Nullable
    private AABB placed;
    /** Where the machine itself was then, and which way it was pointing. See {@link #carryStanders}. */
    @Nullable
    private Vec3 carriedFrom;
    private float carriedHeading;

    /** The last blow taken, so one that arrives through several boxes at once only lands once. */
    @Nullable
    private DamageSource lastHurtSource;
    private long lastHurtTime = Long.MIN_VALUE;

    /**
     * How long this has been a wreck, in ticks, and how hard it was still moving last tick.
     *
     * <p>The age is what the fire burns down from, and it is written into the world with everything
     * else: a wreck left overnight is cold in the morning rather than freshly alight. The other two
     * are only wanted between one tick and the next -- they are how the moment a falling wreck
     * arrives is noticed at all, which is the tick its speed stops being a speed.
     */
    private int wreckAge;
    private boolean wasFalling;
    private double fallSpeed;

    /** The name, which cannot change: an entity's type is settled when it is made. */
    @Nullable
    private ResourceLocation vehicleId;
    /** The boxes under that name, and which set of files they came out of. */
    @Nullable
    private VehicleShape shape;
    private int shapeVersion = -1;

    /** What the machine is carrying inside it, and what its ground crew rearm it out of. */
    private final VehicleHold hold = new VehicleHold(this);

    protected VehicleEntityBase(EntityType<?> type, Level level) {
        super(type, level);
    }

    // ------------------------------------------------------------------
    // What the machine is
    // ------------------------------------------------------------------

    /**
     * This machine's id, which is its entity type's id. Everything else about it, from its file to
     * its model to the item that places it, is found under the same name.
     */
    public ResourceLocation getVehicleId() {
        ResourceLocation id = this.vehicleId;

        if (id == null) {
            id = BuiltInRegistries.ENTITY_TYPE.getKey(this.getType());
            this.vehicleId = id;
        }

        return id;
    }

    /**
     * The boxes this machine is made of.
     *
     * <p>Held rather than looked up afresh, but only for as long as the files stand still: the copy
     * is thrown away the moment {@link Definitions} reports a different version, so a {@code /reload}
     * still takes effect on machines that are already out there.
     */
    public VehicleShape getShape() {
        VehicleShape current = this.shape;

        if (current == null || this.shapeVersion != Definitions.version()) {
            current = Definitions.shape(this.getVehicleId());
            this.shape = current;
            this.shapeVersion = Definitions.version();
            this.onShapeChanged();
        }

        return current;
    }

    /** Called whenever a reload has handed the machine a different set of boxes. */
    protected void onShapeChanged() {
    }

    /**
     * What the engine sounds like, from the machine's own file.
     *
     * <p>Declared here so that {@code EngineSounds} can be given a machine rather than an aeroplane.
     * A tank's engine and a jet's want exactly the same three things said about them — which
     * recording, how loud, and how far it carries — and there was never a reason for the code that
     * plays one to know which it had.
     */
    /**
     * The chassis figures every machine has, whatever it is: how big its plain box is, how far it is
     * tracked, and how far it goes on being reported once it is past the loaded world.
     *
     * <p>Asked by {@code EntityTrackingMixin}, which has no business knowing whether the thing it is
     * deciding about flies or drives — only that a machine is worth hearing about further away than
     * a cow is.
     */
    /**
     * Ticks wherever it is, but only on a client.
     *
     * <p>A machine beyond the world the player has loaded is still being sent, and is still drawn as
     * a ghost, but the client stops ticking anything whose chunk it does not have — and one that is
     * not ticked never runs the interpolation that the position packets feed, so what is drawn out
     * there is a contact frozen at the moment it crossed the edge. Saying it always ticks is what
     * keeps it moving. It is the same answer for a tank as for an aeroplane: the turret of a frozen
     * ghost is the most misleading thing on the screen.
     *
     * <p>Emphatically not on the server. Out there a machine ticks because something is holding its
     * chunk open; ticking anyway would run the physics over ground the server has not loaded, and
     * every block it asked about would be generated on the spot to answer.
     */
    @Override
    public boolean isAlwaysTicking() {
        return this.level().isClientSide;
    }

    /**
     * The one thing every machine does whatever else it is doing: if it is a wreck, it burns.
     *
     * <p>Here rather than in each machine's own tick because it is the same fire either way, and
     * because both of them call up to this on their way through. Server side alone: every one of
     * these effects is a particle packet or a sound, and a client that made its own would draw the
     * fire twice for anybody who could see it.
     *
     * <p>Run before the machine has moved this tick, which is what puts the smoke a wreck leaves
     * behind it rather than in front of it.
     */
    @Override
    public void tick() {
        super.tick();

        if (!this.isWrecked() || !(this.level() instanceof ServerLevel level)) {
            return;
        }

        this.wreckAge++;

        // The delta movement rather than getVelocity(). This runs before the machine has moved this
        // tick, and getVelocity() on the side running the physics is measured from how far it has
        // got -- which at this point in the tick is nowhere, every tick. What the delta holds here is
        // what last tick left it at, which is exactly how the wreck is falling.
        Vec3 velocity = this.getDeltaMovement();
        // Still coming down, which is not the same as still moving. A write-off carries its speed
        // into the ground and ploughs along it, so a wreck that touched down at the top of a field
        // can still be travelling at the bottom of it — and the plume belongs where it first hit,
        // not where the skid ran out.
        boolean falling = velocity.lengthSqr() > WreckEffects.FALLING && !this.onGround();
        double reach = this.reach();

        if (falling) {
            // The fastest it managed on the way down, not the speed it happens to have as it stops.
            // A wreck skids for a few ticks after it lands, and the impact is worth the height it
            // fell from rather than the last of the slide.
            this.fallSpeed = Math.max(this.fallSpeed, velocity.length());
        } else if (this.wasFalling) {
            WreckEffects.impact(level, this.position(), this.fallSpeed, reach);
            this.fallSpeed = 0.0;
        }

        this.wasFalling = falling;

        WreckEffects.burn(level, this.position(), this.getAttitude(), this.wreckAge, velocity, reach);
    }

    public abstract VehicleChassis.Hitbox hitbox();

    public abstract VehicleChassis.Sound soundSetup();

    /**
     * {@code engine.<vehicle>}: the root of everything a machine's engine is recorded under.
     *
     * <p>Named on this side rather than with the rest of the sounds because the server has to be
     * able to ask for one, and the server has never seen a resource pack. See
     * {@code ModSounds}, which is where the client end of the same name lives.
     */
    public static final String SOUND_PREFIX = "engine.";

    /** How hard the engine is working, in [0, 1]: what the note is pitched and faded from. */
    public abstract float getEngineNote();

    /**
     * How much reheat the engine is delivering, in [0, 1].
     *
     * <p>Nothing at all for almost everything: a tank has no afterburner and neither has a
     * helicopter. It is asked for out here rather than on the aircraft because the engine note is,
     * and what the burner does to that note is the same question as how hard the engine is working.
     * See {@code EngineSoundInstance}.
     */
    public float getAfterburner() {
        return 0.0F;
    }

    /** How fast the machine is really going, in blocks a tick, on whichever side is asking. */
    public abstract Vec3 getVelocity();

    /** The radar and the warning receiver. Server side; whoever is at the controls is sent the picture. */
    private final Sensors sensors = new Sensors(this);

    /**
     * What this machine can see of everything else, and who can see it.
     *
     * <p>On the base rather than on the aircraft because both kinds of machine want the same
     * instrument, and because they want it about <em>each other</em>: an aeroplane's warning
     * receiver has to be able to hear a launcher on the ground looking at it, which it can only do
     * if a launcher on the ground has a radar that is the same sort of thing. A machine whose file
     * gives it neither a set nor a receiver sweeps for nothing and costs nothing.
     */
    public Sensors getSensors() {
        return this.sensors;
    }

    /**
     * Which way this machine's weapons point.
     *
     * <p>Not the same question as which way it is <em>lying</em>. An aeroplane aims by pointing
     * itself, so this is the nose; a machine with a turret aims by traversing, so it is the bore and
     * has nothing to do with the hull. Everything that has to know where the weapons are looking —
     * the seeker, the radar's own cone, the sight — asks here rather than picking one of the two,
     * which is what lets all three work on either kind of machine.
     */
    public abstract Vec3 getAimDirection(float partialTick);

    /** What the machine's file says it can see with. {@link VehicleChassis.Radar#NONE} for most. */
    public abstract VehicleChassis.Radar radar();

    /**
     * What the seeker is holding, or null for a machine with no seeker at all.
     *
     * <p>There is at most one per machine however many weapons it carries: a seeker is a thing that
     * looks, not a thing that is fired, and the crew have one pair of eyes. Where it actually lives
     * is each machine's own business — an aircraft keeps it with its pylons, a launcher with its
     * tubes — and nothing out here needs to know which.
     */
    @Nullable
    public TargetLock lock() {
        return null;
    }

    /**
     * Which way the machine is lying, as a rotation rather than as three angles.
     *
     * <p>Heading, elevation and bank are how Minecraft describes a mob's facing, and they are fine
     * for something that stays the right way up. Neither of these does: an aeroplane at the top of a
     * loop is inverted and pointing backwards, and a tank across a slope has a roll Minecraft has no
     * field for at all. A rotation has no such seam.
     *
     * <p>{@code attitudeO} is what it was at the end of the tick before, so that anything drawn
     * between two ticks can be interpolated. How the attitude gets its value is each machine's own
     * business — one integrates it from the controls, the other builds it from the ground.
     */
    protected Quaternionf attitude = new Quaternionf();
    protected Quaternionf attitudeO = new Quaternionf();

    /**
     * Puts a machine nobody is flying or driving into a given pose, for the instruments that draw
     * one.
     *
     * <p>There is a copy of every machine the mod has that exists only to be drawn: the hit readout
     * needs a picture of whatever a round arrived on, and at the range these are fired at that thing
     * is usually well outside the client's own world. So one is made from the entity type, never
     * added to anything, and posed from what the server said — which is this.
     *
     * <p>Both the current attitude and the previous one, because a machine that is never ticked has
     * no previous one and the renderer interpolates between the two. Setting only the first would
     * draw it halfway back to whatever it was built at.
     *
     * @param turret where the turret is laid, ignored by anything that has none
     * @param gun how far the gun is elevated, ignored by anything that has none
     */
    public void poseForDrawing(Quaternionf hull, float turret, float gun) {
        this.attitude = new Quaternionf(hull);
        this.attitudeO = new Quaternionf(hull);
    }

    public Quaternionf getAttitude() {
        return this.attitude;
    }

    /** The same at a moment between two ticks, for anything drawing it. */
    public Quaternionf getAttitude(float partialTick) {
        return new Quaternionf(this.attitudeO).slerp(this.attitude, partialTick);
    }

    // ------------------------------------------------------------------
    // Damage
    // ------------------------------------------------------------------

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        // A figure rather than this machine's own maximum, because this runs from inside the entity
        // constructor, before there is a machine to ask. The constructor fills in the real one.
        builder.define(DATA_HEALTH, DEFAULT_HEALTH);
        builder.define(DATA_WRECKED, false);
        builder.define(DATA_SEATS, "");
    }

    /**
     * Whether this is a wreck rather than a machine: the same shape in the same place, burnt through
     * and good for nothing but the metal in it.
     *
     * <p>Asked all over the place, and by both sides. Nothing about a wreck runs -- no engine, no
     * radar, no trigger, nobody aboard -- and a renderer that has one draws it charred.
     */
    public boolean isWrecked() {
        return this.entityData.get(DATA_WRECKED);
    }

    protected void setWrecked(boolean wrecked) {
        this.entityData.set(DATA_WRECKED, wrecked);
    }

    /** What a whole machine of this sort is worth, from its file. */
    protected abstract float health();

    /**
     * What a whole machine of this sort is worth, in hit points. Never nothing, whatever the file
     * says: one worth zero is destroyed by the first scratch it takes, which is not a thing anybody
     * means to write down and would be very hard to work out from the machine vanishing.
     */
    public float getMaxHealth() {
        return Math.max(this.health(), 1.0F);
    }

    public float getHealth() {
        return this.entityData.get(DATA_HEALTH);
    }

    /**
     * Sets what is left, never below nothing and never above what the machine is worth.
     *
     * <p>The ceiling matters as much as the floor: one whose file has been edited down since it was
     * parked would otherwise come back out of the world with more than it can have.
     */
    public void setHealth(float health) {
        this.entityData.set(DATA_HEALTH, Mth.clamp(health, 0.0F, this.getMaxHealth()));
    }

    /** What is left as a fraction of a whole machine, in [0, 1]. */
    public float getHealthFraction() {
        float max = this.getMaxHealth();

        return max <= 0.0F ? 0.0F : Mth.clamp(this.getHealth() / max, 0.0F, 1.0F);
    }

    /**
     * Whether a round striking one of this machine's boxes is striking armour, and so may be thrown
     * off it rather than going in. See {@link com.ashvehicles.weapon.Ricochet}.
     *
     * <p>No by default, which is the honest answer for an aeroplane: what a wing is made of is a
     * skin over ribs, and a cannon round meeting one at any angle goes through it. Armour is a
     * different thing from thick, and being thrown off it is a different thing from surviving a hit,
     * so nothing gets it by having a lot of health.
     */
    public boolean isArmoured() {
        return false;
    }

    /**
     * How much this machine's armour is worth against being gone through, in degrees taken off the
     * angle a round would otherwise need before the plate throws it off.
     *
     * <p>Only asked of something that {@link #isArmoured} says yes to. The angle itself is not here
     * and does not need to be — the boxes lie the way the machine lies, so a hull turned to meet the
     * shot is already the shallower hit.
     */
    public float armour() {
        return 0.0F;
    }

    /**
     * Takes a blow, once, however many of the machine's boxes it arrived through.
     *
     * <p>Anything that hurts an area — an explosion above all — asks the level for everything inside
     * it and hurts each in turn, and the machine's boxes are all in that list. Passed straight
     * through, a single blast would land once for every box the machine is described with: eleven
     * times over for the Su-25, seven for the Leopard. That would make a machine's toughness depend
     * on how finely somebody chose to draw its shape, which is precisely backwards.
     *
     * <p>So the same blow is counted once. Sameness is the damage source itself: one explosion builds
     * one of those and hands it to everything it touches, while two shells arriving in the same tick
     * bring one each and both count.
     *
     * <p>Everything goes through the health and nothing takes the machine out early. A boat is
     * removed outright by one punch from anyone in creative, and both of these inherited that: an
     * arrow, a stray swing, a test shot, and a whole machine was gone with three hundred points still
     * on the gauge. Whoever wants rid of one uses the wrench, which puts it back in their pocket
     * rather than scattering it over the ground.
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide || this.isRemoved()) {
            return true;
        }

        // A bare hand does nothing to an airframe but make a noise. Before the wreck check, so that a
        // burnt-out hulk rings under a fist as readily as a live machine does.
        Player fist = knuckles(source);

        if (fist != null) {
            this.clank(fist);

            return true;
        }

        if (this.isInvulnerableTo(source)) {
            return false;
        }

        // A wreck has already had everything that can happen to it happen. Nothing takes it further:
        // there is no health left to spend, and going round again would set its own blast off a
        // second time -- which is exactly what that blast reaching the wreck's own boxes would do.
        if (this.isWrecked()) {
            return true;
        }

        long now = this.level().getGameTime();

        if (source == this.lastHurtSource && now == this.lastHurtTime) {
            return true;
        }

        this.lastHurtSource = source;
        this.lastHurtTime = now;

        // Deliberately not markHurt(). All that does is ask the server to broadcast this machine's
        // velocity at the end of the tick, which for a boat is its knockback and for one of these is
        // a figure its own physics owns.
        this.setHurtDir(-this.getHurtDir());
        this.setHurtTime(10);
        this.gameEvent(GameEvent.ENTITY_DAMAGE, source.getEntity());

        if (this.wound(amount)) {
            this.destroy(source);
        }

        return true;
    }

    /**
     * Whether that blow was somebody's bare hand, which a machine of this size does not feel.
     *
     * <p>The hand and not the man: what is asked for is the direct cause of the damage rather than
     * whoever is behind it, so a player's own round, their own bomb and their own blast all still
     * count for exactly what they are worth. Every one of those arrives with something in between.
     *
     * <p>An empty hand only. Anything held is somebody doing something the mod already has an answer
     * for -- the wrench picks the machine up, and everything else is a weapon and is treated as one.
     */
    @Nullable
    static Player knuckles(DamageSource source) {
        return source.getDirectEntity() instanceof Player player && player.getMainHandItem().isEmpty()
                ? player
                : null;
    }

    /**
     * The noise a few tonnes of machine makes when somebody hits it with their hand.
     *
     * <p>Two of the game's own recordings, so that every client already has both and nothing has to
     * be shipped: a metal block's knock for the impact, and an anvil quietened right down and pitched
     * up over the top of it for the ring that carries. Neither on its own is the sound -- the knock
     * alone is a footstep and the anvil alone is a smithy.
     *
     * <p>Played at the fist rather than at the machine, whose origin sits down between the wheels and
     * can be thirty metres from the panel actually being hit.
     */
    private void clank(Player player) {
        Vec3 at = player.getEyePosition();
        float jitter = (this.random.nextFloat() - 0.5F) * 0.2F;

        this.level().playSound(null, at.x, at.y, at.z, SoundEvents.METAL_HIT, SoundSource.NEUTRAL,
                KNUCKLE_VOLUME, KNUCKLE_PITCH + jitter);
        this.level().playSound(null, at.x, at.y, at.z, SoundEvents.ANVIL_LAND, SoundSource.NEUTRAL,
                KNUCKLE_VOLUME * 0.3F, KNUCKLE_PITCH + 0.3F + jitter);
    }

    /**
     * Takes points off, wherever they came from.
     *
     * <p>Point for point: what a weapon's file says it does is what it does here, with none of the
     * scaling a boat applies. A machine is worth a few hundred of these and a player is worth twenty,
     * so the same round that costs a man two hearts costs an airframe four points of three hundred,
     * which is the whole of what the two numbers mean.
     *
     * @return true if that was the last of it
     */
    protected boolean wound(float amount) {
        if (amount <= 0.0F) {
            return false;
        }

        this.setHealth(this.getHealth() - amount);

        return this.getHealth() <= 0.0F;
    }

    /** A machine that has been knocked out comes apart rather than dropping a serviceable one. */
    @Override
    protected void destroy(DamageSource source) {
        this.wreck();
    }

    /** How big a hole this leaves. */
    protected abstract float explosionPower();

    /**
     * The end of it: everyone out, the blast, and a burnt-out hulk left standing where the machine
     * was.
     *
     * <p>The machine is not removed. A destroyed aeroplane that simply stops existing is the one
     * thing about being shot down that never reads as anything having happened -- the sky is empty
     * and so is the ground. What is left instead is the same shape in the same place, dark, inert,
     * and worth nothing but the metal in it. Somebody with a wrench clears it away and keeps the
     * scrap; see {@link #salvage}.
     *
     * <p>Written off before the blast and not after. An explosion damages everything in reach as it
     * goes off, and this machine's own collision boxes are in reach: they pass the hit through, the
     * machine is destroyed again, and it explodes again. Setting the flag first makes {@link #hurt}
     * a no-op, which is the job the removal used to do.
     */
    protected void wreck() {
        if (!(this.level() instanceof ServerLevel level) || this.isRemoved() || this.isWrecked()) {
            return;
        }

        this.setWrecked(true);
        this.ejectPassengers();
        this.onWrecked();

        // Off the origin, which sits at the wheels or the tracks. What blows up is the machine, not
        // the ground under it.
        double reach = this.reach();
        Vec3 pos = this.position().add(0.0, reach * 0.15, 0.0);
        float power = this.explosionPower();

        // The mod's own blast rather than vanilla's: vanilla's carries a bang nobody more than sixty
        // blocks off can hear and a puff of smoke that is thrown away at thirty-two, which for an
        // aeroplane coming apart at altitude means the whole thing happens where nobody can see it.
        // Everything the weapons already do about that, a machine wants for the same reasons.
        Effects.blast(level, this, pos, power, Effects.EMBER);
        WreckEffects.destroyed(level, pos, this.getAttitude(), power, reach);
    }

    /**
     * Called on the server the moment the machine becomes a wreck, for whatever each kind of machine
     * has to shut down: an engine, a radar, whatever was hanging under the wings.
     */
    protected void onWrecked() {
    }

    // ------------------------------------------------------------------
    // Clearing a wreck away
    // ------------------------------------------------------------------

    /**
     * What the machine's file says its wreck is worth, in iron ingots, or zero if it does not say.
     * See {@link #getSalvage()}, which is what anything asking should call.
     */
    protected abstract int declaredSalvage();

    /**
     * How much metal is left in a wreck of this machine, in iron ingots.
     *
     * <p>From the file where the file has an opinion, and otherwise worked out from what the machine
     * is worth in hit points. Toughness is the nearest thing every machine already has to a
     * statement of how much of it there is, so a tank comes out heavier than an aeroplane without
     * anybody having had to write a second number down for it.
     */
    public int getSalvage() {
        int declared = this.declaredSalvage();

        return declared > 0 ? declared : Math.max(1, Math.round(this.getMaxHealth() / HEALTH_PER_INGOT));
    }

    /**
     * Clears a wreck away with a wrench and leaves the metal on the ground.
     *
     * <p>The one thing that can still be done with a machine once it has been destroyed, and the
     * reason a wreck is worth leaving standing rather than removing outright. It is deliberately the
     * same tool that packs a serviceable machine away: taking a machine to pieces is a wrench's job
     * whether or not there is anything left to fly.
     *
     * <p>What comes back is scrap and not the aeroplane. Anyone who wants their aircraft back has to
     * not have lost it.
     */
    public InteractionResult salvage() {
        if (!this.isWrecked()) {
            return InteractionResult.PASS;
        }

        if (this.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }

        // Whatever was aboard comes out with the scrap. A hold survives being shot down: the
        // airframe is a write-off, but a crate of missiles in the belly of it is still a crate of
        // missiles, and losing it to a wreck nobody could open would be a poor answer.
        this.spillHold();

        // The same gamerule vanilla's own destroy() honours, so a world that has turned entity drops
        // off does not quietly get metal out of this one.
        if (this.level().getGameRules().getBoolean(GameRules.RULE_DOENTITYDROPS)) {
            int stack = Math.max(Items.IRON_INGOT.getDefaultMaxStackSize(), 1);

            for (int left = this.getSalvage(); left > 0; left -= stack) {
                this.spawnAtLocation(new ItemStack(Items.IRON_INGOT, Math.min(left, stack)));
            }
        }

        this.discard();

        return InteractionResult.CONSUME;
    }

    // ------------------------------------------------------------------
    // The hold
    // ------------------------------------------------------------------

    /** Three rows of nine inside the machine. Never null, and never synched to a client. */
    public VehicleHold getHold() {
        return this.hold;
    }

    /**
     * Opens the hold for somebody, which is the whole of what the key press comes to.
     *
     * <p>Vanilla's own three-row chest menu, deliberately. A hold is a chest in every way a player
     * cares about, and one drawn by the game's own screen is one that already works with every
     * habit and every mod a player has for moving items about.
     */
    public void openHold(Player player) {
        if (this.level().isClientSide) {
            return;
        }

        player.openMenu(new SimpleMenuProvider(
                (id, inventory, opener) -> ChestMenu.threeRows(id, inventory, this.hold),
                Component.translatable("container.ashvehicles.hold", this.getDisplayName())));
    }

    /**
     * Tips the hold out on to the ground, for a machine that is about to stop existing.
     *
     * <p>Whatever is inside is the player's and has to go somewhere. Folding an aeroplane away with
     * a wrench would otherwise quietly take a hold full of missiles into the item with it, which is
     * the same trap the pylons are stripped one at a time to avoid — and a hold is a good deal
     * easier to forget about than a store hanging under a wing.
     *
     * <p>Under the same gamerule vanilla's own {@code destroy} honours, so a world that has turned
     * entity drops off does not get an aeroplane's load out of this one.
     */
    protected void spillHold() {
        if (!(this.level() instanceof ServerLevel)
                || !this.level().getGameRules().getBoolean(GameRules.RULE_DOENTITYDROPS)) {
            this.hold.clearContent();

            return;
        }

        for (ItemStack stack : this.hold.removeAllItems()) {
            this.spawnAtLocation(stack);
        }
    }

    // ------------------------------------------------------------------
    // Seats
    // ------------------------------------------------------------------

    /**
     * The crew places, in the machine's own axes — x to the right, y up, z towards the front — in
     * blocks. The first is the one that drives or flies it.
     */
    protected abstract List<VehicleChassis.Seat> seats();

    /** Where the camera goes for a crew member with no eye of their own, and how the chase is hung. */
    protected abstract VehicleChassis.CameraMount cameraMount();

    /**
     * What a first-person eye is bolted to when the seat does not say.
     *
     * <p>The turret on anything with one, because that is where a tank's hatches are: lay the gun
     * abeam and the view comes round over the side of the hull with it, as it does from a real
     * cupola. A ship and an aircraft have nothing that swings, so theirs is the hull.
     */
    protected VehicleShape.Mount defaultEyeMount() {
        return VehicleShape.Mount.HULL;
    }

    public int getMaxPassengers() {
        return Math.max(this.seats().size(), 1);
    }

    /** The seat at an index, or the last one there is. Never null, so nobody has to check. */
    private VehicleChassis.Seat seatAt(int index) {
        List<VehicleChassis.Seat> seats = this.seats();

        return seats.isEmpty()
                ? VehicleChassis.Seat.at(Vec3.ZERO)
                : seats.get(Mth.clamp(index, 0, seats.size() - 1));
    }

    /** Seat position relative to the entity origin, before the machine's attitude is applied. */
    public Vec3 getSeatOffset(int index) {
        return this.seatAt(index).pos();
    }

    /**
     * Where the crew member in a seat sees the world from, in the machine's own axes.
     *
     * <p>The seat's own eye where it has one. Where it has not, the machine's single
     * {@code camera.cockpit} — which is what every seat used before seats could carry an eye, and
     * so is what a file that has not been touched goes on doing.
     */
    public Vec3 getSeatEye(int index) {
        return this.seatAt(index).eyeOr(this.cameraMount().cockpit());
    }

    /** What that eye is bolted to: the seat's own answer, or the machine's. */
    public VehicleShape.Mount getSeatEyeMount(int index) {
        return this.seatAt(index).mountOr(this.defaultEyeMount());
    }

    /**
     * Where a rider sees the world from, in the world's own coordinates: their own seat's eye,
     * carried by whichever part of the machine that eye is bolted to.
     */
    public Vec3 eyeOf(Entity rider, float partialTick) {
        int seat = this.getSeatIndex(rider);

        return this.eyeToWorld(this.getSeatEyeMount(seat), this.getSeatEye(seat), partialTick);
    }

    /**
     * A first-person eye in the world. Nothing but a turret makes this any more than
     * {@link #toWorld}, so only the machine that has one overrides it.
     */
    protected Vec3 eyeToWorld(VehicleShape.Mount mount, Vec3 eye, float partialTick) {
        return this.toWorld(eye, partialTick);
    }

    /**
     * Which seat a rider occupies.
     *
     * <p>Their assigned seat, from {@link #DATA_SEATS}, not their place in the passenger list: the
     * two used to be the same thing, but a crew member can now move between seats without leaving,
     * so the list order no longer says where anyone sits. A rider the assignment has not yet caught
     * up with — one aboard for the tick before the server hands out a seat — falls back to their
     * list order, which is where the first of them would have sat anyway.
     */
    public int getSeatIndex(Entity passenger) {
        UUID id = passenger.getUUID();
        UUID[] seated = this.seatOccupants();

        for (int i = 0; i < seated.length; i++) {
            if (id.equals(seated[i])) {
                return i;
            }
        }

        return Math.max(this.getPassengers().indexOf(passenger), 0);
    }

    /** Nobody climbs into a wreck. There is no seat left in it and nothing for them to do there. */
    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return !this.isWrecked() && this.getPassengers().size() < this.getMaxPassengers();
    }

    /**
     * Whoever is in the driver's seat — seat 0, the one at the controls — rather than whoever
     * climbed in first. They are usually the same crew member, but once anybody has moved seats
     * they part company: the controls follow the seat, so a rider who takes seat 0 takes the machine
     * with it, and one who leaves it hands the machine back to the server until somebody sits there
     * again.
     */
    @Override
    public LivingEntity getControllingPassenger() {
        UUID[] seated = this.seatOccupants();

        if (seated.length > 0 && seated[0] != null) {
            for (Entity passenger : this.getPassengers()) {
                if (seated[0].equals(passenger.getUUID())) {
                    return passenger instanceof LivingEntity crew ? crew : super.getControllingPassenger();
                }
            }

            // The seat names a rider who is no longer aboard — a stale line the server has yet to
            // tidy. Nobody is driving until it does.
            return super.getControllingPassenger();
        }

        // Before the first assignment arrives, fall back to the old rule so a freshly boarded
        // machine is drivable on the tick it is entered rather than the tick after.
        return seated.length == 0 && this.getFirstPassenger() instanceof LivingEntity crew
                ? crew
                : super.getControllingPassenger();
    }

    // ------------------------------------------------------------------
    // Moving between seats
    // ------------------------------------------------------------------

    /** The cache behind {@link #seatOccupants}, reparsed only when the synched line changes. */
    @Nullable
    private String seatLine;
    private UUID[] seatCache = new UUID[0];

    /**
     * The occupant of each seat, by seat index, with a null where a seat is empty. Read on both
     * sides straight off {@link #DATA_SEATS}, and cached so the hot callers — every seat drawn and
     * every check of who is driving — do not reparse a string they have already seen.
     */
    private UUID[] seatOccupants() {
        String line = this.entityData.get(DATA_SEATS);

        if (!line.equals(this.seatLine)) {
            this.seatLine = line;
            this.seatCache = parseSeats(line);
        }

        return this.seatCache;
    }

    private static UUID[] parseSeats(String line) {
        if (line.isEmpty()) {
            return new UUID[0];
        }

        String[] fields = line.split(",", -1);
        UUID[] seated = new UUID[fields.length];

        for (int i = 0; i < fields.length; i++) {
            if (!fields[i].isEmpty()) {
                try {
                    seated[i] = UUID.fromString(fields[i]);
                } catch (IllegalArgumentException ignored) {
                    // A malformed field is simply an empty seat; nothing here is worth a crash.
                }
            }
        }

        return seated;
    }

    /**
     * Reads out the current seating, one seat per slot up to the machine's capacity, and drops
     * anybody the list still names who is no longer aboard. Server-side working copy: the caller
     * changes it and writes it back with {@link #writeSeats}.
     */
    private UUID[] currentSeating() {
        int max = this.getMaxPassengers();
        UUID[] seated = new UUID[max];
        UUID[] stored = this.seatOccupants();

        for (int i = 0; i < max && i < stored.length; i++) {
            seated[i] = stored[i];
        }

        // Turf out ids for anybody who has since left, so their old seat reads as free.
        for (int i = 0; i < max; i++) {
            if (seated[i] != null && !this.isAboard(seated[i])) {
                seated[i] = null;
            }
        }

        return seated;
    }

    private boolean isAboard(UUID id) {
        for (Entity passenger : this.getPassengers()) {
            if (id.equals(passenger.getUUID())) {
                return true;
            }
        }

        return false;
    }

    private void writeSeats(UUID[] seated) {
        StringBuilder line = new StringBuilder();

        for (int i = 0; i < seated.length; i++) {
            if (i > 0) {
                line.append(',');
            }

            if (seated[i] != null) {
                line.append(seated[i]);
            }
        }

        this.entityData.set(DATA_SEATS, line.toString());
    }

    /** Sits a rider just climbing aboard in the lowest free seat, which for the first is the driver's. */
    private void seatBoarding(Entity passenger) {
        UUID[] seated = this.currentSeating();
        UUID id = passenger.getUUID();

        // Already placed — a reorder rather than a fresh boarding — needs nothing doing.
        for (UUID occupant : seated) {
            if (id.equals(occupant)) {
                return;
            }
        }

        for (int i = 0; i < seated.length; i++) {
            if (seated[i] == null) {
                seated[i] = id;
                this.writeSeats(seated);

                return;
            }
        }
    }

    /** Empties the seat of a rider getting out, so the seat is free for the next of them. */
    private void seatLeaving(Entity passenger) {
        UUID[] seated = this.currentSeating();
        UUID id = passenger.getUUID();
        boolean changed = false;

        for (int i = 0; i < seated.length; i++) {
            if (id.equals(seated[i])) {
                seated[i] = null;
                changed = true;
            }
        }

        if (changed) {
            this.writeSeats(seated);
        }
    }

    /**
     * Moves a rider to the next free seat, wrapping round from the last back to the first. The way a
     * crew member changes station without getting out: a lone rider walks the whole machine seat by
     * seat, the driver's included, and where seats are shared everyone keeps to their own until one
     * is vacated. An occupied seat is never taken from under the crew member in it — a press with
     * nowhere free to go does nothing.
     *
     * <p>Server-side, off the switch-seat key. Returns whether the rider actually moved.
     */
    public boolean switchToNextSeat(Entity passenger) {
        if (this.level().isClientSide || !this.hasPassenger(passenger)) {
            return false;
        }

        UUID[] seated = this.currentSeating();
        int max = seated.length;

        if (max < 2) {
            return false;
        }

        int from = this.getSeatIndex(passenger);

        for (int step = 1; step <= max; step++) {
            int to = (from + step) % max;

            if (seated[to] == null) {
                seated[from] = null;
                seated[to] = passenger.getUUID();
                this.writeSeats(seated);

                return true;
            }
        }

        return false;
    }

    @Override
    protected void addPassenger(Entity passenger) {
        super.addPassenger(passenger);

        if (!this.level().isClientSide) {
            this.seatBoarding(passenger);
        }
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);

        if (!this.level().isClientSide) {
            this.seatLeaving(passenger);
        }
    }

    /**
     * Built from the machine's own axes rather than from yaw and pitch alone, so a seat banks with
     * the wings and leans with the hull. Rotating the offset by the euler angles instead leaves the
     * crew sitting upright in a rolled machine, adrift of the cockpit the model draws.
     */
    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
        return Attitude.toWorld(this.getAttitude(), this.getSeatOffset(this.getSeatIndex(passenger)));
    }

    /**
     * Turns an offset written in the machine's own axes (x right, y up, z towards the front) into a
     * world position at a moment between two ticks. Used for seats, muzzles and the first-person
     * eye, so all of them ride the machine through whatever it is doing.
     */
    public Vec3 toWorld(Vec3 offset, float partialTick) {
        return this.getPosition(partialTick).add(Attitude.toWorld(this.getAttitude(partialTick), offset));
    }

    // ------------------------------------------------------------------
    // The boxes the machine is made of
    // ------------------------------------------------------------------

    /**
     * Builds the boxes. Called from the constructor and nowhere else: the level records an entity's
     * parts the moment it is added, and one that has none then is remembered as having none and can
     * never be given any afterwards.
     */
    protected final void buildParts() {
        List<VehicleShape.Box> shape = this.getShape().boxes();
        List<VehiclePart> extra = this.extraParts();
        this.parts = new VehiclePart[shape.size() + extra.size()];

        for (int i = 0; i < shape.size(); i++) {
            // Told which box of the file it stands for, so that the machine can put the right one
            // where it belongs each tick. What the part is measured against from then on is the
            // Hitbox it was placed with, and never the upright box it is carried around in.
            this.parts[i] = VehiclePart.airframe(this, shape.get(i).name(), i);
        }

        for (int i = 0; i < extra.size(); i++) {
            this.parts[shape.size() + i] = extra.get(i);
        }

        // Numbered from the machine's own id rather than left with whatever the entity counter
        // handed out, so that the two sides agree about which box is which. See setId.
        this.setId(this.getId());
    }

    /**
     * Boxes the file does not list: an aircraft's pylons, which are places on the machine
     * rather than pieces of it. Nothing else has any.
     */
    protected List<VehiclePart> extraParts() {
        return List.of();
    }

    /**
     * Numbers the machine's boxes after the machine itself, so that both sides call the same box by
     * the same name.
     *
     * <p>A box is an entity with an id, and ids come from a counter each side keeps for itself. The
     * server makes a machine, its boxes take the next few numbers, and the client is then told the
     * machine's id and quietly renumbers only the machine — leaving its boxes on whatever numbers its
     * own counter had reached. The two sides then disagree about every box.
     *
     * <p>Nothing notices until a player clicks one. Being shot is decided by the server against its
     * own boxes and never crosses the gap, but a click is the client naming what it hit and asking
     * the server to act on it; named by a number the server does not recognise, the click reaches
     * nothing and climbing aboard simply fails to happen.
     *
     * <p>Deriving each box's id from the machine's own makes the two sides agree by construction. It
     * is what vanilla does for the ender dragon, for the same reason.
     */
    @Override
    public void setId(int id) {
        super.setId(id);

        // Called once by the entity's own constructor, before there are any boxes to number.
        if (this.parts == null) {
            return;
        }

        for (int i = 0; i < this.parts.length; i++) {
            this.parts[i].setId(id + i + 1);
        }
    }

    @Override
    public boolean isMultipartEntity() {
        return this.parts.length > 0;
    }

    @Override
    public VehiclePart[] getParts() {
        return this.parts;
    }

    /** Where the middle of a box is in the world, which is where whatever carries it has put it. */
    protected abstract Vec3 boxCentre(VehicleShape.Box box);

    /** The rotation a box is standing at in the world, including its own angle within the machine. */
    protected abstract Quaternionf boxRotation(VehicleShape.Box box);

    /**
     * The patch of world every one of the machine's boxes is inside, as of the last time they were
     * put where they belong.
     *
     * <p>One box round the lot of them, for deciding in a single test whether a machine is worth
     * asking about at all. Anything moving near a carrier described by eighty boxes should find out
     * that it is nowhere near it without touching eighty of anything, and this is how.
     *
     * <p>Null before the machine has ever placed its boxes.
     */
    @Nullable
    public AABB placedBounds() {
        return this.placed;
    }

    /**
     * Takes whatever is standing on the machine along with it, by however far it has moved and come
     * round since its boxes were last put down.
     *
     * <p>Called by whatever places them, straight after doing so: the boxes have to be where the
     * machine is now before anybody can be found standing on one, and the machine has to have
     * finished moving for this tick before there is a distance to carry them by.
     *
     * <p>A machine that has been put somewhere else outright — spawned, loaded, teleported — carries
     * nobody for that tick. The distance would not be a distance it travelled.
     */
    protected final void carryStanders() {
        Vec3 now = this.position();
        float heading = Attitude.heading(this.attitude);
        Vec3 before = this.carriedFrom;
        float pointed = this.carriedHeading;

        this.carriedFrom = now;
        this.carriedHeading = heading;

        if (before == null) {
            return;
        }

        Vec3 shift = now.subtract(before);
        float turn = Mth.wrapDegrees(heading - pointed);

        if (shift.lengthSqr() > CARRY_LIMIT * CARRY_LIMIT) {
            return;
        }

        if (shift.lengthSqr() > 1.0E-12 || Math.abs(turn) > 1.0E-4F) {
            Hitboxes.carry(this, before, shift, turn);
        }
    }

    /**
     * Works that out, once, at the end of placing the boxes. Called by whatever placed them: it is
     * the only moment they are all known to be where they belong.
     */
    protected final void notePlacement() {
        AABB union = null;

        for (VehiclePart part : this.parts) {
            Hitbox box = part.hitbox();

            if (box != null) {
                union = union == null ? box.reach() : union.minmax(box.reach());
            }
        }

        this.placed = union;
    }

    /**
     * One of the machine's boxes as it is really lying in the world: where the machine has carried it
     * to, at the size its file gives it, at the angle it is lying at.
     *
     * <p>Not an upright box, and not built out of any. See {@link Hitbox}, which is the mod's own
     * shape and the only thing anything about this machine is ever measured against.
     */
    protected Hitbox hitbox(VehicleShape.Box box) {
        return new Hitbox(this.boxCentre(box), box.size(), this.boxRotation(box));
    }

    /**
     * Whether the machine's real shape has room where it is standing, give or take a margin.
     *
     * <p>Used when one is put down. The boxes stop against the world, so a machine set down with a
     * wing or a track inside a hillside would be wedged there and unable to move; the whole shape has
     * to be clear rather than just the middle of it.
     *
     * @param margin how much each box may overlap the world and still count as clear, which keeps a
     *               wingtip resting a hair inside a slope from making the machine unplaceable
     */
    public boolean hasRoomHere(double margin) {
        return this.hasRoomHere(margin, Hitboxes.UNDERSIDE_NONE);
    }

    /**
     * The same, with a height below which blocks are the floor rather than something the machine is
     * inside.
     *
     * <p>For asking whether the world has closed around a machine that is standing on it, or coming
     * down onto it. Nothing below the wheels is an answer to that question; see
     * {@code Hitboxes.clearOfBlocks}.
     *
     * @param underside the height at or below which blocks are floor, or
     *                  {@link Hitboxes#UNDERSIDE_NONE} to count every one of them
     */
    public boolean hasRoomHere(double margin, double underside) {
        List<VehicleShape.Box> shape = this.getShape().boxes();

        if (shape.isEmpty()) {
            return this.level().noCollision(this, this.getBoundingBox().deflate(margin));
        }

        for (VehicleShape.Box box : shape) {
            // The box as it is really lying, against the blocks as they really are. The upright box
            // round a machine set down on a slope holds a good deal of hillside it never touches.
            if (!Hitboxes.clearOfBlocks(this, this.hitbox(box), margin, underside)) {
                return false;
            }
        }

        return true;
    }

    /**
     * The box the renderer decides visibility against, which is deliberately not the box the machine
     * collides with.
     *
     * <p>The plain hitbox is kept small on purpose — it covers the fuselage, or the hull, so that an
     * overhanging wingtip or gun barrel does not make the machine unplaceable or catch on every
     * doorway. That is the right size to collide with and quite the wrong size to be drawn against: a
     * fifteen-metre aeroplane whose six-metre box has just left the screen is still very much on it,
     * and would blink out. So the shape it really occupies is what culling is given.
     */
    @Override
    public AABB getBoundingBoxForCulling() {
        double reach = this.shapeReach();

        return reach > 0.0 ? this.getBoundingBox().inflate(reach) : this.getBoundingBox();
    }

    /**
     * How far the machine reaches from its middle, in blocks.
     *
     * <p>Half the longest way across the shape it is really made of, and deliberately not half the
     * plain box, which for a fifteen-metre aeroplane is a shed measured in the wrong places. This is
     * what sizes anything drawn at the scale of the machine rather than at the scale of a blast: how
     * far the fire is strung along a burning airframe, how far the wreckage goes.
     *
     * <p>A machine with no boxes at all falls back to its plain box, which is all anybody
     * has said about how big it is.
     */
    public double reach() {
        double reach = this.shapeReach();

        return reach > 0.0 ? reach : Math.max(this.getBbWidth(), this.getBbHeight()) * 0.5;
    }

    /** The same from the collision boxes alone, which is zero for a machine that has none. */
    private double shapeReach() {
        double reach = 0.0;

        for (VehicleShape.Box box : this.getShape().boxes()) {
            for (int axis = 0; axis < 3; axis++) {
                double corner = Math.abs(component(box.offset(), axis)) + component(box.size(), axis) / 2.0;
                reach = Math.max(reach, corner);
            }
        }

        return reach;
    }

    protected static double component(Vec3 v, int axis) {
        return axis == 0 ? v.x : axis == 1 ? v.y : v.z;
    }

    /**
     * A machine does not collide with itself. Its own boxes are solid to everyone else, which is the
     * point of them, but to the machine they are simply where it is: without this it spends every
     * tick shouldering its way past its own wings and never gets up to speed.
     */
    @Override
    public boolean canCollideWith(Entity other) {
        return !(other instanceof VehiclePart part && part.getParent() == this) && super.canCollideWith(other);
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /**
     * The one thing every machine writes down whatever else it keeps, and the one thing that has to
     * survive the world being closed on it: a wreck left in a field is still a wreck tomorrow.
     *
     * <p>Both of these are implemented here rather than left abstract, as they are on {@link Entity},
     * so every machine's own version has to call up to this first.
     */
    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.setWrecked(tag.getBoolean("Wrecked"));
        this.wreckAge = tag.getInt("WreckAge");
        this.hold.load(tag, this.registryAccess());
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putBoolean("Wrecked", this.isWrecked());
        tag.putInt("WreckAge", this.wreckAge);
        this.hold.save(tag, this.registryAccess());
    }

    @Override
    protected Item getDropItem() {
        return BuiltInRegistries.ITEM.get(this.getVehicleId());
    }

    @Override
    public ItemStack getPickResult() {
        return new ItemStack(this.getDropItem());
    }
}
