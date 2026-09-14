package com.ashvehicles;

import org.slf4j.Logger;

import com.ashvehicles.ai.AiConfig;
import com.ashvehicles.registry.ModBlocks;
import com.ashvehicles.registry.ModCreativeTabs;
import com.ashvehicles.registry.ModEntities;
import com.ashvehicles.registry.ModItems;
import com.ashvehicles.registry.ModMenus;
import com.ashvehicles.registry.ModParticles;
import com.ashvehicles.registry.ModRecipes;
import com.ashvehicles.registry.ModRegisters;
import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.BuiltInRegistries;
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

// ここの値は META-INF/neoforge.mods.toml の項目と一致していること
@Mod(AshVehicles.MODID)
public class AshVehicles {
    // 全体から参照する MOD ID
    public static final String MODID = "ashvehicles";
    // slf4j のロガー
    public static final Logger LOGGER = LogUtils.getLogger();

    // MOD クラスのコンストラクタは読み込み時に最初に走る。IEventBus や ModContainer のような
    // 引数型は FML が認識して自動で渡してくる。
    public AshVehicles(IEventBus modEventBus, ModContainer modContainer) {
        // 何よりも先に。コンテンツパックの名前空間ごとのレジスタはこの後の registry パッケージの初期化中
        // に作られ、その場でこのバスへ繋がれる。ModRegisters 参照。
        ModRegisters.bind(modEventBus);

        // MOD 読み込み用に commonSetup を登録
        modEventBus.addListener(this::commonSetup);

        // タブが登録されるよう DeferredRegister を MOD イベントバスへ
        ModCreativeTabs.TABS.register(modEventBus);

        // この MOD の中身は registry パッケージにある
        ModEntities.ENTITY_TYPES.register(modEventBus);
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModParticles.PARTICLE_TYPES.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);
        ModRecipes.RECIPE_TYPES.register(modEventBus);
        ModRecipes.RECIPE_SERIALIZERS.register(modEventBus);

        // サーバーイベント等を受け取るために自身を登録する。必要なのは *このクラス* が直接イベントに
        // 応じる場合だけ（下の onServerStarting のような @SubscribeEvent メソッドが無いなら不要）。
        NeoForge.EVENT_BUS.register(this);

        // FML に設定ファイルを作らせ読ませるため ModConfigSpec を登録
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        // 戦闘 AI の設定。周期・予算・記録・報酬・自己対戦。ワールドごと（serverconfig/）に持つ。
        modContainer.registerConfig(ModConfig.Type.SERVER, AiConfig.SPEC, AiConfig.FILE);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // 共通の初期化処理
        LOGGER.info("HELLO FROM COMMON SETUP");

        if (Config.LOG_DIRT_BLOCK.getAsBoolean()) {
            LOGGER.info("DIRT BLOCK >> {}", BuiltInRegistries.BLOCK.getKey(Blocks.DIRT));
        }

        LOGGER.info("{}{}", Config.MAGIC_NUMBER_INTRODUCTION.get(), Config.MAGIC_NUMBER.getAsInt());

        Config.ITEM_STRINGS.get().forEach((item) -> LOGGER.info("ITEM >> {}", item));
    }

    // SubscribeEvent を付ければイベントバスが呼び出し先を見つけてくれる
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // サーバー起動時の処理
        LOGGER.info("HELLO from server starting");
    }
}
