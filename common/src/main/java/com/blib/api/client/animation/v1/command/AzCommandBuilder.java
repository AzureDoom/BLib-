package com.blib.api.client.animation.v1.command;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import com.blib.api.client.animation.v1.command.play_behavior.AzPlayBehavior;
import com.blib.api.client.animation.v1.command.policy.AzDispatchMode;
import com.blib.api.client.animation.v1.command.policy.AzDispatchPolicy;
import com.blib.api.client.animation.v1.command.policy.OnBlockedByEndless;
import com.blib.api.client.animation.v1.command.policy.OnPropertiesChanged;
import com.blib.api.client.animation.v1.command.sequence.AzAnimationSequenceBuilder;
import com.blib.api.client.animation.v1.track.AzTrackHandle;
import com.blib.internal.client.animation.dispatch.command.action.AzAction;
import com.blib.internal.client.animation.dispatch.command.action.impl.AzCancelAction;
import com.blib.internal.client.animation.dispatch.command.action.impl.AzPauseAction;
import com.blib.internal.client.animation.dispatch.command.action.impl.AzPlayAnimationSequenceAction;
import com.blib.internal.client.animation.dispatch.command.action.impl.AzResumeAction;
import com.blib.internal.client.animation.dispatch.command.action.impl.AzSetAnimationSpeedAction;
import com.blib.internal.client.animation.dispatch.command.action.impl.AzSetEasingTypeAction;
import com.blib.internal.client.animation.dispatch.command.action.impl.AzSetFreezeTickAction;
import com.blib.internal.client.animation.dispatch.command.action.impl.AzSetRepeatTimesAction;
import com.blib.internal.client.animation.dispatch.command.action.impl.AzSetReverseAction;
import com.blib.internal.client.animation.dispatch.command.action.impl.AzSetStartTickOffsetAction;
import com.blib.internal.client.animation.dispatch.command.action.impl.AzSetTransitionSpeedAction;
import com.blib.internal.client.animation.dispatch.command.action.impl.AzSkipCurrentAction;
import com.blib.internal.client.animation.easing.AzEasingType;

public class AzCommandBuilder<T> {

    private final List<AzAction<T>> actions;

    private AzDispatchMode dispatchMode;

    private OnBlockedByEndless onBlockedByEndless;

    private OnPropertiesChanged onPropertiesChanged;

    AzCommandBuilder() {
        this.actions = new ArrayList<>();
        this.dispatchMode = null;
        this.onBlockedByEndless = OnBlockedByEndless.APPEND_ANYWAY;
        this.onPropertiesChanged = OnPropertiesChanged.RESTART;
    }

    public AzCommandBuilder<T> dispatchMode(AzDispatchMode mode) {
        this.dispatchMode = mode;
        return this;
    }

    public AzCommandBuilder<T> onBlockedByEndless(OnBlockedByEndless policy) {
        this.onBlockedByEndless = policy;
        return this;
    }

    public AzCommandBuilder<T> onPropertiesChanged(OnPropertiesChanged policy) {
        this.onPropertiesChanged = policy;
        return this;
    }

    public AzCommandBuilder<T> append(AzCommand<T> command) {
        actions.addAll(command.actions());
        return this;
    }

    /**
     * Full cancel: clears the current animation, drains the queue, transitions state machine to STOP.
     */
    public AzCommandBuilder<T> cancel(AzTarget target) {
        actions.add(new AzCancelAction<>(target));
        return this;
    }

    public AzCommandBuilder<T> cancel(AzTrackHandle<? super T> handle) {
        return cancel(AzTarget.track(handle));
    }

    /**
     * Drops the current animation. Queue preserved.
     */
    public AzCommandBuilder<T> skipCurrent(AzTarget target) {
        actions.add(new AzSkipCurrentAction<>(target));
        return this;
    }

    public AzCommandBuilder<T> skipCurrent(AzTrackHandle<? super T> handle) {
        return skipCurrent(AzTarget.track(handle));
    }

    public AzCommandBuilder<T> pause(AzTarget target) {
        actions.add(new AzPauseAction<>(target));
        return this;
    }

    public AzCommandBuilder<T> pause(AzTrackHandle<? super T> handle) {
        return pause(AzTarget.track(handle));
    }

    public AzCommandBuilder<T> resume(AzTarget target) {
        actions.add(new AzResumeAction<>(target));
        return this;
    }

    public AzCommandBuilder<T> resume(AzTrackHandle<? super T> handle) {
        return resume(AzTarget.track(handle));
    }

    public AzCommandBuilder<T> setEasingType(AzTarget target, AzEasingType easingType) {
        actions.add(new AzSetEasingTypeAction<>(target, easingType));
        return this;
    }

    public AzCommandBuilder<T> setEasingType(AzTrackHandle<? super T> handle, AzEasingType easingType) {
        return setEasingType(AzTarget.track(handle), easingType);
    }

    public AzCommandBuilder<T> setSpeed(AzTarget target, double speed) {
        actions.add(new AzSetAnimationSpeedAction<>(target, speed));
        return this;
    }

    public AzCommandBuilder<T> setSpeed(AzTrackHandle<? super T> handle, double speed) {
        return setSpeed(AzTarget.track(handle), speed);
    }

    public AzCommandBuilder<T> setTransitionSpeed(AzTarget target, float transitionSpeed) {
        actions.add(new AzSetTransitionSpeedAction<>(target, transitionSpeed));
        return this;
    }

    public AzCommandBuilder<T> setTransitionSpeed(AzTrackHandle<? super T> handle, float transitionSpeed) {
        return setTransitionSpeed(AzTarget.track(handle), transitionSpeed);
    }

    /**
     * Sets the targeted track's layer weight at once (BLib 3.1.13 layering).
     *
     * @param target the track(s)
     * @param weight 0 (no effect) to 1 (full effect)
     * @return this builder
     */
    public AzCommandBuilder<T> setWeight(AzTarget target, double weight) {
        actions.add(new com.blib.internal.client.animation.dispatch.command.action.impl.AzSetWeightAction<>(target, weight, 0));
        return this;
    }

    /**
     * @param handle the track
     * @param weight 0 (no effect) to 1 (full effect)
     * @return this builder
     */
    public AzCommandBuilder<T> setWeight(AzTrackHandle<? super T> handle, double weight) {
        return setWeight(AzTarget.track(handle), weight);
    }

    /**
     * Fades the targeted track's layer weight over time (BLib 3.1.13 layering).
     *
     * @param target    the track(s)
     * @param weight    the weight to reach, 0 to 1
     * @param fadeTicks animation ticks to take
     * @return this builder
     */
    public AzCommandBuilder<T> fadeWeight(AzTarget target, double weight, double fadeTicks) {
        actions.add(
            new com.blib.internal.client.animation.dispatch.command.action.impl.AzSetWeightAction<>(target, weight, fadeTicks)
        );
        return this;
    }

    /**
     * @param handle    the track
     * @param weight    the weight to reach, 0 to 1
     * @param fadeTicks animation ticks to take
     * @return this builder
     */
    public AzCommandBuilder<T> fadeWeight(AzTrackHandle<? super T> handle, double weight, double fadeTicks) {
        return fadeWeight(AzTarget.track(handle), weight, fadeTicks);
    }

    public AzCommandBuilder<T> setStartTickOffset(AzTarget target, double tickOffset) {
        actions.add(new AzSetStartTickOffsetAction<>(target, tickOffset));
        return this;
    }

    public AzCommandBuilder<T> setStartTickOffset(AzTrackHandle<? super T> handle, double tickOffset) {
        return setStartTickOffset(AzTarget.track(handle), tickOffset);
    }

    public AzCommandBuilder<T> setFreezeTickOffset(AzTarget target, double freezeTickOffset) {
        actions.add(new AzSetFreezeTickAction<>(target, freezeTickOffset));
        return this;
    }

    public AzCommandBuilder<T> setFreezeTickOffset(AzTrackHandle<? super T> handle, double freezeTickOffset) {
        return setFreezeTickOffset(AzTarget.track(handle), freezeTickOffset);
    }

    /**
     * Sets the total number of plays for animations using {@code AzPlayBehaviors.REPEAT_X_TIMES} on the target. Values
     * of 1 or less fall back to the animation file's {@code repeat_times}.
     */
    public AzCommandBuilder<T> setRepeatAmount(AzTarget target, double repeatXTimes) {
        actions.add(new AzSetRepeatTimesAction<>(target, repeatXTimes));
        return this;
    }

    public AzCommandBuilder<T> setRepeatAmount(AzTrackHandle<? super T> handle, double repeatXTimes) {
        return setRepeatAmount(AzTarget.track(handle), repeatXTimes);
    }

    public AzCommandBuilder<T> setReverseAnimation(AzTarget target, boolean hasReverse) {
        actions.add(new AzSetReverseAction<>(target, hasReverse));
        return this;
    }

    public AzCommandBuilder<T> setReverseAnimation(AzTrackHandle<? super T> handle, boolean hasReverse) {
        return setReverseAnimation(AzTarget.track(handle), hasReverse);
    }

    public AzCommandBuilder<T> play(AzTarget target, String animationName) {
        return playSequence(target, builder -> builder.queue(animationName, properties -> properties));
    }

    public AzCommandBuilder<T> play(AzTrackHandle<? super T> handle, String animationName) {
        return play(AzTarget.track(handle), animationName);
    }

    public AzCommandBuilder<T> play(AzTarget target, String animationName, AzPlayBehavior playBehavior) {
        return playSequence(
            target,
            builder -> builder.queue(animationName, properties -> properties.withPlayBehavior(playBehavior))
        );
    }

    public AzCommandBuilder<T> play(AzTrackHandle<? super T> handle, String animationName, AzPlayBehavior playBehavior) {
        return play(AzTarget.track(handle), animationName, playBehavior);
    }

    public AzCommandBuilder<T> playSequence(
        AzTarget target,
        UnaryOperator<AzAnimationSequenceBuilder> builderUnaryOperator
    ) {
        var sequence = builderUnaryOperator.apply(new AzAnimationSequenceBuilder()).build();
        actions.add(new AzPlayAnimationSequenceAction<>(target, sequence, currentPolicy()));
        return this;
    }

    public AzCommandBuilder<T> playSequence(
        AzTrackHandle<? super T> handle,
        UnaryOperator<AzAnimationSequenceBuilder> builderUnaryOperator
    ) {
        return playSequence(AzTarget.track(handle), builderUnaryOperator);
    }

    /**
     * Plays a reusable {@link com.blib.api.client.animation.v1.command.sequence.AzSequence} (BLib 3.1.13). Its
     * timed events are delivered by an AzSequencePlayer, not by the command itself.
     *
     * @param target   the track(s) to play it on
     * @param sequence the sequence
     * @return this builder
     */
    public AzCommandBuilder<T> playSequence(
        AzTarget target,
        com.blib.api.client.animation.v1.command.sequence.AzSequence sequence
    ) {
        actions.add(new AzPlayAnimationSequenceAction<>(target, sequence.toAnimationSequence(), currentPolicy()));
        return this;
    }

    /**
     * @param handle   the track to play it on
     * @param sequence the sequence
     * @return this builder
     */
    public AzCommandBuilder<T> playSequence(
        AzTrackHandle<? super T> handle,
        com.blib.api.client.animation.v1.command.sequence.AzSequence sequence
    ) {
        return playSequence(AzTarget.track(handle), sequence);
    }

    public AzCommand<T> build() {
        return new AzCommand<>(List.copyOf(actions));
    }

    /**
     * Materializes the {@link AzDispatchPolicy} for the play action being added. Throws if no dispatch mode has been
     * set — every play action must declare its dispatch intent. Use {@link AzCommand#replay()},
     * {@link AzCommand#idempotent()}, {@link AzCommand#enqueueing()}, or call {@link #dispatchMode(AzDispatchMode)} on
     * the builder.
     */
    private AzDispatchPolicy currentPolicy() {
        if (dispatchMode == null) {
            throw new IllegalStateException(
                "No dispatch mode set on this command builder. Call dispatchMode(...) before adding a "
                    + "play action, or start the builder via AzCommand.replay() / .idempotent() / "
                    + ".enqueueing()."
            );
        }

        return new AzDispatchPolicy(dispatchMode, onBlockedByEndless, onPropertiesChanged);
    }
}
