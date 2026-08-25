package com.ashvehicles.client.sound;

import java.util.List;

import com.ashvehicles.AshVehicles;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * The one place the mod's running sounds are wound on, and the one place they are told about a new
 * entity.
 *
 * <p>Each family — engines, undercarriages, what a weapon does in the air — keeps its own list and
 * decides for itself what should be playing; see {@link LiveSounds}. All this does is hand every one
 * of them the ticks and the arrivals, so that there is a single tick handler rather than one per
 * family, and so a family is a field somewhere rather than a class the mod has to be told to load.
 *
 * <p>{@link BulletSounds} is fed from here too, and is not a {@link LiveSounds}: what a gun's round
 * makes is one crack as it goes by rather than a sound that runs for as long as the round exists.
 * The bookkeeping is the same — a list of what is in the air, wound on once a tick — which is why it
 * belongs here rather than in a handler of its own.
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class EntitySounds {
    private static final List<LiveSounds<?>> FAMILIES =
            List.of(EngineSounds.SOUNDS, GearSounds.SOUNDS, ProjectileSounds.SOUNDS);

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }

        for (LiveSounds<?> family : FAMILIES) {
            family.offer(event.getEntity());
        }

        BulletSounds.offer(event.getEntity());
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null) {
            for (LiveSounds<?> family : FAMILIES) {
                family.forget();
            }

            BulletSounds.forget();

            return;
        }

        if (minecraft.isPaused()) {
            return;
        }

        for (LiveSounds<?> family : FAMILIES) {
            family.tick(minecraft);
        }

        BulletSounds.tick(minecraft);
    }

    private EntitySounds() {
    }
}
