package com.ashvehicles.mixin;

import com.ashvehicles.entity.VehicleEntityBase;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stops the server dragging a driven vehicle back off every kerb it drives up.
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
 * still holds — a client cannot report a vehicle a hundred blocks further on than the last one — and
 * where a vehicle may go inside that is decided by {@code limitToShape}, which is a better test than
 * this one and runs on the side that can see the ground.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class VehicleMoveCheckMixin {
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
