package com.blib.internal.client.posteffect;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.ApiStatus;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import com.blib.mod.BLib;

/**
 * RE-RENDERS VISIBLE ENTITIES INTO {@link BLibIrisAuxTarget} SO THE VISION HAS A CLASSIFICATION TO READ WHILE A SHADER
 * PACK OWNS THE MAIN RENDER TARGET.
 * <p>
 * <b>The precedent.</b> This is the shape vanilla's own glowing-entity outline uses \u2014 a separate render target,
 * entities drawn into it a second time, a post pass that reads it \u2014 and that keeps working with shader packs on.
 * The pattern is proven; this is not a new idea, only a new consumer.
 * <p>
 * <b>Why the cost is acceptable.</b> Measured on his own world across 112 samples: a median of 34 entities within 64
 * blocks and a maximum of 38. Drawing those a second time, only while the mask is down, is not a meaningful expense
 * \u2014 and it was worth measuring rather than guessing, because a hive in view is a very different number from a few
 * cows.
 * <p>
 * \u26a0\u26a0 THIS PASS DELIBERATELY DOES NOT WRITE DEPTH. It borrows the main target's depth texture so entities
 * occlude against terrain correctly, but writing into it would corrupt the depth buffer Iris is still using for its own
 * composite passes. Depth TEST on, depth WRITE off, always.
 * <p>
 * <b>Stage 1 scope.</b> Per-entity classification uniforms are still pushed by consumer mixins that currently stand
 * themselves down under a pack, so at this stage entities land in the mask with default classification rather than
 * their thermal categories. That is expected: the question this stage answers is whether the pixels arrive at all.
 * {@code -Dblib.iris.diag=true} logs what actually landed.
 */
@ApiStatus.Internal
public final class BLibIrisClassificationPass {

    private static final int GL_FRAMEBUFFER = 36160;

    private static boolean insidePass;

    private static MultiBufferSource.BufferSource bufferSource;

    private BLibIrisClassificationPass() {
        throw new UnsupportedOperationException();
    }

    /**
     * TRUE ONLY WHILE THE CLASSIFICATION PASS IS DRAWING.
     * <p>
     * Consumer mixins currently bail whenever a shader mod is active, which is right for Iris's own passes and wrong
     * for this one \u2014 this pass is the one place under a pack where their per-entity classification IS wanted. They
     * gate on this in stage 2.
     */
    public static boolean isInsidePass() {
        return insidePass;
    }

    /**
     * Runs the pass, if a shader pack is active and something actually wants a classification.
     * <p>
     * \u26a0 Called at the END of the level pass, where the world's depth is complete but the hand has not yet been
     * drawn \u2014 the same point the no-pack path snapshots depth, and for the same reason.
     */
    public static void run(DeltaTracker deltaTracker, Matrix4f frustumMatrix, Matrix4f projectionMatrix) {
        if (!BLibIrisStage2.isEnabled()) {
            return;
        }

        if (!BLibIrisCompat.isShaderPackActive() || !wantsClassification()) {
            return;
        }

        var mc = Minecraft.getInstance();
        var level = mc.level;
        var camera = mc.gameRenderer.getMainCamera();

        if (level == null || !BLibIrisAuxTarget.INSTANCE.ensure()) {
            return;
        }

        // ⚠⚠ DO NOT COPY DEPTH HERE. The copy is taken EARLIER, before translucent terrain, by
        // MixinLevelRenderer_IrisClassification — see captureSceneDepthBeforeWater(). Copying again at this point
        // would pull in the water surface's depth and re-create the exact bug the early capture exists to avoid:
        // entities below the surface losing the depth test and vanishing when looked at from above.
        //
        // ⭐ If the early capture never happened this frame, the attachment simply holds the previous frame's copy,
        // which is a far better failure mode than testing against depth that includes water.

        RenderSystem.assertOnRenderThreadOrInit();

        var previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        var previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        GL30.glBindFramebuffer(GL_FRAMEBUFFER, BLibIrisAuxTarget.INSTANCE.frameBufferId());

        // Set BEFORE the clear, not after: clearAuxiliaryAttachments now refuses to touch the private framebuffer
        // unless this pass is the one asking, and this is the point at which a clean buffer is genuinely wanted.
        // ⭐⭐⭐ SET THE VIEWPORT EXPLICITLY. THIS IS WHY ENTITIES APPEARED OFF TO ONE SIDE IN A SMALL PATCH.
        // Binding a framebuffer does NOT set a viewport — GL keeps whatever was last set, and under a shader pack that
        // is whatever Iris last used for ITS targets, which are not this size. The classification was correct all
        // along: right silhouette, right category, drawn into the wrong rectangle of our attachment, so the vision
        // sampled it at coordinates the entity was never rendered to. The giveaway was that the shape was perfect and
        // tracked the camera — a scaling and offset problem, never a classification one.
        //
        // ⚠ Restored on the way out, because the frame does not belong to us.
        var previousViewport = new int[4];

        GL11.glGetIntegerv(GL11.GL_VIEWPORT, previousViewport);
        RenderSystem.viewport(0, 0, BLibMainTargetMRT.width(), BLibMainTargetMRT.height());

        insidePass = true;

        // Every pixel must be defined every frame or last frame's classification shows through as trails smeared
        // across the view as the camera turns. That was a real bug on the no-pack path; it is not repeated here.
        BLibMainTargetMRT.clearAuxiliaryAttachments();

        GL30.glBindFramebuffer(GL_FRAMEBUFFER, BLibIrisAuxTarget.INSTANCE.frameBufferId());

        // Depth testing is back ON. It was disabled only to test whether depth was rejecting the entities; with the
        // rotation missing they were being tested at meaningless screen positions, which is why they survived only
        // against the far plane and then vanished once the viewport was corrected. Correctly placed geometry should
        // now occlude against terrain properly.
        // ⭐⭐ DISABLE THE SCISSOR TEST. Scissor is GLOBAL GL state and we never set it, so this pass inherits
        // whatever rectangle Iris or Sodium last used for their own passes \u2014 and every fragment outside it is
        // thrown
        // away. That clips the classification to part of the screen, which is why a single xenomorph came out with its
        // tail classified and its body missing, and why entities appeared and disappeared as the camera moved.
        //
        // ⚠ Same family as the colour-mask bug earlier today: per-draw-buffer or per-context state that is NOT scoped
        // to a framebuffer, inherited from whoever ran last. Bind a framebuffer and you still own none of it.
        // ⚠⚠⚠ CAPTURE EVERYTHING THIS PASS PERTURBS. IT NOW RUNS IN THE MIDDLE OF THE LEVEL PASS, NOT AT THE END.
        // While the hook sat at TAIL, failing to restore state was invisible — rendering was already finished. Moving
        // it ahead of translucent terrain made every unrestored value leak into the REST OF THE FRAME, and the missing
        // one was blending: `_disableBlend()` with no matching restore meant water and every other translucent block
        // drew with blending off, which is why entities in water stopped being visible from either side.
        //
        // 3089 is GL_SCISSOR_TEST, 3042 is GL_BLEND, 2932 is GL_DEPTH_FUNC — spec values, as elsewhere in this file.
        var scissorWasEnabled = GL11.glIsEnabled(3089);
        var blendWasEnabled = GL11.glIsEnabled(3042);
        var previousDepthFunc = GL11.glGetInteger(2932);

        GlStateManager._disableScissorTest();

        // ⭐⭐⭐ PULL THE RE-DRAWN GEOMETRY TOWARDS THE CAMERA. THIS IS THE FLICKER.
        // The pass re-renders entity geometry that is ALREADY in the scene depth buffer at exactly the same depth.
        // Testing coplanar geometry with GL_LEQUAL resolves per fragment on floating-point ties, so faces and edges
        // survive at random and the result changes as the camera moves — classic z-fighting. It produced a cow whose
        // upper surface was classified while its sides were bare outlines.
        //
        // ⭐ HIS OBSERVATION IS THE PROOF: "enviroment like lava or magma doesnt [flicker] its just the entities."
        // Terrain heat is reconstructed from depth inside the post shader and never re-drawn, so it cannot fight with
        // itself. Only entities are drawn a second time, and only entities flicker.
        //
        // A polygon offset is exactly what vanilla uses for decals over coplanar surfaces. Biasing towards the viewer
        // makes the re-draw win its own ties without letting it punch through anything genuinely in front.
        RenderSystem.enablePolygonOffset();
        RenderSystem.polygonOffset(-3.0F, -3.0F);

        GlStateManager._enableDepthTest();
        // 515 is GL_LEQUAL, written as its spec value: nothing in this tree references the LWJGL constant by
        // name, so the number is the verifiable form.
        GlStateManager._depthFunc(515);
        GlStateManager._depthMask(false);
        GlStateManager._disableBlend();

        // ⭐ THE PASS MUST OPEN THE AUXILIARY WRITES ITSELF, because the per-frame default is OFF and the code that
        // normally re-opens them (BLibGbufferUniforms.apply, on the first patched draw) is what the pack check used to
        // skip. Cache reset too, so the next shader bind re-issues the mask rather than trusting a stale belief.
        for (var buffer = 1; buffer <= 6; buffer++) {
            GL30.glColorMaski(buffer, true, true, true, true);
        }

        BLibGbufferUniforms.resetColorMaskCache();

        // Blending is per-draw-buffer but vanilla sets it globally, so an enabled blend would MIX the classification
        // byte with whatever was underneath instead of replacing it. That is the horizon-band bug; not repeating it.
        BLibGbufferUniforms.disableAuxBlending();

        try {
            // ⭐⭐⭐ APPLY THE CAMERA ROTATION. A BARE PoseStack IS THE IDENTITY MATRIX, AND THAT WAS THE WHOLE BUG.
            // LevelRenderer multiplies the frustum matrix into its pose stack before it renders entities — that is
            // what orients the world to where the camera is looking. This pass built a fresh PoseStack, passed entity
            // positions relative to the camera, and never applied the rotation, so every entity was drawn as though
            // the player were facing one fixed direction.
            //
            // ⭐ THE SYMPTOM THAT NAMED IT: the offset moved to the left or the right DEPENDING ON WHICH WAY HE WAS
            // FACING. A viewport error gives a CONSTANT screen offset; only a missing rotation varies with heading.
            // The matrix was already being passed in for frustum culling, two lines below — it just never reached the
            // transform.
            var poseStack = new PoseStack();

            poseStack.mulPose(frustumMatrix);
            // ⭐⭐⭐ OUR OWN BUFFER SOURCE. THIS IS WHY THE PASS DREW NOTHING AT THE EARLY POINT.
            // It used to borrow mc.renderBuffers().bufferSource() — the SHARED one. At the end of the level pass that
            // is harmless: vanilla has flushed everything, the source is idle, and our endBatch() draws exactly our
            // geometry while our framebuffer is bound. Hooked EARLIER, vanilla is mid-sequence with that same source,
            // so our entity geometry was queued into it and flushed LATER BY VANILLA — into the main target, long
            // after we had unbound. Hence a complete, correctly-bound framebuffer with valid depth and ZERO pixels
            // written, and no error anywhere.
            //
            // ⚠ Depth was ruled out twice over (borrowed texture, then a private copy), bindings restore identically,
            // the viewport is set, the framebuffer is complete and nothing throws. The geometry was simply going
            // somewhere else.
            var bufferSource = privateBufferSource();
            var cameraPosition = camera.getPosition();
            var partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
            var dispatcher = mc.getEntityRenderDispatcher();
            // ⚠ NOT LevelRenderer.getFrustum() — that accessor is a NeoForge ADDITION and does not exist in vanilla,
            // so it cannot be used from the common module. The frustum is rebuilt here from the two matrices the
            // level pass already hands us, which is exactly what LevelRenderer does with them internally.
            var frustum = new Frustum(frustumMatrix, projectionMatrix);

            frustum.prepare(cameraPosition.x, cameraPosition.y, cameraPosition.z);

            for (var entity : level.entitiesForRendering()) {
                if (entity == camera.getEntity() && !camera.isDetached()) {
                    // The wearer's own body is never part of what the helmet's optics show.
                    continue;
                }

                if (!dispatcher.shouldRender(entity, frustum, cameraPosition.x, cameraPosition.y, cameraPosition.z)) {
                    continue;
                }

                // ⭐⭐⭐ INTERPOLATED POSITION, NOT THE RAW TICK POSITION. THIS IS THE MOVEMENT FLICKER.
                // LevelRenderer draws every entity at Mth.lerp(partialTick, xOld, getX()). Using getX() directly puts
                // our re-draw at the END of the current tick while the depth buffer holds the INTERPOLATED position
                // from the real pass — up to a full tick of travel apart. The two disagree, our geometry loses the
                // depth test against its own original, and parts of the entity drop out.
                //
                // ⭐ WHICH IS EXACTLY WHY STANDING MOBS WERE PERFECT AND WALKING ONES FLICKERED: for a stationary
                // entity xOld and getX() are identical, so the bug is invisible until something moves.
                dispatcher.render(
                    entity,
                    Mth.lerp(partialTick, entity.xOld, entity.getX()) - cameraPosition.x,
                    Mth.lerp(partialTick, entity.yOld, entity.getY()) - cameraPosition.y,
                    Mth.lerp(partialTick, entity.zOld, entity.getZ()) - cameraPosition.z,
                    entity.getYRot(),
                    partialTick,
                    poseStack,
                    bufferSource,
                    dispatcher.getPackedLightCoords(entity, partialTick)
                );

            }

            bufferSource.endBatch();
        } catch (Throwable throwable) {
            BLib.LOGGER.error("[BLib] Iris classification pass failed: {}", throwable.toString());
        } finally {
            insidePass = false;

            RenderSystem.polygonOffset(0.0F, 0.0F);
            RenderSystem.disablePolygonOffset();

            // 3089 is GL_SCISSOR_TEST, written as its spec value for the same reason as the other constants here.
            if (scissorWasEnabled) {
                GlStateManager._enableScissorTest();
            }

            RenderSystem.viewport(
                previousViewport[0],
                previousViewport[1],
                previousViewport[2],
                previousViewport[3]
            );

            GlStateManager._depthMask(true);
            GlStateManager._depthFunc(previousDepthFunc);

            if (blendWasEnabled) {
                GlStateManager._enableBlend();
            } else {
                GlStateManager._disableBlend();
            }

            // ⚠ HAND THE GLOBAL STATE BACK EXACTLY AS FOUND. Colour masks and blend enables on draw buffers 1-6 are
            // global, and Iris's own passes use those same indices for its colortex targets - leaving them altered is
            // precisely the mistake that blew out the view.
            for (var buffer = 1; buffer <= 6; buffer++) {
                GL30.glColorMaski(buffer, true, true, true, true);
                GL30.glEnablei(GL30.GL_BLEND, buffer);
            }

            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
        }
    }

    /**
     * A buffer source owned entirely by this pass, created once and reused.
     * <p>
     * ⚠ Never share vanilla's. Anything queued into the shared source is at the mercy of whoever flushes it next, and
     * this pass runs at a point where vanilla is part-way through its own batching.
     */
    private static MultiBufferSource.BufferSource privateBufferSource() {
        if (bufferSource == null) {
            // 256 KiB initial capacity; it grows as needed and is reused for the lifetime of the game.
            bufferSource = MultiBufferSource.immediate(new ByteBufferBuilder(256 * 1024));
        }

        return bufferSource;
    }

    /**
     * Only worth drawing if a post effect actually wants it. Checked here rather than left to the caller so the pass
     * costs nothing at all when no vision is up.
     */
    private static boolean wantsClassification() {
        for (var effect : BLibPostEffectRegistry.ALL) {
            if (effect.isActive() && effect.shaderInstance() != null) {
                return true;
            }
        }

        return false;
    }
}
