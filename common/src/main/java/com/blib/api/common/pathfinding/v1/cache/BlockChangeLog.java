package com.blib.api.common.pathfinding.v1.cache;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * ⭐ THE RECORD OF EVERY BLOCK CHANGE A PATH CACHE NEEDS TO HEAR ABOUT.
 * <p>
 * Oct 6: the path evaluator keeps its fifteen lookup caches from one search to the next, and asks this log before
 * reusing them whether any block changed inside the area those caches cover since its last search.
 * <p>
 * ⚠ Oct 7 - THE FIRST VERSION WAS A GLOBAL RING OF THE LAST 8192 CHANGES IN THE WHOLE LEVEL, and a tester's /blib perf
 * showed only 1 search in 26 reusing its caches. On a busy world - fluids settling, crops, farms, leaves, redstone,
 * acid and resin far away - 8192 changes pass in seconds, and every overflow had to answer "something changed" even
 * when nothing changed anywhere near the mob. The answer depended on the rest of the world, not on the area searched.
 * <p>
 * Now the log is SPATIAL: per chunk section (16x16x16) it remembers the game tick of the last change inside it. A
 * search asks only about the sections its own area touches, so a farm a thousand blocks away can no longer wipe a
 * xenomorph's caches. Entries older than {@link #KEEP_TICKS} are pruned; that is safe because the evaluator rejects
 * caches older than 600 ticks on age alone, so it never asks about a time that long ago.
 * <p>
 * Section granularity is deliberately coarse: a change anywhere in a section counts for the whole section. A false
 * "changed" only costs a rebuild - today's cost - never a wrong path.
 */
public final class BlockChangeLog {

    /** How long a section's last-change stamp is kept. Must exceed the evaluator's maximum cache age (600 ticks). */
    public static final long KEEP_TICKS = 1200L;

    /** Asking about more sections than this is answered "changed" without looking - such an area is never reused. */
    private static final int MAX_SECTIONS_ASKED = 16_384;

    /** Prune stale entries at most this often. */
    private static final long PRUNE_INTERVAL_TICKS = 600L;

    private static final Map<Level, BlockChangeLog> LOGS = Collections.synchronizedMap(new WeakHashMap<>());

    /** Section position (SectionPos.asLong) to the game tick of the last change inside it. */
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap lastChangeTick =
        new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

    private long lastPruneTick;

    private BlockChangeLog() {
        lastChangeTick.defaultReturnValue(Long.MIN_VALUE);
    }

    /** Records one block change. Called from the chunk's setBlockState hook, for every real state change. */
    public static void record(Level level, BlockPos pos) {
        var log = LOGS.get(level);

        if (log == null) {
            log = LOGS.computeIfAbsent(level, $ -> new BlockChangeLog());
        }

        log.append(
            net.minecraft.core.SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4),
            level.getGameTime()
        );
    }

    /**
     * {@return the stamp a caller keeps with its caches: the level's current game tick} Pass it back to
     * {@link #changedSince} at the next search.
     */
    public static long sequence(Level level) {
        return level.getGameTime();
    }

    /**
     * {@return true if any block inside the box may have changed at or after the {@code sinceSequence} tick, or if the
     * box is too large to check} A change in the same tick as the stamp counts as changed (the search may have run
     * before it).
     */
    public static boolean changedSince(
        Level level,
        long sinceSequence,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ
    ) {
        if (sinceSequence > level.getGameTime()) {
            return true; // a stamp from a different clock - never trust it
        }

        var log = LOGS.get(level);

        if (log == null) {
            return false; // nothing has changed in this level since it was loaded
        }

        return log.anyInside(sinceSequence, minX >> 4, minY >> 4, minZ >> 4, maxX >> 4, maxY >> 4, maxZ >> 4);
    }

    /**
     * Oct 7 (#14) - the sections inside the box that changed at or after {@code sinceSequence}, so a caller can throw
     * away only what sits near them instead of everything.
     *
     * @return the changed sections as {@code SectionPos.asLong} values (empty when nothing changed), or {@code null}
     *         when the box is too large to check or the stamp cannot be trusted - treat {@code null} as "everything
     *         changed"
     */
    public static long @org.jetbrains.annotations.Nullable [] changedSectionsSince(
        Level level,
        long sinceSequence,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ
    ) {
        if (sinceSequence > level.getGameTime()) {
            return null;
        }

        var log = LOGS.get(level);

        if (log == null) {
            return new long[0];
        }

        return log.collectInside(sinceSequence, minX >> 4, minY >> 4, minZ >> 4, maxX >> 4, maxY >> 4, maxZ >> 4);
    }

    /** Drops a level's log, when the level unloads. */
    public static void forget(Level level) {
        LOGS.remove(level);
    }

    private synchronized void append(long section, long tick) {
        lastChangeTick.put(section, tick);

        if (tick - lastPruneTick >= PRUNE_INTERVAL_TICKS) {
            lastPruneTick = tick;
            var oldest = tick - KEEP_TICKS;
            lastChangeTick.long2LongEntrySet().removeIf(entry -> entry.getLongValue() < oldest);
        }
    }

    private synchronized long[] collectInside(long since, int minSx, int minSy, int minSz, int maxSx, int maxSy, int maxSz) {
        var count = (long) (maxSx - minSx + 1) * (maxSy - minSy + 1) * (maxSz - minSz + 1);

        if (count <= 0L || count > MAX_SECTIONS_ASKED) {
            return null;
        }

        var found = new long[8];
        var size = 0;

        for (var sx = minSx; sx <= maxSx; sx++) {
            for (var sy = minSy; sy <= maxSy; sy++) {
                for (var sz = minSz; sz <= maxSz; sz++) {
                    var section = net.minecraft.core.SectionPos.asLong(sx, sy, sz);

                    if (lastChangeTick.get(section) >= since) {
                        if (size == found.length) {
                            found = java.util.Arrays.copyOf(found, size * 2);
                        }

                        found[size++] = section;
                    }
                }
            }
        }

        return java.util.Arrays.copyOf(found, size);
    }

    private synchronized boolean anyInside(long since, int minSx, int minSy, int minSz, int maxSx, int maxSy, int maxSz) {
        var count = (long) (maxSx - minSx + 1) * (maxSy - minSy + 1) * (maxSz - minSz + 1);

        if (count <= 0L || count > MAX_SECTIONS_ASKED) {
            return true;
        }

        for (var sx = minSx; sx <= maxSx; sx++) {
            for (var sy = minSy; sy <= maxSy; sy++) {
                for (var sz = minSz; sz <= maxSz; sz++) {
                    if (lastChangeTick.get(net.minecraft.core.SectionPos.asLong(sx, sy, sz)) >= since) {
                        return true;
                    }
                }
            }
        }

        return false;
    }
}
