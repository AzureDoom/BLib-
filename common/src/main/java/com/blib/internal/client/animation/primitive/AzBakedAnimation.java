package com.blib.internal.client.animation.primitive;

import com.blib.internal.client.animation.track.keyframe.AzBoneAnimation;

/**
 * A baked animation clip.
 *
 * @param defaults playback defaults authored in the animation JSON ({@code loop}, {@code repeat_times},
 *                 {@code freeze_at}), used when a command doesn't say otherwise
 */
public record AzBakedAnimation(
    String name,
    double length,
    AzBoneAnimation[] boneAnimations,
    AzKeyframes keyframes,
    AzAnimationDefaults defaults
) {

    public AzBakedAnimation(String name, double length, AzBoneAnimation[] boneAnimations, AzKeyframes keyframes) {
        this(name, length, boneAnimations, keyframes, AzAnimationDefaults.NONE);
    }
}
