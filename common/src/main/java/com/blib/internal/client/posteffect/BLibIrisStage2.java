package com.blib.internal.client.posteffect;

import org.jetbrains.annotations.ApiStatus;

/**
 * THE SINGLE SWITCH FOR EVERYTHING THE IRIS SHADER-PACK WORK ADDED. Set {@code -Dblib.iris.stage2=false} and BLib
 * behaves exactly as it did before that work existed.
 * <p>
 * <b>Why one switch instead of the several diagnostic flags it replaces.</b> The shader-pack work changed behaviour at
 * six separate points, and a fault appeared in the NO-pack path that could not be pinned to any of them by reading the
 * code — two separate mechanisms were proposed from inspection and both turned out to be wrong. What was missing was
 * never another probe; it was the ability to run the SAME BUILD with and without the changes and compare. That is all
 * this does.
 * <p>
 * <b>What it disables when false</b>, each restoring the exact prior behaviour:
 * <ol>
 * <li>{@link BLibIrisClassificationPass} does not run at all.</li>
 * <li>{@link BLibMainTargetMRT#reconcileForShaderPackState()} becomes a no-op, so the auxiliary attachments are created
 * once at framebuffer creation and never destroyed or recreated.</li>
 * <li>{@link BLibPostEffectPipeline} stands down whenever a shader pack is active, as it always used to.</li>
 * <li>{@link BLibGbufferUniforms} returns to gating on {@code isAttached()} rather than
 * {@code isAttachedToMainTarget()}, and stops making an exception for the classification pass.</li>
 * <li>{@link BLibDepthSnapshot#capture()} is skipped again while a pack is active.</li>
 * </ol>
 * <b>Default is ON.</b> The switch exists to isolate a fault, not to ship the feature disabled.
 * <p>
 * ⚠ This is a bisect tool with a real cost: every site it guards is a branch that has to stay correct in both states.
 * Once the no-pack fault is understood, delete this class and the branches with it rather than leaving a second
 * configuration nobody tests.
 */
@ApiStatus.Internal
public final class BLibIrisStage2 {

    private static final String PROPERTY = "blib.iris.stage2";

    /**
     * Read once. A switch that could change mid-session would give the two halves of a frame different ideas about
     * whether the attachments live on the main target, which is its own class of bug and exactly what this is meant to
     * help diagnose.
     */
    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty(PROPERTY));

    private BLibIrisStage2() {
        throw new UnsupportedOperationException();
    }

    public static boolean isEnabled() {
        return ENABLED;
    }
}
