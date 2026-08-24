package com.ashvehicles;

import com.ashvehicles.client.ModKeyMappings;
import com.ashvehicles.client.ghost.EntityGhostRegistry;
import com.ashvehicles.client.ghost.GhostConfig;
import com.ashvehicles.client.ghost.adapter.AircraftGhostAdapter;
import com.ashvehicles.client.ghost.adapter.BulletGhostAdapter;
import com.ashvehicles.client.ghost.adapter.GroundVehicleGhostAdapter;
import com.ashvehicles.client.ghost.adapter.RocketGhostAdapter;
import com.ashvehicles.client.particle.BlastParticle;
import com.ashvehicles.client.particle.FlameParticle;
import com.ashvehicles.client.particle.SmokeParticle;
import com.ashvehicles.client.particle.ShockwaveParticle;
import com.ashvehicles.client.particle.SparkParticle;
import com.ashvehicles.client.item.VehicleIcons;
import com.ashvehicles.client.item.VehicleItemModels;
import com.ashvehicles.client.item.VehicleItemRenderer;
import com.ashvehicles.client.model.WeaponModel;
import com.ashvehicles.client.renderer.AircraftRenderer;
import com.ashvehicles.client.renderer.BulletRenderer;
import com.ashvehicles.client.renderer.CountermeasureRenderer;
import com.ashvehicles.client.renderer.GroundVehicleRenderer;
import com.ashvehicles.client.renderer.RocketRenderer;
import com.ashvehicles.registry.ModEntities;
import com.ashvehicles.registry.ModItems;
import com.ashvehicles.registry.ModParticles;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.event.AddPackFindersEvent;
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
        GroundVehicleGhostAdapter ground = new GroundVehicleGhostAdapter();
        ModEntities.vehicles().values().forEach(type -> EntityGhostRegistry.register(type.get(), ground));
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
        ModEntities.vehicles().values()
                .forEach(type -> event.registerEntityRenderer(type.get(), GroundVehicleRenderer::new));
        event.registerEntityRenderer(ModEntities.BULLET.get(), BulletRenderer::new);
        event.registerEntityRenderer(ModEntities.ROCKET.get(), RocketRenderer::new);
        event.registerEntityRenderer(ModEntities.COUNTERMEASURE.get(), CountermeasureRenderer::new);
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
        event.registerSpriteSet(ModParticles.FIRE.get(), FlameParticle::provider);
        event.registerSpriteSet(ModParticles.SHOCKWAVE.get(), ShockwaveParticle::provider);
        event.registerSpriteSet(ModParticles.SPARK.get(), sprites -> SparkParticle.provider(sprites, true));
        event.registerSpriteSet(ModParticles.DEBRIS.get(), sprites -> SparkParticle.provider(sprites, false));
        event.registerSpriteSet(ModParticles.VAPOUR.get(),
                sprites -> SmokeParticle.provider(sprites, SmokeParticle.VAPOUR));
    }

    /**
     * An item model for every machine, generated rather than written out one file per machine. See
     * {@link VehicleItemModels}: what such a file has to say is the same for all of them, and none
     * of it is about the machine.
     */
    @SubscribeEvent
    static void onAddPackFinders(AddPackFindersEvent event) {
        VehicleItemModels.addTo(event);
    }

    /**
     * What a machine's item is drawn as: a picture taken from the machine's own geometry, rather
     * than a texture somebody had to make. See {@link VehicleIcons}.
     */
    @SubscribeEvent
    static void onRegisterClientExtensions(RegisterClientExtensionsEvent event) {
        IClientItemExtensions drawing = new IClientItemExtensions() {
            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return VehicleItemRenderer.instance();
            }
        };

        List<Item> machines = new ArrayList<>();
        ModItems.aircraft().values().forEach(item -> machines.add(item.get()));
        ModItems.vehicles().values().forEach(item -> machines.add(item.get()));

        event.registerItem(drawing, machines.toArray(Item[]::new));
    }

    /**
     * Where a machine's picture is taken: the top of a frame, one machine at a time.
     *
     * <p>Here rather than where it is first wanted because taking one means drawing a whole model
     * into a texture of its own, with the projection, the framing and the lighting all set aside and
     * put back. Doing that in the middle of drawing a screen is asking for the screen to be drawn
     * with somebody else's matrices; doing ten of them in one frame, which is what opening a
     * creative tab full of machines would ask for, is a visible stall.
     */
    @SubscribeEvent
    static void onRenderFrame(RenderFrameEvent.Pre event) {
        VehicleIcons.takeNext();
    }

    /**
     * Which files each weapon is drawn from is worked out once and remembered, because deciding it
     * means asking the resource manager and that ends in a file being looked for on disk — far too
     * much to do on every frame of every missile on the screen. A reload is the one thing that can
     * change the answer, so it is also the one thing that has to throw the answer away.
     *
     * <p>The pictures the machines' items are drawn as go the same way and for the same reason: they
     * were taken from geometry and textures that have just been read again.
     */
    @SubscribeEvent
    static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(
                (net.minecraft.server.packs.resources.ResourceManagerReloadListener) manager -> {
                    WeaponModel.clearCache();
                    VehicleIcons.forget();
                });
    }

    @SubscribeEvent
    static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        for (KeyMapping mapping : ModKeyMappings.ALL) {
            event.register(mapping);
        }
    }
}
