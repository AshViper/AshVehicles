package com.ashvehicles;

import com.ashvehicles.client.ModKeyMappings;
import com.ashvehicles.client.renderer.AircraftRenderer;
import com.ashvehicles.client.renderer.BulletRenderer;
import com.ashvehicles.client.renderer.RocketRenderer;
import com.ashvehicles.registry.ModEntities;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
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
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Some client setup code
        AshVehicles.LOGGER.info("HELLO FROM CLIENT SETUP");
        AshVehicles.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        ModEntities.aircraft().values()
                .forEach(type -> event.registerEntityRenderer(type.get(), AircraftRenderer::new));
        event.registerEntityRenderer(ModEntities.BULLET.get(), BulletRenderer::new);
        event.registerEntityRenderer(ModEntities.ROCKET.get(), RocketRenderer::new);
    }

    @SubscribeEvent
    static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        for (KeyMapping mapping : ModKeyMappings.ALL) {
            event.register(mapping);
        }
    }
}
