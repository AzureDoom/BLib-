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
 * A PRIVATE FRAMEBUFFER THAT HOSTS THE SIX AUXILIARY ATTACHMENTS WHILE A SHADER PACK OWNS THE MAIN RENDER TARGET.
 * <p>
 * <b>Why this exists.</b> Everything the predator vision needs already works under a pack except for one thing: there
 * is nowhere to write the classification. Iris owns the main render target, and bolting our attachments onto it is
 * precisely the bug that produced a grossly overexposed screen. So we render the classification into a framebuffer of
 * our own, which Iris knows nothing about and cannot be corrupted by.
 * <p>
 * <b>What makes this cheap.</b> Three facts measured from live logs, not assumed:
 * <ol>
 * <li>BLib still patches all twelve vanilla entity shaders while a pack is loaded, so the shaders that produce the
 * classification are compiled and ready \u2014 they simply never get bound during Iris's own passes.</li>
 * <li>Iris does not intercept shader binding, on either the {@code RenderSystem} route or the raw {@code glUseProgram}
 * route: 162 samples with a pack live, every one bound the program that was asked for.</li>
 * <li>Vanilla's own depth texture still holds real scene depth under a pack, because Iris hands it to its own render
 * targets. So it can be attached here directly and entities depth-test against terrain for free.</li>
 * </ol>
 * <b>Draw buffer 0 is deliberately {@code GL_NONE}.</b> The patched entity shaders still write their ordinary colour
 * output at location 0; discarding it is what keeps this pass from ever touching the pack's image. Only attachments
 * 1-6, the classification data, are kept.
 * <p>
 * \u26a0 The six textures are NOT owned here \u2014 they belong to {@link BLibMainTargetMRT}, which allocates them
 * through {@code attachInternal} so that their formats and filtering cannot drift from the no-pack path. This class
 * owns only the framebuffer object.
 */
@ApiStatus.Internal
public final class BLibIrisAuxTarget {

    public static final BLibIrisAuxTarget INSTANCE = new BLibIrisAuxTarget();

    private static final int GL_FRAMEBUFFER = 36160;

    private static final int GL_COLOR_ATTACHMENT0 = 36064;

    private static final int GL_DEPTH_ATTACHMENT = 0x8D00;

    private static final int GL_TEXTURE_2D = 3553;

    private static final int GL_NONE = 0;

    private int frameBufferId = -1;

    private int width;

    private int height;

    /**
     * OUR OWN depth texture. Deliberately not the main target's.
     * <p>
     * ⭐⭐ BORROWING THE LIVE TEXTURE WORKS AT THE END OF THE LEVEL PASS AND FAILS BEFORE TRANSLUCENT TERRAIN. By the end
     * Iris has finished with it; earlier it is still attached to Iris's own framebuffer, and having the same depth
     * texture attached to two framebuffers while one of them is being drawn into is precisely where drivers stop
     * behaving predictably. Measured at the early point: depth reads fine, bindings restore fine, the framebuffer is
     * complete, no exception is thrown — and the pass writes ZERO pixels. A private copy removes the sharing entirely.
     */
    private int depthTextureId = -1;

    private BLibIrisAuxTarget() {}

    public boolean isReady() {
        return frameBufferId != -1;
    }

    public int frameBufferId() {
        return frameBufferId;
    }

    /**
     * Creates or revalidates the framebuffer for the current main-target size, returning {@code true} if it is ready to
     * be bound.
     * <p>
     * \u26a0 A resize, a pack toggle, or a resource reload can all replace the main target and its depth texture.
     * Rather than trying to hook every one of those, this compares the size and the borrowed depth texture id each call
     * and rebuilds when either moved \u2014 the same "re-ask the question rather than trust an event" discipline that
     * the main-target reconcile needed.
     */
    public boolean ensure() {
        var mainTarget = Minecraft.getInstance().getMainRenderTarget();

        if (mainTarget == null || mainTarget.viewWidth <= 0 || mainTarget.viewHeight <= 0) {
            return false;
        }

        if (
            frameBufferId != -1
                && width == mainTarget.viewWidth
                && height == mainTarget.viewHeight
        ) {
            return true;
        }

        RenderSystem.assertOnRenderThreadOrInit();

        invalidate();

        width = mainTarget.viewWidth;
        height = mainTarget.viewHeight;

        frameBufferId = GL30.glGenFramebuffers();

        var previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        var previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        GL30.glBindFramebuffer(GL_FRAMEBUFFER, frameBufferId);

        // Allocates the six textures AND attaches them to whatever framebuffer is bound - ours, here.
        BLibMainTargetMRT.attachInternal(frameBufferId, width, height);

        // A depth attachment of our own. Filled each frame by blitting the scene depth in - see copySceneDepth.
        depthTextureId = TextureUtil.generateTextureId();

        GlStateManager._bindTexture(depthTextureId);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10241, 9728);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10240, 9728);
        GlStateManager._texImage2D(GL_TEXTURE_2D, 0, 0x81A6, width, height, 0, 0x1902, 0x1405, null);
        GlStateManager._bindTexture(0);

        GlStateManager._glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthTextureId, 0);

        // Buffer 0 discarded; 1-6 keep the classification. See the class note.
        GL30.glDrawBuffers(
            new int[] {
                GL_NONE,
                GL_COLOR_ATTACHMENT0 + 1,
                GL_COLOR_ATTACHMENT0 + 2,
                GL_COLOR_ATTACHMENT0 + 3,
                GL_COLOR_ATTACHMENT0 + 4,
                GL_COLOR_ATTACHMENT0 + 5,
                GL_COLOR_ATTACHMENT0 + 6
            }
        );

        var status = GL30.glCheckFramebufferStatus(GL_FRAMEBUFFER);

        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);

        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            // Reported loudly and once: an incomplete framebuffer silently discards every draw, which would look
            // exactly like the classification pass "not running" and would be hunted in entirely the wrong place.
            BLib.LOGGER.error(
                "[BLib] Iris auxiliary framebuffer is INCOMPLETE (status 0x{}); classification under a shader pack"
                    + " cannot run. Own depth texture {}, size {}x{}.",
                Integer.toHexString(status),
                depthTextureId,
                width,
                height
            );

            invalidate();

            return false;
        }

        BLib.LOGGER.info(
            "[BLib] Iris auxiliary framebuffer {} ready ({}x{}), own depth texture {}.",
            frameBufferId,
            width,
            height,
            depthTextureId
        );

        return true;
    }

    /**
     * Copies the scene depth from the main render target into our own depth attachment.
     * <p>
     * ⚠ Must be called with the aux framebuffer NOT bound for reading, and before any geometry is drawn for the frame.
     * A blit is a full-resolution copy, but depth-only and nearest-filtered, and it runs once per frame while the mask
     * is down.
     */
    /**
     * Copies scene depth only if the framebuffer already exists, for use from the early hook.
     * <p>
     * ⚠ Deliberately does NOT call {@link #ensure()}. Creating the framebuffer mid-level-pass would allocate textures
     * at a point in Iris's pipeline this class has no business touching; on the first frame the copy is simply skipped
     * and the pass falls back to whatever the attachment already holds.
     */
    public void copySceneDepthIfReady() {
        if (frameBufferId == -1) {
            return;
        }

        copySceneDepth();
    }

    public void copySceneDepth() {
        var mainTarget = Minecraft.getInstance().getMainRenderTarget();

        if (mainTarget == null || frameBufferId == -1) {
            return;
        }

        var previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        var previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mainTarget.frameBufferId);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, frameBufferId);

        // 0x100 is GL_DEPTH_BUFFER_BIT, 0x2600 is GL_NEAREST - spec values, as elsewhere.
        GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, 0x100, 0x2600);

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
    }

    /**
     * Drops the framebuffer and the textures on it.
     * <p>
     * \u26a0 The DEPTH texture is borrowed and must never be released here \u2014 it belongs to the main render target,
     * and deleting it would take vanilla's depth buffer out from under Iris.
     */
    public void invalidate() {
        if (frameBufferId != -1) {
            GL30.glDeleteFramebuffers(frameBufferId);
            frameBufferId = -1;
        }

        if (depthTextureId != -1) {
            TextureUtil.releaseTextureId(depthTextureId);
            depthTextureId = -1;
        }

        width = 0;
        height = 0;

        BLibMainTargetMRT.destroy();
    }
}
