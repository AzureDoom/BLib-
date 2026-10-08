package com.blib.internal.client.posteffect;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.ApiStatus;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.blib.api.client.shader.v1.BLibPostEffectInput;
import com.blib.api.client.shader.v1.BLibPostEffectUniform;
import com.blib.internal.mixin.posteffect.LightTextureAccessor;
import com.blib.mod.BLib;

/**
 * Per-frame runner. Walks {@link BLibPostEffectRegistry#ALL}, filters by active state, ping-pongs source/dest across
 * two {@link BLibPostEffectFramebuffers} targets while compositing, and blits the final result back to the MainTarget's
 * color attachment.
 * <p>
 * No-ops when Iris is active (Iris owns post-processing) or when no effects are active.
 */
@ApiStatus.Internal
public final class BLibPostEffectPipeline {

    /**
     * FIELD DIAGNOSTIC, OFF UNLESS {@code -Dblib.postEffect.maskProbe=true} IS SET. Logs the classification byte at
     * screen centre roughly once a second, alongside the fog distances in force.
     * <p>
     * ⚠⚠ AN EARLIER VERSION OF THIS SHIPPED UNGATED IN 0.3.5-fork and cost every player a framebuffer create/destroy
     * and a render-thread {@code glReadPixels} every second, plus a log line forever. It stays behind a flag now — but
     * it stays, because it is the ONLY instrument that reaches a user's machine, and it is what identified a mask full
     * of values that are not categories at all.
     * <p>
     * READING IT: valid categories are 255 entity, 223 held item, 128 terrain, 64 particle, 16 celestial, 0 sky. ⭐
     * ANYTHING ELSE MEANS THE MASK ITSELF IS WRONG, and no amount of shader tuning will help — look for whatever is
     * writing to or resizing the attachments. Fog start/end are logged beside it because a "no fog" resource pack
     * (Polytone, {@code fog_radius: 10000000}) was confirmed to break the vision, and this is the cheapest way to see
     * whether absurd fog distances are reaching the shaders.
     */
    private static void blib$probeMaskAtCentre() {
        if (!Boolean.getBoolean("blib.postEffect.maskProbe")) {
            return;
        }

        var now = System.currentTimeMillis();

        if (now - blib$lastProbeAt < 1000L) {
            return;
        }

        blib$lastProbeAt = now;

        var maskTexture = BLibMainTargetMRT.entityMaskTextureId();

        if (maskTexture == 0) {
            return;
        }

        var target = Minecraft.getInstance().getMainRenderTarget();
        var previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        var scratch = GL30.glGenFramebuffers();

        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, scratch);
            GL30.glFramebufferTexture2D(
                GL30.GL_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D,
                maskTexture,
                0
            );

            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                return;
            }

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

            var category = pixel.get(0) & 0xFF;

            BLib.LOGGER.info(
                "[BLib][maskProbe] category={} ({}) depthCapturesSinceLastTick={} fogStart={} fogEnd={}",
                category,
                blib$describeCategory(category),
                BLibDepthSnapshot.consumeCaptureCount(),
                RenderSystem.getShaderFogStart(),
                RenderSystem.getShaderFogEnd()
            );
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
            GL30.glDeleteFramebuffers(scratch);
        }
    }

    private static String blib$describeCategory(int category) {
        return switch (category) {
            case 255 -> "entity";
            case 223 -> "held item";
            case 128 -> "terrain";
            case 64 -> "particle";
            case 16 -> "celestial";
            case 0 -> "sky";
            default -> "NOT A VALID CATEGORY";
        };
    }

    private static long blib$lastProbeAt;

    private static final Set<String> WARNED_ARRAY_UNIFORMS = new HashSet<>();

    private BLibPostEffectPipeline() {
        throw new UnsupportedOperationException();
    }

    public static void run(DeltaTracker deltaTracker) {
        // ⚠ Aug 28 — whatever happens below (vision active, inactive, disabled, foreign pipeline), the level-pass
        // aux-write window CLOSES here: everything after this point in the frame is UI-era rendering, and its aux
        // outputs must be discarded. See BLibMainTargetMRT.setLevelPassActive.
        try {
            com.blib.internal.client.posteffect.BLibMainTargetMRT.setLevelPassActive(false);
        } catch (Throwable ignored) {}

        // ⭐⭐ STAGE 2: THE PIPELINE NO LONGER STANDS DOWN JUST BECAUSE A SHADER PACK IS RUNNING.
        // BLibIrisClassificationPass draws the classification into a private framebuffer during the level pass, and
        // BLibMainTargetMRT's accessors hand back those same textures, so every sampler binding below works unchanged.
        // The stand-down now means what it should have meant all along: stand down only if a pack owns the pipeline AND
        // we have no classification of our own to read.
        if (BLibIrisCompat.isShaderPackActive() && !BLibIrisAuxTarget.INSTANCE.isReady()) {
            return;
        }

        var registry = BLibPostEffectRegistry.ALL;

        if (registry.isEmpty()) {
            return;
        }

        var active = registry.stream()
            .filter(BLibPostEffectImpl::isActive)
            .filter(e -> e.shaderInstance() != null)
            .sorted(Comparator.comparingInt(e -> e.spec().priority()))
            .toList();

        if (active.isEmpty()) {
            return;
        }

        var mc = Minecraft.getInstance();
        var mainTarget = mc.getMainRenderTarget();

        if (mainTarget == null) {
            return;
        }

        // ⚠⚠ BIND THE MAINTARGET EXPLICITLY BEFORE TOUCHING ANYTHING. This runs from GameRenderer.render, and other
        // mods hook the very same method to run their OWN post-processing — Polytone binds its post-shader targets
        // there. Mixin ordering between two mods at one injection point is ARBITRARY, so "vanilla left the MainTarget
        // bound" is an assumption we do not get to make. Everything below reads the MainTarget's attachments and blits
        // back into it; starting from someone else's framebuffer corrupts both their frame and ours.
        mainTarget.bindWrite(false);

        // Repair the terrain mask immediately before it is consumed. See BLibTerrainMaskFixup.
        BLibTerrainMaskFixup.runLateRepair();

        var fbs = BLibPostEffectFramebuffers.INSTANCE;
        fbs.ensureSize(mainTarget.width, mainTarget.height);

        var targetA = fbs.targetA();
        var targetB = fbs.targetB();

        if (targetA == null || targetB == null) {
            return;
        }

        // Save GL state we plan to perturb. (We rely on MC's RenderSystem state being consistent on the way in.)
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
        GlStateManager._disableBlend();
        GlStateManager._colorMask(true, true, true, true);

        // Stage 0: copy MainTarget color → targetA via an FBO blit.
        blitColor(mainTarget, targetA);

        var source = targetA;
        var dest = targetB;

        for (var effect : active) {
            applyEffect(effect, source, dest, mainTarget, deltaTracker);
            var swap = source;
            source = dest;
            dest = swap;
        }

        // Final stage: copy whatever ended up in `source` back to MainTarget color.
        blitColor(source, mainTarget);

        // Restore main framebuffer binding for subsequent GUI render. Critically, also restore MainTarget's
        // glDrawBuffers state to [0, 1, 2] — `blitColor` left it as [0] only, which would silently drop writes
        // to attachments 1 and 2 in subsequent frames and "freeze" the entity mask/lightmap at whatever was last
        // populated.
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, mainTarget.frameBufferId);
        BLibMainTargetMRT.restoreDrawBuffers();
        RenderSystem.viewport(0, 0, mainTarget.width, mainTarget.height);

        GlStateManager._depthMask(true);
        GlStateManager._enableDepthTest();
    }

    private static void applyEffect(
        BLibPostEffectImpl effect,
        RenderTarget source,
        RenderTarget dest,
        RenderTarget mainTarget,
        DeltaTracker deltaTracker
    ) {
        var shader = effect.shaderInstance();

        if (shader == null) {
            return;
        }

        dest.bindWrite(true);

        var inputs = effect.spec().inputs();

        if (inputs.contains(BLibPostEffectInput.COLOR_TEXTURE)) {
            shader.setSampler("DiffuseSampler", source.getColorTextureId());
        }

        if (inputs.contains(BLibPostEffectInput.DEPTH_TEXTURE)) {
            // The snapshot, not the live attachment: vanilla clears depth for the item in hand before post-processing
            // runs, so the live one describes only the held item. Falls back to the live attachment if the capture
            // never happened. See BLibDepthSnapshot.
            // The snapshot, not the live attachment: vanilla clears depth for the item in hand before post-processing
            // runs, so the live one describes only the held item. That holds under a shader pack too — pointing this
            // at the live main-target depth produced a completely flat thermal view. The snapshot is taken at the end
            // of the level pass, where the depth is real, and is now captured with or without a pack.
            shader.setSampler("depthtex0", BLibDepthSnapshot.textureId());
        }

        if (inputs.contains(BLibPostEffectInput.LIGHTMAP_TEXTURE)) {
            var lightmap = mc().gameRenderer.lightTexture();
            var loc = ((LightTextureAccessor) lightmap).blib$getLightTextureLocation();
            var tex = mc().getTextureManager().getTexture(loc);

            if (tex != null) {
                shader.setSampler("lightmap", tex.getId());
            }
        }

        if (inputs.contains(BLibPostEffectInput.ENTITY_MASK) && BLibMainTargetMRT.isAttached()) {
            shader.setSampler("entityMask", BLibMainTargetMRT.entityMaskTextureId());

            blib$probeMaskAtCentre();
        }

        if (inputs.contains(BLibPostEffectInput.ENTITY_LIGHTMAP) && BLibMainTargetMRT.isAttached()) {
            shader.setSampler("entityLightmap", BLibMainTargetMRT.entityLightmapTextureId());
        }

        if (inputs.contains(BLibPostEffectInput.ENTITY_NORMAL) && BLibMainTargetMRT.isAttached()) {
            shader.setSampler("entityNormal", BLibMainTargetMRT.entityNormalTextureId());
        }

        if (inputs.contains(BLibPostEffectInput.ENTITY_DRAW_DATA) && BLibMainTargetMRT.isAttached()) {
            shader.setSampler("entityDrawData", BLibMainTargetMRT.entityDrawDataTextureId());
        }

        if (inputs.contains(BLibPostEffectInput.ENTITY_SPECULAR) && BLibMainTargetMRT.isAttached()) {
            shader.setSampler("entitySpecular", BLibMainTargetMRT.entitySpecularTextureId());
        }

        if (inputs.contains(BLibPostEffectInput.ENTITY_MATERIAL_ID) && BLibMainTargetMRT.isAttached()) {
            shader.setSampler("entityMaterialId", BLibMainTargetMRT.entityMaterialIdTextureId());
        }

        BLibPostEffectStdUniforms.apply(shader, deltaTracker, dest.width, dest.height);
        applyEffectUniforms(shader, effect.spec().uniforms());

        shader.apply();

        // Array uniforms go after apply(): they are pushed straight to the program, which apply() is what binds.
        applyArrayUniforms(shader, effect.spec().uniforms());

        drawFullscreenQuad();

        shader.clear();
    }

    private static void applyEffectUniforms(ShaderInstance shader, List<BLibPostEffectUniform> uniforms) {
        for (var u : uniforms) {
            if (u instanceof BLibPostEffectUniform.Float4Array) {
                continue;
            }

            var slot = shader.getUniform(u.name());

            if (slot == null) {
                continue;
            }

            switch (u) {
                case BLibPostEffectUniform.Float4Array ignored -> { /* pushed in applyArrayUniforms, after apply() */ }
                case BLibPostEffectUniform.Float1 f -> slot.set(f.value().getAsFloat());
                case BLibPostEffectUniform.Float2 f -> {
                    var v = f.value().get();
                    slot.set(v.x, v.y);
                }
                case BLibPostEffectUniform.Float3 f -> {
                    var v = f.value().get();
                    slot.set(v.x, v.y, v.z);
                }
                case BLibPostEffectUniform.Float4 f -> {
                    var v = f.value().get();
                    slot.set(v.x, v.y, v.z, v.w);
                }
                case BLibPostEffectUniform.Int1 i -> slot.set(i.value().getAsInt());
                case BLibPostEffectUniform.Matrix4 m -> slot.set(m.value().get());
                case BLibPostEffectUniform.Texture t -> {
                    var loc = t.value().get();
                    var tex = Minecraft.getInstance().getTextureManager().getTexture(loc);
                    if (tex != null) {
                        shader.setSampler(t.name(), tex.getId());
                    }
                }
            }
        }
    }

    /**
     * Pushes {@link BLibPostEffectUniform.Float4Array} values directly to the bound program. Vanilla's
     * {@code ShaderInstance} has no array uniform support — {@code getUniform} returns null for one — so the location
     * is resolved by name against the program id and uploaded with {@code glUniform4fv}. A location of -1 means the
     * shader does not declare it (or the linker dropped it as unused), which is not an error.
     */
    private static void applyArrayUniforms(ShaderInstance shader, List<BLibPostEffectUniform> uniforms) {
        for (var u : uniforms) {
            if (!(u instanceof BLibPostEffectUniform.Float4Array array)) {
                continue;
            }

            var location = resolveArrayLocation(shader.getId(), array.name());

            if (location == -1) {
                continue;
            }

            var values = array.value().get();

            if (values == null || values.length < 4) {
                GL20.glUniform4fv(location, new float[4]);

                continue;
            }

            var count = Math.min(values.length / 4, array.maxCount());

            GL20.glUniform4fv(location, java.util.Arrays.copyOf(values, count * 4));
        }
    }

    /**
     * Resolves an array uniform's location, trying the bare name first and then {@code name[0]}.
     * <p>
     * ⚠ The GL spec allows querying an array by its bare name, but not every driver obliges — some only resolve the
     * subscripted form, and AMD is the usual place this shows up. A silently unresolved location makes an array uniform
     * look like a feature that simply does nothing, so the miss is logged once per name.
     */
    private static int resolveArrayLocation(int programId, String name) {
        var location = GL20.glGetUniformLocation(programId, name);

        if (location != -1) {
            return location;
        }

        location = GL20.glGetUniformLocation(programId, name + "[0]");

        if (location == -1 && WARNED_ARRAY_UNIFORMS.add(name)) {
            BLib.LOGGER.warn(
                "[BLib] Post-effect array uniform '{}' resolved to no location (tried '{}' and '{}[0]')."
                    + " The shader either does not declare it or the linker dropped it as unused.",
                name,
                name,
                name
            );
        }

        return location;
    }

    private static void drawFullscreenQuad() {
        var bb = RenderSystem.renderThreadTesselator().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLIT_SCREEN);
        bb.addVertex(0.0F, 0.0F, 0.0F);
        bb.addVertex(1.0F, 0.0F, 0.0F);
        bb.addVertex(1.0F, 1.0F, 0.0F);
        bb.addVertex(0.0F, 1.0F, 0.0F);
        BufferUploader.draw(bb.buildOrThrow());
    }

    private static void blitColor(RenderTarget src, RenderTarget dst) {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, src.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, dst.frameBufferId);
        GL30.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL30.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
        GL30.glBlitFramebuffer(
            0,
            0,
            src.width,
            src.height,
            0,
            0,
            dst.width,
            dst.height,
            GL11.GL_COLOR_BUFFER_BIT,
            GL11.GL_NEAREST
        );
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }
}
