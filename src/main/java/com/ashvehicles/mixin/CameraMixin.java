package com.ashvehicles.mixin;

import com.ashvehicles.client.AircraftCameraHandler;
import com.ashvehicles.client.GroundVehicleCameraHandler;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * カメラ位置を機体側から指定できるようにする。
 *
 * <p>NeoForge にあるカメラ用イベント ViewportEvent.ComputeCameraAngles は {@link Camera#setup} の
 * 冒頭で飛ぶが、位置はその後で書き込まれるため、イベントで動かした位置は直後に上書きされる。角度は
 * 残る。位置はバニラが置き終わったここで適用するしかない。
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Inject(method = "setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V",
            at = @At("TAIL"))
    private void ashvehicles$placeVehicleCamera(BlockGetter level, Entity entity, boolean detached, boolean flipped,
            float partialTick, CallbackInfo callback) {
        // 効くのは多くても片方。搭乗者がいるのは機体か地上車両かどちらでもないかで、各ハンドラは自分の
        // 担当でなければ何もしない。
        AircraftCameraHandler.placeCamera((Camera) (Object) this, entity, detached, partialTick);
        GroundVehicleCameraHandler.placeCamera((Camera) (Object) this, entity, detached, partialTick);
    }
}
