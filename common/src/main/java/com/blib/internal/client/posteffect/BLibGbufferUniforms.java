package com.blib.internal.client.posteffect;

import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.ApiStatus;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.blib.api.BLibAPI;
import com.blib.api.common.mod.v1.model.loader.ModLoaderType;
import com.blib.mod.BLib;

/**
 * Per-shader hooks that run after every {@code ShaderInstance.apply()}:
 * <ol>
 * <li>Sets the patcher-injected {@code BlibHeldItem} uniform to flag held-item draws (the patched fragment writes the
 * {@code 0.875} held-item mask category when this is non-zero, which the thermal post detects and short-circuits to
 * original color).</li>
 * <li>Sets the patcher-injected {@code BlibBackgroundEntity} (lane A) and {@code BlibBackgroundEntity2} (lane B)
 * uniforms to flag entity draws that should render as part of the world background. The patched fragment packs the two
 * lanes into {@code entityMask.g} as {@code 0.25 * laneA + 0.5 * laneB}, so the four combinations land at 0.0 / 0.25 /
 * 0.5 / 0.75. Consumer post-effects sample {@code .g} and decode to per-lane flags (or just check {@code .g > 0.5} for
 * legacy "any background" behavior, since that matches lane B alone).</li>
 * <li>Toggles {@code glColorMaski} for the auxiliary attachments (1-6) based on whether the bound shader is one we've
 * categorized for the thermal pipeline. Patched shaders write valid auxiliary data and have full writes enabled.
 * Unpatched shaders — entity shadows, the block-outline wireframe, glints, leashes, crumbling overlay, etc. — have
 * writes to attachments 1-6 SUPPRESSED entirely. That's what stops them from blending zeros (or driver-undefined
 * garbage) on top of the underlying terrain/entity's already- written mask byte and corrupting it. The auxiliary
 * content the post shader reads at those pixels is whatever the underlying classified draw left there, which is exactly
 * what we want.</li>
 * </ol>
 * <p>
 * Cache: vanilla calls {@code apply()} hundreds of times per frame, so the {@code glGetUniformLocation} lookup is
 * cached per program ID. Patched/unpatched lookup is name-based and cheap (a handful of string equals).
 */
@ApiStatus.Internal
public final class BLibGbufferUniforms {

    private static final int UNCACHED = Integer.MIN_VALUE;

    private static final ConcurrentMap<Integer, Integer> HELD_ITEM_LOC_CACHE = new ConcurrentHashMap<>();

    private static final ConcurrentMap<Integer, Integer> BACKGROUND_ENTITY_LOC_CACHE = new ConcurrentHashMap<>();

    private static final ConcurrentMap<Integer, Integer> BACKGROUND_ENTITY2_LOC_CACHE = new ConcurrentHashMap<>();

    private static final ConcurrentMap<Integer, Integer> MATERIAL_ID_LOC_CACHE = new ConcurrentHashMap<>();

    /**
     * Tracks the last colorMask state we applied so we don't issue six glColorMaski calls per shader-bind when the
     * state is unchanged. State transitions are clustered (e.g. all terrain draws are patched, then a run of unpatched
     * line draws), so this collapses long runs into a single set.
     */
    private static boolean lastAuxWritesEnabled = true;

    private BLibGbufferUniforms() {
        throw new UnsupportedOperationException();
    }

    public static void apply(int programId, String shaderName) {
        // ⚠⚠ THE SECOND CLAUSE IS WHY THE CLASSIFICATION CAME BACK EMPTY UNDER A SHADER PACK. A blanket pack check
        // here is right for Iris's own passes and WRONG for BLib's classification pass, which is the one place under a
        // pack where these uniforms and the auxiliary colour mask ARE wanted. Without it the pass drew entities into a
        // perfectly valid framebuffer with writes masked off and every uniform unset, and the mask read back zero.
        if (BLibIrisCompat.isShaderPackActive() && !BLibIrisClassificationPass.isInsidePass()) {
            return;
        }

        toggleAuxColorMask(shaderName);
        applyHeldItemUniform(programId);
        applyBackgroundEntityUniform(programId, shaderName);
        applyBackgroundEntity2Uniform(programId, shaderName);
        applyMaterialIdUniform(programId);
    }

    /**
     * Resets the cached colorMask state. Call when the auxiliary attachments are (re)attached so the next shader bind
     * unconditionally re-issues the colorMask state — otherwise a stale "we already enabled writes" record could skip
     * the call after a framebuffer reattach left the GL state at the default (all enabled but for a different
     * framebuffer's draw buffers).
     */
    public static void resetColorMaskCache() {
        lastAuxWritesEnabled = true;
    }

    /**
     * Forces the auxiliary colour mask OFF and syncs the cache to match, at the top of every level pass.
     * <p>
     * WHY THIS EXISTS: {@code toggleAuxColorMask} skips the GL call whenever the requested state already matches
     * {@code lastAuxWritesEnabled}. That cache is BLib's BELIEF about GL state, and nothing stops a third party from
     * invalidating it: any mod that calls {@code glColorMask}/{@code glColorMaski}, switches framebuffers, or runs its
     * own geometry pass leaves the real state and the belief disagreeing, and BLib then skips the very call that would
     * have corrected it. The disagreement persists until something happens to flip the cache.
     * <p>
     * ⚠⚠ AND THE DEFAULT USED TO BE THE DANGEROUS WAY ROUND. {@code resetColorMaskCache} assumes writes are ENABLED, so
     * any foreign pass drawing before the first patched shader of the frame — a shadow pass, an instanced renderer,
     * another mod's post pipeline — writes UNDEFINED values straight into attachments 1-6. That is the same failure as
     * Sodium's unpatched chunk shaders: classification that was never written, read back as structured garbage.
     * <p>
     * ⭐ Making OFF the per-frame default means a foreign pass can no longer corrupt the classification of anything; the
     * worst it can do is leave its own pixels unclassified, which the depth reclassify already handles. The first
     * patched draw of the frame turns writes back on. Six GL calls per frame.
     */
    public static void forceAuxWritesOffForFrame() {
        // ⚠⚠⚠ isAttachedToMainTarget, NOT isAttached, AND THE DIFFERENCE CORRUPTED THE WHOLE SCREEN. glColorMaski is
        // GLOBAL GL state indexed by DRAW BUFFER — it is not scoped to a framebuffer. Once the attachments moved to the
        // private Iris framebuffer, isAttached() was still true, so this ran under a shader pack and masked off colour
        // writes on draw buffers 1-6 for EVERYTHING, including the pack's own gbuffer and composite passes. The result
        // was a grossly overexposed view in every vision mode, including the one that renders nothing at all.
        if (!BLibMainTargetMRT.isAttachedToMainTarget()) {
            return;
        }

        for (int buf = 1; buf <= 6; buf++) {
            GL30.glColorMaski(buf, false, false, false, false);
        }

        lastAuxWritesEnabled = false;
    }

    /**
     * ⚠⚠⚠ TURNS BLENDING OFF FOR THE AUXILIARY ATTACHMENTS ONLY. THIS FIXES THE HORIZON BAND, AND ITS ABSENCE IS WHY
     * THAT BUG SURVIVED EVERY OTHER FIX.
     * <p>
     * Blend state in OpenGL is PER DRAW BUFFER, but vanilla only ever sets it globally — so when it enables blending to
     * draw the sun, the moon or the sunrise gradient, that blend applies to attachments 1-6 as well, and the
     * classification byte gets MIXED with whatever was already there instead of replacing it.
     * <p>
     * ⭐⭐ CAUGHT BY DIRECT ATTRIBUTION, not deduction. The writer trace named {@code position_tex} and showed the damage
     * as PARTIAL values drifting a few units off a real category — terrain 128 sliding to 120, celestial 16 to 15, and
     * pairs oscillating 89/83 and 88/82 between consecutive draws. Garbage does not look like that; a blend does.
     * <p>
     * ⚠ I DISMISSED THIS EARLIER IN THE DAY on the grounds that the patcher writes the mask with alpha 1.0, so under
     * {@code SRC_ALPHA/ONE_MINUS_SRC_ALPHA} the source would win outright. That is true — and irrelevant, because
     * vanilla draws celestial bodies ADDITIVELY, where the destination is added back in whatever the source alpha is.
     * <p>
     * ⚠ Re-asserted at EVERY shader bind rather than once: a plain {@code glEnable(GL_BLEND)} sets the state for ALL
     * draw buffers and silently undoes an indexed disable, and vanilla calls it constantly. There is no hook for "some
     * mod changed blend state", so the only safe cadence is every time we are about to allow a draw to write.
     */
    public static void disableAuxBlending() {
        // Same reasoning as forceAuxWritesOffForFrame: glDisablei(GL_BLEND, n) is global per-draw-buffer state, so it
        // must not be issued on behalf of attachments that currently live on someone else's framebuffer. The
        // classification pass issues it for itself while it owns the bindings.
        if (!BLibMainTargetMRT.isAttachedToMainTarget() && !BLibIrisClassificationPass.isInsidePass()) {
            return;
        }

        for (int buf = 1; buf <= 6; buf++) {
            GL30.glDisablei(GL30.GL_BLEND, buf);
        }
    }

    /**
     * Shaders whose category only makes sense during the sky stage, because vanilla reuses the same name for a
     * full-screen overlay drawn long after terrain and entities.
     * <p>
     * ⚠⚠ {@code position_tex} is tagged CELESTIAL for the sun and moon — and is ALSO what draws the underwater overlay.
     * Submerged, it stamped celestial (mask 16) across the entire view, so a xenomorph two metres away read as sky in
     * BOTH vision modes while block heat, which comes from depth rather than the mask, carried on landing on it.
     * Measured directly: the same creature reads 255 above the surface and 16 below it.
     * <p>
     * ⚠ {@code position_tex_color} is here for the same reason — the sunrise gradient and the void plane during the sky
     * stage, an overlay afterwards.
     * <p>
     * Outside the sky stage these are treated as unpatched overlays: auxiliary writes suppressed, whatever is behind
     * them keeps its classification. Removing them from the patcher's category lists instead does NOT work — that was
     * tried, and it turned the void plane into a flat green sheet across the lower sky, because the sky-stage use
     * genuinely does need deterministic zeroing.
     */
    private static boolean isSkyStageOnlyShader(String shaderName) {
        return shaderName.equals("position_tex") || shaderName.equals("position_tex_color");
    }

    /**
     * ⭐⭐ DIRECT ATTRIBUTION FOR A CORRUPT MASK BYTE. OFF unless {@code -Dblib.postEffect.maskWriterTrace=true}.
     * <p>
     * Every shader bind during the level pass passes through here, so this reads the classification byte at screen
     * centre at each one. When that byte turns into a value the system never writes, it logs the shader that drew
     * IMMEDIATELY BEFORE — which is the shader that put it there. No inference, no elimination: the writer names
     * itself.
     * <p>
     * ⚠ EXPENSIVE ON PURPOSE — a {@code glReadPixels} stall per shader bind, so framerate will drop hard. Run it for a
     * few seconds pointed at the artifact, then take the flag out. It is a trap, not a monitor.
     * <p>
     * READING THE OUTPUT: the interesting line is the FIRST transition to a value that is not 255/223/128/64/16/0, and
     * the {@code after=} name on that line is the culprit. Repeats of the same transition are suppressed so one look at
     * the horizon produces a handful of lines rather than thousands.
     */
    private static void traceMaskWriter(String shaderName) {
        if (!Boolean.getBoolean("blib.postEffect.maskWriterTrace")) {
            return;
        }

        var maskTexture = BLibMainTargetMRT.entityMaskTextureId();

        if (maskTexture == 0) {
            lastTracedShader = shaderName;

            return;
        }

        var target = Minecraft.getInstance().getMainRenderTarget();
        var previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        var scratch = GL30.glGenFramebuffers();
        var category = -1;

        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, scratch);
            GL30.glFramebufferTexture2D(
                GL30.GL_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D,
                maskTexture,
                0
            );

            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE) {
                var pixel = BufferUtils.createByteBuffer(4);

                GL11.glReadPixels(
                    target.width / 2,
                    target.height / 2,
                    1,
                    1,
                    GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE,
                    pixel
                );

                category = pixel.get(0) & 0xFF;
            }
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
            GL30.glDeleteFramebuffers(scratch);
        }

        if (category != -1 && category != lastTracedCategory) {
            var transition = lastTracedCategory + ">" + category + "@" + lastTracedShader;

            if (!transition.equals(lastLoggedTransition)) {
                lastLoggedTransition = transition;

                BLib.LOGGER.info(
                    "[BLib][maskWriterTrace] centre {} -> {}{} after={} nowBinding={}",
                    lastTracedCategory,
                    category,
                    isKnownCategory(category) ? "" : "  <<< NOT A VALID CATEGORY",
                    lastTracedShader,
                    shaderName
                );
            }

            lastTracedCategory = category;
        }

        lastTracedShader = shaderName;
    }

    private static boolean isKnownCategory(int category) {
        return category == 255 || category == 223 || category == 128 || category == 64 || category == 16
            || category == 0;
    }

    private static int lastTracedCategory = -1;

    private static String lastTracedShader = "<frame start>";

    private static String lastLoggedTransition = "";

    /**
     * ⭐⭐ MAKES WATER, GLASS AND ICE THERMALLY SEE-THROUGH WITHOUT LOSING THEIR OWN CLASSIFICATION.
     * <p>
     * The translucent pass draws AFTER entities, and it is tagged TERRAIN, so the water surface painted TERRAIN
     * straight over every fish beneath it — [stated] "i cant see heat through the waters surface". ⚠ The obvious fix,
     * dropping {@code rendertype_translucent} from the TERRAIN list, WAS TRIED AND REVERTED: it makes the pass
     * unpatched, so glass and ice over open sky would keep the SKY classification behind them and read warm.
     * <p>
     * ⭐⭐⭐ INSTEAD, BLEND THE MASK WITH {@code GL_MAX}. The category constants are ordered by how foreground a thing is
     * — entity 255 &gt; held item 223 &gt; terrain 128 &gt; particle 64 &gt; celestial 16 &gt; sky 0 — so taking the
     * maximum means THE MOST FOREGROUND CLASSIFICATION WINS, which is exactly the rule we want:
     * <ul>
     * <li>water over a fish → max(255, 128) = 255, the fish survives and stays warm.</li>
     * <li>glass over open sky → max(0, 128) = 128, the pane reads as terrain rather than as sky.</li>
     * <li>ice over stone → max(128, 128) = 128, unchanged.</li>
     * </ul>
     * <p>
     * ⚠ ATTACHMENT 1 ONLY. Attachments 2-6 stay masked off for this pass: a MAX over drawData or specular would mix the
     * water's own surface detail into the fish's heat inputs, so the classification survives but the numbers feeding it
     * must not be touched.
     * <p>
     * ⚠ Returns true when it has taken over the colour mask, so the caller must not then apply its own.
     */
    /**
     * ⚠⚠⚠ Aug 27 — {@code GL40.glBlendEquationi(1, GL_MAX)} below IS the Fabric 0.1.5 world-join killer (exit
     * {@code 0xC0000409}, no crash report, no hs_err — the process dies inside the driver). Found by bytecode-bisecting
     * the shipped blib-fabric jar live on the affected machine: fourteen single-change jars narrowed it from
     * "registering any post effect" to this one instruction. Removing ONLY this call (TEST13) loads and plays; removing
     * everything else and keeping it (TEST14) still crashes.
     * <p>
     * ⚠ THE MECHANISM IS NOT UNDERSTOOD. The same instruction on the same GPU (GL 4.6) runs fine under NeoForge, and
     * the driver gives no diagnostic. Every theory tried — missing function pointer, stale framebuffer id,
     * patched-shader interaction, per-frame pipeline state — was ELIMINATED by measurement (unpatched shaders still
     * crashed; the pipeline, depth snapshot, uniforms and mask loop were each removed individually and the crash
     * survived all of them). Do not "clean this up" into a capability check: the capability IS reported present on the
     * crashing machines.
     * <p>
     * So the MAX equation is DISABLED BY DEFAULT ON FABRIC and kept on NeoForge, with a system property to override
     * either way for testing: {@code -Dblib.translucentMaxBlend=true|false}. The Fabric fallback is exactly the
     * gameplay-verified TEST13 state: buffer 1 keeps ordinary alpha blending, so the entity classification survives
     * everywhere EXCEPT behind a translucent surface — on Fabric, a xeno under water reads on thermal only once the
     * camera is submerged too. That trade is deliberate: a degraded vision beats a dead process, and the flag lets us
     * re-test the full path the moment the driver interaction is understood.
     */
    private static final boolean TRANSLUCENT_MAX_BLEND_ENABLED = resolveTranslucentMaxBlendEnabled();

    private static boolean resolveTranslucentMaxBlendEnabled() {
        var override = System.getProperty("blib.translucentMaxBlend");

        if (override != null) {
            return Boolean.parseBoolean(override);
        }

        return BLibAPI.getModLoaderType() != ModLoaderType.FABRIC;
    }

    private static boolean applyTranslucentMaxBlend(String shaderName) {
        if (!isTranslucentTerrainShader(shaderName)) {
            return false;
        }

        GL30.glColorMaski(1, true, true, true, true);

        for (int buf = 2; buf <= 6; buf++) {
            GL30.glColorMaski(buf, false, false, false, false);
        }

        GL30.glEnablei(GL30.GL_BLEND, 1);

        // ⚠ glBlendEquationi is GL 4.0, NOT GL30 — the indexed ENABLE and COLOR MASK arrived in 3.0 but the indexed
        // blend-equation setter did not, so the call and its constant live in different classes. The old comment here
        // claimed "every desktop driver that runs Minecraft exposes 4.x, so this is safe" — Fabric falsified that in
        // the field. See TRANSLUCENT_MAX_BLEND_ENABLED above before touching this.
        if (TRANSLUCENT_MAX_BLEND_ENABLED) {
            GL40.glBlendEquationi(1, GL30.GL_MAX);
        }

        // The cache no longer describes reality — force the next ordinary shader to re-issue its mask outright.
        lastAuxWritesEnabled = !lastAuxWritesEnabled;

        return true;
    }

    private static boolean isTranslucentTerrainShader(String shaderName) {
        return shaderName.equals("rendertype_translucent")
            || shaderName.equals("rendertype_translucent_moving_block");
    }

    private static void toggleAuxColorMask(String shaderName) {
        if (!BLibMainTargetMRT.isAttached()) {
            return;
        }

        traceMaskWriter(shaderName);

        // Blend state is per-draw-buffer but vanilla sets it globally, so this has to be re-asserted constantly.
        // See disableAuxBlending — without it, additive sky draws MIX into the classification byte.
        disableAuxBlending();

        if (applyTranslucentMaxBlend(shaderName)) {
            return;
        }

        var patched = BLibEntityShaderPatcher.categoryFor(shaderName) != null;

        // ⚠⚠ Some vanilla shaders wear one name across several jobs at different points in the frame: the void plane
        // and the sunrise gradient during
        // the sky stage, and the FULL-SCREEN UNDERWATER OVERLAY drawn long after terrain and entities. As passthrough
        // it zeroes the auxiliary attachments — correct for the first two, catastrophic for the third, where it wipes
        // the classification of every fish and dolphin behind it.
        //
        // Outside the sky stage it is therefore treated as an unpatched overlay: writes suppressed, whatever is
        // underneath preserved. Dropping it from the passthrough list instead was tried and turned the void plane into
        // a flat green sheet across the lower sky — the name alone cannot tell the cases apart, only the timing can.
        if (patched && !BLibSkyStage.isDrawing() && isSkyStageOnlyShader(shaderName)) {
            patched = false;
        }

        // ⚠⚠ SAME PROBLEM, OTHER END OF THE FRAME: vanilla draws RAIN AND SNOW with the PARTICLE shader
        // (LevelRenderer.renderSnowAndRain -> GameRenderer::getParticleShader), so weather classified as PARTICLE
        // alongside campfire smoke and flame. PARTICLE is not a neutral answer — thermal reads it as
        // 0.80 * emission + blockHeat, which made FALLING WATER REGISTER AS WARM: bright streaks across the sky in
        // every rainstorm, and hot dashes drifting past terrain and mobs.
        //
        // Suppressed rather than given a cold value, deliberately. Rain attenuates what is behind it; it does not
        // paint over it, and a thermal camera shows the scene through the rain. Writing cold instead would punch
        // drop-shaped holes in every warm body standing in a storm.
        //
        // ⚠ Genuine particles must keep their classification, so this is scoped to the weather window only — the
        // shader name alone cannot tell weather from smoke, exactly as with the sky pair above.
        if (patched && BLibWeatherStage.isDrawing() && shaderName.equals("particle")) {
            patched = false;
        }

        if (patched == lastAuxWritesEnabled) {
            return;
        }

        // Buffers 1-6 correspond to the six auxiliary color attachments of MainTarget. Setting all four
        // channel-mask bits at once gates whether fragment writes to those attachments take effect; attachment 0
        // is left untouched so the bound shader's color output always lands.
        for (int buf = 1; buf <= 6; buf++) {
            GL30.glColorMaski(buf, patched, patched, patched, patched);
        }

        lastAuxWritesEnabled = patched;
    }

    private static void applyHeldItemUniform(int programId) {
        var loc = HELD_ITEM_LOC_CACHE.computeIfAbsent(
            programId,
            p -> GL20.glGetUniformLocation(p, "BlibHeldItem")
        );

        if (loc == -1) {
            return;
        }

        GL20.glUniform1i(loc, BLibHeldItemRenderState.isActive() ? 1 : 0);
    }

    private static void applyBackgroundEntityUniform(int programId, String shaderName) {
        var loc = BACKGROUND_ENTITY_LOC_CACHE.computeIfAbsent(
            programId,
            p -> GL20.glGetUniformLocation(p, "BlibBackgroundEntity")
        );

        if (loc == -1) {
            return;
        }

        GL20.glUniform1i(loc, isBackgroundEntity() ? 1 : 0);
    }

    /**
     * ⭐⭐ BLOCK ENTITIES RIDE THE EXISTING BACKGROUND LANE, and that is why no new category plumbing was needed.
     * <p>
     * Vanilla draws chests, beds, signs, banners, shulker boxes, item frames and enchanting tables through ENTITY
     * render types — {@code Sheets.chestSheet()} and {@code Sheets.bedSheet()} resolve to
     * {@code RenderType.entityCutout}/{@code entitySolid} — so furniture was stamped ENTITY and lit up on
     * electromagnetic vision next to living things. ⚠ Thermal misclassified them too; they merely landed in the ambient
     * tier and read cold, so nobody noticed.
     * <p>
     * ⭐ Third instance of the same shape today: {@link BLibSkyStage} split the sun from the underwater overlay,
     * {@link BLibWeatherStage} split rain from smoke. **A shader NAME cannot say what is being drawn — only WHEN it is
     * drawn distinguishes the cases.**
     * <p>
     * The category literal is baked into the patched shader, so it cannot be changed at bind time. The BACKGROUND flag
     * can, and it already means exactly "classified as an entity, but route it to the background branch" — the same
     * mechanism that stops a held item giving away the player's position.
     * <p>
     * ⏭ THE OPT-IN IS DELIBERATELY NOT BUILT YET. Making containers visible again is a consumer-side decision
     * (avp_predator would tag which block entities count), and that needs a BLib hook designed with him rather than
     * guessed at — the natural shape is a settable exemption on {@link BLibBlockEntityStage} that the renderer consults
     * per block entity.
     */
    private static boolean isBackgroundEntity() {
        return BLibBackgroundEntityRenderState.isActiveA() || BLibBlockEntityStage.isDrawing();
    }

    /**
     * Pushes the per-draw material ID. Zero unless a consumer has set one around this draw, so shaders and consumers
     * that never touch it are unaffected.
     */
    private static void applyMaterialIdUniform(int programId) {
        var loc = MATERIAL_ID_LOC_CACHE.computeIfAbsent(
            programId,
            p -> GL20.glGetUniformLocation(p, "BlibMaterialId")
        );

        if (loc == -1) {
            return;
        }

        GL20.glUniform1i(loc, BLibMaterialIdRenderState.current());
    }

    private static void applyBackgroundEntity2Uniform(int programId, String shaderName) {
        var loc = BACKGROUND_ENTITY2_LOC_CACHE.computeIfAbsent(
            programId,
            p -> GL20.glGetUniformLocation(p, "BlibBackgroundEntity2")
        );

        if (loc == -1) {
            return;
        }

        GL20.glUniform1i(loc, BLibBackgroundEntityRenderState.isActiveB() ? 1 : 0);
    }
}
