package com.ashvehicles.mixin;

import com.ashvehicles.entity.Hitboxes;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts the mod's machines in the way of anything that moves.
 *
 * <p>They are not in the game's own way. A machine's boxes lie at whatever angle the machine is
 * lying at, and the game's collision knows only upright boxes, so the parts a machine is made of
 * refuse to be collided with at all — see {@code VehiclePart.canBeCollidedWith} — and are put back
 * here, as the shapes they really are.
 *
 * <p>This is the last word on a move rather than a share of it. Minecraft settles the move against
 * the blocks and against everything else first; what arrives here is how far it has decided
 * something may go, and all that happens is that it may be cut down further. Nothing is handed back
 * that the world has already taken away, so a player wedged in a corner is not let out of it by a
 * tank driving past.
 *
 * <p>What comes of it is what anyone would expect and could not otherwise have: standing on a deck
 * that is tilted and standing on the tilt, walking round a wing rather than round the slab drawn
 * about it, and being lifted onto a track by the same step up that gets a player onto a slab.
 * Minecraft works out whether it has landed from how much of the move survived, and this is inside
 * that reckoning, so a player standing on a hull is standing on the ground as far as the game is
 * concerned.
 */
@Mixin(Entity.class)
public abstract class EntityCollisionMixin {
    @Inject(method = "collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;",
            at = @At("RETURN"), cancellable = true)
    private void ashvehicles$stopAtTheRealShape(Vec3 wanted, CallbackInfoReturnable<Vec3> callback) {
        Entity mover = (Entity) (Object) this;
        Vec3 allowed = callback.getReturnValue();
        Vec3 limited = Hitboxes.limit(mover, mover.getBoundingBox(), allowed);

        if (limited != allowed) {
            callback.setReturnValue(limited);
        }
    }
}
