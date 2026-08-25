package com.ashvehicles.mixin;

import com.ashvehicles.entity.VehicleEntityBase;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stops the server dragging a driven vehicle back off every kerb it drives up, and stops it
 * refusing outright to believe an aeroplane.
 *
 * <p>A vehicle the client is driving reports where it has got to, and the server checks that report
 * before accepting it. Half of that check is whether the client's own movement was possible, which
 * {@code VehicleEntityBase.move} already answers by taking the report as given — the driving side
 * has run the test against ground it could actually see, and the server, a tick behind and probing
 * the terrain at the old position, can only take movement away.
 *
 * <p>The other half is this one, and it was missed. Vanilla asks twice over whether the vehicle's
 * plain box is standing in clear air — once before the reported movement and once after — and puts
 * the vehicle back where it was if it was clear before and is not clear now. For a tank that plain
 * box is a shed four blocks across and three tall standing on the tracks, and it is not what the
 * vehicle collides with: the boxes in its own file are, and {@code GroundVehicleEntity} asks
 * the question a driver would ask at the corners of those instead. So the shed catches on the first
 * block of anything the vehicle is perfectly able to drive up, and it catches on it from two blocks
 * away, which is the vehicle stopping dead against thin air a stride short of a kerb and sitting
 * there with the engine roaring.
 *
 * <p>What is redirected is the question, not the answer to it. Vanilla's correction is for a vehicle
 * that <em>was</em> standing clear and has been driven into something; a vehicle whose plain box has
 * no bearing on where it may go was never standing clear in the sense the check means, and saying so
 * is what stands the correction down. Vehicles with no boxes of their own are left alone: their plain
 * box really is their shape, and the check is right about them.
 *
 * <p>Nothing is given away by this that was not given away already. The distance check above it
 * still holds — a client cannot report a vehicle further on than the speed it is flying could have
 * carried it, which is what {@link #ashvehicles$speedTheServerCanSee} is for — and where a vehicle
 * may go inside that is decided by {@code limitToShape}, which is a better test than this one and
 * runs on the side that can see the ground.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class VehicleMoveCheckMixin {
    /**
     * Ticks of the machine's own travel a single report is allowed to cover.
     *
     * <p>One would be the honest figure if a tick of the client's were a tick of the server's, and
     * it is not. Packets bunch — a hitch at either end lands two or three of them between one server
     * tick and the next, all of them measured from the same starting point — so a check with no room
     * in it fires on a hiccup rather than on a cheat. Four is about a fifth of a second of that, and
     * still bounds a report to something the machine could actually have flown.
     */
    private static final double REPORTS_MAY_COVER_TICKS = 4.0;

    /**
     * The speed the server judges a reported move against.
     *
     * <p>Vanilla asks whether the vehicle could really have reached where the client says it has,
     * and answers by comparing that distance against the vehicle's own delta movement: more than ten
     * blocks further than the vehicle was going is a report it refuses. Refusing it puts the vehicle
     * back where it was and sends the client a correction, which the client applies outright.
     *
     * <p>Both halves of that comparison are wrong for a machine of this mod's. The delta movement
     * the server keeps for one being flown by a client is a deliberate, permanent zero — the flight
     * model runs on the pilot's machine, the position arrives in packets, and a figure with anything
     * real in it would be broadcast back to the pilot and fight what their own flight model had just
     * worked out; see {@code AircraftEntity.tick}. And the distance is one tick of an aeroplane,
     * which at this pack's speeds is seventeen blocks against a limit of ten.
     *
     * <p>So every report from a fast aircraft was refused, every refusal dragged it back to where
     * the last accepted one left it, and the aeroplane hung in the air shaking instead of flying.
     * None of it showed in single player, because vanilla skips the check for the host of an
     * integrated server — it is a multiplayer-only fault, and it is the whole of why an aircraft
     * freezes partway across a server.
     *
     * <p>The check is not stood down here, it is told the truth. The server does know how fast the
     * machine is going: the side flying it reports that every tick, clamped on arrival, and
     * {@code VehicleEntityBase.getVelocity} is where it comes out. A few ticks of that is what a
     * single report may legitimately cover, and anything beyond it is still refused.
     */
    @Redirect(method = "handleMoveVehicle(Lnet/minecraft/network/protocol/game/ServerboundMoveVehiclePacket;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;getDeltaMovement()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 ashvehicles$speedTheServerCanSee(Entity entity) {
        if (entity instanceof VehicleEntityBase machine) {
            return machine.getVelocity().scale(REPORTS_MAY_COVER_TICKS);
        }

        return entity.getDeltaMovement();
    }

    @Redirect(method = "handleMoveVehicle(Lnet/minecraft/network/protocol/game/ServerboundMoveVehiclePacket;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;noCollision"
                            + "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z"))
    private boolean ashvehicles$ignorePlainBox(ServerLevel level, Entity entity, AABB box) {
        if (entity instanceof VehicleEntityBase machine && machine.getParts().length > 0) {
            return false;
        }

        return level.noCollision(entity, box);
    }
}
