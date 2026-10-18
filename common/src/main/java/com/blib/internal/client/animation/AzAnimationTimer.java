package com.blib.internal.client.animation;

import net.minecraft.client.Minecraft;

import com.blib.api.client.animation.v1.animator.AzAnimatorConfig;
import com.blib.internal.client.render.util.RenderUtil;

public class AzAnimationTimer {

    private final AzAnimatorConfig config;

    // Remnants from GeoModel.
    private double animTime;

    private double lastGameTickTime;

    private boolean wasPausedLastFrame;

    /**
     * AzureLib 3.1.22 fix, BLib form (Oct 17). {@link #lastGameTickTime} used to start at 0, so the first
     * {@link #tick()} jumped {@link #animTime} from 0 to the absolute render time. A track reset before that tick (a
     * command replayed when the animator is created, before its first frame) took 0 as its start, saw the jump, and
     * treated its transition as already finished on frame one - one of the causes of the bind-pose (T-pose) frame.
     * <p>
     * ⚠ AzureLib fixes it by starting animTime at 0. BLib instead starts BOTH at the render time now, which removes the
     * jump the same way but keeps animTime on the same absolute clock it always reached after one tick. That matters:
     * {@code query.life_time} is animTime / 20, and the harbinger's whole idle sway (76 keyframe values in
     * harbinger.animation.json) is sin/cos of it. Starting at 0 would have re-phased every harbinger and made each one
     * sway out of step with the others. This way every value from the first tick on is what it was before.
     * </p>
     */
    public AzAnimationTimer(AzAnimatorConfig config) {
        this.config = config;
        this.animTime = RenderUtil.getCurrentTick();
        this.lastGameTickTime = this.animTime;
    }

    public void tick() {
        var minecraft = Minecraft.getInstance();
        var currentRenderTick = RenderUtil.getCurrentTick();

        if (minecraft.isPaused()) {
            if (!wasPausedLastFrame) {
                // If this is the first frame of the game pause time, we need to set a flag.
                this.wasPausedLastFrame = true;
            }

            if (!config.shouldPlayAnimationsWhileGamePaused()) {
                // If we cannot play animations while the game is paused, then return early.
                return;
            }
        }

        // Compute the delta render tick for this frame. This calculation by default does not account for the game
        // pause state, which means that the difference here could be massive by the time the player unpauses.
        var deltaRenderTick = currentRenderTick - lastGameTickTime;

        if (wasPausedLastFrame && !minecraft.isPaused()) {
            // If this is the first frame of the game playtime, we need to set a flag and adjust the deltaRenderTick.
            this.wasPausedLastFrame = false;
            // To account for the deltaRenderTick being massive on exiting the game pause state, we simply set
            // it to 0. This will result in no difference being added to animTime, allowing animations to
            // continue right where it left off.
            deltaRenderTick = 0;
        }

        // Add the deltaRenderTick to animTime. animTime is what controls the progress of animations.
        this.animTime += deltaRenderTick;
        this.lastGameTickTime = currentRenderTick;
    }

    public double getAnimTime() {
        return animTime;
    }
}
