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
     * The mod's five tabs, one per sort of thing there is to carry: the machines that fly, the
     * machines that drive, the ships that float, everything a machine is fed — shells, belts,
     * rockets, bombs and the gun pods that hang under a wing — and the one remaining thing, the
     * wrench that takes a machine apart again.
     *
     * <p>Each tab is drawn as one of its own, so a wall of aeroplanes looks like an aeroplane and a
     * page of shells looks like a shell; the fallback for any that is somehow emptied is the wrench.
     *
     * <p>They are told to sit <em>after</em> the vanilla Spawn Eggs tab rather than before Combat,
     * so the mod's pages collect at the end of the bar and leave the game's own tabs exactly where
     * they were — a mod tab sitting where Spawn Eggs should be is what this replaced.
     */
    /** The aeroplanes. */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> AIRCRAFT_TAB = CREATIVE_MODE_TABS.register("aircraft",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.ashvehicles.aircraft"))
                    .withTabsBefore(CreativeModeTabs.SPAWN_EGGS)
                    .icon(AshVehicles::aircraftIcon)
                    .displayItems((parameters, output) ->
                            ModItems.aircraft().values().forEach(item -> output.accept(item.get())))
                    .build());

    /** The cars and the tanks. */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> VEHICLES_TAB = CREATIVE_MODE_TABS.register("vehicles",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.ashvehicles.vehicles"))
                    .withTabsBefore(AIRCRAFT_TAB.getKey())
                    .icon(AshVehicles::vehiclesIcon)
                    .displayItems((parameters, output) ->
                            ModItems.landVehicles().values().forEach(item -> output.accept(item.get())))
                    .build());

    /** The boats. */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> SHIPS_TAB = CREATIVE_MODE_TABS.register("ships",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.ashvehicles.ships"))
                    .withTabsBefore(VEHICLES_TAB.getKey())
                    .icon(AshVehicles::shipsIcon)
                    .displayItems((parameters, output) ->
                            ModItems.ships().values().forEach(item -> output.accept(item.get())))
                    .build());

    /** The shells, the belts, the rockets and bombs, and the gun pods that hang under a wing. */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> AMMO_TAB = CREATIVE_MODE_TABS.register("ammo",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.ashvehicles.ammo"))
                    .withTabsBefore(SHIPS_TAB.getKey())
                    .icon(AshVehicles::ammoIcon)
                    .displayItems((parameters, output) -> {
                        ModItems.ammo().values().forEach(item -> output.accept(item.get()));
                        ModItems.explosiveStores().values().forEach(item -> output.accept(item.get()));
                        ModItems.gunPods().values().forEach(item -> output.accept(item.get()));
                    }).build());

    /** The wrench, which is the one thing nobody needs a shelf of. */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> ITEMS_TAB = CREATIVE_MODE_TABS.register("items",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.ashvehicles.items"))
                    .withTabsBefore(AMMO_TAB.getKey())
                    .icon(AshVehicles::itemsIcon)
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.WRENCH.get());
                    }).build());

    /** What the aeroplane tab is drawn as: its own picture. */
    private static ItemStack aircraftIcon() {
        return ModItems.TAB_AIR.get().getDefaultInstance();
    }

    /** What the ground tab is drawn as: its own picture. */
    private static ItemStack vehiclesIcon() {
        return ModItems.TAB_TANK.get().getDefaultInstance();
    }

    /** What the ships tab is drawn as: its own picture. */
    private static ItemStack shipsIcon() {
        return ModItems.TAB_SHIP.get().getDefaultInstance();
    }

    /** What the ammunition tab is drawn as: its own picture. */
    private static ItemStack ammoIcon() {
        return ModItems.TAB_AMMO.get().getDefaultInstance();
    }

    /** What the items tab is drawn as: its own picture. */
    private static ItemStack itemsIcon() {
        return ModItems.TAB_ITEM.get().getDefaultInstance();
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
