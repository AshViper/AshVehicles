package com.ashvehicles.client.ghost;

/**
 * How much of an entity is drawn at a given distance.
 *
 * <pre>
 *   FULL        0 .. ghostStartDistance        the game's own entity renderer, nothing of ours
 *   GHOST       .. ghostSimplifiedDistance     our pass: the model, posed from the last snapshot
 *   SIMPLIFIED  .. ghostEndDistance            our pass: the model, static — or Distant Horizons boxes
 *   BILLBOARD   billboardDistance ..           our pass: a flat icon (only when enabled)
 *   HIDDEN      ghostEndDistance ..            nothing at all
 * </pre>
 *
 * <p>Chosen from a squared distance, so the render loop never takes a square root.
 */
public enum GhostLOD {
    FULL,
    GHOST,
    SIMPLIFIED,
    BILLBOARD,
    HIDDEN;

    /** The tier for something this far from the camera. */
    public static GhostLOD of(double distanceSq) {
        if (distanceSq < GhostConfig.startSq()) {
            return FULL;
        }

        if (distanceSq < GhostConfig.simplifiedSq()) {
            return GHOST;
        }

        if (distanceSq >= GhostConfig.endSq()) {
            return HIDDEN;
        }

        if (GhostConfig.billboards() && distanceSq >= GhostConfig.billboardSq()) {
            return BILLBOARD;
        }

        return SIMPLIFIED;
    }

    /** Whether this tier is drawn by the ghost pass rather than by the game. */
    public boolean isGhost() {
        return this == GHOST || this == SIMPLIFIED || this == BILLBOARD;
    }
}
