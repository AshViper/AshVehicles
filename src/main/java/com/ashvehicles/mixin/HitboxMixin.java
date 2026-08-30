package com.ashvehicles.mixin;

import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.VehiclePart;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 当たり判定の表示は MOD 側の描画に任せる。
 *
 * <p>Minecraft が輪郭を描くのは衝突に使う直立した箱で、旋回中の翼に対しては翼のどこにも無い縦長の板、
 * 戦車に対しては何とも衝突しない四角い小屋になる。AshVehicles は本物の箱を、書かれた通りに傾け配置して
 * 描くので、両方描けば互いを隠すだけ。
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class HitboxMixin {
    @Inject(method = "renderHitbox(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/entity/Entity;FFFF)V",
            at = @At("HEAD"), cancellable = true)
    private static void ashvehicles$skipAircraftHitbox(PoseStack poseStack, VertexConsumer buffer, Entity entity,
            float partialTick, float red, float green, float blue, CallbackInfo callback) {
        if (entity instanceof AircraftEntity || entity instanceof GroundVehicleEntity
                || entity instanceof VehiclePart) {
            callback.cancel();
        }
    }
}
