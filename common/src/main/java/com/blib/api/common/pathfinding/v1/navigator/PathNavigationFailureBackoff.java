package com.blib.api.common.pathfinding.v1.navigator;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import org.jetbrains.annotations.Nullable;

import com.blib.mod.common.registry.init.BLibGameRules;

/**
 * Owns failed-path cooldown timing and exponential failure backoff.
 */
final class PathNavigationFailureBackoff {

    private static final int BASE_FAILURE_COOLDOWN = 10;

    private static final int MAX_FAILURE_COOLDOWN = 200;

    private static final double FAILURE_START_RESET_DISTANCE_SQUARED = 4.0;

    private static final long NANOS_PER_TICK = 50_000_000L;

    /**
     * The first repeat of an unchanged partial path rests this long; each further repeat doubles it.
     * <p>
     * 🚨 Oct 5 - THE STUCK-MOB CHURN, MEASURED BY {@code /blib perf}. A partial path ("get as close as you can")
     * counted as a SUCCESS here, so it reset the failure backoff instead of feeding it. A mob whose target was out of
     * reach - a yautja in a two-deep water pit - walked to the end of its partial path, the move finished, the plan
     * re-ran, and it searched again: 589 searches in one minute, about 18 ms each, 9.5 ms of every server tick for one
     * mob.
     * </p>
     */
    private static final int BASE_PARTIAL_STALL_COOLDOWN = 10;

    /** Five seconds. Long enough to stop the churn, short enough that a trapped mob re-tries noticeably often. */
    private static final int MAX_PARTIAL_STALL_COOLDOWN = 100;

    /**
     * How far (squared, in blocks) the target or the partial path's END must move before a partial result counts as NEW
     * information. Two blocks, the same as the failure backoff's start-reset distance.
     */
    private static final double PARTIAL_STALL_RESET_DISTANCE_SQUARED = 4.0;

    /**
     * How far (squared) the mob may be from the stalled path's end and still be refused a search. Three blocks.
     * <p>
     * ⚠⚠ THE START IS DELIBERATELY NOT COMPARED. Re-reviewed Oct 5 against the code path, not the intent: a mob that
     * gets a partial path WALKS to its end before it asks again, so its next request starts at the old end - a start
     * check would reset the stall on every cycle of exactly the churn it exists to stop (most visibly in a pit wider
     * than two blocks). "No progress" is: same target, same best-reachable end, and the mob is standing at that end. A
     * mob knocked or wandered away from the end is let through, so it can walk back.
     * </p>
     */
    private static final double PARTIAL_STALL_AT_END_DISTANCE_SQUARED = 9.0;

    private final LevelReader level;

    private int partialStallCount;

    private @Nullable BlockPos partialStallTarget;

    private @Nullable BlockPos partialStallEnd;

    private long partialStallUntil;

    private final long createdNanos = System.nanoTime();

    PathNavigationFailureBackoff(LevelReader level) {
        this.level = level;
    }

    boolean isInFailureCooldown(
        PathNavigationStateComponent state,
        BlockPos entityStart
    ) {
        if (state.consecutiveFailures == 0) {
            return false;
        }

        if (hasMovedAwayFromFailedStart(state, entityStart)) {
            resetFailureCooldown(state);
            return false;
        }

        return cooldownClock() - state.lastFailureTick < state.failureCooldownTicks;
    }

    void recordFailure(PathNavigationStateComponent state, BlockPos entityStart) {
        state.consecutiveFailures++;
        state.lastFailureEntityStart = entityStart;
        state.lastFailureTick = cooldownClock();
        var shift = Math.min(state.consecutiveFailures - 1, 30);
        state.failureCooldownTicks = Math.min(BASE_FAILURE_COOLDOWN * (1 << shift), MAX_FAILURE_COOLDOWN);
    }

    void resetFailureCooldown(PathNavigationStateComponent state) {
        state.consecutiveFailures = 0;
        state.failureCooldownTicks = 0;
        state.lastFailureEntityStart = null;
    }

    /**
     * Whether a request from {@code entityStart} toward {@code searchTarget} should be refused because the last
     * searches toward that target kept ending at the same spot, and the mob is standing there.
     * <p>
     * ⭐ Any real change clears it at once: the target moving two blocks, the mob being three blocks from the stalled
     * end, a search ending somewhere new, or a search reaching (see {@link #recordPartial} and
     * {@link #resetPartialStall}). So a trapped mob stops paying for identical searches, and a mob that has something
     * new to path toward never waits.
     * </p>
     */
    boolean isInPartialStall(BlockPos entityStart, BlockPos searchTarget) {
        if (partialStallCount < 2 || !isPartialBackoffEnabled()) {
            return false;
        }

        if (
            moved(partialStallTarget, searchTarget)
                || partialStallEnd == null
                || partialStallEnd.distSqr(entityStart) >= PARTIAL_STALL_AT_END_DISTANCE_SQUARED
        ) {
            resetPartialStall();
            return false;
        }

        return cooldownClock() < partialStallUntil;
    }

    /**
     * Records a usable path that does NOT reach its target. The first one is free; every repeat toward the same target
     * that ends in the same place extends the rest - 10, 20, 40, 80, then 100 ticks. {@code entityStart} is accepted
     * for symmetry with the failure backoff but deliberately not compared (see PARTIAL_STALL_AT_END_DISTANCE_SQUARED).
     */
    void recordPartial(BlockPos entityStart, BlockPos searchTarget, BlockPos pathEnd) {
        var sameAsLast = partialStallCount > 0
            && !moved(partialStallTarget, searchTarget)
            && !moved(partialStallEnd, pathEnd);

        partialStallCount = sameAsLast ? partialStallCount + 1 : 1;
        partialStallTarget = searchTarget;
        partialStallEnd = pathEnd;

        if (partialStallCount >= 2) {
            var shift = Math.min(partialStallCount - 2, 30);
            partialStallUntil = cooldownClock()
                + Math.min(BASE_PARTIAL_STALL_COOLDOWN * (1L << shift), MAX_PARTIAL_STALL_COOLDOWN);
        }
    }

    void resetPartialStall() {
        partialStallCount = 0;
        partialStallTarget = null;
        partialStallEnd = null;
        partialStallUntil = 0L;
    }

    /**
     * {@code /gamerule blibPathPartialBackoff false} restores the old search-every-time behaviour on the next request.
     */
    private boolean isPartialBackoffEnabled() {
        return !(level instanceof Level concreteLevel)
            || concreteLevel.getGameRules().getBoolean(BLibGameRules.PATH_PARTIAL_BACKOFF);
    }

    private static boolean moved(@Nullable BlockPos previous, BlockPos current) {
        return previous == null || previous.distSqr(current) >= PARTIAL_STALL_RESET_DISTANCE_SQUARED;
    }

    long cooldownClock() {
        if (level instanceof Level concreteLevel) {
            return concreteLevel.getGameTime();
        }

        return (System.nanoTime() - createdNanos) / NANOS_PER_TICK;
    }

    private boolean hasMovedAwayFromFailedStart(PathNavigationStateComponent state, BlockPos entityStart) {
        return state.lastFailureEntityStart != null
            && entityStart.distSqr(state.lastFailureEntityStart) >= FAILURE_START_RESET_DISTANCE_SQUARED;
    }
}
