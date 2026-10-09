package com.blib.internal.client.animation.track;

import com.blib.api.client.animation.v1.track.AzAnimationTrack;
import com.blib.api.client.animation.v1.animator.AzAnimationContext;
import com.blib.internal.client.animation.track.state.machine.AzAnimationTrackStateMachine;

/**
 * A timer utility that integrates directly with an {@link AzAnimationTrack} to track and adjust tick values for
 * animation playback control, based on the track's state and animation speed modifiers.
 *
 * @param <T> The type of the animatable entity being controlled by the animation track.
 */
public class AzAnimationTrackTimer<T> {

    private final AzAnimationTrack<T> animationTrack;

    private double adjustedTick;

    private double tickOffset;

    public AzAnimationTrackTimer(AzAnimationTrack<T> animationTrack) {
        this.animationTrack = animationTrack;
    }

    public void update() {
        AzAnimationTrackStateMachine<?> stateMachine = animationTrack.stateMachine();
        AzAnimationContext<?> animContext = stateMachine.getContext().animationContext();
        double animationSpeed = animationTrack.animationProperties().animationSpeed();
        double tick = animContext.timer().getAnimTime();
        double tickStartOffset = animationTrack.animationProperties().startTickOffset();
        double freezeTick = animationTrack.effectiveFreezeTickOffset();

        if (freezeTick > 0 && adjustedTick >= freezeTick) {
            adjustedTick = freezeTick;
            return;
        }

        adjustedTick = animationSpeed * Math.max((tick + tickStartOffset) - tickOffset, tickStartOffset);
    }

    /**
     * Resets the internal state of the animation timer. This method updates the tick offset value to the current
     * animation time retrieved from the associated animation context's timer and resets the adjusted tick to zero. It
     * effectively synchronizes the timer with the current state of the animation track, ensuring that subsequent
     * tick calculations reflect the reset starting point.
     */
    public void reset() {
        AzAnimationTrackStateMachine<?> stateMachine = animationTrack.stateMachine();
        AzAnimationContext<?> animContext = stateMachine.getContext().animationContext();
        this.tickOffset = animContext.timer().getAnimTime();
        this.adjustedTick = 0;
    }

    /**
     * Moves playback progress to {@code progress} in a way that survives the next {@link #update()}, by solving
     * {@code update()}'s formula for the tick offset. Unlike {@link #addToAdjustedTick(double)}, which is overwritten
     * on the next update, this persists.
     *
     * @param progress the adjusted tick to continue from
     */
    public void seek(double progress) {
        var properties = animationTrack.animationProperties();
        var speed = properties.animationSpeed();
        var animTime = animationTrack.stateMachine().getContext().animationContext().timer().getAnimTime();

        if (speed != 0) {
            this.tickOffset = animTime + properties.startTickOffset() - progress / speed;
        }

        this.adjustedTick = progress;
    }

    /**
     * Retrieves the adjusted tick value for the animation timer. The adjusted tick represents the calculated
     * progression of the animation timer, accounting for modifiers such as animation speed and tick offset.
     *
     * @return The current adjusted tick value as a double.
     */
    public double getAdjustedTick() {
        return adjustedTick;
    }

    /**
     * Adds the specified value to the currently tracked adjusted tick value for the animation track timer. This
     * method increments the adjusted tick by the provided amount, allowing for cumulative adjustments to the tick value
     * over time.
     *
     * @param adjustedTick The value to be added to the current-adjusted tick. This parameter represents the amount by
     *                     which the adjusted tick should be updated.
     */
    public void addToAdjustedTick(double adjustedTick) {
        this.adjustedTick += adjustedTick;
    }
}
