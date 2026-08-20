package com.ashvehicles;

import com.ashvehicles.client.ModKeyMappings;
import com.ashvehicles.client.ghost.EntityGhostRegistry;
import com.ashvehicles.client.ghost.GhostConfig;
import com.ashvehicles.client.ghost.adapter.AircraftGhostAdapter;
import com.ashvehicles.client.ghost.adapter.BulletGhostAdapter;
import com.ashvehicles.client.ghost.adapter.RocketGhostAdapter;
import com.ashvehicles.client.particle.BlastParticle;
import com.ashvehicles.client.particle.SmokeParticle;
import com.ashvehicles.client.particle.ShockwaveParticle;
import com.ashvehicles.client.particle.SparkParticle;
import com.ashvehicles.client.renderer.AircraftRenderer;
import com.ashvehicles.client.renderer.BulletRenderer;
import com.ashvehicles.client.renderer.RocketRenderer;
import com.ashvehicles.registry.ModEntities;
import com.ashvehicles.registry.ModParticles;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = AshVehicles.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public class AshVehiclesClient {
    public AshVehiclesClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        // The ghost system's settings: distances, budgets, debug. Client only, like the system.
        container.registerConfig(ModConfig.Type.CLIENT, GhostConfig.SPEC);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Some client setup code
        AshVehicles.LOGGER.info("HELLO FROM CLIENT SETUP");
        AshVehicles.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());

        // Which entities become ghosts beyond the normal drawing range, and how. All of them work
        // the same way: past ghostStartDistance the game's renderer stands down and the ghost pass
        // draws from a snapshot, whether the thing is an aeroplane, a missile or a tracer.
        AircraftGhostAdapter aircraft = new AircraftGhostAdapter();
        ModEntities.aircraft().values().forEach(type -> EntityGhostRegistry.register(type.get(), aircraft));
        EntityGhostRegistry.register(ModEntities.BULLET.get(), new BulletGhostAdapter());
        EntityGhostRegistry.register(ModEntities.ROCKET.get(), new RocketGhostAdapter());
    }

    /** The ghost distances are squared once here rather than every frame. */
    @SubscribeEvent
    static void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == GhostConfig.SPEC) {
            GhostConfig.refresh();
        }
    }

    @SubscribeEvent
    static void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == GhostConfig.SPEC) {
            GhostConfig.refresh();
        }
    }

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        ModEntities.aircraft().values()
                .forEach(type -> event.registerEntityRenderer(type.get(), AircraftRenderer::new));
        event.registerEntityRenderer(ModEntities.BULLET.get(), BulletRenderer::new);
        event.registerEntityRenderer(ModEntities.ROCKET.get(), RocketRenderer::new);
    }

    /**
     * What each of the mod's particles is actually drawn as. The type in the registry says only that
     * it exists and what textures it may use; everything about how it behaves is here.
     */
    @SubscribeEvent
    static void onRegisterParticleProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ModParticles.MOTOR_SMOKE.get(),
                sprites -> SmokeParticle.provider(sprites, SmokeParticle.MOTOR));
        event.registerSpriteSet(ModParticles.CONTRAIL.get(),
                sprites -> SmokeParticle.provider(sprites, SmokeParticle.CONTRAIL));
        event.registerSpriteSet(ModParticles.BLAST_SMOKE.get(),
                sprites -> SmokeParticle.provider(sprites, SmokeParticle.BLAST));
        event.registerSpriteSet(ModParticles.BLAST.get(), BlastParticle::provider);
        event.registerSpriteSet(ModParticles.SHOCKWAVE.get(), ShockwaveParticle::provider);
        event.registerSpriteSet(ModParticles.SPARK.get(), sprites -> SparkParticle.provider(sprites, true));
        event.registerSpriteSet(ModParticles.DEBRIS.get(), sprites -> SparkParticle.provider(sprites, false));
        event.registerSpriteSet(ModParticles.VAPOUR.get(),
                sprites -> SmokeParticle.provider(sprites, SmokeParticle.VAPOUR));
    }

    @SubscribeEvent
    static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        for (KeyMapping mapping : ModKeyMappings.ALL) {
            event.register(mapping);
        }
    }
}
