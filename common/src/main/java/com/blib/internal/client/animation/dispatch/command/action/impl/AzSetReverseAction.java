package com.blib.internal.client.animation.dispatch.command.action.impl;

import com.blib.api.client.animation.v1.animator.AzAnimator;
import com.blib.api.client.animation.v1.command.AzTarget;
import com.blib.internal.client.animation.dispatch.command.action.AzAction;

public record AzSetReverseAction<T>(
    AzTarget target,
    boolean hasReverse
) implements AzAction<T> {

    @Override
    public void handle(AzAnimator<?, T> animator) {
        target.forEach(
            animator.getAnimationTrackContainer(),
            // Turns a playing animation around in place instead of snapping to the mirrored pose.
            track -> track.setReversing(hasReverse)
        );
    }
}
