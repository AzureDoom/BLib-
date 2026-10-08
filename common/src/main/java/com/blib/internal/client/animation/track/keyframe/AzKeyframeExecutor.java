package com.blib.internal.client.animation.track.keyframe;

import org.jetbrains.annotations.NotNull;

import java.util.NoSuchElementException;

import com.blib.api.client.animation.v1.track.AzAnimationTrack;
import com.blib.api.common.spatial.v1.Axis;
import com.blib.internal.client.animation.primitive.AzQueuedAnimation;
import com.blib.internal.client.animation.track.AzBoneAnimationQueueCache;
import com.blib.internal.common.molang.MolangQueries;
import com.blib.internal.common.molang.MolangVariableRef;
import com.blib.internal.common.molang.math.IValue;

public class AzKeyframeExecutor<T> extends AzAbstractKeyframeExecutor {

    private final AzAnimationTrack<T> animationTrack;

    private final AzBoneAnimationQueueCache<T> boneAnimationQueueCache;

    public AzKeyframeExecutor(
        AzAnimationTrack<T> animationTrack,
        AzBoneAnimationQueueCache<T> boneAnimationQueueCache
    ) {
        this.animationTrack = animationTrack;
        this.boneAnimationQueueCache = boneAnimationQueueCache;
    }

    private static final MolangVariableRef ANIM_TIME_REF = new MolangVariableRef(MolangQueries.ANIM_TIME);

    /** The adjusted tick of the frame being executed, read by {@link #animTimeSupplier}. */
    private double currentAdjustedTick;

    private final java.util.function.DoubleSupplier animTimeSupplier = () -> currentAdjustedTick / 20d;

    public void execute(@NotNull AzQueuedAnimation currentAnimation, T animatable, boolean crashWhenCantFindBone) {
        var keyframeCallbackHandler = animationTrack.keyframeManager().keyframeCallbackHandler();
        var trackTimer = animationTrack.trackTimer();

        // AzureLib 3.1.13 port: bound through a reference resolved once to a supplier created once - no allocation.
        currentAdjustedTick = trackTimer.getAdjustedTick();
        ANIM_TIME_REF.setMemoized(animTimeSupplier);

        for (var boneAnimation : currentAnimation.animation().boneAnimations()) {
            var boneAnimationQueue = boneAnimationQueueCache.getOrNull(boneAnimation.boneName());

            if (boneAnimationQueue == null) {
                if (crashWhenCantFindBone) {
                    throw new NoSuchElementException("Could not find bone: " + boneAnimation.boneName());
                }

                continue;
            }

            var rotationKeyframes = boneAnimation.rotationKeyframes();
            var positionKeyframes = boneAnimation.positionKeyframes();
            var scaleKeyframes = boneAnimation.scaleKeyframes();
            var adjustedTick = trackTimer.getAdjustedTick();

            updateRotation(rotationKeyframes, boneAnimationQueue, adjustedTick);
            updatePosition(positionKeyframes, boneAnimationQueue, adjustedTick);
            updateScale(scaleKeyframes, boneAnimationQueue, adjustedTick);
        }

        keyframeCallbackHandler.handle(animatable, trackTimer.getAdjustedTick());
    }

    private void updateRotation(
        AzKeyframeStack<AzKeyframe<IValue>> keyframes,
        AzBoneAnimationQueue queue,
        double adjustedTick
    ) {
        if (keyframes.xKeyframes().isEmpty()) {
            return;
        }

        var x = getAnimationPointAtTick(keyframes.xKeyframes(), adjustedTick, true, Axis.X);
        var y = getAnimationPointAtTick(keyframes.yKeyframes(), adjustedTick, true, Axis.Y);
        var z = getAnimationPointAtTick(keyframes.zKeyframes(), adjustedTick, true, Axis.Z);

        queue.addRotations(x, y, z);
    }

    private void updatePosition(
        AzKeyframeStack<AzKeyframe<IValue>> keyframes,
        AzBoneAnimationQueue queue,
        double adjustedTick
    ) {
        if (keyframes.xKeyframes().isEmpty()) {
            return;
        }

        var x = getAnimationPointAtTick(keyframes.xKeyframes(), adjustedTick, false, Axis.X);
        var y = getAnimationPointAtTick(keyframes.yKeyframes(), adjustedTick, false, Axis.Y);
        var z = getAnimationPointAtTick(keyframes.zKeyframes(), adjustedTick, false, Axis.Z);

        queue.addPositions(x, y, z);
    }

    private void updateScale(
        AzKeyframeStack<AzKeyframe<IValue>> keyframes,
        AzBoneAnimationQueue queue,
        double adjustedTick
    ) {
        if (keyframes.xKeyframes().isEmpty()) {
            return;
        }

        var x = getAnimationPointAtTick(keyframes.xKeyframes(), adjustedTick, false, Axis.X);
        var y = getAnimationPointAtTick(keyframes.yKeyframes(), adjustedTick, false, Axis.Y);
        var z = getAnimationPointAtTick(keyframes.zKeyframes(), adjustedTick, false, Axis.Z);

        queue.addScales(x, y, z);
    }
}
