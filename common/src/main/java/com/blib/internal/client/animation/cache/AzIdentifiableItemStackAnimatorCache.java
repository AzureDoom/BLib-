package com.blib.internal.client.animation.cache;

import net.minecraft.Util;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.blib.api.client.animation.v1.animator.AzItemAnimator;
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

    private final Map<UUID, Entry> animatorsByUuid = new HashMap<>();

    private long lastSweepMillis;

    public static AzIdentifiableItemStackAnimatorCache getInstance() {
        return INSTANCE;
    }

    private AzIdentifiableItemStackAnimatorCache() {}

    public synchronized void add(ItemStack itemStack, AzItemAnimator animator) {
        var uuid = itemStack.get(BLibDataComponents.AZ_ID.get());

        if (uuid != null) {
            var now = Util.getMillis();
            animatorsByUuid.computeIfAbsent(uuid, $ -> new Entry(animator, now));
            sweepIfDue(now);
        }
    }

    public synchronized @Nullable AzItemAnimator getOrNull(UUID uuid) {
        if (uuid == null) {
            return null;
        }

        var entry = animatorsByUuid.get(uuid);

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
        return animatorsByUuid.size();
    }

    private void sweepIfDue(long now) {
        if (!BLibDiagnosticSwitches.ITEM_ANIMATOR_EXPIRY_ENABLED || now - lastSweepMillis < SWEEP_INTERVAL_MILLIS) {
            return;
        }

        lastSweepMillis = now;
        var cutoff = now - BLibDiagnosticSwitches.ITEM_ANIMATOR_EXPIRY_MILLIS;
        animatorsByUuid.values().removeIf(entry -> entry.lastUsedMillis < cutoff);
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
