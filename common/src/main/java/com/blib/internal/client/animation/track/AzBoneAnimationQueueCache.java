package com.blib.internal.client.animation.track;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Map;

import com.blib.internal.client.animation.AzBoneAnimationUpdateUtil;
import com.blib.internal.client.animation.cache.AzBoneCache;
import com.blib.internal.client.animation.easing.AzEasingType;
import com.blib.internal.client.animation.track.keyframe.AzBoneAnimationQueue;

public class AzBoneAnimationQueueCache<T> {

    private final Map<String, AzBoneAnimationQueue> boneAnimationQueues;

    private final AzBoneCache boneCache;

    public AzBoneAnimationQueueCache(AzBoneCache boneCache) {
        this.boneAnimationQueues = new Object2ObjectOpenHashMap<>();
        this.boneCache = boneCache;
    }

    public void update(AzEasingType easingType) {
        update(easingType, 1, com.blib.api.client.animation.v1.track.AzBlendMode.OVERRIDE);
    }

    /** AzureLib 3.1.13 layering - applies this frame's keyframes at the track's weight and blend mode. */
    public void update(
        AzEasingType easingType,
        double weight,
        com.blib.api.client.animation.v1.track.AzBlendMode blendMode
    ) {
        var frame = boneCache.currentFrame();

        var boneSnapshots = boneCache.getBoneSnapshotsByName();

        for (var boneAnimation : boneAnimationQueues.values()) {
            var bone = boneAnimation.bone();
            var snapshot = boneSnapshots.get(bone.getName());
            var initialSnapshot = bone.getInitialAzSnapshot();

            AzBoneAnimationUpdateUtil
                .updateRotations(boneAnimation, bone, easingType, initialSnapshot, snapshot, weight, blendMode, frame);
            AzBoneAnimationUpdateUtil
                .updatePositions(boneAnimation, bone, easingType, initialSnapshot, snapshot, weight, blendMode, frame);
            AzBoneAnimationUpdateUtil
                .updateScale(boneAnimation, bone, easingType, initialSnapshot, snapshot, weight, blendMode, frame);
        }
    }

    public Collection<AzBoneAnimationQueue> values() {
        return boneAnimationQueues.values();
    }

    public @Nullable AzBoneAnimationQueue getOrNull(String boneName) {
        var bone = boneCache.getBakedModel().getBoneOrNull(boneName);

        if (bone == null) {
            return null;
        }

        return boneAnimationQueues.computeIfAbsent(boneName, $ -> new AzBoneAnimationQueue(bone));
    }

    public void clear() {
        boneAnimationQueues.clear();
    }
}
