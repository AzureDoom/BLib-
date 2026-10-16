package com.blib.internal.client.animation.cache;

import net.minecraft.Util;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.blib.api.client.animation.v1.animator.AzItemAnimator;
import com.blib.api.client.animation.v1.animator.AzItemAnimatorView;
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

        if (uuid != null && animator != null) {
            var now = Util.getMillis();
            animatorsByView.get(view).computeIfAbsent(uuid, $ -> new Entry(animator, now));
            sweepIfDue(now);
        }
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
        if (!BLibDiagnosticSwitches.ITEM_ANIMATOR_EXPIRY_ENABLED || now - lastSweepMillis < SWEEP_INTERVAL_MILLIS) {
            return;
        }

        lastSweepMillis = now;
        var cutoff = now - BLibDiagnosticSwitches.ITEM_ANIMATOR_EXPIRY_MILLIS;
        for (var animators : animatorsByView.values()) {
            animators.values().removeIf(entry -> entry.lastUsedMillis < cutoff);
        }
    }

    private static final class Entry {

        private final AzItemAnimator animator;

        private long lastUsedMillis;

        private Entry(AzItemAnimator animator, long lastUsedMillis) {
            this.animator = animator;
            this.lastUsedMillis = lastUsedMillis;
        }
    }
}
