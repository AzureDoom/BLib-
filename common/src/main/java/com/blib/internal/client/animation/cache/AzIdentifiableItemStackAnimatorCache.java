package com.blib.internal.client.animation.cache;

import net.minecraft.Util;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.blib.api.client.animation.v1.animator.AzItemAnimator;
import com.blib.api.client.animation.v1.animator.AzItemAnimatorView;
import com.blib.internal.client.animation.dispatch.AzPendingDispatches;
import com.blib.internal.client.model.AzBakedModelCache;
import com.blib.internal.common.diagnostics.BLibDiagnosticSwitches;
import com.blib.mod.common.registry.init.BLibDataComponents;

/**
 * Client-side animators for identity items, keyed by the stack's {@code AZ_ID}.
 * <p>
 * 🚨🚨 Oct 5 - THIS LEAKED EVERY ANIMATOR IT EVER HELD. It was a {@code WeakHashMap<UUID, AzItemAnimator>}, which only
 * frees an entry once nothing else references its KEY - and the value references the key: animator → context →
 * animatable ItemStack → its AZ_ID component → that same UUID. So no entry could ever become collectable. Every
 * identity item gets a random AZ_ID per stack (all nine avp_human guns, the gauntlet, the hand caster...), and each
 * animator holds its own DEEP-COPIED baked model, so every stack ever seen in a session - picked up, dropped, crafted,
 * seen on another player or a marine - stayed in memory until the game closed. Client memory climbing near marine camps
 * was this.
 * </p>
 * <p>
 * ⭐ FIX: a plain map with a last-drawn time. Every lookup (each render of the stack) refreshes it, and entries unused
 * for {@link BLibDiagnosticSwitches#ITEM_ANIMATOR_EXPIRY_MILLIS} are dropped by a sweep that runs at most every
 * {@code SWEEP_INTERVAL_MILLIS}. A stack drawn again after that simply gets a fresh animator from AzProvider - the cost
 * is that an animation in progress on an item nobody could see restarts. Entity and block-entity animators were checked
 * and do NOT leak: they are stored on the entity / block entity and freed with it.
 * </p>
 * <p>
 * Oct 17 (AzureLib 3.1.22 port) - commands sent to a view that has no animator yet are QUEUED per stack id and view and
 * replayed when that view's animator is added ({@link #dispatchOrQueue}); before, they were dropped. Each queued
 * command lives {@link AzPendingDispatches#EXPIRY_MILLIS} and at most {@link AzPendingDispatches#MAX_QUEUED} are kept
 * per view, newest first - BLib's item sync re-sends a held item's loop every tick, and a holder who is tracked but
 * never drawn would otherwise grow the queue without limit.
 * </p>
 * <p>
 * ⚠ Synchronized because the cost is nothing uncontended and a shader mod's render thread arrangement is not ours to
 * assume. {@code -Dblib.itemAnimatorExpiry=false} restores keep-forever.
 * </p>
 */
public class AzIdentifiableItemStackAnimatorCache {

    private static final long SWEEP_INTERVAL_MILLIS = 5_000L;

    private static final AzIdentifiableItemStackAnimatorCache INSTANCE = new AzIdentifiableItemStackAnimatorCache();

    /**
     * Oct 14 (AzureLib port: separate first-person item animators) - one map per {@link AzItemAnimatorView}, so a stack
     * can have a first-person animator alongside its default one.
     */
    private final Map<AzItemAnimatorView, Map<UUID, Entry>> animatorsByView = new java.util.EnumMap<>(AzItemAnimatorView.class);

    {
        for (var view : AzItemAnimatorView.values()) {
            animatorsByView.put(view, new HashMap<>());
        }
    }

    /** Commands waiting for an animator that does not exist yet, by stack id then view. */
    private final Map<UUID, Map<AzItemAnimatorView, ArrayDeque<Queued>>> pending = new HashMap<>();

    private long lastSweepMillis;

    public static AzIdentifiableItemStackAnimatorCache getInstance() {
        return INSTANCE;
    }

    private AzIdentifiableItemStackAnimatorCache() {}

    /** Registers the {@link AzItemAnimatorView#DEFAULT default} animator for the given stack. */
    public synchronized void add(ItemStack itemStack, AzItemAnimator animator) {
        add(itemStack, AzItemAnimatorView.DEFAULT, animator);
    }

    public synchronized void add(ItemStack itemStack, AzItemAnimatorView view, AzItemAnimator animator) {
        var uuid = itemStack.get(BLibDataComponents.AZ_ID.get());

        if (uuid == null || animator == null) {
            return;
        }

        var now = Util.getMillis();
        var animators = animatorsByView.get(view);
        var existing = animators.get(uuid);

        // ⚠⚠ Oct 17: REPLACE a different animator, do not keep the old one. AzProvider only calls this when the stack
        // has no animator OR its model changed (every resource reload, F3+T included, re-bakes each model with a new
        // random id). computeIfAbsent kept the STALE animator, so the provider saw the old model id again next frame
        // and rebuilt: after any reload, every animated item built a fresh animator, deep-copied model and track set on
        // every frame it was drawn, threw it away, and kept drawing the stale bones. Being drawn refreshed the stale
        // entry's expiry, so it never aged out. AzureLib's putIfAbsent has the same hole.
        var generation = AzBakedModelCache.getInstance().generation();

        if (existing != null && existing.animator == animator) {
            existing.lastUsedMillis = now;
            return;
        }

        // ⚠⚠ ...BUT ONLY A STALE ONE. The same stack can be drawn by TWO renderers with different models in one frame:
        // a worn piece with an AzArmorRenderer body model AND an AzItemRenderer icon in the inventory screen share
        // this one per-stack slot. Each sees the other's model id and rebuilds; replacing on every rebuild would make
        // them overwrite each other twice a frame and reset BOTH animations to their first frame. So an animator made
        // since the last reload is kept (the second renderer's throwaway rebuild is what BLib always did there), and
        // only one from before a reload is replaced.
        if (existing != null && existing.modelGeneration == generation) {
            existing.lastUsedMillis = now;
            return;
        }

        animators.put(uuid, new Entry(animator, now, generation));
        sweepIfDue(now);
        flushPending(uuid, view, animator, now);
    }

    /** @return the {@link AzItemAnimatorView#DEFAULT default} animator for the given stack id, or null. */
    public synchronized @Nullable AzItemAnimator getOrNull(UUID uuid) {
        return getOrNull(uuid, AzItemAnimatorView.DEFAULT);
    }

    /**
     * Runs {@code action} on the animator for {@code view}, or on every existing animator of the stack when
     * {@code view} is null. Views that have no animator yet are skipped.
     */
    public void forEach(@Nullable UUID uuid, @Nullable AzItemAnimatorView view, java.util.function.Consumer<AzItemAnimator> action) {
        if (uuid == null) {
            return;
        }

        if (view != null) {
            var animator = getOrNull(uuid, view);

            if (animator != null) {
                action.accept(animator);
            }

            return;
        }

        for (var each : AzItemAnimatorView.values()) {
            var animator = getOrNull(uuid, each);

            if (animator != null) {
                action.accept(animator);
            }
        }
    }

    /**
     * AzureLib 3.1.22 (ported Oct 17) - runs {@code action} on the animator for {@code view} (every view when
     * {@code view} is null), and QUEUES it for each targeted view that has no animator yet, to run the moment that
     * view's animator is {@link #add added}. Like AzureLib, a null view queues separately for each view without an
     * animator; a view the renderer never uses (FIRST_PERSON without {@code useSeparateFirstPersonAnimator()}) simply
     * lets its copy expire.
     */
    public void dispatchOrQueue(
        @Nullable UUID uuid,
        @Nullable AzItemAnimatorView view,
        java.util.function.Consumer<AzItemAnimator> action
    ) {
        if (uuid == null) {
            return;
        }

        if (view != null) {
            dispatchOrQueueOne(uuid, view, action, true);
            return;
        }

        // ⚠ Null view: queue only for a stack that has NO animator in any view yet - the never-drawn case this exists
        // for. AzureLib queues for every view without one, but FIRST_PERSON has no animator on any item that does not
        // use useSeparateFirstPersonAnimator() (most of ours), so every null-view dispatch to an item that IS drawn
        // would park a copy for FIRST_PERSON that nothing ever claims. BLib's item sync re-sends held loops every
        // tick, so that was a steady trickle of garbage per visible holder.
        var queueMissing = !hasAnyAnimator(uuid);

        for (var each : AzItemAnimatorView.values()) {
            dispatchOrQueueOne(uuid, each, action, queueMissing);
        }
    }

    private synchronized boolean hasAnyAnimator(UUID uuid) {
        for (var animators : animatorsByView.values()) {
            if (animators.containsKey(uuid)) {
                return true;
            }
        }

        return false;
    }

    private void dispatchOrQueueOne(
        UUID uuid,
        AzItemAnimatorView view,
        java.util.function.Consumer<AzItemAnimator> action,
        boolean queueIfMissing
    ) {
        AzItemAnimator animator;

        synchronized (this) {
            animator = getOrNull(uuid, view);

            if (animator == null) {
                if (!queueIfMissing) {
                    return;
                }

                var queue = pending.computeIfAbsent(uuid, $ -> new java.util.EnumMap<>(AzItemAnimatorView.class))
                    .computeIfAbsent(view, $ -> new ArrayDeque<>());

                if (queue.size() >= AzPendingDispatches.MAX_QUEUED) {
                    queue.pollFirst();
                }

                var now = Util.getMillis();
                queue.addLast(new Queued(now, action));
                // getOrNull only sweeps on a hit, so a session where queued stacks are never drawn still gets swept.
                sweepIfDue(now);
                return;
            }
        }

        // Outside the lock, like forEach: the action may look the stack's animator up again.
        action.accept(animator);
    }

    /** Hands {@code animator} everything queued for its stack and view. Called holding the lock. */
    private void flushPending(UUID uuid, AzItemAnimatorView view, AzItemAnimator animator, long now) {
        var byView = pending.get(uuid);

        if (byView == null) {
            return;
        }

        var queued = byView.remove(view);

        if (byView.isEmpty()) {
            pending.remove(uuid);
        }

        if (queued == null) {
            return;
        }

        var cutoff = now - AzPendingDispatches.EXPIRY_MILLIS;

        for (var entry : queued) {
            if (entry.queuedMillis() >= cutoff) {
                entry.action().accept(animator);
            }
        }
    }

    public synchronized @Nullable AzItemAnimator getOrNull(UUID uuid, AzItemAnimatorView view) {
        if (uuid == null) {
            return null;
        }

        var entry = animatorsByView.get(view).get(uuid);

        if (entry == null) {
            return null;
        }

        var now = Util.getMillis();
        entry.lastUsedMillis = now;
        sweepIfDue(now);

        return entry.animator;
    }

    /** How many item animators are held right now. */
    public synchronized int size() {
        var total = 0;

        for (var animators : animatorsByView.values()) {
            total += animators.size();
        }

        return total;
    }

    private void sweepIfDue(long now) {
        if (now - lastSweepMillis < SWEEP_INTERVAL_MILLIS) {
            return;
        }

        lastSweepMillis = now;

        if (BLibDiagnosticSwitches.ITEM_ANIMATOR_EXPIRY_ENABLED) {
            var cutoff = now - BLibDiagnosticSwitches.ITEM_ANIMATOR_EXPIRY_MILLIS;
            for (var animators : animatorsByView.values()) {
                animators.values().removeIf(entry -> entry.lastUsedMillis < cutoff);
            }
        }

        // Queued commands go on their own clock and regardless of the expiry switch: unlike an animator, a command
        // that is never claimed has no reason to stay at all.
        var commandCutoff = now - AzPendingDispatches.EXPIRY_MILLIS;
        pending.values().removeIf(byView -> {
            byView.values().removeIf(queue -> {
                queue.removeIf(entry -> entry.queuedMillis() < commandCutoff);
                return queue.isEmpty();
            });
            return byView.isEmpty();
        });
    }

    private record Queued(
        long queuedMillis,
        java.util.function.Consumer<AzItemAnimator> action
    ) {}

    private static final class Entry {

        private final AzItemAnimator animator;

        private long lastUsedMillis;

        /** The model-cache reload generation this animator was created in. */
        private final int modelGeneration;

        private Entry(AzItemAnimator animator, long lastUsedMillis, int modelGeneration) {
            this.animator = animator;
            this.lastUsedMillis = lastUsedMillis;
            this.modelGeneration = modelGeneration;
        }
    }
}
