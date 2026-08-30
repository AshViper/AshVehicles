package com.ashvehicles.data;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.ashvehicles.AshVehicles;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.flag.FeatureFlagSet;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddPackFindersEvent;

/**
 * {@code ashvehiclespack/} に置かれた物を、リソースパックとしてもデータパックとしてもゲームへ差し出す。
 *
 * <p>2種類とも差し出すのは、1つのパックが両方を持っているからだ——機体のモデルとテクスチャは
 * {@code assets/}、機体そのものの記述は {@code data/} にあり、片方だけ入れても機体にはならない。
 *
 * <p><b>常時有効で、一覧にも出ない。</b> {@link PackSelectionConfig} に required と fixedPosition を
 * 立ててあるので、リソースパック画面にも各ワールドのデータパック一覧にも並ばず、外すこともできない。
 * これはフォルダに落とすだけで入るという約束そのものだ。有効化の操作が要るなら、それは
 * {@code resourcepacks/} と {@code datapacks/} が既にやっていることで、このフォルダを作る理由が無い。
 *
 * <p>{@code pack.mcmeta} は読まない。書いてあれば無視される。パックのバージョンや説明は、このフォルダに
 * 置かれた時点で誰も問い合わせない情報だからだ。
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class ContentPackFinder {
    private ContentPackFinder() {
    }

    @SubscribeEvent
    public static void onAddPackFinders(AddPackFindersEvent event) {
        List<Path> packs = ContentPacks.packs();

        if (packs.isEmpty()) {
            return;
        }

        PackType type = event.getPackType();

        event.addRepositorySource(consumer -> packs.forEach(pack -> {
            Pack built = build(pack, type);

            if (built != null) {
                consumer.accept(built);
            }
        }));
    }

    private static Pack build(Path pack, PackType type) {
        String name = ContentPacks.nameOf(pack);
        PackLocationInfo where = new PackLocationInfo(AshVehicles.MODID + "/" + name,
                Component.literal(name), PackSource.BUILT_IN, Optional.empty());
        Pack.Metadata about = new Pack.Metadata(
                Component.literal("AshVehicles content pack " + name),
                PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of(), true);
        Pack.ResourcesSupplier resources = Files.isDirectory(pack)
                ? new PathPackResources.PathResourcesSupplier(pack)
                : new FilePackResources.FileResourcesSupplier(pack);

        try {
            return new Pack(where, resources, about, new PackSelectionConfig(true, Pack.Position.TOP, true));
        } catch (Exception exception) {
            AshVehicles.LOGGER.error("Cannot serve the content pack {} as {}", name, type.getDirectory(), exception);

            return null;
        }
    }
}
