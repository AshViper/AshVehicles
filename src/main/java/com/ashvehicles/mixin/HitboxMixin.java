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
 * Leaves the hitbox overlay to the mod's own drawing.
 *
 * <p>What Minecraft outlines is the upright box it collides against, which for a banked wing is a
 * tall slab nowhere near the wing — and for a tank is a square shed that nothing collides with at
 * all. AshVehicles draws the real boxes instead, tilted and traversed as they are written, so the
 * pair of outlines only obscure each other.
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
