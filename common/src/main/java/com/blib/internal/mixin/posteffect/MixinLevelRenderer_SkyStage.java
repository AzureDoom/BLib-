package com.blib.internal.mixin.posteffect;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.blib.internal.client.posteffect.BLibBlockEntityStage;
import com.blib.internal.client.posteffect.BLibGbufferUniforms;
import com.blib.internal.client.posteffect.BLibMainTargetMRT;
import com.blib.internal.client.posteffect.BLibSkyStage;
import com.blib.internal.client.posteffect.BLibWeatherStage;

/**
 * Marks the window in which the sky stage is drawing, so shaders vanilla reuses across stages can be treated
 * differently depending on when they run. See {@link BLibSkyStage}.
 * <p>
 * {@code require = 0} on the sky pair is deliberate: if a future Minecraft renames this, the flag stays false and
 * {@code position_tex_color} is treated as an overlay everywhere. That degrades to a cosmetic sky artifact rather than
 * wiping entity classification, which is the safer of the two failures.
 */
@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer_SkyStage {

    /**
     * FAIL-SAFE FOR BOTH RENDER-STAGE FLAGS, AND IT GUARDS THE WORST FAILURE THIS SYSTEM HAS. They are cleared at the
     * top of every level pass so it can never survive into the rest of the frame.
     * <p>
     * {@code blib$endSkyStage} runs at {@code renderSky}'s RETURN instructions. Any mod that CANCELS {@code renderSky}
     * at HEAD -- sky replacers do exactly this -- returns through a return instruction inserted by its own callback,
     * not through one this injection was applied to, so the end hook never fires and the flag stays true for the rest
     * of the frame. Everything drawn afterwards with {@code position_tex} is then treated as a sky-stage draw,
     * including vanilla's full-screen underwater overlay: it stamps CELESTIAL across the whole view and the entire
     * scene reads as sun. That is the exact shape of the bug the sky-stage split was written to fix, so it must not be
     * reachable by a third party leaving the flag stuck.
     * <p>
     * Clearing at HEAD costs one field write per frame and makes the flag's lifetime strictly bounded: it can only be
     * true between the beginning of {@code renderSky} and the end of the level pass.
     */
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void blib$resetRenderStagesForFrame(
        DeltaTracker deltaTracker,
        boolean renderBlockOutline,
        Camera camera,
        GameRenderer gameRenderer,
        LightTexture lightTexture,
        Matrix4f frustumMatrix,
        Matrix4f projectionMatrix,
        CallbackInfo ci
    ) {
        BLibSkyStage.end();
        BLibWeatherStage.end();
        BLibBlockEntityStage.end();
        BLibGbufferUniforms.forceAuxWritesOffForFrame();

        // ⭐ A SHADER PACK CAN BE SWITCHED ON OR OFF AT ANY MOMENT, AND NOTHING ELSE NOTICES. Whether the auxiliary
        // attachments belong on the main target was decided once, at framebuffer creation; this is the only place that
        // re-asks the question. Steady state is one boolean compare.
        BLibMainTargetMRT.reconcileForShaderPackState();
    }

    /**
     * ⚠⚠ AND CLOSED AGAIN AT THE END OF THE LEVEL PASS. Classification is only ever WRITTEN during the level pass —
     * terrain, entities, particles, sky. Everything after it (overlays, the hand, and any third-party post-processing
     * hooked into {@code GameRenderer.render}) has no business writing to the auxiliary attachments, yet the colour
     * mask would sit wherever the frame's last patched draw left it, which is ENABLED.
     * <p>
     * ⭐⭐ THAT MATTERS BECAUSE {@code toggleAuxColorMask} ONLY RUNS FROM {@code ShaderInstance.apply()}. A mod rendering
     * with its own GL programs — Polytone's post shaders, GPU particles and shadow pass all do — never goes through it,
     * so BLib never gets the chance to close the mask before those draws. Whatever they leave in attachments 1-6 is
     * undefined, and undefined data that survives into the next frame is what smears classification across the view as
     * the camera turns.
     * <p>
     * Closing here bounds the writable window to exactly the pass that has a reason to write. Six GL calls.
     */
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void blib$closeAuxWritesAfterLevelPass(
        DeltaTracker deltaTracker,
        boolean renderBlockOutline,
        Camera camera,
        GameRenderer gameRenderer,
        LightTexture lightTexture,
        Matrix4f frustumMatrix,
        Matrix4f projectionMatrix,
        CallbackInfo ci
    ) {
        BLibGbufferUniforms.forceAuxWritesOffForFrame();
    }

    @Inject(method = "renderSky", at = @At("HEAD"), require = 0)
    private void blib$beginSkyStage(CallbackInfo ci) {
        BLibSkyStage.begin();
    }

    @Inject(method = "renderSky", at = @At("RETURN"), require = 0)
    private void blib$endSkyStage(CallbackInfo ci) {
        BLibSkyStage.end();
    }
}
