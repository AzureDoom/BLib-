package com.blib.api.common.pathfinding.v1.search;

import org.jetbrains.annotations.ApiStatus;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The life of ONE background path search, so a pathfinder can make sure no two searches ever run on it at once.
 * <p>
 * ⚠⚠ WHY THIS EXISTS (Sep 28). A pathfinder's working state is shared by every search it runs: the open set, the closed
 * list, the preferred corridor, and the evaluator with its chunk snapshot and caches. When a mob re-targets, the
 * navigator "cancels" the old background search and immediately starts a new one on the SAME pathfinder — but
 * cancelling a CompletableFuture never stops the code already running inside it. The old search kept going to its node
 * limit while the new one reset and refilled the very structures it was reading. Rare, because searches are fast, but
 * when it hit, the new path could come out wrong, and it made it unsafe to release the chunk snapshot after a search.
 * <p>
 * A ticket fixes both: {@link #abandonAndAwait} tells the old search to stop, and the search checks
 * {@link #shouldStop()} on every step of its loops, so it exits within microseconds; the new search only starts once
 * the old one has actually left. A ticket abandoned while still QUEUED on the executor is marked so it never starts at
 * all, and nothing waits for it.
 * <p>
 * States: QUEUED → RUNNING → DONE, or QUEUED → SKIPPED. Every transition is a compare-and-set, so a search that is
 * starting at the exact moment it is abandoned either starts (and is then waited for) or is skipped — never both.
 */
@ApiStatus.Internal
final class SearchTicket {

    private static final int QUEUED = 0;

    private static final int RUNNING = 1;

    private static final int DONE = 2;

    private static final int SKIPPED = 3;

    private final AtomicInteger state = new AtomicInteger(QUEUED);

    private final CountDownLatch finished = new CountDownLatch(1);

    private volatile boolean stopRequested;

    /** Called by the background task before any work. False means it was abandoned in the queue: do nothing. */
    boolean tryStart() {
        return state.compareAndSet(QUEUED, RUNNING);
    }

    /** Checked on every step of the search loops. */
    boolean shouldStop() {
        return stopRequested;
    }

    /** Called by the background task as its very last act, after all cleanup. */
    void finish() {
        state.set(DONE);
        finished.countDown();
    }

    /**
     * Asks the search to stop and waits until it has. Returns true once it is guaranteed not to be running — at once if
     * it never started or already finished — or false if it was still running after {@code timeoutMillis}.
     */
    boolean abandonAndAwait(long timeoutMillis) {
        stopRequested = true;

        if (state.compareAndSet(QUEUED, SKIPPED)) {
            return true;
        }

        if (state.get() != RUNNING) {
            return true;
        }

        try {
            return finished.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
