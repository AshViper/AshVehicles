package com.ashvehicles.weapon;

import java.util.Optional;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.VehicleProjectile;
import com.ashvehicles.entity.BulletEntity;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.RocketEntity;
import com.ashvehicles.entity.VehicleHold;
import com.ashvehicles.item.AmmoItem;
import com.ashvehicles.registry.ModEntities;

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
 * A gun built into a vehicle rather than hung on it: a barrel, a magazine, and a wait between
 * rounds. There are two of them on a tank — the main armament in the turret and the machine gun
 * clamped beside it — and this is both.
 *
 * <p>Deliberately not {@link WeaponMounts}. A pylon is a place a store is hung and taken off again,
 * and most of that class is about which station is selected, what is on it, and what the seeker is
 * holding. A tank's guns are none of those things: they are built in, there is one of each, and the
 * only questions are whether one is loaded and where it is pointing. What the two do share is the
 * weapon files — how hard a round hits, how fast it leaves and how often one can be fired are read
 * from {@code data/ashvehicles/weapon/} exactly as an aircraft reads them, so a gun is described in
 * one place whether it is bolted into a turret or hung under a wing.
 *
 * <p><b>Why one class for both guns.</b> Everything below is the same for either: rounds come out
 * of the same hold, the wait between them is the same figure read from the same file, the round
 * leaves the same way and scatters about the same cone. What differs is five things — which weapon
 * it is, which pair of counters it keeps, where its muzzles are, how many of them it has, and what
 * its rounds are called in the save — and those five are the whole of {@link Mount}. Written twice
 * instead, the pair would have drifted the first time either was fixed.
 *
 * <p><b>Whether one press is one round is the weapon's to say.</b> A tank gun is read on the
 * trigger's rising edge: a loader takes several seconds, and one that let go the moment they were
 * finished is not how anybody fires one and takes the aiming out of it entirely. A machine gun or an
 * autocannon is the opposite — it is a thing you hold down, and a burst is the whole of how it is
 * aimed. Both are this class; which one a barrel is is {@link WeaponDefinition#isAutomatic()}, read
 * from the weapon's own file.
 *
 * <p><b>What it fires is what somebody loaded.</b> The magazine is filled out of the vehicle's own
 * hold, a shell or a belt at a time, and only while the vehicle is standing still — see
 * {@link #resupply}. There is no free load: a tank put down out of the creative tab has an empty gun
 * until somebody puts ammunition in it, which is the arrangement an aircraft's pylons have always
 * had.
 *
 * <p><b>Where the state lives.</b> Rounds and the reload counter are synched data on the vehicle
 * rather than fields here, because the client needs both: the main gun's reload counter is what the
 * barrel's recoil is drawn from, and it is enough on its own — a counter that has just jumped to its
 * maximum <em>is</em> the news that the gun has fired, so nothing else has to be sent to say so.
 */
public final class BuiltInGun {
    /**
     * Which of a vehicle's two built-in guns this is: what it fires, what it keeps its count in, and
     * where its rounds leave from.
     *
     * <p>Everything a gun does is the same gun to gun. Everything a gun <em>is</em> is here, and it
     * is five questions long.
     */
    public enum Mount {
        /**
         * The main armament: the gun the turret is built round, the one that recoils and shoves the
         * hull about, and the one the crew put away when they select missiles instead.
         */
        MAIN {
            @Override
            Optional<ResourceLocation> weapon(GroundVehicleEntity vehicle) {
                return vehicle.getStats().armament().main();
            }

            @Override
            int rounds(GroundVehicleEntity vehicle) {
                return vehicle.getRounds();
            }

            @Override
            void rounds(GroundVehicleEntity vehicle, int rounds) {
                vehicle.setRounds(rounds);
            }

            @Override
            int reload(GroundVehicleEntity vehicle) {
                return vehicle.getReload();
            }

            @Override
            void reload(GroundVehicleEntity vehicle, int ticks) {
                vehicle.setReload(ticks);
            }

            @Override
            Vec3 muzzle(GroundVehicleEntity vehicle, int barrel) {
                return vehicle.getMuzzle(barrel, 1.0F);
            }

            @Override
            int barrels(GroundVehicleEntity vehicle) {
                return vehicle.getBarrelCount();
            }

            @Override
            String tag() {
                return "";
            }
        },
        /**
         * The machine gun clamped to the main gun, laid wherever it is laid and fired on a trigger
         * of its own. Its muzzle is a fixed point on the gun rather than a length down a barrel:
         * there is no recoil to slide it back and nothing that needs the barrel's length, so where
         * the rounds leave is simply where the file says they do — see
         * {@link com.ashvehicles.vehicle.GroundVehicleDefinition.Coaxial}.
         */
        COAXIAL {
            @Override
            Optional<ResourceLocation> weapon(GroundVehicleEntity vehicle) {
                return vehicle.getStats().coaxial().gun();
            }

            @Override
            int rounds(GroundVehicleEntity vehicle) {
                return vehicle.getCoaxRounds();
            }

            @Override
            void rounds(GroundVehicleEntity vehicle, int rounds) {
                vehicle.setCoaxRounds(rounds);
            }

            @Override
            int reload(GroundVehicleEntity vehicle) {
                return vehicle.getCoaxReload();
            }

            @Override
            void reload(GroundVehicleEntity vehicle, int ticks) {
                vehicle.setCoaxReload(ticks);
            }

            @Override
            Vec3 muzzle(GroundVehicleEntity vehicle, int barrel) {
                return vehicle.gunToWorld(vehicle.getStats().coaxial().muzzle(), 1.0F);
            }

            @Override
            String tag() {
                return "Coax";
            }
        };

        /** Which weapon file this barrel is, or empty for a vehicle that has not got one. */
        abstract Optional<ResourceLocation> weapon(GroundVehicleEntity vehicle);

        abstract int rounds(GroundVehicleEntity vehicle);

        abstract void rounds(GroundVehicleEntity vehicle, int rounds);

        abstract int reload(GroundVehicleEntity vehicle);

        abstract void reload(GroundVehicleEntity vehicle, int ticks);

        /** Where one of this gun's rounds leaves, in the world, as of this tick. */
        abstract Vec3 muzzle(GroundVehicleEntity vehicle, int barrel);

        /**
         * How many barrels this gun fires out of in turn. One, unless the file says otherwise, and
         * a machine gun clamped to a mantlet has never had two.
         */
        int barrels(GroundVehicleEntity vehicle) {
            return 1;
        }

        /**
         * What this barrel's counters are called in the vehicle's tag. Empty for the main gun, whose
         * keys were written before there was a second barrel and are left exactly as they were, so
         * that a tank saved by an older world comes back with its shells.
         */
        abstract String tag();
    }

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
    private final Mount mount;
    /** Whether the trigger was down last tick, so that holding it does not empty the magazine. */
    private boolean triggerWasDown;
    /**
     * Which barrel the next round comes out of, for a mount with more than one. Kept here rather
     * than on the vehicle because nothing but this class has ever needed to know: the flash and the
     * round are both put where they belong by the server, and what the clients draw of the mount
     * itself — the recoil — is the whole thing running back, not one barrel of it. A gun that comes
     * back from a save starting at its first barrel again is a gun nobody can tell was interrupted.
     */
    private int barrel;
    /** Rounds until the next muzzle flash. See {@link #FLASH_EVERY}. */
    private int untilFlash;

    public BuiltInGun(GroundVehicleEntity vehicle, Mount mount) {
        this.vehicle = vehicle;
        this.mount = mount;
    }

    /**
     * Once a tick, on the server. The trigger is the crew's; the reload is the loader's, and runs
     * whether or not anybody is aboard.
     */
    public void tick(boolean trigger) {
        int reload = this.mount.reload(this.vehicle);

        if (reload > 0) {
            this.mount.reload(this.vehicle, reload - 1);
        }

        boolean wasDown = this.triggerWasDown;
        this.triggerWasDown = trigger;

        Optional<ResourceLocation> fitted = this.mount.weapon(this.vehicle);

        if (fitted.isEmpty() || !(this.vehicle.level() instanceof ServerLevel level)) {
            return;
        }

        ResourceLocation weaponId = fitted.get();
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
        // A machine gun in the same mantlet is the same class of thing, and is a thing you hold
        // down. See WeaponDefinition.isAutomatic.
        boolean pressed = trigger && (weapon.isAutomatic() || !wasDown);

        if (!pressed || reload > 0 || this.mount.rounds(this.vehicle) <= 0) {
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

        if (capacity - this.mount.rounds(this.vehicle) < perItem) {
            return;
        }

        int every = Math.max(1, Math.round((float) RESUPPLY_TICKS * perItem / capacity));

        if (this.vehicle.tickCount % every != 0 || !this.take(kind)) {
            return;
        }

        this.mount.rounds(this.vehicle, this.mount.rounds(this.vehicle) + perItem);
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

    /** Whether this barrel is fitted at all. */
    public boolean exists() {
        return this.mount.weapon(this.vehicle).isPresent();
    }

    /** How many rounds a full magazine holds, from the weapon's own file. */
    public int capacity() {
        return this.mount.weapon(this.vehicle)
                .map(id -> Definitions.weapon(id).ammo())
                .orElse(0);
    }

    /** How long the loader takes, in ticks, from the weapon's rate of fire. */
    public int reloadTicks() {
        return this.mount.weapon(this.vehicle)
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
     * turret exists. A coaxial is clamped to that same gun and so leaves along the same line — which
     * is the whole of what makes it coaxial. The scatter is a cone about that, built across the bore
     * rather than across the world, so a gun laid straight up scatters no differently from one laid
     * flat.
     */
    private void fire(ServerLevel level, ResourceLocation weaponId, WeaponDefinition weapon) {
        // Round about the barrels, one round each. Every barrel of a mount is laid the same way and
        // loaded off the same magazine at the same rate -- a twin mounting is two holes for the
        // rounds to leave by, not two guns -- so the whole of being one is which muzzle this round
        // comes out of. What a file wants instead when it wants two rounds at once is the weapon's
        // own salvo, a few lines below.
        int barrels = Math.max(this.mount.barrels(this.vehicle), 1);
        int firing = this.barrel % barrels;

        this.barrel = (firing + 1) % barrels;

        Vec3 muzzle = this.mount.muzzle(this.vehicle, firing);
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

            // What the barrel adds, plus what the vehicle was already doing along it. Only the part
            // along the bore, never the whole velocity: a hull crabbing sideways down a slope would
            // otherwise bend every round off the barrel by the angle it was sliding at. The same
            // rule a pylon follows — see WeaponMounts.fireRound — and the same one GunSight flies
            // its trajectory with, which is what makes the mark on the screen the truth.
            Vec3 carried = direction.scale(Math.max(0.0, this.vehicle.getVelocity().dot(direction)));

            VehicleProjectile shot = weapon.type() == WeaponDefinition.Type.GUN
                    ? new BulletEntity(ModEntities.BULLET.get(), level)
                    : new RocketEntity(ModEntities.ROCKET.get(), level);

            shot.setup(weaponId, this.vehicle, crew);
            shot.setPos(muzzle);
            // launch rather than setDeltaMovement: the speed has to reach the clients, and it is far
            // too fast for the packets that would ordinarily carry it. See VehicleProjectile.
            shot.launch(direction.scale(weapon.projectile().speed()).add(carried));

            level.addFreshEntity(shot);
        }

        if (--this.untilFlash <= 0) {
            WeaponEffects.muzzleBlast(level, muzzle, bore, blastPower(weapon), weapon.projectile().tracer());
            this.untilFlash = weapon.isAutomatic() ? FLASH_EVERY : 1;
        }

        this.playFireSound(weapon, weaponId);

        this.mount.rounds(this.vehicle, this.mount.rounds(this.vehicle) - 1);
        this.mount.reload(this.vehicle, ticksFor(weapon.firing().roundsPerSecond()));
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
        String rounds = this.mount.tag() + "Rounds";

        // A vehicle written to the world before this was a gun comes back with a full magazine
        // rather than an empty one, which is the kinder of the two guesses.
        this.mount.rounds(this.vehicle, tag.contains(rounds) ? tag.getInt(rounds) : this.capacity());
        this.mount.reload(this.vehicle, tag.getInt(this.mount.tag() + "Reload"));
    }

    public void save(CompoundTag tag) {
        tag.putInt(this.mount.tag() + "Rounds", this.mount.rounds(this.vehicle));
        tag.putInt(this.mount.tag() + "Reload", this.mount.reload(this.vehicle));
    }

}
