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
                var freezeTickOffset = track.animationProperties().freezeTickOffset();

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

            /**
             * ⚠⚠ A FINISHED ONE-SHOT HANDS OFF TO THE QUEUE INSTEAD OF STOPPING DEAD. This used to be an unconditional
             * stop(), which meant anything ENQUEUED behind a play-once never played: the track sat in STOP with nothing
             * current, the bone cache eased every bone back toward its REST snapshot, and whatever came next (an idle
             * loop re-sent a tick later) snapped it back — a visible one-tick twitch at the end of every one-shot. Now:
             * if the queue has a next animation, move to it exactly the way the play state's own tryPlayNextOrStop does
             * (transition() resets the track timer synchronously, so the executor runs the next clip from tick 0 in
             * this same update); if not, stop as before.
             */
            @Override
            public void onFinish(AzAnimationTrackStateMachine.Context<?> context) {
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
    );
}
