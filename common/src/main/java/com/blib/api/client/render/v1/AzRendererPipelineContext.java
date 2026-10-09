package com.blib.api.client.render.v1;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.ints.IntIntPair;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

import com.blib.api.client.model.v1.AzBakedModel;
import com.blib.api.common.color.v1.Color;
import com.blib.internal.client.render.util.RenderUtil;

public abstract class AzRendererPipelineContext<K, T> {

    public ResourceLocation textureOverride;

    private final AzRendererPipeline<K, T> rendererPipeline;

    protected T animatable;

    protected @Nullable Entity currentEntity;

    private AzBakedModel bakedModel;

    private MultiBufferSource multiBufferSource;

    private int packedLight;

    private int packedOverlay;

    private float partialTick;

    private PoseStack poseStack;

    private int renderColor;

    private @Nullable RenderType renderType;

    private VertexConsumer vertexConsumer;

    private boolean applyAnimationOnReRender;

    private @Nullable AzQuadFilter quadFilter;

    private float cubeInflate;

    private boolean skipFlatCubes;

    protected static final Map<ResourceLocation, IntIntPair> TEXTURE_DIMENSIONS_CACHE =
        new Object2ObjectOpenHashMap<>();

    protected AzRendererPipelineContext(AzRendererPipeline<K, T> rendererPipeline) {
        this.rendererPipeline = rendererPipeline;
    }

    public void populate(
        T animatable,
        AzBakedModel bakedModel,
        MultiBufferSource multiBufferSource,
        int packedLight,
        float partialTick,
        PoseStack poseStack,
        RenderType renderType,
        VertexConsumer vertexConsumer
    ) {
        this.animatable = animatable;
        this.bakedModel = bakedModel;
        this.multiBufferSource = multiBufferSource;
        this.packedLight = packedLight;
        this.packedOverlay = getPackedOverlay(animatable, 0, partialTick);
        this.partialTick = partialTick;
        this.poseStack = poseStack;
        this.renderType = renderType;
        this.vertexConsumer = vertexConsumer;
        this.renderColor = getRenderColor(animatable, partialTick, packedLight).argbInt();

        if (renderType == null) {
            var textureLocation = rendererPipeline.config().textureLocation(currentEntity, animatable);
            this.renderType = getDefaultRenderType(
                animatable,
                textureLocation,
                multiBufferSource,
                partialTick,
                rendererPipeline.config().getRenderType(currentEntity, animatable),
                rendererPipeline.config().alpha(animatable)
            );
        }

        if (vertexConsumer == null && this.renderType != null) {
            this.vertexConsumer = multiBufferSource.getBuffer(this.renderType);
        }
    }

    public abstract RenderType getDefaultRenderType(
        T animatable,
        ResourceLocation texture,
        @Nullable MultiBufferSource bufferSource,
        float partialTick,
        RenderType defaultRenderType,
        float alpha
    );

    protected Color getRenderColor(T animatable, float partialTick, int packedLight) {
        return Color.WHITE;
    }

    protected int getPackedOverlay(T animatable, float u, float partialTick) {
        return OverlayTexture.NO_OVERLAY;
    }

    public AzRendererPipeline<K, T> rendererPipeline() {
        return rendererPipeline;
    }

    public T animatable() {
        return animatable;
    }

    public void setCurrentEntity(Entity currentEntity) {
        this.currentEntity = currentEntity;
    }

    public @Nullable Entity currentEntity() {
        return currentEntity;
    }

    public AzBakedModel bakedModel() {
        return bakedModel;
    }

    /**
     * Swaps the model the current pass renders, e.g. for a render layer that re-renders a different model with the
     * same animatable. {@code null} falls back to {@link AzBakedModel#getDefault()}.
     */
    public void setBakedModel(@Nullable AzBakedModel bakedModel) {
        this.bakedModel = bakedModel != null ? bakedModel : AzBakedModel.getDefault();
    }

    /**
     * Whether a re-render pass should run the animator again (normally only the first pass animates). Set for the
     * duration of {@link AzRendererPipeline#reRender(AzRendererPipelineContext, boolean)}.
     */
    public boolean applyAnimationOnReRender() {
        return this.applyAnimationOnReRender;
    }

    public void setApplyAnimationOnReRender(boolean applyAnimationOnReRender) {
        this.applyAnimationOnReRender = applyAnimationOnReRender;
    }

    /**
     * The quad filter for the current pass, or {@code null} to draw every quad. Ignored for bones with a texture
     * override, since the filter is defined in the main texture's UV space.
     */
    public @Nullable AzQuadFilter quadFilter() {
        return quadFilter;
    }

    public void setQuadFilter(@Nullable AzQuadFilter quadFilter) {
        this.quadFilter = quadFilter;
    }

    public MultiBufferSource multiBufferSource() {
        return multiBufferSource;
    }

    public int packedLight() {
        return packedLight;
    }

    public void setPackedLight(int packedLight) {
        this.packedLight = packedLight;
    }

    public int packedOverlay() {
        return packedOverlay;
    }

    public void setPackedOverlay(int packedOverlay) {
        this.packedOverlay = packedOverlay;
    }

    public float partialTick() {
        return partialTick;
    }

    public PoseStack poseStack() {
        return poseStack;
    }

    public int renderColor() {
        return renderColor;
    }

    public void setRenderColor(int renderColor) {
        this.renderColor = renderColor;
    }

    public @Nullable RenderType renderType() {
        return renderType;
    }

    public void setRenderType(@Nullable RenderType renderType) {
        this.renderType = renderType;
    }

    public VertexConsumer vertexConsumer() {
        return vertexConsumer;
    }

    public void setVertexConsumer(VertexConsumer vertexConsumer) {
        this.vertexConsumer = vertexConsumer;
    }

    public void setTextureOverride(ResourceLocation textureOverride) {
        this.textureOverride = textureOverride;
    }

    public ResourceLocation getTextureOverride() {
        return textureOverride;
    }

    public float cubeInflate() {
        return cubeInflate;
    }

    public void setCubeInflate(float cubeInflate) {
        this.cubeInflate = cubeInflate;
    }

    /**
     * When set, {@code AzModelRenderer} skips every cube that has a zero-length side - the single quads (planes)
     * artists use for spines, fins, whiskers and the like. Meant for overlay passes that re-draw the model with a
     * second texture: a mesh or glow stretched over a lone plane reads as a stray sheet in the air rather than a
     * covering. Off by default; an overlay sets it around its {@code reRender} and restores it after, exactly as it
     * does with the render type.
     */
    public boolean skipFlatCubes() {
        return skipFlatCubes;
    }

    public void setSkipFlatCubes(boolean skipFlatCubes) {
        this.skipFlatCubes = skipFlatCubes;
    }

    public IntIntPair computeTextureSize(ResourceLocation texture) {
        return TEXTURE_DIMENSIONS_CACHE.computeIfAbsent(texture, RenderUtil::getTextureDimensions);
    }
}
