package com.blib.mod.common.animation_sync;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;

import com.blib.api.client.animation.v1.command.AzCommand;
import com.blib.api.client.animation.v1.command.AzTarget;
import com.blib.api.client.animation.v1.command.play_behavior.AzPlayBehaviorRegistry;
import com.blib.api.client.animation.v1.command.play_behavior.AzPlayBehaviors;

/**
 * Client side of the item animation sync.
 * <h2>⚠ It resolves the stack from the HAND, not from a slot or a copy</h2> The animator is cached on the ItemStack
 * object, so the command must be dispatched against the very stack the renderer will use. Resolving by hand gives that;
 * anything else lands on a different object and the animation plays where nobody is looking.
 */
public final class BLibItemAnimationClientHandler {

    /** {@code -Dblib.animsync.debug=true}: logs every synced clip and the track state it met. Off by default. */
    private static final boolean DEBUG = Boolean.getBoolean("blib.animsync.debug");

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("blib/animsync");

    private record DedupeKey(
        int entityId,
        boolean offhand,
        String track,
        String animation
    ) {}

    private static final java.util.Map<DedupeKey, Long> RECENT = new java.util.HashMap<>();

    private BLibItemAnimationClientHandler() {
        throw new UnsupportedOperationException();
    }

    public static void handle(S2CItemAnimationPayload payload) {
        var level = Minecraft.getInstance().level;

        if (level == null) {
            return;
        }

        if (!(level.getEntity(payload.entityId()) instanceof LivingEntity holder)) {
            return;
        }

        var stack = holder.getItemInHand(payload.offhand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);

        if (stack.isEmpty()) {
            return;
        }

        // ⚠⚠ A LOOP YIELDS TO A ONE-SHOT THAT IS STILL RUNNING. The idle loop is re-sent every tick, and BLib's
        // idempotent dispatch only asks "is THIS sequence already active?" — with a fire clip active the answer is
        // no, so it replayed the idle over the top of it within one tick and the one-shot never visibly played.
        // The hold belongs here, on the client next to the track, not as a timer on the server: the track itself
        // knows whether it is mid one-shot (a non-LOOP current animation that has not reached STOP).
        var behavior = AzPlayBehaviorRegistry.getOrNull(payload.behavior());

        if (behavior == null) {
            behavior = AzPlayBehaviors.PLAY_ONCE;
        }

        var loop = behavior == AzPlayBehaviors.LOOP;

        // ⚠⚠ DROP THE SECOND COPY. BLibItemAnimationSync sends to the holder's trackers AND to the holder. On NeoForge
        // the tracker send is sendToPlayersTrackingEntityAndSelf — self included — so the holder gets every clip
        // TWICE; on Fabric PlayerLookup.tracking excludes self and the explicit send is needed. A loop shrugs off a
        // duplicate (idempotent); a one-shot REPLAYS: the door closed, snapped open, closed again, every time.
        // Proven by -Dblib.animsync.debug: two "recv gauntlet.left.close" lines per close, one tick apart at most.
        // Dedupe here, by stack + track + clip within the same game tick, so both loaders behave the same.
        var key = new DedupeKey(payload.entityId(), payload.offhand(), payload.trackName(), payload.animationName());
        var now = level.getGameTime();
        var last = RECENT.put(key, now);

        if (last != null && last == now) {
            if (DEBUG) {
                LOGGER.info(
                    "[animsync] t={} dropped duplicate {} on {}",
                    now,
                    payload.animationName(),
                    payload.offhand() ? "offhand" : "mainhand"
                );
            }

            return;
        }

        if (RECENT.size() > 256) {
            RECENT.values().removeIf(tick -> now - tick > 20);
        }

        if (DEBUG) {
            var animator =
                com.blib.internal.client.animation.AzAnimatorAccessor.<java.util.UUID, net.minecraft.world.item.ItemStack>getOrNull(stack);
            var track = animator == null ? null : animator.getAnimationTrackContainer().getOrNull(payload.trackName());
            var current = track == null || track.currentAnimation() == null
                ? "none"
                : track.currentAnimation().animation().name() + "/" + track.currentAnimation().playBehavior().name();

            LOGGER.info(
                "[animsync] t={} recv {} {} on {} | track current={} state={} queue={}",
                level.getGameTime(),
                payload.animationName(),
                behavior.name(),
                payload.offhand() ? "offhand" : "mainhand",
                current,
                track == null
                    ? "no-track"
                    : (track.stateMachine().isStopped()
                        ? "STOP"
                        : track.stateMachine().isPlaying() ? "PLAY" : track.stateMachine().isTransitioning() ? "TRANSITION" : "PAUSE"),
                track == null ? -1 : track.animationQueue().size()
            );
        }

        if (loop && isOneShotRunning(stack, payload.trackName())) {
            // ⚠⚠ ...AND QUEUES ITSELF BEHIND A PLAY-ONCE, so the idle resumes the instant the one-shot ends instead
            // of a tick later when the next send arrives — the gap in between let the bone cache drift toward rest.
            // A HELD clip is left alone (the door stays open until close); a queue that already has something in
            // it is left alone too, so the per-tick re-send cannot pile up copies.
            queueLoopBehindPlayOnce(holder, stack, payload, behavior);
            return;
        }

        // ⚠ A loop is idempotent so a repeat send does not restart it and jitter the model; a one-shot replays,
        // because the same event happening twice must animate twice.
        var builder = loop
            ? AzCommand.<net.minecraft.world.item.ItemStack>idempotent()
            : AzCommand.<net.minecraft.world.item.ItemStack>replay();

        builder
            .play(
                AzTarget.track(payload.trackName()),
                payload.animationName(),
                behavior
            )
            .build()
            .dispatchForItem(holder, stack);
    }

    private static boolean isOneShotRunning(net.minecraft.world.item.ItemStack stack, String trackName) {
        var animator = com.blib.internal.client.animation.AzAnimatorAccessor.<java.util.UUID, net.minecraft.world.item.ItemStack>getOrNull(
            stack
        );

        if (animator == null) {
            return false;
        }

        var track = animator.getAnimationTrackContainer().getOrNull(trackName);

        if (track == null) {
            return false;
        }

        var current = track.currentAnimation();

        return current != null
            && current.playBehavior() != AzPlayBehaviors.LOOP
            && !track.stateMachine().isStopped();
    }

    private static void queueLoopBehindPlayOnce(
        LivingEntity holder,
        net.minecraft.world.item.ItemStack stack,
        S2CItemAnimationPayload payload,
        com.blib.api.client.animation.v1.command.play_behavior.AzPlayBehavior behavior
    ) {
        var animator = com.blib.internal.client.animation.AzAnimatorAccessor.<java.util.UUID, net.minecraft.world.item.ItemStack>getOrNull(
            stack
        );

        if (animator == null) {
            return;
        }

        var track = animator.getAnimationTrackContainer().getOrNull(payload.trackName());

        if (track == null || track.currentAnimation() == null) {
            return;
        }

        if (track.currentAnimation().playBehavior() != AzPlayBehaviors.PLAY_ONCE) {
            return;
        }

        // ⚠⚠ THE RUNNING CLIP COUNTS AS A QUEUE ENTRY. The play state starts a clip with peek(), so a one-shot sits
        // at the head of the queue for its whole run and isEmpty() is false the entire time — which meant the loop
        // was NEVER queued behind it, the track stopped when the one-shot ended, and the bones drifted toward rest
        // for a tick until the next send. Count what is queued BEYOND the running clip instead.
        var queue = track.animationQueue();
        var pending = queue.size() - (queue.peek() == track.currentAnimation() ? 1 : 0);

        if (pending > 0) {
            return;
        }

        AzCommand.<net.minecraft.world.item.ItemStack>enqueueing()
            .play(AzTarget.track(payload.trackName()), payload.animationName(), behavior)
            .build()
            .dispatchForItem(holder, stack);
    }
}
