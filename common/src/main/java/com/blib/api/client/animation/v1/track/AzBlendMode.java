package com.blib.api.client.animation.v1.track;

/**
 * How a track's animation combines with the tracks layered below it (AzureLib 3.1.13 layering).
 * <p>
 * Tracks are layered in the order they were added to the animator. With the default weight of 1 and OVERRIDE, a track
 * replaces what is below it exactly as before layering existed.
 */
public enum AzBlendMode {

    /** Blends from the layers below toward this track's pose, by the track's weight. */
    OVERRIDE,

    /**
     * Adds this track's movement (relative to the bind pose) on top of the layers below, scaled by the track's weight -
     * e.g. breathing or recoil over a walk cycle. Scale multiplies rather than adds.
     */
    ADDITIVE
}
