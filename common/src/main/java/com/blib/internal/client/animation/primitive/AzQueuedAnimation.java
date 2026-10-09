package com.blib.internal.client.animation.primitive;

import org.jetbrains.annotations.Nullable;

import com.blib.api.client.animation.v1.command.play_behavior.AzPlayBehavior;

/**
 * An animation waiting in (or playing from) a track's queue.
 *
 * @param reverseOverride per-stage reverse setting, or {@code null} to use the track's animation properties
 */
public record AzQueuedAnimation(
    AzBakedAnimation animation,
    AzPlayBehavior playBehavior,
    @Nullable Boolean reverseOverride
) {

    public AzQueuedAnimation(AzBakedAnimation animation, AzPlayBehavior playBehavior) {
        this(animation, playBehavior, null);
    }
}
