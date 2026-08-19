package com.ashvehicles.mixin;

import java.util.Set;

import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.entity.AircraftEntity;

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
 * Keeps sending an aircraft to players long after the world around it has stopped being sent.
 *
 * <p>Minecraft decides who hears about an entity twice over: the entity's own tracking range, capped
 * at whatever the player's view distance is, and then whether that player has the entity's chunk
 * loaded at all. Both are the right answer for a cow. Neither is the right answer for an aeroplane
 * at altitude, which is visible from very much further away than the ground under it, and which
 * would otherwise wink out of the sky the moment it crossed the edge of the loaded world — most
 * obviously to anyone running a low vanilla render distance behind Distant Horizons, which is
 * exactly who wants to watch aircraft in the distance.
 *
 * <p>So for an aircraft, and only for an aircraft, both limits are set aside and its own
 * {@code ghost_range} is used instead. What arrives at that distance is a real entity, positioned
 * and turned like any other, and {@link com.ashvehicles.client.renderer.AircraftRenderer} draws it
 * as a ghost once it is beyond the world the player can actually see.
 *
 * <p>Everything else is left exactly as it was: the aircraft still has to want to be broadcast to
 * that player, and every other entity in the game goes through the vanilla path untouched.
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
    private void ashvehicles$trackAircraftBeyondTheWorld(ServerPlayer player, CallbackInfo callback) {
        if (player == this.entity || !(this.entity instanceof AircraftEntity aircraft)) {
            return;
        }

        callback.cancel();

        boolean inRange = aircraft.broadcastToPlayer(player) && withinGhostRange(aircraft, player);

        if (inRange) {
            if (this.seenBy.add(player.connection)) {
                this.serverEntity.addPairing(player);
            }
        } else if (this.seenBy.remove(player.connection)) {
            this.serverEntity.removePairing(player);
        }
    }

    /**
     * Whether this player is near enough to go on hearing about the aircraft.
     *
     * <p>An aircraft whose file sets no limit is always near enough: it is reported wherever it is,
     * for as long as both are in the same world. That is a decision about one entity type rather
     * than about the world, and there are never many aircraft, so the cost is a position packet a
     * tick each rather than anything that scales with how big the world is.
     */
    private static boolean withinGhostRange(AircraftEntity aircraft, ServerPlayer player) {
        AircraftDefinition.Hitbox hitbox = aircraft.getStats().hitbox();

        if (!hitbox.hasGhostLimit()) {
            return true;
        }

        double range = hitbox.ghostRange();
        double dx = player.getX() - aircraft.getX();
        double dz = player.getZ() - aircraft.getZ();

        return dx * dx + dz * dz <= range * range;
    }
}
