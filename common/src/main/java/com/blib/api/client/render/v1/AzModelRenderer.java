package com.blib.api.client.render.v1;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import it.unimi.dsi.fastutil.ints.IntIntPair;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Arrays;

import com.blib.api.client.animation.v1.animator.AzAnimator;
import com.blib.api.client.model.v1.AzBone;
import com.blib.api.client.render.v1.dismemberment.DismembermentBoneVisibilityFilter;
import com.blib.api.client.render.v1.item.pipeline.AzItemRendererPipelineContext;
import com.blib.internal.client.model.GeoCube;
import com.blib.internal.client.model.GeoQuad;
import com.blib.internal.client.model.GeoVertex;
import com.blib.internal.client.render.util.RenderUtil;

/**
 * AzModelRenderer provides a generic and extensible base class for rendering models by processing hierarchical bone
 * structures recursively. It leverages a rendering pipeline and a layer renderer to facilitate advanced rendering
 * tasks, including layer application and animated texture processing.
 *
 * @param <K> The type of the key used to identify the animatable object. Typically, a UUID for items/entities and Long
 *            for BlockEntities.
 * @param <T> the type of animatable object this renderer supports
 */
public class AzModelRenderer<K, T> {

    private final Matrix4f poseStateCache = new Matrix4f();

    private final Vector3f normalScratch = new Vector3f();

    private final Vector4f quadPosition = new Vector4f();

    private final Matrix3f normalStateCache = new Matrix3f();

    private Matrix4f[] savedBonePoses = new Matrix4f[0];

    private Matrix3f[] savedBoneNormals = new Matrix3f[0];

    private int boneDepth;

    private final AzRendererPipeline<K, T> rendererPipeline;

    protected final AzLayerRenderer<K, T> layerRenderer;

    private IntIntPair entityTextureSize;

    @Nullable
    private VertexConsumer passBuffer;

    @Nullable
    private RenderType passRenderType;

    public AzModelRenderer(AzRendererPipeline<K, T> rendererPipeline, AzLayerRenderer<K, T> layerRenderer) {
        this.layerRenderer = layerRenderer;
        this.rendererPipeline = rendererPipeline;
    }

    /**
     * The actual render method that subtype renderers should override to handle their specific rendering tasks.<br>
     */
    protected void render(AzRendererPipelineContext<K, T> context, boolean isReRender) {
        var animatable = context.animatable();
        var model = context.bakedModel();

        rendererPipeline.updateAnimatedTextureFrame(animatable);

        var previousPassBuffer = this.passBuffer;
        var previousPassRenderType = this.passRenderType;
        var previousTextureOverride = context.getTextureOverride();

        this.passBuffer = context.vertexConsumer();
        this.passRenderType = context.renderType();

        try {
            var topLevelBones = model.getTopLevelBones();

            for (int i = 0, size = topLevelBones.size(); i < size; i++) {
                renderRecursively(context, topLevelBones.get(i), isReRender);
            }
        } finally {
            if (this.passBuffer != null) {
                context.setVertexConsumer(this.passBuffer);
            }

            context.setTextureOverride(previousTextureOverride);
            this.passBuffer = previousPassBuffer;
            this.passRenderType = previousPassRenderType;
        }

        var config = rendererPipeline.config();
        config.renderEntry(context);
    }

    /**
     * Whether {@code bone} (and its subtree) should be skipped entirely for this animatable: detached dismembered limbs
     * and bones hidden by the renderer's {@link BoneVisibilityFilter}.
     */
    protected boolean isBoneFiltered(AzRendererPipelineContext<K, T> context, AzBone bone) {
        // Always-on dismemberment hide first so consumers don't need to remember to wire the filter onto every renderer
        // — Dismemberable mobs auto-hide detached limb subtrees, non-Dismemberable animatables (items, block entities)
        // early-return false inside the static check.
        if (DismembermentBoneVisibilityFilter.isDetachedBone(bone, context.animatable())) {
            return true;
        }

        var visibilityFilter = rendererPipeline.config().boneVisibilityFilter();

        return visibilityFilter != null && visibilityFilter.shouldHideBone(bone, context.animatable());
    }

    /**
     * Renders the provided {@link AzBone} and its associated child bones
     */
    protected void renderRecursively(AzRendererPipelineContext<K, T> context, AzBone bone, boolean isReRender) {
        if (isBoneFiltered(context, bone)) {
            return;
        }

        var buffer = context.vertexConsumer();
        var bufferSource = context.multiBufferSource();
        var poseStack = context.poseStack();

        var slot = saveBonePose(poseStack);

        try {
            renderBone(context, bone, isReRender, buffer, bufferSource, poseStack);
        } finally {
            restoreBonePose(poseStack, slot);
        }
    }

    private void renderBone(
        AzRendererPipelineContext<K, T> context,
        AzBone bone,
        boolean isReRender,
        VertexConsumer buffer,
        MultiBufferSource bufferSource,
        PoseStack poseStack
    ) {
        RenderUtil.prepMatrixForBone(poseStack, bone);

        context.setVertexConsumer(getOrRefreshRenderBuffer(isReRender, context, bone));

        if (
            !boneRenderOverride(
                poseStack,
                bone,
                bufferSource,
                buffer,
                context.partialTick(),
                context.packedLight(),
                context.packedOverlay(),
                context.renderColor()
            )
        )
            renderCubesOfBone(context, bone);

        if (!isReRender) {
            layerRenderer.applyRenderLayersForBone(context, bone);
        }

        renderChildBones(context, bone, isReRender);
    }

    /**
     * Saves the current pose so a bone can transform it in place, without the allocation of
     * {@link PoseStack#pushPose()}. Pair every call with {@link #restoreBonePose} (in a {@code finally}), in reverse
     * order. Code between the two may still push and pop the pose stack normally.
     *
     * @return the slot to pass to {@link #restoreBonePose}
     */
    protected final int saveBonePose(PoseStack poseStack) {
        var slot = boneDepth++;

        if (slot >= savedBonePoses.length) {
            var size = Math.max(16, savedBonePoses.length * 2);
            var poses = Arrays.copyOf(savedBonePoses, size);
            var normals = Arrays.copyOf(savedBoneNormals, size);

            for (var i = savedBonePoses.length; i < size; i++) {
                poses[i] = new Matrix4f();
                normals[i] = new Matrix3f();
            }

            savedBonePoses = poses;
            savedBoneNormals = normals;
        }

        var last = poseStack.last();
        savedBonePoses[slot].set(last.pose());
        savedBoneNormals[slot].set(last.normal());

        return slot;
    }

    /**
     * Restores the pose saved by {@link #saveBonePose}.
     */
    protected final void restoreBonePose(PoseStack poseStack, int slot) {
        var last = poseStack.last();
        last.pose().set(savedBonePoses[slot]);
        last.normal().set(savedBoneNormals[slot]);
        boneDepth = slot;
    }

    /**
     * Renders the {@link GeoCube GeoCubes} associated with a given {@link AzBone}
     */
    protected void renderCubesOfBone(AzRendererPipelineContext<K, T> context, AzBone bone) {
        if (bone.isHidden()) {
            return;
        }

        var cubes = bone.getCubes();
        var skipFlatCubes = context.skipFlatCubes();

        for (int i = 0, size = cubes.size(); i < size; i++) {
            var cube = cubes.get(i);

            // A plane (one zero-length side) is skipped only when the pass asked for it - see
            // AzRendererPipelineContext.skipFlatCubes; normal passes draw everything.
            if (skipFlatCubes && cube.isFlat()) {
                continue;
            }

            renderCube(context, cube);
        }
    }

    /** A cube with a zero-length side is a single quad - the same test {@code RenderUtil.fixInvertedFlatCube} uses. */
    protected static boolean isFlat(GeoCube cube) {
        return cube.isFlat();
    }

    /**
     * Render the child bones of a given {@link AzBone}.<br>
     * Note that this does not render the bone itself. That should be done through
     * {@link AzModelRenderer#renderCubesOfBone} separately
     */
    protected void renderChildBones(AzRendererPipelineContext<K, T> context, AzBone bone, boolean isReRender) {
        if (bone.isHidingChildren())
            return;

        var children = bone.getChildBones();

        for (int i = 0, size = children.size(); i < size; i++) {
            renderRecursively(context, children.get(i), isReRender);
        }
    }

    /**
     * Renders an individual {@link GeoCube}.<br>
     * The cube's own rotation is baked into {@link GeoCube#transform()} at load and applied straight to a scratch
     * matrix, so the pose stack is not touched.
     */
    protected void renderCube(AzRendererPipelineContext<K, T> context, GeoCube cube) {
        var last = context.poseStack().last();
        var transform = cube.transform();
        var poseState = poseStateCache.set(last.pose());
        Matrix3f normalisedPoseState;

        if (transform.identity()) {
            normalisedPoseState = last.normal();
        } else {
            poseState.mul(transform.pose());
            normalisedPoseState = normalStateCache.set(last.normal()).mul(transform.normal());
        }

        var cubeInflate = context.cubeInflate();
        var inflated = cubeInflate != 0 && RenderUtil.applyCubeInflation(poseState, cube, cubeInflate);

        var normalFlips = cube.normalFlips();
        var quadFilter = context.getTextureOverride() == null ? context.quadFilter() : null;

        for (var quad : cube.quads()) {
            if (quad == null || (quadFilter != null && !passes(quad, quadFilter))) {
                continue;
            }

            normalScratch.set(quad.normal());
            normalisedPoseState.transform(normalScratch);

            if (inflated) {
                // The inflation scale is not applied to the normal matrix; renormalise so lighting stays correct.
                normalScratch.normalize();
            }

            var normal = normalScratch;

            RenderUtil.fixInvertedFlatCube(normalFlips, normal);
            createVerticesOfQuad(context, quad, poseState, normal);
        }
    }

    private static boolean passes(GeoQuad quad, AzQuadFilter filter) {
        var vertices = quad.vertices();
        float minU = vertices[0].texU(), maxU = minU, minV = vertices[0].texV(), maxV = minV;

        for (int i = 1; i < vertices.length; i++) {
            var u = vertices[i].texU();
            var v = vertices[i].texV();
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
        }

        return filter.test(minU, minV, maxU, maxV);
    }

    /**
     * Applies the {@link GeoQuad Quad's} {@link GeoVertex vertices} to the given {@link VertexConsumer buffer} for
     * rendering
     */
    protected void createVerticesOfQuad(
        AzRendererPipelineContext<K, T> context,
        GeoQuad quad,
        Matrix4f poseState,
        Vector3f normal
    ) {
        var buffer = context.vertexConsumer();
        var color = context.renderColor();
        var packedOverlay = context.packedOverlay();
        var packedLight = context.packedLight();
        var textureOverride = context.getTextureOverride();
        var boneTextureSize = textureOverride != null ? context.computeTextureSize(textureOverride) : null;
        var baseTextureSize = textureOverride != null ? baseTextureSize(context) : null;
        boolean useOverride = textureOverride != null && boneTextureSize != null && baseTextureSize != null;
        float uScale = useOverride ? (float) baseTextureSize.firstInt() / boneTextureSize.firstInt() : 1f;
        float vScale = useOverride ? (float) baseTextureSize.secondInt() / boneTextureSize.secondInt() : 1f;
        float nx = normal.x(), ny = normal.y(), nz = normal.z();

        for (var vertex : quad.vertices()) {
            var position = vertex.position();
            var vector4f = poseState.transform(quadPosition.set(position.x(), position.y(), position.z(), 1.0f));
            if (useOverride) {
                buffer.addVertex(
                    vector4f.x(),
                    vector4f.y(),
                    vector4f.z(),
                    -1,
                    vertex.texU() * uScale,
                    vertex.texV() * vScale,
                    packedOverlay,
                    packedLight,
                    nx,
                    ny,
                    nz
                );
            } else {
                buffer.addVertex(
                    vector4f.x(),
                    vector4f.y(),
                    vector4f.z(),
                    color,
                    vertex.texU(),
                    vertex.texV(),
                    packedOverlay,
                    packedLight,
                    nx,
                    ny,
                    nz
                );
            }
        }
    }

    /**
     * The main texture's size for the current render, cached by {@link #cacheTexture} for the duration of a render
     * pass. Falls back to a lookup when called outside one (e.g. a custom renderer that calls into this directly).
     */
    private @Nullable IntIntPair baseTextureSize(AzRendererPipelineContext<K, T> context) {
        if (entityTextureSize != null) {
            return entityTextureSize;
        }

        return context.computeTextureSize(
            rendererPipeline.config().textureLocation(context.currentEntity(), context.animatable())
        );
    }

    /**
     * Override method for rendering a specific bone. This method can be customized to apply specific transformations,
     * modify the bone's render properties, or override its rendering behavior entirely.
     *
     * @return A boolean indicating whether the bone's rendering behavior has been overridden successfully.
     */
    public boolean boneRenderOverride(
        PoseStack poseStack,
        AzBone bone,
        MultiBufferSource bufferSource,
        VertexConsumer buffer,
        float partialTick,
        int packedLight,
        int packedOverlay,
        int colour
    ) {
        return false;
    }

    public void handleAnimation(AzAnimator<?, T> animator, T animatable, float partialTick) {
        animator.animate(animatable, partialTick);
    }

    /**
     * Retrieves or refreshes the {@link VertexConsumer} for rendering based on the current buffer state and rendering
     * context.
     */
    public VertexConsumer getOrRefreshBufferRenderType(
        AzItemRendererPipelineContext context,
        AzBone bone,
        RenderType renderType
    ) {
        var currentBuffer = context.multiBufferSource().getBuffer(renderType);
        var bufferSource = context.multiBufferSource();

        return refreshBuffer(currentBuffer, bufferSource, renderType);
    }

    /**
     * Retrieves the appropriate {@link VertexConsumer} for rendering {@code bone}, applying any per-bone texture or
     * render type override, or refreshes the pass buffer if the batch it was writing to has been closed.
     */
    public VertexConsumer getOrRefreshRenderBuffer(
        boolean isReRender,
        AzRendererPipelineContext<K, T> context,
        AzBone bone
    ) {
        var config = rendererPipeline.config();
        var bufferSource = context.multiBufferSource();
        var animatable = context.animatable();

        var texture = config.boneTextureOverrideProvider(animatable, bone);
        var renderTypeOverride = config.boneRenderTypeOverrideProvider(animatable, bone);

        context.setTextureOverride(texture);

        if (texture != null && renderTypeOverride == null) {
            var baseTexture = config.textureLocation(context.currentEntity(), animatable);
            var baseRenderType = config.getRenderType(context.currentEntity(), animatable);

            renderTypeOverride = context.getDefaultRenderType(
                animatable,
                texture,
                bufferSource,
                context.partialTick(),
                retargetRenderType(baseRenderType, baseTexture, texture),
                config.alpha(animatable)
            );
        }

        if (renderTypeOverride != null) {
            return bufferSource.getBuffer(renderTypeOverride);
        }

        var buffer = passBuffer != null ? passBuffer : context.vertexConsumer();
        var renderType = passRenderType != null ? passRenderType : context.renderType();

        if (buffer == null || renderType == null) {
            return buffer;
        }

        var refreshed = refreshBuffer(buffer, bufferSource, renderType);

        if (refreshed != buffer && passBuffer != null) {
            passBuffer = refreshed;
        }

        return refreshed;
    }

    /**
     * Returns {@code buffer}, or a fresh buffer for {@code renderType} in its place if the batch it was writing to has
     * been closed.
     */
    protected VertexConsumer refreshBuffer(
        VertexConsumer buffer,
        MultiBufferSource bufferSource,
        RenderType renderType
    ) {
        return switch (buffer) {
            case BufferBuilder builder when isBufferInactive(builder) -> bufferSource.getBuffer(renderType);
            case OutlineBufferSource.EntityOutlineGenerator outline when needsBufferRefresh(outline.delegate()) ->
                new OutlineBufferSource.EntityOutlineGenerator(bufferSource.getBuffer(renderType), outline.color());
            case VertexMultiConsumer.Double pair when needsBufferRefresh(pair.first) || needsBufferRefresh(
                pair.second
            ) ->
                new VertexMultiConsumer.Double(
                    needsBufferRefresh(pair.first) ? bufferSource.getBuffer(renderType) : pair.first,
                    needsBufferRefresh(pair.second) ? bufferSource.getBuffer(renderType) : pair.second
                );
            default -> buffer;
        };
    }

    /**
     * Returns the equivalent of {@code renderType} for a different texture, used for bones with a texture override.
     * <p>
     * Render types can't be re-pointed at another texture directly, so this recognizes the standard entity render types
     * and rebuilds the matching one for {@code newTexture}. Anything else falls back to
     * {@link RenderType#entityCutout}. For full control over an overridden bone's render type, use the bone render type
     * override provider instead, which takes precedence over this.
     * </p>
     */
    protected RenderType retargetRenderType(
        @Nullable RenderType renderType,
        @Nullable ResourceLocation baseTexture,
        ResourceLocation newTexture
    ) {
        if (newTexture.equals(baseTexture)) {
            return renderType != null ? renderType : RenderType.entityCutout(newTexture);
        }

        if (renderType != null && baseTexture != null) {
            if (renderType == RenderType.entityCutoutNoCull(baseTexture)) {
                return RenderType.entityCutoutNoCull(newTexture);
            }

            if (renderType == RenderType.entityTranslucent(baseTexture)) {
                return RenderType.entityTranslucent(newTexture);
            }

            if (renderType == RenderType.entityTranslucentCull(baseTexture)) {
                return RenderType.entityTranslucentCull(newTexture);
            }

            if (renderType == RenderType.entityTranslucentEmissive(baseTexture)) {
                return RenderType.entityTranslucentEmissive(newTexture);
            }

            if (renderType == RenderType.entitySolid(baseTexture)) {
                return RenderType.entitySolid(newTexture);
            }

            if (renderType == RenderType.armorCutoutNoCull(baseTexture)) {
                return RenderType.armorCutoutNoCull(newTexture);
            }
        }

        return RenderType.entityCutout(newTexture);
    }

    /**
     * Determines whether the given {@link VertexConsumer} requires a buffer refresh.
     */
    protected boolean needsBufferRefresh(VertexConsumer buffer) {
        return switch (buffer) {
            case BufferBuilder builder -> isBufferInactive(builder);
            case OutlineBufferSource.EntityOutlineGenerator outline -> needsBufferRefresh(outline.delegate());
            case VertexMultiConsumer.Double pair ->
                needsBufferRefresh(pair.first) || needsBufferRefresh(pair.second);
            default -> false;
        };
    }

    /**
     * Determines if the given {@link BufferBuilder} is inactive (not currently building).
     */
    protected boolean isBufferInactive(BufferBuilder builder) {
        return !builder.building;
    }

    /**
     * Caches the main texture's size for the current render pass, so bones with a texture override don't look it up
     * once per quad. Called by {@link AzRendererPipeline#render}; pair with {@link #clearCacheTexture()}.
     */
    public void cacheTexture(AzRendererPipelineContext<K, T> context) {
        this.entityTextureSize = context.computeTextureSize(
            rendererPipeline.config().textureLocation(context.currentEntity(), context.animatable())
        );
    }

    public void clearCacheTexture() {
        this.entityTextureSize = null;
    }
}
