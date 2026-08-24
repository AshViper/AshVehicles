package com.ashvehicles;

import org.slf4j.Logger;

import com.ashvehicles.registry.ModEntities;
import com.ashvehicles.registry.ModItems;
import com.ashvehicles.registry.ModParticles;
import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(AshVehicles.MODID)
public class AshVehicles {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "ashvehicles";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    /**
     * The mod's tab: the aeroplanes, the stores that go on them, and the tool for taking them apart.
     *
     * <p>Ordered as a hangar would be rather than as the registry happens to be: the wrench first,
     * because everything else needs it eventually, then the airframes, then what hangs on them, and
     * last what goes inside them.
     */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = CREATIVE_MODE_TABS.register("vehicles",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.ashvehicles"))
                    .withTabsBefore(CreativeModeTabs.COMBAT)
                    .icon(AshVehicles::tabIcon)
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.WRENCH.get());
                        ModItems.aircraft().values().forEach(item -> output.accept(item.get()));
                        ModItems.vehicles().values().forEach(item -> output.accept(item.get()));
                        ModItems.weapons().values().forEach(item -> output.accept(item.get()));
                        ModItems.ammo().values().forEach(item -> output.accept(item.get()));
                    }).build());

    /**
     * What the tab is drawn as: the first aeroplane there is, since a tab full of aeroplanes should
     * look like one. The wrench stands in for a pack that has removed every aircraft, which is not a
     * thing anybody is likely to do but is not worth crashing over either.
     */
    private static ItemStack tabIcon() {
        return ModItems.aircraft().values().stream()
                .findFirst()
                .map(item -> item.get().getDefaultInstance())
                .orElseGet(() -> ModItems.WRENCH.get().getDefaultInstance());
    }

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public AshVehicles(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register the Deferred Register to the mod event bus so tabs get registered
        CREATIVE_MODE_TABS.register(modEventBus);

        // The mod's own content lives in the registry package
        ModEntities.ENTITY_TYPES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModParticles.PARTICLE_TYPES.register(modEventBus);

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (AshVehicles) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // Some common setup code
        LOGGER.info("HELLO FROM COMMON SETUP");

        if (Config.LOG_DIRT_BLOCK.getAsBoolean()) {
            LOGGER.info("DIRT BLOCK >> {}", BuiltInRegistries.BLOCK.getKey(Blocks.DIRT));
        }

        LOGGER.info("{}{}", Config.MAGIC_NUMBER_INTRODUCTION.get(), Config.MAGIC_NUMBER.getAsInt());

        Config.ITEM_STRINGS.get().forEach((item) -> LOGGER.info("ITEM >> {}", item));
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Do something when the server starts
        LOGGER.info("HELLO from server starting");
    }
}
