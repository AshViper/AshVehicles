package com.ashvehicles;

import com.ashvehicles.client.ModKeyMappings;
import com.ashvehicles.client.ghost.EntityGhostRegistry;
import com.ashvehicles.client.ghost.GhostConfig;
import com.ashvehicles.client.ghost.adapter.AircraftGhostAdapter;
import com.ashvehicles.client.ghost.adapter.BulletGhostAdapter;
import com.ashvehicles.client.ghost.adapter.GroundVehicleGhostAdapter;
import com.ashvehicles.client.ghost.adapter.RocketGhostAdapter;
import com.ashvehicles.client.ghost.adapter.TargetDroneGhostAdapter;
import com.ashvehicles.client.particle.BlastParticle;
import com.ashvehicles.client.particle.BlastStageParticle;
import com.ashvehicles.client.particle.CinderParticle;
import com.ashvehicles.client.particle.CloudLayerParticle;
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
import com.ashvehicles.client.renderer.DesignationRenderer;
import com.ashvehicles.client.renderer.GroundVehicleRenderer;
import com.ashvehicles.client.renderer.RocketRenderer;
import com.ashvehicles.client.renderer.TargetDroneRenderer;
import com.ashvehicles.client.screen.VehicleWorkbenchScreen;
import com.ashvehicles.registry.ModEntities;
import com.ashvehicles.registry.ModMenus;
import com.ashvehicles.registry.ModRecipes;
import com.ashvehicles.registry.ModItems;
import com.ashvehicles.registry.ModParticles;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.RecipeBookCategories;
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
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterRecipeBookCategoriesEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// 専用サーバーでは読み込まれないクラス。ここからクライアント側のコードに触れて構わない。
@Mod(value = AshVehicles.MODID, dist = Dist.CLIENT)
// EventBusSubscriber を付けると @SubscribeEvent の付いた static メソッドが自動登録される
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public class AshVehiclesClient {
    public AshVehiclesClient(ModContainer container) {
        // NeoForge にこの MOD の設定画面を作らせる（Mods 画面 > MOD > config から開く）。
        // 設定項目の翻訳を en_us.json に足すのを忘れないこと。
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        // ゴースト表示の設定（距離・予算・デバッグ）。仕組み自体と同じくクライアント専用。
        container.registerConfig(ModConfig.Type.CLIENT, GhostConfig.SPEC);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // クライアント側の初期化処理
        AshVehicles.LOGGER.info("HELLO FROM CLIENT SETUP");
        AshVehicles.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());

        // 通常の描画距離を超えたときにゴースト化するエンティティと、その描き方。扱いは全部同じで、
        // ghostStartDistance を越えるとゲーム側のレンダラーが降り、ゴーストパスがスナップショットから
        // 描く。機体でもミサイルでも曳光弾でも変わらない。
        AircraftGhostAdapter aircraft = new AircraftGhostAdapter();
        ModEntities.aircraft().values().forEach(type -> EntityGhostRegistry.register(type.get(), aircraft));
        GroundVehicleGhostAdapter ground = new GroundVehicleGhostAdapter();
        ModEntities.vehicles().values().forEach(type -> EntityGhostRegistry.register(type.get(), ground));
        EntityGhostRegistry.register(ModEntities.BULLET.get(), new BulletGhostAdapter());
        EntityGhostRegistry.register(ModEntities.ROCKET.get(), new RocketGhostAdapter());
        EntityGhostRegistry.register(ModEntities.TARGET_DRONE.get(), new TargetDroneGhostAdapter());
    }

    /** ゴーストの距離は毎フレームではなくここで一度だけ二乗しておく。 */
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
        event.registerEntityRenderer(ModEntities.TARGET_DRONE.get(), TargetDroneRenderer::new);
        event.registerEntityRenderer(ModEntities.COUNTERMEASURE.get(), CountermeasureRenderer::new);
        // 指示点は計器が地表に重ねて描くもので、そこに立つ物体として描くものではない。
        // レンダラーがあるのは、エンティティ型には必ず要るから。
        event.registerEntityRenderer(ModEntities.DESIGNATION.get(), DesignationRenderer::new);
    }

    /**
     * この MOD のパーティクルが実際に何として描かれるか。レジストリの型が言うのは存在と使えるテクスチャ
     * だけで、挙動は全部ここにある。
     */
    @SubscribeEvent
    static void onRegisterParticleProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ModParticles.MOTOR_SMOKE.get(),
                sprites -> SmokeParticle.provider(sprites, SmokeParticle.MOTOR));
        event.registerSpriteSet(ModParticles.CONTRAIL.get(),
                sprites -> SmokeParticle.provider(sprites, SmokeParticle.CONTRAIL));
        event.registerSpriteSet(ModParticles.BLAST_SMOKE.get(),
                sprites -> SmokeParticle.provider(sprites, SmokeParticle.BLAST));
        event.registerSpriteSet(ModParticles.CLOUD.get(),
                sprites -> SmokeParticle.provider(sprites, SmokeParticle.CLOUD));
        event.registerSpriteSet(ModParticles.NUCLEAR_CLOUD.get(),
                sprites -> SmokeParticle.provider(sprites, SmokeParticle.NUCLEAR));
        event.registerSpriteSet(ModParticles.CLOUD_LAYER.get(),
                sprites -> CloudLayerParticle.provider(sprites, CloudLayerParticle.DRIFT));
        event.registerSpriteSet(ModParticles.NUCLEAR_LAYER.get(),
                sprites -> CloudLayerParticle.provider(sprites, CloudLayerParticle.BURNING));
        event.registerSpriteSet(ModParticles.BLAST.get(), BlastParticle::provider);
        event.registerSpriteSet(ModParticles.BLAST_STAGE.get(), BlastStageParticle::provider);
        event.registerSpriteSet(ModParticles.CINDER.get(),
                sprites -> CinderParticle.provider(sprites, CinderParticle.EMBER));
        event.registerSpriteSet(ModParticles.RUBBLE.get(),
                sprites -> CinderParticle.provider(sprites, CinderParticle.RUBBLE));
        event.registerSpriteSet(ModParticles.FIRE.get(), FlameParticle::provider);
        event.registerSpriteSet(ModParticles.SHOCKWAVE.get(), ShockwaveParticle::provider);
        event.registerSpriteSet(ModParticles.SPARK.get(), sprites -> SparkParticle.provider(sprites, true));
        event.registerSpriteSet(ModParticles.DEBRIS.get(), sprites -> SparkParticle.provider(sprites, false));
        event.registerSpriteSet(ModParticles.VAPOUR.get(),
                sprites -> SmokeParticle.provider(sprites, SmokeParticle.VAPOUR));
    }

    /**
     * 全機体分のアイテムモデルを、1機1ファイル書く代わりに生成する。{@link VehicleItemModels} 参照:
     * その手のファイルの中身はどれも同じで、機体固有の情報は一つも無い。
     */
    /** 工廠の盤面を開いたときに出す画面。盤面の種類1つに画面1つ。 */
    @SubscribeEvent
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.VEHICLE_WORKBENCH.get(), VehicleWorkbenchScreen::new);
    }

    /**
     * 工廠のレシピをレシピ本の分類に割り当てる。
     *
     * <p>どこにも出さない {@code UNKNOWN} に入れる。工廠の画面にレシピ本は無いし、バニラの卓の本に
     * 出しても3x3に入らない灰色の項目が並ぶだけだ。それでも割り当てが要るのは、割り当てが無いと
     * ゲームが分類の分からないレシピ1つにつき1行、ワールドに入るたび警告を吐くから。
     */
    @SubscribeEvent
    static void onRegisterRecipeBookCategories(RegisterRecipeBookCategoriesEvent event) {
        event.registerRecipeCategoryFinder(ModRecipes.VEHICLE_CRAFTING_TYPE.get(),
                holder -> RecipeBookCategories.UNKNOWN);
    }

    @SubscribeEvent
    static void onAddPackFinders(AddPackFindersEvent event) {
        VehicleItemModels.addTo(event);
    }

    /**
     * 機体アイテムの見た目。誰かが描いたテクスチャではなく、機体自身のジオメトリから撮った絵。
     * {@link VehicleIcons} 参照。
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
     * 機体の絵を撮る場所。フレームの先頭で、1フレームにつき1機ずつ。
     *
     * <p>必要になった場所で撮らないのは、1枚撮るのにモデル一式を専用テクスチャへ描き、投影・画角・
     * ライティングを退避して戻す必要があるから。画面描画の途中でやれば他人の行列で画面が描かれるし、
     * 機体だらけのクリエイティブタブを開いたときのように1フレームで10枚撮れば目に見えて止まる。
     */
    @SubscribeEvent
    static void onRenderFrame(RenderFrameEvent.Pre event) {
        VehicleIcons.takeNext();
    }

    /**
     * 各兵装をどのファイルから描くかは一度だけ調べて覚える。調べるにはリソースマネージャに訊く必要が
     * あり、それはディスク上のファイル探索に行き着くので、画面上の全ミサイルの毎フレームには重すぎる。
     * 答えが変わり得る唯一の出来事がリロードなので、答えを捨てるのもリロードだけ。
     *
     * <p>機体アイテムの絵も同じ理由で捨てる。読み直されたばかりのジオメトリとテクスチャから撮った絵
     * だから。
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
