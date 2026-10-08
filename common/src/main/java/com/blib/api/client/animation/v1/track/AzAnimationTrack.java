package com.blib.api.client.animation.v1.track;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import com.blib.api.client.animation.v1.animator.AzAnimator;
import com.blib.api.client.animation.v1.command.play_behavior.AzPlayBehaviors;
import com.blib.api.client.animation.v1.command.policy.AzDispatchPolicy;
import com.blib.api.client.animation.v1.command.policy.OnBlockedByEndless;
import com.blib.api.client.animation.v1.command.policy.OnPropertiesChanged;
import com.blib.api.client.animation.v1.command.sequence.AzAnimationSequence;
import com.blib.api.client.animation.v1.keyframe.AzKeyframeCallbacks;
import com.blib.internal.client.animation.primitive.AzQueuedAnimation;
import com.blib.internal.client.animation.property.AzAnimationProperties;
import com.blib.internal.client.animation.track.AzAbstractAnimationTrack;
import com.blib.internal.client.animation.track.AzAnimationQueue;
import com.blib.internal.client.animation.track.AzAnimationTrackTimer;
import com.blib.internal.client.animation.track.AzBoneAnimationQueueCache;
import com.blib.internal.client.animation.track.AzBoneSnapshotCache;
import com.blib.internal.client.animation.track.keyframe.AzKeyframeManager;
import com.blib.internal.client.animation.track.state.impl.AzAnimationPauseState;
import com.blib.internal.client.animation.track.state.impl.AzAnimationPlayState;
import com.blib.internal.client.animation.track.state.impl.AzAnimationStopState;
import com.blib.internal.client.animation.track.state.impl.AzAnimationTransitionState;
import com.blib.internal.client.animation.track.state.machine.AzAnimationTrackStateMachine;

public class AzAnimationTrack<T> extends AzAbstractAnimationTrack {

    protected static final Logger LOGGER = LoggerFactory.getLogger(AzAnimationTrack.class);

    public static <T> AzAnimationTrackBuilder<T> builder(AzAnimator<?, T> animator, String name) {
        return new AzAnimationTrackBuilder<>(animator, name);
    }

    /**
     * Convenience: build a track using a typed {@link AzTrackHandle} as the source of the track's name. The handle's
     * animatable type parameter must match the animator's, enforced by the type system.
     */
    public static <T> AzAnimationTrackBuilder<T> builder(AzAnimator<?, T> animator, AzTrackHandle<? super T> handle) {
        return new AzAnimationTrackBuilder<>(animator, handle.name());
    }

    private final AzAnimationTrackTimer<T> trackTimer;

    private final AzAnimationQueue animationQueue;

    private final AzAnimationTrackStateMachine<T> stateMachine;

    private final AzAnimator<?, T> animator;

    private final AzBoneAnimationQueueCache<T> boneAnimationQueueCache;

    private final AzBoneSnapshotCache boneSnapshotCache;

    private final AzKeyframeManager<T> keyframeManager;

    private final AzAnimationProperties defaultAnimationProperties;

    protected AzQueuedAnimation currentAnimation;

    private AzAnimationProperties animationProperties;

    // AzureLib 3.1.13 layering. Defaults (weight 1, OVERRIDE, every bone) reproduce the pre-layering result exactly.
    private double weight = 1;

    private AzBlendMode blendMode = AzBlendMode.OVERRIDE;

    private AzBoneMask boneMask = AzBoneMask.ALL;

    private final AzWeightFade weightFade = new AzWeightFade();

    AzAnimationTrack(
        String name,
        AzAnimator<?, T> animator,
        AzAnimationProperties animationProperties,
        AzKeyframeCallbacks<T> keyframeCallbacks
    ) {
        super(name);

        this.animator = animator;
        this.trackTimer = new AzAnimationTrackTimer<>(this);
        this.defaultAnimationProperties = animationProperties;
        this.animationProperties = animationProperties;

        this.animationQueue = new AzAnimationQueue();
        this.boneAnimationQueueCache = new AzBoneAnimationQueueCache<>(animator.context().boneCache());
        this.boneSnapshotCache = new AzBoneSnapshotCache();
        this.keyframeManager = new AzKeyframeManager<>(
            this,
            boneAnimationQueueCache,
            boneSnapshotCache,
            keyframeCallbacks
        );

        var stateHolder = new AzAnimationTrackStateMachine.StateHolder<T>(
            new AzAnimationPlayState<>(),
            new AzAnimationPauseState<>(),
            new AzAnimationStopState<>(),
            new AzAnimationTransitionState<>()
        );

        this.stateMachine = new AzAnimationTrackStateMachine<>(stateHolder, this, animator.context());
    }

    @Override
    public boolean hasAnimationFinished() {
        return super.hasAnimationFinished() && stateMachine.isStopped();
    }

    public List<AzQueuedAnimation> tryCreateAnimationQueue(T animatable, AzAnimationSequence sequence) {
        var stages = sequence.stages();
        var animations = new ArrayList<AzQueuedAnimation>();

        for (var stage : stages) {
            var animation = animator.getAnimation(animatable, stage.name());

            if (animation == null) {
                LOGGER.warn(
                    "Unable to find animation: {} for {}",
                    stage.name(),
                    animatable.getClass().getSimpleName()
                );
                return List.of();
            } else {
                animations.add(new AzQueuedAnimation(animation, stage.properties().playBehavior()));
            }
        }

        return animations;
    }

    public void update() {
        // Adjust the tick before making any updates.
        trackTimer.update();
        // Run state machine updates.
        stateMachine.update();
        // Advance any weight fade before applying this frame's values (AzureLib 3.1.13 layering).
        if (weightFade.isActive()) {
            weight = weightFade.update(animator.context().timer().getAnimTime());
        }

        // Update bone animation queue cache.
        boneAnimationQueueCache.update(animationProperties.easingType(), weight, blendMode);

        if (DEBUG_TRACE_FRAMES > 0) {
            DEBUG_TRACE_FRAMES--;
            traceFrame();
        }
    }

    /** {@code -Dblib.animsync.debug=true}: frames left to trace after a play-once handoff (set by AzPlayBehaviors). */
    public static int DEBUG_TRACE_FRAMES;

    private static final org.slf4j.Logger TRACE_LOGGER = org.slf4j.LoggerFactory.getLogger("blib/animsync");

    private void traceFrame() {
        var state = stateMachine.isStopped()
            ? "STOP"
            : stateMachine.isPlaying() ? "PLAY" : stateMachine.isTransitioning() ? "TRANSITION" : "PAUSE";
        var current = currentAnimation == null ? "none" : currentAnimation.animation().name();
        var bones = new StringBuilder();

        for (var entry : animator.context().boneCache().getBakedModel().getBonesByName().entrySet()) {
            var lower = entry.getKey().toLowerCase(java.util.Locale.ROOT);

            if (
                lower.contains("door") || lower.contains("lid") || lower.contains("hatch") || lower.contains("cover") || lower.equals(
                    "root"
                ) || lower.equals("leftarm")
            ) {
                var bone = entry.getValue();

                bones.append(' ')
                    .append(entry.getKey())
                    .append("=rot(")
                    .append(String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", bone.getRotX(), bone.getRotY(), bone.getRotZ()))
                    .append(")pos(")
                    .append(String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", bone.getPosX(), bone.getPosY(), bone.getPosZ()))
                    .append(')');
            }
        }

        TRACE_LOGGER.info(
            "[animsync] frame state={} current={} tick={} queue={}{}",
            state,
            current,
            trackTimer.getAdjustedTick(),
            animationQueue.size(),
            bones
        );
    }

    public void run(@NotNull AzAnimationSequence sequence, @NotNull AzDispatchPolicy policy) {
        if (sequence.stages().isEmpty()) {
            stateMachine.stop();
            return;
        }

        switch (policy.mode()) {
            case REPLAY -> runReplay(sequence);
            case PLAY_IF_NOT_PLAYING -> runIdempotent(sequence, policy.onPropertiesChanged());
            case ENQUEUE -> runEnqueue(sequence, policy.onBlockedByEndless());
        }
    }

    private void runReplay(AzAnimationSequence sequence) {
        var animatable = animator.context().animatable();
        var animations = tryCreateAnimationQueue(animatable, sequence);

        this.currentAnimation = null;
        animationQueue.clear();

        if (animations.isEmpty()) {
            this.currentSequence = null;
            stateMachine.transition();
            return;
        }

        resetAnimationSpeed();
        animationQueue.addAll(animations);
        this.currentSequence = sequence;
        stateMachine.transition();
    }

    private void runIdempotent(AzAnimationSequence sequence, OnPropertiesChanged onPropertiesChanged) {
        if (isAlreadyActive(sequence, onPropertiesChanged)) {
            // X is already the active animation; drop any stale follow-ups so the track's intent
            // matches "X is what should be playing."
            animationQueue.clear();
            return;
        }

        runReplay(sequence);
    }

    private void runEnqueue(AzAnimationSequence sequence, OnBlockedByEndless onBlockedByEndless) {
        if (currentAnimation == null && stateMachine.isStopped()) {
            // Nothing to wait for — start now.
            runReplay(sequence);
            return;
        }

        if (currentAnimation != null && currentAnimation.playBehavior() == AzPlayBehaviors.LOOP) {
            switch (onBlockedByEndless) {
                case REJECT -> {
                    LOGGER.warn(
                        "ENQUEUE rejected on track '{}': current animation is LOOP'd and will not finish.",
                        name()
                    );
                    return;
                }
                case PROMOTE_TO_REPLAY -> {
                    runReplay(sequence);
                    return;
                }
                case APPEND_ANYWAY -> {
                    // fall through to enqueue
                }
            }
        }

        var animatable = animator.context().animatable();
        var animations = tryCreateAnimationQueue(animatable, sequence);
        animationQueue.addAll(animations);
    }

    private boolean isAlreadyActive(AzAnimationSequence sequence, OnPropertiesChanged onPropertiesChanged) {
        if (currentSequence == null || stateMachine.isStopped()) {
            return false;
        }

        return switch (onPropertiesChanged) {
            case RESTART -> sequence.equals(currentSequence);
            case UPDATE_IN_PLACE -> sameRetunableSequence(sequence, currentSequence);
        };
    }

    private static boolean sameRetunableSequence(AzAnimationSequence a, AzAnimationSequence b) {
        var aStages = a.stages();
        var bStages = b.stages();

        if (aStages.size() != bStages.size()) {
            return false;
        }

        for (int i = 0; i < aStages.size(); i++) {
            var aStage = aStages.get(i);
            var bStage = bStages.get(i);

            if (!aStage.name().equals(bStage.name())) {
                return false;
            }

            if (aStage.properties().playBehavior() != bStage.properties().playBehavior()) {
                return false;
            }
        }

        return true;
    }

    private void resetAnimationSpeed() {
        animationProperties = animationProperties.withAnimationSpeed(defaultAnimationProperties.animationSpeed());
    }

    public AzAnimationProperties animationProperties() {
        return animationProperties;
    }

    public void setAnimationProperties(AzAnimationProperties animationProperties) {
        this.animationProperties = animationProperties;
    }

    public AzAnimationQueue animationQueue() {
        return animationQueue;
    }

    public AzBoneAnimationQueueCache<T> boneAnimationQueueCache() {
        return boneAnimationQueueCache;
    }

    public AzBoneSnapshotCache boneSnapshotCache() {
        return boneSnapshotCache;
    }

    public AzAnimationTrackTimer<T> trackTimer() {
        return trackTimer;
    }

    public @Nullable AzQueuedAnimation currentAnimation() {
        return currentAnimation;
    }

    public AzKeyframeManager<T> keyframeManager() {
        return keyframeManager;
    }

    public AzAnimationTrackStateMachine<T> stateMachine() {
        return stateMachine;
    }

    /** @return this track's layer weight, 0 to 1 (AzureLib 3.1.13 layering) */
    public double weight() {
        return weight;
    }

    /**
     * Sets the layer weight immediately, cancelling any fade.
     *
     * @param weight 0 (no effect) to 1 (full effect); clamped
     */
    public void setWeight(double weight) {
        this.weightFade.cancel();
        this.weight = clampWeight(weight);
    }

    /**
     * Fades the layer weight to a target over time; a non-positive length sets it at once.
     *
     * @param targetWeight the weight to reach; clamped to 0..1
     * @param lengthTicks  how long to take, in animation ticks
     */
    public void fadeWeight(double targetWeight, double lengthTicks) {
        if (!(lengthTicks > 0)) {
            setWeight(targetWeight);
            return;
        }

        this.weightFade.start(this.weight, clampWeight(targetWeight), lengthTicks);
    }

    /** @return whether a weight fade is running */
    public boolean isFadingWeight() {
        return weightFade.isActive();
    }

    private static double clampWeight(double weight) {
        return Double.isNaN(weight) ? 0 : Math.max(0, Math.min(1, weight));
    }

    /** @return how this track combines with the layers below it */
    public AzBlendMode blendMode() {
        return blendMode;
    }

    /** @param blendMode how this track combines with the layers below it; null means OVERRIDE */
    public void setBlendMode(AzBlendMode blendMode) {
        this.blendMode = blendMode == null ? AzBlendMode.OVERRIDE : blendMode;
    }

    /** @return the bones this track may animate */
    public AzBoneMask boneMask() {
        return boneMask;
    }

    /** @param boneMask the bones this track may animate; null means every bone */
    public void setBoneMask(AzBoneMask boneMask) {
        this.boneMask = boneMask == null ? AzBoneMask.ALL : boneMask;
    }

    public void setCurrentAnimation(AzQueuedAnimation currentAnimation) {
        this.currentAnimation = currentAnimation;

        if (currentAnimation == null) {
            this.currentSequence = null;
        }
    }
}
