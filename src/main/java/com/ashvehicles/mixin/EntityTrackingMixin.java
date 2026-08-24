package com.ashvehicles.mixin;

import java.util.Set;

import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.entity.VehicleProjectile;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps sending an aircraft, and anything it fires, to players long after the world around it has
 * stopped being sent.
 *
 * <p>Minecraft decides who hears about an entity twice over: the entity's own tracking range, capped
 * at whatever the player's view distance is, and then whether that player has the entity's chunk
 * loaded at all. Both are the right answer for a cow. Neither is the right answer for an aeroplane
 * at altitude, which is visible from very much further away than the ground under it, and which
 * would otherwise wink out of the sky the moment it crossed the edge of the loaded world — most
 * obviously to anyone running a low vanilla render distance behind Distant Horizons, which is
 * exactly who wants to watch aircraft in the distance.
 *
 * <p>So for an aircraft both limits are set aside and its own {@code ghost_range} is used instead.
 * What arrives at that distance is a real entity, positioned and turned like any other, and
 * {@link com.ashvehicles.client.renderer.AircraftRenderer} draws it as a ghost once it is beyond the
 * world the player can actually see.
 *
 * <p>A round or a missile gets the same treatment for the same reason, but keeps the range its
 * entity type was registered with. The view-distance cap is the part that matters there: a missile
 * is aimed at something three hundred blocks away and is worth watching all the way in, and a client
 * running eight chunks would otherwise lose sight of it at a hundred and twenty — while the aircraft
 * that fired it is still perfectly visible.
 *
 * <p>Everything else is left exactly as it was: the entity still has to want to be broadcast to that
 * player, and every other entity in the game goes through the vanilla path untouched.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class EntityTrackingMixin {
    // Declared with the same access the real fields have, which is what mixin checks against.
    @Shadow
    @Final
    ServerEntity serverEntity;
    @Shadow
    @Final
    Entity entity;
    @Shadow
    @Final
    private Set<ServerPlayerConnection> seenBy;

    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
    private void ashvehicles$trackBeyondTheWorld(ServerPlayer player, CallbackInfo callback) {
        if (player == this.entity) {
            return;
        }

        if (this.entity instanceof VehicleEntityBase machine) {
            callback.cancel();
            this.ashvehicles$report(player, withinGhostRange(machine, player));
        } else if (this.entity instanceof VehicleProjectile) {
            callback.cancel();
            // The range its entity type was registered with, in blocks, and nothing else: it is
            // the capping against the player's view distance that has to go, not the range itself.
            this.ashvehicles$report(player,
                    this.ashvehicles$within(player, this.entity.getType().clientTrackingRange() * 16.0));
        }
    }

    /** Vanilla's own bookkeeping, once this has decided for itself whether the player can see it. */
    private void ashvehicles$report(ServerPlayer player, boolean inRange) {
        if (inRange && this.entity.broadcastToPlayer(player)) {
            if (this.seenBy.add(player.connection)) {
                this.serverEntity.addPairing(player);
            }
        } else if (this.seenBy.remove(player.connection)) {
            this.serverEntity.removePairing(player);
        }
    }

    /**
     * Whether this player is near enough to go on hearing about the machine.
     *
     * <p>One whose file sets no limit is always near enough: it is reported wherever it is, for as
     * long as both are in the same world. That is a decision about a handful of entity types rather
     * than about the world, and there are never many machines, so the cost is a position packet a
     * tick each rather than anything that scales with how big the world is.
     *
     * <p>Tanks are here for the same reason aircraft are, if not quite so obviously. A tank does not
     * fly, but the ground it sits on is visible from as far away as any of it, and a column crossing
     * a valley two kilometres off is exactly the thing somebody with Distant Horizons running is
     * looking out at. Left to vanilla it would vanish at the edge of the loaded chunks.
     */
    private boolean withinGhostRange(VehicleEntityBase machine, ServerPlayer player) {
        VehicleChassis.Hitbox hitbox = machine.hitbox();

        return !hitbox.hasGhostLimit() || this.ashvehicles$within(player, hitbox.ghostRange());
    }

    /** Flat distance, as vanilla measures tracking: how high something is has nothing to do with it. */
    private boolean ashvehicles$within(ServerPlayer player, double range) {
        double dx = player.getX() - this.entity.getX();
        double dz = player.getZ() - this.entity.getZ();

        return dx * dx + dz * dz <= range * range;
    }
}
