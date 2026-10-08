package com.blib.internal.common.goap;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.ApiStatus;

import java.util.HashMap;
import java.util.Map;

import com.blib.internal.common.perf.BLibPerfProfiler;
import com.blib.mod.BLib;

/**
 * Logs a GOAP failure ONCE per (mob type, exception type, throwing frame) and counts every repeat silently.
 * <p>
 * Oct 5 - performance programme, stage 1. The error isolation around the agent update and the finish guarantee both
 * report here. A broken sensor fails on every tick of every mob of that type, so logging each one would bury the log
 * and cost more than the bug; one full stack trace per distinct cause is what is needed to fix it.
 * </p>
 * <p>
 * ⚠ The key is capped at {@code MAX_DISTINCT_KEYS}. Past that every new cause is still counted and still reported to
 * {@code /blib perf}, it just stops logging a stack trace - a mod throwing hundreds of distinct errors needs fixing,
 * not more log.
 * </p>
 */
@ApiStatus.Internal
public final class BLibGoapErrors {

    private static final int MAX_DISTINCT_KEYS = 256;

    private static final Map<String, int[]> COUNTS_BY_KEY = new HashMap<>();

    private static boolean capWarned;

    private BLibGoapErrors() {
        throw new UnsupportedOperationException();
    }

    /**
     * Records a failure in {@code where} for {@code entity}. Logs a full stack trace the first time this cause is seen.
     *
     * @param where  what was running - "agent update", "onFinish" - included in the log line
     * @param entity the mob whose agent failed
     * @param error  what it threw
     */
    public static synchronized void report(String where, Entity entity, Throwable error) {
        BLibPerfProfiler.recordGoapError(entity);

        var typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        var trace = error.getStackTrace();
        var frame = trace.length > 0 ? trace[0].toString() : "?";
        var key = typeId + '|' + where + '|' + error.getClass().getName() + '|' + frame;

        var count = COUNTS_BY_KEY.get(key);

        if (count != null) {
            count[0]++;
            return;
        }

        if (COUNTS_BY_KEY.size() >= MAX_DISTINCT_KEYS) {
            if (!capWarned) {
                capWarned = true;
                BLib.LOGGER.error(
                    "[BLib] GOAP error isolation has logged {} distinct failures; further new causes are counted but not logged.",
                    MAX_DISTINCT_KEYS
                );
            }

            return;
        }

        COUNTS_BY_KEY.put(key, new int[] { 1 });

        BLib.LOGGER.error(
            "[BLib] GOAP {} failed for {} (id {}, uuid {}) at {} {} {} in {}. Its plans were abandoned and it rests for one "
                + "second; repeats of this exact failure are counted silently. Set /gamerule blibGoapErrorIsolation false "
                + "to let it crash instead.",
            where,
            typeId,
            entity.getId(),
            entity.getStringUUID(),
            entity.getBlockX(),
            entity.getBlockY(),
            entity.getBlockZ(),
            entity.level().dimension().location(),
            error
        );
    }

    /** How many times the failures logged so far have repeated in total, for the profiler report. */
    public static synchronized int totalRepeats() {
        var total = 0;

        for (var count : COUNTS_BY_KEY.values()) {
            total += count[0] - 1;
        }

        return total;
    }

    /** How many distinct failures have been logged since the server started. */
    public static synchronized int distinctFailures() {
        return COUNTS_BY_KEY.size();
    }
}
