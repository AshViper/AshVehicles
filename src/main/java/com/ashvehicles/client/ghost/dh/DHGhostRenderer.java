package com.ashvehicles.client.ghost.dh;

import java.util.List;

import com.ashvehicles.client.ghost.EntityGhost;
import com.ashvehicles.client.ghost.GhostAdapter;
import com.ashvehicles.client.ghost.GhostConfig;
import com.ashvehicles.client.ghost.GhostLOD;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;

/**
 * Hands simplified ghosts to Distant Horizons to draw, as box groups inside its own pass.
 *
 * <p>Why bother, when our own pass can draw a model out there: because its pass has what ours
 * cannot get. A box group it draws is depth-tested against its terrain, fogged with its fog and lit
 * with its light, and so sits behind the ridge it should sit behind, fades with distance as the
 * ground does, and needs no column walk to decide whether it is hidden. At the simplified tier —
 * half a kilometre and beyond — an aeroplane is a few pixels, and a few boxes shaped like it are
 * all the model those pixels can show anyway.
 *
 * <p>This class decides, once a tick and on the game thread, which ghosts are Distant Horizons'
 * to draw and keeps their groups in step; {@link EntityGhost#isDhDrawn()} tells the render pass to
 * leave those alone. Nothing here names a Distant Horizons type — that is
 * {@link DHRendererBridge}'s job — so it is safe to load anywhere.
 */
public final class DHGhostRenderer {
    private DHGhostRenderer() {
    }

    /** Whether box groups are available at all this tick: the mod, its generic rendering, and our setting. */
    public static boolean available(ClientLevel level) {
        return GhostConfig.dhBoxLod() && DHIntegration.isActive() && DHIntegration.boxesAvailable(level);
    }

    /**
     * Shows, moves or hides a ghost's boxes for this tick.
     *
     * @param lod the tier the ghost is in, as seen from the camera this tick
     * @param allowed whether box groups are available and this ghost is within the draw budget
     */
    public static void sync(ClientLevel level, EntityGhost ghost, GhostLOD lod, boolean allowed) {
        GhostAdapter<?> adapter = ghost.adapter();
        // Occlusion is not consulted: a group in Distant Horizons' pass is depth-tested by it against
        // its own terrain, and painted over by the game's terrain afterwards, which is the real thing.
        boolean show = allowed && adapter.supportsDhBoxes() && lod == GhostLOD.SIMPLIFIED;

        if (show) {
            List<AABB> boxes = adapter.dhBoxes(ghost);
            DHIntegration.updateBoxes(level, ghost, boxes, adapter.dhBoxColour(ghost));
            ghost.setDhDrawn(!boxes.isEmpty() && ghost.dhHandle() != null);
        } else {
            if (ghost.dhHandle() != null) {
                DHIntegration.updateBoxes(level, ghost, List.of(), 0);
            }

            ghost.setDhDrawn(false);
        }
    }

    /** Takes a ghost's boxes out of Distant Horizons for good. */
    public static void release(EntityGhost ghost) {
        ghost.setDhDrawn(false);
        DHIntegration.removeBoxes(ghost);
    }
}
