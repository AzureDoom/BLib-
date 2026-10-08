package com.blib.internal.client.animation;

import com.blib.api.client.animation.v1.track.AzBlendMode;
import com.blib.api.client.model.v1.AzBone;
import com.blib.internal.client.animation.easing.AzEasingType;
import com.blib.internal.client.animation.easing.AzEasingUtil;
import com.blib.internal.client.animation.track.keyframe.AzBoneAnimationQueue;
import com.blib.internal.client.model.AzBoneSnapshot;

public class AzBoneAnimationUpdateUtil {

    // AzureLib 3.1.13 layering. The original four-argument methods are kept and route to weight 1 / OVERRIDE, which
    // takes exactly the old path: the blend block below runs only for a weight under 1 or ADDITIVE, so an unlayered
    // track writes the same values as before. The frame number lets a later layer blend over what an earlier layer
    // wrote this frame instead of over the bind pose.

    public static void updatePositions(
        AzBoneAnimationQueue boneAnimation,
        AzBone bone,
        AzEasingType easingType,
        AzBoneSnapshot snapshot
    ) {
        updatePositions(boneAnimation, bone, easingType, bone.getInitialAzSnapshot(), snapshot, 1, AzBlendMode.OVERRIDE, -1);
    }

    public static void updatePositions(
        AzBoneAnimationQueue boneAnimation,
        AzBone bone,
        AzEasingType easingType,
        AzBoneSnapshot initialSnapshot,
        AzBoneSnapshot snapshot,
        double weight,
        AzBlendMode blendMode,
        long frame
    ) {
        var posXPoint = boneAnimation.positionXQueue().poll();
        var posYPoint = boneAnimation.positionYQueue().poll();
        var posZPoint = boneAnimation.positionZQueue().poll();

        if (posXPoint == null || posYPoint == null || posZPoint == null || weight <= 0) {
            return;
        }

        var x = (float) AzEasingUtil.lerpWithOverride(posXPoint, easingType);
        var y = (float) AzEasingUtil.lerpWithOverride(posYPoint, easingType);
        var z = (float) AzEasingUtil.lerpWithOverride(posZPoint, easingType);

        if (blendMode == AzBlendMode.ADDITIVE || weight < 1) {
            var layered = snapshot.isPositionWrittenInFrame(frame);
            var w = (float) weight;
            var baseX = layered ? bone.getPosX() : initialSnapshot.getOffsetX();
            var baseY = layered ? bone.getPosY() : initialSnapshot.getOffsetY();
            var baseZ = layered ? bone.getPosZ() : initialSnapshot.getOffsetZ();

            if (blendMode == AzBlendMode.ADDITIVE) {
                x = baseX + (x - initialSnapshot.getOffsetX()) * w;
                y = baseY + (y - initialSnapshot.getOffsetY()) * w;
                z = baseZ + (z - initialSnapshot.getOffsetZ()) * w;
            } else {
                x = blend(baseX, x, w);
                y = blend(baseY, y, w);
                z = blend(baseZ, z, w);
            }
        }

        bone.setPosX(x);
        bone.setPosY(y);
        bone.setPosZ(z);
        snapshot.updateOffset(bone.getPosX(), bone.getPosY(), bone.getPosZ());
        snapshot.startPosAnim();
        snapshot.markPositionWritten(frame);
        bone.markPositionAsChanged();
    }

    public static void updateRotations(
        AzBoneAnimationQueue boneAnimation,
        AzBone bone,
        AzEasingType easingType,
        AzBoneSnapshot initialSnapshot,
        AzBoneSnapshot snapshot
    ) {
        updateRotations(boneAnimation, bone, easingType, initialSnapshot, snapshot, 1, AzBlendMode.OVERRIDE, -1);
    }

    public static void updateRotations(
        AzBoneAnimationQueue boneAnimation,
        AzBone bone,
        AzEasingType easingType,
        AzBoneSnapshot initialSnapshot,
        AzBoneSnapshot snapshot,
        double weight,
        AzBlendMode blendMode,
        long frame
    ) {
        var rotXPoint = boneAnimation.rotationXQueue().poll();
        var rotYPoint = boneAnimation.rotationYQueue().poll();
        var rotZPoint = boneAnimation.rotationZQueue().poll();

        if (rotXPoint == null || rotYPoint == null || rotZPoint == null || weight <= 0) {
            return;
        }

        // Keyframe rotations are offsets from the bind rotation.
        var x = (float) AzEasingUtil.lerpWithOverride(rotXPoint, easingType) + initialSnapshot.getRotX();
        var y = (float) AzEasingUtil.lerpWithOverride(rotYPoint, easingType) + initialSnapshot.getRotY();
        var z = (float) AzEasingUtil.lerpWithOverride(rotZPoint, easingType) + initialSnapshot.getRotZ();

        if (blendMode == AzBlendMode.ADDITIVE || weight < 1) {
            // Per-axis Euler blending, consistent with how keyframes themselves interpolate.
            var layered = snapshot.isRotationWrittenInFrame(frame);
            var w = (float) weight;
            var baseX = layered ? bone.getRotX() : initialSnapshot.getRotX();
            var baseY = layered ? bone.getRotY() : initialSnapshot.getRotY();
            var baseZ = layered ? bone.getRotZ() : initialSnapshot.getRotZ();

            if (blendMode == AzBlendMode.ADDITIVE) {
                x = baseX + (x - initialSnapshot.getRotX()) * w;
                y = baseY + (y - initialSnapshot.getRotY()) * w;
                z = baseZ + (z - initialSnapshot.getRotZ()) * w;
            } else {
                x = blend(baseX, x, w);
                y = blend(baseY, y, w);
                z = blend(baseZ, z, w);
            }
        }

        bone.setRotX(x);
        bone.setRotY(y);
        bone.setRotZ(z);
        snapshot.updateRotation(bone.getRotX(), bone.getRotY(), bone.getRotZ());
        snapshot.startRotAnim();
        snapshot.markRotationWritten(frame);
        bone.markRotationAsChanged();
    }

    public static void updateScale(
        AzBoneAnimationQueue boneAnimation,
        AzBone bone,
        AzEasingType easingType,
        AzBoneSnapshot snapshot
    ) {
        updateScale(boneAnimation, bone, easingType, bone.getInitialAzSnapshot(), snapshot, 1, AzBlendMode.OVERRIDE, -1);
    }

    public static void updateScale(
        AzBoneAnimationQueue boneAnimation,
        AzBone bone,
        AzEasingType easingType,
        AzBoneSnapshot initialSnapshot,
        AzBoneSnapshot snapshot,
        double weight,
        AzBlendMode blendMode,
        long frame
    ) {
        var scaleXPoint = boneAnimation.scaleXQueue().poll();
        var scaleYPoint = boneAnimation.scaleYQueue().poll();
        var scaleZPoint = boneAnimation.scaleZQueue().poll();

        if (scaleXPoint == null || scaleYPoint == null || scaleZPoint == null || weight <= 0) {
            return;
        }

        var x = (float) AzEasingUtil.lerpWithOverride(scaleXPoint, easingType);
        var y = (float) AzEasingUtil.lerpWithOverride(scaleYPoint, easingType);
        var z = (float) AzEasingUtil.lerpWithOverride(scaleZPoint, easingType);

        if (blendMode == AzBlendMode.ADDITIVE || weight < 1) {
            var layered = snapshot.isScaleWrittenInFrame(frame);
            var w = (float) weight;
            var baseX = layered ? bone.getScaleX() : initialSnapshot.getScaleX();
            var baseY = layered ? bone.getScaleY() : initialSnapshot.getScaleY();
            var baseZ = layered ? bone.getScaleZ() : initialSnapshot.getScaleZ();

            if (blendMode == AzBlendMode.ADDITIVE) {
                // Scale multiplies: a keyframe of 1.2 over a bind scale of 1 grows whatever is underneath by 20%.
                x = baseX * blend(1, ratio(x, initialSnapshot.getScaleX()), w);
                y = baseY * blend(1, ratio(y, initialSnapshot.getScaleY()), w);
                z = baseZ * blend(1, ratio(z, initialSnapshot.getScaleZ()), w);
            } else {
                x = blend(baseX, x, w);
                y = blend(baseY, y, w);
                z = blend(baseZ, z, w);
            }
        }

        bone.setScaleX(x);
        bone.setScaleY(y);
        bone.setScaleZ(z);
        snapshot.updateScale(bone.getScaleX(), bone.getScaleY(), bone.getScaleZ());
        snapshot.startScaleAnim();
        snapshot.markScaleWritten(frame);
        bone.markScaleAsChanged();
    }

    private static float blend(float from, float to, float weight) {
        return from + (to - from) * weight;
    }

    private static float ratio(float value, float bindScale) {
        return bindScale == 0 ? value : value / bindScale;
    }
}
