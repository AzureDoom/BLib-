package com.blib.internal.common.perf;

import com.just.ai.goap.StateKey;
import com.just.ai.goap.graph.Graph;
import com.just.ai.goap.sensor.Sensor;
import com.just.ai.goap.state.ReadableWorldState;
import net.minecraft.world.entity.Entity;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Oct 6 - profiler v3: per-sensor timing, so the GOAP "(planning + sensors)" line splits into the sensors by name and
 * the planning that is left.
 * <p>
 * ⚠ HOW: just-goap resolves a sensed key by looking its sensor up in the graph's sensor map and calling {@code apply}
 * (read from the 0.4.1 bytecode of {@code SensingWorldState.getOrNull}, which calls {@code graph.getSensorMap()} each
 * time), so the first time a graph runs during a profiling session each sensor in it is swapped for a {@link Timed}
 * wrapper that times each {@code apply} (its own work only) and passes everything else through. One original keeps one
 * wrapper, so a sensor serving several keys stays one object (the debug overlay de-duplicates by identity and unwraps
 * for its labels). Graphs are only wrapped while a session runs; once wrapped they stay wrapped, at the cost of one
 * static read per sensor evaluation.
 * </p>
 * <p>
 * A sensor read from inside an action's perform is not charged here - that time is already in the action's line - and
 * each sensor is charged only for its own work, nested sensors subtracted (see CHILD_NANOS), so the lines partition the
 * time.
 * </p>
 */
public final class TimedSensors {

    /** Graphs already wrapped. Weak: a graph that is no longer used must not be held by the profiler. */
    private static final Map<Graph<?>, Boolean> WRAPPED = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Oct 6 - EXCLUSIVE timing. The first version charged a sensor that reads other keys for their time too, to
     * whichever key happened to be asked first: a trivial "is the target in melee range" sensor showed 27 us a call
     * because it was the one that triggered the nearby-target scan underneath it. Now each sensor is charged only for
     * its own work - a stack of child-time accumulators subtracts nested sensors from their parent - so the scan shows
     * under its own name. Index 0..depth-1 hold the time already spent in children of each open sensor.
     */
    private static final long[] CHILD_NANOS = new long[64];

    /** Depth of timed sensor evaluation on the server thread. */
    private static int depth;

    private TimedSensors() {
        throw new UnsupportedOperationException();
    }

    /** Graph.sensorMap, opened once; null if it could not be (then nothing is timed and the report shows 0). */
    private static @org.jetbrains.annotations.Nullable java.lang.reflect.Field sensorMapField;

    private static boolean sensorMapFieldResolved;

    /**
     * Wraps {@code graph}'s sensors once, while a session is running. Server thread only.
     * <p>
     * ⚠⚠ Oct 6 correction: the first version assumed {@code getSensorMap()} handed out the graph's own HashMap and
     * replaced entries in place. Read from the 0.4.1 bytecode afterwards: {@code Graph.Builder.build()} wraps it in
     * {@code Collections.unmodifiableMap}, so every replace threw, was caught, and NOTHING was timed - the first v3
     * report showed no sensor lines at all. Now the graph's private {@code sensorMap} field is swapped for an
     * unmodifiable copy holding the wrappers. just-goap ships no module-info, so it is an automatic (open) module on
     * NeoForge and plain classpath on Fabric, and the field is a non-static final on an ordinary class, which
     * reflection may set. If any of that fails, it is logged once and sensing simply stays untimed.
     * </p>
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static void ensureWrapped(Graph<?> graph) {
        if (!BLibPerfProfiler.isActive() || WRAPPED.containsKey(graph)) {
            return;
        }

        WRAPPED.put(graph, Boolean.TRUE);

        var field = sensorMapField();

        if (field == null) {
            return;
        }

        try {
            Map<StateKey<?>, Sensor<?>> current = (Map) field.get(graph);
            Map<Sensor<?>, Timed<?>> wrappers = new IdentityHashMap<>();
            Map<StateKey<?>, Sensor<?>> replaced = new java.util.HashMap<>(current.size() * 2);

            for (var entry : current.entrySet()) {
                var sensor = entry.getValue();

                replaced.put(
                    entry.getKey(),
                    sensor == null || sensor instanceof Timed<?> ? sensor : wrappers.computeIfAbsent(sensor, Timed::new)
                );
            }

            field.set(graph, Collections.unmodifiableMap(replaced));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            com.blib.mod.BLib.LOGGER.warn("[BLib] /blib perf could not time GOAP sensors: {}", exception.toString());
            sensorMapField = null;
        }
    }

    private static @org.jetbrains.annotations.Nullable java.lang.reflect.Field sensorMapField() {
        if (!sensorMapFieldResolved) {
            sensorMapFieldResolved = true;

            try {
                var field = Graph.class.getDeclaredField("sensorMap");
                field.setAccessible(true);
                sensorMapField = field;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                com.blib.mod.BLib.LOGGER.warn("[BLib] /blib perf could not time GOAP sensors: {}", exception.toString());
            }
        }

        return sensorMapField;
    }

    /** {@return the sensor a {@link Timed} wraps, or the sensor itself} For the debug overlay. */
    public static Sensor<?> unwrap(Sensor<?> sensor) {
        return sensor instanceof Timed<?> timed ? timed.delegate : sensor;
    }

    /** Pass-through wrapper that times each {@code apply}, its own work only. */
    static final class Timed<T> implements Sensor<T> {

        final Sensor<T> delegate;

        @SuppressWarnings("unchecked")
        Timed(Sensor<?> delegate) {
            this.delegate = (Sensor<T>) delegate;
        }

        @Override
        public Set<StateKey.Sensed<?>> outputKeys() {
            return delegate.outputKeys();
        }

        @Override
        public <V> V apply(StateKey<V> key, T actor, ReadableWorldState worldState) {
            if (
                depth >= CHILD_NANOS.length
                    || BLibPerfProfiler.actionDepth() > 0
                    || !(actor instanceof Entity entity)
            ) {
                return delegate.apply(key, actor, worldState);
            }

            var start = BLibPerfProfiler.timerStart();

            if (start == 0L) {
                return delegate.apply(key, actor, worldState);
            }

            var slot = depth;
            CHILD_NANOS[slot] = 0L;
            depth++;

            try {
                return delegate.apply(key, actor, worldState);
            } finally {
                var total = System.nanoTime() - start;
                depth--;
                BLibPerfProfiler.recordLabelNanos(
                    entity,
                    BLibPerfProfiler.LABEL_SENSOR,
                    key.id(),
                    Math.max(0L, total - CHILD_NANOS[slot])
                );

                if (slot > 0) {
                    CHILD_NANOS[slot - 1] += total;
                }
            }
        }

        @Override
        public int refreshTicks() {
            return delegate.refreshTicks();
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
