package com.blib.api.common.goap.v1.sensor;

import com.just.ai.goap.StateKey;
import com.just.ai.goap.sensor.Sensor;
import com.just.ai.goap.state.ReadableWorldState;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Wraps any just-goap {@link Sensor} so its value is reused for a few ticks instead of being recomputed on every read.
 * <h2>Why this exists</h2> just-goap re-validates a running plan's preconditions EVERY TICK, and those conditions pull
 * sensor values through {@code SensingWorldState.getOrNull}. That is free when a sensor is a field read and expensive
 * when it is an entity query or a line-of-sight raycast. On a live server this measured at 3.31% of the whole server
 * thread, essentially all of it inside sensor evaluation.
 * <h2>⭐ Why it belongs here rather than in just-goap</h2> The library cannot know which sensors are expensive - the
 * consumer can. {@link Sensor} is a plain public interface with two methods, so wrapping it needs no change to
 * just-goap at all, and a wrapped sensor is still an ordinary sensor everywhere else.
 * <h2>⚠ When NOT to use this</h2> A cached sensor answers with a value up to {@code refreshTicks} old. That is right
 * for "what is near me" and wrong for anything an action depends on reacting to immediately - health thresholds,
 * whether the actor is still riding something, whether a target is still alive. Wrap the expensive ones and leave the
 * cheap ones alone; wrapping a field read costs more than it saves.
 * <h2>Usage</h2>
 *
 * <pre>
 *   // was:
 *   Sensors.map(NEARBY_TARGETS, actor -> expensiveScan(actor))
 *
 *   // now:
 *   CachedSensor.every(4, Sensors.map(NEARBY_TARGETS, actor -> expensiveScan(actor)))
 * </pre>
 */
public final class CachedSensor<T extends Entity> implements Sensor<T> {

    private final Sensor<T> delegate;

    private final int refreshTicks;

    /**
     * Per-actor, per-key cached values.
     * <p>
     * ⚠ Weakly held and keyed by entity UUID, so a dead actor takes its entries with it. A strong map here would be a
     * slow leak on any server that spawns and kills entities continuously.
     * </p>
     */
    private final Map<UUID, Map<StateKey<?>, Entry>> cache = new WeakHashMap<>();

    private CachedSensor(int refreshTicks, Sensor<T> delegate) {
        this.refreshTicks = refreshTicks;
        this.delegate = delegate;
    }

    /**
     * Reuses the wrapped sensor's value for {@code refreshTicks} ticks.
     *
     * @param refreshTicks how long a value stays good. 4 is a reasonable default for "what is near me" - vanilla's own
     *                     target scanning runs every 10.
     */
    public static <T extends Entity> CachedSensor<T> every(int refreshTicks, Sensor<T> delegate) {
        return new CachedSensor<>(refreshTicks, delegate);
    }

    @Override
    public Set<StateKey.Sensed<?>> outputKeys() {
        return delegate.outputKeys();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <V> V apply(StateKey<V> key, T actor, ReadableWorldState worldState) {
        var now = actor.tickCount;
        var byKey = cache.computeIfAbsent(actor.getUUID(), $ -> new java.util.HashMap<>());
        var entry = byKey.get(key);

        // ⚠ `now < entry.tick` guards a world reload or an entity whose tickCount restarted - without it a stale value
        // could be held indefinitely.
        if (entry != null && now - entry.tick < refreshTicks && now >= entry.tick) {
            return (V) entry.value;
        }

        var value = delegate.apply(key, actor, worldState);
        byKey.put(key, new Entry(now, value));

        return value;
    }

    private record Entry(
        int tick,
        Object value
    ) {}
}
