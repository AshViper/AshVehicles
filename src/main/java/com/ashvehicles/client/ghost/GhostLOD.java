package com.ashvehicles.client.ghost;

/**
 * How much of an entity is drawn at a given distance.
 *
 * <pre>
 *   FULL        0 .. ghostStartDistance        the game's own entity renderer, nothing of ours
 *   GHOST       .. ghostEndDistance            our pass: the model, moving as the entity moves
 *   BILLBOARD   billboardDistance ..           our pass: a flat icon (only when enabled)
 *   HIDDEN      ghostEndDistance ..            nothing at all
 * </pre>
 *
 * <p>There is one drawn tier and not three because there is nothing a nearer ghost is given that a
 * further one can do without: the model is the same model at any distance, and posing it and
 * playing its cycles costs a handful of bone rotations. What used to be the simplified tier drew
 * the model static, or handed it to Distant Horizons as a few boxes; the boxes are gone, and a
 * static model beside a moving one is the sort of difference that is noticed even when the aircraft
 * is a few pixels across.
 *
 * <p>Chosen from a squared distance, so the render loop never takes a square root.
 */
public enum GhostLOD {
    FULL,
    GHOST,
    BILLBOARD,
    HIDDEN;

    /** The tier for something this far from the camera. */
    public static GhostLOD of(double distanceSq) {
        if (distanceSq < GhostConfig.startSq()) {
            return FULL;
        }

        if (distanceSq >= GhostConfig.endSq()) {
            return HIDDEN;
        }

        if (GhostConfig.billboards() && distanceSq >= GhostConfig.billboardSq()) {
            return BILLBOARD;
        }

        return GHOST;
    }

    /** Whether this tier is drawn by the ghost pass rather than by the game. */
    public boolean isGhost() {
        return this == GHOST || this == BILLBOARD;
    }
}
