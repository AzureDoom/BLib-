package com.blib.api.client.animation.v1.command.play_behavior;

import com.blib.internal.client.animation.track.state.machine.AzAnimationTrackStateMachine;

public class AzPlayBehaviors {

    private static final boolean DEBUG = Boolean.getBoolean("blib.animsync.debug");

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("blib/animsync");

    private AzPlayBehaviors() {}

    public static final AzPlayBehavior FREEZE_ON_FRAME = AzPlayBehaviorRegistry.register(
        new AzPlayBehavior("freeze_on_frame") {

            @Override
            public void onUpdate(AzAnimationTrackStateMachine.Context<?> context) {
                var track = context.animationTrack();
                var trackTimer = track.trackTimer();
                var freezeTickOffset = track.effectiveFreezeTickOffset();

                if (trackTimer.getAdjustedTick() >= freezeTickOffset) {
                    trackTimer.addToAdjustedTick(0);
                    context.stateMachine().pause();
                }
            }

            @Override
            public void onFinish(AzAnimationTrackStateMachine.Context<?> context) {
                context.stateMachine().pause();
            }
        }
    );

    public static final AzPlayBehavior HOLD_ON_LAST_FRAME = AzPlayBehaviorRegistry
        .register(
            new AzPlayBehavior("hold_on_last_frame") {

                @Override
                public void onFinish(AzAnimationTrackStateMachine.Context<?> context) {
                    context.stateMachine().pause();
                }
            }
        );

    public static final AzPlayBehavior LOOP = AzPlayBehaviorRegistry.register(
        new AzPlayBehavior("loop") {

            @Override
            public void onFinish(AzAnimationTrackStateMachine.Context<?> context) {
                var track = context.animationTrack();
                var trackTimer = track.trackTimer();
                var keyframeManager = track.keyframeManager();
                var keyframeCallbackHandler = keyframeManager.keyframeCallbackHandler();

                trackTimer.reset();
                keyframeCallbackHandler.reset();
            }
        }
    );

    public static final AzPlayBehavior PLAY_ONCE = AzPlayBehaviorRegistry.register(
        new AzPlayBehavior("play_once") {

            @Override
            public void onFinish(AzAnimationTrackStateMachine.Context<?> context) {
                advanceOrStop(context);
            }
        }
    );

    /**
     * Placeholder meaning "use whatever the animation file says" (its {@code loop}, {@code repeat_times} and
     * {@code freeze_at} fields, as written by the AzureLib Blockbench plugin). It is swapped for the real behavior when
     * the animation is queued, so it never actually runs; stages that don't set a behavior at all are treated the same
     * way. Files without a {@code loop} field fall back to {@link #PLAY_ONCE}.
     */
    public static final AzPlayBehavior AS_AUTHORED = AzPlayBehaviorRegistry.register(
        new AzPlayBehavior("as_authored") {

            @Override
            public void onFinish(AzAnimationTrackStateMachine.Context<?> context) {
                // Unreachable in practice (resolved at queue time); behave like play_once if it ever leaks through.
                advanceOrStop(context);
            }
        }
    );

    /**
     * Plays the animation forward, then backward, then forward again, indefinitely. Each leg starts on the pose the
     * previous one ended on, so the turnaround is seamless without the animation having to be authored as a loop.
     * <p>
     * The leg direction is stored on each track ({@code flipDirection()}), not on this shared instance. If the stage
     * itself is reversed, the first leg runs backward. Like {@link #LOOP}, it never finishes, so nothing can be queued
     * after it. Keyframe events fire once per leg as the sampled tick passes them.
     */
    public static final AzPlayBehavior PING_PONG = AzPlayBehaviorRegistry.register(
        new AzPlayBehavior("ping_pong") {

            @Override
            public void onFinish(AzAnimationTrackStateMachine.Context<?> context) {
                var track = context.animationTrack();

                track.flipDirection();
                track.trackTimer().reset();
                track.keyframeManager().keyframeCallbackHandler().reset();
            }
        }
    );

    /**
     * Plays the animation the number of times set by the track's repeat amount
     * ({@code AzAnimationProperties#repeatXTimes}, or the file's {@code repeat_times}), then moves on to the next
     * queued stage, or stops if there is none.
     * <p>
     * The repeat count is stored on each track, not on this shared behavior instance, so tracks repeating at the same
     * time do not interfere with each other.
     */
    public static final AzPlayBehavior REPEAT_X_TIMES = AzPlayBehaviorRegistry.register(
        new AzPlayBehavior("repeat_x_times") {

            @Override
            public void onFinish(AzAnimationTrackStateMachine.Context<?> context) {
                var track = context.animationTrack();
                var maxRepeats = track.effectiveRepeatXTimes();
                var repeatCount = track.incrementRepeatCount();

                // repeatCount counts finished plays after the first, so the clip has played repeatCount times now.
                if (maxRepeats > 1 && repeatCount < maxRepeats) {
                    track.trackTimer().reset();
                    track.keyframeManager().keyframeCallbackHandler().reset();
                } else {
                    track.resetRepeatCount();
                    advanceOrStop(context);
                }
            }
        }
    );

    /**
     * Finishes the current clip: hands off to the next queued stage inside the play state, or stops the track if
     * nothing is queued. Shared by every behavior that ends (play once, repeat, weighted pools).
     */
    public static void advanceOrStop(AzAnimationTrackStateMachine.Context<?> context) {
        var track = context.animationTrack();
        var queue = track.animationQueue();

        // ⚠⚠ THE RUNNING CLIP IS STILL AT THE HEAD OF THE QUEUE. The play state starts an animation with
        // peek(), not next(), so the current animation stays queued for its whole run. Taking next() here
        // without this check handed the finished clip straight back as "the next one" — every one-shot
        // played TWICE before anything queued behind it got a turn ([stated] "it looks like it closes
        // twice"). Drop it first, then look for a successor.
        if (queue.peek() == track.currentAnimation()) {
            queue.next();
        }

        // ⚠ The one-frame "draw the final frame first" hold that briefly lived here was chasing a symptom of
        // AzAnimationPlayState executing a captured local after this handoff (fixed there). With the play state
        // re-reading the current clip, the successor starts cleanly on the frame the one-shot finishes.
        var next = queue.next();

        if (DEBUG) {
            com.blib.api.client.animation.v1.track.AzAnimationTrack.DEBUG_TRACE_FRAMES = 8;
            LOGGER.info(
                "[animsync] play_once finished {} -> {}",
                track.currentAnimation() == null ? "none" : track.currentAnimation().animation().name(),
                next == null ? "STOP (queue empty)" : "handoff to " + next.animation().name()
            );
        }

        if (next == null) {
            context.stateMachine().stop();
            return;
        }

        // ⚠⚠ HAND OFF INSIDE PLAY — DO NOT PASS THROUGH THE TRANSITION STATE. With a transition length of 0 the
        // TRANSITION state's first update only switches back to PLAY and returns WITHOUT executing any
        // keyframes; the bone cache has already re-applied every bone's rest snapshot for that frame, so the
        // model shows its rest pose for exactly one frame between the one-shot and the loop ("the door snaps
        // open for one frame then closes again, the arm goes back and forth"). Setting the successor here and
        // resetting the timer keeps the track in PLAY: the executor that runs right after this call plays the
        // successor from tick 0, and there is never a frame nothing writes.
        track.setCurrentAnimation(next);
        track.trackTimer().reset();
        track.keyframeManager().keyframeCallbackHandler().reset();
    }
}
