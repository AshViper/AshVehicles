package com.ashvehicles.mixin;

import com.ashvehicles.client.AimZoom;
import com.ashvehicles.client.CockpitView;
import com.ashvehicles.client.MouseAim;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sends the pilot's mouse into the cockpit rather than to the world.
 *
 * <p>Minecraft reads the mouse straight into a bearing and an elevation. In an aeroplane that is
 * wrong twice over: banked, the screen is tipped but the mouse is not, so sideways stops being
 * sideways; near the vertical, a bearing means nothing and the view slews. Both {@link CockpitView}
 * and {@link MouseAim} work in the aircraft's own axes instead and have neither problem.
 *
 * <p>Which of the two gets the movement is the whole of what the free-look key decides. Ordinarily
 * the mouse moves the mark the aeroplane is flying at, and the head follows the mark so that the
 * pilot is looking at what they are asking for; held down, the mark is left where it is and the mouse
 * moves the head alone, which is how you look over your shoulder without flying there.
 */
@Mixin(MouseHandler.class)
public abstract class MouseLookMixin {
    @Shadow
    private double accumulatedDX;
    @Shadow
    private double accumulatedDY;

    @Inject(method = "turnPlayer(D)V", at = @At("HEAD"), cancellable = true)
    private void ashvehicles$turnTheHead(double partialTick, CallbackInfo callback) {
        // With the sight up the view is narrower, and the mouse is slowed by as much, so that a
        // movement across the screen is the same movement across the screen either way. Vanilla
        // does the same for a spyglass. One, and so nothing, whenever the sight is down.
        double zoom = AimZoom.factor();

        // Not aboard an aircraft: a tank's crew, or nobody's. Vanilla's own mouse, only slowed
        // while the sight is up.
        if (!CockpitView.isActive()) {
            this.accumulatedDX /= zoom;
            this.accumulatedDY /= zoom;

            return;
        }

        boolean inCockpit = Minecraft.getInstance().options.getCameraType().isFirstPerson();

        // Outside the aircraft with the mouse not flying it, there is nothing here worth doing that
        // vanilla does not already do better. Its own handling is a bearing and an elevation against
        // the world, which is exactly what an upright chase camera wants, and it goes the whole way
        // round. Left alone, the view also stays behind the aircraft as it turns, which is the one
        // thing the pilot wants from a camera they are not steering with.
        if (!inCockpit && !MouseAim.isActive()) {
            this.accumulatedDX /= zoom;
            this.accumulatedDY /= zoom;

            return;
        }

        // Minecraft's own feel for the mouse, so flying does not need a different hand to walking.
        double sensitivity = Minecraft.getInstance().options.sensitivity().get() * 0.6 + 0.2;
        double scale = sensitivity * sensitivity * sensitivity * 8.0 * 0.15 / zoom;
        double deltaX = this.accumulatedDX * scale;
        double deltaY = this.accumulatedDY * scale;

        if (MouseAim.isActive()) {
            MouseAim.turn(deltaX, deltaY, inCockpit);

            if (inCockpit) {
                // A head, and it goes only as far as a head goes. What the aeroplane is being asked
                // for may be further round than that; the pilot simply cannot see all of it.
                CockpitView.lookAlong(MouseAim.look());
                CockpitView.applyToPlayer();
            } else {
                MouseAim.applyToPlayer();
            }
        } else {
            CockpitView.turn(deltaX, deltaY);
            CockpitView.applyToPlayer();
        }

        this.accumulatedDX = 0.0;
        this.accumulatedDY = 0.0;
        callback.cancel();
    }
}
