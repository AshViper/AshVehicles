package com.ashvehicles.mixin;

import java.util.List;
import java.util.function.Predicate;

import com.ashvehicles.entity.Hitboxes;
import com.ashvehicles.entity.VehiclePart;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes anything aimed through the world hit the mod's machines where they really are.
 *
 * <p>Everything the game aims comes through here: an arrow, a fireball, a player's crosshair, and
 * the mod's own rounds. What it does is walk the entities along the line and clip each one's upright
 * box — which for a machine's boxes is the box drawn round the tilted one, so a shot threaded past a
 * barrel laid over at forty-five degrees would hit it, and a click aimed at the gap between a wing
 * and a tailplane would climb aboard.
 *
 * <p>So the machines are taken out of that walk altogether and the same line is put to them
 * separately, against the shapes they really are. Whichever of the two answers is nearer to the eye
 * is the one that stands, and a hit on anything that is not one of the mod's machines is left
 * exactly as the game found it.
 *
 * <p>The caller's own view of what is worth hitting is carried through untouched — its filter is
 * asked about every box before the box is tested — so a round that may not hit the aeroplane that
 * fired it still may not, and a pylon with nothing to hang on it still stands aside.
 */
@Mixin(ProjectileUtil.class)
public abstract class HitScanMixin {
    /**
     * Keeps the mod's boxes out of the game's own walk down the line entirely.
     *
     * <p>Not merely so that it cannot land a hit on one against the wrong shape, but so that it
     * cannot land one and thereby hide what was really behind: the game returns the nearest thing it
     * found and nothing else, so a wing whose upright box the line crossed but whose plate it missed
     * would swallow the shot meant for whatever was standing beyond it. Left out of the search, a
     * machine's boxes can neither be hit wrongly nor stand in front of anything, and the scan below
     * puts them back where they really are.
     */
    @Redirect(method = {
            "getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;"
                    + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;"
                    + "Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;",
            "getEntityHitResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;"
                    + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;"
                    + "Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;F)"
                    + "Lnet/minecraft/world/phys/EntityHitResult;"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;"
                            + "Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private static List<Entity> ashvehicles$leaveTheBoxesToUs(Level level, Entity shooter, AABB area,
            Predicate<? super Entity> filter) {
        return level.getEntities(shooter, area, filter.and(found -> !(found instanceof VehiclePart)));
    }

    /**
     * The overload the crosshair and anything with a reach limit uses. The limit is a squared
     * distance, and it applies to the mod's boxes exactly as it applies to everything else.
     */
    @Inject(method = "getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;"
            + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)"
            + "Lnet/minecraft/world/phys/EntityHitResult;",
            at = @At("RETURN"), cancellable = true)
    private static void ashvehicles$aimAtTheRealShape(Entity shooter, Vec3 from, Vec3 to, AABB area,
            Predicate<Entity> filter, double furthest, CallbackInfoReturnable<EntityHitResult> callback) {
        EntityHitResult found = callback.getReturnValue();
        EntityHitResult ours = Hitboxes.pick(shooter.level(), shooter, from, to, 0.0, filter);

        callback.setReturnValue(nearer(from, found, ours, furthest, 0.0f));
    }

    /**
     * The overload projectiles use, which allows every entity a margin round it rather than a reach
     * limit. The same margin is allowed round the mod's boxes, or a graze the game would have
     * counted anywhere else would be refused here.
     */
    @Inject(method = "getEntityHitResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;"
            + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;"
            + "Ljava/util/function/Predicate;F)Lnet/minecraft/world/phys/EntityHitResult;",
            at = @At("RETURN"), cancellable = true)
    private static void ashvehicles$strikeTheRealShape(Level level, Entity shooter, Vec3 from, Vec3 to,
            AABB area, Predicate<Entity> filter, float margin,
            CallbackInfoReturnable<EntityHitResult> callback) {
        EntityHitResult found = callback.getReturnValue();
        EntityHitResult ours = Hitboxes.pick(level, shooter, from, to, margin, filter);

        callback.setReturnValue(nearer(from, found, ours, Double.MAX_VALUE, margin));
    }

    /**
     * Whichever of the two hits is nearer to the eye, with anything the game found against a
     * machine's own boxes thrown away first.
     *
     * <p>Thrown away rather than compared, because it is not a worse answer to the same question but
     * an answer to a different one: it is where the line crossed the upright box a part is carried
     * in, and that box is not the part.
     */
    private static EntityHitResult nearer(Vec3 from, EntityHitResult found, EntityHitResult ours,
            double furthest, float margin) {
        if (found != null && found.getEntity() instanceof VehiclePart) {
            found = null;
        }

        if (ours == null) {
            return found;
        }

        double mine = from.distanceToSqr(ours.getLocation());

        if (mine > furthest) {
            return found;
        }

        return found == null || mine < reach(from, found, margin) ? ours : found;
    }

    /**
     * How far away the game's own hit was.
     *
     * <p>Worked out again rather than read off the result, because one of the two overloads reports
     * a hit without saying where on the entity it landed — the middle of it stands in, and the
     * middle of a long entity is a long way from where a line grazing its nose went in.
     */
    private static double reach(Vec3 from, EntityHitResult found, float margin) {
        return found.getEntity().getBoundingBox().inflate(margin).clip(from, found.getLocation())
                .map(from::distanceToSqr)
                .orElseGet(() -> from.distanceToSqr(found.getLocation()));
    }
}
