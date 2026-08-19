package com.ashvehicles.mixin;

import com.ashvehicles.client.CockpitView;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sends the pilot's mouse to their head rather than to the world.
 *
 * <p>Minecraft reads the mouse straight into a bearing and an elevation. In an aeroplane that is
 * wrong twice over: banked, the screen is tipped but the mouse is not, so sideways stops being
 * sideways; near the vertical, a bearing means nothing and the view slews. Handing the movement to
 * {@link CockpitView}, which turns a rotation about its own axes, has neither problem.
 */
@Mixin(MouseHandler.class)
public abstract class MouseLookMixin {
    @Shadow
    private double accumulatedDX;
    @Shadow
    private double accumulatedDY;

    @Inject(method = "turnPlayer(D)V", at = @At("HEAD"), cancellable = true)
    private void ashvehicles$turnTheHead(double partialTick, CallbackInfo callback) {
        if (!CockpitView.isActive()) {
            return;
        }

        // Minecraft's own feel for the mouse, so flying does not need a different hand to walking.
        double sensitivity = Minecraft.getInstance().options.sensitivity().get() * 0.6 + 0.2;
        double scale = sensitivity * sensitivity * sensitivity * 8.0 * 0.15;

        CockpitView.turn(this.accumulatedDX * scale, this.accumulatedDY * scale);
        CockpitView.applyToPlayer();

        this.accumulatedDX = 0.0;
        this.accumulatedDY = 0.0;
        callback.cancel();
    }
}
