package com.blib.internal.client.animation.dispatch.command.action.impl;

import com.blib.api.client.animation.v1.animator.AzAnimator;
import com.blib.api.client.animation.v1.command.AzTarget;
import com.blib.internal.client.animation.dispatch.command.action.AzAction;

/**
 * Sets or fades the layer weight of the targeted track(s) - AzureLib 3.1.13 layering.
 * <p>
 * BLib commands run on the client (AzCommand refuses server-side dispatch), so unlike AzureLib this needs no network
 * message: a mod changes a layer's weight where its other animation commands already run.
 *
 * @param target    the track(s)
 * @param weight    the weight to set or fade to, 0 to 1
 * @param fadeTicks animation ticks to fade over; 0 or less sets it at once
 * @param <T>       the animatable type
 */
public record AzSetWeightAction<T>(
    AzTarget target,
    double weight,
    double fadeTicks
) implements AzAction<T> {

    @Override
    public void handle(AzAnimator<?, T> animator) {
        target.forEach(animator.getAnimationTrackContainer(), track -> track.fadeWeight(weight, fadeTicks));
    }
}
