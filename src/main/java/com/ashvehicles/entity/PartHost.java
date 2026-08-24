package com.ashvehicles.entity;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

/**
 * What a vehicle has to be able to answer for the boxes it is made of.
 *
 * <p>{@link VehiclePart} is an entity in its own right, and the game hands it hits, clicks and pick
 * results as if it were the whole machine. Almost all of those it passes straight through to
 * whatever it belongs to, which it reaches through {@code Entity}'s own interface; this is the short
 * list of questions that only the vehicle can answer, and the reason a box can belong equally to an
 * aeroplane or to a tank.
 *
 * <p>Nothing here is about the shape any more. A part carries its own {@link
 * com.ashvehicles.vehicle.Hitbox} from the moment the machine places it, and answers for itself.
 *
 * <p>Implemented by {@link AircraftEntity} and {@link GroundVehicleEntity}. Anything implementing it
 * must also be an {@code Entity} — {@link VehiclePart}'s constructor is where the two are required
 * together.
 */
public interface PartHost {
    /**
     * A click on one particular pylon, which means that pylon and nothing else.
     *
     * <p>Only an aircraft has any. Anything else lets the click carry on down and mean whatever it
     * usually means.
     */
    default InteractionResult interactPylon(Player player, InteractionHand hand, int slot) {
        return InteractionResult.PASS;
    }

    /**
     * Whether a pylon is worth reaching for: one with nothing that can be done with it stands aside
     * and lets the click reach the machine behind.
     */
    default boolean isLoadablePylon(int slot) {
        return false;
    }
}
