package com.ashvehicles.mixin;

import com.ashvehicles.client.AircraftCameraHandler;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets an aircraft say where the camera goes.
 *
 * <p>NeoForge's only camera event, ViewportEvent.ComputeCameraAngles, fires at the top of
 * {@link Camera#setup} and the position is written further down, so anything the event moves is
 * overwritten a moment later. The angles survive; the position has to be applied out here, once
 * vanilla has finished placing it.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Inject(method = "setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V",
            at = @At("TAIL"))
    private void ashvehicles$placeAircraftCamera(BlockGetter level, Entity entity, boolean detached, boolean flipped,
            float partialTick, CallbackInfo callback) {
        AircraftCameraHandler.placeCamera((Camera) (Object) this, entity, detached, partialTick);
    }
}
