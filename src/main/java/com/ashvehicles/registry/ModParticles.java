package com.ashvehicles.registry;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.particle.TintedParticleType;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Everything the mod draws for itself. None of it is vanilla's: what the mod needs is smoke that can be
 * seen from a long way off, drawn in whatever colour a weapon's file asks for, and lit properly out
 * where there is no chunk to read a light level from — and none of vanilla's particles do any of
 * those three things.
 *
 * <p>They are all the same type underneath ({@link TintedParticleType}); what makes them different
 * is the textures their file in {@code assets/ashvehicles/particles/} names, and the class the
 * client hangs off them in {@code AshVehiclesClient}.
 */
public final class ModParticles {
    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, AshVehicles.MODID);

    /** The plume at the nozzle while a motor is still burning: dense, hot, and quickly gone. */
    public static final DeferredHolder<ParticleType<?>, TintedParticleType> MOTOR_SMOKE = register("motor_smoke");
    /** What is left hanging in the air afterwards, which is the trail a pilot actually sees. */
    public static final DeferredHolder<ParticleType<?>, TintedParticleType> CONTRAIL = register("contrail");
    /** The fireball of a detonation. */
    public static final DeferredHolder<ParticleType<?>, TintedParticleType> BLAST = register("blast");
    /** And the cloud it rolls up into. */
    public static final DeferredHolder<ParticleType<?>, TintedParticleType> BLAST_SMOKE = register("blast_smoke");
    /** The front of a big one, running out across the ground. */
    public static final DeferredHolder<ParticleType<?>, TintedParticleType> SHOCKWAVE = register("shockwave");
    /** Fragments thrown out of a hit, bright enough to read against anything. */
    public static final DeferredHolder<ParticleType<?>, TintedParticleType> SPARK = register("spark");
    /** Chips off whatever was struck, in the colour of the block they came off. */
    public static final DeferredHolder<ParticleType<?>, TintedParticleType> DEBRIS = register("debris");
    /** Condensation off a hard-working wing. The one thing here that is not a weapon's doing. */
    public static final DeferredHolder<ParticleType<?>, TintedParticleType> VAPOUR = register("vapour");

    private static DeferredHolder<ParticleType<?>, TintedParticleType> register(String name) {
        return PARTICLE_TYPES.register(name, TintedParticleType::new);
    }

    private ModParticles() {
    }
}
