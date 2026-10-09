package com.blib.internal.client.animation.track.keyframe;

import com.blib.api.client.animation.v1.keyframe.AzKeyframeCallbacks;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

import com.blib.api.client.animation.v1.track.AzAnimationTrack;
import com.blib.api.client.animation.v1.keyframe.event.AzCustomInstructionKeyframeEvent;
import com.blib.api.client.animation.v1.keyframe.event.AzParticleKeyframeEvent;
import com.blib.api.client.animation.v1.keyframe.event.AzSoundKeyframeEvent;
import com.blib.internal.client.animation.primitive.AzQueuedAnimation;
import com.blib.api.client.animation.v1.keyframe.data.KeyFrameData;

/**
 * AzKeyframeCallbackHandler acts as a handler for managing animation keyframe events such as sound, particle, or custom
 * events during a specific animation. It works in conjunction with an animation track and a set of keyframe
 * callbacks, executing them as appropriate based on the animation's progress. <br>
 * This class is generic and operates on a user-defined animatable type to handle various keyframe events related to
 * animations.
 *
 * @param <T> the type of the animatable object being handled
 */
// TODO: reduce the boilerplate of the specialized handle functions in this class.
public class AzKeyframeCallbackHandler<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzKeyframeCallbackHandler.class);

    private final AzAnimationTrack<T> animationTrack;

    private final Set<KeyFrameData> executedKeyframes;

    private final AzKeyframeCallbacks<T> keyframeCallbacks;

    private boolean reversed;

    public AzKeyframeCallbackHandler(
        AzAnimationTrack<T> animationTrack,
        AzKeyframeCallbacks<T> keyframeCallbacks
    ) {
        this.animationTrack = animationTrack;
        this.executedKeyframes = new ObjectOpenHashSet<>();
        this.keyframeCallbacks = keyframeCallbacks;
    }

    public void handle(T animatable, double adjustedTick) {
        handle(animatable, adjustedTick, false);
    }

    public void handle(T animatable, double sampleTick, boolean reversed) {
        this.reversed = reversed;
        handleSoundKeyframes(animatable, sampleTick);
        handleParticleKeyframes(animatable, sampleTick);
        handleCustomKeyframes(animatable, sampleTick);
    }

    private boolean reached(KeyFrameData keyframeData, double sampleTick) {
        return reversed ? sampleTick <= keyframeData.getStartTick() : sampleTick >= keyframeData.getStartTick();
    }

    private void handleCustomKeyframes(T animatable, double adjustedTick) {
        var customKeyframeHandler = keyframeCallbacks.customKeyframeHandler();
        var customInstructions = currentAnimation().animation().keyframes().customInstructions();

        for (var keyframeData : customInstructions) {
            if (reached(keyframeData, adjustedTick) && executedKeyframes.add(keyframeData)) {
                if (customKeyframeHandler == null) {
                    LOGGER.warn(
                        "Custom Instruction Keyframe found for {} -> {}, but no keyframe handler registered",
                        animatable.getClass().getSimpleName(),
                        animationTrack.name()
                    );
                    break;
                }

                customKeyframeHandler.handle(
                    new AzCustomInstructionKeyframeEvent<>(animatable, adjustedTick, animationTrack, keyframeData)
                );
            }
        }
    }

    private void handleParticleKeyframes(T animatable, double adjustedTick) {
        var particleKeyframeHandler = keyframeCallbacks.particleKeyframeHandler();
        var particleInstructions = currentAnimation().animation().keyframes().particles();

        for (var keyframeData : particleInstructions) {
            if (reached(keyframeData, adjustedTick) && executedKeyframes.add(keyframeData)) {
                if (particleKeyframeHandler == null) {
                    LOGGER.warn(
                        "Particle Keyframe found for {} -> {}, but no keyframe handler registered",
                        animatable.getClass().getSimpleName(),
                        animationTrack.name()
                    );
                    break;
                }

                particleKeyframeHandler.handle(
                    new AzParticleKeyframeEvent<>(animatable, adjustedTick, animationTrack, keyframeData)
                );
            }
        }
    }

    private void handleSoundKeyframes(T animatable, double adjustedTick) {
        var soundKeyframeHandler = keyframeCallbacks.soundKeyframeHandler();
        var soundInstructions = currentAnimation().animation().keyframes().sounds();

        for (var keyframeData : soundInstructions) {
            if (reached(keyframeData, adjustedTick) && executedKeyframes.add(keyframeData)) {
                if (soundKeyframeHandler == null) {
                    LOGGER.warn(
                        "Sound Keyframe found for {} -> {}, but no keyframe handler registered",
                        animatable.getClass().getSimpleName(),
                        animationTrack.name()
                    );
                    break;
                }

                soundKeyframeHandler.handle(
                    new AzSoundKeyframeEvent<>(animatable, adjustedTick, animationTrack, keyframeData)
                );
            }
        }
    }

    /**
     * Clear the {@link KeyFrameData} cache in preparation for the next animation
     */
    public void reset() {
        executedKeyframes.clear();
    }

    public void resync(double sampleTick, boolean reversed) {
        this.reversed = reversed;
        executedKeyframes.clear();

        var current = currentAnimation();

        if (current == null) {
            return;
        }

        var keyframes = current.animation().keyframes();

        markReached(keyframes.sounds(), sampleTick);
        markReached(keyframes.particles(), sampleTick);
        markReached(keyframes.customInstructions(), sampleTick);
    }

    private void markReached(KeyFrameData[] keyframes, double sampleTick) {
        for (var keyframeData : keyframes) {
            if (reached(keyframeData, sampleTick)) {
                executedKeyframes.add(keyframeData);
            }
        }
    }

    private AzQueuedAnimation currentAnimation() {
        return animationTrack.currentAnimation();
    }
}
