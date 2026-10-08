package com.blib.api.client.animation.v1.track;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

import com.blib.api.client.animation.v1.animator.AzAnimator;
import com.blib.api.client.animation.v1.keyframe.AzKeyframeCallbacks;
import com.blib.internal.client.animation.easing.AzEasingType;
import com.blib.internal.client.animation.property.AzAnimationProperties;

public class AzAnimationTrackBuilder<T> {

    private final AzAnimator<?, T> animator;

    private final String name;

    private AzAnimationProperties animationProperties;

    private AzKeyframeCallbacks<T> keyframeCallbacks;

    public AzAnimationTrackBuilder(AzAnimator<?, T> animator, String name) {
        this.animator = animator;
        this.name = name;
        this.animationProperties = AzAnimationProperties.DEFAULT;
        this.keyframeCallbacks = AzKeyframeCallbacks.noop();
    }

    public AzAnimationTrackBuilder<T> setAnimationSpeed(double animationSpeed) {
        animationProperties = animationProperties.withAnimationSpeed(animationSpeed);
        return this;
    }

    public AzAnimationTrackBuilder<T> setKeyframeCallbacks(@NotNull AzKeyframeCallbacks<T> keyframeCallbacks) {
        Objects.requireNonNull(keyframeCallbacks);
        this.keyframeCallbacks = keyframeCallbacks;
        return this;
    }

    public AzAnimationTrackBuilder<T> setEasingType(AzEasingType easingType) {
        animationProperties = animationProperties.withEasingType(easingType);
        return this;
    }

    public AzAnimationTrackBuilder<T> setTransitionLength(int transitionLength) {
        animationProperties = animationProperties.withTransitionLength(transitionLength);
        return this;
    }

    public AzAnimationTrackBuilder<T> setStartTickOffset(double startTickOffset) {
        animationProperties = animationProperties.withStartTickOffset(startTickOffset);
        return this;
    }

    // AzureLib 3.1.13 layering options, applied after the track is built.
    private double weight = 1;

    private AzBlendMode blendMode = AzBlendMode.OVERRIDE;

    private AzBoneMask boneMask = AzBoneMask.ALL;

    /**
     * @param weight the track's starting layer weight, 0 to 1
     * @return this builder
     */
    public AzAnimationTrackBuilder<T> setWeight(double weight) {
        this.weight = weight;
        return this;
    }

    /**
     * @param blendMode how the track combines with the tracks added before it
     * @return this builder
     */
    public AzAnimationTrackBuilder<T> setBlendMode(AzBlendMode blendMode) {
        this.blendMode = blendMode;
        return this;
    }

    /**
     * @param boneMask the bones the track may animate
     * @return this builder
     */
    public AzAnimationTrackBuilder<T> setBoneMask(AzBoneMask boneMask) {
        this.boneMask = boneMask;
        return this;
    }

    public AzAnimationTrack<T> build() {
        var track = new AzAnimationTrack<>(
            name,
            animator,
            animationProperties,
            keyframeCallbacks
        );
        track.setWeight(weight);
        track.setBlendMode(blendMode);
        track.setBoneMask(boneMask);
        return track;
    }
}
