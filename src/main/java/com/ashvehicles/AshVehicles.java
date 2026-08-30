package com.ashvehicles;

import java.util.Collection;

import org.slf4j.Logger;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.registry.ModBlocks;
import com.ashvehicles.registry.ModEntities;
import com.ashvehicles.registry.ModItems;
import com.ashvehicles.registry.ModMenus;
import com.ashvehicles.registry.ModParticles;
import com.ashvehicles.registry.ModRecipes;
import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
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
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

// ここの値は META-INF/neoforge.mods.toml の項目と一致していること
@Mod(AshVehicles.MODID)
public class AshVehicles {
    // 全体から参照する MOD ID
    public static final String MODID = "ashvehicles";
    // slf4j のロガー
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    /**
     * 飛ぶもののタブ。固定翼機、続いて回転翼機、そして先頭に工具2つ。
     *
     * <p>元は1枚だった。機体・車両・ラック・兵装・ポッド・弾薬で50を超え、クリエイティブの1ページ
     * （45枠）に収まらないので、機体を1つ取るのに兵装をかき分けることになっていた。3枚に割るとどの
     * タブも1ページに収まり、スクロールが要らなくなる。
     *
     * <p>どのタブでも並びはレジストリ順ではなく格納庫の順。
     */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> AIRCRAFT_TAB =
            CREATIVE_MODE_TABS.register("aircraft",
                    () -> CreativeModeTab.builder()
                            .title(Component.translatable("itemGroup.ashvehicles.aircraft"))
                            .withTabsBefore(CreativeModeTabs.COMBAT)
                            .icon(() -> tabIcon(ModItems.aircraft().values()))
                            .displayItems((parameters, output) -> {
                                tools(output);
                                aircraft(output, false);
                                aircraft(output, true);
                            }).build());

    /** 地に足の着くもののタブ。地上車両、続いて艦艇。工具は機体のタブと同じく先頭に。 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> VEHICLE_TAB =
            CREATIVE_MODE_TABS.register("vehicles",
                    () -> CreativeModeTab.builder()
                            .title(Component.translatable("itemGroup.ashvehicles.vehicles"))
                            .withTabsBefore(CreativeModeTabs.COMBAT, AIRCRAFT_TAB.getKey())
                            .icon(() -> tabIcon(ModItems.vehicles().values()))
                            .displayItems((parameters, output) -> {
                                tools(output);
                                vehicles(output, false);
                                vehicles(output, true);
                            }).build());

    /**
     * 作って積むもののタブ。まず工廠と中間素材、続いてラック、そこに載る兵装、ポッド、最後にその中身。
     *
     * <p>工廠と素材がここにあるのは、機体のタブと車両のタブの両方に出すと同じ8個が2度並ぶからで、
     * どちらか片方に置けば残る片方だけを使う人が探しに行くことになるからだ。ここは元から「機体そのもの
     * ではなく、機体のために作る物」の棚で、素材はその一番手前に来る。
     */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> ARMAMENT_TAB =
            CREATIVE_MODE_TABS.register("armament",
                    () -> CreativeModeTab.builder()
                            .title(Component.translatable("itemGroup.ashvehicles.armament"))
                            .withTabsBefore(CreativeModeTabs.COMBAT, VEHICLE_TAB.getKey())
                            .icon(() -> tabIcon(ModItems.weapons().values()))
                            .displayItems((parameters, output) -> {
                                workshop(output);
                                ModItems.racks().values().forEach(item -> output.accept(item.get()));
                                ModItems.weapons().values().forEach(item -> output.accept(item.get()));
                                ModItems.equipment().values().forEach(item -> output.accept(item.get()));
                                ModItems.ammo().values().forEach(item -> output.accept(item.get()));
                            }).build());

    /**
     * ばらす道具と注ぐ燃料。機体にも車両にも要るものなので、機体のタブと車両のタブの両方に出す。
     * バニラでも道具は行き先の数だけ顔を出す。
     */
    private static void tools(CreativeModeTab.Output output) {
        output.accept(ModItems.WRENCH.get());
        output.accept(ModItems.FUEL_CAN.get());
    }

    /**
     * 工廠とその上で使う中間素材。作る順——板・部品・基板、そこから装甲板・エンジン・ジェットエンジン・
     * アビオニクス——に並ぶ。
     */
    private static void workshop(CreativeModeTab.Output output) {
        output.accept(ModItems.VEHICLE_WORKBENCH.get());
        ModItems.MATERIALS.forEach(item -> output.accept(item.get()));
    }

    /** 固定翼機か回転翼機か、片方だけを ID 順に。どちらかは機体ファイルの {@code type} が決める。 */
    private static void aircraft(CreativeModeTab.Output output, boolean helicopters) {
        ModItems.aircraft().forEach((id, item) -> {
            if (Definitions.AIRCRAFT.get(id).isHelicopter() == helicopters) {
                output.accept(item.get());
            }
        });
    }

    /** 地上車両か艦艇か、片方だけを ID 順に。こちらも決めるのは車両ファイルの {@code type}。 */
    private static void vehicles(CreativeModeTab.Output output, boolean ships) {
        ModItems.vehicles().forEach((id, item) -> {
            if (Definitions.VEHICLES.get(id).isShip() == ships) {
                output.accept(item.get());
            }
        });
    }

    /**
     * タブのアイコン。そのタブの中身の1つ目を使う。中身を全部削除したパック向けの保険としてレンチに
     * フォールバックする（まず起きないが、落ちる理由にはしない）。
     */
    private static ItemStack tabIcon(Collection<? extends DeferredItem<? extends Item>> items) {
        return items.stream().<Item>map(DeferredItem::get)
                .findFirst()
                .orElseGet(ModItems.WRENCH::get)
                .getDefaultInstance();
    }

    // MOD クラスのコンストラクタは読み込み時に最初に走る。IEventBus や ModContainer のような
    // 引数型は FML が認識して自動で渡してくる。
    public AshVehicles(IEventBus modEventBus, ModContainer modContainer) {
        // MOD 読み込み用に commonSetup を登録
        modEventBus.addListener(this::commonSetup);

        // タブが登録されるよう DeferredRegister を MOD イベントバスへ
        CREATIVE_MODE_TABS.register(modEventBus);

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
