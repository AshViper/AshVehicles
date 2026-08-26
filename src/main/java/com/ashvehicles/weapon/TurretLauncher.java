package com.ashvehicles.weapon;

import javax.annotation.Nullable;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.BulletEntity;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.RocketEntity;
import com.ashvehicles.entity.VehicleHold;
import com.ashvehicles.entity.VehicleProjectile;
import com.ashvehicles.item.AmmoItem;
import com.ashvehicles.registry.ModEntities;
import com.ashvehicles.vehicle.GroundVehicleDefinition;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The missiles in a launcher's tubes: a seeker, a magazine, and a wait between launches.
 *
 * <p>The same relationship to {@link WeaponMounts} that {@link BuiltInGun} has, and for the same
 * reason. A pylon is a place a store is <em>hung</em>, and most of that class is about which station
 * is selected, what somebody loaded onto it, and putting it back on again out of the hold. A
 * launcher's tubes are none of those things: they are built in, they all hold the same round, and
 * the only questions are how many are left and whether the seeker has anything worth spending one
 * on. What the two do share is the weapon files — how hard the missile hits, how far its seeker
 * sees, how hard it can turn and what fools it are read from {@code data/ashvehicles/weapon/}
 * exactly as an aircraft reads them, so a missile is described in one place whether it is hanging
 * under a wing or standing in a tube.
 *
 * <p><b>The seeker looks whether or not the tubes are selected.</b> That is deliberate and it is
 * most of what makes a battery frightening. A launcher tracking an aeroplane sets off the pilot's
 * warning receiver — see {@link com.ashvehicles.sensor.Sensors} — so the aircraft is told it is
 * being looked at well before anything leaves the rail, which is the warning that gives the pilot
 * something to do about it. A seeker that only woke up when the crew switched to missiles would
 * hand the aeroplane a launch out of a clear sky.
 *
 * <p><b>Nothing is fired without a lock.</b> A guided round with nothing to steer at is a round
 * thrown away, and the crew are better told to keep tracking than allowed to waste a tube. What
 * carries the lock out to the missile is the same handover an aircraft makes: the target as it was
 * at the moment of launch, and nothing afterwards.
 *
 * <p><b>Where the state lives.</b> Rounds left, the wait, and what the seeker is on are synched data
 * on the vehicle rather than fields here, because the crew's instruments need all three and only the
 * server may decide any of them.
 */
public final class TurretLauncher {
    /** How big the flash of a launch is. A boost motor lighting, not a gun going off. */
    private static final float BOOST_BLAST = 2.0F;

    /**
     * Ticks a standing vehicle takes to fill an empty set of tubes out of its own hold. Twice what
     * the gun's loaders take, because a rocket is craned in and a shell is passed up by hand.
     */
    private static final int RESUPPLY_TICKS = 400;

    /** Below this, in blocks a tick, the vehicle counts as standing still and can be loaded. */
    private static final float STANDING = 1.0E-4F;

    private final GroundVehicleEntity vehicle;
    /** What the crew have the seeker on. Only the server ever decides it. */
    private final TargetLock lock;
    /** Whether the trigger was down last tick, so that holding it does not empty the tubes. */
    private boolean triggerWasDown;

    public TurretLauncher(GroundVehicleEntity vehicle) {
        this.vehicle = vehicle;
        this.lock = new TargetLock(vehicle);
    }

    /** What the seeker is on. Read by the instruments; only the server ever decides it. */
    public TargetLock lock() {
        return this.lock;
    }

    /** The figures of the round in the tubes, or null for a vehicle that carries none. */
    @Nullable
    public WeaponDefinition missile() {
        return this.vehicle.getStats().launcher().missile().map(Definitions::weapon).orElse(null);
    }

    /** How many tubes a full load fills, from the missile's own file. */
    public int capacity() {
        return this.vehicle.getStats().launcher().missile()
                .map(id -> Definitions.weapon(id).ammo())
                .orElse(0);
    }

    /** How long the crew take between launches, in ticks, from the missile's rate of fire. */
    public int reloadTicks() {
        return this.vehicle.getStats().launcher().missile()
                .map(id -> ticksFor(Definitions.weapon(id).firing().roundsPerSecond()))
                .orElse(1);
    }

    /**
     * One tick of the loading crew: one round out of the hold and into a tube, if one is due this
     * tick and there is a tube to put it in.
     *
     * <p><b>By hand, one at a time, and only while the vehicle is standing still.</b> A rocket is
     * craned in off a lorry — the real thing takes a transloader and the best part of half an hour —
     * so a launcher fires what somebody loaded aboard it and no more. It used to fill itself out of
     * the air the moment the vehicle was put down, which made a full salvo free and the hold a
     * decoration.
     *
     * <p>Slower than the gun's, and deliberately: {@link #RESUPPLY_TICKS} is a full set of tubes,
     * which for a launcher that holds two dozen is a round every few seconds. Long enough that
     * emptying the tubes is a decision rather than a formality.
     *
     * <p>The same shape as {@code BuiltInGun.resupply}, one floor apart from it because the two count
     * different things: rounds and a reload against tubes and a wait, each in their own synched
     * fields. What they share — the hold, the rate, the standing-still rule — is the arrangement
     * rather than the code, and the arrangement is worth more than the half-dozen lines.
     */
    private void resupply(WeaponDefinition missile) {
        if (Math.abs(this.vehicle.getSpeed()) >= STANDING || this.vehicle.getMissiles() >= missile.ammo()) {
            return;
        }

        int every = Math.max(1, Math.round((float) RESUPPLY_TICKS / missile.ammo()));

        if (this.vehicle.tickCount % every != 0 || !this.take(missile.ammoKind())) {
            return;
        }

        this.vehicle.setMissiles(this.vehicle.getMissiles() + 1);
    }

    /**
     * Takes one rocket out of the hold, in the order whoever packed it laid it out.
     *
     * @return whether there was one to take
     */
    private boolean take(AmmoKind kind) {
        VehicleHold hold = this.vehicle.getHold();

        for (int at = 0; at < hold.getContainerSize(); at++) {
            if (AmmoItem.isKind(hold.getItem(at), kind)) {
                hold.removeItem(at, 1);

                return true;
            }
        }

        return false;
    }

    private static int ticksFor(float roundsPerSecond) {
        return roundsPerSecond <= 0.0F ? 1 : Math.max(1, Math.round(20.0F / roundsPerSecond));
    }

    /**
     * Once a tick, on the server. The seeker looks, the wait runs down, and a press sends one on its
     * way if there is anything to send it at.
     *
     * @param trigger whether the crew are holding the trigger <em>and</em> have the tubes selected.
     *                Which weapon the trigger fires is the vehicle's business, not this class's
     */
    public void tick(boolean trigger) {
        int reload = this.vehicle.getMissileReload();

        if (reload > 0) {
            this.vehicle.setMissileReload(reload - 1);
        }

        GroundVehicleDefinition.Launcher tubes = this.vehicle.getStats().launcher();

        if (!tubes.exists()) {
            this.lock.clear();
            this.triggerWasDown = trigger;

            return;
        }

        ResourceLocation missileId = tubes.missile().orElseThrow();
        WeaponDefinition missile = Definitions.weapon(missileId);

        // Before the seeker and before the trigger, because loading is the one thing that goes on
        // whether or not anybody is aboard: it is the ground crew's work and not the gunner's.
        this.resupply(missile);

        // A battery with nobody in it is not tracking anything. Left looking, an abandoned launcher
        // would go on sweeping a couple of kilometres of sky for ever and go on setting off warning
        // receivers across the map, which is the same rule the radar itself keeps -- see
        // Sensors.tick, which stands down for an empty machine for both of those reasons.
        if (this.vehicle.getControllingPassenger() == null) {
            this.lock.clear();
            this.triggerWasDown = false;

            return;
        }

        // The seeker looks all the time, not only while the tubes are selected. See the class note.
        this.lock.tick(missile.guidance().orElse(null));

        boolean pressed = trigger && (missile.isAutomatic() || !this.triggerWasDown);
        this.triggerWasDown = trigger;

        if (!pressed || reload > 0 || this.vehicle.getMissiles() <= 0) {
            return;
        }

        // Nothing leaves the tube without something to chase.
        if (missile.isGuided() && !this.lock.isLocked()) {
            return;
        }

        if (this.vehicle.level() instanceof ServerLevel level) {
            this.fire(level, missileId, missile);
        }
    }

    /**
     * Sends one on its way.
     *
     * <p>It leaves from the tubes and flies along the <em>bore</em>, which on anything carrying both
     * is where the gun is laid: the barrels and the tubes are bolted to one mounting, so laying the
     * gun on a target lays the tubes on it too. The rail itself is a point on the turret and comes
     * round the ring with it, but it does not rise with the barrels — a tube is a box on the side of
     * the mounting rather than something that recoils.
     *
     * <p><b>Unless something is already locked.</b> The seeker takes a target well outside the
     * bore now that a radar-cued one is held to the set's own arc rather than to its narrow head —
     * see {@link TargetLock#bestCandidate} — so a tube that only ever fired down the bore would
     * launch a locked missile pointed nowhere near what it is locked onto, and count on
     * {@code turn_rate} to close a gap of tens of degrees before the seeker's own {@code
     * track_angle} gives up on it, which for a wide lock it may never manage even once
     * {@code turn_rate} starts working on it. A real rail does the same: the round is caged onto
     * the designated track before it ever leaves, and comes off already pointed close to where it
     * is going, with the fine work left to its own fins. Nothing about an unguided tube changes —
     * one with nothing locked still leaves along the bore, exactly as it always did.
     */
    private void fire(ServerLevel level, ResourceLocation missileId, WeaponDefinition missile) {
        GroundVehicleDefinition.Launcher tubes = this.vehicle.getStats().launcher();
        Vec3 rail = this.vehicle.turretToWorld(tubes.rail(), 1.0F);
        Vec3 bore = this.vehicle.getAimDirection(1.0F);
        Vec3 right = BuiltInGun.across(bore);
        Vec3 up = right.cross(bore).normalize();
        LivingEntity crew = this.vehicle.getControllingPassenger();
        RandomSource random = this.vehicle.getRandom();
        Entity locked = missile.isGuided() && this.lock.isLocked() ? this.lock.target() : null;
        Vec3 caged = cagedAim(locked, rail, bore);

        double scatter = Math.tan(Math.toRadians(missile.firing().spread())) * 0.5;
        double spread = Math.tan(Math.toRadians(missile.firing().salvoSpread())) * 0.5;

        for (int i = 0; i < Math.max(1, missile.firing().salvo()); i++) {
            Vec3 direction = caged
                    .add(right.scale(random.nextGaussian() * (scatter + spread)))
                    .add(up.scale(random.nextGaussian() * (scatter + spread)))
                    .normalize();

            VehicleProjectile shot = missile.type() == WeaponDefinition.Type.GUN
                    ? new BulletEntity(ModEntities.BULLET.get(), level)
                    : new RocketEntity(ModEntities.ROCKET.get(), level);

            shot.setup(missileId, this.vehicle, crew);
            shot.setPos(rail);
            // launch rather than setDeltaMovement: the speed has to reach the clients, and the
            // packets that would ordinarily carry it cannot express one this fast. See
            // VehicleProjectile.
            shot.launch(direction.scale(missile.projectile().speed()));

            if (shot instanceof RocketEntity rocket && locked != null) {
                rocket.setTarget(locked);
            }

            level.addFreshEntity(shot);
        }

        WeaponEffects.muzzleBlast(level, rail, bore, BOOST_BLAST, missile.projectile().tracer());
        this.playLaunchSound(missile, missileId);

        this.vehicle.setMissiles(this.vehicle.getMissiles() - 1);
        this.vehicle.setMissileReload(ticksFor(missile.firing().roundsPerSecond()));
    }

    /**
     * Where a caged round leaves from: at whatever is locked, if anything is, and along the bore
     * otherwise — which is every unguided tube, and a guided one fired with nothing held. Falls
     * back to the bore too on the one case a direction cannot be built from, which is a target
     * standing exactly on the rail.
     */
    private static Vec3 cagedAim(@Nullable Entity locked, Vec3 rail, Vec3 bore) {
        if (locked == null) {
            return bore;
        }

        Vec3 toTarget = locked.position().add(0.0, locked.getBbHeight() * 0.5, 0.0).subtract(rail);

        return toTarget.lengthSqr() > 1.0E-6 ? toTarget.normalize() : bore;
    }

    /**
     * The launch: the event the weapon's file names, else one named after the weapon. Sent with the
     * reach in the volume slot rather than the loudness, for the reason set out in
     * {@code WeaponMounts.playFireSound} — that slot is the only thing deciding who is told about
     * the sound at all, and a missile going off the rail is heard across the valley it was fired
     * over.
     */
    private void playLaunchSound(WeaponDefinition missile, ResourceLocation missileId) {
        ResourceLocation event = missile.sound().fire()
                .orElseGet(() -> missileId.withPath(WeaponMounts.SOUND_PREFIX + missileId.getPath()));

        this.vehicle.level().playSound(null, this.vehicle.getX(), this.vehicle.getY(), this.vehicle.getZ(),
                SoundEvent.createVariableRangeEvent(event), SoundSource.NEUTRAL,
                missile.sound().packetVolume(), missile.sound().pitch());
    }

    /** How far along the lock is, from nothing to one. What the instruments draw while it closes. */
    public float progress() {
        WeaponDefinition missile = this.missile();

        return missile == null || missile.guidance().isEmpty()
                ? 0.0F
                : Mth.clamp(this.lock.progress(missile.guidance().get()), 0.0F, 1.0F);
    }

    public void load(CompoundTag tag) {
        // A vehicle written to the world before it had tubes comes back with them full rather than
        // empty, which is the kinder of the two guesses.
        this.vehicle.setMissiles(tag.contains("Missiles") ? tag.getInt("Missiles") : this.capacity());
        this.vehicle.setMissileReload(tag.getInt("MissileReload"));
    }

    public void save(CompoundTag tag) {
        tag.putInt("Missiles", this.vehicle.getMissiles());
        tag.putInt("MissileReload", this.vehicle.getMissileReload());
    }
}
