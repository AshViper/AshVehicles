package com.ashvehicles.client.ghost;

/**
 * Why a ghost was, or was not, drawn last frame.
 *
 * <p>Kept for the debug read-outs, and worth keeping: "nothing is on the screen" has half a dozen
 * causes that look identical from the outside — the tier, the frustum, the draw budget, terrain in
 * the way — and guessing between them from a screenshot is how an afternoon goes missing.
 */
public enum GhostVerdict {
    /** The game's own entity loop is drawing it; the pass left it alone. */
    GAME,
    /** The pass drew it. */
    DRAWN,
    /** Terrain stands between it and the camera — the game's own, or Distant Horizons'. */
    OCCLUDED,
    /** Outside the frustum, as it would be drawn. */
    CULLED,
    /** Past {@link GhostConfig#maxGhosts()} for this frame; the nearer ghosts had the budget. */
    BUDGET,
    /** Beyond {@code ghostEndDistance}, or otherwise in no drawn tier. */
    HIDDEN
}
