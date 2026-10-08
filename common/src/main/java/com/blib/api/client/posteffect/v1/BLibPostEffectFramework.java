package com.blib.api.client.posteffect.v1;

import com.blib.internal.client.posteffect.BLibBackgroundEntityRenderState;
import com.blib.internal.client.posteffect.BLibIrisClassificationPass;
import com.blib.internal.client.posteffect.BLibIrisCompat;
import com.blib.internal.client.posteffect.BLibMaterialIdRenderState;

/**
 * Public utility entry-point for code that interacts with BLib's post-effect framework from outside BLib (e.g. a
 * downstream mod's mixins that need to no-op when an external shader-pack mod owns rendering, or that want to flag
 * entity draws as "render this as part of the world").
 * <p>
 * BLib itself disables its post-effect pipeline, MRT auxiliaries, and entity-shader patching whenever Iris/Oculus is
 * loaded — external mixins that participate in the post-effect data path (per-bone lighting pushes, render-type
 * substitutions, etc.) should follow the same gate so their writes don't fight the shader-pack pipeline.
 */
public final class BLibPostEffectFramework {

    private BLibPostEffectFramework() {
        throw new UnsupportedOperationException();
    }

    /**
     * @return {@code true} if a third-party shader-pack mod (Iris on Fabric, Oculus on NeoForge) is loaded. The result
     *         is cached after the first call.
     */
    public static boolean isShaderModActive() {
        return BLibIrisCompat.isShaderPackActive();
    }

    /**
     * TRUE ONLY WHILE BLIB'S OWN CLASSIFICATION PASS IS DRAWING, under a shader pack.
     * <p>
     * Consumers gate their per-entity classification on {@link #isShaderModActive()}, which is correct for the pack's
     * own passes and WRONG for this one: the classification pass is the single point under a pack where that work IS
     * wanted. Pair the two — bail when a shader mod is active AND this is false.
     */
    public static boolean isClassificationPassActive() {
        return BLibIrisClassificationPass.isInsidePass();
    }

    /**
     * Marks the start of a "background entity" rendering scope on lane A. Patched entity fragment shaders pack the
     * lane-A and lane-B states into {@code entityMask.g} (see {@link BLibBackgroundEntityRenderState} for the
     * encoding); consumer post-effect shaders sample that channel to render those pixels with the world (terrain)
     * coloring formula instead of the foreground-entity formula.
     * <p>
     * Re-entrant via depth count. Pair every {@link #pushBackgroundEntity()} with exactly one
     * {@link #popBackgroundEntity()} on the render thread. Unrelated to {@code MobEffects.INVISIBILITY}.
     * <p>
     * Most consumers only need lane A; lane B exists for cases where two independent classifications need to coexist
     * per-pixel (e.g. a vision wipe whose two halves of the screen apply different visibility rules).
     */
    public static void pushBackgroundEntity() {
        BLibBackgroundEntityRenderState.pushA();
    }

    /** Marks the end of a {@link #pushBackgroundEntity()} scope. Safe to call without a matching push (no-ops). */
    public static void popBackgroundEntity() {
        BLibBackgroundEntityRenderState.popA();
    }

    /**
     * Marks the start of a "background entity" rendering scope on lane B. The second independent lane lets a consumer
     * encode two per-pixel classifications simultaneously — e.g. "background under oldMode" on lane A and "background
     * under newMode" on lane B during a vision transition wipe. The shader can then pick the right flag based on which
     * side of the wipe a given pixel is on.
     * <p>
     * Re-entrant via depth count. Pair every {@link #pushBackgroundEntityB()} with exactly one
     * {@link #popBackgroundEntityB()}.
     */
    public static void pushBackgroundEntityB() {
        BLibBackgroundEntityRenderState.pushB();
    }

    /** Marks the end of a {@link #pushBackgroundEntityB()} scope. Safe to call without a matching push (no-ops). */
    public static void popBackgroundEntityB() {
        BLibBackgroundEntityRenderState.popB();
    }

    /**
     * Tags the draws inside this scope with an opaque material ID (0-255), delivered to consumer post-effect shaders
     * through the {@code entityMaterialId} attachment. BLib assigns no meaning to the value — a thermal effect might
     * read it as how warm a creature runs, something else as a surface response.
     * <p>
     * Nested via a stack, so a passenger drawn inside its mount's render restores the mount's ID rather than clearing
     * to zero. Pair every call with exactly one {@link #popMaterialId()} on the render thread.
     * <p>
     * ⚠ Entity vertices are queued into a {@code MultiBufferSource} and the uniform is only pushed when that batch is
     * flushed — so without ending the batch at the boundary, every entity in a batch samples whichever ID was current
     * when the flush happened, usually the last one rendered. Call {@code BufferSource.endBatch()} around the scope if
     * per-entity accuracy matters, exactly as the background-entity lanes require.
     */
    public static void pushMaterialId(int materialId) {
        BLibMaterialIdRenderState.push(materialId);
    }

    /** Marks the end of a {@link #pushMaterialId(int)} scope. Safe to call without a matching push (no-ops). */
    public static void popMaterialId() {
        BLibMaterialIdRenderState.pop();
    }
}
