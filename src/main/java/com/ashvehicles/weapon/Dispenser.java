package com.ashvehicles.weapon;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.aircraft.Attitude;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.CountermeasureEntity;
import com.ashvehicles.registry.ModEntities;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * The countermeasure dispenser: what the pilot pulls when the warning receiver starts shouting.
 *
 * <p>Two loads, spent separately, because they are answers to two different questions — a flare for
 * anything homing on heat, chaff for anything homing on a radar return. What the receiver is saying
 * decides which handle is the right one, and the wrong one does nothing at all. See
 * {@link com.ashvehicles.client.RadarDisplay}.
 *
 * <p>The counts live in the aircraft's synched data rather than here, so that the instruments can
 * read them without being sent anything of their own. Everything else — when the dispenser will let
 * go of the next one, where it comes out, how quickly the ground crew put more in — is the server's
 * and is here.
 */
public final class Dispenser {
    /** The dispenser going off. Named by the server, so it lives on this side. */
    public static final ResourceLocation RELEASE_SOUND =
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, WeaponMounts.SOUND_PREFIX + "decoy");
    public static final float RELEASE_VOLUME = 1.2F;
    public static final float RELEASE_PITCH = 1.0F;

    /** How far behind and below the aircraft they come out, in the aircraft's own axes. */
    private static final Vec3 PORT = new Vec3(0.0, -0.6, -2.0);
    /** How much of the aircraft's own speed they keep. They are thrown clear, not fired. */
    private static final double CARRIED = 0.55;
    /** And how much they are scattered as they go, so a burst is a cloud rather than a line. */
    private static final double SCATTER = 0.12;

    private final AircraftEntity aircraft;
    /** Ticks until the dispenser will part with the next one. */
    private int cooldown;
    /** Ticks of ground time counted towards putting another one in each magazine. */
    private int reloading;

    public Dispenser(AircraftEntity aircraft) {
        this.aircraft = aircraft;
    }

    /**
     * One tick of the dispenser.
     *
     * @param flare whether the pilot is asking for a flare this tick
     * @param chaff and whether they are asking for chaff
     */
    public void tick(boolean flare, boolean chaff) {
        if (!(this.aircraft.level() instanceof ServerLevel level)) {
            return;
        }

        if (this.cooldown > 0) {
            this.cooldown--;
        }

        AircraftDefinition.Countermeasures setup = this.aircraft.getStats().countermeasures();

        // One at a time and one sort at a time: asking for both at once sends the flare first and
        // the chaff on the next release, rather than emptying two magazines into the same second.
        if (this.cooldown <= 0 && (flare || chaff)) {
            boolean sendFlare = flare && this.aircraft.getCountermeasures(true) > 0;
            boolean sendChaff = !sendFlare && chaff && this.aircraft.getCountermeasures(false) > 0;

            if (sendFlare || sendChaff) {
                this.release(level, setup, sendFlare);
                this.cooldown = Math.max(setup.intervalTicks(), 1);
            }
        }

        this.reload(setup);
    }

    /** Throws one clear of the aircraft, and tells everyone nearby that it happened. */
    private void release(ServerLevel level, AircraftDefinition.Countermeasures setup, boolean flare) {
        RandomSource random = this.aircraft.getRandom();
        Vec3 at = this.aircraft.toWorld(PORT, 1.0F);
        // Down and back, in the aircraft's own axes, so a decoy always leaves an aeroplane the way it
        // would leave an aeroplane rather than always falling south.
        Vec3 away = Attitude.toWorld(this.aircraft.getAttitude(), new Vec3(0.0, -1.0, -0.35)).normalize();

        CountermeasureEntity decoy = new CountermeasureEntity(ModEntities.COUNTERMEASURE.get(), level);

        decoy.setFlare(flare);
        decoy.setPos(at);
        decoy.setDeltaMovement(this.aircraft.getVelocity().scale(CARRIED)
                .add(away.scale(setup.speed()))
                .add(random.nextGaussian() * SCATTER, random.nextGaussian() * SCATTER,
                        random.nextGaussian() * SCATTER));

        level.addFreshEntity(decoy);
        this.aircraft.setCountermeasures(flare, this.aircraft.getCountermeasures(flare) - 1);

        level.playSound(null, this.aircraft.getX(), this.aircraft.getY(), this.aircraft.getZ(),
                SoundEvent.createVariableRangeEvent(RELEASE_SOUND), SoundSource.NEUTRAL,
                RELEASE_VOLUME, RELEASE_PITCH);
    }

    /**
     * Puts one back in each magazine every so often, with the aircraft parked.
     *
     * <p>Counted as time for a whole load rather than time per round, so an aircraft that has fired
     * everything is ready again after {@code reload_ticks} whether it carries ten or sixty.
     */
    private void reload(AircraftDefinition.Countermeasures setup) {
        if (!this.aircraft.isParked() || this.aircraft.getThrottle() > 0.0F) {
            this.reloading = 0;

            return;
        }

        int biggest = Math.max(Math.max(setup.flares(), setup.chaff()), 1);
        int perRound = Math.max(setup.reloadTicks() / biggest, 1);

        if (++this.reloading < perRound) {
            return;
        }

        this.reloading = 0;

        for (boolean flare : new boolean[] {true, false}) {
            int carried = this.aircraft.getCountermeasures(flare);

            if (carried < setup.capacity(flare)) {
                this.aircraft.setCountermeasures(flare, carried + 1);
            }
        }
    }
}
