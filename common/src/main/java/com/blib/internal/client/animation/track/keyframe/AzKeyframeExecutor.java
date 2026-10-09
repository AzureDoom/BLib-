package com.blib.internal.client.animation.track.keyframe;

import org.jetbrains.annotations.NotNull;

import java.util.NoSuchElementException;
import java.util.function.DoubleSupplier;

import com.blib.api.client.animation.v1.track.AzAnimationTrack;
import com.blib.internal.client.animation.track.AzBoneAnimationQueueCache;
import com.blib.internal.client.animation.primitive.AzQueuedAnimation;
import com.blib.internal.common.molang.math.IValue;
import com.blib.internal.common.molang.MolangQueries;
import com.blib.internal.common.molang.MolangVariableRef;
import com.blib.api.common.spatial.v1.Axis;

/**
 * AzKeyframeExecutor is a specialized implementation of {@link AzAbstractKeyframeExecutor}, designed to handle
 * keyframe-based animations for animatable objects. It delegates animation control to an {@link AzAnimationTrack}
 * and manages bone animation queues through an {@link AzBoneAnimationQueueCache}. <br>
 * This class processes and applies transformations such as rotation, position, and scale to bone animations, based on
 * the current tick time and the keyframes associated with each bone animation.
 *
 * @param <T> The type of the animatable object to which the keyframe animations will be applied
 */
public class AzKeyframeExecutor<T> extends AzAbstractKeyframeExecutor {

    private static final MolangVariableRef ANIM_TIME_REF = new MolangVariableRef(MolangQueries.ANIM_TIME);

    private final AzAnimationTrack<T> animationTrack;

    private final AzBoneAnimationQueueCache<T> boneAnimationQueueCache;

    private double currentAdjustedTick;

    private final DoubleSupplier animTimeSupplier = () -> currentAdjustedTick / 20d;

    public AzKeyframeExecutor(
        AzAnimationTrack<T> animationTrack,
        AzBoneAnimationQueueCache<T> boneAnimationQueueCache
    ) {
        this.animationTrack = animationTrack;
        this.boneAnimationQueueCache = boneAnimationQueueCache;
    }

    /**
     * Handle the current animation's state modifications and translations
     *
     * @param crashWhenCantFindBone Whether the track should throw an exception when unable to find the required
     *                              bone, or continue with the remaining bones
     */
    public void execute(@NotNull AzQueuedAnimation currentAnimation, T animatable, boolean crashWhenCantFindBone) {
        var keyframeCallbackHandler = animationTrack.keyframeManager().keyframeCallbackHandler();
        currentAdjustedTick = animationTrack.sampleTick();
        ANIM_TIME_REF.setMemoized(animTimeSupplier);

        var animation = currentAnimation.animation();
        var boneAnimations = animation.boneAnimations();
        var cursors = prepareKeyframeCursors(animation);
        var queues = boneAnimationQueueCache.resolveQueues(animation);

        for (var boneIndex = 0; boneIndex < boneAnimations.length; boneIndex++) {
            var boneAnimation = boneAnimations[boneIndex];
            var boneAnimationQueue = queues[boneIndex];

            if (boneAnimationQueue == null) {
                if (crashWhenCantFindBone) {
                    throw new NoSuchElementException("Could not find bone: " + boneAnimation.boneName());
                }

                continue;
            }

            // Bones outside the track's mask are skipped before their keyframes are evaluated.
            var mask = animationTrack.boneMask();

            // The all-bones mask (the default) skips the check entirely.
            if (!mask.isAll() && !mask.includes(boneAnimationQueue.bone())) {
                continue;
            }

            sampleStack(boneAnimation.rotationKeyframes(), ROTATION, boneIndex, boneAnimationQueue, cursors);
            sampleStack(boneAnimation.positionKeyframes(), POSITION, boneIndex, boneAnimationQueue, cursors);
            sampleStack(boneAnimation.scaleKeyframes(), SCALE, boneIndex, boneAnimationQueue, cursors);
        }

        keyframeCallbackHandler.handle(animatable, currentAdjustedTick, animationTrack.isPlayingReversed());
    }

    /**
     * Samples all three axes of one transform at the current tick and writes them into the bone's queue.
     */
    private void sampleStack(
        AzKeyframeStack<AzKeyframe<IValue>> keyframes,
        int transform,
        int boneIndex,
        AzBoneAnimationQueue queue,
        int[] cursors
    ) {
        if (keyframes.xKeyframes().isEmpty()) {
            return;
        }

        var tick = currentAdjustedTick;
        var cursor = cursorIndex(boneIndex, transform, 0);

        writeChannel(queue, transform, Axis.X, keyframes.xChannel(), tick, cursors, cursor);
        writeChannel(queue, transform, Axis.Y, keyframes.yChannel(), tick, cursors, cursor + 1);
        writeChannel(queue, transform, Axis.Z, keyframes.zChannel(), tick, cursors, cursor + 2);
    }
}
