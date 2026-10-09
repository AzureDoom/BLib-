package com.blib.internal.client.profiling.profile;

import com.mojang.blaze3d.systems.RenderSystem;

import com.blib.api.client.profiling.v1.AzProfileStage;
import com.blib.api.client.profiling.v1.AzProfilerListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Aggregates AzureLib stage timings for one profiling run.
 * <p>
 * Each entry is keyed by stage, root subject and subject. The root is the outermost subject on the stack, so a held
 * item's animation is reported under the entity holding it and a controller under the entity that owns it.
 * <p>
 * Only the thread that started the session is measured, which is the render thread when started from a client command.
 * Calls from other threads are ignored, so the stack and maps need no synchronization.
 */
@SuppressWarnings("unused")
public final class ProfilerSession implements AzProfilerListener {

    private static final int MAX_DEPTH = 64;

    private static final AzProfileStage[] STAGES = AzProfileStage.values();

    private static final Function<Object, Map<Object, StageStats>> NEW_INNER = key -> new HashMap<>();

    private static final Function<Object, StageStats> NEW_STATS = key -> new StageStats();

    private final Thread owner;

    private final long startNanos;

    private long stopNanos;

    private final Map<Object, Map<Object, StageStats>>[] stats;

    private final int[] stageStack = new int[MAX_DEPTH];

    private final long[] startStack = new long[MAX_DEPTH];

    private final long[] childStack = new long[MAX_DEPTH];

    private final Object[] keyStack = new Object[MAX_DEPTH];

    private final Object[] rootStack = new Object[MAX_DEPTH];

    private int depth;

    private int overflowDepth;

    private long unbalanced;

    private long overflowed;

    @SuppressWarnings("unchecked")
    public ProfilerSession(Thread owner) {
        this.owner = owner;
        this.stats = new Map[STAGES.length];

        for (int i = 0; i < STAGES.length; i++) {
            stats[i] = new HashMap<>();
        }

        this.startNanos = System.nanoTime();
    }

    /**
     * {@code owner == null} means "the render thread". The profiler commands run as server commands, i.e. on the
     * integrated server's thread, so capturing {@code Thread.currentThread()} at start silently filtered out every
     * render-thread event (the 0-entry reports).
     */
    private boolean isOwnerThread() {
        return owner != null ? Thread.currentThread() == owner : RenderSystem.isOnRenderThread();
    }

    @Override
    public void begin(AzProfileStage stage, Object subject) {
        if (!isOwnerThread() || stopNanos != 0) {
            return;
        }

        if (depth == MAX_DEPTH) {
            overflowDepth++;
            overflowed++;
            return;
        }

        var key = SubjectKeys.key(subject);
        stageStack[depth] = stage.ordinal();
        keyStack[depth] = key;
        rootStack[depth] = depth == 0 ? key : rootStack[depth - 1];
        childStack[depth] = 0;
        // Read the clock last so the bookkeeping above isn't billed to the stage.
        startStack[depth++] = System.nanoTime();
    }

    @Override
    public void end(AzProfileStage stage) {
        long now = System.nanoTime();

        if (!isOwnerThread() || stopNanos != 0) {
            return;
        }

        if (overflowDepth > 0) {
            overflowDepth--;
            return;
        }

        int target = stage.ordinal();
        int index = depth - 1;

        while (index >= 0 && stageStack[index] != target) {
            index--;
        }

        if (index < 0) {
            // An end with no begin: the session started partway through this stage.
            unbalanced++;
            return;
        }

        if (index != depth - 1) {
            // Inner stages never ended, most likely because they threw. Drop them; their time stays in this stage's
            // self time.
            unbalanced += depth - 1 - index;
        }

        long total = now - startStack[index];
        long self = total - childStack[index];

        if (index > 0) {
            childStack[index - 1] += total;
        }

        stats[target].computeIfAbsent(rootStack[index], NEW_INNER)
            .computeIfAbsent(keyStack[index], NEW_STATS)
            .record(total, self);

        for (int i = index; i < depth; i++) {
            keyStack[i] = null;
            rootStack[i] = null;
        }

        depth = index;
    }

    /** Stops measuring. Further hook calls are ignored even if the listener is still installed. */
    public void stop() {
        if (stopNanos == 0) {
            stopNanos = System.nanoTime();
        }
    }

    public boolean isOwner(Thread thread) {
        return owner != null ? thread == owner : thread == Thread.currentThread() && RenderSystem.isOnRenderThread();
    }

    public long wallNanos() {
        return (stopNanos == 0 ? System.nanoTime() : stopNanos) - startNanos;
    }

    public long unbalanced() {
        return unbalanced;
    }

    public long overflowed() {
        return overflowed;
    }

    public int entryCount() {
        int count = 0;

        for (var byRoot : stats) {
            for (var bySubject : byRoot.values()) {
                count += bySubject.size();
            }
        }

        return count;
    }

    /** Flattens the collected stats into report rows. Call on the owning thread, or after {@link #stop()}. */
    public List<ReportRow> rows() {
        var rows = new ArrayList<ReportRow>();

        for (int stage = 0; stage < STAGES.length; stage++) {
            for (var byRoot : stats[stage].entrySet()) {
                var rootLabel = SubjectKeys.label(byRoot.getKey());

                for (var bySubject : byRoot.getValue().entrySet()) {
                    var subjectLabel = bySubject.getKey() == byRoot.getKey()
                        ? rootLabel
                        : rootLabel + " > " + SubjectKeys.label(bySubject.getKey());
                    var s = bySubject.getValue();
                    rows.add(
                        new ReportRow(
                            STAGES[stage],
                            subjectLabel,
                            s.count,
                            s.totalNanos,
                            s.selfNanos,
                            s.percentile(0.50),
                            s.percentile(0.99),
                            s.maxNanos
                        )
                    );
                }
            }
        }

        return rows;
    }
}
