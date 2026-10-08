package com.blib.api.common.perf.v1;

import net.minecraft.world.entity.Entity;

import com.blib.internal.common.perf.BLibPerfProfiler;

/**
 * Oct 5 - profiler v3: named laps for a mod's own tick code, shown in {@code /blib perf}'s "inside the tick" table.
 * <p>
 * Usage, between the statements of a tick method:
 * </p>
 *
 * <pre>
 * var lap = BLibPerf.start();
 * doTheFirstThing();
 * lap = BLibPerf.lap(this, "mymob.first", lap);
 * doTheSecondThing();
 * lap = BLibPerf.lap(this, "mymob.second", lap);
 * </pre>
 * <p>
 * Each lap charges the time since the previous one to its label and starts the next. With no session running,
 * {@link #start()} returns 0 and every {@link #lap} returns 0 at once - one static read, nothing recorded, nothing
 * allocated. Labels are map keys: pass constants. Server thread only; calls from any other thread are ignored.
 * </p>
 * <p>
 * ⚠ Do not wrap a call to {@code super.tick()} in a lap: vanilla's AI step, movement and base tick are already timed as
 * their own columns, and wrapping them would count that time twice.
 * </p>
 */
public final class BLibPerf {

    private BLibPerf() {
        throw new UnsupportedOperationException();
    }

    /**
     * Oct 6 - a goal (or other timed object) that wraps another and should be reported under a more useful name than
     * its own class - e.g. a gate around a weapon goal. Return a constant or cached string; it is a map key.
     */
    public interface Named {

        /** {@return the name /blib perf shows for this object} */
        String perfName();
    }

    /** {@return the lap start time, or 0 when nothing is being recorded} */
    public static long start() {
        return BLibPerfProfiler.timerStart();
    }

    /**
     * Charges the time since {@code start} to {@code label} for {@code entity}, and starts the next lap.
     *
     * @param entity the mob whose tick this is
     * @param label  a constant label, e.g. {@code "queen.ovipositor"}
     * @param start  the previous {@link #start()} or {@code lap} result
     * @return the start of the next lap (0 when nothing is being recorded)
     */
    public static long lap(Entity entity, String label, long start) {
        return BLibPerfProfiler.recordLabel(entity, BLibPerfProfiler.LABEL_CODE, label, start);
    }
}
