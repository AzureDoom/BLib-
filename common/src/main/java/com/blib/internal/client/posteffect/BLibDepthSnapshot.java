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
 * A copy of the level's depth buffer, taken while it still describes the world.
 * <h2>Why this exists</h2> Vanilla clears the depth buffer before drawing the item in hand, so the first-person hand
 * cannot clip into terrain. That happens inside the level pass — which means by the time any post effect runs,
 * {@code MainTarget}'s depth attachment describes <em>only the held item</em>. Everything else reads as the far plane.
 * <p>
 * The symptom is deceptive rather than obviously broken: a post effect that reconstructs world position from depth gets
 * plausible-looking values for the hand and far-plane values for the entire world, so it silently does nothing
 * everywhere except a small patch in the corner of the screen. It was diagnosed exactly that way — a debug view lit up
 * on the held block and nowhere else.
 * <p>
 * So the depth is copied at the end of the level pass, before that clear, and post effects sample the copy. This is the
 * same shape as {@link BLibTerrainMaskFixup}: capture during the level pass what the post pass can no longer see.
 * <h2>Cost</h2> One {@code glCopyTexSubImage2D} of a depth texture per frame, which is a GPU-side copy with no readback
 * and no synchronisation. It only runs when at least one post effect is active, so a frame with no vision running pays
 * nothing.
 */
@ApiStatus.Internal
public final class BLibDepthSnapshot {

    private static final int GL_DEPTH_COMPONENT24 = 33190;

    private static final int GL_DEPTH_COMPONENT = 6402;

    private static final int GL_TEXTURE_2D = 3553;

    private static final int GL_FLOAT = 5126;

    private static int textureId = -1;

    private static int width;

    private static int height;

    private static boolean failed;

    private BLibDepthSnapshot() {
        throw new UnsupportedOperationException();
    }

    /**
     * @return the snapshot texture, or the main target's live depth texture when no snapshot has been taken. The
     *         fallback keeps consumers working rather than handing them texture 0, though it will be the
     *         post-hand-clear depth and therefore mostly far plane.
     */
    public static int textureId() {
        if (textureId != -1) {
            return textureId;
        }

        var mainTarget = Minecraft.getInstance().getMainRenderTarget();

        return mainTarget == null ? 0 : mainTarget.getDepthTextureId();
    }

    public static boolean isAvailable() {
        return textureId != -1;
    }

    /**
     * DIAGNOSTIC ONLY. Counts completed captures so the mask probe can report how many landed between its ticks.
     * <p>
     * ⭐ THE QUESTION THIS ANSWERS: thermal reads depth and lags; EM reads only the mask and does NOT lag. That puts the
     * fault in the depth the post effect consumes. Either the snapshot is not being refreshed every frame, or it is
     * refreshed and captures the wrong thing — and those need completely different repairs. A count of roughly the
     * framerate means refreshing is fine and the content is suspect; a count near zero means the capture hook is not
     * running at all, which is the whole bug.
     * <p>
     * ⚠ The capture injection is {@code require = 0}, so it can silently stop matching and never fire.
     */
    public static int consumeCaptureCount() {
        var count = captureCount;

        captureCount = 0;

        return count;
    }

    private static int captureCount;

    /**
     * Copies the MainTarget's depth into the snapshot, at the end of the level pass while that depth still describes
     * the world.
     * <p>
     * ⚠⚠ THE READ FRAMEBUFFER IS BOUND EXPLICITLY, AND ASSUMING IT INSTEAD WAS A REAL BUG. {@code glCopyTexSubImage2D}
     * copies from whatever is bound as the READ framebuffer, and this code used to simply trust that it was still the
     * MainTarget because vanilla leaves it that way. It is not our frame alone: mixin ordering between two mods at the
     * same injection point is ARBITRARY, and other mods snapshot depth, render shadow passes and run their own post
     * pipelines at exactly this hook — Polytone does all three. If one of them runs first and leaves its own
     * framebuffer bound for reading, this silently captured THEIR depth, and every consumer that reconstructs world
     * position from it read nonsense.
     * <p>
     * The previous binding is restored afterwards so nothing downstream can notice.
     */
    public static void capture() {
        // ⭐⭐ THE SHADER-PACK GATE IS GONE, AND REMOVING IT IS THE POINT.
        // It was correct while the whole post pipeline stood down under a pack. Now the pipeline RUNS under a pack, and
        // this snapshot is the only source of usable scene depth it has: vanilla clears the main target's depth for the
        // held item BEFORE post-processing, so the live attachment describes the hand and nothing else. Reading it
        // there gave a uniform flat-blue thermal view — every pixel resolving to "no depth, no heat".
        //
        // ⚠ This runs at the END of the level pass, where the world's depth is complete and the hand has not been
        // drawn. That is as true under Iris as without it: the depth was measured populated at 0.977-0.999 there across
        // a live session with a pack running.
        if (failed) {
            return;
        }

        var mainTarget = Minecraft.getInstance().getMainRenderTarget();

        if (mainTarget == null || mainTarget.width <= 0 || mainTarget.height <= 0) {
            return;
        }

        if (!ensureTexture(mainTarget.width, mainTarget.height)) {
            return;
        }

        var previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        var previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mainTarget.frameBufferId);
        GlStateManager._bindTexture(textureId);

        GL11.glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, mainTarget.width, mainTarget.height);

        GlStateManager._bindTexture(previousTexture);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);

        captureCount++;
    }

    public static void destroy() {
        if (textureId != -1) {
            TextureUtil.releaseTextureId(textureId);
            textureId = -1;
        }

        width = 0;
        height = 0;
    }

    private static boolean ensureTexture(int targetWidth, int targetHeight) {
        if (textureId != -1 && width == targetWidth && height == targetHeight) {
            return true;
        }

        RenderSystem.assertOnRenderThreadOrInit();

        destroy();

        textureId = TextureUtil.generateTextureId();

        var previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        GlStateManager._bindTexture(textureId);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10241, 9728);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10240, 9728);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10242, 33071);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10243, 33071);
        GlStateManager._texImage2D(
            GL_TEXTURE_2D,
            0,
            GL_DEPTH_COMPONENT24,
            targetWidth,
            targetHeight,
            0,
            GL_DEPTH_COMPONENT,
            GL_FLOAT,
            null
        );
        GlStateManager._bindTexture(previousTexture);

        var error = GL11.glGetError();

        if (error != GL11.GL_NO_ERROR) {
            BLib.LOGGER.error(
                "[BLib] Depth snapshot texture allocation failed (GL error 0x{}); post effects will see the"
                    + " post-hand-clear depth instead.",
                Integer.toHexString(error)
            );

            destroy();
            failed = true;

            return false;
        }

        width = targetWidth;
        height = targetHeight;

        return true;
    }
}
