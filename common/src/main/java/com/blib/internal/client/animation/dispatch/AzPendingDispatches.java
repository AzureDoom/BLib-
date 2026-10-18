package com.blib.internal.client.animation.dispatch;

import net.minecraft.Util;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

import com.blib.api.client.animation.v1.animator.AzAnimator;
import com.blib.mod.BLib;

/**
 * Animation commands dispatched to an entity or block entity before it has an animator, held until
 * {@link com.blib.internal.client.render.AzProvider AzProvider} creates one and then replayed onto it.
 * <p>
 * Ported from AzureLib 3.1.22 (Oct 17). Before this, a command sent before the animatable's first render was silently
 * dropped: a freshly spawned mob's first client tick dispatches its idle, nothing is listening yet, and the first frame
 * renders with no animation at all - the bind pose (T-pose) until the next tick's dispatch.
 * </p>
 * <h2>⚠ BLib differences from AzureLib - both because our mods dispatch EVERY CLIENT TICK</h2>
 * <ul>
 * <li><b>Capped at {@value #MAX_QUEUED} per animatable, oldest dropped.</b> AzureLib's queue is unbounded. An animator
 * is only created when the animatable is RENDERED, so a mob that is tracked but off-screen (behind the player, inside a
 * hive wall) never gets one, and its per-tick idle dispatch would grow the queue for as long as it stays out of view.
 * The newest commands are the ones that describe the current state, so they are the ones kept.</li>
 * <li><b>Each command expires {@value #EXPIRY_MILLIS} ms after it was queued</b>, the same lifetime AzureLib gives
 * queued item commands, so a one-shot sent long ago does not replay when the mob finally walks into view. Timed per
 * command, not per queue, so a steady stream of fresh dispatches is never thrown away with an old one.</li>
 * <li><b>Replays log at DEBUG, not INFO.</b> AzureLib logs every replay; here that would be a line for every mob that
 * comes into view.</li>
 * </ul>
 * Keys are weak, so a removed entity or block entity frees its queue. Client thread only; synchronized because the cost
 * is nothing uncontended.
 */
public final class AzPendingDispatches {

    /** The most commands held for one animatable; older ones are dropped first. */
    public static final int MAX_QUEUED = 32;

    /** How long a queued command lives. */
    public static final long EXPIRY_MILLIS = 10_000L;

    private static final Map<Object, Pending> PENDING = new WeakHashMap<>();

    private AzPendingDispatches() {
        throw new UnsupportedOperationException();
    }

    /** Queues {@code dispatch} to run on {@code animatable}'s animator once it exists. */
    public static synchronized void enqueue(Object animatable, Consumer<AzAnimator<?, ?>> dispatch) {
        PENDING.computeIfAbsent(animatable, $ -> new Pending()).add(Util.getMillis(), dispatch);
    }

    /** Replays and clears everything queued for {@code animatable} onto {@code animator}. */
    public static void flush(Object animatable, AzAnimator<?, ?> animator) {
        Pending pending;

        synchronized (AzPendingDispatches.class) {
            pending = PENDING.remove(animatable);
        }

        if (pending == null) {
            return;
        }

        var cutoff = Util.getMillis() - EXPIRY_MILLIS;
        var replayed = 0;

        // Outside the lock: a replayed command may dispatch again (a sequence advancing), which re-enters enqueue.
        for (var entry : pending.queued) {
            if (entry.queuedMillis() >= cutoff) {
                entry.dispatch().accept(animator);
                replayed++;
            }
        }

        BLib.LOGGER.debug("Replayed {} queued animation command(s) for {}", replayed, animatable);
    }

    /** How many animatables have commands waiting. Diagnostics only. */
    public static synchronized int size() {
        return PENDING.size();
    }

    private record Queued(
        long queuedMillis,
        Consumer<AzAnimator<?, ?>> dispatch
    ) {}

    private static final class Pending {

        private final ArrayDeque<Queued> queued = new ArrayDeque<>();

        private void add(long now, Consumer<AzAnimator<?, ?>> dispatch) {
            if (queued.size() >= MAX_QUEUED) {
                queued.pollFirst();
            }

            queued.addLast(new Queued(now, dispatch));
        }
    }
}
