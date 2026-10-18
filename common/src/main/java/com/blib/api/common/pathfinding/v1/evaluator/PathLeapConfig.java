package com.blib.api.common.pathfinding.v1.evaluator;

/**
 * How far a mob can leap across a gap, for the pathfinder's leap move.
 * <p>
 * Oct 18 - the pathfinder had no horizontal jump at all: its moves are walk, step up, step down, drop, crawl, swim and
 * break, so a ledge across a gap was simply unreachable and the search settled for a partial path to the nearest point
 * - the foot of the cliff. A leap move crosses {@code 2..standingGap} blocks of gap from a standstill, or up to
 * {@code runningGap} with a straight run-up of at least one block, landing up to {@link #maxRise} higher or as far down
 * as the mob may fall.
 * <p>
 * Opt-in per mob: {@link #DISABLED} (the default) adds no move at all, so a mob that never sets this paths exactly as
 * before.
 *
 * @param standingGap the widest gap, in blocks, leapt from a standstill; 0 or less disables leaping
 * @param runningGap  the widest gap leapt with a run-up; never less than {@code standingGap}
 * @param maxRise     how much higher than the take-off the landing may be
 */
public record PathLeapConfig(
    int standingGap,
    int runningGap,
    int maxRise
) {

    /** No leaping. */
    public static final PathLeapConfig DISABLED = new PathLeapConfig(0, 0, 0);

    /** The narrowest gap worth a leap: a single missing block is stepped across or down, not leapt. */
    public static final int MIN_GAP = 2;

    public PathLeapConfig {
        standingGap = Math.max(0, standingGap);
        runningGap = Math.max(standingGap, runningGap);
        maxRise = Math.max(0, Math.min(1, maxRise));
    }

    /**
     * A leap of up to {@code standingGap} from a standstill and {@code runningGap} at a run, landing up to 1 higher.
     */
    public static PathLeapConfig of(int standingGap, int runningGap) {
        return standingGap < MIN_GAP ? DISABLED : new PathLeapConfig(standingGap, runningGap, 1);
    }

    public boolean enabled() {
        return standingGap >= MIN_GAP;
    }
}
