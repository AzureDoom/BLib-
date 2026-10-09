package com.blib.internal.client.animation.cache;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

import com.blib.api.client.animation.v1.animator.AzAnimationContext;
import com.blib.api.client.model.v1.AzBakedModel;
import com.blib.api.client.model.v1.AzBone;
import com.blib.internal.client.animation.AzCachedBoneUpdateUtil;
import com.blib.internal.client.model.AzBoneSnapshot;

public class AzBoneCache {

    private AzBakedModel templateModel;

    private AzBakedModel bakedModel;

    private final Map<String, AzBoneSnapshot> boneSnapshotsByName;

    /**
     * The snapshot for each bone of {@link #bakedModel}, by the bone's index in {@link AzBakedModel#getAllBones()}, so
     * {@link #update} doesn't hash every bone name every frame. Rebuilt lazily when the model or the snapshot map
     * changes.
     */
    private AzBoneSnapshot[] indexedSnapshots = new AzBoneSnapshot[0];

    @Nullable
    private AzBakedModel indexedFor;

    private int snapshotsVersion;

    private int indexedVersion = -1;

    public AzBoneCache() {
        this.boneSnapshotsByName = new Object2ObjectOpenHashMap<>();
        setBakedModel(AzBakedModel.getDefault());
    }

    public boolean setActiveModel(AzBakedModel model) {
        if (model == null) {
            this.templateModel = null;
            this.bakedModel = AzBakedModel.getDefault();
            boneSnapshotsByName.clear();
            snapshotsVersion++;
            return true;
        }

        if (this.templateModel == model) {
            return false;
        }

        this.templateModel = model;
        this.bakedModel = model.deepCopy();
        boneSnapshotsByName.clear();
        snapshot();
        snapshotsVersion++;

        return true;
    }

    /** Counts bone-cache updates (frames); layered tracks compare against it. */
    private long currentFrame;

    /**
     * The frame tracks wrote most recently, i.e. the frame {@link #update} last finished. {@code -1} until the first
     * update, which never matches a snapshot's write frame.
     */
    private long lastAnimatedFrame = -1;

    /**
     * The frame tracks are currently writing. Used to tell whether an earlier track already wrote a bone channel this
     * frame, so later tracks blend on top of it rather than on top of the bind pose.
     */
    public long currentFrame() {
        return currentFrame;
    }

    /**
     * Whether an animation track moved this bone's rotation in the most recent animation update.
     * <p>
     * Intended for {@code AzAnimator#setCustomAnimations}, which runs right after that update. When this returns
     * {@code true}, the bone's current rotation is this frame's animated value, so it is safe to add to it. When it
     * returns {@code false}, the bone still holds whatever was set last frame (possibly by your own code), so set the
     * rotation from {@link AzBone#getInitialAzSnapshot()} instead.
     * </p>
     *
     * @param bone a bone from this cache's {@link #getBakedModel() baked model}
     */
    public boolean wasRotationAnimatedThisFrame(AzBone bone) {
        var snapshot = boneSnapshotsByName.get(bone.getName());
        return snapshot != null && snapshot.isRotationWrittenInFrame(lastAnimatedFrame);
    }

    /** Position counterpart of {@link #wasRotationAnimatedThisFrame(AzBone)}. */
    public boolean wasPositionAnimatedThisFrame(AzBone bone) {
        var snapshot = boneSnapshotsByName.get(bone.getName());
        return snapshot != null && snapshot.isPositionWrittenInFrame(lastAnimatedFrame);
    }

    /** Scale counterpart of {@link #wasRotationAnimatedThisFrame(AzBone)}. */
    public boolean wasScaleAnimatedThisFrame(AzBone bone) {
        var snapshot = boneSnapshotsByName.get(bone.getName());
        return snapshot != null && snapshot.isScaleWrittenInFrame(lastAnimatedFrame);
    }

    public void update(AzAnimationContext<?> context) {
        var config = context.config();
        var timer = context.timer();
        var animTime = timer.getAnimTime();
        var resetTickLength = config.boneResetTime();

        // Updates the cached bone snapshots (only if they have changed).
        var bones = bakedModel.getAllBones();
        var snapshots = indexedSnapshots();

        for (int i = 0, size = bones.size(); i < size; i++) {
            var bone = bones.get(i);
            var snapshot = snapshots[i];
            AzCachedBoneUpdateUtil.updateCachedBoneRotation(bone, snapshot, animTime, resetTickLength);
            AzCachedBoneUpdateUtil.updateCachedBonePosition(bone, snapshot, animTime, resetTickLength);
            AzCachedBoneUpdateUtil.updateCachedBoneScale(bone, snapshot, animTime, resetTickLength);
        }

        resetBoneTransformationMarkers();
        // A new frame for the write markers on each bone snapshot.
        lastAnimatedFrame = currentFrame;
        currentFrame++;
    }

    private void resetBoneTransformationMarkers() {
        var bones = bakedModel.getAllBones();

        for (int i = 0, size = bones.size(); i < size; i++) {
            bones.get(i).resetStateChanges();
        }
    }

    private void snapshot() {
        boneSnapshotsByName.clear();

        for (var bone : bakedModel.getBonesByName().values()) {
            boneSnapshotsByName.put(bone.getName(), AzBoneSnapshot.copy(bone.getInitialAzSnapshot()));
        }
    }

    public void setBakedModel(AzBakedModel model) {
        this.bakedModel = (model != null) ? model : AzBakedModel.getDefault();
    }

    private AzBoneSnapshot[] indexedSnapshots() {
        if (indexedFor != bakedModel || indexedVersion != snapshotsVersion) {
            var bones = bakedModel.getAllBones();
            var snapshots = new AzBoneSnapshot[bones.size()];

            for (int i = 0; i < snapshots.length; i++) {
                snapshots[i] = boneSnapshotsByName.get(bones.get(i).getName());
            }

            indexedSnapshots = snapshots;
            indexedFor = bakedModel;
            indexedVersion = snapshotsVersion;
        }

        return indexedSnapshots;
    }

    public AzBakedModel getBakedModel() {
        return bakedModel;
    }

    /** The shared model this cache's per-instance copy was made from, or {@code null} if none is set. */
    public AzBakedModel getTemplateModel() {
        return this.templateModel;
    }

    /**
     * The bone snapshots by bone name. Treat as read-only: {@link #update} works from an index built off this map and
     * won't see entries added or replaced from outside.
     */
    public Map<String, AzBoneSnapshot> getBoneSnapshotsByName() {
        return boneSnapshotsByName;
    }

    /**
     * Captures the active model, its per-instance copy and the bone snapshots, so a caller can temporarily
     * {@link #setActiveModel switch models} and put everything back afterwards with {@link #restoreState} - without the
     * fresh deep copy (and reset snapshots) that switching back with {@code setActiveModel} would cause.
     */
    public State saveState() {
        return new State(templateModel, bakedModel, new Object2ObjectOpenHashMap<>(boneSnapshotsByName));
    }

    /** Restores a state captured by {@link #saveState()}. */
    public void restoreState(State state) {
        this.templateModel = state.templateModel();
        this.bakedModel = state.bakedModel() != null ? state.bakedModel() : AzBakedModel.getDefault();
        this.boneSnapshotsByName.clear();
        this.boneSnapshotsByName.putAll(state.boneSnapshots());
        this.snapshotsVersion++;
    }

    /** A snapshot of an {@link AzBoneCache}'s active model state; see {@link #saveState()}. */
    public record State(
        AzBakedModel templateModel,
        AzBakedModel bakedModel,
        Map<String, AzBoneSnapshot> boneSnapshots
    ) {}

    public boolean isEmpty() {
        return bakedModel.getBonesByName().isEmpty();
    }
}
