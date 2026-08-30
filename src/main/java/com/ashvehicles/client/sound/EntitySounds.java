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
 * MOD の継続音を進める唯一の場所であり、新しいエンティティの到着を伝える唯一の場所。
 *
 * <p>各系統——エンジン、降着装置、空中の兵器の音——は自前のリストを持ち、何を鳴らすべきか自分で決める。
 * {@link LiveSounds} 参照。ここがするのは、それら全てへtickと到着を渡すことだけだ。おかげでtickハンドラは系統
 * ごとではなく1つで済み、系統は「MOD にロードさせる必要のあるクラス」ではなく「どこかのフィールド」で済む。
 *
 * <p>{@link BulletSounds} もここから供給されるが、{@link LiveSounds} ではない。銃弾が出すのは通過時の1回の
 * 破裂音であって、弾が存在する限り鳴り続ける音ではないからだ。帳簿の付け方は同じ——空中にある物のリストを
 * 毎tick進める——であり、だから専用ハンドラではなくここに属する。
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
