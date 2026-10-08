package com.blib.internal.client.posteffect;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.ApiStatus;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import com.blib.mod.BLib;

/**
 * State + helpers for the MainTarget MRT extension. Owns six auxiliary color attachment texture IDs (entity-mask RG8 at
 * attachment 1 — R: category byte, G: background-entity flag — entity-lightmap RGBA8 at attachment 2, entity-normal
 * RGBA8 at attachment 3, entity-draw-data RGBA8 at attachment 4, entity-specular RGBA8 at attachment 5,
 * entity-material-id R8 at attachment 6) and the {@code glDrawBuffers} state needed to keep them attached.
 * <p>
 * The mixin on {@code MainTarget.createFrameBuffer} (and the resize path on {@code RenderTarget.createBuffers})
 * delegates to {@link #attach(int, int, int)} after vanilla finishes its own attachment, and to {@link #destroy()} from
 * the mixin on {@code RenderTarget.destroyBuffers}. The clear path mixin calls {@link #clearAuxiliaryAttachments()} so
 * non-entity fragments read 0 from the auxiliaries.
 */
@ApiStatus.Internal
public final class BLibMainTargetMRT {

    private static final int GL_COLOR_ATTACHMENT0 = 36064;

    private static final int GL_COLOR_ATTACHMENT1 = 36065;

    private static final int GL_COLOR_ATTACHMENT2 = 36066;

    private static final int GL_COLOR_ATTACHMENT3 = 36067;

    private static final int GL_COLOR_ATTACHMENT4 = 36068;

    private static final int GL_COLOR_ATTACHMENT5 = 36069;

    private static final int GL_COLOR_ATTACHMENT6 = 36070;

    private static final int GL_R8 = 33321;

    private static final int GL_RED = 6403;

    private static final int GL_RG8 = 33323;

    private static final int GL_RG = 33319;

    private static final int GL_RGBA8 = 32856;

    private static final int GL_RGBA = 6408;

    private static final int GL_UNSIGNED_BYTE = 5121;

    private static final int GL_TEXTURE_2D = 3553;

    private static int entityMaskTextureId = -1;

    private static int entityLightmapTextureId = -1;

    private static int entityNormalTextureId = -1;

    private static int entityDrawDataTextureId = -1;

    private static int entitySpecularTextureId = -1;

    private static int entityMaterialIdTextureId = -1;

    private static int width;

    private static int height;

    private static boolean attached;

    /**
     * WHICH FRAMEBUFFER the six attachments are currently on.
     * <p>
     * <b>This exists because one boolean was doing the work of two, and it corrupted the screen.</b> {@code attached}
     * has always been read as "the attachments are on the MAIN TARGET", and three call sites still depend on that
     * reading. Once the Iris path started attaching them to a PRIVATE framebuffer instead, {@code attached} stayed true
     * while its implied meaning had silently changed — and {@link #restoreDrawBuffers()}, which acts on whatever
     * framebuffer is bound, began mapping seven draw buffers onto a main target that has exactly one attachment. That
     * is the undefined-write state responsible for the grossly overexposed view, in EVERY vision mode including the one
     * that does nothing.
     */
    private static boolean attachedToMainTarget;

    /**
     * Null until the first reconcile, so the very first call always establishes a baseline rather than assuming one.
     */
    private static Boolean lastKnownShaderPackActive;

    /**
     * The MainTarget FBO the auxiliary attachments belong to, so the clear can bind it rather than trust the caller.
     */
    private static int attachedFrameBufferId = -1;

    private BLibMainTargetMRT() {
        throw new UnsupportedOperationException();
    }

    public static boolean isAttached() {
        return attached;
    }

    /**
     * TRUE ONLY WHEN THE ATTACHMENTS ARE ON THE MAIN RENDER TARGET, as opposed to the private Iris framebuffer.
     * <p>
     * Anything that touches GLOBAL GL state on behalf of the attachments must consult this, not {@link #isAttached()}.
     * Colour masks and blend enables are per-draw-buffer but NOT per-framebuffer: setting them for our attachments sets
     * them for whatever else is using those same draw-buffer indices, which under a shader pack is the pack's own
     * gbuffer and composite passes.
     */
    public static boolean isAttachedToMainTarget() {
        return attached && attachedToMainTarget;
    }

    public static int entityMaskTextureId() {
        return entityMaskTextureId;
    }

    public static int entityLightmapTextureId() {
        return entityLightmapTextureId;
    }

    public static int entityNormalTextureId() {
        return entityNormalTextureId;
    }

    public static int entityDrawDataTextureId() {
        return entityDrawDataTextureId;
    }

    public static int entitySpecularTextureId() {
        return entitySpecularTextureId;
    }

    public static int entityMaterialIdTextureId() {
        return entityMaterialIdTextureId;
    }

    public static int width() {
        return width;
    }

    public static int height() {
        return height;
    }

    /**
     * Allocate the six auxiliary attachments and bind them to the currently-bound framebuffer. Caller is responsible
     * for binding the MainTarget's FBO before calling.
     */
    public static void attach(int frameBufferId, int viewWidth, int viewHeight) {
        if (BLibIrisCompat.isShaderPackActive() || BLibPostEffectRegistry.ALL.isEmpty()) {
            return;
        }

        attachInternal(frameBufferId, viewWidth, viewHeight);

        attachedToMainTarget = true;
    }

    /**
     * The body of {@link #attach}, WITHOUT the shader-pack guard.
     * <p>
     * <b>Split out so the Iris path can reuse every line of it.</b> Under a shader pack the six auxiliary attachments
     * must not go on the main render target — that is the bug fixed by {@link #reconcileForShaderPackState()} — but
     * they are still exactly the textures the post-effect pipeline samples, and the formats, filtering and clear
     * semantics all have to match precisely. Re-deriving that for the Iris framebuffer would be a second copy of the
     * fiddliest code in this class, free to drift from this one. {@link BLibIrisAuxTarget} calls this instead.
     */
    static void attachInternal(int frameBufferId, int viewWidth, int viewHeight) {
        RenderSystem.assertOnRenderThreadOrInit();

        // Assume the private framebuffer; attach() flips this back on for the main-target path.
        attachedToMainTarget = false;

        destroy();

        attachedFrameBufferId = frameBufferId;
        width = viewWidth;
        height = viewHeight;

        // entityMask uses RG8: R holds the category byte (entity / terrain / particle / sky / celestial / held-item),
        // G holds an auxiliary background-entity flag (0 normal, 1 when the patcher's BlibBackgroundEntity uniform
        // is set during the draw). Two-byte attachment costs negligible memory and keeps the category byte's
        // semantics intact for existing consumers — they continue to read .r exactly as before.
        entityMaskTextureId = TextureUtil.generateTextureId();
        GlStateManager._bindTexture(entityMaskTextureId);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10241, 9728);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10240, 9728);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10242, 33071);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10243, 33071);
        GlStateManager._texImage2D(GL_TEXTURE_2D, 0, GL_RG8, viewWidth, viewHeight, 0, GL_RG, GL_UNSIGNED_BYTE, null);

        entityLightmapTextureId = TextureUtil.generateTextureId();
        GlStateManager._bindTexture(entityLightmapTextureId);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10241, 9728);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10240, 9728);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10242, 33071);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10243, 33071);
        GlStateManager._texImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, viewWidth, viewHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, null);

        entityNormalTextureId = TextureUtil.generateTextureId();
        GlStateManager._bindTexture(entityNormalTextureId);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10241, 9728);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10240, 9728);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10242, 33071);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10243, 33071);
        GlStateManager._texImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, viewWidth, viewHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, null);

        entityDrawDataTextureId = TextureUtil.generateTextureId();
        GlStateManager._bindTexture(entityDrawDataTextureId);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10241, 9728);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10240, 9728);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10242, 33071);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10243, 33071);
        GlStateManager._texImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, viewWidth, viewHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, null);

        entitySpecularTextureId = TextureUtil.generateTextureId();
        GlStateManager._bindTexture(entitySpecularTextureId);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10241, 9728);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10240, 9728);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10242, 33071);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10243, 33071);
        GlStateManager._texImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, viewWidth, viewHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, null);

        entityMaterialIdTextureId = TextureUtil.generateTextureId();
        GlStateManager._bindTexture(entityMaterialIdTextureId);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10241, 9728);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10240, 9728);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10242, 33071);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10243, 33071);
        GlStateManager._texImage2D(GL_TEXTURE_2D, 0, GL_R8, viewWidth, viewHeight, 0, GL_RED, GL_UNSIGNED_BYTE, null);

        GlStateManager._bindTexture(0);

        GlStateManager._glBindFramebuffer(36160, frameBufferId);
        GlStateManager._glFramebufferTexture2D(36160, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, entityMaskTextureId, 0);
        GlStateManager._glFramebufferTexture2D(36160, GL_COLOR_ATTACHMENT2, GL_TEXTURE_2D, entityLightmapTextureId, 0);
        GlStateManager._glFramebufferTexture2D(36160, GL_COLOR_ATTACHMENT3, GL_TEXTURE_2D, entityNormalTextureId, 0);
        GlStateManager._glFramebufferTexture2D(36160, GL_COLOR_ATTACHMENT4, GL_TEXTURE_2D, entityDrawDataTextureId, 0);
        GlStateManager._glFramebufferTexture2D(36160, GL_COLOR_ATTACHMENT5, GL_TEXTURE_2D, entitySpecularTextureId, 0);
        GlStateManager._glFramebufferTexture2D(36160, GL_COLOR_ATTACHMENT6, GL_TEXTURE_2D, entityMaterialIdTextureId, 0);

        GL30.glDrawBuffers(
            new int[] {
                GL_COLOR_ATTACHMENT0,
                GL_COLOR_ATTACHMENT1,
                GL_COLOR_ATTACHMENT2,
                GL_COLOR_ATTACHMENT3,
                GL_COLOR_ATTACHMENT4,
                GL_COLOR_ATTACHMENT5,
                GL_COLOR_ATTACHMENT6
            }
        );

        attached = true;

        // Force the auxiliary color masks to "writes enabled" so BLibGbufferUniforms's per-shader cache (which
        // assumes the initial state is enabled) starts from a known state. Without this, a previous run that
        // left a buffer disabled and then went through a re-attach (window resize, Iris toggle) would carry the
        // disabled state into the new framebuffer's draw-buffer indices and silently drop patched-shader writes.
        for (int buf = 1; buf <= 6; buf++) {
            GL30.glColorMaski(buf, true, true, true, true);
        }
        BLibGbufferUniforms.resetColorMaskCache();

        BLib.LOGGER.debug("[BLib] MRT auxiliary attachments allocated: {}x{}", viewWidth, viewHeight);
    }

    /**
     * Restore the {@code glDrawBuffers} state for the currently-bound framebuffer to {@code [0, 1, 2, 3, 4, 5, 6]} so
     * vanilla entity draws populate the auxiliary attachments. Anything that touches MainTarget's FBO state via
     * {@code glDrawBuffers} (e.g., a fullscreen-quad blit that writes only to attachment 0) MUST call this afterwards
     * to put MainTarget back into MRT mode — otherwise subsequent frames silently drop writes to attachments 1-6 and
     * the auxiliary entity data "freezes" at whatever was there last.
     */
    /**
     * ⚠⚠ Aug 28, THE VEIL GUARD — aux draw buffers are LEVEL-PASS-ONLY. The GUI item ghosts under Veil were patched
     * entity shaders writing the classification mask while inventory icons drew: draw buffers 1-6 stayed mapped on the
     * main target all frame, so anything rendered with an entity shader after the vision consumed the mask still wrote
     * it, and Veil's reordered post timing turned those late writes into visible ghosts. The mapping now lives only
     * between the start of LevelRenderer.renderLevel and the moment the vision pipeline finishes consuming; outside
     * that window bindWrite maps nothing, so aux outputs from GUI-era draws are simply discarded by GL. Under vanilla
     * and Iris this changes nothing observable — those writes were wiped unread every frame anyway — it just stops them
     * ever landing.
     */
    private static boolean levelPassActive;

    public static void setLevelPassActive(boolean active) {
        levelPassActive = active;

        // ⚠⚠⚠ Aug 29 — GATED ON VEIL, and forgetting this here was a real regression on his Fabric install: when
        // the window was scoped to Veil the two restore paths were gated but THIS method was not, so every install
        // still unmapped the aux draw buffers at post-processing time and remapped them at level start. The window
        // exists ONLY for Veil's GUI-ghost hole; on every other install the draw-buffer mapping must stay exactly
        // as it was before it existed.
        if (!attached || !attachedToMainTarget || !BLibIrisCompat.isVeilLoaded()) {
            return;
        }

        var previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);

        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, attachedFrameBufferId);

        if (active) {
            mapAuxDrawBuffers();
        } else {
            GL30.glDrawBuffers(new int[] { GL_COLOR_ATTACHMENT0 });
        }

        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
    }

    public static void restoreDrawBuffers() {
        // ⚠⚠ THE SECOND TEST IS THE WHOLE POINT. This maps draw buffers 0-6 onto WHATEVER FRAMEBUFFER IS BOUND, and
        // MainTarget.bindWrite calls it on every bind. When the attachments live on the Iris private framebuffer the
        // main target still has one attachment, so mapping seven onto it is undefined-write territory — which is
        // precisely how this corrupted the view.
        // ⚠⚠ Aug 28, SCOPED TO VEIL after a Fabric regression: with the window enforced everywhere, plain Fabric
        // showed classification TRAILS — the stale-mask smear this class's own history documents — within hours of
        // the change. The claim that the window "changes nothing observable" outside Veil was WRONG. The window's
        // only purpose is the Veil GUI-ghost hole, so its restriction now applies ONLY when Veil is present;
        // every other install keeps the always-mapped behaviour that weeks of play verified.
        if (!attached || !attachedToMainTarget || (!levelPassActive && BLibIrisCompat.isVeilLoaded())) {
            return;
        }

        mapAuxDrawBuffers();
    }

    /** The raw 0-6 mapping — used by the level-pass window AND unconditionally by the clear, which must map to wipe. */
    private static void mapAuxDrawBuffers() {
        GL30.glDrawBuffers(
            new int[] {
                GL_COLOR_ATTACHMENT0,
                GL_COLOR_ATTACHMENT1,
                GL_COLOR_ATTACHMENT2,
                GL_COLOR_ATTACHMENT3,
                GL_COLOR_ATTACHMENT4,
                GL_COLOR_ATTACHMENT5,
                GL_COLOR_ATTACHMENT6
            }
        );
    }

    /**
     * Clears the auxiliary attachments to 0, binding the MainTarget for itself first.
     * <p>
     * ⚠⚠ THIS USED TO DOCUMENT "caller must have the MainTarget FBO bound" AND TRUST IT — and that assumption is how
     * stale classification survived into the next frame. {@code glClearBufferfv} acts on the DRAW framebuffer, so if
     * anything else is bound when this runs it clears SOMEONE ELSE'S buffers and ours keep last frame's contents.
     * Nothing then overwrites the pixels no geometry covers, and the old classification shows through as TRAILS smeared
     * across the view as the camera turns — worst underwater, where the full-screen overlay deliberately suppresses
     * auxiliary writes and so covers a large region that nothing else rewrites.
     * <p>
     * ⭐ Third mod-interaction bug in one day from the same root: trusting a GL binding we did not set. Bind explicitly,
     * restore explicitly.
     */
    public static void clearAuxiliaryAttachments() {
        if (!attached || attachedFrameBufferId == -1) {
            return;
        }

        // ⚠⚠⚠ THIS CLEARS attachedFrameBufferId, WHICH UNDER A SHADER PACK IS THE PRIVATE IRIS FRAMEBUFFER — AND
        // MixinRenderTarget_MRT CALLS IT ON EVERY MainTarget.bindWrite. Iris binds the main target for its own final
        // pass, AFTER the level pass where the classification is drawn, so the freshly written mask was being wiped
        // before anything could read it. Measured: 7 frames carried tens of thousands of classified pixels, 37 frames
        // with the SAME entity counts carried none — per-frame all-or-nothing, which is the signature of a clear
        // landing at an unpredictable point rather than a draw failing.
        //
        // The classification pass clears for itself at the point it actually wants a clean buffer, so outside it there
        // is nothing here to do while the attachments live on someone else's framebuffer.
        if (!attachedToMainTarget && !BLibIrisClassificationPass.isInsidePass()) {
            return;
        }

        var previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);

        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, attachedFrameBufferId);

        // The clears below address DRAW BUFFERS by index, so the aux attachments must be mapped into the draw-buffer
        // list first — folded in here so the two can never be issued against different framebuffers. Mapped RAW,
        // not through the level-pass gate: the clear must always be able to wipe.
        mapAuxDrawBuffers();

        GL30.glClearBufferfv(GL30.GL_COLOR, 1, new float[] { 0.0F, 0.0F, 0.0F, 0.0F });
        GL30.glClearBufferfv(GL30.GL_COLOR, 2, new float[] { 0.0F, 0.0F, 0.0F, 0.0F });
        GL30.glClearBufferfv(GL30.GL_COLOR, 3, new float[] { 0.0F, 0.0F, 0.0F, 0.0F });
        GL30.glClearBufferfv(GL30.GL_COLOR, 4, new float[] { 0.0F, 0.0F, 0.0F, 0.0F });
        GL30.glClearBufferfv(GL30.GL_COLOR, 5, new float[] { 0.0F, 0.0F, 0.0F, 0.0F });
        GL30.glClearBufferfv(GL30.GL_COLOR, 6, new float[] { 0.0F, 0.0F, 0.0F, 0.0F });

        // Outside the level-pass window the mapping drops straight back to colour-0 — the clear may run at frame
        // start, and leaving 1-6 mapped there would re-open the GUI-write hole this class exists to close.
        if (!levelPassActive && BLibIrisCompat.isVeilLoaded()) {
            GL30.glDrawBuffers(new int[] { GL_COLOR_ATTACHMENT0 });
        }

        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
    }

    /**
     * RECONCILES THE AUXILIARY ATTACHMENTS WITH THE CURRENT SHADER-PACK STATE, once per level pass.
     * <p>
     * <b>The bug this fixes, and it is not hypothetical.</b> Whether to attach was decided in ONE place — the
     * MainTarget's {@code createFrameBuffer} — which runs at startup and on window resize. A shader pack switched on
     * MID-SESSION never touches either. So a player who launches without a pack and then enables one is left with six
     * extra colour attachments bolted onto the main render target, seven entries in its draw-buffer list, and a colour
     * mask cache frozen by {@link BLibGbufferUniforms}'s own early return — all of it invisible to Iris, which then
     * renders its final pass into that framebuffer.
     * <p>
     * ⭐⭐ CONFIRMED BY A CLEAN A/B, NOT REASONED: with the pack enabled mid-session the view came out grossly
     * overexposed the moment a post effect was requested; launching with the SAME pack already on reported
     * {@code mrtAttached=false} and rendered correctly. Two runs, one variable.
     * <p>
     * ⚠ Only the TRANSITION does work. Steady state is a single boolean compare, so this is safe to call every frame.
     */
    public static void reconcileForShaderPackState() {
        var packActive = BLibIrisCompat.isShaderPackActive();
        var wanted = !BLibPostEffectRegistry.ALL.isEmpty();

        // ⭐⭐⭐ NOTHING REGISTERED MEANS NOTHING ATTACHED. This is not an optimisation, it is a correctness fix.
        // A user running BLib alongside a mod that registers NO post effect still had all six auxiliary attachments
        // bolted onto the main render target, purely because BLib was present — and `forceAuxWritesOffForFrame` then
        // issued glColorMaski on GLOBAL state, masking colour writes on draw buffers 1-6 for the shader pack's own
        // gbuffer and composite passes. Result: a grossly overexposed view for someone whose installed mods could not
        // have used those attachments for anything.
        //
        // ⚠ THIS CANNOT BE DECIDED IN MainTarget.createFrameBuffer, WHICH IS WHERE ATTACHING USED TO BE DECIDED:
        // that runs at WINDOW CREATION, before mods have registered anything, so the answer there is always "no". It
        // has to be re-asked per frame, which is exactly what this reconcile already does for the shader-pack state.
        if (!wanted) {
            if (attached) {
                detachFromMainTarget();
                BLibIrisAuxTarget.INSTANCE.invalidate();
            }

            lastKnownShaderPackActive = packActive;

            return;
        }

        // ⚠⚠ COMPARE AGAINST THE ACTUAL ATTACHMENT STATE, NOT A REMEMBERED FLAG. The first version tracked only
        // the previous pack state, so on the very first frame of a session with NO pack it still ran a full
        // detach-and-reattach — destroying and recreating all six textures for no reason.
        //
        // ⚠⚠⚠ THAT CHURN GIVES THE TEXTURES NEW GL IDS, AND BLibTerrainMaskFixup BUILDS A SCRATCH FRAMEBUFFER
        // AROUND THE OLD ONES. Recreate them underneath it and it reads and writes deleted textures, so the
        // classification it produces is garbage — which on screen looks like every entity lighting up at once. That
        // path runs ONLY with Sodium and no shader pack, which is exactly where the fault appeared, and it is the sole
        // behavioural difference this work introduced into the no-pack path.
        //
        // Asking "is the state already what it should be" instead of "did the flag change" makes the no-pack startup a
        // no-op again, and still catches every genuine mid-session toggle.
        var alreadyCorrect = packActive ? !attached : (attached && attachedToMainTarget);

        if (alreadyCorrect) {
            lastKnownShaderPackActive = packActive;

            return;
        }

        if (lastKnownShaderPackActive != null && lastKnownShaderPackActive == packActive && attached == packActive) {
            return;
        }

        lastKnownShaderPackActive = packActive;

        if (packActive) {
            detachFromMainTarget();
            BLibIrisAuxTarget.INSTANCE.invalidate();
        } else {
            BLibIrisAuxTarget.INSTANCE.invalidate();
            reattachToMainTarget();
        }
    }

    /**
     * Takes the auxiliary attachments back off the main target and restores a single-draw-buffer configuration.
     * <p>
     * ⚠⚠ {@link #destroy()} ALONE IS NOT ENOUGH AND THAT IS THE WHOLE POINT. It releases the textures, but the
     * framebuffer's draw-buffer list still names attachments 1-6 — and a draw buffer pointing at nothing is exactly the
     * undefined-write situation that has already cost this renderer several days under Sodium. The attachment points
     * are cleared explicitly and the draw-buffer list is put back to attachment 0 alone, before the textures go.
     */
    private static void detachFromMainTarget() {
        if (attachedFrameBufferId != -1) {
            RenderSystem.assertOnRenderThreadOrInit();

            var previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            var previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

            // GL_FRAMEBUFFER, so the attachment edits below apply to the same object the draw-buffer call does.
            GL30.glBindFramebuffer(36160, attachedFrameBufferId);

            for (var attachment = 1; attachment <= 6; attachment++) {
                GlStateManager._glFramebufferTexture2D(36160, GL_COLOR_ATTACHMENT0 + attachment, GL_TEXTURE_2D, 0, 0);
            }

            GL30.glDrawBuffers(new int[] { GL_COLOR_ATTACHMENT0 });

            // Leave the masks open. A future re-attach starts from "writes enabled", which is what
            // BLibGbufferUniforms's cache assumes, and a disabled mask surviving a pack toggle would silently drop
            // every patched-shader write once the pack came off again.
            for (var buffer = 1; buffer <= 6; buffer++) {
                GL30.glColorMaski(buffer, true, true, true, true);
            }

            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);

            BLibGbufferUniforms.resetColorMaskCache();
        }

        destroy();

        BLib.LOGGER.info("[BLib] Shader pack enabled; auxiliary attachments detached from the main render target.");
    }

    /** The mirror of {@link #detachFromMainTarget()}, for a pack being switched back OFF mid-session. */
    private static void reattachToMainTarget() {
        var mainTarget = Minecraft.getInstance().getMainRenderTarget();

        if (mainTarget == null || mainTarget.viewWidth <= 0 || mainTarget.viewHeight <= 0) {
            return;
        }

        RenderSystem.assertOnRenderThreadOrInit();

        var previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        var previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        GL30.glBindFramebuffer(36160, mainTarget.frameBufferId);

        attach(mainTarget.frameBufferId, mainTarget.viewWidth, mainTarget.viewHeight);

        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);

        BLib.LOGGER.info("[BLib] Shader pack disabled; auxiliary attachments restored to the main render target.");
    }

    public static void destroy() {
        attachedFrameBufferId = -1;

        if (entityMaskTextureId != -1) {
            TextureUtil.releaseTextureId(entityMaskTextureId);
            entityMaskTextureId = -1;
        }

        if (entityLightmapTextureId != -1) {
            TextureUtil.releaseTextureId(entityLightmapTextureId);
            entityLightmapTextureId = -1;
        }

        if (entityNormalTextureId != -1) {
            TextureUtil.releaseTextureId(entityNormalTextureId);
            entityNormalTextureId = -1;
        }

        if (entityDrawDataTextureId != -1) {
            TextureUtil.releaseTextureId(entityDrawDataTextureId);
            entityDrawDataTextureId = -1;
        }

        if (entitySpecularTextureId != -1) {
            TextureUtil.releaseTextureId(entitySpecularTextureId);
            entitySpecularTextureId = -1;
        }

        if (entityMaterialIdTextureId != -1) {
            TextureUtil.releaseTextureId(entityMaterialIdTextureId);
            entityMaterialIdTextureId = -1;
        }

        attached = false;
        attachedToMainTarget = false;
        width = 0;
        height = 0;
    }
}
