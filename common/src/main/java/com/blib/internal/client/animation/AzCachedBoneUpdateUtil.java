package com.blib.internal.client.animation;

import org.jetbrains.annotations.Nullable;

import java.util.Map;

import com.blib.api.client.model.v1.AzBone;
import com.blib.internal.client.model.AzBoneSnapshot;

/**
 * Eases bones that no track animated this frame back towards their bind pose.
 * <p>
 * AzureLib 3.1.23 (ported Oct 17): the {@link AzBoneSnapshot} overloads now return whether the channel has SETTLED (no
 * snapshot to ease from, or the reset has finished), so {@link com.blib.internal.client.animation.cache.AzBoneCache}
 * can stop visiting bones that are at rest. They return {@code false} when the bone was changed this frame or is still
 * easing back. The {@code Map} overloads look the snapshot up by name and are kept for compatibility.
 * </p>
 * <p>
 * BLib addition: a bone reset time of 0 or less snaps straight to the bind pose. It used to divide by it, and
 * {@code (animTime - lastReset) / 0} is NaN whenever the two are equal - which is every frame a bone STOPS being
 * animated, since stopping a channel stamps lastReset with this frame's animTime just above - so the bone went NaN.
 * Measured Oct 17: 55,398 NaN bone values over a 600-frame test run before, 0 after. Only reachable with
 * {@code AzAnimatorConfig.withBoneResetTime(0)}; the default is 1.
 * </p>
 */
public class AzCachedBoneUpdateUtil {

    /**
     * Updates the cached position of a given bone by interpolating its offsets towards its initial snapshot. Stops
     * ongoing position animations if necessary and updates the bone's position based on the reset percentage.
     *
     * @param bone            the bone whose position is to be updated
     * @param boneSnapshots   a map containing snapshots of bones by their names
     * @param animTime        the current animation time
     * @param resetTickLength the duration over which the position reset occurs
     */
    public static void updateCachedBonePosition(
        AzBone bone,
        Map<String, AzBoneSnapshot> boneSnapshots,
        double animTime,
        double resetTickLength
    ) {
        if (bone.hasPositionChanged()) {
            return;
        }

        updateCachedBonePosition(bone, boneSnapshots.get(bone.getName()), animTime, resetTickLength);
    }

    /**
     * {@link #updateCachedBonePosition(AzBone, Map, double, double)} with the bone's snapshot already looked up, so
     * per-frame callers can skip the by-name map lookup.
     *
     * @return {@code true} if the channel is at rest (no snapshot, or fully eased back); {@code false} if the bone was
     *         changed this frame or is still easing back
     */
    public static boolean updateCachedBonePosition(
        AzBone bone,
        @Nullable AzBoneSnapshot saveSnapshot,
        double animTime,
        double resetTickLength
    ) {
        if (bone.hasPositionChanged()) {
            return false;
        }

        if (saveSnapshot == null) {
            return true;
        }

        var initialSnapshot = bone.getInitialAzSnapshot();

        if (saveSnapshot.isPosAnimInProgress()) {
            saveSnapshot.stopPosAnim(animTime);
        }

        var percentageReset = resetTickLength > 0
            ? Math.min((animTime - saveSnapshot.getLastResetPositionTick()) / resetTickLength, 1)
            : 1;

        bone.setPosX(
            (float) Interpolations.lerp(
                saveSnapshot.getOffsetX(),
                initialSnapshot.getOffsetX(),
                percentageReset
            )
        );
        bone.setPosY(
            (float) Interpolations.lerp(
                saveSnapshot.getOffsetY(),
                initialSnapshot.getOffsetY(),
                percentageReset
            )
        );
        bone.setPosZ(
            (float) Interpolations.lerp(
                saveSnapshot.getOffsetZ(),
                initialSnapshot.getOffsetZ(),
                percentageReset
            )
        );

        if (percentageReset >= 1) {
            saveSnapshot.updateOffset(bone.getPosX(), bone.getPosY(), bone.getPosZ());
            return true;
        }

        return false;
    }

    /**
     * Updates the cached rotation of a given bone by interpolating its rotation values towards its initial snapshot.
     * Stops any ongoing rotation animations if necessary and updates the bone's rotation based on the reset percentage.
     *
     * @param bone            the bone whose rotation is to be updated
     * @param boneSnapshots   a map containing snapshots of bones by their names
     * @param animTime        the current animation time
     * @param resetTickLength the duration over which the rotation reset occurs
     */
    public static void updateCachedBoneRotation(
        AzBone bone,
        Map<String, AzBoneSnapshot> boneSnapshots,
        double animTime,
        double resetTickLength
    ) {
        if (bone.hasRotationChanged()) {
            return;
        }

        updateCachedBoneRotation(bone, boneSnapshots.get(bone.getName()), animTime, resetTickLength);
    }

    /**
     * {@link #updateCachedBoneRotation(AzBone, Map, double, double)} with the bone's snapshot already looked up, so
     * per-frame callers can skip the by-name map lookup.
     *
     * @return {@code true} if the channel is at rest (no snapshot, or fully eased back); {@code false} if the bone was
     *         changed this frame or is still easing back
     */
    public static boolean updateCachedBoneRotation(
        AzBone bone,
        @Nullable AzBoneSnapshot saveSnapshot,
        double animTime,
        double resetTickLength
    ) {
        if (bone.hasRotationChanged()) {
            return false;
        }

        if (saveSnapshot == null) {
            return true;
        }

        var initialSnapshot = bone.getInitialAzSnapshot();

        if (saveSnapshot.isRotAnimInProgress()) {
            saveSnapshot.stopRotAnim(animTime);
        }

        var percentageReset = resetTickLength > 0
            ? Math.min((animTime - saveSnapshot.getLastResetRotationTick()) / resetTickLength, 1)
            : 1;

        bone.setRotX(
            (float) Interpolations.lerp(saveSnapshot.getRotX(), initialSnapshot.getRotX(), percentageReset)
        );
        bone.setRotY(
            (float) Interpolations.lerp(saveSnapshot.getRotY(), initialSnapshot.getRotY(), percentageReset)
        );
        bone.setRotZ(
            (float) Interpolations.lerp(saveSnapshot.getRotZ(), initialSnapshot.getRotZ(), percentageReset)
        );

        if (percentageReset >= 1) {
            saveSnapshot.updateRotation(bone.getRotX(), bone.getRotY(), bone.getRotZ());
            return true;
        }

        return false;
    }

    /**
     * Updates the cached scale of a given bone by interpolating its scale values towards its initial snapshot. Stops
     * any ongoing scale animations if necessary and updates the bone's scale based on the reset percentage.
     *
     * @param bone            the bone whose scale is to be updated
     * @param boneSnapshots   a map containing snapshots of bones by their names
     * @param animTime        the current animation time
     * @param resetTickLength the duration over which the scale reset occurs
     */
    public static void updateCachedBoneScale(
        AzBone bone,
        Map<String, AzBoneSnapshot> boneSnapshots,
        double animTime,
        double resetTickLength
    ) {
        if (bone.hasScaleChanged()) {
            return;
        }

        updateCachedBoneScale(bone, boneSnapshots.get(bone.getName()), animTime, resetTickLength);
    }

    /**
     * {@link #updateCachedBoneScale(AzBone, Map, double, double)} with the bone's snapshot already looked up, so
     * per-frame callers can skip the by-name map lookup.
     *
     * @return {@code true} if the channel is at rest (no snapshot, or fully eased back); {@code false} if the bone was
     *         changed this frame or is still easing back
     */
    public static boolean updateCachedBoneScale(
        AzBone bone,
        @Nullable AzBoneSnapshot saveSnapshot,
        double animTime,
        double resetTickLength
    ) {
        if (bone.hasScaleChanged()) {
            return false;
        }

        if (saveSnapshot == null) {
            return true;
        }

        var initialSnapshot = bone.getInitialAzSnapshot();

        if (saveSnapshot.isScaleAnimInProgress()) {
            saveSnapshot.stopScaleAnim(animTime);
        }

        var percentageReset = resetTickLength > 0
            ? Math.min((animTime - saveSnapshot.getLastResetScaleTick()) / resetTickLength, 1)
            : 1;

        bone.setScaleX(
            (float) Interpolations.lerp(saveSnapshot.getScaleX(), initialSnapshot.getScaleX(), percentageReset)
        );
        bone.setScaleY(
            (float) Interpolations.lerp(saveSnapshot.getScaleY(), initialSnapshot.getScaleY(), percentageReset)
        );
        bone.setScaleZ(
            (float) Interpolations.lerp(saveSnapshot.getScaleZ(), initialSnapshot.getScaleZ(), percentageReset)
        );

        if (percentageReset >= 1) {
            saveSnapshot.updateScale(bone.getScaleX(), bone.getScaleY(), bone.getScaleZ());
            return true;
        }

        return false;
    }
}
