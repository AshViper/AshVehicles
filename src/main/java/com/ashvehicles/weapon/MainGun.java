package com.ashvehicles.weapon;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.VehicleProjectile;
import com.ashvehicles.entity.BulletEntity;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.RocketEntity;
import com.ashvehicles.entity.VehicleHold;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The gun built into a vehicle's turret: a barrel, a magazine, and a wait between rounds.
 *
 * <p>Deliberately not {@link WeaponMounts}. A pylon is a place a store is hung and taken off again,
 * and most of that class is about which station is selected, what is on it, and what the seeker is
 * holding. A tank's main armament is none of those things: it is built in, there is one of it, and
 * the only questions are whether it is loaded and where it is pointing. What the two do share is the
 * weapon files — how hard a round hits, how fast it leaves and how often one can be fired are read
 * from {@code data/ashvehicles/weapon/} exactly as an aircraft reads them, so a gun is described in
 * one place whether it is bolted into a turret or hung under a wing.
 *
 * <p><b>Whether one press is one round is the weapon's to say.</b> A tank gun is read on the
 * trigger's rising edge: a loader takes several seconds, and one that let go the moment they were
 * finished is not how anybody fires one and takes the aiming out of it entirely. An autocannon in
 * the same sort of turret is the opposite — it is a thing you hold down, and a burst is the whole of
 * how it is aimed. Both are this class; which one a vehicle has is
 * {@link WeaponDefinition#isAutomatic()}, read from the weapon's own file.
 *
 * <p><b>What it fires is what somebody loaded.</b> The magazine is filled out of the vehicle's own
 * hold, a shell or a belt at a time, and only while the vehicle is standing still — see
 * {@link #resupply}. There is no free load: a tank put down out of the creative tab has an empty gun
 * until somebody puts ammunition in it, which is the arrangement an aircraft's pylons have always
 * had.
 *
 * <p><b>Where the state lives.</b> Rounds and the reload counter are synched data on the vehicle
 * rather than fields here, because the client needs both: the reload counter is what the barrel's
 * recoil is drawn from, and it is enough on its own — a counter that has just jumped to its maximum
 * <em>is</em> the news that the gun has fired, so nothing else has to be sent to say so.
 */
public final class MainGun {
    /**
     * Rounds between muzzle flashes on an automatic gun.
     *
     * <p>A flash is four bursts of particles, each of them a packet to everybody who can see the
     * vehicle, and at twenty rounds a second one per round is eighty packets a second and several
     * hundred particles for as long as the trigger is held. One in three still reads as a barrel
     * firing continuously -- the bursts last longer than the gap between them -- and costs a third
     * as much. A gun that fires one round at a time flashes for every one of them.
     */
    private static final int FLASH_EVERY = 3;

    /**
     * Ticks a standing vehicle takes to fill an empty magazine out of its own hold. The loaders,
     * abstracted, and the same ten seconds an aircraft's ground crew take over a pylon.
     */
    private static final int RESUPPLY_TICKS = 200;

    /** Below this, in blocks a tick, the vehicle counts as standing still and can be loaded. */
    private static final float STANDING = 1.0E-4F;

    private final GroundVehicleEntity vehicle;
    /** Whether the trigger was down last tick, so that holding it does not empty the magazine. */
    private boolean triggerWasDown;
    /** Rounds until the next muzzle flash. See {@link #FLASH_EVERY}. */
    private int untilFlash;

    public MainGun(GroundVehicleEntity vehicle) {
        this.vehicle = vehicle;
    }

    /**
     * Once a tick, on the server. The trigger is the crew's; the reload is the loader's, and runs
     * whether or not anybody is aboard.
     */
    public void tick(boolean trigger) {
        int reload = this.vehicle.getReload();

        if (reload > 0) {
            this.vehicle.setReload(reload - 1);
        }

        boolean wasDown = this.triggerWasDown;
        this.triggerWasDown = trigger;

        GroundVehicleDefinition.Armament armament = this.vehicle.getStats().armament();

        if (!armament.exists() || !(this.vehicle.level() instanceof ServerLevel level)) {
            return;
        }

        ResourceLocation weaponId = armament.main().orElseThrow();
        WeaponDefinition weapon = Definitions.weapon(weaponId);

        // The crew at work on a vehicle that is standing still: shells and belts out of the hold and
        // into the magazine. Not on the move, because nobody passes a shell up while the hull is
        // pitching about; and not out of nothing, which is what this used to be.
        if (Math.abs(this.vehicle.getSpeed()) < STANDING) {
            this.resupply(weapon);
        }

        // Whether holding the trigger down keeps it firing is the weapon's own to say rather than
        // this class's. A tank gun is a thing you press — a loader takes several seconds and letting
        // go the instant they finish is not how anybody fires one — and that is still what a weapon
        // file gets by leaving the field out and naming a rate of a fraction of a round a second.
        // An autocannon on a launcher is the same class of thing bolted into the same sort of
        // turret, and is a thing you hold down. See WeaponDefinition.isAutomatic.
        boolean pressed = trigger && (weapon.isAutomatic() || !wasDown);

        if (!pressed || reload > 0 || this.vehicle.getRounds() <= 0) {
            return;
        }

        this.fire(level, weaponId, weapon);
    }

    /**
     * One tick of the loaders: a whole shell, or a whole belt, out of the hold and into the
     * magazine — if one is due this tick and there is room for it.
     *
     * <p><b>A whole one or none.</b> The magazine is counted in rounds and the hold in items, and
     * the crew do not cut a belt in half: a magazine with room for less than one more item is as
     * full as it is going to get. Which costs almost nothing — every gun here but one holds a whole
     * number of items, and the Pantsir's fourteen hundred rounds come to forty-six belts and a
     * twentieth — and buys an ammunition item that is a plain stackable crate rather than one that
     * has to remember how much of itself is left.
     *
     * <p>The rate is a full magazine in {@link #RESUPPLY_TICKS} however big it is, which for a tank
     * that holds forty shells is one every four ticks and for an autocannon that holds forty-six
     * belts is very nearly the same. So a gun is not loaded faster by being bigger, and nothing is
     * loaded in a tick.
     */
    private void resupply(WeaponDefinition weapon) {
        AmmoKind kind = weapon.ammoKind();
        int capacity = weapon.ammo();
        int perItem = kind.roundsPerItem();

        if (capacity - this.vehicle.getRounds() < perItem) {
            return;
        }

        int every = Math.max(1, Math.round((float) RESUPPLY_TICKS * perItem / capacity));

        if (this.vehicle.tickCount % every != 0 || !this.take(kind)) {
            return;
        }

        this.vehicle.setRounds(this.vehicle.getRounds() + perItem);
    }

    /**
     * Takes one item of ammunition out of the hold, in the order whoever packed it laid it out.
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

    /** How many rounds a full magazine holds, from the weapon's own file. */
    public int capacity() {
        return this.vehicle.getStats().armament().main()
                .map(id -> Definitions.weapon(id).ammo())
                .orElse(0);
    }

    /** How long the loader takes, in ticks, from the weapon's rate of fire. */
    public int reloadTicks() {
        return this.vehicle.getStats().armament().main()
                .map(id -> ticksFor(Definitions.weapon(id).firing().roundsPerSecond()))
                .orElse(1);
    }

    private static int ticksFor(float roundsPerSecond) {
        return roundsPerSecond <= 0.0F ? 1 : Math.max(1, Math.round(20.0F / roundsPerSecond));
    }

    /**
     * Sends the round on its way, and does to the vehicle what firing does to it.
     *
     * <p>The round leaves along the bore rather than along the hull: where a tank is pointing and
     * where its gun is pointing are different questions, and the second one is the whole reason a
     * turret exists. The scatter is a cone about that, built across the bore rather than across the
     * world, so a gun laid straight up scatters no differently from one laid flat.
     */
    private void fire(ServerLevel level, ResourceLocation weaponId, WeaponDefinition weapon) {
        Vec3 muzzle = this.vehicle.getMuzzle(1.0F);
        Vec3 bore = this.vehicle.getAimDirection(1.0F);
        Vec3 right = across(bore);
        Vec3 up = right.cross(bore).normalize();
        LivingEntity crew = this.vehicle.getControllingPassenger();
        RandomSource random = this.vehicle.getRandom();

        double scatter = Math.tan(Math.toRadians(weapon.firing().spread())) * 0.5;
        double spread = Math.tan(Math.toRadians(weapon.firing().salvoSpread())) * 0.5;

        for (int i = 0; i < Math.max(1, weapon.firing().salvo()); i++) {
            Vec3 direction = bore
                    .add(right.scale(random.nextGaussian() * (scatter + spread)))
                    .add(up.scale(random.nextGaussian() * (scatter + spread)))
                    .normalize();

            VehicleProjectile shot = weapon.type() == WeaponDefinition.Type.GUN
                    ? new BulletEntity(ModEntities.BULLET.get(), level)
                    : new RocketEntity(ModEntities.ROCKET.get(), level);

            shot.setup(weaponId, this.vehicle, crew);
            shot.setPos(muzzle);
            // launch rather than setDeltaMovement: the speed has to reach the clients, and it is far
            // too fast for the packets that would ordinarily carry it. See VehicleProjectile.
            shot.launch(direction.scale(weapon.projectile().speed()));

            level.addFreshEntity(shot);
        }

        if (--this.untilFlash <= 0) {
            WeaponEffects.muzzleBlast(level, muzzle, bore, blastPower(weapon), weapon.projectile().tracer());
            this.untilFlash = weapon.isAutomatic() ? FLASH_EVERY : 1;
        }

        this.playFireSound(weapon, weaponId);

        this.vehicle.setRounds(this.vehicle.getRounds() - 1);
        this.vehicle.setReload(ticksFor(weapon.firing().roundsPerSecond()));
    }

    /**
     * A unit vector across the bore, for building the scatter cone.
     *
     * <p>Taken against the world's vertical, which fails only for a gun laid exactly at the zenith.
     * A tank cannot reach it and an anti-aircraft mounting very nearly can, so it is answered rather
     * than left to divide by nothing: at the pole every direction across the bore is as good as
     * every other, and any one of them will do.
     *
     * <p>Shared with {@link TurretLauncher}, whose tubes are laid by the same mounting and scatter
     * about the same line.
     */
    static Vec3 across(Vec3 bore) {
        Vec3 right = bore.cross(new Vec3(0.0, 1.0, 0.0));

        return right.lengthSqr() < 1.0E-8 ? new Vec3(1.0, 0.0, 0.0) : right.normalize();
    }

    /** How big the flash is, from what the round carries. Never nothing: every gun has a muzzle. */
    private static float blastPower(WeaponDefinition weapon) {
        return Mth.clamp(weapon.projectile().explosion(), 1.5F, 6.0F);
    }

    /**
     * The report: the event the weapon's file names, else one named after the weapon. Sent with the
     * reach in the volume slot rather than the loudness, for the reason set out in
     * {@code WeaponMounts.playFireSound} — that slot is the only thing deciding who is told about
     * the sound at all, and a tank gun is heard a long way further than thirty-two blocks.
     */
    private void playFireSound(WeaponDefinition weapon, ResourceLocation weaponId) {
        ResourceLocation event = weapon.sound().fire()
                .orElseGet(() -> weaponId.withPath(WeaponMounts.SOUND_PREFIX + weaponId.getPath()));

        this.vehicle.level().playSound(null, this.vehicle.getX(), this.vehicle.getY(), this.vehicle.getZ(),
                SoundEvent.createVariableRangeEvent(event), SoundSource.NEUTRAL,
                weapon.sound().packetVolume(), weapon.sound().pitch());
    }

    public void load(CompoundTag tag) {
        // A vehicle written to the world before this was a gun comes back with a full magazine
        // rather than an empty one, which is the kinder of the two guesses.
        this.vehicle.setRounds(tag.contains("Rounds") ? tag.getInt("Rounds") : this.capacity());
        this.vehicle.setReload(tag.getInt("Reload"));
    }

    public void save(CompoundTag tag) {
        tag.putInt("Rounds", this.vehicle.getRounds());
        tag.putInt("Reload", this.vehicle.getReload());
    }

}
