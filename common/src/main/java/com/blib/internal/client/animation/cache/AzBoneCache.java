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

    /**
     * AzureLib 3.1.23 (ported Oct 17) - {@code atRest[i]}: bone {@code i} has finished easing back to its bind pose on
     * every channel and nothing has changed it since, so {@link #update} skips it until something marks it changed
     * again. The cost of a frame then scales with the bones that are actually animated, not the whole model. Rebuilt
     * (all false) with {@link #indexedSnapshots}, so a model switch or {@link #restoreState} re-checks every bone once.
     */
    private boolean[] atRest = new boolean[0];

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

        // Ease bones nothing animated this frame back towards their bind pose, then clear their change markers.
        var bones = bakedModel.getAllBones();
        var snapshots = indexedSnapshots();
        var rest = atRest;

        for (int i = 0, size = bones.size(); i < size; i++) {
            var bone = bones.get(i);
            var changed = bone.hasRotationChanged() || bone.hasPositionChanged() || bone.hasScaleChanged();

            // A settled bone that nothing touched since would only be set to the pose it already has. Every AzBone
            // setter marks the bone changed, so anything that moves it (a track, setCustomAnimations, a render-time
            // override) brings it back into this loop.
            if (rest[i] && !changed) {
                continue;
            }

            var snapshot = snapshots[i];
            var rotationSettled = AzCachedBoneUpdateUtil.updateCachedBoneRotation(
                bone,
                snapshot,
                animTime,
                resetTickLength
            );
            var positionSettled = AzCachedBoneUpdateUtil.updateCachedBonePosition(
                bone,
                snapshot,
                animTime,
                resetTickLength
            );
            var scaleSettled = AzCachedBoneUpdateUtil.updateCachedBoneScale(
                bone,
                snapshot,
                animTime,
                resetTickLength
            );

            rest[i] = rotationSettled && positionSettled && scaleSettled;
            bone.resetStateChanges();
        }

        // A new frame for the write markers on each bone snapshot.
        lastAnimatedFrame = currentFrame;
        currentFrame++;
    }

    /**
     * AzureLib 3.1.23 - forces the per-bone arrays to be rebuilt on the next {@link #update}. Call it after replacing
     * or removing entries in {@link #getBoneSnapshotsByName()} directly; changing a snapshot's values does not need it.
     * Every BLib path that swaps the model or the snapshot map already does this itself.
     */
    public void invalidateBoneArrays() {
        snapshotsVersion++;
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

            // ⚠ Unchanged index (same model object, same snapshot objects) - just accept the new version. Models with a
            // re-render layer save/restore their state EVERY frame, which bumps the version every frame; without this
            // they reallocated the index array each frame and reset every bone's rest flag.
            if (indexedFor == bakedModel && sameSnapshots(bones)) {
                indexedVersion = snapshotsVersion;
                return indexedSnapshots;
            }

            var snapshots = new AzBoneSnapshot[bones.size()];

            for (int i = 0; i < snapshots.length; i++) {
                snapshots[i] = boneSnapshotsByName.get(bones.get(i).getName());
            }

            atRest = new boolean[snapshots.length];
            indexedSnapshots = snapshots;
            indexedFor = bakedModel;
            indexedVersion = snapshotsVersion;
        }

        return indexedSnapshots;
    }

    private boolean sameSnapshots(java.util.List<AzBone> bones) {
        var indexed = indexedSnapshots;

        if (indexed.length != bones.size()) {
            return false;
        }

        for (int i = 0; i < indexed.length; i++) {
            if (indexed[i] != boneSnapshotsByName.get(bones.get(i).getName())) {
                return false;
            }
        }

        return true;
    }

    public AzBakedModel getBakedModel() {
        return bakedModel;
    }

    /** The shared model this cache's per-instance copy was made from, or {@code null} if none is set. */
    public AzBakedModel getTemplateModel() {
        return this.templateModel;
    }

    /**
     * The bone snapshots by bone name. {@link #update} works from an index built off this map: after adding, replacing
     * or removing entries from outside, call {@link #invalidateBoneArrays()}.
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
