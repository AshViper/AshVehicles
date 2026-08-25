package com.ashvehicles.client;

import java.util.Set;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.VehicleEntityBase;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Takes the hotbar and the held item off the screen for as long as anyone is aboard a vehicle.
 *
 * <p>A crew member is not carrying what is in their hotbar; they are sitting in a machine that has
 * its own weapons, its own selection key and its own instruments — see {@link AircraftHud} and
 * {@link GroundVehicleHud} — and a row of item slots underneath all of that is a second, contradictory
 * answer to the question of what firing does. The arm holding a pickaxe in front of the windscreen is
 * the same mistake in the world instead of on the screen, and in the cockpit view it sits between the
 * pilot and the ground.
 *
 * <p>The status bars go with it. Health, hunger, armour and the experience bar are all readings of
 * the body sitting in the seat, and while the seat is what matters the thing the crew need to be
 * watching is the state of the machine, which is what the instruments already say. Only the air
 * gauge stays, because it appears solely when the crew are drowning and that is the one moment
 * nothing on the panel would tell them.
 *
 * <p>The crosshair goes too, and for the sharpest form of the same reason. A machine in this mod has
 * a sight of its own — the ring on the point the round will reach, see {@link GroundVehicleHud} and
 * {@link AircraftHud} — and vanilla's cross is a second mark answering the same question differently.
 * It is not merely redundant: whichever mark sits in the middle of the screen is the one anybody will
 * aim with, so leaving it there is an invitation to lay the gun on the wrong one.
 *
 * <p>Nothing here changes what the player is holding or what the bars are counting — the items are
 * still there and still selected, the numbers still move, they are merely not drawn — so climbing
 * down puts everything back with no state to restore.
 *
 * <p>The check is on the root vehicle rather than the immediate one, so a seat or any other carrier
 * that ends up between the crew and the machine does not bring the hotbar back.
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class CrewHudSuppressor {
    /**
     * The vanilla layers a crew member has no use for: the carried inventory, the body's bars, and
     * the crosshair the machine's own sight replaces.
     */
    private static final Set<ResourceLocation> HIDDEN = Set.of(
            VanillaGuiLayers.CROSSHAIR,
            VanillaGuiLayers.HOTBAR,
            VanillaGuiLayers.SELECTED_ITEM_NAME,
            VanillaGuiLayers.PLAYER_HEALTH,
            VanillaGuiLayers.FOOD_LEVEL,
            VanillaGuiLayers.ARMOR_LEVEL,
            VanillaGuiLayers.EXPERIENCE_BAR,
            VanillaGuiLayers.EXPERIENCE_LEVEL,
            VanillaGuiLayers.JUMP_METER);

    private CrewHudSuppressor() {
    }

    /** Drops those layers before they draw, while aboard. */
    @SubscribeEvent
    public static void onRenderGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (aboard() && HIDDEN.contains(event.getName())) {
            event.setCanceled(true);
        }
    }

    /**
     * Drops the first-person hands, while aboard. Every hand path in the renderer comes through
     * here, empty hands and maps included, so there is nothing left over to catch elsewhere.
     */
    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (aboard()) {
            event.setCanceled(true);
        }
    }

    private static boolean aboard() {
        LocalPlayer player = Minecraft.getInstance().player;

        return player != null && player.getRootVehicle() instanceof VehicleEntityBase;
    }
}
