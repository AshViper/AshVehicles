package com.ashvehicles.item;

import java.util.function.Supplier;

import com.ashvehicles.aircraft.Attitude;
import com.ashvehicles.entity.AircraftEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;

/** Places the aircraft it was registered with onto the clicked block, facing the player. */
public class AircraftItem extends Item {
    private final Supplier<? extends EntityType<? extends AircraftEntity>> type;

    public AircraftItem(Supplier<? extends EntityType<? extends AircraftEntity>> type, Properties properties) {
        super(properties);
        this.type = type;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();

        if (level instanceof ServerLevel serverLevel) {
            BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
            AircraftEntity aircraft = this.type.get().create(serverLevel);

            if (aircraft == null) {
                return InteractionResult.FAIL;
            }

            Player player = context.getPlayer();
            float yaw = player == null ? 0.0F : player.getYRot();
            aircraft.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0.0F);
            // An aircraft is pointed by its attitude, and moveTo only sets the pair of angles
            // Minecraft keeps. Without this the aeroplane keeps the attitude it was born with and
            // sits facing due south whichever way it was put down — and its boxes, which are placed
            // from the attitude, sit facing south with it.
            aircraft.snapAttitude(Attitude.of(yaw, 0.0F));

            // The whole shape has to be clear, not just the middle of it. An aircraft's wings stop
            // against the world now, so one set down with a wingtip inside a hillside would be
            // wedged there for good. The margin lets a tip rest a little way into a slope.
            if (!aircraft.hasRoomHere(0.25)) {
                return InteractionResult.FAIL;
            }

            serverLevel.addFreshEntity(aircraft);
            serverLevel.gameEvent(player, GameEvent.ENTITY_PLACE, pos);
            context.getItemInHand().consume(1, player);
        }

        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
